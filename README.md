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

## Nutrition timeline

The Russian interface has one nutrition timeline with the Material 3 Button Group
**День / Неделя / Месяц / 3 месяца**. Swipe or use the arrows to move between
periods. Reset returns to the current period anchored on today. Settings opens
from the app-bar gear and provides normal back navigation; there is no bottom bar.

Day combines gradient progress rings with curved labels and one expanded meal
accordion. Week compares seven actual totals with their effective targets. Month
opens individual diary dates, and three months summarizes a calendar quarter with
compact heatmaps. Authored colors follow system light/dark mode; wallpaper colors
do not replace the JetMeal palette. All built-in UI and dates use Russian.

[`docs/implementation/DESIGN_REWORK.md`](docs/implementation/DESIGN_REWORK.md)
is the active UI contract and supersedes the original navigation/design sections
in the MVP brief. Backend and nutrition rules remain unchanged.

See the [redesign research](docs/implementation/DESIGN_REWORK_RESEARCH.md) and
[redesign verification](docs/implementation/DESIGN_REWORK_VERIFICATION.md) for
component decisions, real integration results and emulator screenshot locations.

See [`docs/MVP_SPEC.md`](docs/MVP_SPEC.md) for the complete product and technical boundaries and [`docs/AI_TOOLS.md`](docs/AI_TOOLS.md) for the AI/application contract.

## Codex implementation handoff

Coding agents must read [`AGENTS.md`](AGENTS.md) first.

The current end-to-end implementation brief is [`docs/CODEX_TASK.md`](docs/CODEX_TASK.md). Environment/Supabase testing requirements are in [`docs/ENVIRONMENT_AND_TESTING.md`](docs/ENVIRONMENT_AND_TESTING.md), and mandatory current research starting points are in [`docs/RESEARCH_SOURCES.md`](docs/RESEARCH_SOURCES.md).

The handoff deliberately requires current official research before UI implementation. The first visual/motion design is left to the agent, with Material 3 Expressive treated as a first-class product requirement rather than cosmetic polish.

## Authentication

JetMeal signs into the **existing Supabase account with email and password**, following the product owner's 2026-10-06 decision. The login screen contains only those fields and Sign in. Passwords remain temporary input and are never trimmed, saved or logged. The app persists/refreshes the authenticated session in project-specific private storage, so later updates retain the account while local/cloud builds stay isolated. The Android system timezone is synchronized into the user's profile for consistent external AI dates and meal periods.

## Supabase

[`supabase/bootstrap.sql`](supabase/bootstrap.sql) defines the initial schema, RLS policies, immutable catalogue/basis snapshots for diary nutrition, current diary totals and audit records. It is a bootstrap/reference schema for a fresh project, not generated migration history.

Implementation should use the current Supabase local-development/migration workflow and a real local Supabase stack for repeatable integration tests where the environment supports it. Missing hosted-project access must be reported rather than hidden behind a mock backend.

## Build

Automatic signed GitHub Releases and Obtainium updates:
[Android release pipeline](docs/ANDROID_RELEASES.md).

The implemented app uses AGP 9.4.1, Gradle 9.8.0, Kotlin 2.4.20, Material 3
1.5.0-alpha29 and its compatible Compose 1.13.0-alpha03 line. Install SDK 37.1;
target SDK remains 37, minimum SDK 35. This host builds with Android Studio's
JBR 25. Set `JAVA_HOME` to your installed JDK before using the wrapper.

Copy `jetmeal.local.properties.example` to `jetmeal.local.properties` and supply
your project URL and publishable key. The local file is ignored. The build
rejects privileged keys before generating app configuration. Without client
configuration, the app shows setup instructions. It never loads sample food data.

For local development, start the real Supabase stack using the pinned workflow
in [backend verification](docs/implementation/BACKEND_VERIFICATION.md). Then run
`python3 scripts/configure_local_supabase.py --cli supabase` to write only the
local client-safe configuration. Debug builds allow local HTTP; release builds
require HTTPS. Local test-only account confirmation/OTP setup uses Mailpit at `http://127.0.0.1:54324`; normal app password sign-in sends no email.

On this Windows host, Docker/CLI run inside WSL Ubuntu. Android emulator routing:

```powershell
adb -s emulator-5554 reverse tcp:54321 tcp:54321
adb -s emulator-5554 reverse tcp:54324 tcp:54324
```

With these routes, use `http://127.0.0.1:54321` in the developer configuration.
Alternatively, the Android emulator uses `10.0.2.2` for its host; physical devices
need their own host routing. Never assume Android `localhost` is desktop localhost.

Sign in with the existing account's email and password, set your existing targets in
Settings, and log foods from your own catalogue. Catalogue creation is available
through the typed `NutritionTools.createFood` application operation and its
`jetmeal_create_food` RPC. The app deliberately has no catalogue-authoring screen.

The timeline, manual quantity logging/editing, soft delete, grouped undo,
weekly redistribution and target confirmation are implemented. A past date without logged food is now **unknown**, not a zero-kcal day; users may explicitly confirm zero intake, and a later food entry automatically clears that confirmation. The Week view reports gaps rather than generating artificial calorie credit. See the dated
[research](docs/implementation/RESEARCH_NOTES.md) and
[verification report](docs/implementation/VERIFICATION.md) for actual evidence.
The existing hosted project's legacy tables have not been changed. Hosted data
migration and external ChatGPT user linking require a separate reviewed adapter;
no model or mobile client receives an admin key.

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

## Body weight and target history (2026-10-09)

- Every target change creates a local-date version. History and the week chart replay
  the goals applicable to that date; a week with a changed base target uses the sum
  of seven daily base targets instead of retrospectively applying the newest goal.
- Before the first target-history migration, earlier target changes cannot always be
  reconstructed. The migration seeds the currently saved target as the legacy baseline;
  all subsequent updates are preserved by a database trigger.
- Weight is tracked independently from calorie expenditure. The Settings screen offers
  manual weigh-ins, a trend summary, a recent measurements graph and a target-weight
  deadline. A dashed trajectory is drawn from the weight at goal creation
  to the target date. The weight chart has two modes: **Measurements** with equal
  visual spacing between distinct weighing dates (plus a seven-day plan preview),
  and **Goal** with the complete dated plan. Equal visual spacing never affects
  calculations: the plan and observed weekly pace use actual calendar dates.
  The visible fact-minus-plan delta uses the latest recorded weight and the
  goal allowance **on that measurement's date**, never the separate 7-day
  smoothed average. For an observation before the plan began, no delta is claimed.
  The weight deadline uses the native Material 3 DatePickerDialog with future-date
  constraints and UTC-to-LocalDate conversion. The same owner sees measurements
  across reinstalls because records live in Supabase with RLS.
- **PICOOC** is an optional direct cloud connection (not Google Fit or Fitbit).
  The importer follows the unofficial PICOOC protocol documented by
  [SmartScaleConnect](https://github.com/AlexxIT/SmartScaleConnect). Authenticate
  inside Jetmeal using your PICOOC account. Credentials are encrypted by Android
  Keystore on the phone and are never stored in Supabase. Once the PICOOC app has
  uploaded scale measurements to its cloud, Jetmeal can import them. An hourly
  constrained WorkManager job is best effort, not a real-time guarantee. The
  protocol is third-party and could stop working if PICOOC changes it.
- No PICOOC account credentials are included in CI; successful live PICOOC account
  authentication must be verified by the user through the app.

## Transparent weekly allowances and offline diary (2026-10-09)

- Week view exposes the full calculation: base calories, signed deviations on
  each *known completed* day, the number of remaining days, theoretical
  allowance, configurable clamp, and unreconciled residual. Missing dates are
  shown as unknown, not as zero. Macro targets remain unchanged. No workout
  calorie credit is added.
- After at least one successful online timeline refresh, JetMeal keeps the most
  recently downloaded timeline period in an Android Keystore AES-GCM encrypted
  private file (excluded from backups). That period can be read while offline;
  uncached date ranges must be loaded online before becoming available offline.
- After authenticated timeline refresh, JetMeal asynchronously downloads and
  encrypts the **entire personal food catalogue**, not merely earlier search
  results. Online and offline matching share the same Cyrillic normalization,
  confidence ordering and usage ranking. Search exposes the cached item count
  and an explicit **Обновить** action; newly added cloud foods become available
  offline after refreshing that catalogue. Initial download still requires
  a working connection to Supabase. The encrypted catalogue belongs to the
  signed-in owner and is never bundled with the APK.
- Network availability and **Supabase availability** are distinct. A validated
  Android connection is not proof the database is reachable. After a failed
  remote refresh, a banner identifies whether the network is absent or Supabase
  is unreachable, always offers manual **Повторить**, and retries after
  network changes or with bounded 3–60-second backoff. The banner disappears
  only after a genuine successful server refresh.
- Background diary reads do not await outbox replay. Transport exceptions from
  the Supabase SDK are retryable and do not silently convert unsent operations
  into permanent conflicts. Previously blocked operations can be explicitly
  rechecked with identical UUIDs and revision guards.
- Manual food log, quantity correction and soft delete are committed to a
  persistent, owner-scoped encrypted outbox first and displayed provisionally.
  Other actions (target changes, zero-day confirmations, undo of already
  synchronized actions) still require the server.
- Each request has a stable UUID, applied atomically by
  `public.jetmeal_sync_mutation`. Private receipts preserve exactly-once
  database execution even if HTTP replies are lost; duplicate retries cannot
  create extra foods or audit events. Entry revisions prevent an offline update
  from overwriting an edit made from ChatGPT. Rejected actions remain visible as
  conflicts and can be explicitly discarded after review.
- Pending edits survive process death and are retried using a unique,
  network-constrained WorkManager request and while the app is active.
  Offline entries are **not** claimed to have reached Supabase until the
  server acknowledges them. Signing out while pending edits remain is blocked.
  Queued writes possibly already sent cannot be cancelled blindly.
- Local cache and outbox are non-backed-up. Reinstallation/device migration
  cannot recover unsent offline writes; sync before uninstalling.
- The home-screen widget may lag until the outbox has synchronized.

## Diagnosing Supabase connection failures

In **Settings → Connection diagnostics**, the user can refresh and copy a local
report. The shared Ktor/OkHttp engine records the final failure of each request
at the transport boundary, before supabase-kt can strip `IOException.cause`.
Reports include a coarse route category (Auth, Diary, Catalogue, Mutation,
Other), failed network phase (DNS/TCP/TLS/request/response), allowlisted error
type, total latency and available DNS/TCP/TLS durations. An HTTP 4xx/5xx
response includes numeric status and a validated `sb-request-id` if supplied.
After a failure, the next successful request is logged as `result=recovered`.

Pooled connections legitimately omit DNS/TCP/TLS timings. Failure events may
also be missing if a request never reached OkHttp (for example, a locally
rejected operation). Android `network=wifi:validated` proves only validated
network transport, **not** that Supabase was reachable.

No endpoint URL, hostname, IP address, query string, headers, credentials,
session token, body, raw exception message or exception stack trace is retained,
even in debug builds. Recent history is capped at 160 entries in app-private
preferences. The UI shows the last 12 or last 60; **Copy report** includes all
currently stored entries. This feature has no special diagnostic permissions
and does not send logs to third-party services.

## Food-specific serving measures

Jetmeal now offers units that belong to the selected **food variant**, not a global
user preference. Catalogues retain their authoritative nutritional basis in
grams or millilitres. The Android amount editor supports that base unit plus
available measures: piece (шт.), official portion (порция), and, when relevant,
teaspoon/tablespoon or **exactly two egg sizes** (Среднее/Большое).
Switching measures preserves the edible amount; saving stores the original
measure/number alongside the converted amount and immutable nutrient snapshot.
The app remembers the preferred unit per variant.

Approximate measures are visibly marked (≈); a middle-sized egg is
approximately **44 g edible**, a large egg about **50 g edible**. A level
teaspoon of white sugar is approximately 4 g and a tablespoon about 12 g.
These conversions are *not* applied to other foods: each measure is attached
to the particular food variant. Historical diary totals never update if a
catalogue serving changes.

The additive SQL file `supabase/changes/2026-10-09-product-measures.sql`
creates RLS-scoped `food_measures`, backfills known official restaurant
portions only when the original source explicitly states
`serving=1 порция`, and seeds egg/sugar measures for applicable new
variants. It does not assume a per-100g basis is one burger. It also teaches
the existing typed `jetmeal_log_food`, `jetmeal_update_log` and offline
`jetmeal_sync_mutation` operations to resolve `measure_id` +
`measure_quantity` on the server. Measures and user-entered snapshots live
in the encrypted offline cache and persistent outbox.

## Current product decisions

- Food calculation from weight-loss goals is outside JetMeal. The app stores already-decided calorie and macro targets.
- The weekly calorie target runs Monday through Sunday and does not carry residual variance into the next week.
- Over- and under-consumption are redistributed across remaining days when possible, bounded by a configurable percentage of the base daily calorie target (default ±10%).
- A food variant owns its natural quantity unit: for example grams for cottage cheese, millilitres for a drink, or one piece/serving for a burger. There is no global unit setting.
- The app always follows the Android system theme and system timezone; neither is a user-facing preference.

## Still intentionally open

The MVP specification leaves several first-pass choices open rather than prescribing a mockup: the final Week visualization, the overall visual/motion language, and the final UX for linking an external ChatGPT client to the same authenticated JetMeal user.

Calendar adherence color thresholds are not yet a permanent product invariant; a first implementation may use a clearly isolated, easily changeable provisional mapping for visual review.
