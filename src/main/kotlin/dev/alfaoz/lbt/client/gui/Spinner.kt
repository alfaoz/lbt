package dev.alfaoz.lbt.client.gui

/** Wall-clock-driven text spinner: both the tooltip and panels redraw every frame, so
 * indexing frames by time gives a smooth \ | / - without any per-caller state. */
object Spinner {
    private val FRAMES = charArrayOf('|', '/', '-', '\\')

    fun frame(): Char = FRAMES[((System.currentTimeMillis() / 120) % FRAMES.size).toInt()]
}
