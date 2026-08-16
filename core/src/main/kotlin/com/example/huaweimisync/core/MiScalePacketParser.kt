package com.example.huaweimisync.core

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Parser for the 13-byte Body Composition service payload broadcast by XMTZC05HM. */
class MiScalePacketParser(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun parse(
        advertisedServiceData: ByteArray,
        deviceAddress: String,
        receivedAt: Instant = Instant.now(),
    ): RawScaleMeasurement? {
        if (advertisedServiceData.size < PAYLOAD_SIZE) return null

        // Some Android BLE stacks include bytes preceding the actual Xiaomi payload.
        val data = advertisedServiceData.copyOfRange(
            advertisedServiceData.size - PAYLOAD_SIZE,
            advertisedServiceData.size,
        )
        val flags = data[1].unsigned
        val rawWeight = littleEndianUnsignedShort(data[11], data[12])
        val impedance = littleEndianUnsignedShort(data[9], data[10])
        val weightKg = rawWeight * KG_RESOLUTION
        if (!weightKg.isFinite() || weightKg !in 0.0..500.0) return null

        return RawScaleMeasurement(
            deviceAddress = deviceAddress.uppercase(),
            measuredAt = parseScaleTime(data) ?: receivedAt,
            weightKg = weightKg,
            impedanceOhm = impedance,
            isStable = flags and STABLE_FLAG != 0,
            hasImpedance = flags and IMPEDANCE_FLAG != 0 && impedance > 0,
            rawPayload = data,
            rawWeight = rawWeight,
        )
    }

    private fun parseScaleTime(data: ByteArray): Instant? {
        val year = littleEndianUnsignedShort(data[2], data[3])
        if (year !in 2015..2100) return null

        return try {
            LocalDateTime.of(
                year,
                data[4].unsigned,
                data[5].unsigned,
                data[6].unsigned,
                data[7].unsigned,
                data[8].unsigned,
            ).atZone(zoneId).toInstant()
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun littleEndianUnsignedShort(low: Byte, high: Byte): Int =
        low.unsigned or (high.unsigned shl 8)

    private val Byte.unsigned: Int
        get() = toInt() and 0xff

    private companion object {
        const val PAYLOAD_SIZE = 13
        const val KG_RESOLUTION = RawScaleMeasurement.WEIGHT_RESOLUTION_KG
        const val STABLE_FLAG = 0x20
        const val IMPEDANCE_FLAG = 0x02
    }
}
