package com.wsfisch.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MacroAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: MacroAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) = dispatchAndWait(x, y, x, y, 70L)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        dispatchAndWait(x1, y1, x2, y2, durationMs)
    }

    private fun dispatchAndWait(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>(null)

        mainHandler.post {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()

            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { done.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    error.set("Android cancelled the touch gesture")
                    done.countDown()
                }
            }, mainHandler)

            if (!accepted) {
                error.set("Android did not accept the touch gesture")
                done.countDown()
            }
        }

        check(done.await(3, TimeUnit.SECONDS)) { "Gesture timed out" }
        error.get()?.let { throw IllegalStateException(it) }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
