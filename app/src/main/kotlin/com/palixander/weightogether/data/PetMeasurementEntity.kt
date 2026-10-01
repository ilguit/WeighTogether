package com.palixander.weightogether.data

import androidx.room.ColumnInfo
import com.palixander.weightogether.domain.MeasurementOrigin
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palixander.weightogether.domain.PetId
import com.palixander.weightogether.domain.PetMeasurement
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
    @ColumnInfo(defaultValue = "0")
    val isManuallyEdited: Boolean = false,
) {
    fun toDomain(): PetMeasurement = PetMeasurement(
        id = id,
        petId = PetId(petId),
        measuredAt = Instant.ofEpochSecond(measuredAtEpochSecond),
        firstWeightKg = firstWeightKg,
        secondWeightKg = secondWeightKg,
        petWeightKg = petWeightKg,
        origin = origin,
        isManuallyEdited = isManuallyEdited,
    )
}
