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
        require(pixels.size.toLong() >= width.toLong() * height.toLong())
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
        val y: Int,
        val hits: Int
    )

    private data class SearchProfile(
        val leftPct: Float,
        val rightPct: Float,
        val topPct: Float,
        val bottomPct: Float,
        val priority: Float
    )

    private data class BarCandidate(
        val left: Int,
        val right: Int,
        val centerY: Int,
        val confidence: Float
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

                minGX = min(minGX, cx)
                maxGX = max(maxGX, cx)
                minGY = min(minGY, cy)
                maxGY = max(maxGY, cy)

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

            val diameter =
                ((boxW + boxH) / 2f).roundToInt()

            if (
                diameter < shortSide * 0.045f ||
                diameter > shortSide * 0.31f
            ) continue

            val aspect =
                min(boxW, boxH).toFloat() /
                    max(boxW, boxH).toFloat()

            if (aspect < 0.72f) continue

            val boxArea =
                (boxWGrid * boxHGrid).coerceAtLeast(1)

            val fillRatio =
                tail.toFloat() / boxArea

            if (fillRatio < 0.018f || fillRatio > 0.58f) continue

            val centerX =
                x0 + ((sumGX.toFloat() / tail) * step).roundToInt()

            val centerY =
                y0 + ((sumGY.toFloat() / tail) * step).roundToInt()

            val ring =
                ringCoverage(frame, centerX, centerY, diameter)

            val dark =
                darkCenterCoverage(frame, centerX, centerY, diameter)

            if (ring < 0.20f || dark < 0.12f) continue

            val aspectScore =
                ((aspect - 0.72f) / 0.28f).coerceIn(0f, 1f)

            val confidence = (
                ring * 0.55f +
                    dark * 0.25f +
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

        val sorted =
            candidates.sortedByDescending { it.confidence }

        val unique = ArrayList<ShakePrompt>()

        for (candidate in sorted) {
            val duplicate = unique.any {
                val dx = it.x - candidate.x
                val dy = it.y - candidate.y

                sqrt((dx * dx + dy * dy).toFloat()) <
                    max(it.diameter, candidate.diameter) * 0.28f
            }

            if (!duplicate) unique.add(candidate)
        }

        return unique.maxByOrNull { it.confidence }
    }

    /**
     * Поиск мини-игры сразу по нескольким областям экрана.
     *
     * 1. Основная область выбранной удочки.
     * 2. Расширенная нижняя область.
     * 3. Широкая центральная область.
     * 4. Почти весь экран.
     *
     * Наличие маркера рыбы не обязательно для обнаружения мини-игры.
     */
    fun detectReel(
        frame: CapturedFrame,
        specialRod: String = "None",
        previousFishX: Int? = null
    ): ReelObservation? {
        val w = frame.width
        val h = frame.height

        if (w < 320 || h < 480) return null

        val rod = specialRod.trim().lowercase()

        val rodProfile = when (rod) {
            "bellona's waraxe" ->
                SearchProfile(0.05f, 0.95f, 0.79f, 0.94f, 0.12f)

            "stellarwave melody" ->
                SearchProfile(0.22f, 0.78f, 0.72f, 0.98f, 0.12f)

            "remembrance" ->
                SearchProfile(0.30f, 0.70f, 0.76f, 0.90f, 0.12f)

            else ->
                SearchProfile(0.30f, 0.70f, 0.78f, 0.93f, 0.12f)
        }

        val profiles = listOf(
            rodProfile,

            SearchProfile(
                rodProfile.leftPct,
                rodProfile.rightPct,
                0.56f,
                0.985f,
                0.07f
            ),

            SearchProfile(
                0.20f,
                0.80f,
                0.56f,
                0.985f,
                0.02f
            ),

            SearchProfile(
                0.05f,
                0.95f,
                0.56f,
                0.985f,
                -0.04f
            )
        )

        var best: ReelObservation? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for ((index, profile) in profiles.withIndex()) {
            val left =
                (w * profile.leftPct)
                    .roundToInt()
                    .coerceIn(0, w - 2)

            val right =
                (w * profile.rightPct)
                    .roundToInt()
                    .coerceIn(left + 1, w - 1)

            val top =
                (h * profile.topPct)
                    .roundToInt()
                    .coerceIn(0, h - 2)

            val bottom =
                (h * profile.bottomPct)
                    .roundToInt()
                    .coerceIn(top + 1, h - 1)

            val candidate = scanForWhiteBar(
                frame,
                left,
                right,
                top,
                bottom
            ) ?: continue

            val fish =
                findFishColor(
                    frame,
                    left,
                    right,
                    candidate.centerY,
                    previousFishX
                ) ?: findDarkNotch(
                    frame,
                    left,
                    right,
                    candidate.centerY,
                    previousFishX
                )

            val result = ReelObservation(
                trackLeft = left,
                trackRight = right,
                barLeft = candidate.left,
                barRight = candidate.right,
                fishX = fish,
                centerY = candidate.centerY,
                confidence = candidate.confidence
            )

            val score =
                candidate.confidence + profile.priority

            if (score > bestScore) {
                bestScore = score
                best = result
            }

            if (
                index <= 1 &&
                candidate.confidence >= 0.82f
            ) {
                return result
            }
        }

        return best
    }

    private fun scanForWhiteBar(
        frame: CapturedFrame,
        trackLeft: Int,
        trackRight: Int,
        yTop: Int,
        yBottom: Int
    ): BarCandidate? {
        val trackWidth = trackRight - trackLeft + 1

        if (trackWidth < 80 || yBottom - yTop < 6) {
            return null
        }

        val xStep = 2
        val yStep = max(2, frame.height / 900)

        val minSpan =
            max(14, (trackWidth * 0.045f).roundToInt())

        val maxSpan = min(
            (trackWidth * 0.86f).roundToInt(),
            (frame.width * 0.72f).roundToInt()
        )

        val rows = ArrayList<RowSpan>(160)

        var y = yTop

        while (y <= yBottom) {
            var runStart = -1
            var lastWhite = -1
            var hits = 0
            var gapSamples = 0

            val rowSegments = ArrayList<RowSpan>(6)

            var x = trackLeft

            fun finishRun() {
                if (
                    runStart >= 0 &&
                    hits >= 2 &&
                    lastWhite >= runStart
                ) {
                    rowSegments.add(
                        RowSpan(
                            runStart,
                            lastWhite,
                            y,
                            hits
                        )
                    )
                }

                runStart = -1
                lastWhite = -1
                hits = 0
                gapSamples = 0
            }

            while (x <= trackRight) {
                if (isBarWhite(frame.pixel(x, y))) {
                    if (runStart < 0) runStart = x

                    lastWhite = x
                    hits++
                    gapSamples = 0
                } else if (runStart >= 0) {
                    gapSamples++

                    if (gapSamples > 1) {
                        finishRun()
                    }
                }

                x += xStep
            }

            finishRun()

            if (rowSegments.isNotEmpty()) {
                val ordered = rowSegments.sortedBy { it.left }

                val mergeGap =
                    max(8, (trackWidth * 0.035f).roundToInt())

                var mergedLeft = ordered[0].left
                var mergedRight = ordered[0].right
                var mergedHits = ordered[0].hits

                fun emitMerged() {
                    val span = mergedRight - mergedLeft + 1

                    if (
                        span in minSpan..maxSpan &&
                        mergedHits >= 3
                    ) {
                        rows.add(
                            RowSpan(
                                mergedLeft,
                                mergedRight,
                                y,
                                mergedHits
                            )
                        )
                    }
                }

                for (segmentIndex in 1 until ordered.size) {
                    val segment = ordered[segmentIndex]

                    if (segment.left - mergedRight <= mergeGap) {
                        mergedRight = max(mergedRight, segment.right)
                        mergedHits += segment.hits
                    } else {
                        emitMerged()

                        mergedLeft = segment.left
                        mergedRight = segment.right
                        mergedHits = segment.hits
                    }
                }

                emitMerged()
            }

            y += yStep
        }

        if (rows.size < 3) return null

        val edgeTolerance =
            max(6, (trackWidth * 0.025f).roundToInt())

        val maxClusterHeight =
            max(24, (frame.height * 0.05f).roundToInt())

        var best: BarCandidate? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (anchor in rows) {
            val cluster = rows.filter {
                abs(it.left - anchor.left) <= edgeTolerance &&
                    abs(it.right - anchor.right) <= edgeTolerance &&
                    abs(it.y - anchor.y) <= maxClusterHeight
            }

            val distinctYs = cluster.map { it.y }.distinct()

            if (distinctYs.size < 3) continue

            val minY = distinctYs.minOrNull() ?: continue
            val maxY = distinctYs.maxOrNull() ?: continue

            val verticalSpan = maxY - minY + yStep

            if (
                verticalSpan < yStep * 2 ||
                verticalSpan > maxClusterHeight
            ) continue

            val barLeft =
                median(cluster.map { it.left }).roundToInt()

            val barRight =
                median(cluster.map { it.right }).roundToInt()

            if (barRight <= barLeft) continue

            val barWidth = barRight - barLeft + 1
            val widthRatio = barWidth.toFloat() / trackWidth

            if (widthRatio !in 0.045f..0.86f) continue

            val centerY =
                median(distinctYs).roundToInt()

            val rowsScore =
                (distinctYs.size / 7f).coerceIn(0f, 1f)

            val heightScore =
                (verticalSpan / max(
                    8f,
                    frame.height * 0.018f
                )).coerceIn(0f, 1f)

            val widthScore =
                (1f - abs(widthRatio - 0.30f) / 0.58f)
                    .coerceIn(0f, 1f)

            val lowerScore =
                ((centerY.toFloat() / frame.height - 0.52f) / 0.46f)
                    .coerceIn(0f, 1f)

            val centerX = (barLeft + barRight) / 2f

            val centerScore =
                (1f - abs(centerX - frame.width / 2f) /
                    (frame.width * 0.50f)).coerceIn(0f, 1f)

            val compactness =
                (1f - verticalSpan.toFloat() / maxClusterHeight)
                    .coerceIn(0f, 1f)

            val score =
                rowsScore * 0.34f +
                    heightScore * 0.18f +
                    widthScore * 0.20f +
                    lowerScore * 0.13f +
                    centerScore * 0.08f +
                    compactness * 0.07f

            if (score > bestScore) {
                bestScore = score

                best = BarCandidate(
                    left = barLeft,
                    right = barRight,
                    centerY = centerY,
                    confidence = score.coerceIn(0f, 1f)
                )
            }
        }

        return best?.takeIf { it.confidence >= 0.38f }
    }

    private fun findFishColor(
        frame: CapturedFrame,
        trackLeft: Int,
        trackRight: Int,
        centerY: Int,
        previousFishX: Int?
    ): Int? {
        val yRadius =
            max(8, (frame.height * 0.018f).roundToInt())

        val yStart =
            (centerY - yRadius).coerceAtLeast(0)

        val yEnd =
            (centerY + yRadius).coerceAtMost(frame.height - 1)

        val votes =
            IntArray(trackRight - trackLeft + 1)

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

        while (x <= votes.size) {
            if (x < votes.size && votes[x] >= 2) {
                if (runStart < 0) runStart = x
                runEnd = x
            } else if (runStart >= 0) {
                val runWidth = runEnd - runStart + 1

                if (runWidth <= max(28, votes.size / 12)) {
                    var sum = 0

                    for (i in runStart..runEnd) {
                        sum += votes[i]
                    }

                    val center =
                        trackLeft + (runStart + runEnd) / 2

                    val continuityPenalty =
                        if (previousFishX != null) {
                            abs(center - previousFishX) / 12
                        } else {
                            0
                        }

                    val adjusted = sum - continuityPenalty

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

        return if (bestX >= 0 && bestVotes >= 3) {
            bestX
        } else {
            null
        }
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
        val dy = max(5, (frame.height * 0.005f).roundToInt())

        val yOffsets = intArrayOf(
            -2 * dy,
            -dy,
            0,
            dy,
            2 * dy
        )

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

                val margin = max(9f, max(left, right) * 0.18f)

                if (
                    left - center >= margin &&
                    right - center >= margin
                ) {
                    votes++
                }
            }

            if (votes >= 3) {
                val distance =
                    if (previousFishX == null) {
                        0
                    } else {
                        abs(x - previousFishX)
                    }

                if (
                    votes > bestVotes ||
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

        for (factor in floatArrayOf(0.435f, 0.465f, 0.49f)) {
            val radius = diameter * factor
            var angle = 0

            while (angle < 360) {
                val rad = Math.toRadians(angle.toDouble())

                val x =
                    (cx + cos(rad) * radius).roundToInt()

                val y =
                    (cy + sin(rad) * radius).roundToInt()

                if (
                    x in 0 until frame.width &&
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

        return if (total == 0) {
            0f
        } else {
            hits.toFloat() / total
        }
    }

    private fun darkCenterCoverage(
        frame: CapturedFrame,
        cx: Int,
        cy: Int,
        diameter: Int
    ): Float {
        var hits = 0
        var total = 0

        for (factor in floatArrayOf(0.08f, 0.16f, 0.24f)) {
            val radius = diameter * factor
            var angle = 0

            while (angle < 360) {
                val rad = Math.toRadians(angle.toDouble())

                val x =
                    (cx + cos(rad) * radius).roundToInt()

                val y =
                    (cy + sin(rad) * radius).roundToInt()

                if (
                    x in 0 until frame.width &&
                    y in 0 until frame.height
                ) {
                    total++

                    if (luminance(frame.pixel(x, y)) < 155f) {
                        hits++
                    }
                }

                angle += 15
            }
        }

        return if (total == 0) {
            0f
        } else {
            hits.toFloat() / total
        }
    }

    private fun isNeutralRingPixel(argb: Int): Boolean {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff

        val low = min(r, min(g, b))
        val high = max(r, max(g, b))

        return low >= 138 && high - low <= 76
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

        // Правильный порядок RGB для цвета #5B4B43.
        return abs(r - 0x5B) <= 24 &&
            abs(g - 0x4B) <= 24 &&
            abs(b - 0x43) <= 24
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
