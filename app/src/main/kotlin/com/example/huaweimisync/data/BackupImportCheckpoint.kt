package com.example.huaweimisync.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "backup_import_checkpoint")
data class BackupImportCheckpointEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val phase: String,
    val rollbackDatabaseJson: String,
    val previousSettingsJson: String,
    val targetSettingsJson: String,
) {
    companion object {
        const val SINGLETON_ID = 1
        const val PHASE_TARGET_APPLIED = "TARGET_APPLIED"
        const val PHASE_ROLLBACK_APPLIED = "ROLLBACK_APPLIED"
    }
}

@Dao
interface BackupImportCheckpointDao {
    @Query("SELECT * FROM backup_import_checkpoint WHERE singletonId = 1")
    suspend fun get(): BackupImportCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(checkpoint: BackupImportCheckpointEntity)

    @Query("UPDATE backup_import_checkpoint SET phase = :phase WHERE singletonId = 1")
    suspend fun setPhase(phase: String): Int

    @Query("DELETE FROM backup_import_checkpoint WHERE singletonId = 1")
    suspend fun delete(): Int
}
