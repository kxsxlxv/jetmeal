# Hero ring investigation — 2026-10-08

## Confirmed cause

The old moving cap used the same global `SweepGradient` as its arc body. At 101%, the endpoint is at 3.6° of the overflow lap, but a 23 dp diameter cap spans several degrees on both sides of 12 o'clock. Pixels before the start sampled the **end** of the overflow gradient (the 96–100% yellow-to-green tail); pixels beyond the actual endpoint sampled future colors. That made a short overflow look like several overlapping differently colored caps. Removing a start cap, changing stroke caps, or covering the seam cannot correct the shader coordinate.

`oldSweepGradientReproducesWrongBackOfLapInsideOnePercentCap` isolates just this cap and verifies its pixels differ from the start color. This establishes the shader cause independently of the track, base lap, delta pill, and text.

The old renderer also filled an almost-360° stroked arc plus separate circles. These primitives introduced overlapping antialiasing edges and an artificial incomplete-circle seam. They are unnecessary once the lap has one silhouette.

## Rendering model

The app and both widget variants use the same `CalorieRingRenderer`:

1. A closed circle supplies the track.
2. A partial first lap is the union of an annular sector and its two circular caps, filled once. A complete lap is an exact annulus.
3. Overflow is an independent foreground annular sector with a Butt start and one round moving end, also filled once.
4. The sweep gradient is bounded to the actual arc. Outside its angular interval, it samples the nearest endpoint. An explicit shader mask assigns the forward half of the moving cap its endpoint color, including when it crosses the start of a nearly complete lap.
5. The curved delta pill remains a single translucent black stroke over the result, with dark text on the arc. Its opacity increased from 10% to 14%. A geometric text-width limit keeps a long delta inside a short first lap, preserving the same capsule inset at both caps and the radial edges. Once a full lap exists, the text is unconstrained by the length of the short overflow segment. Center percent and curved consumed/target kcal remain.

The shader mask uses `ComposeShader`, `RadialGradient`, `LinearGradient`, and `BlendMode`, supported by both hardware Compose Canvas and software bitmap Canvas. An initial AGSL implementation was explicitly rejected by the real software-canvas test (`Software rendering doesn't support RuntimeShader`) and removed; no AGSL or renderer-specific fallback remains in production.

There are no approximate-completion thresholds, seam covers, 359.999° arcs, or separate start-cap visibility rules. The only completed-lap case is exact mathematical closure at one full revolution.

## Verification

Instrumentation source:

- `CalorieRingRendererTest`: isolated legacy reproducer; endpoint pixel colors at 101%; annulus bounds and absence of holes through two laps; pixel continuity around exact 100%; curved-pill containment; software full-Hero and ring-only widget captures.
- `CalorieRingComposeTest`: hardware Canvas captures in both light and dark themes.

Scenarios: 3%, 34%, 95%, 99%, 100%, 101% (1966 / 1946, +20), 105%, 107% (+141), and long negative delta -2137. Captures are saved under the app's external files directory, `hero-ring-verification`, and pulled to ignored `.verification/hero-ring-verification`.

Final test results are recorded in the task's main verification report after the standard-shader implementation is exercised on both canvases.

The exact-boundary test permits four 8-bit RGB levels in fully covered pixels and an average summed RGBA difference below two over the seam region. Measured before/after values were at most 3/4/3 RGB levels in the interior and 1.064 summed levels on average. CPU path antialiasing changes boundary coverage when a nearly closed union becomes a closed oval; requiring byte-identical output would test the rasterizer rather than a visible seam. GPU/software comparisons likewise avoid their 1.5-pixel cap-coverage fringe. Bounds and hole tests remain exact away from that fringe.

The ring-only 2×2 widget also uses aspect-preserving `ContentScale.Fit` in Glance rather than `FillBounds`. Its source renderer is tested in square, wide and tall launcher bounds for a centered circular silhouette. The launcher may advertise dimensions different from its final ImageView bounds; preserving the bitmap aspect ratio prevents those host changes from turning a circle into an ellipse.

## Official sources consulted

- https://developer.android.com/reference/android/graphics/Path.Op — inclusive union of paths.
- https://developer.android.com/reference/android/graphics/ComposeShader — shader A is destination, shader B is source; current `BlendMode` constructor.
- https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl — initial RuntimeShader investigation; software compatibility was checked with runtime tests rather than inferred from general Canvas guidance.
