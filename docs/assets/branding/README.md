# Jetmeal launcher icon

Jetmeal's primary launcher artwork is intentionally raster-based to preserve the approved visual exactly.

- `app/src/main/res/drawable-nodpi/ic_launcher_foreground_art.webp` — approved color artwork.
- `app/src/main/res/drawable/ic_launcher_foreground.xml` — bitmap wrapper used by the adaptive icon.
- `app/src/main/res/drawable/ic_launcher_background.xml` — matching warm cream fallback/background.
- `app/src/main/res/drawable/ic_launcher_monochrome.xml` — simplified themed-icon layer only.
- `app/src/main/res/mipmap-anydpi/ic_launcher*.xml` — adaptive launcher definitions.
- `docs/assets/branding/jetmeal-orbit-raster.webp` — branding source copy.

Do not retrace or manually redraw the full-color mark. The approved raster is the source of truth for the normal launcher icon. Vectorization is used only for the monochrome themed-icon fallback.
