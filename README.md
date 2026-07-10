# lbt

lowball tool for hypixel skyblock — see actual prices, volume, and a fair trading price, with all your auction resell fees also calculated.

<img width="442" height="262" alt="the lbt panel showing a full valuation breakdown" src="https://github.com/user-attachments/assets/7d90c3ee-8459-4d3c-a199-59b59071d2bb" />

hover any item and lbt tells you what it *actually* sells for (from real sold auctions, not wishful asks), what to offer for it, and what the flip nets after AH fees. every number is shown with its provenance — no black box.

## install

1. install **Fabric Loader** for Minecraft **26.1.2**
2. drop these in your `mods/` folder:
   - [`lbt-1.0.0.jar`](https://github.com/alfaoz/lbt/releases/latest) — from releases
   - [Fabric API](https://modrinth.com/mod/fabric-api)
   - [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
3. launch, join hypixel skyblock, hover anything.

optional: [ModMenu](https://modrinth.com/mod/modmenu) gives you a config screen in the mod list.

## how the _appraisal_ works

```
you hover an item
        |
        v
NBT is parsed: tier, recomb, stars, reforge, enchants,
gems, scrolls, pet level / held item / candy, ...
        |
        v
recent sold auctions of the same market are fetched:
same item, same displayed tier, same recomb state
(pets also level-banded and candy-matched)
        |
        v
every comparable is normalized: its own upgrades are
priced off the bazaar and subtracted, leaving what the
bare item trades for. outliers are fenced off.
        |
        v
fair value = a conservative percentile of that base
+ your item's parts at resale-realistic haircuts,
blended with - and capped by - the live BIN wall
for your build (normalized the same way)
        |
        v
lowball = fair minus a liquidity-based discount,
never above what the seller nets by just listing it
```

a 320M ask on an item that sells for 15M changes nothing — sold data outranks listings. and a mythic-recombed piece comps against mythic-recombed sales only: the finished-item premium is real and far above the stone's resale value.

## the panel

| line | meaning |
|---|---|
| `Lowball` | what to offer. fair minus a liquidity discount minus your adjustment |
| `Fair` | estimated fair value, with liquidity (high/medium/low) and sample count |
| `Resell nets` | what lands in your purse selling at fair, after real tiered AH fees |
| `Flip profit` | buy at the lowball, resell at fair, pocket this |
| `Base item` | what the bare item trades for, from n normalized sales |
| `Lowest matching BIN` | cheapest current listing of the same tier + recomb state |
| `BIN for this build` | that ask wall re-expressed for your exact modifiers |
| `Parts` | every priced modifier, largest first |

the panel is draggable and works in any inventory, trade window, or AH view. in a hypixel trade it totals the other side (including coin lump sums) and shows the exact price to pitch.

## keybinds

| default | action |
|---|---|
| `'` | toggle the item value panel |
| `[` / `]` | nudge the lowball discount down / up |
| `R` | refresh prices for the hovered item |

all rebindable in Controls → Lowball Tool. they work inside screens too, and stay out of your way while a search bar or anvil field is focused.

## adjustment

the `adj` percentage in the panel footer stacks on top of the liquidity discount. go positive to lowball harder, negative to offer closer to fair. it persists across restarts; reset it from the config screen.

## data sources

- [Coflnet](https://sky.coflnet.com) — sold auctions + active BINs
- [Hypixel API](https://api.hypixel.net) — bulk bazaar prices
- [NEU](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) & [SkyHanni](https://github.com/hannibal002/SkyHanni-REPO) community repos — essence costs, reforge stones, enchant rules

requests are rate-limited and cached (bazaar 5 min, sold 10 min, BINs 60 s — or instantly via the refresh keybind).

not affiliated with hypixel or mojang.

## License

MIT
