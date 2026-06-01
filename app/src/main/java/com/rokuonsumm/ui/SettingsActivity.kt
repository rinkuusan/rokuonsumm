package com.rokuonsumm.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.rokuonsumm.App
import com.rokuonsumm.R
import com.rokuonsumm.data.prefs.AppPreferences
import com.rokuonsumm.databinding.ActivitySettingsBinding
import com.rokuonsumm.transcription.ApiKeyValidator
import com.rokuonsumm.transcription.RefilterWorker
import com.rokuonsumm.transcription.SummaryProvider
import com.rokuonsumm.transcription.TranscriptionNotifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var groqSaveJob: Job? = null
    private var summarySaveJob: Job? = null
    private var fillerSaveJob: Job? = null
    private var properNounsSaveJob: Job? = null
    private var thresholdSaveJob: Job? = null
    private var groqStatusFadeJob: Job? = null
    private var summaryStatusFadeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        val prefs = (application as App).prefs

        // 現在値をロード
        lifecycleScope.launch {
            binding.etGroqApiKey.setText(prefs.groqApiKeyFlow.first())
            if (prefs.transcriptionModelFlow.first() == AppPreferences.DEFAULT_TRANSCRIPTION_MODEL) {
                binding.rbModelTurbo.isChecked = true
            } else {
                binding.rbModelFull.isChecked = true
            }
            binding.switchFillerFilter.isChecked = prefs.fillerFilterEnabledFlow.first()
            binding.etFillerUserPatterns.setText(
                prefs.fillerPatternsUserFlow.first().joinToString("\n")
            )
            binding.switchVad.isChecked = prefs.vadEnabledFlow.first()
            binding.etProperNouns.setText(prefs.properNounsFlow.first())
            binding.switchSpeakerId.isChecked = prefs.speakerIdEnabledFlow.first()
            val th = prefs.speakerThresholdFlow.first()
            binding.seekThreshold.progress = (th * 100).toInt()
            binding.tvThresholdValue.text = "%.2f".format(th)
            binding.switchExcludeUnknown.isChecked = prefs.summaryExcludeUnknownFlow.first()
            // 要約エンジン
            val provider = SummaryProvider.fromKey(prefs.summaryProviderFlow.first())
            val rbId = when (provider) {
                SummaryProvider.NONE -> R.id.rbSummaryNone
                SummaryProvider.GROQ -> R.id.rbSummaryGroq
                SummaryProvider.OPENAI -> R.id.rbSummaryOpenai
                SummaryProvider.CLAUDE -> R.id.rbSummaryClaude
                SummaryProvider.GEMINI -> R.id.rbSummaryGemini
            }
            binding.rgSummaryProvider.check(rbId)
            binding.etSummaryApiKey.setText(prefs.summaryApiKeyFlow.first())
            updateSummaryKeyHint(provider)
        }

        binding.rgSummaryProvider.setOnCheckedChangeListener { _, checkedId ->
            val p = when (checkedId) {
                R.id.rbSummaryGroq -> SummaryProvider.GROQ
                R.id.rbSummaryOpenai -> SummaryProvider.OPENAI
                R.id.rbSummaryClaude -> SummaryProvider.CLAUDE
                R.id.rbSummaryGemini -> SummaryProvider.GEMINI
                else -> SummaryProvider.NONE
            }
            updateSummaryKeyHint(p)
            // プロバイダ変更も即保存
            lifecycleScope.launch {
                prefs.set(AppPreferences.KEY_SUMMARY_PROVIDER, p.key)
            }
        }

        // ── Groq APIキー: 600ms debounce で自動保存 ────────────────
        binding.etGroqApiKey.doAfterTextChanged { txt ->
            showGroqStatus("● 未保存", 0xFF9CA3AF.toInt(), fade = false)
            groqSaveJob?.cancel()
            groqSaveJob = lifecycleScope.launch {
                delay(600)
                val key = txt?.toString().orEmpty().trim()
                prefs.set(AppPreferences.KEY_GROQ_API_KEY, key)
                if (key.isNotBlank()) TranscriptionNotifier.resetSuppression(this@SettingsActivity)
                showGroqStatus("✓ 保存しました", 0xFF4CAF50.toInt(), fade = true)
            }
        }
        binding.etGroqApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) flushGroqSave()
        }

        // 文字起こしモデル切替も即保存
        binding.rbModelTurbo.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                prefs.set(AppPreferences.KEY_TRANSCRIPTION_MODEL, AppPreferences.DEFAULT_TRANSCRIPTION_MODEL)
            }
        }
        binding.rbModelFull.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                prefs.set(AppPreferences.KEY_TRANSCRIPTION_MODEL, "whisper-large-v3")
            }
        }

        // ── 要約APIキー: 同じく自動保存 ────────────────────────────
        binding.etSummaryApiKey.doAfterTextChanged { txt ->
            showSummaryStatus("● 未保存", 0xFF9CA3AF.toInt(), fade = false)
            summarySaveJob?.cancel()
            summarySaveJob = lifecycleScope.launch {
                delay(600)
                prefs.set(AppPreferences.KEY_SUMMARY_API_KEY, txt?.toString().orEmpty().trim())
                showSummaryStatus("✓ 保存しました", 0xFF4CAF50.toInt(), fade = true)
            }
        }
        binding.etSummaryApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) flushSummarySave()
        }

        // ── フィラー除外設定: 自動保存 ────────────────────────────
        binding.switchFillerFilter.setOnCheckedChangeListener { _, _ ->
            lifecycleScope.launch {
                prefs.set(AppPreferences.KEY_FILLER_FILTER_ENABLED, binding.switchFillerFilter.isChecked)
            }
        }
        binding.etFillerUserPatterns.doAfterTextChanged { txt ->
            fillerSaveJob?.cancel()
            fillerSaveJob = lifecycleScope.launch {
                delay(800)
                prefs.set(AppPreferences.KEY_FILLER_PATTERNS_USER, txt?.toString().orEmpty().trim())
            }
        }

        // ── VAD / 固有名詞: 自動保存 ───────────────────────────────
        binding.switchVad.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.set(AppPreferences.KEY_VAD_ENABLED, isChecked) }
        }
        binding.etProperNouns.doAfterTextChanged { txt ->
            properNounsSaveJob?.cancel()
            properNounsSaveJob = lifecycleScope.launch {
                delay(800)
                prefs.set(AppPreferences.KEY_PROPER_NOUNS, txt?.toString().orEmpty().trim())
            }
        }

        // ── 話者識別: 自動保存 + 登録画面リンク ────────────────────
        binding.btnSpeakerRegister.setOnClickListener {
            startActivity(Intent(this, SpeakerRegistrationActivity::class.java))
        }

        // Groq APIキー取得ページをブラウザで開く
        binding.tvGroqKeyLink.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://console.groq.com/keys")))
            } catch (e: Exception) {
                Toast.makeText(this, "ブラウザを開けませんでした", Toast.LENGTH_SHORT).show()
            }
        }
        binding.switchSpeakerId.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.set(AppPreferences.KEY_SPEAKER_ID_ENABLED, isChecked) }
        }
        binding.switchExcludeUnknown.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.set(AppPreferences.KEY_SUMMARY_EXCLUDE_UNKNOWN, isChecked) }
        }
        binding.seekThreshold.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                binding.tvThresholdValue.text = "%.2f".format(v)
                if (fromUser) {
                    thresholdSaveJob?.cancel()
                    thresholdSaveJob = lifecycleScope.launch {
                        delay(300)
                        prefs.set(AppPreferences.KEY_SPEAKER_THRESHOLD, v.toString())
                    }
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })

        // ── 接続テストボタン ──────────────────────────────────────
        binding.btnTestGroqKey.setOnClickListener {
            flushGroqSave()
            lifecycleScope.launch {
                showGroqStatus("⟳ テスト中…", 0xFFB0B0B0.toInt(), fade = false)
                val result = ApiKeyValidator.checkGroq(binding.etGroqApiKey.text.toString().trim())
                when (result) {
                    is ApiKeyValidator.Result.Valid ->
                        showGroqStatus("✓ 有効です", 0xFF4CAF50.toInt(), fade = true)
                    is ApiKeyValidator.Result.Invalid ->
                        showGroqStatus("✗ ${result.message}", 0xFFEF5350.toInt(), fade = false)
                    is ApiKeyValidator.Result.NetworkError ->
                        showGroqStatus("⚠ ${result.message}", 0xFFFFA726.toInt(), fade = false)
                }
            }
        }
        binding.btnTestSummaryKey.setOnClickListener {
            flushSummarySave()
            val provider = when (binding.rgSummaryProvider.checkedRadioButtonId) {
                R.id.rbSummaryGroq -> SummaryProvider.GROQ
                R.id.rbSummaryOpenai -> SummaryProvider.OPENAI
                R.id.rbSummaryClaude -> SummaryProvider.CLAUDE
                R.id.rbSummaryGemini -> SummaryProvider.GEMINI
                else -> SummaryProvider.NONE
            }
            if (provider == SummaryProvider.NONE) return@setOnClickListener  // キー不要
            lifecycleScope.launch {
                val key = binding.etSummaryApiKey.text.toString().trim().ifBlank {
                    // Groq+空欄なら文字起こしキー使う
                    if (provider == SummaryProvider.GROQ) binding.etGroqApiKey.text.toString().trim()
                    else ""
                }
                showSummaryStatus("⟳ テスト中…", 0xFFB0B0B0.toInt(), fade = false)
                val result = when (provider) {
                    SummaryProvider.GROQ -> ApiKeyValidator.checkGroq(key)
                    SummaryProvider.OPENAI -> ApiKeyValidator.checkOpenAI(key)
                    SummaryProvider.CLAUDE -> ApiKeyValidator.checkAnthropic(key)
                    SummaryProvider.GEMINI -> ApiKeyValidator.checkGemini(key)
                    SummaryProvider.NONE -> return@launch
                }
                when (result) {
                    is ApiKeyValidator.Result.Valid ->
                        showSummaryStatus("✓ 有効です", 0xFF4CAF50.toInt(), fade = true)
                    is ApiKeyValidator.Result.Invalid ->
                        showSummaryStatus("✗ ${result.message}", 0xFFEF5350.toInt(), fade = false)
                    is ApiKeyValidator.Result.NetworkError ->
                        showSummaryStatus("⚠ ${result.message}", 0xFFFFA726.toInt(), fade = false)
                }
            }
        }

        // バッテリー最適化ステータス
        refreshBatteryStatus()
        binding.btnBatterySettings.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        binding.btnStorage.setOnClickListener {
            startActivity(Intent(this, StorageActivity::class.java))
        }

        binding.btnRefilter.setOnClickListener {
            // ボタンタップ前に最新の設定を保存してから再フィルタ実行
            lifecycleScope.launch {
                prefs.set(AppPreferences.KEY_FILLER_FILTER_ENABLED, binding.switchFillerFilter.isChecked)
                prefs.set(AppPreferences.KEY_FILLER_PATTERNS_USER,
                    binding.etFillerUserPatterns.text.toString().trim())
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("過去の文字起こしを再フィルタ")
                    .setMessage("既存のトランスクリプトから「ご視聴ありがとうございました」等のフィラー幻覚を除去します(実会話の本文は残します)。元には戻せません。")
                    .setPositiveButton("実行") { _, _ ->
                        RefilterWorker.enqueue(this@SettingsActivity)
                        Toast.makeText(this@SettingsActivity,
                            "再フィルタを開始しました。完了まで数秒〜数十秒。", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        binding.btnSave.setOnClickListener {
            // すべての保留中保存を即時 flush してから閉じる (保険)
            flushAllSaves()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        // 画面離脱時は保留 flush
        flushAllSaves()
    }

    private fun flushAllSaves() {
        groqSaveJob?.cancel(); groqSaveJob = null
        summarySaveJob?.cancel(); summarySaveJob = null
        fillerSaveJob?.cancel(); fillerSaveJob = null
        properNounsSaveJob?.cancel(); properNounsSaveJob = null
        val prefs = (application as App).prefs
        lifecycleScope.launch {
            prefs.set(AppPreferences.KEY_GROQ_API_KEY, binding.etGroqApiKey.text.toString().trim())
            prefs.set(AppPreferences.KEY_SUMMARY_API_KEY, binding.etSummaryApiKey.text.toString().trim())
            prefs.set(AppPreferences.KEY_FILLER_PATTERNS_USER,
                binding.etFillerUserPatterns.text.toString().trim())
            prefs.set(AppPreferences.KEY_PROPER_NOUNS, binding.etProperNouns.text.toString().trim())
        }
    }

    private fun flushGroqSave() {
        groqSaveJob?.cancel(); groqSaveJob = null
        val prefs = (application as App).prefs
        lifecycleScope.launch {
            prefs.set(AppPreferences.KEY_GROQ_API_KEY, binding.etGroqApiKey.text.toString().trim())
        }
    }

    private fun flushSummarySave() {
        summarySaveJob?.cancel(); summarySaveJob = null
        val prefs = (application as App).prefs
        lifecycleScope.launch {
            prefs.set(AppPreferences.KEY_SUMMARY_API_KEY, binding.etSummaryApiKey.text.toString().trim())
        }
    }

    private fun showGroqStatus(text: String, color: Int, fade: Boolean) {
        binding.tvGroqKeyStatus.text = text
        binding.tvGroqKeyStatus.setTextColor(color)
        binding.tvGroqKeyStatus.alpha = 1f
        groqStatusFadeJob?.cancel()
        if (fade) {
            groqStatusFadeJob = lifecycleScope.launch {
                delay(2000)
                binding.tvGroqKeyStatus.animate().alpha(0f).setDuration(400).start()
            }
        }
    }

    private fun showSummaryStatus(text: String, color: Int, fade: Boolean) {
        binding.tvSummaryKeyStatus.text = text
        binding.tvSummaryKeyStatus.setTextColor(color)
        binding.tvSummaryKeyStatus.alpha = 1f
        summaryStatusFadeJob?.cancel()
        if (fade) {
            summaryStatusFadeJob = lifecycleScope.launch {
                delay(2000)
                binding.tvSummaryKeyStatus.animate().alpha(0f).setDuration(400).start()
            }
        }
    }

    private fun updateSummaryKeyHint(provider: SummaryProvider) {
        // 「使わない」ならキー欄一式を隠す (キー不要なので)
        val vis = if (provider == SummaryProvider.NONE) android.view.View.GONE else android.view.View.VISIBLE
        binding.tvSummaryKeyLabel.visibility = vis
        binding.tilSummaryKey.visibility = vis
        binding.llSummaryKeyActions.visibility = vis
        if (provider == SummaryProvider.NONE) return

        val (label, hint) = when (provider) {
            SummaryProvider.GROQ ->
                "要約用 API キー (空欄なら文字起こし用Groqキーを流用)" to "gsk_... (任意)"
            SummaryProvider.OPENAI ->
                "OpenAI API キー" to "sk-..."
            SummaryProvider.CLAUDE ->
                "Anthropic API キー" to "sk-ant-..."
            SummaryProvider.GEMINI ->
                "Google AI Studio API キー" to "AIza..."
            SummaryProvider.NONE -> "" to ""  // 到達しない (上でreturn)
        }
        binding.tvSummaryKeyLabel.text = label
        binding.etSummaryApiKey.hint = hint
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryStatus()
    }

    private fun refreshBatteryStatus() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        binding.tvBatteryStatus.text = getString(
            if (exempt) R.string.settings_battery_ok else R.string.settings_battery_warn
        )
        binding.btnBatterySettings.isEnabled = !exempt
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressedDispatcher.onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }
}
