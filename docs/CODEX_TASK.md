# Codex implementation prompt — JetMeal MVP

You are implementing the JetMeal MVP end to end in this repository. Treat this file as the active task brief. Read the repository-level `AGENTS.md` first and follow every linked source of truth.

Active product-owner decision (2026-10-06): the Android app signs into the existing Supabase account with email and password. This supersedes the earlier Email OTP UI requirement. Keep only email/password/Sign in; no signup, password-reset or email-code UI. Passwords remain temporary input and must never be trimmed, saved or logged.

## Goal

Turn the current Android starter project into a working, polished JetMeal MVP that satisfies `docs/MVP_SPEC.md`, `docs/AI_TOOLS.md`, and the Supabase schema intent.

JetMeal is an AI-first nutrition tracker. ChatGPT is expected to be the most convenient food-logging interface; the Android app is a fast native dashboard plus manual fallback. The application must feel like a modern Android product from 2026, not a generic CRUD prototype.

Functional correctness and design quality are equally important deliverables.

## How to work

Bias toward action and carry the task through to completion. Do not stop after writing a plan or architecture proposal.

Before asking a question, complete all authorized read-only and reversible work that makes the question concrete. Ask only when missing access/information or an irreversible choice materially blocks the next dependent step.

If your environment supports subagents, use them for genuinely independent work such as current Material 3 Expressive research, current Android architecture/toolchain research, Supabase Kotlin/Auth/local-development research, or independent review/testing of an implementation slice. Merge their findings before making shared architectural decisions.

Do not use a mock backend, fake authentication, fake repository, hard-coded food list, or runtime stub to conceal unavailable infrastructure. If real integration access is missing, report that blocker immediately and follow `docs/ENVIRONMENT_AND_TESTING.md`.

Test-only fakes are fine for deterministic unit tests. They must never be presented as proof that the real Supabase integration works.

## Phase 0 — inspect and preflight before dependent implementation

Read:

- `AGENTS.md`
- `docs/MVP_SPEC.md`
- `docs/AI_TOOLS.md`
- `docs/ENVIRONMENT_AND_TESTING.md`
- `docs/RESEARCH_SOURCES.md`
- `supabase/bootstrap.sql`
- all existing Gradle/build/version-catalog files
- the existing Android source tree

Then establish facts rather than assumptions:

1. Run the current baseline Gradle build/tests that are available.
2. Identify the installed JDK, Android SDK, Gradle/AGP/Kotlin environment and any compatibility problems.
3. Check whether you can access current official Android/Supabase documentation from this environment.
4. Check whether a Docker-compatible container runtime and Supabase CLI/local stack are available.
5. Check whether a Supabase MCP/plugin or an authenticated/linkable Supabase CLI is available for hosted-project work.
6. Check whether an Android emulator/device is available for instrumentation/manual UI validation.

### Required early blocker report

Before implementing anything that depends on an unavailable external capability, tell the user what is missing.

Examples of blockers worth reporting immediately:

- no network/documentation access, making the mandatory current-version/design research impossible;
- no Docker/container runtime and no authorized hosted Supabase access, making real Supabase integration testing impossible;
- hosted Supabase work is required but no MCP/CLI authorization exists;
- no Android SDK/toolchain capable of building the intended target;
- no emulator/device when a requested verification strictly requires one.

Name the exact check you performed. State the minimal action the user must take. Do not silently substitute mocks.

If a blocker affects only one later verification path, continue independent work that can still be completed honestly. Do not claim the blocked path is verified.

## Phase 1 — mandatory current research before UI implementation

Do not design JetMeal from model memory.

Study the current official sources in `docs/RESEARCH_SOURCES.md` and follow links to current release notes/API docs as needed. The URLs in that file are starting points, not frozen version declarations.

Research at least:

- the newest available Jetpack Compose Material 3 / Material 3 Expressive release;
- the current Material 3 Expressive component set and experimental APIs;
- current Expressive typography, shape, color and motion guidance;
- the current Material motion APIs and related animation guidance;
- current Compose navigation guidance;
- adaptive/resizable layouts and current Android large-screen guidance;
- accessibility guidance for Compose;
- current Android architecture recommendations (UDF, ViewModel state, repositories, Flow/coroutines);
- current Kotlin/AGP/Gradle/JDK/AndroidX compatibility;
- current Supabase Kotlin client, existing-account email/password sign-in, session persistence, RLS and local-development workflow.

Study the current official Android sample repository and relevant examples:

- `android/compose-samples`
- Reply
- Jetsnack
- Jetcaster
- Jetchat
- JetLagged
- Now in Android
- the Compose Material Catalog

Use these to understand current APIs, layout/state/motion patterns and implementation quality. Do not copy a sample's visual identity.

Create a concise implementation research note in `docs/implementation/RESEARCH_NOTES.md` containing the date of research, important current versions/statuses discovered, preview/experimental Material 3 Expressive APIs you intend to use and why, architecture/toolchain decisions that materially affect the project, and source links.

## Technology policy

### Material 3 Expressive: newest available, stability is secondary

For Material 3 Expressive, prefer the **newest official implementation available at implementation time**, including alpha, beta, preview and experimental APIs.

This is deliberate. The product owner values the newest Android design language, animations and interaction feel more than API stability for this part of the stack.

Do not remain on stable Material 3 merely because it is stable if a newer official Expressive line materially advances the design system.

Before selecting the version:

- verify the latest official AndroidX release notes;
- read source-breaking/API-change notes for the chosen preview line;
- verify compatibility with the Compose/Kotlin/AGP/toolchain you choose;
- opt in to experimental APIs explicitly where needed;
- document the choice in `RESEARCH_NOTES.md`.

If the newest Expressive line requires compatible preview UI dependencies, those preview dependencies are also allowed.

### Everything else

Use the newest **stable and officially recommended** solution by default for Kotlin, AGP, Gradle, JDK, Lifecycle, Activity, Navigation, coroutines, serialization, Supabase client libraries, testing and other non-design dependencies.

Use a preview outside the Expressive/UI compatibility set only when there is a concrete benefit or requirement. Document the reason.

Audit the existing version catalog. Do not assume existing versions are intentional or current. Do not use deprecated APIs when a current replacement exists. Prefer AndroidX/Kotlin/platform capabilities over unnecessary third-party libraries.

## Architecture constraints

Build a modern native Android application:

- Kotlin;
- Jetpack Compose only for app UI;
- single activity;
- Gradle Kotlin DSL/version catalog;
- lifecycle-aware ViewModels;
- unidirectional data flow;
- immutable UI state where practical;
- coroutines + Flow;
- repository boundary for Supabase/data access;
- deterministic application/domain code for business rules;
- no direct network/database access from composables.

Keep the architecture proportional to the app. Start with the existing `app` module; do not create a large multi-module Clean Architecture template unless a real implementation boundary justifies it.

A small domain/use-case layer is appropriate for logic reused across multiple screens or logic that deserves isolated testing, especially weekly calorie budget calculation, meal-period assignment, quantity/nutrition scaling, food ranking/search policy where deterministic, and Nutrition Tools operations.

Supabase remains the source of truth for user catalogue, diary, nutrition targets and audit history. Do not turn local storage into a competing source of truth.

## Product behavior

Implement `docs/MVP_SPEC.md` as authoritative.

Important invariants include:

- four top-level destinations: Today, Week, Calendar, Settings;
- Today/day summary for calories and protein/fat/carbohydrates;
- diary grouped into Morning / Day / Evening / Snack;
- explicit meal-language override beats time inference;
- system timezone is authoritative and synchronized to Supabase;
- manual add uses frequent foods + live personal-catalogue search + confirmation;
- product variants own natural units (g, ml, piece/serving, etc.); there is no global unit preference;
- logging 125 g from a 300 g variant must work correctly;
- normal UI editing changes quantity only and recalculates from immutable nutritional basis snapshots;
- soft deletion;
- calorie/macro target editing;
- deterministic Monday–Sunday calorie redistribution, symmetric for over/under consumption, configurable default ±10%, no carry to the next week;
- macros are not dynamically redistributed;
- Calendar opens the same day screen for a selected date;
- Supabase existing-account email/password sign-in;
- session persistence/refresh;
- user-owned data protected by RLS;
- Nutrition Tools boundary remains suitable for ChatGPT and a future on-device model.

JetMeal must not calculate TDEE, weight-loss goals or recommended deficit inside the app.

## Design mandate

The product owner intentionally does **not** want to prescribe the first visual design. Create and implement your own coherent first design after the mandatory research.

The result should feel unmistakably current and Android-native in 2026.

Use Material 3 Expressive as a design system, not as a dependency checkbox. Explore and apply the newest appropriate official capabilities for component hierarchy, shapes and shape transitions, expressive typography, dynamic/system-aware color where appropriate, navigation surfaces, metric presentation, containers/list items/buttons/controls, motion schemes, state changes, screen transitions and interaction feedback.

Motion and feel are a large part of the product. Give meaningful interactions polished motion while protecting responsiveness and clarity. Use motion for continuity, hierarchy, state change and feedback. Avoid gratuitous animation that creates latency, visual noise or jank.

Design all four destinations as one system, not four unrelated screens.

The product owner wants to judge a working visual proposal. Do not ask for approval of every radius, spacing value, icon placement, color, animation or card layout before implementing the first pass.

### Deliberately open decisions

Where the MVP spec marks a **visual** decision as open, make a strong coherent first-pass design choice.

Where the spec marks a **product semantic/backend rule** as TBD, do not turn a speculative choice into an irreversible schema invariant. Keep provisional choices presentation-only, centralized/configurable where reasonable, and list them in the final report for review.

For example, the Week screen's visual composition is yours to design. Calendar adherence color thresholds are not yet a settled product invariant; if you need a provisional mapping for the first visual implementation, keep it isolated and easy to change, and call it out.

## Supabase implementation

Follow `docs/ENVIRONMENT_AND_TESTING.md`.

Prefer a real local Supabase environment for development and repeatable integration tests. A local Supabase stack is real Postgres/Auth/RLS infrastructure, not a mock.

Use current Supabase migration workflow rather than treating `bootstrap.sql` as permanent migration history. Preserve `bootstrap.sql` as the schema intent/reference unless you have a documented reason to update it.

Implement schema/migrations matching the current model, RLS ownership policies, profile creation/synchronization, existing-account email/password sign-in, session persistence/refresh, personal food catalogue reads/search, diary CRUD/soft delete, targets read/update, audit behavior required by the specs, timezone synchronization, and the Nutrition Tools application boundary/repository operations needed by the app.

Never ship or commit privileged server credentials.

For the Android client, use only client-safe project configuration supplied through an uncommitted developer configuration path. Provide a checked-in example/template with no personal credentials if configuration is needed.

If hosted Supabase access is unavailable, do not fake it. Prove the integration against the local stack and clearly report that hosted deployment/smoke testing remains blocked.

## Testing and verification

Build tests around product invariants rather than chasing an arbitrary coverage percentage.

### Pure Kotlin/unit tests

Cover at least:

- week starts Monday and resets Monday;
- no variance carries into the next calendar week;
- over-consumption redistributes downward;
- under-consumption redistributes upward;
- configurable symmetric clamp;
- unreconciled residual when clamp prevents full compensation;
- fixed daily macro targets;
- meal-period boundaries 05:00 / 12:00 / 17:00 and overnight evening;
- explicit Morning/Day/Evening/Snack overrides;
- quantity scaling from immutable basis snapshots, including fractional amounts such as 125 g from a 300 g variant;
- repeated quantity corrections do not accumulate rounding drift.

### Database/integration tests

Using a real local Supabase stack where available, test at least:

- migrations apply cleanly from an empty database;
- RLS prevents user A from reading/writing user B data;
- allowed owner CRUD works;
- soft-deleted diary entries are excluded from active totals/query paths;
- target writes obey ownership;
- profile creation works;
- email/password sign-in can be exercised against real local Auth; local email capture may provision/confirm test-only accounts;
- the Android repository can talk to real Supabase rather than a fake implementation.

Use current Supabase CLI testing/linting capabilities documented by Supabase.

### Android/UI verification

Run the applicable build, unit, lint and instrumentation/Compose tests supported by the environment.

Verify critical user flows:

1. first sign-in with an existing account's email/password;
2. launch with persisted session;
3. Today loads real data;
4. manual search/select/change quantity/confirm;
5. diary quantity edit;
6. soft delete;
7. Settings target edit;
8. Calendar -> historical day;
9. weekly state changes after prior-day deviation.

Exercise accessibility semantics and large font scaling. Verify edge-to-edge/insets/navigation behavior and adaptive/resizable behavior appropriate to current Android guidance.

Pay attention to animation/performance. Avoid avoidable recomposition and layout work in frequently animated paths.

## Dependency and build hygiene

Keep dependencies purposeful and current.

- Use version catalog consistently.
- Pin versions as appropriate for reproducibility.
- Keep generated/local credential files ignored.
- Do not commit IDE-local state that is not useful to the project.
- Keep the project buildable from command line.
- Add CI only if it meaningfully verifies the project and does not require unavailable private configuration for basic checks.

If baseline/startup profiles or benchmark infrastructure are justified for this small MVP, implement them only after core functionality and correctness are in place; do not let benchmarking scaffolding dominate the project.

## Completion criteria

Do not call the MVP complete until all of the following are true or explicitly reported as blocked:

- current-version/design research is documented;
- the Android app builds;
- real Supabase integration is implemented;
- existing-account email/password sign-in is implemented;
- Today, Week, Calendar and Settings are implemented;
- manual food logging works against the real data layer;
- quantity editing and soft delete work;
- targets and weekly redistribution work;
- key business rules are tested;
- local Supabase integration/RLS is tested where the environment permits;
- the first coherent Material 3 Expressive visual/motion design is implemented;
- accessibility and basic adaptive behavior have been checked;
- there are no production runtime mocks or fake success paths hiding missing infrastructure.

## Final report

When finished, give the product owner a concise evidence-based report containing:

1. what you implemented;
2. screenshots or runnable instructions if the Codex surface can provide them;
3. major visual/motion choices and the Expressive APIs used;
4. architecture/data decisions;
5. exact test/build commands you actually ran and results;
6. Supabase verification performed (local and/or hosted);
7. anything that could not be verified and the exact blocker;
8. provisional design choices worth reviewing in the first visual pass;
9. no speculative claim that an unavailable test/integration succeeded.

Proceed autonomously once preflight and research are complete. Surface real blockers early; otherwise keep working until the implementation is reviewable end to end.
