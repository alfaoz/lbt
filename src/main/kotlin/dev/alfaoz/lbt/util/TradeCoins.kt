package dev.alfaoz.lbt.util

/**
 * Parses the coin lump-sum item Hypixel puts in trade windows. The display name is
 * *abbreviated* ("10.3M coins") - reading it digit-by-digit yields 10.3, off by a factor of a
 * million (real bug, caught in a real trade). The lore's "Total Coins Offered" section carries
 * the exact "(10,300,000)" figure, so that wins; the name's k/M/B suffix is the fallback.
 */
object TradeCoins {
    private val EXACT = Regex("""\(([\d,]+)\)""")
    private val ABBREV = Regex("""([\d,]+(?:\.\d+)?)\s*([kKmMbB])?\s*coins""")

    fun parse(displayName: String, loreLines: List<String>): Double? {
        if (loreLines.none { it.contains("Lump-sum amount") }) return null

        for (line in loreLines) {
            EXACT.find(line)?.let { m ->
                m.groupValues[1].replace(",", "").toDoubleOrNull()?.let { return it }
            }
        }
        val m = ABBREV.find(displayName) ?: return null
        val number = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val multiplier = when (m.groupValues[2].lowercase()) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            "b" -> 1_000_000_000.0
            else -> 1.0
        }
        return number * multiplier
    }
}
