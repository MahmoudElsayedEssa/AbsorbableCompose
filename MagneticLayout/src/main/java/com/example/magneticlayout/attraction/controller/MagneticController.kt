package com.example.magnetic.compose

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.toSize
import com.example.magnetic.view.MagneticView
import com.example.magneticlayout.attraction.controller.AttractableItemPosition
import com.example.magneticlayout.attraction.controller.AttractionPoint
import com.example.magneticlayout.attraction.controller.AttractionState
import com.example.magneticlayout.attraction.controller.ItemState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.sqrt


class MagneticController(
    initialAttractionPoints: List<AttractionPoint>,
    initialAttractionDistanceThreshold: Float,
    initialReleaseDistanceThreshold: Float,
    private val onAttract: ((String) -> Unit)? = null,
    private val onRelease: ((String) -> Unit)? = null
) {
    private val stateLock = ReentrantReadWriteLock()
    private val stackLock = ReentrantReadWriteLock()

    private val itemStates = ConcurrentHashMap<String, ItemState>()
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val attractionStack = LinkedList<String>()

    private val _attractionStackSize = MutableStateFlow(0)
    val attractionStackSize: StateFlow<Int> = _attractionStackSize.asStateFlow()

    private val _needsDistanceCheck = MutableStateFlow(false)
    val needsDistanceCheck: StateFlow<Boolean> = _needsDistanceCheck.asStateFlow()

    private val _releaseCheckRequested = MutableStateFlow(false)
    val releaseCheckRequested: StateFlow<Boolean> = _releaseCheckRequested.asStateFlow()

    private val recentlyReleasedItems = ConcurrentHashMap<String, Long>()
    private val releaseTimeoutMs = 1500L
    private val operationInProgress = AtomicBoolean(false)

    private val _internalAttractionPoints = MutableStateFlow(initialAttractionPoints)
    val currentAttractionPoints: StateFlow<List<AttractionPoint>> = _internalAttractionPoints.asStateFlow()

    val primaryAttractionPoint: State<AttractionPoint?> = derivedStateOf {
        _internalAttractionPoints.value.firstOrNull()
    }

    private val _internalAttractionDistanceThreshold = mutableFloatStateOf(initialAttractionDistanceThreshold)
    val currentAttractionDistanceThreshold: State<Float> get() = _internalAttractionDistanceThreshold

    private val _internalReleaseDistanceThreshold = mutableFloatStateOf(initialReleaseDistanceThreshold)
    val currentReleaseDistanceThreshold: State<Float> get() = _internalReleaseDistanceThreshold

    fun updateConfiguration(
        newAttractionPoints: List<AttractionPoint>,
        newAttractionThreshold: Float,
        newReleaseThreshold: Float
    ) {
        _internalAttractionPoints.value = newAttractionPoints
        _internalAttractionDistanceThreshold.value = newAttractionThreshold
        _internalReleaseDistanceThreshold.value = newReleaseThreshold
        _needsDistanceCheck.value = true
        if (attractionStack.isNotEmpty()) {
            _releaseCheckRequested.value = true
        }
    }

    fun getBitmapForItem(id: String): Bitmap? {
        val bitmap = bitmapCache[id]
        if (bitmap != null && bitmap.isRecycled) {
            bitmapCache.remove(id)
            return null
        }
        return bitmap
    }

    fun storeOriginalPosition(id: String, position: AttractableItemPosition) {
        stateLock.write {
            itemStates[id]?.originalPosition = position.copy()
        }
    }

    fun getOriginalPosition(id: String): AttractableItemPosition? {
        return stateLock.read { itemStates[id]?.originalPosition }
    }

    fun forceReleaseCheck() {
        _releaseCheckRequested.value = true
    }

    private fun updateItemState(id: String, newState: AttractionState) {
        stateLock.write {
            val itemState = itemStates[id] ?: return@write
            if (itemState.state == newState) return@write

            itemState.state = newState
            val isCurrentlyAttracted = newState == AttractionState.ATTRACTED
            val isCurrentlyAnimating = newState == AttractionState.ATTRACTING || newState == AttractionState.RELEASING

            if (itemState.isAttracted.value != isCurrentlyAttracted) itemState.isAttracted.value = isCurrentlyAttracted
            if (itemState.isAnimating.value != isCurrentlyAnimating) itemState.isAnimating.value = isCurrentlyAnimating
            itemState.lastUpdateTime = System.currentTimeMillis()
        }
    }

    fun registerItem(id: String, attractionStrength: Float = 1.0f) {
        stateLock.write {
            val existingState = itemStates[id]
            if (existingState != null) {
                existingState.strength = attractionStrength
            } else {
                itemStates[id] = ItemState(id = id, strength = attractionStrength)
            }
        }
    }

    fun unregisterItem(id: String) {
        var shouldFullyUnregister = true
        stateLock.read {
            itemStates[id]?.let {
                if (it.state == AttractionState.ATTRACTED || it.state == AttractionState.ATTRACTING || it.state == AttractionState.RELEASING) {
                    shouldFullyUnregister = false
                }
            }
        }

        if (shouldFullyUnregister) {
            stackLock.write {
                if (attractionStack.remove(id)) {
                    _attractionStackSize.value = attractionStack.size
                }
            }
            bitmapCache.remove(id)?.recycleIfNotRecycled()
            stateLock.write {
                itemStates.remove(id)
            }
        }
    }

    fun observeItemAttracted(id: String): StateFlow<Boolean> {
        stateLock.write {
            return itemStates.getOrPut(id) { ItemState(id = id) }.isAttracted.asStateFlow()
        }
    }

    fun observeItemAnimating(id: String): StateFlow<Boolean> {
        stateLock.write {
            return itemStates.getOrPut(id) { ItemState(id = id) }.isAnimating.asStateFlow()
        }
    }

    fun updatePosition(id: String, coords: LayoutCoordinates) {
        if (!coords.isAttached) return

        val itemStateSnapshot = stateLock.read { itemStates[id] } ?: return

        if (recentlyReleasedItems.containsKey(id)) {
            if (System.currentTimeMillis() - (recentlyReleasedItems[id] ?: 0) < releaseTimeoutMs) {
                return
            } else {
                recentlyReleasedItems.remove(id)
            }
        }

        val boundsIntSize = coords.size
        if (boundsIntSize.width == 0 || boundsIntSize.height == 0) return

        val boundsSize = boundsIntSize.toSize()
        val localPosition = coords.positionInWindow()
        val itemGeometricCenter = localPosition + Offset(boundsSize.width / 2f, boundsSize.height / 2f)
        val itemCenterTop = Offset(itemGeometricCenter.x, itemGeometricCenter.y - boundsSize.height / 4f)
        val currentItemPositionData = AttractableItemPosition(id, itemGeometricCenter, boundsSize, itemStateSnapshot.strength)

        val attractPointPos = primaryAttractionPoint.value?.position
        var distanceFromAttractionPoint: Float? = null

        if (attractPointPos != null) {
            distanceFromAttractionPoint = getDistance(itemCenterTop, attractPointPos)
        }

        var positionChanged = false
        stateLock.write {
            val currentItemState = itemStates[id]
            if (currentItemState != null) {
                if (currentItemState.position?.center != itemGeometricCenter || currentItemState.position?.size != boundsSize) {
                    val previousGeometricCenterY = currentItemState.position?.center?.y
                    var itemMovingTowardsScreenBottom = false

                    if (previousGeometricCenterY != null && itemGeometricCenter.y > previousGeometricCenterY) {
                        itemMovingTowardsScreenBottom = true
                    }

                    currentItemState.previousPosition = currentItemState.position?.copy()
                    currentItemState.position = currentItemPositionData
                    positionChanged = true

                    if (distanceFromAttractionPoint != null) {
                        currentItemState.lastReportedDistance = distanceFromAttractionPoint
                        if (currentItemState.state == AttractionState.ATTRACTED && attractPointPos != null) {
                            if (distanceFromAttractionPoint > currentReleaseDistanceThreshold.value && itemMovingTowardsScreenBottom) {
                                _releaseCheckRequested.value = true
                            }
                        }
                    }
                }
            }
        }
        if (positionChanged && itemStateSnapshot.state == AttractionState.VISIBLE) {
            _needsDistanceCheck.value = true
        }
    }

    fun setItemAnimating(id: String, animating: Boolean, animationTypeForStart: AttractionState? = null) {
        stateLock.write {
            val itemState = itemStates[id] ?: return@write
            if (animating) {
                if (animationTypeForStart != null && (animationTypeForStart == AttractionState.ATTRACTING || animationTypeForStart == AttractionState.RELEASING)) {
                    itemState.state = animationTypeForStart
                }
                itemState.isAnimating.value = true
            } else {
                itemState.isAnimating.value = false
                if (itemState.state == AttractionState.ATTRACTING) {
                    itemState.state = AttractionState.ATTRACTED
                    itemState.isAttracted.value = true
                } else if (itemState.state == AttractionState.RELEASING) {
                    itemState.state = AttractionState.VISIBLE
                    itemState.isAttracted.value = false
                }
            }
        }
    }

    fun getStackSize(): Int = stackLock.read { attractionStack.size }

    fun checkReleasableItems() {
        val attractPoint = primaryAttractionPoint.value?.position ?: return
        val releaseThresh = currentReleaseDistanceThreshold.value

        stackLock.read {
            if (attractionStack.isEmpty()) return@read
            val topItemId = attractionStack.firstOrNull() ?: return@read
            val itemState = stateLock.read { itemStates[topItemId] } ?: return@read

            if (itemState.state == AttractionState.ATTRACTED) {
                val currentItemFullPosition = itemState.position
                val previousItemFullPosition = itemState.previousPosition

                if (currentItemFullPosition != null) {
                    val itemCenterTop = Offset(currentItemFullPosition.center.x, currentItemFullPosition.center.y - currentItemFullPosition.size.height / 2f)
                    val distance = getDistance(itemCenterTop, attractPoint)
                    var itemMovingTowardsScreenBottom = false
                    if (previousItemFullPosition != null && currentItemFullPosition.center.y > previousItemFullPosition.center.y) {
                        itemMovingTowardsScreenBottom = true
                    }
                    if (distance > releaseThresh && itemMovingTowardsScreenBottom) {
                        _releaseCheckRequested.value = true
                    }
                }
            }
        }
    }

    fun checkForItemRelease(magneticView: MagneticView): Boolean {
        val attractPointPos = primaryAttractionPoint.value?.position ?: return false
        val releaseThresh = currentReleaseDistanceThreshold.value

        if (!operationInProgress.compareAndSet(false, true)) return false
        var releaseInitiated = false
        try {
            val topItemId = stackLock.read { attractionStack.firstOrNull() } ?: run {
                _releaseCheckRequested.value = false
                return false
            }
            val itemState = stateLock.read { itemStates[topItemId] } ?: run {
                stackLock.write { if (attractionStack.remove(topItemId)) _attractionStackSize.value = attractionStack.size }
                _releaseCheckRequested.value = false
                return false
            }

            if (itemState.state != AttractionState.ATTRACTED) {
                _releaseCheckRequested.value = false
                return false
            }

            updateItemState(topItemId, AttractionState.RELEASING)
            stackLock.write {
                if (attractionStack.remove(topItemId)) _attractionStackSize.value = attractionStack.size
            }

            val cachedBitmap = getBitmapForItem(topItemId)
            val releaseTarget = findReleaseTarget(topItemId, attractPointPos, releaseThresh, itemState.position)
            magneticView.startReleaseAnimation(topItemId, attractPointPos, releaseTarget, cachedBitmap)
            releaseInitiated = true
            _releaseCheckRequested.value = false
            return true
        } finally {
            operationInProgress.set(false)
        }
    }

    private fun findReleaseTarget(id: String, attractPosition: Offset, releaseThreshold: Float, currentItemPosition: AttractableItemPosition?): Offset {
        stateLock.read { itemStates[id]?.originalPosition }?.let { return it.center }
        val fallbackX = currentItemPosition?.center?.x ?: attractPosition.x
        val releaseTargetY = attractPosition.y + releaseThreshold * 1.2f
        return Offset(fallbackX, releaseTargetY)
    }

    fun checkAttractableItems(magneticView: MagneticView): Boolean {
        val attractPointPos = primaryAttractionPoint.value?.position ?: return false
        val attractThresh = currentAttractionDistanceThreshold.value

        if (!operationInProgress.compareAndSet(false, true)) return false
        var anyItemAttractedThisCycle = false
        try {
            val candidatePositions = stateLock.read {
                itemStates.values
                    .filter { it.state == AttractionState.VISIBLE && it.position != null && !recentlyReleasedItems.containsKey(it.id) && (it.position!!.size.width > 0 && it.position!!.size.height > 0) }
                    .mapNotNull { it.position?.copy() }
            }

            for (itemPosDetails in candidatePositions) {
                val id = itemPosDetails.id
                val distanceToAttractionPoint = getDistance(itemPosDetails.center, attractPointPos)
                val effectiveAttractThreshold = attractThresh * itemPosDetails.attractionStrength

                if (distanceToAttractionPoint < effectiveAttractThreshold) {
                    val currentItemStateSnapshot = stateLock.read { itemStates[id]?.state }
                    if (currentItemStateSnapshot != AttractionState.VISIBLE) continue

                    storeOriginalPosition(id, itemPosDetails)
                    updateItemState(id, AttractionState.ATTRACTING)
                    stackLock.write {
                        attractionStack.remove(id)
                        attractionStack.addFirst(id)
                        _attractionStackSize.value = attractionStack.size
                    }
                    val bitmapToAnimate = getBitmapForItem(id)
                    magneticView.startAttractionAnimation(id, itemPosDetails, attractPointPos, bitmapToAnimate)
                    anyItemAttractedThisCycle = true
                }
            }
            return anyItemAttractedThisCycle
        } finally {
            _needsDistanceCheck.value = false
            operationInProgress.set(false)
        }
    }

    fun onAttractionAnimationCompleted(id: String) {
        updateItemState(id, AttractionState.ATTRACTED)
        onAttract?.invoke(id)
    }

    fun onReleaseAnimationCompleted(id: String) {
        updateItemState(id, AttractionState.VISIBLE)
        stateLock.write { itemStates[id]?.originalPosition = null }
        recentlyReleasedItems[id] = System.currentTimeMillis()
        bitmapCache.remove(id)?.recycleIfNotRecycled()
        onRelease?.invoke(id)
    }

    fun storeBitmap(id: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        bitmapCache.put(id, bitmap)?.recycleIfNotRecycled(newBitmap = bitmap)
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        recentlyReleasedItems.entries.removeAll { now - it.value > releaseTimeoutMs }
        bitmapCache.entries.removeAll { entry ->
            if (entry.value.isRecycled) {
                true
            } else {
                val itemState = stateLock.read { itemStates[entry.key] }
                // Remove bitmap if item is no longer registered or is visible and not on stack
                if (itemState == null || (itemState.state == AttractionState.VISIBLE && !stackLock.read { attractionStack.contains(entry.key) })) {
                    entry.value.recycleIfNotRecycled()
                    true
                } else {
                    false
                }
            }
        }
    }
}

private fun getDistance(point1: Offset, point2: Offset): Float {
    val dx = point1.x - point2.x
    val dy = point1.y - point2.y
    return sqrt(dx * dx + dy * dy)
}

private fun Bitmap.recycleIfNotRecycled(newBitmap: Bitmap? = null) {
    if (this !== newBitmap && !this.isRecycled) {
        try {
            this.recycle()
        } catch (_: Exception) {
        }
    }
}


@Composable
fun rememberMagneticController(
    initialAttractionPoints: List<AttractionPoint> = emptyList(),
    initialAttractionDistanceThreshold: Float = 200f,
    initialReleaseDistanceThreshold: Float = 250f,
    onAttract: ((String) -> Unit)? = null,
    onRelease: ((String) -> Unit)? = null
): MagneticController {
    val context = LocalContext.current
    val initialAttractionPoints = if (initialAttractionPoints.isEmpty()) {
        listOf(AttractionPoint.deviceNotch(context))
    } else initialAttractionPoints
    return remember(
        initialAttractionPoints,
        initialAttractionDistanceThreshold,
        initialReleaseDistanceThreshold,
        onAttract,
        onRelease
    ) {
        MagneticController(
            initialAttractionPoints = initialAttractionPoints,
            initialAttractionDistanceThreshold = initialAttractionDistanceThreshold,
            initialReleaseDistanceThreshold = initialReleaseDistanceThreshold,
            onAttract = onAttract,
            onRelease = onRelease
        )
    }
}