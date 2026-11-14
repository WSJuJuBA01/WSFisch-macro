package com.wsfisch.mobile

fun main() {
    val width = 800
    val height = 1200
    val pixels = IntArray(width * height) { 0xFF141414.toInt() }

    fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (y in y0 until y1) for (x in x0 until x1) {
            pixels[y * width + x] = color
        }
    }

    fill(60, 990, 350, 1060, 0xFFEAEAEA.toInt())
    fill(450, 990, 740, 1060, 0xFFEAEAEA.toInt())
    // Dark fish markers inside the two control bars.
    fill(190, 1000, 205, 1050, 0xFF505050.toInt())
    fill(610, 1000, 625, 1050, 0xFF505050.toInt())

    val frame = CapturedFrame(
        width = width,
        height = height,
        pixels = pixels,
        sequence = 1L,
        capturedAtElapsedMs = 1L
    )

    val bellona = RodMechanicVision.detectBellona(frame)
        ?: error("Bellona detector failed to find synthetic bars")

    val left = bellona.left ?: error("Left bar not found")
    val right = bellona.right ?: error("Right bar not found")
    check(left.fishX != null) { "Left fish marker not found" }
    check(right.fishX != null) { "Right fish marker not found" }

    println(
        "PASS Bellona: left=${left.barLeft}..${left.barRight}, " +
            "fish=${left.fishX}; right=${right.barLeft}..${right.barRight}, " +
            "fish=${right.fishX}"
    )
}
