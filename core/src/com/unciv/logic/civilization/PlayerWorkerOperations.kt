package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.UnitWorkerRepair
import com.unciv.logic.map.mapunit.WorkerImprovementPicker
import com.unciv.models.ruleset.unique.UniqueType

/** Ordinary worker orders. Every accepted order rechecks the live picker and player authority. */
class PlayerWorkerOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class Job(val name: String, val remainingTurns: Int)

    fun ownsWorker(unit: MapUnit): Boolean = PlayerUnitOperations(civ).owns(unit) &&
        unit.cache.hasUniqueToBuildImprovements && unit.getMatchingUniques(UniqueType.BuildImprovements).any()

    fun queue(unit: MapUnit): List<Job> = if (!ownsWorker(unit)) emptyList()
        else unit.currentTile.improvementQueue.map { Job(it.improvement, it.turnsToImprovement) }

    fun canUsePicker(unit: MapUnit): Boolean = PlayerOperations(civ, spectatorMode).canAct() && ownsWorker(unit) &&
        WorkerImprovementPicker.canOpen(unit) && !unit.currentTile.isMarkedForCreatesOneImprovement()

    fun canStartJob(unit: MapUnit, name: String): Boolean {
        if (!PlayerOperations(civ, spectatorMode).canAct() || !ownsWorker(unit)) return false
        if (name == Constants.repair) return UnitWorkerRepair.canRepair(unit)
        if (name == Constants.cancelImprovementOrder || !canUsePicker(unit)) return false
        val option = WorkerImprovementPicker(unit).options().firstOrNull { it.improvement.name == name } ?: return false
        return option.report.isEmpty() || option.report.isQueueable()
    }

    /** The resulting work queue, including retained progress and preparatory work.
     * Unavailable picker choices can be inspected; [canStartJob] checks execution authority.
     */
    fun plannedQueue(unit: MapUnit, name: String): List<Job>? {
        if (!ownsWorker(unit)) return null
        if (name == Constants.repair) {
            if (!UnitWorkerRepair.isShown(unit)) return null
            return if (unit.currentTile.improvementInProgress == Constants.repair) queue(unit)
                else queue(unit) + Job(Constants.repair, UnitWorkerRepair.turns(unit))
        }
        if (name == Constants.cancelImprovementOrder) return null
        val option = WorkerImprovementPicker(unit).options().firstOrNull { it.improvement.name == name } ?: return null
        val first = option.report.removalImprovement ?: option.improvement
        // The picker wakes a worker already doing the first step without replacing its queue.
        if (first.name == unit.currentTile.improvementInProgress) return queue(unit)
        return listOfNotNull(first, option.improvement.takeIf { it !== first }).map {
            Job(it.name, if (civ.gameInfo.gameParameters.godMode) 1 else it.getTurnsToBuild(civ, unit))
        }
    }

    /** Select the final improvement; a queueable feature removal is prepended exactly as in the picker. */
    fun tryStartJob(unit: MapUnit, name: String): Boolean = synchronized(civ.gameInfo) {
        if (!canStartJob(unit, name)) return@synchronized false
        if (name == Constants.repair) return@synchronized UnitWorkerRepair.tryRepair(unit)
        val planned = checkNotNull(plannedQueue(unit, name))
        val first = civ.gameInfo.ruleset.tileImprovements.getValue(planned.first().name)
        val tile = unit.currentTile
        // Selecting the current job wakes the worker without restarting its progress or queue.
        if (first.name != tile.improvementInProgress) {
            tile.startWorkingOnImprovement(first, civ, unit)
            for (job in planned.drop(1)) tile.queueImprovement(job.name, job.remainingTurns)
        }
        unit.action = null
        true
    }

    fun canStopJob(unit: MapUnit): Boolean = canUsePicker(unit) &&
        WorkerImprovementPicker(unit).options().any { it.improvement.name == Constants.cancelImprovementOrder && it.report.isEmpty() }

    fun tryStopJob(unit: MapUnit): Boolean = synchronized(civ.gameInfo) {
        if (!canStopJob(unit)) return@synchronized false
        unit.currentTile.stopWorkingOnImprovement()
        true
    }
}
