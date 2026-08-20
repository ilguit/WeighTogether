package com.example.huaweimisync.ui.routing

import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns a preview for exactly one ViewModel lifecycle.
 *
 * This state holder deliberately has no SavedState, Saver, account, history, or measurement
 * repository dependency. A new owner therefore always starts empty after process recreation.
 */
internal class UnsavedPreviewMemoryState {
    private val mutableState = MutableStateFlow<UnsavedMeasurementPreviewState?>(null)

    val state: StateFlow<UnsavedMeasurementPreviewState?> = mutableState.asStateFlow()
    val value: UnsavedMeasurementPreviewState?
        get() = mutableState.value

    fun open(pending: PendingMeasurement) {
        mutableState.value = UnsavedMeasurementPreviewState(pending)
    }

    fun update(next: UnsavedMeasurementPreviewState) {
        if (mutableState.value?.pending?.id == next.pending.id) {
            mutableState.value = next
        }
    }

    fun retainPending(pendingIds: Set<PendingMeasurementId>) {
        mutableState.value = mutableState.value?.takeIf { it.pending.id in pendingIds }
    }

    fun clear(pendingId: PendingMeasurementId): Boolean {
        if (mutableState.value?.pending?.id != pendingId) return false
        mutableState.value = null
        return true
    }
}
