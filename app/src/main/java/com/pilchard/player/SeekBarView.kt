package com.pilchard.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View

/**
 * The Cinematic progress bar. Select it with Up, then:
 *  Left / Right  jump back / forward (10 s, speeding up to 30 s and 60 s while held)
 *  OK            jump to that spot
 *  Back / Down   cancel and stay where you were
 */
class SeekBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onSeekPreview(sec: Long)
        fun onSeekCommit(sec: Long)
        fun onSeekCancel()
    }

    var listener: Listener? = null

    var durationSec: Long = 0
        set(value) { field = value; invalidate() }

    var positionSec: Long = 0
        set(value) { field = value; if (!isEditing) invalidate() }

    var chapterTimes: List<Double> = emptyList()
        set(value) { field = value; invalidate() }

    var isEditing = false
        private set

    private var pendingSec: Long = 0
    private val dp = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val gapPaint = Paint().apply { color = context.getColor(R.color.background) }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val rect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = false
    }

    fun cancel() {
        if (!isEditing) return
        isEditing = false
        listener?.onSeekCancel()
        invalidate()
    }

    /** Move the pending spot. [repeat] is how long the button has been held. */
    fun step(direction: Int, repeat: Int) {
        if (durationSec <= 0) return
        if (!isEditing) startEditing()
        val size = when {
            repeat < 6 -> 10L
            repeat < 20 -> 30L
            else -> 60L
        }
        pendingSec = (pendingSec + direction * size).coerceIn(0, durationSec)
        listener?.onSeekPreview(pendingSec)
        invalidate()
    }

    private fun startEditing() {
        isEditing = true
        pendingSec = positionSec
        listener?.onSeekPreview(pendingSec)
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) startEditing() else cancel()
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (isEditing) {
                    isEditing = false
                    listener?.onSeekCommit(pendingSec)
                    invalidate()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val focused = isFocused
        val barH = (if (focused) 10f else 6f) * dp
        val cy = height / 2f
        val left = 0f + 12f * dp
        val right = width - 12f * dp
        val w = right - left
        val radius = barH / 2f

        rect.set(left, cy - barH / 2, right, cy + barH / 2)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val shown = if (isEditing) pendingSec else positionSec
        val frac = if (durationSec > 0) (shown.toFloat() / durationSec).coerceIn(0f, 1f) else 0f
        rect.set(left, cy - barH / 2, left + w * frac, cy + barH / 2)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // Small gaps where chapters begin.
        if (durationSec > 0) {
            for (t in chapterTimes) {
                if (t <= 0.5) continue
                val x = left + w * (t / durationSec).toFloat().coerceIn(0f, 1f)
                canvas.drawRect(x - 1.5f * dp, cy - barH / 2, x + 1.5f * dp, cy + barH / 2, gapPaint)
            }
        }

        if (focused) {
            val x = left + w * frac
            canvas.drawCircle(x, cy, 14f * dp, haloPaint)
            canvas.drawCircle(x, cy, 9f * dp, thumbPaint)
        }
    }
}
