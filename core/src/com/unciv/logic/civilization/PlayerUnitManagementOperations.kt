package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.mapunit.MapUnit
import java.util.Collections

/** Unit naming, gifts and city promotion templates shared by ordinary player controls. */
class PlayerUnitManagementOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class Gift(
        val civilizationId: String,
        val available: Boolean,
        val reasons: List<String>,
        val consumesUnit: Boolean,
        val influenceChange: Float,
        val opinionChange: Float,
    )

    private fun owns(unit: MapUnit) = !spectatorMode && PlayerUnitOperations(civ).owns(unit)
    private fun canAct(unit: MapUnit) = owns(unit) && PlayerOperations(civ, spectatorMode).canAct()

    fun tryRename(unit: MapUnit, name: String): Boolean = synchronized(civ.gameInfo) {
        if (!canAct(unit) || name == unit.name) return@synchronized false
        unit.instanceName = name.takeIf { it.isNotEmpty() }
        true
    }

    fun promotionCity(unit: MapUnit): City? = unit.currentTile.getCity()
        ?.takeIf { owns(unit) && it.civ === civ && !it.isPuppet }

    fun trySavePromotions(unit: MapUnit): Boolean = synchronized(civ.gameInfo) {
        if (!canAct(unit)) return@synchronized false
        val city = promotionCity(unit) ?: return@synchronized false
        city.unitShouldUseSavedPromotion[unit.baseUnit.name] = true
        city.unitToPromotions[unit.baseUnit.name] = unit.promotions
        true
    }

    fun tryUseSavedPromotions(city: City, unitType: String, enabled: Boolean): Boolean = synchronized(civ.gameInfo) {
        if (!PlayerOperations(civ, spectatorMode).canAct() || city.civ !== civ || city !in civ.cities || city.isPuppet ||
            unitType !in city.unitToPromotions) return@synchronized false
        city.unitShouldUseSavedPromotion[unitType] = enabled
        true
    }

    fun gift(unit: MapUnit): Gift? = synchronized(civ.gameInfo) {
        if (!owns(unit)) return@synchronized null
        val recipient = unit.currentTile.getOwner()?.takeIf { it !== civ && civ.knows(it) } ?: return@synchronized null
        if (recipient.isCityState) {
            val option = PlayerCityStateOperations(civ, spectatorMode).giftOption(unit) ?: return@synchronized null
            return@synchronized Gift(recipient.civID, option.available, option.reasons, unit.isGreatPerson(), option.influence, 0f)
        }
        if (!recipient.isMajorCiv() || !unit.currentTile.isFriendlyTerritory(civ) || unit.isTransported) return@synchronized null
        val reasons = buildList {
            if (!PlayerOperations(civ, spectatorMode).canAct()) add("This player cannot act now")
            if (!unit.hasMovement()) add("No movement remaining")
        }
        Gift(recipient.civID, reasons.isEmpty(), Collections.unmodifiableList(reasons), false, 0f, 5f)
    }

    fun tryGift(unit: MapUnit): Boolean = synchronized(civ.gameInfo) {
        val option = gift(unit)?.takeIf { it.available } ?: return@synchronized false
        val recipient = civ.gameInfo.getCivilization(option.civilizationId)
        if (recipient.isCityState) return@synchronized PlayerCityStateOperations(civ, spectatorMode).tryGiftUnit(unit)
        recipient.getDiplomacyManager(civ)!!.addModifier(DiplomaticModifiers.GaveUsUnits, option.opinionChange)
        unit.gift(recipient)
        true
    }
}
