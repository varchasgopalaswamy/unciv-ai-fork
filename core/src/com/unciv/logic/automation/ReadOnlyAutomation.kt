package com.unciv.logic.automation

/** Value-only boundary for read-only, observer-scoped automation branches. */
object ReadOnlyAutomation {
    enum class Kind { ContinueConstruction, Construction, Purchase, Upgrade, Promote, Heal, Attack, Bombard, Improve, Move, FoundCity, AssignCitizen }
    data class Action(val kind: Kind, val subject: String, val selection: String? = null,
                      val destination: String? = null, val origin: String? = null)
    data class Suggestion(val action: Action, val branch: String, val priority: Int)
    data class Purchase(val action: Action, val cost: Int, val hurryModifier: Int, val invested: Int)
    data class Upgrade(val action: Action, val productionCost: Int, val requiresNewResource: Boolean)
    data class Promotion(val action: Action, val heals: Boolean, val skip: Boolean, val free: Boolean)
    data class Attack(val action: Action, val defenderHealth: Int, val minimumDamage: Int, val maximumDamage: Int,
                      val maximumRetaliation: Int, val movementLeft: Float, val terrainDamage: Int,
                      val militaryTarget: Boolean)
    data class RankedAction(val action: Action, val rank: Float, val improvesResource: Boolean = false)
    data class Construction(val action: Action, val remainingWork: Int, val weight: Float,
                            val preference: AutomationSelection.BuildingPreference? = null, val modifier: Float = 0f)
    data class Settlement(val action: Action, val value: AutomationSelection.Settlement, val minimum: Float, val initialCity: Boolean = false)
    data class City(val id: String, val current: String?, val perpetual: Boolean,
                    val purchases: List<Purchase>, val bombardments: List<AutomationSelection.Range<Action>>,
                    val citizenTiles: List<AutomationSelection.Citizen<Action>> = emptyList(),
                    val specialists: List<AutomationSelection.Ranked<Action>> = emptyList(),
                    val constructions: List<Construction> = emptyList())
    data class Unit(val id: String, val health: Int, val healing: Int, val healsAfterAction: Boolean,
                    val airOrMobile: Boolean, val safeCity: Boolean, val nearestEnemy: Int,
                    val heal: Action?, val upgrades: List<Upgrade>, val promotions: List<Promotion>,
                    val attacks: List<Attack>, val improvements: List<RankedAction>,
                    val exploration: List<RankedAction>, val settlement: List<Settlement> = emptyList(),
                    val resourceImprovementRequired: Boolean = false,
                    val militaryAdvance: List<AutomationSelection.NearbyEnemy<Action>> = emptyList())
    data class Position(val gold: Int, val cities: List<City>, val units: List<Unit>)

    /**
     * No action is applied and no random choice is drawn. Branches sharing a
     * subject, budget, destination or target are alternatives. Null/empty inputs
     * mean that the caller could not establish the branch from its knowledge.
     */
    fun select(position: Position, cancelled: () -> Boolean = { false }): List<Suggestion> {
        val result = ArrayList<Suggestion>()
        fun checkCancellation() { if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException() }
        for (city in position.cities) {
            checkCancellation()
            if (city.current != null && !city.perpetual)
                result.add(Suggestion(Action(Kind.ContinueConstruction, city.id, city.current), "continue-investment", 10))
            else if (city.constructions.isNotEmpty()) {
                val keep = Action(Kind.ContinueConstruction, city.id, city.current)
                val selected = AutomationSelection.construction(keep, true, city.constructions.map {
                    AutomationSelection.Construction(it.action, (it.preference?.value() ?: it.modifier) * it.weight, it.remainingWork)
                }, keep)
                result.add(Suggestion(selected, "native-construction-value-per-remaining-production-among-observable-candidates", 70))
            }
            for (purchase in city.purchases) {
                checkCancellation()
                if (AutomationSelection.purchase(position.gold, purchase.cost, purchase.hurryModifier, purchase.invested))
                    result.add(Suggestion(purchase.action, "uninvested-current-construction-purchase", 50))
            }
            AutomationSelection.possibleHighest(city.bombardments).forEach {
                result.add(Suggestion(it, "native-bombard-damage-alternative-without-a-hidden-roll", 90))
            }
            AutomationSelection.citizen(city.citizenTiles, city.specialists)?.let {
                result.add(Suggestion(it, "next-free-citizen-native-yield-preferences", 70))
            }
        }
        for (unit in position.units) {
            checkCancellation()
            AutomationSelection.upgrade(unit.upgrades.map { AutomationSelection.Costed(it, it.productionCost) })
                ?.takeUnless { it.requiresNewResource }?.let {
                    result.add(Suggestion(it.action, "least-production-upgrade-without-new-resource-type", 60))
                }
            AutomationSelection.promotions(unit.health, unit.airOrMobile, unit.promotions.map {
                AutomationSelection.Promotion(it.action, it.heals, it.skip, it.free)
            }).forEach { result.add(Suggestion(it, "native-promotion-pool-no-random-draw", 80)) }
            if (unit.health < 100 && unit.heal != null && AutomationSelection.canRest(unit.health, unit.healing,
                    2, unit.healsAfterAction, unit.safeCity, unit.nearestEnemy, 3))
                result.add(Suggestion(unit.heal, "heal-in-two-turns-without-known-nearby-enemy", 85))
            val targets = unit.attacks.filter { it.militaryTarget && it.maximumRetaliation < unit.health - 1 && it.terrainDamage <= 0 }
                .map { AutomationSelection.Range(it.action,
                    AutomationSelection.militaryAttackValue(it.defenderHealth, it.minimumDamage, it.movementLeft).toFloat(),
                    if (it.maximumDamage >= it.defenderHealth) Float.POSITIVE_INFINITY else
                        AutomationSelection.militaryAttackValue(it.defenderHealth, it.maximumDamage, it.movementLeft).toFloat()) }
            AutomationSelection.possibleHighest(targets, 30f).forEach {
                result.add(Suggestion(it, "native-military-target-value-alternative-without-a-hidden-roll", 90))
            }
            val bestImprovement = unit.improvements.filter { it.rank > 0f }.maxByOrNull { it.rank }
            val resourceImprovement = unit.improvements.filter { it.improvesResource }.maxByOrNull { it.rank }
            AutomationSelection.improvement(bestImprovement?.action, false, resourceImprovement?.action, null,
                unit.resourceImprovementRequired, 0f, bestImprovement?.rank ?: 0f, null)
                ?.let { result.add(Suggestion(it, "current-tile-native-improvement-value-and-resource-priority", 65)) }
            AutomationSelection.highest(unit.exploration.map { AutomationSelection.Ranked(it.action, it.rank) })
                ?.let { result.add(Suggestion(it, "reachable-exploration-value", 30)) }
            AutomationSelection.closestEnemy(unit.militaryAdvance)?.let {
                result.add(Suggestion(it, "advance-towards-closest-observed-attackable-enemy", 55))
            }
            val sites = unit.settlement.map { it to AutomationSelection.settlement(it.value) }.filter { it.second >= it.first.minimum }
            val best = sites.maxByOrNull { it.second }
            val current = sites.firstOrNull { it.first.action.kind == Kind.FoundCity }
            val site = if (current != null && (current.first.initialCity || AutomationSelection.settleHere(current.second, best?.second))) current else best
            site?.let { result.add(Suggestion(it.first.action, "nearby-native-settlement-value-with-observed-rings", 75)) }
        }
        checkCancellation()
        return result.sortedByDescending { it.priority }.toList()
    }
}
