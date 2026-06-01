package com.rokuonsumm.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SummaryEntity)

    @Query("SELECT * FROM summaries ORDER BY date DESC")
    fun observeAll(): Flow<List<SummaryEntity>>

    @Query("SELECT * FROM summaries WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): SummaryEntity?

    /** 要約の全文検索 (LIKE 部分一致)。新しい順、上限50件。 */
    @Query("SELECT * FROM summaries WHERE summary LIKE '%' || :q || '%' ORDER BY date DESC LIMIT 50")
    suspend fun search(q: String): List<SummaryEntity>
}
