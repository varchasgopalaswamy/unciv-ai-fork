package com.unciv.logic.trade

import com.unciv.Constants
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization

/** Assets allocated to an exact, published offer. No transfer occurs until enactment. */
class TradeCommitment : IsPartOfGameInfoSerialization {
    var id = ""
    var version = 1
    var partner = ""
    var nativeRequest = false
    var gold = 0
    var resources = HashMap<String, Int>()
    var trade = Trade()

    fun clone() = TradeCommitment().also {
        it.id = id
        it.version = version
        it.partner = partner
        it.nativeRequest = nativeRequest
        it.gold = gold
        it.resources.putAll(resources)
        it.trade = TradeOperations.copy(trade)
    }

    companion object {
        fun create(id: String, version: Int, partner: String, trade: Trade, nativeRequest: Boolean) =
            TradeCommitment().also { result ->
                result.id = id
                result.version = version
                result.partner = partner
                result.nativeRequest = nativeRequest
                result.trade = TradeOperations.copy(trade)
                val outgoing = trade.ourOffers.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount.toLong() }
                val incoming = trade.theirOffers.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount.toLong() }
                val signing = trade.ourOffers.filter { it.type == TradeOfferType.Treaty && it.name == Constants.researchAgreement }
                    .sumOf { it.amount.toLong() }
                // Incoming gold can fund signing in this exchange, but not another offer or a purchase.
                result.gold = maxOf(outgoing, outgoing + signing - incoming, 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                for (offer in trade.ourOffers.filter { it.type in setOf(TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource) })
                    result.resources[offer.name] = offer.amount
            }

        /** Remove orphaned native holds, including requests withdrawn through an older caller. */
        fun reconcile(civ: Civilization) {
            val pending = civ.gameInfo.civilizations.flatMap { it.tradeRequests }
                .filter { it.requestingCiv == civ.civID }.map { it.requestId }.toSet()
            civ.tradeCommitments = ArrayList(civ.tradeCommitments.filterNot { it.nativeRequest && it.id !in pending })
        }

        /** Unavoidable losses invalidate newest offers, rather than blocking maintenance or combat. */
        fun reconcileBacking(civ: Civilization) {
            reconcile(civ)
            while (civ.tradeCommitments.sumOf { it.gold.toLong() } > civ.gold.coerceAtLeast(0)) {
                val commitment = civ.tradeCommitments.lastOrNull { it.gold > 0 } ?: break
                invalidate(civ, commitment, "Gold committed to this offer is no longer available.")
            }
        }

        fun reconcileResources(civ: Civilization) {
            reconcile(civ)
            val names = civ.tradeCommitments.flatMap { it.resources.keys }.toSet()
            for (name in names)
                while (civ.tradeCommitments.sumOf { it.resources[name]?.toLong() ?: 0L } > civ.getResourceAmount(name).coerceAtLeast(0)) {
                    val commitment = civ.tradeCommitments.lastOrNull { (it.resources[name] ?: 0) > 0 } ?: break
                    invalidate(civ, commitment, "Resources committed to this offer are no longer available.")
                }
        }

        fun invalidate(civ: Civilization, commitment: TradeCommitment, reason: String) {
            civ.releaseTradeCommitment(commitment.id)
            civ.invalidatedTradeCommitments[commitment.id] = reason
            if (commitment.nativeRequest)
                for (recipient in civ.gameInfo.civilizations)
                    recipient.tradeRequests.removeAll { it.requestId == commitment.id && it.requestingCiv == civ.civID }
        }
    }
}
