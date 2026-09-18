package com.palixander.scalesync.data

import androidx.room.withTransaction
import com.palixander.scalesync.core.BodyCompositionCalculator
import com.palixander.scalesync.core.RawScaleMeasurement
import com.palixander.scalesync.core.UserProfile
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.AccountRepository
import com.palixander.scalesync.domain.AccountSettings
import com.palixander.scalesync.domain.AccountSettingsWriter
import com.palixander.scalesync.domain.AccountUpdate
import com.palixander.scalesync.domain.NewAccount
import com.palixander.scalesync.domain.PrimaryHistorySyncMode
import com.palixander.scalesync.domain.ProfileHistoryUpdateMode
import com.palixander.scalesync.domain.ProfileUpdateAttemptResult
import com.palixander.scalesync.domain.WEIGHT_DELTA_KG_RANGE
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomAccountRepository(
    private val database: AppDatabase,
    private val accountDao: AccountDao = database.accountDao(),
    private val appStateDao: AppStateDao = database.appStateDao(),
    private val measurementDao: MultiAccountMeasurementDao = database.multiAccountMeasurementDao(),
    private val calculator: BodyCompositionCalculator = BodyCompositionCalculator(),
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val photoLifecycle: ProfilePhotoLifecycle = ProfilePhotoLifecycle.None,
) : AccountRepository, AccountSettingsWriter {
    override fun observeAccounts(): Flow<List<Account>> = accountDao.observeAll().map { accounts ->
        accounts.map(AccountEntity::toDomain)
    }

    override fun observeSettings(): Flow<AccountSettings> = appStateDao.observe().map { state ->
        state?.toDomain() ?: AccountSettings()
    }

    override suspend fun getAccount(id: AccountId): Account? = accountDao.get(id.value)?.toDomain()

    override suspend fun createAccount(account: NewAccount): Account = database.withTransaction {
        ensureAppState()
        accountDao.getByNormalizedName(account.normalizedName)?.let {
            throw AccountNameConflictException(account.normalizedName)
        }
        val timestamp = now()
        val entity = account.toEntity(
            id = newId(),
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        if (accountDao.insert(entity) == -1L) {
            throw AccountNameConflictException(account.normalizedName)
        }
        if (accountDao.count() == 1) {
            check(appStateDao.setPrimary(entity.id) == 1) { "App state singleton is missing" }
        }
        entity.toDomain()
    }

    override suspend fun hasProfileRecalculationCandidates(accountId: AccountId): Boolean =
        measurementDao.hasProfileRecalculationCandidates(accountId.value)

    override suspend fun attemptProfileUpdate(
        account: AccountUpdate,
    ): ProfileUpdateAttemptResult {
        val (result, dereferencedPhotoPath) = database.withTransaction {
            val current = accountDao.get(account.id.value)
                ?: throw AccountNotFoundException(account.id)
            val updated = account.toEntity(current, now())
            if (current.requiresProfileRecalculation(updated) &&
                measurementDao.hasProfileRecalculationCandidates(current.id)
            ) {
                return@withTransaction ProfileUpdateAttemptResult.ConfirmationRequired to null
            }
            saveAccountLocked(account, current, updated)
            ProfileUpdateAttemptResult.Saved(updated.toDomain()) to current.replacedPhotoPath(updated)
        }
        dereferencedPhotoPath?.let { photoLifecycle.onPhotoDereferenced(it) }
        return result
    }

    override suspend fun updateAccount(
        account: AccountUpdate,
        historyUpdateMode: ProfileHistoryUpdateMode,
    ): Account {
        val (saved, dereferencedPhotoPath) = database.withTransaction {
            val current = accountDao.get(account.id.value)
                ?: throw AccountNotFoundException(account.id)
            val updated = account.toEntity(current, now())
            if (current.requiresProfileRecalculation(updated) &&
                historyUpdateMode == ProfileHistoryUpdateMode.RECALCULATE
            ) {
                val profile = account.profile.toUserProfile()
                measurementDao.getProfileRecalculationCandidates(current.id).forEach { measurement ->
                    val recalculated = measurement
                        .backfillMissingSyncedCalculatedValues()
                        .recalculate(calculator, profile)
                    check(measurementDao.update(recalculated) == 1) {
                        "Measurement ${measurement.id} disappeared during profile recalculation"
                    }
                }
            }
            saveAccountLocked(account, current, updated)
            updated.toDomain() to current.replacedPhotoPath(updated)
        }
        dereferencedPhotoPath?.let { photoLifecycle.onPhotoDereferenced(it) }
        return saved
    }

    override suspend fun setPrimaryAccount(
        accountId: AccountId,
        historySyncMode: PrimaryHistorySyncMode,
    ) {
        database.withTransaction {
            ensureAppState()
            if (accountDao.get(accountId.value) == null) throw AccountNotFoundException(accountId)
            val previousPrimaryId = appStateDao.get()?.primaryAccountId
            if (previousPrimaryId != null && previousPrimaryId != accountId.value) {
                measurementDao.demoteUnfinishedHistory(previousPrimaryId)
            }
            check(appStateDao.setPrimary(accountId.value) == 1) { "App state singleton is missing" }
            if (historySyncMode == PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY) {
                measurementDao.promoteEligibleHistory(accountId.value)
            }
        }
    }

    override suspend fun deleteAccount(accountId: AccountId) {
        val photoPath = database.withTransaction {
            ensureAppState()
            val state = requireNotNull(appStateDao.get())
            if (state.primaryAccountId == accountId.value) {
                throw PrimaryAccountReplacementRequiredException(accountId)
            }
            val deleted = accountDao.get(accountId.value)
                ?: throw AccountNotFoundException(accountId)
            if (accountDao.delete(accountId.value) != 1) throw AccountNotFoundException(accountId)
            deleted.photoPath
        }
        photoPath?.let { photoLifecycle.onPhotoDereferenced(it) }
    }

    override suspend fun deletePrimaryWithReplacement(
        primaryAccountId: AccountId,
        replacementAccountId: AccountId?,
        historySyncMode: PrimaryHistorySyncMode,
    ) {
        val photoPath = database.withTransaction {
            ensureAppState()
            val state = requireNotNull(appStateDao.get())
            if (state.primaryAccountId != primaryAccountId.value) {
                throw PrimaryAccountChangedException(primaryAccountId)
            }
            if (replacementAccountId == primaryAccountId) {
                throw IllegalArgumentException("Replacement account must differ from the deleted account")
            }
            val accountCount = accountDao.count()
            if (replacementAccountId == null && accountCount > 1) {
                throw PrimaryAccountReplacementRequiredException(primaryAccountId)
            }
            if (replacementAccountId != null && accountDao.get(replacementAccountId.value) == null) {
                throw AccountNotFoundException(replacementAccountId)
            }

            check(appStateDao.setPrimary(replacementAccountId?.value) == 1) {
                "App state singleton is missing"
            }
            if (replacementAccountId != null &&
                historySyncMode == PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY
            ) {
                measurementDao.promoteEligibleHistory(replacementAccountId.value)
            }
            val deleted = accountDao.get(primaryAccountId.value)
                ?: throw AccountNotFoundException(primaryAccountId)
            if (accountDao.delete(primaryAccountId.value) != 1) {
                throw AccountNotFoundException(primaryAccountId)
            }
            deleted.photoPath
        }
        photoPath?.let { photoLifecycle.onPhotoDereferenced(it) }
    }

    override suspend fun updateWeightDeltaKg(weightDeltaKg: Double) {
        require(weightDeltaKg.isFinite() && weightDeltaKg in WEIGHT_DELTA_KG_RANGE) {
            "Weight delta must be between 0.1 and 50.0 kg"
        }
        database.withTransaction {
            ensureAppState()
            check(appStateDao.setWeightDelta(weightDeltaKg) == 1) {
                "App state singleton is missing"
            }
        }
    }

    override suspend fun updateIgnoreUnknownMeasurements(enabled: Boolean) {
        database.withTransaction {
            ensureAppState()
            check(appStateDao.setIgnoreUnknownMeasurements(enabled) == 1) {
                "App state singleton is missing"
            }
        }
    }

    private suspend fun ensureAppState() {
        appStateDao.insertDefault()
    }

    private suspend fun saveAccountLocked(
        account: AccountUpdate,
        current: AccountEntity,
        updated: AccountEntity,
    ) {
        accountDao.getByNormalizedName(account.normalizedName)
            ?.takeIf { it.id != current.id }
            ?.let { throw AccountNameConflictException(account.normalizedName) }
        if (accountDao.update(updated) != 1) throw AccountNotFoundException(account.id)
    }
}

class AccountNameConflictException(val normalizedName: String) :
    IllegalArgumentException("An account named '$normalizedName' already exists")

class AccountNotFoundException(val accountId: AccountId) :
    NoSuchElementException("Account ${accountId.value} does not exist")

class PrimaryAccountReplacementRequiredException(val accountId: AccountId) :
    IllegalStateException("Primary account ${accountId.value} requires an explicit replacement")

class PrimaryAccountChangedException(val expectedAccountId: AccountId) :
    IllegalStateException("Account ${expectedAccountId.value} is no longer primary")

private fun AppStateEntity.toDomain(): AccountSettings = AccountSettings(
    primaryAccountId = primaryAccountId?.let(::AccountId),
    weightDeltaKg = weightDeltaKg,
    ignoreUnknownMeasurements = ignoreUnknownMeasurements,
)

private fun NewAccount.toEntity(
    id: String,
    createdAt: Instant,
    updatedAt: Instant,
): AccountEntity = AccountEntity(
    id = id,
    displayName = displayName,
    normalizedName = normalizedName,
    heightCm = profile.heightCm,
    birthDateEpochDay = profile.birthDate.toEpochDay(),
    sex = profile.sex.name,
    isProfileComplete = true,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
    photoPath = photoPath,
)

private fun AccountUpdate.toEntity(current: AccountEntity, updatedAt: Instant): AccountEntity =
    current.copy(
        displayName = displayName,
        normalizedName = normalizedName,
        heightCm = profile.heightCm,
        birthDateEpochDay = profile.birthDate.toEpochDay(),
        sex = profile.sex.name,
        isProfileComplete = true,
        updatedAtEpochMillis = updatedAt.toEpochMilli(),
        photoPath = photoPath,
    )

private fun AccountEntity.replacedPhotoPath(updated: AccountEntity): String? =
    photoPath?.takeIf { it != updated.photoPath }

private fun AccountEntity.requiresProfileRecalculation(updated: AccountEntity): Boolean =
    !isProfileComplete ||
        heightCm != updated.heightCm ||
        birthDateEpochDay != updated.birthDateEpochDay ||
        sex != updated.sex

private fun AccountProfile.Complete.toUserProfile(): UserProfile =
    UserProfile(
        heightCm = heightCm,
        birthDate = birthDate,
        sex = sex,
    )

internal fun MeasurementEntity.recalculate(
    calculator: BodyCompositionCalculator,
    profile: UserProfile,
): MeasurementEntity {
    if (measurementType == MeasurementType.WEIGHT_ONLY) {
        return copy(
            ratingHeightCm = profile.heightCm,
            ratingHeightOrigin = RatingHeightOrigin.CAPTURED,
        )
    }
    val composition = calculator.calculate(
        raw = RawScaleMeasurement(
            deviceAddress = deviceAddress,
            measuredAt = measuredAt,
            weightKg = weightKg,
            impedanceOhm = requireNotNull(impedanceOhm) {
                "Full measurement $id has no impedance"
            },
            isStable = true,
            hasImpedance = true,
            // The calculator does not interpret the packet. Keep the persisted payload untouched.
            rawPayload = byteArrayOf(),
            rawWeight = rawWeight,
        ),
        profile = profile,
    )
    return copy(
        bmi = composition.bmi,
        bodyFatPercent = composition.bodyFatPercent,
        bodyFatMassKg = composition.bodyFatMassKg,
        waterPercent = composition.waterPercent,
        waterMassKg = composition.waterMassKg,
        muscleMassKg = composition.muscleMassKg,
        skeletalMuscleMassKg = composition.skeletalMuscleMassKg,
        boneMassKg = composition.boneMassKg,
        proteinPercent = composition.proteinPercent,
        proteinMassKg = composition.proteinMassKg,
        visceralFatLevel = composition.visceralFatLevel,
        basalMetabolicRateKcal = composition.basalMetabolicRateKcal,
        metabolicAge = composition.metabolicAge,
        leanBodyMassKg = composition.leanBodyMassKg,
        algorithmVersion = composition.algorithmVersion,
        ratingHeightCm = profile.heightCm,
        ratingHeightOrigin = RatingHeightOrigin.CAPTURED,
    )
}
