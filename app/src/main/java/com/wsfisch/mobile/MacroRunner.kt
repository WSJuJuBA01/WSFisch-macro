package com.wsfisch.mobile

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

object MacroRunner {

    private const val PREFS = "mobile_macro_rod_settings"
    private const val FRAME_MAX_AGE_MS = 1_200L
    private const val LOOP_SLEEP_MS = 35L
    private const val CONTROL_PULSE_MS = 55L

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
            val firstFrame = freshFrame(capture, stopFlag)

            val reelTouchX =
                (firstFrame.width / 2f)
                    .coerceIn(2f, firstFrame.width - 3f)

            val reelTouchY =
                (firstFrame.height / 2f)
                    .coerceIn(2f, firstFrame.height - 3f)

            onStatus(
                "ГОТОВО: ${firstFrame.width}×${firstFrame.height} · ${config.specialRod}"
            )

            var cycle = 0
            var totalShakeClicks = 0

            while (!stopFlag.get()) {
                cycle++

                val beforeCast = freshFrame(capture, stopFlag)
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
                        "Android не завершил жест заброса. Проверь службу касаний и повтори тест"
                    )
                }

                onStatus("ОЖИДАНИЕ · ищу SHAKE и мини-игру")

                sleepCancellable(
                    config.waitForBobberMs,
                    stopFlag
                )

                if (stopFlag.get()) return

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

                onStatus("МИНИ-ИГРА · полоса найдена, веду рыбу")

                val reelResult = playReel(
                    capture = capture,
                    accessibility = accessibility,
                    initial = waitResult.reel,
                    specialRod = config.specialRod,
                    touchX = reelTouchX,
                    touchY = reelTouchY,
                    timeoutMs = config.reelTimeoutMs,
                    stopFlag = stopFlag,
                    onStatus = onStatus
                )

                if (stopFlag.get()) return

                onStatus(
                    if (reelResult == ReelEnd.BAR_DISAPPEARED) {
                        "МИНИ-ИГРА ЗАВЕРШЕНА · цикл $cycle · SHAKE $totalShakeClicks"
                    } else {
                        "ТАЙМ-АУТ МИНИ-ИГРЫ · перезапуск цикла $cycle"
                    }
                )

                sleepCancellable(
                    config.recastDelayMs,
                    stopFlag
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            onStatus("ОСТАНОВЛЕНО")
        } catch (e: Exception) {
            onStatus("ОШИБКА: ${e.message ?: "неизвестная ошибка"}")
        }
    }

    private data class WaitResult(
        val reel: ReelObservation?,
        val shakeClicks: Int
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
        var lastClickedX = -1
        var lastClickedY = -1
        var lastClickAt = 0L

        var lastCandidate: ShakePrompt? = null
        var candidateStableFrames = 0
        var barStableFrames = 0
        var shakeClicks = 0
        var lastStatusAt = 0L
        var previousFish: Int? = null

        while (
            !stopFlag.get() &&
            SystemClock.elapsedRealtime() - started <
            config.shakeTimeoutMs
        ) {
            val frame = freshFrame(capture, stopFlag)
            if (stopFlag.get()) break

            if (frame.sequence == lastSequence) {
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            lastSequence = frame.sequence

            val reel = FischVision.detectReel(
                frame,
                config.specialRod,
                previousFish
            )

            if (reel != null) {
                barStableFrames++

                if (reel.fishX != null) {
                    previousFish = reel.fishX
                }

                if (barStableFrames >= 2) {
                    onStatus(
                        "МИНИ-ИГРА ОБНАРУЖЕНА · рыба ${
                            positionPct(
                                reel.fishX,
                                reel.trackLeft,
                                reel.trackRight
                            )
                        }"
                    )

                    return WaitResult(reel, shakeClicks)
                }

                if (
                    SystemClock.elapsedRealtime() - lastStatusAt >
                    900L
                ) {
                    onStatus("ПОЛОСА НАЙДЕНА · жду маркер рыбы")
                    lastStatusAt = SystemClock.elapsedRealtime()
                }
            } else {
                barStableFrames = 0

                val prompt = FischVision.detectShake(frame)

                if (prompt == null) {
                    lastCandidate = null
                    candidateStableFrames = 0
                } else {
                    val now = SystemClock.elapsedRealtime()

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

                    if (
                        previous != null &&
                        abs(previous.x - prompt.x) <
                        prompt.diameter * 0.30f &&
                        abs(previous.y - prompt.y) <
                        prompt.diameter * 0.30f
                    ) {
                        candidateStableFrames++
                    } else {
                        candidateStableFrames = 1
                    }

                    lastCandidate = prompt

                    val confident = prompt.confidence >= 0.68f
                    val stable = candidateStableFrames >= 2

                    if (
                        (movedToNewPrompt || canRetrySame) &&
                        (confident || stable)
                    ) {
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
                                prompt.confidence.toInt().coerceIn(0, 100)
                            }%"
                        )

                        lastStatusAt =
                            SystemClock.elapsedRealtime()
                    }
                }
            }

            Thread.sleep(LOOP_SLEEP_MS)
        }

        return WaitResult(null, shakeClicks)
    }

    private enum class ReelEnd {
        BAR_DISAPPEARED,
        TIMEOUT
    }

    private fun playReel(
        capture: ScreenCaptureService,
        accessibility: MacroAccessibilityService,
        initial: ReelObservation,
        specialRod: String,
        touchX: Float,
        touchY: Float,
        timeoutMs: Long,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ): ReelEnd {
        val started = SystemClock.elapsedRealtime()

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

        while (
            !stopFlag.get() &&
            SystemClock.elapsedRealtime() - started < timeoutMs
        ) {
            val frame = freshFrame(capture, stopFlag)
            if (stopFlag.get()) return ReelEnd.TIMEOUT

            if (frame.sequence == lastSequence) {
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            lastSequence = frame.sequence

            val observation = FischVision.detectReel(
                frame,
                specialRod,
                lastFishX
            )

            if (observation == null) {
                missingBarFrames++

                if (missingBarFrames >= 8) {
                    return ReelEnd.BAR_DISAPPEARED
                }

                Thread.sleep(55L)
                continue
            }

            missingBarFrames = 0

            val fishX = observation.fishX

            if (fishX == null) {
                if (noFishSince == 0L) {
                    noFishSince = SystemClock.elapsedRealtime()
                }

                if (
                    SystemClock.elapsedRealtime() - noFishSince >
                    4_000L &&
                    SystemClock.elapsedRealtime() - lastStatusAt >
                    1_000L
                ) {
                    onStatus(
                        "ПОЛОСА НАЙДЕНА · не вижу маркер рыбы"
                    )

                    lastStatusAt =
                        SystemClock.elapsedRealtime()
                }

                Thread.sleep(55L)
                continue
            }

            noFishSince = 0L

            val now = SystemClock.elapsedRealtime()

            val dt = (
                (now - lastSampleAt).coerceIn(25L, 250L)
                ) / 1000f

            val currentBarCenter = observation.barCenter
            val oldFish = lastFishX
            val oldBar = lastBarCenter

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

            val error = fishX - currentBarCenter
            val relativeVelocity = fishVelocity - barVelocity

            val normalizedPosition = (
                error / (observation.trackWidth * 0.22f)
                ).coerceIn(-1f, 1f)

            val normalizedVelocity = (
                relativeVelocity / (observation.trackWidth * 1.5f)
                ).coerceIn(-1f, 1f)

            val duty = (
                0.46f +
                    0.34f * normalizedPosition +
                    0.08f * normalizedVelocity
                ).coerceIn(0.08f, 0.92f)

            holdAccumulator += duty

            val holdNow = holdAccumulator >= 1f

            if (holdNow) {
                holdAccumulator -= 1f
            }

            if (holdNow) {
                val ok = accessibility.pressAndHold(
                    x = touchX,
                    y = touchY,
                    durationMs = CONTROL_PULSE_MS,
                    stopFlag = stopFlag
                )

                if (!ok && !stopFlag.get()) {
                    throw IllegalStateException(
                        "Не удалось отправить касание управления мини-игрой"
                    )
                }
            } else {
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
