package com.palixander.scalesync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WeighingReminderDao {
    @Query("SELECT * FROM weighing_reminder_schedules WHERE ownerType=:ownerType AND ownerId=:ownerId ORDER BY minuteOfDay, id")
    fun observe(ownerType: WeighingReminderOwnerType, ownerId: String): Flow<List<WeighingReminderScheduleEntity>>

    @Query("SELECT id FROM weighing_reminder_schedules WHERE ownerType=:ownerType AND ownerId=:ownerId")
    suspend fun getIds(ownerType: WeighingReminderOwnerType, ownerId: String): List<String>

    @Query("SELECT * FROM weighing_reminder_schedules WHERE id=:id")
    suspend fun get(id: String): WeighingReminderScheduleEntity?

    @Query("SELECT * FROM weighing_reminder_schedules WHERE enabled=1 ORDER BY ownerType, ownerId, minuteOfDay, id")
    suspend fun getEnabled(): List<WeighingReminderScheduleEntity>

    @Query("SELECT * FROM weighing_reminder_schedules ORDER BY ownerType, ownerId, minuteOfDay, id")
    suspend fun getAll(): List<WeighingReminderScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(schedule: WeighingReminderScheduleEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(schedules: List<WeighingReminderScheduleEntity>)

    @Update(onConflict = OnConflictStrategy.IGNORE)
    suspend fun update(schedule: WeighingReminderScheduleEntity): Int

    @Query("DELETE FROM weighing_reminder_schedules WHERE id=:id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM weighing_reminder_schedules")
    suspend fun deleteAll()

    @Query("SELECT * FROM weighing_reminder_runtime WHERE scheduleId=:scheduleId")
    suspend fun getRuntime(scheduleId: String): WeighingReminderRuntimeEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRuntime(runtime: WeighingReminderRuntimeEntity)

    @Update
    suspend fun updateRuntime(runtime: WeighingReminderRuntimeEntity): Int
}
