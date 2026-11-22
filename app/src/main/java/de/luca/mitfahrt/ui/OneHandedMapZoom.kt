package de.luca.mitfahrt.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.osmdroid.views.MapView
import kotlin.math.hypot

/** Pure zoom math for Google-Maps-style one-finger double-tap-and-drag zoom. */
internal object OneHandedZoomMath {
    /** Dragging down zooms in; dragging up zooms out (~1 zoom level per 110 dp). */
    private const val DP_PER_ZOOM_LEVEL = 110f

    fun nextZoom(
        startZoom: Double,
        deltaYPx: Float,
        density: Float,
        minZoom: Double,
        maxZoom: Double
    ): Double {
        if (!startZoom.isFinite() || !deltaYPx.isFinite() || density <= 0f) {
            return startZoom.coerceIn(minZoom, maxZoom)
        }
        val deltaDp = deltaYPx / density
        val deltaZoom = deltaDp / DP_PER_ZOOM_LEVEL
        return (startZoom + deltaZoom).coerceIn(minZoom, maxZoom)
    }
}

/**
 * Adds Google-Maps-style one-finger zoom (double-tap and drag down to zoom in, drag up to zoom out;
 * quick double-tap without drag zooms in by 1 level) to an osmdroid [MapView] while preserving
 * two-finger pinch-zoom, single-finger pan, and parent-scroll interception handling.
 */
internal class OneHandedMapZoomTouchHandler(
    private val map: MapView,
    private val onUserGesture: () -> Unit = {}
) : View.OnTouchListener {
    private val config = ViewConfiguration.get(map.context)
    private val density = map.resources.displayMetrics.density
    private val touchSlop = config.scaledTouchSlop.toFloat()
    private val doubleTapSlop = config.scaledDoubleTapSlop.toFloat()
    private val doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong().coerceAtLeast(300L)

    private var firstDownAt = 0L
    private var firstDownX = 0f
    private var firstDownY = 0f
    private var firstTapMoved = false
    private var lastTapUpAt = 0L
    private var lastTapUpX = 0f
    private var lastTapUpY = 0f

    private var doubleTapArmed = false
    private var draggingZoom = false
    private var secondDownX = 0f
    private var secondDownY = 0f
    private var startZoom = 15.0

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            onUserGesture()
            view.parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            view.parent?.requestDisallowInterceptTouchEvent(false)
        }

        // Hand off cleanly to osmdroid whenever a second finger touches down (pinch-to-zoom).
        if (event.pointerCount > 1 || action == MotionEvent.ACTION_POINTER_DOWN) {
            resetDoubleTap()
            return false
        }

        val now = SystemClock.uptimeMillis()
        return when (action) {
            MotionEvent.ACTION_DOWN -> {
                val isSecondTap = lastTapUpAt > 0L &&
                    (now - lastTapUpAt) <= doubleTapTimeoutMs &&
                    hypot(event.x - lastTapUpX, event.y - lastTapUpY) <= doubleTapSlop
                if (isSecondTap) {
                    doubleTapArmed = true
                    draggingZoom = false
                    secondDownX = event.x
                    secondDownY = event.y
                    startZoom = map.zoomLevelDouble
                    lastTapUpAt = 0L
                    // Consume down/move/up for this second tap so osmdroid's own double-tap or pan does not conflict.
                    true
                } else {
                    doubleTapArmed = false
                    draggingZoom = false
                    firstDownAt = now
                    firstDownX = event.x
                    firstDownY = event.y
                    firstTapMoved = false
                    false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (doubleTapArmed) {
                    val dy = event.y - secondDownY
                    if (!draggingZoom && hypot(event.x - secondDownX, dy) > touchSlop) {
                        draggingZoom = true
                    }
                    if (draggingZoom) {
                        val target = OneHandedZoomMath.nextZoom(
                            startZoom = startZoom,
                            deltaYPx = dy,
                            density = density,
                            minZoom = map.minZoomLevel,
                            maxZoom = map.maxZoomLevel
                        )
                        map.controller.setZoom(target)
                        map.invalidate()
                    }
                    true
                } else {
                    if (!firstTapMoved && hypot(event.x - firstDownX, event.y - firstDownY) > touchSlop) {
                        firstTapMoved = true
                    }
                    false
                }
            }

            MotionEvent.ACTION_UP -> {
                if (doubleTapArmed) {
                    val wasDragging = draggingZoom
                    val tapX = secondDownX.toInt()
                    val tapY = secondDownY.toInt()
                    resetDoubleTap()
                    if (!wasDragging) {
                        map.controller.zoomInFixing(tapX, tapY)
                    }
                    true
                } else {
                    if (!firstTapMoved && (now - firstDownAt) <= doubleTapTimeoutMs) {
                        lastTapUpAt = now
                        lastTapUpX = event.x
                        lastTapUpY = event.y
                    } else {
                        lastTapUpAt = 0L
                    }
                    false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                resetDoubleTap()
                lastTapUpAt = 0L
                false
            }

            else -> false
        }
    }

    private fun resetDoubleTap() {
        doubleTapArmed = false
        draggingZoom = false
    }
}
