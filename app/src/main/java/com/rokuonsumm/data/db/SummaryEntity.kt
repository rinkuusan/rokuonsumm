package com.rokuonsumm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "summaries")
data class SummaryEntity(
    @PrimaryKey val date: String,         // "YYYY-MM-DD"
    val summary: String,
    val highlightsJson: String,           // JSON array
    val todosJson: String,                // JSON array
    val createdAt: Long = System.currentTimeMillis()
)
