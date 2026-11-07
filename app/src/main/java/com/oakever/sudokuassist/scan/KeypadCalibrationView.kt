package com.oakever.sudokuassist.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** User marks the centres of keys 1 through 9 on the captured screenshot. */
class KeypadCalibrationView(
    context: Context,
    private val screenshot: Bitmap,
    private val onStepChanged: (Int) -> Unit,
    private val onComplete: (FloatArray) -> Unit
) : View(context) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(22, 184, 166)
        style = Paint.Style.FILL
    }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 18f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val points = FloatArray(18)
    private var count = 0

    private fun scale() = min(width.toFloat() / screenshot.width, height.toFloat() / screenshot.height)
    private fun offsetX() = (width - screenshot.width * scale()) / 2f
    private fun offsetY() = (height - screenshot.height * scale()) / 2f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(15, 24, 34))
        val s = scale()
        val l = offsetX()
        val t = offsetY()
        canvas.drawBitmap(screenshot, null, RectF(l, t, l + screenshot.width * s, t + screenshot.height * s), bitmapPaint)
        for (i in 0 until count) {
            val cx = l + points[i * 2] * s
            val cy = t + points[i * 2 + 1] * s
            canvas.drawCircle(cx, cy, 17f * resources.displayMetrics.density, markerPaint)
            canvas.drawText((i + 1).toString(), cx, cy + markerTextPaint.textSize * 0.34f, markerTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP || count >= 9) return true
        val s = scale()
        if (s <= 0f) return true
        val x = (event.x - offsetX()) / s
        val y = (event.y - offsetY()) / s
        if (x !in 0f..screenshot.width.toFloat() || y !in 0f..screenshot.height.toFloat()) return true
        points[count * 2] = x
        points[count * 2 + 1] = y
        count++
        if (count == 9) onComplete(points.copyOf()) else onStepChanged(count + 1)
        invalidate()
        return true
    }
}
