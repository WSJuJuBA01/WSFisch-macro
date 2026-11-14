package com.wsfisch.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends simultaneous touch contacts through AccessibilityService.dispatchGesture.
 * Used only for Bellona's two independent control zones.
 *
 * This helper is a separate extension so the existing serialized single-touch
 * diagnostics/queue implementation remains untouched for other rods.
 */
fun MacroAccessibilityService.pressMultiTouch(
    points: List<PointF>,
    durationMs: Long = 55L,
    stopFlag: AtomicBoolean
): Boolean {
    if (points.isEmpty() || points.size > 2) return false
    if (durationMs !in 40L..5_000L || stopFlag.get()) return false
    if (Looper.myLooper() == Looper.getMainLooper()) return false

    val result = AtomicReference<Boolean?>(null)
    val finished = CountDownLatch(1)
    val handler = Handler(Looper.getMainLooper())

    val paths = points.map { point ->
        if (!point.x.isFinite() || !point.y.isFinite() || point.x < 0f || point.y < 0f) {
            return false
        }
        Path().apply {
            moveTo(point.x, point.y)
            lineTo(point.x + 0.5f, point.y)
        }
    }

    val posted = handler.post {
        if (stopFlag.get()) {
            result.set(false)
            finished.countDown()
            return@post
        }

        val builder = GestureDescription.Builder()
        paths.forEach { path ->
            builder.addStroke(
                GestureDescription.StrokeDescription(path, 0L, durationMs)
            )
        }

        val accepted = try {
            dispatchGesture(
                builder.build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        result.set(!stopFlag.get())
                        finished.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        result.set(false)
                        finished.countDown()
                    }
                },
                handler
            )
        } catch (_: Exception) {
            false
        }

        if (!accepted) {
            result.set(false)
            finished.countDown()
        }
    }
    if (!posted) return false

    return try {
        finished.await(durationMs + 2_500L, TimeUnit.MILLISECONDS) && result.get() == true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
