package com.stb.handdraw.input

import android.view.KeyEvent
import com.stb.handdraw.hand.UserHand

enum class ScreenMode { CAMERA, SETTINGS, BLOCKING }

sealed interface RemoteCommand {
    data object StrokeStart : RemoteCommand
    data object StrokeEnd : RemoteCommand
    data object ToggleHand : RemoteCommand
    data class SetHand(val hand: UserHand) : RemoteCommand
    data object CycleColor : RemoteCommand
    data class CycleColorBy(val delta: Int) : RemoteCommand
    data object CycleThickness : RemoteCommand
    data class CycleThicknessBy(val delta: Int) : RemoteCommand
    data object Undo : RemoteCommand
    data object ClearImmediate : RemoteCommand
    data object AskClearOrExit : RemoteCommand
    data object Exit : RemoteCommand
    data object OpenSettings : RemoteCommand
    data object CloseSettings : RemoteCommand
    data object SettingsUp : RemoteCommand
    data object SettingsDown : RemoteCommand
    data class SettingsNudge(val delta: Int) : RemoteCommand
    data object CycleModel : RemoteCommand
    data object ToggleDelegate : RemoteCommand
    data object ToggleResolution : RemoteCommand
    data object ToggleNumHands : RemoteCommand
    data object ToggleMirror : RemoteCommand
}

sealed interface KeyResult {
    data class Consume(val command: RemoteCommand? = null) : KeyResult
    data object Pass : KeyResult
}

/**
 * Traduce el mando. La pulsación larga se detecta con [longPress] o con
 * [LONG_REPEAT] repeticiones, y la suelta corta no repite la acción.
 */
class RemoteDecoder {
    private var drawHeld = false
    private var leftLong = false
    private var rightLong = false
    private var backLong = false

    fun reset() {
        drawHeld = false
        leftLong = false
        rightLong = false
        backLong = false
    }

    fun onKey(
        mode: ScreenMode,
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        longPress: Boolean,
    ): KeyResult {
        if (mode == ScreenMode.BLOCKING) return KeyResult.Pass
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return KeyResult.Pass

        if (isDrawKey(keyCode)) return drawKey(mode, action, repeatCount)
        val shortcut = if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) shortcut(keyCode) else null
        if (shortcut != null && mode == ScreenMode.CAMERA) return KeyResult.Consume(shortcut)
        if (shortcut != null && mode == ScreenMode.SETTINGS) return KeyResult.Consume(shortcut)
        if (mode == ScreenMode.SETTINGS) return settings(action, keyCode, repeatCount, longPress)
        return camera(action, keyCode, repeatCount, longPress)
    }

    private fun drawKey(mode: ScreenMode, action: Int, repeatCount: Int): KeyResult {
        if (mode == ScreenMode.SETTINGS) {
            if (action == KeyEvent.ACTION_UP && drawHeld) {
                drawHeld = false
                return KeyResult.Consume(RemoteCommand.StrokeEnd)
            }
            if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                return KeyResult.Consume(RemoteCommand.SettingsNudge(1))
            }
            return KeyResult.Consume(null)
        }
        if (action == KeyEvent.ACTION_DOWN) {
            val start = repeatCount == 0 && !drawHeld
            drawHeld = true
            return KeyResult.Consume(if (start) RemoteCommand.StrokeStart else null)
        }
        drawHeld = false
        return KeyResult.Consume(RemoteCommand.StrokeEnd)
    }

    private fun camera(action: Int, keyCode: Int, repeatCount: Int, longPress: Boolean): KeyResult {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> return hold(
                action, repeatCount, longPress,
                isLong = { leftLong },
                setLong = { leftLong = it },
                longCommand = RemoteCommand.OpenSettings,
                shortCommand = RemoteCommand.ToggleHand,
            )
            KeyEvent.KEYCODE_DPAD_RIGHT -> return hold(
                action, repeatCount, longPress,
                isLong = { rightLong },
                setLong = { rightLong = it },
                longCommand = RemoteCommand.CycleThickness,
                shortCommand = RemoteCommand.CycleColor,
            )
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                    return KeyResult.Consume(RemoteCommand.Undo)
                }
                return KeyResult.Consume(null)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                    return KeyResult.Consume(RemoteCommand.ClearImmediate)
                }
                return KeyResult.Consume(null)
            }
            KeyEvent.KEYCODE_BACK -> return back(action, repeatCount, longPress, RemoteCommand.AskClearOrExit)
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_BOOKMARK,
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_NUMPAD_0,
            -> {
                if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                    return KeyResult.Consume(RemoteCommand.OpenSettings)
                }
                return KeyResult.Consume(null)
            }
        }
        return KeyResult.Pass
    }

    private fun settings(action: Int, keyCode: Int, repeatCount: Int, longPress: Boolean): KeyResult {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (action == KeyEvent.ACTION_DOWN) return KeyResult.Consume(RemoteCommand.SettingsUp)
                return KeyResult.Consume(null)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (action == KeyEvent.ACTION_DOWN) return KeyResult.Consume(RemoteCommand.SettingsDown)
                return KeyResult.Consume(null)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                    return KeyResult.Consume(RemoteCommand.SettingsNudge(-1))
                }
                return KeyResult.Consume(null)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                    return KeyResult.Consume(RemoteCommand.SettingsNudge(1))
                }
                return KeyResult.Consume(null)
            }
            KeyEvent.KEYCODE_BACK -> return back(action, repeatCount, longPress, RemoteCommand.CloseSettings)
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_BOOKMARK,
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_NUMPAD_0,
            -> {
                if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                    return KeyResult.Consume(RemoteCommand.CloseSettings)
                }
                return KeyResult.Consume(null)
            }
        }
        return KeyResult.Pass
    }

    private fun back(
        action: Int,
        repeatCount: Int,
        longPress: Boolean,
        shortCommand: RemoteCommand,
    ): KeyResult {
        if (action == KeyEvent.ACTION_DOWN) {
            if (repeatCount == 0) backLong = false
            if (!backLong && (longPress || repeatCount >= LONG_REPEAT)) {
                backLong = true
                return KeyResult.Consume(RemoteCommand.Exit)
            }
            return KeyResult.Consume(null)
        }
        val command = if (backLong) null else shortCommand
        backLong = false
        return KeyResult.Consume(command)
    }

    private fun hold(
        action: Int,
        repeatCount: Int,
        longPress: Boolean,
        isLong: () -> Boolean,
        setLong: (Boolean) -> Unit,
        longCommand: RemoteCommand,
        shortCommand: RemoteCommand,
    ): KeyResult {
        if (action == KeyEvent.ACTION_DOWN) {
            if (repeatCount == 0) setLong(false)
            if (!isLong() && (longPress || repeatCount >= LONG_REPEAT)) {
                setLong(true)
                return KeyResult.Consume(longCommand)
            }
            return KeyResult.Consume(null)
        }
        val command = if (isLong()) null else shortCommand
        setLong(false)
        return KeyResult.Consume(command)
    }

    private fun shortcut(keyCode: Int): RemoteCommand? = when (keyCode) {
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> RemoteCommand.SetHand(UserHand.RIGHT)
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> RemoteCommand.SetHand(UserHand.LEFT)
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3, KeyEvent.KEYCODE_PROG_YELLOW -> RemoteCommand.CycleThickness
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4, KeyEvent.KEYCODE_PROG_BLUE -> RemoteCommand.CycleModel
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5, KeyEvent.KEYCODE_PROG_GREEN -> RemoteCommand.ToggleDelegate
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6, KeyEvent.KEYCODE_PROG_RED -> RemoteCommand.ToggleResolution
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        -> RemoteCommand.ToggleNumHands
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> RemoteCommand.ToggleMirror
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> RemoteCommand.CycleColor
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_MEDIA_REWIND -> RemoteCommand.Undo
        KeyEvent.KEYCODE_MEDIA_STOP -> RemoteCommand.ClearImmediate
        else -> null
    }

    private companion object {
        const val LONG_REPEAT = 6

        fun isDrawKey(keyCode: Int): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            -> true
            else -> false
        }
    }
}
