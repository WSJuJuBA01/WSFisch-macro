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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.wsfisch.mobile.action.START_CAPTURE"
        const val ACTION_STOP = "com.wsfisch.mobile.action.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "mobile_macro_capture"
        private const val NOTIFICATION_ID = 4210

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }

    private data class Frame(
        val width: Int,
        val height: Int,
        val pixels: IntArray
    )

    private val latestFrame = AtomicReference<Frame?>(null)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    private var foregroundStarted = false
    private var releasing = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent, startId)

            ACTION_STOP -> {
                releaseCapture(stopProjection = true)
                stopSelf(startId)
            }

            else -> stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent, startId: Int) {
        if (projection != null) {
            releaseCapture(stopProjection = true)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            stopSelf(startId)
            return
        }

        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification()
            )
            foregroundStarted = true

            val manager =
                getSystemService(MediaProjectionManager::class.java)

            val newProjection =
                manager.getMediaProjection(resultCode, resultData)

            projection = newProjection

            newProjection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        releaseCapture(stopProjection = false)
                        stopSelf()
                    }
                },
                Handler(mainLooper)
            )

            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels

            val reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            imageReader = reader

            val thread = HandlerThread("WSFischFrameReader")
            thread.start()

            imageThread = thread

            val handler = Handler(thread.looper)
            imageHandler = handler

            reader.setOnImageAvailableListener(
                { source ->
                    copyLatestFrame(source)
                },
                handler
            )

            virtualDisplay = newProjection.createVirtualDisplay(
                "WSFischMobileCapture",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )

            if (virtualDisplay == null) {
                throw IllegalStateException(
                    "VirtualDisplay could not be created"
                )
            }

        } catch (e: Exception) {
            android.util.Log.e(
                "WSFischCapture",
                "Capture startup failed",
                e
            )

            releaseCapture(stopProjection = true)
            stopSelf(startId)
        }
    }

    private fun copyLatestFrame(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        image.use { frame ->
            try {
                val plane = frame.planes.firstOrNull() ?: return
                val buffer = plane.buffer

                val pixels = IntArray(frame.width * frame.height)

                for (y in 0 until frame.height) {
                    for (x in 0 until frame.width) {
                        val offset =
                            y * plane.rowStride + x * plane.pixelStride

                        val red =
                            buffer.get(offset).toInt() and 0xff

                        val green =
                            buffer.get(offset + 1).toInt() and 0xff

                        val blue =
                            buffer.get(offset + 2).toInt() and 0xff

                        val alpha =
                            if (plane.pixelStride >= 4) {
                                buffer.get(offset + 3).toInt() and 0xff
                            } else {
                                0xff
                            }

                        pixels[y * frame.width + x] =
                            (alpha shl 24) or
                            (red shl 16) or
                            (green shl 8) or
                            blue
                    }
                }

                latestFrame.set(
                    Frame(frame.width, frame.height, pixels)
                )
            } catch (e: Exception) {
                android.util.Log.w(
                    "WSFischCapture",
                    "Frame copy failed",
                    e
                )
            }
        }
    }

    fun pixelColor(x: Int, y: Int): Int {
        val frame = latestFrame.get()
            ?: throw IllegalStateException(
                "Waiting for the first screen frame"
            )

        require(
            x in 0 until frame.width &&
            y in 0 until frame.height
        ) {
            "Coordinates outside ${frame.width}x${frame.height}"
        }

        return frame.pixels[y * frame.width + x]
    }

    fun findPixel(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        color: Int,
        tolerance: Int
    ): IntArray? {
        require(tolerance in 0..255)

        val frame = latestFrame.get()
            ?: throw IllegalStateException(
                "Waiting for the first screen frame"
            )

        val left = minOf(x1, x2).coerceIn(0, frame.width - 1)
        val right = maxOf(x1, x2).coerceIn(0, frame.width - 1)
        val top = minOf(y1, y2).coerceIn(0, frame.height - 1)
        val bottom = maxOf(y1, y2).coerceIn(0, frame.height - 1)

        val targetR = (color shr 16) and 0xff
        val targetG = (color shr 8) and 0xff
        val targetB = color and 0xff

        for (y in top..bottom) {
            for (x in left..right) {
                val pixel = frame.pixels[y * frame.width + x]

                val red = (pixel shr 16) and 0xff
                val green = (pixel shr 8) and 0xff
                val blue = pixel and 0xff

                if (
                    abs(red - targetR) <= tolerance &&
                    abs(green - targetG) <= tolerance &&
                    abs(blue - targetB) <= tolerance
                ) {
                    return intArrayOf(x, y)
                }
            }
        }

        return null
    }

    private fun releaseCapture(stopProjection: Boolean) {
        if (releasing) return
        releasing = true

        try {
            val oldProjection = projection
            projection = null

            try {
                virtualDisplay?.release()
            } catch (_: Exception) {
            }
            virtualDisplay = null

            try {
                imageReader?.setOnImageAvailableListener(null, null)
                imageReader?.close()
            } catch (_: Exception) {
            }
            imageReader = null

            latestFrame.set(null)

            try {
                imageThread?.quitSafely()
            } catch (_: Exception) {
            }

            imageThread = null
            imageHandler = null

            if (stopProjection) {
                try {
                    oldProjection?.stop()
                } catch (_: Exception) {
                }
            }

            if (foregroundStarted) {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {
                }
                foregroundStarted = false
            }
        } finally {
            releasing = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WSFisch screen capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("WSFisch screen capture")
            .setContentText("Screen capture is active")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        releaseCapture(stopProjection = true)

        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
