package com.palixander.weightogether.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY createdAtEpochMillis ASC, id ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY createdAtEpochMillis ASC, id ASC")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE normalizedName = :normalizedName")
    suspend fun getByNormalizedName(normalizedName: String): AccountEntity?

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM accounts WHERE photoPath = :photoPath")
    suspend fun countPhotoReferences(photoPath: String): Int

    @Query("SELECT photoPath FROM accounts WHERE photoPath IS NOT NULL")
    suspend fun getPhotoPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: AccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(accounts: List<AccountEntity>): List<Long>

    @Update
    suspend fun update(account: AccountEntity): Int

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM accounts")
    suspend fun deleteAll(): Int
}
