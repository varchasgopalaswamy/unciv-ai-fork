package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.models.ruleset.Building
import com.unciv.models.stats.Stats

/**
 * Read-only planning for an unfounded, empty city under current civilization
 * modifiers. No territory is acquired, citizens assigned or founding triggers run.
 * Costs contain only the native city/population unhappiness components, not a
 * prediction of total empire happiness after founding. Buildings are independent
 * possibilities; their requirements still apply and no production rate is assumed.
 */
class SettlementBudget(private val civ: Civilization, private val map: TileMap = civ.gameInfo.tileMap) {
    data class Cost(val population: Int, val cityUnhappiness: Float, val populationUnhappiness: Float)
    data class BuildingOption(val building: Building, val happiness: Float, val productionCost: Int,
                              val requirements: List<String>, val missingTechnologies: List<String>)

    private fun city(position: HexCoord, population: Int): City = City().also {
        require(population >= 1)
        it.id = "settlement-budget"
        it.name = "Proposed city"
        it.location = position
        it.foundingCivObject = civ
        it.population = it.population.clone(population)
        // Empty owned/worked tile sets make transient setup read-only to the map.
        it.setTransients(civ, map)
    }

    fun costs(position: HexCoord, populations: List<Int>): List<Cost> = populations.map { population ->
        val city = city(position, population)
        city.cityStats.updateCityHappiness(StatTreeNode(), Stats(), StatTreeNode())
        val values = city.cityStats.happinessList
        Cost(population, -(values["Cities"] ?: 0f), -(values["Population"] ?: 0f))
    }

    fun buildings(position: HexCoord, population: Int): List<BuildingOption> {
        val city = city(position, population)
        return civ.gameInfo.ruleset.buildings.values.asSequence()
            .filter { !it.isAnyWonder() && (it.uniqueTo == null || civ.matchesFilter(it.uniqueTo!!)) && civ.getEquivalentBuilding(it) == it }
            .map { building -> building to building.getStats(city).happiness }
            .filter { (_, happiness) -> happiness > 0 }
            .map { (building, happiness) ->
                BuildingOption(building, happiness, building.getProductionCost(civ, city),
                    building.getRejectionReasons(city.cityConstructions).map { it.errorMessage }.distinct().toList(),
                    building.requiredTechs().filterNot { civ.tech.isResearched(it) }.sorted().toList())
            }.sortedBy { it.building.name }.toList()
    }
}
