package com.rokuonsumm.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.rokuonsumm.OpLog
import com.rokuonsumm.R
import com.rokuonsumm.databinding.ActivityMainBinding
import com.rokuonsumm.recording.RecordingService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var adapter: DayRowAdapter
    private var statusDotAnim: ObjectAnimator? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioOk = results[Manifest.permission.RECORD_AUDIO] ?: hasPerm(Manifest.permission.RECORD_AUDIO)
        if (audioOk) {
            requestBatteryExemption()
            startRecording()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        OpLog.i(this, "MainActivity.onCreate")

        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            OpLog.i(this, "MainActivity", "auto_start intent")
            checkPermissionsAndStart()
        }

        // テスト用: adb shell am start --es navigate "settings|storage|timeline:YYYY-MM-DD|detail:ID"
        intent.getStringExtra(EXTRA_NAVIGATE)?.let { target ->
            OpLog.i(this, "MainActivity", "navigate=$target")
            when {
                target == "settings" ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                target == "storage" ->
                    startActivity(Intent(this, StorageActivity::class.java))
                target.startsWith("timeline:") ->
                    startActivity(Intent(this, DayTimelineActivity::class.java).apply {
                        putExtra(DayTimelineActivity.EXTRA_DATE_KEY, target.removePrefix("timeline:"))
                    })
                target.startsWith("detail:") ->
                    target.removePrefix("detail:").toLongOrNull()?.let { id ->
                        startActivity(Intent(this, UtteranceDetailActivity::class.java).apply {
                            putExtra(UtteranceDetailActivity.EXTRA_TRANSCRIPT_ID, id)
                        })
                    }
            }
        }

        adapter = DayRowAdapter { row ->
            OpLog.i(this, "tap dayRow", "key=${row.dateKey}")
            startActivity(
                Intent(this, DayTimelineActivity::class.java).apply {
                    putExtra(DayTimelineActivity.EXTRA_DATE_KEY, row.dateKey)
                }
            )
        }
        binding.rvDays.layoutManager = LinearLayoutManager(this)
        binding.rvDays.adapter = adapter

        binding.btnMic.setOnClickListener {
            val state = RecordingService.stateFlow.value
            OpLog.i(this, "tap btnMic", "state=$state")
            when (state) {
                RecordingService.RecordingState.IDLE -> checkPermissionsAndStart()
                else -> stopRecording()
            }
        }

        binding.btnSearch.setOnClickListener {
            OpLog.i(this, "tap btnSearch")
            startActivity(Intent(this, SearchActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            OpLog.i(this, "tap btnSettings")
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnToday.setOnClickListener {
            val list = viewModel.dayRows.value
            val idx = list.indexOfFirst { it.isToday }
            OpLog.i(this, "tap btnToday", "found_idx=$idx")
            if (idx >= 0) binding.rvDays.smoothScrollToPosition(idx)
        }

        // 日付リスト購読
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dayRows.collect { rows ->
                    OpLog.i(this@MainActivity, "dayRows", "n=${rows.size} today=${rows.any { it.isToday }}")
                    adapter.submitList(rows)
                    binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    val hasToday = rows.any { it.isToday }
                    binding.btnToday.visibility = if (hasToday && rows.size > 1) View.VISIBLE else View.GONE
                }
            }
        }

        // 文字起こし状況チップ
        binding.rowTranscription.setOnClickListener {
            OpLog.i(this, "tap requeueTranscription")
            viewModel.requeuePending()
            android.widget.Toast.makeText(this, "文字起こしを再キューしました", android.widget.Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.transcriptionStatus.collect { st -> renderTranscriptionStatus(st) }
            }
        }

        // 録音状態の控えめな反映
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecordingService.stateFlow.collect { state ->
                    when (state) {
                        RecordingService.RecordingState.RECORDING -> {
                            binding.vStatusDot.setBackgroundResource(R.drawable.bg_status_dot_rec)
                            binding.tvStatus.text = getString(R.string.notif_recording)
                            binding.tvStatus.setTextColor(0xFFEF5350.toInt())
                            binding.btnMic.setImageResource(R.drawable.ic_stop_small)
                            binding.btnMic.contentDescription = "録音停止"
                            startStatusDotBreathing()
                        }
                        RecordingService.RecordingState.PAUSED -> {
                            binding.vStatusDot.setBackgroundResource(R.drawable.bg_status_dot_pause)
                            binding.tvStatus.text = getString(R.string.notif_paused)
                            binding.tvStatus.setTextColor(0xFFFF9800.toInt())
                            binding.btnMic.setImageResource(R.drawable.ic_stop_small)
                            binding.btnMic.contentDescription = "録音停止"
                            stopStatusDotBreathing()
                        }
                        RecordingService.RecordingState.IDLE -> {
                            binding.vStatusDot.setBackgroundResource(R.drawable.bg_status_dot_idle)
                            binding.tvStatus.text = getString(R.string.notif_idle)
                            binding.tvStatus.setTextColor(0xFFB0B0B0.toInt())
                            binding.btnMic.setImageResource(R.drawable.ic_mic)
                            binding.btnMic.contentDescription = "録音開始"
                            stopStatusDotBreathing()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        // アプリを開いた時、録音すべきなのにサービスが止まっていたら復帰させる。
        // (MIUI 等にバックグラウンドサービスを殺された場合の保険。前景なのでFGS起動可)
        val shouldRecord = getSharedPreferences("rec_state", MODE_PRIVATE)
            .getBoolean("should_be_recording", false)
        if (shouldRecord && RecordingService.stateFlow.value != RecordingService.RecordingState.RECORDING) {
            startRecording()
        }
    }

    /** 文字起こし状況チップの表示更新。残り0件なら隠す。 */
    private fun renderTranscriptionStatus(st: TranscriptionStatus) {
        if (st.allDone && !st.isActive) {
            binding.rowTranscription.visibility = View.GONE
            return
        }
        binding.rowTranscription.visibility = View.VISIBLE
        val msg = when {
            st.running > 0 -> "文字起こし中… 残り${st.pending}件 (処理中${st.running})"
            st.enqueued > 0 -> "文字起こし待機中… 残り${st.pending}件"
            st.pending > 0 -> "文字起こし未処理 ${st.pending}件"
            else -> "文字起こし完了"
        }
        binding.tvTranscription.text = msg
        // 実行中はスピナー、待機/停滞中はオレンジドット
        if (st.running > 0) {
            binding.pbTranscription.visibility = View.VISIBLE
            binding.dotTranscription.visibility = View.GONE
        } else {
            binding.pbTranscription.visibility = View.GONE
            binding.dotTranscription.visibility = View.VISIBLE
        }
        // 動いてる時は「今すぐ」を出さない (押す必要ない)。停滞してる時だけ手動キックを促す。
        binding.tvTranscriptionAction.visibility =
            if (st.pending > 0 && !st.isActive) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        stopStatusDotBreathing()
        super.onDestroy()
    }

    private fun startStatusDotBreathing() {
        if (statusDotAnim?.isStarted == true) return
        statusDotAnim = ObjectAnimator.ofFloat(binding.vStatusDot, "alpha", 1f, 0.4f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopStatusDotBreathing() {
        statusDotAnim?.cancel()
        statusDotAnim = null
        binding.vStatusDot.alpha = 1f
    }

    // ── 権限 / 録音 ──────────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        val needed = buildList {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && !hasPerm(Manifest.permission.POST_NOTIFICATIONS))
                add(Manifest.permission.POST_NOTIFICATIONS)
            if (!hasPerm(Manifest.permission.READ_PHONE_STATE))
                add(Manifest.permission.READ_PHONE_STATE)
        }
        if (needed.isEmpty()) {
            requestBatteryExemption()
            startRecording()
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startRecording() {
        startForegroundService(
            Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
        )
    }

    private fun stopRecording() {
        startService(
            Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
        )
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
        const val EXTRA_NAVIGATE = "navigate"
    }
}
