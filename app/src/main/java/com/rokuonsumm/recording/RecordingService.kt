package com.rokuonsumm.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rokuonsumm.App
import com.rokuonsumm.OpLog
import com.rokuonsumm.R
import com.rokuonsumm.data.prefs.AppPreferences
import com.rokuonsumm.transcription.TranscriptionWorker
import com.rokuonsumm.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RecordingService : LifecycleService() {

    // ── 状態 ──────────────────────────────────────────────────────────────

    enum class RecordingState { IDLE, RECORDING, PAUSED }

    private enum class PauseReason { MIC_STOLEN, MEDIA_PLAYING, PHONE_CALL }

    private var state = RecordingState.IDLE
        set(v) { field = v; _stateFlow.value = v }

    private val pauseReasons = mutableSetOf<PauseReason>()

    // ── コンポーネント ────────────────────────────────────────────────────

    private lateinit var recorder: AudioSegmentRecorder
    private lateinit var files: SegmentFileManager
    private lateinit var prefs: AppPreferences
    private lateinit var audioManager: AudioManager
    private lateinit var stateLogger: StateLogger

    // ── Prefs ライブコピー ─────────────────────────────────────────────

    @Volatile private var segmentMs = AppPreferences.DEFAULT_SEGMENT_DURATION_MIN * 60_000L
    @Volatile private var bitrateBps = AppPreferences.DEFAULT_BITRATE_BPS
    @Volatile private var storageWarnBytes = AppPreferences.DEFAULT_STORAGE_WARNING_MB * 1024L * 1024L

    private var rollTimerJob: Job? = null
    private var notifUpdateJob: Job? = null
    private var watchdogJob: Job? = null

    // resume/error リトライ管理
    private var errorRetryCount = 0

    // ── 「録音すべき」永続フラグ ───────────────────────────────────────
    // システムにサービスを殺されても、再起動時にこれを見て自動再開する。
    // ユーザが明示的に停止した時だけ false にする。
    private val recState by lazy { getSharedPreferences("rec_state", MODE_PRIVATE) }
    private var shouldBeRecording: Boolean
        get() = recState.getBoolean("should_be_recording", false)
        set(v) { recState.edit().putBoolean("should_be_recording", v).apply() }

    // ── 電話着信検知 ─────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private val legacyPhoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleCallState(state)
    }

    private var telephonyCallbackHolder: Any? = null  // TelephonyCallback (API31+) を保持

    // ── AudioRecordingCallback: カメラ等によるマイク奪取を検知 ──────────
    // handler=null → メインスレッドで呼ばれる

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val silenced = configs.any { it.isClientSilenced }
            if (silenced) addPauseReason(PauseReason.MIC_STOLEN)
            else removePauseReason(PauseReason.MIC_STOLEN)
        }
    }

    // ── AudioPlaybackCallback: YouTube 等 USAGE_MEDIA 再生を検知 ─────────

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val mediaPlaying = configs.any {
                it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA
            }
            if (mediaPlaying) addPauseReason(PauseReason.MEDIA_PLAYING)
            else removePauseReason(PauseReason.MEDIA_PLAYING)
        }
    }

    // ── ライフサイクル ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = (application as App).prefs
        files = SegmentFileManager(this)
        recorder = AudioSegmentRecorder(
            context = this,
            onSegmentSealed = ::onSegmentSealed,
            onError = ::handleError
        )
        audioManager = getSystemService(AudioManager::class.java)
        stateLogger = StateLogger(this)
        collectPrefs()
        audioManager.registerAudioRecordingCallback(recordingCallback, null)
        audioManager.registerAudioPlaybackCallback(playbackCallback, null)
        registerPhoneListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                shouldBeRecording = true
                startForeground(NOTIF_ID_RECORDING, buildNotification())
                if (state == RecordingState.IDLE) {
                    stateLogger.log("SERVICE_START")
                    startSegment()
                }
                startWatchdog()
            }
            ACTION_STOP -> {
                shouldBeRecording = false
                stopWatchdog()
                stopAndExit()
            }
            else -> {
                // システムによる START_STICKY 再起動 (intent=null)。
                // 録音すべき状態なら、勝手に復帰する。
                // null intent でも startForegroundService 契約を満たすため
                // 必ず一度 startForeground する (未履行だと Android 8+ でクラッシュ)。
                startForeground(NOTIF_ID_RECORDING, buildNotification())
                if (shouldBeRecording) {
                    stateLogger.log("SERVICE_RESTART_AUTO")
                    OpLog.i(this, "RecordingService.auto_restart", "state=$state")
                    if (state == RecordingState.IDLE && pauseReasons.isEmpty()) startSegment()
                    startWatchdog()
                } else {
                    stopWatchdog()
                    stopAndExit()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        unregisterPhoneListener()
        notifUpdateJob?.cancel()
        rollTimerJob?.cancel()
        watchdogJob?.cancel()
        recorder.stop()?.let {
            stateLogger.log("SEGMENT_STOP", it.name, it.length().toString())
            enqueueTranscription(it)
        }
        stateLogger.log("SERVICE_DESTROY")
        super.onDestroy()
    }

    // ── セグメント録音制御 ─────────────────────────────────────────────

    private fun startSegment() {
        val file = files.newSegmentFile()
        recorder.start(file, bitrateBps)
        if (!recorder.isRecording) return  // start 内エラー → handleError が後処理
        errorRetryCount = 0                // 起動成功でカウンタリセット
        // セッション開始時刻の管理 (IDLE→RECORDING 遷移のみリセット)
        val now = System.currentTimeMillis()
        if (state == RecordingState.IDLE) {
            sessionStartMs  = now
            totalPausedMs   = 0L
            pauseEnteredMs  = 0L
        } else if (state == RecordingState.PAUSED && pauseEnteredMs > 0) {
            totalPausedMs  += now - pauseEnteredMs
            pauseEnteredMs  = 0L
        }
        state = RecordingState.RECORDING
        stateLogger.log("SEGMENT_START", file.name)
        scheduleRoll()
        startNotifUpdater()
        updateNotification()
        checkStorage()
        Log.i(TAG, "segment started: ${file.name}")
    }

    /**
     * gapless ロール完了コールバック（メインスレッド）。
     * 旧ファイルは sealed 済み。recorder は既に新ファイルに書き込み中。
     */
    private fun onSegmentSealed(file: java.io.File) {
        stateLogger.log("SEGMENT_SEALED", file.name, file.length().toString())
        Log.i(TAG, "sealed: ${file.name} (${file.length()} bytes)")
        enqueueTranscription(file)
        if (state == RecordingState.RECORDING) scheduleRoll()
    }

    /**
     * 5分タイマー → rollTo() 呼び出し。
     * 実際の切り替えと次タイマーの設定は onSegmentSealed() に委ねる。
     */
    private fun scheduleRoll() {
        rollTimerJob?.cancel()
        rollTimerJob = lifecycleScope.launch {
            delay(segmentMs)
            if (state == RecordingState.RECORDING) {
                recorder.rollTo(files.newSegmentFile(), bitrateBps)
            }
        }
    }

    /** ハード停止 (pause 専用)。ギャップは仕様として許容。 */
    private fun pauseSegment(reason: PauseReason) {
        if (state != RecordingState.RECORDING) return
        pauseEnteredMs = System.currentTimeMillis()
        rollTimerJob?.cancel()
        errorRetryCount = 0  // 次の resume に向けてリセット
        val stopped = recorder.stop()
        stopped?.let {
            stateLogger.log("SEGMENT_STOP", it.name, it.length().toString())
            enqueueTranscription(it)
        }
        stateLogger.log("PAUSED", reason.name)
        state = RecordingState.PAUSED
        stopNotifUpdater()
        updateNotification()
        Log.i(TAG, "paused: $reason")
    }

    /**
     * resume: 解放側アプリがまだマイクを保持している可能性があるため
     * RESUME_DELAY_MS だけ待ってから start する。
     * 失敗した場合は handleError() の指数バックオフリトライに委ねる。
     */
    private fun resumeSegment() {
        if (state != RecordingState.PAUSED || pauseReasons.isNotEmpty()) return
        Log.i(TAG, "resume scheduled (${RESUME_DELAY_MS}ms delay)")
        lifecycleScope.launch {
            delay(RESUME_DELAY_MS)
            if (state == RecordingState.PAUSED && pauseReasons.isEmpty()) {
                stateLogger.log("RESUMED")
                startSegment()
            }
        }
    }

    private fun stopAndExit() {
        stopNotifUpdater()
        rollTimerJob?.cancel()
        recorder.stop()?.let {
            stateLogger.log("SEGMENT_STOP", it.name, it.length().toString())
            enqueueTranscription(it)
        }
        stateLogger.log("SERVICE_STOP")
        sessionStartMs = 0L; totalPausedMs = 0L; pauseEnteredMs = 0L
        state = RecordingState.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── 一時停止理由管理（メインスレッド前提） ──────────────────────────

    private fun addPauseReason(reason: PauseReason) {
        val wasEmpty = pauseReasons.isEmpty()
        pauseReasons.add(reason)
        if (wasEmpty && state == RecordingState.RECORDING) pauseSegment(reason)
    }

    private fun removePauseReason(reason: PauseReason) {
        pauseReasons.remove(reason)
        if (pauseReasons.isEmpty() && state == RecordingState.PAUSED) resumeSegment()
    }

    // ── 電話着信 ─────────────────────────────────────────────────────────

    private fun handleCallState(callState: Int) {
        when (callState) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> addPauseReason(PauseReason.PHONE_CALL)
            TelephonyManager.CALL_STATE_IDLE    -> removePauseReason(PauseReason.PHONE_CALL)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) return
        val tm = getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleCallState(state)
            }
            telephonyCallbackHolder = cb
            tm.registerTelephonyCallback(mainExecutor, cb)
        } else {
            tm.listen(legacyPhoneListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterPhoneListener() {
        val tm = getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            (telephonyCallbackHolder as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
        } else {
            tm.listen(legacyPhoneListener, PhoneStateListener.LISTEN_NONE)
        }
        telephonyCallbackHolder = null
    }

    // ── WorkManager ───────────────────────────────────────────────────

    private fun enqueueTranscription(file: java.io.File) {
        TranscriptionWorker.enqueue(applicationContext, file.absolutePath)
    }

    // ── エラーリカバリー（指数バックオフ、最大 MAX_ERROR_RETRIES 回）──────
    //
    // 典型ケース: resume 直後にマイクがまだ他アプリ保持中で IllegalStateException。
    // RESUME_DELAY_MS 後の 1 回目失敗 → ここで 2s 後リトライ → 4s → 8s → 諦め。

    private fun handleError(e: Exception) {
        errorRetryCount++
        val errDesc = "${e.javaClass.simpleName}: ${e.message}"
        Log.e(TAG, "recorder error attempt $errorRetryCount/$MAX_ERROR_RETRIES: $errDesc")
        stateLogger.log("ERROR", errDesc, "attempt=$errorRetryCount")
        state = RecordingState.IDLE

        if (errorRetryCount > MAX_ERROR_RETRIES) {
            Log.e(TAG, "giving up — waiting for next resume trigger")
            stateLogger.log("ERROR_GIVEUP", "max_retries=$MAX_ERROR_RETRIES", errDesc)
            errorRetryCount = 0
            state = RecordingState.PAUSED
            updateNotification()
            return
        }

        // 指数バックオフ: 2s → 4s → 8s
        val delayMs = ERROR_RETRY_BASE_MS shl (errorRetryCount - 1)
        Log.i(TAG, "retrying in ${delayMs}ms")
        stateLogger.log("ERROR_RETRY", "attempt=$errorRetryCount", "delay_ms=$delayMs")
        lifecycleScope.launch {
            delay(delayMs)
            if (pauseReasons.isEmpty()) {
                startSegment()
            } else {
                stateLogger.log("ERROR_RETRY_SKIP", "pause_reasons=${pauseReasons}")
                errorRetryCount = 0
            }
        }
    }

    // ── ストレージ監視 ────────────────────────────────────────────────

    private fun checkStorage() {
        val free = files.freeSpaceBytes()
        if (free < storageWarnBytes) {
            val n = NotificationCompat.Builder(this, App.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(getString(R.string.storage_warning_title))
                .setContentText(getString(R.string.storage_warning_text, (free / 1024 / 1024).toInt()))
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID_STORAGE, n)
        }
    }

    // ── Prefs 収集 ────────────────────────────────────────────────────

    private fun collectPrefs() {
        lifecycleScope.launch { prefs.segmentDurationMinFlow.collect { segmentMs = it * 60_000L } }
        lifecycleScope.launch { prefs.bitrateBpsFlow.collect { bitrateBps = it } }
        lifecycleScope.launch { prefs.storageWarningMbFlow.collect { storageWarnBytes = it * 1024L * 1024L } }
    }

    // ── 録音ウォッチドッグ ─────────────────────────────────────────────
    //
    // 「録音すべき」のに RECORDING でなく、かつ pause 理由(動画撮影/視聴/通話)も
    // 無い場合 → 隙あらば録音を再開する。エラー諦め後のPAUSED固着や、見えない
    // 停止からの自動復帰を担う。

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = lifecycleScope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (shouldBeRecording &&
                    state != RecordingState.RECORDING &&
                    pauseReasons.isEmpty() &&
                    !recorder.isRecording
                ) {
                    stateLogger.log("WATCHDOG_RESUME", "state=$state")
                    OpLog.i(this@RecordingService, "RecordingService.watchdog_resume", "state=$state")
                    errorRetryCount = 0
                    startSegment()
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // ── 通知経過時間アップデーター ─────────────────────────────────────────

    private fun startNotifUpdater() {
        notifUpdateJob?.cancel()
        notifUpdateJob = lifecycleScope.launch {
            while (true) {
                delay(60_000)
                if (state == RecordingState.RECORDING) updateNotification()
            }
        }
    }

    private fun stopNotifUpdater() {
        notifUpdateJob?.cancel()
        notifUpdateJob = null
    }

    // ── 通知 ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val tapPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val statusText = when (state) {
            RecordingState.RECORDING -> {
                val elapsedMs = if (sessionStartMs > 0)
                    (System.currentTimeMillis() - sessionStartMs - totalPausedMs).coerceAtLeast(0)
                else 0L
                val h = elapsedMs / 3_600_000
                val m = (elapsedMs % 3_600_000) / 60_000
                val elapsed = if (h > 0) "${h}時間${m}分" else "${m}分"
                getString(R.string.notif_recording) + " — $elapsed"
            }
            RecordingState.PAUSED    -> getString(R.string.notif_paused)
            RecordingState.IDLE      -> getString(R.string.notif_idle)
        }
        return NotificationCompat.Builder(this, App.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setContentIntent(tapPi)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID_RECORDING, buildNotification())
    }

    // ── 定数 / companion ─────────────────────────────────────────────

    companion object {
        const val ACTION_START = "com.rokuonsumm.START"
        const val ACTION_STOP  = "com.rokuonsumm.STOP"
        private const val NOTIF_ID_RECORDING  = 1001
        private const val NOTIF_ID_STORAGE    = 1003
        private const val TAG                 = "RecordingService"

        private const val RESUME_DELAY_MS     = 500L   // 解放側アプリのマイク保持残存対策
        private const val ERROR_RETRY_BASE_MS = 2_000L // 指数バックオフ基底
        private const val MAX_ERROR_RETRIES   = 3      // 最大リトライ回数
        private const val WATCHDOG_INTERVAL_MS = 20_000L // 録音復帰チェック間隔

        private val _stateFlow = MutableStateFlow(RecordingState.IDLE)
        val stateFlow: StateFlow<RecordingState> = _stateFlow

        // タイマー用: Activityが再生成されても経過時間を保持する
        @Volatile var sessionStartMs = 0L       // IDLE→RECORDING の瞬間
        @Volatile var totalPausedMs  = 0L       // セッション内の累積 pause 時間
        @Volatile var pauseEnteredMs = 0L       // 現在の pause 開始時刻 (0=非 pause)
    }
}
