package com.example.absorbable.attraction.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.magnetic.compose.MagneticController
import com.example.magnetic.view.MagneticView
import com.example.magneticlayout.attraction.controller.AttractionPoint
import com.example.magneticlayout.attraction.compose.MagneticLayoutScope
import com.example.magneticlayout.attraction.compose.MagneticLayoutScopeImpl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun MagneticLayout(
    modifier: Modifier = Modifier,
    controller: MagneticController,
    cleanupIntervalMs: Long = 30000L,
    content: @Composable MagneticLayoutScope.() -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentAttractionPoints by controller.currentAttractionPoints.collectAsState()
    val primaryAttractionPointForView by controller.primaryAttractionPoint

    val magneticView = remember(context) {
        MagneticView(context, controller).apply {
            setReleaseAnimationCompletedCallback { id ->
                controller.onReleaseAnimationCompleted(id)
            }
            setAttractionAnimationCompletedCallback { id ->
                controller.onAttractionAnimationCompleted(id)
            }
        }
    }

    LaunchedEffect(controller, cleanupIntervalMs) {
        coroutineScope.launch {
            while (isActive) {
                delay(cleanupIntervalMs)
                controller.cleanup()
            }
        }
    }

    LaunchedEffect(controller) {
        while (isActive) {
            delay(100)
            if (controller.getStackSize() > 0 && controller.currentAttractionPoints.value.isNotEmpty()) {
                controller.checkReleasableItems()
            }
        }
    }

    val magneticLayoutScope = remember(controller) { MagneticLayoutScopeImpl(controller) }

    Box(modifier = modifier.fillMaxSize()) {
        magneticLayoutScope.content()

        AndroidView(
            factory = {
                magneticView.apply {
                    primaryAttractionPointForView?.let { primary ->
                        setPrimaryAttractionPoint(primary.position, primary.radius)
                    }
                    setAttractionPoints(currentAttractionPoints.ifEmpty {
                        // Provide a default for MagneticView if controller has none,
                        // though controller should ideally always have points if active.
                        listOf(AttractionPoint.deviceNotch(context))
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                primaryAttractionPointForView?.let { primary ->
                    view.setPrimaryAttractionPoint(primary.position, primary.radius)
                }
                view.setAttractionPoints(currentAttractionPoints.ifEmpty {
                    listOf(AttractionPoint.deviceNotch(context))
                })
            }
        )
    }

    LaunchedEffect(controller, magneticView) {
        controller.needsDistanceCheck.collect { needsCheck ->
            if (needsCheck && controller.currentAttractionPoints.value.isNotEmpty()) {
                controller.checkAttractableItems(magneticView)
            }
        }
    }

    LaunchedEffect(controller, magneticView) {
        controller.releaseCheckRequested.collect { needsCheck ->
            if (needsCheck && controller.currentAttractionPoints.value.isNotEmpty()) {
                controller.checkForItemRelease(magneticView)
            }
        }
    }
}