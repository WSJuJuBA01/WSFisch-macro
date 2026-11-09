package com.wsfisch.mobile

import kotlin.math.abs
import kotlin.math.sqrt

private fun frameWithBackground(
    w: Int = 1080,
    h: Int = 2400
): CapturedFrame =
    CapturedFrame(
        w,
        h,
        IntArray(w * h) {
            0xff24303a.toInt()
        },
        1,
        1000
    )

private fun drawShakeRing(
    frame: CapturedFrame,
    cx: Int,
    cy: Int,
    diameter: Int
) {
    val r1 = diameter * 0.49
    val r2 = diameter * 0.44

    for (y in cy - diameter / 2..cy + diameter / 2) {
        for (x in cx - diameter / 2..cx + diameter / 2) {
            val d = sqrt(
                (
                    (x - cx) * (x - cx) +
                        (y - cy) * (y - cy)
                    ).toDouble()
            )

            if (
                abs(d - r1) < 4.0 ||
                abs(d - r2) < 2.5
            ) {
                frame.pixels[
                    y * frame.width + x
                ] = 0xffc7c7c7.toInt()
            }
        }
    }
}

private fun drawReel(
    frame: CapturedFrame,
    left: Int,
    right: Int,
    fishX: Int
) {
    for (y in 2010..2080) {
        for (x in 324..756) {
            frame.pixels[
                y * frame.width + x
            ] = 0xff60666d.toInt()
        }
    }

    for (y in 2022..2060) {
        for (x in left..right) {
            frame.pixels[
                y * frame.width + x
            ] = 0xfff1f1f1.toInt()
        }
    }

    for (y in 2010..2080) {
        for (x in fishX - 2..fishX + 2) {
            frame.pixels[
                y * frame.width + x
            ] = 0xff434b5b.toInt()
        }
    }
}

fun main() {
    val shakeFrame = frameWithBackground()

    drawShakeRing(
        shakeFrame,
        270,
        1500,
        220
    )

    val shake =
        FischVision.detectShake(shakeFrame)

    check(
        shake != null &&
            abs(shake.x - 270) < 8 &&
            abs(shake.y - 1500) < 8
    ) {
        "Shake detector failed: $shake"
    }

    val textOnly = frameWithBackground()

    for (y in 1100..1110) {
        for (x in 300..720) {
            if (x % 9 < 4) {
                textOnly.pixels[
                    y * textOnly.width + x
                ] = 0xffdedede.toInt()
            }
        }
    }

    check(
        FischVision.detectShake(textOnly) == null
    ) {
        "Text/noise mistaken for Shake circle"
    }

    val reelFrame = frameWithBackground()

    drawReel(
        reelFrame,
        400,
        590,
        520
    )

    val reel =
        FischVision.detectReel(reelFrame)

    check(
        reel != null &&
            abs(reel.barLeft - 400) <= 2 &&
            abs(reel.barRight - 590) <= 2
    ) {
        "Reel bar detector failed: $reel"
    }

    check(
        reel!!.fishX != null &&
            abs(reel.fishX!! - 520) <= 2
    ) {
        "Fish marker detector failed: $reel"
    }

    val movedFrame = frameWithBackground()

    drawReel(
        movedFrame,
        500,
        690,
        640
    )

    val moved = FischVision.detectReel(
        movedFrame,
        previousFishX = 520
    )

    check(
        moved != null &&
            abs(moved.barLeft - 500) <= 2 &&
            abs(moved.fishX!! - 640) <= 2
    ) {
        "Tracking did not follow moved bar/fish: $moved"
    }

    println(
        "PASS: shake circle, noise rejection, bar span, fish marker, moved bar"
    )
}
