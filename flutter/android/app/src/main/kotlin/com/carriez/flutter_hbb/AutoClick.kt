package com.carriez.flutter_hbb

/**
 * AutoClick — авто-нажатие диалога MediaProjection.
 *
 * Цель: выбрать "Entire screen" и нажать "Start" без участия пользователя.
 *
 * Поддерживаемые диалоги:
 *   Android ≤ 13 — кнопка "Start now" / "Allow"
 *   Android 14–15 — Spinner: "A single app" / "Entire screen" + "Start"
 *   Android 16    — Spinner: "Share one app" / "Share entire screen" + "Start"
 *
 * Исправленные баги:
 *   1. findClickableByTexts теперь возвращает саму ноду если нет кликабельного предка.
 *      ListPopupWindow-элементы помечены isClickable=false, но реагируют на ACTION_CLICK.
 *   2. State B больше не возвращает true когда entireNode==null.
 *      Старый код застревал: обнаружил State B → ничего не кликнул → return true → ∞.
 *   3. Поиск во всех окнах (allRoots): дропдаун Spinner — отдельный PopupWindow.
 */
object AutoClick {

    private const val TAG = "AutoClick"
    const val DEBUG_DUMP = true   // выключи после отладки

    @Volatile private var lastDumpTime   = 0L
    @Volatile private var lastClickLabel = ""
    @Volatile private var lastClickTime  = 0L
    private const val COOLDOWN_MS = 800L

    // ------------------------------------------------------------------
    // Метки — EN + RU + Android 16
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
        "Start now",            // Android <= 13
        "Start",                // Android 14+
	"Share screen",         // Android 16 EN
        "Начать",
        "Старт",
    )
    private val confirmLabels = listOf(
        "Start now",
        "Start recording",
        "Начать запись",
    )

    // Якорные тексты — определяем что это именно MP-диалог
    private val MP_ANCHOR_TEXTS = listOf(
        "Start recording or casting with",            // EN Android 12-15
        "recording or casting",
        "запись или трансляцию с",                    // RU
        "will have access to all of the information", // EN Android 11
        "RustDesk will have access",
        "be careful with things like passwords",      // EN Android 16 (общий хвост)
        "sharing an app, anything shown",             // EN Android 16 — "Share one app"
        "sharing your entire screen, anything",       // EN Android 16 — "Share entire screen"
    )

    private val MP_TITLE_HINTS = listOf(
        "recording or casting",
        "запись или трансляция",
        "record or cast",
    )

    // -----------------------------------------------------------------------
    // Точка входа
    // -----------------------------------------------------------------------
    fun handleEvent(
        pkg: String,
        root: android.view.accessibility.AccessibilityNodeInfo,
        allRoots: List<android.view.accessibility.AccessibilityNodeInfo>? = null
    ) {
        try {
            if (DEBUG_DUMP) maybeLogDump(pkg, root, allRoots)

            // Ищем root содержащий тело MP-диалога (не popup-окно дропдауна)
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
    // Основной обработчик (Android 14+)
    // Состояния:
    //   A: виден "A single app"  → открыть Spinner
    //   B: виден "Entire screen" в любом окне → кликнуть его + сразу Start
    //   C: Spinner показывает "Entire screen", список закрыт → Start
    // -----------------------------------------------------------------------
    private fun handleMpDialog(
        source: android.view.accessibility.AccessibilityNodeInfo,
        allRoots: List<android.view.accessibility.AccessibilityNodeInfo>?
    ): Boolean {
        // Ищем во ВСЕХ окнах: Spinner открывает popup в отдельном a11y-окне
        val hasEntireScreen = hasTextInAnyRoot(source, allRoots, entireLabels)
        val hasSingleApp    = hasTextInAnyRoot(source, allRoots, singleAppLabels)
        val hasStart        = hasTextInTree(source, startLabels)
        val isMpDialog      = hasTextInTree(source, MP_TITLE_HINTS)

        if (!isMpDialog && !hasSingleApp && !hasEntireScreen) return false

        android.util.Log.d(TAG,
            "States: isMp=$isMpDialog single=$hasSingleApp entire=$hasEntireScreen start=$hasStart")

        // ── State C: Spinner показывает "Entire screen", popup закрыт ──
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

        // ── State B: dropdown открыт, "Entire screen" видно в каком-то окне ──
        //
        // БАГ ИСПРАВЛЕН: старый код делал return true даже если entireNode==null.
        // Это вызывало вечное застревание:
        //   State B обнаружен → findClickableByTexts→null → ничего не кликнуто → return true
        //   → следующее событие → то же самое → ∞
        //
        // Новое поведение: если ноду не нашли нигде → return false (дать шанс повтору).
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
                    android.util.Log.d(TAG, "State B -> already selected, going for Start")
                }
                entireNode.recycle()

                // Сразу пробуем Start — работало в старом коде для radio-button стиля.
                // Для spinner-стиля Start появится при следующем событии (State C).
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

            // Нода не найдена ни в одном окне — не возвращаем true, позволяем повтор
            android.util.Log.w(TAG, "State B: entireNode==null in all windows, will retry")
            return false
        }

        // ── State A: только "A single app" → открываем Spinner ──
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
    // Fallback Android <= 13: "Start now" / "Start recording"
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
    // Утилиты
    // -----------------------------------------------------------------------

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

    /**
     * Ищет кликабельную ноду по тексту.
     *
     * КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: если кликабельный предок не найден в пределах 5 уровней —
     * возвращаем саму текстовую ноду.
     * Элементы ListPopupWindow (дропдаун Spinner) имеют isClickable=false на всех уровнях,
     * но реагируют на ACTION_CLICK напрямую. performAction вернёт false если не сработает.
     */
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
                    // Кликабельного предка нет — возвращаем саму ноду.
                    // Для ListPopupWindow это правильно: item не помечен isClickable,
                    // но ACTION_CLICK на него работает (так делает TalkBack).
                    results.forEach { if (it != node) it.recycle() }
                    return node  // caller вызовет performAction(ACTION_CLICK)
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
        root: android.view.accessibility.AccessibilityNodeInfo,
        allRoots: List<android.view.accessibility.AccessibilityNodeInfo>?
    ) {
        val isSystemPkg = pkg.startsWith("com.android") || pkg.startsWith("android") ||
            pkg.startsWith("com.google.android") || pkg.isEmpty()
        if (!isSystemPkg) return
        val now = System.currentTimeMillis()
        if (now - lastDumpTime < 3_000L) return
        lastDumpTime = now
        android.util.Log.v(TAG, "=== DUMP pkg=$pkg (${allRoots?.size ?: 1} windows) ===")
        dumpTree(root, 0)
        allRoots?.forEachIndexed { i, r ->
            if (r !== root) {
                android.util.Log.v(TAG, "=== WINDOW[$i] ===")
                dumpTree(r, 0)
            }
        }
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
