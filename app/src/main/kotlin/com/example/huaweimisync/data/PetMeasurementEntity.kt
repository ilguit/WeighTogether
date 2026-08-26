package com.example.huaweimisync.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetMeasurement
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
    val firstWeightKg: Double,
    val secondWeightKg: Double,
    val petWeightKg: Double,
) {
    fun toDomain(): PetMeasurement = PetMeasurement(
        id = id,
        petId = PetId(petId),
        measuredAt = Instant.ofEpochSecond(measuredAtEpochSecond),
        firstWeightKg = firstWeightKg,
        secondWeightKg = secondWeightKg,
    ).also {
        check(it.petWeightKg == petWeightKg) { "Stored pet weight does not match source readings" }
    }
}
