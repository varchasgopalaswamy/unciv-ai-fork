package com.unciv.logic.city

import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class CityStatsConstructionTest {
    @Test
    fun `hypothetical conversion follows supplied construction without changing live city`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(HexCoord.Zero))
        city.cityConstructions.addBuilding("Library")
        city.cityConstructions.addBuilding("University")
        city.cityConstructions.addBuilding("National College")
        city.cityConstructions.addBuilding("Market")
        val owners = testGame.tileMap.values.associateWith { it.getCity() }

        for ((before, project) in listOf(
            "Settler" to PerpetualConstruction.Science,
            "Science" to PerpetualConstruction.Gold,
            "Gold" to PerpetualConstruction.Science,
        )) {
            city.cityConstructions.constructionQueue = arrayListOf(before)
            city.cityStats.update()
            val liveStats = city.cityStats.currentCityStats
            val liveBreakdown = city.cityStats.finalStatList
            val empireStats = civ.stats.statsForNextTurn
            val workedTiles = city.workedTiles.toSet()
            val preview = CityStats(city)
            preview.statsFromTiles = city.cityStats.statsFromTiles.clone()
            preview.update(project, updateTileStats = false, updateCivStats = false)
            val conversion = preview.finalStatList["Construction"]
            assertNotNull("Missing ${project.name} conversion while building $before", conversion)
            assertTrue(conversion!![project.stat] > 0f)
            val otherStat = if (project.stat == Stat.Science) Stat.Gold else Stat.Science
            assertEquals(0f, conversion[otherStat], 0f)
            assertEquals(listOf(before), city.cityConstructions.constructionQueue)
            assertSame(liveStats, city.cityStats.currentCityStats)
            assertSame(liveBreakdown, city.cityStats.finalStatList)
            assertSame(empireStats, civ.stats.statsForNextTurn)
            assertEquals(workedTiles, city.workedTiles)
            for ((tile, owner) in owners) assertSame(owner, tile.getCity())

            city.cityConstructions.constructionQueue = arrayListOf(project.name)
            city.cityStats.update()
            assertEquals(city.cityStats.finalStatList.getValue("Construction")[project.stat], conversion[project.stat], 0.001f)
        }
        val preview = CityStats(city)
        preview.statsFromTiles = city.cityStats.statsFromTiles.clone()
        preview.update(testGame.ruleset.units.getValue("Warrior"), updateTileStats = false, updateCivStats = false)
        assertNull("A unit preview must not retain the active Science conversion", preview.finalStatList["Construction"])
    }
}
