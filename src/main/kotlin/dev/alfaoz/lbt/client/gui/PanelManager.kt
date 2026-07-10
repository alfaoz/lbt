package dev.alfaoz.lbt.client.gui

import dev.alfaoz.lbt.LowballerClient
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * Draws the panels *inside* container screens - the layer the old HUD-element approach never
 * reached (HUD renders under open screens). Fabric's screen API handles both render and input:
 * afterExtract paints on top of the fully-drawn screen each frame, and the allow-phase mouse
 * events let a panel consume clicks/drags before the inventory sees them. No mixins needed.
 */
object PanelManager {

    lateinit var itemPanel: ItemValuePanel
        private set
    lateinit var tradePanel: TradePanel
        private set

    private val panels: List<Panel> get() = listOf(tradePanel, itemPanel)

    fun init() {
        val config = LowballerClient.config
        itemPanel = ItemValuePanel(config.panelX("item_value", 8), config.panelY("item_value", 60))
        tradePanel = TradePanel(config.panelX("trade", 8), config.panelY("trade", 60))
        itemPanel.visible = config.itemPanelVisible
        for (panel in panels) {
            panel.onMoved = {
                config.setPanelPos(it.id, it.x, it.y)
                config.save()
            }
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            hook(screen)
        }
    }

    fun toggleItemPanel() {
        itemPanel.visible = !itemPanel.visible
        LowballerClient.config.itemPanelVisible = itemPanel.visible
        LowballerClient.config.save()
    }

    private fun hook(screen: Screen) {
        ScreenEvents.afterExtract(screen).register { _, graphics, mouseX, mouseY, _ ->
            tradePanel.draw(graphics, mouseX, mouseY)
            if (itemPanel.visible && !tradePanel.active) itemPanel.draw(graphics, mouseX, mouseY)
        }

        ScreenMouseEvents.allowMouseClick(screen).register { _, event ->
            val consumed = activePanels().any { it.mouseDown(event.x(), event.y(), event.button()) }
            !consumed
        }

        ScreenMouseEvents.allowMouseDrag(screen).register { _, event, _, _ ->
            val consumed = activePanels().any { it.mouseDragTo(event.x(), event.y()) }
            !consumed
        }

        ScreenMouseEvents.allowMouseRelease(screen).register { _, _ ->
            activePanels().forEach { it.mouseUp() }
            true
        }

        // Vanilla keybind clicks don't fire while a screen is open; route presses to the mod's
        // key mappings (nudge, refresh, toggle) so they work where the mod actually lives.
        // Never while a text field has focus - anvil renames and search bars must stay typable.
        ScreenKeyboardEvents.allowKeyPress(screen).register { s, event ->
            if (s.focused is net.minecraft.client.gui.components.EditBox) true
            else !LowballerClient.handleScreenKey(event)
        }
    }

    private fun activePanels(): List<Panel> = buildList {
        if (tradePanel.active) add(tradePanel)
        if (itemPanel.visible && !tradePanel.active) add(itemPanel)
    }
}
