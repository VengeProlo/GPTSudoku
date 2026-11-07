package com.oakever.sudokuassist

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.oakever.sudokuassist.automation.SudokuInputAccessibilityService
import com.oakever.sudokuassist.capture.LiveCaptureService
import com.oakever.sudokuassist.core.SudokuSolver
import com.oakever.sudokuassist.overlay.SudokuOverlayService
import com.oakever.sudokuassist.scan.BoardCropView
import com.oakever.sudokuassist.scan.KeypadCalibrationView
import com.oakever.sudokuassist.scan.SudokuScanner
import kotlin.math.min

class MainActivity : Activity() {
    private var screenshot: Bitmap? = null
    private var liveScreenshot = false
    private var cropView: BoardCropView? = null
    private var boardBounds: RectF? = null
    private var keypadPoints: FloatArray? = null
    private var puzzle = IntArray(81)
    private var solution = IntArray(81)
    private var reviewFields: List<EditText> = emptyList()
    private var awaitingOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = DARK
        window.navigationBarColor = DARK
        handleIncomingIntent(intent)
        if (screenshot == null) showHome()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingOverlayPermission && Settings.canDrawOverlays(this)) {
            startScreenCaptureConsent()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != ACTION_REVIEW_CAPTURE) return
        val path = intent.getStringExtra(EXTRA_CAPTURE_PATH) ?: return
        val loaded = BitmapFactory.decodeFile(path)
        if (loaded == null) {
            toast("The captured screenshot could not be opened. Please scan again.")
            showHome()
            return
        }
        screenshot?.recycle()
        screenshot = loaded
        liveScreenshot = true
        boardBounds = null
        keypadPoints = null
        puzzle = IntArray(81)
        showCropScreen()
    }

    @Deprecated("Uses Android's system result callback for MediaProjection consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                if (awaitingOverlayPermission && Settings.canDrawOverlays(this)) startScreenCaptureConsent()
                else if (!Settings.canDrawOverlays(this)) toast("Display over other apps permission is needed for the floating SCAN control.")
            }
            REQUEST_CAPTURE_CONSENT -> {
                if (resultCode == RESULT_OK && data != null) startCaptureService(resultCode, data)
                else toast("Screen capture was cancelled. No screen content was captured.")
            }
            REQUEST_OVERLAY_ONLY -> {
                if (!Settings.canDrawOverlays(this)) toast("Display over other apps permission was not granted.")
            }
            REQUEST_IMAGE -> {
                if (resultCode == RESULT_OK) {
                    val uri = data?.data ?: return
                    try {
                        val input = contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Could not read the selected image")
                        val bitmap = input.use { BitmapFactory.decodeStream(it) }
                            ?: throw IllegalStateException("Image decoder returned no bitmap")
                        screenshot?.recycle()
                        screenshot = bitmap
                        liveScreenshot = false
                        boardBounds = null
                        keypadPoints = null
                        showCropScreen()
                    } catch (_: Exception) {
                        toast("Could not open that screenshot. Please choose another image.")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        screenshot?.recycle()
        screenshot = null
        super.onDestroy()
    }

    private fun showHome() {
        val page = page("SUDOKU ASSIST", "Scan. Verify. Solve. Enter.")
        addText(page, "Capture the Oakever Sudoku screen, align the 9×9 board, review the detected clues, and solve it with a uniqueness check.", 15f, MUTED)
        addSection(page, "HOW IT WORKS")
        addStep(page, "01", "Live screen scan", "Android asks for screen-capture consent. Use the floating SCAN control over the game.")
        addStep(page, "02", "Crop and review", "Align the square to the board. OCR reads cells individually; you can correct every digit.")
        addStep(page, "03", "Verify the answer", "The Kotlin solver rejects invalid or non-unique puzzles rather than guessing.")
        addStep(page, "04", "Overlay or auto-fill", "Show the missing digits over the game, or calibrate the keypad and explicitly start the tap feeder.")
        addButton(page, "Start live screen scan", true) { requestLiveCapture() }
        addButton(page, "Import a screenshot", false) { openImagePicker() }
        addButton(page, "Hide answer overlay", false) {
            startService(Intent(this, SudokuOverlayService::class.java).setAction(SudokuOverlayService.ACTION_STOP))
            toast("Answer overlay hidden.")
        }
        addNote(page, "Auto-fill is optional and requires enabling the Sudoku Assist Accessibility Service in Android Settings. The service sends taps only; it does not read screen text or inspect the game's interface. Use only while the Oakever Sudoku board and keypad are visible.")
        addText(page, "Independent helper app. Not affiliated with Oakever Games.", 11f, MUTED)
        setPage(page)
    }

    private fun requestLiveCapture() {
        if (!Settings.canDrawOverlays(this)) {
            awaitingOverlayPermission = true
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            return
        }
        startScreenCaptureConsent()
    }

    private fun startScreenCaptureConsent() {
        awaitingOverlayPermission = false
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE_CONSENT)
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, LiveCaptureService::class.java).apply {
            action = LiveCaptureService.ACTION_START
            putExtra(LiveCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(LiveCaptureService.EXTRA_RESULT_DATA, data)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            val page = page("LIVE CAPTURE READY", "Switch back to Oakever Sudoku.")
            addText(page, "A floating SCAN button will appear on top of the game. Keep the full puzzle and number keypad visible, then tap SCAN. The helper hides its floating control before capturing.", 15f, MUTED)
            addNote(page, "Android's screen-sharing notification remains visible while capture is active. Tap Stop capture in the notification or × on the floating control to end the session.")
            setPage(page)
            window.decorView.postDelayed({ moveTaskToBack(true) }, 650L)
        } catch (e: Exception) {
            toast("Could not start capture: ${e.message ?: "unknown error"}")
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    private fun showCropScreen() {
        val bitmap = screenshot ?: return showHome()
        val page = page("ALIGN THE BOARD", "Fit the square frame to the 9×9 grid.")
        addText(page, "Drag inside the frame to move it. Drag the lower-right round handle to resize it. Include all 81 cells, but not the outer game controls.", 13f, MUTED)
        val crop = BoardCropView(this, bitmap).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, screenshotPreviewHeight(bitmap)).apply {
                topMargin = dp(16)
                bottomMargin = dp(12)
            }
        }
        cropView = crop
        page.addView(crop)
        addButton(page, "Read the 81 cells", true) { scanBoard() }
        addButton(page, "Back to home", false) { showHome() }
        setPage(page)
    }

    private fun scanBoard() {
        val cropper = cropView ?: return
        val crop = try { cropper.getCroppedBitmap() } catch (_: Exception) { null }
        if (crop == null) {
            toast("Could not crop the board. Adjust the frame and try again.")
            return
        }
        boardBounds = cropper.getCropRectInSource()
        val loading = page("READING THE BOARD", "Scanning cells individually.")
        addText(loading, "OCR can miss or misread digits. Uncertain cells remain blank, and you will review all 81 cells before solving.", 14f, MUTED)
        setPage(loading)
        SudokuScanner().scan(crop) { values ->
            crop.recycle()
            puzzle = values.copyOf()
            if (!isFinishing) showReviewScreen()
        }
    }

    private fun showReviewScreen() {
        val page = page("CHECK THE SCAN", "Correct the clues before solving.")
        addText(page, "Tap a cell and enter a digit from 1 to 9. Leave empty cells blank. This review step protects against OCR mistakes.", 13f, MUTED)
        val (grid, fields) = makeEditableGrid(puzzle)
        reviewFields = fields
        page.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
            bottomMargin = dp(12)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        addButton(page, "Solve and verify unique answer", true) {
            val entered = IntArray(81) { index -> reviewFields[index].text.toString().toIntOrNull() ?: 0 }
            val result = SudokuSolver.solve(entered)
            when (result.status) {
                SudokuSolver.Status.UNIQUE -> {
                    puzzle = entered
                    solution = result.solution!!.copyOf()
                    showSolutionScreen(result.message)
                }
                else -> addText(page, result.message, 14f, WARNING)
            }
        }
        addButton(page, "Adjust crop", false) { showCropScreen() }
        addButton(page, "Back to home", false) { showHome() }
        setPage(page)
    }

    private fun showSolutionScreen(message: String) {
        val page = page("SOLUTION VERIFIED", message)
        val grid = makeReadOnlyGrid(puzzle, solution)
        page.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
            bottomMargin = dp(14)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        addText(page, "Original clues are white; solved cells are mint. The answer is only accepted when the solver finds exactly one solution.", 12f, MUTED)
        addButton(page, "Show floating answer over Sudoku", true) { showAnswerOverlay() }
        if (liveScreenshot) {
            addButton(page, "Calibrate keypad for auto-fill", false) { showCalibrationScreen() }
        } else {
            addNote(page, "Auto-fill is available after a live screen capture because keypad coordinates must match the device's current screen. Importing an image is for solving and reviewing only.")
        }
        addButton(page, "Edit detected clues", false) { showReviewScreen() }
        addButton(page, "New puzzle", false) {
            screenshot?.recycle()
            screenshot = null
            boardBounds = null
            keypadPoints = null
            showHome()
        }
        setPage(page)
    }

    private fun showAnswerOverlay() {
        val bounds = boardBounds
        val bitmap = screenshot
        if (bounds == null || bitmap == null) {
            toast("Board coordinates are missing. Scan the puzzle again.")
            return
        }
        val displayMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        if (bitmap.width != displayMetrics.widthPixels || bitmap.height != displayMetrics.heightPixels) {
            toast("For an aligned overlay, capture the current device screen live instead of using a screenshot from another device.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            val permissionIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(permissionIntent, REQUEST_OVERLAY_ONLY)
            toast("Grant Display over other apps, return here, then tap the overlay button again.")
            return
        }
        val intent = Intent(this, SudokuOverlayService::class.java).apply {
            putExtra(SudokuOverlayService.EXTRA_PUZZLE, puzzle)
            putExtra(SudokuOverlayService.EXTRA_SOLUTION, solution)
            putExtra(SudokuOverlayService.EXTRA_LEFT, bounds.left)
            putExtra(SudokuOverlayService.EXTRA_TOP, bounds.top)
            putExtra(SudokuOverlayService.EXTRA_RIGHT, bounds.right)
            putExtra(SudokuOverlayService.EXTRA_BOTTOM, bounds.bottom)
            putExtra(SudokuOverlayService.EXTRA_BITMAP_WIDTH, bitmap.width)
            putExtra(SudokuOverlayService.EXTRA_BITMAP_HEIGHT, bitmap.height)
        }
        try {
            startService(intent)
            toast("Answer overlay shown. Use the × at the top-right to hide it.")
            window.decorView.postDelayed({ moveTaskToBack(true) }, 350L)
        } catch (e: Exception) {
            toast("Could not show overlay: ${e.message ?: "unknown error"}")
        }
    }

    private fun showCalibrationScreen() {
        val bitmap = screenshot ?: return
        val page = page("CALIBRATE NUMBER KEYS", "Tap each digit's centre on the screenshot.")
        val step = TextView(this).apply {
            text = "Tap the centre of key 1. Then tap 2, 3 … through 9."
            setTextColor(MUTED)
            textSize = 13f
            setPadding(0, dp(8), 0, dp(10))
        }
        page.addView(step)
        val calibration = KeypadCalibrationView(
            this,
            bitmap,
            onStepChanged = { digit -> step.text = "Now tap the centre of key $digit." },
            onComplete = { points ->
                keypadPoints = points
                showAutoFillScreen()
            }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, screenshotPreviewHeight(bitmap))
        }
        page.addView(calibration)
        addButton(page, "Cancel calibration", false) { showSolutionScreen("One unique solution was verified.") }
        setPage(page)
    }

    private fun showAutoFillScreen() {
        val page = page("AUTO-FILL READY", "Keypad coordinates saved for this screenshot.")
        addText(page, "Before starting, return to the same Oakever Sudoku puzzle. Make sure the board and number keypad are still in the same positions and the game is ready for normal cell → number entry.", 14f, MUTED)
        addNote(page, "The helper will tap only blank cells, then tap the matching number key. It waits for the Oakever Sudoku app to become the foreground app. Keep the device awake and do not move or resize the game while it runs.")
        addButton(page, "Enable / open Accessibility Settings", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton(page, "Start auto-fill", true) { startAutoFill() }
        addButton(page, "Back to solution", false) { showSolutionScreen("One unique solution was verified.") }
        setPage(page)
    }

    private fun startAutoFill() {
        if (!isAccessibilityServiceEnabled()) {
            toast("Enable Sudoku Assist Auto Feeder in Android Accessibility Settings first.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val service = SudokuInputAccessibilityService.instance
        if (service == null) {
            toast("Accessibility service is not connected yet. Return after enabling it and try again.")
            return
        }
        val bounds = boardBounds
        val keys = keypadPoints
        val bitmap = screenshot
        if (bounds == null || keys == null || bitmap == null) {
            toast("Board or keypad calibration is missing.")
            return
        }
        val currentMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(currentMetrics)
        if (bitmap.width != currentMetrics.widthPixels || bitmap.height != currentMetrics.heightPixels) {
            toast("The device display changed since capture. Scan again and recalibrate the keypad.")
            return
        }
        val started = service.startAutoFill(puzzle, solution, bounds, bitmap.width, bitmap.height, keys)
        if (!started) {
            toast("Auto-fill could not start. Recheck the scan and keypad calibration.")
            return
        }
        toast("Auto-fill queued. Returning to the previous app; the helper will tap only in Oakever Sudoku.")
        window.decorView.postDelayed({ moveTaskToBack(true) }, 350L)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val expected = ComponentName(this, SudokuInputAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun makeEditableGrid(values: IntArray): Pair<GridLayout, List<EditText>> {
        val grid = GridLayout(this).apply { columnCount = 9; rowCount = 9 }
        val cell = dp(min(40f, ((resources.displayMetrics.widthPixels / resources.displayMetrics.density) - 60f) / 9f).toInt())
        val fields = ArrayList<EditText>(81)
        for (i in 0 until 81) {
            val field = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(1), InputFilter { source, start, end, _, _, _ ->
                    val typed = source?.subSequence(start, end)?.toString().orEmpty()
                    if (typed.isEmpty() || typed.all { it in '1'..'9' }) null else ""
                })
                setText(if (values[i] == 0) "" else values[i].toString())
                setTextColor(WHITE)
                setHintTextColor(MUTED)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                background = cellBackground()
                isSingleLine = true
                setSelectAllOnFocus(true)
            }
            val params = GridLayout.LayoutParams(GridLayout.spec(i / 9), GridLayout.spec(i % 9)).apply {
                width = cell
                height = cell
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            grid.addView(field, params)
            fields.add(field)
        }
        return grid to fields
    }

    private fun makeReadOnlyGrid(original: IntArray, solved: IntArray): GridLayout {
        val grid = GridLayout(this).apply { columnCount = 9; rowCount = 9 }
        val cell = dp(min(40f, ((resources.displayMetrics.widthPixels / resources.displayMetrics.density) - 60f) / 9f).toInt())
        for (i in 0 until 81) {
            val value = TextView(this).apply {
                text = solved[i].toString()
                gravity = Gravity.CENTER
                textSize = 16f
                setTextColor(if (original[i] == 0) MINT else WHITE)
                setBackgroundColor(if (original[i] == 0) 0xFF203640.toInt() else 0xFF1A2935.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            grid.addView(value, GridLayout.LayoutParams(GridLayout.spec(i / 9), GridLayout.spec(i % 9)).apply {
                width = cell
                height = cell
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
        return grid
    }

    private fun cellBackground() = GradientDrawable().apply {
        setColor(0xFF1A2935.toInt())
        setStroke(dp(1), 0xFF415365.toInt())
        cornerRadius = dp(3).toFloat()
    }

    private fun page(heading: String, subtitle: String): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(DARK)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(DARK)
            addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        addText(layout, heading, 11f, MINT, true)
        addText(layout, subtitle, 26f, WHITE, true, top = 7)
        return layout
    }

    private fun setPage(layout: LinearLayout) {
        // page() attaches the scroll container before child buttons are appended.
        layout.requestLayout()
    }

    private fun addText(parent: LinearLayout, value: String, size: Float, color: Int, bold: Boolean = false, top: Int = 10) {
        val text = TextView(this).apply {
            this.text = value
            setTextColor(color)
            textSize = size
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(top), 0, dp(4))
        }
        parent.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addSection(parent: LinearLayout, value: String) {
        addText(parent, value, 11f, MINT, true, 20)
    }

    private fun addStep(parent: LinearLayout, number: String, title: String, description: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, dp(9))
        }
        val num = TextView(this).apply {
            text = number
            setTextColor(MINT)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), dp(12), 0)
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addText(body, title, 15f, WHITE, true, 0)
        addText(body, description, 12f, MUTED, false, 2)
        row.addView(num)
        row.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
    }

    private fun addNote(parent: LinearLayout, value: String) {
        val note = TextView(this).apply {
            text = value
            textSize = 12f
            setTextColor(0xFFFFD38A.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(0xFF2B2B25.toInt())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF6A5940.toInt())
            }
        }
        parent.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
            bottomMargin = dp(8)
        })
    }

    private fun addButton(parent: LinearLayout, label: String, primary: Boolean, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(if (primary) Color.rgb(7, 29, 32) else WHITE)
            background = GradientDrawable().apply {
                setColor(if (primary) MINT else 0xFF233442.toInt())
                cornerRadius = dp(11).toFloat()
                if (!primary) setStroke(dp(1), 0xFF405565.toInt())
            }
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(10)
        })
    }

    private fun screenshotPreviewHeight(bitmap: Bitmap): Int {
        val density = resources.displayMetrics.density
        val availableWidthDp = (resources.displayMetrics.widthPixels / density - 40f).coerceAtLeast(240f)
        val desiredHeightDp = availableWidthDp * bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
        return dp(desiredHeightDp.coerceIn(430f, 700f).toInt())
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val ACTION_REVIEW_CAPTURE = "com.oakever.sudokuassist.REVIEW_CAPTURE"
        const val EXTRA_CAPTURE_PATH = "capture_path"
        private const val REQUEST_OVERLAY_PERMISSION = 5101
        private const val REQUEST_CAPTURE_CONSENT = 5102
        private const val REQUEST_IMAGE = 5103
        private const val REQUEST_OVERLAY_ONLY = 5104
        private const val DARK = 0xFF101923.toInt()
        private const val WHITE = 0xFFF3F7FA.toInt()
        private const val MUTED = 0xFFB0BEC8.toInt()
        private const val MINT = 0xFF39D6C4.toInt()
        private const val WARNING = 0xFFFFC36E.toInt()
    }
}
