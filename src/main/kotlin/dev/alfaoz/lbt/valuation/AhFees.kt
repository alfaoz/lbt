package dev.alfaoz.lbt.valuation

import kotlin.math.min

/**
 * Hypixel auction-house fees for a BIN listing, per the wiki (verified 2026-07):
 *  - listing fee: 1% under 10M, 2% from 10M to 100M, 2.5% above 100M
 *  - collection tax: 1% of the sale when above 1M, capped so the collected amount
 *    never drops below 1M.
 * This is what turns "sells for Y" into "you pocket Y - fees" - the number a flipper
 * actually cares about.
 */
object AhFees {

    fun listingFee(price: Double): Double = when {
        price <= 0 -> 0.0
        price < 10_000_000 -> price * 0.01
        price <= 100_000_000 -> price * 0.02
        else -> price * 0.025
    }

    fun collectionTax(price: Double): Double = when {
        price <= 1_000_000 -> 0.0
        else -> min(price * 0.01, price - 1_000_000)
    }

    fun total(price: Double): Double = listingFee(price) + collectionTax(price)

    /** What lands in your purse after listing at [price] and it selling. */
    fun netProceeds(price: Double): Double = (price - total(price)).coerceAtLeast(0.0)
}
