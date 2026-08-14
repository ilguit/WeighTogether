package com.example.huaweimisync.sync

import android.content.Context
import com.example.huaweimisync.BuildConfig
import com.example.huaweimisync.data.MeasurementEntity
import com.huawei.hihealth.error.HiHealthError
import com.huawei.hihealth.listener.ResultCallback
import com.huawei.hihealthkit.auth.HiHealthAuth
import com.huawei.hihealthkit.auth.HiHealthOpenPermissionType
import com.huawei.hihealthkit.auth.IAuthorizationListener
import com.huawei.hihealthkit.data.HiHealthData
import com.huawei.hihealthkit.data.HiHealthPointData
import com.huawei.hihealthkit.data.store.HiHealthDataStore
import com.huawei.hihealthkit.data.type.HiHealthPointType
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

fun createHuaweiHealthGateway(context: Context): HuaweiHealthGateway =
    ExtendedHuaweiHealthGateway(context)

private class ExtendedHuaweiHealthGateway(
    private val context: Context,
) : HuaweiHealthGateway {
    override val isAvailableInBuild: Boolean = true
    override val isConfigured: Boolean get() = BuildConfig.HUAWEI_APP_ID != "0"

    override suspend fun authorize(): SyncResult = suspendCancellableCoroutine { continuation ->
        if (!isConfigured) {
            continuation.resume(SyncResult.Blocked("Укажите HUAWEI_APP_ID после выдачи scope"))
            return@suspendCancellableCoroutine
        }
        HiHealthAuth.requestAuthorization(
            context,
            intArrayOf(HiHealthOpenPermissionType.HEALTH_OPEN_PERMISSION_TYPE_WRITE_DATA_SET_WEIGHT),
            intArrayOf(),
            object : IAuthorizationListener {
                override fun onResult(resultCode: Int, data: Any?) {
                    if (continuation.isActive) {
                        continuation.resume(mapHuaweiResult("Авторизация", resultCode))
                    }
                }
            },
        )
    }

    override suspend fun write(measurement: MeasurementEntity): SyncResult =
        suspendCancellableCoroutine { continuation ->
            if (!isConfigured) {
                continuation.resume(SyncResult.Blocked("Huawei appId/scope ещё не настроены"))
                return@suspendCancellableCoroutine
            }
            HiHealthDataStore.saveSamples(
                context,
                buildPoints(measurement),
                object : ResultCallback {
                    override fun onResult(resultCode: Int, data: Any?) {
                        if (continuation.isActive) {
                            continuation.resume(mapHuaweiResult("Запись", resultCode))
                        }
                    }
                },
            )
        }

    private fun buildPoints(value: MeasurementEntity): List<HiHealthData> {
        val time = value.measuredAtEpochMillis
        fun point(type: Int, number: Double): HiHealthData =
            HiHealthPointData(type, time, time, number, DEFAULT_UNIT)

        return listOf(
            point(HiHealthPointType.DATA_POINT_WEIGHT, value.weightKg),
            point(HiHealthPointType.DATA_POINT_WEIGHT_BMI, value.bmi),
            point(HiHealthPointType.DATA_POINT_WEIGHT_BODYFAT, value.bodyFatPercent),
            point(HiHealthPointType.DATA_POINT_WEIGHT_MOISTURERATE, value.waterPercent),
            point(HiHealthPointType.DATA_POINT_WEIGHT_MOISTURE, value.waterMassKg),
            point(HiHealthPointType.DATA_POINT_WEIGHT_MUSCLES, value.muscleMassKg),
            point(HiHealthPointType.DATA_POINT_WEIGHT_SKELETAL_MUSCLE_MASS, value.skeletalMuscleMassKg),
            point(HiHealthPointType.DATA_POINT_WEIGHT_BONE_MINERAL, value.boneMassKg),
            point(HiHealthPointType.DATA_POINT_WEIGHT_PROTEIN, value.proteinPercent),
            point(HiHealthPointType.DATA_POINT_WEIGHT_PROTEIN_VALUE, value.proteinMassKg),
            point(HiHealthPointType.DATA_POINT_WEIGHT_FATLEVEL, value.visceralFatLevel),
            point(HiHealthPointType.DATA_POINT_WEIGHT_BMR, value.basalMetabolicRateKcal),
            point(HiHealthPointType.DATA_POINT_WEIGHT_BODYAGE, value.metabolicAge.toDouble()),
            point(HiHealthPointType.DATA_POINT_WEIGHT_IMPEDANCE, value.impedanceOhm.toDouble()),
        )
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
