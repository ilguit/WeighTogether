package com.palixander.scalesync.sync

import android.content.Context
import com.palixander.scalesync.BuildConfig
import com.huawei.hihealth.error.HiHealthError
import com.huawei.hihealth.listener.ResultCallback
import com.huawei.hihealthkit.auth.HiHealthAuth
import com.huawei.hihealthkit.auth.IDataAuthStatusListener
import com.huawei.hihealthkit.auth.HiHealthOpenPermissionType
import com.huawei.hihealthkit.auth.IAuthorizationListener
import com.huawei.hihealthkit.data.HiHealthData
import com.huawei.hihealthkit.data.HiHealthPointData
import com.huawei.hihealthkit.data.store.HiHealthDataStore
import com.huawei.hihealthkit.data.type.HiHealthPointType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

fun createHuaweiHealthGateway(context: Context): HuaweiHealthGateway =
    ExtendedHuaweiHealthGateway(context)

private class ExtendedHuaweiHealthGateway(
    private val context: Context,
) : HuaweiHealthGateway {
    private val permissionChecker = HuaweiWritePermissionChecker(
        SdkHuaweiDataAuthStatusApi(context),
    )

    override val isAvailableInBuild: Boolean = true
    override val isConfigured: Boolean get() = BuildConfig.HUAWEI_APP_ID != "0"

    override suspend fun checkWriteWeightPermission(): HuaweiPermissionCheckResult {
        if (!isConfigured) return HuaweiPermissionCheckResult.CHECK_FAILED
        return permissionChecker.check()
    }

    override suspend fun authorize(): SyncResult {
        if (!isConfigured) {
            return SyncResult.Blocked("Укажите HUAWEI_APP_ID после выдачи scope")
        }
        return awaitSingleHuaweiResult(
            synchronousFailure = SyncResult.Retryable(
                "Авторизация Huawei Health временно недоступна",
            ),
        ) { complete ->
            HiHealthAuth.requestAuthorization(
                context,
                intArrayOf(
                    HiHealthOpenPermissionType.HEALTH_OPEN_PERMISSION_TYPE_WRITE_DATA_SET_WEIGHT,
                ),
                intArrayOf(),
                object : IAuthorizationListener {
                    override fun onResult(resultCode: Int, data: Any?) {
                        complete(mapHuaweiResult("Авторизация", resultCode))
                    }
                },
            )
        }
    }

    override suspend fun write(payload: MeasurementSyncPayload): SyncResult {
        if (!isConfigured) {
            return SyncResult.Blocked("Huawei appId/scope ещё не настроены")
        }
        if (payload.isEmpty) return SyncResult.Success
        return awaitSingleHuaweiResult(
            synchronousFailure = SyncResult.Retryable(
                "Запись Huawei Health временно недоступна",
            ),
        ) { complete ->
            HiHealthDataStore.saveSamples(
                context,
                buildHuaweiPoints(payload),
                object : ResultCallback {
                    override fun onResult(resultCode: Int, data: Any?) {
                        complete(mapHuaweiResult("Запись", resultCode))
                    }
                },
            )
        }
    }

    private fun buildHuaweiPoints(payload: MeasurementSyncPayload): List<HiHealthData> {
        val time = payload.measurement.measuredAtEpochMillis
        return buildHuaweiPointSpecs(payload).map { spec ->
            HiHealthPointData(spec.type, time, time, spec.value, DEFAULT_UNIT).apply {
                metaData = spec.metadata
            }
        }
    }

    private fun mapHuaweiResult(operation: String, code: Int): SyncResult = when (code) {
        HiHealthError.SUCCESS -> SyncResult.Success
        HiHealthError.ERR_NETWORK,
        HiHealthError.ERR_HEALTH_SERVICE_DISCONNECTED,
        HiHealthError.ERR_API_EXCEPTION,
        -> SyncResult.Retryable("$operation Huawei Health временно недоступна, код $code")
        else -> SyncResult.Blocked("$operation Huawei Health отклонена, код $code")
    }

    private companion object {
        // The experimental adapter must be verified against Huawei's body-fat-scale sample and
        // a real approved scope before release. Huawei's current public API does not document
        // per-point unit constants on the reference page.
        const val DEFAULT_UNIT = 0
    }
}

internal data class HuaweiPointSpec(
    val type: Int,
    val value: Double,
    val externalId: String,
    val metadata: String = externalId,
)

internal fun buildHuaweiPointSpecs(payload: MeasurementSyncPayload): List<HuaweiPointSpec> {
    val value = payload.measurement
    fun point(type: Int, number: Double) = HuaweiPointSpec(
        type = type,
        value = number,
        externalId = "${value.id}:huawei:$type",
        metadata = "${value.id}:huawei:$type" +
            if (value.origin == com.palixander.scalesync.domain.MeasurementOrigin.MANUAL) ";origin=MANUAL" else "",
    )

    return buildList {
        if (payload.includesWeight) {
            add(point(HiHealthPointType.DATA_POINT_WEIGHT, value.weightKg))
        }
        payload.composition?.let { composition ->
            add(point(HiHealthPointType.DATA_POINT_WEIGHT_BMI, composition.bmi))
            add(point(HiHealthPointType.DATA_POINT_WEIGHT_BODYFAT, composition.bodyFatPercent))
            add(point(HiHealthPointType.DATA_POINT_WEIGHT_MOISTURERATE, composition.waterPercent))
            add(point(HiHealthPointType.DATA_POINT_WEIGHT_MOISTURE, composition.waterMassKg))
            add(point(HiHealthPointType.DATA_POINT_WEIGHT_MUSCLES, composition.muscleMassKg))
            add(
                point(
                    HiHealthPointType.DATA_POINT_WEIGHT_SKELETAL_MUSCLE_MASS,
                    composition.skeletalMuscleMassKg,
                ),
            )
            add(point(HiHealthPointType.DATA_POINT_WEIGHT_BONE_MINERAL, composition.boneMassKg))
            add(point(HiHealthPointType.DATA_POINT_WEIGHT_PROTEIN, composition.proteinPercent))
            add(
                point(
                    HiHealthPointType.DATA_POINT_WEIGHT_PROTEIN_VALUE,
                    composition.proteinMassKg,
                ),
            )
            add(point(HiHealthPointType.DATA_POINT_WEIGHT_FATLEVEL, composition.visceralFatLevel))
            add(point(HiHealthPointType.DATA_POINT_WEIGHT_BMR, composition.basalMetabolicRateKcal))
            add(
                point(
                    HiHealthPointType.DATA_POINT_WEIGHT_BODYAGE,
                    composition.metabolicAge.toDouble(),
                ),
            )
            add(
                point(
                    HiHealthPointType.DATA_POINT_WEIGHT_IMPEDANCE,
                    composition.impedanceOhm.toDouble(),
                ),
            )
        }
    }
}

internal fun interface HuaweiDataAuthStatusCallback {
    fun onResult(
        resultCode: Int,
        message: String?,
        grantedWriteTypes: IntArray?,
        grantedReadTypes: IntArray?,
    )
}

internal fun interface HuaweiDataAuthStatusApi {
    fun getDataAuthStatusEx(
        requestedWriteTypes: IntArray,
        requestedReadTypes: IntArray,
        callback: HuaweiDataAuthStatusCallback,
    )
}

private class SdkHuaweiDataAuthStatusApi(
    private val context: Context,
) : HuaweiDataAuthStatusApi {
    override fun getDataAuthStatusEx(
        requestedWriteTypes: IntArray,
        requestedReadTypes: IntArray,
        callback: HuaweiDataAuthStatusCallback,
    ) {
        HiHealthAuth.getDataAuthStatusEx(
            context,
            requestedWriteTypes,
            requestedReadTypes,
            object : IDataAuthStatusListener {
                override fun onResult(
                    resultCode: Int,
                    message: String?,
                    grantedWriteTypes: IntArray?,
                    grantedReadTypes: IntArray?,
                ) {
                    // HiHealthKitApi$3 reads the third callback argument from "writeTypes"
                    // and the fourth from "readTypes" in the 6.7.0.300 runtime bytecode.
                    callback.onResult(
                        resultCode,
                        message,
                        grantedWriteTypes,
                        grantedReadTypes,
                    )
                }
            },
        )
    }
}

internal class HuaweiWritePermissionChecker(
    private val api: HuaweiDataAuthStatusApi,
    private val timeoutMillis: Long = HUAWEI_CALLBACK_TIMEOUT_MILLIS,
) {
    suspend fun check(): HuaweiPermissionCheckResult = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            continuation.invokeOnCancellation { completed.set(true) }

            fun resumeOnce(result: HuaweiPermissionCheckResult) {
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(result)
                }
            }

            try {
                api.getDataAuthStatusEx(
                    requestedWriteTypes = intArrayOf(REQUIRED_WRITE_WEIGHT_PERMISSION),
                    requestedReadTypes = intArrayOf(),
                    callback = HuaweiDataAuthStatusCallback {
                            resultCode,
                            _,
                            grantedWriteTypes,
                            grantedReadTypes,
                        ->
                        resumeOnce(
                            mapHuaweiDataAuthStatus(
                                resultCode = resultCode,
                                grantedWriteTypes = grantedWriteTypes,
                                grantedReadTypes = grantedReadTypes,
                            ),
                        )
                    },
                )
            } catch (_: Exception) {
                resumeOnce(HuaweiPermissionCheckResult.CHECK_FAILED)
            }
        }
    } ?: HuaweiPermissionCheckResult.CHECK_FAILED
}

/** Makes Huawei's callback-only authorization API safe for cancellation and bad SDK callbacks. */
internal suspend fun awaitSingleHuaweiResult(
    synchronousFailure: SyncResult,
    timeoutMillis: Long = HUAWEI_CALLBACK_TIMEOUT_MILLIS,
    register: (complete: (SyncResult) -> Unit) -> Unit,
): SyncResult = withTimeoutOrNull(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        continuation.invokeOnCancellation { completed.set(true) }

        fun resumeOnce(result: SyncResult) {
            if (completed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(result)
            }
        }

        try {
            register(::resumeOnce)
        } catch (_: Exception) {
            resumeOnce(synchronousFailure)
        }
    }
} ?: synchronousFailure

internal const val HUAWEI_CALLBACK_TIMEOUT_MILLIS = 30_000L

internal fun mapHuaweiDataAuthStatus(
    resultCode: Int,
    grantedWriteTypes: IntArray?,
    @Suppress("UNUSED_PARAMETER") grantedReadTypes: IntArray?,
): HuaweiPermissionCheckResult = when {
    resultCode != HiHealthError.SUCCESS -> HuaweiPermissionCheckResult.CHECK_FAILED
    grantedWriteTypes == null -> HuaweiPermissionCheckResult.CHECK_FAILED
    REQUIRED_WRITE_WEIGHT_PERMISSION in grantedWriteTypes -> HuaweiPermissionCheckResult.AUTHORIZED
    else -> HuaweiPermissionCheckResult.NOT_AUTHORIZED
}

internal const val REQUIRED_WRITE_WEIGHT_PERMISSION =
    HiHealthOpenPermissionType.HEALTH_OPEN_PERMISSION_TYPE_WRITE_DATA_SET_WEIGHT
