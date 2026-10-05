# JetMeal

JetMeal is an open-source, AI-first nutrition tracker for Android.

The app is intentionally small: ChatGPT is expected to be the primary natural-language and image-capable food logging interface, while JetMeal provides a fast native dashboard, manual fallback logging, history, weekly feedback, and settings. A future on-device model can reuse the same application operations as ChatGPT.

## Stack

- Android only for the current project.
- Kotlin.
- Jetpack Compose.
- Material 3 Expressive. For the design/UI stack, JetMeal intentionally prefers the newest official Expressive APIs available at implementation time, including preview/alpha APIs when appropriate.
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

AI clients interpret intent and select typed operations. They do not receive arbitrary SQL access and must never receive privileged Supabase server credentials.

## MVP navigation

JetMeal has four top-level destinations:

1. **Today** — current calorie/macronutrient progress and food entries grouped into Morning, Day, Evening and Snack.
2. **Week** — weekly statistics and calorie-budget state. The exact visual treatment is intentionally left to the first design implementation.
3. **Calendar** — month history with per-day calories and calorie-goal status; tapping a date opens the same day view used by Today.
4. **Settings** — calorie/macronutrient targets, weekly redistribution limit and account/session information.

See [`docs/MVP_SPEC.md`](docs/MVP_SPEC.md) for the complete product and technical boundaries and [`docs/AI_TOOLS.md`](docs/AI_TOOLS.md) for the AI/application contract.

## Codex implementation handoff

Coding agents must read [`AGENTS.md`](AGENTS.md) first.

The current end-to-end implementation brief is [`docs/CODEX_TASK.md`](docs/CODEX_TASK.md). Environment/Supabase testing requirements are in [`docs/ENVIRONMENT_AND_TESTING.md`](docs/ENVIRONMENT_AND_TESTING.md), and mandatory current research starting points are in [`docs/RESEARCH_SOURCES.md`](docs/RESEARCH_SOURCES.md).

The handoff deliberately requires current official research before UI implementation. The first visual/motion design is left to the agent, with Material 3 Expressive treated as a first-class product requirement rather than cosmetic polish.

## Authentication

JetMeal uses Supabase Auth with a **one-time code sent to email (Email OTP), no password**. The user signs in once, the app persists/refreshes the session, and a new device can authenticate again by email. The Android system timezone is authoritative and is synchronized into the user's Supabase profile so external AI tools can resolve local dates and meal periods consistently.

## Supabase

[`supabase/bootstrap.sql`](supabase/bootstrap.sql) defines the initial schema, RLS policies, immutable catalogue/basis snapshots for diary nutrition, current diary totals and audit records. It is a bootstrap/reference schema for a fresh project, not generated migration history.

Implementation should use the current Supabase local-development/migration workflow and a real local Supabase stack for repeatable integration tests where the environment supports it. Missing hosted-project access must be reported rather than hidden behind a mock backend.

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

The MVP specification leaves several first-pass choices open rather than prescribing a mockup: the final Week visualization, the overall visual/motion language, and the final UX for linking an external ChatGPT client to the same authenticated JetMeal user.

Calendar adherence color thresholds are not yet a permanent product invariant; a first implementation may use a clearly isolated, easily changeable provisional mapping for visual review.