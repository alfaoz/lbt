package dev.alfaoz.lbt

import dev.alfaoz.lbt.valuation.Comp
import dev.alfaoz.lbt.valuation.ItemAttributes
import dev.alfaoz.lbt.valuation.ValuationSettings
import dev.alfaoz.lbt.valuation.Valuator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ValuatorTest {
    private val settings = ValuationSettings()

    private fun comp(price: Double, attrs: ItemAttributes = ItemAttributes(itemId = "TEST_ITEM")) =
        Comp(price = price, count = 1, bin = true, endedAtMillis = null, attributes = attrs)

    // --- the Ultimate Wise regression: the bug that started this rewrite --------------------

    @Test
    fun `parts value flows in even when no comp carries the enchant`() {
        // 7 cheap bare sales, none with Ultimate Wise - the old filter-based estimator said 560k.
        val comps = (1..7).map { comp(500_000.0 + it * 20_000) }
        val target = ItemAttributes(itemId = "TEST_ITEM", enchants = mapOf("ultimate_wise" to 5))
        val prices = Fixtures.MapPrices(sell = mapOf("ENCHANTMENT_ULTIMATE_WISE_5" to 2_600_000.0))

        val v = assertNotNull(Valuator.estimate(target, comps, prices, Fixtures.bareRules, settings))
        val bookValue = 2_600_000.0 * 0.70 // ENCHANT_ULTIMATE haircut
        assertTrue(
            v.fairValue >= 500_000 + bookValue * 0.99,
            "fair (${v.fairValue}) must include the book's market value, not just bare comps",
        )
        assertEquals(bookValue, v.partsTotal, 1.0)
    }

    @Test
    fun `comps with expensive parts are normalized down to base`() {
        // Same base item; half sold bare at ~10M, half sold with a 2.6M book for ~12.6M.
        // Adjusted bases should agree at ~10M-ish instead of splitting into two clusters.
        val enchanted = ItemAttributes(itemId = "TEST_ITEM", enchants = mapOf("ultimate_wise" to 5))
        val prices = Fixtures.MapPrices(sell = mapOf("ENCHANTMENT_ULTIMATE_WISE_5" to 2_600_000.0))
        val comps = (1..5).map { comp(10_000_000.0 + it * 100_000) } +
            (1..5).map { comp(12_600_000.0 + it * 100_000, enchanted) }

        val bare = ItemAttributes(itemId = "TEST_ITEM")
        val v = assertNotNull(Valuator.estimate(bare, comps, prices, Fixtures.bareRules, settings))
        val base = assertNotNull(v.baseValue)
        assertTrue(base in 9_500_000.0..11_500_000.0, "normalized base was $base, expected ~10M")
    }

    @Test
    fun `reforge stone value is subtracted from comps and added to the target`() {
        val repo = Fixtures.repoData()
        val prices = Fixtures.MapPrices(sell = mapOf("WITHER_BLOOD" to 20_000_000.0))
        val stoneValue = 20_000_000.0 * 0.35 // REFORGE haircut

        // All sales are Withered at 57M; a clean target is worth the base without the stone.
        val withered = ItemAttributes(itemId = "TEST_ITEM", reforge = "withered")
        val comps = (1..10).map { comp(57_000_000.0 + it * 50_000, withered) }
        val clean = assertNotNull(
            Valuator.estimate(ItemAttributes(itemId = "TEST_ITEM"), comps, prices, repo, settings),
        )
        val cleanBase = assertNotNull(clean.baseValue)
        assertTrue(cleanBase < 57_000_000.0 - stoneValue * 0.9, "stone value must come off comp prices (base=$cleanBase)")

        // And a Withered target gets it added back.
        val witheredTarget = assertNotNull(Valuator.estimate(withered, comps, prices, repo, settings))
        assertEquals(stoneValue, witheredTarget.partsTotal, 1.0)
        assertTrue(witheredTarget.fairValue > clean.fairValue)
    }

    // --- rarity: same tag, different market ---------------------------------------------------

    @Test
    fun `different-rarity comps are excluded`() {
        val legendary = ItemAttributes(itemId = "PET_ENDER_DRAGON", tier = "LEGENDARY", petLevel = 80)
        val epicComps = (1..10).map {
            comp(30_000_000.0, ItemAttributes(itemId = "PET_ENDER_DRAGON", tier = "EPIC", petLevel = 80))
        }
        val legendaryComps = (1..5).map {
            comp(400_000_000.0 + it * 1_000_000, ItemAttributes(itemId = "PET_ENDER_DRAGON", tier = "LEGENDARY", petLevel = 82))
        }
        val v = assertNotNull(
            Valuator.estimate(legendary, epicComps + legendaryComps, Fixtures.MapPrices(), Fixtures.bareRules, settings),
        )
        assertTrue(v.fairValue > 300_000_000.0, "EPIC sales must not drag a LEGENDARY pet's value (got ${v.fairValue})")
        assertEquals(5, v.compCount)
    }

    @Test
    fun `recombed comps match at base rarity`() {
        // A recombed item reports one tier up; after stepping down it comps against clean ones.
        val cleanLegendary = ItemAttributes(itemId = "TEST_ITEM", tier = "LEGENDARY")
        val recombed = ItemAttributes(itemId = "TEST_ITEM", tier = "MYTHIC", recombobulated = true)
        val prices = Fixtures.MapPrices(sell = mapOf("RECOMBOBULATOR_3000" to 12_000_000.0))
        val comps = (1..8).map { comp(50_000_000.0 + it * 200_000, recombed) }

        val v = assertNotNull(Valuator.estimate(cleanLegendary, comps, prices, Fixtures.bareRules, settings))
        assertEquals(8, v.compCount, "recombed MYTHIC sales are base-LEGENDARY comps")
        val base = assertNotNull(v.baseValue)
        // Comps sold at 50.2-51.6M; with the 3.6M recomb part subtracted, the p20 base lands
        // near 46.9M - anything under 47.5M proves the subtraction happened.
        assertTrue(base < 47_500_000.0, "recomb value must be subtracted from comp prices (base=$base)")
    }

    @Test
    fun `recombed items price from their as-is market, not base plus stone arithmetic`() {
        // The SA Helmet case: clean LEGENDARY 5-stars sell ~8M, mythic-recombed ones sell
        // 16-25M. The old base-tier matching priced the mythic as "LEGENDARY + 3.6M stone"
        // (~11M); with 62 real mythic sales in the pool, those must carry the estimate.
        val legendary = ItemAttributes(itemId = "SA_HELMET", tier = "LEGENDARY")
        val mythic = ItemAttributes(itemId = "SA_HELMET", tier = "MYTHIC", recombobulated = true)
        val prices = Fixtures.MapPrices(sell = mapOf("RECOMBOBULATOR_3000" to 12_000_000.0))
        val comps = (1..40).map { comp(8_000_000.0 + it * 25_000, legendary) } +
            (1..12).map { comp(17_000_000.0 + it * 250_000, mythic) }

        val v = assertNotNull(Valuator.estimate(mythic, comps, prices, Fixtures.bareRules, settings))
        assertEquals(12, v.compCount, "must comp against the 12 as-is mythic sales only")
        assertTrue(v.fairValue > 14_000_000.0, "mythic premium must survive (fair=${v.fairValue})")
        // And the clean legendary still prices off its own market, recombed sales excluded.
        val clean = assertNotNull(Valuator.estimate(legendary, comps, prices, Fixtures.bareRules, settings))
        assertEquals(40, clean.compCount)
        assertTrue(clean.fairValue < 10_000_000.0)
    }

    @Test
    fun `cross-market BIN wall cannot cap a recombed item below its own sold market`() {
        // Sold data is rich in mythic-recombed sales, but the visible listings are all clean
        // legendaries (Coflnet pages the 10 cheapest). That wall isn't clickable for this
        // item - it must not cap fair value or ceiling the offer.
        val legendary = ItemAttributes(itemId = "SA_HELMET", tier = "LEGENDARY")
        val mythic = ItemAttributes(itemId = "SA_HELMET", tier = "MYTHIC", recombobulated = true)
        val prices = Fixtures.MapPrices(sell = mapOf("RECOMBOBULATOR_3000" to 12_000_000.0))
        val comps = (1..12).map { comp(17_000_000.0 + it * 250_000, mythic) }
        val bins = (1..6).map { comp(8_000_000.0 + it * 300_000, legendary) }

        val v = assertNotNull(Valuator.estimate(mythic, comps, prices, Fixtures.bareRules, settings, bins))
        assertTrue(v.fairValue > 14_000_000.0, "legendary asks must not cap the mythic (fair=${v.fairValue})")
        assertTrue(!v.ceilingBound, "offer must not be ceilinged by a cross-market wall")

        // But a single real as-is listing IS a wall: add a 15M mythic BIN and the cap returns.
        val binsWithMythic = bins + comp(15_000_000.0, mythic)
        val capped = assertNotNull(Valuator.estimate(mythic, comps, prices, Fixtures.bareRules, settings, binsWithMythic))
        val anchor = assertNotNull(capped.binAnchor)
        assertTrue(capped.fairValue <= anchor, "as-is listing must anchor (fair=${capped.fairValue}, anchor=$anchor)")
    }

    @Test
    fun `pet held item prices as a part and candied comps are excluded for uncandied pets`() {
        val pet = ItemAttributes(
            itemId = "PET_GOLEM", tier = "LEGENDARY", petLevel = 81,
            petHeldItem = "PET_ITEM_LUCKY_CLOVER",
        )
        val prices = Fixtures.MapPrices(sell = mapOf("PET_ITEM_LUCKY_CLOVER" to 1_000_000.0))
        val candied = ItemAttributes(itemId = "PET_GOLEM", tier = "LEGENDARY", petLevel = 80, petCandyUsed = 10)
        val clean = ItemAttributes(itemId = "PET_GOLEM", tier = "LEGENDARY", petLevel = 80)
        val comps = (1..6).map { comp(3_000_000.0, candied) } +
            (1..6).map { comp(5_000_000.0 + it * 10_000, clean) }

        val v = assertNotNull(Valuator.estimate(pet, comps, prices, Fixtures.bareRules, settings))
        assertEquals(6, v.compCount, "candied sales are a different market for an uncandied pet")
        assertEquals(850_000.0, v.partsTotal, 1.0) // held item at PET_ITEM haircut 0.85
        assertTrue(v.fairValue > 5_000_000.0, "candied 3M sales must not drag the estimate (fair=${v.fairValue})")
    }

    // --- trending markets: the Bouquet of Lies case --------------------------------------------

    private fun timedComp(price: Double, hoursAgo: Double, now: Long = 1_800_000_000_000L) =
        Comp(price = price, count = 1, bin = true, endedAtMillis = now - (hoursAgo * 3_600_000).toLong(),
             attributes = ItemAttributes(itemId = "TEST_ITEM"))

    @Test
    fun `a rallying market prices at today's level, not last week's`() {
        // BOL live case: sold ~30M three days ago, ~55M in the last 12h. A percentile over the
        // mixed pool said ~34M; detrending must land the estimate near the fresh cluster.
        val comps = (1..30).map { timedComp(30_000_000.0 + it * 100_000, hoursAgo = 60.0 + it) } +
            (1..20).map { timedComp(40_000_000.0 + it * 100_000, hoursAgo = 24.0 + it / 2.0) } +
            (1..8).map { timedComp(55_000_000.0 + it * 200_000, hoursAgo = it.toDouble()) }
        val v = assertNotNull(
            Valuator.estimate(ItemAttributes(itemId = "TEST_ITEM"), comps, Fixtures.MapPrices(), Fixtures.bareRules, settings),
        )
        assertTrue(v.fairValue > 48_000_000.0, "rally must lift the estimate to the fresh level (fair=${v.fairValue})")
        assertTrue(v.notes.any { it.contains("market moved") }, "drift note must surface")
    }

    @Test
    fun `a crashing market prices at today's level, protecting the buyer`() {
        // Symmetric: sold ~55M days ago, ~30M now. Offering last week's price overpays.
        val comps = (1..8).map { timedComp(30_000_000.0 + it * 200_000, hoursAgo = it.toDouble()) } +
            (1..20).map { timedComp(40_000_000.0 + it * 100_000, hoursAgo = 24.0 + it / 2.0) } +
            (1..30).map { timedComp(55_000_000.0 + it * 100_000, hoursAgo = 60.0 + it) }
        val v = assertNotNull(
            Valuator.estimate(ItemAttributes(itemId = "TEST_ITEM"), comps, Fixtures.MapPrices(), Fixtures.bareRules, settings),
        )
        assertTrue(v.fairValue < 36_000_000.0, "crash must drop the estimate to the fresh level (fair=${v.fairValue})")
    }

    @Test
    fun `a flat market is untouched by detrending`() {
        val comps = (1..40).map { timedComp(20_000_000.0 + (it % 5) * 100_000, hoursAgo = it * 2.0) }
        val v = assertNotNull(
            Valuator.estimate(ItemAttributes(itemId = "TEST_ITEM"), comps, Fixtures.MapPrices(), Fixtures.bareRules, settings),
        )
        assertTrue(v.fairValue in 19_500_000.0..21_000_000.0, "flat market must stay put (fair=${v.fairValue})")
        assertTrue(v.notes.none { it.contains("market moved") })
    }

    @Test
    fun `price window cuts old comps but measures from the newest sale`() {
        // 12h window on data whose newest sale is days in the past (recorded fixtures).
        val old = (1..10).map { timedComp(50_000_000.0, hoursAgo = 200.0 + it) }
        val fresh = (1..6).map { timedComp(30_000_000.0 + it * 50_000, hoursAgo = 190.0 + it * 0.5) }
        val v = assertNotNull(
            Valuator.estimate(
                ItemAttributes(itemId = "TEST_ITEM"), old + fresh, Fixtures.MapPrices(), Fixtures.bareRules,
                settings.copy(priceWindowHours = 12),
            ),
        )
        assertEquals(6, v.compCount, "only the newest 12h (relative to the newest sale) may count")
        assertTrue(v.fairValue < 35_000_000.0)
    }

    // --- ask-wall interactions -----------------------------------------------------------------

    @Test
    fun `thin comps defer to the visible BIN wall`() {
        // 2 stale sales at 60M but everyone currently lists at 100M: estimate must not insult
        // the seller with a 60M-anchored number.
        val comps = listOf(comp(60_000_000.0), comp(61_000_000.0))
        val bins = listOf(comp(100_000_000.0), comp(101_000_000.0))
        val v = assertNotNull(
            Valuator.estimate(ItemAttributes(itemId = "TEST_ITEM"), comps, Fixtures.MapPrices(), Fixtures.bareRules, settings, bins),
        )
        assertTrue(v.fairValue > 75_000_000.0, "fair ${v.fairValue} should lean toward the 100M ask wall on n=2")
    }

    @Test
    fun `delusional asks lose to deep sold evidence`() {
        // The user's case: item actually sells for ~15M, someone lists at 320M.
        val comps = (1..20).map { comp(15_000_000.0 + it * 100_000) }
        val bins = listOf(comp(320_000_000.0))
        val v = assertNotNull(
            Valuator.estimate(ItemAttributes(itemId = "TEST_ITEM"), comps, Fixtures.MapPrices(), Fixtures.bareRules, settings, bins),
        )
        assertTrue(v.fairValue < 25_000_000.0, "a 320M ask must not inflate a 15M item (got ${v.fairValue})")
        assertTrue(v.notes.any { it.contains("inflated") }, "the inflated-ask note must surface")
    }

    @Test
    fun `god-rolled BIN normalizes down before anchoring a clean item`() {
        // Cheapest listings: a clean 16M and a 320M one whose Ultimate Wise book explains most
        // of its price. The clean target's anchor must come from the clean listing.
        val godRoll = ItemAttributes(itemId = "TEST_ITEM", enchants = mapOf("ultimate_wise" to 5))
        val prices = Fixtures.MapPrices(sell = mapOf("ENCHANTMENT_ULTIMATE_WISE_5" to 400_000_000.0))
        val bins = listOf(comp(16_000_000.0), comp(320_000_000.0, godRoll))
        val v = assertNotNull(
            Valuator.estimate(ItemAttributes(itemId = "TEST_ITEM"), emptyList(), prices, Fixtures.bareRules, settings, bins),
        )
        assertTrue(v.fairValue < 20_000_000.0, "clean target anchors on the clean 16M ask, got ${v.fairValue}")
    }

    @Test
    fun `single troll BIN cannot crash the estimate`() {
        val bins = listOf(comp(1.0), comp(95_000_000.0), comp(99_000_000.0))
        val v = assertNotNull(
            Valuator.estimate(ItemAttributes(itemId = "TEST_ITEM"), emptyList(), Fixtures.MapPrices(), Fixtures.bareRules, settings, bins),
        )
        assertTrue(v.fairValue > 1_000_000.0, "1-coin listing must be troll-filtered, got ${v.fairValue}")
    }

    @Test
    fun `deep comps override hopeful asks and the anchor caps the offer`() {
        val comps = (1..30).map { comp(60_000_000.0 + it * 100_000) }
        val bins = listOf(comp(100_000_000.0))
        val v = assertNotNull(
            Valuator.estimate(ItemAttributes(itemId = "TEST_ITEM"), comps, Fixtures.MapPrices(), Fixtures.bareRules, settings, bins),
        )
        assertTrue(v.fairValue < 70_000_000.0, "30 real sales at ~60M outweigh a 100M ask")
        val ceiling = dev.alfaoz.lbt.valuation.AhFees.netProceeds(100_000_000.0) * (1 - settings.impatiencePremium)
        assertTrue(v.suggestedOffer <= ceiling + 1)
    }

    @Test
    fun `flip fields expose real fee economics`() {
        val comps = (1..20).map { comp(50_000_000.0 + it * 50_000) }
        val v = assertNotNull(
            Valuator.estimate(ItemAttributes(itemId = "TEST_ITEM"), comps, Fixtures.MapPrices(), Fixtures.bareRules, settings),
        )
        // ~50M sale: 2% listing + 1% collection = ~3% fees.
        assertEquals(v.fairValue * 0.03, v.resaleFees, v.fairValue * 0.001)
        assertEquals(v.fairValue - v.resaleFees, v.netResale, 1.0)
        assertEquals(v.netResale - v.suggestedOffer, v.flipProfit, 1.0)
        assertTrue(v.flipProfit > 0, "buying at the suggested offer must clear fees (profit=${v.flipProfit})")
    }

    @Test
    fun `offer is always below fair value`() {
        val comps = (1..20).map { comp(1_000_000.0 + it * 10_000) }
        val v = assertNotNull(
            Valuator.estimate(ItemAttributes(itemId = "TEST_ITEM"), comps, Fixtures.MapPrices(), Fixtures.bareRules, settings),
        )
        assertTrue(v.suggestedOffer < v.fairValue)
        assertTrue(v.suggestedOffer > 0)
    }

    // --- end-to-end on recorded market data ---------------------------------------------------

    @Test
    fun `terminator estimate from recorded live data is sane`() {
        val comps = Fixtures.soldComps("TERMINATOR")
        val bins = Fixtures.binComps("TERMINATOR")
        val prices = Fixtures.RecordedPrices("TERMINATOR")
        val repo = Fixtures.repoData()

        // A middle-of-the-road recombed 5-star Terminator.
        val target = ItemAttributes(
            itemId = "TERMINATOR", recombobulated = true, upgradeLevel = 5, dungeonItem = true,
            hotPotatoCount = 15, tier = "MYTHIC",
            enchants = mapOf("power" to 6, "overload" to 5, "cubism" to 5, "toxophilite" to 7),
        )
        val v = assertNotNull(Valuator.estimate(target, comps, prices, repo, settings, bins))

        assertTrue(v.compCount > 20, "recorded pool has ~100 sales, trimmed n=${v.compCount}")
        val anchor = assertNotNull(v.binAnchor)
        assertTrue(v.fairValue <= anchor, "fair ${v.fairValue} must respect the anchor cap $anchor")
        assertTrue(v.fairValue > anchor * 0.2, "fair ${v.fairValue} improbably far below anchor $anchor")
        assertTrue(v.suggestedOffer < v.fairValue)
        assertTrue(v.partsTotal > 0, "recomb + stars + enchants must price as parts")
        assertTrue(v.parts.any { it.part.productId == "RECOMBOBULATOR_3000" })
    }

    @Test
    fun `no data at all returns null, parts-only returns parts`() {
        val bare = ItemAttributes(itemId = "UNKNOWN_ITEM")
        assertEquals(null, Valuator.estimate(bare, emptyList(), Fixtures.MapPrices(), Fixtures.bareRules, settings))

        val enchanted = bare.copy(enchants = mapOf("ultimate_wise" to 5))
        val prices = Fixtures.MapPrices(sell = mapOf("ENCHANTMENT_ULTIMATE_WISE_5" to 2_600_000.0))
        val v = assertNotNull(Valuator.estimate(enchanted, emptyList(), prices, Fixtures.bareRules, settings))
        assertEquals(v.partsTotal, v.fairValue, 1.0)
    }
}
