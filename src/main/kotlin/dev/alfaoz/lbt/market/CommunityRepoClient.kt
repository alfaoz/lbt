package dev.alfaoz.lbt.market

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.alfaoz.lbt.valuation.EssenceCost
import dev.alfaoz.lbt.valuation.ReforgeStone
import dev.alfaoz.lbt.valuation.RepoData
import dev.alfaoz.lbt.valuation.ValueRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Fetches community-maintained Skyblock knowledge from the NEU and SkyHanni GitHub repos:
 * enchant pricing rules, essence star costs, reforge stone mappings. Disk-cached (refreshed
 * daily) so the mod prices items correctly even when launched offline.
 */
class CommunityRepoClient(private val scope: CoroutineScope, private val cacheDir: Path) {

    @Volatile var repoData: RepoData = RepoData()
        private set

    private val refreshAfter = Duration.ofHours(24).toMillis()

    private val sources = mapOf(
        "skyhanni_items.json" to "https://raw.githubusercontent.com/hannibal002/SkyHanni-REPO/main/constants/Items.json",
        "essencecosts.json" to "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/essencecosts.json",
        "reforgestones.json" to "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/reforgestones.json",
    )

    fun start() {
        loadFromDisk()
        scope.launch {
            var changed = false
            for ((file, url) in sources) {
                val path = cacheDir.resolve(file)
                val stale = !Files.exists(path) ||
                    System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis() > refreshAfter
                if (!stale) continue
                val body = Http.getString(url, timeoutSeconds = 20) ?: continue
                try {
                    JsonParser.parseString(body) // validate before persisting
                    Files.createDirectories(cacheDir)
                    Files.writeString(path, body)
                    changed = true
                } catch (e: Exception) {
                    marketLogger.warn("Repo file $file failed validation: ${e.message}")
                }
            }
            if (changed) loadFromDisk()
        }
    }

    private fun loadFromDisk() {
        try {
            repoData = RepoData(
                valueRules = readFile("skyhanni_items.json")?.let(::parseValueRules) ?: ValueRules(),
                essenceCosts = readFile("essencecosts.json")?.let(::parseEssenceCosts).orEmpty(),
                reforgeStones = readFile("reforgestones.json")?.let(::parseReforgeStones).orEmpty(),
            )
            marketLogger.info(
                "Repo data loaded: ${repoData.essenceCosts.size} essence schedules, " +
                    "${repoData.reforgeStones.size} reforges, " +
                    "${repoData.valueRules.alwaysActiveEnchants.size} always-active enchant rules",
            )
        } catch (e: Exception) {
            marketLogger.warn("Repo data load failed, using built-in defaults: ${e.message}")
        }
    }

    private fun readFile(name: String): JsonObject? {
        val path = cacheDir.resolve(name)
        if (!Files.exists(path)) return null
        return try {
            JsonParser.parseString(Files.readString(path)).asJsonObject
        } catch (e: Exception) {
            marketLogger.warn("Corrupt repo cache $name: ${e.message}")
            null
        }
    }

    companion object {
        /** SkyHanni Items.json -> the value_calculation_data block. */
        fun parseValueRules(root: JsonObject): ValueRules {
            val data = root.getAsJsonObject("value_calculation_data") ?: return ValueRules()

            val alwaysActive = data.getAsJsonObject("always_active_enchants")?.entrySet()?.associate { (name, v) ->
                val obj = v.asJsonObject
                name to ValueRules.AlwaysActive(
                    level = obj.get("level").asInt,
                    items = obj.getAsJsonArray("items").map { it.asString }.toSet(),
                )
            }.orEmpty()

            fun stringSet(key: String): Set<String> =
                data.getAsJsonArray(key)?.map { it.asString }?.toSet().orEmpty()

            // endcap_enchants_new: enchant -> [{required_level, endcap_item}]; falls back to the
            // deprecated single-entry endcap_enchants shape (which carries a "//" comment key).
            val endcaps = mutableMapOf<String, List<ValueRules.Endcap>>()
            data.getAsJsonObject("endcap_enchants_new")?.entrySet()?.forEach { (name, v) ->
                if (name == "//") return@forEach
                endcaps[name] = v.asJsonArray.map {
                    val o = it.asJsonObject
                    ValueRules.Endcap(o.get("required_level").asInt, o.get("endcap_item").asString)
                }
            }
            if (endcaps.isEmpty()) {
                data.getAsJsonObject("endcap_enchants")?.entrySet()?.forEach { (name, v) ->
                    if (name == "//" || !v.isJsonObject) return@forEach
                    val o = v.asJsonObject
                    if (o.has("required_level")) {
                        endcaps[name] = listOf(
                            ValueRules.Endcap(o.get("required_level").asInt, o.get("endcap_item").asString),
                        )
                    }
                }
            }

            return ValueRules(
                alwaysActiveEnchants = alwaysActive,
                onlyTierOnePrices = stringSet("only_tier_one_prices"),
                onlyTierFivePrices = stringSet("only_tier_five_prices"),
                endcapEnchants = endcaps,
            )
        }

        /** NEU essencecosts.json: {"TAG": {"type": "Wither", "1": 10, ..., "items": {"4": ["SKYBLOCK_COIN:10000"]}}} */
        fun parseEssenceCosts(root: JsonObject): Map<String, EssenceCost> =
            root.entrySet().mapNotNull { (tag, v) ->
                if (!v.isJsonObject) return@mapNotNull null
                val obj = v.asJsonObject
                val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val perStar = obj.entrySet().mapNotNull { (k, amount) ->
                    val star = k.toIntOrNull() ?: return@mapNotNull null
                    star to (amount.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
                }.toMap()
                val items = obj.getAsJsonObject("items")?.entrySet()?.mapNotNull { (k, arr) ->
                    val star = k.toIntOrNull() ?: return@mapNotNull null
                    star to arr.asJsonArray.map { it.asString }
                }?.toMap().orEmpty()
                if (perStar.isEmpty()) null else tag to EssenceCost(type, perStar, items)
            }.toMap()

        /**
         * NEU reforgestones.json, keyed under BOTH names a reforge travels as: the NBT modifier
         * (live items; only present in NEU when it differs from the display name, e.g. Warped =
         * "aote_stone") and the lowercase display name (Coflnet sold records only carry the
         * reforge as the item-name prefix, "Withered Hyperion").
         */
        fun parseReforgeStones(root: JsonObject): Map<String, ReforgeStone> {
            val out = mutableMapOf<String, ReforgeStone>()
            for ((stoneTag, v) in root.entrySet()) {
                if (!v.isJsonObject) continue
                val obj = v.asJsonObject
                val reforgeName = obj.get("reforgeName")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                val costs = obj.getAsJsonObject("reforgeCosts")?.entrySet()?.associate { (rarity, cost) ->
                    rarity to (cost.takeIf { it.isJsonPrimitive }?.asLong ?: 0L)
                }.orEmpty()
                val stone = ReforgeStone(stoneTag, reforgeName, costs)
                out[reforgeName.lowercase()] = stone
                obj.get("nbtModifier")?.takeIf { it.isJsonPrimitive }?.asString?.let { out[it.lowercase()] = stone }
            }
            return out
        }
    }
}
