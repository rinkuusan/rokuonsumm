package com.rokuonsumm.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class IdText(val id: Long, val text: String)

/** 話者リラベル用の射影 (声紋付き発話) */
data class EmbRow(val id: Long, val speakerEmbedding: ByteArray?, val speakerLabel: String?)

@Dao
interface TranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranscriptEntity): Long

    @Query("SELECT * FROM transcripts WHERE startTimeMs >= :fromMs AND startTimeMs < :toMs ORDER BY startTimeMs ASC")
    suspend fun getForDateRange(fromMs: Long, toMs: Long): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TranscriptEntity?

    @Query("SELECT id, text FROM transcripts")
    suspend fun getAllIdsAndTexts(): List<IdText>

    /**
     * 全文検索 (LIKE 部分一致)。日本語は単語境界が曖昧なため、トークナイズ不要の
     * 部分一致が最適。空文字は呼び出し側で弾く。新しい順、上限200件。
     */
    @Query("SELECT * FROM transcripts WHERE text LIKE '%' || :q || '%' ORDER BY startTimeMs DESC LIMIT 200")
    suspend fun search(q: String): List<TranscriptEntity>

    @Query("SELECT COUNT(*) FROM transcripts")
    suspend fun count(): Int

    @Query("SELECT * FROM transcripts ORDER BY startTimeMs DESC")
    fun observeAll(): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE audioDeleted = 0 ORDER BY startTimeMs DESC")
    fun observeWithAudio(): Flow<List<TranscriptEntity>>

    @Query("UPDATE transcripts SET audioDeleted = 1 WHERE segmentPath = :path")
    suspend fun markAudioDeleted(path: String)

    /** 保持期間を過ぎた(=startTimeMs が cutoff より古い)未削除音声のパス一覧 */
    @Query("SELECT segmentPath FROM transcripts WHERE audioDeleted = 0 AND startTimeMs < :cutoff")
    suspend fun getStaleAudioPaths(cutoff: Long): List<String>

    @Query("DELETE FROM transcripts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE transcripts SET text = :text WHERE id = :id")
    suspend fun updateText(id: Long, text: String)

    // ── 話者命名(Phase 1b) ──
    @Query("UPDATE transcripts SET speakerLabel = :newLabel WHERE speakerLabel = :oldLabel")
    suspend fun relabelSpeaker(oldLabel: String, newLabel: String): Int

    @Query("UPDATE transcripts SET speakerLabel = :label WHERE id = :id")
    suspend fun setSpeakerLabel(id: Long, label: String)

    /** 声紋を持つ発話(リラベル磁石用)。embeddingがある=新パイプラインで処理済み */
    @Query("SELECT id, speakerEmbedding, speakerLabel FROM transcripts WHERE speakerEmbedding IS NOT NULL")
    suspend fun getEmbeddedRows(): List<EmbRow>
}
