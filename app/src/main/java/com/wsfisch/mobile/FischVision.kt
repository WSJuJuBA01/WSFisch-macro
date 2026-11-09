package com.wsfisch.mobile

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class CapturedFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val sequence: Long,
    val capturedAtElapsedMs: Long
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size >= width * height)
    }

    fun pixel(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        return pixels[y * width + x]
    }
}

data class ShakePrompt(
    val x: Int,
    val y: Int,
    val diameter: Int,
    val confidence: Float
)

data class ReelObservation(
    val trackLeft: Int,
    val trackRight: Int,
    val barLeft: Int,
    val barRight: Int,
    val fishX: Int?,
    val centerY: Int,
    val confidence: Float
) {
    val barCenter: Float get() = (barLeft + barRight) / 2f
    val barWidth: Int get() = (barRight - barLeft + 1).coerceAtLeast(1)
    val trackWidth: Int get() = (trackRight - trackLeft + 1).coerceAtLeast(1)
}

object FischVision {

    private data class RowSpan(
        val left: Int,
        val right: Int,
        val y: Int
    )

    fun detectShake(frame: CapturedFrame): ShakePrompt? {
        val w = frame.width
        val h = frame.height
        val shortSide = min(w, h)
        if (shortSide < 240) return null

        val x0 = (w * 0.08f).roundToInt().coerceIn(0, w - 1)
        val x1 = (w * 0.92f).roundToInt().coerceIn(x0 + 1, w)
        val y0 = (h * 0.24f).roundToInt().coerceIn(0, h - 1)
        val y1 = (h * 0.91f).roundToInt().coerceIn(y0 + 1, h)

        val step = 2
        val gw = ((x1 - x0 + step - 1) / step).coerceAtLeast(1)
        val gh = ((y1 - y0 + step - 1) / step).coerceAtLeast(1)
        val size = gw * gh

        val mask = BooleanArray(size)
        val visited = BooleanArray(size)

        var gy = 0
        while (gy < gh) {
            val py = (y0 + gy * step).coerceAtMost(h - 1)
            var gx = 0
            while (gx < gw) {
                val px = (x0 + gx * step).coerceAtMost(w - 1)
                mask[gy * gw + gx] =
                    isNeutralRingPixel(frame.pixel(px, py))
                gx++
            }
            gy++
        }

        val queue = IntArray(size)
        val candidates = ArrayList<ShakePrompt>(8)

        for (start in 0 until size) {
            if (!mask[start] || visited[start]) continue

            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true

            var minGX = gw
            var maxGX = -1
            var minGY = gh
            var maxGY = -1
            var sumGX = 0L
            var sumGY = 0L

            while (head < tail) {
                val index = queue[head++]
                val cy = index / gw
                val cx = index - cy * gw

                if (cx < minGX) minGX = cx
                if (cx > maxGX) maxGX = cx
                if (cy < minGY) minGY = cy
                if (cy > maxGY) maxGY = cy

                sumGX += cx
                sumGY += cy

                var ny = cy - 1
                while (ny <= cy + 1) {
                    if (ny in 0 until gh) {
                        var nx = cx - 1
                        while (nx <= cx + 1) {
                            if (nx in 0 until gw) {
                                val ni = ny * gw + nx
                                if (mask[ni] && !visited[ni]) {
                                    visited[ni] = true
                                    queue[tail++] = ni
                                }
                            }
                            nx++
                        }
                    }
                    ny++
                }
            }

            val boxWGrid = maxGX - minGX + 1
            val boxHGrid = maxGY - minGY + 1
            if (boxWGrid < 6 || boxHGrid < 6) continue

            val boxW = boxWGrid * step
            val boxH = boxHGrid * step
            val diameter = ((boxW + boxH) / 2f).roundToInt()

            if (diameter < shortSide * 0.045f ||
                diameter > shortSide * 0.31f
            ) continue

            val aspect =
                min(boxW, boxH).toFloat() /
                    max(boxW, boxH).toFloat()

            if (aspect < 0.72f) continue

            val boxArea = (boxWGrid * boxHGrid).coerceAtLeast(1)
            val fillRatio = tail.toFloat() / boxArea

            if (fillRatio < 0.018f || fillRatio > 0.58f) continue

            val centerX =
                x0 + ((sumGX.toFloat() / tail) * step).roundToInt()
            val centerY =
                y0 + ((sumGY.toFloat() / tail) * step).roundToInt()

            val ringCoverage =
                ringCoverage(frame, centerX, centerY, diameter)

            val darkCenter =
                darkCenterCoverage(frame, centerX, centerY, diameter)

            if (ringCoverage < 0.20f || darkCenter < 0.12f) continue

            val aspectScore =
                ((aspect - 0.72f) / 0.28f).coerceIn(0f, 1f)

            val confidence = (
                ringCoverage * 0.55f +
                    darkCenter * 0.25f +
                    aspectScore * 0.20f
                ).coerceIn(0f, 1f)

            candidates.add(
                ShakePrompt(
                    centerX,
                    centerY,
                    diameter,
                    confidence
                )
            )
        }

        if (candidates.isEmpty()) return null

        val sorted = candidates.sortedByDescending { it.confidence }
        val unique = ArrayList<ShakePrompt>()

        for (candidate in sorted) {
            val duplicate = unique.any {
                val dx = it.x - candidate.x
                val dy = it.y - candidate.y
                val distance =
                    sqrt((dx * dx + dy * dy).toFloat())

                distance <
                    max(it.diameter, candidate.diameter) * 0.28f
            }

            if (!duplicate) unique.add(candidate)
        }

        return unique.maxByOrNull { it.confidence }
    }

    fun detectReel(
        frame: CapturedFrame,
        specialRod: String = "None",
        previousFishX: Int? = null
    ): ReelObservation? {
        val w = frame.width
        val h = frame.height
        if (w < 320 || h < 480) return null

        val rod = specialRod.trim().lowercase()

        val trackLeftPct: Float
        val trackRightPct: Float
        val yTopPct: Float
        val yBottomPct: Float

        when (rod) {
            "bellona's waraxe" -> {
                trackLeftPct = 0.05f
                trackRightPct = 0.95f
                yTopPct = 0.837f
                yBottomPct = 0.875f
            }

            "stellarwave melody" -> {
                trackLeftPct = 0.22f
                trackRightPct = 0.78f
                yTopPct = 0.78f
                yBottomPct = 0.95f
            }

            "remembrance" -> {
                trackLeftPct = 0.30f
                trackRightPct = 0.70f
                yTopPct = 0.807f
                yBottomPct = 0.847f
            }

            else -> {
                trackLeftPct = 0.30f
                trackRightPct = 0.70f
                yTopPct = 0.837f
                yBottomPct = 0.875f
            }
        }

        val trackLeft =
            (w * trackLeftPct).roundToInt().coerceIn(0, w - 2)

        val trackRight =
            (w * trackRightPct).roundToInt()
                .coerceIn(trackLeft + 1, w - 1)

        val yTop =
            (h * yTopPct).roundToInt().coerceIn(0, h - 2)

        val yBottom =
            (h * yBottomPct).roundToInt()
                .coerceIn(yTop + 1, h - 1)

        val trackWidth = trackRight - trackLeft + 1

        val rows = ArrayList<RowSpan>()
        val yStep = max(2, h / 1200)
        val minSpan = max(18, (trackWidth * 0.055f).roundToInt())
        val maxSpan = (trackWidth * 0.88f).roundToInt()

        var y = yTop
        while (y <= yBottom) {
            var first = -1
            var last = -1
            var x = trackLeft

            while (x <= trackRight) {
                if (isBarWhite(frame.pixel(x, y))) {
                    if (first < 0) first = x
                    last = x
                }
                x++
            }

            if (first >= 0 && last >= first) {
                val span = last - first + 1

                if (span in minSpan..maxSpan) {
                    var hits = 0
                    var sx = first

                    while (sx <= last) {
                        if (isBarWhite(frame.pixel(sx, y))) hits++
                        sx++
                    }

                    if (hits >= 2) {
                        rows.add(RowSpan(first, last, y))
                    }
                }
            }

            y += yStep
        }

        if (rows.size < 3) return null

        val edgeTolerance =
            max(6, (trackWidth * 0.035f).roundToInt())

        var bestCluster: List<RowSpan>? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (anchor in rows) {
            val cluster = rows.filter {
                abs(it.left - anchor.left) <= edgeTolerance &&
                    abs(it.right - anchor.right) <= edgeTolerance
            }

            if (cluster.size < 3) continue

            val avgY = cluster.map { it.y }.average().toFloat()
            val width =
                cluster.map { it.right - it.left }.average().toFloat()

            val expectedY = h * ((yTopPct + yBottomPct) / 2f)
            val yPenalty = abs(avgY - expectedY) / h
            val widthPenalty =
                abs(width - trackWidth * 0.30f) / trackWidth

            val score =
                cluster.size * 1.0f -
                    yPenalty * 10f -
                    widthPenalty * 0.35f

            if (score > bestScore) {
                bestScore = score
                bestCluster = cluster
            }
        }

        val cluster = bestCluster ?: return null
        if (cluster.size < 3) return null

        val barLeft =
            median(cluster.map { it.left }).roundToInt()

        val barRight =
            median(cluster.map { it.right }).roundToInt()

        if (barRight <= barLeft) return null

        val centerY =
            median(cluster.map { it.y }).roundToInt()

        val fishX = findFishColor(
            frame,
            trackLeft,
            trackRight,
            centerY,
            previousFishX
        ) ?: findDarkNotch(
            frame,
            trackLeft,
            trackRight,
            centerY,
            previousFishX
        )

        val rowConfidence =
            (cluster.size / 12f).coerceIn(0.15f, 1f)

        return ReelObservation(
            trackLeft = trackLeft,
            trackRight = trackRight,
            barLeft = barLeft,
            barRight = barRight,
            fishX = fishX,
            centerY = centerY,
            confidence = rowConfidence
        )
    }

    private fun findFishColor(
        frame: CapturedFrame,
        trackLeft: Int,
        trackRight: Int,
        centerY: Int,
        previousFishX: Int?
    ): Int? {
        val h = frame.height
        val yRadius = max(8, (h * 0.018f).roundToInt())
        val yStart = (centerY - yRadius).coerceAtLeast(0)
        val yEnd = (centerY + yRadius).coerceAtMost(h - 1)

        val votes = IntArray(trackRight - trackLeft + 1)

        var y = yStart
        while (y <= yEnd) {
            var x = trackLeft

            while (x <= trackRight) {
                if (isFishColor(frame.pixel(x, y))) {
                    votes[x - trackLeft]++
                }
                x++
            }

            y += 2
        }

        var bestX = -1
        var bestVotes = 0
        var runStart = -1
        var runEnd = -1
        var x = 0

        while (x < votes.size) {
            if (votes[x] >= 2) {
                if (runStart < 0) runStart = x
                runEnd = x
            } else if (runStart >= 0) {
                val runWidth = runEnd - runStart + 1

                if (runWidth <= 28) {
                    var sum = 0
                    var i = runStart

                    while (i <= runEnd) {
                        sum += votes[i]
                        i++
                    }

                    val center =
                        trackLeft + (runStart + runEnd) / 2

                    val adjusted = sum -
                        if (previousFishX != null) {
                            abs(center - previousFishX) / 12
                        } else {
                            0
                        }

                    if (adjusted > bestVotes) {
                        bestVotes = adjusted
                        bestX = center
                    }
                }

                runStart = -1
                runEnd = -1
            }

            x++
        }

        if (runStart >= 0) {
            val runWidth = runEnd - runStart + 1

            if (runWidth <= 28) {
                var sum = 0
                var i = runStart

                while (i <= runEnd) {
                    sum += votes[i]
                    i++
                }

                val center =
                    trackLeft + (runStart + runEnd) / 2

                val adjusted = sum -
                    if (previousFishX != null) {
                        abs(center - previousFishX) / 12
                    } else {
                        0
                    }

                if (adjusted > bestVotes) {
                    bestVotes = adjusted
                    bestX = center
                }
            }
        }

        return if (bestX >= 0 && bestVotes >= 3) bestX else null
    }

    private fun findDarkNotch(
        frame: CapturedFrame,
        trackLeft: Int,
        trackRight: Int,
        centerY: Int,
        previousFishX: Int?
    ): Int? {
        val width = trackRight - trackLeft + 1
        val offset = max(5, (width * 0.025f).roundToInt())

        val yOffsets = intArrayOf(-12, -6, 0, 6, 12)
        val ySamples = yOffsets.map {
            (centerY + it).coerceIn(0, frame.height - 1)
        }

        var bestX = -1
        var bestVotes = 0
        var bestDistance = Int.MAX_VALUE

        var x = trackLeft + offset

        while (x < trackRight - offset) {
            var votes = 0

            for (sampleY in ySamples) {
                val center = luminance(frame.pixel(x, sampleY))
                val left = luminance(frame.pixel(x - offset, sampleY))
                val right = luminance(frame.pixel(x + offset, sampleY))
                val side = (left + right) / 2f
                val margin = max(9f, side * 0.18f)

                if (side - center >= margin) votes++
            }

            if (votes >= 3) {
                val distance =
                    if (previousFishX == null) 0
                    else abs(x - previousFishX)

                if (votes > bestVotes ||
                    (votes == bestVotes && distance < bestDistance)
                ) {
                    bestVotes = votes
                    bestDistance = distance
                    bestX = x
                }
            }

            x++
        }

        return if (bestX >= 0) bestX else null
    }

    private fun ringCoverage(
        frame: CapturedFrame,
        cx: Int,
        cy: Int,
        diameter: Int
    ): Float {
        var hits = 0
        var total = 0

        val radii = floatArrayOf(0.435f, 0.465f, 0.49f)

        for (radiusFactor in radii) {
            val radius = diameter * radiusFactor
            var angle = 0

            while (angle < 360) {
                val rad = Math.toRadians(angle.toDouble())
                val x = (cx + cos(rad) * radius).roundToInt()
                val y = (cy + sin(rad) * radius).roundToInt()

                if (x in 0 until frame.width &&
                    y in 0 until frame.height
                ) {
                    total++
                    if (isNeutralRingPixel(frame.pixel(x, y))) {
                        hits++
                    }
                }

                angle += 6
            }
        }

        return if (total == 0) 0f else hits.toFloat() / total
    }

    private fun darkCenterCoverage(
        frame: CapturedFrame,
        cx: Int,
        cy: Int,
        diameter: Int
    ): Float {
        var hits = 0
        var total = 0

        val radii = floatArrayOf(0.08f, 0.16f, 0.24f)

        for (radiusFactor in radii) {
            val radius = diameter * radiusFactor
            var angle = 0

            while (angle < 360) {
                val rad = Math.toRadians(angle.toDouble())
                val x = (cx + cos(rad) * radius).roundToInt()
                val y = (cy + sin(rad) * radius).roundToInt()

                if (x in 0 until frame.width &&
                    y in 0 until frame.height
                ) {
                    total++
                    if (luminance(frame.pixel(x, y)) < 155) {
                        hits++
                    }
                }

                angle += 15
            }
        }

        return if (total == 0) 0f else hits.toFloat() / total
    }

    private fun isNeutralRingPixel(argb: Int): Boolean {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff

        val minChannel = min(r, min(g, b))
        val maxChannel = max(r, max(g, b))

        return minChannel >= 138 &&
            maxChannel - minChannel <= 76
    }

    private fun isBarWhite(argb: Int): Boolean {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff

        return r >= 198 &&
            g >= 198 &&
            b >= 198 &&
            max(r, max(g, b)) - min(r, min(g, b)) <= 62
    }

    private fun isFishColor(argb: Int): Boolean {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff

        return abs(r - 0x43) <= 20 &&
            abs(g - 0x4B) <= 20 &&
            abs(b - 0x5B) <= 20
    }

    private fun luminance(argb: Int): Float {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff

        return 0.2126f * r +
            0.7152f * g +
            0.0722f * b
    }

    private fun median(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0

        val sorted = values.sorted()
        val mid = sorted.size / 2

        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid].toDouble()
        }
    }
}
