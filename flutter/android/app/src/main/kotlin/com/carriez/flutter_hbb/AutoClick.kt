package com.carriez.flutter_hbb

/**
 * AutoClick — авто-нажатие диалога MediaProjection.
 *
 * Цель: выбрать "Entire screen" и нажать "Start" без участия пользователя.
 *
 * Поддерживаемые варианты диалога:
 *   Android ≤ 13 — простая кнопка "Start now" / "Allow"
 *   Android 14–15 — Spinner: "A single app" / "Entire screen" + кнопка "Start"
 *   Android 16    — Spinner: "Share one app" / "Share entire screen" + кнопка "Start"
 *
 * Ключевые особенности:
 *   1. Поиск текста ведётся во ВСЕХ accessibility-окнах (allRoots), потому что
 *      Spinner открывает dropdown как отдельный PopupWindow в своём окне.
 *   2. При обнаружении "Entire screen" — кликаем и тут же ищем "Start" в том же
 *      проходе (логика из старого AutoClick, которая реально работала).
 *   3. Cooldown предотвращает двойные клики, но не блокирует переход между шагами
 *      (разные метки = разные бакеты cooldown).
 */
object AutoClick {

    private const val TAG = "AutoClick"
    const val DEBUG_DUMP = false   // включи для logcat-дампа дерева

    @Volatile private var lastDumpTime   = 0L
    @Volatile private var lastClickLabel = ""
    @Volatile private var lastClickTime  = 0L
    private const val COOLDOWN_MS = 800L

    // ------------------------------------------------------------------
    // Текстовые метки — EN + RU + Android 16 варианты
    // ------------------------------------------------------------------
    private val entireLabels = listOf(
        "Entire screen",        // Android 12-15 EN
        "Share entire screen",  // Android 16 EN
        "Весь экран",           // RU
        "Full screen",
    )
    private val singleAppLabels = listOf(
        "A single app",         // Android 12-15 EN
        "Share one app",        // Android 16 EN
        "Одно приложение",      // RU
        "Single app",
    )
    private val startLabels = listOf(
        "Start now",            // Android <= 13 EN
        "Start",                // Android 14+ EN
	"Share screen",		// Android 16 EN
        "Начать",               // RU
        "Старт",
    )
    private val confirmLabels = listOf(
        "Start now",
        "Start recording",
        "Начать запись",
    )

    // Якорные тексты — по ним определяем что перед нами именно MP-диалог
    private val MP_ANCHOR_TEXTS = listOf(
        "Start recording or casting with",             // EN Android 12-15
        "recording or casting",
        "запись или трансляцию с",                     // RU
        "will have access to all of the information",  // EN Android 11
        "RustDesk will have access",
        // Android 16: текст описания меняется вместе с выбранным пунктом
        "be careful with things like passwords",       // общий хвост обоих описаний
        "sharing an app, anything shown",              // описание для "Share one app"
        "sharing your entire screen, anything",        // описание для "Share entire screen"
    )

    // Заголовок диалога — дополнительный признак
    private val MP_TITLE_HINTS = listOf(
        "recording or casting",
        "запись или трансляция",
        "record or cast",
    )

    // -----------------------------------------------------------------------
    // Точка входа (вызывается из InputService)
    // -----------------------------------------------------------------------
    fun handleEvent(
        pkg: String,
        root: android.view.accessibility.AccessibilityNodeInfo,
        allRoots: List<android.view.accessibility.AccessibilityNodeInfo>? = null
    ) {
        try {
            if (DEBUG_DUMP) maybeLogDump(pkg, root)

            // Ищем root, содержащий тело MP-диалога (не popup-окно дропдауна)
            val mpRoot = allRoots?.firstOrNull { hasTextInTree(it, MP_ANCHOR_TEXTS) }
                ?: if (hasTextInTree(root, MP_ANCHOR_TEXTS)) root else null
            if (mpRoot == null) return

            android.util.Log.d(TAG, "MP dialog detected (pkg=$pkg)")

            if (handleMpDialog(mpRoot, allRoots)) return
            handleMpConfirmLegacy(mpRoot)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleEvent error", e)
        }
    }

    // -----------------------------------------------------------------------
    // Основной обработчик: Android 14+ (и Android 16)
    //
    // Состояния:
    //   A — виден только "A single app"        -> кликаем Spinner, открываем список
    //   B — виден "Entire screen" в любом окне -> кликаем его + сразу ищем Start
    //   C — Spinner уже показывает "Entire screen", список закрыт -> кликаем Start
    // -----------------------------------------------------------------------
    private fun handleMpDialog(
        source: android.view.accessibility.AccessibilityNodeInfo,
        allRoots: List<android.view.accessibility.AccessibilityNodeInfo>?
    ): Boolean {

        // КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: ищем тексты во ВСЕХ окнах, не только в source.
        // Spinner открывает dropdown как отдельный PopupWindow (отдельное a11y-окно).
        // hasTextInTree(source, entireLabels) возвращал false пока popup открыт —
        // State B никогда не срабатывал и автоклик застревал на State A навсегда.
        val hasEntireScreen = hasTextInAnyRoot(source, allRoots, entireLabels)
        val hasSingleApp    = hasTextInAnyRoot(source, allRoots, singleAppLabels)
        val hasStart        = hasTextInTree(source, startLabels)
        val isMpDialog      = hasTextInTree(source, MP_TITLE_HINTS)

        if (!isMpDialog && !hasSingleApp && !hasEntireScreen) return false

        // ------------------------------------------------------------------
        // State C: Spinner уже показывает "Entire screen", список закрыт.
        // Признак: Entire screen есть, Single app НЕТ нигде (включая попап).
        // ------------------------------------------------------------------
        if (hasEntireScreen && !hasSingleApp && hasStart) {
            val startNode = findClickableByTexts(source, startLabels)
            if (startNode != null) {
                if (canClick("start")) {
                    android.util.Log.d(TAG, "State C -> Click 'Start'")
                    startNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                startNode.recycle()
                return true
            }
        }

        // ------------------------------------------------------------------
        // State B: "Entire screen" виден — в popup-окне или radio-button стиле.
        //
        // Ключевая логика из старого AutoClick: нашли Entire screen -> кликаем ->
        // СРАЗУ ЖЕ в том же проходе ищем Start и кликаем.
        // Старый код работал именно так — не ждал следующего события.
        // Для spinner-стиля: Start может не сработать сейчас (popup ещё открыт),
        // тогда State C поймает его при следующем событии после закрытия popup.
        // Для radio-button стиля: Start сработает прямо сейчас.
        // ------------------------------------------------------------------
        if (hasEntireScreen && hasSingleApp) {
            // Ищем "Entire screen": сначала в главном окне, потом в popup-окнах
            var entireNode = findClickableByTexts(source, entireLabels)
            if (entireNode == null && allRoots != null) {
                for (r in allRoots) {
                    if (r === source) continue
                    entireNode = findClickableByTexts(r, entireLabels)
                    if (entireNode != null) break
                }
            }

            if (entireNode != null) {
                val alreadySelected = entireNode.isChecked || entireNode.isSelected
                if (!alreadySelected) {
                    if (canClick("entire_screen_item")) {
                        android.util.Log.d(TAG, "State B -> Click 'Entire screen'")
                        entireNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    }
                } else {
                    android.util.Log.d(TAG, "State B -> 'Entire screen' already selected")
                }
                entireNode.recycle()

                // Сразу ищем Start — как в старом коде.
                // Radio-button: Start кликабелен уже сейчас.
                // Spinner-popup: Start сработает если popup уже закрылся после клика.
                val startNode = findClickableByTexts(source, startLabels)
                if (startNode != null) {
                    if (canClick("start")) {
                        android.util.Log.d(TAG, "State B -> Click 'Start' (same pass)")
                        startNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    startNode.recycle()
                }
                return true
            }
        }

        // ------------------------------------------------------------------
        // State A: виден только "A single app" -> открываем Spinner
        // ------------------------------------------------------------------
        if (hasSingleApp && !hasEntireScreen) {
            val spinner = findClickableByTexts(source, singleAppLabels)
            if (spinner != null) {
                if (canClick("spinner_expand")) {
                    android.util.Log.d(TAG, "State A -> Expand Spinner")
                    spinner.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                spinner.recycle()
                return true
            }
        }

        return false
    }

    // -----------------------------------------------------------------------
    // Fallback для Android <= 13: "Start now" / "Start recording"
    // -----------------------------------------------------------------------
    private fun handleMpConfirmLegacy(
        source: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {
        val node = findClickableByTexts(source, confirmLabels) ?: return false
        val label = node.text?.toString() ?: ""
        if (canClick("confirm_$label")) {
            android.util.Log.d(TAG, "Legacy -> Click '$label'")
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            return true
        }
        node.recycle()
        return false
    }

    // -----------------------------------------------------------------------
    // Cooldown: предотвращает повторный клик по одной кнопке.
    // Разные label-ключи не блокируют друг друга:
    //   "spinner_expand" != "entire_screen_item" != "start"
    // -----------------------------------------------------------------------
    private fun canClick(label: String): Boolean {
        val now = System.currentTimeMillis()
        if (lastClickLabel == label && now - lastClickTime < COOLDOWN_MS) return false
        lastClickLabel = label
        lastClickTime  = now
        return true
    }

    // -----------------------------------------------------------------------
    // Утилиты
    // -----------------------------------------------------------------------

    /**
     * Ищет метки в source И во всех остальных accessibility-окнах.
     * Исправляет главный баг: spinner popup — отдельное окно, hasTextInTree(source)
     * его не видел.
     */
    private fun hasTextInAnyRoot(
        source: android.view.accessibility.AccessibilityNodeInfo,
        allRoots: List<android.view.accessibility.AccessibilityNodeInfo>?,
        labels: List<String>
    ): Boolean {
        if (hasTextInTree(source, labels)) return true
        if (allRoots == null) return false
        return allRoots.any { it !== source && hasTextInTree(it, labels) }
    }

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

    // -----------------------------------------------------------------------
    // Debug dump
    // -----------------------------------------------------------------------
    private fun maybeLogDump(
        pkg: String,
        root: android.view.accessibility.AccessibilityNodeInfo
    ) {
        val isSystemPkg = pkg.startsWith("com.android") || pkg.startsWith("android") ||
            pkg.startsWith("com.google.android") || pkg.isEmpty()
        if (!isSystemPkg) return
        val now = System.currentTimeMillis()
        if (now - lastDumpTime < 500L) return
        lastDumpTime = now
        android.util.Log.v(TAG, "=== DUMP pkg=$pkg ===")
        dumpTree(root, 0)
    }

    private fun dumpTree(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        depth: Int
    ) {
        node ?: return
        val indent = "  ".repeat(depth)
        val text  = node.text?.toString()?.trim() ?: ""
        val desc  = node.contentDescription?.toString()?.trim() ?: ""
        val cls   = node.className?.toString()?.substringAfterLast('.') ?: ""
        val flags = listOf(
            if (node.isClickable) "CLICK"    else "",
            if (node.isCheckable) "CHECK"    else "",
            if (node.isChecked)   "CHECKED"  else "",
            if (node.isSelected)  "SELECTED" else "",
            if (!node.isEnabled)  "DISABLED" else ""
        ).filter { it.isNotEmpty() }.joinToString("|")
        android.util.Log.v(TAG, "$indent[$cls] text=\"$text\" desc=\"$desc\" $flags")
        for (i in 0 until node.childCount) dumpTree(node.getChild(i), depth + 1)
    }

    fun reset() {
        lastClickLabel = ""
        lastClickTime  = 0L
        lastDumpTime   = 0L
        android.util.Log.d(TAG, "reset")
    }
}
