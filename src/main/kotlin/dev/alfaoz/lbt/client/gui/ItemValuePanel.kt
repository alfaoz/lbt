package dev.alfaoz.lbt.client.gui

import dev.alfaoz.lbt.LowballerClient
import dev.alfaoz.lbt.client.Estimate
import dev.alfaoz.lbt.client.HoverState
import dev.alfaoz.lbt.valuation.PartValue
import dev.alfaoz.lbt.valuation.formatCoins
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The per-item breakdown: base-from-comps, every priced part, floor/ceiling provenance, and
 * the suggested lowball with live nudge buttons. Follows the hovered item; keeps showing the
 * last one so the numbers don't vanish the moment the mouse moves off the slot.
 */
class ItemValuePanel(x: Int, y: Int) : Panel("item_value", x, y) {

    private val minusButton = Button("minus")
    private val plusButton = Button("plus")

    private var lines: List<Pair<String, Int>> = emptyList()
    private var showButtons = false

    override fun buttons(): List<Button> = if (showButtons) listOf(minusButton, plusButton) else emptyList()

    override fun onButton(id: String): Boolean {
        val step = LowballerClient.DISCOUNT_NUDGE_STEP
        LowballerClient.nudgeDiscount(if (id == "minus") -step else step)
        return true
    }

    override fun layout() {
        val item = HoverState.lastAttributes
        val estimate = item?.let { LowballerClient.estimates.estimate(it) }
        lines = buildLines(item?.displayName, estimate)
        showButtons = estimate?.valuation != null

        val textWidth = lines.maxOfOrNull { font.width(it.first) } ?: 0
        width = (textWidth + 10).coerceIn(150, 260)
        height = 16 + lines.size * 10 + (if (showButtons) 16 else 2)

        if (showButtons) {
            val by = y + height - 14
            minusButton.apply { x = this@ItemValuePanel.x + 5; this.y = by; w = 16; h = 11 }
            plusButton.apply { x = this@ItemValuePanel.x + 24; this.y = by; w = 16; h = 11 }
        } else {
            minusButton.w = 0
            plusButton.w = 0
        }
    }

    private fun buildLines(name: String?, estimate: Estimate?): List<Pair<String, Int>> {
        if (name == null || estimate == null) {
            return listOf("Hover a Skyblock item" to PanelColors.DIM)
        }
        val v = estimate.valuation
        val out = mutableListOf<Pair<String, Int>>()
        out.add(name.take(32) to PanelColors.TEXT)

        if (v == null) {
            out.add((if (estimate.loading) "Loading market data..." else "No market data found") to PanelColors.DIM)
            return out
        }

        out.add("Lowball: ${formatCoins(v.suggestedOffer)}" to PanelColors.GOLD)
        val liqColor = when (v.liquidity.name) {
            "HIGH" -> PanelColors.GREEN
            "MEDIUM" -> PanelColors.YELLOW
            else -> PanelColors.RED
        }
        out.add("Fair: ${formatCoins(v.fairValue)}  (${v.liquidity.name.lowercase()}, n=${v.compCount})" to liqColor)

        // The flip, spelled out: buy at the offer, resell at fair, minus real AH fees.
        out.add("Resell nets ${formatCoins(v.netResale)} (fees ${formatCoins(v.resaleFees)})" to PanelColors.DIM)
        val profitColor = if (v.flipProfit >= 0) PanelColors.GREEN else PanelColors.RED
        val sign = if (v.flipProfit >= 0) "+" else "-"
        out.add("Flip profit: $sign${formatCoins(kotlin.math.abs(v.flipProfit))}" to profitColor)

        v.baseValue?.let { out.add("Base item: ${formatCoins(it)} from ${v.compCount} sales" to PanelColors.DIM) }
        // "Matching" = same displayed tier + recomb state when such listings exist; the raw
        // tag-wide lowest can be a different market (clean LEGENDARY under a recombed MYTHIC).
        v.lowestBin?.let { out.add("Lowest matching BIN: ${formatCoins(it)}" to PanelColors.DIM) }
        v.binAnchor?.let { anchor ->
            // Only worth a line when the modifier-adjusted ask differs from the sticker price.
            val lowest = v.lowestBin
            if (lowest == null || anchor < lowest * 0.9 || anchor > lowest * 1.1 || v.ceilingBound) {
                val suffix = if (v.ceilingBound) " (caps offer)" else ""
                out.add("BIN for this build: ${formatCoins(anchor)}$suffix" to PanelColors.DIM)
            }
        }

        val priced = v.parts.filter { it.value > 0 }
        if (priced.isNotEmpty()) {
            out.add("Parts: ${formatCoins(v.partsTotal)}" to PanelColors.AQUA)
            for (part in priced.take(7)) out.add(partLine(part) to PanelColors.TEXT)
            if (priced.size > 7) out.add(" +${priced.size - 7} smaller parts" to PanelColors.DIM)
        }
        for (note in v.notes) out.add("! $note" to PanelColors.YELLOW)
        if (estimate.loading) out.add("(still loading - may refine)" to PanelColors.DIM)
        return out
    }

    private fun partLine(part: PartValue): String =
        " ${part.part.label.take(24)}: ${formatCoins(part.value)}"

    override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        layout()
        drawFrame(g, "LBT")
        var ty = y + 15
        for ((text, color) in lines) {
            g.text(font, text, x + 5, ty, color)
            ty += 10
        }
        if (showButtons) {
            minusButton.draw(g, font, "-", mouseX, mouseY)
            plusButton.draw(g, font, "+", mouseX, mouseY)
            val pct = (LowballerClient.config.manualDiscountAdjustment * 100).toInt()
            val label = "adj ${if (pct >= 0) "+" else ""}$pct%  (${LowballerClient.nudgeKeyNames()})"
            g.text(font, label, plusButton.x + plusButton.w + 6, minusButton.y + 2, PanelColors.DIM)
        }
    }
}
