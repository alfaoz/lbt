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
    private val windowButtons = WINDOWS.map { (hours, label) -> Button("window_$hours") to label }

    private var lines: List<Pair<String, Int>> = emptyList()
    private var showButtons = false

    override fun buttons(): List<Button> =
        if (showButtons) listOf(minusButton, plusButton) + windowButtons.map { it.first } else emptyList()

    override fun onButton(id: String): Boolean {
        if (id.startsWith("window_")) {
            val config = LowballerClient.config
            config.priceWindowHours = id.removePrefix("window_").toInt()
            config.save()
            return true
        }
        val step = LowballerClient.DISCOUNT_NUDGE_STEP
        LowballerClient.nudgeDiscount(if (id == "minus") -step else step)
        return true
    }

    override fun layout() {
        val item = HoverState.lastAttributes
        val estimate = item?.let { LowballerClient.estimates.estimate(it) }
        showButtons = estimate?.valuation != null

        // Long notes wrap to the panel's max width instead of escaping the frame.
        val maxTextWidth = MAX_WIDTH - 10
        lines = buildLines(item?.displayName, estimate).flatMap { (text, color) ->
            wrap(text, maxTextWidth).map { it to color }
        }

        val textWidth = lines.maxOfOrNull { font.width(it.first) } ?: 0
        width = (textWidth + 10).coerceIn(150, MAX_WIDTH)
        height = 16 + lines.size * 10 + (if (showButtons) 32 else 2)

        if (showButtons) {
            val windowRowY = y + height - 30
            var bx = this@ItemValuePanel.x + 5
            for ((button, label) in windowButtons) {
                button.apply { x = bx; this.y = windowRowY; w = font.width(label) + 10; h = 11 }
                bx += button.w + 3
            }
            val adjRowY = y + height - 14
            minusButton.apply { x = this@ItemValuePanel.x + 5; this.y = adjRowY; w = 16; h = 11 }
            plusButton.apply { x = this@ItemValuePanel.x + 24; this.y = adjRowY; w = 16; h = 11 }
        } else {
            minusButton.w = 0
            plusButton.w = 0
            windowButtons.forEach { it.first.w = 0 }
        }
    }

    /** Greedy word wrap; continuation lines get a small hanging indent. */
    private fun wrap(text: String, maxWidth: Int): List<String> {
        if (font.width(text) <= maxWidth) return listOf(text)
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (font.width(candidate) > maxWidth && line.isNotEmpty()) {
                out.add(line.toString())
                line = StringBuilder("  $word")
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }

    private fun buildLines(name: String?, estimate: Estimate?): List<Pair<String, Int>> {
        if (name == null || estimate == null) {
            return listOf("Hover a Skyblock item" to PanelColors.DIM)
        }
        val v = estimate.valuation
        val out = mutableListOf<Pair<String, Int>>()
        out.add(name.take(32) to PanelColors.TEXT)

        if (v == null) {
            out.add((if (estimate.loading) "Fetching market data ${Spinner.frame()}" else "No market data found") to PanelColors.DIM)
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
        if (v.unpricedPartCount > 0 && LowballerClient.config.showUnpricedPartsNote) {
            out.add("! ${v.unpricedPartCount} part(s) had no market price" to PanelColors.YELLOW)
        }
        for (note in v.notes) out.add("! $note" to PanelColors.YELLOW)
        if (estimate.loading) out.add("refining ${Spinner.frame()}" to PanelColors.DIM)
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
            val activeWindow = LowballerClient.config.priceWindowHours
            for ((button, label) in windowButtons) {
                button.draw(g, font, label, mouseX, mouseY, active = button.id == "window_$activeWindow")
            }
            windowButtons.lastOrNull()?.let { (last, _) ->
                g.text(font, "window", last.x + last.w + 6, last.y + 2, PanelColors.DIM)
            }
            minusButton.draw(g, font, "-", mouseX, mouseY)
            plusButton.draw(g, font, "+", mouseX, mouseY)
            val pct = (LowballerClient.config.manualDiscountAdjustment * 100).toInt()
            val label = "adj ${if (pct >= 0) "+" else ""}$pct%  (${LowballerClient.nudgeKeyNames()})"
            g.text(font, label, plusButton.x + plusButton.w + 6, minusButton.y + 2, PanelColors.DIM)
        }
    }

    companion object {
        private const val MAX_WIDTH = 260
        private val WINDOWS = listOf(12 to "12h", 72 to "3d", 168 to "7d")
    }
}
