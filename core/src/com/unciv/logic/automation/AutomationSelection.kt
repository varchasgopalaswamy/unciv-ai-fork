package com.unciv.logic.automation

import yairm210.purity.annotations.Readonly

/**
 * Selection steps shared by automation and read-only callers. Inputs are values,
 * not civilizations, units, tiles or callbacks into a mutable game. Callers must
 * supply only candidates and facts available to their observer. A missing fact
 * is not permission to consult another player's private state.
 *
 * Each result is a next-action alternative, not a simulated sequence. In
 * particular a purchase, move or attack invalidates overlapping alternatives.
 */
object AutomationSelection {
    data class Yields(val food: Float = 0f, val production: Float = 0f, val gold: Float = 0f,
                      val science: Float = 0f, val culture: Float = 0f, val happiness: Float = 0f, val faith: Float = 0f) {
        @Readonly fun value(treasury: Int, goldPerTurn: Float) = statsValue(food, production, gold, science, culture, happiness, faith, treasury, goldPerTurn)
    }
    data class BuildingPreference(val weightedYields: Yields, val otherValue: Float, val treasury: Int, val goldPerTurn: Float) {
        @Readonly fun value() = weightedYields.value(treasury, goldPerTurn) + otherValue
    }
    @Readonly fun workersWanted(cities: Int) = if (cities <= 1) 1f else if (cities <= 5) cities * 1.8f else 9f + 1.3f * (cities - 5)
    @Readonly fun workerConstructionModifier(cities: Int, workers: Float): Float? =
        workersWanted(cities).takeIf { workers < it }?.let { it / (workers + 0.17f) }

    data class Citizen<T>(val value: T, val rank: Float, val longitude: Int, val latitude: Int)
    fun <T> citizen(tiles: List<Citizen<T>>, specialists: List<Ranked<T>>): T? {
        val tile = tiles.maxWithOrNull(compareBy({ it.rank }, { it.longitude }, { it.latitude }))
        val specialist = specialists.maxByOrNull { it.rank }
        return if ((tile?.rank ?: 0f) > (specialist?.rank ?: 0f)) tile?.value else specialist?.value
    }

    /** The resource and fort adjustments applied after native improvement yield valuation. */
    @Readonly fun improvementValue(yields: Float, bonusResource: Boolean?, oldImproves: Boolean,
                                    newImproves: Boolean, similarNearby: Boolean, replacesFort: Boolean): Float {
        var value = yields
        if (bonusResource != null) {
            val bonus = if (bonusResource) 0.3f else 2f
            if (oldImproves) value -= bonus
            if (newImproves && (!bonusResource || !similarNearby)) value += bonus
        }
        return if (replacesFort) value / 2 else value
    }

    @Readonly fun <T> improvement(best: T?, bestIsRoad: Boolean, resource: T?, current: T?,
                        resourceRequired: Boolean, currentRank: Float, bestRank: Float, removal: T?): T? = when {
        best != null && bestIsRoad -> best
        resource != null && resourceRequired -> resource.takeUnless { it == current }
        resourceRequired -> null
        best == null || current != null && currentRank > bestRank -> null
        removal != null -> removal
        else -> best
    }

    @Readonly fun cityDistanceValue(distance: Int, capital: Boolean): Float = (when (distance) {
        7 -> 2f; 6 -> 4f; 5 -> 8f; 4 -> 6f; 3 -> -25f
        else -> if (distance < 3) -30f else 0f
    }) * if (capital) 2 else 1

    data class Settlement(val distance: Int, val cityDistanceValue: Float, val coast: Boolean, val hill: Boolean,
                          val mountain: Boolean, val river: Boolean, val unimprovable: Boolean,
                          val resource: Boolean, val bonusResource: Boolean, val jungleLuxury: Boolean,
                          val rings: List<List<Float>>)
    @Readonly fun settlement(value: Settlement): Float {
        var score = value.cityDistanceValue
        if (value.coast) score += 3
        if (value.hill) score += 14
        if (value.mountain) score += 5
        if (value.river) score += 20
        if (value.unimprovable) score += 3
        if (value.resource) score -= 4
        if (value.bonusResource) score -= 8
        if (value.jungleLuxury) score += 10
        for ((distance, ring) in value.rings.withIndex())
            for (tile in ring) score += tile * (3f / (distance + 1))
        score -= (19 - value.rings.sumOf { it.size }) * 2.4f
        return score * (100 - (value.distance * 3f).coerceIn(0f, 99f)) / 100
    }

    @Readonly fun settleHere(currentRank: Float, bestRank: Float?) = bestRank == null || currentRank >= bestRank - 2

    data class Range<T>(val value: T, val minimum: Float, val maximum: Float)
    /** Candidates which may maximize the native rank, without consulting a damage roll. */
    fun <T> possibleHighest(choices: List<Range<T>>, threshold: Float = Float.NEGATIVE_INFINITY): List<T> {
        val bestMinimum = choices.maxOfOrNull { it.minimum } ?: return emptyList()
        return choices.filter { it.maximum >= bestMinimum && it.maximum > threshold }.map { it.value }
    }
    data class Construction<T>(val value: T, val modifier: Float, val remainingWork: Int)

    fun <T> construction(current: T, perpetual: Boolean, choices: List<Construction<T>>, fallback: T): T {
        if (!perpetual) return current
        return choices.maxByOrNull { (it.modifier / it.remainingWork.coerceAtLeast(1)).coerceAtLeast(0f) }?.value ?: fallback
    }

    fun purchase(gold: Int, cost: Int, hurryModifier: Int, investedProduction: Int): Boolean =
        gold >= cost && hurryModifier <= gold.coerceAtLeast(0) / 100 && investedProduction == 0

    data class Costed<T>(val value: T, val productionCost: Int)
    fun <T> upgrade(choices: List<Costed<T>>): T? = choices.minByOrNull { it.productionCost }?.value

    data class Ranked<T>(val value: T, val rank: Float)
    @Readonly fun <T> highest(choices: List<Ranked<T>>): T? = choices.maxByOrNull { it.rank }?.value

    data class NearbyEnemy<T>(val value: T, val distance: Int)
    fun <T> closestEnemy(choices: List<NearbyEnemy<T>>): T? = choices.minByOrNull { it.distance }?.value

    fun explorationRank(height: Int, unexploredAtSightEdge: Int): Int = height + unexploredAtSightEdge

    @Readonly fun canRest(health: Int, healing: Int, turns: Int, healsAfterAction: Boolean,
                safeCity: Boolean, enemyDistance: Int, avoidDistance: Int): Boolean =
        !healsAfterAction && (safeCity || enemyDistance > avoidDistance) &&
            kotlin.math.ceil((100 - health).toFloat() / turns).toInt() <= healing

    data class Promotion<T>(val value: T, val heals: Boolean, val skip: Boolean, val free: Boolean)
    /** The native executor draws randomly from this pool. A reader must not draw from live random state. */
    fun <T> promotions(health: Int, airOrMobile: Boolean, choices: List<Promotion<T>>): List<T> {
        val pool = if (health <= 60 && !airOrMobile && choices.any { it.heals }) choices.filter { it.heals }
            else choices.filterNot { it.skip }
        val free = pool.filter { it.free }
        return (free.ifEmpty { pool }).map { it.value }
    }

    @Readonly fun militaryAttackValue(defenderHealth: Int, damageToDefender: Int, movementLeft: Float): Int =
        200 - defenderHealth + damageToDefender + (movementLeft * 5).toInt()

    fun <T> attack(choices: List<Ranked<T>>): T? = choices.maxByOrNull { it.rank }?.takeIf { it.rank > 30 }?.value

    @Readonly fun statsValue(food: Float, production: Float, gold: Float, science: Float, culture: Float,
                   happiness: Float, faith: Float, treasury: Int, goldPerTurn: Float): Float =
        food * 1.2f + (if (treasury < 0 && goldPerTurn <= 0) gold else gold / 3) +
            happiness + production + science + culture + faith
}
