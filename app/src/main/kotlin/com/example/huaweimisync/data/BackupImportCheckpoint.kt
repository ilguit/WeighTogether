package com.example.huaweimisync.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "backup_import_checkpoint")
data class BackupImportCheckpointEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val phase: String = PHASE_TARGET_APPLIED,
    // Keep the v7 physical column names so existing databases need no destructive migration.
    @ColumnInfo(name = "rollbackDatabaseJson") val operationId: String,
    @ColumnInfo(name = "previousSettingsJson") val sweepNeeded: String,
    val targetSettingsJson: String,
) {
    companion object {
        const val SINGLETON_ID = 1
        const val PHASE_TARGET_APPLIED = "TARGET_APPLIED"
    }
}

@Dao
interface BackupImportCheckpointDao {
    @Query("SELECT * FROM backup_import_checkpoint WHERE singletonId = 1")
    suspend fun get(): BackupImportCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(checkpoint: BackupImportCheckpointEntity)

    @Query("DELETE FROM backup_import_checkpoint WHERE singletonId = 1")
    suspend fun delete(): Int
}
