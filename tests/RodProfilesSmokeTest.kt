package com.wsfisch.mobile

private fun makeFrame(barRgb: Int, fishRgb: Int? = null): CapturedFrame {
    val w = 800
    val h = 1200
    val pixels = IntArray(w * h) { 0xFF606060.toInt() }
    fun fill(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        for (y in y0 until y1) for (x in x0 until x1) pixels[y * w + x] = c
    }
    fill(180, 985, 620, 1018, 0xFF000000.toInt() or barRgb)
    if (fishRgb != null) fill(395, 990, 409, 1014, 0xFF000000.toInt() or fishRgb)
    if (barRgb == 0x8A2115) fill(395, 968, 409, 984, 0xFF101010.toInt())
    return CapturedFrame(w, h, pixels, 1L, 1L)
}

fun main() {
    val base = ReelObservation(160, 640, 300, 500, null, 1000, 0.25f)
    val cases = listOf(
        Triple("Verdant Oath", 0x3B8113, 0x434B5B),
        Triple("MiguRod", 0xE0A66F, 0xF9D9D4),
        Triple("Tryhard Rod", 0x8A2115, 0x202020),
        Triple("Remembrance", 0xB5B5B5, 0xFFFFFF),
        Triple("Noiseform", 0x33A95F, 0x0C4125),
        Triple("Halibut Harpoon", 0x5D52A8, 0x0D0B0B)
    )
    for ((rod, barColor, fishColor) in cases) {
        val frame = makeFrame(barColor, fishColor)
        val colors = if (rod == "MiguRod") listOf(0xF9D9D4) else listOf(fishColor)
        val result = RodMechanicVision.detectSpecialReel(
            frame = frame,
            rod = rod,
            base = base,
            previousFishX = 400,
            fishColors = colors,
            barColors = emptyList(),
            fishTolerance = 28,
            noteColorRgb = 0xB2CFFF,
            noteTolerance = 20,
            noteBias = 100,
            remembranceMode = "Living",
            halibutPlayMinigame = true
        )
        check(result != null) { "$rod bar detector failed synthetic frame" }
        println("PASS $rod bar=${result.barLeft}..${result.barRight} fish=${result.fishX}")
    }
}
