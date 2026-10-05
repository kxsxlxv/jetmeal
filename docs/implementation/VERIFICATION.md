# JetMeal implementation and verification

Verified 2026-10-06, Europe/Istanbul. This report describes the implemented local MVP and the checks actually performed. Research sources and version decisions are in [RESEARCH_NOTES.md](RESEARCH_NOTES.md), with detailed [design](DESIGN_RESEARCH.md), [toolchain](TOOLCHAIN_RESEARCH.md), and [backend](BACKEND_VERIFICATION.md) notes.

## Delivered behavior

The single-activity Kotlin/Compose app implements Today, Week, Calendar and Settings against real Supabase Auth/PostgREST. The owner's subsequent decision replaces Email OTP with existing-account email/password login. Sessions persist through the SDK and refresh automatically. Android's timezone is synchronized to the owner profile. No production food fixtures, fake authentication or runtime repositories are supplied.

Today and historical dates use the same grouped diary. Manual logging starts with frequent personal foods and live catalogue search, then previews nutrition for the entered quantity and requires confirmation. Editing changes quantity using immutable nutritional basis snapshots. Deletion is soft, and audited grouped undo restores the last intent. Estimates retain an immutable diary marker even if their catalogue variant later changes.

Settings accepts already-decided calorie/macro targets and a symmetric weekly adjustment limit, with a concrete confirmation dialog before writing. Monday–Sunday redistribution, residual variance and Monday reset are deterministic; macros remain fixed. Calendar displays daily calories and opens the same historical day screen. The typed Nutrition Tools boundary implements all twelve operations listed in the application contract, including food creation, meal repetition, constrained catalogue search and undo.

The UI reads lifecycle-aware StateFlow from a ViewModel. Supabase access stays behind its repository and typed application operations; reusable budget, meal and scaling rules remain pure Kotlin. Navigation 3 retains top-level/history state through SavedStateHandle. Protected RPCs make nutrition changes and append audit records atomically, with authenticated ownership checks, immutable snapshots and RLS. Android configuration contains only the project URL and client-safe publishable key from an ignored developer file; build-time validation rejects privileged keys. Session files are excluded from Android backups.

## Visual proposal

Material 3 Expressive supplies the theme, expressive motion scheme, emphasized typography, shape-changing buttons, list items, loading indicator, sheet transitions and adaptive navigation bar/rail. System light/dark appearance and dynamic color are authoritative. Today keeps the nutrient summary compact so the meal diary stays prominent; Week uses seven daily progress rows and explicit budget/deviation/residual text; Calendar uses accessible day cells with calories and status descriptions.

Week composition is a first visual proposal. Calendar adherence colors are presentation-only and centralized: within 10% of allowance, within 25%, or farther away. These thresholds do not change backend behavior. Current copy is English. Review the actual screenshots and motion on the emulator before treating these visual choices as final.

## Pinned versions

| Component | Implemented version |
| --- | --- |
| AGP / Gradle | 9.4.1 / 9.8.0 |
| Kotlin / Compose compiler / serialization plugin | 2.4.20 |
| Material 3 | 1.5.0-alpha29 |
| Compose UI / Foundation / Animation | 1.13.0-alpha03, compatible with the chosen Expressive line |
| Compose BOM | 2026.09.00 |
| Core / Activity / Lifecycle / Navigation 3 | 1.19.1 / 1.13.0 / 2.11.0 / 1.2.0 |
| Supabase Kotlin / Ktor | 3.8.0 / 3.6.0 |
| Coroutines / JSON serialization | 1.11.0 / 1.11.0 |
| Supabase CLI / database | 2.119.0 / PostgreSQL 17 |

Material and compatible Compose preview APIs are intentional under the repository's Expressive policy. Other dependency selections were verified against current official releases. Compile SDK is 37.1 because the compatible Compose artifacts require it; target SDK is 37 and minimum SDK is 35. This host built with Android Studio JBR 25.0.3 and Java bytecode target 17.

## Preflight and access

The starter baseline passed debug assemble, unit tests and lint before implementation. Android Studio's JBR and SDK were detected despite Java being absent from PATH. The API 37 emulator `emulator-5554` was available; a connected physical device was left untouched. Official Android/Supabase documentation and upstream samples were inspected before implementation. Detailed source links and transport limitations are recorded in the research notes.

GitHub connector authentication and the repository origin were verified. Supabase connector authentication exposed the existing `nutrition-tracker` hosted project. Its legacy tables already contain data and use another model. Hosted inspection was read-only; no hosted schema or data was changed. An empty real local stack was used for development instead.

Windows had no native Docker daemon/CLI, but WSL Ubuntu had a working Docker daemon. Official CLI and containers were installed there. Initial ECR image downloads timed out; the CLI's Docker Hub registry option resolved that route. WSL later shut down when its last foreground process exited, restarting services and briefly breaking OTP template readiness. A foreground WSL helper now holds the distro open during local testing/review. See backend notes for the exact start procedure. These infrastructure failures were resolved without replacing the integration.

## Commands and evidence

Windows commands below were executed from the repository with `JAVA_HOME=C:/Program Files/Android/Android Studio/jbr`:

```powershell
./gradlew.bat assembleDebug assembleRelease testDebugUnitTest lintDebug :app:assembleDebugAndroidTest
```

The final combined run passed in 1m 26s: debug and optimized release APKs assembled, all 20 domain tests passed (zero failures/errors), Android lint reported zero issues, and the instrumentation APK assembled. Release output is unsigned and requires an HTTPS project configuration and signing before distribution. It is not the local HTTP review build.

The real local database was freshly reset after the third migration was added, then checked from WSL:

```sh
supabase db reset --local
supabase test db --local
supabase db lint --local --schema public,private --fail-on error
supabase db advisors --local --type all --level warn --fail-on error
python3 scripts/verify_local_supabase.py --cli supabase
```

All three migrations applied successfully. All 52 pgTAP assertions passed. Database lint and security/performance advisors reported no issues. The HTTP script passed real two-user Email OTP, catalogue/diary operations, ownership isolation, immutable quantity scaling, deletion/undo, target confirmation, protected audit, refresh and logout checks. pgTAP fixtures are rolled back; HTTP and Android tests create real accounts/data only in the isolated local development stack.

The emulator received only the client-safe configured debug APK and test APK:

```powershell
adb -s emulator-5554 reverse tcp:54321 tcp:54321
adb -s emulator-5554 reverse tcp:54324 tcp:54324
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class com.kxsxlxv.jetmeal.ui.JetMealEndToEndTest,com.kxsxlxv.jetmeal.data.SupabaseRepositoryIntegrationTest,com.kxsxlxv.jetmeal.ui.JetMealUiTest com.kxsxlxv.jetmeal.test/androidx.test.runner.AndroidJUnitRunner
```

All eight instrumentation tests passed in 32.64s. Six component tests use test-source-only fixtures for semantics, confirmation gates, quantity editing, failure visibility and 1.5× font scaling. The repository integration test uses two independently authenticated real local Supabase sessions and verifies owner operations, RLS rejection, confirmation replay/cross-owner rejection, refresh and sign-out. The full MainActivity test exercises actual UI OTP, persisted SDK session, target review/save, search/select/125 g confirmation, quantity correction, deletion/undo, prior-day logging, Week arithmetic, Calendar → historical day → Back, and activity recreation. It captures all four actual screens. The two real integration tests were also rerun after the final fresh reset: both passed in 31.873s.

Cold-process persistence was verified after these tests, without clearing app data:

```powershell
adb -s emulator-5554 shell am force-stop com.kxsxlxv.jetmeal
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -W -n com.kxsxlxv.jetmeal/.MainActivity
adb -s emulator-5554 shell uiautomator dump /sdcard/jetmeal-persistence.xml
```

The cold launch showed Today and the real account's cottage-cheese diary row, with no sign-in prompt. This verifies preserved app data/session across the APK replacement mechanism using the same build/version; it does not claim a distinct version-upgrade migration was tested.

The running app was also resized to 1800×1200 pixels at 240 dpi (1200×800 dp), with system font scale 1.5. A cold launch, UI hierarchy and screenshot confirmed the wide navigation rail, all four destinations, calorie summary, macros and diary remained visible and usable. The screenshot was inspected alongside all four portrait captures. Emulator size, density and font scale were restored afterward. The first hierarchy capture raced configuration recreation and returned a null root; retrying after the cold launch completed succeeded. The exact reversible configuration commands were:

```powershell
adb -s emulator-5554 shell wm size 1800x1200
adb -s emulator-5554 shell wm density 240
adb -s emulator-5554 shell settings put system font_scale 1.5
# Cold launch and capture; then restore the original configuration.
adb -s emulator-5554 shell settings put system font_scale 1.0
adb -s emulator-5554 shell wm density reset
adb -s emulator-5554 shell wm size reset
```

That full test exposed an upstream Supabase Kotlin 3.8.0 query serialization issue: separate top-level filters for the same timestamp column lost the upper bound. The repository now serializes date bounds inside `and(...)`; regression assertions require adjacent-day diary queries to remain isolated. This was a real integration finding, not a fixture-only check.

Local evidence is kept under ignored `.verification/` and `.gradle/jetmeal-final-verification.log`. Screenshots are under `.verification/verification/`; APKs are under `app/build/outputs/apk/`. No account session or privileged CLI status is included in these artifacts.

| Actual local app capture | File |
| --- | --- |
| Today | [Today.png](../../.verification/verification/Today.png) |
| Week | [Week.png](../../.verification/verification/Week.png) |
| Calendar | [Calendar.png](../../.verification/verification/Calendar.png) |
| Settings | [Settings.png](../../.verification/verification/Settings.png) |
| Wide window, 1.5× font | [WideLargeFont.png](../../.verification/verification/WideLargeFont.png) |

The screenshots show data created by the end-to-end test in a real isolated local account; this data is not bundled with the app. The emulator remains signed in to that local account for visual review. The local stack requires the WSL foreground helper to remain alive (the current helper lasts eight hours); see backend notes for starting it again.

## Remaining verification and deployment boundaries

Hosted rollout is complete in the selected existing project; see [HOSTED_DEPLOYMENT.md](HOSTED_DEPLOYMENT.md). All seven additive migrations were applied and rollback verification passed, preserving original tables and records. SMTP is not required for the selected password login. The user personally set the existing account's password; inspection confirms a password now exists without reading its value or hash. The physical phone's saved authenticated session successfully read profile, targets, diary, catalogue and usage through the real SDK. Hosted authenticated mutations were verified with transactional database-role tests, not automated writes through the person's phone account.

External ChatGPT user linking remains explicitly open in the product specification. The shared operations are implemented, but an external authenticated transport/linking and human-confirmation adapter has not been deployed. The one-use target challenge binds owner and values; the future adapter must restrict preparation to its actual human approval path rather than treating an AI-provided Boolean as consent. No privileged service key is exposed as a workaround.

Automatic one-hour JWT expiry timing was not waited out. Explicit refresh and refresh revocation after sign-out were tested. Manual TalkBack exploration, formal contrast auditing, benchmark/frame timing and a signed production release remain unverified. Semantic labels, appropriate touch targets, font scaling, emulator navigation/insets and visual layout were checked; these checks do not imply a full accessibility or performance certification.

## Final password and connection verification

The password field uses current Material 3 `OutlinedSecureTextField` with a temporary `TextFieldState`, full masking, exact whitespace preservation, and no saved password. Production exposes only email, password and Sign in. Sessions use endpoint-specific private preferences; local sessions cannot be imported into the hosted build.

Foreground Auth/PostgREST operations have 15-second request limits; PostgREST automatic retries are disabled so a user can retry explicitly. The final engine is explicitly selected Ktor OkHttp 3.6.0. A diagnostic Android engine trial was reverted after an actual physical-device cancellation/response-close failure. The initial cloud test hang was separately identified as SDK initialization waiting for process ON_START in a headless test; test-only lifecycle callbacks are disabled. Production retains normal Activity lifecycle behavior.

Canceled or superseded refresh/search requests cannot publish errors or stale data. This prevents canceled transport I/O from appearing as an active connection failure. Current genuine request failures still produce actionable errors.

Final checks on 2026-10-06:

- 34 JVM tests passed: 20 domain, 8 error classification, 4 project-session isolation, 2 sign-in validation.
- Fresh local database reset applied all seven migrations; 64 pgTAP tests passed. Isolated additive replay also passed with legacy rows/ACLs/policies unchanged.
- 10 Android tests passed on API 37 in 58.66 seconds using actual local Auth/PostgREST/Mailpit. This includes password UI login, quantity/undo/targets, independent-owner RLS, six rapid refresh/navigation/search replacement cycles without a spurious error, and component/accessibility behavior.
- Two hosted Android tests passed on the connected V2515/API 36 phone in 68.792 seconds: actual saved-account authenticated reads and unauthenticated SDK isolation/denied mutations. The final phone APK was installed with `adb install -r`, preserving application data.
- After the final cloud rebuild/reinstall, the saved-account authenticated-read test passed again in 1.805 seconds. The temporary instrumentation package was removed from the phone, and the normal app reopened.
- After reopening the phone app, the actual UI hierarchy showed Today, no Sign in screen, and no Connection interrupted banner.
- Debug/release assembly, debug unit tests and both lint checks passed; lint reports no issues. Release APK remains unsigned; the installable cloud artifact is the debug-signed `.verification/JetMeal-1.0.1-cloud.apk`.

The one-time user-operated password setup helper was checked against real local Auth Admin: account identity was preserved and exact new password login succeeded. No privileged key, password or token is bundled into the Android app or persisted by that helper.
