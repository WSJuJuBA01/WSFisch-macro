package com.wsfisch.mobile

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android-native macro loop.
 *
 * Profile selection is read from RodProfiles and SharedPreferences.
 * Existing Castbound/Stellarwave paths are retained; Requiem timing and
 * Dreambreaker reverse-control timing are wired into the reel controller.
 * Detectors that require dedicated image recognition remain fail-closed:
 * a profile name alone never fabricates a fish/bar/marker observation.
 */
object MacroRunner {

    private const val PREFS = "mobile_macro_rod_settings"
    private const val FRAME_MAX_AGE_MS = 1_200L
    private const val LOOP_SLEEP_MS = 25L

    private const val CONTROL_HOLD_SHORT_MS = 40L
    private const val CONTROL_HOLD_MEDIUM_MS = 45L
    private const val CONTROL_HOLD_LONG_MS = 55L
    private const val CONTROL_CLICK_MS = 45L
    private const val CONTROL_CLICK_COOLDOWN_MS = 115L

    private const val REEL_ANIMATION_DELAY_MS = 420L
    private const val REEL_CONFIRMATION_HIGH_FRAMES = 3
    private const val REEL_CONFIRMATION_LOW_FRAMES = 4
    private const val REEL_CONFIRMATION_MIN_MS = 110L

    private const val FISH_STUN_GRACE_MS = 650L
    private const val REEL_BAR_MISSING_GRACE_MS = 950L

    private const val DREAMBREAKER_REVERSE_DELAY_MS = 1_500L
    private const val ZODIAC_DISCOVERY_TIMEOUT_MS = 3_500L
    private const val ZODIAC_TOTAL_TIMEOUT_MS = 9_000L
    private const val ZODIAC_CLICK_VERIFY_DELAY_MS = 90L

    enum class ReelEnd {
        BAR_DISAPPEARED,
        TIMEOUT
    }

    data class Config(
        val castXPercent: Int,
        val castYPercent: Int,
        val castHoldMs: Long,
        val specialRod: String,
        val profile: RodProfiles.Profile,
        val waitForBobberMs: Long,
        val shakeTimeoutMs: Long,
        val reelTimeoutMs: Long,
        val recastDelayMs: Long,

        val fishColorRgb: Int?,
        val fishColorsRgb: List<Int>,
        val barColorsRgb: List<Int>,
        val fishColorTolerance: Int,
        val barWhiteTolerance: Int,
        val trackTolerance: Int,
        val noteColorRgb: Int,
        val noteColorTolerance: Int,
        val noteBias: Int,
        val noteHandoffPx: Int,
        val lullRadiusPct: Int,
        val lullPivotUpPx: Int,
        val lullLightLum: Int,
        val lullArmDelta: Int,
        val lullTriggerPct: Int,
        val lullClearMs: Long,
        val lullMinDegrees: Int,
        val maniaLeadMs: Int,

        val controlMode: RodProfiles.ControlMode,
        val kp: Float,
        val kd: Float,
        val velocitySmoothing: Float,
        val stoppingDistanceMultiplier: Float,

        val requiemMinHoldMs: Long,
        val requiemMinReleaseMs: Long,
        val dreambreakerReverseDelayMs: Long,

        val halibutPlayMinigame: Boolean,
        val remembranceMode: String,

        val zodiacClickSigns: Boolean,
        val zodiacFastClicks: Boolean,
        val zodiacRetryClicks: Boolean,
        val zodiacHeadColorRgb: Int,
        val zodiacBarColorRgb: Int,
        val zodiacHeadTolerance: Int,
        val zodiacBarTolerance: Int
    )

    fun readConfig(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedRod = prefs.getString("SpecialRod", "None") ?: "None"
        val specialRod = RodProfiles.canonicalize(savedRod)
        val profile = RodProfiles.get(specialRod)

        fun intPref(key: String, default: Int, minValue: Int, maxValue: Int): Int =
            prefs.getString(key, default.toString())
                ?.toIntOrNull()?.coerceIn(minValue, maxValue) ?: default

        fun longPref(key: String, default: Long, minValue: Long, maxValue: Long): Long =
            prefs.getString(key, default.toString())
                ?.toLongOrNull()?.coerceIn(minValue, maxValue) ?: default

        fun floatPref(key: String, default: Float, minValue: Float, maxValue: Float): Float =
            prefs.getString(key, default.toString())
                ?.toFloatOrNull()?.coerceIn(minValue, maxValue) ?: default

        fun colorList(value: String?): List<Int> = value
            ?.split(',', ';', ' ', '\n', '\t')
            ?.mapNotNull { token ->
                val parsed = readHexColor(token.trim(), -1)
                parsed.takeIf { it >= 0 }
            }
            ?.distinct()
            .orEmpty()

        val fishColorDefaults = when (specialRod) {
            "Castbound" -> "0x5522A3"
            "Stellarwave Melody" -> "0xAFDAE8"
            "Tryhard Rod" -> ""
            "MiguRod" -> "0xF9D9D4,0xFAD6CE,0xF9D4C7,0xF9D2C4,0xF8D0B7"
            "Verdant Oath", "Pinions Aria", "Requiem", "Darkheart",
            "Bellona's Waraxe", "Dreambreaker", "Tranquility", "Lullaby" -> "0x434B5B"
            "Noiseform" -> "0x0C4125"
            "Halibut Harpoon" -> "0x0D0B0B"
            "Remembrance" -> "0xFFFFFF"
            else -> ""
        }
        val fishColorKey = profile.fishColorKey
        val fishColors = if (fishColorKey != null) {
            colorList(prefs.getString(fishColorKey, fishColorDefaults))
        } else {
            colorList(fishColorDefaults)
        }
        val fishColor = fishColors.firstOrNull()
        val barColorKey = when (specialRod) {
            "Castbound" -> "CastboundBarColors"
            "Tryhard Rod" -> "TryhardBarColors"
            "Verdant Oath" -> "VerdantBarColors"
            else -> null
        }
        val barColors = barColorKey?.let { colorList(prefs.getString(it, "")) }.orEmpty()

        val fishToleranceKey = when (specialRod) {
            "Castbound" -> "CastboundFishTol"
            "Stellarwave Melody" -> "StellarFishTol"
            "Tryhard Rod" -> "TryhardFishTol"
            "MiguRod" -> "MiguFishTol"
            "Verdant Oath" -> "VerdantFishTol"
            else -> null
        }
        val fishTolerance = fishToleranceKey?.let {
            intPref(it, if (it == "StellarFishTol") 22 else 20, 5, 100)
        } ?: 24

        val barTolerance = when (specialRod) {
            "Castbound" -> intPref("CastboundBarTol", 20, 5, 100)
            "Tryhard Rod" -> intPref("TryhardBarTol", 20, 5, 100)
            "Verdant Oath" -> intPref("VerdantBarTol", 20, 5, 100)
            else -> 20
        }

        val trackTolerance = when (specialRod) {
            "Castbound" -> intPref("CastboundTrackTol", 20, 5, 100)
            "Tryhard Rod" -> intPref("TryhardTrackTol", 20, 5, 100)
            else -> 20
        }

        val defaultControl = when (specialRod) {
            "Noiseform", "MiguRod", "Halibut Harpoon", "Tryhard Rod",
            "Stellarwave Melody", "Remembrance", "Requiem",
            "Bellona's Waraxe", "Dreambreaker", "Verdant Oath",
            "Astraenus Serenade" -> RodProfiles.ControlMode.PRECISION
            "Pinions Aria", "Darkheart", "Lullaby", "Castbound" ->
                RodProfiles.ControlMode.LINES
            else -> profile.controlMode
        }
        val savedControl = prefs.getString("ControlMode", defaultControl.name)
            ?: defaultControl.name
        val requestedControl = when (savedControl.trim().uppercase()) {
            "PRECISION" -> RodProfiles.ControlMode.PRECISION
            "LINES" -> RodProfiles.ControlMode.LINES
            "NOTE_PRIORITY" -> RodProfiles.ControlMode.NOTE_PRIORITY
            "REVERSED_AFTER_DELAY" -> RodProfiles.ControlMode.REVERSED_AFTER_DELAY
            else -> RodProfiles.ControlMode.ADAPTIVE
        }
        val allowedControls = when (specialRod) {
            "Noiseform", "MiguRod", "Halibut Harpoon", "Tryhard Rod", "Stellarwave Melody" ->
                setOf(RodProfiles.ControlMode.PRECISION)
            "Pinions Aria", "Darkheart", "Lullaby", "Castbound" ->
                setOf(RodProfiles.ControlMode.LINES)
            "Remembrance", "Requiem", "Bellona's Waraxe", "Dreambreaker", "Verdant Oath" ->
                setOf(RodProfiles.ControlMode.LINES, RodProfiles.ControlMode.PRECISION)
            else -> setOf(
                RodProfiles.ControlMode.ADAPTIVE,
                RodProfiles.ControlMode.PRECISION,
                RodProfiles.ControlMode.LINES
            )
        }
        val controlMode = requestedControl.takeIf { it in allowedControls } ?: defaultControl

        val zodiacEnabled = specialRod == "Stellarwave Melody"
        val zodiacHead = readHexColor(
            prefs.getString("StellarHeadColors", "0xFFFFFF"),
            0xFFFFFF
        )
        val zodiacBar = readHexColor(
            prefs.getString("StellarBarColors", "0x55446D"),
            0x55446D
        )

        return Config(
            castXPercent = intPref("CastXPercent", 50, 0, 100),
            castYPercent = intPref("CastYPercent", 50, 0, 100),
            castHoldMs = longPref("CastHoldMs", 1_000L, 250L, 5_000L),
            specialRod = specialRod,
            profile = profile,
            waitForBobberMs = longPref("WaitForBobberMs", 1_000L, 250L, 5_000L),
            shakeTimeoutMs = longPref("ShakeTimeoutMs", 35_000L, 10_000L, 90_000L),
            reelTimeoutMs = longPref("ReelTimeoutMs", 120_000L, 30_000L, 180_000L),
            recastDelayMs = longPref("RecastDelayMs", 1_000L, 300L, 5_000L),
            fishColorRgb = fishColor,
            fishColorsRgb = fishColors,
            barColorsRgb = barColors,
            fishColorTolerance = fishTolerance,
            barWhiteTolerance = barTolerance,
            trackTolerance = trackTolerance,
            noteColorRgb = readHexColor(prefs.getString("NoteColor", "0xB2CFFF"), 0xB2CFFF),
            noteColorTolerance = intPref("NoteColorTolerance", 15, 4, 80),
            noteBias = intPref("NoteBias", 100, 0, 100),
            noteHandoffPx = intPref("NoteHandoff", 50, 0, 250),
            lullRadiusPct = intPref("LullRadiusPct", 16, 7, 24),
            lullPivotUpPx = intPref("LullPivotUp", 26, 0, 180),
            lullLightLum = intPref("LullLightLum", 90, 30, 220),
            lullArmDelta = intPref("LullArmDelta", 45, 15, 200),
            lullTriggerPct = intPref("LullTrigPct", 10, 3, 90),
            lullClearMs = longPref("LullClearMs", 100L, 20L, 2_000L),
            lullMinDegrees = intPref("LullMinDeg", 4, 2, 60),
            maniaLeadMs = intPref("ManiaLead", 45, 0, 180),
            controlMode = controlMode,
            kp = floatPref("Kp", 0.5f, 0.05f, 3.0f),
            kd = floatPref("Kd", 0.3f, 0.0f, 2.0f),
            velocitySmoothing = floatPref("VelocitySmoothing", 0.2f, 0.01f, 0.95f),
            stoppingDistanceMultiplier = floatPref(
                "StoppingDistanceMultiplier", 3.0f, 0.5f, 10.0f
            ),
            requiemMinHoldMs = longPref("ReelMinHold", 200L, 40L, 1_000L),
            requiemMinReleaseMs = longPref("ReelMinRelease", 200L, 40L, 1_000L),
            dreambreakerReverseDelayMs = longPref(
                "DreambreakerReverseDelayMs", DREAMBREAKER_REVERSE_DELAY_MS, 500L, 5_000L
            ),
            halibutPlayMinigame = prefs.getBoolean("HalibutPlayMinigame", true),
            remembranceMode = prefs.getString("RemembranceMode", "Living")
                ?.takeIf { it == "Living" || it == "Departed" } ?: "Living",
            zodiacClickSigns = zodiacEnabled && prefs.getBoolean("SwClickSigns", true),
            zodiacFastClicks = prefs.getBoolean("SwFastClicks", false),
            zodiacRetryClicks = prefs.getBoolean("SwRetryClicks", false),
            zodiacHeadColorRgb = zodiacHead,
            zodiacBarColorRgb = zodiacBar,
            zodiacHeadTolerance = intPref("StellarHeadTol", 30, 5, 100),
            zodiacBarTolerance = intPref("StellarBarTol", 26, 5, 100)
        )
    }

    private fun readHexColor(value: String?, fallback: Int): Int {
        val raw = value?.trim()?.removePrefix("0x")
            ?.removePrefix("0X")?.removePrefix("#") ?: return fallback
        return raw.toLongOrNull(16)?.toInt()?.and(0xFFFFFF) ?: fallback
    }

    fun run(
        context: Context,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ) {
        try {
            onStatus("ПРОВЕРКА: управление касаниями")
            val accessibility = MacroAccessibilityService.instance
                ?: throw IllegalStateException("Включи службу управления касаниями Android")

            onStatus("ПРОВЕРКА: захват экрана")
            val capture = ScreenCaptureService.instance
                ?: throw IllegalStateException("Сначала нажми «Начать захват» и разреши запись экрана")

            val readyDeadline = SystemClock.elapsedRealtime() + 12_000L
            while (!stopFlag.get() && SystemClock.elapsedRealtime() < readyDeadline) {
                if (capture.isCaptureReady(FRAME_MAX_AGE_MS)) break
                Thread.sleep(100L)
            }
            if (stopFlag.get()) return
            if (!capture.isCaptureReady(FRAME_MAX_AGE_MS)) {
                throw IllegalStateException("Захват не отдаёт свежие кадры. Проверь разрешение записи экрана")
            }

            val config = readConfig(context)
            val firstFrame = freshFrame(capture, stopFlag)
            val profileMode = when (config.specialRod) {
                "Remembrance" -> " · ${config.remembranceMode}"
                "Halibut Harpoon" -> if (config.halibutPlayMinigame) {
                    " · marker mode requested"
                } else {
                    " · fish tracking requested"
                }
                else -> ""
            }
            onStatus(
                "ГОТОВО: ${firstFrame.width}×${firstFrame.height} · ${config.specialRod} · ${config.controlMode}$profileMode"
            )

            var cycle = 0
            var totalShakeClicks = 0

            while (!stopFlag.get()) {
                cycle++
                val beforeCast = freshFrame(capture, stopFlag)
                if (stopFlag.get()) return

                val x = (beforeCast.width * config.castXPercent / 100f)
                    .toInt().coerceIn(2, beforeCast.width - 3)
                val y = (beforeCast.height * config.castYPercent / 100f)
                    .toInt().coerceIn(2, beforeCast.height - 3)

                onStatus("ЗАБРОС #$cycle · удержание ${config.castHoldMs} мс")
                val castDispatched = accessibility.pressAndHold(
                    x = x.toFloat(),
                    y = y.toFloat(),
                    durationMs = config.castHoldMs,
                    stopFlag = stopFlag
                )
                if (stopFlag.get()) return
                if (!castDispatched) {
                    throw IllegalStateException("Android не завершил жест заброса. Проверь службу касаний")
                }

                onStatus("ЗАБРОС ВЫПОЛНЕН · ищу поклёвку и мини-игру")
                val waitResult = waitForShakeOrReel(
                    capture, accessibility, config, stopFlag, onStatus
                )
                if (stopFlag.get()) return
                totalShakeClicks += waitResult.shakeClicks

                if (waitResult.specialMinigame && config.specialRod == "Tranquility") {
                    playTranquilityMinigame(
                        capture, accessibility, config, stopFlag, onStatus
                    )
                    if (stopFlag.get()) return
                    sleepCancellable(config.recastDelayMs, stopFlag)
                    continue
                }

                if (waitResult.specialMinigame && config.specialRod == "Lullaby") {
                    playLullabyMinigame(
                        capture, accessibility, config, stopFlag, onStatus
                    )
                    if (stopFlag.get()) return
                    sleepCancellable(config.recastDelayMs, stopFlag)
                    continue
                }

                if (waitResult.bellona != null) {
                    val bellonaResult = playBellonaMinigame(
                        capture, accessibility, config,
                        stopFlag, onStatus
                    )
                    if (stopFlag.get()) return
                    onStatus(
                        if (bellonaResult == ReelEnd.BAR_DISAPPEARED) {
                            "BELLONA · обе зоны завершены · цикл #$cycle"
                        } else {
                            "BELLONA · тайм-аут · цикл #$cycle"
                        }
                    )
                    sleepCancellable(config.recastDelayMs, stopFlag)
                    continue
                }

                if (waitResult.reel == null) {
                    onStatus("ПОКЛЁВКА НЕ НАЙДЕНА · SHAKE ${waitResult.shakeClicks} · повтор")
                    sleepCancellable(config.recastDelayMs, stopFlag)
                    continue
                }

                onStatus(
                    "МИНИ-ИГРА · ${config.specialRod} · ${config.profile.detectionMode}"
                )
                val zodiacHandled = AtomicBoolean(false)
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
                    zodiacHandled = zodiacHandled,
                    onStatus = onStatus
                )
                if (stopFlag.get()) return

                if (reelResult == ReelEnd.BAR_DISAPPEARED &&
                    config.zodiacClickSigns && !zodiacHandled.get()) {
                    handleStellarZodiac(capture, accessibility, config, stopFlag, onStatus)
                }
                if (stopFlag.get()) return

                when (reelResult) {
                    ReelEnd.BAR_DISAPPEARED -> onStatus(
                        "УЛОВ ЗАВЕРШЁН · цикл #$cycle · SHAKE $totalShakeClicks"
                    )
                    ReelEnd.TIMEOUT -> onStatus("ТАЙМ-АУТ МИНИ-ИГРЫ · цикл #$cycle")
                }
                sleepCancellable(config.recastDelayMs, stopFlag)
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
        val shakeClicks: Int,
        val frameWidth: Int = 0,
        val frameHeight: Int = 0,
        val frameRotation: Int = -1,
        val specialMinigame: Boolean = false,
        val bellona: RodMechanicVision.BellonaObservation? = null
    )

    private fun waitForShakeOrReel(
        capture: ScreenCaptureService,
        accessibility: MacroAccessibilityService,
        config: Config,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ): WaitResult {
        val started = SystemClock.elapsedRealtime()
        val sequenceBeforeWait = capture.snapshot()?.sequence ?: -1L

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
        var maniaStableFrames = 0
        var lastTwigCheckAt = 0L
        var bellonaStableFrames = 0
        var lastBellonaCandidate: RodMechanicVision.BellonaObservation? = null

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

                onStatus("ЭКРАН ОБНОВЛЁН · ${frame.width}×${frame.height}")
            }

            if (frame.sequence == lastSequence) {
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }
            lastSequence = frame.sequence

            val elapsed = SystemClock.elapsedRealtime() - started

            if (config.specialRod == "Splitbranch Twig" &&
                SystemClock.elapsedRealtime() - lastTwigCheckAt >= 300L) {
                lastTwigCheckAt = SystemClock.elapsedRealtime()
                handleTwigPrompt(capture, accessibility, frame, stopFlag, onStatus)
            }

            if (config.specialRod == "Tranquility" && elapsed >= config.waitForBobberMs) {
                maniaStableFrames = if (RodMechanicVision.detectManiaRings(frame)) {
                    maniaStableFrames + 1
                } else 0
                if (maniaStableFrames >= 2) {
                    onStatus("TRANQUILITY · MANIA обнаружена · запускаю 4 дорожки")
                    return WaitResult(
                        reel = null,
                        shakeClicks = shakeClicks,
                        frameWidth = frame.width,
                        frameHeight = frame.height,
                        frameRotation = rotation,
                        specialMinigame = true
                    )
                }
            }

            if (config.specialRod == "Lullaby" && elapsed >= REEL_ANIMATION_DELAY_MS) {
                val visible = RodMechanicVision.detectLullabyRing(
                    frame, (frame.height * 0.843f).roundToInt(), config.lullRadiusPct
                )
                if (visible) {
                    onStatus("LULLABY · световые сектора обнаружены")
                    return WaitResult(
                        reel = null,
                        shakeClicks = shakeClicks,
                        frameWidth = frame.width,
                        frameHeight = frame.height,
                        frameRotation = rotation,
                        specialMinigame = true
                    )
                }
            }

            // Bellona использует два независимых трека: не пропускаем их через
            // обычный однополосный детектор.
            if (config.specialRod == "Bellona's Waraxe" && elapsed >= REEL_ANIMATION_DELAY_MS) {
                val currentBellona = RodMechanicVision.detectBellona(frame)
                if (currentBellona != null) {
                    val previousBellona = lastBellonaCandidate
                    bellonaStableFrames = if (
                        previousBellona != null &&
                        isSameBellonaObservation(previousBellona, currentBellona, frame)
                    ) bellonaStableFrames + 1 else 1
                    lastBellonaCandidate = currentBellona
                    if (bellonaStableFrames >= 2) {
                        onStatus("BELLONA · две зоны обнаружены · запускаю независимое управление")
                        return WaitResult(
                            reel = null,
                            shakeClicks = shakeClicks,
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                            frameRotation = rotation,
                            bellona = currentBellona
                        )
                    }
                } else {
                    bellonaStableFrames = 0
                    lastBellonaCandidate = null
                }
            }

            // До завершения анимации появления Reel UI белые фрагменты не считаются мини-игрой.
            val rawReel = if (elapsed >= REEL_ANIMATION_DELAY_MS && config.specialRod != "Bellona's Waraxe") {
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
            } else null
            val reel = RodMechanicVision.detectSpecialReel(
                frame = frame,
                rod = config.specialRod,
                base = rawReel,
                previousFishX = previousFish,
                fishColors = config.fishColorsRgb,
                barColors = config.barColorsRgb,
                fishTolerance = config.fishColorTolerance,
                noteColorRgb = config.noteColorRgb,
                noteTolerance = config.noteColorTolerance,
                noteBias = config.noteBias,
                noteHandoffPx = config.noteHandoffPx,
                remembranceMode = config.remembranceMode,
                halibutPlayMinigame = config.halibutPlayMinigame
            )

            if (reel != null) {
                val previous = lastReelCandidate
                val yTolerance = max(14, (frame.height * 0.020f).toInt())
                val centerTolerance = max(40, (frame.width * 0.075f).toInt())

                val previousTrackCenter = if (previous != null) {
                    (previous.trackLeft + previous.trackRight) / 2
                } else {
                    0
                }
                val currentTrackCenter = (reel.trackLeft + reel.trackRight) / 2

                // trackLeft/Right — границы области поиска vision, а не игровые края.
                // При сужении полосы область поиска может измениться, поэтому сравниваем центр и Y.
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
                val stableMs = if (reelCandidateSince > 0L) now - reelCandidateSince else 0L

                if (
                    reelStableFrames >= requiredFrames &&
                    stableMs >= REEL_CONFIRMATION_MIN_MS
                ) {
                    onStatus(
                        "REEL НАЙДЕН · BAR ${reel.barWidth}px · уверенность ${(reel.confidence * 100).toInt()}%"
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
                        val sameAsLastClick = lastClickedX >= 0 &&
                            kotlin.math.sqrt((dx * dx + dy * dy).toFloat()) < max(36f, prompt.diameter * 0.35f)
                        val retrySame = sameAsLastClick && now - lastClickAt >= 850L
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
                        if ((newPrompt || retrySame) && (confident || stable)) {
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
                                onStatus("SHAKE #$shakeClicks · ${prompt.x},${prompt.y}")
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
        zodiacHandled: AtomicBoolean,
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

        // Фиксируем только дорожку и положение интерфейса.
        // Ширина Control Bar может меняться во время мини-игры.
        var referenceObservation: ReelObservation? = initial
        var baselineCandidate: ReelObservation? = null

        val requiem = config.specialRod == "Requiem"
        val dreambreaker = config.specialRod == "Dreambreaker"
        val lullabyTracker = if (config.specialRod == "Lullaby") {
            RodMechanicVision.LullabyTracker(
                config.lullRadiusPct, config.lullPivotUpPx,
                config.lullLightLum, config.lullArmDelta,
                config.lullTriggerPct, config.lullClearMs,
                config.lullMinDegrees
            )
        } else null
        var dreambreakerFirstBarX: Float? = null
        var dreambreakerMovementAt = 0L
        val dreambreakerSeenAt = started

        var zodiacNextIndex = 0
        var zodiacLastBoardKey: String? = null
        var zodiacStableFrames = 0
        var zodiacLastClickAt = 0L
        var zodiacLastBoardAt = 0L
        var zodiacSeenDuringReel = false

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

            if (config.specialRod == "Stellarwave Melody" &&
                config.zodiacClickSigns && !zodiacHandled.get()) {
                val zodiacNow = SystemClock.elapsedRealtime()
                val board = ZodiacVision.detect(
                    frame = frame,
                    headColorRgb = config.zodiacHeadColorRgb,
                    barColorRgb = config.zodiacBarColorRgb,
                    headTolerance = config.zodiacHeadTolerance,
                    barTolerance = config.zodiacBarTolerance
                )
                if (board != null) {
                    zodiacSeenDuringReel = true
                    zodiacLastBoardAt = zodiacNow
                    val key = board.stableKey()
                    if (key == zodiacLastBoardKey) zodiacStableFrames++ else {
                        zodiacLastBoardKey = key
                        zodiacStableFrames = 1
                    }
                    if (zodiacStableFrames >= 2 &&
                        zodiacNextIndex < 12 &&
                        zodiacNow - zodiacLastClickAt >= if (config.zodiacFastClicks) 55L else 95L) {
                        val match = ZodiacVision.findMatch(board, zodiacNextIndex)
                        val currentGeometry = capture.isCaptureReady(FRAME_MAX_AGE_MS) &&
                            capture.frameWidth() == frame.width &&
                            capture.frameHeight() == frame.height
                        if (match != null && currentGeometry) {
                            var clicked = accessibility.tap(
                                match.glyph.centerX.toFloat(),
                                match.glyph.centerY.toFloat(),
                                if (config.zodiacFastClicks) 45L else 65L,
                                stopFlag
                            )
                            if (!clicked && config.zodiacRetryClicks && !stopFlag.get()) {
                                sleepCancellable(120L, stopFlag)
                                clicked = accessibility.tap(
                                    match.glyph.centerX.toFloat(),
                                    match.glyph.centerY.toFloat(),
                                    if (config.zodiacFastClicks) 45L else 65L,
                                    stopFlag
                                )
                            }
                            if (clicked) {
                                zodiacNextIndex++
                                zodiacLastClickAt = SystemClock.elapsedRealtime()
                                zodiacStableFrames = 0
                                zodiacLastBoardKey = null
                                onStatus("STELLARWAVE ZODIAC · $zodiacNextIndex/12")
                                if (zodiacNextIndex >= 12) zodiacHandled.set(true)
                            } else {
                                onStatus("STELLARWAVE · клик по Zodiac не подтверждён")
                            }
                        }
                    }
                } else {
                    zodiacStableFrames = 0
                    zodiacLastBoardKey = null
                    if (zodiacSeenDuringReel && zodiacNow - zodiacLastBoardAt >= 1_100L) {
                        zodiacHandled.set(true)
                        onStatus("STELLARWAVE · Zodiac Wheel исчез · обработано $zodiacNextIndex/12")
                    }
                }
            }

            val rawDetected = AdaptiveReelVision.detect(
                frame = frame,
                specialRod = config.specialRod,
                previousFishX = lastFishX,
                fishColorRgb = config.fishColorRgb,
                fishColorTolerance = config.fishColorTolerance,
                barWhiteTolerance = config.barWhiteTolerance,
                trackTolerance = config.trackTolerance,
                previousBarCenter = lastBarCenter
            )
            val detected = RodMechanicVision.detectSpecialReel(
                frame = frame,
                rod = config.specialRod,
                base = rawDetected,
                previousFishX = lastFishX,
                fishColors = config.fishColorsRgb,
                barColors = config.barColorsRgb,
                fishTolerance = config.fishColorTolerance,
                noteColorRgb = config.noteColorRgb,
                noteTolerance = config.noteColorTolerance,
                noteBias = config.noteBias,
                noteHandoffPx = config.noteHandoffPx,
                remembranceMode = config.remembranceMode,
                halibutPlayMinigame = config.halibutPlayMinigame
            )

            if (detected == null) {
                val missingNow = SystemClock.elapsedRealtime()
                if (missingBarSince == 0L) missingBarSince = missingNow
                if (missingNow - missingBarSince >= REEL_BAR_MISSING_GRACE_MS) {
                    return ReelEnd.BAR_DISAPPEARED
                }
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            if (lullabyTracker != null) {
                val sectorTap = lullabyTracker.update(frame, detected.centerY)
                if (sectorTap != null) {
                    val clicked = accessibility.tap(
                        sectorTap.x.toFloat(),
                        sectorTap.y.toFloat(),
                        45L,
                        stopFlag
                    )
                    if (!clicked && !stopFlag.get()) {
                        onStatus("LULLABY · Android не подтвердил нажатие сектора")
                    } else if (clicked) {
                        onStatus("LULLABY · сектор ${sectorTap.sector + 1} обработан")
                    }
                }
            }

            val reference = referenceObservation
            if (reference == null) {
                val candidate = baselineCandidate
                if (candidate != null && isSameReelTrack(candidate, detected, frame)) {
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
                if (missingBarSince == 0L) missingBarSince = missingNow
                if (missingNow - missingBarSince >= REEL_BAR_MISSING_GRACE_MS) {
                    return ReelEnd.BAR_DISAPPEARED
                }
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            missingBarSince = 0L

            // Ширина измеряется заново на каждом кадре — это важно для Castbound.
            val currentBarWidth = detected.barWidth.coerceAtLeast(1)
            val currentBarCenter = detected.barCenter
            val now = SystemClock.elapsedRealtime()

            if (dreambreaker) {
                val first = dreambreakerFirstBarX
                if (first == null) {
                    dreambreakerFirstBarX = currentBarCenter
                } else if (
                    dreambreakerMovementAt == 0L &&
                    abs(currentBarCenter - first) > max(6f, frame.height * 0.0083f)
                ) {
                    dreambreakerMovementAt = now
                }
            }

            val elapsedSinceSample = (
                (now - lastSampleAt).coerceIn(25L, 250L)
            ) / 1000f

            val oldBar = lastBarCenter
            if (oldBar != null && elapsedSinceSample > 0f) {
                val rawBarVelocity =
                    (currentBarCenter - oldBar) / elapsedSinceSample
                val smoothing = config.velocitySmoothing.coerceIn(0.01f, 0.95f)
                barVelocity = smoothing * rawBarVelocity + (1f - smoothing) * barVelocity
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
                    fishVelocity = 0f
                } else if (previousFishX != null && gapSinceFish >= 25L) {
                    val fishDt = gapSinceFish.coerceIn(25L, FISH_STUN_GRACE_MS) / 1000f
                    val rawFishVelocity = (freshFishX - previousFishX) / fishDt
                    val smoothing = if (gapSinceFish > 180L) 0.28f else
                        config.velocitySmoothing.coerceIn(0.01f, 0.95f)
                    fishVelocity = smoothing * rawFishVelocity +
                        (1f - smoothing) * fishVelocity
                }

                lastFishX = freshFishX
                lastFishObservedAt = now
            } else if (
                lastFishX != null &&
                lastFishObservedAt > 0L &&
                now - lastFishObservedAt <= FISH_STUN_GRACE_MS
            ) {
                // Краткий stun/VFX не считаем потерей рыбы.
                fishVelocity *= 0.20f
            }

            val effectiveFishX = when {
                freshFishX != null -> freshFishX
                lastFishX != null && lastFishObservedAt > 0L &&
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
                lastBarCenter = currentBarCenter
                lastSampleAt = now
                Thread.sleep(LOOP_SLEEP_MS)
                continue
            }

            noFishStatusAt = 0L

            val error = effectiveFishX - currentBarCenter
            val relativeVelocity = fishVelocity - barVelocity

            val reactionSeconds = when (config.controlMode) {
                RodProfiles.ControlMode.PRECISION -> 0.045f
                RodProfiles.ControlMode.LINES -> 0.060f
                else -> 0.075f
            } * config.stoppingDistanceMultiplier.coerceIn(0.5f, 5f) / 3f

            // Kp/Kd из профиля управляют вкладом ошибки и скорости.
            val proportionalFactor = (config.kp / 0.5f).coerceIn(0.25f, 2.5f)
            val derivativeFactor = if (config.kd <= 0f) 0f else
                (config.kd / 0.3f).coerceIn(0f, 2.5f)
            var predictedError = error * proportionalFactor +
                relativeVelocity * reactionSeconds * derivativeFactor

            // Dreambreaker меняет направление управления примерно через 1.5 секунды.
            val reverseActive = dreambreaker && (
                (dreambreakerMovementAt > 0L &&
                    now - dreambreakerMovementAt >= config.dreambreakerReverseDelayMs) ||
                    now - dreambreakerSeenAt >= 3_100L
                )
            if (reverseActive) predictedError = -predictedError

            val centerDeadZone = when (config.controlMode) {
                RodProfiles.ControlMode.PRECISION -> max(
                    1f,
                    min(currentBarWidth * 0.10f, detected.trackWidth * 0.003f)
                )
                RodProfiles.ControlMode.LINES -> max(
                    2f,
                    min(currentBarWidth * 0.20f, detected.trackWidth * 0.006f)
                )
                else -> max(
                    1f,
                    min(currentBarWidth * 0.16f, detected.trackWidth * 0.004f)
                )
            }

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
                    // Рыба правее — удерживаем. Для Dreambreaker направление уже перевёрнуто.
                    predictedError > centerDeadZone -> {
                        val halfBar = max(4f, currentBarWidth / 2f)
                        val urgency = predictedError / halfBar
                        val baseHoldMs = when {
                            urgency >= 1.35f -> CONTROL_HOLD_LONG_MS
                            urgency >= 0.70f -> CONTROL_HOLD_MEDIUM_MS
                            else -> CONTROL_HOLD_SHORT_MS
                        }

                        val widthFactor =
                            (currentBarWidth / 34f).coerceIn(0.45f, 1f)
                        val calculatedHold = (
                            baseHoldMs * widthFactor
                        ).toLong().coerceIn(40L, CONTROL_HOLD_LONG_MS)
                        val holdMs = if (requiem) {
                            max(calculatedHold, config.requiemMinHoldMs)
                        } else {
                            calculatedHold
                        }

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

                        if (requiem && !stopFlag.get()) {
                            sleepCancellable(config.requiemMinReleaseMs, stopFlag)
                        }
                    }

                    // Короткий click возле центра компенсирует естественный дрейф полосы.
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

                        if (driftAllowsClick && now - lastClickAt >= clickCooldown) {
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

                    // Рыба левее — отпускаем. Игра сама смещает полосу влево.
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
                val rodExtra = when {
                    dreambreaker && reverseActive -> " · REVERSE"
                    dreambreaker -> " · REVERSE IN ${(config.dreambreakerReverseDelayMs - (now - started)).coerceAtLeast(0L)}ms"
                    requiem -> " · HOLD/RELEASE ${config.requiemMinHoldMs}/${config.requiemMinReleaseMs}ms"
                    else -> ""
                }
                val fishState = if (freshFishX == null) " · STUN/VFX" else ""
                onStatus(
                    "РЫБА $fishPercent · BAR $barPercent · ${currentBarWidth}px · $action$rodExtra$fishState"
                )
                lastStatusAt = now
            }

            lastBarCenter = currentBarCenter
            lastSampleAt = now
        }

        return ReelEnd.TIMEOUT
    }

    private fun handleTwigPrompt(
        capture: ScreenCaptureService,
        accessibility: MacroAccessibilityService,
        initialFrame: CapturedFrame,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ) {
        var frame = initialFrame
        val prompt = RodMechanicVision.detectTwigPrompt(frame) ?: return
        val iconY = prompt.iconY.toFloat()
        val attempts = listOf(
            prompt.leftFishX to iconY,
            prompt.rightFishX to iconY,
            prompt.leftFishX to prompt.labelY.toFloat(),
            prompt.rightFishX to prompt.labelY.toFloat()
        )
        onStatus("SPLITBRANCH TWIG · найден выбор рыбы")
        for ((x, y) in attempts) {
            if (stopFlag.get()) return
            if (x !in 2..(frame.width - 3) || y !in 2f..(frame.height - 3).toFloat()) continue
            accessibility.tap(x.toFloat(), y, 45L, stopFlag)
            sleepCancellable(260L, stopFlag)
            if (stopFlag.get()) return
            frame = freshFrame(capture, stopFlag)
            if (RodMechanicVision.detectTwigPrompt(frame) == null) {
                onStatus("SPLITBRANCH TWIG · выбор подтверждён")
                return
            }
        }
        onStatus("SPLITBRANCH TWIG · prompt остался, повторю проверку")
    }

    private fun playTranquilityMinigame(
        capture: ScreenCaptureService,
        accessibility: MacroAccessibilityService,
        config: Config,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ) {
        val tracker = RodMechanicVision.TranquilityTracker(config.maniaLeadMs)
        val started = SystemClock.elapsedRealtime()
        var lastSequence = -1L
        var lastLiveAt = started
        var lastStatusAt = 0L
        onStatus("TRANQUILITY · слежение за четырьмя дорожками")

        while (!stopFlag.get() && SystemClock.elapsedRealtime() - started < config.reelTimeoutMs) {
            val frame = freshFrame(capture, stopFlag)
            if (frame.sequence == lastSequence) {
                Thread.sleep(20L)
                continue
            }
            lastSequence = frame.sequence
            val now = SystemClock.elapsedRealtime()
            val taps = tracker.update(frame, now)
            if (taps.isNotEmpty()) lastLiveAt = now
            for (tap in taps) {
                if (stopFlag.get()) return
                if (!capture.isCaptureReady(FRAME_MAX_AGE_MS) ||
                    capture.frameWidth() != frame.width || capture.frameHeight() != frame.height) continue
                val clicked = accessibility.tap(
                    tap.x.toFloat(), tap.y.toFloat(), 45L, stopFlag
                )
                if (clicked) {
                    onStatus("TRANQUILITY · дорожка ${tap.lane + 1} · ${tap.timeToHitMs}мс до линии")
                    lastLiveAt = now
                }
            }
            if (now - lastStatusAt >= 700L) {
                onStatus("TRANQUILITY MANIA · дорожки обработаны · ${taps.size} нажатий")
                lastStatusAt = now
            }
            val ringVisible = RodMechanicVision.detectManiaRings(frame)
            if (ringVisible) lastLiveAt = now
            if (!ringVisible && !tracker.hasLiveNotes(now) && now - lastLiveAt > 700L) {
                onStatus("TRANQUILITY · Mania завершена")
                return
            }
            Thread.sleep(20L)
        }
        if (!stopFlag.get()) onStatus("TRANQUILITY · тайм-аут Mania")
    }

    private fun playLullabyMinigame(
        capture: ScreenCaptureService,
        accessibility: MacroAccessibilityService,
        config: Config,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ) {
        val tracker = RodMechanicVision.LullabyTracker(
            config.lullRadiusPct, config.lullPivotUpPx,
            config.lullLightLum, config.lullArmDelta,
            config.lullTriggerPct, config.lullClearMs,
            config.lullMinDegrees
        )
        val started = SystemClock.elapsedRealtime()
        var lastSequence = -1L
        var activeSince = 0L
        var goneFrames = 0
        var lastStatusAt = 0L
        onStatus("LULLABY · калибрую радиальное кольцо")

        while (!stopFlag.get() && SystemClock.elapsedRealtime() - started < config.reelTimeoutMs) {
            val frame = freshFrame(capture, stopFlag)
            if (frame.sequence == lastSequence) {
                Thread.sleep(25L)
                continue
            }
            lastSequence = frame.sequence
            val centerY = (frame.height * 0.843f).roundToInt()
            val visible = RodMechanicVision.detectLullabyRing(
                frame, centerY, config.lullRadiusPct
            )
            if (visible) {
                if (activeSince == 0L) activeSince = SystemClock.elapsedRealtime()
                goneFrames = 0
            } else if (activeSince > 0L) {
                goneFrames++
            }

            val sectorTap = tracker.update(frame, centerY)
            if (sectorTap != null) {
                val clicked = accessibility.tap(
                    sectorTap.x.toFloat(),
                    sectorTap.y.toFloat(),
                    45L,
                    stopFlag
                )
                if (clicked) {
                    onStatus("LULLABY · световой сектор ${sectorTap.sector + 1} активирован")
                }
            }
            if (goneFrames >= 25) {
                onStatus("LULLABY · все световые сектора исчезли")
                return
            }
            if (SystemClock.elapsedRealtime() - lastStatusAt >= 800L) {
                onStatus(
                    if (tracker.calibrated()) "LULLABY · кольцо откалибровано" else "LULLABY · калибровка ${min(26, (SystemClock.elapsedRealtime() - started) / 100)} / 26"
                )
                lastStatusAt = SystemClock.elapsedRealtime()
            }
            Thread.sleep(25L)
        }
        if (!stopFlag.get()) onStatus("LULLABY · тайм-аут мини-игры")
    }

    private fun isSameBellonaObservation(
        first: RodMechanicVision.BellonaObservation,
        second: RodMechanicVision.BellonaObservation,
        frame: CapturedFrame
    ): Boolean {
        fun sameSide(a: RodMechanicVision.BellonaSide?, b: RodMechanicVision.BellonaSide?): Boolean {
            if (a == null || b == null) return a == null && b == null
            val centerA = (a.barLeft + a.barRight) / 2
            val centerB = (b.barLeft + b.barRight) / 2
            return abs(centerA - centerB) <= max(24, (frame.width * 0.035f).toInt())
        }
        return sameSide(first.left, second.left) && sameSide(first.right, second.right)
    }

    /**
     * Bellona has independent left/right bars. Android receives both contacts
     * inside one multi-stroke Accessibility gesture, so the two sides can be
     * pulsed concurrently rather than collapsed into one fish target.
     */
    private fun playBellonaMinigame(
        capture: ScreenCaptureService,
        accessibility: MacroAccessibilityService,
        config: Config,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ): ReelEnd {
        val started = SystemClock.elapsedRealtime()
        var lastSequence = -1L
        var lastSeenAt = started
        var previousAt = started
        var previousFish = arrayOfNulls<Int>(2)
        var previousBar = arrayOfNulls<Float>(2)
        var fishVelocity = FloatArray(2)
        var barVelocity = FloatArray(2)
        var accumulators = FloatArray(2) { 0.463f }
        var lastStatusAt = 0L

        fun sideDecision(
            index: Int,
            side: RodMechanicVision.BellonaSide?,
            now: Long
        ): Boolean? {
            side ?: return null
            val fish = side.fishX ?: return null
            val bar = side.barCenter
            val dt = ((now - previousAt).coerceIn(25L, 250L)) / 1000f
            val oldFish = previousFish[index]
            val oldBar = previousBar[index]
            if (oldFish != null && dt > 0f) {
                val raw = (fish - oldFish) / dt
                fishVelocity[index] = 0.60f * raw + 0.40f * fishVelocity[index]
            }
            if (oldBar != null && dt > 0f) {
                val raw = (bar - oldBar) / dt
                barVelocity[index] = 0.60f * raw + 0.40f * barVelocity[index]
            }
            previousFish[index] = fish
            previousBar[index] = bar

            // Bellona AHK uses an independent PD accumulator for each half.
            val error = bar - fish
            val relativeVelocity = barVelocity[index] - fishVelocity[index]
            val resolutionScale = frameScale(capture)
            val centerError = abs(error)
            val centerZone = 36f * resolutionScale
            val velocityZone = 120f * resolutionScale
            if (centerError <= centerZone && abs(relativeVelocity) <= velocityZone) {
                val kp = 0.0122f / resolutionScale
                val kd = 0.00488f / resolutionScale
                val duty = (0.463f - kp * error - kd * relativeVelocity).coerceIn(0f, 1f)
                accumulators[index] += duty
                if (accumulators[index] >= 1f) {
                    accumulators[index] -= 1f
                    return true
                }
                return false
            }
            return (error + relativeVelocity * 0.5f) < 0f
        }

        onStatus("BELLONA · независимое управление левой и правой зонами")
        while (!stopFlag.get() && SystemClock.elapsedRealtime() - started < config.reelTimeoutMs) {
            val frame = freshFrame(capture, stopFlag)
            if (frame.sequence == lastSequence) {
                Thread.sleep(20L)
                continue
            }
            lastSequence = frame.sequence
            val now = SystemClock.elapsedRealtime()
            val current = RodMechanicVision.detectBellona(frame)
            if (current == null) {
                if (now - lastSeenAt >= REEL_BAR_MISSING_GRACE_MS) return ReelEnd.BAR_DISAPPEARED
                Thread.sleep(25L)
                continue
            }
            lastSeenAt = now

            val leftWant = sideDecision(0, current.left, now)
            val rightWant = sideDecision(1, current.right, now)
            val points = ArrayList<android.graphics.PointF>(2)
            if (leftWant == true) {
                points.add(android.graphics.PointF(frame.width * 0.25f, frame.height * 0.50f))
            }
            if (rightWant == true) {
                points.add(android.graphics.PointF(frame.width * 0.75f, frame.height * 0.50f))
            }
            val geometryCurrent = capture.isCaptureReady(FRAME_MAX_AGE_MS) &&
                capture.frameWidth() == frame.width && capture.frameHeight() == frame.height
            if (geometryCurrent && points.isNotEmpty()) {
                val pressed = accessibility.pressMultiTouch(points, 55L, stopFlag)
                if (!pressed && !stopFlag.get()) {
                    onStatus("BELLONA · Android не подтвердил многоточечный жест")
                }
            }
            if (now - lastStatusAt >= 500L) {
                onStatus(
                    "BELLONA · L=${current.left?.fishX ?: -1}/${current.left?.barCenter?.toInt() ?: -1}" +
                        " · R=${current.right?.fishX ?: -1}/${current.right?.barCenter?.toInt() ?: -1}" +
                        " · пальцы=${points.size}"
                )
                lastStatusAt = now
            }
            previousAt = now
            Thread.sleep(15L)
        }
        return ReelEnd.TIMEOUT
    }

    private fun frameScale(capture: ScreenCaptureService): Float =
        (capture.frameWidth().toFloat() / 2560f).coerceIn(0.45f, 1.5f)

    private fun isSameReelTrack(
        reference: ReelObservation,
        candidate: ReelObservation,
        frame: CapturedFrame
    ): Boolean {
        val yTolerance = max(55, (frame.height * 0.05f).toInt())
        val centerTolerance = max(55, (frame.width * 0.10f).toInt())
        val oldCenter = (reference.trackLeft + reference.trackRight) / 2
        val newCenter = (candidate.trackLeft + candidate.trackRight) / 2
        return abs(reference.centerY - candidate.centerY) <= yTolerance &&
            abs(oldCenter - newCenter) <= centerTolerance &&
            candidate.confidence >= 0.34f
    }

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
        var firstSeenAt = 0L
        var lastBoardAt = 0L
        var nextIndex = 0

        onStatus("STELLARWAVE · ищу 12 знаков Zodiac")
        while (!stopFlag.get() && SystemClock.elapsedRealtime() < deadline && nextIndex < 12) {
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
                if (firstSeenAt == 0L && now - started >= ZODIAC_DISCOVERY_TIMEOUT_MS) {
                    onStatus("STELLARWAVE · Zodiac не найден; без случайных кликов")
                    return
                }
                if (firstSeenAt > 0L && now - lastBoardAt > 1_100L) {
                    onStatus("STELLARWAVE · Zodiac исчез · ${nextIndex}/12")
                    return
                }
                Thread.sleep(40L)
                continue
            }
            if (firstSeenAt == 0L) firstSeenAt = now
            lastBoardAt = now
            val key = board.stableKey()
            if (key == lastBoardKey) stableFrames++ else {
                lastBoardKey = key
                stableFrames = 1
            }
            if (stableFrames < 2) {
                Thread.sleep(35L)
                continue
            }

            val match = ZodiacVision.findMatch(board, nextIndex)
            if (match == null) {
                onStatus("STELLARWAVE · знак ${nextIndex + 1}/12 не распознан уверенно")
                return
            }
            val geometryCurrent = capture.isCaptureReady(FRAME_MAX_AGE_MS) &&
                capture.frameWidth() == frame.width &&
                capture.frameHeight() == frame.height
            if (!geometryCurrent) continue

            val duration = if (config.zodiacFastClicks) 45L else 65L
            var clicked = accessibility.tap(
                match.glyph.centerX.toFloat(),
                match.glyph.centerY.toFloat(), duration, stopFlag
            )
            if (!clicked && config.zodiacRetryClicks && !stopFlag.get()) {
                sleepCancellable(120L, stopFlag)
                clicked = accessibility.tap(
                    match.glyph.centerX.toFloat(),
                    match.glyph.centerY.toFloat(), duration, stopFlag
                )
            }
            if (stopFlag.get()) return
            if (!clicked) {
                onStatus("STELLARWAVE · Android не подтвердил клик ${nextIndex + 1}")
                return
            }
            nextIndex++
            onStatus("STELLARWAVE ZODIAC · $nextIndex/12 · ${(match.score * 100).toInt()}%")
            stableFrames = 0
            lastBoardKey = null
            val delay = if (config.zodiacFastClicks) 55L else 105L
            sleepCancellable(max(delay, ZODIAC_CLICK_VERIFY_DELAY_MS), stopFlag)
        }
        if (!stopFlag.get() && nextIndex == 12) {
            onStatus("STELLARWAVE · все 12 знаков обработаны")
        }
    }

    private fun freshFrame(
        capture: ScreenCaptureService,
        stopFlag: AtomicBoolean
    ): CapturedFrame {
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (!stopFlag.get() && SystemClock.elapsedRealtime() < deadline) {
            val frame = capture.snapshot()
            if (frame != null && capture.isCaptureReady(FRAME_MAX_AGE_MS)) return frame
            Thread.sleep(45L)
        }
        if (stopFlag.get()) throw InterruptedException("Stopped")
        throw IllegalStateException("Кадры экрана зависли или устарели")
    }

    private fun positionPct(value: Int, left: Int, right: Int): String {
        if (right <= left) return "?"
        val pct = ((value - left) * 100f / (right - left)).toInt().coerceIn(0, 100)
        return "$pct%"
    }

    private fun sleepCancellable(millis: Long, stopFlag: AtomicBoolean) {
        var remaining = millis
        while (remaining > 0L && !stopFlag.get()) {
            val part = min(remaining, 100L)
            Thread.sleep(part)
            remaining -= part
        }
    }
}
