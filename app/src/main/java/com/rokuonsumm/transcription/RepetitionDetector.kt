package com.rokuonsumm.transcription

/**
 * 反復ループ幻覚の検出 (Point 3)。ブラックリスト非依存。
 *
 * Whisper は無音/雑音/VADすり抜けの環境音で、固有名詞(店名・人名)等を文脈無関係に
 * 何度も反復出力することがある(例: 「田中ボタン店田中ボタン店…」「おかん、田中ボタン店」×N)。
 * 既存の scrub(4連以上の同一"文字"畳み)では語/フレーズ単位の反復は捕捉できないため、
 * 「任意の単位文字列が連続反復している度合い」を直接測る。
 *
 * 固有名詞 prompt を認識精度のために残したままでも、漏れたループを捕捉できる。
 */
object RepetitionDetector {

    private const val MAX_ANALYZE = 8_000  // 解析する先頭文字数(長尺の保険)
    private const val MAX_UNIT = 40        // 反復単位の最大長

    /**
     * @param maxRepeat 最も支配的な反復単位の連続反復回数
     * @param coverage  その反復がテキスト全体に占める割合 (0..1)
     * @param unit      反復していた単位文字列(ログ/デバッグ用)
     */
    data class Result(val maxRepeat: Int, val coverage: Float, val unit: String)

    fun analyze(text: String): Result {
        val s = text.trim()
        if (s.length < 4) return Result(1, 0f, "")
        val n = minOf(s.length, MAX_ANALYZE)
        var bestCount = 1
        var bestUnit = ""
        var bestSpan = 0  // reps*p = その反復がカバーする文字数
        val maxP = minOf(n / 2, MAX_UNIT)
        for (p in 1..maxP) {
            var i = 0
            while (i + p <= n) {
                var reps = 1
                var j = i + p
                while (j + p <= n && s.regionMatches(i, s, j, p)) { reps++; j += p }
                if (reps >= 2 && reps * p > bestSpan) {
                    bestCount = reps; bestUnit = s.substring(i, i + p); bestSpan = reps * p
                }
                i = if (reps > 1) j else i + 1
            }
        }
        val coverage = if (s.isNotEmpty()) bestSpan.toFloat() / s.length else 0f
        return Result(bestCount, coverage, bestUnit)
    }

    /**
     * 反復ループとみなすか。
     *  - フレーズ(4字以上)の単位が minRepeat 回以上連続反復 → ループ
     *    (「田中ボタン店」×4 等の固有名詞・文の丸写し反復)
     *  - もしくは反復がテキストの大半(60%以上)を占める → ループ(単位長は問わない)
     *    (「おかん、田中ボタン店」×2 で全文埋まる/「うんうんうん」等、セグメント丸ごとの反復)
     *  - 短い単位(1-3字)が長文中に少しバーストするだけ(被覆<60%)は相槌/吃音とみなし非ループ。
     */
    fun isLoop(text: String, minRepeat: Int): Boolean {
        val r = analyze(text)
        if (r.maxRepeat >= minRepeat && r.unit.length >= 4) return true
        if (r.maxRepeat >= 2 && r.coverage >= 0.6f) return true
        return false
    }
}
