package com.rokuonsumm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val segmentPath: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val audioDeleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** 話者ラベル。null=未識別 / 話者名 / "不明"(=テレビ/動画/来客等) */
    val speakerLabel: String? = null,
    /** この発話の代表声紋(192次元 little-endian)。後からの命名・再クラスタ用。null=未計算 */
    val speakerEmbedding: ByteArray? = null
) {
    // ByteArray を含むため equals/hashCode は声紋を除いて生成 (DiffUtilの無駄な再描画防止)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TranscriptEntity) return false
        return id == other.id && segmentPath == other.segmentPath &&
            startTimeMs == other.startTimeMs && endTimeMs == other.endTimeMs &&
            text == other.text && audioDeleted == other.audioDeleted &&
            createdAt == other.createdAt && speakerLabel == other.speakerLabel
    }
    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + segmentPath.hashCode()
        r = 31 * r + startTimeMs.hashCode()
        r = 31 * r + endTimeMs.hashCode()
        r = 31 * r + text.hashCode()
        r = 31 * r + audioDeleted.hashCode()
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + (speakerLabel?.hashCode() ?: 0)
        return r
    }
}
