package dev.alfaoz.lbt.market

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.alfaoz.lbt.valuation.Comp
import dev.alfaoz.lbt.valuation.GemSlot
import dev.alfaoz.lbt.valuation.ItemAttributes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Parses Coflnet auction JSON (sold + active-BIN endpoints share a shape) into the valuation
 * model. Field formats verified against live responses: `enchantments` is an array of
 * {type, level}; `flattenedNbt` holds stringly-typed modifier fields (upgrade_level "5",
 * ability_scroll "A B C", gem slots like SAPPHIRE_0/COMBAT_0 + COMBAT_0_gem).
 */
object CoflnetJson {

    fun parseAuctions(body: String, tag: String): List<Comp> {
        val root = JsonParser.parseString(body)
        if (!root.isJsonArray) return emptyList()
        return root.asJsonArray.mapNotNull { element ->
            try {
                parseAuction(element.asJsonObject, tag)
            } catch (e: Exception) {
                marketLogger.warn("Skipping unparseable Coflnet record for $tag: ${e.message}")
                null
            }
        }
    }

    private fun parseAuction(obj: JsonObject, tag: String): Comp? {
        val price = obj.double("highestBidAmount")?.takeIf { it > 0 }
            ?: obj.double("startingBid")
            ?: return null

        // Sold records key their NBT "flattenedNbt"; active/bin records use "flatNbt". Missing
        // the second made every listing parse bare (stars=0, recomb=false) - asks looked clean.
        val flat = obj.getAsJsonObject("flattenedNbt") ?: obj.getAsJsonObject("flatNbt") ?: JsonObject()
        val flatMap = flat.entrySet().mapNotNull { (k, v) ->
            if (v.isJsonPrimitive) k to v.asString else null
        }.toMap()

        val enchants = mutableMapOf<String, Int>()
        if (obj.has("enchantments") && obj.get("enchantments").isJsonArray) {
            for (e in obj.getAsJsonArray("enchantments")) {
                val eo = e.asJsonObject
                val type = eo.string("type") ?: continue
                enchants[type.lowercase()] = eo.double("level")?.toInt() ?: 0
            }
        }

        // "[Lvl 97] Ender Dragon" - pet level lives in the display name
        val petLevel = if (tag.startsWith("PET_")) {
            Regex("""\[Lvl (\d+)]""").find(obj.string("itemName").orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
        } else null

        val attrs = ItemAttributes(
            itemId = tag,
            displayName = obj.string("itemName").orEmpty(),
            count = obj.double("count")?.toInt() ?: 1,
            // Sold records carry no reforge field at all - it only survives as the item-name
            // prefix ("Withered Hyperion"). PartCatalog resolves whether that word really is a
            // reforge; non-reforge first words simply miss the stone table and price as 0.
            reforge = obj.string("reforge")?.takeIf { it.isNotBlank() && it != "None" }?.lowercase()
                ?: flatMap["modifier"]
                ?: obj.string("itemName")?.split(' ')?.firstOrNull()
                    ?.filter { it.isLetter() }?.takeIf { it.isNotBlank() }?.lowercase(),
            recombobulated = (flatMap["rarity_upgrades"]?.toIntOrNull() ?: 0) > 0,
            upgradeLevel = flatMap["upgrade_level"]?.toIntOrNull()
                ?: flatMap["dungeon_item_level"]?.toIntOrNull() ?: 0,
            dungeonItem = flatMap["dungeon_item"]?.toIntOrNull() == 1 || flatMap.containsKey("dungeon_item_level"),
            hotPotatoCount = flatMap["hpc"]?.toIntOrNull() ?: flatMap["hot_potato_count"]?.toIntOrNull() ?: 0,
            enchants = enchants,
            gems = GemSlot.fromRawMap(flatMap),
            abilityScrolls = flatMap["ability_scroll"]?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
            artOfWarCount = flatMap["art_of_war_count"]?.toIntOrNull() ?: 0,
            artOfPeace = (flatMap["artOfPeaceApplied"]?.toIntOrNull() ?: 0) > 0,
            statsBook = (flatMap["stats_book"]?.toIntOrNull() ?: 0) > 0,
            woodSingularity = (flatMap["wood_singularity_count"]?.toIntOrNull() ?: 0) > 0,
            jalapenoCount = flatMap["jalapeno_count"]?.toIntOrNull() ?: 0,
            divanPowderCoating = (flatMap["divan_powder_coating"]?.toIntOrNull() ?: 0) > 0,
            mithrilInfusion = (flatMap["mithril_infusion"]?.toIntOrNull() ?: 0) > 0,
            freeWill = (flatMap["free_will"]?.toIntOrNull() ?: 0) > 0,
            farmingForDummies = flatMap["farming_for_dummies_count"]?.toIntOrNull() ?: 0,
            polarvoid = flatMap["polarvoid"]?.toIntOrNull() ?: 0,
            bookwormBooks = flatMap["bookworm_books"]?.toIntOrNull() ?: 0,
            tunedTransmission = flatMap["tuned_transmission"]?.toIntOrNull() ?: 0,
            etherwarpMerged = (flatMap["ethermerge"]?.toIntOrNull() ?: 0) > 0,
            manaDisintegrators = flatMap["mana_disintegrator_count"]?.toIntOrNull() ?: 0,
            wetBookCount = flatMap["wet_book_count"]?.toIntOrNull() ?: 0,
            pocketSackInASack = flatMap["sack_pss"]?.toIntOrNull() ?: 0,
            drillParts = listOfNotNull(
                flatMap["drill_part_engine"], flatMap["drill_part_fuel_tank"], flatMap["drill_part_upgrade_module"],
            ),
            powerAbilityScroll = flatMap["power_ability_scroll"],
            skin = flatMap["skin"],
            dyeItem = flatMap["dye_item"],
            runes = flatMap.keys.filter { it.startsWith("RUNE_") }
                .associate { it.removePrefix("RUNE_") to (flatMap[it]?.toIntOrNull() ?: 1) },
            enrichment = flatMap["talisman_enrichment"],
            tier = obj.string("tier")?.takeIf { it.isNotBlank() && it != "UNKNOWN" },
            petLevel = petLevel,
            petHeldItem = flatMap["heldItem"],
            petCandyUsed = flatMap["candyUsed"]?.toIntOrNull() ?: 0,
        )

        return Comp(
            price = price,
            count = attrs.count,
            bin = obj.has("bin") && obj.get("bin").let { !it.isJsonNull && it.asBoolean },
            endedAtMillis = obj.epochMillis("end") ?: obj.epochMillis("start"),
            attributes = attrs,
        )
    }

    private fun JsonObject.double(key: String): Double? =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive) get(key).asDouble else null

    private fun JsonObject.string(key: String): String? =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive) get(key).asString else null

    /** Coflnet's sold endpoint sends unzoned local timestamps ("2026-07-10T01:10:52"); UTC assumed. */
    private fun JsonObject.epochMillis(key: String): Long? {
        val primitive = (if (has(key) && !get(key).isJsonNull) get(key) else null)?.asJsonPrimitive ?: return null
        if (primitive.isNumber) return primitive.asLong
        val text = primitive.asString
        return try {
            LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                Instant.parse(text).toEpochMilli()
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }
}
