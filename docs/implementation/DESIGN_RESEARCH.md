# JetMeal design research

Research date: 2026-10-06. Official release notes, Google Maven artifacts and official sample source were inspected before implementing UI.

## Versions and compatibility

The [Material 3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3) and [Google Maven metadata](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/maven-metadata.xml) agree that **1.5.0-alpha29**, released 2026-09-23, is the newest published official implementation. Material 3 stable remains 1.4.0. This project deliberately selects alpha29 for the newest Expressive components.

The [Compose overview](https://developer.android.com/jetpack/androidx/releases/compose) lists core stable 1.12.1 and preview 1.13.0-alpha03. The [alpha29 Android POM](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.5.0-alpha29/material3-android-1.5.0-alpha29.pom) requires core UI, runtime, foundation and animation 1.13.0-alpha01. Select the newer matching 1.13.0-alpha03 core line; these UI previews are a necessary compatibility exception, not a general dependency policy.

Direct inspection of the published alpha29 AAR's `META-INF/com/android/build/gradle/aar-metadata.properties` established `minCompileSdk=37`, `minCompileMinorSdk=0`, and `minAndroidGradlePluginVersion=9.1.0`. The separate A2UI artifact's 37.1 release note must not be mistaken for the core Material 3 requirement. The subsequent Gradle AAR check established that the selected newer Compose core 1.13.0-alpha03 UI/foundation/tooling artifacts require compile SDK **37.1**. Therefore the complete stack uses SDK 37.1 and AGP 9.4.1, even though Material 3 itself requires only 37.0.

Relevant changes in the preview line: use `MaterialTheme.motionScheme` because `LocalMotionScheme` was removed; use current stateful Slider APIs with required `onValueChange` if a slider is introduced; the old stateless slider overloads were deprecated/hidden. Top-app-bar scroll behavior now uses `ScrollableState`. Button, typography, shapes and several navigation APIs have graduated from Expressive opt-in even though the artifact itself remains alpha.

## Theme, motion and components

The pinned [alpha29 source archive](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.5.0-alpha29/material3-android-1.5.0-alpha29-sources.jar) was inspected for `MaterialTheme`, `MotionScheme`, `Typography`, `Shapes`, `Button`, `ListItem`, short navigation bar, wide navigation rail and loading indicator signatures. Use `MaterialExpressiveTheme` with explicit system light/dark and dynamic color on Android 12+, an authored fallback palette, and `MotionScheme.expressive()`. Emphasized headline/title styles distinguish key nutrition values. The shape scale includes increased large/extra-large and XXL containers.

Use morphing `Button(..., shapes = ButtonDefaults.shapes())`, current clickable `ListItem(onClick=..., content=...)`, `ShortNavigationBar` and `WideNavigationRail`. `LoadingIndicator` remains `ExperimentalMaterial3ExpressiveApi`; opt in locally. Bottom sheets and calendar controls use the current Material APIs rather than custom gesture implementations.

The [official Androidify walkthrough](https://android-developers.googleblog.com/2025/05/androidify-building-delightful-ui-with-compose.html) demonstrates the Expressive theme, spring motion and meaningful shape feedback. Use spatial specs for position/size and effects specs for opacity/color. The [animation guide](https://developer.android.com/develop/ui/compose/animation/quick-guide) distinguishes true removal (`AnimatedVisibility`) from alpha-only hiding and recommends drawing/layer phases for animated visual properties. Animate screen/state continuity and progress updates without delaying actions. The [roadmap](https://developer.android.com/jetpack/androidx/compose-roadmap) is context only; shipped signatures above determine implementation.

Material's motion/typography website was reachable but its JavaScript-only content was not readable through text browsing. The current Android guide, published API/source and official Androidify implementation provide the inspected guidance; no unread Material website content is represented as studied.

## Proposed first visual pass

JetMeal uses a calm food-oriented palette, bold numerical hierarchy, compact nutrition bands and grouped rounded diary rows. All four destinations share the same spacing, typography, container hierarchy and system color behavior.

- Today: a compact calorie summary with consumed/target/remaining values, one meaningful progress bar, and three macro summaries; four meal groups with explicit add buttons. The diary remains the primary content. Historical dates use this same screen.
- Add/edit: a native modal surface. Frequent foods appear immediately; live catalogue matches include brand/source/base serving. Selection leads to a quantity and scaled-nutrition confirmation; edit exposes quantity only. Product units remain fixed to the variant. A delete action reports success only after persistence and exposes undo.
- Week: seven accessible labeled calorie/target rows with a weekly budget/deviation summary and visible clamp/residual explanation. This is intentionally the minimal provisional visualization.
- Calendar: month navigation and generous date targets; logged cells include kcal and calorie adherence. The provisional presentation mapping uses absolute deviation within 10% as green, within 25% as amber, otherwise red, isolated in UI code. Text/semantics also communicate status. These are not backend invariants.
- Settings: visible calorie, macro and adjustment-limit fields plus account controls; saving presents a concrete review. No theme/timezone/unit preferences or inferred nutrition targets.

## Sample study and architecture

The [official samples README](https://github.com/android/compose-samples/blob/main/README.md) and the following implementation files were read through the selected GitHub connector:

| Sample | Inspected source | Technique applied |
| --- | --- | --- |
| Reply | [ReplyApp.kt](https://github.com/android/compose-samples/blob/main/Reply/app/src/main/java/com/example/reply/ui/ReplyApp.kt) | Adapt navigation and content to available space/posture, keep navigation actions outside screen content. |
| Jetsnack | [Home.kt](https://github.com/android/compose-samples/blob/main/Jetsnack/app/src/main/java/com/example/jetsnack/ui/home/Home.kt) | Maintain coherent design tokens and navigation-state continuity; avoid its custom navigation appearance. |
| Jetcaster | [Home.kt](https://github.com/android/compose-samples/blob/main/Jetcaster/mobile/src/main/java/com/example/jetcaster/ui/home/Home.kt) | Lifecycle collection, immutable screen state, adaptive grid, native Expressive toolbar; do not import its artwork-driven visual identity. |
| Jetchat | [UserInput.kt](https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/UserInput.kt) | Hoisted text input/focus and keyboard-aware layout. |
| JetLagged | [JetLaggedScreen.kt](https://github.com/android/compose-samples/blob/main/JetLagged/app/src/main/java/com/example/jetlagged/JetLaggedScreen.kt) | Responsive flowing content, constrained widths, lifecycle-aware collection and safe insets. |
| Now in Android | [NiaApp.kt](https://github.com/android/nowinandroid/blob/main/app/src/main/kotlin/com/google/samples/apps/nowinandroid/ui/NiaApp.kt), [AGENTS.md](https://github.com/android/nowinandroid/blob/main/AGENTS.md) | Actual current code uses Nav3 `NavDisplay`, `entryProvider`, adaptive scenes, lifecycle state and inset consumption. AGENTS still mentions Nav2; current code/docs govern selection. Do not copy its module graph. |
| Material Catalog | [Theme.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/integration-tests/material3-catalog/src/main/java/androidx/compose/material3/catalog/library/ui/theme/Theme.kt), [Components.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/integration-tests/material3-catalog/src/main/java/androidx/compose/material3/catalog/library/model/Components.kt) | Explicit Expressive theme, dynamic/system modes, font scaling and component examples. Source inspected; on-device behavior must still be verified. |

The [current architecture recommendations](https://developer.android.com/topic/architecture/recommendations) favor repositories, UDF, ViewModel StateFlow, lifecycle collection and a single activity. Use a small domain boundary for reusable arithmetic and typed operations. [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) is now the official recommendation; retain serializable destination keys and a real back stack.

## Accessibility and adaptive acceptance

Use native semantic roles, readable units/labels, minimum 48dp interaction targets and appropriate on-color roles. [Compose API defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) warns that automatic touch expansion can overlap neighboring targets; calendar targets receive explicit space. [Accessibility guidance](https://developer.android.com/develop/ui/compose/accessibility) requires scalable content and checked semantics. Never make calendar status color-only.

Use [available window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes), not physical-device/tablet checks. Compact width gets bottom navigation; wider windows get rail navigation and constrained content. Navigation reads `LocalWindowInfo.current.containerSize` through `LocalDensity`, so resizable app windows determine the 600dp switch; it does not use device configuration screen dimensions. The app minimum SDK is 35, so dynamic color requires no obsolete Android 12 availability guard. Large fonts can increase summary height and wrap macro content instead of clipping it to a hard percentage. Follow [edge-to-edge setup](https://developer.android.com/develop/ui/compose/system/setup-e2e), consuming scaffold insets once and accounting for IME in input surfaces. Verify compact/expanded widths, large fonts, TalkBack ordering and real data flows on an available device.


