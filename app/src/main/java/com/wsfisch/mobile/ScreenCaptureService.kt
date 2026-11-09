package com.wsfisch.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
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
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START =
            "com.wsfisch.mobile.CAPTURE_START"

        const val ACTION_STOP =
            "com.wsfisch.mobile.CAPTURE_STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "wsfisch_capture"
        private const val NOTIFICATION_ID = 4102

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var handlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val latestFrame =
        AtomicReference<CapturedFrame?>(null)

    private val sequence = AtomicLong(0L)

    @Volatile
    private var captureWidth = 0

    @Volatile
    private var captureHeight = 0

    @Volatile
    private var captureStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification()
        )
        instance = this
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(
                    EXTRA_RESULT_CODE,
                    0
                )

                @Suppress("DEPRECATION")
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        EXTRA_RESULT_DATA,
                        Intent::class.java
                    )
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (data != null) {
                    startCapture(resultCode, data)
                }
            }

            ACTION_STOP -> stopCapture()
        }

        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent
    ) {
        if (captureStarted) return

        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        projection = manager.getMediaProjection(
            resultCode,
            data
        )

        val metrics = resources.displayMetrics

        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels

        handlerThread = HandlerThread(
            "WSFischScreenCapture"
        ).also { it.start() }

        captureHandler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader!!.setOnImageAvailableListener(
            { reader ->
                val image = try {
                    reader.acquireLatestImage()
                } catch (_: Exception) {
                    null
                }

                if (image != null) {
                    try {
                        publishFrame(image)
                    } finally {
                        image.close()
                    }
                }
            },
            captureHandler
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "WSFischCapture",
            captureWidth,
            captureHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler
        )

        captureStarted = virtualDisplay != null
    }

    private fun publishFrame(image: Image) {
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val rowPadding =
            (rowStride - pixelStride * captureWidth) /
                pixelStride

        val paddedWidth = captureWidth + rowPadding

        val raw = IntArray(
            paddedWidth * captureHeight
        )

        buffer.rewind()
        buffer.asIntBuffer().get(
            raw,
            0,
            minOf(raw.size, buffer.remaining() / 4)
        )

        val pixels = IntArray(
            captureWidth * captureHeight
        )

        var y = 0
        while (y < captureHeight) {
            val sourceOffset = y * paddedWidth
            val targetOffset = y * captureWidth

            System.arraycopy(
                raw,
                sourceOffset,
                pixels,
                targetOffset,
                captureWidth
            )

            y++
        }

        val frame = CapturedFrame(
            width = captureWidth,
            height = captureHeight,
            pixels = pixels,
            sequence = sequence.incrementAndGet(),
            capturedAtElapsedMs = SystemClock.elapsedRealtime()
        )

        latestFrame.set(frame)
    }

    fun snapshot(): CapturedFrame? {
        return latestFrame.get()
    }

    fun isCaptureReady(
        maxAgeMs: Long = 1200L
    ): Boolean {
        if (!captureStarted) return false

        val frame = latestFrame.get() ?: return false
        val age =
            SystemClock.elapsedRealtime() -
                frame.capturedAtElapsedMs

        return frame.width == captureWidth &&
            frame.height == captureHeight &&
            age in 0..maxAgeMs
    }

    fun hasFrame(): Boolean {
        return latestFrame.get() != null
    }

    fun frameWidth(): Int {
        return latestFrame.get()?.width ?: captureWidth
    }

    fun frameHeight(): Int {
        return latestFrame.get()?.height ?: captureHeight
    }

    fun frameAgeMs(): Long {
        val frame = latestFrame.get() ?: return Long.MAX_VALUE

        return (
            SystemClock.elapsedRealtime() -
                frame.capturedAtElapsedMs
            ).coerceAtLeast(0L)
    }

    fun pixelColor(
        x: Int,
        y: Int
    ): Int {
        return latestFrame.get()?.pixel(x, y) ?: 0
    }

    fun findPixel(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        color: Int,
        tolerance: Int = 10
    ): Pair<Int, Int>? {
        val frame = latestFrame.get() ?: return null

        val left = x1.coerceIn(0, frame.width - 1)
        val right = x2.coerceIn(left, frame.width - 1)
        val top = y1.coerceIn(0, frame.height - 1)
        val bottom = y2.coerceIn(top, frame.height - 1)

        val targetR = (color shr 16) and 0xff
        val targetG = (color shr 8) and 0xff
        val targetB = color and 0xff

        var y = top
        while (y <= bottom) {
            var x = left

            while (x <= right) {
                val pixel = frame.pixel(x, y)

                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                if (
                    kotlin.math.abs(r - targetR) <= tolerance &&
                    kotlin.math.abs(g - targetG) <= tolerance &&
                    kotlin.math.abs(b - targetB) <= tolerance
                ) {
                    return x to y
                }

                x += 2
            }

            y += 2
        }

        return null
    }

    private fun stopCapture() {
        captureStarted = false

        latestFrame.set(null)

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        projection?.stop()
        projection = null

        handlerThread?.quitSafely()
        handlerThread = null
        captureHandler = null
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("WSFisch")
                .setContentText("Захват экрана")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("WSFisch")
                .setContentText("Захват экрана")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }
    }

    override fun onDestroy() {
        stopCapture()

        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
