package com.rokuonsumm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.rokuonsumm.databinding.ActivitySpeakerRegistrationBinding
import com.rokuonsumm.databinding.DialogEnrollRecordBinding
import com.rokuonsumm.recording.EnrollmentRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class SpeakerRegistrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpeakerRegistrationBinding
    private val viewModel: SpeakerRegistrationViewModel by viewModels()
    private lateinit var adapter: SpeakerProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpeakerRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = SpeakerProfileAdapter(::onProfileClick)
        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = adapter

        binding.btnAddCustom.setOnClickListener { promptCustomName() }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.rows.collect { adapter.submitList(it) } }
                launch {
                    viewModel.enrollEvent.collect { ev ->
                        when (ev) {
                            is EnrollResult.Success -> Toast.makeText(
                                this@SpeakerRegistrationActivity,
                                "「${ev.name}」を登録しました (+${ev.addedSamples} / 計${ev.totalSamples}サンプル)",
                                Toast.LENGTH_LONG
                            ).show()
                            is EnrollResult.Failure -> Toast.makeText(
                                this@SpeakerRegistrationActivity, ev.reason, Toast.LENGTH_LONG
                            ).show()
                            null -> {}
                        }
                        if (ev != null) viewModel.consumeEvent()
                    }
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun onProfileClick(row: ProfileRow) {
        val options = if (row.registered)
            arrayOf("録音して追加登録", "既存音声から登録", "削除")
        else
            arrayOf("録音して登録", "既存音声から登録")
        AlertDialog.Builder(this)
            .setTitle(row.name)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "録音して登録", "録音して追加登録" -> showRecordDialog(row.name)
                    "既存音声から登録" -> showSegmentPicker(row.name)
                    "削除" -> {
                        viewModel.delete(row.name)
                        Toast.makeText(this, "「${row.name}」を削除しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun promptCustomName() {
        val et = android.widget.EditText(this).apply { hint = "話者名" }
        AlertDialog.Builder(this)
            .setTitle("話者を追加")
            .setView(et)
            .setPositiveButton("次へ") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) onProfileClick(ProfileRow(name, 0, false))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── 録音して登録 ──────────────────────────────────────────────
    private fun showRecordDialog(name: String) {
        val db = DialogEnrollRecordBinding.inflate(LayoutInflater.from(this))
        db.tvRecTitle.text = "「$name」の声を録音"
        val recorder = EnrollmentRecorder(this)
        var timerJob: Job? = null
        val dialog = AlertDialog.Builder(this).setView(db.root).setCancelable(false).create()

        db.btnRecToggle.setOnClickListener {
            if (!recorder.isRecording) {
                if (!recorder.start()) {
                    Toast.makeText(this, "録音を開始できません（マイク使用中?）", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                db.btnRecToggle.text = "停止して登録"
                timerJob = lifecycleScope.launch {
                    var s = 0
                    while (true) { db.tvTimer.text = "%d:%02d".format(s / 60, s % 60); delay(1000); s++ }
                }
            } else {
                timerJob?.cancel()
                val f = recorder.stop()
                dialog.dismiss()
                if (f != null) viewModel.enrollFromFile(name, f)
                else Toast.makeText(this, "録音に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "キャンセル") { _, _ ->
            timerJob?.cancel()
            if (recorder.isRecording) recorder.stop()?.delete()
        }
        dialog.show()
    }

    // ── 既存音声から登録 ──────────────────────────────────────────
    private fun showSegmentPicker(name: String) {
        lifecycleScope.launch {
            val segs = viewModel.recentSegments()
            if (segs.isEmpty()) {
                Toast.makeText(this@SpeakerRegistrationActivity, "選べる録音がありません", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = segs.map { segLabel(it) }.toTypedArray()
            AlertDialog.Builder(this@SpeakerRegistrationActivity)
                .setTitle("「$name」の声を含む録音を選択")
                .setItems(labels) { _, which ->
                    Toast.makeText(this@SpeakerRegistrationActivity, "解析中…", Toast.LENGTH_SHORT).show()
                    viewModel.enrollFromFile(name, segs[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun segLabel(f: File): String {
        // seg_yyyyMMdd_HHmmss.m4a → "5/31 14:03 (8.3MB)"
        val n = f.nameWithoutExtension.removePrefix("seg_")
        val mb = "%.1fMB".format(f.length() / 1024.0 / 1024.0)
        return try {
            val d = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).parse(n)
            java.text.SimpleDateFormat("M/d H:mm", java.util.Locale.getDefault()).format(d!!) + " ($mb)"
        } catch (e: Exception) { "${f.name} ($mb)" }
    }
}
