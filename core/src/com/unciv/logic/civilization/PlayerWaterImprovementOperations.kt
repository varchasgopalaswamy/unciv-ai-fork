package com.unciv.logic.civilization

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers
import java.util.Collections

/** Native water-resource improvement actions, including instant construction by military units.
 * An offered button is revalidated on the unit's current tile when executed.
 * Legacy water builders are consumed; instant construction uses its own action modifiers.
 */
class PlayerWaterImprovementOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class Option(
        val name: String,
        val title: String,
        val resource: String,
        val available: Boolean,
        val reasons: List<String>,
        val consumesUnit: Boolean = true,
    )

    private data class Choice(val option: Option, val unique: Unique?)

    fun option(unit: MapUnit): Option? = synchronized(civ.gameInfo) { choice(unit)?.option }

    private fun choice(unit: MapUnit): Choice? {
        if (spectatorMode || civ.isSpectator() || !PlayerUnitOperations(civ).owns(unit) ||
            civ.gameInfo.civilizations.none { it === civ }) return null
        val tile = unit.currentTile
        if (!tile.isWater) return null
        val resource = tile.tileResource?.takeIf { civ.canSeeResource(it) } ?: return null
        val improvement = resource.getImprovingImprovement(tile, unit.cache.state) ?: return null
        val legacyBuilder = unit.hasUnique(UniqueType.CreateWaterImprovements)
        val context = GameContext(civInfo = civ, unit = unit, tile = tile)
        val unique = if (legacyBuilder) null else {
            UnitActionModifiers.getUsableUnitActionUniques(unit, UniqueType.ConstructImprovementInstantly)
                .firstOrNull { improvement.matchesFilter(it.params[0], context) } ?: return null
        }
        val reasons = ArrayList<String>()
        if (!PlayerOperations(civ, spectatorMode).canAct()) reasons.add("This player cannot act now")
        if (!unit.hasMovement()) reasons.add("No movement remaining")
        if (unique != null) {
            if (!tile.improvementFunctions.canBuildImprovement(improvement, unit.cache.state))
                reasons.add("This improvement cannot be built on the current tile")
            if (tile.isMarkedForCreatesOneImprovement())
                reasons.add("A construction has reserved this tile for another improvement")
            val resources = civ.getCivResourcesByName()
            if (improvement.getMatchingUniques(UniqueType.ConsumesResources).any { (resources[it.params[1]] ?: 0) < it.params[0].toInt() })
                reasons.add("Not enough resources")
            if (!UnitActionModifiers.canActivateSideEffects(unit, unique))
                reasons.add("The ability's movement, use or resource requirements are not met")
        }
        val title = if (unique == null) "Create [${improvement.name}]"
            else UnitActionModifiers.actionTextWithSideEffects("Create [${improvement.name}]", unique, unit)
        return Choice(Option(improvement.name, title, resource.name, reasons.isEmpty(),
            Collections.unmodifiableList(reasons),
            consumesUnit = unique == null || UnitActionModifiers.willConsumeUnit(unit, unique)), unique)
    }

    fun tryCreate(unit: MapUnit, name: String): Boolean = synchronized(civ.gameInfo) {
        val offered = choice(unit)?.takeIf { it.option.name == name && it.option.available } ?: return@synchronized false
        val improvement = civ.gameInfo.ruleset.tileImprovements.getValue(offered.option.name)
        unit.currentTile.setImprovement(improvement, civ, unit)
        if (offered.unique == null) unit.destroy()
        else {
            civ.cache.updateViewableTiles()
            UnitActionModifiers.activateSideEffects(unit, offered.unique)
        }
        true
    }
}
