package dev.alfaoz.lbt

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import dev.alfaoz.lbt.client.gui.LbtConfigScreen

/** Loaded by ModMenu when present (compileOnly dep; entrypoint is skipped without it). */
class ModMenuEntry : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> LbtConfigScreen(parent) }
}
