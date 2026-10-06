# UI redesign verification — 6 October 2026

`DESIGN_REWORK.md` was the active UI contract. The complete redesign runs in the
real Android application against Supabase. Production repositories, domain rules,
typed Nutrition Tools, SQL schema and RLS were preserved. There is no runtime
sample catalogue, fake authentication or placeholder integration.

## Environment and baseline

Before dependent implementation, the existing debug build, unit tests and Android
lint passed. The available toolchain was checked: Android Studio JBR 25.0.3,
Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20, SDK 37.1, minimum API 35 and target API 37.
Official Android/Material documentation was reachable. Exact component signatures
were additionally inspected in Google's source and sample JARs.

A real local Supabase stack ran in WSL Ubuntu with Docker 29.8 and Supabase CLI
2.119. Local database reset applied all seven existing migrations; all 64 pgTAP
database/RLS assertions passed, and database lint reported no errors. The real
`scripts/verify_local_supabase.py` integration checks passed: two-user Email OTP,
session refresh/revocation, authenticated ownership isolation, diary mutations,
immutable-basis scaling, soft delete/undo, confirmed targets, audit history and
anonymous rejection. Hosted project access was verified read-only. This UI task
did not modify the hosted project.

Android tests and screenshots used only `emulator-5554` / Medium_Phone_API_37.0.
The connected physical phone was not installed to or used for tests.

## Implementation and design choices

- One nutrition timeline with **День / Неделя / Месяц / 3 месяца**, official
  connected Material Button Groups, horizontal period paging, matching arrow
  actions and a reset anchored on today. Settings is a secondary Navigation 3
  destination reached through the app-bar gear; bottom navigation is removed.
- The day hero uses a smooth gradient calorie ring and curved text. Each macro
  has its own gradient ring and curved label. Over-target states retain numerical
  magnitude. A zero effective calorie allowance is handled explicitly without
  infinite percentages. These rings use Canvas because standard progress
  components do not provide the requested curved labels and gradients.
- Four compact meal summaries form an animated, one-at-a-time accordion.
  Historical default selection uses the latest actual entry; today's selection
  uses the existing meal-period rule. Food rows use official `SegmentedListItem`
  shapes. Contextual add actions retain explicit meal selection.
- Search and quantity sheets use current search/input APIs and the real personal
  catalogue. Quantity previews use immutable nutritional bases; only explicit
  confirmation writes. Quantity-only corrections, soft delete and undo remain.
- Week compares seven actual totals with effective targets. Month presents daily
  kcal, numeric/non-color adherence markers and date navigation. Three months
  uses compact calendar-quarter heatmaps and accessible day-selection menus.
- Settings uses tonal rounded inputs, a stateful adjustment Slider and exact
  numeric input. Reviewing and confirming new targets remain separate steps.
- Authored warm-paper/forest light and deep-forest dark palettes follow system
  mode. Dynamic wallpaper colors are disabled. Regular/emphasized type roles,
  official Rounded Material Symbols and Expressive spatial/effects motion provide
  a consistent visual language. Icon sources and Apache license are included in
  `third_party/material-symbols/`.
- Built-in UI, Material resource strings, dates, numbers and accessibility labels
  are Russian. The supported framework per-app locale API leaves system language,
  timezone and light/dark preferences intact.

The existing ViewModel was adapted only for timeline state, real multi-month
reads and derived calendar targets through the existing weekly-budget function.
Session handling and repository contracts remain unchanged. Pager state survives
Activity recreation without advancing the selected period again.

## Current versions and preview APIs

Official research confirmed the existing **Material 3 1.5.0-alpha29** and compatible
**Compose 1.13.0-alpha03** pins; no dependency upgrade was needed. Preview use is
intentional under the repository's Expressive policy. The implementation uses
`MaterialExpressiveTheme`, Expressive `MotionScheme`, connected ToggleButton
shapes/press-width motion, segmented lists, current search fields and stateful
Slider APIs; loading uses the experimental Expressive `LoadingIndicator`.

The researched APIs, official links and composition plan are recorded in
[DESIGN_REWORK_RESEARCH.md](DESIGN_REWORK_RESEARCH.md).

## Automated verification

| Verification | Result |
| --- | --- |
| Initial debug assemble / unit tests / lint | Passed |
| Final JVM unit suite | 50 tests passed |
| Full emulator UI/integration suite | 17 tests passed |
| Final critical UI repeat after visual fixes | 5 tests passed |
| Final viewport suite | All 5 configurations passed |
| Real local migration reset / SQL-RLS tests / database lint | Passed / 64 assertions passed / no errors |
| Real local two-user OTP/application integration script | Passed |
| Final cloud debug and release assemble / Android lint | Passed; lint: 0 errors, 0 warnings |

The full emulator suite includes authentication/form tests, accordion and
over-target/zero-allowance tests, search/quantity tests, real two-user repository
integration and the end-to-end MainActivity flow. The final critical repeat covers
the end-to-end flow and four day component tests. End-to-end verification signs
in against real local Auth, confirms targets, logs/edits quantities, deletes and
undoes, cancels real requests without false connection warnings, tests arrows and
swipes in every scale, resets each period, recreates the Activity, and opens days
from both month and quarter views.

Viewport tests also assert Russian Material resources, persisted real session,
visible selectors, at least 48dp selector targets and actual rendered glyph bounds
(not merely accessibility text). Tests use isolated local test accounts and
real catalogue/diary fixtures created through typed operations. Deterministic
component fixtures are limited to test source sets.

The over-target screenshot additionally waits for the newly written real diary
entry to appear in ViewModel state and for the exact overage label before capture;
an initial capture had raced an asynchronous refresh and shown the previous frame.

## Rendered visual review

Actual emulator screenshots were captured and inspected, then defects were fixed
and the affected scenarios were rerun. Reviewed states include populated day,
empty meal, over-target day, historical day, expanded/collapsed accordions,
search/add preview, quantity edit, target confirmation, week, month, quarter and
settings. Both light and dark mode, enlarged fonts, narrow and wide layouts were
reviewed.

| Configuration | Resolution / density | Font scale | Theme |
| --- | --- | --- | --- |
| Light phone | 1080×2400 / 420dpi | 1.0 | Light |
| Dark phone | 1080×2400 / 420dpi | 1.0 | Dark |
| Large font phone | 1080×2400 / 420dpi | 1.5 | Light |
| Narrow phone | 840×1920 / 420dpi, 320dp wide | 1.5 | Light |
| Wide/resizable | 1800×1200 / 240dpi, 1200dp wide | 1.5 | Light |

Visual iteration corrected ring gradient seams, overage arcs touching numbers,
large-font ring spacing, an invisible inactive Slider track, clipped period
labels, undersized narrow-calendar targets, and cramped week values. Constrained
selectors now use two rows; macro groups wrap; calendar weeks split into two
weekday-labelled rows; week values gain full-width accessible segmented rows.

Final artifacts on this workspace (ignored by Git):

- `.verification/design-review-final/`: 46 final viewport screenshots.
- `.verification/redesign-delivery/`: 19 final real end-to-end screenshots.
- `.verification/redesign-final-tests.txt`: full 17-test result.
- `.verification/redesign-final-critical.txt`: final five-test result.
- `.verification/redesign-*-final.txt`: individual final viewport results.
- `.verification/redesign-cloud-build.txt`: final Gradle result.
- `.verification/JetMeal-1.1.0-design.apk`: installable signed debug APK, version
  1.1.0/code 3, configured for the existing hosted project.

The emulator's original resolution, density, font scale and light mode were
restored. The local test session remains on the emulator. Developer configuration
was restored to the original cloud client-safe configuration for the final build;
configuration, account/session secrets and local screenshots are not committed.

## Limits and provisional choices

Calendar adherence bands remain deliberately isolated and provisional: within
±10% is close, within ±25% is near; other values are above/below. Review these
thresholds and the authored ring/palette treatment visually before treating them
as a permanent product decision. Macro targets remain fixed and are never
redistributed.

No frame-time benchmark on a physical 60/120Hz device or manual TalkBack session
was performed. Semantic roles, descriptions, non-color status, enlarged text and
touch bounds were checked, but those do not replace a full assistive-technology
audit. The redesigned cloud APK was built with the existing project configuration;
this turn's new UI integration scenarios ran against real local Supabase, not the
product owner's hosted account. The hosted backend and existing password/session
behavior were preserved. Release assembly passed, but the release APK is unsigned;
the delivered installable artifact is the signed debug APK.

No unresolved implementation or environment blocker remains for this redesign.

## Reproducing local Android verification

Configure the real local stack using `scripts/configure_local_supabase.py` and
establish `adb -s emulator-5554 reverse tcp:54321 tcp:54321` plus the equivalent
54324 route for captured test confirmation mail. Build/install debug and
debugAndroidTest APKs. Run `adb -s emulator-5554 shell am instrument -w -r` with
the `com.kxsxlxv.jetmeal.test/androidx.test.runner.AndroidJUnitRunner` component;
select the test classes explicitly so a connected phone is not targeted.

Run `DesignViewportTest` with `-e viewport light|dark|large|narrow|wide` after
applying the corresponding emulator display/font/theme configuration above.
Restore those settings afterward. Restore the backed-up developer cloud
configuration before building a delivery APK; do not ship local localhost URLs.
