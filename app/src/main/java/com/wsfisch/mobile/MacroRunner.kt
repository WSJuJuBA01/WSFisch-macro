package com.wsfisch.mobile

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

object MacroRunner {

    private const val PREFS = "mobile_macro_rod_settings"

    data class Config(
        val castXPercent: Int,
        val castYPercent: Int,
        val castHoldMs: Long,
        val specialRod: String
    )

    fun readConfig(context: Context): Config {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        return Config(
            castXPercent = prefs.getString(
                "CastXPercent",
                "50"
            )?.toIntOrNull() ?: 50,

            castYPercent = prefs.getString(
                "CastYPercent",
                "85"
            )?.toIntOrNull() ?: 85,

            castHoldMs = prefs.getString(
                "CastHoldMs",
                "1000"
            )?.toLongOrNull() ?: 1000L,

            specialRod = prefs.getString(
                "SpecialRod",
                "None"
            ) ?: "None"
        )
    }

    fun run(
        context: Context,
        stopFlag: AtomicBoolean,
        onStatus: (String) -> Unit
    ) {
        try {
            onStatus("Проверка управления...")

            val accessibility =
                MacroAccessibilityService.instance
                    ?: throw IllegalStateException(
                        "Включи службу управления касаниями"
                    )

            if (stopFlag.get()) return

            val capture = ScreenCaptureService.instance
                ?: throw IllegalStateException(
                    "Сначала включи захват экрана"
                )

            onStatus("Ожидание кадра экрана...")

            var attempts = 0

            while (!capture.hasFrame() && attempts < 50) {
                if (stopFlag.get()) return

                Thread.sleep(100)
                attempts++
            }

            if (!capture.hasFrame()) {
                throw IllegalStateException(
                    "Не удалось получить кадр экрана"
                )
            }

            val width = capture.frameWidth()
            val height = capture.frameHeight()

            if (width <= 0 || height <= 0) {
                throw IllegalStateException(
                    "Некорректный размер экрана"
                )
            }

            val config = readConfig(context)

            require(config.castXPercent in 0..100) {
                "Cast X должен быть от 0 до 100"
            }

            require(config.castYPercent in 0..100) {
                "Cast Y должен быть от 0 до 100"
            }

            require(config.castHoldMs in 100L..5000L) {
                "Время удержания должно быть от 100 до 5000 мс"
            }

            val x = (
                width * config.castXPercent / 100f
            ).toInt().coerceIn(1, width - 2)

            val y = (
                height * config.castYPercent / 100f
            ).toInt().coerceIn(1, height - 2)

            onStatus(
                "Удочка: ${config.specialRod}"
            )

            onStatus(
                "Точка заброса: $x, $y"
            )

            for (seconds in 3 downTo 1) {
                if (stopFlag.get()) {
                    onStatus("Остановлено")
                    return
                }

                onStatus("Заброс через $seconds...")
                Thread.sleep(1000)
            }

            if (stopFlag.get()) return

            onStatus(
                "Зажимаю кнопку заброса..."
            )

            val success = accessibility.pressAndHold(
                x = x.toFloat(),
                y = y.toFloat(),
                durationMs = config.castHoldMs,
                stopFlag = stopFlag
            )

            if (stopFlag.get()) {
                onStatus("Остановлено")
                return
            }

            if (!success) {
                throw IllegalStateException(
                    "Android не смог выполнить удержание"
                )
            }

            onStatus(
                "Кнопка отпущена. Заброс выполнен."
            )

            Thread.sleep(1000)

            if (!stopFlag.get()) {
                onStatus(
                    "Ожидание поклёвки: распознавание ещё не подключено"
                )
            }

        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            onStatus("Остановлено")
        } catch (e: Exception) {
            onStatus(
                "Ошибка: ${e.message ?: "неизвестная ошибка"}"
            )
        }
    }
}
