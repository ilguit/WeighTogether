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
        val storedHeight = heightCm?.takeIf(Double::isFinite)
        val storedSex = sex?.let { value ->
            runCatching { Sex.valueOf(value) }.getOrNull()
        }
        val storedBirthDate = birthDateEpochDay?.let { epochDay ->
            runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull()
        }
        val completeProfile = if (isProfileComplete &&
            storedHeight != null &&
            storedBirthDate != null &&
            storedSex != null
        ) {
            runCatching {
                AccountProfile.Complete(
                    heightCm = storedHeight,
                    birthDate = storedBirthDate,
                    sex = storedSex,
                )
            }.getOrNull()
        } else {
            null
        }
        val profile = completeProfile ?: AccountProfile.IncompleteRecovery(
            heightCm = storedHeight,
            birthDate = storedBirthDate,
            sex = storedSex,
        )
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
