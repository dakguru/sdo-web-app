package com.karursdo.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.karursdo.data.db.KarurDatabase
import com.karursdo.data.security.DbKeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

/**
 * v1 -> v2: adds the Mail Overseer beat-office table without touching existing data.
 * Kept at file scope (not inside the @Module object) so the Hilt/KSP processor does not
 * try to introspect this anonymous Migration as a module member.
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mo_beat_offices` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`beat` TEXT NOT NULL, `serialNo` INTEGER NOT NULL, " +
                "`officeName` TEXT NOT NULL, `matchedOfficeId` TEXT, " +
                "`visits` TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mo_beat_offices_beat` ON `mo_beat_offices` (`beat`)")
    }
}

/**
 * v2 -> v3: adds the four app-user tables (favorites, notes, prefs, activity) that back
 * the login feature. Additive only — no existing data is touched. Declared at file scope
 * (not inside @Module) to avoid the Hilt/KSP anonymous-Migration introspection crash.
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_favorites` (" +
                "`itemType` TEXT NOT NULL, `itemId` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, " +
                "PRIMARY KEY(`itemType`, `itemId`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_notes` (" +
                "`id` TEXT NOT NULL, `targetType` TEXT NOT NULL, `targetId` TEXT, " +
                "`title` TEXT, `body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                "`syncState` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_notes_targetType` ON `user_notes` (`targetType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_notes_targetId` ON `user_notes` (`targetId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_prefs` (" +
                "`key` TEXT NOT NULL, `value` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "`syncState` TEXT NOT NULL, PRIMARY KEY(`key`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity_log` (" +
                "`id` TEXT NOT NULL, `action` TEXT NOT NULL, `targetType` TEXT, `targetId` TEXT, " +
                "`summary` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_log_createdAt` ON `activity_log` (`createdAt`)")
    }
}

/**
 * v3 -> v4: adds the login-accounts table and an author column on notes (so each note
 * shows who wrote it). Additive only. File scope for the KSP/Hilt reason above.
 */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `users` (" +
                "`username` TEXT NOT NULL, `passwordHash` TEXT NOT NULL, `salt` TEXT NOT NULL, " +
                "`iterations` INTEGER NOT NULL, `displayName` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                "`active` INTEGER NOT NULL, `mustChangePassword` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, " +
                "PRIMARY KEY(`username`))"
        )
        db.execSQL("ALTER TABLE `user_notes` ADD COLUMN `authorName` TEXT")
    }
}

/**
 * v4 -> v5: adds MO-beat visit provenance (who edited + when + sync state) so visit
 * updates propagate to every user. Nullable columns (no SQL default) to match Room's
 * generated schema exactly. File scope for the KSP/Hilt reason above.
 */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `mo_beat_offices` ADD COLUMN `updatedBy` TEXT")
        db.execSQL("ALTER TABLE `mo_beat_offices` ADD COLUMN `updatedAt` INTEGER")
        db.execSQL("ALTER TABLE `mo_beat_offices` ADD COLUMN `syncState` TEXT")
    }
}

/** v5 -> v6: adds the group-chat messages table. Additive; file scope for KSP/Hilt. */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_messages` (" +
                "`id` TEXT NOT NULL, `username` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `syncState` TEXT, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_createdAt` ON `chat_messages` (`createdAt`)")
    }
}

/** v6 -> v7: adds the Mail Overseer tour-programme table. Additive; file scope for KSP/Hilt. */
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mo_programmes` (" +
                "`id` TEXT NOT NULL, `beat` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                "`details` TEXT NOT NULL, `author` TEXT, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `syncState` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mo_programmes_beat` ON `mo_programmes` (`beat`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mo_programmes_date` ON `mo_programmes` (`date`)")
    }
}

/** v7 -> v8: adds the synced staff phone-edit override table. Additive; file scope for KSP/Hilt. */
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `phone_edits` (" +
                "`targetType` TEXT NOT NULL, `targetId` TEXT NOT NULL, `phone` TEXT NOT NULL, " +
                "`updatedBy` TEXT, `updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, " +
                "PRIMARY KEY(`targetType`, `targetId`))"
        )
    }
}

/** v8 -> v9: chat messages gain edit-time + soft-delete columns. Additive; file scope for KSP/Hilt. */
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
        // Seed edit-time from createdAt so the pull high-water mark stays consistent.
        db.execSQL("UPDATE `chat_messages` SET `updatedAt` = `createdAt`")
    }
}

/**
 * v9 -> v10: adds chat read-receipt watermarks, presence, and dataset snapshots, plus the
 * new arrangement columns (type / personId / personName). Additive; file scope for KSP/Hilt.
 */
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_reads` (" +
                "`username` TEXT NOT NULL, `lastReadAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, PRIMARY KEY(`username`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `presence` (" +
                "`username` TEXT NOT NULL, `lastSeenAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, PRIMARY KEY(`username`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dataset_snapshots` (" +
                "`type` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `count` INTEGER NOT NULL, " +
                "`uploadedBy` TEXT, `uploadedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, " +
                "PRIMARY KEY(`type`))"
        )
        // Arrangements gain richer columns; existing rows get NULLs (re-uploaded next sync).
        db.execSQL("ALTER TABLE `arrangements` ADD COLUMN `arrangementType` TEXT")
        db.execSQL("ALTER TABLE `arrangements` ADD COLUMN `personId` TEXT")
        db.execSQL("ALTER TABLE `arrangements` ADD COLUMN `personName` TEXT")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDbKeyManager(@ApplicationContext context: Context): DbKeyManager =
        DbKeyManager(context)

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyManager: DbKeyManager
    ): KarurDatabase {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(keyManager.dbPassphrase())
        return Room.databaseBuilder(context, KarurDatabase::class.java, "karursdo.db")
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun employeeDao(db: KarurDatabase) = db.employeeDao()
    @Provides fun outsiderDao(db: KarurDatabase) = db.outsiderDao()
    @Provides fun mobileDao(db: KarurDatabase) = db.mobileDao()
    @Provides fun officeMasterDao(db: KarurDatabase) = db.officeMasterDao()
    @Provides fun arrangementDao(db: KarurDatabase) = db.arrangementDao()
    @Provides fun importMetaDao(db: KarurDatabase) = db.importMetaDao()
    @Provides fun moBeatDao(db: KarurDatabase) = db.moBeatDao()
    @Provides fun userDataDao(db: KarurDatabase) = db.userDataDao()
    @Provides fun userAccountDao(db: KarurDatabase) = db.userAccountDao()
    @Provides fun chatDao(db: KarurDatabase) = db.chatDao()
    @Provides fun moProgrammeDao(db: KarurDatabase) = db.moProgrammeDao()
    @Provides fun phoneEditDao(db: KarurDatabase) = db.phoneEditDao()
    @Provides fun chatReadDao(db: KarurDatabase) = db.chatReadDao()
    @Provides fun chatReactionDao(db: KarurDatabase) = db.chatReactionDao()
    @Provides fun presenceDao(db: KarurDatabase) = db.presenceDao()
    @Provides fun datasetSnapshotDao(db: KarurDatabase) = db.datasetSnapshotDao()
}
