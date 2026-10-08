# JetMeal MVP technical specification

## Product invariant

JetMeal is AI-first, but AI is not the database controller. A model interprets user intent and chooses a typed application operation. Deterministic application logic validates and executes that operation against the user's data.

```text
User
  -> ChatGPT / future local model / JetMeal UI
  -> Nutrition Tools contract
  -> application logic
  -> user-owned Supabase
```

The same operation boundary must be reusable by external AI, on-device AI and the normal Android UI.

## Product scope

JetMeal is primarily a personal/open-source project, not a mass-market calorie tracker. The expected users are the owner and technically capable friends who can install an APK and configure a Supabase-backed app.

The app is a native Android dashboard and manual fallback for an AI-first food log. ChatGPT remains the preferred input path because it can understand natural language and food images. Creating and maintaining a full food catalogue through the UI is not a primary product goal.

## Platform and stack

- Android only for the MVP.
- Kotlin.
- Jetpack Compose.
- Material 3, with Expressive components where appropriate and available.
- Gradle Kotlin DSL.
- Supabase Auth + PostgreSQL.

Kotlin Multiplatform, iOS and Kotlin Toolchain assumptions from the previous YapMeal project are obsolete.

## Top-level navigation

The MVP has at most four top-level destinations:

1. `Today`
2. `Week`
3. `Calendar`
4. `Settings`

No catalogue screen is required as a top-level destination.

## Today / day screen

The same day screen is used for today and historical dates selected from Calendar.

### Header

A compact calorie + protein/fat/carbohydrate summary occupies roughly the top 10–15% of the content area. The exact visualization is intentionally open: rings, bars or another compact progress treatment are acceptable.

The header shows consumed values, targets and remaining allowance. Calories use the effective calorie target for the selected day; macro targets remain the configured daily macro targets and are not redistributed by the weekly calorie-budget engine.

### Meal periods

Food entries are presented in four sections:

- `morning`
- `day`
- `evening`
- `snack`

Automatic classification uses the user's local system timezone:

- Morning: 05:00–11:59
- Day: 12:00–16:59
- Evening: 17:00–04:59
- Snack: never inferred from time alone; it requires explicit user intent such as “перекусил” or “запиши в перекус”.

Explicit user intent always overrides time inference. Examples:

- At 20:00, “с утра съел овсянку” is recorded as `morning`.
- At any time, “запиши это в перекус” is recorded as `snack`.
- If no meal period is stated, the backend derives it from `consumed_at` in the user's synchronized timezone.

`created_at` and `consumed_at` remain separate concepts.

### Entry presentation and editing

A diary row should expose the useful summary without opening details:

- name;
- amount/quantity and unit;
- calories;
- protein, fat and carbohydrates.

Tapping an entry opens a detail/edit surface. In the MVP the normal UI may edit **only the consumed quantity**. Calories/macros are recalculated proportionally from the entry's immutable nutritional-basis snapshot. The user may also soft-delete the entry.

Long-press has no MVP behavior.

AI correction operations may correct additional metadata such as consumed time or meal type when required by explicit user intent; this does not imply those fields need to be editable in the normal UI.

## Manual food logging

Manual logging is deliberately minimal.

Each meal-period section can expose an add action. The add flow is:

1. Show frequently used personal foods immediately.
2. Provide live autocomplete search over the user's personal catalogue.
3. Rank/search using name plus useful disambiguators such as brand/source.
4. Selecting a result opens a confirmation step showing the selected product/variant, its base serving and nutrition.
5. Before confirming, the user may change the consumed quantity.
6. Nothing is written until the user confirms.

Creating a brand-new catalogue food through the normal UI is not required for the MVP. ChatGPT/AI may create catalogue items through the typed tools when no suitable match exists.

## Food, variant and units

`foods` represents the semantic item, for example “Творог 5%” or “Чизбургер”.

`food_variants` represents a concrete serving/nutrition version. A variant owns:

- `serving_amount`;
- `serving_unit`;
- calories;
- protein;
- fat;
- carbohydrates;
- source/estimation metadata.

The unit is defined by the food variant, not by a global app preference. Canonical examples include:

- cottage cheese: grams (`g`), e.g. base serving 300 g;
- drink: millilitres (`ml`), e.g. base serving 250 ml;
- burger: piece/serving, e.g. base serving 1 piece.

If the stored cottage-cheese variant is 300 g and the user consumes 125 g, the diary entry records 125 g and scales the nutrition from that variant. The UI must not force the entire package/serving.

Changed package size, serving weight or nutrition creates another variant instead of rewriting old history.

## Diary nutrition snapshots

A diary entry keeps two related concepts:

1. **Immutable nutritional basis snapshot** — the amount/unit and calorie/macronutrient values used as the reference for future proportional quantity edits. When an entry comes from a catalogue variant, this is copied from that variant at log time. For an AI-only estimate, the initially logged quantity/nutrition can itself become the basis.
2. **Current consumed quantity and totals** — the quantity actually recorded in the diary and its total calories/macros.

Catalogue changes must never silently rewrite either historical basis or diary totals.

An explicit quantity correction is allowed to change the current quantity and current totals, but must calculate them from the immutable basis rather than from a potentially changed current catalogue value or repeatedly scaling already-rounded totals.

## Deletion and audit

MVP deletion is soft deletion. Deleted entries remain available to audit/undo and are excluded from active totals.

Normal food mutations are auto-write and undoable. Destructive catalogue merge/hard delete is not exposed to AI in the MVP.

## Nutrition targets

JetMeal does **not** calculate a weight-loss plan, TDEE, target weight timeline or recommended deficit. Those calculations happen outside the app.

JetMeal stores already-decided values:

- base daily calories;
- daily protein target;
- daily fat target;
- daily carbohydrate target;
- weekly redistribution limit ratio.

The user can edit these values in Settings. An AI client may also propose/update them through `update_targets`, but target changes require explicit confirmation.

## Weekly calorie budget

### Calendar boundary

A calorie-budget week is always the user's local calendar week from Monday through Sunday.

No calorie variance is carried from one calendar week into the next. Monday starts from the configured base target again.

### Goal

The engine tries to keep the week's total consumption aligned with:

```text
weekly_base_budget = base_daily_calories * 7
```

Both over-consumption and under-consumption from **recorded** completed days are redistributed across the remaining days. A completed day with no diary entries is **unknown**, not a zero-calorie day. An explicitly confirmed zero-calorie day counts as zero. Only past dates may be confirmed; logging food on such a date clears the confirmation.

### Deterministic algorithm

For a day with `n` remaining days in the current Monday–Sunday week, including the current day:

```text
cumulative_deviation = sum(known_completed_day_calories - base_daily_calories)
unclamped_target = base_daily_calories - cumulative_deviation / n
lower_bound = base_daily_calories * (1 - adjustment_limit_ratio)
upper_bound = base_daily_calories * (1 + adjustment_limit_ratio)
effective_target = clamp(unclamped_target, lower_bound, upper_bound)
```

Default `adjustment_limit_ratio` is `0.10` (±10%) and is user-configurable in Settings.

Example with a 2000 kcal base target:

- weekly base budget = 14,000 kcal;
- Monday actual = 2,500 kcal;
- cumulative deviation = +500 kcal;
- six days remain;
- Tuesday target before clamp = 2000 - 500/6 ≈ 1917 kcal.

If some completed days are missing, their unknown consumption is not used in the calculation and the UI reports incomplete weekly data. This is not a guarantee that the true weekly intake meets the target. If the known deviation is small enough, the algorithm distributes it across the remaining days. If a deviation is too large to reconcile while respecting the configured ± limit, the limit wins. The unreconciled residual may remain at the end of the week and is then discarded rather than carried into the next week.

This replaces the old YapMeal `carry_over_ratio` / fixed-kcal-clamp model.

Only calories are redistributed. Protein/fat/carbohydrate targets remain fixed daily targets.

The budget engine is deterministic application code, never an LLM calculation.

## Calendar

Calendar shows month history and provides navigation to any logged day.

For a day with entries, the cell should show at least:

- date;
- total calories;
- a visual calorie-goal status.

The intended status is a green/yellow/red continuum communicating how closely the day matched its calorie target. Exact thresholds and gradient mapping are intentionally **TBD** and must not be treated as a settled product invariant yet.

Tapping a date opens the normal day screen for that date.

Calendar MVP evaluates calorie adherence only; macro adherence does not affect the colour.

## Week screen

The Week destination remains part of the four-screen structure, but its final visualization is intentionally unresolved.

The data layer must be able to provide at least:

- each day's consumed calories;
- each day's effective calorie target;
- weekly base budget;
- total consumed calories so far;
- cumulative deviation;
- current effective target;
- remaining calendar days.

Do not invent a complex statistics UX before it is specified. A minimal implementation may present these data in a simple native layout while preserving room for later redesign.

## Settings

MVP Settings exposes:

- base daily calorie target;
- protein target;
- fat target;
- carbohydrate target;
- weekly redistribution limit percentage (default ±10%);
- signed-in email/account state and sign-out/re-authentication controls as needed.

Not user-configurable in the MVP:

- timezone: always follows the Android system timezone;
- theme: always follows the Android system light/dark mode;
- a global measurement-unit preference: units belong to food variants.

There is no expert/basic mode. Advanced settings such as the redistribution limit remain visible.

No manual JSON/CSV export/import is required for the MVP.

## Timezone behavior

The Android system timezone is authoritative. The app synchronizes its current IANA timezone identifier into `profiles.timezone` on authenticated app start/resume when it changes.

External AI tools use that stored timezone to resolve local date boundaries and infer meal periods. The user does not manually configure timezone in JetMeal.

## Authentication

Use Supabase Auth with **email and password for the existing account**. The product owner's 2026-10-06 decision supersedes the earlier Email OTP requirement.

Expected UX:

- user enters the existing account's email and password, then explicitly taps Sign in or submits with the keyboard;
- the app signs in through Supabase's Email provider;
- the login screen has only email, password and Sign in; signup, reset-password and email-code flows are outside this authorized scope;
- the password is hidden and held only in temporary UI state; never trim it, persist it, log it, or include it in saved instance/ViewModel state;
- the app persists and refreshes the authenticated session;
- normal APK/app updates do not require re-login while the stored session remains valid;
- a new phone can authenticate again with the existing email and password.

The existing Supabase account must have a password and a confirmed email. Account provisioning/password handoff is an authorized administrative action outside the mobile login UI. Email templates and SMTP are not required for this sign-in path. Saved sessions must remain isolated by project when switching between local and hosted builds.

All user data is authorized with RLS using the authenticated `auth.uid()`.

The mobile client must never contain a `service_role`/secret key.

## ChatGPT and external AI linking

ChatGPT is intended to be a primary writer to the same user-owned data, but the exact secure linking UX between an external ChatGPT tool session and a JetMeal Supabase user is still open.

Hard requirements:

- external AI must act as the intended authenticated user;
- RLS must remain effective;
- do not solve linking by embedding or exposing `service_role` credentials;
- typed Nutrition Tools remain the only public AI mutation surface.

## Local on-device AI experiment

A future on-device model is an experiment, not a production dependency.

The purpose is to benchmark whether a small local model can understand text/voice-derived intent, search the personal catalogue and invoke the same Nutrition Tools contract with acceptable latency and reliability on a high-end Android device.

The local model must not introduce a second business-logic path. It should select the same typed operations as ChatGPT.

## Matching policy

Food resolution should rank candidates using:

1. normalized text similarity;
2. brand/source match;
3. personal usage frequency;
4. recency;
5. relevant context such as meal period/time of day.

High-confidence matches may be selected automatically by AI. Low-confidence estimates are allowed when useful, but corrections must remain easy.

Manual UI autocomplete should favor frequent foods before typing and useful ranked matches while typing.

## Included in MVP

- native Android Jetpack Compose app;
- user-owned Supabase project;
- existing-account email/password authentication;
- personal food catalogue;
- food variants and natural serving units;
- calories + protein + fat + carbohydrates;
- immutable nutritional-basis snapshots plus current diary totals;
- Morning/Day/Evening/Snack grouping;
- manual add with frequent foods + autocomplete + confirmation;
- quantity-only normal UI correction and soft deletion;
- Today/day view;
- Calendar history;
- Week data and a minimal statistics surface;
- editable nutrition targets;
- deterministic Monday–Sunday calorie redistribution with configurable ± limit;
- undo/audit support;
- typed Nutrition Tools for ChatGPT and future local AI.

## Explicitly deferred / not MVP

- iOS;
- Kotlin Multiplatform;
- Play Store / App Store distribution;
- mass-market onboarding polish;
- in-app calculation of TDEE, weight-loss goal or calorie deficit;
- Health Connect / Apple Health;
- recipes and pantry inventory;
- global food database;
- micronutrients;
- native image-recognition workflow inside JetMeal;
- full offline nutrition coach;
- manual catalogue authoring as a core UI workflow;
- export/import;
- long-press shortcuts.

ChatGPT may still use images externally to identify/estimate food before invoking the typed JetMeal operations.

## Open decisions

These are intentionally unresolved and should be kept visible rather than silently invented:

1. final Week screen visualization;
2. exact calendar green/yellow/red thresholds and gradient mapping;
3. final secure user-linking UX for external ChatGPT;
4. final visual form of the Today calorie/macro progress header.

## Recommended implementation order

1. Align Supabase bootstrap schema with this specification.
2. Implement existing-account email/password Supabase Auth and persisted session handling.
3. Implement repository/domain operations and deterministic weekly-budget engine with tests.
4. Implement Today/day UI and manual logging flow.
5. Implement Calendar navigation/history.
6. Implement Settings and target updates.
7. Implement a minimal Week surface backed by the final week data contract.
8. Expose Nutrition Tools over the same application operations.
9. Connect ChatGPT end to end.
10. Benchmark an on-device model against the same tool contract.
