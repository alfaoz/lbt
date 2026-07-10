package dev.alfaoz.lbt.valuation

/**
 * Community-maintained Skyblock knowledge, fetched from the NEU and SkyHanni repos on GitHub
 * (see market/CommunityRepoClient). Kept as plain data so the valuation core stays pure and
 * tests can load it from recorded fixtures.
 */
data class RepoData(
    val valueRules: ValueRules = ValueRules(),
    /** item tag -> essence upgrade schedule for stars. */
    val essenceCosts: Map<String, EssenceCost> = emptyMap(),
    /** NBT reforge modifier (lowercase, e.g. "withered") -> reforge stone info. */
    val reforgeStones: Map<String, ReforgeStone> = emptyMap(),
)

/** SkyHanni value_calculation_data: the enchant pricing domain rules. */
data class ValueRules(
    /** enchant -> (level, item tags) where that enchant is built into the item and worth nothing. */
    val alwaysActiveEnchants: Map<String, AlwaysActive> = emptyMap(),
    /** Enchants only traded as tier-1 books: level N (2..5) = 2^(N-1) tier-1 books. */
    val onlyTierOnePrices: Set<String> = emptySet(),
    /** Enchants only traded as tier-5 books: level N (6..10) = 2^(N-5) tier-5 books. */
    val onlyTierFivePrices: Set<String> = emptySet(),
    /** enchant -> levels beyond which a special "endcap" item is applied instead of higher books. */
    val endcapEnchants: Map<String, List<Endcap>> = emptyMap(),
    /** Enchants that level by usage (champion, expertise...) - only the tier-1 book has buy value. */
    val stackingEnchants: Set<String> = DEFAULT_STACKING,
) {
    data class AlwaysActive(val level: Int, val items: Set<String>)
    data class Endcap(val requiredLevel: Int, val endcapItem: String)

    companion object {
        /** SkyHanni-REPO's Enchants.json stacking list is empty upstream right now, so ship the known set. */
        val DEFAULT_STACKING = setOf(
            "champion", "compact", "cultivating", "expertise", "hecatomb", "toxophilite",
        )
    }
}

/** NEU essencecosts.json entry: essence type plus per-star essence amounts and extra items. */
data class EssenceCost(
    val essenceType: String,
    /** star number (1-based) -> essence amount. */
    val essencePerStar: Map<Int, Int>,
    /** star number -> extra items, entries like "SKYBLOCK_COIN:10000" or "WITHER_BLOOD:1". */
    val itemsPerStar: Map<Int, List<String>> = emptyMap(),
) {
    val essenceProductId: String get() = "ESSENCE_${essenceType.uppercase()}"
    val maxStar: Int get() = essencePerStar.keys.maxOrNull() ?: 0
}

/** NEU reforgestones.json entry (keyed by stone tag, matched via lowercase reforge name). */
data class ReforgeStone(
    val stoneTag: String,
    val reforgeName: String,
    /** rarity name (COMMON..DIVINE) -> coins to apply at the blacksmith. */
    val applyCosts: Map<String, Long> = emptyMap(),
)
