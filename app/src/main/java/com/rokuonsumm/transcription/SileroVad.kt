package com.rokuonsumm.transcription

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD (v5) を ONNX Runtime で動かす。16kHz mono PCM を 512サンプル窓で処理し、
 * 各窓の発話確率を出して状態 [2,1,128] を伝播する。
 *
 * モデル契約 (実機検証済み):
 *   入力: input[batch, samples] / state[2, batch, 128] / sr int64
 *   出力: output[batch, 1] (発話確率) / stateN[2, batch, 128]
 *
 * 使い終わったら close()。Worker内で1ファイルごとに生成→破棄する想定。
 */
class SileroVad(context: Context) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
    }

    data class SpeechRegion(val startSample: Int, val endSample: Int)

    /**
     * PCM (16kHz mono, [-32768,32767]) を走査し、発話区間をサンプル単位で返す。
     * パラメータは Silero 公式 get_speech_timestamps のデフォルトに準拠。
     */
    fun detectSpeechRegions(
        pcm: ShortArray,
        sampleRate: Int = 16_000,
        threshold: Float = 0.5f,
        negThreshold: Float = 0.35f,
        minSpeechMs: Int = 250,
        minSilenceMs: Int = 300,
        speechPadMs: Int = 100
    ): List<SpeechRegion> {
        if (pcm.isEmpty()) return emptyList()
        val window = if (sampleRate >= 16_000) 512 else 256
        val probs = runProbs(pcm, sampleRate, window)
        if (probs.isEmpty()) return emptyList()

        val minSpeechSamples = sampleRate * minSpeechMs / 1000
        val minSilenceSamples = sampleRate * minSilenceMs / 1000
        val padSamples = sampleRate * speechPadMs / 1000

        // ステートマシン: しきい値ヒステリシスで区間を切り出す
        val raw = mutableListOf<SpeechRegion>()
        var triggered = false
        var speechStart = 0
        var tempEnd = 0  // 無音が続き始めた位置

        probs.forEachIndexed { i, p ->
            val pos = i * window
            if (p >= threshold) {
                if (tempEnd != 0) tempEnd = 0
                if (!triggered) { triggered = true; speechStart = pos }
            } else if (p < negThreshold && triggered) {
                if (tempEnd == 0) tempEnd = pos
                if (pos - tempEnd >= minSilenceSamples) {
                    val end = tempEnd
                    if (end - speechStart >= minSpeechSamples) raw.add(SpeechRegion(speechStart, end))
                    triggered = false
                    tempEnd = 0
                }
            }
        }
        // 末尾が発話のまま終わった場合
        if (triggered) {
            val end = pcm.size
            if (end - speechStart >= minSpeechSamples) raw.add(SpeechRegion(speechStart, end))
        }

        // パディング付与 + 重なりマージ
        return mergeWithPadding(raw, padSamples, pcm.size)
    }

    private fun runProbs(pcm: ShortArray, sampleRate: Int, window: Int): FloatArray {
        val state = Array(2) { Array(1) { FloatArray(128) } }
        // Silero v5 は「直前 contextSize サンプル + window 新サンプル」を入力する。
        // 16kHz: context=64 / 8kHz: context=32。これを付けないと声を一切検出できない。
        val contextSize = if (window == 512) 64 else 32
        val inputLen = contextSize + window
        val context = FloatArray(contextSize)            // 初回は無音(ゼロ)
        val inBuf = FloatArray(inputLen)
        val srTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(sampleRate.toLong())), longArrayOf()
        )
        val probs = ArrayList<Float>(pcm.size / window + 1)
        try {
            var i = 0
            while (i + window <= pcm.size) {
                // [context | 今の512サンプル] を連結
                System.arraycopy(context, 0, inBuf, 0, contextSize)
                for (j in 0 until window) inBuf[contextSize + j] = pcm[i + j] / 32768f
                val input = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(inBuf.copyOf()), longArrayOf(1, inputLen.toLong())
                )
                val stateTensor = OnnxTensor.createTensor(env, state)
                val out = session.run(
                    mapOf("input" to input, "state" to stateTensor, "sr" to srTensor)
                )
                @Suppress("UNCHECKED_CAST")
                val prob = (out.get(0).value as Array<FloatArray>)[0][0]
                @Suppress("UNCHECKED_CAST")
                val newState = out.get(1).value as Array<Array<FloatArray>>
                for (a in 0..1) System.arraycopy(newState[a][0], 0, state[a][0], 0, 128)
                probs.add(prob)
                // 次の context = 今のチャンクの末尾 contextSize サンプル
                for (j in 0 until contextSize) context[j] = pcm[i + window - contextSize + j] / 32768f
                input.close(); stateTensor.close(); out.close()
                i += window
            }
        } catch (e: Exception) {
            Log.e(TAG, "VAD inference failed", e)
            srTensor.close()
            return FloatArray(0)
        }
        srTensor.close()
        return probs.toFloatArray()
    }

    private fun mergeWithPadding(
        regions: List<SpeechRegion>, pad: Int, total: Int
    ): List<SpeechRegion> {
        if (regions.isEmpty()) return emptyList()
        val padded = regions.map {
            SpeechRegion(
                (it.startSample - pad).coerceAtLeast(0),
                (it.endSample + pad).coerceAtMost(total)
            )
        }
        val merged = mutableListOf(padded.first())
        for (k in 1 until padded.size) {
            val last = merged.last()
            val cur = padded[k]
            if (cur.startSample <= last.endSample) {
                merged[merged.size - 1] = SpeechRegion(last.startSample, maxOf(last.endSample, cur.endSample))
            } else {
                merged.add(cur)
            }
        }
        return merged
    }

    override fun close() {
        try { session.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "SileroVad"
        private const val MODEL_ASSET = "silero_vad.onnx"
    }
}
