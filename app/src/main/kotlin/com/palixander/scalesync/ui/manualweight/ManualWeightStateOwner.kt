package com.palixander.scalesync.ui.manualweight

import com.palixander.scalesync.R
import com.palixander.scalesync.domain.ManualWeightOwner
import com.palixander.scalesync.domain.ManualWeightRequest
import com.palixander.scalesync.domain.ManualWeightResult
import com.palixander.scalesync.domain.parseManualWeight
import com.palixander.scalesync.ui.text.UiText
import com.palixander.scalesync.ui.text.uiText
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ManualWeightDraft(
    val requestId: String,
    val owner: ManualWeightOwner,
    val ownerName: String,
    val date: LocalDate,
    val time: LocalTime,
    val weight: String = "",
    val ownerAvailable: Boolean = true,
    val saving: Boolean = false,
    val duplicate: Boolean = false,
    val error: UiText? = null,
    val dateError: UiText? = null,
) {
    val weightError: UiText?
        get() = if (weight.isNotEmpty() && parseManualWeight(weight) == null) {
            uiText(R.string.manual_weight_invalid_weight)
        } else null
    val canSave: Boolean
        get() = ownerAvailable && !saving && parseManualWeight(weight) != null && dateError == null
}

/** In-memory owner retained by a ViewModel; each opening is a distinct idempotent operation. */
class ManualWeightStateOwner(
    private val scope: CoroutineScope,
    private val save: suspend (ManualWeightRequest, Boolean) -> ManualWeightResult,
    private val onSaved: (ManualWeightOwner, ManualWeightResult.Saved) -> Unit,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = clock.zone,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableDraft = MutableStateFlow<ManualWeightDraft?>(null)
    val draft = mutableDraft.asStateFlow()

    fun open(owner: ManualWeightOwner, name: String) {
        if (mutableDraft.value != null) return
        val now = clock.instant().atZone(zoneId).truncatedTo(ChronoUnit.MINUTES)
        mutableDraft.value = ManualWeightDraft(newRequestId(), owner, name, now.toLocalDate(), now.toLocalTime())
    }

    fun dismiss() { mutableDraft.value = null }
    fun changeWeight(value: String) = change { copy(weight = value) }
    fun changeDate(value: LocalDate) = change { copy(date = value) }
    fun changeTime(value: LocalTime) = change { copy(time = value.truncatedTo(ChronoUnit.MINUTES)) }
    fun dismissDuplicate() { mutableDraft.value = mutableDraft.value?.copy(duplicate = false) }

    fun setOwnerAvailable(available: Boolean) {
        mutableDraft.value = mutableDraft.value?.copy(ownerAvailable = available)
    }

    private fun change(transform: ManualWeightDraft.() -> ManualWeightDraft) {
        val current = mutableDraft.value ?: return
        if (current.saving) return
        // An edited retry is a new request. Unchanged retries retain the original UUID.
        mutableDraft.value = validate(current.transform().copy(requestId = newRequestId(), duplicate = false, error = null))
    }

    private fun validate(value: ManualWeightDraft): ManualWeightDraft {
        val local = value.date.atTime(value.time)
        val offsets = zoneId.rules.getValidOffsets(local)
        val error = when {
            offsets.isEmpty() -> uiText(R.string.manual_weight_invalid_local_time)
            local.atZone(zoneId).toInstant().isAfter(clock.instant()) -> uiText(R.string.manual_weight_future_time)
            else -> null
        }
        return value.copy(dateError = error)
    }

    fun submit(allowDuplicate: Boolean = false) {
        val current = mutableDraft.value ?: return
        if (current.saving || (allowDuplicate && !current.duplicate)) return
        val validated = validate(current)
        mutableDraft.value = validated
        if (!validated.canSave) return
        val operation = validated.copy(saving = true, duplicate = false, error = null)
        mutableDraft.value = operation
        val request = ManualWeightRequest(
            operation.requestId, operation.owner, requireNotNull(parseManualWeight(operation.weight)),
            operation.date.atTime(operation.time).atZone(zoneId).toInstant(),
        )
        scope.launch {
            try {
                val result = save(request, allowDuplicate)
                // Closing/reopening while the transaction runs must never mutate the new draft.
                if (mutableDraft.value?.requestId != operation.requestId) return@launch
                when (result) {
                    is ManualWeightResult.Saved -> {
                        mutableDraft.value = null
                        onSaved(operation.owner, result)
                    }
                    is ManualWeightResult.Duplicate -> mutableDraft.value = operation.copy(saving = false, duplicate = true)
                    ManualWeightResult.OwnerUnavailable -> mutableDraft.value = operation.copy(saving = false, ownerAvailable = false)
                    ManualWeightResult.Invalid -> mutableDraft.value = validate(operation.copy(saving = false)).let {
                        if (it.dateError != null) it else it.copy(error = uiText(R.string.manual_weight_check_fields))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (mutableDraft.value?.requestId == operation.requestId) {
                    mutableDraft.value = operation.copy(saving = false, error = uiText(R.string.manual_weight_save_failed))
                }
            }
        }
    }
}
