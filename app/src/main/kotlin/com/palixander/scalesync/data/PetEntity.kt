package com.palixander.scalesync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSpecies
import java.time.Instant

@Entity(
    tableName = "pets",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class PetEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val normalizedName: String,
    val species: PetSpecies,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun toDomain(): Pet = Pet(
        id = PetId(id),
        displayName = displayName,
        normalizedName = normalizedName,
        species = species,
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )
}
