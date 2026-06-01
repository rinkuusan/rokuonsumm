package com.rokuonsumm.transcription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * APIキー の有効性チェック。プロバイダの軽量エンドポイントを叩いて 200/401 で判別。
 *
 * 注: rate制限を避けるため、ユーザが「テスト」ボタンを押した時だけ実行。
 */
object ApiKeyValidator {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    sealed class Result {
        object Valid : Result()
        data class Invalid(val message: String) : Result()
        data class NetworkError(val message: String) : Result()
    }

    suspend fun checkGroq(apiKey: String): Result = call(
        url = "https://api.groq.com/openai/v1/models",
        headers = mapOf("Authorization" to "Bearer $apiKey"),
        apiKey = apiKey
    )

    suspend fun checkOpenAI(apiKey: String): Result = call(
        url = "https://api.openai.com/v1/models",
        headers = mapOf("Authorization" to "Bearer $apiKey"),
        apiKey = apiKey
    )

    suspend fun checkAnthropic(apiKey: String): Result = call(
        url = "https://api.anthropic.com/v1/models",
        headers = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to "2023-06-01"
        ),
        apiKey = apiKey
    )

    suspend fun checkGemini(apiKey: String): Result = call(
        url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey",
        headers = emptyMap(),
        apiKey = apiKey
    )

    private suspend fun call(
        url: String, headers: Map<String, String>, apiKey: String
    ): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Invalid("キーが空です")
        try {
            val req = Request.Builder().url(url).also { b ->
                headers.forEach { (k, v) -> b.addHeader(k, v) }
            }.build()
            client.newCall(req).execute().use { res ->
                when (res.code) {
                    in 200..299 -> Result.Valid
                    401, 403 -> Result.Invalid("認証エラー (${res.code}): キーが無効です")
                    429 -> Result.NetworkError("レート制限超過 (429)")
                    else -> Result.Invalid("HTTP ${res.code}: ${res.body?.string()?.take(120) ?: ""}")
                }
            }
        } catch (e: Exception) {
            Result.NetworkError(e.message ?: "通信エラー")
        }
    }
}
