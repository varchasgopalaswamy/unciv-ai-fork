package com.unciv.logic.city

import com.unciv.logic.copyForPreview
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unique.UniqueType
import java.util.Collections

/** Numerical one-time grants if construction finished in the current world.
 * Native completion runs in a detached world; it never grants objects to the live
 * player. This is separate from fixed-citizen passive yield comparisons because
 * completion may add population, free buildings, units and pending choices.
 */
object BuildingCompletionPreview {
    data class Grants(
        val amounts: Map<String, Int>,
        val units: Map<String, Int>,
        val population: Map<String, Int>,
        val buildings: Map<String, List<String>>,
    )

    fun estimate(city: City, building: Building): Grants? {
        require(city in city.civ.cities && city.getRuleset().buildings[building.name] === building)
        require(!city.cityConstructions.isBuilt(building.name))
        val civ = city.civ
        // Ordinary passive buildings need no extra clone. Conditional triggers
        // are evaluated by the native completion routine in the preview itself.
        val triggered = building.uniqueObjects.any { it.isTriggerable && !it.hasTriggerConditional() } ||
            city.getTriggeredUniques(UniqueType.TriggerUponConstructingBuilding, city.state).any() ||
            city.getTriggeredUniques(UniqueType.TriggerUponConstructingBuildingCityFilter, city.state).any() ||
            civ.hasUnique(UniqueType.TechBoostWhenScientificBuildingsBuiltInCapital) && city.isCapital() && building.isStatRelated(com.unciv.models.stats.Stat.Science, city)
        if (!triggered) return null
        val game = civ.gameInfo.copyForPreview()
        val owner = game.getCivilization(civ.civID)
        val target = owner.cities.single { it.id == city.id }
        fun amounts(civilization: Civilization): Map<String, Int> = with(civilization) {
            mapOf("Gold" to gold, "Culture" to policies.storedCulture, "Faith" to religionManager.storedFaith,
                "Science" to tech.getOverflowScience() + game.ruleset.technologies.keys.sumOf { if (tech.isResearched(it)) tech.costOfTech(it) else tech.researchOfTech(it) },
                "freeTechnologies" to tech.freeTechs, "freePolicies" to policies.freePolicies,
                "freeGreatPeople" to greatPeople.freeGreatPeople, "goldenAgeTurns" to goldenAges.turnsLeftForCurrentGoldenAge)
        }
        val before = amounts(owner)
        val units = owner.units.getCivUnits().map { it.id }.toSet()
        val populations = owner.cities.associate { it.id to it.population.population }
        val buildings = owner.cities.associate { it.id to it.cityConstructions.builtBuildings.toSet() }
        target.cityConstructions.addBuilding(building)
        val result = Grants(
            Collections.unmodifiableMap(amounts(owner).mapValues { (key, value) -> value - before.getValue(key) }.filterValues { it != 0 }),
            Collections.unmodifiableMap(owner.units.getCivUnits().filter { it.id !in units }.groupingBy { it.name }.eachCount()),
            Collections.unmodifiableMap(owner.cities.associate { it.id to it.population.population - populations.getValue(it.id) }.filterValues { it != 0 }),
            Collections.unmodifiableMap(owner.cities.associate { other -> other.id to Collections.unmodifiableList(
                other.cityConstructions.builtBuildings.filter { it !in buildings.getValue(other.id) && !(other === target && it == building.name) }.sorted())
            }.filterValues { it.isNotEmpty() }),
        )
        return result.takeIf { it.amounts.isNotEmpty() || it.units.isNotEmpty() || it.population.isNotEmpty() || it.buildings.isNotEmpty() }
    }
}
