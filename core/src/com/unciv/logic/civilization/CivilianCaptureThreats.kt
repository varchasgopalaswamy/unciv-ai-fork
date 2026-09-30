package com.unciv.logic.civilization

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/** A potential next-turn capture, calculated using the observing player's current vision. */
class CivilianCaptureThreats(private val observer: Civilization) {
    data class Threat(val unit: MapUnit, val path: List<Tile>, val speculative: Boolean, val movement: Int)

    private val enemies = observer.viewableTiles.asSequence().flatMap { it.getUnits() }
        .filter { it.isVisibleTo(observer) && it.isMilitary() && !it.baseUnit.isAirUnit() && observer.isAtWarWith(it.civ) }
        .toList()
    private val movementBudgets by lazy {
        enemies.associateWith { enemy ->
            // A hidden cohabitant must not reveal itself through a transferred movement allowance.
            val visibleBoost = enemy.currentTile.getUnits().filter { it !== enemy && it.isVisibleTo(observer) }
                .filter { unit -> unit.getMatchingUniques(UniqueType.TransferMovement).any { enemy.matchesFilter(it.params[0]) } }
                .maxOfOrNull { it.getMaxMovement(ignoreOtherUnit = true) } ?: 0
            maxOf(enemy.getMaxMovement(ignoreOtherUnit = true), visibleBoost)
        }
    }
    private val paths by lazy {
        enemies.associateWith { enemy ->
            // Current remaining movement is irrelevant: the enemy gets a new movement budget next turn.
            enemy.movement.getMovementToTilesAtPosition(
                enemy.currentTile.position, movementBudgets.getValue(enemy).toFloat(),
                includeOtherEscortUnit = false, forPlanning = true, observer = observer,
            )
        }
    }

    /** A same-tile military escort or friendly city prevents an unopposed capture. */
    @Readonly
    fun protectedAt(unit: MapUnit, destination: Tile, preserveEscort: Boolean = true): Boolean =
        !unit.isCivilian() ||
            preserveEscort && unit.isEscorting() && unit.getOtherEscortUnit()?.isMilitary() == true ||
            destination.militaryUnit?.civ === observer ||
            destination.isCityCenter() && destination.getOwner() === observer

    /** Unknown terrain is speculative; hidden units never change an estimate. */
    @Readonly @Suppress("purity") // Lazy path cache is local to this observation, not game state.
    fun at(unit: MapUnit, destination: Tile, preserveEscort: Boolean = true): List<Threat> {
        if (protectedAt(unit, destination, preserveEscort)) return emptyList()
        return enemies.mapNotNull { enemy ->
            val reachable = paths.getValue(enemy)
            if (destination !in reachable || !enemy.movement.canMoveTo(
                    destination, includeOtherEscortUnit = false, forPlanning = true, observer = observer,
                )) return@mapNotNull null
            // An embarked land unit cannot capture a civilian on water by entering its tile.
            if (destination.isVisible(observer) && destination.isWater && enemy.baseUnit.isLandUnit && !enemy.cache.canMoveOnWater)
                return@mapNotNull null
            val path = reachable.getPathToTile(destination)
            Threat(enemy, path, path.any { !it.isVisible(observer) }, movementBudgets.getValue(enemy))
        }
    }
}
