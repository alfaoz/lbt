package dev.alfaoz.lbt

import dev.alfaoz.lbt.util.TradeCoins
import kotlin.test.Test
import kotlin.test.assertEquals

/** Regression for the real trade where "10.3M coins" was counted as 10 coins. */
class TradeCoinsTest {

    @Test
    fun `exact amount from lore wins`() {
        val lore = listOf("Lump-sum amount", "", "Total Coins Offered:", "10.3M", "(10,300,000)")
        assertEquals(10_300_000.0, TradeCoins.parse("10.3M coins", lore))
    }

    @Test
    fun `abbreviated name is the fallback`() {
        val lore = listOf("Lump-sum amount")
        assertEquals(10_300_000.0, TradeCoins.parse("10.3M coins", lore))
        assertEquals(500_000.0, TradeCoins.parse("500k coins", lore))
        assertEquals(1_200_000_000.0, TradeCoins.parse("1.2B coins", lore))
        assertEquals(10.0, TradeCoins.parse("10 coins", lore))
        assertEquals(10_000.0, TradeCoins.parse("10,000 coins", lore))
    }

    @Test
    fun `non-coin items are not coins`() {
        assertEquals(null, TradeCoins.parse("Hyperion", listOf("Some lore")))
        assertEquals(null, TradeCoins.parse("10.3M coins", emptyList()), "no Lump-sum lore = not the coin item")
    }
}
