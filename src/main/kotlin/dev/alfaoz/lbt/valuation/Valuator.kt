package dev.alfaoz.lbt.valuation

import kotlin.math.abs

enum class LiquidityTier { HIGH, MEDIUM, LOW }

/** Pure knobs for the valuation math - a projection of LowballerConfig without any I/O. */
data class ValuationSettings(
    val fairValuePercentile: Double = 20.0,
    val minComparableSamples: Int = 4,
    val highLiquidityMinSamples: Int = 10,
    val mediumLiquidityMinSamples: Int = 3,
    val highLiquidityDiscount: Double = 0.08,
    val mediumLiquidityDiscount: Double = 0.18,
    val lowLiquidityDiscount: Double = 0.32,
    val manualDiscountAdjustment: Double = 0.0,
    /** Extra margin under lowest BIN for instant coins - the whole point of a lowball. */
    val impatiencePremium: Double = 0.05,
    /** PartKind name -> haircut override; unset kinds use PartKind.defaultHaircut. */
    val haircutOverrides: Map<String, Double> = emptyMap(),
    val petLevelTolerance: Int = 15,
    /** Asks usually clear a bit under the sticker: what fraction of the BIN anchor counts as fair. */
    val binAskDiscount: Double = 0.95,
    /** Shrinkage constant: comp weight = n/(n+k). Few sales -> trust the visible BIN; many -> trust sales. */
    val binBlendK: Double = 4.0,
    /** Asks above this multiple of the sold-comp estimate are treated as hopeful, not evidence -
     * sold prices outrank listings ("320M ask on an item that sells for 15M"). */
    val inflatedAskFactor: Double = 1.5,
) {
    fun haircutFor(kind: PartKind): Double = haircutOverrides[kind.name] ?: kind.defaultHaircut

    fun tierFor(sampleSize: Int): LiquidityTier = when {
        sampleSize >= highLiquidityMinSamples -> LiquidityTier.HIGH
        sampleSize >= mediumLiquidityMinSamples -> LiquidityTier.MEDIUM
        else -> LiquidityTier.LOW
    }

    fun discountFor(tier: LiquidityTier): Double = when (tier) {
        LiquidityTier.HIGH -> highLiquidityDiscount
        LiquidityTier.MEDIUM -> mediumLiquidityDiscount
        LiquidityTier.LOW -> lowLiquidityDiscount
    }
}

/** The full answer, with enough provenance to render a no-black-box breakdown. */
data class Valuation(
    val fairValue: Double,
    val suggestedOffer: Double,
    val liquidity: LiquidityTier,
    val discountApplied: Double,
    /** Adjusted-comp base estimate; null when no sold data existed and BIN carried the estimate. */
    val baseValue: Double?,
    val compCount: Int,
    val partsTotal: Double,
    /** Priced parts, largest first; unpriced parts kept (flagged) so the UI can say so. */
    val parts: List<PartValue>,
    /** Cheapest current listing, raw - what the user sees in the AH. */
    val lowestBin: Double?,
    /** Ask-wall anchor expressed for *this* item's build: troll-filtered normalized listing
     * base + this item's parts. This is what caps and blends, not the raw lowest. */
    val binAnchor: Double?,
    /** True when the offer was clamped by the ask-wall ceiling rather than the discount. */
    val ceilingBound: Boolean,
    val notes: List<String>,
    /** Parts with no market price anywhere (counted as 0); surfaced only when configured. */
    val unpricedPartCount: Int = 0,
) {
    /** Exact AH fees (listing tier + collection tax) if this resells at fair value. */
    val resaleFees: Double get() = AhFees.total(fairValue)

    /** What actually lands in your purse after reselling at fair value. */
    val netResale: Double get() = AhFees.netProceeds(fairValue)

    /** The flip: buy at the suggested offer, resell at fair, pocket this after fees. */
    val flipProfit: Double get() = netResale - suggestedOffer
}

/**
 * The estimator. Comps are *adjusted to a common basis*, not filtered: each sold auction's own
 * parts value is subtracted from its sale price, yielding a distribution of what the bare base
 * item trades for; a low percentile of that plus the target's parts is the fair value. A thin
 * pool can't hide a 2.8M Ultimate Wise V anymore - the book's bazaar value flows in directly.
 *
 * Current BIN listings get the same treatment (normalized by *their* modifiers where Coflnet
 * exposes them, re-expressed with the target's parts), then serve three roles: evidence when
 * sold data is thin, a cap on fair value, and the offer ceiling (beat "just list it" net of
 * tax and waiting). Sold prices outrank asks: an ask wall far above what the item actually
 * sells for is flagged as hopeful and clamped instead of believed.
 */
object Valuator {

    private val RARITY_ORDER = listOf(
        "COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL",
    )

    fun estimate(
        target: ItemAttributes,
        comps: List<Comp>,
        source: PriceSource,
        repo: RepoData,
        settings: ValuationSettings,
        binListings: List<Comp> = emptyList(),
    ): Valuation? {
        val catalog = PartCatalog(repo)
        val notes = mutableListOf<String>()

        val targetParts = priceParts(catalog.partsFor(target), source) { settings.haircutFor(it) }
            .sortedByDescending { it.value }
        val partsTotal = targetParts.sumOf { it.value }
        val unpricedCount = targetParts.count { !it.priced }

        fun compBase(comp: Comp): Double? {
            val unitPrice = comp.price / comp.count.coerceAtLeast(1)
            if (unitPrice <= 0) return null
            val compParts = priceParts(catalog.partsFor(comp.attributes), source) { settings.haircutFor(it) }
            // A comp's base can't really be negative; mismatched haircuts occasionally push it
            // there, so keep a floor at 10% of the sale price instead of poisoning the pool.
            return (unitPrice - compParts.sumOf { it.value }).coerceAtLeast(unitPrice * 0.10)
        }

        // --- sold comps -> base distribution --------------------------------------------------
        val usableComps = filterComps(target, comps, settings, notes, settings.minComparableSamples).comps
        val trimmedBases = trimOutliers(usableComps.mapNotNull(::compBase), settings.minComparableSamples)
        val base = if (trimmedBases.isNotEmpty()) percentile(trimmedBases.sorted(), settings.fairValuePercentile) else null

        // --- ask wall -> anchor for this item's build -----------------------------------------
        // Even a single as-is listing is a real wall - the buyer can literally click it.
        val binsMatch = filterComps(target, binListings, settings, notes = mutableListOf(), minExactSamples = 1)
        val usableBins = binsMatch.comps
        // A fallback wall (e.g. clean LEGENDARY asks + stone arithmetic for a recombed MYTHIC
        // target) is not clickable for *this* item and must not cap it below its real market;
        // for clean targets the cross-market error runs high, which a cap tolerates.
        val anchorIsRealWall = binsMatch.exact || !target.recombobulated
        val rawAsks = usableBins.map { it.price / it.count.coerceAtLeast(1) }.filter { it > 0 }.sorted()
        val lowestBin = rawAsks.firstOrNull()
        val askBases = usableBins.mapNotNull(::compBase).sorted()
        // Troll filter: drop asks under 10% of the median ask (fake 1-coin listings), then the
        // cheapest surviving normalized base is the wall.
        val anchorBase = askBases.let { bases ->
            if (bases.isEmpty()) return@let null
            val median = bases[bases.size / 2]
            bases.filter { it >= median * 0.10 }.minOrNull() ?: bases.min()
        }
        val binAnchor = anchorBase?.plus(partsTotal)

        // --- blend: sold evidence vs visible asks ----------------------------------------------
        val compEstimate = base?.plus(partsTotal)
        var binFair = binAnchor?.times(settings.binAskDiscount)
        if (compEstimate != null && binFair != null && binFair > compEstimate * settings.inflatedAskFactor) {
            binFair = compEstimate * settings.inflatedAskFactor
            notes.add("current asks look inflated")
        }

        var fair = when {
            compEstimate != null && binFair != null -> {
                val weight = trimmedBases.size / (trimmedBases.size + settings.binBlendK)
                val blended = weight * compEstimate + (1 - weight) * binFair
                if (blended > compEstimate * 1.15) notes.add("few recent sales - estimate leans on current BIN asks")
                blended
            }
            compEstimate != null -> compEstimate
            binFair != null -> {
                notes.add("no sold auctions - fair value anchored to current BIN asks")
                binFair
            }
            partsTotal > 0 -> {
                notes.add("no sold auctions or BINs - parts value only, base item counted as 0")
                partsTotal
            }
            else -> return null
        }

        // A buyer can always just buy off the ask wall instead - fair value can't exceed the
        // anchor (normalized + target parts, so clean cheap listings don't wrongly cap god rolls).
        if (binAnchor != null && fair > binAnchor && anchorIsRealWall) {
            notes.add("fair value capped at current BIN asks")
            fair = binAnchor
        }

        val tier = settings.tierFor(trimmedBases.size)
        val discount = (settings.discountFor(tier) + settings.manualDiscountAdjustment).coerceIn(0.0, 0.9)
        var offer = fair * (1 - discount)

        // The seller's alternative is listing at ~the ask wall, paying real AH fees, and
        // waiting; an offer above what that nets them is money thrown away, one below it is
        // the impatience discount. Uses the exact tiered fee schedule, not a flat rate.
        var ceilingBound = false
        if (binAnchor != null && anchorIsRealWall) {
            val ceiling = AhFees.netProceeds(binAnchor) * (1 - settings.impatiencePremium)
            if (offer > ceiling) {
                offer = ceiling
                ceilingBound = true
            }
        }

        return Valuation(
            fairValue = fair,
            suggestedOffer = offer,
            liquidity = tier,
            discountApplied = discount,
            baseValue = base,
            compCount = trimmedBases.size,
            partsTotal = partsTotal,
            parts = targetParts,
            lowestBin = lowestBin,
            binAnchor = binAnchor,
            ceilingBound = ceilingBound,
            notes = notes,
            unpricedPartCount = unpricedCount,
        )
    }

    /**
     * Rarity is a hard comparable boundary: a MYTHIC and a LEGENDARY of the same tag are
     * different markets (pets especially - same tag, wildly different tiers).
     *
     * Matching prefers the *as-is market* - same displayed tier AND same recomb state -
     * because the market premium for a finished (e.g. recombed MYTHIC) item is empirically
     * far above the recomb stone's resale value: STARRED_SHADOW_ASSASSIN_HELMET legendaries
     * sell ~7-9M while mythic-recombed ones sell 16-25M; additive "LEGENDARY + 3.55M stone"
     * arithmetic priced those at ~7.5M. Only when the exact market is thin does it fall back
     * to base-rarity matching (recomb stepped down, its value handled as a part), then to the
     * unfiltered pool with a warning. Unknown tiers never count as exact matches.
     * Pets additionally match on a level band - parts math can't see levels.
     */
    /** A comp pool plus provenance: exact = every comp shares the target's tier + recomb state. */
    private class Matched(val comps: List<Comp>, val exact: Boolean)

    private fun filterComps(
        target: ItemAttributes,
        comps: List<Comp>,
        settings: ValuationSettings,
        notes: MutableList<String>,
        minExactSamples: Int,
    ): Matched {
        val targetTier = target.tier?.uppercase()
        var result = comps
        var isExact = false
        if (targetTier != null && comps.isNotEmpty()) {
            val exact = comps.filter { comp ->
                val compTier = comp.attributes.tier?.uppercase() ?: return@filter false
                compTier == targetTier && comp.attributes.recombobulated == target.recombobulated
            }
            val targetBaseTier = baseTier(target)
            if (exact.size >= minExactSamples) {
                result = exact
                isExact = true
            } else {
                val matched = comps.filter { comp ->
                    val compTier = baseTier(comp.attributes) ?: return@filter true
                    compTier == targetBaseTier
                }
                if (matched.isEmpty()) {
                    notes.add("no same-rarity sales found - using all rarities, treat with caution")
                } else {
                    result = matched
                    if (target.recombobulated) {
                        notes.add("few $targetTier-as-is sales - priced as $targetBaseTier + recomb value")
                    }
                }
            }
        }
        if (target.isPet) {
            val level = target.petLevel
            if (level != null) {
                result = result.filter { comp ->
                    val compLevel = comp.attributes.petLevel ?: return@filter true
                    abs(compLevel - level) <= settings.petLevelTolerance
                }
            }
            // Candied pets trade at a visible discount - don't let them drag an uncandied
            // pet's estimate (or vice versa). Soft: keep the mixed pool if the split is empty.
            val targetCandied = target.petCandyUsed > 0
            val sameCandy = result.filter { (it.attributes.petCandyUsed > 0) == targetCandied }
            if (sameCandy.isNotEmpty()) result = sameCandy
        }
        return Matched(result, isExact)
    }

    /** The rarity the item was born with: reported tier, one step down if recombobulated. */
    private fun baseTier(attrs: ItemAttributes): String? {
        val tier = attrs.tier?.uppercase() ?: return null
        if (!attrs.recombobulated) return tier
        val index = RARITY_ORDER.indexOf(tier)
        return if (index > 0) RARITY_ORDER[index - 1] else tier
    }

    /** IQR fence so a single troll listing can't drag the estimate. */
    private fun trimOutliers(values: List<Double>, minSamples: Int): List<Double> {
        if (values.size < minSamples) return values
        val sorted = values.sorted()
        val q1 = percentile(sorted, 25.0)
        val q3 = percentile(sorted, 75.0)
        val iqr = q3 - q1
        if (iqr <= 0.0) return sorted
        val cleaned = sorted.filter { it in (q1 - 1.5 * iqr)..(q3 + 1.5 * iqr) }
        return cleaned.ifEmpty { sorted }
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val index = p / 100.0 * (sorted.size - 1)
        val lower = index.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        val frac = index - lower
        return sorted[lower] * (1 - frac) + sorted[upper] * frac
    }
}

/** 12.3M / 45.6k / 789 - the only coin format anyone reads. */
fun formatCoins(value: Double): String = when {
    value >= 1_000_000_000 -> "%.2fB".format(value / 1_000_000_000)
    value >= 1_000_000 -> "%.2fM".format(value / 1_000_000)
    value >= 1_000 -> "%.1fk".format(value / 1_000)
    else -> "%.0f".format(value)
}
