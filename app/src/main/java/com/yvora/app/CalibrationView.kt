package com.yvora.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** Schematic of the real analysis coordinates; no additional camera stream. */
class CalibrationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = ContextCompat.getColor(context, R.color.divider)
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val dot = ContextCompat.getColor(context, R.color.accent_green)
    private var range = ControlRange()
    private var handX = Float.NaN
    private var handY = Float.NaN
    private var aspect = 0.75f

    internal fun setRange(value: ControlRange) {
        range = value
        invalidate()
    }

    internal fun setSample(x: Float, y: Float, frameWidth: Int, frameHeight: Int) {
        handX = x
        handY = y
        if (frameWidth > 0 && frameHeight > 0) aspect = frameWidth.toFloat() / frameHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 8f * resources.displayMetrics.density
        val availableW = (width - 2 * pad).coerceAtLeast(0f)
        val availableH = (height - 2 * pad).coerceAtLeast(0f)
        val w = minOf(availableW, availableH * aspect)
        val h = w / aspect
        val x = (width - w) / 2
        val y = (height - h) / 2
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * resources.displayMetrics.density
        paint.color = border
        canvas.drawRect(x, y, x + w, y + h, paint)
        paint.color = accent
        canvas.drawRect(x + range.left * w, y + range.top * h,
            x + range.right * w, y + range.bottom * h, paint)
        if (handX.isFinite() && handY.isFinite()) {
            paint.style = Paint.Style.FILL
            paint.color = dot
            canvas.drawCircle(x + handX.coerceIn(0f, 1f) * w,
                y + handY.coerceIn(0f, 1f) * h, 4f * resources.displayMetrics.density, paint)
        }
    }
}
