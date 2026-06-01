package com.rokuonsumm.recording

import android.content.Context
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SegmentFileManager(private val context: Context) {

    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * 音声セグメント保存先。
     * - **外部 app-specific ディレクトリ** `/sdcard/Android/data/com.rokuonsumm/files/segments/`
     *   - ファイルマネージャから見える、PCから USB MTP 経由でアクセス可
     *   - 容量が大きい (録音ファイルは多くなりがち)
     *   - 外部が使えない場合は内部にフォールバック (extremely rare)
     */
    val segmentDir: File
        get() = (context.getExternalFilesDir("segments") ?: File(context.filesDir, "segments"))
            .also { it.mkdirs() }

    fun newSegmentFile(): File = File(segmentDir, "seg_${fmt.format(Date())}.m4a")

    fun allSegmentFiles(): List<File> =
        segmentDir.listFiles { f -> f.extension == "m4a" }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun freeSpaceBytes(): Long = StatFs(segmentDir.absolutePath).availableBytes

    fun delete(file: File): Boolean = file.delete()
}
