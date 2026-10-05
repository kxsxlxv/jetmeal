# Mandatory research sources for implementation

These are starting points for the implementation research phase. They are not frozen version pins. Always check the current page/release notes at task time and follow newer official references when available.

Prefer official Android, Kotlin, Supabase and OpenAI documentation over model memory, old blog posts, Stack Overflow answers, or random sample projects.

## Material 3 Expressive / Compose UI

### Material 3 in Compose

- https://developer.android.com/develop/ui/compose/designsystems/material3

Use this to understand the current Material 3 / Material 3 Expressive implementation, theming direction, dynamic color and current component guidance.

### Compose Material 3 release notes

- https://developer.android.com/jetpack/androidx/releases/compose-material3

This is mandatory before choosing the Material 3 dependency. JetMeal deliberately prefers the newest official Material 3 Expressive line even when it is alpha/beta/preview.

Read the API/source-breaking notes for the exact version selected.

### Compose release overview

- https://developer.android.com/jetpack/androidx/releases/compose

Use this to verify the current stable/preview state of Compose runtime/UI/foundation/animation and avoid accidental incompatible combinations.

### Material motion / MotionScheme API

- https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme

Follow related current animation/motion guidance linked from Android Developers. Do not implement motion from memory if newer Material motion APIs are available.

### Compose animation guidance

- https://developer.android.com/develop/ui/compose/animation/quick-guide

Use current guidance for appropriate animation APIs and performance characteristics.

### Compose roadmap

- https://developer.android.com/jetpack/androidx/compose-roadmap

Use this only as context for what is being stabilized/changed. Release notes/API docs remain the source of truth for APIs that actually shipped.

## Official Android sample code

### Jetpack Compose samples

- https://github.com/android/compose-samples

This repository is a required study source. Relevant samples include:

- Reply — Material 3 and adaptive UI patterns;
- Jetsnack — custom layouts/design system/animation techniques;
- Jetcaster — theming, insets, richer application UI;
- Jetchat — state, text input and animation patterns;
- JetLagged — custom data visualization/layout/graphics.

Study techniques and APIs. Do not copy a sample's visual identity.

### Now in Android

- https://github.com/android/nowinandroid
- https://github.com/android/nowinandroid/blob/main/AGENTS.md

Use it as a current example of Google's recommended Android architecture, Compose state flow, testing and production project organization. Do not mechanically copy its modularization into this small app.

### Compose Material Catalog

The Compose samples README links to the current AOSP Material Catalog. Use it to inspect how current Material components are configured and behave on a real device.

- https://github.com/android/compose-samples#readme

## Android architecture and app-quality guidance

### Architecture recommendations

- https://developer.android.com/topic/architecture/recommendations

Required topics:

- clearly defined data/UI layers;
- repositories;
- unidirectional data flow;
- ViewModel state holders;
- coroutines/Flow;
- lifecycle-aware state collection;
- when a domain layer is and is not justified.

### Adaptive layouts / large screens

Start from current Android adaptive-layout guidance and follow its current recommended APIs:

- https://developer.android.com/develop/ui/compose/layouts/adaptive

The MVP is phone-first but must not be hard-coded to a single portrait size.

### Accessibility in Compose

Start from current Android accessibility guidance:

- https://developer.android.com/develop/ui/compose/accessibility

Check semantics, touch targets, contrast, font scaling and TalkBack behavior.

### Edge-to-edge / system UI

- https://developer.android.com/develop/ui/compose/system/setup-e2e

Use current platform behavior and inset handling rather than old status/navigation-bar workarounds.

## Toolchain research

Before changing versions, verify current compatibility from official sources for:

- Android Gradle Plugin;
- Gradle;
- Kotlin;
- Compose compiler/plugin;
- Java/JDK requirements;
- Android SDK compile/target guidance;
- Activity/Lifecycle/Navigation.

Do not assume the versions already present in `gradle/libs.versions.toml` are intentional or current.

Prefer latest stable for these unless the chosen newest Material 3 Expressive line has a documented compatibility requirement that justifies a preview dependency.

## Supabase

### Android/Kotlin quickstart and AI tooling

- https://supabase.com/docs/guides/getting-started/quickstarts/kotlin

This page also documents current Supabase Agent Skills and Supabase MCP options for AI coding agents. If the environment supports skills, consider installing/using the official Supabase agent skill according to the current instructions.

### Supabase MCP

- https://supabase.com/docs/guides/getting-started/mcp

Use this for authorized hosted-project access when the Codex environment exposes/permits MCP.

### Kotlin client initialization

- https://supabase.com/docs/reference/kotlin/initializing

### Email OTP sign-in

- https://supabase.com/docs/reference/kotlin/auth-signinwithotp
- https://supabase.com/docs/reference/kotlin/auth-verifyotp

JetMeal requires code-based Email OTP. Verify the current client methods and email-template requirements.

### Local development

- https://supabase.com/docs/guides/local-development
- https://supabase.com/docs/guides/local-development/cli/getting-started
- https://supabase.com/docs/guides/local-development/cli-workflows
- https://supabase.com/docs/guides/local-development/cli/config

### Database/auth testing and linting

- https://supabase.com/docs/guides/local-development/cli/testing-and-linting

Use the current supported database testing, email-capture and linting workflow rather than inventing one.

### Security guidance

Follow current Supabase security/RLS guidance from official docs. In particular, preserve user ownership checks and never place privileged server credentials in the Android client.

## Codex / GPT-6.1 Sol working guidance

The repository prompt was written for Codex using GPT-6.1 Sol. If Codex behavior or capabilities are relevant to the task, use current official OpenAI docs rather than guessing.

Starting points:

- https://developers.openai.com/api/docs/guides/latest-model?model=gpt-6.1-sol
- https://openai.com/index/harness-engineering/
- https://openai.com/index/unrolling-the-codex-agent-loop/

Key repository principle: keep `AGENTS.md` as a map and keep deeper, maintainable knowledge in `docs/` rather than turning one instruction file into a huge monolith.

## Research output

Before implementing the first production UI screen, create `docs/implementation/RESEARCH_NOTES.md` with concise findings and the exact official sources actually used.

The note should state explicitly:

- the chosen Material 3 Expressive version/status;
- any preview APIs used;
- the chosen current navigation approach;
- selected stable toolchain versions;
- current Supabase Kotlin/Auth approach;
- any compatibility constraint that forced a non-stable dependency;
- any official sample patterns that informed the design/architecture.

Do not turn the research note into a copy of documentation.