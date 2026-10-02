package com.unciv.logic.civilization

import com.badlogic.gdx.InputProcessor
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.ManagedGameSession
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.view.GameView
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(BaseTestRunner::class)
class ManagedSessionTest {
    @After fun cleanup() { UncivGame.Current.managedSession = null }

    @Test fun `externally paused views reject cached mutations and resume without changing native rules`() {
        val test = TestGame().apply { makeHexagonalMap(5) }
        val player = test.addCiv(test.ruleset.nations.getValue("Rome"), isPlayer = true)
        val city = test.addCity(player, test.getTile(0, 0))
        test.gameInfo.currentPlayer = player.civID
        test.gameInfo.currentPlayerCiv = player
        val view = GameView(test.gameInfo, player)
        val operations = PlayerOperations(player)
        var allowed = false
        UncivGame.Current.managedSession = object : ManagedGameSession {
            override val viewingCivilization = player.civID
            override val unreadCount = 0
            override fun ownsGame(game: GameInfo) = game === test.gameInfo
            override fun canChangeState(game: GameInfo) = ownsGame(game) && allowed
            override fun canAct(civilization: String) = allowed && civilization == player.civID
            override fun uiContext() = EmptyCoroutineContext
            override fun input(processor: InputProcessor) = processor
            override fun endTurn(worldScreen: WorldScreen) = Unit
            override fun openDiplomacy(civilization: String?) = false
            override fun usesSharedDiplomacy(civilization: String) = false
            override fun worldScreenCreated(worldScreen: WorldScreen) = Unit
            override fun exit() = Unit
            override fun failed(failure: Throwable) { throw failure }
        }
        val originalName = city.name
        assertFalse(operations.tryRenameCity(city, "Roma"))
        assertFalse(view.getCityView(city).tryRenameCity("Roma"))
        assertFalse(view.civView.tryMarkMovedAutomatedUnits())
        view.civView.tryDisableCivConstruction("Worker")
        assertFalse(view.civView.isCivConstructionDisabled("Worker"))
        assertEquals(originalName, city.name)
        allowed = true
        assertTrue(view.getCityView(city).tryRenameCity("Roma"))
        assertEquals("Roma", city.name)
        assertFalse(operations.trySetResearchQueue(listOf("Unknown technology")))
        assertTrue(operations.trySetResearchQueue(listOf("Pottery")))
        view.civView.tryDisableCivConstruction("Worker")
        assertTrue(view.civView.isCivConstructionDisabled("Worker"))
        allowed = false
        view.civView.tryEnableCivConstruction("Worker")
        assertTrue(view.civView.isCivConstructionDisabled("Worker"))
        UncivGame.Current.managedSession = null
        assertTrue(operations.tryRenameCity(city, "Unmanaged"))
    }
}
