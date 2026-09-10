package com.palixander.scalesync.ui.components

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.palixander.scalesync.domain.MeasurementOrigin
import com.palixander.scalesync.ui.icons.HuaweiIcons

/** Origin remains independent of later edits and synchronization status. */
@Composable
fun MeasurementOriginIndicator(origin: MeasurementOrigin, modifier: Modifier = Modifier) {
    val presentation = when (origin) {
        MeasurementOrigin.MANUAL -> OriginPresentation(
            icon = HuaweiIcons.Keyboard,
            accessibleName = "Источник: ручной ввод",
        )
        MeasurementOrigin.SCALE -> OriginPresentation(
            icon = HuaweiIcons.Scale,
            accessibleName = "Источник: весы",
        )
        MeasurementOrigin.LEGACY -> return
    }
    Icon(
        imageVector = presentation.icon,
        contentDescription = null,
        modifier = modifier
            .size(20.dp)
            .semantics { contentDescription = presentation.accessibleName },
    )
}

private data class OriginPresentation(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accessibleName: String,
)
