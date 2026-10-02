package com.unciv.logic.trade

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers

data class UnavailableTradeItem(val type: TradeOfferType, val name: String? = null, val amount: Int? = null, val duration: Int? = null, val reason: String)

/** Reasons for omitted native choices, without disclosing the partner's hidden technologies. */
fun unavailableTradeItems(a: Civilization, b: Civilization): List<UnavailableTradeItem> {
    val offered = TradeOperations(a, b).available().ourOffers
    val reason = TradeOperations(a, b).initiationReason()
    val special = listOf(
        TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = a.gameInfo.speed),
        TradeOffer(Constants.openBorders, TradeOfferType.Agreement, speed = a.gameInfo.speed),
        TradeOffer(Constants.researchAgreement, TradeOfferType.Treaty, a.diplomacyFunctions.getResearchAgreementCost(b), a.gameInfo.speed),
        TradeOffer(Constants.defensivePact, TradeOfferType.Treaty, speed = a.gameInfo.speed),
    )
    val diplomacy = a.getDiplomacyManager(b)!!
    return special.filter { option -> offered.none { it.type == option.type && it.name == option.name } }.map { option ->
        val why = reason ?: when {
            a.isAtWarWith(b) -> "Requires peace."
            option.type == TradeOfferType.Embassy && (diplomacy.hasModifier(DiplomaticModifiers.ReceivedEmbassy) || diplomacy.hasModifier(DiplomaticModifiers.SharedEmbassies)) -> "This player already has an embassy in your capital."
            option.type == TradeOfferType.Embassy && a.getCapital() == null -> "No capital is available for an embassy."
            option.type == TradeOfferType.Embassy && (diplomacy.getFlag(DiplomacyFlags.Denunciation) == 30 || b.getDiplomacyManager(a)!!.getFlag(DiplomacyFlags.Denunciation) == 30) -> "Embassies cannot be established on the turn of a denunciation."
            option.type == TradeOfferType.Embassy -> "Requires embassy technology, a capital, no existing embassy in this direction, and no denunciation this turn."
            option.name == Constants.openBorders && b.getDiplomacyManager(a)!!.hasOpenBorders -> "You already grant this player Open Borders."
            !a.diplomacyFunctions.meetsEmbassyRequirementFor(b) -> "This ruleset requires mutual embassies first."
            option.name == Constants.openBorders -> "Both civilizations need technology enabling Open Borders."
            !diplomacy.hasFlag(DiplomacyFlags.DeclarationOfFriendship) -> "Requires an active Declaration of Friendship."
            option.name == Constants.researchAgreement && diplomacy.hasFlag(DiplomacyFlags.ResearchAgreement) -> "A research agreement is already active."
            option.name == Constants.defensivePact && diplomacy.hasFlag(DiplomacyFlags.DefensivePact) -> "A defensive pact is already active."
            option.name == Constants.researchAgreement -> "Both civilizations need technology enabling research agreements and remaining research."
            else -> "Both civilizations need technology enabling defensive pacts."
        }
        UnavailableTradeItem(option.type, option.name, option.amount, option.duration, why)
    } + if (offered.none { it.type == TradeOfferType.City }) {
        listOf(UnavailableTradeItem(TradeOfferType.City, reason = reason ?: when {
            a.isOneCityChallenger() || b.isOneCityChallenger() -> "One City Challenge prevents city trading."
            a.cities.any { it.hasJustBeenConquered } || a.popupAlerts.any { it.type in setOf(AlertType.CityTraded, AlertType.CityConquered) } -> "A pending city disposition must be resolved before trading that city."
            else -> "Only non-capital cities outside resistance can be traded."
        }))
    } else {
        emptyList()
    }
}
