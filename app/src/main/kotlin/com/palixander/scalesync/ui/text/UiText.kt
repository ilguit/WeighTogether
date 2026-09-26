package com.palixander.scalesync.ui.text

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

sealed interface UiText {
    data class Resource(
        @param:StringRes val id: Int,
        val arguments: List<Any> = emptyList(),
    ) : UiText

    data class Plural(
        @param:PluralsRes val id: Int,
        val quantity: Int,
        val arguments: List<Any> = emptyList(),
    ) : UiText
}

fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Resource -> resources.getString(id, *arguments.toTypedArray())
    is UiText.Plural -> resources.getQuantityString(id, quantity, *arguments.toTypedArray())
}

fun uiText(@StringRes id: Int, vararg arguments: Any): UiText =
    UiText.Resource(id, arguments.toList())

fun pluralUiText(@PluralsRes id: Int, quantity: Int, vararg arguments: Any): UiText =
    UiText.Plural(id, quantity, arguments.toList())
