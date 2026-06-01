package com.rokuonsumm.transcription

import com.rokuonsumm.data.db.TranscriptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 要約エンジン抽出。Groq / OpenAI / Anthropic Claude / Google Gemini の 4プロバイダを
 * 1つのインターフェースで呼び分ける。各プロバイダは API 形式が異なるので内部で分岐。
 *
 * APIキー:
 * - GROQ: 専用キー未設定なら文字起こし用 Groq キーを fallback
 * - 他: 専用キー必須
 */
enum class SummaryProvider(val key: String, val displayName: String, val model: String) {
    NONE("none", "使わない（自分で全文コピペ）", ""),
    GROQ("groq", "Groq (Llama 3.3 70B)", "llama-3.3-70b-versatile"),
    OPENAI("openai", "OpenAI (GPT-4o mini)", "gpt-4o-mini"),
    CLAUDE("claude", "Anthropic Claude (Haiku 3.5)", "claude-3-5-haiku-20241022"),
    GEMINI("gemini", "Google Gemini (2.0 Flash)", "gemini-2.0-flash");

    companion object {
        // 既定は NONE = API要約を使わず全文コピペで自分のLLMに貼る運用
        fun fromKey(key: String): SummaryProvider =
            values().firstOrNull { it.key == key } ?: NONE
    }
}

object Summarizer {

    private const val MAX_INPUT_CHARS = 5000
    private const val TRUNCATE_MARKER = "\n\n…(発言が多いため中略)…\n\n"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val SYSTEM_PROMPT = """
        あなたは音声日記の要約者だ。以下は1日分の発言記録（時刻つき）。
        日記として後で読み返すための簡潔な要約を出力せよ。

        ルール:
        - 200〜400文字、自然な文章（箇条書きではない）
        - 出来事・感情・気付き・人・場所などの重要情報を盛り込む
        - 「今日は」「一日を通して」「以上です」のような前置きやまとめフレーズ禁止
        - 一人称で書く（「俺」「私」など、発言記録に合わせる）
        - 装飾的な絵文字や記号は使わない
        - 要約本文のみを出力する（前置き・後置きの文章は一切不要）
    """.trimIndent()

    /**
     * 要約を生成する。
     * @throws IOException 通信/API エラー
     * @throws IllegalArgumentException トランスクリプトが空
     */
    suspend fun summarize(
        transcripts: List<TranscriptEntity>,
        provider: SummaryProvider,
        apiKey: String
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw IOException("${provider.displayName} の API キーが未設定です")

        val records = buildRecords(transcripts)
        if (records.isBlank()) throw IllegalArgumentException("要約対象がありません")

        val (payload, truncated) = truncateIfLong(records)
        val systemPrompt = if (truncated)
            "$SYSTEM_PROMPT\n\n注: 発言量が多いため中盤の一部は省略されています。"
        else SYSTEM_PROMPT

        when (provider) {
            SummaryProvider.GROQ -> callOpenAiCompatible(
                "https://api.groq.com/openai/v1/chat/completions",
                provider.model, apiKey, systemPrompt, payload
            )
            SummaryProvider.OPENAI -> callOpenAiCompatible(
                "https://api.openai.com/v1/chat/completions",
                provider.model, apiKey, systemPrompt, payload
            )
            SummaryProvider.CLAUDE -> callAnthropic(
                provider.model, apiKey, systemPrompt, payload
            )
            SummaryProvider.GEMINI -> callGemini(
                provider.model, apiKey, systemPrompt, payload
            )
            // NONE は呼び出し側で弾く想定 (ここには来ない)
            SummaryProvider.NONE -> throw IllegalStateException("要約エンジン未使用 (NONE)")
        }
    }

    fun buildRecords(transcripts: List<TranscriptEntity>): String {
        val timeFmt = SimpleDateFormat("H:mm", Locale.getDefault())
        return transcripts
            .sortedBy { it.startTimeMs }
            .filter { it.text.trim().isNotEmpty() }
            .joinToString("\n") {
                // 話者ラベルがあれば「[14:03] 純: 本文」、無ければ従来の「[14:03] 本文」
                val who = it.speakerLabel?.takeIf { l -> l.isNotBlank() }?.let { l -> "$l: " } ?: ""
                "[${timeFmt.format(Date(it.startTimeMs))}] $who${it.text.trim()}"
            }
    }

    private fun truncateIfLong(records: String): Pair<String, Boolean> {
        if (records.length <= MAX_INPUT_CHARS) return records to false
        val keep = MAX_INPUT_CHARS - TRUNCATE_MARKER.length
        val head = (keep * 0.6).toInt()
        val tail = keep - head
        return (records.take(head) + TRUNCATE_MARKER + records.takeLast(tail)) to true
    }

    // ── OpenAI 互換 (Groq / OpenAI) ──────────────────────────────────

    private fun callOpenAiCompatible(
        url: String, model: String, apiKey: String,
        systemPrompt: String, userPayload: String
    ): String {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.6)
            put("max_tokens", 500)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userPayload) })
            })
        }.toString()
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { res ->
            val respBody = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw mapError(res.code, respBody, url)
            return JSONObject(respBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }

    // ── Anthropic Claude ──────────────────────────────────────────────

    private fun callAnthropic(
        model: String, apiKey: String, systemPrompt: String, userPayload: String
    ): String {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 500)
            put("temperature", 0.6)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", userPayload) })
            })
        }.toString()
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { res ->
            val respBody = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw mapError(res.code, respBody, "Claude")
            return JSONObject(respBody)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
        }
    }

    // ── Google Gemini ─────────────────────────────────────────────────

    private fun callGemini(
        model: String, apiKey: String, systemPrompt: String, userPayload: String
    ): String {
        // Gemini は system_instruction を別フィールドで受ける
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", userPayload) })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.6)
                put("maxOutputTokens", 500)
            })
        }.toString()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { res ->
            val respBody = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw mapError(res.code, respBody, "Gemini")
            return JSONObject(respBody)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
        }
    }

    private fun mapError(code: Int, body: String, provider: String): IOException {
        val groqMsg = runCatching {
            JSONObject(body).getJSONObject("error").getString("message")
        }.getOrNull() ?: body.take(200)
        val userMsg = when (code) {
            413, 400 -> "発言量が多すぎます ($groqMsg)"
            429 -> "$provider レート制限超過。1分待ってリトライしてください ($groqMsg)"
            401, 403 -> "$provider の APIキーが無効です。設定を確認してください"
            else -> "$provider API $code: $groqMsg"
        }
        return IOException(userMsg)
    }
}
