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
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun appStateDao(): AppStateDao

    abstract fun measurementDao(): MeasurementDao

    abstract fun multiAccountMeasurementDao(): MultiAccountMeasurementDao

    abstract fun pendingMeasurementDao(): PendingMeasurementDao

    companion object {
        fun migration1To2(
            context: Context,
            nowEpochMillis: () -> Long = System::currentTimeMillis,
        ): Migration = Migration1To2(
            legacyProfile = LegacyProfileSnapshot.from(context),
            nowEpochMillis = nowEpochMillis,
        )

        val MIGRATION_2_3: Migration = Migration2To3

        fun build(
            context: Context,
            databaseName: String = "huawei-mi-sync.db",
        ): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(migration1To2(context), MIGRATION_2_3)
            .build()
    }
}
