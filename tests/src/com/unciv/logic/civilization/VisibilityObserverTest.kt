package com.unciv.logic.civilization

import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class VisibilityObserverTest {
    @Test
    fun `specific tile reveal updates remembered improvements before notifying observers`() {
        val game = TestGame().apply { makeHexagonalMap(10, "Grassland") }
        val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        game.addCity(player, game.getTile(0, 0))
        val remote = game.getTile(7, 0)
        remote.setImprovement("Farm")
        var observed = false
        player.cache.onTileRevealed = { tile ->
            if (tile === remote) {
                observed = true
                assertFalse(tile.isVisible(player))
                assertEquals("Farm", tile.getShownImprovement(player))
            }
        }
        assertTrue(UniqueTriggerActivation.triggerUnique(
            Unique("Reveal up to [all] [Land] within a [1] tile radius"), player, tile = remote))
        assertTrue(observed)
    }

    @Test
    fun `remote reveals report discoveries without granting sight and are not cloned`() {
        val game = TestGame().apply { makeHexagonalMap(10, "Grassland") }
        val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        game.addCity(player, game.getTile(0, 0))
        val minor = game.addCiv(game.ruleset.nations.getValue("Geneva"))
        val capital = game.addCity(minor, game.getTile(7, 0))
        val discovered = mutableListOf<Pair<HexCoord, String?>>()
        player.cache.onTileRevealed = { tile ->
            assertTrue(tile.isExplored(player))
            assertFalse(tile.isVisible(player))
            discovered.add(tile.position to tile.getCity()?.name)
        }
        player.diplomacyFunctions.makeCivilizationsMeet(minor)
        assertTrue(discovered.contains(capital.location to capital.name))
        assertFalse(capital.getCenterTile().isVisible(player))
        val count = discovered.size
        capital.name = "Hidden rename"
        assertEquals(count, discovered.size)
        capital.getCenterTile().setExplored(player, true)
        assertEquals(capital.location to "Hidden rename", discovered.last())
        assertEquals(count + 1, discovered.size)
        assertNull(player.clone().cache.onTileRevealed)
        player.cache.onTileRevealed = null
        capital.getCenterTile().setExplored(player, true)
        assertEquals(count + 1, discovered.size)
    }

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
