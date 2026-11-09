
package com.wsfisch.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MacroAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MacroAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Создаёт короткое касание с минимальным смещением.
     * Смещение в 1 px позволяет избежать нулевой длины пути
     * на Android-реализациях, которые её не принимают.
     */
    fun tap(x: Float, y: Float): Boolean {
        return dispatchAndWait(
            x = x,
            y = y,
            durationMs = 90L,
            stopFlag = null
        )
    }

    /**
     * Удерживает касание заданное время.
     * Вызывать только из фонового потока.
     */
    fun pressAndHold(
        x: Float,
        y: Float,
        durationMs: Long,
        stopFlag: AtomicBoolean
    ): Boolean {
        require(durationMs in 100L..5000L)

        if (stopFlag.get()) return false

        return dispatchAndWait(
            x = x,
            y = y,
            durationMs = durationMs,
            stopFlag = stopFlag
        )
    }

    private fun dispatchAndWait(
        x: Float,
        y: Float,
        durationMs: Long,
        stopFlag: AtomicBoolean?
    ): Boolean {

        if (x < 0f || y < 0f) return false
        if (stopFlag?.get() == true) return false

        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }

        val gesture = try {
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        durationMs
                    )
                )
                .build()
        } catch (_: Exception) {
            return false
        }

        val finished = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)

        val accepted = try {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {

                    override fun onCompleted(
                        gestureDescription: GestureDescription
                    ) {
                        completed.set(true)
                        finished.countDown()
                    }

                    override fun onCancelled(
                        gestureDescription: GestureDescription
                    ) {
                        cancelled.set(true)
                        finished.countDown()
                    }
                },
                null
            )
        } catch (_: Exception) {
            false
        }

        if (!accepted) return false

        return try {
            val callbackReceived = finished.await(
                durationMs + 2500L,
                TimeUnit.MILLISECONDS
            )

            callbackReceived &&
                completed.get() &&
                !cancelled.get() &&
                stopFlag?.get() != true

        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }
}
