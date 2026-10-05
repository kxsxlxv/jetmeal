# JetMeal

JetMeal is an open-source, AI-first nutrition tracker for Android.

The app is intentionally small: ChatGPT is expected to be the primary natural-language and image-capable food logging interface, while JetMeal provides a fast native dashboard, manual fallback logging, history, weekly feedback, and settings. A future on-device model can reuse the same application operations as ChatGPT.

## Stack

- Android only for the current project.
- Kotlin.
- Jetpack Compose.
- Material 3; Expressive components may be used where appropriate and available.
- Gradle Kotlin DSL.
- User-owned Supabase for Auth and PostgreSQL data.

There is no Kotlin Multiplatform or iOS target in the MVP.

## Product architecture

```text
User
  -> ChatGPT / future local AI / JetMeal UI
  -> Nutrition Tools
  -> deterministic application logic
  -> user-owned Supabase
```

AI clients interpret intent and select typed operations. They do not receive arbitrary SQL access and must never receive a Supabase `service_role`/secret key.

## MVP navigation

JetMeal has four top-level destinations:

1. **Today** — current calorie/macronutrient progress and food entries grouped into Morning, Day, Evening and Snack.
2. **Week** — weekly statistics and calorie-budget state. The exact visual treatment is still intentionally open.
3. **Calendar** — month history with per-day calories and calorie-goal status; tapping a date opens the same day view used by Today.
4. **Settings** — calorie/macronutrient targets, weekly redistribution limit and account/session information.

See [`docs/MVP_SPEC.md`](docs/MVP_SPEC.md) for the complete product and technical boundaries and [`docs/AI_TOOLS.md`](docs/AI_TOOLS.md) for the AI/application contract.

## Authentication

JetMeal uses Supabase Auth with a **one-time code sent to email (Email OTP), no password**. The user signs in once, the app persists/refreshes the session, and a new device can authenticate again by email. The Android system timezone is authoritative and is synchronized into the user's Supabase profile so external AI tools can resolve local dates and meal periods consistently.

## Supabase

[`supabase/bootstrap.sql`](supabase/bootstrap.sql) defines the initial schema, RLS policies, immutable catalogue/basis snapshots for diary nutrition, current diary totals and audit records. It is a bootstrap script for a fresh project, not generated migration history.

## Build

Debug APK:

```bash
./gradlew assembleDebug
```

Run unit tests:

```bash
./gradlew test
```

Run Android lint:

```bash
./gradlew lint
```

## Current product decisions

- Food calculation from weight-loss goals is outside JetMeal. The app stores already-decided calorie and macro targets.
- The weekly calorie target runs Monday through Sunday and does not carry residual variance into the next week.
- Over- and under-consumption are redistributed across remaining days when possible, bounded by a configurable percentage of the base daily calorie target (default ±10%).
- A food variant owns its natural quantity unit: for example grams for cottage cheese, millilitres for a drink, or one piece/serving for a burger. There is no global unit setting.
- The app always follows the Android system theme and system timezone; neither is a user-facing preference.

## Still intentionally open

The MVP specification explicitly leaves several visual/product choices open rather than inventing requirements: the final Week screen visualization, exact calendar colour thresholds, and the final UX for linking an external ChatGPT client to the same authenticated JetMeal user.