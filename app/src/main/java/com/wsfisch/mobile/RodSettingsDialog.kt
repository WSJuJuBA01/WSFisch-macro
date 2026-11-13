package com.wsfisch.mobile

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object RodSettingsDialog {

    private const val PREFS = "mobile_macro_rod_settings"

    private data class Setting(
        val key: String,
        val title: String,
        val defaultValue: String,
        val numeric: Boolean = true
    )

    private val castSettings = listOf(
        Setting("CastXPercent", "Положение заброса X (%)", "50"),
        Setting("CastYPercent", "Положение заброса Y (%)", "50"),
        Setting("CastHoldMs", "Удержание заброса (мс)", "1000"),
        Setting("WaitForBobberMs", "Ожидание поплавка (мс)", "1000"),
        Setting("ShakeTimeoutMs", "Тайм-аут поиска поклёвки (мс)", "35000"),
        Setting("ReelTimeoutMs", "Тайм-аут мини-игры (мс)", "120000"),
        Setting("RecastDelayMs", "Пауза между забросами (мс)", "1000")
    )

    private val reelSettings = listOf(
        Setting("ReelMinHold", "Requiem: минимальное удержание (мс)", "200"),
        Setting("ReelMinRelease", "Requiem: минимальное отпускание (мс)", "200"),
        Setting("ManiaLead", "Tranquility: упреждение Mania (мс)", "45"),
        Setting("NoteHandoff", "Pinions: передача цели (px)", "50"),
        Setting("LullRadiusPct", "Lullaby: радиус поиска (%)", "16"),
        Setting("LullPivotUp", "Lullaby: смещение центра (%)", "26"),
        Setting("LullLightLum", "Lullaby: яркость света", "90"),
        Setting("LullArmDelta", "Lullaby: разница яркости", "45"),
        Setting("LullTrigPct", "Lullaby: порог срабатывания (%)", "10"),
        Setting("LullClearMs", "Lullaby: задержка очистки (мс)", "100"),
        Setting("LullMinDeg", "Lullaby: минимальный угол", "4")
    )

    private val tuningSettings = listOf(
        Setting("StableRightMultiplier", "Stable: правый множитель", "2.1"),
        Setting("StableRightDivision", "Stable: правое деление", "1.4"),
        Setting("StableLeftMultiplier", "Stable: левый множитель", "1.1"),
        Setting("StableLeftDivision", "Stable: левое деление", "1.0"),
        Setting("UnstableRightMultiplier", "Unstable: правый множитель", "2.4"),
        Setting("UnstableRightDivision", "Unstable: правое деление", "1.5"),
        Setting("UnstableLeftMultiplier", "Unstable: левый множитель", "1.3"),
        Setting("UnstableLeftDivision", "Unstable: левое деление", "1.1"),
        Setting("RightAnkleBreakMultiplier", "Right ankle break", "1"),
        Setting("LeftAnkleBreakMultiplier", "Left ankle break", "0.9"),
        Setting("Kp", "Precision: Kp", "0.5"),
        Setting("Kd", "Precision: Kd", "0.3"),
        Setting("VelocitySmoothing", "Сглаживание скорости", "0.2"),
        Setting("StoppingDistanceMultiplier", "Stopping distance", "3.0")
    )

    private val colorSettings = listOf(
        Setting("TryhardBarColors", "Tryhard: цвет полосы", "", false),
        Setting("TryhardFishColors", "Tryhard: цвет рыбы", "", false),
        Setting("TryhardTrackColors", "Tryhard: цвет дорожки", "", false),

        Setting("CastboundBarColors", "Castbound: цвет полосы", "", false),
        Setting("CastboundFishColors", "Castbound: цвет меча", "0x5522A3", false),
        Setting("CastboundTrackColors", "Castbound: цвет дорожки", "", false),

        Setting("MiguFishColors", "MiguRod: цвет рыбы", "", false),

        Setting("VerdantBarColors", "Verdant Oath: цвет зелёной зоны", "", false),
        Setting("VerdantFishColors", "Verdant Oath: цвет рыбы", "", false),

        Setting("StellarBarColors", "Stellarwave: цвет полосы", "0x55446D", false),
        Setting("StellarFishColors", "Stellarwave: цвет рыбы", "0xAFDAE8", false),
        Setting("StellarHeadColors", "Stellarwave: цвет головы", "0xFFFFFF", false)
    )

    private val toleranceSettings = listOf(
        Setting("TryhardBarTol", "Tryhard: допуск полосы", "20"),
        Setting("TryhardFishTol", "Tryhard: допуск рыбы", "14"),
        Setting("TryhardTrackTol", "Tryhard: допуск дорожки", "20"),

        Setting("CastboundBarTol", "Castbound: допуск полосы", "20"),
        Setting("CastboundFishTol", "Castbound: допуск меча", "20"),
        Setting("CastboundTrackTol", "Castbound: допуск дорожки", "20"),

        Setting("VerdantBarTol", "Verdant: допуск зелёной зоны", "20"),
        Setting("VerdantFishTol", "Verdant: допуск рыбы", "20"),

        Setting("MiguFishTol", "MiguRod: допуск рыбы", "20"),

        Setting("StellarBarTol", "Stellarwave: допуск полосы", "26"),
        Setting("StellarFishTol", "Stellarwave: допуск рыбы", "22"),
        Setting("StellarHeadTol", "Stellarwave: допуск головы", "30")
    )

    fun show(
        activity: Activity,
        onSaved: () -> Unit = {}
    ) {
        val prefs = activity.getSharedPreferences(
            PREFS,
            Activity.MODE_PRIVATE
        )

        val density = activity.resources.displayMetrics.density

        fun dp(value: Int): Int =
            (value * density).toInt()

        val background = Color.rgb(22, 26, 34)
        val mainText = Color.rgb(239, 243, 249)
        val mutedText = Color.rgb(165, 176, 193)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(background)
        }

        fun addTitle(value: String) {
            root.addView(
                TextView(activity).apply {
                    text = value
                    textSize = 17f
                    setTextColor(mainText)
                    setPadding(0, dp(16), 0, dp(7))
                }
            )
        }

        fun addHint(value: String) {
            root.addView(
                TextView(activity).apply {
                    text = value
                    textSize = 11f
                    setTextColor(mutedText)
                    setPadding(0, dp(5), 0, dp(8))
                }
            )
        }

        fun addDivider() {
            root.addView(
                TextView(activity).apply {
                    setBackgroundColor(Color.rgb(51, 58, 70))
                    height = dp(1)
                },
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        }

        val rodButton = TextView(activity).apply {
            textSize = 16f
            setTextColor(mainText)
            setPadding(0, dp(12), 0, dp(12))
        }

        fun updateRodButton() {
            val selected = prefs.getString("SpecialRod", "None") ?: "None"
            rodButton.text = "Удочка: $selected\nНажми, чтобы изменить"
        }

        updateRodButton()

        rodButton.setOnClickListener {
            val names = RodProfiles.names()
            val current = prefs.getString("SpecialRod", "None") ?: "None"
            val selectedIndex = names.indexOf(
                RodProfiles.canonicalize(current)
            ).coerceAtLeast(0)

            AlertDialog.Builder(activity)
                .setTitle("Выбор удочки")
                .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                    prefs.edit()
                        .putString("SpecialRod", names[which])
                        .apply()

                    updateRodButton()
                    dialog.dismiss()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        root.addView(rodButton)

        val controlModes = arrayOf(
            "Adaptive",
            "Precision",
            "Lines"
        )

        var selectedControlMode =
            prefs.getString("ControlMode", "Adaptive") ?: "Adaptive"

        val controlButton = TextView(activity).apply {
            textSize = 14f
            setTextColor(mainText)
            setPadding(0, dp(8), 0, dp(12))
        }

        fun updateControlButton() {
            controlButton.text =
                "Режим управления: $selectedControlMode\nНажми, чтобы изменить"
        }

        updateControlButton()

        controlButton.setOnClickListener {
            val index = controlModes.indexOf(selectedControlMode)
                .coerceAtLeast(0)

            AlertDialog.Builder(activity)
                .setTitle("Режим управления")
                .setSingleChoiceItems(controlModes, index) { dialog, which ->
                    selectedControlMode = controlModes[which]
                    updateControlButton()
                    dialog.dismiss()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        root.addView(controlButton)
        addDivider()

        val halibut = CheckBox(activity).apply {
            text = "Halibut Harpoon: играть с маркером-восклицанием"
            setTextColor(mainText)
            isChecked = prefs.getBoolean("HalibutPlayMinigame", true)
        }

        val stellarClickSigns = CheckBox(activity).apply {
            text = "Stellarwave: нажимать знаки Zodiac"
            setTextColor(mainText)
            isChecked = prefs.getBoolean("SwClickSigns", true)
        }

        val stellarFastClicks = CheckBox(activity).apply {
            text = "Stellarwave: быстрые нажатия"
            setTextColor(mainText)
            isChecked = prefs.getBoolean("SwFastClicks", false)
        }

        val stellarRetryClicks = CheckBox(activity).apply {
            text = "Stellarwave: повторять неподтверждённые клики"
            setTextColor(mainText)
            isChecked = prefs.getBoolean("SwRetryClicks", false)
        }

        val remembranceDeparted = CheckBox(activity).apply {
            text = "Remembrance: режим Departed (выключено = Living)"
            setTextColor(mainText)
            isChecked = prefs.getString("RemembranceMode", "Living") == "Departed"
        }

        root.addView(halibut)
        root.addView(stellarClickSigns)
        root.addView(stellarFastClicks)
        root.addView(stellarRetryClicks)
        root.addView(remembranceDeparted)

        val editors = linkedMapOf<String, EditText>()
        val settingDefinitions = linkedMapOf<String, Setting>()

        fun addSettings(
            title: String,
            settings: List<Setting>
        ) {
            addTitle(title)

            settings.forEach { setting ->
                settingDefinitions[setting.key] = setting

                root.addView(
                    TextView(activity).apply {
                        text = setting.title
                        textSize = 12f
                        setTextColor(mutedText)
                        setPadding(0, dp(5), 0, 0)
                    }
                )

                val editor = EditText(activity).apply {
                    inputType = if (setting.numeric) {
                        InputType.TYPE_CLASS_NUMBER or
                            InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            InputType.TYPE_NUMBER_FLAG_SIGNED
                    } else {
                        InputType.TYPE_CLASS_TEXT
                    }

                    isSingleLine = true
                    setTextColor(mainText)
                    setHintTextColor(mutedText)
                    setHint(setting.defaultValue)

                    setText(
                        prefs.getString(
                            setting.key,
                            setting.defaultValue
                        )
                    )
                }

                editors[setting.key] = editor

                root.addView(
                    editor,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(46)
                )
            }
        }

        addSettings("ЗАБРОС И ЦИКЛ", castSettings)
        addHint(
            "X/Y — координаты удержания относительно размеров экрана. " +
                "50/50 означает центр."
        )

        addSettings("ОСОБЫЕ МЕХАНИКИ", reelSettings)
        addSettings("НАСТРОЙКА УПРАВЛЕНИЯ", tuningSettings)
        addSettings("ЦВЕТОВАЯ КАЛИБРОВКА (HEX)", colorSettings)
        addSettings("ДОПУСКИ ЦВЕТОВ", toleranceSettings)

        addTitle("АТРИБУЦИЯ")
        addHint(
            "Original project: WSFisch macro / DeepFish BETA by Yato.\n" +
                "Android Kotlin port context: WSJuJuBA01."
        )

        addTitle("СОСТОЯНИЕ ПЕРЕНОСА")
        addHint(
            "Сохранение параметров не означает, что соответствующий " +
                "детектор уже реализован. Для каждой уникальной механики " +
                "нужна отдельная обработка в FischVision и MacroRunner."
        )

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(background)
            isFillViewport = true
            addView(root)
        }

        AlertDialog.Builder(activity)
            .setTitle("Настройки WSFisch")
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->

                fun intValue(key: String): Int? =
                    editors[key]
                        ?.text
                        ?.toString()
                        ?.trim()
                        ?.toIntOrNull()

                val castX = intValue("CastXPercent")
                val castY = intValue("CastYPercent")
                val castHold = intValue("CastHoldMs")
                val waitBobber = intValue("WaitForBobberMs")
                val shakeTimeout = intValue("ShakeTimeoutMs")
                val reelTimeout = intValue("ReelTimeoutMs")
                val recastDelay = intValue("RecastDelayMs")

                val valid =
                    castX != null && castX in 0..100 &&
                    castY != null && castY in 0..100 &&
                    castHold != null && castHold in 250..5000 &&
                    waitBobber != null && waitBobber in 250..5000 &&
                    shakeTimeout != null && shakeTimeout in 10000..90000 &&
                    reelTimeout != null && reelTimeout in 30000..180000 &&
                    recastDelay != null && recastDelay in 300..5000

                if (!valid) {
                    AlertDialog.Builder(activity)
                        .setTitle("Некорректные настройки")
                        .setMessage(
                            "Проверь значения:\n\n" +
                                "X/Y: 0–100\n" +
                                "Удержание и ожидание: 250–5000 мс\n" +
                                "Поиск поклёвки: 10000–90000 мс\n" +
                                "Мини-игра: 30000–180000 мс\n" +
                                "Пауза: 300–5000 мс"
                        )
                        .setPositiveButton("OK", null)
                        .show()

                    return@setPositiveButton
                }

                val editor = prefs.edit()
                    .putString("SpecialRod", RodProfiles.canonicalize(
                        prefs.getString("SpecialRod", "None")
                    ))
                    .putString("ControlMode", selectedControlMode)
                    .putBoolean("HalibutPlayMinigame", halibut.isChecked)
                    .putBoolean("SwClickSigns", stellarClickSigns.isChecked)
                    .putBoolean("SwFastClicks", stellarFastClicks.isChecked)
                    .putBoolean("SwRetryClicks", stellarRetryClicks.isChecked)
                    .putString(
                        "RemembranceMode",
                        if (remembranceDeparted.isChecked) {
                            "Departed"
                        } else {
                            "Living"
                        }
                    )

                editors.forEach { (key, field) ->
                    val value = field.text.toString().trim()
                    if (value.isNotEmpty()) {
                        editor.putString(key, value)
                    }
                }

                editor.apply()
                onSaved()
            }
            .show()
    }
}
