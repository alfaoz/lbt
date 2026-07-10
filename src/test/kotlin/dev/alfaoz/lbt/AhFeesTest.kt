package dev.alfaoz.lbt

import dev.alfaoz.lbt.valuation.AhFees
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hypixel wiki fee schedule: 1%/2%/2.5% listing tiers + capped 1% collection tax. */
class AhFeesTest {

    @Test
    fun `listing fee tiers`() {
        assertEquals(50_000.0, AhFees.listingFee(5_000_000.0), 1.0) // 1% under 10M
        assertEquals(1_000_000.0, AhFees.listingFee(50_000_000.0), 1.0) // 2% in 10M..100M
        assertEquals(5_000_000.0, AhFees.listingFee(200_000_000.0), 1.0) // 2.5% over 100M
    }

    @Test
    fun `collection tax only above 1M`() {
        assertEquals(0.0, AhFees.collectionTax(900_000.0))
        assertEquals(0.0, AhFees.collectionTax(1_000_000.0))
        assertEquals(500_000.0, AhFees.collectionTax(50_000_000.0), 1.0)
        // Capped: collecting can't drop below 1M, so tax on 1.005M is 5k, not 10.05k.
        assertEquals(5_000.0, AhFees.collectionTax(1_005_000.0), 1.0)
    }

    @Test
    fun `net proceeds are what the flipper pockets`() {
        // 50M sale: 1M listing + 500k tax = 48.5M net.
        assertEquals(48_500_000.0, AhFees.netProceeds(50_000_000.0), 1.0)
        assertTrue(AhFees.netProceeds(0.0) == 0.0)
    }
}
