package com.wsfisch.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * Centralized Android touch controller for WSFisch.
 *
 * All gestures are serialized, dispatched on the service main thread and
 * confirmed through AccessibilityService.GestureResultCallback. Macro code
 * calls this service from its worker thread, never from the UI thread.
 */
class MacroAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WSFischTouch"
        private const val LOCK_WAIT_MS = 8_000L
        private const val DISPATCH_WAIT_MS = 2_000L
        private const val CALLBACK_GRACE_MS = 2_500L
        private const val MAX_HOLD_MS = 5_000L
        private const val MAX_SWIPE_MS = 10_000L

        const val MIN_GESTURE_MS = 40L

        @Volatile
        var instance: MacroAccessibilityService? = null
            private set
    }

    data class Diagnostics(
        val connected: Boolean,
        val activeGesture: String?,
        val requests: Long,
        val accepted: Long,
        val completed: Long,
        val cancelled: Long,
        val rejected: Long,
        val timedOut: Long,
        val lastResult: String,
        val lastDurationMs: Long
    )

    private data class DisplayBounds(
        val width: Int,
        val height: Int
    ) {
        fun contains(x: Float, y: Float): Boolean =
            x.isFinite() && y.isFinite() &&
                x >= 0f && y >= 0f &&
                x < width.toFloat() && y < height.toFloat()
    }

    private enum class GestureResult {
        QUEUED,
        COMPLETED,
        CANCELLED,
        REJECTED,
        SERVICE_LOST,
        TIMED_OUT
    }

    private class GestureSession(
        val id: Long,
        val label: String,
        val durationMs: Long
    ) {
        val dispatchFinished = CountDownLatch(1)
        val gestureFinished = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        val abandoned = AtomicBoolean(false)
        val result = AtomicReference(GestureResult.QUEUED)
    }

    private data class AwaitResult(
        val completed: Boolean,
        val interrupted: Boolean
    )

    private lateinit var mainHandler: Handler

    private val gestureLock = ReentrantLock(true)
    private val activeGesture = AtomicReference<GestureSession?>(null)
    private val connected = AtomicBoolean(false)

    private val nextGestureId = AtomicLong(0L)
    private val requestCount = AtomicLong(0L)
    private val acceptedCount = AtomicLong(0L)
    private val completedCount = AtomicLong(0L)
    private val cancelledCount = AtomicLong(0L)
    private val rejectedCount = AtomicLong(0L)
    private val timedOutCount = AtomicLong(0L)

    private val lastResult = AtomicReference("Ожидание касаний")
    private val lastDuration = AtomicLong(0L)

    override fun onCreate() {
        super.onCreate()
        mainHandler = Handler(mainLooper)
        Log.i(TAG, "Touch service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected.set(true)
        instance = this
        setResult("Служба касаний подключена")
        Log.i(TAG, "Touch service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // UI tree access is intentionally disabled in the service config.
        // All game recognition is performed from MediaProjection frames.
    }

    override fun onInterrupt() {
        setResult("Служба касаний прервана Android")
        Log.w(TAG, "Accessibility service interrupted")
        // The active gesture callback reports whether Android completed
        // or cancelled the current stroke.
    }

    override fun onDestroy() {
        connected.set(false)

        if (instance === this) {
            instance = null
        }

        val current = activeGesture.getAndSet(null)

        if (current != null) {
            current.result.set(GestureResult.SERVICE_LOST)
            current.dispatchFinished.countDown()
            current.gestureFinished.countDown()
        }

        setResult("Служба касаний остановлена")
        Log.i(TAG, "Touch service destroyed")

        super.onDestroy()
    }

    /**
     * Normal short tap.
     * Returns true only when Android invokes onCompleted.
     */
    fun tap(
        x: Float,
        y: Float,
        durationMs: Long = 70L,
        stopFlag: AtomicBoolean? = null
    ): Boolean {
        val duration =
            durationMs.coerceIn(MIN_GESTURE_MS, 500L)

        val bounds = readDisplayBounds()
            ?: return reject("Не удалось определить размер экрана")

        if (!bounds.contains(x, y)) {
            return reject(
                "Координаты касания вне экрана: ${x.toInt()},${y.toInt()}"
            )
        }

        val endX =
            if (x < bounds.width - 1f) x + 1f else x - 1f

        val path = Path().apply {
            moveTo(x, y)
            lineTo(endX, y)
        }

        return performGesture(
            label = "TAP ${x.toInt()},${y.toInt()}",
            path = path,
            durationMs = duration,
            stopFlag = stopFlag
        )
    }

    /**
     * Stationary press for cast and reel control.
     * 55 ms pulses from MacroRunner are supported.
     */
    fun pressAndHold(
        x: Float,
        y: Float,
        durationMs: Long,
        stopFlag: AtomicBoolean
    ): Boolean {
        require(durationMs in MIN_GESTURE_MS..MAX_HOLD_MS) {
            "Длительность удержания должна быть ${MIN_GESTURE_MS}..${MAX_HOLD_MS} мс"
        }

        val bounds = readDisplayBounds()
            ?: return reject("Не удалось определить размер экрана")

        if (!bounds.contains(x, y)) {
            return reject(
                "Координаты удержания вне экрана: ${x.toInt()},${y.toInt()}"
            )
        }

        val endX =
            if (x < bounds.width - 1f) x + 1f else x - 1f

        val path = Path().apply {
            moveTo(x, y)
            lineTo(endX, y)
        }

        return performGesture(
            label = "HOLD ${durationMs}ms @ ${x.toInt()},${y.toInt()}",
            path = path,
            durationMs = durationMs,
            stopFlag = stopFlag
        )
    }

    /** Explicit long-press helper. */
    fun longPress(
        x: Float,
        y: Float,
        durationMs: Long = 650L,
        stopFlag: AtomicBoolean? = null
    ): Boolean {
        val duration =
            durationMs.coerceIn(300L, MAX_HOLD_MS)

        val bounds = readDisplayBounds()
            ?: return reject("Не удалось определить размер экрана")

        if (!bounds.contains(x, y)) {
            return reject("Координаты долгого нажатия вне экрана")
        }

        val endX =
            if (x < bounds.width - 1f) x + 1f else x - 1f

        val path = Path().apply {
            moveTo(x, y)
            lineTo(endX, y)
        }

        return performGesture(
            label = "LONG PRESS ${duration}ms",
            path = path,
            durationMs = duration,
            stopFlag = stopFlag
        )
    }

    /**
     * Swipe/drag primitive.
     * Endpoints are validated against the real display.
     */
    fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 250L,
        stopFlag: AtomicBoolean? = null
    ): Boolean {
        require(durationMs in MIN_GESTURE_MS..MAX_SWIPE_MS) {
            "Длительность свайпа должна быть ${MIN_GESTURE_MS}..${MAX_SWIPE_MS} мс"
        }

        val bounds = readDisplayBounds()
            ?: return reject("Не удалось определить размер экрана")

        if (
            !bounds.contains(fromX, fromY) ||
            !bounds.contains(toX, toY)
        ) {
            return reject(
                "Начальная или конечная точка свайпа вне экрана"
            )
        }

        if (fromX == toX && fromY == toY) {
            return reject("Свайп не может иметь нулевую длину")
        }

        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }

        return performGesture(
            label = "SWIPE ${fromX.toInt()},${fromY.toInt()} → ${toX.toInt()},${toY.toInt()}",
            path = path,
            durationMs = durationMs,
            stopFlag = stopFlag
        )
    }

    /**
     * Sequence of individually confirmed taps.
     */
    fun tapSequence(
        points: List<PointF>,
        durationMs: Long = 65L,
        intervalMs: Long = 75L,
        stopFlag: AtomicBoolean? = null
    ): Boolean {
        if (points.isEmpty()) {
            return reject("Последовательность касаний пуста")
        }

        if (intervalMs !in 0L..1_000L) {
            return reject("Интервал между касаниями вне диапазона")
        }

        for ((index, point) in points.withIndex()) {
            if (stopFlag?.get() == true) return false

            if (!tap(point.x, point.y, durationMs, stopFlag)) {
                return false
            }

            if (index != points.lastIndex && intervalMs > 0L) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }

        return stopFlag?.get() != true
    }

    /** Two individually confirmed taps. */
    fun doubleTap(
        x: Float,
        y: Float,
        durationMs: Long = 65L,
        intervalMs: Long = 90L,
        stopFlag: AtomicBoolean? = null
    ): Boolean {
        return tapSequence(
            points = listOf(PointF(x, y), PointF(x, y)),
            durationMs = durationMs,
            intervalMs = intervalMs,
            stopFlag = stopFlag
        )
    }

    /** True when connected and no unresolved gesture remains. */
    fun isReady(): Boolean {
        return connected.get() && activeGesture.get() == null
    }

    /** Snapshot for troubleshooting and a future diagnostics panel. */
    fun diagnostics(): Diagnostics {
        val active = activeGesture.get()

        return Diagnostics(
            connected = connected.get(),
            activeGesture = active?.let {
                "${it.label} · ${it.durationMs}ms"
            },
            requests = requestCount.get(),
            accepted = acceptedCount.get(),
            completed = completedCount.get(),
            cancelled = cancelledCount.get(),
            rejected = rejectedCount.get(),
            timedOut = timedOutCount.get(),
            lastResult = lastResult.get(),
            lastDurationMs = lastDuration.get()
        )
    }

    /** Reset counters without affecting a running gesture. */
    fun resetDiagnostics() {
        requestCount.set(0L)
        acceptedCount.set(0L)
        completedCount.set(0L)
        cancelledCount.set(0L)
        rejectedCount.set(0L)
        timedOutCount.set(0L)
        lastDuration.set(0L)
        setResult("Статистика сброшена")
    }

    private fun performGesture(
        label: String,
        path: Path,
        durationMs: Long,
        stopFlag: AtomicBoolean?
    ): Boolean {
        requestCount.incrementAndGet()

        if (!connected.get()) {
            return reject(
                "Служба специальных возможностей не подключена"
            )
        }

        // Waiting synchronously on the main thread would block the callback.
        if (Looper.myLooper() == mainLooper) {
            return reject(
                "Жест вызван из главного потока; нужен рабочий поток"
            )
        }

        if (stopFlag?.get() == true) {
            return reject("Жест отменён до отправки")
        }

        var locked = false
        var interrupted = false

        try {
            locked = try {
                gestureLock.tryLock(
                    LOCK_WAIT_MS,
                    TimeUnit.MILLISECONDS
                )
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }

            if (!locked) {
                if (interrupted) {
                    Thread.currentThread().interrupt()
                }

                return reject(
                    if (interrupted) {
                        "Ожидание жеста прервано"
                    } else {
                        "Очередь жестов занята"
                    }
                )
            }

            if (!connected.get()) {
                return reject(
                    "Служба отключилась перед отправкой жеста"
                )
            }

            if (stopFlag?.get() == true) {
                return reject("Жест отменён до отправки")
            }

            // Never stack a gesture on top of an unconfirmed one.
            if (activeGesture.get() != null) {
                return reject(
                    "Предыдущий жест ещё не завершён Android"
                )
            }

            val session = GestureSession(
                id = nextGestureId.incrementAndGet(),
                label = label,
                durationMs = durationMs
            )

            if (!activeGesture.compareAndSet(null, session)) {
                return reject(
                    "Не удалось зарезервировать очередь жестов"
                )
            }

            // dispatchGesture is always invoked on the service main thread.
            val posted = mainHandler.post {
                if (
                    session.abandoned.get() ||
                    !connected.get() ||
                    stopFlag?.get() == true
                ) {
                    session.result.set(GestureResult.REJECTED)
                    activeGesture.compareAndSet(session, null)
                    rejectedCount.incrementAndGet()

                    setResult("$label · отменён до отправки")

                    session.dispatchFinished.countDown()
                    session.gestureFinished.countDown()

                    return@post
                }

                val acceptedByAndroid = try {
                    dispatchGesture(
                        GestureDescription.Builder()
                            .addStroke(
                                GestureDescription.StrokeDescription(
                                    path,
                                    0L,
                                    durationMs
                                )
                            )
                            .build(),

                        object : GestureResultCallback() {

                            override fun onCompleted(
                                gestureDescription: GestureDescription
                            ) {
                                session.result.set(
                                    GestureResult.COMPLETED
                                )

                                completedCount.incrementAndGet()
                                lastDuration.set(durationMs)

                                setResult(
                                    "${session.label} · выполнено"
                                )

                                activeGesture.compareAndSet(
                                    session,
                                    null
                                )

                                session.gestureFinished.countDown()

                                Log.d(
                                    TAG,
                                    "Gesture ${session.id} completed"
                                )
                            }

                            override fun onCancelled(
                                gestureDescription: GestureDescription
                            ) {
                                session.result.set(
                                    GestureResult.CANCELLED
                                )

                                cancelledCount.incrementAndGet()

                                setResult(
                                    "${session.label} · отменено Android"
                                )

                                activeGesture.compareAndSet(
                                    session,
                                    null
                                )

                                session.gestureFinished.countDown()

                                Log.w(
                                    TAG,
                                    "Gesture ${session.id} cancelled"
                                )
                            }
                        },

                        mainHandler
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "dispatchGesture failed", e)
                    false
                }

                session.accepted.set(acceptedByAndroid)

                if (acceptedByAndroid) {
                    acceptedCount.incrementAndGet()

                    setResult(
                        "${session.label} · принято Android"
                    )
                } else {
                    session.result.set(GestureResult.REJECTED)

                    rejectedCount.incrementAndGet()

                    activeGesture.compareAndSet(
                        session,
                        null
                    )

                    setResult(
                        "${session.label} · Android отклонил жест"
                    )

                    session.gestureFinished.countDown()
                }

                session.dispatchFinished.countDown()
            }

            if (!posted) {
                session.abandoned.set(true)
                session.result.set(GestureResult.SERVICE_LOST)

                activeGesture.compareAndSet(session, null)

                session.dispatchFinished.countDown()
                session.gestureFinished.countDown()

                return reject(
                    "Главный поток службы недоступен"
                )
            }

            val dispatchWait = awaitUninterruptibly(
                session.dispatchFinished,
                DISPATCH_WAIT_MS
            )

            interrupted =
                interrupted || dispatchWait.interrupted

            if (!dispatchWait.completed) {
                session.abandoned.set(true)

                timedOutCount.incrementAndGet()

                session.result.set(GestureResult.TIMED_OUT)

                setResult(
                    "Тайм-аут очереди отправки: $label"
                )

                return false
            }

            if (!session.accepted.get()) {
                return false
            }

            // When STOP is pressed, wait for Android's finger-up callback.
            // This avoids leaving a gesture active after MacroRunner exits.
            val gestureWait = awaitUninterruptibly(
                session.gestureFinished,
                durationMs + CALLBACK_GRACE_MS
            )

            interrupted =
                interrupted || gestureWait.interrupted

            if (!gestureWait.completed) {
                timedOutCount.incrementAndGet()

                session.result.set(GestureResult.TIMED_OUT)

                setResult(
                    "Android не прислал callback: $label"
                )

                Log.e(
                    TAG,
                    "Gesture ${session.id} timed out; queue remains blocked"
                )

                // Keep activeGesture reserved until Android eventually
                // sends its callback. This prevents overlapping input.
                return false
            }

            val completed =
                session.result.get() == GestureResult.COMPLETED &&
                    connected.get()

            val stopped = stopFlag?.get() == true

            if (interrupted) {
                setResult(
                    "$label · остановлено после завершения жеста"
                )

                return false
            }

            if (stopped) {
                setResult(
                    "$label · завершено, макрос остановлен"
                )

                return false
            }

            return completed

        } finally {
            if (locked) {
                gestureLock.unlock()
            }

            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Wait without abandoning an in-flight Android gesture when STOP
     * interrupts the worker thread.
     */
    private fun awaitUninterruptibly(
        latch: CountDownLatch,
        timeoutMs: Long
    ): AwaitResult {
        val deadline =
            SystemClock.elapsedRealtime() + timeoutMs

        var interrupted = false
        var completed = false

        while (!completed) {
            val remaining =
                deadline - SystemClock.elapsedRealtime()

            if (remaining <= 0L) break

            try {
                completed = latch.await(
                    remaining,
                    TimeUnit.MILLISECONDS
                )
            } catch (_: InterruptedException) {
                interrupted = true
                // Wait only for the already-dispatched finger-up.
            }
        }

        return AwaitResult(
            completed = completed,
            interrupted = interrupted
        )
    }

    private fun readDisplayBounds(): DisplayBounds? {
        return try {
            val windowManager = getSystemService(
                Context.WINDOW_SERVICE
            ) as? WindowManager ?: return null

            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            if (
                metrics.widthPixels <= 1 ||
                metrics.heightPixels <= 1
            ) {
                null
            } else {
                DisplayBounds(
                    width = metrics.widthPixels,
                    height = metrics.heightPixels
                )
            }

        } catch (e: Exception) {
            Log.w(
                TAG,
                "Unable to read display bounds",
                e
            )

            null
        }
    }

    private fun reject(message: String): Boolean {
        rejectedCount.incrementAndGet()
        setResult(message)
        Log.w(TAG, message)
        return false
    }

    private fun setResult(value: String) {
        lastResult.set(value.take(180))
    }
}
