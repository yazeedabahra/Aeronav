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
    private var ranges=HandRanges()
    private var selected=0
    private val xs=FloatArray(2){Float.NaN}
    private val ys=FloatArray(2){Float.NaN}
    private var aspect = 0.75f
    private val colors=intArrayOf(android.graphics.Color.rgb(56,189,248),android.graphics.Color.rgb(250,180,65))

    internal fun setRanges(value:HandRanges,slot:Int) {
        ranges=value;selected=slot;invalidate()
    }

    internal fun setSamples(leftX:Float,leftY:Float,rightX:Float,rightY:Float,frameWidth:Int,frameHeight:Int) {
        xs[0]=leftX;ys[0]=leftY;xs[1]=rightX;ys[1]=rightY
        if(frameWidth>0 && frameHeight>0) aspect=frameWidth.toFloat()/frameHeight
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
        val order=if(ranges.linked) intArrayOf(selected) else intArrayOf(1-selected,selected)
        for(slot in order) {
            val range=ranges.effective(slot)
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=(if(slot==selected) 3f else 1.5f)*resources.displayMetrics.density
            paint.color=if(ranges.linked) accent else colors[slot]
            canvas.drawRect(x+range.left*w,y+range.top*h,x+range.right*w,y+range.bottom*h,paint)
        }
        for(slot in 0..1) if(xs[slot].isFinite() && ys[slot].isFinite()) {
            paint.style=Paint.Style.FILL;paint.color=colors[slot]
            canvas.drawCircle(x+xs[slot].coerceIn(0f,1f)*w,y+ys[slot].coerceIn(0f,1f)*h,
                4f*resources.displayMetrics.density,paint)
        }
    }
}
