package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.civilization.managers.TurnManager

/** The world-screen automation menu, without graphical scheduling or turn advancement. */
class PlayerAutomationOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    enum class Scope { ALL, MILITARY, CIVILIANS, ECONOMY }

    fun canMoveAutomatedUnits(): Boolean = PlayerOperations(civ, spectatorMode).canAct() &&
        !UncivGame.Current.settings.automatedUnitsMoveOnTurnStart && !civ.hasMovedAutomatedUnits &&
        civ.units.getCivUnits().any { it.currentMovement > Constants.minimumMovementEpsilon &&
            (it.isAutomated() || it.isExploring() || it.isMoving()) }

    fun tryMoveAutomatedUnits(): Boolean = synchronized(civ.gameInfo) {
        if (!canMoveAutomatedUnits()) return@synchronized false
        civ.hasMovedAutomatedUnits = true
        civ.units.getCivUnits().toList().forEach { it.doAction() }
        true
    }

    fun tryAutomate(scope: Scope): Boolean = synchronized(civ.gameInfo) {
        if (!PlayerOperations(civ, spectatorMode).canAct()) return@synchronized false
        when (scope) {
            Scope.ALL -> TurnManager(civ).automateTurn()
            Scope.ECONOMY -> NextTurnAutomation.automateCities(civ)
            Scope.MILITARY, Scope.CIVILIANS -> {
                val atWar = civ.isAtWar()
                val units = civ.units.getCivUnits().filter { if (scope == Scope.MILITARY) it.isMilitary() else it.isCivilian() }
                    .sortedBy { NextTurnAutomation.getUnitPriority(it, atWar) }.toList()
                units.forEach { if (!it.isDestroyed) UnitAutomation.automateUnitMoves(it) }
                if (scope == Scope.MILITARY) civ.cities.forEach { UnitAutomation.tryBombardEnemy(it) }
            }
        }
        true
    }
}
