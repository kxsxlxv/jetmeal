# JetMeal — Codex instructions

This file is the repository map for coding agents. Keep it short. The detailed product contract lives in `docs/`.

## Start here

Before modifying production code, read these files in order:

1. `docs/CODEX_TASK.md` — the current implementation task and execution rules.
2. `docs/MVP_SPEC.md` — product behavior and MVP boundaries.
3. `docs/AI_TOOLS.md` — the typed AI/application contract.
4. `docs/ENVIRONMENT_AND_TESTING.md` — Supabase access, secrets, local integration testing, and blocker policy.
5. `docs/RESEARCH_SOURCES.md` — mandatory current sources for Android, Material 3 Expressive, Compose, and Supabase.
6. `supabase/bootstrap.sql` — canonical initial data model and RLS intent.

Direct user instructions for the active task take precedence. Do not silently change product invariants in the specs. If a spec is internally inconsistent, surface the exact conflict before choosing a permanent backend behavior.

## Working style

Bias toward action and follow through until the authorized task is complete. Do not stop after producing a plan.

Ask the user only when missing information, access, or an irreversible choice would materially change the result. Before asking, do every safe read-only/reversible step needed to make the blocker concrete.

If a blocker is discovered, report it early with:

- what is missing;
- what you checked;
- the exact capability/credential/access needed;
- what work can still continue independently.

Do not hide blockers by replacing real integrations with runtime mocks, fake repositories, hard-coded sample data, fake authentication, or TODO implementations. Test-only fakes/fixtures are allowed inside test source sets when they test deterministic logic and do not substitute for integration verification.

## Environment preflight is mandatory

Before dependent implementation work:

- inspect the repository and current Gradle/version catalog;
- establish a clean baseline build/test result;
- verify the Android/JDK/Gradle toolchain actually available;
- verify whether current official documentation is reachable;
- verify whether a local Supabase stack can run;
- verify whether hosted Supabase access exists through MCP/plugin or an authenticated CLI if remote work is needed.

Follow `docs/ENVIRONMENT_AND_TESTING.md`. If neither a real local Supabase stack nor authorized hosted access is available, do not implement a fake Supabase-backed runtime and claim integration is complete.

## Current technology policy

Research versions at implementation time; do not rely on model memory or stale numbers in this repository.

- **Material 3 Expressive:** use the newest available official Compose Material 3 Expressive implementation, including alpha/beta/preview/experimental APIs when that is where the newest Expressive design language lives. This is an intentional exception to normal stability preference.
- **UI/design dependencies required by the newest Expressive stack:** preview dependencies are allowed when compatibility requires them. Verify release notes and compatibility first.
- **Everything else:** prefer the newest stable, officially recommended Android/Kotlin/Gradle/AndroidX/Supabase solution unless there is a concrete reason not to.
- Do not use deprecated APIs when a current replacement exists.
- Do not adopt a third-party library merely because it is fashionable. Prefer platform/AndroidX/Kotlin solutions unless the library materially improves the implementation.

## Design is a first-class deliverable

Do not design JetMeal from memory. Complete the design research phase in `docs/CODEX_TASK.md` before implementing screens.

The first visual design is intentionally yours to propose. The product owner wants to review a coherent implemented design, not approve every card, radius, color, or animation in advance.

Use Material 3 Expressive substantively: component hierarchy, shapes, typography, color, motion, state transitions, navigation, and interaction feedback should feel current. Experimental Expressive APIs are acceptable.

Motion quality matters, but animation must preserve responsiveness and meaning. Prefer motion that communicates continuity, hierarchy, state change, or feedback. Avoid ornamental jank.

Study official examples for techniques and architecture, but do not clone the appearance of Reply, Jetsnack, Jetcaster, Jetchat, JetLagged, Now in Android, or the Material Catalog.

Accessibility remains required: semantic roles, TalkBack usability, font scaling, contrast, and appropriate touch targets must survive the expressive treatment.

## Android architecture

Use native Android Kotlin + Jetpack Compose only for the MVP.

Prefer a small, modern architecture:

- single-activity Compose app;
- unidirectional data flow;
- lifecycle-aware `ViewModel` state;
- Kotlin coroutines and `Flow`;
- repository boundary between UI and Supabase/data sources;
- deterministic domain/application code for weekly budget, meal classification, quantity scaling, and other business rules;
- one app module unless a real boundary justifies modularization.

Do not put business rules or direct Supabase access in composables. Do not recreate enterprise-style layering merely for ceremony.

## Supabase and security

Supabase is the source of truth for user food data, diary entries, nutrition targets, and audit history.

- Preserve authenticated-user RLS and ownership checks.
- Never put a `service_role`, secret key, database password, personal access token, refresh token, or CLI login token in source control or app resources.
- The Android app may use only client-safe project configuration such as the project URL and a publishable key, supplied through an uncommitted developer configuration mechanism.
- Do not assume a Codex session has the same Supabase connector access as another ChatGPT conversation. Detect access explicitly.
- Prefer a real local Supabase stack for repeatable development/integration tests; use hosted access only when authorized and needed.

## Verification requirements

A feature is not complete merely because it compiles.

At minimum, run the applicable current equivalents of:

- Gradle build/assemble;
- unit tests;
- Android lint/static checks;
- Supabase database reset/migration verification on the local stack;
- database/RLS tests;
- Email OTP/Auth integration checks;
- Compose/instrumented tests for critical flows where the environment supports them.

Business-rule tests must cover at least:

- Monday–Sunday budget boundaries and Monday reset;
- over- and under-consumption redistribution;
- configurable ± adjustment clamp and unreconciled residual behavior;
- meal-period inference and explicit override;
- quantity scaling from immutable nutritional basis snapshots;
- ownership/RLS isolation.

If a test cannot run because the environment lacks a device, container runtime, network, permission, or remote credential, say so explicitly in the final report and do not claim that test passed.

## Completion

Leave the repository buildable and internally consistent. Remove accidental placeholders and dead scaffolding. Keep secrets out of commits.

At the end, report:

- what was implemented;
- major design/architecture choices;
- versions/preview APIs intentionally chosen;
- tests actually run and their results;
- any unverified path or remaining blocker;
- any intentionally provisional design choice that the product owner should review visually.