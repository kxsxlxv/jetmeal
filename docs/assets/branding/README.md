# Jetmeal launcher icon

Jetmeal uses a true Android adaptive launcher icon.

- `app/src/main/res/drawable/ic_launcher_foreground.xml` — full-color VectorDrawable mark.
- `app/src/main/res/drawable/ic_launcher_monochrome.xml` — dedicated themed-icon layer.
- `app/src/main/res/drawable/ic_launcher_background.xml` — full-bleed warm cream background.
- `app/src/main/res/mipmap-anydpi/ic_launcher*.xml` — adaptive launcher definitions.
- `docs/assets/branding/jetmeal-orbit-source.svg` — editable source/reference for the mark.

The Jetmeal identity is an orange-like fruit treated as a small orbiting planet: the leaf communicates food/freshness, while the Saturn-like ring implies speed and the "Jet" part of the name without using a literal airplane silhouette.

The mark is intentionally compact and centered so it survives Android launcher masks. Because the app currently has `minSdk = 35`, no pre-Android-8 launcher fallback is required. Existing density WebP files are inactive historical resources.
