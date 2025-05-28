package com.example.magnetic.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.example.magnetic.compose.MagneticController
import com.example.magneticlayout.attraction.controller.AttractableItemPosition
import com.example.magneticlayout.attraction.controller.AttractionPoint
import com.example.magneticlayout.attraction.controller.AttractionState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenGL surface view that renders the magnetic points with metaball effects
 * and animates attractable items with direct gesture interaction
 */
class MagneticView(context: Context, private val controller: MagneticController) :
    GLSurfaceView(context) {

        companion object
    private  val TAG = "MagneticView"
    private val renderer: MagneticRenderer

    // Track which items are currently animating
    private val animatingItems = ConcurrentHashMap<String, Boolean>()

    // Main attraction point properties
    private var mainAttractionCenter = Offset.Zero
    private var mainAttractionRadius = 0f

    // List of all attraction points
    private var attractionPoints = listOf<AttractionPoint>()


    // Flag to ensure we don't start new animations while one is in progress
    private val isAnimationInProgress = AtomicBoolean(false)

    // Callbacks
    private var releaseAnimationCompletedCallback: ((String) -> Unit)? = null
    private var attractionAnimationCompletedCallback: ((String) -> Unit)? = null

    init {

        // Configure OpenGL ES 3.0
        setEGLContextClientVersion(3)

        // Configure alpha channel for transparency
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)

        // Create and set renderer
        renderer = MagneticRenderer(this, controller)
        setRenderer(renderer)

        // Start in efficient mode, only rendering when needed
        renderMode = RENDERMODE_WHEN_DIRTY

        // Ensure this view overlays other Compose elements
        setZOrderOnTop(true)

        // Set up gesture detection
    }

    /**
     * Set callback for release animation completion
     */
    fun setReleaseAnimationCompletedCallback(callback: (String) -> Unit) {
        releaseAnimationCompletedCallback = callback
    }

    /**
     * Set callback for attraction animation completion
     */
    fun setAttractionAnimationCompletedCallback(callback: (String) -> Unit) {
        attractionAnimationCompletedCallback = callback
    }

    /**
     * Set the position and size of the main attraction point
     */
    fun setPrimaryAttractionPoint(center: Offset, radius: Float) {
        // Save for gesture detection
        mainAttractionCenter = center
        mainAttractionRadius = radius

        // Set in renderer
        renderer.setPrimaryAttractionPoint(center.x, center.y, radius)
        requestRender()
    }

    /**
     * Set all attraction points
     */
    fun setAttractionPoints(points: List<AttractionPoint>) {
        attractionPoints = points
        renderer.setAttractionPoints(points)
        requestRender()
    }


    /**
     * Start animation for attracting an item
     */
    fun startAttractionAnimation(
        id: String, itemPosition: AttractableItemPosition, targetPosition: Offset, bitmap: Bitmap?
    ) {

        try {
            // Mark as animating
            animatingItems[id] = true
            controller.setItemAnimating(id, true, AttractionState.ATTRACTING)

            // Mark that an animation is in progress
            isAnimationInProgress.set(true)

            // Create a copy of bitmap to avoid recycling issues
            val bitmapCopy = bitmap?.let {
                try {
                    if (!it.isRecycled) it.config?.let { config -> it.copy(config, true) } else null
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying bitmap: ${e.message}")
                    null
                }
            }

            // Pass to renderer via GL thread
            queueEvent {
                renderer.startAttractionAnimation(id, itemPosition, targetPosition, bitmapCopy)

                // Ensure continuous rendering during animation
                renderMode = RENDERMODE_CONTINUOUSLY
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting attraction animation", e)

            // Clean up on error
            animatingItems.remove(id)
            controller.setItemAnimating(id, false)
            isAnimationInProgress.set(false)

            // Complete animation immediately on error
            attractionAnimationCompletedCallback?.invoke(id)
        }
    }

    /**
     * Start animation for releasing an item
     */
    fun startReleaseAnimation(
        id: String, startPosition: Offset, targetPosition: Offset, releasedBitmap: Bitmap? = null
    ) {

        try {
            // Mark as animating
            animatingItems[id] = true
            controller.setItemAnimating(id, true, AttractionState.RELEASING)

            // Mark that an animation is in progress
            isAnimationInProgress.set(true)

            // Pass to renderer via GL thread
            queueEvent {
                renderer.startReleaseAnimation(id, startPosition, targetPosition, releasedBitmap)

                // Ensure continuous rendering during animation
                renderMode = RENDERMODE_CONTINUOUSLY
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting release animation", e)

            // Clean up on error
            animatingItems.remove(id)
            controller.setItemAnimating(id, false)
            isAnimationInProgress.set(false)

            // Complete animation immediately on error
            releaseAnimationCompletedCallback?.invoke(id)
        }
    }

    /**
     * Called by renderer when an attraction animation completes
     */
    fun onAttractionAnimationCompleted(id: String) {

        // Mark as not animating
        animatingItems.remove(id)

        // Use post to move back to UI thread and update render mode
        post {
            try {
                attractionAnimationCompletedCallback?.invoke(id)
            } finally {
                // Always clean up state
                checkRenderMode()
                isAnimationInProgress.set(false)
            }
        }
    }

    /**
     * Called by renderer when a release animation completes
     */
    fun onReleaseAnimationCompleted(id: String) {

        // Mark as not animating
        animatingItems.remove(id)

        // Use post to move back to UI thread and update render mode
        post {
            try {
                releaseAnimationCompletedCallback?.invoke(id)
            } finally {
                // Always clean up state
                checkRenderMode()
                isAnimationInProgress.set(false)
            }
        }
    }

    /**
     * Switch to on-demand rendering if no animations are active
     */
    private fun checkRenderMode() {
        val hasActiveAnimations = animatingItems.isNotEmpty()

        if (!hasActiveAnimations && renderMode == RENDERMODE_CONTINUOUSLY) {
            renderMode = RENDERMODE_WHEN_DIRTY
            requestRender() // Final render
        } else if (hasActiveAnimations && renderMode == RENDERMODE_WHEN_DIRTY) {
            renderMode = RENDERMODE_CONTINUOUSLY
        }
    }

}