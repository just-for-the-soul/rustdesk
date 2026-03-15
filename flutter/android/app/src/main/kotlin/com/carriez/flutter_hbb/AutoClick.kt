package com.carriez.flutter_hbb

/**
 * AutoClick — централизованная логика авто-нажатий через AccessibilityService.
 *
 * Используется для:
 *  1. MediaProjection диалог Android 14+: expand chooser → Entire screen → Start
 *  2. MediaProjection диалог Android ≤ 13: "Start now" / "Allow"
 *  3. (будущие нужды) — добавляй новые handle* функции сюда
 *
 * Дебаг: при первом появлении MP диалога дампит всё дерево в logcat.
 *   adb logcat -s AutoClick:V
 *   или в Termux: adb -s <device> logcat | grep AutoClick
 */
object AutoClick {

    private const val TAG = "AutoClick"

    // -----------------------------------------------------------------------
    // Debug dump — выключи в проде установив false
    // -----------------------------------------------------------------------
    private const val DEBUG_DUMP = true
    // Дампим только один раз на сессию чтобы не спамить logcat
    @Volatile private var dumpDone = false

    // -----------------------------------------------------------------------
    // State machine для Android 14+ chooser диалога
    // -----------------------------------------------------------------------
    private enum class ChooserState { IDLE, WAITING_FOR_EXPANDED }
    @Volatile private var chooserState = ChooserState.IDLE

    // Ключевые слова по которым определяем что это MP диалог
    private val MP_DIALOG_HINTS = listOf(
        "single app", "одно приложение",
        "entire screen", "весь экран",
        "start now", "начать",
        "rustdesk", "screen record", "cast"
    )

    // -----------------------------------------------------------------------
    // Точка входа
    // -----------------------------------------------------------------------
    fun handleEvent(pkg: String, source: android.view.accessibility.AccessibilityNodeInfo?) {
        source ?: return
        try {
            // Debug dump — срабатывает один раз когда видим MP диалог
            if (DEBUG_DUMP && !dumpDone) {
                if (looksLikeMpDialog(source)) {
                    dumpDone = true
                    dumpTree(source, 0)
                }
            }

            if (handleMediaProjectionChooser(source)) return
            if (handleMediaProjectionConfirm(source)) return
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleEvent error", e)
        }
    }

    // -----------------------------------------------------------------------
    // 1. Android 14+ chooser: collapsed → expand → Entire screen → Start
    // -----------------------------------------------------------------------
    private fun handleMediaProjectionChooser(
        source: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {

        val entireLabels    = listOf("Entire screen", "Весь экран", "Full screen")
        val singleAppLabels = listOf("A single app", "Одно приложение", "Single app")
        val startLabels     = listOf("Start", "Начать", "Старт")

        // Фаза 2: список уже раскрыт — ищем Entire screen
        if (chooserState == ChooserState.WAITING_FOR_EXPANDED) {
            val entireNode = findClickableByTexts(source, entireLabels)
            if (entireNode != null) {
                android.util.Log.d(TAG, "Phase 2: clicking 'Entire screen'")
                entireNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                entireNode.recycle()

                val startNode = findClickableByTexts(source, startLabels)
                if (startNode != null) {
                    android.util.Log.d(TAG, "Phase 2: clicking 'Start'")
                    startNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    startNode.recycle()
                }
                chooserState = ChooserState.IDLE
                return true
            }
            // Ещё не раскрылось — ждём следующего события
            return true
        }

        val hasSingleApp    = hasTextInTree(source, singleAppLabels)
        val hasEntireScreen = hasTextInTree(source, entireLabels)

        // Уже раскрыт — сразу фаза 2
        if (hasSingleApp && hasEntireScreen) {
            val entireNode = findClickableByTexts(source, entireLabels)
            if (entireNode != null) {
                android.util.Log.d(TAG, "Already expanded: clicking 'Entire screen'")
                entireNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                entireNode.recycle()
                val startNode = findClickableByTexts(source, startLabels)
                if (startNode != null) {
                    startNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    startNode.recycle()
                }
                chooserState = ChooserState.IDLE
                return true
            }
        }

        // Collapsed — кликаем спиннер чтобы раскрыть
        if (hasSingleApp && !hasEntireScreen) {
            val spinnerNode = findClickableByTexts(source, singleAppLabels)
            if (spinnerNode != null) {
                android.util.Log.d(TAG, "Phase 1: expanding chooser, className=${spinnerNode.className}")
                spinnerNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                spinnerNode.recycle()
                chooserState = ChooserState.WAITING_FOR_EXPANDED
                return true
            }
        }

        return false
    }

    // -----------------------------------------------------------------------
    // 2. Android ≤ 13: "Start now" / "Allow"
    // -----------------------------------------------------------------------
    private fun handleMediaProjectionConfirm(
        source: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {
        val labels = listOf("Start now", "Начать", "Allow", "Разрешить")
        val node = findClickableByTexts(source, labels) ?: return false
        android.util.Log.d(TAG, "Confirm: clicking '${node.text}'")
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return true
    }

    // -----------------------------------------------------------------------
    // Debug: дамп дерева в logcat
    // Читай через: adb logcat | grep AutoClick
    // или в Termux (без adb): logcat | grep AutoClick
    // -----------------------------------------------------------------------
    private fun dumpTree(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        depth: Int
    ) {
        node ?: return
        val indent = "  ".repeat(depth)
        val text        = node.text?.toString()?.trim() ?: ""
        val desc        = node.contentDescription?.toString()?.trim() ?: ""
        val cls         = node.className?.toString()?.substringAfterLast('.') ?: ""
        val clickable   = if (node.isClickable) "CLICK" else ""
        val checkable   = if (node.isCheckable) "CHECK" else ""
        val checked     = if (node.isChecked)   "CHECKED" else ""
        val selected    = if (node.isSelected)  "SELECTED" else ""
        val enabled     = if (!node.isEnabled)  "DISABLED" else ""
        val flags = listOf(clickable, checkable, checked, selected, enabled)
            .filter { it.isNotEmpty() }.joinToString("|")

        android.util.Log.v(TAG,
            "$indent[$cls] text=\"$text\" desc=\"$desc\" $flags"
        )

        for (i in 0 until node.childCount) {
            dumpTree(node.getChild(i), depth + 1)
        }
    }

    private fun looksLikeMpDialog(
        source: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {
        // Быстрая проверка — смотрим только первые 3 уровня
        return looksLikeMpDialogRecursive(source, 0)
    }

    private fun looksLikeMpDialogRecursive(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        depth: Int
    ): Boolean {
        node ?: return false
        if (depth > 3) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (MP_DIALOG_HINTS.any { text.contains(it) || desc.contains(it) }) return true
        for (i in 0 until node.childCount) {
            if (looksLikeMpDialogRecursive(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    // -----------------------------------------------------------------------
    // Утилиты (публичные — для использования в будущих обработчиках)
    // -----------------------------------------------------------------------

    fun hasTextInTree(
        root: android.view.accessibility.AccessibilityNodeInfo,
        labels: List<String>
    ): Boolean {
        for (label in labels) {
            try {
                val results = root.findAccessibilityNodeInfosByText(label)
                if (!results.isNullOrEmpty()) {
                    results.forEach { it.recycle() }
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    fun findClickableByTexts(
        root: android.view.accessibility.AccessibilityNodeInfo,
        labels: List<String>
    ): android.view.accessibility.AccessibilityNodeInfo? {
        for (label in labels) {
            try {
                val results = root.findAccessibilityNodeInfosByText(label)
                if (results.isNullOrEmpty()) continue
                for (node in results) {
                    val clickable = findClickableAncestor(node)
                    if (clickable != null) {
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

    fun reset() {
        chooserState = ChooserState.IDLE
        dumpDone = false  // сбрасываем чтобы дамп сработал при следующей сессии
    }
}
