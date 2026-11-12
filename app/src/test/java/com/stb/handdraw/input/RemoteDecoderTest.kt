package com.stb.handdraw.input

import android.view.KeyEvent
import com.stb.handdraw.hand.UserHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDecoderTest {
    private val decoder = RemoteDecoder()

    @Test
    fun centerHoldDrawsOnceUntilRelease() {
        assertEquals(RemoteCommand.StrokeStart, command(down(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 0)))
        assertNull(command(down(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 4)))
        assertEquals(RemoteCommand.StrokeEnd, command(up(KeyEvent.KEYCODE_DPAD_CENTER)))
        assertEquals(RemoteCommand.StrokeStart, command(down(KeyEvent.KEYCODE_ENTER, repeat = 0)))
    }

    @Test
    fun shortLeftTogglesHandAndLongLeftOpensSettings() {
        assertNull(command(down(KeyEvent.KEYCODE_DPAD_LEFT, repeat = 0)))
        assertEquals(RemoteCommand.ToggleHand, command(up(KeyEvent.KEYCODE_DPAD_LEFT)))

        decoder.reset()
        assertNull(command(down(KeyEvent.KEYCODE_DPAD_LEFT, repeat = 0)))
        assertEquals(RemoteCommand.OpenSettings, command(down(KeyEvent.KEYCODE_DPAD_LEFT, repeat = 6)))
        assertNull(command(up(KeyEvent.KEYCODE_DPAD_LEFT)))
    }

    @Test
    fun rightCyclesColorAndLongPressCyclesThickness() {
        assertNull(command(down(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 0)))
        assertEquals(RemoteCommand.CycleColor, command(up(KeyEvent.KEYCODE_DPAD_RIGHT)))
        decoder.reset()
        command(down(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 0))
        assertEquals(RemoteCommand.CycleThickness, command(down(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 6, longPress = true)))
        assertNull(command(up(KeyEvent.KEYCODE_DPAD_RIGHT)))
    }

    @Test
    fun dpadUndoClearAndBack() {
        assertEquals(RemoteCommand.Undo, command(down(KeyEvent.KEYCODE_DPAD_UP, repeat = 0)))
        assertNull(command(down(KeyEvent.KEYCODE_DPAD_UP, repeat = 3)))
        assertEquals(RemoteCommand.ClearImmediate, command(down(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 0)))
        assertNull(command(down(KeyEvent.KEYCODE_BACK, repeat = 0)))
        assertEquals(RemoteCommand.AskClearOrExit, command(up(KeyEvent.KEYCODE_BACK)))
        decoder.reset()
        assertEquals(RemoteCommand.Exit, command(down(KeyEvent.KEYCODE_BACK, repeat = 6)))
    }

    @Test
    fun numberKeysAreShortcutsAndSettingsUseTheDpad() {
        assertEquals(RemoteCommand.SetHand(UserHand.RIGHT), command(down(KeyEvent.KEYCODE_1, repeat = 0)))
        assertEquals(RemoteCommand.ToggleDelegate, command(down(KeyEvent.KEYCODE_PROG_GREEN, repeat = 0)))
        assertEquals(RemoteCommand.ToggleResolution, command(down(KeyEvent.KEYCODE_6, repeat = 0)))
        val inSettings = RemoteDecoder()
        assertEquals(
            RemoteCommand.SettingsDown,
            inSettings.onKey(ScreenMode.SETTINGS, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0, false)
                .let { (it as KeyResult.Consume).command },
        )
        assertEquals(
            RemoteCommand.CloseSettings,
            inSettings.onKey(ScreenMode.SETTINGS, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, false)
                .let { (it as KeyResult.Consume).command },
        )
    }

    @Test
    fun blockingModeDoesNotStealKeys() {
        val result = decoder.onKey(
            ScreenMode.BLOCKING,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            0,
            false,
        )
        assertTrue(result is KeyResult.Pass)
    }

    private fun command(result: KeyResult): RemoteCommand? = (result as KeyResult.Consume).command

    private fun down(keyCode: Int, repeat: Int, longPress: Boolean = false): KeyResult =
        decoder.onKey(ScreenMode.CAMERA, KeyEvent.ACTION_DOWN, keyCode, repeat, longPress)

    private fun up(keyCode: Int): KeyResult =
        decoder.onKey(ScreenMode.CAMERA, KeyEvent.ACTION_UP, keyCode, 0, false)
}
