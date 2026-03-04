package com.limelight.ui

import com.limelight.binding.input.GameInputDevice

interface GameGestures {
    fun toggleKeyboard()
    fun showGameMenu(device: GameInputDevice?)
}
