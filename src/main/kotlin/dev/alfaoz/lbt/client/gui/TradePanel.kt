package dev.alfaoz.lbt.client.gui

import dev.alfaoz.lbt.LowballerClient
import dev.alfaoz.lbt.util.SkyblockItem
import dev.alfaoz.lbt.valuation.formatCoins
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents

/**
 * Live totals for both sides of a Hypixel trade. The trade window's real title starts with
 * "You     " (several spaces; detection trick credited to NEU via SkyHanni) - the old
 * contains("Trade") check matched nothing, which is why the previous overlay never appeared.
 * Slot grid per side: 4 rows, columns 0-3 yours, 5-8 theirs.
 */
class TradePanel(x: Int, y: Int) : Panel("trade", x, y) {

    private data class Row(val text: String, val color: Int)

    private var rows: List<Row> = emptyList()

    private val minusButton = Button("minus")
    private val plusButton = Button("plus")

    var active = false
        private set

    override fun buttons(): List<Button> = if (active) listOf(minusButton, plusButton) else emptyList()

    override fun onButton(id: String): Boolean {
        val step = LowballerClient.DISCOUNT_NUDGE_STEP
        LowballerClient.nudgeDiscount(if (id == "minus") -step else step)
        return true
    }

    override fun layout() {
        val screen = Minecraft.getInstance().screen as? AbstractContainerScreen<*>
        active = screen != null && screen.title.string.startsWith("You     ")
        if (!active || screen == null) {
            rows = emptyList()
            return
        }

        val yourSlots = (0..3).flatMap { row -> (0..3).map { col -> col + 9 * row } }.toSet()
        val theirSlots = (0..3).flatMap { row -> (5..8).map { col -> col + 9 * row } }.toSet()

        val out = mutableListOf<Row>()
        val yours = sideRows(screen, yourSlots, "You give", out)
        out.add(Row("", PanelColors.TEXT))
        val theirs = sideRows(screen, theirSlots, "You get", out)

        // The number to actually say in chat: the summed lowball offers for their items.
        // This is the line the [-]/[+] adjustment visibly moves.
        if (theirs.pitch > 0) {
            out.add(Row("", PanelColors.TEXT))
            out.add(Row("Pitch: ${formatCoins(theirs.pitch)} for their items", PanelColors.GOLD))
        }

        // The flip, from a lowballer's seat: what you pay now vs what their items net you
        // at the AH after real fees (coins received carry no fee).
        out.add(Row("", PanelColors.TEXT))
        val cost = yours.fair + yours.coins
        val fees = theirs.fair - theirs.netResale
        out.add(Row("Pay ${formatCoins(cost)} -> resell nets ${formatCoins(theirs.netResale + theirs.coins)}", PanelColors.TEXT))
        val profit = theirs.netResale + theirs.coins - cost
        val (verdict, color) = when {
            profit > 0 -> "+${formatCoins(profit)} profit (after ${formatCoins(fees)} AH fees)" to PanelColors.GREEN
            profit < 0 -> "-${formatCoins(-profit)} loss (after ${formatCoins(fees)} AH fees)" to PanelColors.RED
            else -> "break-even" to PanelColors.DIM
        }
        out.add(Row("Flip: $verdict", color))
        rows = out

        val textWidth = rows.maxOfOrNull { font.width(it.text) } ?: 0
        width = (textWidth + 10).coerceIn(160, 280)
        height = 16 + rows.size * 10 + 18

        val by = y + height - 14
        minusButton.apply { x = this@TradePanel.x + 5; this.y = by; w = 16; h = 11 }
        plusButton.apply { x = this@TradePanel.x + 24; this.y = by; w = 16; h = 11 }
    }

    private data class SideTotals(val fair: Double, val coins: Double, val netResale: Double, val pitch: Double)

    /** Appends this side's item rows + total; net resale applies real AH fees per listing. */
    private fun sideRows(
        screen: AbstractContainerScreen<*>,
        indices: Set<Int>,
        label: String,
        out: MutableList<Row>,
    ): SideTotals {
        var fairTotal = 0.0
        var coinsTotal = 0.0
        var netResaleTotal = 0.0
        var pitchTotal = 0.0
        var pending = 0

        out.add(Row(label, PanelColors.TITLE))
        val playerInventory = Minecraft.getInstance().player?.inventory
        for (slot in screen.menu.slots) {
            if (slot.container === playerInventory) continue
            if (slot.containerSlot !in indices) continue
            val stack = slot.item
            if (stack.isEmpty) continue

            val coins = parseTradeCoins(stack)
            if (coins != null) {
                coinsTotal += coins
                out.add(Row(" ${formatCoins(coins)} coins", PanelColors.GOLD))
                continue
            }

            val attrs = SkyblockItem.fromStack(stack) ?: continue
            val estimate = LowballerClient.estimates.estimate(attrs)
            val v = estimate.valuation
            if (v == null) {
                pending++
                continue
            }
            val stackFair = v.fairValue * attrs.count
            fairTotal += stackFair
            pitchTotal += v.suggestedOffer * attrs.count
            netResaleTotal += dev.alfaoz.lbt.valuation.AhFees.netProceeds(stackFair)
            val liqColor = when (v.liquidity.name) {
                "HIGH" -> PanelColors.GREEN
                "MEDIUM" -> PanelColors.YELLOW
                else -> PanelColors.RED
            }
            out.add(Row(" ${attrs.displayName.take(22)}: ${formatCoins(v.fairValue)} (n=${v.compCount})", liqColor))
        }
        if (pending > 0) out.add(Row(" $pending item(s) loading...", PanelColors.DIM))
        val totalLabel = if (coinsTotal > 0 && fairTotal > 0) {
            " Total ${formatCoins(fairTotal + coinsTotal)} (items + coins)"
        } else {
            " Total ${formatCoins(fairTotal + coinsTotal)}"
        }
        out.add(Row(totalLabel, PanelColors.GOLD))
        return SideTotals(fairTotal, coinsTotal, netResaleTotal, pitchTotal)
    }

    private fun parseTradeCoins(stack: net.minecraft.world.item.ItemStack): Double? {
        val lore = stack.get(DataComponents.LORE)?.lines?.map { it.string } ?: return null
        return dev.alfaoz.lbt.util.TradeCoins.parse(stack.hoverName.string, lore)
    }

    override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        layout()
        if (!active) return
        drawFrame(g, "Trade Value")
        var ty = y + 15
        for (row in rows) {
            if (row.text.isNotEmpty()) g.text(font, row.text, x + 5, ty, row.color)
            ty += 10
        }
        minusButton.draw(g, font, "-", mouseX, mouseY)
        plusButton.draw(g, font, "+", mouseX, mouseY)
        val pct = (LowballerClient.config.manualDiscountAdjustment * 100).toInt()
        val label = "adj ${if (pct >= 0) "+" else ""}$pct%  (${LowballerClient.nudgeKeyNames()})"
        g.text(font, label, plusButton.x + plusButton.w + 6, minusButton.y + 2, PanelColors.DIM)
    }
}
