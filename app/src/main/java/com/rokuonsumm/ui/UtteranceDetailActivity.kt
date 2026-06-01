package com.rokuonsumm.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rokuonsumm.App
import com.rokuonsumm.OpLog
import com.rokuonsumm.R
import com.rokuonsumm.data.db.TranscriptEntity
import com.rokuonsumm.databinding.ActivityUtteranceDetailBinding
import com.rokuonsumm.recording.AudioAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UtteranceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUtteranceDetailBinding

    private var mediaPlayer: MediaPlayer? = null
    private var seekJob: Job? = null
    private var analyzeJob: Job? = null
    private var userSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUtteranceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val id = intent.getLongExtra(EXTRA_TRANSCRIPT_ID, -1L)
        if (id < 0) {
            OpLog.w(this, "UtteranceDetailActivity", "no transcriptId, finish")
            finish(); return
        }
        OpLog.i(this, "UtteranceDetailActivity.onCreate", "transcriptId=$id")

        setupPlayerControls()

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val db = (application as App).db
                val t = withContext(Dispatchers.IO) { db.transcriptDao().getById(id) }
                if (t == null) {
                    Toast.makeText(this@UtteranceDetailActivity, "見つかりません", Toast.LENGTH_SHORT).show()
                    finish()
                    return@repeatOnLifecycle
                }
                bind(t)
            }
        }
    }

    private fun bind(t: TranscriptEntity) {
        val timeFmt = SimpleDateFormat("H:mm", Locale.getDefault())
        binding.toolbar.title = timeFmt.format(Date(t.startTimeMs))
        binding.tvText.text = t.text
        renderSpeaker(t)

        val file = File(t.segmentPath)
        if (t.audioDeleted || !file.exists() || file.length() == 0L) {
            // ファイルがない場合、プレイヤーは控えめに非表示
            binding.waveformView.visibility = View.GONE
            binding.seekBar.visibility = View.GONE
            binding.fabPlayPause.visibility = View.GONE
            binding.btnRewind.visibility = View.GONE
            binding.btnFastForward.visibility = View.GONE
            binding.tvCurrentTime.visibility = View.GONE
            binding.tvTotalTime.visibility = View.GONE
            binding.tvNoAudio.visibility = View.VISIBLE
            return
        }
        openPlayer(file)
    }

    private fun renderSpeaker(t: TranscriptEntity) {
        val label = t.speakerLabel
        if (label.isNullOrBlank()) {
            binding.speakerBar.visibility = View.GONE
            return
        }
        binding.speakerBar.visibility = View.VISIBLE
        binding.tvSpeaker.text = "話者: $label"
        // 「不明」/「人物N」で声紋があるものだけ命名可能 (登録済み話者は対象外)
        val nameable = (label == "不明" || label.startsWith("人物")) && t.speakerEmbedding != null
        binding.btnNameSpeaker.visibility = if (nameable) View.VISIBLE else View.GONE
        if (nameable) binding.btnNameSpeaker.setOnClickListener { showNameDialog(t) }
    }

    private fun showNameDialog(t: TranscriptEntity) {
        val et = android.widget.EditText(this).apply { hint = "名前 (例: あやか)" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("この声に名前を付ける")
            .setMessage("再生して誰の声か確認してから付けてな。同じ声紋の「不明」発話もまとめて名前が付く。")
            .setView(et)
            .setPositiveButton("付ける") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val app = application as App
                    val thr = maxOf(app.prefs.speakerThresholdFlow.first(), 0.5f)  // 一括反映は保守的に
                    val n = withContext(Dispatchers.IO) {
                        com.rokuonsumm.transcription.SpeakerNamer.name(app.db, t, name, thr)
                    }
                    Toast.makeText(this@UtteranceDetailActivity, "「$name」を登録 ($n 件に反映)", Toast.LENGTH_LONG).show()
                    val updated = withContext(Dispatchers.IO) { app.db.transcriptDao().getById(t.id) }
                    if (updated != null) renderSpeaker(updated)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupPlayerControls() {
        binding.waveformView.onSeek = { fraction ->
            mediaPlayer?.let { mp ->
                val pos = (fraction * mp.duration).toInt()
                mp.seekTo(pos)
                binding.seekBar.progress = pos
                binding.tvCurrentTime.text = formatMs(pos)
                binding.waveformView.setPosFraction(fraction)
            }
        }

        binding.fabPlayPause.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                setPlayIcon(false)
                stopSeekUpdater()
            } else {
                mp.start()
                setPlayIcon(true)
                startSeekUpdater()
            }
        }

        binding.btnRewind.setOnClickListener {
            mediaPlayer?.let { mp ->
                val pos = (mp.currentPosition - 10_000).coerceAtLeast(0)
                mp.seekTo(pos)
                binding.seekBar.progress = pos
                updateWaveformPos(pos, mp.duration)
            }
        }

        binding.btnFastForward.setOnClickListener {
            mediaPlayer?.let { mp ->
                val pos = (mp.currentPosition + 10_000).coerceAtMost(mp.duration)
                mp.seekTo(pos)
                binding.seekBar.progress = pos
                updateWaveformPos(pos, mp.duration)
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                mediaPlayer?.let { mp ->
                    mp.seekTo(sb.progress)
                    updateWaveformPos(sb.progress, mp.duration)
                }
                userSeeking = false
            }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = formatMs(progress)
                    mediaPlayer?.let { updateWaveformPos(progress, it.duration) }
                }
            }
        })
    }

    private fun openPlayer(file: File) {
        releasePlayer()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    setPlayIcon(false)
                    stopSeekUpdater()
                    seekTo(0)
                    binding.seekBar.progress = 0
                    binding.tvCurrentTime.text = formatMs(0)
                    binding.waveformView.setPosFraction(0f)
                }
            }
        } catch (e: Exception) {
            mediaPlayer?.release()
            mediaPlayer = null
            Toast.makeText(this, "再生エラー: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        val duration = mediaPlayer!!.duration
        binding.seekBar.max = duration
        binding.seekBar.progress = 0
        binding.tvCurrentTime.text = formatMs(0)
        binding.tvTotalTime.text = formatMs(duration)

        binding.waveformView.setLoading()
        analyzeJob = lifecycleScope.launch(Dispatchers.IO) {
            val (amps, _) = AudioAnalyzer.analyzeFile(file.absolutePath)
            withContext(Dispatchers.Main) { binding.waveformView.setAmplitudes(amps) }
        }
    }

    private fun releasePlayer() {
        analyzeJob?.cancel()
        analyzeJob = null
        stopSeekUpdater()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun setPlayIcon(playing: Boolean) {
        binding.fabPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateWaveformPos(posMs: Int, durationMs: Int) {
        if (durationMs > 0) binding.waveformView.setPosFraction(posMs.toFloat() / durationMs)
    }

    private fun startSeekUpdater() {
        stopSeekUpdater()
        seekJob = lifecycleScope.launch {
            while (true) {
                val mp = mediaPlayer ?: break
                if (!userSeeking && mp.isPlaying) {
                    val pos = mp.currentPosition
                    binding.seekBar.progress = pos
                    binding.tvCurrentTime.text = formatMs(pos)
                    updateWaveformPos(pos, mp.duration)
                }
                delay(500)
            }
        }
    }

    private fun stopSeekUpdater() {
        seekJob?.cancel()
        seekJob = null
    }

    private fun formatMs(ms: Int): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_TRANSCRIPT_ID = "transcript_id"
    }
}
