package com.rokuonsumm.transcription

import android.content.Context
import com.rokuonsumm.OpLog
import java.io.File

/**
 * 文字起こし前のVADゲート。m4aをデコード→Silero VADで音声区間検出→
 * 「Groqに送るもの」を決める:
 *
 *  - 音声区間の合計が MIN_VOICED_MS 未満 → null (= 丸ごとスキップ、幻覚ゼロ)
 *  - 音声比率が HIGH_RATIO 以上 → 原本m4aをそのまま使う (再エンコード不要)
 *  - それ以外 → 音声区間だけ抽出して WAV 化して返す
 */
object VadGate {

    private const val MIN_VOICED_MS = 700        // これ未満は会話とみなさない
    private const val HIGH_RATIO = 0.9f          // ほぼ全部声なら原本送信

    private const val MIN_SPK_SAMPLES = 8_000     // 話者埋め込みは0.5秒以上の区間のみ
    // 抽出WAVの上限サンプル数。16kHzで約11.5分=約22MB → Groqの25MB上限に収める
    private const val MAX_EXTRACT_SAMPLES = 11_000_000

    sealed class Decision {
        /** Groqに送らない (無音/環境音のみ) */
        object Skip : Decision()
        /** 原本m4aをそのまま送る */
        data class SendOriginal(val voicedMs: Int) : Decision()
        /** 抽出済みWAVを送る (送信後に削除すること) */
        data class SendExtracted(val wav: File, val voicedMs: Int) : Decision()
    }

    /** 登録話者の参照(名前 + L2正規化済み埋め込み) */
    data class SpeakerRef(val name: String, val embedding: FloatArray)

    /** VAD判定 + 話者ラベル + 代表声紋(自動クラスタリング用, null=未計算) */
    data class GateResult(
        val decision: Decision,
        val speakerLabel: String?,
        val speakerEmbedding: FloatArray? = null
    )

    /**
     * @param sourceFile 録音セグメント (.m4a)
     * @param cacheDir   抽出WAVの一時保存先
     * @param speakers   登録話者(空/null なら話者識別しない)
     * @param threshold  コサイン類似度の閾値(これ未満は「不明」)
     */
    fun evaluate(
        ctx: Context,
        sourceFile: File,
        cacheDir: File,
        speakers: List<SpeakerRef>? = null,
        threshold: Float = 0.45f
    ): GateResult {
        val pcm = PcmDecoder.decode(sourceFile.absolutePath)
        if (pcm == null || pcm.samples.isEmpty()) {
            OpLog.w(ctx, "vad.decode_fail", "file=${sourceFile.name} → 原本送信にフォールバック")
            return GateResult(Decision.SendOriginal(-1), null)
        }
        val sr = pcm.sampleRate
        val regions = try {
            SileroVad(ctx).use { it.detectSpeechRegions(pcm.samples, sr) }
        } catch (e: Exception) {
            OpLog.e(ctx, "vad.error", "file=${sourceFile.name} → 原本送信にフォールバック", e)
            return GateResult(Decision.SendOriginal(-1), null)
        }

        val voicedSamples = regions.sumOf { (it.endSample - it.startSample).toLong() }
        val voicedMs = (voicedSamples * 1000 / sr).toInt()
        val totalMs = (pcm.samples.size.toLong() * 1000 / sr).toInt()

        if (voicedMs < MIN_VOICED_MS) {
            OpLog.i(ctx, "vad.skip", "file=${sourceFile.name} voiced=${voicedMs}ms/${totalMs}ms regions=${regions.size}")
            return GateResult(Decision.Skip, null)
        }

        // 話者識別 (有効時のみ)。登録話者がゼロでも声紋は取る = 自動クラスタリング用。
        // speakers==null は識別OFF、空リストは「ONだが登録なし(全部新規候補)」を意味する。
        val (label, repEmb) = if (speakers != null)
            identifySpeaker(ctx, pcm.samples, sr, regions, speakers, threshold, sourceFile.name)
        else null to null

        val ratio = if (pcm.samples.isNotEmpty()) voicedSamples.toFloat() / pcm.samples.size else 0f
        if (ratio >= HIGH_RATIO) {
            OpLog.i(ctx, "vad.send_original", "file=${sourceFile.name} voiced=${voicedMs}ms ratio=${"%.2f".format(ratio)} spk=$label")
            return GateResult(Decision.SendOriginal(voicedMs), label, repEmb)
        }

        // 音声区間だけ連結 (Groqの25MB上限対策: MAX_EXTRACT_SAMPLESで頭打ち=約22MB WAV)
        val cap = minOf(voicedSamples, MAX_EXTRACT_SAMPLES.toLong()).toInt()
        val extracted = ShortArray(cap)
        var w = 0
        for (r in regions) {
            if (w >= cap) break
            val len = minOf(r.endSample - r.startSample, cap - w)
            System.arraycopy(pcm.samples, r.startSample, extracted, w, len)
            w += len
        }
        val capped = w < voicedSamples
        val wav = File(cacheDir, "vad_${sourceFile.nameWithoutExtension}.wav")
        WavWriter.write(wav, extracted, sr)
        OpLog.i(ctx, "vad.extract", "file=${sourceFile.name} voiced=${voicedMs}ms/${totalMs}ms regions=${regions.size} ratio=${"%.2f".format(ratio)}${if (capped) " CAPPED" else ""} spk=$label")
        return GateResult(Decision.SendExtracted(wav, voicedMs), label, repEmb)
    }

    /**
     * 声区間ごとに埋め込み→最も近い登録話者で照合。閾値未満は「不明」。
     * セグメントの代表ラベル = 累計発話時間が最大のラベル(複数話者混在時の優勢者)。
     */
    private fun identifySpeaker(
        ctx: Context,
        pcm: ShortArray,
        sr: Int,
        regions: List<SileroVad.SpeechRegion>,
        speakers: List<SpeakerRef>,
        threshold: Float,
        fileName: String
    ): Pair<String?, FloatArray?> {
        val durByLabel = HashMap<String, Long>()
        val maxSimByName = HashMap<String, Float>()  // 診断用: 各話者への最大コサイン
        var repEmb: FloatArray? = null               // 最長区間の声紋 = セグメント代表(クラスタリング用)
        var repLen = 0
        try {
            SpeakerEmbedder(ctx).use { embedder ->
                for (r in regions) {
                    val len = r.endSample - r.startSample
                    if (len < MIN_SPK_SAMPLES) continue
                    val seg = pcm.copyOfRange(r.startSample, r.endSample)
                    val e = embedder.embed(seg, sr) ?: continue
                    if (len > repLen) { repLen = len; repEmb = e }
                    var best = UNKNOWN
                    var bestSim = threshold
                    for (s in speakers) {
                        val sim = SpeakerEmbedder.cosine(e, s.embedding)
                        if (sim > (maxSimByName[s.name] ?: -1f)) maxSimByName[s.name] = sim
                        if (sim >= bestSim) { bestSim = sim; best = s.name }
                    }
                    durByLabel.merge(best, len.toLong(), Long::plus)
                }
            }
        } catch (e: Exception) {
            OpLog.e(ctx, "speaker.error", "file=$fileName", e)
            return null to null
        }
        val decided = durByLabel.maxByOrNull { it.value }?.key
        // 診断: 各登録話者への最大コサイン・しきい値・最終判定を残す(閾値調整の材料)
        val simStr = maxSimByName.entries.joinToString(" ") { "${it.key}=${"%.2f".format(it.value)}" }
        OpLog.i(ctx, "speaker.match", "file=$fileName thr=${"%.2f".format(threshold)} max[$simStr] -> ${decided ?: "null"}")
        return decided to repEmb
    }

    const val UNKNOWN = "不明"
}
