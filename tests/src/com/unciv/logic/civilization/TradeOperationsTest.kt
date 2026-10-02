package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.trade.*
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class TradeOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(9) }
    private val ours = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val theirs = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val capital = game.addCity(ours, game.getTile(-5, 0))
    private val otherCapital = game.addCity(theirs, game.getTile(5, 0))
    private val operations = TradeOperations(ours, theirs)
    init {
        ours.diplomacyFunctions.makeCivilizationsMeet(theirs)
        ours.addGold(1000)
        theirs.addGold(1000)
        game.gameInfo.currentPlayer = ours.civID
        game.gameInfo.currentPlayerCiv = ours
    }
    private fun gold(amount: Int) = TradeOffer(Constants.flatGold, TradeOfferType.Gold, amount, game.gameInfo.speed)
    private fun advanced() {
        for (civ in listOf(ours, theirs)) {
            fun research(name: String) {
                if (civ.tech.isResearched(name)) return
                game.ruleset.technologies.getValue(name).prerequisites.forEach(::research)
                civ.tech.addTechnology(name)
            }
            research("Education")
            research("Chivalry")
        }
        val available = operations.available()
        assertTrue(operations.tryEnact(TradeOperations.draft(
            available.ourOffers.filter { it.type == TradeOfferType.Embassy },
            available.theirOffers.filter { it.type == TradeOfferType.Embassy })))
        ours.getDiplomacyManager(theirs)!!.signDeclarationOfFriendship()
    }

    @Test fun `draft edits pair and remove treaties without affecting balances requests or source terms`() {
        advanced()
        val treaty = operations.available().ourOffers.single { it.name == Constants.researchAgreement }
        theirs.addGold(-theirs.gold)
        val original = TradeOperations.draft(listOf(treaty))
        val prepared = operations.prepare(original)
        assertEquals(1, original.ourOffers.size)
        assertTrue(original.theirOffers.isEmpty())
        assertEquals(treaty.amount, prepared.ourOffers.single { it.type == TradeOfferType.Gold }.amount)
        assertTrue(ours.tradeRequests.isEmpty() && theirs.tradeRequests.isEmpty())
        assertEquals(1000, ours.gold)
        assertEquals(0, theirs.gold)
        assertNull(operations.invalidReason(prepared))
        TradeOperations.edit(prepared.theirOffers, prepared.ourOffers, treaty, 0)
        assertTrue((prepared.ourOffers + prepared.theirOffers).none { it.type == TradeOfferType.Treaty })
        assertEquals(treaty.amount, original.ourOffers.single().amount)
    }

    @Test fun `research funding includes existing transfers and accepts exact combined affordability`() {
        advanced()
        val treaty = operations.available().ourOffers.single { it.name == Constants.researchAgreement }
        ours.addGold(2 * treaty.amount - ours.gold)
        theirs.addGold(-theirs.gold)
        val explicit = operations.prepare(TradeOperations.draft(listOf(treaty, gold(10))))
        assertEquals(treaty.amount, explicit.ourOffers.single { it.type == TradeOfferType.Gold }.amount)
        assertNull(operations.invalidReason(explicit))
        assertTrue(operations.tryEnact(explicit))
        assertEquals(0, ours.gold)
        assertEquals(0, theirs.gold)
        assertEquals(treaty.duration, ours.getDiplomacyManager(theirs)!!.getFlag(DiplomacyFlags.ResearchAgreement))
    }

    @Test fun `acceptance cannot repair unfunded asymmetric or changed duration terms`() {
        advanced()
        val treaty = operations.available().ourOffers.single { it.name == Constants.researchAgreement }
        theirs.addGold(-theirs.gold)
        for (trade in listOf(TradeOperations.draft(listOf(treaty)),
            TradeOperations.draft(listOf(treaty), listOf(treaty.copy(duration = treaty.duration + 1))),
            TradeOperations.draft(listOf(treaty), listOf(treaty)))) {
            assertNotNull(operations.invalidReason(trade))
            assertFalse(operations.tryEnact(trade))
        }
        assertEquals(1000, ours.gold)
        assertEquals(0, theirs.gold)
        assertFalse(ours.getDiplomacyManager(theirs)!!.hasFlag(DiplomacyFlags.ResearchAgreement))
    }

    @Test fun `duplicate excessive and overflowing transfers leave the game unchanged`() {
        val duplicate = TradeOperations.draft(listOf(gold(10), gold(20)))
        assertEquals(2, duplicate.ourOffers.size)
        for (trade in listOf(duplicate, TradeOperations.draft(listOf(gold(1001))), TradeOperations.draft(listOf(gold(0)))))
            assertFalse(operations.tryEnact(trade))
        theirs.addGold(Int.MAX_VALUE - theirs.gold)
        assertFalse(operations.tryEnact(TradeOperations.draft(listOf(gold(1)))))
        assertEquals(1000, ours.gold)
        assertEquals(Int.MAX_VALUE, theirs.gold)
    }

    @Test fun `native staging and headless drafts preserve identity direction quantities and durations`() {
        val city = game.addCity(ours, game.getTile(-5, -5))
        advanced()
        val view = GameView(game.gameInfo, ours)
        val native = view.civView.getTradeView(view.getForeignCivView(theirs))
        val options = operations.available()
        val give = options.ourOffers.filter { it.type == TradeOfferType.City || it.name == Constants.defensivePact }
        val trade = operations.prepare(TradeOperations.draft(give, listOf(gold(25))))
        native.setStagedTrade(trade)
        assertTrue(TradeOperations.sameTerms(trade, TradeOperations.draft(native.ourStagedOffers(), native.theirStagedOffers())))
        assertTrue(native.tryProposeStagedTrade())
        val request = theirs.tradeRequests.single()
        assertTrue(TradeOperations.sameTerms(trade.reverse(), request.trade))
        assertFalse(TradeOperations.sameTerms(trade, TradeOperations.draft(trade.ourOffers.map { it.copy(duration = it.duration + 1) }, trade.theirOffers)))
        assertTrue(TradeOperations(theirs, ours).tryAcceptRequest(request))
        assertFalse(TradeOperations(theirs, ours).tryAcceptRequest(request))
        assertSame(theirs, city.civ)
        assertEquals(1025, ours.gold)
        assertEquals(975, theirs.gold)
        assertTrue(ours.getDiplomacyManager(theirs)!!.hasFlag(DiplomacyFlags.DefensivePact))
    }

    @Test fun `resources and income cannot be oversold and stale cities cannot transfer`() {
        capital.getCenterTile().setTileResource("Silk")
        otherCapital.getCenterTile().setTileResource("Iron")
        otherCapital.getCenterTile().resourceAmount = 4
        ours.tech.addTechnology("Calendar")
        theirs.tech.addTechnology("Mining")
        theirs.tech.addTechnology("Iron Working")
        ours.cache.updateCivResources()
        theirs.cache.updateCivResources()
        theirs.stats.statsForNextTurn.gold = 5f
        val options = operations.available()
        val silk = options.ourOffers.single { it.name == "Silk" }
        val iron = options.theirOffers.single { it.name == "Iron" }.copy(amount = 2)
        val gpt = options.theirOffers.single { it.type == TradeOfferType.Gold_Per_Turn }
        assertTrue(operations.tryEnact(TradeOperations.draft(listOf(silk), listOf(iron, gpt))))
        assertEquals(2, ours.getResourceAmount("Iron"))
        assertEquals(1, theirs.getResourceAmount("Silk"))
        assertFalse(operations.tryEnact(TradeOperations.draft(listOf(silk))))
        val city = game.addCity(ours, game.getTile(-5, -5))
        val offer = operations.available().ourOffers.single { it.name == city.id }
        city.hasJustBeenConquered = true
        assertFalse(operations.tryEnact(TradeOperations.draft(listOf(offer))))
        assertSame(ours, city.civ)
    }

    @Test fun `third party war and mediated peace use native availability and effects`() {
        val target = game.addCiv(game.ruleset.nations.getValue("France"))
        game.addCity(target, game.getTile(0, 7))
        ours.diplomacyFunctions.makeCivilizationsMeet(target)
        theirs.diplomacyFunctions.makeCivilizationsMeet(target)
        val war = operations.available().ourOffers.single { it.type == TradeOfferType.WarDeclaration && it.name == target.civID }
        assertTrue(operations.tryEnact(TradeOperations.draft(listOf(war))))
        assertTrue(ours.isAtWarWith(target))
        assertFalse(theirs.isAtWarWith(target))
        ours.getDiplomacyManager(target)!!.removeFlag(DiplomacyFlags.DeclaredWar)
        target.getDiplomacyManager(ours)!!.removeFlag(DiplomacyFlags.DeclaredWar)
        val peace = operations.available().ourOffers.single { it.type == TradeOfferType.PeaceProposal && it.name == target.civID }
        assertEquals(1, peace.amount)
        assertTrue(operations.tryEnact(TradeOperations.draft(listOf(peace))))
        assertFalse(ours.isAtWarWith(target))
        assertEquals(game.gameInfo.speed.peaceDealDuration, ours.getDiplomacyManager(target)!!.turnsToPeaceTreaty())
    }
}
