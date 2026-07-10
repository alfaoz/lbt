package dev.alfaoz.lbt

import com.mojang.blaze3d.platform.InputConstants
import dev.alfaoz.lbt.client.EstimateService
import dev.alfaoz.lbt.client.HoverState
import dev.alfaoz.lbt.client.gui.PanelManager
import dev.alfaoz.lbt.market.CommunityRepoClient
import dev.alfaoz.lbt.market.MarketData
import dev.alfaoz.lbt.util.SkyblockItem
import dev.alfaoz.lbt.valuation.formatCoins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object LowballerClient : ClientModInitializer {
    val logger: Logger = LoggerFactory.getLogger("lbt")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var config: LowballerConfig
        private set
    lateinit var estimates: EstimateService
        private set

    // Translation IDs unchanged from 0.1 so bindings users already made in Controls survive.
    private lateinit var togglePanelKey: KeyMapping
    private lateinit var discountDownKey: KeyMapping
    private lateinit var discountUpKey: KeyMapping
    private lateinit var refreshKey: KeyMapping

    const val DISCOUNT_NUDGE_STEP = 0.02

    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("lbt", "main"))

    override fun onInitializeClient() {
        config = LowballerConfig.load()

        val repos = CommunityRepoClient(scope, FabricLoader.getInstance().configDir.resolve("lbt-repo"))
        repos.start()
        estimates = EstimateService(MarketData(scope), repos)

        // Apostrophe, not numpad multiply: numpad keys don't exist on laptop keyboards.
        togglePanelKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.skyblock-lowballer.toggle_overlay", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_APOSTROPHE, category),
        )
        discountDownKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.skyblock-lowballer.discount_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, category),
        )
        discountUpKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.skyblock-lowballer.discount_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, category),
        )
        refreshKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.skyblock-lowballer.refresh_prices", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category),
        )

        PanelManager.init()

        ItemTooltipCallback.EVENT.register(ItemTooltipCallback { stack, _, _, lines ->
            HoverState.update(stack)
            val attrs = SkyblockItem.fromStack(stack) ?: return@ItemTooltipCallback
            val estimate = estimates.estimate(attrs)
            val v = estimate.valuation
            lines.add(
                Component.literal(
                    when {
                        v != null -> "§6Lowball: §e~${formatCoins(v.suggestedOffer)} §7(fair ${formatCoins(v.fairValue)}, n=${v.compCount})"
                        estimate.loading -> "§7Lowball: loading market data..."
                        else -> "§8Lowball: no market data"
                    },
                ),
            )
        })

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick {
            while (togglePanelKey.consumeClick()) togglePanel()
            while (discountDownKey.consumeClick()) nudgeDiscount(-DISCOUNT_NUDGE_STEP)
            while (discountUpKey.consumeClick()) nudgeDiscount(DISCOUNT_NUDGE_STEP)
            while (refreshKey.consumeClick()) refreshHoveredPrices()
        })
    }

    /**
     * Vanilla key-click handling is suspended while a screen is open - exactly where this mod
     * lives - so PanelManager routes screen key presses here. Returns true when consumed.
     * KeyMapping.matches respects whatever the user rebound in Controls.
     */
    fun handleScreenKey(event: net.minecraft.client.input.KeyEvent): Boolean {
        when {
            togglePanelKey.matches(event) -> togglePanel()
            discountDownKey.matches(event) -> nudgeDiscount(-DISCOUNT_NUDGE_STEP)
            discountUpKey.matches(event) -> nudgeDiscount(DISCOUNT_NUDGE_STEP)
            refreshKey.matches(event) -> refreshHoveredPrices()
            else -> return false
        }
        return true
    }

    private fun togglePanel() {
        PanelManager.toggleItemPanel()
        notify("Value panel ${if (PanelManager.itemPanel.visible) "shown" else "hidden"}")
    }

    private fun refreshHoveredPrices() {
        val attrs = dev.alfaoz.lbt.client.HoverState.lastAttributes
        if (attrs == null) {
            notify("Hover a Skyblock item to refresh its prices")
            return
        }
        estimates.market.refresh(attrs.itemId)
        estimates.clearMemo()
        notify("Refreshing BINs + sales for ${attrs.displayName.ifBlank { attrs.itemId }}")
    }

    fun nudgeDiscount(delta: Double) {
        config.nudgeManualDiscount(delta)
        config.save()
        notify("Lowball discount adjustment: ${(config.manualDiscountAdjustment * 100).toInt()}%")
    }

    /** The user's *actual* bound keys (they may have rebound in Controls), not hardcoded defaults. */
    fun nudgeKeyNames(): String =
        "${discountDownKey.translatedKeyMessage.string}/${discountUpKey.translatedKeyMessage.string}"

    /** Action-bar message so key presses are visibly confirmed even with no panel open. */
    private fun notify(message: String) {
        Minecraft.getInstance().gui.setOverlayMessage(Component.literal("§b[LBT] §f$message"), false)
    }
}
