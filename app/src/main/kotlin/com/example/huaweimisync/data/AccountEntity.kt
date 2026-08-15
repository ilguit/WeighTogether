package com.example.huaweimisync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val normalizedName: String,
    val heightCm: Double?,
    val birthDateEpochDay: Long?,
    val sex: String?,
    val isProfileComplete: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun toDomain(): Account {
        val storedSex = sex?.let(Sex::valueOf)
        val storedBirthDate = birthDateEpochDay?.let(LocalDate::ofEpochDay)
        val profile = if (isProfileComplete) {
            AccountProfile.Complete(
                heightCm = requireNotNull(heightCm),
                birthDate = requireNotNull(storedBirthDate),
                sex = requireNotNull(storedSex),
            )
        } else {
            AccountProfile.IncompleteRecovery(
                heightCm = heightCm,
                birthDate = storedBirthDate,
                sex = storedSex,
            )
        }
        return Account(
            id = AccountId(id),
            displayName = displayName,
            normalizedName = normalizedName,
            profile = profile,
            createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
            updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
        )
    }
}
