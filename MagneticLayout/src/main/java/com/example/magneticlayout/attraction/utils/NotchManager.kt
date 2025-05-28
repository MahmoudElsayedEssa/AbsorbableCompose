package com.example.magneticlayout.attraction.utils

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * A manager class that handles detection of circular camera notches on Android devices.
 * Provides positioning and size information for UI adjustments.
 */
class NotchManager private constructor() {

    // Cache notch information to avoid recalculation
    private var cachedNotchInfo: NotchInfo? = null
    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    /**
     * Represents information about a device's circular camera notch
     * @param position Offset coordinates of the center of the notch
     * @param size Size of the notch area (diameter)
     */
    data class NotchInfo(
        val position: Offset,
        val size: Size
    )

    companion object {
        @Volatile
        private var INSTANCE: NotchManager? = null

        fun getInstance(): NotchManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotchManager().also { INSTANCE = it }
            }
        }
    }

    /**
     * Get notch information for the current device
     * @param context Application context
     * @param forceRefresh Whether to force refresh the cache
     * @return NotchInfo object containing position and size
     */
    fun getNotchInfo(context: Context, forceRefresh: Boolean = false): NotchInfo {
        val currentOrientation = context.resources.configuration.orientation

        // Use cached value if available and orientation hasn't changed
        if (!forceRefresh && cachedNotchInfo != null && lastOrientation == currentOrientation) {
            return cachedNotchInfo!!
        }

        lastOrientation = currentOrientation
        cachedNotchInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getNotchInfoFromApi(context)
        } else {
            getDefaultNotchInfo(context)
        }

        return cachedNotchInfo!!
    }

    /**
     * Checks if the device has a notch
     * @param context Application context
     * @return true if device has a notch, false otherwise
     */
    fun hasNotch(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val windowInsets = (context as? Activity)?.window?.decorView?.rootWindowInsets
            val cutout = windowInsets?.displayCutout
            return cutout != null && cutout.boundingRects.isNotEmpty()
        }

        // Fallback for older API levels
        return false
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun getNotchInfoFromApi(context: Context): NotchInfo {
        val windowInsets = (context as? Activity)?.window?.decorView?.rootWindowInsets
        val cutout = windowInsets?.displayCutout
        val bounds = cutout?.boundingRects?.firstOrNull()

        return if (bounds != null) {
            val centerX = bounds.centerX().toFloat()
            // Assume a circular notch and calculate radius from width
            val cameraRadius = minOf(bounds.width(), bounds.height()) / 2f

            // Adjust the position to target the camera itself, not just the bounding box
            val centerY = bounds.bottom - cameraRadius

            // Double the radius for effective UI area around the notch
            val effectRadius = cameraRadius * 2f

            NotchInfo(
                position = Offset(centerX, centerY),
                size = Size(effectRadius, effectRadius)
            )
        } else {
            getDefaultNotchInfo(context)
        }
    }

    private fun getDefaultNotchInfo(context: Context): NotchInfo {
        // Fallback: center of screen width, middle of status bar
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val statusBarHeight = getStatusBarHeight(context)

        return NotchInfo(
            position = Offset(screenWidth / 2f, statusBarHeight / 2f),
            size = Size(80f, 30f)
        )
    }

    private fun getStatusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            24.dpToPx(context)
        }
    }

    private fun Int.dpToPx(context: Context): Int =
        (this * context.resources.displayMetrics.density).toInt()
}