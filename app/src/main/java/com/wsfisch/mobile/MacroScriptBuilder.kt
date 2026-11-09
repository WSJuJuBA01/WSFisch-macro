package com.wsfisch.mobile

import android.content.Context

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
            specialRod = prefs.getString(
                "SpecialRod",
                "None"
            ) ?: "None",

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

    /**
     * Маркер запуска native MacroRunner.
     * Никаких автоматических кликов здесь больше нет.
     */
    fun build(context: Context): String {
        readConfig(context)
        return "WSFISCH_NATIVE_RUNNER"
    }
}
