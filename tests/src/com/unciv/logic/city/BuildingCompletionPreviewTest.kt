package com.unciv.logic.city

import com.unciv.json.json
import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class BuildingCompletionPreviewTest {
    @Test fun `native one-time grants are numerical and do not mutate the original game`() {
        val game = TestGame().apply { makeHexagonalMap(6, "Grassland") }
        val civ = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        game.gameInfo.currentPlayer = civ.civID
        game.gameInfo.currentPlayerCiv = civ
        val city = game.addCity(civ, game.getTile(HexCoord.Zero))
        val other = game.addCity(civ, game.getTile(4, 0))
        val before = json().toJson(game.gameInfo)
        val library = BuildingCompletionPreview.estimate(city, game.ruleset.buildings.getValue("The Great Library"))!!
        assertEquals(1, library.amounts["freeTechnologies"])
        assertEquals(listOf("Library"), library.buildings[city.id])
        val pyramids = BuildingCompletionPreview.estimate(city, game.ruleset.buildings.getValue("The Pyramids"))!!
        assertEquals(2, pyramids.units["Worker"])
        val tower = BuildingCompletionPreview.estimate(city, game.ruleset.buildings.getValue("CN Tower"))!!
        assertEquals(mapOf(city.id to 1, other.id to 1), tower.population)
        assertEquals(mapOf(city.id to listOf("Broadcast Tower"), other.id to listOf("Broadcast Tower")), tower.buildings)
        val oracle = BuildingCompletionPreview.estimate(other, game.ruleset.buildings.getValue("The Oracle"))!!
        assertEquals(1, oracle.amounts["freePolicies"])
        assertNull(BuildingCompletionPreview.estimate(city, game.ruleset.buildings.getValue("Granary")))
        assertEquals(before, json().toJson(game.gameInfo))
        city.cityConstructions.addBuilding("The Great Library")
        assertEquals(library.amounts["freeTechnologies"], civ.tech.freeTechs)
        assertTrue(city.cityConstructions.isBuilt("Library"))
    }
}
