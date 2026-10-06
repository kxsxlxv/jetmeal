# JetMeal UI Redesign Brief — Material 3 Expressive

## Task

Redesign the existing JetMeal Android UI so it feels like a deliberate, modern **Material 3 Expressive** product rather than a default Material CRUD application.

The current implementation is functionally useful, but the visual hierarchy, composition, motion, interaction model, and component choices are too conservative. Do **not** treat Material 3 Expressive as merely a theme wrapper or a set of rounded components.

This task is primarily a **UI/UX redesign**. Preserve the working backend, Supabase integration, domain rules, nutrition calculations, RLS behavior, authentication/session handling, and repository contracts unless a UI change strictly requires a small compatible adaptation.

Do not replace working real integrations with mocks or sample data.

---

# 1. Product direction

JetMeal should feel:

- modern Android-native;
- visually distinctive;
- expressive without becoming noisy;
- fast and fluid;
- data-dense where useful, but not spreadsheet-like;
- polished enough that motion, shapes, hierarchy, typography, and transitions are part of the product experience;
- clearly based on the newest available Material 3 Expressive design language.

The design should not look like a generic admin panel, form app, or plain list of rounded cards.

Use the newest official Material 3 Expressive APIs available at implementation time, even if they are alpha / preview / experimental.

Before changing UI code, inspect the current official Material 3 Expressive documentation, current Compose Material 3 release notes, relevant official samples, and the currently selected Material 3 version in the project.

---

# 2. Language

The entire user-facing application UI must be **Russian**.

This includes:

- navigation labels;
- headings;
- buttons;
- empty states;
- errors;
- account/settings labels;
- nutrition labels;
- date and period labels;
- accessibility descriptions where appropriate.

Examples:

- Today → `Сегодня`
- Day → `День`
- Week → `Неделя`
- Month → `Месяц`
- 3 Months → `3 месяца`
- Morning → `Утро`
- Day meal → `День`
- Evening → `Вечер`
- Snack → `Перекус`
- Settings → `Настройки`
- Add → `Добавить`

Use Russian date formatting and Russian locale conventions.

Do not leave mixed English/Russian UI.

---

# 3. Replace the current navigation model

The current bottom navigation with:

- Today
- Week
- Calendar
- Settings

must be redesigned.

## 3.1 Main information architecture

JetMeal should have one primary nutrition/history surface with four **time scales**:

1. `День`
2. `Неделя`
3. `Месяц`
4. `3 месяца`

These are **not four independent destinations**.

They are four views of the same nutrition timeline at different temporal scales.

Use a Material 3 Expressive **Button Group** as the main time-scale selector.

Conceptually:

```text
[ День ] [ Неделя ] [ Месяц ] [ 3 месяца ]
```

The selected state should be highly legible and expressive, with shape/color/motion behavior appropriate to the current Button Group API.

Do not add `Год`.

---

# 4. Remove bottom navigation

After moving Day / Week / Month / 3 Months into the time-scale Button Group, only the main nutrition experience and Settings remain.

A bottom navigation bar is unnecessary for two top-level destinations and wastes vertical space.

Remove the current bottom navigation.

## 4.1 Settings access

Settings should be opened from the main top app bar using a Material Symbol settings icon.

For example:

```text
JetMeal                                      [⚙]
```

Settings is a secondary destination.

Opening Settings should transition away from the main timeline surface.

The Settings screen should provide a normal back affordance to return to the main experience.

Use current official Material Symbols rather than hand-drawn Canvas icons.

Do not keep custom Canvas navigation icons.

---

# 5. Main header structure

The main screen should have three conceptual layers.

## Layer 1 — App identity / Settings

Compact app bar:

```text
JetMeal                                      [⚙]
```

Do not repeat `JetMeal` redundantly under every page title.

Use an appropriate modern Material 3 Expressive app bar.

Explore current flexible / two-row / collapsing app bar APIs if they improve the result.

## Layer 2 — Time-scale Button Group

```text
[ День ] [ Неделя ] [ Месяц ] [ 3 месяца ]
```

This selector should remain visually stable while navigating within the selected period.

## Layer 3 — Current period navigation

Examples:

### Day
```text
Сегодня                  [‹] [›] [↶]
```

### Previous day
```text
Вчера, 5 октября         [‹] [›] [↶]
```

### Week
```text
5–11 октября             [‹] [›] [↶]
```

### Month
```text
Октябрь 2026             [‹] [›] [↶]
```

### 3 months
```text
Октябрь–декабрь 2026     [‹] [›] [↶]
```

The reset/current-period action should return to the current day/week/month/three-month interval.

Use Material Symbols for arrows/reset.

---

# 6. Horizontal swipe navigation

Horizontal swipe gestures should navigate between adjacent periods **inside the currently selected time scale**.

Examples:

## Day

```text
4 октября  ←  5 октября  ←  Сегодня  →  7 октября
```

## Week

```text
28 сен – 4 окт  ←  5–11 окт  →  12–18 окт
```

## Month

```text
Сентябрь  ←  Октябрь  →  Ноябрь
```

## 3 months

```text
Июль–сентябрь  ←  Октябрь–декабрь  →  Январь–март
```

Prefer current Compose paging APIs such as `HorizontalPager` where appropriate.

The arrow buttons must perform the same navigation as swipe.

The swipe interaction must feel native, fluid, and predictable.

---

# 7. Do not use horizontal meal carousels on the Day view

Do **not** introduce another horizontal swipe layer for:

- Утро
- День
- Вечер
- Перекус

because horizontal swipe is already reserved for changing the date.

Avoid gesture ambiguity.

Do not create nested horizontal pagers for meal periods.

---

# 8. Day view — redesign from first principles

The Day view is the most important screen in the app and should define the visual language of JetMeal.

The existing layout should not simply be restyled.

Recompose it.

The Day view should contain:

1. daily calorie hero;
2. macro summary;
3. meal-period summary/accordion;
4. food entries inside the active meal period.

---

# 9. Daily calorie hero

The current large rectangular card containing text plus a standard linear progress bar is too generic.

Create a stronger hero composition.

Possible direction:

- large dominant calorie value;
- clear relationship between consumed and current target;
- expressive progress visualization;
- strong over/under state;
- subtle supporting text.

Example information hierarchy:

```text
        2327
        ккал

цель 2000
+327 сверх нормы
```

Consider the current Material 3 Expressive:

- `CircularWavyProgressIndicator`
- expressive progress APIs
- emphasized typography
- animated shape/progress/state changes

Use the most appropriate current official component/API.

## Important over-target behavior

Do not visually clamp a day above 100% in a way that makes 2050 and 3500 kcal look identical.

The UI must clearly represent:

- remaining calories when below target;
- achieved target;
- calories above target.

The exact visual design is yours, but over-target status must be immediately understandable.

---

# 10. Macro summary

Protein / Fat / Carbs should no longer look like three spreadsheet columns.

Create three compact, visually coherent macro elements.

Russian labels:

- `Белки`
- `Жиры`
- `Углеводы`

Possible treatment:

- three compact tonal containers;
- compact progress visualization;
- emphasized consumed value;
- smaller target / remaining value.

Do not overuse wavy progress indicators everywhere.

A good hierarchy could reserve the most expressive wavy treatment for calories while keeping macro progress visually quieter.

The macro section must wrap correctly for large font sizes.

---

# 11. Meal periods — use an animated accordion

The Day screen contains four meal periods:

- `Утро`
- `День`
- `Вечер`
- `Перекус`

Do not show four fully expanded sections simultaneously.

Do not hide them behind a second horizontal tab/button group.

Use an **animated accordion** model:

- all four meal periods remain visible as compact summary rows;
- only one meal period is expanded at a time;
- tapping another meal period collapses the previous one and expands the selected one;
- expansion/collapse should use meaningful Material 3 Expressive motion.

Example:

```text
Утро
420 ккал · 2 блюда                           [+]

День                                         [⌃]
711 ккал · 3 блюда                           [+]

┌ Филе куриное по-пармски              400
├ Салат крабовый                         151
└ Компот ягодный                         160

Вечер
0 ккал · нет записей                         [+]

Перекус
1196 ккал · 3 блюда                          [+]
```

This is conceptual, not a literal required layout.

---

# 12. Default expanded meal period

For **today**, automatically expand the current inferred meal period based on the existing product rules:

- 05:00–11:59 → `Утро`
- 12:00–16:59 → `День`
- 17:00–04:59 → `Вечер`

`Перекус` remains explicit-only and should not become the automatic time-derived section.

For a historical day, prefer the **last non-empty meal period** as the initial expanded section.

If all are empty, choose a sensible default without changing product semantics.

---

# 13. Meal group summary

Each collapsed meal-period row should show useful information at a glance.

At minimum:

- meal period name;
- total kcal for that period;
- number of entries or an empty state;
- add action.

Avoid verbose text such as `No food logged`.

Use concise Russian empty states.

Examples:

```text
Утро
Нет записей
```

or

```text
Утро
0 ккал
```

The visual solution is up to the agent.

---

# 14. Food entries inside a meal

Food entries belonging to one meal period should visually feel like a **single related group**, not a stack of independent rounded cards.

Study and prefer current Material 3 Expressive grouped-list APIs such as:

- `SegmentedListItem`
- current segmented list shapes/defaults

where appropriate.

Desired feeling:

```text
╭ Филе куриное по-пармски         400 ккал
├ Салат крабовый                  151 ккал
╰ Компот ягодный                  160 ккал
```

Each row should still show the most useful supporting data:

- amount + unit;
- food name;
- kcal;
- macro information if it can remain readable without turning the row into a spreadsheet;
- estimated status when relevant.

Tapping a row still opens quantity editing.

Do not change the rule that normal manual editing changes **quantity only**.

---

# 15. Add-food action

Replace the current large text `+ Add` buttons beside every meal heading.

Use a compact expressive action.

Prefer:

- Material Symbol add icon;
- expressive IconButton / appropriate compact button;
- shape/motion state behavior.

The add action remains contextual to a meal period so the user knows where the food will be logged.

Do not automatically replace this with one global FAB unless the UX becomes clearly better.

A future global add action could use current:

- `ToggleFloatingActionButton`
- `FloatingActionButtonMenu`

but it is not mandatory in this redesign.

---

# 16. Add / search flow

The existing bottom-sheet model is acceptable and can remain.

However, redesign its content so it does not feel like a plain form.

The flow remains:

1. open Add for a meal period;
2. show frequent foods;
3. allow live search in personal catalogue;
4. select food;
5. show selected food details and default quantity;
6. allow quantity change;
7. explicitly confirm add.

Use modern current Material 3 search/input components where appropriate.

Investigate current expressive SearchBar / app-bar-with-search patterns instead of defaulting automatically to a single plain `OutlinedTextField`.

Preserve the existing real Supabase data path.

---

# 17. Week view

Week is not a stack of seven identical large cards.

Redesign it as an analytical view of the same nutrition timeline.

The core visual should make the seven days understandable at a glance.

Recommended direction:

- a seven-day graph / bar visualization;
- actual calories compared with each day's effective target;
- meaningful shape and motion transitions;
- current/selected day highlighted;
- compact metric summaries.

Important weekly values that may be surfaced:

- current effective daily calorie target;
- total consumed;
- weekly base budget;
- completed-day deviation;
- days remaining;
- unreconciled residual when adjustment limit prevents full compensation.

Example high-level hierarchy:

```text
1946 ккал
текущая дневная норма

+327
отклонение

6
дней осталось
```

Do not turn every statistic into a separate large card.

Macros are not dynamically redistributed and should not imply that they are.

---

# 18. Month view

`Месяц` replaces the old standalone Calendar destination.

Month is a timeline visualization mode, not a separate app section.

Use a calendar-like month visualization.

Requirements:

- show kcal on logged days;
- visually communicate proximity to target;
- tapping a date switches to `День` and opens that date;
- current day should be identifiable;
- selected date should be identifiable;
- adherence status must not rely on color alone;
- changing month should animate smoothly;
- horizontal swipe changes month;
- arrow controls change month;
- reset returns to current month.

The exact adherence thresholds are still provisional product behavior and should remain isolated/easy to change.

Avoid a visually crude grid of generic independent rectangles.

Use expressive shape/state treatment.

---

# 19. Three-month view

`3 месяца` should provide a higher-level history view covering three consecutive months.

Do not simply show three full huge month calendars stacked vertically.

Design a compact analytical overview.

Possible directions include:

- three compact month heatmaps;
- a 12–13 week trend;
- daily calorie/adherence mini-cells;
- month summaries plus a continuous trend;
- a combined graph with month boundaries.

The goal is to understand patterns across roughly one quarter at a glance.

Horizontal swipe moves to the previous/next three-month block.

A tap on a specific day should be able to open that day in `День` mode where practical.

The visual composition is intentionally left to the agent after studying current Material 3 Expressive patterns.

---

# 20. Settings redesign

Settings remains a separate secondary screen opened from the top app bar.

The current Settings screen is too form-like.

Do not present every setting as a giant default `OutlinedTextField`.

Redesign the settings hierarchy.

Required settings remain:

- base daily calories;
- protein target;
- fat target;
- carbohydrate target;
- weekly adjustment limit;
- account/session controls.

Use current Material 3 / Expressive input styling where appropriate.

Investigate current APIs such as:

- tonal text field colors;
- expressive rounded text field shapes;
- current Slider APIs for the weekly percentage limit.

For weekly adjustment, consider:

- a large visible percentage value;
- Slider for fast adjustment;
- precise numeric edit if needed.

Preserve confirmation before sensitive target updates.

Use Russian UI.

---

# 21. Color system

Do not let Android wallpaper-derived dynamic color erase JetMeal's visual identity.

JetMeal should continue to follow the **system light/dark mode**, but should use an authored JetMeal color system.

Disable dynamic color as the default product behavior.

Requirements:

- system determines light vs dark;
- JetMeal owns its color palette;
- palette should work coherently in both modes;
- nutrition status colors should be purposeful and accessible;
- do not make the app monochrome unless there is a strong deliberate reason.

The existing green/food-oriented fallback palette may be revisited rather than accepted automatically.

Create a coherent branded color language.

---

# 22. Typography

Do not leave the project on plain default `Typography()`.

Build a deliberate typography hierarchy using the current Material 3 Expressive type system.

Use emphasized variants where they improve hierarchy.

Calories and key analytics deserve stronger numerical hierarchy than supporting labels.

Headings, metrics, section summaries, and food rows should not all have similar visual weight.

Do not introduce arbitrary custom fonts unless there is a clear reason and licensing/distribution is appropriate.

---

# 23. Shapes

Do not solve the redesign by simply increasing corner radii everywhere.

Create a coherent shape language.

Use current expressive shape APIs and shape morphing where appropriate.

Different component roles should be distinguishable:

- hero;
- controls;
- segmented food groups;
- meal headers;
- selected states;
- compact metric containers;
- modal surfaces.

Expressive shapes should support hierarchy and interaction, not become decoration.

---

# 24. Motion

Motion is a first-class requirement.

Use `MaterialTheme.motionScheme` and current Material 3 Expressive motion APIs.

Important motion opportunities:

- time-scale selection;
- Day / Week / Month / 3 Months content transition;
- horizontal period paging;
- meal accordion expand/collapse;
- food addition/removal;
- quantity changes;
- calorie/macro progress updates;
- selected calendar date;
- Settings opening/closing;
- add-food bottom sheet transitions.

Prefer motion that communicates:

- continuity;
- hierarchy;
- state change;
- spatial relationship;
- feedback.

Avoid ornamental animation that slows interaction.

The app should feel excellent at 60 Hz and 120 Hz.

---

# 25. Material Symbols

Replace hand-drawn Canvas navigation/action icons with current official Material Symbols where suitable.

Use current Android guidance for importing/using Material Symbols.

Icons should be visually consistent with the Material 3 Expressive component set.

---

# 26. Empty and error states

Do not use large raw text banners where a more composed Material treatment exists.

Connection errors, empty meals, loading, save confirmation, undo, and no-results states should fit the design system.

They must remain functional and accessible.

Do not obscure errors behind decorative UI.

---

# 27. Preserve working behavior

Do not rewrite working backend/domain code merely to support this redesign.

Preserve:

- Supabase as source of truth;
- authenticated session behavior;
- RLS;
- real personal food catalogue;
- diary reads/writes;
- soft delete;
- quantity-only correction;
- immutable nutrition basis calculations;
- target update confirmation;
- Monday–Sunday weekly redistribution;
- ± adjustment limit;
- no carry into next week;
- fixed macro targets;
- meal period semantics;
- system timezone;
- existing typed application/Nutrition Tools boundaries.

If the current branch differs from older documentation, inspect the actual current implementation and user-approved behavior before changing backend semantics.

---

# 28. Do not introduce mocks

If an environment, emulator, Supabase connection, or external capability is unavailable:

- report it;
- explain exactly what cannot be verified;
- continue only with work that can be honestly completed.

Do not add runtime sample data, fake repositories, fake auth, or placeholder success paths.

---

# 29. Visual verification is mandatory

Do not consider the redesign complete after compilation.

Run the app on an available Android emulator/device and inspect the actual rendered UI.

For each important state, capture and review screenshots.

At minimum visually review:

- Day — populated;
- Day — empty meal period;
- Day — over target;
- Day — historical date;
- expanded/collapsed meal sections;
- add-food flow;
- Week;
- Month;
- 3 Months;
- Settings;
- dark mode;
- light mode if practical;
- large font scaling;
- narrow phone layout;
- a wider/resizable layout if available.

Iterate after visual inspection.

Do not claim an excellent visual result solely from source-code inspection.

---

# 30. Acceptance criteria

The redesign is successful only if all of the following are true:

- there is no bottom navigation on compact phone layout;
- main time selector is `День / Неделя / Месяц / 3 месяца`;
- there is no `Год`;
- all visible UI is Russian;
- horizontal swipe changes period within the selected time scale;
- Settings is accessed from the main app bar;
- Day is visually redesigned, not merely recolored;
- calorie summary has strong visual hierarchy;
- macros no longer look like plain spreadsheet columns;
- meal periods use a compact one-at-a-time expandable accordion;
- food entries in a meal visually form a grouped/segmented set;
- contextual add actions are compact and expressive;
- Month replaces the old standalone Calendar concept;
- 3 Months is a real higher-level analytical view;
- authored JetMeal colors are used instead of wallpaper-derived dynamic color by default;
- typography is intentionally configured rather than plain `Typography()`;
- Material Symbols replace hand-drawn app/navigation icons where appropriate;
- motion is clearly present and meaningful;
- the implementation substantially uses current Material 3 Expressive capabilities;
- no working backend behavior is regressed;
- no fake runtime backend/data is introduced;
- UI has been visually verified on an emulator/device.

---

# 31. Agent process

Before coding:

1. inspect the current UI implementation;
2. inspect the current Material 3 / Compose versions in the repository;
3. study the latest official Material 3 Expressive documentation and release notes;
4. inspect current APIs for Button Groups, segmented list items, expressive progress, app bars, text fields, sliders, motion, shapes, FAB/menu patterns, and Material Symbols;
5. inspect relevant official Compose samples for implementation techniques;
6. write a short redesign plan explaining the intended component hierarchy and interaction model.

Then implement the redesign end-to-end.

Do not stop after a design plan.

After implementation:

1. build;
2. run tests that remain applicable;
3. install/run on emulator/device;
4. visually inspect;
5. iterate on obvious visual problems;
6. provide screenshots or screenshot paths if the environment supports it;
7. report any unresolved visual/product issue honestly.

---

# 32. Design freedom

This brief intentionally defines interaction architecture and product constraints, but it does **not** prescribe pixel-perfect mockups.

The agent still has design freedom over:

- exact spacing;
- exact shapes;
- exact color palette;
- hero composition;
- macro component composition;
- detailed Week visualization;
- detailed Month visual treatment;
- detailed 3 Months visualization;
- transition choreography;
- precise typographic sizes within Material guidance.

Use that freedom to produce a coherent, distinctive JetMeal design.

Do not fall back to the safest/default Material layout merely because the brief does not prescribe every pixel.

The desired outcome is a design that feels intentionally authored, current, expressive, and delightful.
