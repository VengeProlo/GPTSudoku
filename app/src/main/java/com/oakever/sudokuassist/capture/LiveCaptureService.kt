package com.oakever.sudokuassist.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import android.content.pm.ServiceInfo
import com.oakever.sudokuassist.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** One-session MediaProjection capture. Android's system consent is requested by MainActivity. */
class LiveCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var windowManager: WindowManager? = null
    private var floatingControls: View? = null
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    private val pendingCapture = AtomicBoolean(false)
    private val captureScheduled = AtomicBoolean(false)
    private var attempts = 0
    private var released = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START -> startCapture(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (projection != null) return
        startAsForeground()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || resultData == null) {
            toast("Screen capture permission data is missing.")
            stopCapture()
            return
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            val activeProjection = projection
            if (activeProjection == null) {
                toast("Android did not provide a screen-capture session.")
                stopCapture()
                return
            }

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            densityDpi = metrics.densityDpi
            if (screenWidth <= 0 || screenHeight <= 0) throw IllegalStateException("Invalid display size")

            workerThread = HandlerThread("SudokuAssistCapture").also { it.start() }
            val captureHandler = Handler(workerThread!!.looper)
            worker = captureHandler
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
            imageReader?.setOnImageAvailableListener({ reader ->
                if (pendingCapture.get()) {
                    captureLatest(reader)
                } else {
                    // Keep the ImageReader queue flowing while idle so the next scan is fresh.
                    try { reader.acquireLatestImage()?.close() } catch (_: Exception) { }
                }
            }, captureHandler)

            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }
            activeProjection.registerCallback(projectionCallback!!, captureHandler)
            virtualDisplay = activeProjection.createVirtualDisplay(
                "SudokuAssistDisplay",
                screenWidth,
                screenHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                captureHandler
            )
            if (virtualDisplay == null) throw IllegalStateException("Could not create virtual display")
            showFloatingControls()
        } catch (e: Exception) {
            toast("Could not start screen capture: ${e.message ?: "unknown error"}")
            stopCapture()
        }
    }

    private fun startAsForeground() {
        val channelId = "sudoku_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Sudoku screen capture", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, LiveCaptureService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 31, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val stopIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        val notification: Notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Sudoku Assist is capturing your screen")
            .setContentText("Tap SCAN in the floating control, or stop capture here.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(stopIcon, "Stop capture", stopPending).build())
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showFloatingControls() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xEE14232E.toInt())
        }
        val scan = Button(this).apply {
            text = "SCAN"
            isAllCaps = false
            setOnClickListener { requestCapture() }
        }
        val stop = Button(this).apply {
            text = "×"
            isAllCaps = false
            setOnClickListener { stopCapture() }
        }
        row.addView(scan, LinearLayout.LayoutParams(dp(92), dp(48)))
        row.addView(stop, LinearLayout.LayoutParams(dp(48), dp(48)))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(180)
        }
        try {
            windowManager?.addView(row, params)
            floatingControls = row
        } catch (e: Exception) {
            toast("Could not show the floating SCAN control. Check overlay permission.")
            stopCapture()
        }
    }

    private fun requestCapture() {
        if (!captureScheduled.compareAndSet(false, true)) return
        attempts = 0
        floatingControls?.visibility = View.INVISIBLE
        // Drain/discard frames while the button disappears, then accept a fresh frame.
        worker?.postDelayed({
            pendingCapture.set(true)
            tryCaptureNow()
        }, 450L) ?: failCapture("The capture worker is not ready.")
    }

    private fun tryCaptureNow() {
        if (!pendingCapture.get()) return
        val reader = imageReader
        if (reader != null) captureLatest(reader)
        else failCapture("The screen reader is not ready.")
    }

    private fun captureLatest(reader: ImageReader) {
        if (!pendingCapture.get()) return
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) {
            attempts++
            if (attempts < 25) worker?.postDelayed({ tryCaptureNow() }, 80L)
            else failCapture("No screen frame arrived. Please tap SCAN again.")
            return
        }

        try {
            val bitmap = imageToBitmap(image)
            val output = File(cacheDir, "sudoku_capture_${System.currentTimeMillis()}.png")
            FileOutputStream(output).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IllegalStateException("Could not save screenshot")
                }
            }
            bitmap.recycle()
            pendingCapture.set(false)
            captureScheduled.set(false)
            val openApp = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_REVIEW_CAPTURE
                putExtra(MainActivity.EXTRA_CAPTURE_PATH, output.absolutePath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(openApp)
            stopCapture()
        } catch (e: Exception) {
            failCapture("Screenshot failed: ${e.message ?: "unknown error"}")
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: throw IllegalStateException("No screenshot pixels")
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return result
    }

    private fun failCapture(message: String) {
        pendingCapture.set(false)
        captureScheduled.set(false)
        floatingControls?.visibility = View.VISIBLE
        toast(message)
    }

    private fun stopCapture() {
        if (released) {
            stopSelf()
            return
        }
        released = true
        pendingCapture.set(false)
        captureScheduled.set(false)
        try {
            floatingControls?.let { view ->
                try { windowManager?.removeView(view) } catch (_: Exception) { }
            }
            floatingControls = null
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
            projectionCallback?.let { callback ->
                try { projection?.unregisterCallback(callback) } catch (_: Exception) { }
            }
            projectionCallback = null
            val oldProjection = projection
            projection = null
            try { oldProjection?.stop() } catch (_: Exception) { }
            workerThread?.quitSafely()
            workerThread = null
            worker = null
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } finally {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (!released) stopCapture()
        super.onDestroy()
    }

    private fun toast(message: String) {
        Handler(mainLooper).post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_START = "com.oakever.sudokuassist.capture.START"
        const val ACTION_STOP = "com.oakever.sudokuassist.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 410
    }
}
