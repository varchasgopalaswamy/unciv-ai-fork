package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.models.UnitActionType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerWaterImprovementOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(6, "Coast") }
    private val player = game.addCiv(game.ruleset.nations.getValue("Japan"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val tile = game.getTile(1, 0)
    private val operations = PlayerWaterImprovementOperations(player)

    init {
        for (water in game.tileMap.values) water.setBaseTerrain(game.ruleset.terrains.getValue("Coast"))
        game.getTile(0, 0).setBaseTerrain(game.ruleset.terrains.getValue("Grassland"))
        game.addCity(player, game.getTile(0, 0))
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        for (tech in listOf("Sailing", "Optics")) player.tech.addTechnology(tech, false)
        tile.setTileResource("Fish")
        player.cache.updateCivResources()
        check(tile.isWater) { "Expected water: $tile" }
    }

    @Test
    fun `Samurai inspection is pure and desktop construction retains the unit`() {
        val samurai = game.addUnit("Samurai", player, tile)
        val before = json().toJson(game.gameInfo)
        val option = operations.option(samurai)!!
        repeat(3) { assertEquals(option, operations.option(samurai)) }
        assertEquals(before, json().toJson(game.gameInfo))
        assertEquals("Fishing Boats", option.name)
        assertEquals("Fish", option.resource)
        assertTrue(option.reasons.toString(), option.available)
        assertFalse(option.consumesUnit)
        val desktop = UnitActions.getUnitActions(samurai, UnitActionType.CreateImprovement).single()
        assertEquals(option.title, desktop.title)
        val movement = samurai.currentMovement
        desktop.action!!.invoke()
        assertEquals("Fishing Boats", tile.improvement)
        assertFalse(samurai.isDestroyed)
        assertEquals(movement - 1f, samurai.currentMovement)
        val after = json().toJson(game.gameInfo)
        desktop.action!!.invoke()
        assertFalse(operations.tryCreate(samurai, option.name))
        assertEquals(after, json().toJson(game.gameInfo))
    }

    @Test
    fun `stale desktop choices revalidate movement resource and active player`() {
        val samurai = game.addUnit("Samurai", player, tile)
        val desktop = UnitActions.getUnitActions(samurai, UnitActionType.CreateImprovement).single().action!!
        samurai.currentMovement = 0f
        var before = json().toJson(game.gameInfo)
        assertFalse(operations.option(samurai)!!.available)
        desktop()
        assertEquals(before, json().toJson(game.gameInfo))
        samurai.currentMovement = 2f
        game.gameInfo.currentPlayer = other.civID
        game.gameInfo.currentPlayerCiv = other
        before = json().toJson(game.gameInfo)
        desktop()
        assertEquals(before, json().toJson(game.gameInfo))
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        tile.setTileResource(null)
        before = json().toJson(game.gameInfo)
        assertNull(operations.option(samurai))
        desktop()
        assertEquals(before, json().toJson(game.gameInfo))
    }

    @Test
    fun `ownership spectator ability technology and tile legality are enforced`() {
        val samurai = game.addUnit("Samurai", player, tile)
        assertNull(PlayerWaterImprovementOperations(other).option(samurai))
        assertNull(PlayerWaterImprovementOperations(player, true).option(samurai))
        val before = json().toJson(game.gameInfo)
        assertFalse(PlayerWaterImprovementOperations(other).tryCreate(samurai, "Fishing Boats"))
        assertFalse(PlayerWaterImprovementOperations(player, true).tryCreate(samurai, "Fishing Boats"))
        assertFalse(operations.tryCreate(samurai, "Offshore Platform"))
        assertEquals(before, json().toJson(game.gameInfo))
        player.tech.techsResearched.remove("Sailing")
        assertNull(operations.option(samurai))
        assertFalse(operations.tryCreate(samurai, "Fishing Boats"))
        player.tech.techsResearched.add("Sailing")
        val far = game.getTile(5, 0)
        far.setTileResource("Fish")
        val outside = game.addUnit("Samurai", player, far)
        assertTrue(operations.option(outside)?.available != true)
        assertFalse(operations.tryCreate(outside, "Fishing Boats"))
        val ordinary = game.addUnit("Warrior", player, game.getTile(0, 1))
        ordinary.currentTile.setTileResource("Fish")
        assertNull(operations.option(ordinary))
        samurai.destroy()
        assertNull(operations.option(samurai))
    }

    @Test
    fun `legacy Work Boats still consume their builder`() {
        val boat = game.addUnit("Work Boats", player, tile)
        val option = operations.option(boat)!!
        assertTrue(option.available && option.consumesUnit)
        assertTrue(operations.tryCreate(boat, option.name))
        assertTrue(boat.isDestroyed)
        assertEquals("Fishing Boats", tile.improvement)
        assertFalse(operations.tryCreate(boat, option.name))
    }

    @Test
    fun `instant construction keeps its own movement and final use consumption`() {
        val base = game.createBaseUnit("Sword", "Can instantly construct a [Fishing Boats] improvement <for [2] movement> <once> <after which this unit is consumed>")
        val unit = game.addUnit(base.name, player, tile)
        unit.currentMovement = 2f
        val option = operations.option(unit)!!
        assertTrue(option.available && option.consumesUnit)
        assertTrue(operations.tryCreate(unit, option.name))
        assertTrue(unit.isDestroyed)
        assertEquals("Fishing Boats", tile.improvement)
    }
}
