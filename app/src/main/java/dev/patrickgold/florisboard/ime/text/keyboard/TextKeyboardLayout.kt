/*
 * Copyright (C) 2021 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.text.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.animation.AccelerateInterpolator
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.prefs.AppPrefs
import dev.patrickgold.florisboard.app.prefs.florisPreferenceModel
import dev.patrickgold.florisboard.common.FlorisRect
import dev.patrickgold.florisboard.common.Pointer
import dev.patrickgold.florisboard.common.PointerMap
import dev.patrickgold.florisboard.common.android.isOrientationLandscape
import dev.patrickgold.florisboard.common.android.isOrientationPortrait
import dev.patrickgold.florisboard.common.observeAsTransformingState
import dev.patrickgold.florisboard.common.toIntOffset
import dev.patrickgold.florisboard.debug.LogTopic
import dev.patrickgold.florisboard.debug.flogDebug
import dev.patrickgold.florisboard.glideTypingManager
import dev.patrickgold.florisboard.ime.core.InputKeyEvent
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.RenderInfo
import dev.patrickgold.florisboard.ime.onehanded.OneHandedMode
import dev.patrickgold.florisboard.ime.popup.PopupUiController
import dev.patrickgold.florisboard.ime.popup.rememberPopupUiController
import dev.patrickgold.florisboard.ime.text.gestures.GlideTypingGesture
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.gestures.SwipeGesture
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.theme.FlorisImeTheme
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.snygg.ui.SnyggSurface
import dev.patrickgold.florisboard.snygg.ui.snyggBackground
import dev.patrickgold.florisboard.snygg.ui.solidColor
import dev.patrickgold.florisboard.snygg.ui.spSize
import dev.patrickgold.jetpref.datastore.model.observeAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TextKeyboardLayout(
    modifier: Modifier = Modifier,
    renderInfo: RenderInfo,
    isPreview: Boolean = false,
    isSmartbarKeyboard: Boolean = false,
): Unit = with(LocalDensity.current) {
    val prefs by florisPreferenceModel()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val glideTypingManager by context.glideTypingManager()

    val glideEnabled by prefs.glide.enabled.observeAsState()
    val glideShowTrail by prefs.glide.showTrail.observeAsState()

    val keyboard = renderInfo.keyboard
    val controller = remember { TextKeyboardLayoutController(context) }.also {
        it.keyboard = keyboard
        if (glideEnabled && !isSmartbarKeyboard && !isPreview && keyboard.mode == KeyboardMode.CHARACTERS) {
            val keys = keyboard.keys().asSequence().toList()
            glideTypingManager.setLayout(keys)
        }
    }
    val touchEventChannel = remember { Channel<MotionEvent>(64) }

    DisposableEffect(Unit) {
        controller.glideTypingDetector.registerListener(controller)
        controller.glideTypingDetector.registerListener(glideTypingManager)
        onDispose {
            controller.glideTypingDetector.unregisterListener(controller)
            controller.glideTypingDetector.unregisterListener(glideTypingManager)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(
                if (isSmartbarKeyboard) {
                    FlorisImeSizing.smartbarHeight
                } else {
                    FlorisImeSizing.keyboardRowBaseHeight * keyboard.rowCount
                }
            )
            .onGloballyPositioned { coords ->
                controller.size = coords.size.toSize()
            }
            .pointerInteropFilter { event ->
                if (isPreview) return@pointerInteropFilter false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_POINTER_UP,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        val clonedEvent = MotionEvent.obtain(event)
                        touchEventChannel
                            .trySend(clonedEvent)
                            .onFailure {
                                // Make sure to prevent MotionEvent memory leakage
                                // in case the input channel is full
                                clonedEvent.recycle()
                            }
                        return@pointerInteropFilter true
                    }
                }
                return@pointerInteropFilter false
            }
            .drawWithContent {
                drawContent()
                if (glideEnabled && glideShowTrail && !isSmartbarKeyboard) {
                    val targetDist = 3.0f
                    val radius = 20.0f

                    val radiusReductionFactor = 0.99f
                    if (controller.fadingGlideRadius > 0) {
                        controller.drawGlideTrail(
                            this,
                            controller.fadingGlide,
                            targetDist,
                            controller.fadingGlideRadius,
                            radiusReductionFactor
                        )
                    }
                    if (controller.isGliding && controller.glideDataForDrawing.isNotEmpty()) {
                        controller.drawGlideTrail(
                            this, controller.glideDataForDrawing, targetDist, radius, radiusReductionFactor
                        )
                    }
                }
            },
    ) {
        val keyMarginH by prefs.keyboard.keySpacingHorizontal.observeAsTransformingState { it.dp.toPx() }
        val keyMarginV by prefs.keyboard.keySpacingVertical.observeAsTransformingState { it.dp.toPx() }
        val desiredKey = remember { TextKey(data = TextKeyData.UNSPECIFIED) }
        val keyboardWidth = constraints.maxWidth.toFloat()
        val keyboardHeight = constraints.maxHeight.toFloat()
        desiredKey.touchBounds.apply {
            if (isSmartbarKeyboard) {
                width = keyboardWidth / 8f
                height = FlorisImeSizing.smartbarHeight.toPx()
            } else {
                width = keyboardWidth / 10f
                height = FlorisImeSizing.keyboardRowBaseHeight.toPx()
            }
        }
        desiredKey.visibleBounds.applyFrom(desiredKey.touchBounds).deflateBy(keyMarginH, keyMarginV)
        keyboard.layout(keyboardWidth, keyboardHeight, desiredKey)

        val fontSizeMultiplier = prefs.keyboard.fontSizeMultiplier()
        val popupUiController = rememberPopupUiController(
            boundsProvider = { key ->
                val keyPopupWidth: Float
                val keyPopupHeight: Float
                when {
                    configuration.isOrientationLandscape() -> {
                        if (isSmartbarKeyboard) {
                            keyPopupWidth = key.visibleBounds.width * 1.0f
                            keyPopupHeight = desiredKey.visibleBounds.height * 3.0f * 1.2f
                        } else {
                            keyPopupWidth = desiredKey.visibleBounds.width * 1.0f
                            keyPopupHeight = desiredKey.visibleBounds.height * 3.0f
                        }
                    }
                    else -> {
                        if (isSmartbarKeyboard) {
                            keyPopupWidth = key.visibleBounds.width * 1.1f
                            keyPopupHeight = desiredKey.visibleBounds.height * 2.5f * 1.2f
                        } else {
                            keyPopupWidth = desiredKey.visibleBounds.width * 1.1f
                            keyPopupHeight = desiredKey.visibleBounds.height * 2.5f
                        }
                    }
                }
                val keyPopupDiffX = (key.visibleBounds.width - keyPopupWidth) / 2.0f
                FlorisRect.new().apply {
                    left = key.visibleBounds.left + keyPopupDiffX
                    top = key.visibleBounds.bottom - keyPopupHeight
                    right = left + keyPopupWidth
                    bottom = top + keyPopupHeight
                }
            },
        )
        popupUiController.evaluator = renderInfo.evaluator
        popupUiController.fontSizeMultiplier = fontSizeMultiplier
        popupUiController.keyHintConfiguration = prefs.keyboard.keyHintConfiguration()
        controller.popupUiController = popupUiController
        for (textKey in keyboard.keys()) {
            TextKeyButton(textKey, renderInfo, fontSizeMultiplier, isSmartbarKeyboard)
        }

        popupUiController.RenderPopups()
    }

    LaunchedEffect(Unit) {
        for (event in touchEventChannel) {
            if (!isActive) break
            controller.onTouchEventInternal(event)
            event.recycle()
        }
    }
}

@Composable
private fun TextKeyButton(
    key: TextKey,
    renderInfo: RenderInfo,
    fontSizeMultiplier: Float,
    isSmartbarKey: Boolean,
) = with(LocalDensity.current) {
    val keyStyle = FlorisImeTheme.style.get(
        element = if (isSmartbarKey) FlorisImeUi.SmartbarKey else FlorisImeUi.Key,
        code = key.computedData.code,
        mode = renderInfo.state.inputMode.value,
        isPressed = key.isPressed && key.isEnabled,
        isDisabled = !key.isEnabled,
    )
    val fontSize = keyStyle.fontSize.spSize() * fontSizeMultiplier * when (key.computedData.code) {
        KeyCode.VIEW_CHARACTERS,
        KeyCode.VIEW_SYMBOLS,
        KeyCode.VIEW_SYMBOLS2 -> 0.80f
        KeyCode.VIEW_NUMERIC,
        KeyCode.VIEW_NUMERIC_ADVANCED -> 0.55f
        else -> 1.0f
    }
    SnyggSurface(
        modifier = Modifier
            .requiredSize(key.visibleBounds.size.toDpSize())
            .absoluteOffset { key.visibleBounds.topLeft.toIntOffset() },
        background = keyStyle.background,
        clip = true,
        shape = keyStyle.shape,
    ) {
        key.label?.let { label ->
            Text(
                modifier = Modifier
                    .wrapContentSize()
                    .align(Alignment.Center),
                text = label,
                color = keyStyle.foreground.solidColor(),
                fontSize = fontSize,
                maxLines = if (key.computedData.code == KeyCode.VIEW_NUMERIC_ADVANCED) 2 else 1,
                softWrap = key.computedData.code == KeyCode.VIEW_NUMERIC_ADVANCED,
                overflow = when (key.computedData.code) {
                    KeyCode.SPACE -> TextOverflow.Ellipsis
                    else -> TextOverflow.Visible
                },
            )
        }
        key.hintedLabel?.let { hintedLabel ->
            val keyHintStyle = FlorisImeTheme.style.get(
                element = FlorisImeUi.KeyHint,
                code = key.computedHintData.code,
                mode = renderInfo.state.inputMode.value,
                isPressed = key.isPressed,
            )
            val hintFontSize = keyHintStyle.fontSize.spSize() * fontSizeMultiplier
            Text(
                modifier = Modifier
                    .padding(end = (key.visibleBounds.width / 12f).toDp())
                    .wrapContentSize()
                    .align(Alignment.TopEnd)
                    .snyggBackground(keyHintStyle.background, keyHintStyle.shape),
                text = hintedLabel,
                color = keyHintStyle.foreground.solidColor(),
                fontFamily = FontFamily.Monospace,
                fontSize = hintFontSize,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
        key.foregroundDrawableId?.let { drawableId ->
            Icon(
                modifier = Modifier
                    .requiredSize(fontSize.toDp() * 1.1f)
                    .align(Alignment.Center),
                painter = painterResource(drawableId),
                contentDescription = null,
                tint = keyStyle.foreground.solidColor(),
            )
        }
    }
}

@Suppress("unused_parameter")
private class TextKeyboardLayoutController(
    context: Context,
) : SwipeGesture.Listener, GlideTypingGesture.Listener {
    private val prefs by florisPreferenceModel()
    private val keyboardManager by context.keyboardManager()

    private val activeEditorInstance get() = FlorisImeService.activeEditorInstance()
    private val activeState get() = keyboardManager.activeState
    private val inputEventDispatcher get() = keyboardManager.inputEventDispatcher
    private val inputFeedbackController get() = FlorisImeService.inputFeedbackController()
    private val keyHintConfiguration = prefs.keyboard.keyHintConfiguration()
    private val pointerMap: PointerMap<TouchPointer> = PointerMap { TouchPointer() }
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    lateinit var popupUiController: PopupUiController

    private var initSelectionStart: Int = 0
    private var initSelectionEnd: Int = 0
    var isGliding: Boolean = false

    val glideTypingDetector = GlideTypingGesture.Detector(context)
    val glideDataForDrawing = mutableStateListOf<Pair<GlideTypingGesture.Detector.Position, Long>>()
    val fadingGlide = mutableStateListOf<Pair<GlideTypingGesture.Detector.Position, Long>>()
    var fadingGlideRadius by mutableStateOf(0.0f)
    private val swipeGestureDetector = SwipeGesture.Detector(this)

    lateinit var keyboard: TextKeyboard
    var size = Size.Zero

    fun onTouchEventInternal(event: MotionEvent) {
        flogDebug { "event=$event" }
        swipeGestureDetector.onTouchEvent(event)

        if (prefs.glide.enabled.get() && keyboard.mode == KeyboardMode.CHARACTERS) {
            val glidePointer = pointerMap.findById(0)
            if (glideTypingDetector.onTouchEvent(event, glidePointer?.initialKey)) {
                for (pointer in pointerMap) {
                    if (pointer.activeKey != null) {
                        onTouchCancelInternal(event, pointer)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pointerMap.clear()
                }
                isGliding = true
                return
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val pointer = pointerMap.add(pointerId, pointerIndex)
                if (pointer != null) {
                    swipeGestureDetector.onTouchDown(event, pointer)
                    onTouchDownInternal(event, pointer)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val oldPointer = pointerMap.findById(pointerId)
                if (oldPointer != null) {
                    swipeGestureDetector.onTouchCancel(event, oldPointer)
                    onTouchCancelInternal(event, oldPointer)
                    pointerMap.removeById(oldPointer.id)
                }
                // Search for active character keys and cancel them
                for (pointer in pointerMap) {
                    val activeKey = pointer.activeKey
                    if (activeKey != null && popupUiController.isSuitableForPopups(activeKey)) {
                        swipeGestureDetector.onTouchCancel(event, pointer)
                        onTouchUpInternal(event, pointer)
                    }
                }
                val pointer = pointerMap.add(pointerId, pointerIndex)
                if (pointer != null) {
                    swipeGestureDetector.onTouchDown(event, pointer)
                    onTouchDownInternal(event, pointer)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (pointerIndex in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(pointerIndex)
                    val pointer = pointerMap.findById(pointerId)
                    if (pointer != null) {
                        pointer.index = pointerIndex
                        val alwaysTriggerOnMove = (pointer.hasTriggeredGestureMove
                            && (pointer.initialKey?.computedData?.code == KeyCode.DELETE
                            && prefs.gestures.deleteKeySwipeLeft.get() == SwipeAction.DELETE_CHARACTERS_PRECISELY
                            || pointer.initialKey?.computedData?.code == KeyCode.SPACE
                            || pointer.initialKey?.computedData?.code == KeyCode.CJK_SPACE))
                        if (swipeGestureDetector.onTouchMove(
                                event,
                                pointer,
                                alwaysTriggerOnMove
                            ) || pointer.hasTriggeredGestureMove
                        ) {
                            pointer.longPressJob?.cancel()
                            pointer.longPressJob = null
                            pointer.hasTriggeredGestureMove = true
                            pointer.activeKey?.let { activeKey ->
                                activeKey.isPressed = false
                                if (inputEventDispatcher.isPressed(activeKey.computedData.code)) {
                                    inputEventDispatcher.send(InputKeyEvent.cancel(activeKey.computedData))
                                }
                            }
                        } else {
                            onTouchMoveInternal(event, pointer)
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val pointer = pointerMap.findById(pointerId)
                if (pointer != null) {
                    pointer.index = pointerIndex
                    if (swipeGestureDetector.onTouchUp(
                            event,
                            pointer
                        ) || pointer.hasTriggeredGestureMove || pointer.shouldBlockNextUp
                    ) {
                        if (pointer.hasTriggeredGestureMove && pointer.initialKey?.computedData?.code == KeyCode.DELETE) {
                            activeEditorInstance?.apply {
                                if (selection.isSelectionMode) {
                                    deleteBackwards()
                                }
                            }
                        }
                        onTouchCancelInternal(event, pointer)
                    } else {
                        onTouchUpInternal(event, pointer)
                    }
                    pointerMap.removeById(pointer.id)
                }
            }
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                for (pointer in pointerMap) {
                    if (pointer.id == pointerId) {
                        pointer.index = pointerIndex
                        if (swipeGestureDetector.onTouchUp(
                                event,
                                pointer
                            ) || pointer.hasTriggeredGestureMove || pointer.shouldBlockNextUp
                        ) {
                            if (pointer.hasTriggeredGestureMove && pointer.initialKey?.computedData?.code == KeyCode.DELETE) {
                                activeEditorInstance?.apply {
                                    if (selection.isSelectionMode) {
                                        deleteBackwards()
                                    }
                                }
                            }
                            onTouchCancelInternal(event, pointer)
                        } else {
                            onTouchUpInternal(event, pointer)
                        }
                    } else {
                        swipeGestureDetector.onTouchCancel(event, pointer)
                        onTouchCancelInternal(event, pointer)
                    }
                }
                pointerMap.clear()
            }
            MotionEvent.ACTION_CANCEL -> {
                for (pointer in pointerMap) {
                    swipeGestureDetector.onTouchCancel(event, pointer)
                    onTouchCancelInternal(event, pointer)
                }
                pointerMap.clear()
            }
        }
    }

    private fun onTouchDownInternal(event: MotionEvent, pointer: TouchPointer) {
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW) { "pointer=$pointer" }

        val key = keyboard.getKeyForPos(event.getX(pointer.index), event.getY(pointer.index))
        if (key != null && key.isEnabled) {
            inputEventDispatcher.send(InputKeyEvent.down(key.computedData))
            if (prefs.keyboard.popupEnabled.get() && popupUiController.isSuitableForPopups(key)) {
                popupUiController.show(key)
            }
            inputFeedbackController?.keyPress(key.computedData)
            key.isPressed = true
            if (pointer.initialKey == null) {
                pointer.initialKey = key
            }
            pointer.activeKey = key
            pointer.longPressJob = scope.launch {
                val delayMillis = prefs.keyboard.longPressDelay.get().toLong()
                when (key.computedData.code) {
                    KeyCode.SPACE, KeyCode.CJK_SPACE -> {
                        activeEditorInstance?.run {
                            initSelectionStart = selection.start
                            initSelectionEnd = selection.end
                        }
                        delay((delayMillis * 2.5f).toLong())
                        when (prefs.gestures.spaceBarLongPress.get()) {
                            SwipeAction.NO_ACTION,
                            SwipeAction.INSERT_SPACE -> {
                            }
                            else -> {
                                keyboardManager.executeSwipeAction(prefs.gestures.spaceBarLongPress.get())
                                pointer.shouldBlockNextUp = true
                            }
                        }
                    }
                    KeyCode.SHIFT -> {
                        delay((delayMillis * 2.5f).toLong())
                        inputEventDispatcher.send(InputKeyEvent.downUp(TextKeyData.CAPS_LOCK))
                        inputFeedbackController?.keyLongPress(key.computedData)
                    }
                    KeyCode.LANGUAGE_SWITCH -> {
                        delay((delayMillis * 2.0f).toLong())
                        pointer.shouldBlockNextUp = true
                        inputEventDispatcher.send(InputKeyEvent.downUp(TextKeyData.SYSTEM_INPUT_METHOD_PICKER))
                    }
                    else -> {
                        delay(delayMillis)
                        if (popupUiController.isSuitableForPopups(key) && key.computedPopups.getPopupKeys(
                                keyHintConfiguration
                            ).isNotEmpty()
                        ) {
                            popupUiController.extend(key, size)
                            inputFeedbackController?.keyLongPress(key.computedData)
                        }
                    }
                }
            }
        } else {
            pointer.activeKey = null
        }
    }

    private fun onTouchMoveInternal(event: MotionEvent, pointer: TouchPointer) {
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW) { "pointer=$pointer" }

        val initialKey = pointer.initialKey
        val activeKey = pointer.activeKey
        if (initialKey != null && activeKey != null) {
            if (popupUiController.isShowingExtendedPopup) {
                val x = event.getX(pointer.index)
                val y = event.getY(pointer.index)
                if (!popupUiController.propagateMotionEvent(activeKey, x, y)) {
                    onTouchCancelInternal(event, pointer)
                    onTouchDownInternal(event, pointer)
                }
            } else {
                if ((event.getX(pointer.index) < activeKey.visibleBounds.left - 0.1f * activeKey.visibleBounds.width)
                    || (event.getX(pointer.index) > activeKey.visibleBounds.right + 0.1f * activeKey.visibleBounds.width)
                    || (event.getY(pointer.index) < activeKey.visibleBounds.top - 0.35f * activeKey.visibleBounds.height)
                    || (event.getY(pointer.index) > activeKey.visibleBounds.bottom + 0.35f * activeKey.visibleBounds.height)
                ) {
                    onTouchCancelInternal(event, pointer)
                    onTouchDownInternal(event, pointer)
                }
            }
        }
    }

    private fun onTouchUpInternal(event: MotionEvent, pointer: TouchPointer) {
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW) { "pointer=$pointer" }
        pointer.longPressJob?.cancel()
        pointer.longPressJob = null

        val initialKey = pointer.initialKey
        val activeKey = pointer.activeKey
        if (initialKey != null && activeKey != null) {
            activeKey.isPressed = false
            if (popupUiController.isSuitableForPopups(activeKey)) {
                val retData = popupUiController.getActiveKeyData(activeKey)
                if (retData != null && !pointer.hasTriggeredGestureMove) {
                    if (retData == activeKey.computedData) {
                        inputEventDispatcher.send(InputKeyEvent.up(activeKey.computedData))
                    } else {
                        if (inputEventDispatcher.isPressed(activeKey.computedData.code)) {
                            inputEventDispatcher.send(InputKeyEvent.cancel(activeKey.computedData))
                        }
                        inputEventDispatcher.send(InputKeyEvent.downUp(retData))
                    }
                } else {
                    if (inputEventDispatcher.isPressed(activeKey.computedData.code)) {
                        inputEventDispatcher.send(InputKeyEvent.cancel(activeKey.computedData))
                    }
                }
                popupUiController.hide()
            } else {
                if (pointer.hasTriggeredGestureMove) {
                    inputEventDispatcher.send(InputKeyEvent.cancel(activeKey.computedData))
                } else {
                    inputEventDispatcher.send(InputKeyEvent.up(activeKey.computedData))
                }
            }
            pointer.activeKey = null
        }
        pointer.hasTriggeredGestureMove = false
        pointer.shouldBlockNextUp = false
    }

    private fun onTouchCancelInternal(event: MotionEvent, pointer: TouchPointer) {
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW) { "pointer=$pointer" }
        pointer.longPressJob?.cancel()
        pointer.longPressJob = null

        val activeKey = pointer.activeKey
        if (activeKey != null) {
            activeKey.isPressed = false
            inputEventDispatcher.send(InputKeyEvent.cancel(activeKey.computedData))
            if (popupUiController.isSuitableForPopups(activeKey)) {
                popupUiController.hide()
            }
            pointer.activeKey = null
        }
        pointer.hasTriggeredGestureMove = false
        pointer.shouldBlockNextUp = false
    }

    override fun onSwipe(event: SwipeGesture.Event): Boolean {
        val pointer = pointerMap.findById(event.pointerId) ?: return false
        val initialKey = pointer.initialKey ?: return false
        val activeKey = pointer.activeKey
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW)

        return when (initialKey.computedData.code) {
            KeyCode.DELETE -> handleDeleteSwipe(event)
            KeyCode.SPACE, KeyCode.CJK_SPACE -> handleSpaceSwipe(event)
            else -> when {
                (initialKey.computedData.code == KeyCode.SHIFT && activeKey?.computedData?.code == KeyCode.SPACE ||
                    initialKey.computedData.code == KeyCode.SHIFT && activeKey?.computedData?.code == KeyCode.CJK_SPACE) &&
                    event.type == SwipeGesture.Type.TOUCH_MOVE -> handleSpaceSwipe(event)
                initialKey.computedData.code == KeyCode.SHIFT && activeKey?.computedData?.code != KeyCode.SHIFT &&
                    event.type == SwipeGesture.Type.TOUCH_UP -> {
                    activeKey?.let {
                        inputEventDispatcher.send(
                            InputKeyEvent.up(popupUiController.getActiveKeyData(it) ?: it.computedData)
                        )
                    }
                    inputEventDispatcher.send(InputKeyEvent.cancel(TextKeyData.SHIFT))
                    true
                }
                initialKey.computedData.code > KeyCode.SPACE && !popupUiController.isShowingExtendedPopup -> when {
                    !prefs.glide.enabled.get() && !pointer.hasTriggeredGestureMove -> when (event.type) {
                        SwipeGesture.Type.TOUCH_UP -> {
                            val swipeAction = when (event.direction) {
                                SwipeGesture.Direction.UP -> prefs.gestures.swipeUp.get()
                                SwipeGesture.Direction.DOWN -> prefs.gestures.swipeDown.get()
                                SwipeGesture.Direction.LEFT -> prefs.gestures.swipeLeft.get()
                                SwipeGesture.Direction.RIGHT -> prefs.gestures.swipeRight.get()
                                else -> SwipeAction.NO_ACTION
                            }
                            if (swipeAction != SwipeAction.NO_ACTION) {
                                keyboardManager.executeSwipeAction(swipeAction)
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                    else -> false
                }
                else -> false
            }
        }
    }

    private fun handleDeleteSwipe(event: SwipeGesture.Event): Boolean {
        if (activeState.isRawInputEditor) return false
        val pointer = pointerMap.findById(event.pointerId) ?: return false

        return when (event.type) {
            SwipeGesture.Type.TOUCH_MOVE -> when (prefs.gestures.deleteKeySwipeLeft.get()) {
                SwipeAction.DELETE_CHARACTERS_PRECISELY -> {
                    activeEditorInstance?.apply {
                        if (abs(event.relUnitCountX) > 0) {
                            inputFeedbackController?.gestureMovingSwipe(TextKeyData.DELETE)
                        }
                        markComposingRegion(null)
                        if (selection.isValid) {
                            selection.updateAndNotify(
                                (selection.end + event.absUnitCountX + 1).coerceIn(0, selection.end),
                                selection.end
                            )
                        }
                    }
                    pointer.shouldBlockNextUp = true
                    true
                }
                SwipeAction.DELETE_WORDS_PRECISELY -> {
                    activeEditorInstance?.apply {
                        if (abs(event.relUnitCountX) > 0) {
                            inputFeedbackController?.gestureMovingSwipe(TextKeyData.DELETE)
                        }
                        markComposingRegion(null)
                        if (selection.isValid) {
                            selectionSetNWordsLeft(abs(event.absUnitCountX / 2) - 1)
                        }
                    }
                    pointer.shouldBlockNextUp = true
                    true
                }
                else -> false
            }
            SwipeGesture.Type.TOUCH_UP -> {
                if (event.direction == SwipeGesture.Direction.LEFT &&
                    prefs.gestures.deleteKeySwipeLeft.get() == SwipeAction.DELETE_WORD
                ) {
                    keyboardManager.executeSwipeAction(prefs.gestures.deleteKeySwipeLeft.get())
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun handleSpaceSwipe(event: SwipeGesture.Event): Boolean {
        val pointer = pointerMap.findById(event.pointerId) ?: return false

        return when (event.type) {
            SwipeGesture.Type.TOUCH_MOVE -> when (event.direction) {
                SwipeGesture.Direction.LEFT -> {
                    if (prefs.gestures.spaceBarSwipeLeft.get() == SwipeAction.MOVE_CURSOR_LEFT) {
                        abs(event.relUnitCountX).let {
                            val count = if (!pointer.hasTriggeredGestureMove) {
                                it - 1
                            } else {
                                it
                            }
                            if (count > 0) {
                                inputFeedbackController?.gestureMovingSwipe(TextKeyData.SPACE)
                                inputEventDispatcher.send(
                                    InputKeyEvent.downUp(
                                        TextKeyData.ARROW_LEFT, count
                                    )
                                )
                            }
                        }
                    }
                    true
                }
                SwipeGesture.Direction.RIGHT -> {
                    if (prefs.gestures.spaceBarSwipeRight.get() == SwipeAction.MOVE_CURSOR_RIGHT) {
                        abs(event.relUnitCountX).let {
                            val count = if (!pointer.hasTriggeredGestureMove) {
                                it - 1
                            } else {
                                it
                            }
                            if (count > 0) {
                                inputFeedbackController?.gestureMovingSwipe(TextKeyData.SPACE)
                                inputEventDispatcher.send(
                                    InputKeyEvent.downUp(
                                        TextKeyData.ARROW_RIGHT, count
                                    )
                                )
                            }
                        }
                    }
                    true
                }
                else -> true // To prevent the popup display of nearby keys
            }
            SwipeGesture.Type.TOUCH_UP -> when (event.direction) {
                SwipeGesture.Direction.LEFT -> {
                    prefs.gestures.spaceBarSwipeLeft.get().let {
                        if (it != SwipeAction.MOVE_CURSOR_LEFT) {
                            keyboardManager.executeSwipeAction(it)
                            true
                        } else {
                            false
                        }
                    }
                }
                SwipeGesture.Direction.RIGHT -> {
                    prefs.gestures.spaceBarSwipeRight.get().let {
                        if (it != SwipeAction.MOVE_CURSOR_RIGHT) {
                            keyboardManager.executeSwipeAction(it)
                            true
                        } else {
                            false
                        }
                    }
                }
                else -> {
                    if (event.absUnitCountY < -6) {
                        keyboardManager.executeSwipeAction(prefs.gestures.spaceBarSwipeUp.get())
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    override fun onGlideAddPoint(point: GlideTypingGesture.Detector.Position) {
        if (prefs.glide.enabled.get()) {
            glideDataForDrawing.add(point to System.currentTimeMillis())
        }
    }

    override fun onGlideComplete(data: GlideTypingGesture.Detector.PointerData) {
        onGlideCancelled()
    }

    override fun onGlideCancelled() {
        if (prefs.glide.showTrail.get()) {
            fadingGlide.clear()
            fadingGlide.addAll(glideDataForDrawing)

            val animator = ValueAnimator.ofFloat(20.0f, 0.0f)
            animator.interpolator = AccelerateInterpolator()
            animator.duration = prefs.glide.trailDuration.get().toLong()
            animator.addUpdateListener {
                fadingGlideRadius = it.animatedValue as Float
            }
            animator.start()

            glideDataForDrawing.clear()
            isGliding = false
        }
    }

    fun drawGlideTrail(
        drawScope: ContentDrawScope,
        gestureData: MutableList<Pair<GlideTypingGesture.Detector.Position, Long>>,
        targetDist: Float,
        initialRadius: Float,
        radiusReductionFactor: Float,
    ) {
        var radius = initialRadius
        var drawnPoints = 0
        var prevX = gestureData.lastOrNull()?.first?.x ?: 0.0f
        var prevY = gestureData.lastOrNull()?.first?.y ?: 0.0f
        val time = System.currentTimeMillis()

        outer@ for (i in gestureData.size - 1 downTo 1) {
            if (time - gestureData[i - 1].second > prefs.glide.trailDuration.get()) break

            val dx = prevX - gestureData[i - 1].first.x
            val dy = prevY - gestureData[i - 1].first.y
            val dist = sqrt(dx * dx + dy * dy)

            val numPoints = (dist / targetDist).toInt()
            for (j in 0 until numPoints) {
                radius *= radiusReductionFactor
                val intermediateX =
                    gestureData[i].first.x * (1 - j.toFloat() / numPoints) + gestureData[i - 1].first.x * (j.toFloat() / numPoints)
                val intermediateY =
                    gestureData[i].first.y * (1 - j.toFloat() / numPoints) + gestureData[i - 1].first.y * (j.toFloat() / numPoints)
                drawScope.drawCircle(Color.Green, radius, center = Offset(intermediateX, intermediateY))
                drawnPoints += 1
                prevX = intermediateX
                prevY = intermediateY
            }
        }
    }

    private class TouchPointer : Pointer() {
        var initialKey: TextKey? = null
        var activeKey: TextKey? = null
        var longPressJob: Job? = null
        var hasTriggeredGestureMove: Boolean = false
        var shouldBlockNextUp: Boolean = false

        override fun reset() {
            super.reset()
            initialKey = null
            activeKey = null
            longPressJob?.cancel()
            longPressJob = null
            hasTriggeredGestureMove = false
            shouldBlockNextUp = false
        }

        override fun toString(): String {
            return "${TouchPointer::class.simpleName} { id=$id, index=$index, initialKey=$initialKey, activeKey=$activeKey }"
        }
    }
}

@Composable
private fun AppPrefs.Keyboard.fontSizeMultiplier(): Float {
    val configuration = LocalConfiguration.current
    val oneHandedMode by oneHandedMode.observeAsState()
    val oneHandedModeFactor by oneHandedModeScaleFactor.observeAsTransformingState { it / 100.0f }
    val fontSizeMultiplierBase by if (configuration.isOrientationPortrait()) {
        fontSizeMultiplierPortrait
    } else {
        fontSizeMultiplierLandscape
    }.observeAsTransformingState { it / 100.0f }
    val fontSizeMultiplier = fontSizeMultiplierBase * if (oneHandedMode != OneHandedMode.OFF && configuration.isOrientationPortrait()) {
        oneHandedModeFactor
    } else {
        1.0f
    }
    return fontSizeMultiplier
}
