package com.unciv.logic.automation

import com.unciv.json.json
import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.automation.unit.WorkerAutomation
import com.unciv.logic.city.BuildingYieldPreview
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.stats.Stats
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AutomationSelectionTest {
    @Test fun `construction retains investment and compares actual remaining production`() {
        val choices = listOf(AutomationSelection.Construction("Library", 9f, 90), AutomationSelection.Construction("Granary", 5f, 10))
        assertEquals("Granary", AutomationSelection.construction("Nothing", true, choices, "Nothing"))
        assertEquals("Library", AutomationSelection.construction("Library", false, choices, "Nothing"))
        assertEquals("Nothing", AutomationSelection.construction("Nothing", true, emptyList(), "Nothing"))
        assertFalse(AutomationSelection.purchase(99, 100, 0, 0))
        assertFalse(AutomationSelection.purchase(100, 100, 0, 1))
        assertFalse(AutomationSelection.purchase(100, 100, 2, 0))
        assertTrue(AutomationSelection.purchase(100, 100, 1, 0))
    }

    @Test fun `citizen tie breaking and promotion pools need no random draw`() {
        val tiles = listOf(AutomationSelection.Citizen("west", 3f, -1, 0), AutomationSelection.Citizen("east", 3f, 1, 0))
        assertEquals("east", AutomationSelection.citizen(tiles, emptyList()))
        assertEquals("Scientist", AutomationSelection.citizen(tiles, listOf(AutomationSelection.Ranked("Scientist", 3f))))
        val promotions = listOf(AutomationSelection.Promotion("Heal", true, true, false),
            AutomationSelection.Promotion("Drill", false, false, false), AutomationSelection.Promotion("Free", false, false, true))
        assertEquals(listOf("Heal"), AutomationSelection.promotions(60, false, promotions))
        assertEquals(listOf("Free"), AutomationSelection.promotions(61, false, promotions))
        assertEquals(listOf("Free"), AutomationSelection.promotions(20, true, promotions))
        assertEquals(listOf("a", "b"), AutomationSelection.possibleHighest(listOf(
            AutomationSelection.Range("a", 20f, 40f), AutomationSelection.Range("b", 30f, 50f), AutomationSelection.Range("c", 0f, 29f))))
        assertEquals("near", AutomationSelection.closestEnemy(listOf(AutomationSelection.NearbyEnemy("far", 5), AutomationSelection.NearbyEnemy("near", 2))))
    }

    @Test fun `worker resource priority agrees with native execution`() {
        val game = TestGame().apply { makeHexagonalMap(7, "Grassland") }
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(0, 0))
        civ.tech.techsResearched.add(game.ruleset.tileImprovements.getValue("Mine").techRequired!!)
        civ.tech.techsResearched.add(game.ruleset.tileResources.getValue("Iron").revealedBy!!)
        val tile = game.getTile(1, 1)
        tile.setImprovementBasic("Farm")
        tile.setTileResource("Iron")
        val worker = game.addUnit("Worker", civ, tile)
        val selected = AutomationSelection.improvement("Farm", false, "Mine", "Farm", true, 20f, 1f, null)
        WorkerAutomation(civ, 3).automateWorkerAction(worker, hashSetOf())
        assertEquals("Mine", selected)
        assertEquals(selected, tile.improvementInProgress)
        assertNull(AutomationSelection.improvement("Farm", false, "Mine", "Mine", true, 0f, 1f, null))
        assertEquals("Road", AutomationSelection.improvement("Road", true, "Mine", "Farm", true, 20f, 1f, null))
    }

    @Test fun `construction executor preserves investment and passive preview leaves owners untouched`() {
        val game = TestGame().apply { makeHexagonalMap(6, "Grassland") }
        val civ = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        val city = game.addCity(civ, game.getTile(0, 0))
        city.population.setPopulation(5)
        city.cityConstructions.constructionQueue = arrayListOf("Library")
        city.cityConstructions.inProgressConstructions["Library"] = 17
        val automation = ConstructionAutomation(city.cityConstructions)
        val saved = json().toJson(game.gameInfo)
        automation.chooseNextConstruction()
        assertEquals(saved, json().toJson(game.gameInfo))
        val owners = game.tileMap.values.associateWith { it.getCity() }
        val statistics = city.cityStats.currentCityStats
        val preview = BuildingYieldPreview(game.gameInfo).compare(city, game.ruleset.buildings.getValue("Library"))
        val delta = preview.after.automationTotal - preview.before.automationTotal
        val preference = automation.buildingPreference(game.ruleset.buildings.getValue("Library"), delta)
        assertNotNull(preference)
        assertEquals(saved, json().toJson(game.gameInfo))
        assertSame(statistics, city.cityStats.currentCityStats)
        for ((tile, owner) in owners) assertSame(owner, tile.getCity())
        assertNull(automation.buildingPreference(game.ruleset.buildings.getValue("Walls"), Stats()))
        assertNull(automation.buildingPreference(game.ruleset.buildings.getValue("The Great Library"), Stats()))
        // Previewing never substitutes a controller or a production conversion.
        assertTrue(civ.isHuman())
        assertEquals(listOf("Library"), city.cityConstructions.constructionQueue)
        assertNotEquals(PerpetualConstruction.Science.name, city.cityConstructions.getCurrentConstruction().name)
    }

    @Test fun `read only selector is cancellable and negative ordinary improvements are not selected`() {
        val unit = ReadOnlyAutomation.Unit("worker", 100, 0, false, false, false, 999, null,
            emptyList(), emptyList(), emptyList(), listOf(ReadOnlyAutomation.RankedAction(
                ReadOnlyAutomation.Action(ReadOnlyAutomation.Kind.Improve, "worker", "Farm"), -1f)), emptyList())
        val position = ReadOnlyAutomation.Position(0, emptyList(), listOf(unit))
        assertTrue(ReadOnlyAutomation.select(position).isEmpty())
        assertThrows(InterruptedException::class.java) { ReadOnlyAutomation.select(position) { true } }
    }
}
