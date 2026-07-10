package dev.alfaoz.lbt.util

import com.google.gson.JsonParser
import dev.alfaoz.lbt.valuation.GemSlot
import dev.alfaoz.lbt.valuation.ItemAttributes
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

/**
 * Live ItemStack -> ItemAttributes. Hypixel flattened the old "ExtraAttributes" sub-compound
 * directly onto the CUSTOM_DATA root in this MC version; key names verified against SkyHanni's
 * SkyBlockItemModifierUtils (their production parser for the same data).
 */
object SkyblockItem {

    /**
     * Type-tolerant numeric read: Hypixel serializes some flags as bytes, some as ints, and
     * CompoundTag.getIntOr silently returns the default on any type mismatch - which is how a
     * recombed item lost its recomb (rarity_upgrades as a byte read as 0). Numbers of any tag
     * type and numeric strings all resolve.
     */
    private fun CompoundTag.num(key: String): Int? {
        val tag = get(key) ?: return null
        return when (tag) {
            is net.minecraft.nbt.NumericTag -> tag.intValue()
            is net.minecraft.nbt.StringTag -> tag.value.toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    private fun CompoundTag.numOr(key: String, default: Int): Int = num(key) ?: default

    fun fromStack(stack: ItemStack): ItemAttributes? {
        val root = rootOf(stack) ?: return null
        val itemId = root.getStringOr("id", "")
        if (itemId.isBlank()) return null

        val enchants = mutableMapOf<String, Int>()
        for (key in root.getCompoundOrEmpty("enchantments").keySet()) {
            enchants[key.lowercase()] = root.getCompoundOrEmpty("enchantments").numOr(key, 0)
        }

        // gems live in a sub-compound with the same key scheme Coflnet flattens: SLOT_N -> quality
        // (or a {quality: ...} sub-compound for newer items), SLOT_N_gem -> gem type.
        val gemsRaw = mutableMapOf<String, String>()
        val gemCompound = root.getCompoundOrEmpty("gems")
        for (key in gemCompound.keySet()) {
            gemCompound.getString(key).ifPresent { gemsRaw[key] = it }
            gemCompound.getCompound(key).ifPresent { sub ->
                sub.getString("quality").ifPresent { gemsRaw[key] = it }
            }
        }

        val runes = mutableMapOf<String, Int>()
        val runeCompound = root.getCompoundOrEmpty("runes")
        for (key in runeCompound.keySet()) {
            runes[key.uppercase()] = runeCompound.numOr(key, 1)
        }

        val abilityScrolls = root.getListOrEmpty("ability_scroll").let { list ->
            (0 until list.size).mapNotNull { i -> list.getString(i).orElse(null) }
        }

        // Pets carry id="PET"; the species lives in petInfo JSON. Market tags (Coflnet, NEU)
        // are PET_<TYPE> - querying the literal "PET" finds nothing.
        val petInfo = root.getString("petInfo").orElse(null)
        var resolvedId = itemId
        var petTier: String? = null
        var petLevel: Int? = null
        var petHeldItem: String? = null
        var petCandyUsed = 0
        if (petInfo != null) {
            try {
                val obj = JsonParser.parseString(petInfo).asJsonObject
                obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.let { resolvedId = "PET_$it" }
                petTier = obj.get("tier")?.takeIf { it.isJsonPrimitive }?.asString
                petHeldItem = obj.get("heldItem")?.takeIf { it.isJsonPrimitive }?.asString
                petCandyUsed = obj.get("candyUsed")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                // petInfo carries exp, not level; the level in the display name is close enough
                petLevel = Regex("""\[Lvl (\d+)]""").find(stack.hoverName.string)
                    ?.groupValues?.get(1)?.toIntOrNull()
            } catch (ignored: Exception) {
            }
        }

        val tier = petTier ?: rarityFromLore(stack)

        return ItemAttributes(
            itemId = resolvedId,
            displayName = stack.hoverName.string,
            count = stack.count,
            reforge = root.getString("modifier").orElse(null),
            recombobulated = root.numOr("rarity_upgrades", 0) > 0,
            upgradeLevel = root.numOr("upgrade_level", root.numOr("dungeon_item_level", 0)),
            dungeonItem = root.numOr("dungeon_item", 0) > 0 || root.contains("dungeon_item_level"),
            hotPotatoCount = root.numOr("hot_potato_count", 0),
            enchants = enchants,
            gems = GemSlot.fromRawMap(gemsRaw),
            abilityScrolls = abilityScrolls,
            artOfWarCount = root.numOr("art_of_war_count", 0),
            artOfPeace = root.numOr("artOfPeaceApplied", 0) > 0,
            statsBook = root.numOr("stats_book", 0) > 0,
            woodSingularity = root.numOr("wood_singularity_count", 0) > 0,
            jalapenoCount = root.numOr("jalapeno_count", 0),
            divanPowderCoating = root.numOr("divan_powder_coating", 0) > 0,
            mithrilInfusion = root.numOr("mithril_infusion", 0) > 0,
            freeWill = root.numOr("free_will", 0) > 0,
            farmingForDummies = root.numOr("farming_for_dummies_count", 0),
            polarvoid = root.numOr("polarvoid", 0),
            bookwormBooks = root.numOr("bookworm_books", 0),
            tunedTransmission = root.numOr("tuned_transmission", 0),
            etherwarpMerged = root.numOr("ethermerge", 0) > 0,
            manaDisintegrators = root.numOr("mana_disintegrator_count", 0),
            wetBookCount = root.numOr("wet_book_count", 0),
            pocketSackInASack = root.numOr("sack_pss", 0),
            drillParts = listOfNotNull(
                root.getString("drill_part_engine").orElse(null),
                root.getString("drill_part_fuel_tank").orElse(null),
                root.getString("drill_part_upgrade_module").orElse(null),
            ),
            powerAbilityScroll = root.getString("power_ability_scroll").orElse(null),
            skin = root.getString("skin").orElse(null),
            dyeItem = root.getString("dye_item").orElse(null),
            runes = runes,
            enrichment = root.getString("talisman_enrichment").orElse(null),
            tier = tier,
            petLevel = petLevel,
            petHeldItem = petHeldItem,
            petCandyUsed = petCandyUsed,
        )
    }

    private val RARITIES = listOf(
        "VERY SPECIAL", "SPECIAL", "DIVINE", "MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON",
    )

    /**
     * Rarity isn't in NBT - it's the shouty word on the item's last lore line
     * ("§6§lLEGENDARY DUNGEON BOW"). Scanned bottom-up; multi-word rarities checked first so
     * "VERY SPECIAL" doesn't match as "SPECIAL".
     */
    private fun rarityFromLore(stack: ItemStack): String? {
        val lore = stack.get(DataComponents.LORE)?.lines ?: return null
        for (line in lore.asReversed()) {
            val text = line.string.uppercase()
            for (rarity in RARITIES) {
                if (text.contains(rarity)) return rarity.replace(' ', '_')
            }
        }
        return null
    }

    private fun rootOf(stack: ItemStack): CompoundTag? {
        if (stack.isEmpty) return null
        return stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.takeIf { it.contains("id") }
    }

    /** One-line explanation of exactly where NBT parsing stops, for diagnosing why an item didn't resolve. */
    fun diagnose(stack: ItemStack): String {
        val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return "no CUSTOM_DATA component on stack"
        val root = customData.copyTag()
        if (!root.contains("id")) return "CUSTOM_DATA present but no 'id' key; root keys=${root.keySet()}"
        val attrs = fromStack(stack)
        return "ok: id=${root.getStringOr("id", "")} tier=${attrs?.tier} recomb=${attrs?.recombobulated} " +
            "stars=${attrs?.upgradeLevel} reforge=${attrs?.reforge} enchants=${attrs?.enchants?.size} " +
            "(raw rarity_upgrades tag=${root.get("rarity_upgrades")?.javaClass?.simpleName})"
    }
}
