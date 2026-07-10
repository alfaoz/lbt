package dev.alfaoz.lbt.valuation

/**
 * Everything on a Skyblock item that carries market value, in one Minecraft-independent model.
 * Both the live ItemStack parser and the Coflnet sold-record parser produce this, which is what
 * lets the valuator subtract a comp's modifiers from its sale price on equal footing with the
 * hovered item's own modifiers.
 */
data class ItemAttributes(
    val itemId: String,
    val displayName: String = "",
    val count: Int = 1,
    val reforge: String? = null,
    val recombobulated: Boolean = false,
    /** Stars: upgrade_level (new) or dungeon_item_level (legacy); 6-10 are master stars on dungeon gear. */
    val upgradeLevel: Int = 0,
    val dungeonItem: Boolean = false,
    val hotPotatoCount: Int = 0,
    val enchants: Map<String, Int> = emptyMap(),
    val gems: List<GemSlot> = emptyList(),
    /** Hyperion-class wither scrolls etc. (IMPLOSION_SCROLL...), each an AH/bazaar item of its own. */
    val abilityScrolls: List<String> = emptyList(),
    val artOfWarCount: Int = 0,
    val artOfPeace: Boolean = false,
    val statsBook: Boolean = false,
    val woodSingularity: Boolean = false,
    val jalapenoCount: Int = 0,
    val divanPowderCoating: Boolean = false,
    val mithrilInfusion: Boolean = false,
    val freeWill: Boolean = false,
    val farmingForDummies: Int = 0,
    val polarvoid: Int = 0,
    val bookwormBooks: Int = 0,
    val tunedTransmission: Int = 0,
    val etherwarpMerged: Boolean = false,
    val manaDisintegrators: Int = 0,
    val wetBookCount: Int = 0,
    val pocketSackInASack: Int = 0,
    /** Values of drill_part_engine / drill_part_fuel_tank / drill_part_upgrade_module (item ids). */
    val drillParts: List<String> = emptyList(),
    val powerAbilityScroll: String? = null,
    val skin: String? = null,
    val dyeItem: String? = null,
    /** rune name -> level, e.g. "MUSIC" -> 3. */
    val runes: Map<String, Int> = emptyMap(),
    val enrichment: String? = null,
    /** Current rarity (COMMON..DIVINE) - a hard comparable boundary; Coflnet sends it on every
     * record, the live parser reads it from lore. Null = unknown, matches everything. */
    val tier: String? = null,
    /** Pets only: level band matching, since parts math can't see levels. */
    val petLevel: Int? = null,
    /** Pets only: equipped pet item tag (e.g. PET_ITEM_LUCKY_CLOVER) - real resale value. */
    val petHeldItem: String? = null,
    /** Pets only: exp-from-candy count; candied pets trade at a visible discount. */
    val petCandyUsed: Int = 0,
) {
    val isPet: Boolean get() = itemId.startsWith("PET_") || petLevel != null
}

/** One filled gemstone slot: slot key ("COMBAT_0"), gem type ("JASPER"), quality ("FINE"). */
data class GemSlot(val slotKey: String, val gemType: String, val quality: String) {
    /** Bazaar product for the gem itself, e.g. FINE_JASPER_GEM. */
    val productId: String get() = "${quality}_${gemType}_GEM"

    companion object {
        val QUALITIES = setOf("ROUGH", "FLAWED", "FINE", "FLAWLESS", "PERFECT")

        /**
         * Builds gem slots from the raw gems mapping as it appears in both live NBT and Coflnet
         * flattenedNbt: "SLOT_N" -> quality, plus "SLOT_N_gem" -> type for multi-type slots
         * (COMBAT_0_gem=JASPER); single-type slots encode the type in the key (SAPPHIRE_0).
         */
        fun fromRawMap(raw: Map<String, String>): List<GemSlot> {
            val slots = mutableListOf<GemSlot>()
            for ((key, value) in raw) {
                if (key.endsWith("_gem") || key == "unlocked_slots" || key.contains('.')) continue
                val quality = value.uppercase()
                if (quality !in QUALITIES) continue
                val type = raw["${key}_gem"]?.uppercase() ?: key.substringBeforeLast('_').uppercase()
                slots.add(GemSlot(key, type, quality))
            }
            return slots
        }
    }
}

/** One sold auction: what it went for and what was on it. */
data class Comp(
    val price: Double,
    val count: Int,
    val bin: Boolean,
    val endedAtMillis: Long?,
    val attributes: ItemAttributes,
)
