package com.rokuonsumm.transcription

import com.rokuonsumm.data.db.TranscriptEntity

/**
 * 文字起こし品質ゲート (Point 2 + 3)。
 *
 * 破壊的削除はせず、各行に「フラグ」をその場で算出する。フラグは DB に保存せず、
 * 常に〈閾値 + メタデータ + 本文〉から導出するので、閾値変更や再フィルタで自動的に再計算される。
 *  - nonSpeech : no_speech_prob が高い → 非音声混入の疑い
 *  - repetition: compression_ratio が高い、または本文が反復ループ → 反復幻覚
 *  - lowConf   : avg_logprob が低い → 低信頼(本文は残すが要確認)
 *
 * メタデータ(verbose_json)は新パイプライン以降のみ存在。旧データは null だが、
 * 反復(repetition)だけは本文から検出できるので遡及適用が効く。
 */
object TranscriptQuality {

    data class Thresholds(
        val noSpeechMax: Float,
        val compressionMax: Float,
        val avgLogprobMin: Float,
        val repMinCount: Int
    )

    data class Flags(
        val nonSpeech: Boolean,
        val repetition: Boolean,
        val lowConf: Boolean
    ) {
        val any: Boolean get() = nonSpeech || repetition || lowConf
    }

    fun evaluate(t: TranscriptEntity, thr: Thresholds): Flags {
        val ns = t.noSpeechProb
        val al = t.avgLogprob
        val cr = t.compressionRatio
        val nonSpeech = ns != null && ns > thr.noSpeechMax
        val lowConf = al != null && al < thr.avgLogprobMin
        val repByMeta = cr != null && cr > thr.compressionMax
        val repByText = RepetitionDetector.isLoop(t.text, thr.repMinCount)
        return Flags(nonSpeech, repByMeta || repByText, lowConf)
    }

    /** 計測 (Point 5): 各ルールで何行フラグが立つかを集計する */
    data class Tally(
        var scanned: Int = 0,
        var hasMeta: Int = 0,
        var nonSpeech: Int = 0,
        var repetition: Int = 0,
        var repByTextOnly: Int = 0,  // メタ無し(旧データ)でもテキスト反復で拾えた数=遡及の効き
        var lowConf: Int = 0
    )

    fun tally(rows: List<TranscriptEntity>, thr: Thresholds): Tally {
        val t = Tally()
        for (r in rows) {
            t.scanned++
            if (r.compressionRatio != null || r.noSpeechProb != null) t.hasMeta++
            val f = evaluate(r, thr)
            if (f.nonSpeech) t.nonSpeech++
            if (f.lowConf) t.lowConf++
            if (f.repetition) {
                t.repetition++
                val repByMeta = r.compressionRatio != null && r.compressionRatio > thr.compressionMax
                if (!repByMeta) t.repByTextOnly++
            }
        }
        return t
    }
}
