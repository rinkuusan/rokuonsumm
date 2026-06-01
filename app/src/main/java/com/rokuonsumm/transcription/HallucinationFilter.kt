package com.rokuonsumm.transcription

/**
 * Whisper の幻覚 (hallucination) 検出。
 *
 * Whisper は無音や音楽パートで YouTube 動画末尾フレーズや決まり文句を生成しがち。
 * 既知パターン+正規化文字列の完全/部分マッチで除外判定する。
 *
 * 使い方:
 *   val res = HallucinationFilter.isHallucination(text, userPatterns)
 *   if (res.isJunk) skip()
 */
object HallucinationFilter {

    data class FilterResult(
        val isJunk: Boolean,
        val reason: String,
        val matchedPattern: String?
    )

    /** Whisper 日本語幻覚の代表パターン (16個) */
    val DEFAULT_PATTERNS: List<String> = listOf(
        "ご視聴ありがとうございました",
        "ご視聴いただきありがとうございました",
        "おやすみなさい",
        "お疲れさまでした",
        "お疲れ様でした",
        "チャンネル登録お願いします",
        "チャンネル登録と高評価よろしくお願いします",
        "高評価よろしくお願いします",
        "それではまた次回お会いしましょう",
        "次回の動画でお会いしましょう",
        "また次回の動画でお会いしましょう",
        "私の動画でお楽しみください",
        "この動画でお楽しみください",
        "バイバイ",
        "またね",
        "字幕作成",
        "字幕 by",
        "Thanks for watching",
        "ありがとうございました",
        "お先に失礼します"
    )

    /** 短文判定の閾値 (これ以下なら部分一致でも junk) */
    private const val SHORT_TEXT_LEN = 30

    /**
     * @param text 文字起こし結果
     * @param userPatterns ユーザ設定の追加除外語 (空文字は無視)
     */
    fun isHallucination(text: String, userPatterns: List<String> = emptyList()): FilterResult {
        val normalized = normalize(text)
        if (normalized.isEmpty()) {
            return FilterResult(isJunk = true, reason = "empty after normalize", matchedPattern = null)
        }

        val patterns = (DEFAULT_PATTERNS + userPatterns).filter { it.isNotBlank() }
        val normalizedPatterns = patterns.map { normalize(it) to it }

        // 完全一致 (正規化後)
        normalizedPatterns.firstOrNull { (np, _) -> normalized == np }?.let { (_, orig) ->
            return FilterResult(true, "exact match (normalized)", orig)
        }

        // 短文 (≤30文字) で部分一致
        if (normalized.length <= SHORT_TEXT_LEN) {
            normalizedPatterns.firstOrNull { (np, _) -> normalized.contains(np) }?.let { (_, orig) ->
                return FilterResult(true, "short text contains pattern", orig)
            }
        }

        // 「丸ごと幻覚」判定: 既知パターンを全部除去した残りが極小なら、
        // テキスト全体が幻覚フレーズの羅列 (例: 「ご視聴ありがとうございました」×3) とみなす。
        // 30文字超でも素通りしてた穴を塞ぐ。
        run {
            var stripped = normalized
            var matched: String? = null
            for ((np, orig) in normalizedPatterns) {
                if (np.isNotEmpty() && stripped.contains(np)) {
                    stripped = stripped.replace(np, "")
                    if (matched == null) matched = orig
                }
            }
            // 元が十分長く (誤検知防止に8文字以上)、テキストの75%以上が既知幻覚なら junk。
            // 「はい」等の相槌が少し残っても拾える。逆に意味のある本文が25%以上あれば残す。
            if (matched != null && normalized.length >= 8 && stripped.length * 4 < normalized.length) {
                return FilterResult(true, "mostly filler phrases", matched)
            }
        }

        // 長文だが「末尾がパターン単独」のケース
        // 例: "...今日のミーティングは終わり。ご視聴ありがとうございました"
        //      末尾近く (最後の40文字以内) にパターンがあって、その後ろが空・句読点だけ
        if (normalized.length > SHORT_TEXT_LEN) {
            val tail = normalized.takeLast(40)
            normalizedPatterns.firstOrNull { (np, _) ->
                tail.endsWith(np) || tail.endsWith(np + ".") || tail.endsWith(np + "。")
            }?.let { (_, orig) ->
                // 末尾フレーズはあるが本文も意味があるはずなので **本来は除去** だが
                // junk 判定にすると本文も消える。今回は warning だけ。
                // 今は false で返す (Phase 2 で segment 単位除去するときに対応)
                return FilterResult(false, "trailing filler (kept)", orig)
            }
        }

        return FilterResult(false, "ok", null)
    }

    /**
     * 幻覚フレーズをテキストから「その場で除去」する(行ごと削除ではなく本文を温存)。
     * Whisperは無音/雑音で「ご視聴ありがとうございました」等をループ生成するが、
     * 実会話に混ざることも多い。既知パターンだけ抜き、本文は残す。
     * - 既知パターン(長い順)を全出現削除
     * - 4連以上の同一文字を1つに畳む (んんんん→ん, ーーーー→ー)
     * - 余分な空白/前後の句読点を整理
     */
    fun scrub(text: String, userPatterns: List<String> = emptyList()): String {
        var t = text
        val patterns = (DEFAULT_PATTERNS + userPatterns)
            .filter { it.isNotBlank() }
            .sortedByDescending { it.length }  // 長いパターンを先に消して部分残りを防ぐ
        for (p in patterns) t = t.replace(p, "")
        t = collapseRepeats(t)
        t = t.replace(Regex("[ 　]{2,}"), " ").trim(' ', '　', '。', '、', ',', '.')
        return t
    }

    /** 句読点・空白を除いた実質文字数。scrub後にこれが極小なら丸ごと幻覚とみなせる。 */
    fun meaningfulLength(s: String): Int =
        s.count { !it.isWhitespace() && it !in "。、！？!?,.…〜~・「」『』（）()【】\"'" }

    /** 同一文字が4回以上連続する箇所を1文字に畳む */
    private fun collapseRepeats(s: String): String {
        if (s.length < 4) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            var j = i + 1
            while (j < s.length && s[j] == c) j++
            val run = j - i
            if (run >= 4) sb.append(c) else repeat(run) { sb.append(c) }
            i = j
        }
        return sb.toString()
    }

    /**
     * テキスト正規化:
     * - 全角英数記号 → 半角
     * - 句読点・記号・空白を除去
     * - 長音「ー」を正規化
     * - 大文字小文字無視
     */
    private fun normalize(s: String): String {
        if (s.isBlank()) return ""
        val sb = StringBuilder()
        for (ch in s) {
            val c = when {
                // 全角英数記号 (U+FF01..U+FF5E) → 半角
                ch in '！'..'～' -> (ch.code - 0xFEE0).toChar()
                // 全角スペース → 半角
                ch == '　' -> ' '
                else -> ch
            }
            // 除外: 制御文字・空白・句読点・記号類
            when {
                c.isWhitespace() -> { /* skip */ }
                c in "。、！？!?,.…〜~・「」『』()（）[]【】" -> { /* skip */ }
                c == '"' || c == '\'' -> { /* skip */ }
                else -> sb.append(c.lowercaseChar())
            }
        }
        return sb.toString()
    }
}
