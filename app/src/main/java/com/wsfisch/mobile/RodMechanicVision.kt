package com.wsfisch.mobile

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Специализированные распознаватели профилей WSFisch.
 *
 * Реализация написана на Kotlin для Android Surface screenshot frames.
 * Она не зависит от AutoHotkey, GDI или Win32 API.
 */
object RodMechanicVision {

    data class TwigPrompt(
        val leftFishX: Int,
        val rightFishX: Int,
        val iconY: Int,
        val labelY: Int,
        val confidence: Float
    )

    data class ManiaTap(
        val lane: Int,
        val x: Int,
        val y: Int,
        val timeToHitMs: Long
    )

    data class LullabyTap(
        val sector: Int,
        val x: Int,
        val y: Int
    )

    data class BellonaSide(
        val trackLeft: Int,
        val trackRight: Int,
        val barLeft: Int,
        val barRight: Int,
        val fishX: Int?
    ) {
        val barCenter: Float get() = (barLeft + barRight) / 2f
        val barWidth: Int get() = (barRight - barLeft + 1).coerceAtLeast(1)
    }

    data class BellonaObservation(
        val left: BellonaSide?,
        val right: BellonaSide?,
        val confidence: Float
    )

    private data class Run(
        val left: Int,
        val right: Int,
        val y: Int,
        val hits: Int
    ) {
        val width: Int get() = right - left + 1
        val center: Int get() = (left + right) / 2
    }

    private data class MarkerPoint(
        val x: Int,
        val y: Int,
        val votes: Int
    )

    /**
     * Полный путь обнаружения для специальных Bar с нестандартным цветом.
     * Если белый детектор не нашёл полосу, создаются ограниченные поисковые
     * окна вокруг ожидаемой нижней области мини-игры и запускается цветовой
     * сканер профиля.
     */
    fun detectSpecialReel(
        frame: CapturedFrame,
        rod: String,
        base: ReelObservation?,
        previousFishX: Int?,
        fishColors: List<Int>,
        barColors: List<Int>,
        fishTolerance: Int,
        noteColorRgb: Int,
        noteTolerance: Int,
        noteBias: Int,
        noteHandoffPx: Int = 50,
        remembranceMode: String,
        halibutPlayMinigame: Boolean
    ): ReelObservation? {
        val normalized = rod.trim().lowercase()
        val specialized = normalized in setOf(
            "verdant oath", "migurod", "tryhard rod", "remembrance",
            "noiseform", "halibut harpoon"
        ) || (normalized == "castbound" && barColors.isNotEmpty())
        if (!specialized) {
            return base?.let {
                refineReel(
                    frame, rod, it, previousFishX, fishColors, barColors, fishTolerance,
                    noteColorRgb, noteTolerance, noteBias, noteHandoffPx, remembranceMode,
                    halibutPlayMinigame
                )
            }
        }

        val seeds = if (base != null) listOf(base) else {
            val leftPct = if (normalized == "halibut harpoon") 0.22f else 0.25f
            val rightPct = if (normalized == "halibut harpoon") 0.78f else 0.75f
            listOf(0.775f, 0.835f, 0.895f).map { y ->
                val left = (frame.width * leftPct).roundToInt().coerceIn(0, frame.width - 2)
                val right = (frame.width * rightPct).roundToInt().coerceIn(left + 1, frame.width - 1)
                val centerY = (frame.height * y).roundToInt().coerceIn(0, frame.height - 1)
                ReelObservation(left, right, left, min(right, left + (right - left) / 3), null, centerY, 0.20f)
            }
        }

        var best: ReelObservation? = null
        for (seed in seeds) {
            val measured = when (normalized) {
                "castbound" -> detectColorBar(frame, seed, barColors, 20, 0.002f, 0.86f)
                "verdant oath" -> detectColorBar(
                    frame, seed, if (barColors.isNotEmpty()) barColors else listOf(0x3B8113),
                    22, 0.035f, 0.86f
                )
                "migurod" -> detectPredicateBar(frame, seed, 0.05f, 0.85f) { p ->
                    val r = red(p); val g = green(p); val b = blue(p)
                    r > 190 && g in 140..245 && b in 90..215 && r - b > 40
                }
                "tryhard rod" -> if (barColors.isNotEmpty()) {
                    detectColorBar(frame, seed, barColors, 20, 0.02f, 0.60f)
                } else {
                    detectPredicateBar(frame, seed, 0.02f, 0.60f) { p ->
                        val r = red(p); val g = green(p); val b = blue(p)
                        r in 28..205 && r > g * 1.55 && r > b * 1.7 && g < 95 && b < 75
                    }
                }
                "remembrance" -> {
                    val range = if (remembranceMode == "Departed") 55..110 else 145..235
                    detectPredicateBar(frame, seed, 0.08f, 0.35f) { p ->
                        val r = red(p); val g = green(p); val b = blue(p)
                        abs(r - g) <= 10 && abs(g - b) <= 10 && ((r + g + b) / 3) in range
                    }
                }
                "noiseform" -> detectColorBar(frame, seed, listOf(0x33A95F, 0x2C894D), 18, 0.15f, 0.45f)
                "halibut harpoon" -> detectColorBar(frame, seed, listOf(0x5D52A8), 18, 0.04f, 0.86f)
                else -> null
            } ?: continue

            val refined = refineReel(
                frame, rod, measured, previousFishX, fishColors, barColors, fishTolerance,
                noteColorRgb, noteTolerance, noteBias, noteHandoffPx, remembranceMode,
                halibutPlayMinigame
            )
            if (best == null || refined.confidence > best.confidence) best = refined
        }
        return best ?: base?.let {
            refineReel(
                frame, rod, it, previousFishX, fishColors, barColors, fishTolerance,
                noteColorRgb, noteTolerance, noteBias, noteHandoffPx, remembranceMode,
                halibutPlayMinigame
            )
        }
    }

    /**
     * Для специальных удочек заменяет стандартное измерение Bar там,
     * где его цвет отличается от белого. После этого ищет особую цель
     * (меч, белую линию, note, голубой маркер и т. п.).
     */
    fun refineReel(
        frame: CapturedFrame,
        rod: String,
        base: ReelObservation,
        previousFishX: Int?,
        fishColors: List<Int>,
        barColors: List<Int>,
        fishTolerance: Int,
        noteColorRgb: Int,
        noteTolerance: Int,
        noteBias: Int,
        noteHandoffPx: Int = 50,
        remembranceMode: String,
        halibutPlayMinigame: Boolean
    ): ReelObservation {
        val normalized = rod.trim().lowercase()
        val selectedBar = when (normalized) {
            "verdant oath" -> detectColorBar(
                frame, base, if (barColors.isNotEmpty()) barColors else listOf(0x3B8113),
                22, 0.035f, 0.86f
            )
            "migurod" -> detectPredicateBar(
                frame, base,
                minWidthRatio = 0.05f,
                maxWidthRatio = 0.85f
            ) { p ->
                val r = red(p); val g = green(p); val b = blue(p)
                r > 190 && g in 140..245 && b in 90..215 && r - b > 40
            }
            "tryhard rod" -> if (barColors.isNotEmpty()) {
                detectColorBar(frame, base, barColors, 20, 0.02f, 0.60f)
            } else {
                detectPredicateBar(
                    frame, base,
                    minWidthRatio = 0.02f,
                    maxWidthRatio = 0.60f
                ) { p ->
                    val r = red(p); val g = green(p); val b = blue(p)
                    r in 28..205 && r > g * 1.55 && r > b * 1.7 && g < 95 && b < 75
                }
            }
            "castbound" -> if (barColors.isNotEmpty()) {
                detectColorBar(frame, base, barColors, 20, 0.002f, 0.86f)
            } else null
            "remembrance" -> {
                val range = if (remembranceMode == "Departed") 55..110 else 145..235
                detectPredicateBar(
                    frame, base,
                    minWidthRatio = 0.08f,
                    maxWidthRatio = 0.35f
                ) { p ->
                    val r = red(p); val g = green(p); val b = blue(p)
                    abs(r - g) <= 10 && abs(g - b) <= 10 && ((r + g + b) / 3) in range
                }
            }
            "noiseform" -> detectColorBar(
                frame, base, listOf(0x33A95F, 0x2C894D), 18, 0.15f, 0.45f
            )
            else -> null
        }

        val bar = selectedBar ?: base
        val coloredFish = if (fishColors.isNotEmpty()) {
            findColorMarker(
                frame, bar.trackLeft, bar.trackRight, bar.centerY,
                fishColors, fishTolerance, previousFishX,
                verticalRadiusRatio = 0.025f
            )
        } else null

        val targetX = when (normalized) {
            "pinions aria" -> {
                val note = findColorMarkerPoint(
                    frame,
                    bar.trackLeft,
                    bar.trackRight,
                    bar.centerY - (frame.height * 0.075f).roundToInt(),
                    noteColorRgb,
                    noteTolerance,
                    previousFishX,
                    verticalRadiusRatio = 0.17f
                )
                choosePinionsTarget(
                    fishX = coloredFish ?: bar.fishX,
                    note = note,
                    reel = bar,
                    frame = frame,
                    noteBias = noteBias,
                    handoffPx = noteHandoffPx
                )
            }
            "noiseform" -> {
                val gimmick = findColorMarker(
                    frame, bar.trackLeft, bar.trackRight,
                    bar.centerY - (frame.height * 0.04f).roundToInt(),
                    listOf(noteColorRgb), noteTolerance, previousFishX,
                    verticalRadiusRatio = 0.18f
                )
                gimmick ?: coloredFish ?: bar.fishX
            }
            "halibut harpoon" -> {
                if (halibutPlayMinigame) {
                    findBlueMarker(frame, bar.trackLeft, bar.trackRight, bar.centerY)
                        ?: coloredFish ?: bar.fishX
                } else {
                    coloredFish ?: bar.fishX
                }
            }
            "remembrance" -> {
                val white = findColorMarker(
                    frame, bar.trackLeft, bar.trackRight, bar.centerY,
                    listOf(0xFFFFFF), 14, previousFishX,
                    verticalRadiusRatio = 0.035f
                )
                val dark = findDarkMarker(frame, bar, previousFishX)
                remembranceTarget(white, dark, bar.barWidth, remembranceMode)
                    ?: coloredFish ?: bar.fishX
            }
            "tryhard rod" -> coloredFish
                ?: findDarkMarkerAboveBar(frame, bar, previousFishX)
                ?: findDarkMarker(frame, bar, previousFishX)
                ?: bar.fishX
            "verdant oath", "migurod", "castbound", "stellarwave melody" ->
                coloredFish ?: bar.fishX
            "migu rod" -> coloredFish ?: bar.fishX
            else -> coloredFish ?: bar.fishX
        }

        return bar.copy(fishX = targetX)
    }

    private fun choosePinionsTarget(
        fishX: Int?,
        note: MarkerPoint?,
        reel: ReelObservation,
        frame: CapturedFrame,
        noteBias: Int,
        handoffPx: Int
    ): Int? {
        if (note == null || noteBias <= 0) return fishX
        if (fishX == null && note.y >= reel.centerY) return null

        val barCenter = reel.barCenter
        val halfBar = reel.barWidth / 2f
        val reach = max(4f, halfBar - max(14f, reel.barWidth * 0.30f))
        val coverX = if (fishX != null && abs(fishX - note.x) <= reach) fishX else note.x

        // Notes fall into the bar's hit line. Estimate their remaining time and
        // only leave the fish when the bar can realistically reach the note.
        // 0.60 screen-heights/second is the conservative fallback used while
        // there are too few frames to estimate a stable note velocity.
        val fallSpeed = frame.height * 0.60f
        val distanceToImpact = (reel.centerY - note.y).coerceAtLeast(0)
        val timeToImpact = distanceToImpact / fallSpeed
        // Leave the current note before it lands so the controller can hand off
        // to the next priority target instead of chasing a note already at the line.
        if (fishX != null && distanceToImpact <= handoffPx.coerceIn(0, 250)) return fishX
        val horizontalSpeed = frame.width * 0.30f
        val travelTime = abs(coverX - barCenter) / horizontalSpeed

        if (timeToImpact + 0.05f < travelTime + 0.15f) return fishX
        if (timeToImpact > travelTime + 0.90f) return fishX

        if (fishX != null && coverX != fishX) {
            val timeAwayFromFish = timeToImpact + abs(coverX - fishX) / horizontalSpeed + 0.10f
            val progressFloor = 0.12f + (100 - noteBias.coerceIn(0, 100)) * 0.005f
            // Without a reliable progress meter, do not commit to a long detour.
            if (timeAwayFromFish > 1.60f || progressFloor > 0.60f) return fishX
        }

        if (fishX == null) return coverX
        val weight = noteBias.coerceIn(0, 100) / 100f
        return (coverX * weight + fishX * (1f - weight)).roundToInt()
    }

    private fun findColorMarkerPoint(
        frame: CapturedFrame,
        trackLeft: Int,
        trackRight: Int,
        centerY: Int,
        targetRgb: Int,
        tolerance: Int,
        previousX: Int?,
        verticalRadiusRatio: Float
    ): MarkerPoint? {
        val left = trackLeft.coerceIn(0, frame.width - 1)
        val right = trackRight.coerceIn(left + 1, frame.width - 1)
        val radius = max(8, (frame.height * verticalRadiusRatio).roundToInt())
        val top = (centerY - radius).coerceIn(0, frame.height - 1)
        val bottom = (centerY + radius).coerceIn(top + 1, frame.height - 1)
        val votes = IntArray(right - left + 1)
        val ySums = LongArray(votes.size)
        val stepY = 2
        for (y in top..bottom step stepY) {
            for (x in left..right) {
                if (nearRgb(frame.pixel(x, y), targetRgb, tolerance)) {
                    val index = x - left
                    votes[index]++
                    ySums[index] += y.toLong()
                }
            }
        }
        val x = bestVoteRun(
            votes, left, previousX, minVotes = 3,
            maxWidth = max(28, votes.size / 10)
        ) ?: return null
        val from = (x - left - 8).coerceAtLeast(0)
        val to = (x - left + 8).coerceAtMost(votes.lastIndex)
        var totalVotes = 0L
        var totalY = 0L
        for (index in from..to) {
            totalVotes += votes[index]
            totalY += ySums[index]
        }
        if (totalVotes < 3L) return null
        return MarkerPoint(x, (totalY / totalVotes).toInt(), totalVotes.toInt())
    }

    private fun remembranceTarget(
        whiteX: Int?,
        blackX: Int?,
        barWidth: Int,
        mode: String
    ): Int? {
        val white = whiteX ?: return null
        val black = blackX ?: return white
        val half = max(4f, barWidth / 2f)
        val departed = mode == "Departed"
        val clear = half + if (departed) barWidth * 0.15f else barWidth * 0.06f
        if (abs(black - white) > clear + half) return white
        return if (black > white) {
            var aim = black - clear
            if (!departed) aim = max(aim, white - half + 2f)
            aim.roundToInt()
        } else {
            var aim = black + clear
            if (!departed) aim = min(aim, white + half - 2f)
            aim.roundToInt()
        }
    }

    private fun detectColorBar(
        frame: CapturedFrame,
        base: ReelObservation,
        colors: List<Int>,
        tolerance: Int,
        minRatio: Float,
        maxRatio: Float
    ): ReelObservation? = detectPredicateBar(
        frame, base, minRatio, maxRatio
    ) { pixel -> colors.any { nearRgb(pixel, it, tolerance) } }

    private fun detectPredicateBar(
        frame: CapturedFrame,
        base: ReelObservation,
        minWidthRatio: Float,
        maxWidthRatio: Float,
        predicate: (Int) -> Boolean
    ): ReelObservation? {
        val left = base.trackLeft.coerceIn(0, frame.width - 1)
        val right = base.trackRight.coerceIn(left + 1, frame.width - 1)
        val width = right - left + 1
        if (width < 60) return null

        val yRadius = max(3, (frame.height * 0.010f).roundToInt())
        val ys = listOf(
            (base.centerY - yRadius).coerceIn(0, frame.height - 1),
            base.centerY.coerceIn(0, frame.height - 1),
            (base.centerY + yRadius).coerceIn(0, frame.height - 1)
        ).distinct()
        val runs = ArrayList<Run>()

        for (y in ys) {
            var start = -1
            var last = -1
            var hits = 0
            var gap = 0
            val mergeGap = max(3, (width * 0.04f).roundToInt())
            fun finish() {
                if (start >= 0 && last >= start) {
                    val span = last - start + 1
                    if (span >= (width * minWidthRatio).roundToInt().coerceAtLeast(3) &&
                        span <= (width * maxWidthRatio).roundToInt()) {
                        runs.add(Run(start, last, y, hits))
                    }
                }
                start = -1; last = -1; hits = 0; gap = 0
            }
            var x = left
            while (x <= right) {
                if (predicate(frame.pixel(x, y))) {
                    if (start < 0) start = x
                    last = x; hits++; gap = 0
                } else if (start >= 0) {
                    gap++
                    // A fish/marker sprite may cover a short part of the bar.
                    // Bridge that interruption rather than truncating the bar.
                    if (gap > mergeGap) finish()
                }
                x++
            }
            finish()
        }
        if (runs.isEmpty()) return null

        var best: Run? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (candidate in runs) {
            val matching = runs.filter {
                abs(it.left - candidate.left) <= max(7, width / 35) &&
                    abs(it.right - candidate.right) <= max(7, width / 35)
            }
            val rows = matching.map { it.y }.distinct().size
            if (rows < 2) continue
            val medianLeft = matching.map { it.left }.sorted()[matching.size / 2]
            val medianRight = matching.map { it.right }.sorted()[matching.size / 2]
            val ratio = (medianRight - medianLeft + 1).toFloat() / width
            if (ratio !in minWidthRatio..maxWidthRatio) continue
            val score = rows * 0.28f + candidate.hits / width.toFloat() +
                (1f - abs(ratio - 0.30f)).coerceIn(0f, 1f) * 0.18f
            if (score > bestScore) {
                bestScore = score
                best = Run(medianLeft, medianRight, matching.map { it.y }.average().roundToInt(), rows)
            }
        }
        val result = best ?: return null
        return base.copy(
            barLeft = result.left,
            barRight = result.right,
            centerY = result.y,
            confidence = max(base.confidence, (0.42f + bestScore * 0.16f).coerceIn(0.42f, 0.88f))
        )
    }

    private fun findColorMarker(
        frame: CapturedFrame,
        left: Int,
        right: Int,
        centerY: Int,
        colors: List<Int>,
        tolerance: Int,
        previousX: Int?,
        verticalRadiusRatio: Float
    ): Int? {
        if (colors.isEmpty()) return null
        val x0 = left.coerceIn(0, frame.width - 1)
        val x1 = right.coerceIn(x0 + 1, frame.width - 1)
        val radius = max(6, (frame.height * verticalRadiusRatio).roundToInt())
        val y0 = (centerY - radius).coerceIn(0, frame.height - 1)
        val y1 = (centerY + radius).coerceIn(y0 + 1, frame.height - 1)
        val votes = IntArray(x1 - x0 + 1)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                if (colors.any { nearRgb(frame.pixel(x, y), it, tolerance) }) {
                    votes[x - x0]++
                }
                x += 1
            }
            y += 2
        }
        return bestVoteRun(votes, x0, previousX, minVotes = 3, maxWidth = max(28, votes.size / 10))
    }

    private fun bestVoteRun(
        votes: IntArray,
        originX: Int,
        previousX: Int?,
        minVotes: Int,
        maxWidth: Int
    ): Int? {
        var best: Int? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var start = -1
        fun score(end: Int) {
            if (start < 0) return
            val runWidth = end - start + 1
            if (runWidth !in 2..maxWidth) return
            val total = (start..end).sumOf { votes[it] }
            if (total < minVotes) return
            val center = originX + (start + end) / 2
            val penalty = if (previousX != null) abs(center - previousX) * 0.08f else 0f
            val current = total - penalty
            if (current > bestScore) { bestScore = current; best = center }
        }
        for (i in votes.indices) {
            if (votes[i] > 0) {
                if (start < 0) start = i
            } else if (start >= 0) {
                score(i - 1); start = -1
            }
        }
        if (start >= 0) score(votes.lastIndex)
        return best
    }

    private fun findDarkMarker(
        frame: CapturedFrame,
        reel: ReelObservation,
        previousX: Int?
    ): Int? {
        val radius = max(8, (frame.height * 0.028f).roundToInt())
        val y0 = (reel.centerY - radius).coerceAtLeast(0)
        val y1 = (reel.centerY + radius).coerceAtMost(frame.height - 1)
        val votes = IntArray(reel.trackWidth)
        for (y in y0..y1 step 2) {
            for (x in reel.trackLeft..reel.trackRight) {
                val p = frame.pixel(x, y)
                if (red(p) < 48 && green(p) < 42 && blue(p) < 52) {
                    votes[x - reel.trackLeft]++
                }
            }
        }
        return bestVoteRun(votes, reel.trackLeft, previousX, 4, max(26, reel.trackWidth / 16))
    }

    private fun findDarkMarkerAboveBar(
        frame: CapturedFrame,
        reel: ReelObservation,
        previousX: Int?
    ): Int? {
        val y0 = (reel.centerY - frame.height * 0.030f).roundToInt().coerceAtLeast(0)
        val y1 = (reel.centerY - frame.height * 0.006f).roundToInt().coerceAtLeast(y0 + 1)
        val votes = IntArray(reel.trackWidth)
        for (y in y0..y1 step 2) {
            for (x in reel.trackLeft..reel.trackRight) {
                val p = frame.pixel(x, y)
                val r = red(p); val g = green(p); val b = blue(p)
                if (r < 72 && g < 58 && b < 62) votes[x - reel.trackLeft]++
            }
        }
        return bestVoteRun(
            votes,
            reel.trackLeft,
            previousX,
            minVotes = 2,
            maxWidth = max(24, reel.trackWidth / 12)
        )
    }

    private fun findBlueMarker(
        frame: CapturedFrame,
        left: Int,
        right: Int,
        centerY: Int
    ): Int? {
        val x0 = left.coerceIn(0, frame.width - 1)
        val x1 = right.coerceIn(x0 + 1, frame.width - 1)
        val yRadius = (frame.height * 0.18f).roundToInt()
        val y0 = (centerY - yRadius).coerceAtLeast(0)
        val y1 = (centerY + yRadius).coerceAtMost(frame.height - 1)
        val votes = IntArray(x1 - x0 + 1)
        var total = 0
        for (y in y0..y1 step 2) {
            for (x in x0..x1 step 2) {
                val p = frame.pixel(x, y)
                val r = red(p); val g = green(p); val b = blue(p)
                if (b > 105 && b > r + 35 && b > g + 8 && g > r + 5) {
                    votes[x - x0]++
                    total++
                }
            }
        }
        if (total < 12) return null
        return bestVoteRun(votes, x0, null, 8, max(60, votes.size / 4))
    }

    /**
     * Bellona's Waraxe: анализирует левую и правую половины игрового трека
     * независимо. В каждой половине ищет яркую полосу и тёмный маркер рыбы.
     *
     * Android-рантайм может затем управлять двумя контактами одновременно
     * через pressMultiTouch().
     */
    fun detectBellona(frame: CapturedFrame): BellonaObservation? {
        if (frame.width < 320 || frame.height < 480) return null
        val top = (frame.height * 0.81f).roundToInt().coerceIn(0, frame.height - 2)
        val bottom = (frame.height * 0.90f).roundToInt().coerceIn(top + 1, frame.height - 1)
        val stepX = 4
        val stepY = 4
        val split = frame.width / 2

        fun analyse(left: Int, right: Int): BellonaSide? {
            val columns = ((right - left) / stepX + 1).coerceAtLeast(1)
            val lum = IntArray(columns)
            for (ci in 0 until columns) {
                val x = (left + ci * stepX).coerceAtMost(right)
                var sum = 0L
                var count = 0
                var y = top
                while (y <= bottom) {
                    val pixel = frame.pixel(x, y)
                    sum += (red(pixel) + green(pixel) + blue(pixel)) / 3
                    count++
                    y += stepY
                }
                lum[ci] = if (count == 0) 0 else (sum / count).toInt()
            }
            if (lum.size < 12) return null

            val sorted = lum.sorted()
            val background = sorted[(sorted.size * 0.20f).toInt().coerceIn(0, sorted.lastIndex)]
            val peak = sorted[(sorted.size * 0.98f).toInt().coerceIn(0, sorted.lastIndex)]
            val threshold = max(background + 25, background + ((peak - background) * 0.40f).roundToInt())
            val maxGap = max(2, (lum.size * 0.035f).roundToInt())
            val minWidth = max(5, (lum.size * 0.15f).roundToInt())
            val maxWidth = max(minWidth, (lum.size * 0.85f).roundToInt())

            var bestStart = -1
            var bestEnd = -1
            var bestLength = 0
            var start = -1
            var last = -1
            var gap = 0
            fun finishRun() {
                if (start >= 0) {
                    val length = last - start + 1
                    if (length in minWidth..maxWidth && length > bestLength) {
                        bestStart = start
                        bestEnd = last
                        bestLength = length
                    }
                }
                start = -1
                last = -1
                gap = 0
            }
            for (i in lum.indices) {
                if (lum[i] >= threshold) {
                    if (start < 0) start = i
                    last = i
                    gap = 0
                } else if (start >= 0) {
                    gap++
                    if (gap > maxGap) finishRun()
                }
            }
            finishRun()
            if (bestStart < 0) return null

            val barAverage = lum.slice(bestStart..bestEnd).average().toFloat()
            val contrast = barAverage - background
            if (contrast < 28f) return null

            val markerLow = background + max(20f, (peak - background) * 0.15f)
            val markerHigh = barAverage * 0.62f
            val fishCandidates = ArrayList<Pair<Int, Float>>()
            val markerStart = (bestStart - 3).coerceAtLeast(0)
            val markerEnd = (bestEnd + 3).coerceAtMost(lum.lastIndex)
            var fishRun = -1
            fun finishFish(end: Int) {
                if (fishRun >= 0 && end >= fishRun) {
                    val width = end - fishRun + 1
                    if (width <= max(2, (lum.size * 0.10f).roundToInt())) {
                        val avg = lum.slice(fishRun..end).average().toFloat()
                        fishCandidates.add((fishRun + end) / 2 to avg)
                    }
                }
                fishRun = -1
            }
            for (i in markerStart..(markerEnd + 1)) {
                val isFish = i <= markerEnd && lum[i] >= markerLow && lum[i] < markerHigh
                if (isFish) {
                    if (fishRun < 0) fishRun = i
                } else if (fishRun >= 0) finishFish(i - 1)
            }
            val fish = fishCandidates.minByOrNull { it.second }?.first?.let { left + it * stepX + stepX / 2 }
            val barLeft = left + bestStart * stepX
            val barRight = (left + bestEnd * stepX).coerceAtMost(right)
            return BellonaSide(left, right, barLeft, barRight, fish)
        }

        val leftSide = analyse(0, split - 1)
        val rightSide = analyse(split, frame.width - 1)
        if (leftSide == null && rightSide == null) return null
        val found = listOfNotNull(leftSide, rightSide)
        val confidence = (found.sumOf { it.barWidth.toDouble() } / frame.width.toDouble()).toFloat()
            .coerceIn(0f, 1f)
        return BellonaObservation(leftSide, rightSide, confidence)
    }

    /**
     * Splitbranch prompt: ищет две половины fish picker в центральном окне.
     * Возвращает четыре безопасные точки-кандидата, MacroRunner проверяет
     * исчезновение prompt после каждого касания.
     */
    fun detectTwigPrompt(frame: CapturedFrame): TwigPrompt? {
        val cropW = (frame.width * 0.20f).roundToInt()
        val cropH = (frame.height * 0.41f).roundToInt()
        if (cropW < 60 || cropH < 60) return null
        val x0 = (frame.width * 0.40f).roundToInt()
        val y0 = (frame.height * 0.17f).roundToInt()
        val x1 = (x0 + cropW).coerceAtMost(frame.width)
        val y1 = (y0 + cropH).coerceAtMost(frame.height)
        val topEnd = (y0 + cropH * 0.17f).roundToInt().coerceAtMost(y1)
        var white = 0
        for (y in y0 until topEnd step 2) for (x in x0 until x1 step 2) {
            val p = frame.pixel(x, y)
            val r = red(p); val g = green(p); val b = blue(p)
            if (min(r, min(g, b)) > 225 && max(r, max(g, b)) - min(r, min(g, b)) < 30) white++
        }
        if (white * 4 < (topEnd - y0) * cropW * 0.04f) return null

        val scanY = (y0 + cropH * 0.878f).roundToInt().coerceIn(y0, y1 - 1)
        val half = cropW / 2
        var leftN = 0; var rightN = 0
        var leftSum = 0L; var rightSum = 0L; var ySum = 0L
        for (y in scanY until y1 step 2) {
            for (x in (x0 + 3) until (x1 - 3) step 2) {
                val p = frame.pixel(x, y)
                val mx = max(red(p), max(green(p), blue(p)))
                if (mx <= 185) continue
                var hasDarkNeighbor = false
                for (d in 1..3) {
                    val lp = frame.pixel((x - d).coerceAtLeast(0), y)
                    val rp = frame.pixel((x + d).coerceAtMost(frame.width - 1), y)
                    if ((red(lp) < 70 && green(lp) < 70 && blue(lp) < 70) ||
                        (red(rp) < 70 && green(rp) < 70 && blue(rp) < 70)) {
                        hasDarkNeighbor = true
                    }
                }
                if (!hasDarkNeighbor) continue
                if (x < x0 + half) { leftN++; leftSum += x } else { rightN++; rightSum += x }
                ySum += y
            }
        }
        val required = max(3, ((y1 - scanY) * half * 0.035f / 4f).roundToInt())
        if (leftN < required || rightN < required) return null
        val confidence = min(1f, min(leftN, rightN).toFloat() / (required * 2f))
        return TwigPrompt(
            leftFishX = (leftSum / leftN).toInt(),
            rightFishX = (rightSum / rightN).toInt(),
            iconY = (y0 + cropH * 0.493f).roundToInt(),
            labelY = (ySum / (leftN + rightN)).toInt(),
            confidence = confidence
        )
    }

    /** Проверяет нижнюю дугу светового кольца Lullaby по ярким пикселям. */
    fun detectLullabyRing(
        frame: CapturedFrame,
        barCenterY: Int,
        radiusPercent: Int = 16
    ): Boolean {
        if (frame.width < 240 || frame.height < 400) return false
        val cx = frame.width / 2
        val cy = (barCenterY - 26).coerceIn(0, frame.height - 1)
        val radius = (frame.width * radiusPercent.coerceIn(7, 24) / 100f).roundToInt()
        var valid = 0
        var bright = 0
        var angle = 180
        while (angle <= 360) {
            val radians = Math.toRadians(angle.toDouble())
            val x = (cx + radius * kotlin.math.cos(radians)).roundToInt()
            val y = (cy + radius * kotlin.math.sin(radians)).roundToInt()
            if (x in 0 until frame.width && y in 0 until frame.height) {
                val pixel = frame.pixel(x, y)
                val luminance = (red(pixel) + green(pixel) + blue(pixel)) / 3
                valid++
                if (luminance >= 120) bright++
            }
            angle += 2
        }
        return valid >= 30 && bright >= max(3, (valid * 0.025f).roundToInt())
    }

    /** Проверка цветных колец Mania: не менее трёх из четырёх дорожек. */
    fun detectManiaRings(frame: CapturedFrame): Boolean {
        if (frame.width < 320 || frame.height < 500) return false
        val laneFractions = floatArrayOf(0.123f, 0.374f, 0.622f, 0.875f)
        val hitY = (frame.height * 0.923f).roundToInt()
        var lanes = 0
        for (fraction in laneFractions) {
            val cx = (frame.width * fraction).roundToInt()
            var colorful = 0
            for (y in (hitY - frame.height * 0.035f).roundToInt()..(hitY + frame.height * 0.025f).roundToInt()) {
                for (x in (cx - frame.width * 0.018f).roundToInt()..(cx + frame.width * 0.018f).roundToInt() step 2) {
                    val p = frame.pixel(x, y)
                    val mx = max(red(p), max(green(p), blue(p)))
                    val mn = min(red(p), min(green(p), blue(p)))
                    if (mx > 95 && mx - mn > 35) colorful++
                }
            }
            if (colorful >= 8) lanes++
        }
        return lanes >= 3
    }

    /**
     * Lullaby radial sectors. Первые кадры строят фон кольца; далее сравниваем
     * яркость каждой точки с фоном и выдаём одно событие на активированный сектор.
     */
    class LullabyTracker(
        private val radiusPercent: Int = 16,
        private val pivotUpPx: Int = 26,
        private val lightThreshold: Int = 90,
        private val armDelta: Int = 45,
        private val triggerPercent: Int = 10,
        private val clearMs: Long = 100,
        private val minSectorDegrees: Int = 4
    ) {
        private val calibration = Array(14) { Array(91) { ArrayList<Int>(26) } }
        private var radiusIndex = -1
        private var baseline = IntArray(91) { -1 }
        private var sectors: List<IntRange> = emptyList()
        private val armed = HashMap<Int, Boolean>()
        private val clearAt = HashMap<Int, Long>()
        private var calibrationFrames = 0

        fun update(frame: CapturedFrame, barCenterY: Int): LullabyTap? {
            if (frame.width < 100 || frame.height < 200) return null
            val centerX = frame.width / 2
            val centerY = (barCenterY - pivotUpPx).coerceIn(0, frame.height - 1)
            val currentRadii = (0 until 14).map {
                (frame.width * (7f + it * 1.2f) / 100f).roundToInt()
            }

            if (radiusIndex < 0) {
                for (ri in currentRadii.indices) {
                    val samples = sampleRing(frame, centerX, centerY, currentRadii[ri])
                    for (angleIndex in samples.indices) {
                        val value = samples[angleIndex]
                        if (value >= 140 || value < 40) calibration[ri][angleIndex].add(value)
                    }
                }
                calibrationFrames++
                if (calibrationFrames >= 26) chooseCalibration()
                return null
            }

            val radius = currentRadii[radiusIndex]
            val current = sampleRing(frame, centerX, centerY, radius)
            var triggeredSector = -1
            val now = android.os.SystemClock.elapsedRealtime()
            for ((index, sector) in sectors.withIndex()) {
                var total = 0
                var changed = 0
                var angle = sector.first
                while (angle <= sector.last) {
                    val sampleIndex = (angle - 180) / 2
                    if (sampleIndex in baseline.indices && baseline[sampleIndex] >= 0 && current[sampleIndex] >= 0) {
                        total++
                        if (abs(current[sampleIndex] - baseline[sampleIndex]) >= armDelta) changed++
                    }
                    angle += 2
                }
                val hit = total > 0 && changed * 100 >= total * triggerPercent
                val key = sector.first
                if (hit) {
                    clearAt[key] = 0L
                    if (armed[key] != false && triggeredSector < 0) {
                        armed[key] = false
                        triggeredSector = index
                    }
                } else {
                    val started = clearAt[key] ?: 0L
                    if (started == 0L) clearAt[key] = now
                    else if (now - started >= clearMs) armed[key] = true
                }
            }
            if (triggeredSector < 0) return null
            val selected = sectors[triggeredSector]
            val angle = (selected.first + selected.last) / 2f
            val radians = Math.toRadians(angle.toDouble())
            val tapX = (centerX + radius * kotlin.math.cos(radians)).roundToInt()
            val tapY = (centerY + radius * kotlin.math.sin(radians)).roundToInt()
            if (tapX !in 1 until frame.width - 1 || tapY !in 1 until frame.height - 1) return null
            return LullabyTap(triggeredSector, tapX, tapY)
        }

        fun calibrated(): Boolean = radiusIndex >= 0

        private fun chooseCalibration() {
            var bestScore = -1
            var bestIndex = -1
            for (ri in calibration.indices) {
                var light = 0; var dark = 0; var total = 0
                for (ai in calibration[ri].indices) {
                    val values = calibration[ri][ai]
                    if (values.isEmpty()) continue
                    total++
                    val bright = values.count { it >= 140 }
                    val dim = values.count { it < 40 }
                    if (bright > dim) light++ else if (dim > bright) dark++
                }
                val score = min(light, dark) * 100 + total
                if (total >= 30 && dark >= 10 && light >= 2 && score > bestScore) {
                    bestScore = score
                    bestIndex = ri
                }
            }
            if (bestIndex < 0) {
                radiusIndex = (radiusPercent - 7).div(1.2f).roundToInt().coerceIn(0, 13)
                baseline = IntArray(91) { -1 }
            } else {
                radiusIndex = bestIndex
                baseline = IntArray(91) { i ->
                    val values = calibration[bestIndex][i].filter { it >= 140 }
                    if (values.isEmpty()) 0 else values.average().roundToInt()
                }
            }
            sectors = buildSectors()
            sectors.forEach { armed[it.first] = true }
        }

        private fun buildSectors(): List<IntRange> {
            val result = ArrayList<IntRange>()
            var start = -1
            for (degree in 180..362 step 2) {
                val index = (degree - 180) / 2
                val lit = degree <= 360 && index in baseline.indices && baseline[index] >= lightThreshold
                if (lit && start < 0) start = degree
                if (!lit && start >= 0) {
                    val end = degree - 2
                    if (end - start >= minSectorDegrees - 2) result.add(start..end)
                    start = -1
                }
            }
            return result
        }

        private fun sampleRing(frame: CapturedFrame, cx: Int, cy: Int, radius: Int): IntArray {
            return IntArray(91) { index ->
                val angle = 180 + index * 2
                val radians = Math.toRadians(angle.toDouble())
                val x = (cx + radius * kotlin.math.cos(radians)).roundToInt()
                val y = (cy + radius * kotlin.math.sin(radians)).roundToInt()
                if (x !in 0 until frame.width || y !in 0 until frame.height) -1
                else {
                    val p = frame.pixel(x, y)
                    (red(p) + green(p) + blue(p)) / 3
                }
            }
        }
    }

    /**
     * Tracker для Tranquility Mania. Четыре дорожки берутся из пропорций
     * исходного UI. На каждом кадре обновляем вертикальную скорость ноты,
     * чтобы нажать дорожку перед линией попадания.
     */
    class TranquilityTracker(private val maniaLeadMs: Int = 45) {
        private data class NoteTrack(
            var bottomY: Int,
            var lastAt: Long,
            var velocityPxPerSecond: Float,
            var fired: Boolean
        )
        private val tracks = arrayOfNulls<NoteTrack>(4)
        private val lastTapAt = LongArray(4)
        private val laneFractions = floatArrayOf(0.123f, 0.374f, 0.622f, 0.875f)

        fun update(frame: CapturedFrame, nowMs: Long): List<ManiaTap> {
            val hitY = (frame.height * 0.9233f).roundToInt()
            val taps = ArrayList<ManiaTap>()
            for (lane in 0..3) {
                val centerX = (frame.width * laneFractions[lane]).roundToInt()
                val halfLane = max(3, (frame.width * 0.025f).roundToInt())
                val segments = findColorfulSegments(
                    frame, centerX - halfLane, centerX + halfLane,
                    (frame.height * 0.12f).roundToInt(),
                    (frame.height * 0.91f).roundToInt()
                )
                if (segments.isEmpty()) {
                    val old = tracks[lane]
                    if (old != null && nowMs - old.lastAt > 450L) tracks[lane] = null
                    continue
                }

                val old = tracks[lane]
                val segment = if (old == null) {
                    segments.maxByOrNull { it.second }
                } else {
                    segments.minByOrNull { abs(it.second - old.bottomY) }
                } ?: continue

                val bottom = segment.second
                val velocity = if (old != null && nowMs > old.lastAt && bottom >= old.bottomY - 3) {
                    val dt = (nowMs - old.lastAt).coerceIn(16L, 250L) / 1000f
                    val measured = (bottom - old.bottomY) / dt
                    (0.25f * measured + 0.75f * old.velocityPxPerSecond)
                        .coerceIn(frame.height * 0.10f, frame.height * 2.0f)
                } else {
                    frame.height * 0.60f
                }
                val timeToHit = ((hitY - bottom).coerceAtLeast(0) / velocity * 1000f).toLong()
                val track = old ?: NoteTrack(bottom, nowMs, velocity, false)
                track.bottomY = bottom
                track.lastAt = nowMs
                track.velocityPxPerSecond = velocity
                if (bottom < hitY - 5) track.fired = false
                tracks[lane] = track

                val lead = maniaLeadMs.coerceIn(0, 180).toLong()
                if (!track.fired && timeToHit <= lead && nowMs - lastTapAt[lane] >= 85L) {
                    track.fired = true
                    lastTapAt[lane] = nowMs
                    taps.add(ManiaTap(lane, centerX, hitY, timeToHit))
                }
            }
            return taps
        }

        fun hasLiveNotes(nowMs: Long): Boolean =
            tracks.any { it != null && nowMs - it.lastAt < 450L }

        private fun findColorfulSegments(
            frame: CapturedFrame,
            x0: Int,
            x1: Int,
            y0: Int,
            y1: Int
        ): List<Pair<Int, Int>> {
            val left = x0.coerceIn(0, frame.width - 1)
            val right = x1.coerceIn(left + 1, frame.width - 1)
            val hits = IntArray(frame.height)
            for (y in y0.coerceIn(0, frame.height - 1)..y1.coerceIn(0, frame.height - 1) step 2) {
                var count = 0
                for (x in left..right step 2) {
                    val p = frame.pixel(x, y)
                    val mx = max(red(p), max(green(p), blue(p)))
                    val mn = min(red(p), min(green(p), blue(p)))
                    if (mx > 90 && mx - mn > 35) count++
                }
                hits[y] = count
            }
            val result = ArrayList<Pair<Int, Int>>()
            var start = -1; var last = -1
            for (y in y0.coerceIn(0, frame.height - 1)..y1.coerceIn(0, frame.height - 1) step 2) {
                if (hits[y] >= 2) {
                    if (start < 0) start = y
                    last = y
                } else if (start >= 0) {
                    if (last - start >= 4) result.add(start to last)
                    start = -1; last = -1
                }
            }
            if (start >= 0 && last - start >= 4) result.add(start to last)
            return result
        }
    }

    private fun nearRgb(pixel: Int, target: Int, tolerance: Int): Boolean {
        val tol = tolerance.coerceIn(1, 100)
        return abs(red(pixel) - red(target)) <= tol &&
            abs(green(pixel) - green(target)) <= tol &&
            abs(blue(pixel) - blue(target)) <= tol
    }

    private fun red(pixel: Int) = (pixel shr 16) and 0xFF
    private fun green(pixel: Int) = (pixel shr 8) and 0xFF
    private fun blue(pixel: Int) = pixel and 0xFF
}
