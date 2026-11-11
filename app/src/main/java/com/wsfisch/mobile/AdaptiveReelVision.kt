package com.wsfisch.mobile

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Дополнительный слой для динамического reeling.
 *
 * Основной распознаватель FischVision остаётся источником стандартной
 * геометрии. Этот класс:
 *  - сохраняет повторное измерение Control Bar на каждом кадре;
 *  - подхватывает очень узкий Bar, который старый детектор отсекает;
 *  - использует специальные цвета Castbound/Stellarwave для маркера рыбы;
 *  - не принимает старую ширину полосы за эталон.
 */
object AdaptiveReelVision {

    private data class SearchProfile(
        val leftPct: Float,
        val rightPct: Float,
        val topPct: Float,
        val bottomPct: Float,
        val priority: Float
    )

    private data class WhiteRun(
        val left: Int,
        val right: Int,
        val y: Int,
        val hits: Int
    )

    private data class BarCandidate(
        val left: Int,
        val right: Int,
        val centerY: Int,
        val confidence: Float
    )

    fun detect(
        frame: CapturedFrame,
        specialRod: String,
        previousFishX: Int?,
        fishColorRgb: Int?,
        fishColorTolerance: Int,
        barWhiteTolerance: Int = 20,
        trackTolerance: Int = 20,
        previousBarCenter: Float? = null
    ): ReelObservation? {
        val castbound = specialRod.equals(
            "Castbound",
            ignoreCase = true
        )

        // Castbound сразу использует динамический сканер: старый детектор
        // ограничивает минимальную ширину белого участка и теряет сужающийся Bar.
        val base = if (castbound) {
            null
        } else {
            FischVision.detectReel(
                frame = frame,
                specialRod = specialRod,
                previousFishX = previousFishX
            )
        }

        // Для обычных удочек сначала работает проверенный общий детектор;
        // если он теряет узкий Bar, включается адаптивный проход.
        val observation = base ?: detectNarrowBar(
            frame = frame,
            specialRod = specialRod,
            barWhiteTolerance = barWhiteTolerance,
            trackTolerance = trackTolerance,
            markerColorRgb = fishColorRgb,
            markerColorTolerance = fishColorTolerance,
            previousBarCenter = previousBarCenter
        ) ?: return null

        if (fishColorRgb == null) {
            return observation
        }

        val coloredFish = findColoredFish(
            frame = frame,
            trackLeft = observation.trackLeft,
            trackRight = observation.trackRight,
            centerY = observation.centerY,
            targetRgb = fishColorRgb,
            tolerance = fishColorTolerance,
            previousFishX = previousFishX
        )

        // Для особых удочек не используем общий тёмный notch как замену
        // цветному маркеру: это часто приводит к захвату постороннего UI.
        // Если цветной маркер пропал, возвращаем null. Последнюю позицию
        // и 650-мс stun grace ведёт MacroRunner, чтобы не маскировать потерю
        // распознавания под якобы свежий кадр рыбы.
        return observation.copy(fishX = coloredFish)
    }

    private fun detectNarrowBar(
        frame: CapturedFrame,
        specialRod: String,
        barWhiteTolerance: Int,
        trackTolerance: Int,
        markerColorRgb: Int?,
        markerColorTolerance: Int,
        previousBarCenter: Float?
    ): ReelObservation? {
        if (frame.width < 320 || frame.height < 480) return null

        val rod = specialRod.trim().lowercase()

        val primary = when (rod) {
            "castbound" ->
                SearchProfile(0.20f, 0.80f, 0.72f, 0.985f, 0.15f)

            "bellona's waraxe" ->
                SearchProfile(0.05f, 0.95f, 0.79f, 0.94f, 0.12f)

            "stellarwave melody" ->
                SearchProfile(0.22f, 0.78f, 0.72f, 0.985f, 0.12f)

            "remembrance" ->
                SearchProfile(0.30f, 0.70f, 0.76f, 0.94f, 0.12f)

            else ->
                SearchProfile(0.30f, 0.70f, 0.76f, 0.95f, 0.12f)
        }

        val profiles = if (rod == "castbound") {
            // У Castbound UI остаётся по центру экрана; два прохода
            // покрывают обычную и смещённую по вертикали область.
            // Не сканируем почти весь кадр 4 раза на каждом обновлении.
            listOf(
                primary,
                SearchProfile(0.20f, 0.80f, 0.56f, 0.985f, 0.07f)
            )
        } else {
            listOf(
                primary,
                SearchProfile(
                    primary.leftPct,
                    primary.rightPct,
                    0.56f,
                    0.985f,
                    0.07f
                ),
                SearchProfile(0.20f, 0.80f, 0.56f, 0.985f, 0.02f),
                SearchProfile(0.05f, 0.95f, 0.56f, 0.985f, -0.04f)
            )
        }

        var best: ReelObservation? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for ((profileIndex, profile) in profiles.withIndex()) {
            val left = (frame.width * profile.leftPct)
                .roundToInt().coerceIn(0, frame.width - 2)
            val right = (frame.width * profile.rightPct)
                .roundToInt().coerceIn(left + 1, frame.width - 1)
            val top = (frame.height * profile.topPct)
                .roundToInt().coerceIn(0, frame.height - 2)
            val bottom = (frame.height * profile.bottomPct)
                .roundToInt().coerceIn(top + 1, frame.height - 1)

            val candidate = scanThinWhiteBar(
                frame,
                left,
                right,
                top,
                bottom,
                barWhiteTolerance,
                trackTolerance,
                markerColorRgb,
                markerColorTolerance,
                previousBarCenter,
                xStep = if (rod == "castbound") 1 else 2
            ) ?: continue

            val measuredWidth =
                (candidate.right - candidate.left + 1).coerceAtLeast(1)

            val measuredCenter =
                (candidate.left + candidate.right) / 2f

            // Если эффект/маркер перекрыл почти весь очень тонкий Bar,
            // один оставшийся крайний пиксель не должен мгновенно сдвигать
            // расчётный центр. Ширина остаётся измеренной с текущего кадра,
            // центр удерживается возле последнего правдоподобного положения.
            val canAnchorNarrowCenter =
                measuredWidth <= 2 &&
                    previousBarCenter != null &&
                    abs(measuredCenter - previousBarCenter) <=
                        max(8f, (right - left + 1) * 0.02f)

            val outputLeft = if (canAnchorNarrowCenter) {
                (
                    previousBarCenter!! -
                        (measuredWidth - 1) / 2f
                    ).roundToInt()
            } else {
                candidate.left
            }

            val outputRight = outputLeft + measuredWidth - 1

            val result = ReelObservation(
                trackLeft = left,
                trackRight = right,
                barLeft = outputLeft,
                barRight = outputRight,
                fishX = null,
                centerY = candidate.centerY,
                confidence = candidate.confidence
            )

            val score = candidate.confidence + profile.priority
            if (score > bestScore) {
                bestScore = score
                best = result
            }

            // Если основной профиль дал уверенную геометрию,
            // второй расширенный проход не нужен. На Castbound это
            // сохраняет запас времени до следующего 60-мс кадра.
            if (
                rod == "castbound" &&
                profileIndex == 0 &&
                candidate.confidence >= 0.82f
            ) {
                return result
            }
        }

        return best
    }

    /**
     * Находит горизонтальный светлый Control Bar, включая узкие размеры.
     * Ширина берётся только из текущего кадра — никаких стартовых размеров.
     */
    private fun scanThinWhiteBar(
        frame: CapturedFrame,
        trackLeft: Int,
        trackRight: Int,
        yTop: Int,
        yBottom: Int,
        barWhiteTolerance: Int,
        trackTolerance: Int,
        markerColorRgb: Int?,
        markerColorTolerance: Int,
        previousBarCenter: Float?,
        xStep: Int
    ): BarCandidate? {
        val trackWidth = trackRight - trackLeft + 1
        if (trackWidth < 80 || yBottom - yTop < 8) return null

        val yStep = max(2, frame.height / 1000)
        // Control Bar может стать почти нитевидным у Castbound.
        // Допускаем 1 px при условии устойчивости по нескольким строкам.
        val minSpan = max(1, (trackWidth * 0.002f).roundToInt())
        val maxSpan = (trackWidth * 0.86f).roundToInt()
        val runs = ArrayList<WhiteRun>(256)

        var y = yTop
        while (y <= yBottom) {
            var runStart = -1
            var lastWhite = -1
            var hits = 0
            var gap = 0
            var x = trackLeft

            fun finishRun() {
                if (runStart >= 0 && hits >= 1 && lastWhite >= runStart) {
                    val span = lastWhite - runStart + 1
                    if (span in minSpan..maxSpan) {
                        runs.add(
                            WhiteRun(
                                left = runStart,
                                right = lastWhite,
                                y = y,
                                hits = hits
                            )
                        )
                    }
                }
                runStart = -1
                lastWhite = -1
                hits = 0
                gap = 0
            }

            while (x <= trackRight) {
                val pixel = frame.pixel(x, y)
                val isMarker = markerColorRgb != null && nearRgb(
                    pixel,
                    markerColorRgb,
                    markerColorTolerance
                )

                if (
                    isBarWhite(pixel, barWhiteTolerance) ||
                    isMarker
                ) {
                    if (runStart < 0) runStart = x
                    lastWhite = x
                    hits++
                    gap = 0
                } else if (runStart >= 0) {
                    gap++
                    if (gap > 3) finishRun()
                }
                x += xStep
            }
            finishRun()
            y += yStep
        }

        if (runs.size < 4) return null

        // TrackTol задаёт допустимую разницу краёв между строками.
        // 20 соответствует примерно 2% ширины области поиска.
        val edgeTolerance = max(
            4,
            (trackWidth * trackTolerance.coerceIn(5, 80) / 1000f).roundToInt()
        )
        val maxClusterHeight = max(28, (frame.height * 0.045f).roundToInt())

        var best: BarCandidate? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (anchor in runs) {
            val cluster = runs.filter {
                abs(it.left - anchor.left) <= edgeTolerance &&
                    abs(it.right - anchor.right) <= edgeTolerance &&
                    abs(it.y - anchor.y) <= maxClusterHeight
            }

            val ys = cluster.map { it.y }.distinct()
            // Одиночная вертикальная линия не считается Bar:
            // требуется подтверждение минимум на четырёх строках.
            if (ys.size < 4) continue

            val minY = ys.minOrNull() ?: continue
            val maxY = ys.maxOrNull() ?: continue
            val height = maxY - minY + yStep
            if (height !in (yStep * 2)..maxClusterHeight) continue

            val left = median(cluster.map { it.left }).roundToInt()
            val right = median(cluster.map { it.right }).roundToInt()
            if (right < left) continue

            val barWidth = right - left + 1
            val ratio = barWidth.toFloat() / trackWidth
            if (ratio !in 0.0015f..0.86f) continue

            val centerY = median(ys).roundToInt()
            val rowScore = (ys.size / 7f).coerceIn(0f, 1f)
            val heightScore = (
                height / max(8f, frame.height * 0.018f)
            ).coerceIn(0f, 1f)
            val widthScore = (
                1f - abs(ratio - 0.20f) / 0.75f
            ).coerceIn(0f, 1f)
            val lowerScore = (
                (centerY.toFloat() / frame.height - 0.50f) / 0.48f
            ).coerceIn(0f, 1f)

            val centerX = (left + right) / 2f
            val continuityScore = if (previousBarCenter != null) {
                (
                    1f - abs(centerX - previousBarCenter) /
                        (trackWidth * 0.20f)
                    ).coerceIn(0f, 1f) * 0.16f
            } else {
                0f
            }

            val score =
                rowScore * 0.45f +
                    heightScore * 0.20f +
                    widthScore * 0.17f +
                    lowerScore * 0.12f +
                    continuityScore * 0.06f

            if (score > bestScore) {
                bestScore = score
                best = BarCandidate(
                    left = left,
                    right = right,
                    centerY = centerY,
                    confidence = score.coerceIn(0f, 1f)
                )
            }
        }

        return best?.takeIf { it.confidence >= 0.34f }
    }

    private fun findColoredFish(
        frame: CapturedFrame,
        trackLeft: Int,
        trackRight: Int,
        centerY: Int,
        targetRgb: Int,
        tolerance: Int,
        previousFishX: Int?
    ): Int? {
        val left = trackLeft.coerceIn(0, frame.width - 1)
        val right = trackRight.coerceIn(left + 1, frame.width - 1)
        val yRadius = max(8, (frame.height * 0.020f).roundToInt())
        val y0 = (centerY - yRadius).coerceAtLeast(0)
        val y1 = (centerY + yRadius).coerceAtMost(frame.height - 1)
        val votes = IntArray(right - left + 1)
        val step = 2
        var y = y0

        while (y <= y1) {
            var x = left
            // По X идём с шагом 1: маркер Castbound/Stellarwave
            // может быть всего 2–4 пикселя шириной.
            while (x <= right) {
                if (nearRgb(frame.pixel(x, y), targetRgb, tolerance)) {
                    votes[x - left]++
                }
                x++
            }
            y += step
        }

        val maxRunWidth = max(32, (votes.size * 0.10f).roundToInt())
        val minVotes = 3
        var bestX: Int? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var start = -1
        var last = -1

        fun scoreRun() {
            if (start < 0 || last < start) return
            val runWidth = last - start + 1
            if (runWidth !in 2..maxRunWidth) return

            var total = 0
            for (i in start..last) total += votes[i]
            if (total < minVotes) return

            val center = left + (start + last) / 2
            val distancePenalty = if (previousFishX != null) {
                abs(center - previousFishX) * 0.08f
            } else {
                0f
            }
            val score = total - distancePenalty

            if (score > bestScore) {
                bestScore = score
                bestX = center
            }
        }

        for (i in votes.indices) {
            if (votes[i] > 0) {
                if (start < 0) start = i
                last = i
            } else if (start >= 0) {
                // Объединяем только небольшие разрывы внутри цветного спрайта.
                if (i - last > 3) {
                    scoreRun()
                    start = -1
                    last = -1
                }
            }
        }
        scoreRun()

        return bestX
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

        val tol = tolerance.coerceIn(1, 100)

        return abs(r - tr) <= tol &&
            abs(g - tg) <= tol &&
            abs(b - tb) <= tol
    }

    private fun isBarWhite(
        argb: Int,
        tolerance: Int
    ): Boolean {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff

        // В настройках Rod Settings значение означает допуск к чистому белому.
        // 20 -> минимальный канал 235; больший допуск мягче.
        val minimumChannel = (255 - tolerance.coerceIn(5, 80))
            .coerceIn(175, 250)

        return r >= minimumChannel &&
            g >= minimumChannel &&
            b >= minimumChannel &&
            max(r, max(g, b)) - min(r, min(g, b)) <= 72
    }

    private fun median(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle].toDouble()
        }
    }
}
