package com.tvtime.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * An on-screen mouse pointer moved with the remote's D-pad.
 *
 * Holding a direction accelerates the pointer smoothly (driven by the display's frame
 * clock, not by key-repeat, so it feels the same on every remote). When the pointer is
 * pushed against an edge, [onEdgePush] is called so the page can be scrolled.
 */
class CursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    var cursorX = 0f
        private set
    var cursorY = 0f
        private set

    /** Pointer moved to (x, y) in this view's coordinates. */
    var onMoved: ((Float, Float) -> Unit)? = null

    /** Pointer is held against an edge. dx/dy are the scroll amounts in px for this frame. */
    var onEdgePush: ((Float, Float) -> Unit)? = null

    private var dirX = 0
    private var dirY = 0
    private var speed = 0f
    private var lastFrame = 0L
    private var running = false
    private var lastActivity = 0L
    private var initialized = false

    private val minSpeed = 260f * density      // px per second when a key is first held
    private val maxSpeed = 1500f * density     // px per second at full acceleration
    private val accel = 1700f * density        // px per second^2
    private val nudge = 6f * density           // instant step so short taps are precise
    private val radius = 13f * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x55FFFFFF
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        color = 0x99000000.toInt()
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFE50914.toInt()
    }

    private val hideAfterMs = 6000L
    private val autoHide = Runnable { if (SystemClock.uptimeMillis() - lastActivity >= hideAfterMs) hideCursor() }

    init {
        isFocusable = false
        isClickable = false
        visibility = INVISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized && w > 0 && h > 0) {
            cursorX = w / 2f
            cursorY = h / 2f
            initialized = true
        } else {
            cursorX = cursorX.coerceIn(0f, w.toFloat() - 1)
            cursorY = cursorY.coerceIn(0f, h.toFloat() - 1)
        }
    }

    val cursorVisible: Boolean get() = visibility == VISIBLE

    fun showCursor() {
        lastActivity = SystemClock.uptimeMillis()
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            invalidate()
        }
        removeCallbacks(autoHide)
        postDelayed(autoHide, hideAfterMs)
    }

    fun hideCursor() {
        removeCallbacks(autoHide)
        dirX = 0; dirY = 0; speed = 0f
        visibility = INVISIBLE
    }

    /** Called on key down (repeats are harmless). */
    fun press(dx: Int, dy: Int) {
        showCursor()
        val fresh = (dx != 0 && dirX != dx) || (dy != 0 && dirY != dy)
        if (dx != 0) dirX = dx
        if (dy != 0) dirY = dy
        if (fresh && speed == 0f) moveBy(dx * nudge, dy * nudge)
        if (!running) {
            running = true
            lastFrame = SystemClock.uptimeMillis()
            if (speed == 0f) speed = minSpeed
            postOnAnimation(tick)
        }
    }

    /** Called on key up. */
    fun release(dx: Int, dy: Int) {
        if (dx != 0 && dirX == dx) dirX = 0
        if (dy != 0 && dirY == dy) dirY = 0
        if (dirX == 0 && dirY == 0) speed = 0f
    }

    fun releaseAll() {
        dirX = 0; dirY = 0; speed = 0f
    }

    private val tick = object : Runnable {
        override fun run() {
            if (dirX == 0 && dirY == 0) {
                running = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrame).coerceIn(1L, 50L) / 1000f
            lastFrame = now
            speed = min(speed + accel * dt, maxSpeed)
            val step = speed * dt
            moveBy(dirX * step, dirY * step)
            lastActivity = now
            postOnAnimation(this)
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val nx = cursorX + dx
        val ny = cursorY + dy
        val margin = 2f
        var edgeX = 0f
        var edgeY = 0f
        if (nx < margin) edgeX = nx - margin else if (nx > w - margin) edgeX = nx - (w - margin)
        if (ny < margin) edgeY = ny - margin else if (ny > h - margin) edgeY = ny - (h - margin)
        cursorX = nx.coerceIn(margin, w - margin)
        cursorY = ny.coerceIn(margin, h - margin)
        invalidate()
        onMoved?.invoke(cursorX, cursorY)
        if (edgeX != 0f || edgeY != 0f) onEdgePush?.invoke(edgeX, edgeY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(cursorX, cursorY, radius, fill)
        canvas.drawCircle(cursorX, cursorY, radius, outline)
        canvas.drawCircle(cursorX, cursorY, radius, ring)
        canvas.drawCircle(cursorX, cursorY, 3f * density, dot)
    }
}
