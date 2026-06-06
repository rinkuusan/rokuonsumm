package com.rokuonsumm.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {

    companion object {
        val KEY_SEGMENT_DURATION_MIN = intPreferencesKey("segment_duration_min")
        val KEY_BITRATE_BPS = intPreferencesKey("bitrate_bps")
        val KEY_GROQ_API_KEY = stringPreferencesKey("groq_api_key")
        val KEY_TRANSCRIPTION_MODEL = stringPreferencesKey("transcription_model")
        val KEY_AUTO_DELETE_AFTER_TRANSCRIPTION = booleanPreferencesKey("auto_delete_after_transcription")
        val KEY_SUMMARY_MODE = stringPreferencesKey("summary_mode")       // "api" | "share"
        val KEY_SUMMARY_PROVIDER = stringPreferencesKey("summary_provider") // "claude"|"gemini"|"openai"
        val KEY_SUMMARY_API_KEY = stringPreferencesKey("summary_api_key")
        val KEY_DAY_END_HOUR = intPreferencesKey("day_end_hour")
        val KEY_DAY_END_MINUTE = intPreferencesKey("day_end_minute")
        val KEY_STORAGE_WARNING_MB = intPreferencesKey("storage_warning_mb")
        val KEY_FILLER_FILTER_ENABLED = booleanPreferencesKey("filler_filter_enabled")
        val KEY_FILLER_PATTERNS_USER = stringPreferencesKey("filler_patterns_user")
        val KEY_VAD_ENABLED = booleanPreferencesKey("vad_enabled")
        val KEY_PROPER_NOUNS = stringPreferencesKey("proper_nouns")
        val KEY_SPEAKER_ID_ENABLED = booleanPreferencesKey("speaker_id_enabled")
        val KEY_SPEAKER_THRESHOLD = stringPreferencesKey("speaker_threshold")  // Float を文字列保存
        val KEY_SUMMARY_EXCLUDE_UNKNOWN = booleanPreferencesKey("summary_exclude_unknown")

        // ── 品質ゲート閾値 (verbose_json メタデータ + 反復検出) ──
        val KEY_Q_NOSPEECH_MAX = floatPreferencesKey("q_nospeech_max")
        val KEY_Q_COMPRESSION_MAX = floatPreferencesKey("q_compression_max")
        val KEY_Q_AVGLOGPROB_MIN = floatPreferencesKey("q_avglogprob_min")
        val KEY_Q_REP_MIN_COUNT = intPreferencesKey("q_rep_min_count")

        const val DEFAULT_PROPER_NOUNS = "あやか\n純\nボタ美\nおかん\n田中ボタン店"
        const val DEFAULT_SPEAKER_THRESHOLD = 0.45f
        const val DEFAULT_Q_NOSPEECH_MAX = 0.6f      // これ超で「非音声」フラグ
        const val DEFAULT_Q_COMPRESSION_MAX = 2.4f   // これ超で「反復幻覚」フラグ(Whisper慣例値)
        const val DEFAULT_Q_AVGLOGPROB_MIN = -1.0f   // これ未満で「低信頼」フラグ
        const val DEFAULT_Q_REP_MIN_COUNT = 3        // 同一フレーズ連続反復の閾値

        const val DEFAULT_SEGMENT_DURATION_MIN = 5
        const val DEFAULT_BITRATE_BPS = 32_000
        const val DEFAULT_TRANSCRIPTION_MODEL = "whisper-large-v3-turbo"
        const val DEFAULT_DAY_END_HOUR = 4
        const val DEFAULT_DAY_END_MINUTE = 0
        const val DEFAULT_STORAGE_WARNING_MB = 500
    }

    val segmentDurationMinFlow: Flow<Int> = context.dataStore.data.map {
        it[KEY_SEGMENT_DURATION_MIN] ?: DEFAULT_SEGMENT_DURATION_MIN
    }
    val bitrateBpsFlow: Flow<Int> = context.dataStore.data.map {
        it[KEY_BITRATE_BPS] ?: DEFAULT_BITRATE_BPS
    }
    val qNoSpeechMaxFlow: Flow<Float> = context.dataStore.data.map {
        it[KEY_Q_NOSPEECH_MAX] ?: DEFAULT_Q_NOSPEECH_MAX
    }
    val qCompressionMaxFlow: Flow<Float> = context.dataStore.data.map {
        it[KEY_Q_COMPRESSION_MAX] ?: DEFAULT_Q_COMPRESSION_MAX
    }
    val qAvgLogprobMinFlow: Flow<Float> = context.dataStore.data.map {
        it[KEY_Q_AVGLOGPROB_MIN] ?: DEFAULT_Q_AVGLOGPROB_MIN
    }
    val qRepMinCountFlow: Flow<Int> = context.dataStore.data.map {
        it[KEY_Q_REP_MIN_COUNT] ?: DEFAULT_Q_REP_MIN_COUNT
    }
    val groqApiKeyFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_GROQ_API_KEY] ?: ""
    }
    val transcriptionModelFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_TRANSCRIPTION_MODEL] ?: DEFAULT_TRANSCRIPTION_MODEL
    }
    val autoDeleteAfterTranscriptionFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_AUTO_DELETE_AFTER_TRANSCRIPTION] ?: true
    }
    val summaryModeFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_SUMMARY_MODE] ?: "api"
    }
    val summaryProviderFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_SUMMARY_PROVIDER] ?: "none"  // 既定: API要約は使わず全文コピペ運用
    }
    val summaryApiKeyFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_SUMMARY_API_KEY] ?: ""
    }
    val dayEndHourFlow: Flow<Int> = context.dataStore.data.map {
        it[KEY_DAY_END_HOUR] ?: DEFAULT_DAY_END_HOUR
    }
    val dayEndMinuteFlow: Flow<Int> = context.dataStore.data.map {
        it[KEY_DAY_END_MINUTE] ?: DEFAULT_DAY_END_MINUTE
    }
    val storageWarningMbFlow: Flow<Int> = context.dataStore.data.map {
        it[KEY_STORAGE_WARNING_MB] ?: DEFAULT_STORAGE_WARNING_MB
    }
    val fillerFilterEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_FILLER_FILTER_ENABLED] ?: true
    }
    val fillerPatternsUserFlow: Flow<List<String>> = context.dataStore.data.map {
        (it[KEY_FILLER_PATTERNS_USER] ?: "")
            .split("\n")
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
    }
    val vadEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_VAD_ENABLED] ?: true
    }
    /** 固有名詞: Whisperのprompt用。改行/読点区切りで保持 */
    val properNounsFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_PROPER_NOUNS] ?: DEFAULT_PROPER_NOUNS
    }
    val speakerIdEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_SPEAKER_ID_ENABLED] ?: true
    }
    val speakerThresholdFlow: Flow<Float> = context.dataStore.data.map {
        it[KEY_SPEAKER_THRESHOLD]?.toFloatOrNull() ?: DEFAULT_SPEAKER_THRESHOLD
    }
    /** 要約から「不明」話者を除外するか (デフォルト false = 全部含める) */
    val summaryExcludeUnknownFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_SUMMARY_EXCLUDE_UNKNOWN] ?: false
    }

    suspend fun set(key: Preferences.Key<Int>, value: Int) {
        context.dataStore.edit { it[key] = value }
    }
    suspend fun set(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }
    suspend fun set(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }
}
