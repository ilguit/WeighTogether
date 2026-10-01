package com.palixander.weightogether.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration17To18 : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `weighing_reminder_schedules` (
                `id` TEXT NOT NULL, `ownerType` TEXT NOT NULL, `ownerId` TEXT NOT NULL,
                `minuteOfDay` INTEGER NOT NULL, `weekdaysMask` INTEGER NOT NULL,
                `importance` TEXT NOT NULL, `enabled` INTEGER NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_weighing_reminder_schedules_ownerType_ownerId` ON `weighing_reminder_schedules` (`ownerType`, `ownerId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_weighing_reminder_schedules_ownerType_ownerId_minuteOfDay_weekdaysMask_importance` ON `weighing_reminder_schedules` (`ownerType`, `ownerId`, `minuteOfDay`, `weekdaysMask`, `importance`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `weighing_reminder_runtime` (
                `scheduleId` TEXT NOT NULL, `generation` INTEGER NOT NULL,
                `regularOccurrenceToken` TEXT, `regularDueEpochMillis` INTEGER,
                `regularStatus` TEXT NOT NULL, `activeOccurrenceToken` TEXT,
                `snoozeGeneration` INTEGER, `snoozeSourceOccurrenceToken` TEXT,
                `snoozeOccurrenceToken` TEXT, `snoozeDueEpochMillis` INTEGER,
                `snoozeStatus` TEXT NOT NULL, PRIMARY KEY(`scheduleId`),
                FOREIGN KEY(`scheduleId`) REFERENCES `weighing_reminder_schedules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        createWeighingReminderOwnerTriggers(db)
    }
}

internal fun createWeighingReminderOwnerTriggers(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TRIGGER IF NOT EXISTS delete_account_weighing_reminders AFTER DELETE ON accounts BEGIN DELETE FROM weighing_reminder_schedules WHERE ownerType='ACCOUNT' AND ownerId=OLD.id; END")
    db.execSQL("CREATE TRIGGER IF NOT EXISTS delete_pet_weighing_reminders AFTER DELETE ON pets BEGIN DELETE FROM weighing_reminder_schedules WHERE ownerType='PET' AND ownerId=OLD.id; END")
}
