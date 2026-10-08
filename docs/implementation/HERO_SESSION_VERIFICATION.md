# Hero, widget and session verification — 2026-10-08

Base: remote master `553ab629b784b99361579c4a1e629b0f8c51649c`.

## Findings and implementation

- [Animation diagnosis](HERO_ANIMATION_DIAGNOSIS.md): real local MainActivity traces identified catalogue decoding/ranking on Main after each period refresh. Removed eager catalogue loading; search runs in the background with atomic cache publication. The existing linear shared clock now updates drawing without recomposing the Hero. No pager rewrite or easing substitution.
- [Ring diagnosis](HERO_RING_VERIFICATION.md): a sweep shader on a round cap sampled the far end of the gradient across 12 o'clock. App and both widgets now share a bounded shader and single silhouette per lap. Curved pill, dark text, center percent and consumed/target kcal remain.
- Ring-only 2×2 widget: `ContentScale.FillBounds` stretched the source bitmap if the launcher's actual bounds differed from Glance's advertised size. `ContentScale.Fit` preserves circular geometry for both widget variants, including rectangular cell bounds. Renderer already centers a circle using the smaller dimension. A physical owner's launcher is not available for verification.
- [Session diagnosis](SESSION_DIAGNOSIS.md): fixed background initialization, false signed-out UI, temporary refresh rejection deleting storage, and repeated reloads on token rotation. Safe Auth diagnostics capture original DNS/TLS/status before SDK exception wrapping. Historical daytime mobile-data failures cannot be attributed conclusively without the old device trace; inspected hosted requests were successful.

## Environment and completed checks

Android Studio JBR 25.0.3, Gradle 9.8, API 37 emulator, actual local Supabase in WSL Docker. Production dependency versions were preserved; only test dependencies were added. No hosted settings or data were changed. Existing untracked local migrations were preserved.

- Baseline and final CI command: `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-configuration-cache --console=plain`: successful. 74 JVM tests, no lint errors (11 warnings).
- `python -m unittest discover -s scripts -p test_android_release.py`: 6 passed.
- Real local `supabase db reset --local --yes`: all 7 migrations applied.
- `supabase test db --local`: 64 pgTAP assertions passed.
- `supabase db lint --local --schema public,private --fail-on error`: no errors.
- `scripts/verify_local_supabase.py`: real Email OTP, two-user RLS isolation, typed mutations, quantity, soft-delete, targets, audit, refresh and logout revocation passed.
- Before/after actual MainActivity runtime traces: confirmed elimination of the 164–258 ms catalogue stalls. Occasional emulator scheduling gaps remain; no claim of perfect physical-device frame timing.

Final local `connectedDebugAndroidTest` run completed successfully in 2m30s: 30 executed, 28 passed, two JUnit assumptions (hosted connectivity on a local build; viewport capture without its persisted account). The owner-hosted saved-session test was excluded because no owner credentials/session were supplied. AGP's XML represents assumptions as failure nodes, but the runner reports zero failed tests and the Gradle task succeeds; those assumptions are counted here as skips, not passes.

The passing cases include real Email OTP/repository/RLS integration, actual Activity-stop/headless-widget session use, all seven native ring tests plus hardware/software Compose parity, both shared-clock animation regressions, large-font accessibility, and full real MainActivity sign-in/quantity/delete/undo/pager/recreation flow. The latter captures actual application screens. Two existing UI tests referenced the removed refresh icon and old pre-Hero captions; they now exercise pull-to-refresh and current accessibility descriptions. The viewport fixture now checks for its documented saved-session prerequisite explicitly.

The final CI command is repeated with the usual hosted developer configuration after local instrumentation. Hosted-only connectivity is checked separately; its result is recorded below. No historical owner-mobile request is reproduced by these environment checks.

- Final hosted-config CI build/test/lint: **successful**, 22 seconds. The APK left in `app/build/outputs/apk/debug/app-debug.apk` uses the normal hosted developer configuration, not the local test endpoint.
- Real hosted `SupabaseHostedConnectivityTest`: **passed**, 19.091 seconds. Emulator TLS, Auth settings, anonymous isolation on six protected tables and denial of unauthenticated RPC were verified without signing in as the owner or changing hosted data.
- Persisted-owner hosted-session replay and the standalone viewport fixture remain unverified. The full generated-local-account E2E and light/dark Hero capture tests did pass. The owner's physical mobile network/launcher was not available.

Temporary trace hooks and runtime investigation tests were removed. Raw logs and captures are retained only in ignored `.verification/`. No release publication or hosted account sign-in was performed.
