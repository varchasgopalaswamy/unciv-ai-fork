package com.unciv.logic.civilization

import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class VisibilityObserverTest {
    @Test
    fun `observer sees intermediate movement discoveries after exploration and improvement memory update`() {
        val game = TestGame().apply { makeHexagonalMap(10, "Grassland") }
        val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        val unit = game.addUnit("Horseman", player, game.getTile(0, 0))
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        game.getTile(-2, 1).setImprovement("Farm")
        val opening = player.viewableTiles.map { it.position }.toSet()
        val observed = mutableSetOf<HexCoord>()
        var calls = 0
        player.cache.onViewableTilesUpdated = { tiles ->
            calls++
            assertEquals(player.viewableTiles, tiles)
            assertTrue(tiles.all { it.isExplored(player) })
            assertTrue(tiles.all { player.getLastSeenImprovement(it.position) == it.improvement })
            observed.addAll(tiles.map { it.position })
        }
        assertTrue(PlayerUnitOperations(player).tryMove(unit, game.getTile(0, 4)))
        val brieflySeen = observed - opening - player.viewableTiles.map { it.position }.toSet()
        assertTrue(calls > 1)
        assertTrue(brieflySeen.isNotEmpty())
        assertTrue(brieflySeen.all { game.tileMap[it].isExplored(player) })
        assertNull(player.clone().cache.onViewableTilesUpdated)
        player.cache.onViewableTilesUpdated = null
        val beforeDetach = calls
        player.cache.updateViewableTiles()
        assertEquals(beforeDetach, calls)
    }
}
