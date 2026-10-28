package com.unciv.logic.trade

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerPeaceOperations
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon

/** Complete bilateral trades, independent of controller type or presentation.
 *
 * Drafts are detached. Preparing a draft never sends a request or spends anything. Enactment
 * rechecks the exact agreed terms; it must never call a draft helper to repair an agreement.
 * Callers own consent, request identity and persistence, and must serialize other game writes.
 */
class TradeOperations(val ours: Civilization, val theirs: Civilization) {
    fun stateReason(): String? = when {
        ours === theirs || ours.gameInfo !== theirs.gameInfo || !ours.isMajorCiv() || !theirs.isMajorCiv() ||
            !ours.knows(theirs) || !theirs.knows(ours) -> "Trade requires contact between major civilizations."
        ours.isDefeated() || theirs.isDefeated() -> "A civilization has been defeated."
        !ours.gameInfo.oneMoreTurnMode && (ours.gameInfo.victoryData != null || ours.gameInfo.civilizations.any { it.victoryManager.hasWon() }) -> "The game has ended."
        else -> null
    }

    /** The native negotiate-peace button's cooldown applies to starting a negotiation.
     * An already received native offer may still be accepted during that cooldown.
     */
    fun initiationReason(): String? = stateReason() ?: if (ours.isAtWarWith(theirs))
        PlayerPeaceOperations(ours).negotiationStateReason(theirs) else null

    fun available(commitmentId: String? = null): Trade = TradeLogic(ours, theirs, commitmentId).let { logic ->
        draft(logic.ourAvailableOffers, logic.theirAvailableOffers)
    }

    /** Prepare paired treaties and optional immediate research funding. Conflicting explicitly
     * supplied treaties are retained so validation can explain the conflict, never overwritten.
     */
    fun prepare(trade: Trade, fundResearch: Boolean = true): Trade = copy(trade).apply {
        for ((source, target) in listOf(ourOffers to theirOffers, theirOffers to ourOffers))
            for (offer in source.filter { it.type == TradeOfferType.Treaty })
                if (target.none { it.type == offer.type && it.name == offer.name }) target.add(offer.copy())
        if (fundResearch) fundResearchAgreement(this)
    }

    private fun fundResearchAgreement(trade: Trade) {
        val cost = trade.ourOffers.firstOrNull { it.type == TradeOfferType.Treaty && it.name == Constants.researchAgreement }?.amount ?: return
        val outgoing = trade.ourOffers.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount.toLong() }
        val incoming = trade.theirOffers.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount.toLong() }
        val ourBalance = ours.availableGold().toLong() + incoming - outgoing - cost
        val theirBalance = theirs.availableGold().toLong() + outgoing - incoming - cost
        fun fund(offers: TradeOffersList, existing: Long, balance: Int, deficit: Long) {
            if (deficit <= 0 || existing + deficit > balance || deficit > Int.MAX_VALUE) return
            offers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, deficit.toInt(), ours.gameInfo.speed))
        }
        if (ourBalance < 0 && theirBalance >= -ourBalance)
            fund(trade.theirOffers, incoming, theirs.availableGold(), -ourBalance)
        else if (theirBalance < 0 && ourBalance >= -theirBalance)
            fund(trade.ourOffers, outgoing, ours.availableGold(), -theirBalance)
    }

    fun invalidReason(trade: Trade, commitmentId: String? = null): String? = synchronized(ours.gameInfo) {
        stateReason()?.let { return@synchronized it }
        if (commitmentId != null) {
            ours.invalidatedTradeCommitments[commitmentId]?.let { return@synchronized it }
            theirs.invalidatedTradeCommitments[commitmentId]?.let { return@synchronized it }
        }
        val sides = listOf(trade.ourOffers, trade.theirOffers)
        if (sides.all { it.isEmpty() }) return@synchronized "A trade must contain terms."
        if (sides.any { offers -> offers.any { it.amount <= 0 } || offers.distinctBy { it.type to it.name }.size != offers.size })
            return@synchronized "Each item must appear once per giver with a positive quantity."
        val ourTreaties = trade.ourOffers.filter { it.type == TradeOfferType.Treaty }
        val theirTreaties = trade.theirOffers.filter { it.type == TradeOfferType.Treaty }
        if (!sameOffers(ourTreaties, theirTreaties))
            return@synchronized "Every treaty must have identical quantity, cost and duration on both sides."
        // The native wartime trade table preselects peace and does not let either party remove it.
        if (ours.isAtWarWith(theirs) && !trade.isPeaceTreaty())
            return@synchronized "Peace negotiations require a Peace Treaty on both sides."
        val options = available(commitmentId)
        for ((offers, available) in listOf(trade.ourOffers to options.ourOffers, trade.theirOffers to options.theirOffers)) {
            for (offer in offers) {
                if (available.none { it.type == offer.type && it.name == offer.name && it.duration == offer.duration &&
                        if (offer.type == TradeOfferType.Treaty) it.amount == offer.amount else it.amount >= offer.amount })
                    return@synchronized "The offered ${if (offer.type == TradeOfferType.City) "city" else offer.name} quantity, cost or duration is no longer available."
            }
        }
        val researchCost = ourTreaties.firstOrNull { it.name == Constants.researchAgreement }?.amount ?: 0
        for ((recipient, outgoing, incoming) in listOf(
            Triple(ours, trade.ourOffers, trade.theirOffers), Triple(theirs, trade.theirOffers, trade.ourOffers))) {
            val received = incoming.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount.toLong() }
            val given = outgoing.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount.toLong() }
            if (recipient.gold.toLong() + received > Int.MAX_VALUE)
                return@synchronized "The gold transfer exceeds the engine balance limit."
            if (researchCost > 0 && recipient.availableGold(commitmentId).toLong() + received - given < researchCost)
                return@synchronized "${recipient.civID} cannot fund the research agreement after the proposed gold transfers."
        }
        if (TradeEvaluation().isTradeValid(trade, ours, theirs)) null else "The engine no longer permits these trade terms."
    }

    /** False has no game effect. A true result applies exactly the supplied terms once; callers
     * must record that result under their own request/consent identity before returning it.
     */
    fun tryEnact(trade: Trade, commitmentId: String? = null): Boolean = synchronized(ours.gameInfo) {
        val agreed = copy(trade)
        if (invalidReason(agreed, commitmentId) != null) return@synchronized false
        if (commitmentId != null) {
            for (civ in listOf(ours, theirs)) {
                val held = civ.tradeCommitments.firstOrNull { it.id == commitmentId }
                if (held != null && (held.partner != (if (civ === ours) theirs.civID else ours.civID) ||
                        !sameTerms(held.trade, if (civ === ours) agreed else agreed.reverse()))) return@synchronized false
            }
            ours.releaseTradeCommitment(commitmentId)
            theirs.releaseTradeCommitment(commitmentId)
        }
        val logic = TradeLogic(ours, theirs)
        logic.currentTrade.set(agreed)
        logic.acceptTrade()
        if (agreed.isPeaceTreaty()) {
            ours.tradeRequests.removeAll { it.requestingCiv == theirs.civID && it.trade.isPeaceTreaty() }
            theirs.tradeRequests.removeAll { it.requestingCiv == ours.civID && it.trade.isPeaceTreaty() }
            ours.cache.updateCivResources()
            theirs.cache.updateCivResources()
        }
        true
    }

    /** Native queued-response adapter. Object identity prevents a stale popup from executing twice. */
    fun tryAcceptRequest(request: TradeRequest): Boolean = synchronized(ours.gameInfo) {
        if (request.requestingCiv != theirs.civID || ours.tradeRequests.none { it === request } ||
            invalidReason(request.trade, request.requestId) != null) return@synchronized false
        if (!tryEnact(request.trade, request.requestId)) return@synchronized false
        ours.tradeRequests.remove(request)
        theirs.addNotification("[${ours.civName}] has accepted your trade request", NotificationCategory.Trade,
            ours.civName, NotificationIcon.Trade)
        true
    }

    /** Reserve only the publishing side; receiving a proposal does not express consent. */
    fun tryReserve(trade: Trade, id: String, version: Int = 1, nativeRequest: Boolean = false): Boolean = synchronized(ours.gameInfo) {
        require(id.isNotEmpty() && version > 0)
        val previous = ours.tradeCommitments.firstOrNull { it.id == id }
        if (previous != null && previous.version == version)
            return@synchronized previous.partner == theirs.civID && sameTerms(previous.trade, trade)
        if (previous != null && previous.version >= version) return@synchronized false
        if (invalidReason(trade, id) != null) return@synchronized false
        val commitment = TradeCommitment.create(id, version, theirs.civID, trade, nativeRequest)
        if (commitment.gold > ours.availableGold(id)) return@synchronized false
        ours.releaseTradeCommitment(id)
        ours.invalidatedTradeCommitments.remove(id)
        ours.tradeCommitments = ArrayList(ours.tradeCommitments).apply { add(commitment) }
        true
    }

    companion object {
        /** Publish a native request and reserve its author's assets under the same monitor. */
        fun enqueue(recipient: Civilization, request: TradeRequest): Boolean = synchronized(recipient.gameInfo) {
            val author = recipient.gameInfo.civilizations.firstOrNull { it.civID == request.requestingCiv } ?: return@synchronized false
            if (request.requestId.isEmpty()) request.requestId = java.util.UUID.randomUUID().toString()
            val operations = TradeOperations(author, recipient)
            // Add first so reconciliation recognizes the request during reservation validation.
            recipient.tradeRequests.add(request)
            if (!operations.tryReserve(request.trade.reverse(), request.requestId, nativeRequest = true)) {
                recipient.tradeRequests.remove(request)
                return@synchronized false
            }
            author.cache.updateCivResources()
            true
        }

        fun draft(give: List<TradeOffer> = emptyList(), receive: List<TradeOffer> = emptyList()) = Trade().apply {
            // Preserve duplicate entries for validation instead of silently merging amounts.
            ourOffers.addAll(give.map { it.copy() })
            theirOffers.addAll(receive.map { it.copy() })
        }
        fun copy(trade: Trade) = draft(trade.ourOffers, trade.theirOffers)

        /** An absolute edit, including zero to remove an item. Treaty edits always update both
         * sides. Quantity legality is checked against fresh native availability on publication.
         */
        fun edit(offers: TradeOffersList, opposite: TradeOffersList, offer: TradeOffer, amount: Int) {
            require(amount >= 0)
            fun set(items: TradeOffersList) {
                items.removeAll { it.type == offer.type && it.name == offer.name }
                if (amount > 0) items.add(offer.copy(amount = amount))
            }
            set(offers)
            if (offer.type == TradeOfferType.Treaty) set(opposite)
        }

        // Trade.equalTrade intentionally ignores duration for ongoing agreement matching. Consent cannot.
        fun sameTerms(a: Trade, b: Trade) = sameOffers(a.ourOffers, b.ourOffers) && sameOffers(a.theirOffers, b.theirOffers)
        private fun sameOffers(a: List<TradeOffer>, b: List<TradeOffer>): Boolean {
            fun counts(offers: List<TradeOffer>) = offers.groupingBy { listOf(it.type, it.name, it.amount, it.duration) }.eachCount()
            return counts(a) == counts(b)
        }
    }
}
