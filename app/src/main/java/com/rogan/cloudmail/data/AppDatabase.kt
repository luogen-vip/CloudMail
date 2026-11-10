package com.rogan.cloudmail.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Account::class, MailMessage::class, Contact::class, OutboxItem::class,
                Label::class, MailLabel::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun messages(): MessageDao
    abstract fun contacts(): ContactDao
    abstract fun outbox(): OutboxDao
    abstract fun labels(): LabelDao
    abstract fun mailLabels(): MailLabelDao

    companion object {
        @Volatile private var inst: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN size INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE outbox ADD COLUMN error TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN oauthToken TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE accounts ADD COLUMN oauthRefresh TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE accounts ADD COLUMN oauthExpire INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS labels (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS mail_labels (" +
                        "labelId INTEGER NOT NULL, messageId INTEGER NOT NULL, " +
                        "PRIMARY KEY(labelId, messageId))")
            }
        }

        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "cloudmail.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { inst = it }
        }
    }
}
