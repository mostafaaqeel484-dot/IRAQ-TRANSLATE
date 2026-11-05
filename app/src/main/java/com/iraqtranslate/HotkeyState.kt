package com.iraqtranslate

import android.view.KeyEvent

object HotkeyState {
    private var f1PressedAt: Long = 0L

    @Synchronized
    fun consumeKeyDown(keyCode: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (keyCode == KeyEvent.KEYCODE_F1) {
            f1PressedAt = now
            return false
        }
        if (keyCode == KeyEvent.KEYCODE_3) {
            val matched = f1PressedAt > 0L && now - f1PressedAt in 0..2500
            f1PressedAt = 0L
            return matched
        }
        return false
    }
}
