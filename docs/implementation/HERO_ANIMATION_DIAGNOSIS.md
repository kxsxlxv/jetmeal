# Day Hero animation investigation — 2026-10-08

The day-change hitch was reproduced with the production `MainActivity`, ViewModel and a real local Supabase Auth/Postgres stack. The test account had 591 real catalogue variants and alternating diary values across seven days. Frame timing came from Android `FrameMetrics` and the real Choreographer; no Compose test clock drove this measurement.

## Confirmed cause

`refresh()` published the selected day's records, allowing its 700 ms Hero animation to start, and then unconditionally called `search("")`. That query fetched usage, foods and variants, decoded the JSON and ranked the catalogue on the caller's main dispatcher. Across five day changes, frame gaps of roughly 164–216 ms ended within 2–5 ms of the catalogue-ready event. A cold initial load produced a 258 ms gap. Adding 350 ms of latency to the real local `/food_variants` response moved the stall into the final animation frames: progress jumped from about 0.881 to 1.0 after an 83 ms gap.

The four indicators stopped together because this work occupied the UI thread. The current-day refresh in this trace kept its targets unchanged and did not start another progress animation, although it still loaded the catalogue. Same-day changes of the animated values are covered separately by the Compose regression test. Changing the easing or sharing an animation clock could not remove the UI-thread stall.

## Causes checked and ruled out in the production trace

- One Hero instance and one final target were observed per day change.
- Hero bounds stayed constant at 996 × 441 px on the emulator.
- Pager settling finished before the new progress animation started.
- The old Hero was disposed before settling; there was no late page disposal or second `showPeriod()`/target update at the stall.
- A test-only loader with deliberately delayed `busy` publication could create an intermediate zero Hero. The real ViewModel's immediate loading update did not reproduce that intermediate composition. No pager/state architecture rewrite was made based on that fixture artifact.

## Fix and regression coverage

- Day refresh no longer loads the search catalogue. The Add flow already explicitly requests the live catalogue when opened.
- Search decoding/ranking runs on an injected background dispatcher. Small synchronized cache sections make invalidation and conditional publication atomic while network work remains outside the lock.
- The shared animation clock and its linear 700 ms tween are preserved. Its `State` is read in `Canvas`/`drawBehind`, so frame updates no longer recompose the entire Hero and its `BoxWithConstraints` subtree.
- `PeriodRefreshTest` verifies initial load, day change and current-day refresh never fetch foods/variants.
- `SearchCacheTest` verifies an in-flight background query cannot install an obsolete cache after invalidation.
- `HealthProgressAnimationTest` verifies a uniform shared clock, stable composition/layout through the final frame, same-day retarget continuity and a zero start for a new day.

The same real-stack runtime scenario passed after the fix (23.032 s). Each date had one Hero instance, one target and one composition. No catalogue query ran during navigation. The 90–100% portion generally advanced every 15–18 ms; a 24/11 ms pair remained on the emulator. Whole animations occasionally had 33–52 ms emulator frame gaps, but none of the previous 164–258 ms catalogue stalls remained. The separate controlled Compose tests passed and verify the mathematical frame sequence without emulator scheduling noise.

All temporary production trace hooks and investigation fixtures were removed after measurement. Before/after logs remain local under the ignored `.verification/` directory; the three regression test classes above remain in the repository.

Official guidance checked during this change:

- [Pager state and settling](https://developer.android.com/develop/ui/compose/layouts/pager)
- [Compose phases and deferred state reads](https://developer.android.com/develop/ui/compose/performance/phases)
- [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices#defer-reads)
- [Main-safe suspend functions and injected dispatchers](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
