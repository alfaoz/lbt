package dev.alfaoz.lbt

import dev.alfaoz.lbt.valuation.ItemAttributes
import dev.alfaoz.lbt.valuation.PartCatalog
import dev.alfaoz.lbt.valuation.PriceSource
import dev.alfaoz.lbt.valuation.priceParts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The enchant domain rules (SkyHanni-derived) that turn raw NBT into priceable parts. */
class PartCatalogTest {
    private val catalog = PartCatalog(Fixtures.bareRules)

    private fun item(vararg enchants: Pair<String, Int>) =
        ItemAttributes(itemId = "TEST_SWORD", enchants = enchants.toMap())

    @Test
    fun `efficiency 1-5 is worthless, 6+ counts silexes`() {
        assertTrue(catalog.partsFor(item("efficiency" to 5)).isEmpty())
        val silex = catalog.partsFor(item("efficiency" to 8)).single()
        assertEquals("SIL_EX", silex.productId)
        assertEquals(3.0, silex.count)
    }

    @Test
    fun `tier-one-only enchants use combine math`() {
        val part = catalog.partsFor(item("smoldering" to 3)).single()
        assertEquals("ENCHANTMENT_SMOLDERING_1", part.productId)
        assertEquals(4.0, part.count, "level 3 = 2^(3-1) tier-1 books")
    }

    @Test
    fun `tier-five-only enchants combine from tier 5`() {
        val part = catalog.partsFor(item("strong_mana" to 8)).single()
        assertEquals("ENCHANTMENT_STRONG_MANA_5", part.productId)
        assertEquals(8.0, part.count, "level 8 = 2^(8-5) tier-5 books")
    }

    @Test
    fun `stacking enchants price as the tier-1 book`() {
        val part = catalog.partsFor(item("champion" to 9)).single()
        assertEquals("ENCHANTMENT_CHAMPION_1", part.productId)
        assertEquals(1.0, part.count)
    }

    @Test
    fun `always-active enchants are worth nothing on their host item`() {
        val hosted = ItemAttributes(itemId = "CRYPT_DREADLORD_SWORD", enchants = mapOf("scavenger" to 5))
        assertTrue(catalog.partsFor(hosted).isEmpty())
        val elsewhere = ItemAttributes(itemId = "OTHER_SWORD", enchants = mapOf("scavenger" to 5))
        assertEquals("ENCHANTMENT_SCAVENGER_5", elsewhere.let { catalog.partsFor(it).single().productId })
    }

    @Test
    fun `dungeon item stars split into essence and master stars`() {
        val repo = Fixtures.repoData()
        val fullCatalog = PartCatalog(repo)
        val terminator = ItemAttributes(itemId = "TERMINATOR", upgradeLevel = 7, dungeonItem = true)
        val parts = fullCatalog.partsFor(terminator)
        assertTrue(parts.any { it.productId == "ESSENCE_DRAGON" }, "Terminator stars 1-5 cost dragon essence per NEU schedule")
        assertTrue(parts.any { it.productId == "FIRST_MASTER_STAR" })
        assertTrue(parts.any { it.productId == "SECOND_MASTER_STAR" })
        assertTrue(parts.none { it.productId == "THIRD_MASTER_STAR" }, "only 2 master stars at 7 stars total")
    }

    @Test
    fun `reforge stones price via display name and via nbt modifier`() {
        val repo = Fixtures.repoData()
        val fullCatalog = PartCatalog(repo)
        // Coflnet comps carry the display name ("withered"), live NBT carries the modifier -
        // for Withered they coincide; both must resolve to the Wither Blood stone.
        val withered = ItemAttributes(itemId = "HYPERION", reforge = "withered")
        assertEquals("WITHER_BLOOD", fullCatalog.partsFor(withered).single().productId)

        // Warped is the known divergent case: NBT says "aote_stone", display says "Warped".
        val json = com.google.gson.JsonParser.parseString(
            """{"AOTE_STONE": {"reforgeName": "Warped", "nbtModifier": "aote_stone", "reforgeCosts": {"EPIC": 10000}}}""",
        ).asJsonObject
        val stones = dev.alfaoz.lbt.market.CommunityRepoClient.parseReforgeStones(json)
        assertEquals("AOTE_STONE", stones["warped"]?.stoneTag)
        assertEquals("AOTE_STONE", stones["aote_stone"]?.stoneTag)
    }

    @Test
    fun `basic reforges without stones price as zero, not as errors`() {
        val fullCatalog = PartCatalog(Fixtures.repoData())
        // "Hasty"/"Heroic" are coin-applied basic reforges - no stone exists, no value assigned.
        val hasty = ItemAttributes(itemId = "TERMINATOR", reforge = "hasty")
        assertTrue(fullCatalog.partsFor(hasty).isEmpty())
    }

    @Test
    fun `recomb, hpb and fuming, scrolls, gems all enumerate`() {
        val attrs = ItemAttributes(
            itemId = "HYPERION",
            recombobulated = true,
            hotPotatoCount = 15,
            abilityScrolls = listOf("IMPLOSION_SCROLL", "WITHER_SHIELD_SCROLL"),
            gems = listOf(dev.alfaoz.lbt.valuation.GemSlot("COMBAT_0", "JASPER", "PERFECT")),
        )
        val ids = catalog.partsFor(attrs).map { it.productId }
        assertTrue("RECOMBOBULATOR_3000" in ids)
        assertTrue("HOT_POTATO_BOOK" in ids)
        assertTrue("FUMING_POTATO_BOOK" in ids)
        assertTrue("IMPLOSION_SCROLL" in ids)
        assertTrue("PERFECT_JASPER_GEM" in ids)
    }

    @Test
    fun `bazaar-only part kinds never fall back to AH lookups`() {
        // Enchant books aren't an AH market; Coflnet 400s on ENCHANTMENT_* tags, and each
        // hovered item prices dozens of comp parts - AH fallback here was a rate-limit storm.
        val noAh = object : PriceSource {
            override fun bazaarSell(productId: String): Double? = null
            override fun bazaarBuy(productId: String): Double? = null
            override fun lowestBin(tag: String): Double? =
                fail("AH lookup for bazaar-only part: $tag")
        }
        val parts = catalog.partsFor(
            item("ultimate_wise" to 5, "sharpness" to 6).copy(recombobulated = true, hotPotatoCount = 10),
        )
        val priced = priceParts(parts, noAh) { it.defaultHaircut }
        assertTrue(priced.none { it.priced }, "nothing should price without bazaar data")

        // AH-market kinds (scrolls, pet items) still fall through to BIN.
        val scroll = catalog.partsFor(ItemAttributes(itemId = "HYPERION", abilityScrolls = listOf("IMPLOSION_SCROLL")))
        val binPriced = priceParts(scroll, Fixtures.MapPrices(binsByTag = mapOf("IMPLOSION_SCROLL" to listOf(250_000_000.0)))) { it.defaultHaircut }
        assertEquals("BIN", binPriced.single().source)
    }
}
