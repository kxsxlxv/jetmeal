# Jetmeal launcher icon

The Android launcher icon is implemented as a true adaptive icon rather than a pre-rounded bitmap.

- `app/src/main/res/drawable/ic_launcher_foreground.xml` — full-color VectorDrawable foreground.
- `app/src/main/res/drawable/ic_launcher_monochrome.xml` — dedicated Android themed-icon layer.
- `app/src/main/res/drawable/ic_launcher_background.xml` — full-bleed dark blue/teal background.
- `app/src/main/res/mipmap-anydpi/ic_launcher*.xml` — adaptive launcher definitions.

The important Jetmeal mark is inset from the adaptive-icon mask edge. The identity combines a meal bowl, jet/wing motion, fresh food and a small AI-orbit sparkle.

Because the app currently has `minSdk = 35`, no pre-Android-8 launcher fallback is required. Existing density WebP files are retained only as inactive historical resources and can be removed later without changing runtime behavior.
