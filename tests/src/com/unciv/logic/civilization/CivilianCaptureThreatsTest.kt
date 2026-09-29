package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.map.mapunit.UnitTurnManager
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class CivilianCaptureThreatsTest {
    private val game = TestGame().apply { makeHexagonalMap(10, "Grassland") }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val enemy = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val civilian = game.addUnit("Settler", player, game.getTile(0, 0))
    private val warrior = game.addUnit("Warrior", enemy, game.getTile(4, 0))
    private val stop = game.getTile(2, 0)

    init {
        game.addUnit("Settler", enemy, game.getTile(9, 0))
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        player.diplomacyFunctions.makeCivilizationsMeet(enemy)
        player.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(player, true)
        player.viewableTiles = game.tileMap.values.toSet()
    }

    @Test fun `two flat tiles are capture range even when enemy has spent its movement`() {
        warrior.currentMovement = 0f
        val saved = json().toJson(game.gameInfo)
        val threat = CivilianCaptureThreats(player).at(civilian, stop).single()
        assertSame(warrior, threat.unit)
        assertEquals(2, threat.path.size)
        assertFalse(threat.speculative)
        assertEquals(saved, json().toJson(game.gameInfo))
    }

    @Test fun `terrain costs can prevent a two tile capture`() {
        for (tile in warrior.currentTile.neighbors) {
            tile.setTerrainFeatures(listOf("Hill"))
            tile.setTerrainTransients()
        }
        assertTrue(CivilianCaptureThreats(player).at(civilian, stop).isEmpty())
    }

    @Test fun `faster enemies are detected outside a two tile proximity radius`() {
        warrior.destroy()
        val horseman = game.addUnit("Horseman", enemy, game.getTile(5, 0))
        player.viewableTiles = game.tileMap.values.toSet()
        assertEquals(3, horseman.currentTile.aerialDistanceTo(stop))
        assertSame(horseman, CivilianCaptureThreats(player).at(civilian, stop).single().unit)
    }

    @Test fun `ranged land units can also capture civilians by moving onto them`() {
        warrior.destroy()
        val archer = game.addUnit("Archer", enemy, game.getTile(4, 0))
        player.viewableTiles = game.tileMap.values.toSet()
        assertSame(archer, CivilianCaptureThreats(player).at(civilian, stop).single().unit)
    }

    @Test fun `a hidden movement donor cannot extend a visible enemy threat`() {
        warrior.removeFromTile()
        warrior.putInTile(game.getTile(6, 0))
        val donorType = game.createBaseUnit("Civilian", "Invisible to others", "Transfer Movement to [All]")
        donorType.movement = 6
        val donor = game.addUnit(donorType.name, enemy, warrior.currentTile)
        player.viewableTiles = game.tileMap.values.toSet()
        assertFalse(donor.isVisibleTo(player))
        assertEquals(6, warrior.getMaxMovement())
        assertTrue(CivilianCaptureThreats(player).at(civilian, stop).isEmpty())
        player.viewableInvisibleUnitsTiles = setOf(donor.currentTile)
        assertTrue(donor.isVisibleTo(player))
        assertSame(warrior, CivilianCaptureThreats(player).at(civilian, stop).single().unit)
    }

    @Test fun `nearby military is not protection but shared destination and formation are`() {
        val escort = game.addUnit("Warrior", player, game.getTile(1, 0))
        player.viewableTiles = game.tileMap.values.toSet()
        assertFalse(CivilianCaptureThreats(player).at(civilian, stop).isEmpty())
        escort.removeFromTile()
        escort.putInTile(stop)
        assertTrue(CivilianCaptureThreats(player).at(civilian, stop).isEmpty())
        escort.removeFromTile()
        escort.putInTile(civilian.currentTile)
        civilian.startEscorting()
        player.viewableTiles = game.tileMap.values.toSet()
        assertTrue(CivilianCaptureThreats(player).at(civilian, stop).isEmpty())
        assertTrue(CivilianCaptureThreats(player).at(escort, stop).isEmpty())
        civilian.stopEscorting()
        assertFalse(CivilianCaptureThreats(player).at(civilian, stop).isEmpty())
    }

    @Test fun `unknown terrain and hidden units cannot change warnings`() {
        val visible = setOf(warrior.currentTile, civilian.currentTile, stop)
        player.viewableTiles = visible
        val before = CivilianCaptureThreats(player).at(civilian, stop)
        assertTrue(before.single().speculative)
        for (tile in game.tileMap.values) if (tile !in visible) {
            tile.setBaseTerrain(game.ruleset.terrains.getValue("Coast"))
            tile.setTerrainTransients()
        }
        game.addUnit("Trireme", enemy, game.getTile(3, 0))
        player.viewableTiles = visible
        assertEquals(before, CivilianCaptureThreats(player).at(civilian, stop))
        player.viewableTiles = setOf(civilian.currentTile, stop)
        assertTrue(CivilianCaptureThreats(player).at(civilian, stop).isEmpty())
    }

    @Test fun `invisible hostile on visible terrain does not become a threat`() {
        warrior.destroy()
        val invisible = game.addDefaultMeleeUnitWithUniques(enemy, game.getTile(3, 0), "Invisible to others")
        player.viewableTiles = game.tileMap.values.toSet()
        assertFalse(invisible.isVisibleTo(player))
        assertTrue(CivilianCaptureThreats(player).at(civilian, stop).isEmpty())
    }

    @Test fun `persistent civilian orders stop before a threatened future endpoint after reload`() {
        warrior.removeFromTile()
        warrior.putInTile(game.getTile(6, 0))
        assertTrue(PlayerUnitOperations(player).trySetDestination(civilian, game.getTile(7, 0)))
        assertSame(stop, civilian.currentTile)
        val restored = json().fromJson(GameInfo::class.java, json().toJson(game.gameInfo))
        restored.setTransients()
        val owner = restored.getCivilization(player.civID)
        owner.viewableTiles = restored.tileMap.values.toSet()
        val moving = owner.units.getCivUnits().single()
        val start = moving.currentTile
        UnitTurnManager(moving).startTurn()
        moving.doAction()
        assertSame(start, moving.currentTile)
        assertEquals(2f, moving.currentMovement, 0f)
        assertFalse(moving.isMoving())
        assertTrue(owner.notifications.any { "possible capture" in it.text })
    }

    @Test fun `formation orders continue despite a visible enemy near their next stop`() {
        warrior.removeFromTile()
        warrior.putInTile(game.getTile(4, 1))
        val escort = game.addUnit("Warrior", player, civilian.currentTile)
        civilian.startEscorting()
        player.viewableTiles = game.tileMap.values.toSet()
        assertTrue(PlayerUnitOperations(player).trySetDestination(civilian, game.getTile(7, 0)))
        val start = civilian.currentTile
        UnitTurnManager(civilian).startTurn()
        UnitTurnManager(escort).startTurn()
        civilian.doAction()
        assertNotSame(start, civilian.currentTile)
        assertSame(civilian.currentTile, escort.currentTile)
    }
}
