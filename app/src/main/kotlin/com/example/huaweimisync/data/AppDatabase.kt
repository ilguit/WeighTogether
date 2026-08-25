package com.example.huaweimisync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        AccountEntity::class,
        AppStateEntity::class,
        MeasurementEntity::class,
        PendingMeasurementEntity::class,
        MeasurementTombstoneEntity::class,
        BackupImportCheckpointEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun appStateDao(): AppStateDao

    abstract fun measurementDao(): MeasurementDao

    abstract fun multiAccountMeasurementDao(): MultiAccountMeasurementDao

    abstract fun pendingMeasurementDao(): PendingMeasurementDao

    abstract fun backupImportCheckpointDao(): BackupImportCheckpointDao

    companion object {
        fun migration1To2(
            context: Context,
            nowEpochMillis: () -> Long = System::currentTimeMillis,
        ): Migration = Migration1To2(
            legacyProfile = LegacyProfileSnapshot.from(context),
            nowEpochMillis = nowEpochMillis,
        )

        val MIGRATION_2_3: Migration = Migration2To3
        val MIGRATION_3_4: Migration = Migration3To4
        val MIGRATION_4_5: Migration = Migration4To5
        val MIGRATION_5_6: Migration = Migration5To6
        val MIGRATION_6_7: Migration = Migration6To7

        fun build(
            context: Context,
            databaseName: String = "huawei-mi-sync.db",
        ): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(
                migration1To2(context),
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .build()
    }
}
