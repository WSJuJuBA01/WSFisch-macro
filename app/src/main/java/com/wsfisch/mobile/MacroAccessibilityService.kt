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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /**
     * Короткое касание.
     */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    70
                )
            )
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Удерживает палец в одной точке заданное время.
     * Метод блокирующий — вызывать только из фонового потока.
     */
    fun pressAndHold(
        x: Float,
        y: Float,
        durationMs: Long,
        stopFlag: AtomicBoolean
    ): Boolean {
        require(durationMs in 100L..5000L)

        if (stopFlag.get()) return false

        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    durationMs
                )
            )
            .build()

        val finished = CountDownLatch(1)
        val completed = AtomicBoolean(false)

        val accepted = dispatchGesture(
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
                    finished.countDown()
                }
            },
            null
        )

        if (!accepted) return false

        return try {
            finished.await(
                durationMs + 1500L,
                TimeUnit.MILLISECONDS
            ) && completed.get() && !stopFlag.get()
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
