package com.palixander.weightogether.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palixander.weightogether.domain.DEFAULT_WEIGHT_DELTA_KG

@Entity(
    tableName = "app_state",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["primaryAccountId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("primaryAccountId")],
)
data class AppStateEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val primaryAccountId: String? = null,
    val weightDeltaKg: Double = DEFAULT_WEIGHT_DELTA_KG,
    @ColumnInfo(defaultValue = "0")
    val ignoreUnknownMeasurements: Boolean = false,
) {
    init {
        require(singletonId == SINGLETON_ID) { "App state must use the singleton id" }
    }

    companion object {
        const val SINGLETON_ID: Int = 1
    }
}
