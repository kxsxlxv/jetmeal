# JetMeal — basic food catalogue seed (2026-10-11)

## Status

The connected personal Supabase catalogue received **426** new Russian-language
generic foods: 338 curated base foods and 88 selected USDA SR Legacy foods,
with 100 g nutritional bases, source IDs, and system audit events.
The original 558 foods, original variants and diary history were not modified.
The live catalogue after the import contains 984 foods and 1,046 variants.

The seed is **not** the full FatSecret catalogue. It intentionally prioritizes
everyday Russian-language staples, with separate records for raw versus prepared
foods, to keep searching simple and nutrition contexts intelligible.

## Provenance and licence

**Dataset:** [TempoLife Food DB](https://github.com/tempolife-app/tempo-food-db),
files `data/tempolife-foods.csv` / `data/tempolife-foods.json`.

**Licence:** [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/).
Required attribution:

> Food nutrition data from TempoLife (tempolife.app), CC-BY-4.0.

The 88 second-file entries originate from **USDA FoodData Central SR Legacy**
(public-domain United States government nutrient data), repackaged in TempoLife.
The source's curation and grouping are still attributed as above.

In JetMeal the original English name and the TempoLife numeric source ID
are retained in `food_variants.source_note`. Russian display names are editorial
translations rather than official Russian names from the source. Values apply
to **100 g** of the indicated product, including cooked/raw distinction.
They are general reference values and cannot replace product-package labels.

Seed files:

- `supabase/seeds/tempo_basic_foods_ru.csv` — 338 curated foods.
- `supabase/seeds/tempo_usda_staples_ru.csv` — 88 further staples.
- `supabase/seeds/import_tempo_basic_foods.sql` — replayable `psql` import.

**Do not bulk-copy or scrape FatSecret into this catalogue.** A separate,
licensed API integration would need to respect FatSecret terms, request
limits, attribution and cache/retention requirements. Open Food Facts
also imposes ODbL attribution and share-alike requirements on covered data;
evaluate them before merging its data into a shared database.

## Import / replay

Before importing to another JetMeal installation, take a database backup and
verify the `public.profiles.id` UUID of the intended user. With a privileged
database connection stored **outside** the repository:

```sh
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -v owner_id="<existing-profile-uuid>" \
  -f supabase/seeds/import_tempo_basic_foods.sql
```

Run this from the **repository root** so `\copy` finds both CSV files.
No Supabase service-role key or database password belongs in the Android APK,
repo or model prompt.

The script imports within one transaction, validates the owner and seed
sizes, and uses deterministic UUIDs plus existing normalized generic-food
names to avoid adding duplicates on replay. It never edits a pre-existing
product or historical diary nutrition. New records receive audit events.
On an already populated catalogue it normally inserts zero records.

The current database schema is **owner-scoped**, so this imports into a
single personal catalogue. A future shared reference catalogue would
require separate read-only tables, data API/RLS grants, search ranking,
offline-cache and typed logging changes; those are **not** implemented here.

## App behavior

After the hosted import, use the app's food-search **Обновить** action
while connected to Supabase. JetMeal already downloads and encrypts the
owner's entire catalogue; offline search will include newly cached foods.
The Kotlin repository paginates tables by 500 records.

Existing `food_measures` triggers automatically add approximate serving
conversions for recognized eggs (Среднее ≈44 g, Большое ≈50 g) and white sugar
(ч. л. ≈4 g, ст. л. ≈12 g). Exact nutrient intake is still scaled by the
variant's gram basis; no global spoon/piece conversion is assumed.

## Checks

Verified directly against the connected Supabase project on 2026-10-11:

- Food count: **558 → 984**; variants: **620 → 1,046**.
- Exactly **426** foods have the new TempoLife-source labels.
- No imported food lacks an active variant; no imported variant violates
  100 g base or nonnegative-range checks.
- No duplicate *active generic food names* detected by normalized-name grouping.
- Existing `diary_entries` count remains **57**.
- Existing owner-scoped SELECT RLS policies for foods/variants/measures remain.
- Egg and sugar measure triggers produced the expected approximate values.

Not verified: Android build/emulator flows or authenticated app-side refresh
in this session. No Android source files were changed for this seed.
