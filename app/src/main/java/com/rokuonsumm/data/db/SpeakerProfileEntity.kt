package com.rokuonsumm.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 話者プロファイル。声紋エンベディング(192次元, L2正規化済み)を BLOB で保持。
 * name が主キー(「純」「あやか」「おかん」等)。
 */
@Entity(tableName = "speaker_profiles")
data class SpeakerProfileEntity(
    @PrimaryKey val name: String,
    val embedding: ByteArray,      // FloatArray(192) を little-endian でシリアライズ
    val sampleCount: Int,          // 平均に使ったサンプル数
    val createdAt: Long = System.currentTimeMillis(),
    /** true=自動発見クラスタ(「人物A」等)。ユーザが命名すると false に昇格 */
    @ColumnInfo(defaultValue = "0") val isAuto: Boolean = false,
    /** この話者として識別された発話の累計回数(頻度ゲート/表示順用) */
    @ColumnInfo(defaultValue = "0") val occurrences: Int = 0
) {
    fun embeddingFloats(): FloatArray = bytesToFloats(embedding)

    // data class の ByteArray 警告対策
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeakerProfileEntity) return false
        return name == other.name && embedding.contentEquals(other.embedding) &&
            sampleCount == other.sampleCount && createdAt == other.createdAt &&
            isAuto == other.isAuto && occurrences == other.occurrences
    }
    override fun hashCode(): Int {
        var r = name.hashCode()
        r = 31 * r + embedding.contentHashCode()
        r = 31 * r + sampleCount
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + isAuto.hashCode()
        r = 31 * r + occurrences
        return r
    }

    companion object {
        fun floatsToBytes(v: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (x in v) buf.putFloat(x)
            return buf.array()
        }
        fun bytesToFloats(b: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { buf.float }
        }
    }
}
