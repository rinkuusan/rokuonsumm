package com.rokuonsumm.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.rokuonsumm.R
import com.rokuonsumm.databinding.ActivityAudioListBinding
import com.rokuonsumm.recording.AudioAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AudioListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudioListBinding
    private val viewModel: AudioListViewModel by viewModels()
    private lateinit var adapter: SegmentAdapter
    private var actionMode: ActionMode? = null

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var sheetBehavior: BottomSheetBehavior<*>
    private var seekJob: Job? = null
    private var analyzeJob: Job? = null
    private var userSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = SegmentAdapter(
            onLongPress = ::handleLongPress,
            onItemClick  = ::handleItemClick
        )
        binding.rvSegments.layoutManager = LinearLayoutManager(this)
        binding.rvSegments.adapter = adapter

        installSwipeToDelete()
        setupPlayer()

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.listItems.collect { items ->
                        adapter.submitList(items)
                        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.selectedPaths.collect { paths ->
                        adapter.selectedPaths = paths
                        adapter.isSelectionMode = paths.isNotEmpty()
                        when {
                            paths.isEmpty() -> actionMode?.finish()
                            else            -> actionMode?.title =
                                getString(R.string.selected_count, paths.size)
                        }
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    // ── プレイヤーセットアップ ─────────────────────────────────────────

    private fun setupPlayer() {
        sheetBehavior = BottomSheetBehavior.from(binding.playerSheet).apply {
            isHideable = true
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_HIDDEN
        }

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
            mediaPlayer?.let { mp ->
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

    private fun openPlayer(item: SegmentUiItem) {
        val file = File(item.filePath)
        if (!file.exists() || file.length() == 0L) {
            android.widget.Toast.makeText(this, "再生できないファイルです (0 KB)",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }

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
            android.widget.Toast.makeText(this, "再生エラー: ${e.message}",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val duration = mediaPlayer!!.duration
        binding.seekBar.max = duration
        binding.seekBar.progress = 0
        binding.tvCurrentTime.text = formatMs(0)
        binding.tvTotalTime.text = formatMs(duration)
        binding.tvPlayerTitle.text = item.displayTime

        setPlayIcon(true)
        mediaPlayer!!.start()
        startSeekUpdater()

        if (sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        // Analyze amplitude in background
        binding.waveformView.setLoading()
        analyzeJob = lifecycleScope.launch(Dispatchers.IO) {
            val (amps, _) = AudioAnalyzer.analyzeFile(file.absolutePath)
            withContext(Dispatchers.Main) {
                binding.waveformView.setAmplitudes(amps)
            }
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
        binding.fabPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
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
                    val dur = mp.duration
                    binding.seekBar.progress = pos
                    binding.tvCurrentTime.text = formatMs(pos)
                    updateWaveformPos(pos, dur)
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
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    // ── アイテム操作 ──────────────────────────────────────────────────

    private fun handleItemClick(item: SegmentUiItem) {
        if (viewModel.selectedPaths.value.isNotEmpty()) {
            viewModel.toggleSelection(item.filePath)
        } else {
            openPlayer(item)
        }
    }

    private fun handleLongPress(item: SegmentUiItem) {
        if (actionMode == null) actionMode = startSupportActionMode(actionModeCallback)
        viewModel.toggleSelection(item.filePath)
    }

    // ── ActionMode ────────────────────────────────────────────────────

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_action_delete, menu)
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == R.id.action_delete_selected) { confirmDeleteSelected(); return true }
            return false
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            viewModel.clearSelection()
            actionMode = null
        }
    }

    private fun confirmDeleteSelected() {
        val count = viewModel.selectedPaths.value.size
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_selected_title, count))
            .setMessage(R.string.delete_selected_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteSelected()
                actionMode?.finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── スワイプ削除 ──────────────────────────────────────────────────

    private fun installSwipeToDelete() {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder) = false

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                if (vh is SegmentAdapter.HeaderViewHolder) return 0
                if (adapter.isSelectionMode) return 0
                return super.getSwipeDirs(rv, vh)
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val listItem = adapter.currentList.getOrNull(pos) ?: return
                if (listItem is ListItem.Segment) viewModel.deleteFile(listItem.item.filePath)
            }
        }).attachToRecyclerView(binding.rvSegments)
    }
}
