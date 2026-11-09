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

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun tap(
        x: Float,
        y: Float,
        durationMs: Long = 70L
    ): Boolean {
        return dispatchAndWait(
            x = x,
            y = y,
            durationMs = durationMs.coerceIn(40L, 500L),
            stopFlag = null
        )
    }

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
        if (stopFlag?.get() == true) return false

        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
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
                    completed.set(false)
                    finished.countDown()
                }
            },
            null
        )

        if (!accepted) return false

        val callbackReceived = try {
            finished.await(
                durationMs + 1500L,
                TimeUnit.MILLISECONDS
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        return callbackReceived &&
            completed.get() &&
            stopFlag?.get() != true
    }
}
