package dev.alfaoz.lbt.client.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Every raw hex color must go through this: GuiGraphicsExtractor.text() silently no-ops on alpha 0. */
fun opaque(rgb: Int): Int = rgb or 0xFF000000.toInt()

object PanelColors {
    val BACKGROUND = 0xE8101014.toInt() // near-black, mostly opaque
    val BORDER = opaque(0x3A3A44)
    val TITLE = opaque(0xFFD75F)
    val TEXT = opaque(0xE8E8E8)
    val DIM = opaque(0x9A9A9A)
    val GOLD = opaque(0xFFAA00)
    val GREEN = opaque(0x55FF55)
    val YELLOW = opaque(0xFFFF55)
    val RED = opaque(0xFF5555)
    val AQUA = opaque(0x55FFFF)
    val BUTTON = opaque(0x2A2A32)
    val BUTTON_HOVER = opaque(0x40404C)
}

/** A clickable region inside a panel, laid out per frame while drawing. */
class Button(val id: String, var x: Int = 0, var y: Int = 0, var w: Int = 0, var h: Int = 0) {
    fun contains(px: Double, py: Double): Boolean =
        w > 0 && px >= x && px < x + w && py >= y && py < y + h

    fun draw(g: GuiGraphicsExtractor, font: Font, label: String, mouseX: Int, mouseY: Int, active: Boolean = false) {
        val hovered = contains(mouseX.toDouble(), mouseY.toDouble())
        val fill = if (hovered || active) PanelColors.BUTTON_HOVER else PanelColors.BUTTON
        g.fill(x, y, x + w, y + h, fill)
        val tx = x + (w - font.width(label)) / 2
        val ty = y + (h - font.lineHeight) / 2 + 1
        g.text(font, label, tx, ty, if (active) PanelColors.GOLD else PanelColors.TEXT)
    }
}

/**
 * A draggable panel drawn inside a container screen. Subclasses fill [lines] and buttons in
 * [layout]; dragging anywhere on the panel that isn't a button moves it (5px threshold so
 * button clicks don't turn into drags), and the position persists via [onMoved].
 */
abstract class Panel(val id: String, var x: Int, var y: Int) {
    var width = 120
    var height = 30
    var visible = true

    /** Set while the mouse is held on the panel background; actual move starts past the threshold. */
    private var grabbed = false
    private var dragging = false
    private var grabDx = 0
    private var grabDy = 0
    private var grabStartX = 0.0
    private var grabStartY = 0.0

    var onMoved: (Panel) -> Unit = {}

    protected val font: Font get() = Minecraft.getInstance().font

    /** Recompute contents + size + button rects for this frame. */
    abstract fun layout()

    abstract fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int)

    open fun buttons(): List<Button> = emptyList()

    /** Button click hook; return true if handled. */
    open fun onButton(id: String): Boolean = false

    fun contains(px: Double, py: Double): Boolean =
        visible && px >= x && px < x + width && py >= y && py < y + height

    protected fun drawFrame(g: GuiGraphicsExtractor, title: String) {
        g.fill(x, y, x + width, y + height, PanelColors.BACKGROUND)
        g.fill(x, y, x + width, y + 1, PanelColors.BORDER)
        g.fill(x, y + height - 1, x + width, y + height, PanelColors.BORDER)
        g.fill(x, y, x + 1, y + height, PanelColors.BORDER)
        g.fill(x + width - 1, y, x + width, y + height, PanelColors.BORDER)
        g.text(font, title, x + 5, y + 4, PanelColors.TITLE)
    }

    /** Returns true when the event is consumed (click landed on this panel). */
    fun mouseDown(px: Double, py: Double, button: Int): Boolean {
        if (!contains(px, py) || button != 0) return false
        for (b in buttons()) {
            if (b.contains(px, py)) {
                onButton(b.id)
                return true
            }
        }
        grabbed = true
        dragging = false
        grabStartX = px
        grabStartY = py
        grabDx = (px - x).toInt()
        grabDy = (py - y).toInt()
        return true
    }

    fun mouseDragTo(px: Double, py: Double): Boolean {
        if (!grabbed) return false
        if (!dragging) {
            val moved = Math.abs(px - grabStartX) + Math.abs(py - grabStartY)
            if (moved < 5) return true
            dragging = true
        }
        x = (px - grabDx).toInt().coerceAtLeast(0)
        y = (py - grabDy).toInt().coerceAtLeast(0)
        return true
    }

    fun mouseUp(): Boolean {
        if (!grabbed) return false
        if (dragging) onMoved(this)
        grabbed = false
        dragging = false
        return true
    }
}
