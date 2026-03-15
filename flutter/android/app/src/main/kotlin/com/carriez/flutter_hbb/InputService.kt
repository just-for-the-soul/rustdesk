package com.carriez.flutter_hbb

/**
 * Объединённый AccessibilityService:
 * 1. Обработка remote input (gesture, key, touch) — оригинальный InputService
 * 2. XmlCapture — захват экрана через дерево accessibility
 * 3. Keep-alive — сервис не даёт себя убить
 * 4. Авто-принятие диалога MediaProjection
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.KeyEvent as KeyEventAndroid
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.annotation.RequiresApi
import hbb.KeyEventConverter
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode
import java.lang.Character
import java.util.*
import kotlin.math.abs
import kotlin.math.max

// ---------------------------------------------------------------------------
// Top-level константы
// Объявлены здесь — старый InputService.kt должен быть удалён из проекта.
// Если константы уже объявлены в другом .kt файле пакета — удали их оттуда.
// ---------------------------------------------------------------------------
const val LEFT_DOWN = 9
const val LEFT_MOVE = 8
const val LEFT_UP = 10
const val RIGHT_UP = 18
// (BUTTON_BACK << 3) | BUTTON_UP
const val BACK_UP = 66
const val WHEEL_BUTTON_DOWN = 33
const val WHEEL_BUTTON_UP = 34
const val WHEEL_DOWN = 523331
const val WHEEL_UP = 963

const val TOUCH_SCALE_START = 1
const val TOUCH_SCALE = 2
const val TOUCH_SCALE_END = 3
const val TOUCH_PAN_START = 4
const val TOUCH_PAN_UPDATE = 5
const val TOUCH_PAN_END = 6

const val WHEEL_STEP = 120
const val WHEEL_DURATION = 50L
const val LONG_TAP_DELAY = 200L

class InputService : AccessibilityService() {

    companion object {
        var ctx: InputService? = null
        val isOpen: Boolean
            get() = ctx != null

        // Keep-alive interval
        private const val KEEP_ALIVE_INTERVAL_MS = 5_000L
    }

    private val logTag = "input service"

    // -----------------------------------------------------------------------
    // Input state (оригинальный InputService)
    // -----------------------------------------------------------------------
    private var leftIsDown = false
    private var touchPath = Path()
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastTouchGestureStartTime = 0L
    private var mouseX = 0
    private var mouseY = 0
    private var timer = Timer()
    private var recentActionTask: TimerTask? = null
    private val longPressDuration =
        ViewConfiguration.getTapTimeout().toLong() + ViewConfiguration.getLongPressTimeout().toLong()
    private val wheelActionsQueue = LinkedList<GestureDescription>()
    private var isWheelActionsPolling = false
    private var isWaitingLongPress = false
    private var fakeEditTextForTextStateCalculation: EditText? = null
    private var lastX = 0
    private var lastY = 0
    private val volumeController: VolumeController by lazy {
        VolumeController(applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager)
    }

    // -----------------------------------------------------------------------
    // Keep-alive
    // -----------------------------------------------------------------------
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            try { rootInActiveWindow?.recycle() } catch (_: Exception) {}
            keepAliveHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
        }
    }

    // Отдельный поток для onAccessibilityEvent — не блокируем main thread,
    // иначе система помечает сервис как "malfunctioning" при задержке >5с
    private val eventThread = HandlerThread("InputServiceEvents").also { it.start() }
    private val eventHandler = Handler(eventThread.looper)

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    override fun onServiceConnected() {
        super.onServiceConnected()
        ctx = this
        Log.d(logTag, "onServiceConnected!")

        // Не пересоздаём ServiceInfo полностью — берём существующий из XML
        // и добавляем только FLAG_INPUT_METHOD_EDITOR (недоступен через XML).
        // Полная замена через setServiceInfo(new AccessibilityServiceInfo()) 
        // сбрасывает canPerformGestures и вызывает "malfunctioning".
        if (Build.VERSION.SDK_INT >= 33) {
            serviceInfo?.let { info ->
                info.flags = info.flags or FLAG_INPUT_METHOD_EDITOR
                setServiceInfo(info)
            }
        }

        fakeEditTextForTextStateCalculation = EditText(this)
        fakeEditTextForTextStateCalculation?.layoutParams = LayoutParams(100, 100)
        fakeEditTextForTextStateCalculation?.onPreDraw()

        keepAliveHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL_MS)
    }

    override fun onDestroy() {
        ctx = null
        XmlCapture.stop()
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        try { eventThread.quitSafely() } catch (_: Exception) {}
        Log.w(logTag, "onDestroy")
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // -----------------------------------------------------------------------
    // AccessibilityEvent — делегируем в AutoClick
    // -----------------------------------------------------------------------
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Обрабатываем в отдельном потоке чтобы не блокировать main thread.
        // Блокировка main thread на >5с → Android помечает сервис "malfunctioning".
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            val source = event.source
            eventHandler.post {
                try {
                    AutoClick.handleEvent(pkg, source)
                } catch (_: Exception) {
                } finally {
                    source?.recycle()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // XmlCapture API (вызывается из CaptureController)
    // -----------------------------------------------------------------------
    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    fun getWindowsList(): List<android.view.accessibility.AccessibilityWindowInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) windows ?: emptyList()
        else emptyList()

    // -----------------------------------------------------------------------
    // Mouse input
    // -----------------------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.N)
    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        val x = max(0, _x)
        val y = max(0, _y)

        if (mask == 0 || mask == LEFT_MOVE) {
            val oldX = mouseX
            val oldY = mouseY
            mouseX = x * SCREEN_INFO.scale
            mouseY = y * SCREEN_INFO.scale
            if (isWaitingLongPress) {
                val delta = abs(oldX - mouseX) + abs(oldY - mouseY)
                if (delta > 8) isWaitingLongPress = false
            }
        }

        if (mask == LEFT_DOWN) {
            isWaitingLongPress = true
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (isWaitingLongPress) {
                        isWaitingLongPress = false
                        continueGesture(mouseX, mouseY)
                    }
                }
            }, longPressDuration)
            leftIsDown = true
            startGesture(mouseX, mouseY)
            return
        }

        if (leftIsDown) continueGesture(mouseX, mouseY)

        if (mask == LEFT_UP) {
            if (leftIsDown) {
                leftIsDown = false
                isWaitingLongPress = false
                endGesture(mouseX, mouseY)
                return
            }
        }

        if (mask == RIGHT_UP) { longPress(mouseX, mouseY); return }
        if (mask == BACK_UP) { performGlobalAction(GLOBAL_ACTION_BACK); return }

        if (mask == WHEEL_BUTTON_DOWN) {
            timer.purge()
            recentActionTask = object : TimerTask() {
                override fun run() {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    recentActionTask = null
                }
            }
            timer.schedule(recentActionTask, LONG_TAP_DELAY)
        }

        if (mask == WHEEL_BUTTON_UP) {
            if (recentActionTask != null) {
                recentActionTask!!.cancel()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        if (mask == WHEEL_DOWN) {
            if (mouseY < WHEEL_STEP) return
            val path = Path().apply {
                moveTo(mouseX.toFloat(), mouseY.toFloat())
                lineTo(mouseX.toFloat(), (mouseY - WHEEL_STEP).toFloat())
            }
            wheelActionsQueue.offer(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, WHEEL_DURATION))
                    .build()
            )
            consumeWheelActions()
        }

        if (mask == WHEEL_UP) {
            if (mouseY < WHEEL_STEP) return
            val path = Path().apply {
                moveTo(mouseX.toFloat(), mouseY.toFloat())
                lineTo(mouseX.toFloat(), (mouseY + WHEEL_STEP).toFloat())
            }
            wheelActionsQueue.offer(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, WHEEL_DURATION))
                    .build()
            )
            consumeWheelActions()
        }
    }

    // -----------------------------------------------------------------------
    // Touch input
    // -----------------------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.N)
    fun onTouchInput(mask: Int, _x: Int, _y: Int) {
        when (mask) {
            TOUCH_PAN_UPDATE -> {
                mouseX -= _x * SCREEN_INFO.scale
                mouseY -= _y * SCREEN_INFO.scale
                mouseX = max(0, mouseX)
                mouseY = max(0, mouseY)
                continueGesture(mouseX, mouseY)
            }
            TOUCH_PAN_START -> {
                mouseX = max(0, _x) * SCREEN_INFO.scale
                mouseY = max(0, _y) * SCREEN_INFO.scale
                startGesture(mouseX, mouseY)
            }
            TOUCH_PAN_END -> {
                endGesture(mouseX, mouseY)
                mouseX = max(0, _x) * SCREEN_INFO.scale
                mouseY = max(0, _y) * SCREEN_INFO.scale
            }
            else -> {}
        }
    }

    // -----------------------------------------------------------------------
    // Gesture helpers
    // -----------------------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.N)
    private fun consumeWheelActions() {
        if (isWheelActionsPolling) return
        isWheelActionsPolling = true
        wheelActionsQueue.poll()?.let {
            dispatchGesture(it, null, null)
            timer.purge()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    isWheelActionsPolling = false
                    consumeWheelActions()
                }
            }, WHEEL_DURATION + 10)
        } ?: run { isWheelActionsPolling = false }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun performClick(x: Int, y: Int, duration: Long) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        try {
            val builder = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(logTag, "performClick error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun longPress(x: Int, y: Int) = performClick(x, y, longPressDuration)

    private fun startGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) touchPath.reset()
        else touchPath = Path()
        touchPath.moveTo(x.toFloat(), y.toFloat())
        lastTouchGestureStartTime = System.currentTimeMillis()
        lastX = x; lastY = y
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun doDispatchGesture(x: Int, y: Int, willContinue: Boolean) {
        touchPath.lineTo(x.toFloat(), y.toFloat())
        var duration = System.currentTimeMillis() - lastTouchGestureStartTime
        if (duration <= 0) duration = 1
        try {
            stroke = if (stroke == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    GestureDescription.StrokeDescription(touchPath, 0, duration, willContinue)
                else GestureDescription.StrokeDescription(touchPath, 0, duration)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    stroke?.continueStroke(touchPath, 0, duration, willContinue)
                else GestureDescription.StrokeDescription(touchPath, 0, duration)
            }
            stroke?.let {
                dispatchGesture(GestureDescription.Builder().addStroke(it).build(), null, null)
            }
        } catch (e: Exception) {
            Log.e(logTag, "doDispatchGesture error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun continueGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            doDispatchGesture(x, y, true)
            touchPath.reset()
            touchPath.moveTo(x.toFloat(), y.toFloat())
            lastTouchGestureStartTime = System.currentTimeMillis()
            lastX = x; lastY = y
        } else {
            touchPath.lineTo(x.toFloat(), y.toFloat())
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun endGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            doDispatchGesture(x, y, false)
            touchPath.reset()
            stroke = null
        } else {
            try {
                touchPath.lineTo(x.toFloat(), y.toFloat())
                var duration = System.currentTimeMillis() - lastTouchGestureStartTime
                if (duration <= 0) duration = 1
                val s = GestureDescription.StrokeDescription(touchPath, 0, duration)
                dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null)
            } catch (e: Exception) {
                Log.e(logTag, "endGesture error:$e")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Key event
    // -----------------------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.N)
    fun onKeyEvent(data: ByteArray) {
        val keyEvent = KeyEvent.parseFrom(data)
        val keyboardMode = keyEvent.getMode()
        var textToCommit: String? = null

        if (keyEvent.hasSeq()) {
            textToCommit = keyEvent.getSeq()
        } else if (keyboardMode == KeyboardMode.Legacy) {
            if (keyEvent.hasChr() && (keyEvent.getDown() || keyEvent.getPress())) {
                keyEvent.getChr()?.let { textToCommit = String(Character.toChars(it)) }
            }
        }

        var ke: KeyEventAndroid? = null
        if (Build.VERSION.SDK_INT < 33 || textToCommit == null) {
            ke = KeyEventConverter.toAndroidKeyEvent(keyEvent)
        }
        ke?.let {
            if (tryHandleVolumeKeyEvent(it)) return
            if (tryHandlePowerKeyEvent(it)) return
        }

        if (Build.VERSION.SDK_INT >= 33) {
            getInputMethod()?.getCurrentInputConnection()?.let { ic ->
                if (textToCommit != null) {
                    ic.commitText(textToCommit, 1, null)
                } else {
                    ke?.let { event ->
                        ic.sendKeyEvent(event)
                        if (keyEvent.getPress()) {
                            ic.sendKeyEvent(KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode))
                        }
                    }
                }
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                ke?.let { event ->
                    for (item in possibleAccessibiltyNodes()) {
                        if (trySendKeyEvent(event, item, textToCommit)) {
                            if (keyEvent.getPress()) {
                                trySendKeyEvent(
                                    KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode),
                                    item, textToCommit
                                )
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    private fun tryHandleVolumeKeyEvent(event: KeyEventAndroid): Boolean {
        when (event.keyCode) {
            KeyEventAndroid.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN)
                    volumeController.raiseVolume(null, true, AudioManager.STREAM_SYSTEM)
                return true
            }
            KeyEventAndroid.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN)
                    volumeController.lowerVolume(null, true, AudioManager.STREAM_SYSTEM)
                return true
            }
            KeyEventAndroid.KEYCODE_VOLUME_MUTE -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN)
                    volumeController.toggleMute(true, AudioManager.STREAM_SYSTEM)
                return true
            }
            else -> return false
        }
    }

    private fun tryHandlePowerKeyEvent(event: KeyEventAndroid): Boolean {
        if (event.keyCode == KeyEventAndroid.KEYCODE_POWER) {
            if (event.action == KeyEventAndroid.ACTION_UP)
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            return true
        }
        return false
    }

    // -----------------------------------------------------------------------
    // Text node helpers
    // -----------------------------------------------------------------------
    private fun insertAccessibilityNode(
        list: LinkedList<AccessibilityNodeInfo>, node: AccessibilityNodeInfo
    ) {
        if (!list.contains(node)) list.add(node)
    }

    private fun findChildNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isEditable && node.isFocusable) return node
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (child.isEditable && child.isFocusable) return child
                if (Build.VERSION.SDK_INT < 33) child.recycle()
            }
        }
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findChildNode(child)
                if (Build.VERSION.SDK_INT < 33) { if (child != result) child.recycle() }
                if (result != null) return result
            }
        }
        return null
    }

    private fun possibleAccessibiltyNodes(): LinkedList<AccessibilityNodeInfo> {
        val linkedList = LinkedList<AccessibilityNodeInfo>()
        val latestList = LinkedList<AccessibilityNodeInfo>()
        val focusInput = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val focusAccessibilityInput = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val rootInActiveWindow = getRootInActiveWindow()

        fun addNode(node: AccessibilityNodeInfo?, preferredList: LinkedList<AccessibilityNodeInfo>) {
            node ?: return
            if (node.isFocusable && node.isEditable) insertAccessibilityNode(linkedList, node)
            else insertAccessibilityNode(preferredList, node)
        }

        addNode(focusInput, latestList)
        addNode(focusAccessibilityInput, latestList)
        findChildNode(focusInput)?.let { insertAccessibilityNode(linkedList, it) }
        findChildNode(focusAccessibilityInput)?.let { insertAccessibilityNode(linkedList, it) }
        rootInActiveWindow?.let { insertAccessibilityNode(linkedList, it) }
        latestList.forEach { insertAccessibilityNode(linkedList, it) }
        return linkedList
    }

    private fun trySendKeyEvent(
        event: KeyEventAndroid, node: AccessibilityNodeInfo, textToCommit: String?
    ): Boolean {
        node.refresh()
        fakeEditTextForTextStateCalculation?.setSelection(0, 0)
        fakeEditTextForTextStateCalculation?.setText(null)

        val text = node.text
        var isShowingHint = false
        if (Build.VERSION.SDK_INT >= 26) isShowingHint = node.isShowingHintText

        var textSelectionStart = node.textSelectionStart
        var textSelectionEnd = node.textSelectionEnd
        if (text != null) {
            if (textSelectionStart > text.length) textSelectionStart = text.length
            if (textSelectionEnd > text.length) textSelectionEnd = text.length
            if (textSelectionStart > textSelectionEnd) textSelectionStart = textSelectionEnd
        }

        var success = false
        if (textToCommit != null) {
            if (textSelectionStart == -1 || textSelectionEnd == -1) {
                fakeEditTextForTextStateCalculation?.setText(textToCommit)
                success = updateTextForAccessibilityNode(node)
            } else if (text != null) {
                fakeEditTextForTextStateCalculation?.setText(text)
                fakeEditTextForTextStateCalculation?.setSelection(textSelectionStart, textSelectionEnd)
                fakeEditTextForTextStateCalculation?.text?.insert(textSelectionStart, textToCommit)
                success = updateTextAndSelectionForAccessibiltyNode(node)
            }
        } else {
            if (isShowingHint) fakeEditTextForTextStateCalculation?.setText(null)
            else fakeEditTextForTextStateCalculation?.setText(text)
            if (textSelectionStart != -1 && textSelectionEnd != -1) {
                fakeEditTextForTextStateCalculation?.setSelection(textSelectionStart, textSelectionEnd)
            }
            fakeEditTextForTextStateCalculation?.let {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                it.layout(rect.left, rect.top, rect.right, rect.bottom)
                it.onPreDraw()
                if (event.action == KeyEventAndroid.ACTION_DOWN) it.onKeyDown(event.keyCode, event)
                else if (event.action == KeyEventAndroid.ACTION_UP) it.onKeyUp(event.keyCode, event)
            }
            success = updateTextAndSelectionForAccessibiltyNode(node)
        }
        return success
    }

    fun updateTextForAccessibilityNode(node: AccessibilityNodeInfo): Boolean {
        fakeEditTextForTextStateCalculation?.text?.let {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, it.toString())
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    fun updateTextAndSelectionForAccessibiltyNode(node: AccessibilityNodeInfo): Boolean {
        val success = updateTextForAccessibilityNode(node)
        if (success) {
            val start = fakeEditTextForTextStateCalculation?.selectionStart
            val end = fakeEditTextForTextStateCalculation?.selectionEnd
            if (start != null && end != null) {
                val arguments = Bundle()
                arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
            }
        }
        return success
    }
}
