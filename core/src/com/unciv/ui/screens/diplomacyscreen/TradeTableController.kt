package com.unciv.ui.screens.diplomacyscreen

import com.unciv.view.TradeView

/** Optional negotiation lifecycle for the ordinary native offer controls.
 * Drafts use native offers; publishing and accepting are supplied by the owner.
 * A controller must revalidate each action, including callbacks from an older dialog.
 */
interface TradeTableController {
    val tradeView: TradeView
    val canEdit: Boolean
    val message: String
    fun refresh(): Boolean
    fun draftChanged()
    fun actions(): List<TradeTableAction>
}

class TradeTableAction(val label: String, val enabled: Boolean = true, val run: () -> Unit)
