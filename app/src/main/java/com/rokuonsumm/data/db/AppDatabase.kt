package com.rokuonsumm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TranscriptEntity::class, SummaryEntity::class, SpeakerProfileEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao
    abstract fun summaryDao(): SummaryDao
    abstract fun speakerProfileDao(): SpeakerProfileDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * v1→v2: 話者識別の追加。
         *  - transcripts に speakerLabel 列(nullable)を追加(既存行は null=未識別)
         *  - speaker_profiles テーブルを新設
         * 既存データ(文字起こし・要約)は破壊しない。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcripts ADD COLUMN speakerLabel TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS speaker_profiles (" +
                        "name TEXT NOT NULL, embedding BLOB NOT NULL, " +
                        "sampleCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(name))"
                )
            }
        }

        /**
         * v2→v3: 自動話者クラスタリング(Phase 1)の追加。
         *  - transcripts に speakerEmbedding 列(nullable BLOB) = 発話ごとの代表声紋
         *  - speaker_profiles に isAuto / occurrences 列(NOT NULL DEFAULT 0)
         * DEFAULT 0 は @ColumnInfo(defaultValue="0") と一致させてある。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcripts ADD COLUMN speakerEmbedding BLOB")
                db.execSQL("ALTER TABLE speaker_profiles ADD COLUMN isAuto INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE speaker_profiles ADD COLUMN occurrences INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v3→v4: 文字起こし品質メタデータ(verbose_json)の追加。
         * transcripts に no_speech/avg_logprob/compression を nullable REAL で追加。
         * 旧行は null のまま(=メタゲート対象外、テキスト反復検出のみ遡及適用)。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcripts ADD COLUMN noSpeechProb REAL")
                db.execSQL("ALTER TABLE transcripts ADD COLUMN avgLogprob REAL")
                db.execSQL("ALTER TABLE transcripts ADD COLUMN compressionRatio REAL")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rokuonsumm.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
    }
}
