package com.seenot.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.seenot.app.data.local.dao.AppHintDao
import com.seenot.app.data.local.dao.IntentConstraintDao
import com.seenot.app.data.local.dao.RuleRecordDao
import com.seenot.app.data.local.dao.ScreenAnalysisResultDao
import com.seenot.app.data.local.dao.SessionDao
import com.seenot.app.data.local.dao.SessionIntentDao
import com.seenot.app.data.local.entity.AppHintEntity
import com.seenot.app.data.local.entity.IntentConstraintEntity
import com.seenot.app.data.local.entity.RuleRecordEntity
import com.seenot.app.data.local.entity.ScreenAnalysisResultEntity
import com.seenot.app.data.local.entity.SessionEntity
import com.seenot.app.data.local.entity.SessionIntentEntity

/**
 * Room database for SeeNot app
 */
@Database(
    entities = [
        SessionEntity::class,
        SessionIntentEntity::class,
        IntentConstraintEntity::class,
        ScreenAnalysisResultEntity::class,
        RuleRecordEntity::class,
        AppHintEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class SeenotDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun sessionIntentDao(): SessionIntentDao
    abstract fun intentConstraintDao(): IntentConstraintDao
    abstract fun screenAnalysisResultDao(): ScreenAnalysisResultDao
    abstract fun ruleRecordDao(): RuleRecordDao
    abstract fun appHintDao(): AppHintDao

    companion object {
        private const val DATABASE_NAME = "seenot_database"

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_hints ADD COLUMN scopeType TEXT NOT NULL DEFAULT 'INTENT_SPECIFIC'")
                db.execSQL("ALTER TABLE app_hints ADD COLUMN scopeKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE app_hints SET scopeKey = intentId WHERE scopeKey = ''")
            }
        }

        @Volatile
        private var INSTANCE: SeenotDatabase? = null

        fun getInstance(context: Context): SeenotDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SeenotDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
