package com.wsfisch.mobile

import android.app.Activity
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
import android.content.pm.ServiceInfo
import android.util.Log
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
        private const val TAG = "WSFischCapture"

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }

    private val captureLock = Any()

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    private var handlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val mainHandler = Handler(mainLooper)

    private val latestFrame =
        AtomicReference<CapturedFrame?>(null)

    private val sequence = AtomicLong(0L)

    @Volatile
    private var captureWidth = 0

    @Volatile
    private var captureHeight = 0

    @Volatile
    private var captureStarted = false

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {
                Log.i(TAG, "MediaProjection остановлена системой")

                releaseCapture(stopProjection = false)

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

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

                if (resultCode != Activity.RESULT_OK || data == null) {
                    Log.e(TAG, "Нет разрешения на захват экрана")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                try {
                    startCaptureForeground()
                    startCapture(resultCode, data)

                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка запуска захвата", e)

                    releaseCapture(stopProjection = true)

                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }

            ACTION_STOP -> {
                Log.i(TAG, "Получена команда остановки")

                releaseCapture(stopProjection = true)

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }

            else -> {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun startCaptureForeground() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent
    ) {
        synchronized(captureLock) {
            if (captureStarted || projection != null) {
                Log.w(TAG, "Захват уже запущен")
                return
            }
        }

        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        val newProjection = manager.getMediaProjection(
            resultCode,
            data
        ) ?: throw IllegalStateException(
            "MediaProjection не создан"
        )

        projection = newProjection

        val metrics = resources.displayMetrics

        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels

        if (captureWidth <= 0 || captureHeight <= 0) {
            throw IllegalStateException(
                "Некорректный размер экрана"
            )
        }

        val thread = HandlerThread(
            "WSFischScreenCapture"
        )

        thread.start()

        handlerThread = thread

        val handler = Handler(thread.looper)
        captureHandler = handler

        val reader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader = reader

        reader.setOnImageAvailableListener(
            { source ->
                val image = try {
                    source.acquireLatestImage()
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось получить кадр", e)
                    null
                }

                if (image != null) {
                    try {
                        publishFrame(image)
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка обработки кадра", e)
                    } finally {
                        image.close()
                    }
                }
            },
            handler
        )

        // ОБЯЗАТЕЛЬНО регистрируем callback
        // ДО createVirtualDisplay().
        newProjection.registerCallback(
            projectionCallback,
            mainHandler
        )

        val display = newProjection.createVirtualDisplay(
            "WSFischCapture",
            captureWidth,
            captureHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        ) ?: throw IllegalStateException(
            "VirtualDisplay не создан"
        )

        virtualDisplay = display
        captureStarted = true

        Log.i(
            TAG,
            "Захват запущен: ${captureWidth}x${captureHeight}"
        )
    }

    private fun publishFrame(image: Image) {
        val plane = image.planes.firstOrNull() ?: return

        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        if (pixelStride != 4) {
            Log.w(TAG, "Неожиданный pixelStride: $pixelStride")
            return
        }

        val width = image.width
        val height = image.height

        if (width <= 0 || height <= 0) return

        val rowPadding =
            (rowStride - pixelStride * width) / pixelStride

        val paddedWidth = width + rowPadding

        if (paddedWidth < width) return

        val raw = IntArray(
            paddedWidth * height
        )

        buffer.rewind()

        val intBuffer = buffer.asIntBuffer()

        val available = minOf(
            raw.size,
            intBuffer.remaining()
        )

        if (available <= 0) return

        intBuffer.get(
            raw,
            0,
            available
        )

        val pixels = IntArray(
            width * height
        )

        var y = 0

        while (y < height) {
            val sourceOffset = y * paddedWidth
            val targetOffset = y * width

            if (sourceOffset + width > available) {
                return
            }

            System.arraycopy(
                raw,
                sourceOffset,
                pixels,
                targetOffset,
                width
            )

            y++
        }

        val frame = CapturedFrame(
            width = width,
            height = height,
            pixels = pixels,
            sequence = sequence.incrementAndGet(),
            capturedAtElapsedMs =
                SystemClock.elapsedRealtime()
        )

        captureWidth = width
        captureHeight = height

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
        val frame = latestFrame.get()
            ?: return Long.MAX_VALUE

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

        if (frame.width <= 0 || frame.height <= 0) {
            return null
        }

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

    private fun releaseCapture(
        stopProjection: Boolean
    ) {
        val oldProjection: MediaProjection?
        val oldDisplay: VirtualDisplay?
        val oldReader: ImageReader?
        val oldThread: HandlerThread?

        synchronized(captureLock) {
            captureStarted = false

            latestFrame.set(null)

            oldProjection = projection
            oldDisplay = virtualDisplay
            oldReader = imageReader
            oldThread = handlerThread

            projection = null
            virtualDisplay = null
            imageReader = null

            handlerThread = null
            captureHandler = null
        }

        try {
            oldDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка освобождения VirtualDisplay", e)
        }

        try {
            oldReader?.setOnImageAvailableListener(null, null)
            oldReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка закрытия ImageReader", e)
        }

        if (stopProjection && oldProjection != null) {
            try {
                oldProjection.unregisterCallback(
                    projectionCallback
                )
            } catch (e: Exception) {
                Log.w(TAG, "Callback уже освобождён", e)
            }

            try {
                oldProjection.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка остановки MediaProjection", e)
            }
        }

        try {
            oldThread?.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки потока", e)
        }

        Log.i(TAG, "Ресурсы захвата освобождены")
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
                .setContentText("Захват экрана активен")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("WSFisch")
                .setContentText("Захват экрана активен")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "ScreenCaptureService уничтожается")

        releaseCapture(stopProjection = true)

        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
