package dev.alfaoz.lbt

import com.google.gson.GsonBuilder
import dev.alfaoz.lbt.valuation.ValuationSettings
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.file.Files

/**
 * Persisted tuning. Valuation knobs mirror ValuationSettings (projected via
 * valuationSettings() so the pure core never sees this class); panel positions and the live
 * "[" / "]" nudge survive restarts too.
 */
data class LowballerConfig(
    var highLiquidityMinSamples: Int = 10,
    var mediumLiquidityMinSamples: Int = 3,
    var highLiquidityDiscount: Double = 0.08,
    var mediumLiquidityDiscount: Double = 0.18,
    var lowLiquidityDiscount: Double = 0.32,
    var soldLookbackDays: Int = 7,
    /** Percentile of the adjusted-comp base distribution used as base value - low, not median:
     * the goal is "cheapest a reasonable buyer actually paid," not center-of-mass. */
    var fairValuePercentile: Double = 20.0,
    var manualDiscountAdjustment: Double = 0.0,
    var impatiencePremium: Double = 0.05,
    /** PartKind name -> haircut override (fraction of part price a buyer credits once applied). */
    var haircutOverrides: MutableMap<String, Double> = mutableMapOf(),
    var itemPanelVisible: Boolean = true,
    var panelPositions: MutableMap<String, IntArray> = mutableMapOf(),
) {
    fun valuationSettings() = ValuationSettings(
        fairValuePercentile = fairValuePercentile,
        highLiquidityMinSamples = highLiquidityMinSamples,
        mediumLiquidityMinSamples = mediumLiquidityMinSamples,
        highLiquidityDiscount = highLiquidityDiscount,
        mediumLiquidityDiscount = mediumLiquidityDiscount,
        lowLiquidityDiscount = lowLiquidityDiscount,
        manualDiscountAdjustment = manualDiscountAdjustment,
        impatiencePremium = impatiencePremium,
        haircutOverrides = haircutOverrides,
    )

    fun nudgeManualDiscount(delta: Double) {
        manualDiscountAdjustment = (manualDiscountAdjustment + delta).coerceIn(-0.3, 0.3)
    }

    fun panelX(id: String, default: Int): Int = panelPositions[id]?.getOrNull(0) ?: default
    fun panelY(id: String, default: Int): Int = panelPositions[id]?.getOrNull(1) ?: default
    fun setPanelPos(id: String, x: Int, y: Int) {
        panelPositions[id] = intArrayOf(x, y)
    }

    fun save() {
        try {
            Files.newBufferedWriter(configPath()).use { gson.toJson(this, it) }
        } catch (e: IOException) {
            LowballerClient.logger.error("Failed to save lowballer config", e)
        }
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        private fun configPath() = FabricLoader.getInstance().configDir.resolve("lbt.json")

        /** Pre-rename config file; read once so panel positions/knobs survive the move to lbt. */
        private fun legacyConfigPath() = FabricLoader.getInstance().configDir.resolve("skyblock-lowballer.json")

        fun load(): LowballerConfig {
            val path = configPath()
            if (!Files.exists(path) && Files.exists(legacyConfigPath())) {
                try {
                    Files.copy(legacyConfigPath(), path)
                } catch (ignored: IOException) {
                }
            }
            if (!Files.exists(path)) {
                val default = LowballerConfig()
                default.save()
                return default
            }
            return try {
                Files.newBufferedReader(path).use { gson.fromJson(it, LowballerConfig::class.java) }
                    ?: LowballerConfig()
            } catch (e: Exception) {
                LowballerClient.logger.error("Failed to load lowballer config, using defaults", e)
                LowballerConfig()
            }
        }
    }
}
