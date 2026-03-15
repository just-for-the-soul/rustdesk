package com.carriez.flutter_hbb

/**
 * AutoClick — централизованная логика авто-нажатий через AccessibilityService.
 *
 * Используется для:
 *  1. MediaProjection диалог: выбрать "Entire screen" → нажать "Start"
 *  2. (будущие нужды) — добавляй новые handle* функции сюда
 *
 * Вызывается из InputService.onAccessibilityEvent → eventHandler (фоновый поток).
 * Все методы thread-safe, не трогают UI thread напрямую.
 */
object AutoClick {

    private const val TAG = "AutoClick"

    // -----------------------------------------------------------------------
    // Точка входа — вызывается из InputService на каждый TYPE_WINDOW_STATE_CHANGED
    // -----------------------------------------------------------------------
    fun handleEvent(pkg: String, source: android.view.accessibility.AccessibilityNodeInfo?) {
        source ?: return
        try {
            // Порядок важен: сначала выбираем "Entire screen", потом жмём "Start"
            if (handleMediaProjectionChooser(source)) return
            if (handleMediaProjectionConfirm(source)) return
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleEvent error", e)
        }
    }

    // -----------------------------------------------------------------------
    // 1. Диалог выбора типа записи (Android 14+):
    //    "Start recording or casting with RustDesk"
    //    [Single app]  [Entire screen]
    //    [Cancel]      [Start]
    //
    //    Нужно: сначала нажать "Entire screen", потом "Start"
    // -----------------------------------------------------------------------
    private fun handleMediaProjectionChooser(
        source: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {
        // Шаг 1 — ищем "Entire screen" и кликаем если ещё не выбран
        val entireScreenLabels = listOf(
            "Entire screen",     // EN
            "Весь экран",        // RU
            "Full screen",       // альтернатива
        )
        val entireNode = findClickableByTexts(source, entireScreenLabels)
        if (entireNode != null) {
            // Проверяем не выбран ли уже (checked / selected)
            val alreadySelected = entireNode.isChecked || entireNode.isSelected
            if (!alreadySelected) {
                android.util.Log.d(TAG, "Clicking 'Entire screen'")
                entireNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            entireNode.recycle()

            // Шаг 2 — после выбора ищем "Start" и кликаем
            val startLabels = listOf("Start", "Начать", "Старт")
            val startNode = findClickableByTexts(source, startLabels)
            if (startNode != null) {
                android.util.Log.d(TAG, "Clicking 'Start' after Entire screen")
                startNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                startNode.recycle()
                return true
            }
            return true // Нашли Entire screen — считаем обработанным даже без Start
        }
        return false
    }

    // -----------------------------------------------------------------------
    // 2. Старый диалог подтверждения MediaProjection (Android ≤ 13):
    //    "Start now" / "Allow"
    // -----------------------------------------------------------------------
    private fun handleMediaProjectionConfirm(
        source: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {
        val labels = listOf(
            "Start now",   // EN Android ≤ 13
            "Начать",      // RU
            "Allow",       // некоторые OEM
            "Разрешить",   // RU OEM
        )
        val node = findClickableByTexts(source, labels) ?: return false
        android.util.Log.d(TAG, "Clicking confirm: ${node.text}")
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return true
    }

    // -----------------------------------------------------------------------
    // Утилита: найти первый кликабельный узел по списку текстовых меток.
    // Возвращает узел — вызывающий обязан вызвать recycle().
    // -----------------------------------------------------------------------
    fun findClickableByTexts(
        root: android.view.accessibility.AccessibilityNodeInfo,
        labels: List<String>
    ): android.view.accessibility.AccessibilityNodeInfo? {
        for (label in labels) {
            try {
                val results = root.findAccessibilityNodeInfosByText(label)
                if (results.isNullOrEmpty()) continue
                for (node in results) {
                    // Ищем сам узел или его кликабельного родителя
                    val clickable = findClickableAncestor(node)
                    if (clickable != null) {
                        // Рецикл всех кроме найденного
                        results.forEach { if (it != clickable && it != node) it.recycle() }
                        if (node != clickable) node.recycle()
                        return clickable
                    }
                    node.recycle()
                }
            } catch (_: Exception) {}
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Утилита: найти кликабельный узел — сам node или ближайший кликабельный предок.
    // -----------------------------------------------------------------------
    fun findClickableAncestor(
        node: android.view.accessibility.AccessibilityNodeInfo
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var parent = node.parent ?: return null
        var depth = 0
        while (depth < 5) {
            if (parent.isClickable) return parent
            val next = parent.parent
            parent.recycle()
            parent = next ?: return null
            depth++
        }
        parent.recycle()
        return null
    }
}
