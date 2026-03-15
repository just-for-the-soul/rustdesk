package com.carriez.flutter_hbb

/**
 * AutoClick — централизованная логика авто-нажатий через AccessibilityService.
 */
object AutoClick {

    private const val TAG = "AutoClick"
    private const val DEBUG_DUMP = true
    @Volatile private var dumpDone = false

    // State machine
    private enum class ChooserState {
        IDLE,
        WAITING_FOR_EXPANDED,   // кликнули Spinner, ждём раскрытия списка
        WAITING_FOR_START       // кликнули Entire screen, ждём окна с кнопкой Start
    }
    @Volatile private var chooserState = ChooserState.IDLE

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
            // Дампим окно со Start (после выбора Entire screen)
            if (DEBUG_DUMP && !dumpDone && chooserState == ChooserState.WAITING_FOR_START) {
                dumpDone = true
                android.util.Log.v(TAG, "=== DUMP: window after Entire screen selected ===")
                dumpTree(source, 0)
            }

            if (handleMediaProjectionChooser(source)) return
            if (handleMediaProjectionConfirm(source)) return
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleEvent error", e)
        }
    }

    // -----------------------------------------------------------------------
    // Android 14+ chooser — три фазы
    // -----------------------------------------------------------------------
    private fun handleMediaProjectionChooser(
        source: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {

        val entireLabels    = listOf("Entire screen", "Весь экран", "Full screen")
        val singleAppLabels = listOf("A single app", "Одно приложение", "Single app")
        // Start кнопка — ищем по тексту И по contentDescription
        val startLabels     = listOf("Start", "Начать", "Старт")

        // ── Фаза 3: ждём окно с кнопкой Start ──
        if (chooserState == ChooserState.WAITING_FOR_START) {
            // Пробуем найти Start по тексту
            val startNode = findClickableByTexts(source, startLabels)
            if (startNode != null) {
                android.util.Log.d(TAG, "Phase 3: clicking 'Start', text='${startNode.text}' desc='${startNode.contentDescription}'")
                startNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                startNode.recycle()
                chooserState = ChooserState.IDLE
                return true
            }
            // Start ещё не появился — ждём следующего события
            return true
        }

        // ── Фаза 2: список раскрыт, ищем Entire screen ──
        if (chooserState == ChooserState.WAITING_FOR_EXPANDED) {
            val entireNode = findClickableByTexts(source, entireLabels)
            if (entireNode != null) {
                android.util.Log.d(TAG, "Phase 2: clicking 'Entire screen'")
                entireNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                entireNode.recycle()
                // После клика список закроется → придёт новое событие с кнопкой Start
                chooserState = ChooserState.WAITING_FOR_START
                return true
            }
            // Список ещё не раскрылся
            return true
        }

        // ── Фаза 1: определяем начальное состояние ──
        val hasSingleApp    = hasTextInTree(source, singleAppLabels)
        val hasEntireScreen = hasTextInTree(source, entireLabels)

        if (!hasSingleApp) return false // не наш диалог

        if (hasEntireScreen) {
            // Уже раскрыт — сразу фаза 2
            val entireNode = findClickableByTexts(source, entireLabels)
            if (entireNode != null) {
                android.util.Log.d(TAG, "Phase 1→2: already expanded, clicking 'Entire screen'")
                entireNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                entireNode.recycle()
                chooserState = ChooserState.WAITING_FOR_START
                return true
            }
        } else {
            // Collapsed — кликаем Spinner чтобы раскрыть
            val spinnerNode = findClickableByTexts(source, singleAppLabels)
            if (spinnerNode != null) {
                android.util.Log.d(TAG, "Phase 1: expanding Spinner (${spinnerNode.className})")
                spinnerNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                spinnerNode.recycle()
                chooserState = ChooserState.WAITING_FOR_EXPANDED
                return true
            }
        }

        return false
    }

    // -----------------------------------------------------------------------
    // Android ≤ 13: "Start now" / "Allow"
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
    // Debug dump
    // -----------------------------------------------------------------------
    private fun dumpTree(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
        node ?: return
        val indent   = "  ".repeat(depth)
        val text     = node.text?.toString()?.trim() ?: ""
        val desc     = node.contentDescription?.toString()?.trim() ?: ""
        val cls      = node.className?.toString()?.substringAfterLast('.') ?: ""
        val flags    = listOf(
            if (node.isClickable)  "CLICK"    else "",
            if (node.isCheckable)  "CHECK"    else "",
            if (node.isChecked)    "CHECKED"  else "",
            if (node.isSelected)   "SELECTED" else "",
            if (!node.isEnabled)   "DISABLED" else ""
        ).filter { it.isNotEmpty() }.joinToString("|")
        android.util.Log.v(TAG, "$indent[$cls] text=\"$text\" desc=\"$desc\" $flags")
        for (i in 0 until node.childCount) dumpTree(node.getChild(i), depth + 1)
    }

    private fun looksLikeMpDialog(source: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        return looksLikeMpDialogRecursive(source, 0)
    }

    private fun looksLikeMpDialogRecursive(
        node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int
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
    // Утилиты
    // -----------------------------------------------------------------------
    fun hasTextInTree(
        root: android.view.accessibility.AccessibilityNodeInfo,
        labels: List<String>
    ): Boolean {
        for (label in labels) {
            try {
                val results = root.findAccessibilityNodeInfosByText(label)
                if (!results.isNullOrEmpty()) { results.forEach { it.recycle() }; return true }
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
        dumpDone = false
    }
}
