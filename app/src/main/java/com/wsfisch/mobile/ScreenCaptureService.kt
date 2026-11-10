package com.wsfisch.mobile

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
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

        // Не обрабатываем каждый системный кадр.
        private const val MIN_FRAME_INTERVAL_MS = 120L

        // Защита от слишком больших изображений.
        private const val MAX_CAPTURE_PIXELS = 12_000_000L

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val latestFrame =
        AtomicReference<CapturedFrame?>(null)

    private val sequence = AtomicLong(0L)

    // Все операции запуска и остановки выполняются
    // последовательно в главном потоке сервиса.
    private var startInProgress = false

    @Volatile
    private var captureStarted = false

    @Volatile
    private var captureWidth = 0

    @Volatile
    private var captureHeight = 0

    @Volatile
    private var lastFrameAt = 0L

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        instance = this

        Log.i(TAG, "Сервис создан")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {
                // Повторный запуск не должен создавать
                // второй MediaProjection.
                if (captureStarted || startInProgress) {
                    Log.w(TAG, "Захват уже запущен или запускается")
                    return START_NOT_STICKY
                }

                val resultCode = intent.getIntExtra(
                    EXTRA_RESULT_CODE,
                    Activity.RESULT_CANCELED
                )

                val data = getProjectionIntent(intent)

                if (
                    resultCode != Activity.RESULT_OK ||
                    data == null
                ) {
                    Log.e(TAG, "Нет разрешения на захват экрана")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                startInProgress = true

                try {
                    startCaptureForeground()
                    startCapture(resultCode, data)

                    Log.i(TAG, "Запуск захвата завершён")

                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка запуска захвата", e)

                    releaseCapture(stopProjection = true)

                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (stopError: Exception) {
                        Log.w(
                            TAG,
                            "Не удалось убрать уведомление",
                            stopError
                        )
                    }

                    stopSelf(startId)

                } finally {
                    startInProgress = false
                }
            }

            ACTION_STOP -> {
                Log.i(TAG, "Получена команда остановки")

                releaseCapture(stopProjection = true)

                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка остановки foreground", e)
                }

                stopSelf(startId)
            }

            else -> {
                Log.w(TAG, "Неизвестное действие сервиса")
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun getProjectionIntent(
        intent: Intent
    ): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
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

        Log.i(TAG, "Foreground service запущен")
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent
    ) {
        if (projection != null || captureStarted) {
            Log.w(TAG, "Попытка повторного запуска отклонена")
            return
        }

        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        // Каждый запуск использует новый результат согласия.
        val newProjection = manager.getMediaProjection(
            resultCode,
            data
        ) ?: throw IllegalStateException(
            "Android не создал MediaProjection"
        )

        projection = newProjection

        // Получаем реальные размеры дисплея.
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        val windowManager = getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            throw IllegalStateException(
                "Android вернул некорректный размер экрана"
            )
        }

        val pixelCount = width.toLong() * height.toLong()

        if (pixelCount > MAX_CAPTURE_PIXELS) {
            throw IllegalStateException(
                "Разрешение экрана слишком большое: ${width}x${height}"
            )
        }

        captureWidth = width
        captureHeight = height

        Log.i(
            TAG,
            "Размер экрана: ${width}x${height}"
        )

        // Отдельный поток для получения кадров.
        val thread = HandlerThread(
            "WSFischScreenCapture"
        )

        thread.start()

        captureThread = thread
        captureHandler = Handler(thread.looper)

        // ImageReader хранит максимум два необработанных кадра.
        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader = reader

        reader.setOnImageAvailableListener(
            { source ->
                processAvailableImage(source)
            },
            captureHandler
        )

        // Android 14+ требует callback до createVirtualDisplay().
        // Callback привязан к конкретному экземпляру projection.
        val callback = object : MediaProjection.Callback() {

            override fun onStop() {
                Log.i(
                    TAG,
                    "MediaProjection остановлена системой"
                )

                mainHandler.post {
                    handleProjectionStopped(newProjection)
                }
            }
        }

        projectionCallback = callback

        newProjection.registerCallback(
            callback,
            mainHandler
        )

        // Для одного разрешения пользователя создаём
        // только один VirtualDisplay.
        val display = newProjection.createVirtualDisplay(
            "WSFischCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        ) ?: throw IllegalStateException(
            "Android не создал VirtualDisplay"
        )

        virtualDisplay = display
        captureStarted = true

        Log.i(
            TAG,
            "Захват запущен: ${width}x${height}"
        )
    }

    private fun processAvailableImage(
        source: ImageReader
    ) {
        val image = try {
            source.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось получить изображение", e)
            null
        } ?: return

        try {
            val now = SystemClock.elapsedRealtime()

            // Пропускаем лишние кадры, но всегда закрываем Image.
            if (
                now - lastFrameAt <
                MIN_FRAME_INTERVAL_MS
            ) {
                return
            }

            lastFrameAt = now

            publishFrame(image)

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки кадра", e)

        } finally {
            try {
                image.close()
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось закрыть Image", e)
            }
        }
    }

    private fun publishFrame(image: Image) {
        val plane = image.planes.firstOrNull() ?: return

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        if (pixelStride != 4 || rowStride <= 0) {
            Log.w(
                TAG,
                "Неподдерживаемый формат: pixelStride=$pixelStride, rowStride=$rowStride"
            )
            return
        }

        val width = image.width
        val height = image.height

        if (width <= 0 || height <= 0) return

        val pixelCountLong =
            width.toLong() * height.toLong()

        if (
            pixelCountLong <= 0 ||
            pixelCountLong > MAX_CAPTURE_PIXELS
        ) {
            Log.w(TAG, "Кадр слишком большой")
            return
        }

        if (rowStride % pixelStride != 0) {
            Log.w(TAG, "Некорректный rowStride")
            return
        }

        val rowPixels = rowStride / pixelStride

        if (rowPixels < width) {
            Log.w(TAG, "Ширина строки меньше ширины кадра")
            return
        }

        val buffer = plane.buffer
        buffer.rewind()

        val input = buffer.asIntBuffer()

        val requiredInts =
            rowPixels.toLong() * (height - 1) + width

        if (requiredInts > input.limit().toLong()) {
            Log.w(TAG, "Недостаточно данных в буфере кадра")
            return
        }

        val pixels = try {
            IntArray(pixelCountLong.toInt())
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Недостаточно памяти для кадра", e)
            return
        }

        // Копируем строки напрямую в итоговый массив.
        // Дополнительный большой промежуточный массив не создаётся.
        var y = 0

        while (y < height) {
            val sourceOffset = y * rowPixels
            val targetOffset = y * width

            input.position(sourceOffset)

            input.get(
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

    private fun handleProjectionStopped(
        stoppedProjection: MediaProjection
    ) {
        // Защита от запоздавшего callback старого сеанса.
        if (projection !== stoppedProjection) {
            return
        }

        Log.i(TAG, "Освобождение после системной остановки")

        releaseCapture(stopProjection = false)

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка удаления уведомления", e)
        }

        stopSelf()
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
        // Сначала отсоединяем ресурсы от сервиса.
        // Повторный вызов этой функции безопасен.
        captureStarted = false

        latestFrame.set(null)
        lastFrameAt = 0L

        val oldProjection = projection
        val oldCallback = projectionCallback
        val oldDisplay = virtualDisplay
        val oldReader = imageReader
        val oldThread = captureThread

        projection = null
        projectionCallback = null

        virtualDisplay = null
        imageReader = null

        captureThread = null
        captureHandler = null

        // Снимаем listener перед закрытием ImageReader.
        try {
            oldReader?.setOnImageAvailableListener(
                null,
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка отключения listener", e)
        }

        // Освобождаем виртуальный дисплей.
        try {
            oldDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка освобождения VirtualDisplay", e)
        }

        // Закрываем ImageReader.
        try {
            oldReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка закрытия ImageReader", e)
        }

        if (stopProjection && oldProjection != null) {
            // Сначала убираем callback, чтобы stop()
            // не вызвал повторную очистку ресурсов.
            try {
                if (oldCallback != null) {
                    oldProjection.unregisterCallback(oldCallback)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка удаления callback", e)
            }

            try {
                oldProjection.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка остановки MediaProjection", e)
            }
        }

        // Останавливаем поток после отключения ImageReader.
        try {
            oldThread?.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки HandlerThread", e)
        }

        Log.i(TAG, "Ресурсы захвата освобождены")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WSFisch screen capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление о захвате экрана"
            }

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
                .setOngoing(true)
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
