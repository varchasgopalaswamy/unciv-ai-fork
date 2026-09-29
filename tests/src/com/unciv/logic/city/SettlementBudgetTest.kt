package com.unciv.logic.city

import com.unciv.json.json
import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class SettlementBudgetTest {
    @Test fun `supplied planning terrain controls site requirements without inspecting live terrain`() {
        val game = TestGame().apply { makeHexagonalMap(7, "Grassland") }
        val civ = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        game.addCity(civ, game.getTile(0, 0))
        val remembered = TestGame().apply { makeHexagonalMap(7, "Plains") }
        val site = HexCoord(0, 5)
        remembered.getTile(site).setBaseTerrain(remembered.ruleset.terrains.getValue("Plains"))
        val budget = SettlementBudget(civ, remembered.tileMap)
        val before = budget.buildings(site, 1)
        val stoneWorks = before.single { it.building.name == "Stone Works" }
        assertTrue(stoneWorks.requirements.any { it.contains("Plains") })
        game.getTile(site).setBaseTerrain(game.ruleset.terrains.getValue("Desert"))
        val saved = json().toJson(game.gameInfo)
        assertEquals(before, budget.buildings(site, 1))
        assertEquals(saved, json().toJson(game.gameInfo))
        assertEquals(SettlementBudget(civ).costs(site, listOf(1, 2, 5)), budget.costs(site, listOf(1, 2, 5)))
    }

    @Test fun `costs match founding and growth without mutating the game`() {
        for (nation in listOf("Rome", "India")) {
            val game = TestGame()
            game.makeHexagonalMap(7, "Grassland")
            val civ = game.addCiv(game.ruleset.nations.getValue(nation), isPlayer = true)
            game.addCity(civ, game.getTile(HexCoord.Zero))
            val position = HexCoord(0, 5)
            val before = json().toJson(game.gameInfo)
            val costs = SettlementBudget(civ).costs(position, listOf(1, 2, 5))
            val buildings = SettlementBudget(civ).buildings(position, 1)
            assertEquals(before, json().toJson(game.gameInfo))
            assertTrue(buildings.any { it.building.name == "Colosseum" && it.happiness > 0 && it.missingTechnologies.isNotEmpty() })
            val city = game.addCity(civ, game.getTile(position))
            for (cost in costs) {
                city.population.setPopulation(cost.population)
                city.cityStats.update()
                assertEquals(-city.cityStats.happinessList.getValue("Cities"), cost.cityUnhappiness, 0.001f)
                assertEquals(-city.cityStats.happinessList.getValue("Population"), cost.populationUnhappiness, 0.001f)
            }
        }
    }

    @Test fun `additional luxury happiness matches connection and does not count duplicates`() {
        val game = TestGame()
        game.makeHexagonalMap(5, "Grassland")
        val civ = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        val city = game.addCity(civ, game.getTile(HexCoord.Zero))
        val resource = game.ruleset.tileResources.getValue("Spices")
        val tile = city.getTiles().first { !it.isCityCenter() }
        tile.setTileResource(resource)
        civ.tech.addTechnology("Calendar")
        civ.cache.updateCivResources()
        val before = civ.stats.getHappinessBreakdown().values.sum()
        val saved = json().toJson(game.gameInfo)
        val gain = civ.stats.getAdditionalLuxuryHappiness(resource)
        assertEquals(saved, json().toJson(game.gameInfo))
        assertTrue(gain > 0)
        tile.setImprovement(game.ruleset.tileImprovements.getValue("Plantation"), civ)
        civ.cache.updateCivResources()
        assertEquals(gain, civ.stats.getHappinessBreakdown().values.sum() - before, 0.001f)
        assertEquals(0f, civ.stats.getAdditionalLuxuryHappiness(resource), 0.001f)
        assertEquals(0f, civ.stats.getAdditionalLuxuryHappiness(game.ruleset.tileResources.getValue("Iron")), 0.001f)
    }

    @Test fun `founding on a luxury requires extraction technology but no terrain clearing`() {
        val game = TestGame()
        game.makeHexagonalMap(5, "Grassland")
        val civ = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        val tile = game.getTile(HexCoord.Zero)
        val spices = game.ruleset.tileResources.getValue("Spices")
        tile.setTileResource(spices)
        tile.setTerrainFeatures(listOf("Forest"))
        tile.setTerrainTransients()
        assertFalse(spices.canConnectUnderCity(civ))
        val city = game.addCity(civ, tile)
        assertFalse(city.getCenterTile().providesResources(civ))
        civ.tech.addTechnology("Calendar")
        assertFalse(civ.tech.isResearched("Mining"))
        assertTrue(spices.canConnectUnderCity(civ))
        assertTrue(city.getCenterTile().providesResources(civ))
    }
}
