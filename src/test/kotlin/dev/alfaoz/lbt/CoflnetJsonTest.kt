package dev.alfaoz.lbt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parser checked against a recorded /sold response - field shapes verified by hand via curl. */
class CoflnetJsonTest {

    @Test
    fun `parses recorded terminator sold auctions`() {
        val comps = Fixtures.soldComps("TERMINATOR")
        assertTrue(comps.size > 50, "expected a substantial comp pool, got ${comps.size}")

        assertTrue(comps.all { it.price > 0 })
        // Modifier detail must survive parsing: the pool reliably contains recombed, starred,
        // potato'd, multi-enchant sales.
        assertTrue(comps.any { it.attributes.recombobulated }, "some sales are recombed")
        assertTrue(comps.any { it.attributes.upgradeLevel >= 5 }, "some sales have 5+ stars")
        assertTrue(comps.any { it.attributes.hotPotatoCount >= 10 }, "hpc parses")
        assertTrue(comps.any { (it.attributes.enchants["toxophilite"] ?: 0) >= 5 }, "per-enchant levels parse")
        assertTrue(comps.any { it.attributes.enchants.size >= 10 })
    }

    @Test
    fun `parses gems and ability scrolls from flattened nbt`() {
        val comps = Fixtures.soldComps("HYPERION")
        val withScrolls = comps.filter { it.attributes.abilityScrolls.isNotEmpty() }
        assertTrue(withScrolls.isNotEmpty(), "hyperion sales include ability scrolls")
        assertTrue(
            withScrolls.any { "IMPLOSION_SCROLL" in it.attributes.abilityScrolls },
            "space-separated ability_scroll field splits into tags",
        )
        val withGems = comps.filter { it.attributes.gems.isNotEmpty() }
        assertTrue(withGems.isNotEmpty(), "SAPPHIRE_0/COMBAT_0-style gem slots parse")
        assertTrue(withGems.flatMap { it.attributes.gems }.all { it.quality in setOf("ROUGH", "FLAWED", "FINE", "FLAWLESS", "PERFECT") })
    }

    @Test
    fun `reforge is recovered from the item-name prefix on sold records`() {
        // Sold records have no reforge field; "Withered Hyperion" must still yield "withered".
        val comps = Fixtures.soldComps("HYPERION")
        assertTrue(comps.any { it.attributes.reforge == "withered" })
        assertTrue(comps.any { it.attributes.reforge == "heroic" })
    }

    @Test
    fun `bin endpoint parses to positive unit prices`() {
        val bins = Fixtures.binPrices("TERMINATOR")
        assertTrue(bins.isNotEmpty())
        assertTrue(bins.all { it > 0 })
        assertEquals(bins, bins.sorted())
    }
}
