package dev.alfaoz.lbt.client

import dev.alfaoz.lbt.LowballerClient
import dev.alfaoz.lbt.market.CommunityRepoClient
import dev.alfaoz.lbt.market.MarketData
import dev.alfaoz.lbt.valuation.ItemAttributes
import dev.alfaoz.lbt.valuation.Valuation
import dev.alfaoz.lbt.valuation.Valuator

/** A valuation plus how settled it is, so the UI can say "still loading" instead of lying. */
data class Estimate(val valuation: Valuation?, val loading: Boolean)

/**
 * Glue between UI-thread callers and the market caches. Estimates recompute from cached data
 * only (no blocking I/O); MarketData kicks background fetches on cache misses and the next
 * frame's recompute picks up whatever landed. A tiny memo keyed by item + data generation keeps
 * per-frame tooltip/panel calls cheap.
 */
class EstimateService(val market: MarketData, private val repos: CommunityRepoClient) {

    private data class MemoKey(val attrs: ItemAttributes, val nudge: Double, val windowHours: Int)
    private class MemoEntry(val estimate: Estimate, val at: Long)

    private val memo = HashMap<MemoKey, MemoEntry>()
    private val memoTtlMillis = 750L

    @Synchronized
    fun clearMemo() {
        memo.clear()
    }

    @Synchronized
    fun estimate(attrs: ItemAttributes): Estimate {
        val config = LowballerClient.config
        val key = MemoKey(attrs, config.manualDiscountAdjustment, config.priceWindowHours)
        val now = System.currentTimeMillis()
        memo[key]?.let { if (now - it.at < memoTtlMillis) return it.estimate }
        if (memo.size > 256) memo.clear()

        val comps = market.soldComps(attrs.itemId, config.soldLookbackDays)
        val valuation = Valuator.estimate(
            target = attrs,
            comps = comps.orEmpty(),
            source = market,
            repo = repos.repoData,
            settings = config.valuationSettings(),
            binListings = market.binListings(attrs.itemId, attrs.tier).orEmpty(),
        )
        val loading = comps == null || !market.fullyLoaded(attrs.itemId, attrs.tier) || !market.bazaarReady
        val estimate = Estimate(valuation, loading)
        memo[key] = MemoEntry(estimate, now)
        return estimate
    }
}
