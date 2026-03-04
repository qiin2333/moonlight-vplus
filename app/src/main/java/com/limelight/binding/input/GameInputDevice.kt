package com.limelight.binding.input

import com.limelight.GameMenu

/**
 * Generic Input Device
 */
interface GameInputDevice {
    /**
     * @return list of device specific game menu options, e.g. configure a controller's mouse mode
     */
    fun getGameMenuOptions(): List<GameMenu.MenuOption>
}
