package com.wsfisch.mobile

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

object MacroRunner {

    private const val PREFS = "mobile_macro_rod_settings"
    private const val FRAME_MAX_AGE_MS = 1_200L
    private const val LOOP_SLEEP_MS = 25L
    private const val CONTROL_PULSE_MS = 55L

    private const val REEL_MISSING_FRAMES_TO_FINISH = 10

    data class Config(
        val castXPercent: Int,
        val castYPercent: Int,
        val castHoldMs: Long,
        val specialRod: String,
        val waitForBobberMs: Long,
        val shakeTimeoutMs: Long,
        val reelTimeoutMs: Long,
        val recastDelayMs: Long
    )

    fun readConfig(context: Context): Config {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        return Config(
            castXPercent = prefs.getString("CastXPercent", "50")
                ?.toIntOrNull()?.coerceIn(0, 100) ?: 50,

            castYPercent = prefs.getString("CastYPercent", "50")
                ?.toIntOrNull()?.coerceIn(0, 100) ?: 50,

            castHoldMs = prefs.getString("CastHoldMs", "1000")
                ?.toLongOrNull()?.coerceIn(250L, 5000L) ?: 1000L,

            specialRod = prefs.getString("SpecialRod", "None") ?: "None",

            waitForBobberMs = prefs.getString("WaitForBobberMs", "1000")
                ?.toLongOrNull()?.coerceIn(250L, 5000L) ?: 1000L,

            shakeTimeoutMs = prefs.getString("ShakeTimeoutMs", "35000")
                ?.toLongOrNull()?.coerceIn(10_000L, 90_000L) ?: 35_000L,

            reelTimeoutMs = prefs.getString("ReelTimeoutMs", "120000")
                ?.toLongOrNull()?.coerceIn(30_000L, 180_000L) ?: 120_000L,

            recastDelayMs = prefs.getString("RecastDelayMs", "1000")
                ?.toLongOrNull()?.coerceIn(300L, 5000L) ?: 1000L
        )
    }

    fun run(
        context: Context,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ) {
        try {
            onStatus("ПРОВЕРКА: управление касаниями")

            val accessibility =
                MacroAccessibilityService.instance
                    ?: throw IllegalStateException(
                        "Включи службу управления касаниями Android"
                    )

            onStatus("ПРОВЕРКА: захват экрана")

            val capture =
                ScreenCaptureService.instance
                    ?: throw IllegalStateException(
                        "Сначала нажми «Начать захват» и разреши запись экрана"
                    )

            val readyDeadline =
                SystemClock.elapsedRealtime() + 12_000L

            while (
                !stopFlag.get() &&
                SystemClock.elapsedRealtime() < readyDeadline
            ) {
                if (capture.isCaptureReady(FRAME_MAX_AGE_MS)) break
                Thread.sleep(100L)
            }

            if (stopFlag.get()) return

            if (!capture.isCaptureReady(FRAME_MAX_AGE_MS)) {
                throw IllegalStateException(
                    "Захват не отдаёт свежие кадры. Проверь разрешение записи экрана"
                )
            }

            val config = readConfig(context)

            val firstFrame = freshFrame(
                capture,
                stopFlag
            )

            onStatus(
                "ГОТОВО: ${firstFrame.width}×${firstFrame.height} · ${config.specialRod}"
            )

            var cycle = 0
            var totalShakeClicks = 0

            while (!stopFlag.get()) {
                cycle++

                val beforeCast = freshFrame(
                    capture,
                    stopFlag
                )

                if (stopFlag.get()) return

                val x = (
                    beforeCast.width *
                        config.castXPercent / 100f
                    ).toInt().coerceIn(2, beforeCast.width - 3)

                val y = (
                    beforeCast.height *
                        config.castYPercent / 100f
                    ).toInt().coerceIn(2, beforeCast.height - 3)

                onStatus(
                    "ЗАБРОС #$cycle · удержание ${config.castHoldMs} мс"
                )

                val castDispatched = accessibility.pressAndHold(
                    x = x.toFloat(),
                    y = y.toFloat(),
                    durationMs = config.castHoldMs,
                    stopFlag = stopFlag
                )

                if (stopFlag.get()) return

                if (!castDispatched) {
                    throw IllegalStateException(
                        "Android не завершил жест заброса. Проверь службу касаний"
                    )
                }

                onStatus("ПОИСК · отслеживаю мини-игру и SHAKE")

                val waitResult = waitForShakeOrReel(
                    capture = capture,
                    accessibility = accessibility,
                    config = config,
                    stopFlag = stopFlag,
                    onStatus = onStatus
                )

                if (stopFlag.get()) return

                totalShakeClicks += waitResult.shakeClicks

                if (waitResult.reel == null) {
                    onStatus(
                        "ПОКЛЁВКА НЕ НАЙДЕНА · SHAKE ${waitResult.shakeClicks} · повтор"
                    )

                    sleepCancellable(
                        config.recastDelayMs,
                        stopFlag
                    )

                    continue
                }

                onStatus(
                    "МИНИ-ИГРА · начинаю управление полосой"
                )

                val reelResult = playReel(
                    capture = capture,
                    accessibility = accessibility,
                    initial = waitResult.reel,
                    initialFrameWidth = waitResult.frameWidth,
                    initialFrameHeight = waitResult.frameHeight,
                    initialRotation = waitResult.frameRotation,
                    specialRod = config.specialRod,
                    timeoutMs = config.reelTimeoutMs,
                    stopFlag = stopFlag,
                    onStatus = onStatus
                )

                if (stopFlag.get()) return

                when (reelResult) {
                    ReelEnd.BAR_DISAPPEARED -> {
                        onStatus(
                            "ПОЛОСА ИСЧЕЗЛА · завершаю цикл #$cycle"
                        )
                    }

                    ReelEnd.TIMEOUT -> {
                        onStatus(
                            "ТАЙМ-АУТ МИНИ-ИГРЫ · перезапуск цикла #$cycle"
                        )
                    }
                }

                sleepCancellable(
                    config.recastDelayMs,
                    stopFlag
                )
            }

        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            onStatus("ОСТАНОВЛЕНО")

        } catch (e: Exception) {
            onStatus(
                "ОШИБКА: ${e.message ?: "неизвестная ошибка"}"
            )
        }
    }

    private data class WaitResult(
        val reel: ReelObservation?,
        val shakeClicks: Int,
        val frameWidth: Int = 0,
        val frameHeight: Int = 0,
        val frameRotation: Int = -1
    )

    private fun waitForShakeOrReel(
        capture: ScreenCaptureService,
        accessibility: MacroAccessibilityService,
        config: Config,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ): WaitResult {
        val started = SystemClock.elapsedRealtime()

        var lastSequence = -1L

        var lastFrameWidth = -1
        var lastFrameHeight = -1
        var lastFrameRotation = -2

        var lastClickedX = -1
        var lastClickedY = -1
        var lastClickAt = 0L

        var lastCandidate: ShakePrompt? = null
        var candidateStableFrames = 0

        var lastReelCandidate: ReelObservation? = null
        var reelStableFrames = 0

        var shakeClicks = 0
        var lastStatusAt = 0L
        var previousFish: Int? = null

        while (
            !stopFlag.get() &&
            SystemClock.elapsedRealtime() - started <
            config.shakeTimeoutMs
        ) {
            val frame = freshFrame(
                capture,
                stopFlag
            )

            if (stopFlag.get()) break

            val currentRotation = capture.displayRotation()

            if (
                frame.width != lastFrameWidth ||
                frame.height != lastFrameHeight ||
                currentRotation != lastFrameRotation
            ) {
                lastFrameWidth = frame.width
                lastFrameHeight = frame.height
                lastFrameRotation = currentRotation

                lastClickedX = -1
                lastClickedY = -1
                lastClickAt = 0L

                lastCandidate = null
                candidateStableFrames = 0

                lastReelCandidate = null
                reelStableFrames = 0

                previousFish = null

                onStatus(
                    "ЭКРАН ОБНОВЛЁН · ${frame.width}×${frame.height}"
                )
            }

            if (frame.sequence == lastSequence) {
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            lastSequence = frame.sequence

            val elapsed =
                SystemClock.elapsedRealtime() - started

            val reel = FischVision.detectReel(
                frame,
                config.specialRod,
                previousFish
            )

            if (reel != null) {
                val previous = lastReelCandidate

                val sameGeometry =
                    previous != null &&
                    abs(previous.centerY - reel.centerY) <=
                        max(18f, frame.height * 0.035f).toInt() &&
                    abs(previous.barWidth - reel.barWidth) <=
                        max(28f, previous.trackWidth * 0.28f).toInt()

                reelStableFrames =
                    if (sameGeometry) reelStableFrames + 1 else 1

                lastReelCandidate = reel

                if (reel.fishX != null) {
                    previousFish = reel.fishX
                }

                val neededFrames =
                    if (reel.confidence >= 0.58f) 2 else 3

                if (reelStableFrames >= neededFrames) {
                    onStatus(
                        "МИНИ-ИГРА ОБНАРУЖЕНА · уверенность ${
                            (reel.confidence * 100).toInt()
                        }% · рыба ${
                            positionPct(
                                reel.fishX,
                                reel.trackLeft,
                                reel.trackRight
                            )
                        }"
                    )

                    return WaitResult(
                        reel = reel,
                        shakeClicks = shakeClicks,
                        frameWidth = frame.width,
                        frameHeight = frame.height,
                        frameRotation = currentRotation
                    )
                }

                if (
                    SystemClock.elapsedRealtime() - lastStatusAt >
                    700L
                ) {
                    onStatus(
                        "КАНДИДАТ МИНИ-ИГРЫ · подтверждаю $reelStableFrames/$neededFrames"
                    )

                    lastStatusAt =
                        SystemClock.elapsedRealtime()
                }

                lastCandidate = null
                candidateStableFrames = 0

            } else {
                lastReelCandidate = null
                reelStableFrames = 0

                if (elapsed >= config.waitForBobberMs) {
                    val prompt = FischVision.detectShake(frame)

                    if (prompt == null) {
                        lastCandidate = null
                        candidateStableFrames = 0

                        if (
                            SystemClock.elapsedRealtime() - lastStatusAt >
                            1_200L
                        ) {
                            onStatus(
                                "ПОИСК · проверяю мини-игру и SHAKE"
                            )

                            lastStatusAt =
                                SystemClock.elapsedRealtime()
                        }

                    } else {
                        val now =
                            SystemClock.elapsedRealtime()

                        val dx = prompt.x - lastClickedX
                        val dy = prompt.y - lastClickedY

                        val sameAsLastClick =
                            lastClickedX >= 0 &&
                            kotlin.math.sqrt(
                                (dx * dx + dy * dy).toFloat()
                            ) < max(
                                36f,
                                prompt.diameter * 0.35f
                            )

                        val canRetrySame =
                            sameAsLastClick &&
                            now - lastClickAt >= 850L

                        val movedToNewPrompt = !sameAsLastClick
                        val previous = lastCandidate

                        candidateStableFrames =
                            if (
                                previous != null &&
                                abs(previous.x - prompt.x) <
                                    prompt.diameter * 0.30f &&
                                abs(previous.y - prompt.y) <
                                    prompt.diameter * 0.30f
                            ) {
                                candidateStableFrames + 1
                            } else {
                                1
                            }

                        lastCandidate = prompt

                        val confident =
                            prompt.confidence >= 0.68f

                        val stable =
                            candidateStableFrames >= 2

                        if (
                            (movedToNewPrompt || canRetrySame) &&
                            (confident || stable)
                        ) {
                            val geometryStillCurrent =
                                capture.isCaptureReady(FRAME_MAX_AGE_MS) &&
                                    capture.frameWidth() == frame.width &&
                                    capture.frameHeight() == frame.height &&
                                    capture.displayRotation() == currentRotation

                            if (!geometryStillCurrent) {
                                lastCandidate = null
                                candidateStableFrames = 0
                                continue
                            }

                            val tapped = accessibility.tap(
                                prompt.x.toFloat(),
                                prompt.y.toFloat(),
                                70L
                            )

                            if (tapped) {
                                shakeClicks++

                                lastClickedX = prompt.x
                                lastClickedY = prompt.y

                                lastClickAt =
                                    SystemClock.elapsedRealtime()

                                onStatus(
                                    "SHAKE #$shakeClicks · ${prompt.x},${prompt.y}"
                                )
                            } else {
                                onStatus(
                                    "SHAKE НАЙДЕН, НО КАСАНИЕ ОТКЛОНЕНО"
                                )
                            }

                            candidateStableFrames = 0

                        } else if (
                            SystemClock.elapsedRealtime() - lastStatusAt >
                            1_100L
                        ) {
                            onStatus(
                                "ИЩУ SHAKE · кандидат ${
                                    (prompt.confidence * 100)
                                        .toInt()
                                        .coerceIn(0, 100)
                                }%"
                            )

                            lastStatusAt =
                                SystemClock.elapsedRealtime()
                        }
                    }

                } else if (
                    SystemClock.elapsedRealtime() - lastStatusAt >
                    1_000L
                ) {
                    onStatus(
                        "ПРОВЕРКА МИНИ-ИГРЫ · жду интерфейс поклёвки"
                    )

                    lastStatusAt =
                        SystemClock.elapsedRealtime()
                }
            }

            Thread.sleep(LOOP_SLEEP_MS)
        }

        return WaitResult(
            reel = null,
            shakeClicks = shakeClicks
        )
    }

    private enum class ReelEnd {
        BAR_DISAPPEARED,
        TIMEOUT
    }

    private fun playReel(
        capture: ScreenCaptureService,
        accessibility: MacroAccessibilityService,
        initial: ReelObservation,
        initialFrameWidth: Int,
        initialFrameHeight: Int,
        initialRotation: Int,
        specialRod: String,
        timeoutMs: Long,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ): ReelEnd {
        val started =
            SystemClock.elapsedRealtime()

        var lastSequence = -1L

        var lastFishX: Int? = initial.fishX
        var lastBarCenter: Float? = initial.barCenter
        var lastSampleAt = started

        var fishVelocity = 0f
        var barVelocity = 0f
        var holdAccumulator = 0f

        var missingBarFrames = 0
        var noFishSince = 0L
        var lastStatusAt = 0L

        var lastFrameWidth = initialFrameWidth
        var lastFrameHeight = initialFrameHeight
        var lastFrameRotation = initialRotation

        // Геометрия исходной полосы.
        // Не даём случайной белой полоске на экране
        // выдаваться за продолжающуюся мини-игру.
        var referenceObservation: ReelObservation? = initial
        var baselineCandidate: ReelObservation? = null

        while (
            !stopFlag.get() &&
            SystemClock.elapsedRealtime() - started < timeoutMs
        ) {
            val frame = freshFrame(
                capture,
                stopFlag
            )

            if (stopFlag.get()) return ReelEnd.TIMEOUT

            val currentRotation = capture.displayRotation()

            if (
                frame.width != lastFrameWidth ||
                frame.height != lastFrameHeight ||
                currentRotation != lastFrameRotation
            ) {
                lastFrameWidth = frame.width
                lastFrameHeight = frame.height
                lastFrameRotation = currentRotation

                lastFishX = null
                lastBarCenter = null

                fishVelocity = 0f
                barVelocity = 0f
                holdAccumulator = 0f

                missingBarFrames = 0
                noFishSince = 0L

                lastSampleAt =
                    SystemClock.elapsedRealtime()

                // После поворота ищем игровую полосу заново.
                referenceObservation = null
                baselineCandidate = null

                onStatus(
                    "ЭКРАН ОБНОВЛЁН · ${frame.width}×${frame.height} · продолжаю мини-игру"
                )
            }

            if (frame.sequence == lastSequence) {
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            lastSequence = frame.sequence

            val detected = FischVision.detectReel(
                frame,
                specialRod,
                lastFishX
            )

            if (detected == null) {
                missingBarFrames++

                if (
                    missingBarFrames >=
                    REEL_MISSING_FRAMES_TO_FINISH
                ) {
                    return ReelEnd.BAR_DISAPPEARED
                }

                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            // Если после поворота нет контрольной геометрии,
            // подтверждаем новую полосу двумя последовательными кадрами.
            val reference = referenceObservation

            if (reference == null) {
                val previousCandidate = baselineCandidate

                if (
                    previousCandidate != null &&
                    isSameReelOverlay(
                        previousCandidate,
                        detected,
                        frame
                    )
                ) {
                    referenceObservation = detected
                    baselineCandidate = null
                    missingBarFrames = 0

                    lastBarCenter = detected.barCenter
                    lastSampleAt =
                        SystemClock.elapsedRealtime()

                    Thread.sleep(LOOP_SLEEP_MS)
                    continue
                }

                baselineCandidate = detected
                missingBarFrames = 0

                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            // Проверяем, что это та же самая игровая полоса,
            // а не другой белый элемент интерфейса.
            if (
                !isSameReelOverlay(
                    reference,
                    detected,
                    frame
                )
            ) {
                missingBarFrames++

                if (
                    missingBarFrames >=
                    REEL_MISSING_FRAMES_TO_FINISH
                ) {
                    return ReelEnd.BAR_DISAPPEARED
                }

                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            missingBarFrames = 0

            val observation = detected
            val fishX = observation.fishX
            val now = SystemClock.elapsedRealtime()

            val dt = (
                (now - lastSampleAt).coerceIn(25L, 250L)
            ) / 1000f

            val currentBarCenter = observation.barCenter
            val oldFish = lastFishX
            val oldBar = lastBarCenter

            if (fishX == null) {
                if (noFishSince == 0L) {
                    noFishSince = now
                }

                if (
                    now - noFishSince > 4_000L &&
                    now - lastStatusAt > 1_000L
                ) {
                    onStatus(
                        "ПОЛОСА НАЙДЕНА · ищу маркер рыбы"
                    )

                    lastStatusAt = now
                }

                // Если маркер рыбы временно не найден,
                // поддерживаем приблизительный баланс управления.
                holdAccumulator += 0.46f

                if (holdAccumulator >= 1f) {
                    holdAccumulator -= 1f

                    val result = sendReelControlPulse(
                        capture = capture,
                        accessibility = accessibility,
                        frame = frame,
                        expectedRotation = currentRotation,
                        stopFlag = stopFlag
                    )

                    if (result == false && !stopFlag.get()) {
                        throw IllegalStateException(
                            "Не удалось отправить касание управления мини-игрой"
                        )
                    }

                    if (result == null) {
                        holdAccumulator = 0f
                    }

                } else {
                    Thread.sleep(CONTROL_PULSE_MS)
                }

                lastBarCenter = currentBarCenter
                lastSampleAt = now
                continue
            }

            noFishSince = 0L

            if (
                oldFish != null &&
                oldBar != null &&
                dt > 0f
            ) {
                val rawFishVelocity =
                    (fishX - oldFish) / dt

                val rawBarVelocity =
                    (currentBarCenter - oldBar) / dt

                fishVelocity =
                    0.55f * rawFishVelocity +
                    0.45f * fishVelocity

                barVelocity =
                    0.55f * rawBarVelocity +
                    0.45f * barVelocity
            }

            // Положительная ошибка означает, что рыба правее полосы:
            // нужно дольше удерживать палец.
            val error =
                fishX - currentBarCenter

            val relativeVelocity =
                fishVelocity - barVelocity

            val normalizedPosition = (
                error / (observation.trackWidth * 0.22f)
            ).coerceIn(-1f, 1f)

            val normalizedVelocity = (
                relativeVelocity / (observation.trackWidth * 1.5f)
            ).coerceIn(-1f, 1f)

            // Чем правее рыба относительно полосы,
            // тем чаще нажимаем и удерживаем.
            // Чем левее — тем чаще отпускаем.
            val duty = (
                0.46f +
                    0.34f * normalizedPosition +
                    0.08f * normalizedVelocity
                ).coerceIn(0.08f, 0.92f)

            holdAccumulator += duty

            val holdNow = holdAccumulator >= 1f

            if (holdNow) {
                holdAccumulator -= 1f

                val result = sendReelControlPulse(
                    capture = capture,
                    accessibility = accessibility,
                    frame = frame,
                    expectedRotation = currentRotation,
                    stopFlag = stopFlag
                )

                if (result == false && !stopFlag.get()) {
                    throw IllegalStateException(
                        "Не удалось отправить касание управления мини-игрой"
                    )
                }

                if (result == null) {
                    // Геометрия экрана изменилась прямо перед касанием.
                    holdAccumulator = 0f
                }

            } else {
                // Палец отпущен — полоса Fisch должна двигаться влево.
                Thread.sleep(CONTROL_PULSE_MS)
            }

            if (now - lastStatusAt >= 650L) {
                onStatus(
                    "РЫБА ${
                        positionPct(
                            fishX,
                            observation.trackLeft,
                            observation.trackRight
                        )
                    } · ПОЛОСА ${
                        positionPct(
                            currentBarCenter.toInt(),
                            observation.trackLeft,
                            observation.trackRight
                        )
                    }"
                )

                lastStatusAt = now
            }

            lastFishX = fishX
            lastBarCenter = currentBarCenter
            lastSampleAt = now
        }

        return ReelEnd.TIMEOUT
    }

    /**
     * Проверяет, что обнаружена та же полоса мини-игры.
     */
    private fun isSameReelOverlay(
        reference: ReelObservation,
        candidate: ReelObservation,
        frame: CapturedFrame
    ): Boolean {
        val yTolerance =
            max(50, (frame.height * 0.045f).toInt())

        val centerTolerance =
            max(45, (frame.width * 0.09f).toInt())

        val trackWidthTolerance =
            max(60, (reference.trackWidth * 0.60f).toInt())

        val barWidthTolerance =
            max(35, (reference.barWidth * 0.70f).toInt())

        val referenceTrackCenter =
            (reference.trackLeft + reference.trackRight) / 2

        val candidateTrackCenter =
            (candidate.trackLeft + candidate.trackRight) / 2

        return abs(
            candidate.centerY - reference.centerY
        ) <= yTolerance &&

            abs(
                candidateTrackCenter - referenceTrackCenter
            ) <= centerTolerance &&

            abs(
                candidate.trackWidth - reference.trackWidth
            ) <= trackWidthTolerance &&

            abs(
                candidate.barWidth - reference.barWidth
            ) <= barWidthTolerance &&

            candidate.confidence >= 0.38f
    }

    /**
     * Отправляет короткое удержание по центру актуального кадра.
     *
     * null означает, что экран изменился прямо перед касанием.
     */
    private fun sendReelControlPulse(
        capture: ScreenCaptureService,
        accessibility: MacroAccessibilityService,
        frame: CapturedFrame,
        expectedRotation: Int,
        stopFlag: AtomicBoolean
    ): Boolean? {
        val geometryStillCurrent =
            capture.isCaptureReady(FRAME_MAX_AGE_MS) &&
                capture.frameWidth() == frame.width &&
                capture.frameHeight() == frame.height &&
                capture.displayRotation() == expectedRotation

        if (!geometryStillCurrent) {
            return null
        }

        val x = (frame.width / 2f)
            .coerceIn(2f, (frame.width - 3).toFloat())

        val y = (frame.height / 2f)
            .coerceIn(2f, (frame.height - 3).toFloat())

        return accessibility.pressAndHold(
            x = x,
            y = y,
            durationMs = CONTROL_PULSE_MS,
            stopFlag = stopFlag
        )
    }

    private fun freshFrame(
        capture: ScreenCaptureService,
        stopFlag: AtomicBoolean
    ): CapturedFrame {
        val deadline =
            SystemClock.elapsedRealtime() + 3_000L

        while (
            !stopFlag.get() &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            val frame = capture.snapshot()

            if (
                frame != null &&
                capture.isCaptureReady(FRAME_MAX_AGE_MS)
            ) {
                return frame
            }

            Thread.sleep(60L)
        }

        if (stopFlag.get()) {
            throw InterruptedException("Stopped")
        }

        throw IllegalStateException(
            "Кадры экрана зависли или устарели"
        )
    }

    private fun positionPct(
        value: Int?,
        left: Int,
        right: Int
    ): String {
        if (value == null || right <= left) return "?"

        val pct = (
            (value - left) * 100f / (right - left)
        ).toInt().coerceIn(0, 100)

        return "$pct%"
    }

    private fun sleepCancellable(
        millis: Long,
        stopFlag: AtomicBoolean
    ) {
        var remaining = millis

        while (remaining > 0 && !stopFlag.get()) {
            val part = minOf(remaining, 100L)
            Thread.sleep(part)
            remaining -= part
        }
    }
}
