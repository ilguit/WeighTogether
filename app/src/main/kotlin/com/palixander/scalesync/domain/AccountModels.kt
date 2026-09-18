package com.palixander.scalesync.domain

import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.core.UserProfile
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotBlank()) { "Account id must not be blank" }
    }

    override fun toString(): String = value
}

sealed interface AccountProfile {
    val heightCm: Double?
    val birthDate: LocalDate?
    val sex: Sex?

    data class Complete(
        override val heightCm: Double,
        override val birthDate: LocalDate,
        override val sex: Sex,
    ) : AccountProfile {
        init {
            UserProfile(heightCm, birthDate, sex)
        }
    }

    /**
     * A migration-only profile which preserves every readable legacy field. It may be observed
     * and edited, but must not be used to calculate a body composition or to enable external sync.
     */
    data class IncompleteRecovery(
        override val heightCm: Double? = null,
        override val birthDate: LocalDate? = null,
        override val sex: Sex? = null,
    ) : AccountProfile {
        init {
            require(heightCm == null || heightCm.isFinite()) {
                "Recovered height must be finite when present"
            }
        }
    }
}

val AccountProfile.isComplete: Boolean
    get() = this is AccountProfile.Complete

fun AccountProfile.toUserProfileOrNull(): UserProfile? = when (this) {
    is AccountProfile.Complete -> UserProfile(heightCm, birthDate, sex)
    is AccountProfile.IncompleteRecovery -> null
}

fun UserProfile.toAccountProfile(): AccountProfile.Complete = AccountProfile.Complete(
    heightCm = heightCm,
    birthDate = birthDate,
    sex = sex,
)

data class Account(
    val id: AccountId,
    val displayName: String,
    val normalizedName: String = normalizeAccountName(displayName),
    val profile: AccountProfile,
    val createdAt: Instant,
    val updatedAt: Instant,
    val photoPath: String? = null,
) {
    init {
        require(displayName == displayName.trim()) { "Account display name must be trimmed" }
        require(displayName.length in ACCOUNT_NAME_LENGTH) {
            "Account display name must contain 1 to 50 characters"
        }
        require(normalizedName == normalizeAccountName(displayName)) {
            "Normalized name must match the display name"
        }
        require(!updatedAt.isBefore(createdAt)) { "Updated time cannot precede created time" }
        validateManagedProfilePhotoPath(photoPath)
    }
}

data class NewAccount(
    val displayName: String,
    val profile: AccountProfile.Complete,
    val photoPath: String? = null,
) {
    init {
        require(displayName == displayName.trim()) { "Account display name must be trimmed" }
        require(displayName.length in ACCOUNT_NAME_LENGTH) {
            "Account display name must contain 1 to 50 characters"
        }
        validateManagedProfilePhotoPath(photoPath)
    }

    val normalizedName: String = normalizeAccountName(displayName)
}

data class AccountUpdate(
    val id: AccountId,
    val displayName: String,
    val profile: AccountProfile.Complete,
    val photoPath: String? = null,
) {
    init {
        require(displayName == displayName.trim()) { "Account display name must be trimmed" }
        require(displayName.length in ACCOUNT_NAME_LENGTH) {
            "Account display name must contain 1 to 50 characters"
        }
        validateManagedProfilePhotoPath(photoPath)
    }

    val normalizedName: String = normalizeAccountName(displayName)
}

data class AccountSettings(
    val primaryAccountId: AccountId? = null,
    val weightDeltaKg: Double = DEFAULT_WEIGHT_DELTA_KG,
    val ignoreUnknownMeasurements: Boolean = false,
) {
    init {
        require(weightDeltaKg.isFinite() && weightDeltaKg in WEIGHT_DELTA_KG_RANGE) {
            "Weight delta must be between 0.1 and 50.0 kg"
        }
    }
}

enum class ExternalSyncPolicy {
    AUTO,
    ACCOUNT_LOCAL,
    USER_LOCAL,
}

enum class PrimaryHistorySyncMode {
    FUTURE_ONLY,
    INCLUDE_ELIGIBLE_HISTORY,
}

fun normalizeAccountName(displayName: String): String =
    displayName.trim().lowercase(Locale.ROOT)

val ACCOUNT_NAME_LENGTH: IntRange = 1..50
val WEIGHT_DELTA_KG_RANGE: ClosedFloatingPointRange<Double> = 0.1..50.0
const val DEFAULT_WEIGHT_DELTA_KG: Double = 3.0

/** Ensures profile photos only reference files owned by the app's managed media store. */
fun validateManagedProfilePhotoPath(path: String?) {
    if (path == null) return
    require(path.isNotBlank()) { "Profile photo path must not be blank" }
    require(!path.startsWith('/') && !path.startsWith('\\')) {
        "Profile photo path must be relative"
    }
    require('\\' !in path && ':' !in path) {
        "Profile photo path must not be a URI or use platform-specific separators"
    }
    require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "Profile photo path must contain only managed relative path segments"
    }
}
