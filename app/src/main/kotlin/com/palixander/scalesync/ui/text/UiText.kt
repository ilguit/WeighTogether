package com.palixander.scalesync.ui.text

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

sealed interface UiText {
    data class Raw(val value: String) : UiText

    data class Resource(
        @param:StringRes val id: Int,
        val arguments: List<Any> = emptyList(),
    ) : UiText

    data class Plural(
        @param:PluralsRes val id: Int,
        val quantity: Int,
        val arguments: List<Any> = emptyList(),
    ) : UiText

    data class Joined(
        val values: List<UiText>,
        val separator: String,
    ) : UiText
}

fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Raw -> value
    is UiText.Resource -> resources.getString(id, *arguments.resolve(resources))
    is UiText.Plural -> resources.getQuantityString(id, quantity, *arguments.resolve(resources))
    is UiText.Joined -> values.joinToString(separator) { it.resolve(resources) }
}

private fun List<Any>.resolve(resources: Resources): Array<Any> =
    map { argument -> if (argument is UiText) argument.resolve(resources) else argument }.toTypedArray()

fun uiText(@StringRes id: Int, vararg arguments: Any): UiText =
    UiText.Resource(id, arguments.toList())

fun pluralUiText(@PluralsRes id: Int, quantity: Int, vararg arguments: Any): UiText =
    UiText.Plural(id, quantity, arguments.toList())
