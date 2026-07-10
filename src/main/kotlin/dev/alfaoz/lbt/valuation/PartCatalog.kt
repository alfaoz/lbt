package dev.alfaoz.lbt.valuation

import kotlin.math.min

/**
 * Enumerates every value-bearing part of an item. Coverage and the enchant domain rules follow
 * SkyHanni's EstimatedItemValueCalculator (the community reference for "what carries value"),
 * reimplemented as one declarative pass over ItemAttributes.
 *
 * Known gaps vs SkyHanni, deliberate for now: gemstone slot *unlock* costs, crimson prestige
 * cost, new-year-cake/edition collectibles, pet held items. Comps cushion these: an unpriced
 * part shifts the estimate, the sold-auction distribution still anchors it.
 */
class PartCatalog(private val repo: RepoData) {

    fun partsFor(attrs: ItemAttributes): List<Part> {
        if (attrs.isPet) {
            // Pets are priced by tier/level-matched comps; the held item is the one real,
            // freely-removable add-on worth normalizing (skins ride along via the skin field).
            return listOfNotNull(
                attrs.petHeldItem?.let { Part(PartKind.PET_ITEM, prettyName(it), it) },
                attrs.skin?.let { Part(PartKind.COSMETIC, prettyName(it), it) },
            )
        }
        val parts = mutableListOf<Part>()

        parts += enchantParts(attrs)
        parts += starParts(attrs)
        parts += gemParts(attrs)
        parts += reforgeParts(attrs)

        if (attrs.recombobulated) parts.add(Part(PartKind.RECOMB, "Recombobulated", "RECOMBOBULATOR_3000"))

        val hpb = min(attrs.hotPotatoCount, 10)
        val fuming = (attrs.hotPotatoCount - 10).coerceAtLeast(0)
        if (hpb > 0) parts.add(Part(PartKind.HPB, "Hot Potato Book x$hpb", "HOT_POTATO_BOOK", hpb.toDouble()))
        if (fuming > 0) parts.add(Part(PartKind.HPB, "Fuming Potato Book x$fuming", "FUMING_POTATO_BOOK", fuming.toDouble()))

        for (scroll in attrs.abilityScrolls) {
            parts.add(Part(PartKind.ABILITY_SCROLL, prettyName(scroll), scroll))
        }
        attrs.powerAbilityScroll?.let { parts.add(Part(PartKind.ABILITY_SCROLL, prettyName(it), it)) }

        for (drillPart in attrs.drillParts) {
            parts.add(Part(PartKind.DRILL_PART, prettyName(drillPart), drillPart.uppercase()))
        }

        if (attrs.etherwarpMerged) {
            parts.add(Part(PartKind.ETHERWARP, "Etherwarp Conduit", "ETHERWARP_CONDUIT"))
            parts.add(Part(PartKind.ETHERWARP, "Etherwarp Merger", "ETHERWARP_MERGER"))
        }
        if (attrs.tunedTransmission > 0) {
            parts.add(Part(PartKind.ETHERWARP, "Transmission Tuner x${attrs.tunedTransmission}", "TRANSMISSION_TUNER", attrs.tunedTransmission.toDouble()))
        }

        attrs.skin?.let { parts.add(Part(PartKind.COSMETIC, prettyName(it), it)) }
        attrs.dyeItem?.let { parts.add(Part(PartKind.COSMETIC, prettyName(it), it)) }
        for ((rune, level) in attrs.runes) {
            parts.add(Part(PartKind.RUNE, "${prettyName(rune)} Rune $level", "RUNE_${rune.uppercase()}"))
        }
        attrs.enrichment?.let {
            parts.add(Part(PartKind.ENRICHMENT, "${prettyName(it)} Enrichment", "TALISMAN_ENRICHMENT_${it.uppercase()}"))
        }

        // one-shot consumable upgrades
        fun consumable(condition: Boolean, label: String, product: String, count: Int = 1) {
            if (condition && count > 0) parts.add(
                Part(PartKind.CONSUMABLE, if (count > 1) "$label x$count" else label, product, count.toDouble()),
            )
        }
        consumable(attrs.artOfWarCount > 0, "The Art of War", "THE_ART_OF_WAR", attrs.artOfWarCount)
        consumable(attrs.artOfPeace, "The Art of Peace", "THE_ART_OF_PEACE")
        consumable(attrs.statsBook, "Book of Stats", "BOOK_OF_STATS")
        consumable(attrs.woodSingularity, "Wood Singularity", "WOOD_SINGULARITY")
        consumable(attrs.jalapenoCount > 0, "Jalapeno Book", "JALAPENO_BOOK", attrs.jalapenoCount)
        consumable(attrs.divanPowderCoating, "Divan Powder Coating", "DIVAN_POWDER_COATING")
        consumable(attrs.mithrilInfusion, "Mithril Infusion", "MITHRIL_INFUSION")
        consumable(attrs.freeWill, "Free Will", "FREE_WILL")
        consumable(attrs.farmingForDummies > 0, "Farming for Dummies", "FARMING_FOR_DUMMIES", attrs.farmingForDummies)
        consumable(attrs.polarvoid > 0, "Polarvoid Book", "POLARVOID_BOOK", attrs.polarvoid)
        consumable(attrs.bookwormBooks > 0, "Bookworm Book", "BOOKWORM_BOOK", attrs.bookwormBooks)
        consumable(attrs.manaDisintegrators > 0, "Mana Disintegrator", "MANA_DISINTEGRATOR", attrs.manaDisintegrators)
        consumable(attrs.wetBookCount > 0, "Wet Book", "WET_BOOK", attrs.wetBookCount)
        consumable(attrs.pocketSackInASack > 0, "Pocket Sack-in-a-Sack", "POCKET_SACK_IN_A_SACK", attrs.pocketSackInASack)

        return parts
    }

    /**
     * SkyHanni's enchant pricing rules:
     *  - efficiency 1-5 is trivially cheap; 6+ means Silex applications (one per level above 5)
     *  - "always active" enchants ship built into specific items and are worth nothing there
     *  - stacking enchants (champion, expertise...) level by use; only the tier-1 book was bought
     *  - enchants only traded at tier 1 (or 5) price as 2^(level-base) combined base books
     *  - endcap enchants: levels past the cap come from a special item, not a bigger book
     */
    private fun enchantParts(attrs: ItemAttributes): List<Part> {
        val rules = repo.valueRules
        val parts = mutableListOf<Part>()

        for ((rawName, rawLevel) in attrs.enchants) {
            if (rawName == "efficiency") {
                if (rawLevel > 5) parts.add(Part(PartKind.ENCHANT, "Silex x${rawLevel - 5}", "SIL_EX", (rawLevel - 5).toDouble()))
                continue
            }

            val alwaysActive = rules.alwaysActiveEnchants[rawName]
            if (alwaysActive != null && alwaysActive.level == rawLevel && attrs.itemId in alwaysActive.items) continue

            var level = rawLevel
            var multiplier = 1

            if (rawName in rules.stackingEnchants) level = 1

            rules.endcapEnchants[rawName]?.takeIf { it.isNotEmpty() }?.let { endcaps ->
                val minRequired = endcaps.minOf { it.requiredLevel }
                if (rawLevel >= minRequired) level = minRequired
                for (endcap in endcaps) {
                    if (rawLevel >= endcap.requiredLevel + 1) {
                        parts.add(Part(PartKind.ENCHANT, prettyName(endcap.endcapItem), endcap.endcapItem))
                    }
                }
            }

            when {
                rawName in rules.onlyTierOnePrices && level in 2..5 -> {
                    multiplier = 1 shl (level - 1)
                    level = 1
                }
                rawName in rules.onlyTierFivePrices && level in 6..10 -> {
                    multiplier = 1 shl (level - 5)
                    level = 5
                }
            }

            val kind = if (rawName.startsWith("ultimate_")) PartKind.ENCHANT_ULTIMATE else PartKind.ENCHANT
            val label = "${prettyName(rawName)} ${roman(rawLevel)}"
            parts.add(Part(kind, label, "ENCHANTMENT_${rawName.uppercase()}_$level", multiplier.toDouble()))
        }
        return parts
    }

    /** Stars 1-5 (or 1-10 on crimson gear) cost essence per NEU's schedule; 6-10 on dungeon gear are master stars. */
    private fun starParts(attrs: ItemAttributes): List<Part> {
        if (attrs.upgradeLevel <= 0) return emptyList()
        val parts = mutableListOf<Part>()
        val cost = repo.essenceCosts[attrs.itemId]

        if (cost != null) {
            val essenceStars = min(attrs.upgradeLevel, cost.maxStar)
            var essenceTotal = 0L
            var coinTotal = 0.0
            val extraItems = mutableMapOf<String, Int>()
            for (star in 1..essenceStars) {
                essenceTotal += (cost.essencePerStar[star] ?: 0).toLong()
                for (entry in cost.itemsPerStar[star].orEmpty()) {
                    val tag = entry.substringBefore(':')
                    val amount = entry.substringAfter(':', "1").toIntOrNull() ?: 1
                    if (tag == "SKYBLOCK_COIN") coinTotal += amount else extraItems.merge(tag, amount, Int::plus)
                }
            }
            if (essenceTotal > 0) {
                parts.add(
                    Part(
                        PartKind.STAR_ESSENCE, "$essenceStars★ (${prettyName(cost.essenceType)} essence x$essenceTotal)",
                        cost.essenceProductId, essenceTotal.toDouble(),
                    ),
                )
            }
            if (coinTotal > 0) parts.add(Part(PartKind.STAR_ESSENCE, "Star upgrade fees", null, coinValue = coinTotal))
            for ((tag, amount) in extraItems) {
                parts.add(Part(PartKind.STAR_ESSENCE, "${prettyName(tag)} x$amount", tag, amount.toDouble()))
            }
        }

        // Master stars: dungeon gear past 5 stars, one discrete bazaar item each.
        val masterTags = listOf("FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR")
        val isDungeonLike = attrs.dungeonItem || (cost != null && cost.maxStar <= 5)
        if (isDungeonLike && attrs.upgradeLevel > 5) {
            for (star in 6..min(attrs.upgradeLevel, 10)) {
                parts.add(Part(PartKind.MASTER_STAR, "${prettyName(masterTags[star - 6])}", masterTags[star - 6]))
            }
        }
        return parts
    }

    private fun gemParts(attrs: ItemAttributes): List<Part> = attrs.gems.map { gem ->
        Part(PartKind.GEMSTONE, "${prettyName(gem.quality)} ${prettyName(gem.gemType)}", gem.productId)
    }

    /**
     * Reforge stone price only. The rarity-dependent blacksmith apply fee is skipped (we don't
     * reliably know rarity for every comp) - the REFORGE haircut is set low partly to absorb that.
     */
    private fun reforgeParts(attrs: ItemAttributes): List<Part> {
        val reforge = attrs.reforge?.lowercase() ?: return emptyList()
        val stone = repo.reforgeStones[reforge] ?: return emptyList()
        return listOf(Part(PartKind.REFORGE, "${stone.reforgeName} (${prettyName(stone.stoneTag)})", stone.stoneTag))
    }

    companion object {
        fun prettyName(id: String): String = id.lowercase().split('_', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

        private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
        fun roman(level: Int): String = ROMAN.getOrNull(level - 1) ?: level.toString()
    }
}
