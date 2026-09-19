package com.palixander.scalesync.profile

import kotlin.math.max
import kotlin.math.roundToInt

/** A source-space rectangle. Values are pixels and may be fractional while the editor is moving. */
data class ProfilePhotoCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Integer source bounds used identically by the preview and bitmap renderer. */
data class ProfilePhotoCropBounds(
    val left: Int,
    val top: Int,
    val size: Int,
)

/**
 * Serializable editor state. Pan is normalized to the available movement: -1 is the leading/top
 * edge, 0 is centered and 1 is the trailing/bottom edge. This makes the state independent of the
 * actual Compose viewport size and safe to keep in saved state.
 */
data class ProfilePhotoCropTransform(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    fun constrained(maxZoom: Float = DEFAULT_MAX_ZOOM): ProfilePhotoCropTransform {
        require(maxZoom >= 1f)
        return copy(
            zoom = zoom.takeIf(Float::isFinite)?.coerceIn(1f, maxZoom) ?: 1f,
            panX = panX.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f,
            panY = panY.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f,
        )
    }

    companion object {
        const val DEFAULT_MAX_ZOOM = 4f
    }
}

/** Pure geometry shared by the editor preview and the final bitmap renderer. */
class ProfilePhotoCropGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val viewportDiameterPx: Float,
    val maxZoom: Float = ProfilePhotoCropTransform.DEFAULT_MAX_ZOOM,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(viewportDiameterPx.isFinite() && viewportDiameterPx > 0f)
        require(maxZoom.isFinite() && maxZoom >= 1f)
    }

    /** Scale applied to source pixels at minimum zoom so the circular viewport is fully covered. */
    val minimumDisplayScale: Float = max(
        viewportDiameterPx / sourceWidth,
        viewportDiameterPx / sourceHeight,
    )

    fun displayScale(transform: ProfilePhotoCropTransform): Float =
        minimumDisplayScale * transform.constrained(maxZoom).zoom

    fun sourceCropRect(transform: ProfilePhotoCropTransform): ProfilePhotoCropRect {
        val value = transform.constrained(maxZoom)
        val cropSize = (viewportDiameterPx / displayScale(value))
            .coerceAtMost(minOf(sourceWidth, sourceHeight).toFloat())
        val xTravel = (sourceWidth - cropSize).coerceAtLeast(0f)
        val yTravel = (sourceHeight - cropSize).coerceAtLeast(0f)
        val left = xTravel * ((value.panX + 1f) / 2f)
        val top = yTravel * ((value.panY + 1f) / 2f)
        return ProfilePhotoCropRect(left, top, left + cropSize, top + cropSize)
    }

    fun sourceCropBounds(transform: ProfilePhotoCropTransform): ProfilePhotoCropBounds {
        val crop = sourceCropRect(transform)
        val size = crop.width.toInt().coerceIn(1, minOf(sourceWidth, sourceHeight))
        return ProfilePhotoCropBounds(
            left = crop.left.roundToInt().coerceIn(0, sourceWidth - size),
            top = crop.top.roundToInt().coerceIn(0, sourceHeight - size),
            size = size,
        )
    }

    /** Converts a preview translation into normalized pan without allowing empty viewport space. */
    fun panBy(
        transform: ProfilePhotoCropTransform,
        sourceDeltaX: Float,
        sourceDeltaY: Float,
    ): ProfilePhotoCropTransform {
        val value = transform.constrained(maxZoom)
        val rect = sourceCropRect(value)
        val xTravel = (sourceWidth - rect.width).coerceAtLeast(0f)
        val yTravel = (sourceHeight - rect.height).coerceAtLeast(0f)
        return value.copy(
            panX = if (xTravel == 0f) 0f else value.panX + sourceDeltaX * 2f / xTravel,
            panY = if (yTravel == 0f) 0f else value.panY + sourceDeltaY * 2f / yTravel,
        ).constrained(maxZoom)
    }

    /** Applies a viewport gesture while keeping the image point below the gesture centroid fixed. */
    fun transformBy(
        transform: ProfilePhotoCropTransform,
        centroidX: Float,
        centroidY: Float,
        displayPanX: Float,
        displayPanY: Float,
        zoomChange: Float,
    ): ProfilePhotoCropTransform {
        val value = transform.constrained(maxZoom)
        val oldCrop = sourceCropRect(value)
        val oldScale = displayScale(value)
        val effectiveZoom = zoomChange.takeIf { it.isFinite() && it > 0f } ?: 1f
        val zoomed = value.copy(zoom = value.zoom * effectiveZoom).constrained(maxZoom)
        val zoomedCrop = sourceCropRect(zoomed)
        val newScale = displayScale(zoomed)
        val sourceAtCentroidX = oldCrop.left + centroidX / oldScale
        val sourceAtCentroidY = oldCrop.top + centroidY / oldScale
        return panBy(
            zoomed,
            sourceAtCentroidX - (centroidX + displayPanX) / newScale - zoomedCrop.left,
            sourceAtCentroidY - (centroidY + displayPanY) / newScale - zoomedCrop.top,
        )
    }
}
