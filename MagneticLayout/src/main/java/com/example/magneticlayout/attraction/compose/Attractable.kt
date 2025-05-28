package com.example.absorbable.attraction.compose

import android.graphics.Bitmap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.example.magnetic.compose.MagneticController
import com.example.magnetic.view.MagneticRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean


fun Modifier.attractableInternal(
    controller: MagneticController, id: String? = null, attractionStrength: Float = 1.0f
): Modifier = composed {
    val itemId = remember { id ?: "attr-${UUID.randomUUID().toString().substring(0, 8)}" }

    val isAttractedState by controller.observeItemAttracted(itemId).collectAsState()
    val isAnimatingState by controller.observeItemAnimating(itemId).collectAsState()

    val bitmapCaptured = remember(itemId) { mutableStateOf(false) }
    val isCapturingBitmap = remember { AtomicBoolean(false) }
    val graphicsLayer = rememberGraphicsLayer().apply { clip = true }
    var lastKnownLayoutCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var positionUpdateJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(itemId, controller, attractionStrength) {
        controller.registerItem(itemId, attractionStrength)
    }

    DisposableEffect(itemId, controller) {
        onDispose {
            positionUpdateJob?.cancel()
            controller.unregisterItem(itemId)
        }
    }

    val currentAlpha = if (isAttractedState || isAnimatingState) 0f else 1f
    LaunchedEffect(lastKnownLayoutCoordinates, currentAlpha, controller) {
        val currentCoords = lastKnownLayoutCoordinates
        if (currentCoords != null && currentCoords.isAttached && currentAlpha == 1f && !bitmapCaptured.value) {
            if (controller.getBitmapForItem(itemId) == null) {
                if (isCapturingBitmap.compareAndSet(false, true)) {
                    try {
                        captureBitmapFromGraphicsLayer(graphicsLayer, itemId, controller)
                        if (controller.getBitmapForItem(itemId) != null) {
                            bitmapCaptured.value = true
                        }
                    } catch (e: Exception) {
                    } finally {
                        isCapturingBitmap.set(false)
                    }
                }
            } else {
                if (!bitmapCaptured.value && controller.getBitmapForItem(itemId) != null) {
                    bitmapCaptured.value = true
                }
            }
        }
    }

    LaunchedEffect(isAttractedState, isAnimatingState) {
        if (!isAttractedState && !isAnimatingState) {
            if (bitmapCaptured.value) {
                bitmapCaptured.value = false
            }
        }
    }

    // This is the critical part - make sure we always update positions
    LaunchedEffect(lastKnownLayoutCoordinates) {
        while (isActive) {
            val currentCoords = lastKnownLayoutCoordinates
            if (currentCoords != null && currentCoords.isAttached) {
                // Always update position regardless of state - critical for release detection
                controller.updatePosition(itemId, currentCoords)

                // If it's attracted, do more frequent updates
                if (isAttractedState) {
                    delay(50) // More frequent updates for attracted items
                } else {
                    delay(100) // Normal update rate for visible items
                }
            } else {
                delay(100) // Wait until coordinates are available
            }
        }
    }

    this
        .alpha(currentAlpha)
        .onGloballyPositioned { coordinates ->
            lastKnownLayoutCoordinates = coordinates

            // Immediate position update on change (in addition to periodic updates)
            if (coordinates.isAttached) {
                controller.updatePosition(itemId, coordinates)
            }
        }
        .drawWithContent {
            graphicsLayer.record {
                this@drawWithContent.drawContent()
            }
            drawLayer(graphicsLayer)
        }
}

private suspend fun captureBitmapFromGraphicsLayer(
    graphicsLayer: GraphicsLayer, id: String, controller: MagneticController
) {
    try {
        val imageBitmap = graphicsLayer.toImageBitmap()
        val androidBitmap = imageBitmap.asAndroidBitmap()

        if (androidBitmap.width <= 1 || androidBitmap.height <= 1) {

            return
        }
        val resultBitmap = androidBitmap.copy(Bitmap.Config.ARGB_8888, true) ?: run {

            return
        }
        controller.storeBitmap(id, resultBitmap)
    } catch (e: Exception) {

    }
}