package com.palixander.weightogether.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        AppStateEntity::class,
        MeasurementEntity::class,
        PendingMeasurementEntity::class,
        MeasurementTombstoneEntity::class,
        BackupImportCheckpointEntity::class,
        PetEntity::class,
        PetMeasurementEntity::class,
        AcceptedStableMeasurementEntity::class,
        WeighingReminderScheduleEntity::class,
        WeighingReminderRuntimeEntity::class,
    ],
    version = 19,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun appStateDao(): AppStateDao

    abstract fun measurementDao(): MeasurementDao

    abstract fun multiAccountMeasurementDao(): MultiAccountMeasurementDao

    abstract fun pendingMeasurementDao(): PendingMeasurementDao

    abstract fun backupImportCheckpointDao(): BackupImportCheckpointDao

    abstract fun petDao(): PetDao

    abstract fun acceptedStableMeasurementDao(): AcceptedStableMeasurementDao

    abstract fun weighingReminderDao(): WeighingReminderDao

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
        val MIGRATION_7_8: Migration = Migration7To8
        val MIGRATION_8_9: Migration = Migration8To9
        val MIGRATION_9_10: Migration = Migration9To10
        val MIGRATION_10_11: Migration = Migration10To11
        val MIGRATION_11_12: Migration = Migration11To12
        val MIGRATION_13_14: Migration = Migration13To14
        val MIGRATION_14_15: Migration = Migration14To15
        val MIGRATION_15_16: Migration = Migration15To16
        val MIGRATION_16_17: Migration = Migration16To17
        val MIGRATION_17_18: Migration = Migration17To18
        val MIGRATION_18_19: Migration = Migration18To19
        val MIGRATION_12_13: Migration = Migration12To13

        fun build(
            context: Context,
            databaseName: String = "scalesync.db",
        ): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addCallback(
                object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createWeighingReminderOwnerTriggers(db)
                    }
                },
            )
            .addMigrations(
                migration1To2(context),
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
            .build()
    }
}
