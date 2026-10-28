package com.unciv.logic

import com.unciv.models.ruleset.PerpetualConstruction

/** A detached world for read-only estimates that need native cache recalculation.
 * Empty queues must not choose production while setting up a preview. City tile
 * sets are serialization collections that GameInfo.clone otherwise shares.
 */
fun GameInfo.copyForPreview(): GameInfo = clone().also { copy ->
    copy.gameParameters = gameParameters.clone()
    copy.gameParameters.godMode = gameParameters.godMode
    copy.tileMap.mapParameters = tileMap.mapParameters.clone()
    for (civ in copy.civilizations) for (city in civ.cities) {
        city.tiles = HashSet(city.tiles)
        city.workedTiles = HashSet(city.workedTiles)
        city.lockedTiles = HashSet(city.lockedTiles)
        if (city.cityConstructions.constructionQueue.isEmpty())
            city.cityConstructions.constructionQueue.add(PerpetualConstruction.Idle.name)
    }
    if (copy.currentPlayer.isEmpty()) copy.currentPlayer = copy.civilizations.first().civID
    copy.setTransients(ruleset)
}
