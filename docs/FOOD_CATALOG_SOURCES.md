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

The personal replay above is retained for compatibility with the initial
2026-10-11 import. For new installations, **prefer the global reference
import**, which does not need an account:

```sh
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -f supabase/seeds/import_tempo_reference.sql
```

Run after applying the read-only reference schema migrations. This importer
creates the same 426 shared products and variant-specific piece/egg/sugar
measures independently of any user's personal rows.

The live architecture now has a shared, authenticated, SELECT-only
`reference_foods` / `reference_food_variants` /
`reference_food_measures` catalogue, plus existing owner-scoped personal
`foods` / `food_variants` / `food_measures`. Shared variants are visible to
every signed-in user and only the selected reference food is materialized into
a personal copy when logged, including in the offline outbox replay path.
Personal entries with the same normalized Russian name take priority.
Shared reference and personal entries both keep immutable nutrition bases.

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

Reference-catalogue additions verified against live Supabase:
426 shared foods, 426 shared variants, 59 measures, and the original 57
diary rows remain. Rollback-only integration checks passed using two test users:
reference-to-private adoption, 2 bananas → 236 g, idempotent offline replay,
cross-variant rejection, and owner RLS. Additional egg checks passed for
2 medium eggs → 88 g, 3 large eggs → 150 g, and direct 75 g input.

The Android repository/search and amount editor were changed after the initial
import. Android CI/build/emulator verification has not been performed in this
session; the GitHub Android release workflow will run on changes to `app/**`.
