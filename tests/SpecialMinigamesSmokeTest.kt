package com.wsfisch.mobile

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

fun main() {
    val width = 800
    val height = 1200
    val lanes = floatArrayOf(0.123f, 0.374f, 0.622f, 0.875f)
    val maniaPixels = IntArray(width * height) { 0xFF101010.toInt() }
    val hitY = (height * 0.923f).roundToInt()
    for (lane in 0..2) {
        val cx = (width * lanes[lane]).roundToInt()
        for (y in hitY - 12..hitY + 10) {
            for (x in cx - 8..cx + 8) maniaPixels[y * width + x] = 0xFF32C9E8.toInt()
        }
    }
    val maniaFrame = CapturedFrame(width, height, maniaPixels, 1L, 1L)
    check(RodMechanicVision.detectManiaRings(maniaFrame)) {
        "Tranquility Mania ring detector failed synthetic frame"
    }

    val tracker = RodMechanicVision.TranquilityTracker(45)
    val notePixels = IntArray(width * height) { 0xFF101010.toInt() }
    val laneX = (width * lanes[1]).roundToInt()
    for (y in 1066..1084) for (x in laneX - 6..laneX + 6) {
        notePixels[y * width + x] = 0xFF22D2F0.toInt()
    }
    val noteFrame = CapturedFrame(width, height, notePixels, 2L, 2L)
    val taps = tracker.update(noteFrame, 100L)
    check(taps.any { it.lane == 1 }) {
        "Tranquility tracker failed to predict a note at the hit line"
    }

    val lullaby = RodMechanicVision.LullabyTracker(
        radiusPercent = 16,
        pivotUpPx = 26,
        lightThreshold = 90,
        armDelta = 45,
        triggerPercent = 10,
        clearMs = 100,
        minSectorDegrees = 4
    )
    val barCenterY = 1000
    val centerX = width / 2
    val centerY = barCenterY - 26
    val radius = (width * 0.166f).roundToInt()

    fun ringFrame(darkenSector: Boolean, sequence: Long): CapturedFrame {
        val pixels = IntArray(width * height) { 0xFF101010.toInt() }
        for (angle in 180..360 step 2) {
            val inBrightArc = angle in 205..239
            val inDarkenArc = darkenSector && angle in 210..226
            val value = if (inBrightArc && !inDarkenArc) 210 else 18
            val radians = Math.toRadians(angle.toDouble())
            val x = (centerX + radius * cos(radians)).roundToInt()
            val y = (centerY + radius * sin(radians)).roundToInt()
            if (x in 0 until width && y in 0 until height) {
                pixels[y * width + x] = (0xFF shl 24) or
                    (value shl 16) or (value shl 8) or value
            }
        }
        return CapturedFrame(width, height, pixels, sequence, sequence * 30L)
    }

    for (i in 1..26) lullaby.update(ringFrame(false, i.toLong()), barCenterY)
    check(lullaby.calibrated()) {
        "Lullaby ring calibration did not finish on synthetic ring"
    }
    val tap = lullaby.update(ringFrame(true, 27L), barCenterY)
    check(tap != null) {
        "Lullaby sector detector did not raise an event after sector illumination changed"
    }

    println("PASS Tranquility Mania rings and lane timing")
    println("PASS Lullaby radial calibration and sector event")
}
