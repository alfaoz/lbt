package dev.alfaoz.lbt

import com.google.gson.JsonParser
import dev.alfaoz.lbt.market.CoflnetJson
import dev.alfaoz.lbt.market.CommunityRepoClient
import dev.alfaoz.lbt.valuation.Comp
import dev.alfaoz.lbt.valuation.PriceSource
import dev.alfaoz.lbt.valuation.RepoData
import dev.alfaoz.lbt.valuation.ValueRules

/** Loads the recorded live-API responses in src/test/resources/fixtures. */
object Fixtures {
    fun text(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

    fun soldComps(tag: String): List<Comp> =
        CoflnetJson.parseAuctions(text("sold_${tag.lowercase()}.json"), tag)

    fun binComps(tag: String): List<Comp> =
        CoflnetJson.parseAuctions(text("bin_${tag.lowercase()}.json"), tag)

    fun binPrices(tag: String): List<Double> =
        binComps(tag).map { it.price / it.count.coerceAtLeast(1) }.sorted()

    fun repoData(): RepoData {
        val items = JsonParser.parseString(text("skyhanni_items.json")).asJsonObject
        return RepoData(
            valueRules = CommunityRepoClient.parseValueRules(items),
            essenceCosts = CommunityRepoClient.parseEssenceCosts(
                JsonParser.parseString(text("essencecosts.json")).asJsonObject,
            ),
            reforgeStones = CommunityRepoClient.parseReforgeStones(
                JsonParser.parseString(text("reforgestones.json")).asJsonObject,
            ),
        )
    }

    /** PriceSource backed by the recorded bazaar snapshot + recorded BIN lists. */
    class RecordedPrices(vararg binTags: String) : PriceSource {
        private val bazaar: Map<String, Pair<Double, Double>> =
            JsonParser.parseString(text("bazaar_quickstatus.json")).asJsonObject.entrySet()
                .associate { (id, v) ->
                    val o = v.asJsonObject
                    id to (o.get("sellPrice").asDouble to o.get("buyPrice").asDouble)
                }
        private val bins: Map<String, List<Double>> = binTags.associateWith { binPrices(it) }

        override fun bazaarSell(productId: String): Double? = bazaar[productId]?.first?.takeIf { it > 0 }
        override fun bazaarBuy(productId: String): Double? = bazaar[productId]?.second?.takeIf { it > 0 }
        override fun lowestBin(tag: String): Double? = bins[tag]?.firstOrNull()
        override fun lowestBins(tag: String): List<Double> = bins[tag].orEmpty()
    }

    /** Minimal deterministic price source for unit tests that don't want the full snapshot. */
    class MapPrices(
        private val sell: Map<String, Double> = emptyMap(),
        private val binsByTag: Map<String, List<Double>> = emptyMap(),
    ) : PriceSource {
        override fun bazaarSell(productId: String): Double? = sell[productId]
        override fun bazaarBuy(productId: String): Double? = null
        override fun lowestBin(tag: String): Double? = binsByTag[tag]?.firstOrNull()
        override fun lowestBins(tag: String): List<Double> = binsByTag[tag].orEmpty()
    }

    val bareRules = RepoData(
        valueRules = ValueRules(
            onlyTierOnePrices = setOf("smoldering"),
            onlyTierFivePrices = setOf("strong_mana"),
            alwaysActiveEnchants = mapOf(
                "scavenger" to ValueRules.AlwaysActive(5, setOf("CRYPT_DREADLORD_SWORD")),
            ),
        ),
    )
}
