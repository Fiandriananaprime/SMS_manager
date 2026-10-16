package mg.smsgateway.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SettingsEntity::class, JobEntity::class, SmsEntity::class, LogEntity::class, CampaignDraftEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun jobDao(): JobDao
    abstract fun smsDao(): SmsDao
    abstract fun logDao(): LogDao
    abstract fun campaignDraftDao(): CampaignDraftDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS campaign_draft (" +
                        "id INTEGER NOT NULL PRIMARY KEY, sourceName TEXT, message TEXT NOT NULL, " +
                        "recipientsJson TEXT NOT NULL, updatedAt INTEGER NOT NULL)"
                )
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN scheduledAt INTEGER")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN gatewayAuthorized INTEGER NOT NULL DEFAULT 1")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sms ADD COLUMN variablesJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "smsgateway.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
