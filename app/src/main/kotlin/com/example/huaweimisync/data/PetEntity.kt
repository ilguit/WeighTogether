package com.example.huaweimisync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetId
import java.time.Instant

@Entity(
    tableName = "pets",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class PetEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val normalizedName: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun toDomain(): Pet = Pet(
        id = PetId(id),
        displayName = displayName,
        normalizedName = normalizedName,
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )
}
