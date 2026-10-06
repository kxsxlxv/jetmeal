# Expressive redesign research and implementation plan

Researched 6 October 2026. `DESIGN_REWORK.md` is the active UI contract; previously approved password authentication and working nutrition/backend behavior remain unchanged.

## Current official stack

The [Material 3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3) list **1.5.0-alpha29**, released 23 September 2026, as the newest Expressive line. Keep the existing pin. The [AndroidX release index](https://developer.android.com/jetpack/androidx/versions) also lists the existing Compose runtime/UI/foundation/animation **1.13.0-alpha03** preview as current. No dependency upgrade is justified for this redesign.

Release details relevant here: Button Groups graduated in alpha22; Expressive list items graduated in alpha23; alpha28 removed deprecated ToggleButton overloads and deprecated stateless sliders; alpha29 requires `onValueChange` on stateful Slider. Actual APIs below were checked against Google's cached **1.5.0-alpha29 source and sample source JARs**, not inferred from old examples. Several large API-reference pages failed the web text fetch size limit; Maven source supplied exact signatures instead.

## Component/API decisions

| Role | Current API and decision |
| --- | --- |
| Scale selector | Stable `ButtonGroup(overflowIndicator, modifier, expandedRatio, horizontalArrangement, verticalAlignment, content: ButtonGroupScope.() -> Unit)`. Its DSL provides `toggleableItem(checked, label, onCheckedChange, weight)` and `customItem(buttonGroupContent, menuContent)`. Connected custom ToggleButtons use `ButtonGroupDefaults.connectedLeadingButtonShapes()`, `connectedMiddleButtonShapes()`, `connectedTrailingButtonShapes()`, and scope `Modifier.animateWidth(interactionSource, compressionLimit)`. Overflow remains accessible at large font sizes. |
| Food grouping | Stable `SegmentedListItem(onClick, shapes, …, supportingContent, trailingContent, content)` with `ListItemDefaults.segmentedShapes(index, count)`. Group ends and interactive morphing come from the official component. |
| App identity | Compact `TopAppBar` leaves room for the stable selector and period controls. `MediumFlexibleTopAppBar(title, subtitle, …)` and `TwoRowsTopAppBar` were inspected, but their expanded identity row would compete with the daily nutrition hero. |
| Search | Stable `SearchBar(state: SearchBarState, inputField, …)` with `SearchBarDefaults.InputField(textFieldState: TextFieldState, searchBarState, onSearch, …)`; query changes feed the existing catalogue path. Avoid deprecated expanded-Boolean overloads. |
| Numeric input | Current state-based `TextField`/rounded shapes and tonal colors; minimize the visual form weight while retaining explicit quantity and target confirmation. |
| Adjustment | Stable `Slider(state: SliderState, onValueChange, onValueChangeFinished, …)` and `rememberSliderState(value, steps, valueRange)`. UI preview remains local until confirmed targets are saved. |
| Progress | `CircularWavyProgressIndicator` was inspected. The user's requested smooth gradient rings and curved labels require custom rendering, so draw arcs with Compose/Android Canvas while retaining Material semantic progress, accessible text equivalents and authored colors. Additional revolution and explicit numeric status must expose over-target progress. Wavy loading remains appropriate in loading states. |
| Typography | Current stable Typography exposes the complete emphasized type scale. Configure all regular and emphasized roles using Android system sans-serif; larger, heavier numbers and tighter display tracking give the hero priority without a downloaded font. |
| Shape/motion | `MaterialExpressiveTheme`, `MotionScheme.expressive()`, and `MaterialTheme.motionScheme.default/fast/slowSpatialSpec<T>()` for bounds/shape changes; effects equivalents for opacity/color. Configure eight shape roles including increased sizes; use official component morphing rather than a decorative shape on every surface. `MaterialShapes`/LoadingIndicator still require Expressive opt-in; `LoadingIndicator` may serve loading. |
| FAB/menu | Current stable `ToggleFloatingActionButton`/`FloatingActionButtonMenu` were inspected. Contextual meal add icons remain clearer; no global FAB replaces them. |
| Symbols | [Android's current icon guidance](https://developer.android.com/develop/ui/compose/graphics/images/material) recommends Material Symbols Android XML resources and advises against the older material-icons artifact. Download the official Rounded 24px defaults from Google's `fonts.gstatic.com` endpoints; tint them with Compose `Icon`. No Canvas action/navigation symbols. |

## Short composition plan

Use one timeline with a compact JetMeal app bar, a shape-responsive `День / Неделя / Месяц / 3 месяца` Button Group, and period controls tied to one horizontal pager. Settings opens as a secondary destination with back navigation. Russian labels, dates, number formatting and accessibility descriptions are part of the redesign.

The day hero pairs a dominant calorie number with a green gradient ring and curved supporting label. Three smaller violet/amber/coral macro rings form a wrapping group. One meal accordion expands at a time; its food rows are a segmented group. Week uses an actual-versus-target chart, month uses expressive date states, and three months offers compact patterns rather than three tall calendars. Add/edit sheets retain real data and explicit writes. Settings uses a compact target hierarchy and percentage Slider with confirmation.

Use authored warm-paper/forest light colors and deep-forest dark surfaces, selected solely by system light/dark mode. Nutrition tones have separate readable foreground/container pairs. Dynamic wallpaper colors do not alter JetMeal. Progress colors communicate value but are accompanied by numeric/status labels.

The implemented palette's primary-button, secondary-text, and four nutrition foreground/container pairs were calculated with the WCAG relative-luminance formula in both modes: minimum **6.72:1**, maximum **10.73:1** among these checked pairs. This is a palette check; screenshot/TalkBack review still needs to check the final composition and actual text placement.

## Official sources and techniques studied

- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3): authored color roles, system light/dark behavior, current Expressive design-system integration.
- [MotionScheme](https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme) and [Compose animation guidance](https://developer.android.com/develop/ui/compose/animation/quick-guide): spatial versus effects motion; content size and visibility animation.
- [Compose samples](https://github.com/android/compose-samples), particularly [JetLaggedScreen](https://github.com/android/compose-samples/blob/main/JetLagged/app/src/main/java/com/example/jetlagged/JetLaggedScreen.kt) and [Jetsnack theme](https://github.com/android/compose-samples/blob/main/Jetsnack/app/src/main/java/com/example/jetsnack/ui/theme/Theme.kt): adaptive flowing composition and cohesive design-system boundaries. The visual appearance is original to JetMeal.
- Official Material3 alpha29 sample JAR: `ButtonGroupWithCustomItemSample`, `SingleSelectConnectedButtonGroupSample`, list expansion/grouped-shape samples, stateful search, text-field and slider examples. Inspecting the pinned samples prevents using removed alpha APIs.
- [Material Symbols repository/license](https://github.com/google/material-design-icons): consistent Rounded symbols, Apache 2.0 resources.
- [Per-app language preferences](https://developer.android.com/guide/topics/resources/app-languages): because the app minimum is API35, use the supported Android framework `LocaleManager.applicationLocales` in `Application.onCreate` to choose Russian before UI creation. A Russian-only `localeConfig` advertises the supported language; Compose/Material built-in resource strings inherit this app configuration without changing system language, timezone or light/dark mode. No AppCompat or deprecated resource mutation is required.

Build/tests and emulator visual review are acceptance work, not assumed results of this research. Current behavior/backend correctness remains covered by the existing real integration paths.

## Changes after rendered review

The stock weighted `toggleableItem` labels clipped at large font scale, despite
remaining discoverable in merged accessibility semantics. The implemented selector
therefore uses the official connected `ToggleButton` shapes inside `ButtonGroup`
custom items, compact padding and the group's press-width motion. At constrained
width/font combinations, the same four choices occupy two rows. All four remain
visible; a rendered-glyph check guards against clipping.

Calendar weeks split into two labelled rows below 384dp or at enlarged font size.
The week graph retains seven bars, while constrained layouts provide full-width
segmented day rows for readable values and adequate touch targets. Ring endpoints
and over-target arcs were refined after screenshot inspection. Zero effective
calorie allowance is a legitimate domain result and has an explicit, finite UI.
