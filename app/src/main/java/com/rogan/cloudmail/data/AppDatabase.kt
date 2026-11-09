package com.rogan.cloudmail.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Account::class, MailMessage::class, Contact::class, OutboxItem::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun messages(): MessageDao
    abstract fun contacts(): ContactDao
    abstract fun outbox(): OutboxDao

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

        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "cloudmail.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { inst = it }
        }
    }
}
