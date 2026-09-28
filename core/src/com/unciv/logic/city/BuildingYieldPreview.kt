package com.unciv.logic.city

import com.unciv.logic.GameInfo
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
 * Both sides use Nothing as the construction, excluding production conversion and
 * bonuses specific to the next construction. Citizen assignments stay fixed. This
 * installs the building's passive effects only: completion triggers (free buildings,
 * units, technologies, population, etc.) are not executed. Other cities are refreshed
 * for global happiness and connection effects, but the returned yields are local.
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

    data class Comparison(val before: Yields, val after: Yields)

    private val game by lazy {
        source.clone().also { copy ->
            copy.gameParameters = source.gameParameters.clone()
            copy.gameParameters.godMode = source.gameParameters.godMode
            copy.tileMap.mapParameters = source.tileMap.mapParameters.clone()
            // Loading an empty queue can invoke production automation. A preview
            // does not choose construction or consume randomness on its behalf.
            for (civ in copy.civilizations) for (city in civ.cities) {
                city.tiles = HashSet(city.tiles)
                city.workedTiles = HashSet(city.workedTiles)
                city.lockedTiles = HashSet(city.lockedTiles)
                if (city.cityConstructions.constructionQueue.isEmpty())
                    city.cityConstructions.constructionQueue.add(PerpetualConstruction.Idle.name)
            }
            copy.setTransients()
        }
    }

    private val results = HashMap<Pair<String, String>, Comparison>()

    fun compare(city: City, building: Building): Comparison {
        require(city.civ.gameInfo === source && city in city.civ.cities)
        require(source.ruleset.buildings[building.name] === building)
        require(!city.cityConstructions.isBuilt(building.name))
        return results.getOrPut(city.id to building.name) {
            val owner = game.getCivilization(city.civ.civID)
            val target = owner.cities.single { it.id == city.id }
            val constructions = target.cityConstructions
            val queue = constructions.constructionQueue
            constructions.constructionQueue = arrayListOf(PerpetualConstruction.Idle.name)
            try {
                refresh(owner)
                val before = yields(target)
                // setTransients rebuilds passive unique maps without executing
                // addBuilding's completion triggers, free grants or map changes.
                constructions.builtBuildings.add(building.name)
                constructions.setTransients()
                refresh(owner)
                Comparison(before, yields(target))
            } finally {
                constructions.builtBuildings.remove(building.name)
                constructions.setTransients()
                constructions.constructionQueue = queue
                refresh(owner)
            }
        }
    }

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
