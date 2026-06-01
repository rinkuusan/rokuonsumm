package com.rokuonsumm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.rokuonsumm.App
import com.rokuonsumm.OpLog
import com.rokuonsumm.R
import com.rokuonsumm.databinding.ActivityDayTimelineBinding
import kotlinx.coroutines.launch

class DayTimelineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDayTimelineBinding
    private val viewModel: DayTimelineViewModel by viewModels()
    private lateinit var adapter: TimelineAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDayTimelineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val dateKey = intent.getStringExtra(EXTRA_DATE_KEY) ?: run {
            OpLog.w(this, "DayTimelineActivity", "no dateKey, finish")
            finish(); return
        }
        OpLog.i(this, "DayTimelineActivity.onCreate", "dateKey=$dateKey")
        viewModel.setDateKey(dateKey)

        adapter = TimelineAdapter(
            onParagraphClick = { transcriptId ->
                OpLog.i(this, "tap paragraph", "transcriptId=$transcriptId")
                startActivity(
                    Intent(this, UtteranceDetailActivity::class.java).apply {
                        putExtra(UtteranceDetailActivity.EXTRA_TRANSCRIPT_ID, transcriptId)
                    }
                )
            },
            onSummarizeClick = {
                OpLog.i(this, "tap btnSummarize")
                viewModel.summarize()
            }
        )
        binding.rvTimeline.layoutManager = LinearLayoutManager(this)
        binding.rvTimeline.adapter = adapter

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.timeline.collect { data ->
                        if (data != null) {
                            binding.toolbar.title = data.displayDate
                            adapter.submitList(data.items)
                        }
                    }
                }
                launch {
                    viewModel.speakerFilter.collect { f ->
                        binding.toolbar.subtitle = if (f == null) null else "話者: $f"
                    }
                }
                launch {
                    viewModel.summarizeState.collect { state ->
                        OpLog.i(this@DayTimelineActivity, "summarizeState",
                            state.javaClass.simpleName + (if (state is SummarizeState.Error) " msg=${state.message}" else ""))
                        if (state is SummarizeState.Error) {
                            Toast.makeText(this@DayTimelineActivity, state.message, Toast.LENGTH_LONG).show()
                            viewModel.clearError()
                        }
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_day_timeline, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_filter_speaker -> { showSpeakerFilterDialog(); true }
            R.id.action_copy_all -> { copyOrShareTranscripts(share = false); true }
            R.id.action_copy_template -> { showCopyTemplateDialog(); true }
            R.id.action_share_all -> { copyOrShareTranscripts(share = true); true }
            R.id.action_copy_summary -> { copySummary(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun copyOrShareTranscripts(share: Boolean) {
        lifecycleScope.launch {
            val text = viewModel.buildFullTranscriptText()
            if (text.isNullOrBlank()) {
                Toast.makeText(this@DayTimelineActivity,
                    "コピーする発言がありません", Toast.LENGTH_SHORT).show()
                return@launch
            }
            OpLog.i(this@DayTimelineActivity, "copyOrShareTranscripts",
                "share=$share len=${text.length}")
            if (share) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(intent, "全文を共有"))
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("発言記録", text))
                Toast.makeText(this@DayTimelineActivity,
                    "全文 (${text.length}文字) をクリップボードにコピーしました",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    /** LLMに貼る用に、用途別プロンプトを前置きして全文コピーする */
    private fun showCopyTemplateDialog() {
        // (ラベル, プロンプト前置き)
        val templates = listOf(
            "要約して" to "次の会話ログ（時刻・話者つき）を200〜400字の自然な文章に要約して：\n\n",
            "スライド構成に" to "次の会話ログを、プレゼン用スライドの構成（各スライドのタイトル＋箇条書き）に変換して：\n\n",
            "日記にして" to "次の会話ログを、一人称の日記風の読み物にまとめて：\n\n",
            "TODO・約束を抽出" to "次の会話ログから、やるべきこと・約束・宿題だけを抽出してチェックリストにして：\n\n",
            "議事録に" to "次の会話ログを議事録形式（決定事項／論点／TODO）にまとめて：\n\n",
            "三行でまとめて" to "次の会話ログを三行で要約して：\n\n"
        )
        val labels = templates.map { it.first }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("用途別にコピー（LLMに貼る用）")
            .setItems(labels) { _, which ->
                lifecycleScope.launch {
                    val body = viewModel.buildFullTranscriptText()
                    if (body.isNullOrBlank()) {
                        Toast.makeText(this@DayTimelineActivity, "コピーする発言がありません", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val text = templates[which].second + body
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("発言記録", text))
                    OpLog.i(this@DayTimelineActivity, "copyTemplate", "kind=${templates[which].first} len=${text.length}")
                    Toast.makeText(this@DayTimelineActivity,
                        "「${templates[which].first}」用にコピー (${text.length}文字)。LLMに貼ってな", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSpeakerFilterDialog() {
        val speakers = viewModel.availableSpeakers.value
        if (speakers.isEmpty()) {
            Toast.makeText(this, "この日に話者ラベルはまだありません", Toast.LENGTH_SHORT).show()
            return
        }
        val options = listOf("すべて表示") + speakers
        val current = viewModel.speakerFilter.value
        val checked = if (current == null) 0 else (speakers.indexOf(current) + 1).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("話者で絞り込み")
            .setSingleChoiceItems(options.toTypedArray(), checked) { dlg, which ->
                viewModel.setSpeakerFilter(if (which == 0) null else options[which])
                dlg.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copySummary() {
        val data = viewModel.timeline.value
        val summary = data?.items?.firstNotNullOfOrNull { it as? TimelineUiItem.SummaryCard }?.summary
        if (summary.isNullOrBlank()) {
            Toast.makeText(this, "この日の要約はまだ生成されていません", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("要約", summary))
        Toast.makeText(this, "要約をクリップボードにコピーしました", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_DATE_KEY = "date_key"
    }
}
