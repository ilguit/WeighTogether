package com.palixander.scalesync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
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
    val photoPath: String? = null,
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
            photoPath = photoPath,
        )
    }
}
