package com.wsfisch.mobile

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Плавающая панель управления WSFisch.
 *
 * Возможности:
 * - мониторинг захвата экрана;
 * - мониторинг службы касаний;
 * - статистика жестов;
 * - обновление статуса;
 * - перетаскивание панели;
 * - защита от повторного запуска;
 * - сворачивание подробностей;
 * - управление макросом.
 */
class OverlayControlService : Service() {

    companion object {
        const val ACTION_SHOW =
            "com.wsfisch.mobile.SHOW_OVERLAY"

        const val ACTION_STOP_MACRO =
            "com.wsfisch.mobile.STOP_MACRO"

        const val EXTRA_SCRIPT =
            "macro_script"

        private const val REFRESH_INTERVAL_MS = 100L
    }

    private lateinit var windowManager: WindowManager

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var overlay: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var macroStatusView: TextView? = null
    private var touchStatusView: TextView? = null
    private var captureStatusView: TextView? = null
    private var diagnosticsView: TextView? = null

    // Управление сворачиванием.
    private var collapseButton: Button? = null
    private var collapsed = false

    // Эти элементы скрываются при сворачивании.
    private val collapsibleViews =
        ArrayList<View>()

    private val stopFlag =
        AtomicBoolean(false)

    @Volatile
    private var running = false

    @Volatile
    private var macroThread: Thread? = null

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWindowX = 0
    private var dragStartWindowY = 0

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshSystemStatus()

            if (overlay != null) {
                mainHandler.postDelayed(
                    this,
                    REFRESH_INTERVAL_MS
                )
            }
        }
    }

    // =====================================================
    // ЦВЕТА
    // =====================================================

    private val backgroundColor =
        Color.rgb(25, 31, 42)

    private val cardColor =
        Color.rgb(35, 44, 59)

    private val accentColor =
        Color.rgb(48, 112, 245)

    private val stopColor =
        Color.rgb(76, 88, 108)

    private val textColor =
        Color.rgb(239, 243, 250)

    private val mutedColor =
        Color.rgb(166, 179, 199)

    private val successColor =
        Color.rgb(104, 220, 166)

    private val warningColor =
        Color.rgb(255, 194, 92)

    private val errorColor =
        Color.rgb(255, 112, 112)

    // =====================================================
    // ЖИЗНЕННЫЙ ЦИКЛ
    // =====================================================

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_SHOW -> {
                showOverlay()
            }

            ACTION_STOP_MACRO -> {
                stopMacro()
            }
        }

        return START_NOT_STICKY
    }

    // =====================================================
    // СОЗДАНИЕ ПАНЕЛИ
    // =====================================================

    private fun showOverlay() {

        if (overlay != null) {
            refreshSystemStatus()
            return
        }

        collapsed = false
        collapseButton = null
        collapsibleViews.clear()

        if (!android.provider.Settings.canDrawOverlays(this)) {

            Toast.makeText(
                this,
                "Разреши отображение WSFisch поверх окон",
                Toast.LENGTH_LONG
            ).show()

            stopSelf()
            return
        }

        val panel = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(11),
                dp(9),
                dp(11),
                dp(9)
            )

            background = rounded(
                backgroundColor,
                17
            )

            elevation = dp(8).toFloat()
        }

        // =================================================
        // ШАПКА
        // =================================================

        val header = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL

            setPadding(
                dp(3),
                dp(1),
                dp(1),
                dp(7)
            )

            setOnTouchListener { _, event ->
                handleDrag(event)
            }
        }

        val headerText = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            gravity = Gravity.CENTER_VERTICAL

            setOnTouchListener { _, event ->
                handleDrag(event)
            }

            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(42),
                1f
            )
        }

        val title = TextView(this).apply {

            text = "WSFISCH  /  CONTROL"

            textSize = 13f

            typeface = Typeface.DEFAULT_BOLD

            setTextColor(textColor)

            setOnTouchListener { _, event ->
                handleDrag(event)
            }
        }

        val subtitle = TextView(this).apply {

            text = "ПЕРЕТАЩИ ЗА ШАПКУ · LIVE STATUS"

            textSize = 8f

            letterSpacing = 0.08f

            setTextColor(mutedColor)

            setPadding(
                0,
                dp(2),
                0,
                0
            )

            setOnTouchListener { _, event ->
                handleDrag(event)
            }
        }

        headerText.addView(title)
        headerText.addView(subtitle)

        header.addView(headerText)

        // =================================================
        // КНОПКА СВОРАЧИВАНИЯ
        // =================================================

        val minimizeButton = makeButton(
            "−",
            cardColor,
            36,
            36
        ).apply {

            contentDescription =
                "Свернуть подробности"

            setOnClickListener {
                setOverlayCollapsed(!collapsed)
            }
        }

        collapseButton = minimizeButton

        header.addView(minimizeButton)

        // =================================================
        // КНОПКА ЗАКРЫТИЯ
        // =================================================

        val closeButton = makeButton(
            "×",
            stopColor,
            38,
            36
        )

        closeButton.contentDescription =
            "Закрыть панель WSFisch"

        closeButton.setOnClickListener {

            stopMacro()

            removeOverlay()

            stopSelf()
        }

        header.addView(closeButton)

        panel.addView(header)

        // =================================================
        // РАЗДЕЛИТЕЛЬ
        // =================================================

        val separator = View(this).apply {
            setBackgroundColor(
                Color.rgb(57, 69, 88)
            )
        }

        panel.addView(
            separator,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                bottomMargin = dp(8)
            }
        )

        collapsibleViews.add(separator)

        // =================================================
        // СТАТУС МАКРОСА
        // =================================================

        val macroCard = makeStatusCard()

        macroStatusView = TextView(this).apply {

            text = "ОЖИДАНИЕ · нажми ЗАПУСК"

            textSize = 11f

            typeface = Typeface.DEFAULT_BOLD

            setTextColor(warningColor)

            maxLines = 3

            gravity = Gravity.CENTER_VERTICAL
        }

        macroCard.addView(macroStatusView)

        panel.addView(macroCard)

        collapsibleViews.add(macroCard)

        // =================================================
        // СЛУЖБЫ
        // =================================================

        val statusRow = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER

            setPadding(
                0,
                dp(7),
                0,
                dp(2)
            )
        }

        // Служба касаний.

        val touchCard = makeStatusCard().apply {

            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(66),
                1f
            ).apply {
                marginEnd = dp(4)
            }
        }

        touchCard.addView(
            makeSmallLabel("УПРАВЛЕНИЕ КАСАНИЯМИ")
        )

        touchStatusView = makeStatusValue(
            "Проверка…"
        )

        touchCard.addView(touchStatusView)

        // Захват экрана.

        val captureCard = makeStatusCard().apply {

            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(66),
                1f
            ).apply {
                marginStart = dp(4)
            }
        }

        captureCard.addView(
            makeSmallLabel("ЗАХВАТ ЭКРАНА")
        )

        captureStatusView = makeStatusValue(
            "Проверка…"
        )

        captureCard.addView(captureStatusView)

        statusRow.addView(touchCard)
        statusRow.addView(captureCard)

        panel.addView(statusRow)

        collapsibleViews.add(statusRow)

        // =================================================
        // ДИАГНОСТИКА
        // =================================================

        diagnosticsView = TextView(this).apply {

            text = "Диагностика: ожидание данных"

            textSize = 9f

            setTextColor(mutedColor)

            maxLines = 3

            setPadding(
                dp(4),
                dp(5),
                dp(4),
                dp(7)
            )
        }

        panel.addView(diagnosticsView)

        collapsibleViews.add(diagnosticsView!!)

        // =================================================
        // КНОПКИ ЗАПУСКА И ОСТАНОВКИ
        // Эти кнопки остаются видимыми всегда.
        // =================================================

        val actions = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER
        }

        val startButton = makeButton(
            "▶  ЗАПУСК",
            accentColor,
            0,
            43
        ).apply {

            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(43),
                1f
            ).apply {
                marginEnd = dp(4)
            }

            contentDescription = "Запустить макрос"

            setOnClickListener {
                startMacro()
            }
        }

        val stopButton = makeButton(
            "■  СТОП",
            stopColor,
            0,
            43
        ).apply {

            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(43),
                1f
            ).apply {
                marginStart = dp(4)
            }

            contentDescription = "Остановить макрос"

            setOnClickListener {
                stopMacro()
            }
        }

        actions.addView(startButton)
        actions.addView(stopButton)

        panel.addView(actions)

        // =================================================
        // РУЧНОЕ ОБНОВЛЕНИЕ
        // =================================================

        val refreshButton = makeButton(
            "↻  ОБНОВИТЬ СОСТОЯНИЕ",
            cardColor,
            0,
            34
        ).apply {

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply {
                topMargin = dp(6)
            }

            textSize = 10f

            setOnClickListener {
                refreshSystemStatus()
            }
        }

        panel.addView(refreshButton)

        collapsibleViews.add(refreshButton)

        // =================================================
        // ОКНО
        // =================================================

        val params = WindowManager.LayoutParams(

            dp(292),

            WindowManager.LayoutParams.WRAP_CONTENT,

            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,

            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,

            PixelFormat.TRANSLUCENT

        ).apply {

            gravity =
                Gravity.TOP or Gravity.CENTER_HORIZONTAL

            x = 0

            y = dp(8)
        }

        try {

            windowManager.addView(
                panel,
                params
            )

            overlay = panel

            overlayParams = params

            refreshSystemStatus()

            mainHandler.removeCallbacks(
                refreshRunnable
            )

            mainHandler.postDelayed(
                refreshRunnable,
                REFRESH_INTERVAL_MS
            )

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Не удалось открыть панель: ${
                    e.message ?: e.javaClass.simpleName
                }",
                Toast.LENGTH_LONG
            ).show()

            removeOverlay()
            stopSelf()
        }
    }

    // =====================================================
    // СВОРАЧИВАНИЕ И РАЗВОРАЧИВАНИЕ
    // =====================================================

    private fun setOverlayCollapsed(value: Boolean) {

        val panel =
            overlay ?: return

        if (collapsed == value) return

        collapsed = value

        val visibility =
            if (collapsed) {
                View.GONE
            } else {
                View.VISIBLE
            }

        // Скрываем только подробности.
        // Шапка и кнопки ЗАПУСК/СТОП остаются на экране.
        collapsibleViews.forEach { view ->
            view.visibility = visibility
        }

        collapseButton?.apply {

            text =
                if (collapsed) {
                    "+"
                } else {
                    "−"
                }

            contentDescription =
                if (collapsed) {
                    "Развернуть панель"
                } else {
                    "Свернуть подробности"
                }
        }

        panel.requestLayout()

        val params = overlayParams

        if (params != null) {
            try {
                windowManager.updateViewLayout(
                    panel,
                    params
                )
            } catch (_: Exception) {
            }
        }
    }

    // =====================================================
    // ЗАПУСК МАКРОСА
    // =====================================================

    private fun startMacro() {

        if (running) {

            updateMacroStatus(
                "МАКРОС УЖЕ РАБОТАЕТ",
                warningColor
            )

            return
        }

        val touch =
            MacroAccessibilityService.instance

        if (
            touch == null ||
            !touch.diagnostics().connected
        ) {

            updateMacroStatus(
                "ОШИБКА: включи службу управления касаниями",
                errorColor
            )

            refreshSystemStatus()

            return
        }

        val capture =
            ScreenCaptureService.instance

        if (
            capture == null ||
            !capture.isCaptureReady()
        ) {

            updateMacroStatus(
                "ОШИБКА: нет свежих кадров экрана",
                errorColor
            )

            refreshSystemStatus()

            return
        }

        stopFlag.set(false)

        running = true

        updateMacroStatus(
            "ЗАПУСК · проверяю игру и настройки…",
            successColor
        )

        val worker = thread(
            start = false,
            name = "WSFischMacroRunner"
        ) {

            try {

                MacroRunner.run(

                    context = applicationContext,

                    stopFlag = stopFlag,

                    onStatus = { message ->

                        updateMacroStatus(message)
                    }
                )

            } catch (e: InterruptedException) {

                Thread.currentThread().interrupt()

                updateMacroStatus(
                    "ОСТАНОВЛЕНО",
                    warningColor
                )

            } catch (e: Exception) {

                updateMacroStatus(
                    "ОШИБКА: ${
                        e.message ?: e.javaClass.simpleName
                    }",
                    errorColor
                )

            } finally {

                running = false

                macroThread = null

                if (stopFlag.get()) {

                    updateMacroStatus(
                        "МАКРОС ОСТАНОВЛЕН",
                        warningColor
                    )
                }

                refreshSystemStatus()
            }
        }

        macroThread = worker

        worker.start()

        refreshSystemStatus()
    }

    // =====================================================
    // ОСТАНОВКА МАКРОСА
    // =====================================================

    private fun stopMacro() {

        val worker = macroThread

        if (!running || worker == null) {

            stopFlag.set(true)

            updateMacroStatus(
                "МАКРОС НЕ ЗАПУЩЕН",
                mutedColor
            )

            return
        }

        stopFlag.set(true)

        worker.interrupt()

        updateMacroStatus(
            "ОСТАНОВКА · завершаю текущую операцию…",
            warningColor
        )
    }

    // =====================================================
    // ОБНОВЛЕНИЕ СИСТЕМНОГО СОСТОЯНИЯ
    // =====================================================

    private fun refreshSystemStatus() {

        if (overlay == null) return

        // Состояние службы касаний.

        val touch =
            MacroAccessibilityService.instance

        if (touch == null) {

            touchStatusView?.text =
                "ВЫКЛЮЧЕНА"

            touchStatusView?.setTextColor(
                errorColor
            )

            diagnosticsView?.text =
                "Служба касаний не подключена · открой настройки специальных возможностей"

        } else {

            val data = touch.diagnostics()

            touchStatusView?.text =
                if (data.connected) {
                    "ПОДКЛЮЧЕНА"
                } else {
                    "НЕ ГОТОВА"
                }

            touchStatusView?.setTextColor(
                if (data.connected) {
                    successColor
                } else {
                    errorColor
                }
            )

            diagnosticsView?.text = buildString {

                append(
                    "Жесты: ${data.completed}/${data.requests} выполнено"
                )

                append(
                    " · отменено ${data.cancelled}"
                )

                append(
                    " · отклонено ${data.rejected}"
                )

                if (data.activeGesture != null) {

                    append(
                        "\nСейчас: ${data.activeGesture}"
                    )

                } else {

                    append(
                        "\nПоследнее: ${data.lastResult}"
                    )
                }
            }
        }

        // Состояние захвата экрана.

        val capture =
            ScreenCaptureService.instance

        if (capture == null) {

            captureStatusView?.text =
                "НЕ ЗАПУЩЕН"

            captureStatusView?.setTextColor(
                errorColor
            )

        } else {

            val ready =
                capture.isCaptureReady()

            val age =
                capture.frameAgeMs()

            val dimensions =
                "${capture.frameWidth()}×${capture.frameHeight()}"

            captureStatusView?.text = when {

                ready -> {
                    "АКТИВЕН · $dimensions\nКадр ${age}мс"
                }

                !capture.hasFrame() -> {
                    "ОЖИДАНИЕ КАДРА"
                }

                else -> {
                    "УСТАРЕЛ · ${age}мс"
                }
            }

            captureStatusView?.setTextColor(
                if (ready) {
                    successColor
                } else {
                    warningColor
                }
            )
        }
    }

    // =====================================================
    // ОБНОВЛЕНИЕ ТЕКСТА МАКРОСА
    // =====================================================

    private fun updateMacroStatus(
        message: String,
        color: Int? = null
    ) {

        mainHandler.post {

            macroStatusView?.text =
                message.take(150)

            if (color != null) {

                macroStatusView?.setTextColor(
                    color
                )

            } else {

                val lowered =
                    message.lowercase()

                val statusColor = when {

                    "ошибка" in lowered ||
                        "отклон" in lowered -> {
                        errorColor
                    }

                    "останов" in lowered ||
                        "тайм-аут" in lowered -> {
                        warningColor
                    }

                    "готово" in lowered ||
                        "запуск" in lowered ||
                        "мини-игра" in lowered -> {
                        successColor
                    }

                    else -> {
                        textColor
                    }
                }

                macroStatusView?.setTextColor(
                    statusColor
                )
            }
        }
    }

    // =====================================================
    // ПЕРЕТАСКИВАНИЕ ПАНЕЛИ
    // =====================================================

    private fun handleDrag(
        event: MotionEvent
    ): Boolean {

        val params =
            overlayParams ?: return false

        val panel =
            overlay ?: return false

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                dragStartRawX = event.rawX

                dragStartRawY = event.rawY

                dragStartWindowX = params.x

                dragStartWindowY = params.y

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                params.x =
                    dragStartWindowX +
                        (event.rawX - dragStartRawX).toInt()

                params.y =
                    dragStartWindowY +
                        (event.rawY - dragStartRawY).toInt()

                try {

                    windowManager.updateViewLayout(
                        panel,
                        params
                    )

                } catch (_: Exception) {
                }

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                return true
            }
        }

        return false
    }

    // =====================================================
    // UI-КОМПОНЕНТЫ
    // =====================================================

    private fun makeStatusCard(): LinearLayout {

        return LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            gravity = Gravity.CENTER_VERTICAL

            setPadding(
                dp(9),
                dp(7),
                dp(9),
                dp(7)
            )

            background = rounded(
                cardColor,
                11
            )
        }
    }

    private fun makeSmallLabel(
        value: String
    ): TextView {

        return TextView(this).apply {

            text = value

            textSize = 8f

            typeface = Typeface.DEFAULT_BOLD

            letterSpacing = 0.04f

            setTextColor(mutedColor)
        }
    }

    private fun makeStatusValue(
        value: String
    ): TextView {

        return TextView(this).apply {

            text = value

            textSize = 10f

            typeface = Typeface.DEFAULT_BOLD

            setTextColor(textColor)

            maxLines = 2

            setPadding(
                0,
                dp(4),
                0,
                0
            )
        }
    }

    private fun makeButton(
        label: String,
        color: Int,
        widthDp: Int,
        heightDp: Int
    ): Button {

        return Button(this).apply {

            text = label

            textSize = 12f

            isAllCaps = false

            minHeight = 0

            minimumHeight = 0

            minWidth = 0

            setTextColor(Color.WHITE)

            typeface = Typeface.DEFAULT_BOLD

            background = rounded(
                color,
                10
            )

            setPadding(
                dp(5),
                0,
                dp(5),
                0
            )

            layoutParams = LinearLayout.LayoutParams(

                if (widthDp > 0) {
                    dp(widthDp)
                } else {
                    LinearLayout.LayoutParams.WRAP_CONTENT
                },

                dp(heightDp)
            )
        }
    }

    // =====================================================
    // ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
    // =====================================================

    private fun rounded(
        color: Int,
        radius: Int
    ) = GradientDrawable().apply {

        setColor(color)

        cornerRadius =
            dp(radius).toFloat()
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    // =====================================================
    // УДАЛЕНИЕ ПАНЕЛИ И СЕРВИСА
    // =====================================================

    private fun removeOverlay() {

        mainHandler.removeCallbacks(
            refreshRunnable
        )

        val old = overlay

        if (old != null) {
            try {

                windowManager.removeView(old)

            } catch (_: Exception) {
            }
        }

        overlay = null
        overlayParams = null

        macroStatusView = null
        touchStatusView = null
        captureStatusView = null
        diagnosticsView = null

        collapseButton = null
        collapsibleViews.clear()
        collapsed = false
    }

    override fun onDestroy() {

        stopMacro()

        removeOverlay()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
