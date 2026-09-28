package com.palixander.scalesync.data

import androidx.room.withTransaction
import com.palixander.scalesync.domain.WeighingReminderId
import com.palixander.scalesync.domain.WeighingReminderImportance
import com.palixander.scalesync.domain.WeighingReminderOwner
import com.palixander.scalesync.domain.WeighingReminderSchedule
import com.palixander.scalesync.domain.toWeekdayMask
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WeighingReminderDraft(
    val owner: WeighingReminderOwner,
    val time: LocalTime,
    val weekdays: Set<DayOfWeek>,
    val importance: WeighingReminderImportance,
    val enabled: Boolean = true,
) {
    init {
        require(weekdays.isNotEmpty())
    }
}

sealed interface SaveWeighingReminderResult {
    data class Saved(val schedule: WeighingReminderSchedule) : SaveWeighingReminderResult
    data object OwnerUnavailable : SaveWeighingReminderResult
    data object Duplicate : SaveWeighingReminderResult
    data object NotFound : SaveWeighingReminderResult
}

enum class ReminderCallbackKind { REGULAR, SNOOZE }

sealed interface ReminderClaimResult {
    data class Publish(val occurrenceToken: String) : ReminderClaimResult
    data object NoOp : ReminderClaimResult
}

data class WeighingReminderSnapshot(
    val schedule: WeighingReminderSchedule,
    val generation: Long,
    val regularOccurrenceToken: String?,
    val regularDueEpochMillis: Long?,
    val regularStatus: ReminderOccurrenceStatus,
    val activeOccurrenceToken: String?,
    val snoozeOccurrenceToken: String?,
    val snoozeDueEpochMillis: Long?,
    val snoozeStatus: ReminderSnoozeStatus,
)

class RoomWeighingReminderRepository(
    private val database: AppDatabase,
    private val dao: WeighingReminderDao = database.weighingReminderDao(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    fun observe(owner: WeighingReminderOwner): Flow<List<WeighingReminderSchedule>> {
        val (type, id) = owner.storageIdentity()
        return dao.observe(type, id).map { rows -> rows.map(WeighingReminderScheduleEntity::toDomain) }
    }

    suspend fun get(id: WeighingReminderId): WeighingReminderSchedule? =
        serializedTransaction { dao.get(id.value)?.toDomain() }

    suspend fun snapshot(id: WeighingReminderId): WeighingReminderSnapshot? = serializedTransaction {
        val schedule = dao.get(id.value) ?: return@serializedTransaction null
        val runtime = dao.getRuntime(id.value) ?: return@serializedTransaction null
        schedule.toDomain().withRuntime(runtime)
    }

    suspend fun ownerDisplayName(owner: WeighingReminderOwner): String? = serializedTransaction {
        when (owner) {
            is WeighingReminderOwner.Account -> database.accountDao().get(owner.id.value)?.displayName
            is WeighingReminderOwner.Pet -> database.petDao().getPet(owner.id.value)?.displayName
        }
    }

    suspend fun snapshotEnabled(): List<WeighingReminderSnapshot> = serializedTransaction {
        dao.getEnabled().map { schedule ->
            val runtime = requireNotNull(dao.getRuntime(schedule.id))
            schedule.toDomain().withRuntime(runtime)
        }
    }

    suspend fun create(draft: WeighingReminderDraft): SaveWeighingReminderResult = serializedTransaction {
        if (!ownerExists(draft.owner)) return@serializedTransaction SaveWeighingReminderResult.OwnerUnavailable
        val now = nowEpochMillis()
        val entity = draft.toEntity(newId(), now, now)
        if (dao.insert(entity) == -1L) return@serializedTransaction SaveWeighingReminderResult.Duplicate
        dao.insertRuntime(WeighingReminderRuntimeEntity(scheduleId = entity.id))
        SaveWeighingReminderResult.Saved(entity.toDomain())
    }

    suspend fun update(id: WeighingReminderId, draft: WeighingReminderDraft): SaveWeighingReminderResult =
        serializedTransaction {
            val current = dao.get(id.value) ?: return@serializedTransaction SaveWeighingReminderResult.NotFound
            if (!ownerExists(draft.owner)) return@serializedTransaction SaveWeighingReminderResult.OwnerUnavailable
            val updated = draft.toEntity(id.value, current.createdAtEpochMillis, nowEpochMillis())
            if (dao.update(updated) == 0) return@serializedTransaction SaveWeighingReminderResult.Duplicate
            resetRuntime(id.value)
            SaveWeighingReminderResult.Saved(updated.toDomain())
        }

    suspend fun setEnabled(id: WeighingReminderId, enabled: Boolean): SaveWeighingReminderResult =
        serializedTransaction {
            val current = dao.get(id.value) ?: return@serializedTransaction SaveWeighingReminderResult.NotFound
            val updated = current.copy(enabled = enabled, updatedAtEpochMillis = nowEpochMillis())
            dao.update(updated)
            resetRuntime(id.value)
            SaveWeighingReminderResult.Saved(updated.toDomain())
        }

    suspend fun delete(id: WeighingReminderId): Boolean = serializedTransaction { dao.delete(id.value) == 1 }

    suspend fun prepareRegularOccurrence(id: WeighingReminderId, dueEpochMillis: Long): String? =
        serializedTransaction {
            val schedule = dao.get(id.value) ?: return@serializedTransaction null
            if (!schedule.enabled) return@serializedTransaction null
            val runtime = requireNotNull(dao.getRuntime(id.value))
            val token = newId()
            dao.updateRuntime(
                runtime.copy(
                    generation = runtime.generation + 1,
                    regularOccurrenceToken = token,
                    regularDueEpochMillis = dueEpochMillis,
                    regularStatus = ReminderOccurrenceStatus.SCHEDULED,
                ),
            )
            token
        }

    suspend fun snooze(
        id: WeighingReminderId,
        sourceOccurrenceToken: String,
        dueEpochMillis: Long,
    ): String? = serializedTransaction {
        val schedule = dao.get(id.value) ?: return@serializedTransaction null
        val runtime = dao.getRuntime(id.value) ?: return@serializedTransaction null
        if (!schedule.enabled || runtime.activeOccurrenceToken != sourceOccurrenceToken) {
            return@serializedTransaction null
        }
        val token = newId()
        val generation = runtime.generation + 1
        dao.updateRuntime(
            runtime.copy(
                generation = generation,
                activeOccurrenceToken = null,
                snoozeGeneration = generation,
                snoozeSourceOccurrenceToken = sourceOccurrenceToken,
                snoozeOccurrenceToken = token,
                snoozeDueEpochMillis = dueEpochMillis,
                snoozeStatus = ReminderSnoozeStatus.SCHEDULED,
            ),
        )
        token
    }

    suspend fun claimDue(
        id: WeighingReminderId,
        kind: ReminderCallbackKind,
        occurrenceToken: String,
    ): ReminderClaimResult = serializedTransaction {
        val schedule = dao.get(id.value) ?: return@serializedTransaction ReminderClaimResult.NoOp
        val runtime = dao.getRuntime(id.value) ?: return@serializedTransaction ReminderClaimResult.NoOp
        if (!schedule.enabled) return@serializedTransaction ReminderClaimResult.NoOp
        when (kind) {
            ReminderCallbackKind.REGULAR -> claimRegular(runtime, occurrenceToken)
            ReminderCallbackKind.SNOOZE -> claimSnooze(runtime, occurrenceToken)
        }
    }

    suspend fun consumeAction(id: WeighingReminderId, occurrenceToken: String): Boolean =
        serializedTransaction {
            val runtime = dao.getRuntime(id.value) ?: return@serializedTransaction false
            if (runtime.activeOccurrenceToken != occurrenceToken) return@serializedTransaction false
            dao.updateRuntime(runtime.copy(activeOccurrenceToken = null)) == 1
        }

    suspend fun invalidateRuntime(id: WeighingReminderId): Boolean = serializedTransaction {
        if (dao.get(id.value) == null || dao.getRuntime(id.value) == null) return@serializedTransaction false
        resetRuntime(id.value)
        true
    }

    private suspend fun claimRegular(
        runtime: WeighingReminderRuntimeEntity,
        token: String,
    ): ReminderClaimResult {
        if (runtime.regularOccurrenceToken != token || runtime.regularStatus != ReminderOccurrenceStatus.SCHEDULED) {
            return ReminderClaimResult.NoOp
        }
        val regularDue = requireNotNull(runtime.regularDueEpochMillis)
        val snoozeWins = runtime.snoozeStatus == ReminderSnoozeStatus.SCHEDULED &&
            requireNotNull(runtime.snoozeDueEpochMillis) < regularDue
        if (snoozeWins) {
            dao.updateRuntime(runtime.clearRegular())
            return ReminderClaimResult.NoOp
        }
        dao.updateRuntime(
            runtime.copy(
                regularStatus = ReminderOccurrenceStatus.CLAIMED,
                activeOccurrenceToken = token,
                snoozeGeneration = null,
                snoozeSourceOccurrenceToken = null,
                snoozeOccurrenceToken = null,
                snoozeDueEpochMillis = null,
                snoozeStatus = ReminderSnoozeStatus.NONE,
            ),
        )
        return ReminderClaimResult.Publish(token)
    }

    private suspend fun claimSnooze(
        runtime: WeighingReminderRuntimeEntity,
        token: String,
    ): ReminderClaimResult {
        if (runtime.snoozeOccurrenceToken != token || runtime.snoozeStatus != ReminderSnoozeStatus.SCHEDULED) {
            return ReminderClaimResult.NoOp
        }
        val snoozeDue = requireNotNull(runtime.snoozeDueEpochMillis)
        val regularWins = runtime.regularStatus == ReminderOccurrenceStatus.SCHEDULED &&
            requireNotNull(runtime.regularDueEpochMillis) <= snoozeDue
        if (regularWins) {
            dao.updateRuntime(runtime.clearSnooze())
            return ReminderClaimResult.NoOp
        }
        dao.updateRuntime(
            runtime.clearRegular().copy(
                activeOccurrenceToken = token,
                snoozeStatus = ReminderSnoozeStatus.CONSUMED,
            ),
        )
        return ReminderClaimResult.Publish(token)
    }

    private suspend fun resetRuntime(scheduleId: String) {
        val runtime = requireNotNull(dao.getRuntime(scheduleId))
        dao.updateRuntime(WeighingReminderRuntimeEntity(scheduleId, generation = runtime.generation + 1))
    }

    private suspend fun ownerExists(owner: WeighingReminderOwner): Boolean = when (owner) {
        is WeighingReminderOwner.Account -> database.accountDao().get(owner.id.value) != null
        is WeighingReminderOwner.Pet -> database.petDao().getPet(owner.id.value) != null
    }

    private suspend fun <T> serializedTransaction(block: suspend () -> T): T =
        mutex.withLock { database.withTransaction { block() } }
}

private fun WeighingReminderSchedule.withRuntime(runtime: WeighingReminderRuntimeEntity) =
    WeighingReminderSnapshot(
        schedule = this,
        generation = runtime.generation,
        regularOccurrenceToken = runtime.regularOccurrenceToken,
        regularDueEpochMillis = runtime.regularDueEpochMillis,
        regularStatus = runtime.regularStatus,
        activeOccurrenceToken = runtime.activeOccurrenceToken,
        snoozeOccurrenceToken = runtime.snoozeOccurrenceToken,
        snoozeDueEpochMillis = runtime.snoozeDueEpochMillis,
        snoozeStatus = runtime.snoozeStatus,
    )

private fun WeighingReminderRuntimeEntity.clearSnooze() = copy(
    snoozeGeneration = null,
    snoozeSourceOccurrenceToken = null,
    snoozeOccurrenceToken = null,
    snoozeDueEpochMillis = null,
    snoozeStatus = ReminderSnoozeStatus.NONE,
)

private fun WeighingReminderRuntimeEntity.clearRegular() = copy(
    regularOccurrenceToken = null,
    regularDueEpochMillis = null,
    regularStatus = ReminderOccurrenceStatus.NONE,
)

private fun WeighingReminderOwner.storageIdentity(): Pair<WeighingReminderOwnerType, String> = when (this) {
    is WeighingReminderOwner.Account -> WeighingReminderOwnerType.ACCOUNT to id.value
    is WeighingReminderOwner.Pet -> WeighingReminderOwnerType.PET to id.value
}

private fun WeighingReminderDraft.toEntity(
    id: String,
    createdAtEpochMillis: Long,
    updatedAtEpochMillis: Long,
): WeighingReminderScheduleEntity {
    val (ownerType, ownerId) = owner.storageIdentity()
    return WeighingReminderScheduleEntity(
        id = id,
        ownerType = ownerType,
        ownerId = ownerId,
        minuteOfDay = time.hour * 60 + time.minute,
        weekdaysMask = weekdays.toWeekdayMask(),
        importance = importance,
        enabled = enabled,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
