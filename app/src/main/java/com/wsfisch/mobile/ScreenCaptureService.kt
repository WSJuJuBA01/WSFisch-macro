package com.wsfisch.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/** Owns one user-approved MediaProjection session and exposes sampled screen pixels. */
class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.wsfisch.mobile.action.START_CAPTURE"
        const val ACTION_STOP = "com.wsfisch.mobile.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 4210

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent, startId)
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
            else -> stopSelf(startId)
        }
        // Do not recreate a projection without a fresh, explicit user consent.
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent, startId: Int) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || resultData == null) {
            stopSelf(startId)
            return
        }

        // The user-approved result is received before the service starts. Promote promptly,
        // then consume the projection grant once for this session.
        startForeground(NOTIFICATION_ID, buildNotification())
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val newProjection = manager.getMediaProjection(resultCode, resultData)
            projection = newProjection
            newProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    stopSelf()
                }
            }, null)

            val (width, height, densityDpi) = displayMetrics()
            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = newReader
            display = newProjection.createVirtualDisplay(
                "WSFisch screen capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                null,
                null
            )
        } catch (e: Exception) {
            stopCapture()
            stopSelf(startId)
            throw e
        }
    }

    /** Returns an Android ARGB color; coordinates are physical display pixels. */
    fun pixelColor(x: Int, y: Int): Int {
        val image = reader?.acquireLatestImage()
            ?: throw IllegalStateException("No screen frame yet; is capture allowed and active?")
        image.use {
            require(x in 0 until it.width && y in 0 until it.height) {
                "Pixel ($x,$y) is outside the captured display (${it.width}x${it.height})"
            }
            val plane = it.planes[0]
            val buffer = plane.buffer
            val offset = y * plane.rowStride + x * plane.pixelStride
            val red = buffer.get(offset).toInt() and 0xff
            val green = buffer.get(offset + 1).toInt() and 0xff
            val blue = buffer.get(offset + 2).toInt() and 0xff
            val alpha = if (plane.pixelStride >= 4) buffer.get(offset + 3).toInt() and 0xff else 0xff
            return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    private fun displayMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun stopCapture() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.let {
            // Avoid recursively handling our own stop callback.
            projection = null
            it.stop()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while screen capture is active" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("WSFisch screen capture")
        .setContentText("Screen capture is active")
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        stopCapture()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
