package com.unciv.logic.civilization

import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.models.UnitActionType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerUnitManagementOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(6, "Grassland") }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val city = game.addCity(player, game.getTile(0, 0))
    private val foreignCity = game.addCity(other, game.getTile(5, 0))
    private val unit = game.addUnit("Warrior", player, game.getTile(1, 0))
    private val operations = PlayerUnitManagementOperations(player)

    init {
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        player.diplomacyFunctions.makeCivilizationsMeet(other)
    }

    @Test fun `name and promotion defaults survive saving and respect ownership`() {
        assertTrue(operations.tryRename(unit, "First Legion"))
        unit.promotions.addPromotion("Shock I", isFree = true)
        assertTrue(operations.trySavePromotions(unit))
        assertTrue(city.unitShouldUseSavedPromotion.getValue("Warrior"))
        assertEquals(unit.promotions.promotions, city.unitToPromotions.getValue("Warrior").promotions)
        assertTrue(operations.tryUseSavedPromotions(city, "Warrior", false))
        val saved = json().toJson(game.gameInfo)
        assertFalse(PlayerUnitManagementOperations(other).tryRename(unit, "stolen"))
        assertFalse(PlayerUnitManagementOperations(player, true).tryRename(unit, "spectator"))
        assertFalse(operations.tryUseSavedPromotions(foreignCity, "Warrior", true))
        assertEquals(saved, json().toJson(game.gameInfo))
        val restored = json().fromJson(GameInfo::class.java, saved).also { it.setTransients() }.getCivilization(player.civID)
        assertEquals("First Legion", restored.units.getCivUnits().single().instanceName)
        assertEquals(false, restored.cities.single().unitShouldUseSavedPromotion["Warrior"])
        assertTrue(operations.tryRename(unit, ""))
        assertNull(unit.instanceName)
    }

    @Test fun `major gift transfers a unit and applies native diplomatic modifier`() {
        assertNull(operations.gift(unit))
        player.getDiplomacyManager(other)!!.hasOpenBorders = true
        unit.removeFromTile()
        unit.putInTile(game.getTile(4, 0))
        val before = json().toJson(game.gameInfo)
        val option = operations.gift(unit)!!
        assertTrue(option.available)
        assertEquals(other.civID, option.civilizationId)
        assertEquals(before, json().toJson(game.gameInfo))
        assertTrue(operations.tryGift(unit))
        assertSame(other, unit.civ)
        assertEquals(5f, other.getDiplomacyManager(player)!!.diplomaticModifiers.getValue(DiplomaticModifiers.GaveUsUnits.name), 0f)
        assertFalse(operations.tryGift(unit))
    }

    @Test fun `swap preserves units and road automation is persistent and stoppable`() {
        val partner = game.addUnit("Warrior", player, game.getTile(0, 1))
        val original = unit.currentTile
        val destination = partner.currentTile
        val moves = PlayerUnitOperations(player)
        assertTrue(moves.swapDestinations(unit).contains(destination))
        assertTrue(moves.trySwap(unit, destination))
        assertSame(destination, unit.currentTile)
        assertSame(original, partner.currentTile)
        val worker = game.addUnit("Worker", player, game.getTile(0, 0))
        assertFalse(moves.canConnectRoad(worker))
        player.tech.addTechnology("The Wheel")
        val roadEnd = game.getTile(3, 0)
        assertNotNull(moves.roadPath(worker, roadEnd))
        assertTrue(moves.tryConnectRoad(worker, roadEnd))
        assertEquals(roadEnd.position, worker.automatedRoadConnectionDestination)
        assertTrue(worker.automated)
        val restored = json().fromJson(GameInfo::class.java, json().toJson(game.gameInfo)).also { it.setTransients() }
        assertEquals(roadEnd.position, restored.getCivilization(player.civID).units.getCivUnits().single { it.name == "Worker" }.automatedRoadConnectionDestination)
        assertTrue(PlayerUnitOrders(player).tryOrder(worker, PlayerUnitOrders.Order.STOP_AUTOMATION))
        assertNull(worker.automatedRoadConnectionDestination)
        assertNull(worker.automatedRoadConnectionPath)
    }

    @Test fun `setup and guard match native movement and wake semantics`() {
        val catapult = game.addUnit("Catapult", player, game.getTile(2, 0))
        val withdrawing = game.createBaseUnit("Archery", "Withdraws before melee combat")
        val slinger = game.addUnit(withdrawing.name, player, game.getTile(3, 0))
        val orders = PlayerUnitOrders(player)
        val movement = catapult.currentMovement
        assertTrue(orders.tryOrder(catapult, PlayerUnitOrders.Order.SET_UP))
        assertEquals(movement - 1, catapult.currentMovement, 0f)
        assertTrue(catapult.isSetUpForSiege())
        assertFalse(orders.tryOrder(catapult, PlayerUnitOrders.Order.SET_UP))
        assertTrue(orders.tryOrder(slinger, PlayerUnitOrders.Order.GUARD))
        assertEquals(UnitActionType.Guard.value, slinger.action)
        assertTrue(orders.tryOrder(slinger, PlayerUnitOrders.Order.WAKE))
        assertNull(slinger.action)
    }

    @Test fun `automation remains within the current turn and rejects inactive players`() {
        val turn = game.gameInfo.turns
        assertFalse(PlayerAutomationOperations(other).tryAutomate(PlayerAutomationOperations.Scope.ECONOMY))
        assertTrue(PlayerAutomationOperations(player).tryAutomate(PlayerAutomationOperations.Scope.ECONOMY))
        assertEquals(turn, game.gameInfo.turns)
        assertSame(player, game.gameInfo.currentPlayerCiv)
        val settings = UncivGame.Current.settings
        val old = settings.automatedUnitsMoveOnTurnStart
        try {
            settings.automatedUnitsMoveOnTurnStart = false
            unit.action = UnitActionType.Explore.value
            player.hasMovedAutomatedUnits = false
            assertTrue(PlayerAutomationOperations(player).tryMoveAutomatedUnits())
            assertTrue(player.hasMovedAutomatedUnits)
            assertFalse(PlayerAutomationOperations(player).tryMoveAutomatedUnits())
        } finally { settings.automatedUnitsMoveOnTurnStart = old }
    }
}
