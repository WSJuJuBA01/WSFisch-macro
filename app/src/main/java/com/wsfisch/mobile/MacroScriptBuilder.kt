package com.wsfisch.mobile

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Собирает сценарий из сохранённых настроек.
 *
 * Важно: текущий ScriptEngine поддерживает только Click, Sleep и Loop.
 * Поэтому это базовый тестовый цикл нажатий, а не полноценный
 * алгоритм распознавания рыбалки.
 */
object MacroScriptBuilder {

    private const val PREFS = "mobile_macro_rod_settings"

    data class Config(
        val specialRod: String,
        val halibutPlayMinigame: Boolean,
        val stellarClickSigns: Boolean,
        val stellarFastClicks: Boolean,
        val stellarRetryClicks: Boolean,
        val tryhardBarTolerance: Int,
        val castboundBarTolerance: Int,
        val stellarBarTolerance: Int
    )

    fun readConfig(context: Context): Config {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        return Config(
            specialRod = prefs.getString("SpecialRod", "None") ?: "None",
            halibutPlayMinigame = prefs.getBoolean(
                "HalibutPlayMinigame",
                true
            ),
            stellarClickSigns = prefs.getBoolean(
                "SwClickSigns",
                true
            ),
            stellarFastClicks = prefs.getBoolean(
                "SwFastClicks",
                false
            ),
            stellarRetryClicks = prefs.getBoolean(
                "SwRetryClicks",
                false
            ),
            tryhardBarTolerance = prefs.getString(
                "TryhardBarTol",
                "20"
            )?.toIntOrNull() ?: 20,
            castboundBarTolerance = prefs.getString(
                "CastboundBarTol",
                "20"
            )?.toIntOrNull() ?: 20,
            stellarBarTolerance = prefs.getString(
                "StellarBarTol",
                "26"
            )?.toIntOrNull() ?: 26
        )
    }

    private fun getScreenCenter(context: Context): Pair<Int, Int> {
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        val manager = context.getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)

        return Pair(
            metrics.widthPixels / 2,
            metrics.heightPixels / 2
        )
    }

    fun build(context: Context): String {
        val config = readConfig(context)
        val (centerX, centerY) = getScreenCenter(context)

        // Интервал зависит от выбранной удочки и её настроек.
        val clickDelay = when (config.specialRod) {
            "Stellarwave Melody" -> {
                if (config.stellarFastClicks) 120 else 250
            }

            "Tryhard" -> {
                if (config.tryhardBarTolerance < 15) 180 else 250
            }

            "Castbound" -> {
                if (config.castboundBarTolerance < 15) 180 else 250
            }

            "Halibut" -> {
                if (config.halibutPlayMinigame) 250 else 500
            }

            else -> 300
        }

        val retryDelay = if (
            config.specialRod == "Stellarwave Melody" &&
            config.stellarRetryClicks
        ) {
            150
        } else {
            300
        }

        val script = StringBuilder()

        script.appendLine("; WSFisch Mobile generated script")
        script.appendLine("; Rod: ${config.specialRod}")
        script.appendLine("; Generated automatically from saved settings")
        script.appendLine()
        script.appendLine("Loop, 1000000")
        script.appendLine("{")

        script.appendLine("    ; Basic interaction cycle")
        script.appendLine("    Click, $centerX, $centerY")
        script.appendLine("    Sleep, 1000")

        script.appendLine("    ; Repeated interaction")
        script.appendLine("    Loop, 20")
        script.appendLine("    {")
        script.appendLine("        Click, $centerX, $centerY")
        script.appendLine("        Sleep, $clickDelay")
        script.appendLine("    }")

        if (
            config.specialRod == "Stellarwave Melody" &&
            config.stellarClickSigns
        ) {
            script.appendLine()
            script.appendLine("    ; Stellarwave click-sign option")
            script.appendLine("    Click, $centerX, $centerY")
            script.appendLine("    Sleep, $retryDelay")
        }

        script.appendLine()
        script.appendLine("    ; Restart delay")
        script.appendLine("    Sleep, 1000")
        script.appendLine("}")

        return script.toString()
    }
}
