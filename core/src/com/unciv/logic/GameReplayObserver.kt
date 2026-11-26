package com.unciv.logic

import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/** Optional synchronous notifications for a trusted recorder. Callbacks must not mutate the game. */
interface GameReplayObserver {
    fun turnStarted(civilization: String)

    /** Called after a unit enters a tile and its sight has been refreshed. */
    fun unitEntered(unit: MapUnit, from: HexCoord?, visibleBefore: Set<String>, transit: List<HexCoord>)

    /** Called after a unit has been removed from the map. */
    fun unitRemoved(unit: MapUnit, visibleBefore: Set<String>)

    fun territoryChanged(tile: Tile)

    /** Called before completed attack facts can expire. The ID can select filtered attack reports. */
    fun attackCompleted(id: String)
}
