package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.models.translations.tr
import com.unciv.logic.trade.TradeOperations
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView
import com.unciv.view.ForeignCivView

class TradeTable(
    private val civ: CivView,
    private val otherCivilization: ForeignCivView,
    diplomacyScreen: DiplomacyScreen,
    private val controller: TradeTableController? = null
): Table(BaseScreen.skin) {
    internal val tradeView = controller?.tradeView ?: civ.getTradeView(otherCivilization)
    internal val offerColumnsTable = OfferColumnsTable(tradeView, diplomacyScreen, civ, otherCivilization,
        canEdit = { controller?.canEdit != false },
        availableHeight = diplomacyScreen.stage.height - if (controller != null) 140f else 0f
    ) { onChange() }
    // This is so that after a trade has been traded, we can switch out the offersToDisplay to start anew - this is the easiest way
    private val offerColumnsTableWrapper = Table()

    val offerTradeText = "{Offer trade}\n({They'll decide on their turn})"
    private val offerButton = offerTradeText.toTextButton()
    private val lowerTable = Table().apply { defaults().pad(10f) }
    private val statusLabel = Label("", BaseScreen.skin).apply { setWrap(true) }

    private fun retractOffer() {
        tradeView.tryRetractOffer()
        offerButton.setText(offerTradeText.tr())
    }

    init {
        if (controller != null)
            add(statusLabel).width(diplomacyScreen.getTradeColumnsWidth() * 2 - 40f).pad(8f).row()
        offerColumnsTableWrapper.add(offerColumnsTable)
        add(offerColumnsTableWrapper).row()

        if (controller == null && tradeView.tryLoadOurPendingOffer())
            offerColumnsTable.update()

        if (tradeView.hasPendingOfferFromUs()) offerButton.setText("Retract offer".tr())
        else offerButton.apply { isEnabled = false }.setText(offerTradeText.tr())

        offerButton.onClick {
            if (tradeView.hasPendingOfferFromUs()) {
                retractOffer()
                return@onClick
            }
            tradeView.fundStagedResearchAgreement()
            offerColumnsTable.update()
            val agreed = TradeOperations.draft(tradeView.ourStagedOffers(), tradeView.theirStagedOffers())
            val text = "Our trade offer".tr() + "\n" + agreed.ourOffers.joinToString("\n") { it.getOfferText() } +
                "\n" + "[${otherCivilization.civName}]'s trade offer".tr() + "\n" + agreed.theirOffers.joinToString("\n") { it.getOfferText() }
            ConfirmPopup(diplomacyScreen, text, "Offer trade") {
                tradeView.setStagedTrade(agreed)
                if (tradeView.tryProposeStagedTrade()) offerButton.setText("Retract offer".tr())
            }.open()
        }

        if (controller == null) lowerTable.add(offerButton) else updateActions()

        lowerTable.pack()
        lowerTable.y = 10f
        add(lowerTable)
        pack()
    }

    private fun onChange() {
        offerColumnsTable.update()
        if (controller != null) {
            controller.draftChanged()
            updateActions()
            return
        }
        retractOffer()
        offerButton.isEnabled = !(tradeView.theirStagedOffers().size == 0 && tradeView.ourStagedOffers().size == 0)
    }

    fun enableOfferButton(isEnabled: Boolean) {
        if (controller != null) return
        offerButton.isEnabled = isEnabled
    }

    private fun updateActions() {
        val controls = controller ?: return
        statusLabel.setText(controls.message)
        lowerTable.clear()
        for (action in controls.actions()) {
            lowerTable.add(action.label.toTextButton().apply {
                isEnabled = action.enabled
                onClick { if (action.enabled) action.run() }
            })
        }
    }

    override fun act(delta: Float) {
        if (controller?.refresh() == true) {
            offerColumnsTable.update()
            updateActions()
        }
        super.act(delta)
    }
}
