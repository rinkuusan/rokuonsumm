package com.rokuonsumm.ui

import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.rokuonsumm.App
import com.rokuonsumm.OpLog
import com.rokuonsumm.R
import com.rokuonsumm.databinding.ActivityStorageBinding
import com.rokuonsumm.recording.SegmentFileManager
import com.rokuonsumm.transcription.TranscriptionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StorageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageBinding
    private lateinit var segmentFiles: SegmentFileManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        segmentFiles = SegmentFileManager(this)
        OpLog.i(this, "StorageActivity.onCreate")

        binding.btnDeleteAll.setOnClickListener {
            OpLog.i(this, "tap btnDeleteAll")
            confirmDeleteAll()
        }
        binding.btnExport.setOnClickListener {
            OpLog.i(this, "tap btnExport")
            showExportDialog()
        }
        binding.btnRequeue.setOnClickListener {
            OpLog.i(this, "tap btnRequeue")
            requeuePendingTranscriptions()
        }
    }

    override fun onResume() {
        super.onResume()
        updateDisplay()
    }

    private fun updateDisplay() {
        val files = segmentFiles.allSegmentFiles()
        val totalBytes = files.sumOf { it.length() }
        val totalMb = totalBytes / 1024.0 / 1024.0
        binding.tvUsedSize.text = String.format("%.1f MB", totalMb)
        binding.tvFileCount.text = "${files.size} ファイル"

        val freeBytes = segmentFiles.freeSpaceBytes()
        val freeGb = freeBytes / 1024.0 / 1024.0 / 1024.0
        binding.tvFreeSpace.text = String.format("残り %.1f GB", freeGb)

        // プログレスは「使用量 / 警告閾値」で表示 (デフォ 500MB)。閾値超で赤
        val warningMb = com.rokuonsumm.data.prefs.AppPreferences.DEFAULT_STORAGE_WARNING_MB
        val ratio = totalMb / warningMb
        val progress = (ratio * 100).toInt().coerceIn(0, 100)
        binding.pbStorage.progress = progress
        binding.pbStorage.setIndicatorColor(
            if (ratio >= 1.0) 0xFFEF5350.toInt()
            else if (ratio >= 0.7) 0xFFFFA726.toInt()
            else 0xFF4CAF50.toInt()
        )
    }

    private fun requeuePendingTranscriptions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val app = application as App
            val transcripts = app.db.transcriptDao().observeAll()
                .first()
                .mapTo(HashSet()) { it.segmentPath }
            val pending = segmentFiles.allSegmentFiles().filter { it.absolutePath !in transcripts }
            OpLog.i(this@StorageActivity, "requeue", "pending=${pending.size}")
            pending.forEach { TranscriptionWorker.enqueue(this@StorageActivity, it.absolutePath) }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@StorageActivity,
                    "${pending.size} 件を文字起こしキューに入れました",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmDeleteAll() {
        val files = segmentFiles.allSegmentFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.storage_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_all_title)
            .setMessage(getString(R.string.delete_all_message, files.size))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                files.forEach { segmentFiles.delete(it) }
                Toast.makeText(this, getString(R.string.storage_deleted, files.size), Toast.LENGTH_SHORT).show()
                updateDisplay()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showExportDialog() {
        val files = segmentFiles.allSegmentFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.storage_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_dialog_title, files.size))
            .setItems(arrayOf(
                getString(R.string.export_option_zip),
                getString(R.string.export_option_share)
            )) { _, which ->
                when (which) {
                    0 -> exportAsZip(files)
                    1 -> shareAsZip(files)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Downloads/Rokuonsumm/ にZIP保存。MediaStore経由なのでアンインストールでも消えない。
     */
    private fun exportAsZip(files: List<File>) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "rokuonsumm_${timestamp}.zip"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/Rokuonsumm")
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw Exception("Downloadsへの書き込み権限なし")

                contentResolver.openOutputStream(uri)?.use { out ->
                    ZipOutputStream(out.buffered()).use { zos ->
                        files.forEach { f ->
                            zos.putNextEntry(ZipEntry(f.name))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                } ?: throw Exception("OutputStream取得失敗")

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@StorageActivity,
                        "${files.size} 件を Downloads/Rokuonsumm/${filename} に保存しました",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StorageActivity, "ZIP作成エラー: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareAsZip(files: List<File>) {
        val zipFile = File(cacheDir, "recording_share.zip")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                    files.forEach { f ->
                        zos.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                val uri = FileProvider.getUriForFile(
                    this@StorageActivity,
                    "${packageName}.fileprovider",
                    zipFile
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    startActivity(Intent.createChooser(intent, getString(R.string.export_share_chooser)))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StorageActivity, "共有エラー: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
