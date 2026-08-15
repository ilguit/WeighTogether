package com.example.huaweimisync.worker

import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.data.AccountEntity
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.domain.ExternalSyncPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementSyncEligibilityPolicyTest {
    @Test
    fun currentPrimaryWithAutoPolicyAndCompleteProfileIsEligible() = runPolicyTest {
        assertTrue(policy(completeAccount()) { loadCount += 1 }.isEligible(measurement()))
        assertEquals(1, loadCount)
    }

    @Test
    fun nonAutoPoliciesAreRejectedBeforeLoadingPrimary() = runPolicyTest {
        listOf(ExternalSyncPolicy.ACCOUNT_LOCAL, ExternalSyncPolicy.USER_LOCAL).forEach { syncPolicy ->
            assertFalse(
                policy(completeAccount()) { loadCount += 1 }.isEligible(
                    measurement().copy(externalSyncPolicy = syncPolicy.name),
                ),
            )
        }
        assertEquals(0, loadCount)
    }

    @Test
    fun formerPrimaryIsRejectedFromTheFreshPrimarySnapshot() = runPolicyTest {
        assertFalse(
            policy(completeAccount(id = "new-primary")).isEligible(
                measurement(accountId = "former-primary"),
            ),
        )
    }

    @Test
    fun profileFlagAloneCannotMakeCorruptPrimaryEligible() = runPolicyTest {
        val corruptPrimary = completeAccount().copy(
            birthDateEpochDay = null,
            isProfileComplete = true,
        )

        assertFalse(policy(corruptPrimary).isEligible(measurement()))
    }

    @Test
    fun explicitlyIncompleteRecoveryPrimaryIsRejected() = runPolicyTest {
        val incompletePrimary = completeAccount().copy(isProfileComplete = false)

        assertFalse(policy(incompletePrimary).isEligible(measurement()))
    }

    private fun runPolicyTest(block: suspend PolicyTestScope.() -> Unit) =
        runBlocking { PolicyTestScope().block() }

    private class PolicyTestScope {
        var loadCount: Int = 0

        fun policy(
            primary: AccountEntity?,
            onLoad: () -> Unit = {},
        ) = MeasurementSyncEligibilityPolicy {
            onLoad()
            primary
        }
    }
}

private fun completeAccount(id: String = "primary") = AccountEntity(
    id = id,
    displayName = "Primary",
    normalizedName = "primary",
    heightCm = 175.0,
    birthDateEpochDay = 7_305L,
    sex = Sex.MALE.name,
    isProfileComplete = true,
    createdAtEpochMillis = 1_000L,
    updatedAtEpochMillis = 1_000L,
)

private fun measurement(accountId: String = "primary") = MeasurementEntity(
    id = "measurement-1",
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAtEpochMillis = 1_754_912_096_000,
    rawPayloadHex = "00",
    weightKg = 70.0,
    impedanceOhm = 500,
    bmi = 22.9,
    bodyFatPercent = 18.0,
    bodyFatMassKg = 12.6,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 52.0,
    skeletalMuscleMassKg = 28.0,
    boneMassKg = 3.0,
    proteinPercent = 19.0,
    proteinMassKg = 13.3,
    visceralFatLevel = 8.0,
    basalMetabolicRateKcal = 1_650.0,
    metabolicAge = 35,
    leanBodyMassKg = 57.4,
    algorithmVersion = "test",
    accountId = accountId,
    externalSyncPolicy = ExternalSyncPolicy.AUTO.name,
)
