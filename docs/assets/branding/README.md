# Jetmeal launcher icon

Jetmeal uses an Android adaptive icon traced from the selected orange-orbit source artwork.

- `app/src/main/res/drawable/ic_launcher_foreground.xml` — full-color auto-traced VectorDrawable.
- `app/src/main/res/drawable/ic_launcher_monochrome.xml` — dedicated themed-icon silhouette.
- `app/src/main/res/drawable/ic_launcher_background.xml` — full-bleed warm cream background.
- `app/src/main/res/mipmap-anydpi/ic_launcher*.xml` — adaptive launcher definitions.
- `docs/assets/branding/jetmeal-orbit-source.svg` — editable traced source.

The geometry is traced from the chosen raster rather than manually redrawn. The fruit and orbit therefore preserve the proportions and silhouette of the approved concept while keeping the launcher resource resolution-independent.

The mark is inset inside the adaptive safe region so circle, squircle and other launcher masks do not crop the leaf or orbital ring.
