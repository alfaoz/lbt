# lbt

lowball tool for hypixel skyblock — see actual prices, volume, and a fair trading price, with all your auction resell fees also calculated.

<img width="442" height="262" alt="the lbt panel showing a full valuation breakdown" src="https://github.com/user-attachments/assets/7d90c3ee-8459-4d3c-a199-59b59071d2bb" />

hover any item and lbt tells you what it *actually* sells for, what to offer for it, and what the flip nets after AH fees.

## install

get it on modrinth: [modrinth.com/project/lbt](https://modrinth.com/project/lbt)

dependencies:
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)

optional: [ModMenu](https://modrinth.com/mod/modmenu) gives you a config screen in the mod list.

## the appraisal

```
recent sold auctions of the same market are fetched
        |
        v
every comparable is normalized: its own upgrades are
priced off the bazaar and subtracted, leaving what the
bare item trades for.
        |
        v
fair value is calculated by a conservative percentile 
of that base + your item's parts at realistic cuts,
blended with, and capped by, the BIN prices of the said
item for your build
        |
        v
lowball price = fair - liquidity-based discount,
```

## adjustment

the `adj` percentage in the panel footer stacks on top of the liquidity discount.

## data sources

- [Coflnet](https://sky.coflnet.com) for sold auctions + active BINs
- [Hypixel API](https://api.hypixel.net) for bulk bazaar prices
- [NEU](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) & [SkyHanni](https://github.com/hannibal002/SkyHanni-REPO) community repos for essence costs, reforge stones, enchant rules

!!! not affiliated with hypixel or mojang.

## License

GNU GPL V3
