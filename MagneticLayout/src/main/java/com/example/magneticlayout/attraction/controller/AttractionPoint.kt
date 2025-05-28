package com.example.magneticlayout.attraction.controller

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.magneticlayout.attraction.utils.NotchManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.cos
import kotlin.math.sin

/**
 * Defines a point in the UI that can attract [attractable] elements.
 *
 * An `AttractionPoint` represents a specific location and area in the UI where
 * composables modified with [androidx.compose.ui.Modifier.attractable] can be drawn towards and magnetically held.
 *
 * @property position The [androidx.compose.ui.geometry.Offset] defining the center coordinates (x, y) of the attraction point
 * in pixels, relative to the screen or relevant parent container.
 * @property radius The radius of the attraction zone in pixels. Items within this conceptual
 * distance (modified by their own strength and global thresholds) may be attracted.
 * @property strength An optional multiplier for the attraction strength of this specific point.
 * Values greater than 1.0 make this point more "magnetic," while values
 * less than 1.0 make it less so. Default is 1.0f.
 *
 * @constructor Creates an attraction point with the specified properties.
 * @throws IllegalArgumentException if [radius] or [strength] is not positive.
 */

data class AttractionPoint(
    val position: Offset, val radius: Float, val strength: Float = 1.0f
) {
    init {
        require(radius > 0f) { "Attraction point radius must be positive, was $radius" }
        require(strength > 0f) { "Attraction point strength must be positive, was $strength" }
    }

    companion object {
        fun deviceNotch(context: Context, strength: Float = 1.0f): AttractionPoint {
            val notchInfo = NotchManager.Companion.getInstance().getNotchInfo(context)
            return AttractionPoint(
                position = notchInfo.position,
                radius = notchInfo.size.width / 2f,
                strength = strength
            )
        }

        fun custom(
            x: Float, y: Float, radius: Float, strength: Float = 1.0f
        ): AttractionPoint {
            return AttractionPoint(
                position = Offset(x, y), radius = radius, strength = strength
            )
        }

        fun center(
            context: Context, radius: Float, strength: Float = 1.0f
        ): AttractionPoint {
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels.toFloat()
            val screenHeight = displayMetrics.heightPixels.toFloat()

            return AttractionPoint(
                position = Offset(screenWidth / 2f, screenHeight / 2f),
                radius = radius,
                strength = strength
            )
        }

        fun topCenter(
            context: Context, radius: Float, offsetFromTop: Float = 50f, strength: Float = 1.0f
        ): AttractionPoint {
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels.toFloat()

            return AttractionPoint(
                position = Offset(screenWidth / 2f, offsetFromTop),
                radius = radius,
                strength = strength
            )
        }

        fun bottomCenter(
            context: Context, radius: Float, offsetFromBottom: Float = 50f, strength: Float = 1.0f
        ): AttractionPoint {
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels.toFloat()
            val screenHeight = displayMetrics.heightPixels.toFloat()

            return AttractionPoint(
                position = Offset(screenWidth / 2f, screenHeight - offsetFromBottom),
                radius = radius,
                strength = strength
            )
        }

        fun circle(
            @Suppress("UNUSED_PARAMETER") context: Context,
            centerX: Float,
            centerY: Float,
            circleRadius: Float,
            pointRadius: Float,
            pointCount: Int,
            strength: Float = 1.0f
        ): List<AttractionPoint> {
            require(pointCount > 0) { "Point count must be positive, was $pointCount" }

            return List(pointCount) { i ->
                val angle = (2 * Math.PI * i / pointCount).toFloat()
                val x = centerX + circleRadius * cos(angle)
                val y = centerY + circleRadius * sin(angle)

                AttractionPoint(
                    position = Offset(x, y), radius = pointRadius, strength = strength
                )
            }
        }
    }
}


enum class AttractionState {
    VISIBLE,
    ATTRACTING,
    ATTRACTED,
    RELEASING,
}

data class AttractableItemPosition(
    val id: String,
    val center: Offset,
    val size: Size,
    val attractionStrength: Float = 1.0f
)

data class ItemState(
    val id: String,
    var state: AttractionState = AttractionState.VISIBLE,
    var position: AttractableItemPosition? = null,
    var previousPosition: AttractableItemPosition? = null,
    var originalPosition: AttractableItemPosition? = null,
    var lastReportedDistance: Float = 0f,
    var strength: Float = 1.0f,
    val isAnimating: MutableStateFlow<Boolean> = MutableStateFlow(false),
    val isAttracted: MutableStateFlow<Boolean> = MutableStateFlow(false),
    var lastUpdateTime: Long = System.currentTimeMillis()
)
