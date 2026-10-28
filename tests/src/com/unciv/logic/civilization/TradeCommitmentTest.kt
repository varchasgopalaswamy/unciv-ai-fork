package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.json.json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeOperations
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class TradeCommitmentTest {
    private val game = TestGame().apply { makeHexagonalMap(9) }
    private val ours = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val theirs = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val operations = TradeOperations(ours, theirs)

    init {
        game.addCity(ours, game.getTile(-5, 0))
        game.addCity(theirs, game.getTile(5, 0))
        ours.diplomacyFunctions.makeCivilizationsMeet(theirs)
        ours.addGold(500)
        theirs.addGold(500)
    }

    private fun offer(amount: Int) = TradeOperations.draft(listOf(
        TradeOffer(Constants.flatGold, TradeOfferType.Gold, amount, game.gameInfo.speed)))

    @Test fun `publication protects gold from ordinary spending without changing the total`() {
        assertTrue(operations.tryReserve(offer(300), "offer1"))
        assertEquals(500, ours.gold)
        assertEquals(300, ours.committedGold())
        assertEquals(200, ours.availableGold())
        assertFalse(ours.hasStatToBuy(Stat.Gold, 250))
        assertEquals(200, operations.available().ourOffers.single { it.type == TradeOfferType.Gold }.amount)
        assertFalse(operations.tryReserve(offer(250), "offer2"))
        assertEquals(300, ours.committedGold())
    }

    @Test fun `settlement consumes its own reservation while protecting other offers`() {
        assertTrue(operations.tryReserve(offer(300), "offer1"))
        assertTrue(operations.tryReserve(offer(100), "offer2"))
        assertTrue(operations.tryEnact(offer(300), "offer1"))
        assertEquals(200, ours.gold)
        assertEquals(100, ours.committedGold())
        assertEquals(100, ours.availableGold())
        assertEquals(800, theirs.gold)
    }

    @Test fun `failed replacement preserves the exact earlier commitment`() {
        assertTrue(operations.tryReserve(offer(300), "offer1"))
        assertFalse(operations.tryReserve(offer(600), "offer1", version = 2))
        assertEquals(300, ours.committedGold())
        assertEquals(1, ours.tradeCommitments.single().version)
        assertTrue(operations.tryReserve(offer(150), "offer1", version = 2))
        assertEquals(150, ours.committedGold())
        assertFalse(operations.tryEnact(offer(300), "offer1"))
        ours.releaseTradeCommitment("offer1")
        assertEquals(500, ours.availableGold())
    }

    @Test fun `mandatory loss invalidates newest offers and retains older backing`() {
        assertTrue(operations.tryReserve(offer(200), "older"))
        assertTrue(operations.tryReserve(offer(200), "newer"))
        ours.addGold(-250)
        assertEquals(250, ours.gold)
        assertEquals(200, ours.committedGold())
        assertTrue("newer" in ours.invalidatedTradeCommitments)
        assertFalse("older" in ours.invalidatedTradeCommitments)
    }

    @Test fun `native queued requests reserve and settle once`() {
        val request = TradeRequest(ours.civID, offer(300).reverse())
        assertTrue(TradeOperations.enqueue(theirs, request))
        assertTrue(request.requestId.isNotEmpty())
        assertEquals(300, ours.committedGold())
        assertTrue(TradeOperations(theirs, ours).tryAcceptRequest(request))
        assertFalse(TradeOperations(theirs, ours).tryAcceptRequest(request))
        assertEquals(200, ours.gold)
        assertEquals(0, ours.committedGold())
    }

    @Test fun `cloning preserves allocations without sharing mutable records`() {
        assertTrue(operations.tryReserve(offer(300), "offer1"))
        val copy = ours.clone()
        assertEquals(300, copy.committedGold())
        copy.tradeCommitments.single().gold = 10
        assertEquals(300, ours.committedGold())
    }
    @Test fun `resource offers protect quantities without removing benefits and loss releases the whole offer`() {
        val capital = ours.cities.first()
        capital.getCenterTile().setTileResource("Silk")
        ours.tech.addTechnology("Calendar")
        ours.cache.updateCivResources()
        val silk = operations.available().ourOffers.single { it.name == "Silk" }
        val terms = TradeOperations.draft(listOf(silk, offer(100).ourOffers.single()))
        assertTrue(operations.tryReserve(terms, "silk"))
        assertEquals(1, ours.getResourceAmount("Silk"))
        assertEquals(1, ours.committedResource("Silk"))
        assertEquals(0, ours.availableResource("Silk"))
        assertFalse(operations.tryReserve(TradeOperations.draft(listOf(silk)), "duplicate"))
        capital.getCenterTile().setTileResource(null)
        ours.cache.updateCivResources()
        assertTrue("silk" in ours.invalidatedTradeCommitments)
        assertEquals(0, ours.committedGold())
        assertEquals(0, ours.committedResource("Silk"))
    }

    @Test fun `same exchange incoming gold funds signing but cannot fund unrelated commitments`() {
        for (civ in listOf(ours, theirs)) {
            fun research(name: String) {
                if (civ.tech.isResearched(name)) return
                game.ruleset.technologies.getValue(name).prerequisites.forEach(::research)
                civ.tech.addTechnology(name)
            }
            research("Education")
        }
        val embassy = operations.available()
        assertTrue(operations.tryEnact(TradeOperations.draft(
            embassy.ourOffers.filter { it.type == TradeOfferType.Embassy },
            embassy.theirOffers.filter { it.type == TradeOfferType.Embassy })))
        ours.getDiplomacyManager(theirs)!!.signDeclarationOfFriendship()
        val treaty = operations.available().ourOffers.single { it.name == Constants.researchAgreement }
        ours.addGold(-ours.gold)
        theirs.addGold(2 * treaty.amount - theirs.gold)
        val funded = operations.prepare(TradeOperations.draft(listOf(treaty)))
        assertTrue(operations.tryReserve(funded, "research"))
        assertEquals(0, ours.availableGold())
        assertFalse(operations.tryReserve(offer(1), "unrelated"))
        assertTrue(operations.tryEnact(funded, "research"))
        assertEquals(0, ours.gold)
        assertEquals(0, theirs.gold)
    }

    @Test fun `purchasing and publishing atomically compete for the same available balance`() {
        val city = ours.cities.first()
        val tile = game.getTile(-3, 0)
        val cost = city.expansion.getGoldCostOfTile(tile)
        ours.addGold(cost - ours.gold)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val published = executor.submit<Boolean> { start.await(); operations.tryReserve(offer(cost), "race") }
            val purchased = executor.submit<Boolean> {
                start.await()
                try { city.expansion.buyTile(tile); true } catch (_: Exception) { false }
            }
            start.countDown()
            assertTrue(published.get(5, TimeUnit.SECONDS) xor purchased.get(5, TimeUnit.SECONDS))
            assertTrue(ours.gold >= ours.committedGold())
            assertEquals(0, ours.availableGold())
        } finally { executor.shutdownNow() }
    }

    @Test fun `serialization retains exact commitments and invalidation reasons`() {
        assertTrue(operations.tryReserve(offer(200), "older"))
        assertTrue(operations.tryReserve(offer(200), "newer"))
        ours.addGold(-250)
        val restored = json().fromJson(Civilization::class.java, json().toJson(ours))
        assertEquals(250, restored.gold)
        assertEquals(200, restored.committedGold())
        assertEquals(50, restored.availableGold())
        assertEquals(ours.invalidatedTradeCommitments, restored.invalidatedTradeCommitments)
        assertTrue(TradeOperations.sameTerms(ours.tradeCommitments.single().trade, restored.tradeCommitments.single().trade))
    }

    @Test fun `committed iron cannot be consumed by a purchase or a stale paid upgrade`() {
        val city = ours.cities.first()
        city.getCenterTile().setTileResource("Iron")
        city.getCenterTile().resourceAmount = 1
        ours.tech.addTechnology("Mining")
        ours.tech.addTechnology("Iron Working")
        ours.cache.updateCivResources()
        val warrior = game.addUnit("Warrior", ours, game.getTile(-4, 0))
        val legion = game.ruleset.units.getValue("Legion")
        assertTrue(warrior.upgrade.canUpgrade(legion))
        val iron = operations.available().ourOffers.single { it.name == "Iron" }
        assertTrue(operations.tryReserve(TradeOperations.draft(listOf(iron)), "iron"))
        val beforeGold = ours.gold
        assertFalse(city.cityConstructions.purchaseConstruction("Legion", -1, false))
        warrior.upgrade.performUpgrade(legion, isFree = false)
        assertFalse(warrior.isDestroyed)
        assertEquals(beforeGold, ours.gold)
        assertEquals(1, ours.committedResource("Iron"))
    }

    @Test fun `a factory cannot consume coal allocated to a published offer`() {
        val city = ours.cities.first()
        city.getCenterTile().setTileResource("Coal")
        city.getCenterTile().resourceAmount = 1
        for (technology in game.ruleset.technologies.keys) ours.tech.addTechnology(technology)
        city.cityConstructions.addBuilding("Workshop")
        ours.addGold(10000)
        ours.cache.updateCivResources()
        val coal = operations.available().ourOffers.single { it.name == "Coal" }
        assertTrue(operations.tryReserve(TradeOperations.draft(listOf(coal)), "coal"))
        assertFalse(city.cityConstructions.purchaseConstruction("Factory", -1, false))
        assertFalse(city.cityConstructions.isBuilt("Factory"))
        assertEquals(1, ours.committedResource("Coal"))
    }

}
