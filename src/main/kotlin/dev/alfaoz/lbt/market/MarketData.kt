package dev.alfaoz.lbt.market

import com.google.gson.JsonParser
import dev.alfaoz.lbt.valuation.Comp
import dev.alfaoz.lbt.valuation.PriceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * All market state behind one facade. Sync reads (implementing valuation's PriceSource) return
 * whatever is cached and kick background refreshes; the UI re-estimates each frame from cache,
 * so data streams in: parts price instantly once bazaar lands, comps/BINs follow.
 */
class MarketData(private val scope: CoroutineScope) : PriceSource {

    // --- bazaar: one bulk endpoint covers all ~1900 products ---------------------------------

    private data class BazaarQuote(val sellPrice: Double, val buyPrice: Double)

    @Volatile private var bazaar: Map<String, BazaarQuote> = emptyMap()
    @Volatile private var bazaarFetchedAt = 0L
    private val bazaarTtl = Duration.ofMinutes(5).toMillis()
    private val bazaarMutex = Mutex()

    override fun bazaarSell(productId: String): Double? {
        refreshBazaarIfStale()
        return bazaar[productId]?.sellPrice?.takeIf { it > 0 }
    }

    override fun bazaarBuy(productId: String): Double? {
        refreshBazaarIfStale()
        return bazaar[productId]?.buyPrice?.takeIf { it > 0 }
    }

    val bazaarReady: Boolean get() = bazaar.isNotEmpty()

    private fun refreshBazaarIfStale() {
        if (System.currentTimeMillis() - bazaarFetchedAt < bazaarTtl) return
        scope.launch {
            bazaarMutex.withLock {
                if (System.currentTimeMillis() - bazaarFetchedAt < bazaarTtl) return@withLock
                val body = Http.getString("https://api.hypixel.net/v2/skyblock/bazaar", timeoutSeconds = 25) ?: return@withLock
                try {
                    val products = JsonParser.parseString(body).asJsonObject.getAsJsonObject("products")
                    val map = HashMap<String, BazaarQuote>(products.size())
                    for ((id, product) in products.entrySet()) {
                        val q = product.asJsonObject.getAsJsonObject("quick_status") ?: continue
                        map[id] = BazaarQuote(
                            sellPrice = q.get("sellPrice")?.asDouble ?: 0.0,
                            buyPrice = q.get("buyPrice")?.asDouble ?: 0.0,
                        )
                    }
                    bazaar = map
                    bazaarFetchedAt = System.currentTimeMillis()
                    marketLogger.info("Bazaar refreshed: ${map.size} products")
                } catch (e: Exception) {
                    marketLogger.warn("Bazaar parse failed: ${e.message}")
                }
            }
        }
    }

    // --- Coflnet: sold comps + active BINs per item tag ---------------------------------------

    private class TimedEntry<T>(val value: T, val fetchedAt: Long)

    private val soldCache = ConcurrentHashMap<String, TimedEntry<List<Comp>>>()
    private val binCache = ConcurrentHashMap<String, TimedEntry<List<Comp>>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val soldTtl = Duration.ofMinutes(10).toMillis()

    // Asks churn fast and the anchor/ceiling live off them - keep this tight. One tag refresh
    // is a single cheap request against a 20-per-10s budget.
    private val binTtl = Duration.ofSeconds(60).toMillis()

    /** Manual refresh: drop everything cached for the tag so the next estimate refetches. */
    fun refresh(tag: String) {
        soldCache.remove(tag)
        binCache.keys.removeIf { it == tag || it.startsWith("$tag|") }
    }

    /** Sold comps from cache (kicking a refresh if needed); null means still loading. */
    fun soldComps(tag: String, lookbackDays: Int): List<Comp>? {
        val entry = soldCache[tag]
        if (entry == null || System.currentTimeMillis() - entry.fetchedAt > soldTtl) fetchSold(tag, lookbackDays)
        return entry?.value
    }

    /**
     * Full listing detail (kicking a refresh if stale) so the valuator can normalize asks by
     * their modifiers; null means still loading. Coflnet's active/bin endpoint pages 10-cheapest
     * at a time, so an expensive build's own market (e.g. mythic-recombed over a wall of clean
     * legendaries) never shows on page one - when [rarity] is known, a Rarity-filtered page is
     * fetched too and merged, so the exact-tier wall is always visible to the valuator.
     */
    fun binListings(tag: String, rarity: String? = null): List<Comp>? {
        val key = if (rarity != null) "$tag|$rarity" else tag
        val entry = binCache[key]
        if (entry == null || System.currentTimeMillis() - entry.fetchedAt > binTtl) fetchBins(tag, rarity, key)
        return entry?.value
    }

    override fun lowestBin(tag: String): Double? = lowestBins(tag).firstOrNull()

    override fun lowestBins(tag: String): List<Double> =
        binListings(tag).orEmpty().map { it.price / it.count.coerceAtLeast(1) }.filter { it > 0 }.sorted()

    /** True once both sold comps and BINs have landed for the tag (estimate is final). */
    fun fullyLoaded(tag: String, rarity: String? = null): Boolean {
        val key = if (rarity != null) "$tag|$rarity" else tag
        return soldCache.containsKey(tag) && binCache.containsKey(key)
    }

    private fun fetchSold(tag: String, lookbackDays: Int) {
        if (!inFlight.add("sold:$tag")) return
        scope.launch {
            try {
                // 500 deep: exact-market matching needs enough same-tier+recomb records even
                // when that combo is a minority of sales (e.g. ~60 mythic in 500 SA helmets).
                val body = Http.getString("https://sky.coflnet.com/api/auctions/tag/$tag/sold?pageSize=500")
                if (body != null) {
                    val cutoff = System.currentTimeMillis() - Duration.ofDays(lookbackDays.toLong()).toMillis()
                    val comps = CoflnetJson.parseAuctions(body, tag)
                        .filter { it.endedAtMillis == null || it.endedAtMillis >= cutoff }
                    soldCache[tag] = TimedEntry(comps, System.currentTimeMillis())
                } else if (!soldCache.containsKey(tag)) {
                    // Cache the failure briefly so a dead endpoint doesn't get hammered every frame.
                    soldCache[tag] = TimedEntry(emptyList(), System.currentTimeMillis() - soldTtl + Duration.ofSeconds(30).toMillis())
                }
            } finally {
                inFlight.remove("sold:$tag")
            }
        }
    }

    private fun fetchBins(tag: String, rarity: String?, key: String) {
        if (!inFlight.add("bin:$key")) return
        scope.launch {
            try {
                val cheapest = Http.getString("https://sky.coflnet.com/api/auctions/tag/$tag/active/bin")
                val sameTier = rarity?.let {
                    Http.getString("https://sky.coflnet.com/api/auctions/tag/$tag/active/bin?Rarity=$it")
                }
                if (cheapest != null || sameTier != null) {
                    val merged = (parseBins(cheapest, tag) + parseBins(sameTier, tag)).distinct()
                    binCache[key] = TimedEntry(merged, System.currentTimeMillis())
                } else if (!binCache.containsKey(key)) {
                    binCache[key] = TimedEntry(emptyList(), System.currentTimeMillis() - binTtl + Duration.ofSeconds(30).toMillis())
                }
            } finally {
                inFlight.remove("bin:$key")
            }
        }
    }

    private fun parseBins(body: String?, tag: String): List<Comp> =
        body?.let { CoflnetJson.parseAuctions(it, tag) } ?: emptyList()
}
