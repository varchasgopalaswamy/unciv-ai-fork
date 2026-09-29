package com.unciv.logic.civilization

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.UnitSettlement
import com.unciv.logic.map.mapunit.movement.MovementCost
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.civilization.Notification.NotificationCategory
import yairm210.purity.annotations.Readonly
import java.util.Collections

/** Ordinary movement, persistent destinations and settlement. Special relocation modes use their own operations. */
class PlayerUnitOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class MovePreview(val destination: Tile, val path: List<Tile>, val movementCost: Float, val escortMovementCost: Float? = null)
    /** Turn stops and costs are estimates: discoveries, embarkation and other units can change them. */
    data class RoutePreview(val destination: Tile, val turns: List<MovePreview>)

    @Readonly
    fun owns(unit: MapUnit): Boolean = unit.civ === civ && !unit.isDestroyed &&
        civ.units.getCivUnits().any { it === unit } && unit.currentTile.getUnits().any { it === unit }

    @Readonly
    fun supportsMovement(unit: MapUnit): Boolean = owns(unit) && !unit.baseUnit.isAirUnit() &&
        !unit.isPreparingParadrop() && !unit.isPreparingAirSweep()

    /** Speculative paths use only explored terrain and visible units. No path is a promise of arrival. */
    @Readonly @Suppress("purity") // getPathToTile constructs a detached path; no game state changes
    fun moves(unit: MapUnit): List<MovePreview> {
        if (!supportsMovement(unit) || !unit.hasMovement() || unit.cache.cannotMove) return emptyList()
        val paths = unit.movement.getMovementToTilesAtPosition(unit.currentTile.position, unit.currentMovement, forPlanning = true)
        return paths.entries.filter { (tile, _) ->
            tile !== unit.currentTile && unit.movement.canMoveTo(tile, forPlanning = true)
        }.map { (tile, entry) -> movePreview(unit, unit.currentTile, tile, paths.getPathToTile(tile), entry.totalMovement) }
    }

    fun tryMove(unit: MapUnit, destination: Tile): Boolean = synchronized(civ.gameInfo) {
        if (!PlayerOperations(civ, spectatorMode).canAct()) return@synchronized false
        val preview = moves(unit).firstOrNull { it.destination === destination } ?: return@synchronized false
        clearMovementActions(unit)
        unit.movement.moveToTile(destination, plannedPath = preview.path)
        // An obstacle discovered while moving can stop a valid order short of its destination.
        true
    }

    /** Uses the native multi-turn pathfinder, with the same information limits as single-turn previews. */
    @Readonly @Suppress("purity")
    fun route(unit: MapUnit, destination: Tile): RoutePreview? {
        if (!supportsMovement(unit) || !unit.hasMovement() || unit.cache.cannotMove ||
            destination.tileMap !== civ.gameInfo.tileMap || destination === unit.currentTile ||
            !unit.movement.canMoveTo(destination, forPlanning = true)) return null
        val stops = unit.movement.getShortestPath(destination, forPlanning = true)
        if (stops.isEmpty()) return null
        var origin = unit.currentTile
        val turns = ArrayList<MovePreview>()
        for ((index, stop) in stops.withIndex()) {
            val paths = unit.movement.getMovementToTilesAtPosition(origin.position,
                if (index == 0) unit.currentMovement else unit.getMaxMovement().toFloat(),
                considerZoneOfControl = index == 0, forPlanning = true, useMaximumMovementForEscort = index > 0)
            val entry = paths[stop] ?: return null
            val path = paths.getPathToTile(stop)
            if (path.isEmpty()) return null
            turns.add(movePreview(unit, origin, stop, Collections.unmodifiableList(path), entry.totalMovement, index > 0))
            origin = stop
        }
        return RoutePreview(destination, Collections.unmodifiableList(turns))
    }

    /** Formation paths share stops; the two units still pay their own terrain costs. */
    private fun movePreview(unit: MapUnit, origin: Tile, destination: Tile, path: List<Tile>, cost: Float, laterTurn: Boolean = false): MovePreview {
        val escort = unit.getOtherEscortUnit()?.takeIf { unit.isEscorting() }
            ?: return MovePreview(destination, path, cost)
        fun unitCost(subject: MapUnit): Float {
            var previous = origin
            var total = 0f
            for (tile in path) {
                total += MovementCost.getMovementCostBetweenAdjacentTiles(
                    subject, previous, tile, considerZoneOfControl = !laterTurn, forPlanning = true,
                )
                previous = tile
            }
            return total.coerceAtMost(if (laterTurn) subject.getMaxMovement().toFloat() else subject.currentMovement)
        }
        return MovePreview(destination, path, unitCost(unit), unitCost(escort))
    }

    /** A direct movement instruction replaces independent travel/automation for the moving formation. */
    private fun clearMovementActions(unit: MapUnit) {
        val escort = unit.getOtherEscortUnit()?.takeIf { unit.isEscorting() }
        unit.action = null
        unit.automated = false
        if (escort != null) {
            escort.action = null
            escort.automated = false
        }
    }

    /** Begins moving immediately; the ordinary serialized moveTo action retains the destination. */
    fun trySetDestination(unit: MapUnit, destination: Tile): Boolean = synchronized(civ.gameInfo) {
        if (!PlayerOperations(civ, spectatorMode).canAct()) return@synchronized false
        val preview = route(unit, destination) ?: return@synchronized false
        val origin = unit.currentTile
        clearMovementActions(unit)
        val first = preview.turns.first()
        unit.movement.moveToTile(first.destination, plannedPath = first.path)
        if (!unit.isDestroyed && unit.currentTile !== origin && unit.currentTile !== destination)
            unit.action = "moveTo ${destination.position.x},${destination.position.y}"
        true
    }

    @Readonly
    fun movementDestination(unit: MapUnit): Tile? {
        if (!owns(unit)) return null
        if (unit.isMoving()) return unit.getMovementDestination()
        val escort = unit.getOtherEscortUnit()?.takeIf { unit.isEscorting() && owns(it) }
        return escort?.takeIf { it.isMoving() }?.getMovementDestination()
    }

    @Readonly
    fun canCancelMovement(unit: MapUnit): Boolean =
        PlayerOperations(civ, spectatorMode).canAct() && movementDestination(unit) != null

    fun tryCancelMovement(unit: MapUnit): Boolean = synchronized(civ.gameInfo) {
        if (!canCancelMovement(unit)) return@synchronized false
        if (unit.isMoving()) unit.action = null
        unit.getOtherEscortUnit()?.takeIf { unit.isEscorting() && owns(it) && it.isMoving() }?.action = null
        true
    }

    /** Called by native turn automation. Hidden enemies must not cancel or redirect a player's order. */
    internal fun continueDestination(unit: MapUnit) {
        while (owns(unit) && unit.isMoving() && unit.hasMovement() &&
            (!unit.isEscorting() || unit.getOtherEscortUnit()!!.hasMovement())) {
            val nearby = unit.movement.getMovementToTilesAtPosition(unit.currentTile.position,
                unit.currentMovement, forPlanning = true)
            if (!unit.isCivilian() && !unit.isEscorting() && nearby.keys.any { tile -> tile.militaryUnit?.let {
                    it.isVisibleTo(civ) && civ.isAtWarWith(it.civ)
                } == true }) {
                unit.action = null
                return
            }
            val destination = unit.getMovementDestination()
            val preview = route(unit, destination)
            if (preview == null) {
                unit.action = null
                return
            }
            val origin = unit.currentTile
            val first = preview.turns.first()
            if (CivilianCaptureThreats(civ).at(unit, first.destination).isNotEmpty()) {
                unit.action = null
                civ.addNotification("Movement of our [${unit.name}] was stopped to avoid possible capture",
                    unit.currentTile.position, NotificationCategory.Units, unit.name)
                return
            }
            unit.movement.moveToTile(first.destination, plannedPath = first.path)
            if (unit.isDestroyed || unit.currentTile === destination) unit.action = null
            if (unit.currentTile === origin) {
                if (unit.movementMemories.lastOrNull()?.position == origin.position) unit.action = null
                return
            }
        }
    }

    @Readonly
    fun canFoundCity(unit: MapUnit): Boolean = PlayerOperations(civ, spectatorMode).canAct() && owns(unit) && UnitSettlement.canFoundCity(unit)

    fun tryFoundCity(unit: MapUnit, confirmBreakPromise: Boolean = false): Boolean = synchronized(civ.gameInfo) {
        canFoundCity(unit) && UnitSettlement.tryFoundCity(unit, confirmBreakPromise) != null
    }
}
