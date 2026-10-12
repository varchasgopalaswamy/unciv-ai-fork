package com.unciv.logic.city

import com.unciv.json.json
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class BuildingYieldPreviewTest {
    private fun fixture(): Pair<TestGame, City> {
        val game = TestGame()
        game.makeHexagonalMap(6, "Grassland")
        val civ = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        game.gameInfo.currentPlayer = civ.civID
        game.gameInfo.currentPlayerCiv = civ
        val city = game.addCity(civ, game.getTile(HexCoord.Zero))
        city.cityConstructions.constructionQueue = arrayListOf(PerpetualConstruction.Idle.name)
        return game to city
    }

    @Test fun `library depends on city population and matches actual completion`() {
        val (game, city) = fixture()
        city.population.setPopulation(6)
        val small = game.addCity(city.civ, game.getTile(HexCoord(0, 5)), initialPopulation = 2)
        val preview = BuildingYieldPreview(game.gameInfo)
        val library = game.ruleset.buildings.getValue("Library")
        val largeEffect = preview.compare(city, library)
        val smallEffect = preview.compare(small, library)
        assertEquals(3f, largeEffect.after.total.science - largeEffect.before.total.science, 0.001f)
        assertEquals(1f, smallEffect.after.total.science - smallEffect.before.total.science, 0.001f)
        city.cityConstructions.addBuilding(library)
        city.cityStats.update()
        assertEquals(city.cityStats.currentCityStats.science, largeEffect.after.total.science, 0.001f)
        assertEquals(1f, largeEffect.after.buildingMaintenance - largeEffect.before.buildingMaintenance, 0.001f)
    }

    @Test fun `granary counts worked resources and leaves unworked bonuses potential`() {
        val (game, city) = fixture()
        city.population.setPopulation(2)
        val worked = game.getTile(1, 0)
        val unused = game.getTile(0, 1)
        for (tile in listOf(worked, unused)) {
            tile.setTileResource(game.ruleset.tileResources.getValue("Wheat"))
            city.expansion.takeOwnership(tile)
        }
        city.workedTiles = hashSetOf(worked.position)
        city.cityStats.update()
        val result = BuildingYieldPreview(game.gameInfo).compare(city, game.ruleset.buildings.getValue("Granary"))
        assertEquals(3f, result.after.total.food - result.before.total.food, 0.001f)
        city.cityConstructions.addBuilding("Granary")
        // Completion may reassign citizens. Compare at the promised fixed assignments.
        city.workedTiles = hashSetOf(worked.position)
        city.cityStats.update()
        assertEquals(city.cityStats.currentCityStats.food, result.after.total.food, 0.001f)
        assertEquals(city.cityStats.currentCityStats.gold, result.after.total.gold, 0.001f)
        assertEquals(city.foodForNextTurn(), result.after.foodPerTurn)
        assertEquals(city.population.getNumTurnsToNewPopulation(), result.after.turnsToGrowth)
        assertEquals(city.population.getNumTurnsToStarvation(), result.after.turnsToStarvation)
    }

    @Test fun `university includes worked jungle and modifiers without staffing new slots`() {
        val (game, city) = fixture()
        city.population.setPopulation(3)
        city.cityConstructions.addBuilding("Library")
        val jungle = game.setTileTerrainAndFeatures(HexCoord(1, 0), "Plains", "Jungle")
        city.expansion.takeOwnership(jungle)
        city.workedTiles = hashSetOf(jungle.position)
        val allocations = city.population.specialistAllocations.clone()
        city.cityStats.update()
        val result = BuildingYieldPreview(game.gameInfo).compare(city, game.ruleset.buildings.getValue("University"))
        city.cityConstructions.addBuilding("University")
        city.cityStats.update()
        assertEquals(allocations, city.population.specialistAllocations)
        assertEquals(city.cityStats.currentCityStats.science, result.after.total.science, 0.001f)
        assertTrue(result.after.total.science > result.before.total.science * 1.33f)
    }

    @Test fun `preview preserves game objects tiles assignments and conversion queue`() {
        val (game, city) = fixture()
        city.population.setPopulation(3)
        city.cityConstructions.constructionQueue = arrayListOf("Science")
        city.cityStats.update()
        val serialized = json().toJson(game.gameInfo)
        val stats = city.cityStats.currentCityStats
        val empireStats = city.civ.stats.statsForNextTurn
        val tiles = game.tileMap.values.associateWith { it.getCity() }
        val preview = BuildingYieldPreview(game.gameInfo)
        val library = preview.compare(city, game.ruleset.buildings.getValue("Library"))
        preview.compare(city, game.ruleset.buildings.getValue("Workshop"))
        val repeated = preview.compare(city, game.ruleset.buildings.getValue("Library"))
        assertEquals(library, repeated)
        assertEquals(serialized, json().toJson(game.gameInfo))
        assertSame(stats, city.cityStats.currentCityStats)
        assertSame(empireStats, city.civ.stats.statsForNextTurn)
        for ((tile, owner) in tiles) assertSame(owner, tile.getCity())
        assertEquals(listOf("Science"), city.cityConstructions.constructionQueue)
        val independent = BuildingYieldPreview(game.gameInfo).compare(city, game.ruleset.buildings.getValue("Workshop"))
        val batched = preview.compare(city, game.ruleset.buildings.getValue("Workshop"))
        for (stat in Stat.entries) {
            assertEquals(independent.before.total[stat], batched.before.total[stat], 0.001f)
            assertEquals(independent.after.total[stat], batched.after.total[stat], 0.001f)
        }
    }

    @Test fun `workshop production and maintenance agree with completion under golden age`() {
        val (game, city) = fixture()
        city.civ.goldenAges.enterGoldenAge()
        city.cityStats.update()
        val result = BuildingYieldPreview(game.gameInfo).compare(city, game.ruleset.buildings.getValue("Workshop"))
        city.cityConstructions.addBuilding("Workshop")
        city.cityStats.update()
        for (stat in listOf(Stat.Science, Stat.Food, Stat.Production, Stat.Gold, Stat.Culture))
            assertEquals(stat.name, city.cityStats.currentCityStats[stat], result.after.total[stat], 0.001f)
    }

    @Test fun `passive comparison excludes free buildings and technology grants`() {
        val (game, city) = fixture()
        city.population.setPopulation(6)
        val before = json().toJson(game.gameInfo)
        val result = BuildingYieldPreview(game.gameInfo).compare(city, game.ruleset.buildings.getValue("The Great Library"))
        // The wonder itself supplies 3 science. Its free Library and technology
        // are completion effects outside this ongoing-yield comparison.
        assertEquals(3f, result.after.total.science - result.before.total.science, 0.001f)
        assertEquals(1f, result.after.total.culture - result.before.total.culture, 0.001f)
        assertEquals(before, json().toJson(game.gameInfo))
    }

    @Test fun `following conversion uses completed building modifiers and has a separate cache entry`() {
        val (game, city) = fixture()
        city.civ.tech.addTechnology("Education", false)
        city.civ.tech.addTechnology("Philosophy", false)
        city.civ.tech.addTechnology("Currency", false)
        city.civ.tech.addTechnology("Guilds", false)
        city.population.setPopulation(6)
        city.cityConstructions.addBuilding("Library")
        city.cityConstructions.constructionQueue = arrayListOf("Science")
        city.cityStats.update()
        val saved = json().toJson(game.gameInfo)
        val preview = BuildingYieldPreview(game.gameInfo)
        val building = game.ruleset.buildings.getValue("University")
        val idle = preview.compare(city, building)
        val science = preview.compare(city, building, PerpetualConstruction.Science)
        val gold = preview.compare(city, building, PerpetualConstruction.Gold)
        assertTrue(science.after.total.science > idle.after.total.science)
        assertTrue(gold.after.total.gold > idle.after.total.gold)
        assertEquals(science, preview.compare(city, building, PerpetualConstruction.Science))
        assertEquals(saved, json().toJson(game.gameInfo))
        val worked = city.workedTiles.toHashSet()
        city.cityConstructions.addBuilding(building)
        city.workedTiles = worked
        city.cityStats.update()
        for (stat in Stat.entries)
            if (stat != Stat.Happiness)
                assertEquals(stat.name, city.cityStats.currentCityStats[stat], science.after.total[stat], 0.001f)
    }

    @Test fun `locked following conversion is rejected without changing the game`() {
        val (game, city) = fixture()
        val saved = json().toJson(game.gameInfo)
        val failure = runCatching {
            BuildingYieldPreview(game.gameInfo).compare(city, game.ruleset.buildings.getValue("Library"), PerpetualConstruction.Science)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(saved, json().toJson(game.gameInfo))
    }

    @Test fun `empire preview includes growth recovery in other cities`() {
        val (game, city) = fixture()
        city.population.setPopulation(8)
        val other = game.addCity(city.civ, game.getTile(HexCoord(0, 5)), initialPopulation = 2)
        other.cityConstructions.addBuilding("Granary")
        other.cityConstructions.constructionQueue = arrayListOf(PerpetualConstruction.Idle.name)
        val foodTiles = listOf(game.getTile(HexCoord(0, 4)), game.getTile(HexCoord(1, 5)))
        for (tile in foodTiles) other.expansion.takeOwnership(tile)
        other.workedTiles = foodTiles.map { it.position }.toHashSet()
        // An empire happiness grant crosses the native unhappy growth threshold.
        val building = game.ruleset.buildings.getValue("Notre Dame")
        for (owned in city.civ.cities) owned.cityStats.update()
        city.civ.updateStatsForNextTurn()
        val saved = json().toJson(game.gameInfo)
        val result = BuildingYieldPreview(game.gameInfo).compare(city, building)
        assertTrue(result.empireAfter.happiness > result.empireBefore.happiness)
        assertEquals(saved, json().toJson(game.gameInfo))
        city.cityConstructions.addBuilding(building)
        for (owned in city.civ.cities) owned.cityStats.update()
        city.civ.updateStatsForNextTurn()
        assertEquals(city.civ.stats.happiness, result.empireAfter.happiness)
        assertEquals(other.foodForNextTurn(), result.empireAfter.cities.getValue(other.id).foodPerTurn)
        assertTrue("Happiness ${result.empireBefore.happiness} -> ${result.empireAfter.happiness}; food ${result.empireBefore.cities.getValue(other.id).foodPerTurn} -> ${result.empireAfter.cities.getValue(other.id).foodPerTurn}",
            result.empireAfter.cities.getValue(other.id).foodPerTurn > result.empireBefore.cities.getValue(other.id).foodPerTurn)
    }
}
