package dev.alfaoz.lbt

import com.google.gson.JsonParser
import dev.alfaoz.lbt.market.CoflnetJson
import dev.alfaoz.lbt.market.CommunityRepoClient
import dev.alfaoz.lbt.valuation.ItemAttributes
import dev.alfaoz.lbt.valuation.PriceSource
import dev.alfaoz.lbt.valuation.RepoData
import dev.alfaoz.lbt.valuation.ValuationSettings
import dev.alfaoz.lbt.valuation.Valuator
import dev.alfaoz.lbt.valuation.Comp
import dev.alfaoz.lbt.valuation.PartCatalog
import dev.alfaoz.lbt.valuation.priceParts
import java.net.URI

import kotlin.test.Ignore
import kotlin.test.Test

/** Scratch harness: reproduce an in-game estimate against live APIs. Remove @Ignore and run
 * `./gradlew test --tests "*LiveDebug*"` when debugging; stays out of the offline suite. */
@Ignore
class LiveDebug {

    private fun fetch(url: String): String =
        URI.create(url).toURL().openConnection().apply { setRequestProperty("User-Agent", "sl-debug") }
            .getInputStream().bufferedReader().readText()

    @Test
    fun shadowAssassinHelmet() {
        val tag = "STARRED_SHADOW_ASSASSIN_HELMET"
        val comps = CoflnetJson.parseAuctions(fetch("https://sky.coflnet.com/api/auctions/tag/$tag/sold?pageSize=500"), tag)
        // Mirror MarketData: cheapest page + same-displayed-tier page merged.
        val bins = (
            CoflnetJson.parseAuctions(fetch("https://sky.coflnet.com/api/auctions/tag/$tag/active/bin"), tag) +
                CoflnetJson.parseAuctions(fetch("https://sky.coflnet.com/api/auctions/tag/$tag/active/bin?Rarity=MYTHIC"), tag)
            ).distinct()

        val bazaar = JsonParser.parseString(fetch("https://api.hypixel.net/v2/skyblock/bazaar"))
            .asJsonObject.getAsJsonObject("products")
        val prices = object : PriceSource {
            override fun bazaarSell(productId: String) =
                bazaar.getAsJsonObject(productId)?.getAsJsonObject("quick_status")?.get("sellPrice")?.asDouble?.takeIf { it > 0 }
            override fun bazaarBuy(productId: String) =
                bazaar.getAsJsonObject(productId)?.getAsJsonObject("quick_status")?.get("buyPrice")?.asDouble?.takeIf { it > 0 }
            override fun lowestBin(tag: String): Double? = null
        }
        val repo = RepoData(
            valueRules = CommunityRepoClient.parseValueRules(
                JsonParser.parseString(fetch("https://raw.githubusercontent.com/hannibal002/SkyHanni-REPO/main/constants/Items.json")).asJsonObject,
            ),
            essenceCosts = CommunityRepoClient.parseEssenceCosts(
                JsonParser.parseString(fetch("https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/essencecosts.json")).asJsonObject,
            ),
            reforgeStones = CommunityRepoClient.parseReforgeStones(
                JsonParser.parseString(fetch("https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/reforgestones.json")).asJsonObject,
            ),
        )

        // The user's helmet: displays MYTHIC, recombed, 5 stars, Ancient, 10 HPB, Rejuvenate V.
        val target = ItemAttributes(
            itemId = tag, tier = "MYTHIC", recombobulated = true, upgradeLevel = 5, dungeonItem = true,
            hotPotatoCount = 10, reforge = "ancient",
            enchants = mapOf(
                "aqua_affinity" to 1, "growth" to 5, "protection" to 5,
                "rejuvenate" to 5, "respiration" to 3, "thorns" to 3,
            ),
        )
        val settings = ValuationSettings(manualDiscountAdjustment = 0.06)
        val v = Valuator.estimate(target, comps, prices, repo, settings, bins)!!

        println("=== $tag ===")
        println("fair=${"%,.0f".format(v.fairValue)} offer=${"%,.0f".format(v.suggestedOffer)} " +
            "liq=${v.liquidity} n=${v.compCount} ceilingBound=${v.ceilingBound}")
        println("base=${v.baseValue?.let { "%,.0f".format(it) }} partsTotal=${"%,.0f".format(v.partsTotal)}")
        println("lowestBin=${v.lowestBin?.let { "%,.0f".format(it) }} binAnchor=${v.binAnchor?.let { "%,.0f".format(it) }}")
        v.notes.forEach { println("note: $it") }
        v.parts.forEach { p ->
            println("  ${p.part.label}: unit=${p.unitPrice?.let { "%,.0f".format(it) } ?: "-"} " +
                "x${p.part.count} haircut=${p.haircut} => ${"%,.0f".format(p.value)} [${p.source}]")
        }

        // Empirics: what tier/recomb combos exist in sold data, and the base distribution.
        println("--- sold tier x recomb (n=${comps.size}) ---")
        comps.groupingBy { "${it.attributes.tier}/recomb=${it.attributes.recombobulated}" }.eachCount()
            .toSortedMap().forEach { (k, c) -> println("  $k: $c") }
        val catalog = PartCatalog(repo)
        fun base(c: Comp): Double? {
            val unit = c.price / c.count.coerceAtLeast(1)
            if (unit <= 0) return null
            val parts = priceParts(catalog.partsFor(c.attributes), prices) { settings.haircutFor(it) }
            return (unit - parts.sumOf { it.value }).coerceAtLeast(unit * 0.10)
        }
        fun dumpDist(label: String, pool: List<Comp>) {
            val bases = pool.mapNotNull(::base).sorted()
            if (bases.isEmpty()) { println("$label: empty"); return }
            fun pct(p: Double): String {
                val i = (p / 100.0 * (bases.size - 1)).toInt()
                return "%,.0f".format(bases[i])
            }
            println("$label n=${bases.size}: p5=${pct(5.0)} p20=${pct(20.0)} p50=${pct(50.0)} p80=${pct(80.0)} p95=${pct(95.0)}")
        }
        val legendaryish = comps.filter { c ->
            val t = c.attributes.tier?.uppercase()
            (t == "LEGENDARY" && !c.attributes.recombobulated) || (t == "MYTHIC" && c.attributes.recombobulated)
        }
        dumpDist("sold bases (matched pool)", legendaryish)
        dumpDist("sold bases (all)", comps)
        println("--- matched sold, price vs build (recent 30) ---")
        legendaryish.take(30).forEach { c ->
            val a = c.attributes
            println("  price=${"%,.0f".format(c.price)} base=${base(c)?.let { "%,.0f".format(it) }} " +
                "tier=${a.tier} recomb=${a.recombobulated} stars=${a.upgradeLevel} hpb=${a.hotPotatoCount} " +
                "reforge=${a.reforge} ench=${a.enchants.size}")
        }
        println("--- active bins (all, cheapest first) ---")
        bins.sortedBy { it.price }.take(25).forEach { c ->
            val a = c.attributes
            println("  ask=${"%,.0f".format(c.price)} base=${base(c)?.let { "%,.0f".format(it) }} " +
                "tier=${a.tier} recomb=${a.recombobulated} stars=${a.upgradeLevel} hpb=${a.hotPotatoCount} " +
                "reforge=${a.reforge} ench=${a.enchants.size}")
        }
    }

    @Test
    fun shadowAssassinBoots() {
        val tag = "SHADOW_ASSASSIN_BOOTS"
        val comps = CoflnetJson.parseAuctions(fetch("https://sky.coflnet.com/api/auctions/tag/$tag/sold?pageSize=200"), tag)
        val bins = CoflnetJson.parseAuctions(fetch("https://sky.coflnet.com/api/auctions/tag/$tag/active/bin"), tag)

        val bazaar = JsonParser.parseString(fetch("https://api.hypixel.net/v2/skyblock/bazaar"))
            .asJsonObject.getAsJsonObject("products")
        val prices = object : PriceSource {
            override fun bazaarSell(productId: String) =
                bazaar.getAsJsonObject(productId)?.getAsJsonObject("quick_status")?.get("sellPrice")?.asDouble?.takeIf { it > 0 }
            override fun bazaarBuy(productId: String) =
                bazaar.getAsJsonObject(productId)?.getAsJsonObject("quick_status")?.get("buyPrice")?.asDouble?.takeIf { it > 0 }
            override fun lowestBin(tag: String): Double? = null
        }
        val repo = RepoData(
            valueRules = CommunityRepoClient.parseValueRules(
                JsonParser.parseString(fetch("https://raw.githubusercontent.com/hannibal002/SkyHanni-REPO/main/constants/Items.json")).asJsonObject,
            ),
            essenceCosts = CommunityRepoClient.parseEssenceCosts(
                JsonParser.parseString(fetch("https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/essencecosts.json")).asJsonObject,
            ),
            reforgeStones = CommunityRepoClient.parseReforgeStones(
                JsonParser.parseString(fetch("https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/reforgestones.json")).asJsonObject,
            ),
        )

        // The user's boot: EPIC base recombed -> LEGENDARY, 5 stars, Mythic (basic) reforge.
        val target = ItemAttributes(
            itemId = tag, tier = "LEGENDARY", recombobulated = true, upgradeLevel = 5, dungeonItem = true,
            reforge = "mythic",
            enchants = mapOf(
                "feather_falling" to 5, "growth" to 5, "hardened_mana" to 5,
                "protection" to 5, "rejuvenate" to 5, "thorns" to 3,
            ),
        )
        val settings = ValuationSettings(manualDiscountAdjustment = 0.03)
        val v = Valuator.estimate(target, comps, prices, repo, settings, bins)!!

        println("=== ${target.itemId} ===")
        println("fair=${"%,.0f".format(v.fairValue)} offer=${"%,.0f".format(v.suggestedOffer)} " +
            "liq=${v.liquidity} n=${v.compCount} ceilingBound=${v.ceilingBound}")
        println("base=${v.baseValue?.let { "%,.0f".format(it) }} partsTotal=${"%,.0f".format(v.partsTotal)}")
        println("lowestBin=${v.lowestBin?.let { "%,.0f".format(it) }} binAnchor=${v.binAnchor?.let { "%,.0f".format(it) }}")
        v.notes.forEach { println("note: $it") }
        v.parts.forEach { p ->
            println("  ${p.part.label}: unit=${p.unitPrice?.let { "%,.0f".format(it) } ?: "-"} " +
                "x${p.part.count} haircut=${p.haircut} => ${"%,.0f".format(p.value)} [${p.source}]")
        }
    }
}
