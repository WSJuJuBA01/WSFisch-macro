package com.wsfisch.mobile

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object MacroRunner {

    private const val PREFS = "mobile_macro_rod_settings"

    private const val FRAME_MAX_AGE_MS = 1_200L
    private const val LOOP_SLEEP_MS = 25L

    // Обычные короткие движения полосы.
    // Gesture callback блокирующий: держим импульсы короче одного
    // интервала захвата 60 мс, чтобы чаще получать новое измерение Bar.
    private const val CONTROL_HOLD_SHORT_MS = 40L
    private const val CONTROL_HOLD_MEDIUM_MS = 45L
    private const val CONTROL_HOLD_LONG_MS = 55L
    private const val CONTROL_CLICK_MS = 45L
    private const val CONTROL_CLICK_COOLDOWN_MS = 115L

    // Fisch показывает анимацию появления Reel UI после заброса.
    private const val REEL_ANIMATION_DELAY_MS = 420L
    private const val REEL_CONFIRMATION_HIGH_FRAMES = 3
    private const val REEL_CONFIRMATION_LOW_FRAMES = 4
    private const val REEL_CONFIRMATION_MIN_MS = 110L

    // Piercing/Chaotic могут оглушить рыбу приблизительно на 350 мс.
    // Небольшой запас нужен на задержку кадра и VFX поверх маркера.
    private const val FISH_STUN_GRACE_MS = 650L

    // Piercing может временно оглушать рыбу и сопровождаться VFX.
    // Завершение игры определяем по реальному отсутствию Bar почти секунду.
    private const val REEL_BAR_MISSING_GRACE_MS = 950L

    private const val ZODIAC_DISCOVERY_TIMEOUT_MS = 3_500L
    private const val ZODIAC_TOTAL_TIMEOUT_MS = 9_000L
    private const val ZODIAC_CLICK_VERIFY_DELAY_MS = 90L

    data class Config(
        val castXPercent: Int,
        val castYPercent: Int,
        val castHoldMs: Long,
        val specialRod: String,
        val waitForBobberMs: Long,
        val shakeTimeoutMs: Long,
        val reelTimeoutMs: Long,
        val recastDelayMs: Long,

        val fishColorRgb: Int?,
        val fishColorTolerance: Int,
        val barWhiteTolerance: Int,
        val trackTolerance: Int,

        val zodiacClickSigns: Boolean,
        val zodiacFastClicks: Boolean,
        val zodiacRetryClicks: Boolean,
        val zodiacHeadColorRgb: Int,
        val zodiacBarColorRgb: Int,
        val zodiacHeadTolerance: Int,
        val zodiacBarTolerance: Int
    )

    fun readConfig(context: Context): Config {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val specialRod =
            prefs.getString("SpecialRod", "None") ?: "None"

        val castbound = specialRod.equals(
            "Castbound",
            ignoreCase = true
        )

        val stellarwave = specialRod.equals(
            "Stellarwave Melody",
            ignoreCase = true
        )

        val fishColor = when {
            castbound -> readHexColor(
                prefs.getString("CastboundFishColors", "0x5522A3"),
                0x5522A3
            )

            stellarwave -> readHexColor(
                prefs.getString("StellarFishColors", "0xAFDAE8"),
                0xAFDAE8
            )

            else -> null
        }

        val fishTolerance = when {
            castbound -> prefs.getString(
                "CastboundFishTol",
                "20"
            )?.toIntOrNull()?.coerceIn(5, 80) ?: 20

            stellarwave -> prefs.getString(
                "StellarFishTol",
                "22"
            )?.toIntOrNull()?.coerceIn(5, 80) ?: 22

            else -> 24
        }

        // CastboundBarTol относится к белому Control Bar.
        // StellarBarTol отдельно калибрует цветные знаки Zodiac Wheel.
        val barWhiteTolerance = if (castbound) {
            prefs.getString(
                "CastboundBarTol",
                "20"
            )?.toIntOrNull()?.coerceIn(5, 80) ?: 20
        } else {
            20
        }

        val trackTolerance = if (castbound) {
            prefs.getString(
                "CastboundTrackTol",
                "20"
            )?.toIntOrNull()?.coerceIn(5, 80) ?: 20
        } else {
            20
        }

        return Config(
            castXPercent = prefs.getString("CastXPercent", "50")
                ?.toIntOrNull()?.coerceIn(0, 100) ?: 50,

            castYPercent = prefs.getString("CastYPercent", "50")
                ?.toIntOrNull()?.coerceIn(0, 100) ?: 50,

            castHoldMs = prefs.getString("CastHoldMs", "1000")
                ?.toLongOrNull()?.coerceIn(250L, 5000L) ?: 1000L,

            specialRod = specialRod,

            waitForBobberMs = prefs.getString("WaitForBobberMs", "1000")
                ?.toLongOrNull()?.coerceIn(250L, 5000L) ?: 1000L,

            shakeTimeoutMs = prefs.getString("ShakeTimeoutMs", "35000")
                ?.toLongOrNull()?.coerceIn(10_000L, 90_000L) ?: 35_000L,

            reelTimeoutMs = prefs.getString("ReelTimeoutMs", "120000")
                ?.toLongOrNull()?.coerceIn(30_000L, 180_000L) ?: 120_000L,

            recastDelayMs = prefs.getString("RecastDelayMs", "1000")
                ?.toLongOrNull()?.coerceIn(300L, 5000L) ?: 1000L,

            fishColorRgb = fishColor,
            fishColorTolerance = fishTolerance,
            barWhiteTolerance = barWhiteTolerance,
            trackTolerance = trackTolerance,

            zodiacClickSigns = stellarwave && prefs.getBoolean(
                "SwClickSigns",
                true
            ),

            zodiacFastClicks = prefs.getBoolean(
                "SwFastClicks",
                false
            ),

            zodiacRetryClicks = prefs.getBoolean(
                "SwRetryClicks",
                false
            ),

            zodiacHeadColorRgb = readHexColor(
                prefs.getString("StellarHeadColors", "0xFFFFFF"),
                0xFFFFFF
            ),

            zodiacBarColorRgb = readHexColor(
                prefs.getString("StellarBarColors", "0x55446D"),
                0x55446D
            ),

            zodiacHeadTolerance = prefs.getString(
                "StellarHeadTol",
                "30"
            )?.toIntOrNull()?.coerceIn(5, 100) ?: 30,

            zodiacBarTolerance = prefs.getString(
                "StellarBarTol",
                "26"
            )?.toIntOrNull()?.coerceIn(5, 100) ?: 26
        )
    }

    private fun readHexColor(
        value: String?,
        fallback: Int
    ): Int {
        val raw = value
            ?.trim()
            ?.removePrefix("0x")
            ?.removePrefix("0X")
            ?.removePrefix("#")
            ?: return fallback

        return raw.toLongOrNull(16)
            ?.toInt()
            ?.and(0xFFFFFF)
            ?: fallback
    }

    // =====================================================
    // ГЛАВНЫЙ ЦИКЛ
    // =====================================================

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

                onStatus(
                    "ЗАБРОС ВЫПОЛНЕН · жду анимацию интерфейса"
                )

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
                    "МИНИ-ИГРА ПОДТВЕРЖДЕНА · запускаю адаптивное управление"
                )

                val reelResult = playReel(
                    capture = capture,
                    accessibility = accessibility,
                    config = config,
                    initial = waitResult.reel,
                    initialFrameWidth = waitResult.frameWidth,
                    initialFrameHeight = waitResult.frameHeight,
                    initialRotation = waitResult.frameRotation,
                    timeoutMs = config.reelTimeoutMs,
                    stopFlag = stopFlag,
                    onStatus = onStatus
                )

                if (stopFlag.get()) return

                if (
                    reelResult == ReelEnd.BAR_DISAPPEARED &&
                    config.zodiacClickSigns
                ) {
                    handleStellarZodiac(
                        capture = capture,
                        accessibility = accessibility,
                        config = config,
                        stopFlag = stopFlag,
                        onStatus = onStatus
                    )
                }

                if (stopFlag.get()) return

                when (reelResult) {
                    ReelEnd.BAR_DISAPPEARED -> {
                        onStatus(
                            "УЛОВ ЗАВЕРШЁН · цикл #$cycle · SHAKE $totalShakeClicks"
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

    // =====================================================
    // ОЖИДАНИЕ SHAKE / REEL
    // =====================================================

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
        val sequenceBeforeWait =
            capture.snapshot()?.sequence ?: -1L

        var lastSequence = -1L
        var lastFrameWidth = -1
        var lastFrameHeight = -1
        var lastFrameRotation = -2

        var lastClickedX = -1
        var lastClickedY = -1
        var lastClickAt = 0L

        var lastShakeCandidate: ShakePrompt? = null
        var shakeStableFrames = 0

        var lastReelCandidate: ReelObservation? = null
        var reelStableFrames = 0
        var reelCandidateSince = 0L

        var shakeClicks = 0
        var lastStatusAt = 0L
        var previousFish: Int? = null
        var previousBarCenter: Float? = null

        while (
            !stopFlag.get() &&
            SystemClock.elapsedRealtime() - started < config.shakeTimeoutMs
        ) {
            val frame = freshFrame(capture, stopFlag)
            if (stopFlag.get()) break

            // Старый кадр до завершения cast не используем.
            if (
                frame.sequence <= sequenceBeforeWait ||
                frame.capturedAtElapsedMs < started
            ) {
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            val rotation = capture.displayRotation()

            if (
                frame.width != lastFrameWidth ||
                frame.height != lastFrameHeight ||
                rotation != lastFrameRotation
            ) {
                lastFrameWidth = frame.width
                lastFrameHeight = frame.height
                lastFrameRotation = rotation

                lastClickedX = -1
                lastClickedY = -1
                lastClickAt = 0L

                lastShakeCandidate = null
                shakeStableFrames = 0

                lastReelCandidate = null
                reelStableFrames = 0
                reelCandidateSince = 0L

                previousFish = null
                previousBarCenter = null

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

            // До завершения анимации появление белых фрагментов не считается Reel.
            val reel = if (elapsed >= REEL_ANIMATION_DELAY_MS) {
                AdaptiveReelVision.detect(
                    frame = frame,
                    specialRod = config.specialRod,
                    previousFishX = previousFish,
                    fishColorRgb = config.fishColorRgb,
                    fishColorTolerance = config.fishColorTolerance,
                    barWhiteTolerance = config.barWhiteTolerance,
                    trackTolerance = config.trackTolerance,
                    previousBarCenter = previousBarCenter
                )
            } else {
                null
            }

            if (reel != null) {
                val previous = lastReelCandidate

                // ВАЖНО: barWidth здесь не сравнивается.
                // У Castbound ширина может меняться каждый новый кадр.
                val yTolerance =
                    max(14, (frame.height * 0.020f).toInt())

                val centerTolerance =
                    max(40, (frame.width * 0.075f).toInt())

                val previousTrackCenter = if (previous != null) {
                    (previous.trackLeft + previous.trackRight) / 2
                } else {
                    0
                }
                val currentTrackCenter =
                    (reel.trackLeft + reel.trackRight) / 2

                // trackLeft/Right — границы области поиска vision, а не
                // игровые края. При сужении полосы детектор может расширить
                // эту область, поэтому сравниваем только её центр и Y.
                val sameTrack = previous != null &&
                    abs(previous.centerY - reel.centerY) <= yTolerance &&
                    abs(previousTrackCenter - currentTrackCenter) <= centerTolerance

                val now = SystemClock.elapsedRealtime()

                reelStableFrames = if (sameTrack) {
                    reelStableFrames + 1
                } else {
                    reelCandidateSince = now
                    1
                }

                lastReelCandidate = reel
                if (reel.fishX != null) previousFish = reel.fishX
                previousBarCenter = reel.barCenter

                val requiredFrames = if (reel.confidence >= 0.68f) {
                    REEL_CONFIRMATION_HIGH_FRAMES
                } else {
                    REEL_CONFIRMATION_LOW_FRAMES
                }

                val stableMs =
                    if (reelCandidateSince > 0L) now - reelCandidateSince else 0L

                if (
                    reelStableFrames >= requiredFrames &&
                    stableMs >= REEL_CONFIRMATION_MIN_MS
                ) {
                    onStatus(
                        "REEL НАЙДЕН · BAR ${reel.barWidth}px · уверенность ${
                            (reel.confidence * 100).toInt()
                        }%"
                    )

                    return WaitResult(
                        reel = reel,
                        shakeClicks = shakeClicks,
                        frameWidth = frame.width,
                        frameHeight = frame.height,
                        frameRotation = rotation
                    )
                }

                if (now - lastStatusAt > 700L) {
                    onStatus(
                        "ПОДТВЕРЖДАЮ REEL · $reelStableFrames/$requiredFrames · BAR ${reel.barWidth}px"
                    )
                    lastStatusAt = now
                }

                lastShakeCandidate = null
                shakeStableFrames = 0

            } else {
                lastReelCandidate = null
                reelStableFrames = 0
                reelCandidateSince = 0L

                if (elapsed >= config.waitForBobberMs) {
                    val prompt = FischVision.detectShake(frame)

                    if (prompt == null) {
                        lastShakeCandidate = null
                        shakeStableFrames = 0

                        if (SystemClock.elapsedRealtime() - lastStatusAt > 1_200L) {
                            onStatus("ПОИСК · проверяю SHAKE и мини-игру")
                            lastStatusAt = SystemClock.elapsedRealtime()
                        }

                    } else {
                        val now = SystemClock.elapsedRealtime()
                        val dx = prompt.x - lastClickedX
                        val dy = prompt.y - lastClickedY

                        val sameAsLastClick =
                            lastClickedX >= 0 &&
                                kotlin.math.sqrt(
                                    (dx * dx + dy * dy).toFloat()
                                ) < max(36f, prompt.diameter * 0.35f)

                        val retrySame =
                            sameAsLastClick && now - lastClickAt >= 850L

                        val newPrompt = !sameAsLastClick
                        val oldCandidate = lastShakeCandidate

                        shakeStableFrames = if (
                            oldCandidate != null &&
                            abs(oldCandidate.x - prompt.x) < prompt.diameter * 0.30f &&
                            abs(oldCandidate.y - prompt.y) < prompt.diameter * 0.30f
                        ) {
                            shakeStableFrames + 1
                        } else {
                            1
                        }

                        lastShakeCandidate = prompt

                        val confident = prompt.confidence >= 0.68f
                        val stable = shakeStableFrames >= 2

                        if (
                            (newPrompt || retrySame) &&
                            (confident || stable)
                        ) {
                            val geometryStillCurrent =
                                capture.isCaptureReady(FRAME_MAX_AGE_MS) &&
                                    capture.frameWidth() == frame.width &&
                                    capture.frameHeight() == frame.height &&
                                    capture.displayRotation() == rotation

                            if (!geometryStillCurrent) {
                                lastShakeCandidate = null
                                shakeStableFrames = 0
                                continue
                            }

                            val tapped = accessibility.tap(
                                prompt.x.toFloat(),
                                prompt.y.toFloat(),
                                70L,
                                stopFlag
                            )

                            if (tapped) {
                                shakeClicks++
                                lastClickedX = prompt.x
                                lastClickedY = prompt.y
                                lastClickAt = SystemClock.elapsedRealtime()

                                onStatus(
                                    "SHAKE #$shakeClicks · ${prompt.x},${prompt.y}"
                                )
                            } else if (!stopFlag.get()) {
                                onStatus("SHAKE НАЙДЕН, НО КАСАНИЕ НЕ ПОДТВЕРЖДЕНО")
                            }

                            shakeStableFrames = 0
                        }
                    }

                } else if (SystemClock.elapsedRealtime() - lastStatusAt > 1_000L) {
                    onStatus("ОЖИДАНИЕ · анимация заброса")
                    lastStatusAt = SystemClock.elapsedRealtime()
                }
            }

            Thread.sleep(LOOP_SLEEP_MS)
        }

        return WaitResult(
            reel = null,
            shakeClicks = shakeClicks
        )
    }

    // =====================================================
    // REEL: ДИНАМИЧЕСКИЙ БАР + HOLD / CLICK / RELEASE
    // =====================================================

    private enum class ReelEnd {
        BAR_DISAPPEARED,
        TIMEOUT
    }

    private fun playReel(
        capture: ScreenCaptureService,
        accessibility: MacroAccessibilityService,
        config: Config,
        initial: ReelObservation,
        initialFrameWidth: Int,
        initialFrameHeight: Int,
        initialRotation: Int,
        timeoutMs: Long,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ): ReelEnd {
        val started = SystemClock.elapsedRealtime()

        var lastSequence = -1L
        var lastFishX: Int? = initial.fishX
        var lastFishObservedAt = if (initial.fishX != null) started else 0L
        var lastBarCenter: Float? = initial.barCenter
        var lastSampleAt = started

        var fishVelocity = 0f
        var barVelocity = 0f

        var missingBarSince = 0L
        var noFishStatusAt = 0L
        var lastStatusAt = 0L
        var lastClickAt = 0L

        var lastFrameWidth = initialFrameWidth
        var lastFrameHeight = initialFrameHeight
        var lastFrameRotation = initialRotation

        // Здесь фиксируется только игровая дорожка/позиция интерфейса.
        // Ширина Control Bar намеренно НЕ фиксируется — Castbound её меняет.
        var referenceObservation: ReelObservation? = initial
        var baselineCandidate: ReelObservation? = null

        while (
            !stopFlag.get() &&
            SystemClock.elapsedRealtime() - started < timeoutMs
        ) {
            val frame = freshFrame(capture, stopFlag)
            if (stopFlag.get()) return ReelEnd.TIMEOUT

            val rotation = capture.displayRotation()

            if (
                frame.width != lastFrameWidth ||
                frame.height != lastFrameHeight ||
                rotation != lastFrameRotation
            ) {
                lastFrameWidth = frame.width
                lastFrameHeight = frame.height
                lastFrameRotation = rotation

                lastFishX = null
                lastFishObservedAt = 0L
                lastBarCenter = null
                fishVelocity = 0f
                barVelocity = 0f
                missingBarSince = 0L
                lastClickAt = 0L

                lastSampleAt = SystemClock.elapsedRealtime()
                referenceObservation = null
                baselineCandidate = null

                onStatus(
                    "ЭКРАН ОБНОВЛЁН · ${frame.width}×${frame.height} · переизмеряю Control Bar"
                )
            }

            if (frame.sequence == lastSequence) {
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }
            lastSequence = frame.sequence

            val detected = AdaptiveReelVision.detect(
                frame = frame,
                specialRod = config.specialRod,
                previousFishX = lastFishX,
                fishColorRgb = config.fishColorRgb,
                fishColorTolerance = config.fishColorTolerance,
                barWhiteTolerance = config.barWhiteTolerance,
                trackTolerance = config.trackTolerance,
                previousBarCenter = lastBarCenter
            )

            if (detected == null) {
                val missingNow = SystemClock.elapsedRealtime()
                if (missingBarSince == 0L) {
                    missingBarSince = missingNow
                }
                if (missingNow - missingBarSince >= REEL_BAR_MISSING_GRACE_MS) {
                    return ReelEnd.BAR_DISAPPEARED
                }
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            val reference = referenceObservation

            if (reference == null) {
                val candidate = baselineCandidate
                if (
                    candidate != null &&
                    isSameReelTrack(candidate, detected, frame)
                ) {
                    referenceObservation = detected
                    baselineCandidate = null
                    lastBarCenter = detected.barCenter
                    lastSampleAt = SystemClock.elapsedRealtime()
                } else {
                    baselineCandidate = detected
                    missingBarSince = 0L
                    Thread.sleep(LOOP_SLEEP_MS)
                    continue
                }
            } else if (!isSameReelTrack(reference, detected, frame)) {
                val missingNow = SystemClock.elapsedRealtime()
                if (missingBarSince == 0L) {
                    missingBarSince = missingNow
                }
                if (missingNow - missingBarSince >= REEL_BAR_MISSING_GRACE_MS) {
                    return ReelEnd.BAR_DISAPPEARED
                }
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            // Здесь reference либо ещё не было и базовая геометрия
            // подтверждена, либо кадр совпал с уже известным треком.
            missingBarSince = 0L

            // Важное отличие от старой логики: barWidth читается из detected
            // на каждом новом кадре, без сравнения с начальной шириной.
            val currentBarWidth = detected.barWidth.coerceAtLeast(1)
            val currentBarCenter = detected.barCenter
            val now = SystemClock.elapsedRealtime()

            val elapsedSinceSample = (
                (now - lastSampleAt).coerceIn(25L, 250L)
            ) / 1000f

            val oldBar = lastBarCenter
            if (oldBar != null && elapsedSinceSample > 0f) {
                val rawBarVelocity =
                    (currentBarCenter - oldBar) / elapsedSinceSample
                barVelocity =
                    0.60f * rawBarVelocity + 0.40f * barVelocity
            }

            val freshFishX = detected.fishX
            if (freshFishX != null) {
                val previousFishObservedAt = lastFishObservedAt
                val gapSinceFish = if (previousFishObservedAt > 0L) {
                    now - previousFishObservedAt
                } else {
                    Long.MAX_VALUE
                }
                val previousFishX = lastFishX

                if (gapSinceFish > FISH_STUN_GRACE_MS) {
                    // После долгого исчезновения маркера не переносим старую скорость.
                    fishVelocity = 0f
                } else if (previousFishX != null && gapSinceFish >= 25L) {
                    // Скорость считаем по времени между двумя РЕАЛЬНЫМИ
                    // наблюдениями рыбы, а не по соседним кадрам.
                    // Иначе после stun 350 мс получался бы ложный скачок.
                    val fishDt = gapSinceFish.coerceIn(25L, FISH_STUN_GRACE_MS) / 1000f
                    val rawFishVelocity =
                        (freshFishX - previousFishX) / fishDt
                    val smoothing = if (gapSinceFish > 180L) 0.28f else 0.58f
                    fishVelocity =
                        smoothing * rawFishVelocity +
                            (1f - smoothing) * fishVelocity
                }

                lastFishX = freshFishX
                lastFishObservedAt = now
            } else if (
                lastFishX != null &&
                lastFishObservedAt > 0L &&
                now - lastFishObservedAt <= FISH_STUN_GRACE_MS
            ) {
                // Piercing, Castbound slash и похожие эффекты: короткий
                // stun/VFX — не потеря рыбы.
                // Используем последнюю достоверную позицию, гасим скорость.
                fishVelocity *= 0.20f
            }

            val effectiveFishX = when {
                freshFishX != null -> freshFishX
                lastFishX != null &&
                    lastFishObservedAt > 0L &&
                    now - lastFishObservedAt <= FISH_STUN_GRACE_MS -> lastFishX
                else -> null
            }

            if (effectiveFishX == null) {
                if (noFishStatusAt == 0L || now - noFishStatusAt >= 900L) {
                    onStatus(
                        "CONTROL BAR ${currentBarWidth}px · маркер рыбы временно не виден, жду повторного кадра"
                    )
                    noFishStatusAt = now
                }

                // Не делаем случайный hold с duty 0.46: он мог уводить полосу,
                // когда Piercing/VFX временно скрывали маркер.
                lastBarCenter = currentBarCenter
                lastSampleAt = now
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            noFishStatusAt = 0L

            val error = effectiveFishX - currentBarCenter
            val relativeVelocity = fishVelocity - barVelocity

            // Прогнозируем положение на короткий интервал реакции жеста.
            val predictedError =
                error + relativeVelocity * 0.075f

            // Полоса может стать очень узкой. Dead-zone рассчитывается
            // от её текущей измеренной ширины, а не от начального кадра.
            val centerDeadZone = max(
                1f,
                min(
                    currentBarWidth * 0.16f,
                    detected.trackWidth * 0.004f
                )
            )

            val touchX = (frame.width / 2f)
                .coerceIn(2f, (frame.width - 3).toFloat())
            val touchY = (frame.height / 2f)
                .coerceIn(2f, (frame.height - 3).toFloat())

            val geometryStillCurrent =
                capture.isCaptureReady(FRAME_MAX_AGE_MS) &&
                    capture.frameWidth() == frame.width &&
                    capture.frameHeight() == frame.height &&
                    capture.displayRotation() == rotation

            if (geometryStillCurrent) {
                when {
                    // Рыба правее — удерживаем, чтобы подвинуть Bar вправо.
                    predictedError > centerDeadZone -> {
                        val halfBar = max(4f, currentBarWidth / 2f)
                        val urgency = predictedError / halfBar

                        val baseHoldMs = when {
                            urgency >= 1.35f -> CONTROL_HOLD_LONG_MS
                            urgency >= 0.70f -> CONTROL_HOLD_MEDIUM_MS
                            else -> CONTROL_HOLD_SHORT_MS
                        }

                        // При уменьшении Control Bar уменьшаем и импульс:
                        // узкая полоса требует более мелких коррекций.
                        val widthFactor =
                            (currentBarWidth / 34f).coerceIn(0.45f, 1f)

                        val holdMs = (
                            baseHoldMs * widthFactor
                        ).toLong().coerceIn(40L, CONTROL_HOLD_LONG_MS)

                        val held = accessibility.pressAndHold(
                            x = touchX,
                            y = touchY,
                            durationMs = holdMs,
                            stopFlag = stopFlag
                        )

                        if (!held && !stopFlag.get()) {
                            throw IllegalStateException(
                                "Android не подтвердил удержание Control Bar"
                            )
                        }
                    }

                    // Рыба около центра — короткий click вместо постоянного hold.
                    // Клик компенсирует естественный дрейф Bar влево.
                    abs(predictedError) <= centerDeadZone -> {
                        val clickCooldown = if (config.specialRod.equals(
                                "Stellarwave Melody",
                                ignoreCase = true
                            )) {
                            105L
                        } else {
                            CONTROL_CLICK_COOLDOWN_MS
                        }

                        val driftAllowsClick = barVelocity <= 22f

                        if (
                            driftAllowsClick &&
                            now - lastClickAt >= clickCooldown
                        ) {
                            val clicked = accessibility.tap(
                                touchX,
                                touchY,
                                CONTROL_CLICK_MS,
                                stopFlag
                            )

                            if (!clicked && !stopFlag.get()) {
                                throw IllegalStateException(
                                    "Android не подтвердил точный click Control Bar"
                                )
                            }

                            if (clicked) lastClickAt = SystemClock.elapsedRealtime()
                        }
                    }

                    // Рыба левее — отпускаем. Fisch сама двигает Bar влево.
                    else -> Unit
                }
            }

            if (now - lastStatusAt >= 600L) {
                val fishPercent = positionPct(
                    effectiveFishX,
                    detected.trackLeft,
                    detected.trackRight
                )
                val barPercent = positionPct(
                    currentBarCenter.toInt(),
                    detected.trackLeft,
                    detected.trackRight
                )

                val action = when {
                    predictedError > centerDeadZone -> "HOLD"
                    abs(predictedError) <= centerDeadZone -> "CLICK / CENTER"
                    else -> "RELEASE"
                }

                val fishState = if (freshFishX == null) " · STUN/VFX" else ""

                onStatus(
                    "РЫБА $fishPercent · BAR $barPercent · ${currentBarWidth}px · $action$fishState"
                )

                lastStatusAt = now
            }

            lastBarCenter = currentBarCenter
            lastSampleAt = now
        }

        return ReelEnd.TIMEOUT
    }

    /**
     * Сравнивает только постоянные признаки игрового трека.
     * Ширина Control Bar исключена: она может изменяться каждый кадр.
     */
    private fun isSameReelTrack(
        reference: ReelObservation,
        candidate: ReelObservation,
        frame: CapturedFrame
    ): Boolean {
        val yTolerance = max(55, (frame.height * 0.05f).toInt())
        val edgeTolerance = max(55, (frame.width * 0.10f).toInt())
        val referenceCenter =
            (reference.trackLeft + reference.trackRight) / 2
        val candidateCenter =
            (candidate.trackLeft + candidate.trackRight) / 2

        // Ширина search window может расшириться на тонком Castbound Bar.
        // Не сравниваем её и тем более не сравниваем barWidth.
        return abs(reference.centerY - candidate.centerY) <= yTolerance &&
            abs(referenceCenter - candidateCenter) <= edgeTolerance &&
            candidate.confidence >= 0.34f
    }

    // =====================================================
    // STELLARWAVE MELODY — ZODIAC WHEEL
    // =====================================================

    private fun handleStellarZodiac(
        capture: ScreenCaptureService,
        accessibility: MacroAccessibilityService,
        config: Config,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ) {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + ZODIAC_TOTAL_TIMEOUT_MS

        var lastSequence = -1L
        var lastBoardKey: String? = null
        var stableFrames = 0
        var boardFirstSeenAt = 0L
        var lastBoardAt = 0L
        var nextSignIndex = 0

        onStatus("STELLARWAVE · ищу 12 знаков зодиака")

        while (
            !stopFlag.get() &&
            SystemClock.elapsedRealtime() < deadline &&
            nextSignIndex < 12
        ) {
            val frame = freshFrame(capture, stopFlag)
            if (stopFlag.get()) return

            if (frame.sequence == lastSequence) {
                Thread.sleep(35L)
                continue
            }
            lastSequence = frame.sequence

            val board = ZodiacVision.detect(
                frame = frame,
                headColorRgb = config.zodiacHeadColorRgb,
                barColorRgb = config.zodiacBarColorRgb,
                headTolerance = config.zodiacHeadTolerance,
                barTolerance = config.zodiacBarTolerance
            )

            val now = SystemClock.elapsedRealtime()

            if (board == null) {
                if (
                    boardFirstSeenAt == 0L &&
                    now - started >= ZODIAC_DISCOVERY_TIMEOUT_MS
                ) {
                    onStatus(
                        "STELLARWAVE · Zodiac Wheel не найден, пропускаю без случайных кликов"
                    )
                    return
                }

                if (
                    boardFirstSeenAt > 0L &&
                    now - lastBoardAt > 1_100L
                ) {
                    onStatus(
                        "STELLARWAVE · Zodiac Wheel исчез, обработано $nextSignIndex/12"
                    )
                    return
                }

                Thread.sleep(40L)
                continue
            }

            if (boardFirstSeenAt == 0L) boardFirstSeenAt = now
            lastBoardAt = now

            val key = board.stableKey()
            if (key == lastBoardKey) {
                stableFrames++
            } else {
                lastBoardKey = key
                stableFrames = 1
            }

            if (stableFrames < 2) {
                Thread.sleep(35L)
                continue
            }

            val match = ZodiacVision.findMatch(
                board = board,
                sequenceIndex = nextSignIndex,
                // Каждая следующая позиция задаётся следующим символом
                // верхней последовательности. Не блокируем старые X:
                // нижний ряд может сдвигаться после правильного клика.
                usedCenters = emptySet()
            )

            if (match == null) {
                onStatus(
                    "STELLARWAVE · знак ${nextSignIndex + 1}/12 не распознан уверенно, пропускаю автоматический клик"
                )
                return
            }

            val geometryStillCurrent =
                capture.isCaptureReady(FRAME_MAX_AGE_MS) &&
                    capture.frameWidth() == frame.width &&
                    capture.frameHeight() == frame.height

            if (!geometryStillCurrent) continue

            val clickDuration = if (config.zodiacFastClicks) 45L else 65L
            var clicked = accessibility.tap(
                match.glyph.centerX.toFloat(),
                match.glyph.centerY.toFloat(),
                clickDuration,
                stopFlag
            )

            // Retry применяется только когда Android явно не подтвердил жест.
            // Мы не повторяем клик просто потому, что VFX задержал обновление UI.
            if (
                !clicked &&
                config.zodiacRetryClicks &&
                !stopFlag.get()
            ) {
                sleepCancellable(120L, stopFlag)
                clicked = accessibility.tap(
                    match.glyph.centerX.toFloat(),
                    match.glyph.centerY.toFloat(),
                    clickDuration,
                    stopFlag
                )
            }

            if (stopFlag.get()) return

            if (!clicked) {
                onStatus(
                    "STELLARWAVE · Android не подтвердил клик по знаку ${nextSignIndex + 1}"
                )
                return
            }

            nextSignIndex++

            onStatus(
                "STELLARWAVE ZODIAC · ${nextSignIndex}/12 · совпадение ${(match.score * 100).toInt()}%"
            )

            stableFrames = 0
            lastBoardKey = null

            val delay = if (config.zodiacFastClicks) 55L else 105L
            sleepCancellable(
                max(delay, ZODIAC_CLICK_VERIFY_DELAY_MS),
                stopFlag
            )
        }

        if (!stopFlag.get() && nextSignIndex >= 12) {
            onStatus("STELLARWAVE · все 12 знаков обработаны")
        }
    }

    // =====================================================
    // КАДРЫ И ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
    // =====================================================

    private fun freshFrame(
        capture: ScreenCaptureService,
        stopFlag: AtomicBoolean
    ): CapturedFrame {
        val deadline = SystemClock.elapsedRealtime() + 3_000L

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
            Thread.sleep(45L)
        }

        if (stopFlag.get()) {
            throw InterruptedException("Stopped")
        }

        throw IllegalStateException(
            "Кадры экрана зависли или устарели"
        )
    }

    private fun positionPct(
        value: Int,
        left: Int,
        right: Int
    ): String {
        if (right <= left) return "?"
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
