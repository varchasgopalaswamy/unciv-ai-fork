package com.unciv.ui

import com.badlogic.gdx.InputProcessor
import com.unciv.logic.GameInfo
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.diplomacyscreen.TradeTable
import com.unciv.view.CivView
import com.unciv.view.ForeignCivView
import kotlin.coroutines.CoroutineContext
import yairm210.purity.annotations.Readonly

/** Optional desktop integration with externally coordinated turn ownership.
 *
 * The application owns one live game. A session fixes its viewing player, coordinates
 * UI work with external state changes and supplies its own turn handoff and diplomacy.
 * Ordinary local and multiplayer games do not install a session.
 */
interface ManagedGameSession {
    val viewingCivilization: String
    val unreadCount: Int
    fun ownsGame(game: GameInfo): Boolean
    fun canChangeState(game: GameInfo): Boolean
    @Readonly fun canAct(civilization: String): Boolean
    @Readonly fun controllerLabel(civilization: String): String? = null
    fun uiContext(): CoroutineContext
    fun input(processor: InputProcessor): InputProcessor
    fun endTurn(worldScreen: WorldScreen)
    fun openDiplomacy(civilization: String? = null): Boolean
    fun usesSharedDiplomacy(civilization: String): Boolean
    fun createTradeTable(viewer: CivView, partner: ForeignCivView, screen: DiplomacyScreen): TradeTable? = null
    fun worldScreenCreated(worldScreen: WorldScreen)
    fun exit()
    fun failed(failure: Throwable)
}
