package com.unciv.logic.city

import com.unciv.logic.GameInfo
import com.unciv.logic.copyForPreview
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.stats.Stats
import kotlin.math.roundToInt

/**
 * Fixed-population comparison of a building's ongoing city yields. Each instance is
 * a batch for one game state; discard it when that state changes. The original game
 * is never edited, even temporarily. A private game copy owns all preview tiles,
 * cities, caches and statistics, so city initialization cannot steal live tiles.
 *
 * Both sides use the selected perpetual construction (Nothing by default).
 * Citizen assignments stay fixed. This
 * installs the building's passive effects only: completion triggers (free buildings,
 * units, technologies, population, etc.) are not executed. Other cities are refreshed
 * for global happiness and connection effects. Local and owned-empire yields are
 * returned separately; empire totals include native civilization-level modifiers.
 */
class BuildingYieldPreview(private val source: GameInfo) {
    data class Yields(
        val total: Stats,
        val sources: Map<String, Stats>,
        val buildingMaintenance: Float,
        val foodPerTurn: Int,
        val turnsToGrowth: Int?,
        val turnsToStarvation: Int?,
    )

    data class EmpireYields(val total: Stats, val happiness: Int, val cities: Map<String, Yields>)

    data class Comparison(val before: Yields, val after: Yields,
                          val empireBefore: EmpireYields, val empireAfter: EmpireYields)

    private val game by lazy { source.copyForPreview() }

    private val results = HashMap<Triple<String, String, String>, Comparison>()

    fun compare(city: City, building: Building, following: PerpetualConstruction = PerpetualConstruction.Idle): Comparison {
        require(city.civ.gameInfo === source && city in city.civ.cities)
        require(source.ruleset.buildings[building.name] === building)
        require(!city.cityConstructions.isBuilt(building.name))
        require(following.isBuildable(city.cityConstructions))
        return results.getOrPut(Triple(city.id, building.name, following.name)) {
            val owner = game.getCivilization(city.civ.civID)
            val target = owner.cities.single { it.id == city.id }
            val constructions = target.cityConstructions
            val queue = constructions.constructionQueue
            constructions.constructionQueue = arrayListOf(following.name)
            try {
                refresh(owner)
                val before = yields(target)
                val empireBefore = empireYields(owner)
                // setTransients rebuilds passive unique maps without executing
                // addBuilding's completion triggers, free grants or map changes.
                constructions.builtBuildings.add(building.name)
                constructions.setTransients()
                refresh(owner)
                Comparison(before, yields(target), empireBefore, empireYields(owner))
            } finally {
                constructions.builtBuildings.remove(building.name)
                constructions.setTransients()
                constructions.constructionQueue = queue
                refresh(owner)
            }
        }
    }

    private fun empireYields(civ: Civilization) = EmpireYields(
        civ.stats.statsForNextTurn.clone(), civ.stats.happiness,
        civ.cities.associate { it.id to yields(it) })

    private fun refresh(civ: Civilization) {
        civ.cache.updateCitiesConnectedToCapital(true)
        civ.cache.updateCivResources()
        for (city in civ.cities) {
            city.cityStats.updateTileStats()
            city.cityStats.updateCityHappiness(city.cityConstructions.getStats())
        }
        civ.stats.happiness = civ.stats.getHappinessBreakdown().values.sum().roundToInt()
        for (city in civ.cities)
            city.cityStats.update(updateTileStats = false, updateCivStats = false)
        civ.updateStatsForNextTurn()
    }

    private fun yields(city: City): Yields {
        val sources = city.cityStats.finalStatList.mapValues { it.value.clone() }.toMutableMap()
        for ((name, amount) in city.cityStats.happinessList)
            sources.getOrPut(name) { Stats() }.happiness = amount
        val total = city.cityStats.currentCityStats.clone()
        total.happiness = city.cityStats.happinessList.values.sum()
        return Yields(total, sources, -(city.cityStats.finalStatList["Maintenance"]?.gold ?: 0f),
            city.foodForNextTurn(), city.population.getNumTurnsToNewPopulation(), city.population.getNumTurnsToStarvation())
    }
}
