package dev.alfaoz.lbt.valuation

/**
 * Category of value-bearing part. The haircut - what fraction of the part's market price
 * survives being applied to an item, from a buyer's perspective - is looked up per kind.
 */
enum class PartKind(val defaultHaircut: Double) {
    /** Applied enchant books: can't be extracted, buyers pay well under book price. */
    ENCHANT(0.55),

    /** Ultimate enchants drive the purchase decision and hold value better. */
    ENCHANT_ULTIMATE(0.70),

    /** Recombobulator: consumed on apply, and the market repays only a fraction of the ~12M
     * book on most items - measured vs live asks/sales (recombed SA leggings ask ~9M against
     * ~5.5M clean = ~3M premium, ~25-30% recovery). */
    RECOMB(0.30),

    /** Essence dumped into stars 1-5 (and crimson stars): mostly sunk cost. */
    STAR_ESSENCE(0.40),

    /** Master stars 6-10: discrete bazaar items, decent value retention. */
    MASTER_STAR(0.70),

    /** Hot potato / fuming potato books. */
    HPB(0.40),

    /** Gemstones are removable for a small fee - near-full value. */
    GEMSTONE(0.90),

    /** Hyperion-class ability scrolls: effectively define the item, near-full value. */
    ABILITY_SCROLL(0.95),

    /** Drill parts are removable. */
    DRILL_PART(0.90),

    /** Reforge stone: mostly sunk (apply cost not recoverable, stone can't be removed). */
    REFORGE(0.35),

    /** Cosmetics: skins/dyes applied to the item. */
    COSMETIC(0.80),

    RUNE(0.50),
    ENRICHMENT(0.50),

    /** Etherwarp conduit + merger + tuners on an AOTV. */
    ETHERWARP(0.70),

    /** One-shot consumable upgrades (art of war, wood singularity, jalapeno book, ...). */
    CONSUMABLE(0.45),

    /** Pet held item: freely removable, resells near its own market price. */
    PET_ITEM(0.85),
}

/** One value-bearing component of an item, before market pricing. */
data class Part(
    val kind: PartKind,
    /** Human-readable, e.g. "Ultimate Wise V" or "Master Star x3". */
    val label: String,
    /** Market product/tag to price (bazaar first, AH lowest BIN fallback); null for fixed coin costs. */
    val productId: String?,
    val count: Double = 1.0,
    /** Fixed coin amount for coin-denominated costs (essence-upgrade coin fees etc.). */
    val coinValue: Double = 0.0,
)

/** A part with its market price attached. */
data class PartValue(
    val part: Part,
    /** Price of one unit before haircut, or null when no market source knew the product. */
    val unitPrice: Double?,
    val haircut: Double,
    /** "bazaar", "BIN", "coins" - shown in the breakdown so no number is a black box. */
    val source: String,
) {
    val priced: Boolean get() = unitPrice != null || part.coinValue > 0
    val value: Double
        get() = when {
            unitPrice != null -> unitPrice * part.count * haircut
            else -> part.coinValue * haircut
        }
}

/** What the valuation core needs from the outside world. Implemented by MarketData, faked in tests. */
interface PriceSource {
    /** Proceeds of instantly selling one unit to bazaar buy orders (conservative resale value). */
    fun bazaarSell(productId: String): Double?

    /** Cost of instantly buying one unit from bazaar sell orders. */
    fun bazaarBuy(productId: String): Double?

    /** Cheapest current buy-it-now auction for the tag, per unit. */
    fun lowestBin(tag: String): Double?

    /** Cheapest few current BINs, ascending - lets the valuator anchor on the second-lowest
     * so a single fake 1-coin listing can't crash the estimate. */
    fun lowestBins(tag: String): List<Double> = listOfNotNull(lowestBin(tag))
}

/** Prices parts against the market: bazaar sell -> bazaar buy -> AH lowest BIN, in that order. */
fun priceParts(parts: List<Part>, source: PriceSource, haircutFor: (PartKind) -> Double): List<PartValue> =
    parts.map { part ->
        val haircut = haircutFor(part.kind).coerceIn(0.0, 1.0)
        if (part.productId == null) {
            PartValue(part, unitPrice = null, haircut = haircut, source = "coins")
        } else {
            val bazaarSell = source.bazaarSell(part.productId)
            val bazaarBuy = source.bazaarBuy(part.productId)
            val bin = if (bazaarSell == null && bazaarBuy == null) source.lowestBin(part.productId) else null
            val (unit, src) = when {
                bazaarSell != null && bazaarSell > 0 -> bazaarSell to "bazaar"
                bazaarBuy != null && bazaarBuy > 0 -> bazaarBuy to "bazaar"
                bin != null && bin > 0 -> bin to "BIN"
                else -> null to "unpriced"
            }
            PartValue(part, unit, haircut, src)
        }
    }
