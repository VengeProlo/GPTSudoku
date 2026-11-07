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

/** Square crop selector. Its returned rectangle is always in source-bitmap pixel coordinates. */
class BoardCropView(context: Context, private val source: Bitmap) : View(context) {
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(31, 221, 193)
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val shadePaint = Paint().apply { color = 0x99000000.toInt() }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val crop = RectF()
    private var dragMode = 0 // 0 none, 1 move, 2 resize
    private var previousX = 0f
    private var previousY = 0f
    private val density = resources.displayMetrics.density

    init {
        val size = min(source.width, source.height) * 0.90f
        crop.set(
            (source.width - size) / 2f,
            (source.height - size) / 2f,
            (source.width + size) / 2f,
            (source.height + size) / 2f
        )
        isClickable = true
    }

    private fun imageScale(): Float = min(width.toFloat() / source.width, height.toFloat() / source.height)
    private fun imageLeft(): Float = (width - source.width * imageScale()) / 2f
    private fun imageTop(): Float = (height - source.height * imageScale()) / 2f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(15, 24, 34))
        val scale = imageScale()
        val left = imageLeft()
        val top = imageTop()
        canvas.drawBitmap(source, null, RectF(left, top, left + source.width * scale, top + source.height * scale), imagePaint)

        val r = RectF(
            left + crop.left * scale,
            top + crop.top * scale,
            left + crop.right * scale,
            top + crop.bottom * scale
        )
        canvas.drawRect(left, top, left + source.width * scale, r.top, shadePaint)
        canvas.drawRect(left, r.bottom, left + source.width * scale, top + source.height * scale, shadePaint)
        canvas.drawRect(left, r.top, r.left, r.bottom, shadePaint)
        canvas.drawRect(r.right, r.top, left + source.width * scale, r.bottom, shadePaint)
        canvas.drawRect(r, borderPaint)
        canvas.drawCircle(r.right, r.bottom, 10f * density, handlePaint)
        canvas.drawCircle(r.right, r.bottom, 10f * density, borderPaint)
    }

    fun getCropRectInSource(): RectF = RectF(crop)

    fun getCroppedBitmap(): Bitmap {
        val left = crop.left.toInt().coerceIn(0, source.width - 2)
        val top = crop.top.toInt().coerceIn(0, source.height - 2)
        val size = min(crop.width().toInt(), min(source.width - left, source.height - top)).coerceAtLeast(2)
        return Bitmap.createBitmap(source, left, top, size, size)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scale = imageScale()
        if (scale <= 0f) return false
        val x = (event.x - imageLeft()) / scale
        val y = (event.y - imageTop()) / scale
        val handleRadius = 34f * density / scale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = x
                previousY = y
                dragMode = if (kotlin.math.hypot(x - crop.right, y - crop.bottom) <= handleRadius) 2
                else if (crop.contains(x, y)) 1 else 0
                return dragMode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - previousX
                val dy = y - previousY
                if (dragMode == 1) {
                    val tx = dx.coerceIn(-crop.left, source.width - crop.right)
                    val ty = dy.coerceIn(-crop.top, source.height - crop.bottom)
                    crop.offset(tx, ty)
                } else if (dragMode == 2) {
                    val maxSize = min(source.width - crop.left, source.height - crop.top)
                    val newSize = (maxOf(x - crop.left, y - crop.top)).coerceIn(120f, maxSize)
                    crop.right = crop.left + newSize
                    crop.bottom = crop.top + newSize
                }
                previousX = x
                previousY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = dragMode != 0
                dragMode = 0
                return handled
            }
        }
        return true
    }
}
