package com.palixander.scalesync.data

import androidx.room.ColumnInfo
import com.palixander.scalesync.domain.MeasurementOrigin
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetMeasurement
import java.time.Instant

@Entity(
    tableName = "pet_measurements",
    foreignKeys = [
        ForeignKey(
            entity = PetEntity::class,
            parentColumns = ["id"],
            childColumns = ["petId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["petId", "measuredAtEpochSecond"])],
)
data class PetMeasurementEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val measuredAtEpochSecond: Long,
    val firstWeightKg: Double?,
    val secondWeightKg: Double?,
    val petWeightKg: Double,
    @ColumnInfo(defaultValue = "'LEGACY'")
    val origin: MeasurementOrigin = MeasurementOrigin.LEGACY,
) {
    fun toDomain(): PetMeasurement = PetMeasurement(
        id = id,
        petId = PetId(petId),
        measuredAt = Instant.ofEpochSecond(measuredAtEpochSecond),
        firstWeightKg = firstWeightKg,
        secondWeightKg = secondWeightKg,
        petWeightKg = petWeightKg,
        origin = origin,
    )
}
