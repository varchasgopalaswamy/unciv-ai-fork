package com.unciv.view

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOperations
import com.unciv.logic.trade.TradeOffersList
import com.unciv.logic.trade.TradeRequest
import yairm210.purity.annotations.Readonly

/** View of a trade negotiation between [civ] (always the viewer's own civ) and [otherCiv].
 *  Always built fresh, same as [TradeLogic] itself - staged offers live in this instance only. */
class TradeView(private val civ: Civilization, private val otherCiv: Civilization, gameView: GameView) : GameBasedView<Civilization>(civ, civ, gameView = gameView) {
    private val tradeLogic = TradeLogic(civ, otherCiv)

    // Data retrieval - lists are the live mutable instances, callers may add/remove offers directly
    @Readonly fun ourAvailableOffers(): TradeOffersList = tradeLogic.ourAvailableOffers
    @Readonly fun theirAvailableOffers(): TradeOffersList = tradeLogic.theirAvailableOffers
    @Readonly fun ourStagedOffers(): TradeOffersList = tradeLogic.currentTrade.ourOffers
    @Readonly fun theirStagedOffers(): TradeOffersList = tradeLogic.currentTrade.theirOffers
    @Readonly fun hasPendingOfferFromUs(): Boolean =
        otherCiv.tradeRequests.any { it.requestingCiv == civ.civID }

    // Actions - staging
    fun setStagedTrade(trade: Trade) = tradeLogic.currentTrade.set(TradeOperations.copy(trade))

    /** Staging only; callers must display these final terms before submitting them. */
    fun fundStagedResearchAgreement() {
        setStagedTrade(TradeOperations(civ, otherCiv).prepare(tradeLogic.currentTrade))
    }

    /** Restores a trade we already sent to [otherCiv] (if any) into staging, so it can be re-displayed or retracted. */
    fun tryLoadOurPendingOffer(): Boolean {
        val existing = otherCiv.tradeRequests.firstOrNull { it.requestingCiv == civ.civID } ?: return false
        tradeLogic.currentTrade.set(existing.trade.reverse())
        return true
    }

    // Actions - proposing
    fun tryProposeStagedTrade(): Boolean {
        if (!com.unciv.logic.civilization.PlayerOperations(civ, spectatorMode).canAct()) return false
        if (com.unciv.UncivGame.isCurrentInitialized() && com.unciv.UncivGame.Current.managedSession?.usesSharedDiplomacy(otherCiv.civID) == true) return false
        val operations = TradeOperations(civ, otherCiv)
        if (operations.initiationReason() != null || operations.invalidReason(tradeLogic.currentTrade) != null) return false
        if (hasPendingOfferFromUs()) return false
        otherCiv.tradeRequests.add(TradeRequest(civ.civID, tradeLogic.currentTrade.reverse()))
        civ.cache.updateCivResources()
        return true
    }

    fun tryRetractOffer(): Boolean {
        if (!com.unciv.logic.civilization.PlayerOperations(civ, spectatorMode).canAct()) return false
        if (!hasPendingOfferFromUs()) return false
        otherCiv.tradeRequests.removeAll { it.requestingCiv == civ.civID }
        civ.cache.updateCivResources()
        return true
    }
}
