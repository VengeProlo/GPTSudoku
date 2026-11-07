package com.oakever.sudokuassist.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.math.abs

/**
 * Explicit opt-in input helper. It reads only window package-change events, not screen text or
 * view hierarchies. Gestures are sent only to the Oakever Sudoku package after user confirmation.
 */
class SudokuInputAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var queuedTaps: List<PointF> = emptyList()
    private var tapIndex = 0
    private var running = false
    private var waitingForGame = false
    private var lastWindowPackage: String? = null
    private var timeout: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        lastWindowPackage = packageName
        if (!running) return

        if (waitingForGame && packageName == TARGET_GAME_PACKAGE) {
            waitingForGame = false
            timeout?.let(main::removeCallbacks)
            timeout = null
            toast("Oakever Sudoku is active. Auto-fill will begin in a moment.")
            main.postDelayed({
                if (running && !waitingForGame && lastWindowPackage == TARGET_GAME_PACKAGE) dispatchNextTap()
            }, 900L)
        } else if (!waitingForGame && packageName != TARGET_GAME_PACKAGE) {
            cancel("You left Oakever Sudoku. Auto-fill stopped.")
        }
    }

    override fun onInterrupt() {
        cancel("Accessibility input was interrupted.")
    }

    fun startAutoFill(
        puzzle: IntArray,
        solution: IntArray,
        board: RectF,
        screenWidth: Int,
        screenHeight: Int,
        keypadPoints: FloatArray
    ): Boolean {
        if (running || puzzle.size != 81 || solution.size != 81 || keypadPoints.size != 18) return false
        if (screenWidth <= 0 || screenHeight <= 0 || board.width() <= 0f || board.height() <= 0f) return false
        if (abs(board.width() - board.height()) > 5f) return false
        if (board.left < 0f || board.top < 0f || board.right > screenWidth || board.bottom > screenHeight) return false
        if (puzzle.indices.any { puzzle[it] !in 0..9 || solution[it] !in 1..9 }) return false
        if (puzzle.indices.any { puzzle[it] != 0 && puzzle[it] != solution[it] }) return false

        val keyByDigit = Array(10) { PointF() }
        for (digit in 1..9) {
            val x = keypadPoints[(digit - 1) * 2]
            val y = keypadPoints[(digit - 1) * 2 + 1]
            if (!x.isFinite() || !y.isFinite() || x !in 0f..(screenWidth - 1).toFloat() || y !in 0f..(screenHeight - 1).toFloat()) return false
            keyByDigit[digit] = PointF(x, y)
        }

        val taps = ArrayList<PointF>()
        for (index in 0 until 81) {
            if (puzzle[index] != 0) continue
            val row = index / 9
            val col = index % 9
            taps += PointF(
                board.left + (col + 0.5f) * board.width() / 9f,
                board.top + (row + 0.5f) * board.height() / 9f
            )
            taps += keyByDigit[solution[index]]
        }
        if (taps.isEmpty()) return false

        queuedTaps = taps
        tapIndex = 0
        running = true
        waitingForGame = true
        timeout = Runnable {
            if (running && waitingForGame) cancel("Oakever Sudoku was not opened within 45 seconds. Auto-fill cancelled.")
        }.also { main.postDelayed(it, 45_000L) }

        // If the game is already in front, its next window event will trigger the first tap.
        // MainActivity returns to the previous task immediately after this method succeeds.
        if (lastWindowPackage == TARGET_GAME_PACKAGE) {
            waitingForGame = false
            timeout?.let(main::removeCallbacks)
            timeout = null
            dispatchNextTap()
        }
        return true
    }

    fun stopAutoFill() = cancel("Auto-fill stopped by the user.")

    private fun dispatchNextTap() {
        if (!running) return
        if (tapIndex >= queuedTaps.size) {
            running = false
            queuedTaps = emptyList()
            toast("Auto-fill finished. Check the puzzle for accepted entries.")
            return
        }
        val point = queuedTaps[tapIndex]
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 65L))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (!running) return
                tapIndex++
                main.postDelayed({ dispatchNextTap() }, 140L)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                cancel("The game cancelled an input tap. Auto-fill stopped.")
            }
        }, main)
        if (!accepted) cancel("Android rejected an input tap. Auto-fill stopped.")
    }

    private fun cancel(message: String?) {
        val hadWork = running
        running = false
        waitingForGame = false
        timeout?.let(main::removeCallbacks)
        timeout = null
        queuedTaps = emptyList()
        tapIndex = 0
        if (hadWork && !message.isNullOrBlank()) toast(message)
    }

    private fun toast(message: String) {
        main.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        cancel(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        cancel(null)
        super.onDestroy()
    }

    companion object {
        // Google Play listing for Oakever Games' "Sudoku - Classic Sudoku Puzzle".
        const val TARGET_GAME_PACKAGE = "easy.sudoku.puzzle.solver.free"

        @Volatile
        var instance: SudokuInputAccessibilityService? = null
            private set
    }
}
