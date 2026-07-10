# Postmortem: mythic-recombed items priced far below their visible market

**Date:** 2026-07-10
**Symptom:** Ancient Shadow Assassin Helmet (mythic, recombed, 5★): panel said fair 7.77M /
lowball 6.68M while the lowest same-tier BIN sat at 14M (and the lowest tag-wide BIN at 8M).
Mythic-recombed helmets were actually *selling* for 16-25M.

## Three stacked causes

### 1. Rarity matching happened at "base tier", erasing the mythic market (valuation bug)

`Valuator.filterComps` stepped a recombed item's displayed tier down one (MYTHIC -> LEGENDARY)
and compared everything there, treating the recomb as an additive part worth the stone's resale
(~3.55M). So the mythic helmet was priced as "a LEGENDARY + 3.55M".

Reality (Coflnet sold, 500 records): LEGENDARY 5★ helmets sell ~7-9M; MYTHIC-recombed ones sell
16-25M. The market premium for the *finished* item is ~10M, not the stone's 3.55M. The 62 real
mythic sales in the pool were being averaged into 438 legendary ones and the premium vanished.

**Fix:** match the *as-is market* first - same displayed tier AND same recomb state - whenever
that pool has enough samples (sold: `minComparableSamples`; BINs: a single listing counts, since
a real listing is a clickable wall). Base-tier + stone arithmetic survives only as the fallback
for thin markets, with a note in the UI. A cross-market fallback wall must NOT cap fair value or
ceiling the offer for a recombed target (that wall isn't buyable *as this item*); for clean
targets the fallback errs high, which a cap tolerates.

### 2. Coflnet `active/bin` returns only the 10 cheapest listings (data-coverage bug)

The endpoint pages 10 at a time (`?page=N`), sorted by price. An expensive build's own market
(mythic wall at 14M+) never appears on page 0 when clean legendaries fill 8-12M. So the valuator
*never saw* a mythic listing and could not have anchored on one.

The error response for an unknown param revealed the endpoint accepts Coflnet **filters** as
query params: `?Rarity=MYTHIC` returns that tier's listings directly. (The `sold` endpoint
ignores these filters - but `pageSize=500` works there, so client-side matching covers sold.)

**Fix:** `MarketData.fetchBins` now fetches the cheapest page *plus* a `Rarity=<displayed tier>`
page and merges (dedup via `distinct()`). Cache key is `tag|RARITY`; `refresh(tag)` clears all
rarity variants. Sold fetch deepened 200 -> 500 so minority tier+recomb combos still yield a
usable exact pool.

### 3. BIN records parse their NBT under `flatNbt`, not `flattenedNbt` (parser bug)

`CoflnetJson` only read `flattenedNbt` - the **sold** endpoint's key. Active/bin records key the
same map as `flatNbt`. Result: every BIN listing parsed *bare* - stars=0, hpb=0, recomb=false -
even for "✪✪✪✪✪" listings. Ask normalization (subtracting the listing's own parts before
anchoring) was silently half-blind since the rewrite, and exact tier+recomb matching against
listings could never see recomb.

**Fix:** fall back `flattenedNbt` -> `flatNbt` when parsing. Caught by the recorded Terminator
fixture the moment exact matching relied on listing recomb flags.

## Outcome (live data, same item)

fair 7.77M -> **10.08M**, lowball 6.68M -> **~8.7M** (at +6% adj). Fair is capped by the
normalized mythic ask wall: the 15M mythic listing carries ~10M of priceable parts, so
re-expressed for this build the wall is 10.08M - i.e. paying more than that beats buying the
strictly-better 15M listing. Panel now shows "Lowest matching BIN: 14M" (same tier + recomb),
matching what a human sees when they filter the AH by hand.

## Lessons

- **Displayed tier + recomb state is a market identity, not an arithmetic detail.** Upgrade
  premiums are item-specific and often far above the upgrade item's resale value. Decompose into
  parts only when the exact market is too thin to read.
- **Know your endpoint's pagination before trusting "the lowest".** A 10-cheapest page is a
  biased sample that systematically hides expensive sub-markets.
- **Same API, different field names per endpoint** (`flattenedNbt` vs `flatNbt`). Every record
  shape assumption needs a fixture test per endpoint, not per API.

Regression tests: `ValuatorTest.recombed items price from their as-is market...`,
`ValuatorTest.cross-market BIN wall cannot cap...`, and the Terminator recorded-data test now
exercises listing-side recomb parsing. Live repro harness: `LiveDebug.shadowAssassinHelmet`.
