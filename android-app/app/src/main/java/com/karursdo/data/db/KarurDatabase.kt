package com.karursdo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        EmployeeEntity::class,
        OutsiderEntity::class,
        MobileMapEntity::class,
        MobileRosterEntity::class,
        OfficeMasterEntity::class,
        ArrangementEntity::class,
        ImportMetaEntity::class,
        MoBeatOfficeEntity::class,
        FavoriteEntity::class,
        NoteEntity::class,
        UserPrefEntity::class,
        ActivityEntity::class,
        UserAccountEntity::class,
        ChatMessageEntity::class,
        MoProgrammeEntity::class,
        PhoneEditEntity::class,
        ChatReadEntity::class,
        ChatReactionEntity::class,
        PresenceEntity::class,
        DatasetSnapshotEntity::class,
        OfficeEditEntity::class,
        StaffEditEntity::class,
        EventEntity::class
    ],
    version = 12,
    exportSchema = false
)
abstract class KarurDatabase : RoomDatabase() {
    abstract fun employeeDao(): EmployeeDao
    abstract fun outsiderDao(): OutsiderDao
    abstract fun mobileDao(): MobileDao
    abstract fun officeMasterDao(): OfficeMasterDao
    abstract fun arrangementDao(): ArrangementDao
    abstract fun importMetaDao(): ImportMetaDao
    abstract fun moBeatDao(): MoBeatDao
    abstract fun userDataDao(): UserDataDao
    abstract fun userAccountDao(): UserAccountDao
    abstract fun chatDao(): ChatDao
    abstract fun moProgrammeDao(): MoProgrammeDao
    abstract fun phoneEditDao(): PhoneEditDao
    abstract fun chatReadDao(): ChatReadDao
    abstract fun chatReactionDao(): ChatReactionDao
    abstract fun presenceDao(): PresenceDao
    abstract fun datasetSnapshotDao(): DatasetSnapshotDao
    abstract fun officeEditDao(): OfficeEditDao
    abstract fun staffEditDao(): StaffEditDao
    abstract fun eventDao(): EventDao
}
