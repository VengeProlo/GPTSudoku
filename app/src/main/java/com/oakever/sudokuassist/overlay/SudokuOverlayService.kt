package com.oakever.sudokuassist.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.graphics.PixelFormat

/** Draws only the solved values in originally blank cells over the current screen. */
class SudokuOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var answerView: View? = null
    private var closeView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopOverlay()
            return START_NOT_STICKY
        }
        val puzzle = intent?.getIntArrayExtra(EXTRA_PUZZLE)
        val solution = intent?.getIntArrayExtra(EXTRA_SOLUTION)
        val bounds = RectF(
            intent?.getFloatExtra(EXTRA_LEFT, 0f) ?: 0f,
            intent?.getFloatExtra(EXTRA_TOP, 0f) ?: 0f,
            intent?.getFloatExtra(EXTRA_RIGHT, 0f) ?: 0f,
            intent?.getFloatExtra(EXTRA_BOTTOM, 0f) ?: 0f
        )
        val bitmapWidth = intent?.getIntExtra(EXTRA_BITMAP_WIDTH, 0) ?: 0
        val bitmapHeight = intent?.getIntExtra(EXTRA_BITMAP_HEIGHT, 0) ?: 0
        if (puzzle == null || solution == null || puzzle.size != 81 || solution.size != 81 ||
            bitmapWidth <= 0 || bitmapHeight <= 0 || bounds.width() <= 0f || bounds.height() <= 0f
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlay(puzzle, solution, bounds, bitmapWidth, bitmapHeight)
        return START_NOT_STICKY
    }

    private fun showOverlay(puzzle: IntArray, solution: IntArray, bounds: RectF, bitmapWidth: Int, bitmapHeight: Int) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        removeViews()

        val drawingView = object : View(this) {
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(48, 255, 188)
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            }
            private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                style = Paint.Style.STROKE
                strokeWidth = 3f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val sx = width.toFloat() / bitmapWidth
                val sy = height.toFloat() / bitmapHeight
                val board = RectF(bounds.left * sx, bounds.top * sy, bounds.right * sx, bounds.bottom * sy)
                val cellW = board.width() / 9f
                val cellH = board.height() / 9f
                fill.textSize = cellH * 0.62f
                outline.textSize = fill.textSize
                for (index in 0 until 81) {
                    if (puzzle[index] != 0) continue
                    val row = index / 9
                    val col = index % 9
                    val x = board.left + (col + 0.5f) * cellW
                    val y = board.top + (row + 0.5f) * cellH - (fill.ascent() + fill.descent()) / 2f
                    val value = solution[index].toString()
                    canvas.drawText(value, x, y, outline)
                    canvas.drawText(value, x, y, fill)
                }
            }
        }
        answerView = drawingView
        val wm = windowManager ?: run {
            stopSelf()
            return
        }
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val mainParams = WindowManager.LayoutParams(
            dm.widthPixels,
            dm.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        try {
            wm.addView(drawingView, mainParams)
            val close = TextView(this).apply {
                text = "×"
                textSize = 25f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundColor(0xDD14232E.toInt())
                setOnClickListener { stopOverlay() }
            }
            closeView = close
            val closeParams = WindowManager.LayoutParams(
                dp(48), dp(48),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(8)
                y = dp(55)
            }
            wm.addView(close, closeParams)
        } catch (_: Exception) {
            removeViews()
            stopSelf()
        }
    }

    private fun stopOverlay() {
        removeViews()
        stopSelf()
    }

    private fun removeViews() {
        try { answerView?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        try { closeView?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        answerView = null
        closeView = null
    }

    override fun onDestroy() {
        removeViews()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "com.oakever.sudokuassist.overlay.STOP"
        const val EXTRA_PUZZLE = "puzzle"
        const val EXTRA_SOLUTION = "solution"
        const val EXTRA_LEFT = "left"
        const val EXTRA_TOP = "top"
        const val EXTRA_RIGHT = "right"
        const val EXTRA_BOTTOM = "bottom"
        const val EXTRA_BITMAP_WIDTH = "bitmap_width"
        const val EXTRA_BITMAP_HEIGHT = "bitmap_height"
    }
}
