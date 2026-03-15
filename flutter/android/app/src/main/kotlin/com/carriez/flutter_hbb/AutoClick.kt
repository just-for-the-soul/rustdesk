package com.carriez.flutter_hbb

/**
 * AutoClick — централизованная логика авто-нажатий через AccessibilityService.
 *
 * Структура MP диалога Android 14+ (из дампа):
 *   [TextView] text="Start recording or casting with RustDesk?"
 *   [Spinner]  CLICK
 *     [TextView] text="A single app" | "Entire screen"   ← меняется после клика
 *   [Button] text="Cancel"
 *   [Button] text="Start"
 *
 * Состояния диалога:
 *   A) Spinner показывает "A single app"  → кликаем Spinner → раскрывается список
 *   B) Список раскрыт, видим "Entire screen" в списке → кликаем его
 *   C) Spinner показывает "Entire screen" (список закрылся) → кликаем Start
 */
object AutoClick {

    private const val TAG = "AutoClick"
    private const val DEBUG_DUMP = true

    @Volatile private var lastDumpTime = 0L

    // Cooldown — не кликаем одно и то же чаще раза в COOLDOWN мс
    @Volatile private var lastClickLabel = ""
    @Volatile private var lastClickTime  = 0L
    private const val COOLDOWN_MS = 1500L

    // Текст заголовка диалога — по нему определяем что это MP диалог
    private val MP_TITLE_HINTS = listOf(
        "recording or casting",
        "запись или трансляция",
        "record or cast",
    )

    private val entireLabels    = listOf("Entire screen", "Весь экран", "Full screen")
    private val singleAppLabels = listOf("A single app", "Одно приложение", "Single app")
    private val startLabels     = listOf("Start", "Начать", "Старт")
    private val confirmLabels   = listOf("Start now", "Начать", "Allow", "Разрешить")

    // -----------------------------------------------------------------------
    // Точка входа
    // -----------------------------------------------------------------------
    fun handleEvent(pkg: String, source: android.view.accessibility.AccessibilityNodeInfo?) {
        source ?: return
        try {
            if (handleMpDialogAndroid14(source)) return
            handleMpConfirmAndroid13(source)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleEvent error", e)
        }
    }

    // -----------------------------------------------------------------------
    // Android 14+ — stateless, три состояния
    // -----------------------------------------------------------------------
    private fun handleMpDialogAndroid14(
        source: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {
        val hasSingleApp    = hasTextInTree(source, singleAppLabels)
        val hasEntireScreen = hasTextInTree(source, entireLabels)
        val hasStart        = hasTextInTree(source, startLabels)
        val isMpDialog      = hasTextInTree(source, MP_TITLE_HINTS)

        // Не наш диалог — быстрый выход
        if (!isMpDialog && !hasSingleApp && !hasEntireScreen) return false

        // Debug dump — раз в 10с
        if (DEBUG_DUMP) {
            val now = System.currentTimeMillis()
            if (now - lastDumpTime > 10_000L) {
                lastDumpTime = now
                android.util.Log.v(TAG,
                    "=== DUMP isMpDialog=$isMpDialog hasSingle=$hasSingleApp " +
                    "hasEntire=$hasEntireScreen hasStart=$hasStart ===")
                dumpTree(source, 0)
            }
        }

        // ── Состояние A: Spinner показывает "A single app" → раскрываем ──
        if (hasSingleApp && !hasEntireScreen) {
            val spinner = findClickableByTexts(source, singleAppLabels)
            if (spinner != null) {
                if (canClick("spinner")) {
                    android.util.Log.d(TAG, "State A: expanding Spinner")
                    spinner.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                spinner.recycle()
            }
            return true
        }

        // ── Состояние B: список раскрыт, есть "Entire screen" И "A single app" ──
        // (оба видны одновременно когда список открыт)
        if (hasEntireScreen && hasSingleApp) {
            val entireNode = findClickableByTexts(source, entireLabels)
            if (entireNode != null) {
                if (canClick("entire_screen")) {
                    android.util.Log.d(TAG, "State B: clicking 'Entire screen'")
                    entireNode.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                entireNode.recycle()
            }
            return true
        }

        // ── Состояние C: Spinner показывает "Entire screen" (single app исчез),
        //    список закрылся — кликаем Start ──
        if (hasEntireScreen && !hasSingleApp && hasStart) {
            val startNode = findClickableByTexts(source, startLabels)
            if (startNode != null) {
                if (canClick("start")) {
                    android.util.Log.d(TAG, "State C: clicking 'Start'")
                    startNode.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                startNode.recycle()
            }
            return true
        }

        // ── Состояние C': isMpDialog + hasStart но Spinner уже показывает Entire
        //    (повторный запуск — Android запомнил выбор) ──
        if (isMpDialog && hasStart && !hasSingleApp) {
            val startNode = findClickableByTexts(source, startLabels)
            if (startNode != null) {
                if (canClick("start")) {
                    android.util.Log.d(TAG, "State C': repeat launch, clicking 'Start' directly")
                    startNode.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                startNode.recycle()
            }
            return true
        }

        return false
    }

    // -----------------------------------------------------------------------
    // Android ≤ 13 — "Start now" / "Allow"
    // -----------------------------------------------------------------------
    private fun handleMpConfirmAndroid13(
        source: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {
        val node = findClickableByTexts(source, confirmLabels) ?: return false
        val label = node.text?.toString() ?: ""
        if (canClick("confirm_$label")) {
            android.util.Log.d(TAG, "Android≤13: clicking '$label'")
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            return true
        }
        node.recycle()
        return false
    }

    // -----------------------------------------------------------------------
    // Cooldown
    // -----------------------------------------------------------------------
    private fun canClick(label: String): Boolean {
        val now = System.currentTimeMillis()
        if (lastClickLabel == label && now - lastClickTime < COOLDOWN_MS) return false
        lastClickLabel = label
        lastClickTime  = now
        return true
    }

    // -----------------------------------------------------------------------
    // Debug dump
    // -----------------------------------------------------------------------
    private fun dumpTree(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
        node ?: return
        val indent = "  ".repeat(depth)
        val text   = node.text?.toString()?.trim() ?: ""
        val desc   = node.contentDescription?.toString()?.trim() ?: ""
        val cls    = node.className?.toString()?.substringAfterLast('.') ?: ""
        val flags  = listOf(
            if (node.isClickable) "CLICK"    else "",
            if (node.isCheckable) "CHECK"    else "",
            if (node.isChecked)   "CHECKED"  else "",
            if (node.isSelected)  "SELECTED" else "",
            if (!node.isEnabled)  "DISABLED" else ""
        ).filter { it.isNotEmpty() }.joinToString("|")
        android.util.Log.v(TAG, "$indent[$cls] text=\"$text\" desc=\"$desc\" $flags")
        for (i in 0 until node.childCount) dumpTree(node.getChild(i), depth + 1)
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
                val r = root.findAccessibilityNodeInfosByText(label)
                if (!r.isNullOrEmpty()) { r.forEach { it.recycle() }; return true }
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
        lastClickLabel = ""
        lastClickTime  = 0L
        lastDumpTime   = 0L
        android.util.Log.d(TAG, "reset")
    }
}
