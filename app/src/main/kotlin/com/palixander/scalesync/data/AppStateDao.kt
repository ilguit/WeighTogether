package com.palixander.scalesync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppStateDao {
    @Query("SELECT * FROM app_state WHERE singletonId = 1")
    fun observe(): Flow<AppStateEntity?>

    @Query("SELECT * FROM app_state WHERE singletonId = 1")
    suspend fun get(): AppStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefault(state: AppStateEntity = AppStateEntity()): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(state: AppStateEntity)

    @Query("UPDATE app_state SET primaryAccountId = :accountId WHERE singletonId = 1")
    suspend fun setPrimary(accountId: String?): Int

    @Query("UPDATE app_state SET weightDeltaKg = :weightDeltaKg WHERE singletonId = 1")
    suspend fun setWeightDelta(weightDeltaKg: Double): Int

    @Query("UPDATE app_state SET ignoreUnknownMeasurements = :enabled WHERE singletonId = 1")
    suspend fun setIgnoreUnknownMeasurements(enabled: Boolean): Int
}
