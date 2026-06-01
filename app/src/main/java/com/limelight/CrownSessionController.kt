package com.limelight

import android.widget.Toast
import com.limelight.binding.input.advance_setting.ControllerManager

class CrownSessionController(
    private val game: Game,
    private val controllerManagerProvider: () -> ControllerManager?
) {
    var backKeyMenuMode: Game.BackKeyMenuMode = Game.BackKeyMenuMode.GAME_MENU
        private set

    private var elementsVisible = true

    fun setBackKeyMenuMode(mode: Game.BackKeyMenuMode) {
        backKeyMenuMode = mode
    }

    fun toggleElementsVisibility() {
        val elementController = controllerManagerProvider()?.elementController ?: return
        elementsVisible = !elementsVisible
        if (elementsVisible) {
            elementController.showAllElementsForTest()
            Toast.makeText(game, game.getString(R.string.toast_elements_visible), Toast.LENGTH_SHORT).show()
        } else {
            elementController.hideAllElementsForTest()
            Toast.makeText(game, game.getString(R.string.toast_elements_hidden), Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleBackKeyMenuType() {
        when (backKeyMenuMode) {
            Game.BackKeyMenuMode.GAME_MENU -> enterCrownMode()
            Game.BackKeyMenuMode.CROWN_MODE -> exitCrownMode()
            Game.BackKeyMenuMode.NO_MENU -> backKeyMenuMode = Game.BackKeyMenuMode.GAME_MENU
        }
    }

    private fun enterCrownMode() {
        backKeyMenuMode = Game.BackKeyMenuMode.CROWN_MODE
        elementsVisible = true
        controllerManagerProvider()?.elementController?.showAllElementsForTest()
        Toast.makeText(game, game.getString(R.string.toast_back_key_menu_switch_2), Toast.LENGTH_SHORT).show()
    }

    private fun exitCrownMode() {
        backKeyMenuMode = Game.BackKeyMenuMode.GAME_MENU
        Toast.makeText(game, game.getString(R.string.toast_back_key_menu_switch_1), Toast.LENGTH_SHORT).show()
    }
}
