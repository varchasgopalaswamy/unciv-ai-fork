package com.unciv.logic.map

import com.unciv.logic.GameReplayObserver
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class GameReplayObserverTest {
    @Test
    fun reportsActualEntriesAndRemoval() {
        val fixture = TestGame()
        fixture.makeHexagonalMap(3)
        val civ = fixture.addCiv(isPlayer = true)
        val start = fixture.tileMap[0, 0]
        val destination = fixture.tileMap[0, 1]
        val entered = mutableListOf<Pair<HexCoord?, HexCoord>>()
        val removed = mutableListOf<Int>()
        fixture.gameInfo.replayObserver = object : GameReplayObserver {
            override fun turnStarted(civilization: String) = Unit
            override fun territoryChanged(tile: Tile) = Unit
            override fun attackCompleted(id: String) = Unit
            override fun unitEntered(unit: MapUnit, from: HexCoord?, visibleBefore: Set<String>, transit: List<HexCoord>) {
                entered.add(from to unit.currentTile.position)
                if (from != null) assertTrue(civ.civID in visibleBefore)
            }
            override fun unitRemoved(unit: MapUnit, visibleBefore: Set<String>) {
                assertTrue(civ.civID in visibleBefore)
                removed.add(unit.id)
            }
        }
        val unit = fixture.tileMap.placeUnitNearTile(start.position, fixture.ruleset.units.getValue("Warrior"), civ)!!
        unit.movement.moveToTile(destination)
        unit.destroy()
        assertEquals(listOf(null to start.position, start.position to destination.position), entered)
        assertEquals(listOf(unit.id), removed)
    }
}
