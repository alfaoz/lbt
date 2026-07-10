package dev.alfaoz.lbt.client.gui

import dev.alfaoz.lbt.LowballerClient
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/** ModMenu-reachable settings. Only knobs that make sense to flip mid-session live here;
 * the discount adjustment has its own in-panel buttons and keybinds. */
class LbtConfigScreen(private val parent: Screen?) : Screen(Component.literal("Lowball Tool")) {

    override fun init() {
        val config = LowballerClient.config
        val x = width / 2 - 100
        var y = height / 6 + 20

        addRenderableWidget(
            CycleButton.onOffBuilder(config.itemPanelVisible)
                .create(x, y, 200, 20, Component.literal("Item value panel")) { _, value ->
                    config.itemPanelVisible = value
                    PanelManager.itemPanel.visible = value
                    config.save()
                },
        )
        y += 24

        addRenderableWidget(
            CycleButton.onOffBuilder(config.showUnpricedPartsNote)
                .create(x, y, 200, 20, Component.literal("Unpriced-parts note")) { _, value ->
                    config.showUnpricedPartsNote = value
                    config.save()
                },
        )
        y += 24

        addRenderableWidget(
            Button.builder(Component.literal("Reset discount adjustment")) {
                config.manualDiscountAdjustment = 0.0
                config.save()
            }.bounds(x, y, 200, 20).build(),
        )
        y += 24

        addRenderableWidget(
            Button.builder(Component.literal("Reset panel positions")) {
                config.panelPositions.clear()
                config.save()
            }.bounds(x, y, 200, 20).build(),
        )

        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(x, height - 40, 200, 20).build(),
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        graphics.text(font, title, (width - font.width(title)) / 2, height / 6 - 8, PanelColors.TEXT)
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }
}
