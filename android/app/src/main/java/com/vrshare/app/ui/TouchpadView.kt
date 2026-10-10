package com.vrshare.app.ui

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Laptop-style touchpad:
 * - one finger drag → onMove(dx, dy)   (relative pixels, throttled to ~60 Hz)
 * - tap (<300 ms, no drift) → onTap()  (left click)
 * - press-and-hold 500 ms → onLongPress() (right click)
 * - two-finger drag → onScroll(dx, dy) (dy positive = scroll up, spec §3.11)
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onMove(dx: Int, dy: Int)
        fun onTap()
        fun onLongPress()
        fun onScroll(dx: Int, dy: Int)
    }

    var listener: Listener? = null

    private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private var activeId = -1
    private var secondId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var sLastX = 0f
    private var sLastY = 0f
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var longPressFired = false
    private var accDx = 0f
    private var accDy = 0f
    private var lastFlush = 0L

    private val longPressRunnable = Runnable {
        if (activeId != -1 && secondId == -1) {
            longPressFired = true
            listener?.onLongPress()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeId = e.getPointerId(0)
                lastX = e.x; lastY = e.y
                downX = e.x; downY = e.y
                downTime = SystemClock.uptimeMillis()
                longPressFired = false
                accDx = 0f; accDy = 0f
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, 500)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                secondId = e.getPointerId(e.actionIndex)
                sLastX = e.getX(e.actionIndex)
                sLastY = e.getY(e.actionIndex)
                removeCallbacks(longPressRunnable)
            }

            MotionEvent.ACTION_MOVE -> {
                if (secondId != -1) {
                    val i = e.findPointerIndex(secondId)
                    if (i >= 0) {
                        val x = e.getX(i); val y = e.getY(i)
                        accDx += x - sLastX
                        accDy += y - sLastY
                        sLastX = x; sLastY = y
                    }
                } else if (activeId != -1) {
                    val i = e.findPointerIndex(activeId)
                    if (i >= 0) {
                        val x = e.getX(i); val y = e.getY(i)
                        accDx += x - lastX
                        accDy += y - lastY
                        lastX = x; lastY = y
                        if (hypot(x - downX, y - downY) > tapSlop) {
                            removeCallbacks(longPressRunnable)
                        }
                    }
                }
                maybeFlush()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == secondId) {
                    flush(scrolling = true)
                    secondId = -1
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                flush(scrolling = secondId != -1)
                if (e.actionMasked == MotionEvent.ACTION_UP && !longPressFired && secondId == -1) {
                    val dt = SystemClock.uptimeMillis() - downTime
                    val dist = hypot(e.x - downX, e.y - downY)
                    if (dt < 300 && dist < tapSlop) listener?.onTap()
                }
                activeId = -1
                secondId = -1
                accDx = 0f; accDy = 0f
            }
        }
        return true
    }

    private fun maybeFlush() {
        if (SystemClock.uptimeMillis() - lastFlush >= 16) flush(scrolling = secondId != -1)
    }

    private fun flush(scrolling: Boolean) {
        lastFlush = SystemClock.uptimeMillis()
        val dx = accDx.toInt()
        val dy = accDy.toInt()
        accDx -= dx
        accDy -= dy
        if (dx == 0 && dy == 0) return
        if (scrolling) listener?.onScroll(dx, -dy) // swipe up (screen dy<0) => scroll up (dy>0)
        else listener?.onMove(dx, dy)
    }
}
