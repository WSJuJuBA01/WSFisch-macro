package com.wsfisch.mobile

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Распознавание Zodiac Wheel для Stellarwave Melody.
 *
 * Верхний ряд содержит эталонный порядок знаков, нижний — те же значки
 * в случайном порядке. Имена/Unicode символов не нужны: знаки сопоставляются
 * по нормализованной форме пиксельной маски.
 *
 * Детектор намеренно возвращает null, если не смог уверенно выделить ровно
 * 12 значков в обоих рядах. Это безопаснее случайного клика по игровому UI.
 */
object ZodiacVision {

    private const val GRID_SIZE = 16
    private const val EXPECTED_SIGNS = 12
    private const val MIN_MATCH_SCORE = 0.52f
    private const val MIN_MATCH_MARGIN = 0.035f

    data class Glyph(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val centerX: Int,
        val centerY: Int,
        internal val signature: BooleanArray
    )

    data class Board(
        val upperOrder: List<Glyph>,
        val shuffled: List<Glyph>,
        val confidence: Float
    ) {
        fun stableKey(): String {
            return buildString {
                append("U:")
                upperOrder.forEach {
                    append(it.centerX / 4)
                    append(',')
                    append(it.centerY / 4)
                    append(';')
                }
                append("|L:")
                shuffled.forEach {
                    append(it.centerX / 4)
                    append(',')
                    append(it.centerY / 4)
                    append(';')
                }
            }
        }
    }

    data class Match(
        val glyph: Glyph,
        val score: Float,
        val margin: Float
    )

    private data class Box(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    private data class GlyphRow(
        val glyphs: List<Glyph>,
        val top: Int,
        val bottom: Int,
        val pixelCount: Int
    ) {
        val centerY: Int get() = (top + bottom) / 2
    }

    fun detect(
        frame: CapturedFrame,
        headColorRgb: Int,
        barColorRgb: Int,
        headTolerance: Int = 30,
        barTolerance: Int = 26
    ): Board? {
        if (frame.width < 480 || frame.height < 700) return null

        val upperRows = collectRows(
            frame,
            headColorRgb,
            headTolerance
        )

        // После правильного клика значок может исчезнуть/сменить цвет.
        // Поэтому верхний ряд обязан иметь 12 знаков, а нижний допускает
        // от 1 до 12 ещё не выбранных значков.
        val lowerRows = collectRows(
            frame,
            barColorRgb,
            barTolerance,
            minGlyphCount = 1,
            maxGlyphCount = EXPECTED_SIGNS
        )

        if (upperRows.isEmpty() || lowerRows.isEmpty()) return null

        var bestBoard: Board? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (upper in upperRows) {
            for (lower in lowerRows) {
                if (lower.centerY <= upper.centerY) continue

                val verticalDistance = lower.centerY - upper.centerY
                if (verticalDistance < frame.height * 0.012f) continue
                if (verticalDistance > frame.height * 0.42f) continue

                val orderConfidence = rowPairConfidence(
                    upper.glyphs,
                    lower.glyphs
                ) ?: continue

                val upperSpan =
                    upper.glyphs.last().centerX - upper.glyphs.first().centerX
                val lowerSpan =
                    lower.glyphs.last().centerX - lower.glyphs.first().centerX

                val spanRatio =
                    min(upperSpan, lowerSpan).toFloat() /
                        max(upperSpan, lowerSpan).toFloat().coerceAtLeast(1f)

                // При первых кликах нижний ряд постепенно редеет, поэтому
                // требование одинакового горизонтального размаха снимается.
                if (lower.glyphs.size >= 8 && spanRatio < 0.48f) continue

                val occupancy =
                    lower.glyphs.size.toFloat() / EXPECTED_SIGNS

                val score =
                    orderConfidence * 0.82f +
                        spanRatio * 0.06f +
                        occupancy * 0.08f +
                        rowCompleteness(upper) * 0.02f +
                        rowCompleteness(lower) * 0.02f

                if (score > bestScore) {
                    bestScore = score
                    bestBoard = Board(
                        upperOrder = upper.glyphs,
                        shuffled = lower.glyphs,
                        confidence = score.coerceIn(0f, 1f)
                    )
                }
            }
        }

        return bestBoard?.takeIf { it.confidence >= 0.48f }
    }

    /**
     * Ищет на нижней полосе символ, совпадающий с верхним знаком sequenceIndex.
     * usedCenters блокирует повторный клик по уже обработанному символу.
     */
    fun findMatch(
        board: Board,
        sequenceIndex: Int,
        usedCenters: Set<Int> = emptySet()
    ): Match? {
        if (sequenceIndex !in board.upperOrder.indices) return null

        val target = board.upperOrder[sequenceIndex]
        val ranked = board.shuffled
            .asSequence()
            .filter { glyph ->
                usedCenters.none { abs(it - glyph.centerX) <= 5 }
            }
            .map { glyph ->
                glyph to similarity(target.signature, glyph.signature)
            }
            .sortedByDescending { it.second }
            .toList()

        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: 0f
        val margin = best.second - second

        if (best.second < MIN_MATCH_SCORE) return null
        if (ranked.size > 1 && margin < MIN_MATCH_MARGIN) return null

        return Match(
            glyph = best.first,
            score = best.second,
            margin = margin
        )
    }

    private fun collectRows(
        frame: CapturedFrame,
        targetRgb: Int,
        tolerance: Int,
        minGlyphCount: Int = EXPECTED_SIGNS,
        maxGlyphCount: Int = EXPECTED_SIGNS
    ): List<GlyphRow> {
        val step = 2
        val x0 = (frame.width * 0.035f).roundToInt().coerceIn(0, frame.width - 1)
        val x1 = (frame.width * 0.965f).roundToInt().coerceIn(x0 + 1, frame.width - 1)
        val y0 = (frame.height * 0.18f).roundToInt().coerceIn(0, frame.height - 1)
        val y1 = (frame.height * 0.985f).roundToInt().coerceIn(y0 + 1, frame.height - 1)

        val gridW = ((x1 - x0) / step + 1).coerceAtLeast(1)
        val gridH = ((y1 - y0) / step + 1).coerceAtLeast(1)
        val mask = BooleanArray(gridW * gridH)
        val rowCounts = IntArray(gridH)

        var totalPixels = 0
        var gy = 0
        while (gy < gridH) {
            val py = y0 + gy * step
            var gx = 0
            var count = 0

            while (gx < gridW) {
                val px = x0 + gx * step
                val hit = nearRgb(
                    frame.pixel(px, py),
                    targetRgb,
                    tolerance
                )

                mask[gy * gridW + gx] = hit
                if (hit) count++
                gx++
            }

            rowCounts[gy] = count
            totalPixels += count
            gy++
        }

        val minimumTotalPixels = if (minGlyphCount < EXPECTED_SIGNS) 12 else 45
        if (totalPixels < minimumTotalPixels) return emptyList()

        val minRowPixels = if (minGlyphCount < EXPECTED_SIGNS) {
            max(1, (gridW * 0.0008f).roundToInt())
        } else {
            max(3, (gridW * 0.0022f).roundToInt())
        }
        val maxBandHeight = max(48, (frame.height * 0.075f / step).roundToInt())
        val maxInactiveGap = max(4, (frame.height * 0.006f / step).roundToInt())

        val bands = ArrayList<IntRange>()
        var bandStart = -1
        var lastActive = -1

        for (row in rowCounts.indices) {
            if (rowCounts[row] >= minRowPixels) {
                if (bandStart < 0) bandStart = row
                lastActive = row
            } else if (
                bandStart >= 0 &&
                row - lastActive > maxInactiveGap
            ) {
                if (lastActive - bandStart >= 3) {
                    bands.add(bandStart..lastActive)
                }
                bandStart = -1
                lastActive = -1
            }
        }

        if (bandStart >= 0 && lastActive - bandStart >= 3) {
            bands.add(bandStart..lastActive)
        }

        val result = ArrayList<GlyphRow>()

        for (band in bands) {
            if (band.last - band.first + 1 > maxBandHeight) continue

            val columnCounts = IntArray(gridW)
            var pixelCount = 0

            for (row in band) {
                val offset = row * gridW
                for (column in 0 until gridW) {
                    if (mask[offset + column]) {
                        columnCounts[column]++
                        pixelCount++
                    }
                }
            }

            if (pixelCount < minimumTotalPixels) continue

            val groups = splitColumns(
                columnCounts,
                mergeGap = max(3, (gridW * 0.008f).roundToInt())
            )

            if (groups.size !in minGlyphCount..maxGlyphCount) continue

            val glyphs = ArrayList<Glyph>(groups.size)

            for (group in groups) {
                val box = makeGlyphBox(
                    mask = mask,
                    gridW = gridW,
                    x0 = x0,
                    y0 = y0,
                    step = step,
                    band = band,
                    columns = group
                ) ?: continue

                if (
                    box.width < 5 ||
                    box.height < 7 ||
                    box.width > frame.width * 0.13f ||
                    box.height > frame.height * 0.07f
                ) {
                    continue
                }

                glyphs.add(
                    Glyph(
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom,
                        centerX = box.centerX,
                        centerY = box.centerY,
                        signature = makeSignature(
                            frame,
                            box,
                            targetRgb,
                            tolerance
                        )
                    )
                )
            }

            if (glyphs.size !in minGlyphCount..maxGlyphCount) continue

            val sorted = glyphs.sortedBy { it.centerX }
            val span = sorted.last().centerX - sorted.first().centerX
            if (sorted.size >= 8 && span < frame.width * 0.32f) continue

            result.add(
                GlyphRow(
                    glyphs = sorted,
                    top = sorted.minOf { it.top },
                    bottom = sorted.maxOf { it.bottom },
                    pixelCount = pixelCount
                )
            )
        }

        return result.distinctBy { row ->
            row.glyphs.joinToString("|") { it.centerX.toString() }
        }
    }

    private fun splitColumns(
        counts: IntArray,
        mergeGap: Int
    ): List<IntRange> {
        val result = ArrayList<IntRange>()
        var start = -1
        var last = -1

        for (x in counts.indices) {
            if (counts[x] > 0) {
                if (start < 0) start = x
                last = x
            } else if (start >= 0 && x - last > mergeGap) {
                result.add(start..last)
                start = -1
                last = -1
            }
        }

        if (start >= 0) result.add(start..last)
        return result
    }

    private fun makeGlyphBox(
        mask: BooleanArray,
        gridW: Int,
        x0: Int,
        y0: Int,
        step: Int,
        band: IntRange,
        columns: IntRange
    ): Box? {
        var minGX = columns.first
        var maxGX = columns.last
        var minGY = band.last
        var maxGY = band.first
        var hits = 0

        for (gy in band) {
            val offset = gy * gridW
            for (gx in columns) {
                if (mask[offset + gx]) {
                    minGY = min(minGY, gy)
                    maxGY = max(maxGY, gy)
                    hits++
                }
            }
        }

        if (hits < 4) return null

        return Box(
            left = x0 + minGX * step,
            top = y0 + minGY * step,
            right = x0 + maxGX * step,
            bottom = y0 + maxGY * step
        )
    }

    private fun makeSignature(
        frame: CapturedFrame,
        box: Box,
        targetRgb: Int,
        tolerance: Int
    ): BooleanArray {
        val signature = BooleanArray(GRID_SIZE * GRID_SIZE)

        for (gy in 0 until GRID_SIZE) {
            for (gx in 0 until GRID_SIZE) {
                val cellLeft = box.left + (box.width * gx) / GRID_SIZE
                val cellRight = box.left + (box.width * (gx + 1)) / GRID_SIZE
                val cellTop = box.top + (box.height * gy) / GRID_SIZE
                val cellBottom = box.top + (box.height * (gy + 1)) / GRID_SIZE

                val xA = cellLeft.coerceIn(0, frame.width - 1)
                val xB = max(xA, (cellRight - 1).coerceIn(0, frame.width - 1))
                val yA = cellTop.coerceIn(0, frame.height - 1)
                val yB = max(yA, (cellBottom - 1).coerceIn(0, frame.height - 1))

                var found = false
                val sampleXs = intArrayOf(
                    xA,
                    (xA + xB) / 2,
                    xB
                )
                val sampleYs = intArrayOf(
                    yA,
                    (yA + yB) / 2,
                    yB
                )

                for (sampleY in sampleYs) {
                    for (sampleX in sampleXs) {
                        if (
                            nearRgb(
                                frame.pixel(sampleX, sampleY),
                                targetRgb,
                                tolerance
                            )
                        ) {
                            found = true
                            break
                        }
                    }
                    if (found) break
                }

                signature[gy * GRID_SIZE + gx] = found
            }
        }

        return signature
    }

    private fun rowPairConfidence(
        upper: List<Glyph>,
        lower: List<Glyph>
    ): Float? {
        if (
            upper.size != EXPECTED_SIGNS ||
            lower.isEmpty() ||
            lower.size > EXPECTED_SIGNS
        ) {
            return null
        }

        data class PairScore(
            val upper: Int,
            val lower: Int,
            val score: Float
        )

        val pairs = ArrayList<PairScore>(upper.size * lower.size)
        for (u in upper.indices) {
            for (l in lower.indices) {
                pairs.add(
                    PairScore(
                        u,
                        l,
                        similarity(
                            upper[u].signature,
                            lower[l].signature
                        )
                    )
                )
            }
        }

        pairs.sortByDescending { it.score }

        val usedUpper = HashSet<Int>()
        val usedLower = HashSet<Int>()
        var total = 0f
        var count = 0

        for (pair in pairs) {
            if (pair.upper in usedUpper || pair.lower in usedLower) continue
            usedUpper.add(pair.upper)
            usedLower.add(pair.lower)
            total += pair.score
            count++
            if (count == EXPECTED_SIGNS) break
        }

        if (count != lower.size) return null

        val average = total / count
        return average.takeIf { it >= 0.43f }
    }

    private fun rowCompleteness(row: GlyphRow): Float {
        return (row.pixelCount / 850f).coerceIn(0f, 1f)
    }

    private fun similarity(
        first: BooleanArray,
        second: BooleanArray
    ): Float {
        if (first.size != second.size || first.isEmpty()) return 0f

        var intersection = 0
        var union = 0

        for (i in first.indices) {
            if (first[i] || second[i]) union++
            if (first[i] && second[i]) intersection++
        }

        return if (union == 0) 0f else intersection.toFloat() / union
    }

    private fun nearRgb(
        argb: Int,
        targetRgb: Int,
        tolerance: Int
    ): Boolean {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff

        val tr = (targetRgb shr 16) and 0xff
        val tg = (targetRgb shr 8) and 0xff
        val tb = targetRgb and 0xff

        val tol = tolerance.coerceIn(1, 110)

        return abs(r - tr) <= tol &&
            abs(g - tg) <= tol &&
            abs(b - tb) <= tol
    }
}
