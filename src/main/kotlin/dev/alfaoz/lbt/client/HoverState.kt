package dev.alfaoz.lbt.client

import dev.alfaoz.lbt.LowballerClient
import dev.alfaoz.lbt.util.SkyblockItem
import dev.alfaoz.lbt.valuation.ItemAttributes
import net.minecraft.world.item.ItemStack

/**
 * The tooltip callback fires for whatever the mouse is over each frame; this caches the last
 * seen Skyblock item so panels and keybinds have a "currently hovered item" to work with.
 */
object HoverState {
    var lastAttributes: ItemAttributes? = null
        private set

    private var lastLoggedItemName: String? = null

    fun update(stack: ItemStack) {
        val attrs = SkyblockItem.fromStack(stack)
        if (attrs != null) lastAttributes = attrs

        // Diagnostic: log once per distinct item, pinpointing which NBT-parsing step failed.
        val itemName = stack.item.toString()
        if (itemName != lastLoggedItemName) {
            lastLoggedItemName = itemName
            LowballerClient.logger.info("Hover: item={} -> {}", itemName, SkyblockItem.diagnose(stack))
        }
    }
}
