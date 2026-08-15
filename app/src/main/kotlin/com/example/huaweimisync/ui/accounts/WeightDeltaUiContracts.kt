package com.example.huaweimisync.ui.accounts

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import com.example.huaweimisync.domain.DEFAULT_WEIGHT_DELTA_KG
import com.example.huaweimisync.domain.WEIGHT_DELTA_KG_RANGE

@Immutable
data class WeightDeltaEditorState(
    val input: String = formatLocalizedDecimal(DEFAULT_WEIGHT_DELTA_KG),
    val isSaving: Boolean = false,
) {
    val parsedValue: Double?
        get() = parseLocalizedDecimal(input)

    val error: String?
        get() {
            val value = parsedValue
            return when {
                input.isBlank() -> "Введите дельту веса"
                value == null -> "Введите число через запятую или точку"
                value !in WEIGHT_DELTA_KG_RANGE -> "Допустимое значение: 0,1–50,0 кг"
                else -> null
            }
        }

    val canSave: Boolean
        get() = !isSaving && error == null

    companion object {
        fun from(value: Double): WeightDeltaEditorState = WeightDeltaEditorState(
            input = formatLocalizedDecimal(value),
        )
    }
}

object WeightDeltaEditorStateSaver : Saver<WeightDeltaEditorState, String> {
    override fun SaverScope.save(value: WeightDeltaEditorState): String = value.input

    override fun restore(value: String): WeightDeltaEditorState = WeightDeltaEditorState(value)
}

sealed interface WeightDeltaEditorAction {
    data class InputChanged(val value: String) : WeightDeltaEditorAction
    data class SavingChanged(val value: Boolean) : WeightDeltaEditorAction
}

fun reduceWeightDeltaEditor(
    state: WeightDeltaEditorState,
    action: WeightDeltaEditorAction,
): WeightDeltaEditorState = when (action) {
    is WeightDeltaEditorAction.InputChanged -> state.copy(input = action.value)
    is WeightDeltaEditorAction.SavingChanged -> state.copy(isSaving = action.value)
}
