# Nutrition Tools contract v0.2

This document defines the stable boundary between AI clients and JetMeal application logic.

AI clients do not receive arbitrary SQL access. ChatGPT, a future on-device model and the normal Android UI should all converge on the same typed application operations.

## Tool surface

| Tool | Type | Confirmation | Purpose |
| --- | --- | --- | --- |
| `search_food` | read | no | Search the personal catalogue and return ranked matches. |
| `create_food` | write | no | Create a catalogue item/variant when no adequate match exists. |
| `log_food` | write | no | Create a diary entry with persisted nutritional basis and current totals. |
| `update_log` | write | no | Correct an existing diary entry. |
| `delete_log` | write | no | Soft-delete a diary entry. |
| `repeat_meal` | write | no | Copy a previous meal/group into a new consumed time. |
| `get_day` | read | no | Read active diary entries, meal grouping and totals for a local date. |
| `get_week` | read | no | Read daily totals and deterministic weekly-budget state. |
| `get_targets` | read | no | Read current calorie/macronutrient targets and redistribution limit. |
| `search_catalog` | read | no | Find foods satisfying calorie/macro constraints. |
| `undo_last_action` | write | no | Reverse the latest undoable action group. |
| `update_targets` | sensitive write | **yes** | Change calorie/macronutrient targets or weekly adjustment limit. |

## Common response envelope

Every tool should eventually return an envelope equivalent to:

```json
{
  "ok": true,
  "action_id": "uuid-or-null",
  "data": {},
  "warnings": [],
  "undoable": false
}
```

`action_id` groups all writes produced by one user intent. For example, “два сырка и банан” may create three diary rows while remaining one undoable action.

## Identity and authorization

Tools operate as the intended authenticated Supabase user and preserve RLS.

The external-AI linking mechanism is still an implementation decision, but the tool layer must never bypass ownership checks merely for convenience.

Never expose a Supabase `service_role`/secret key to the mobile client or to a model prompt/tool payload.

## Timezone contract

`profiles.timezone` stores the IANA timezone synchronized from the user's Android system timezone.

AI clients should not guess UTC offsets or hardcode the user's location. Application logic uses the stored timezone to:

- resolve local dates;
- resolve phrases such as “сегодня”, “вчера”, “с утра” when enough context is available;
- infer a meal period when one was not explicit.

The normal app has no manual timezone preference.

## Meal types

Canonical `meal_type` values:

- `morning`
- `day`
- `evening`
- `snack`

When the user explicitly names a meal period, that intent wins.

When no meal period is explicit, deterministic application logic derives it from `consumed_at` in the user's stored timezone:

- `morning`: 05:00–11:59
- `day`: 12:00–16:59
- `evening`: 17:00–04:59
- `snack`: never assigned from time alone

Examples:

- “съел овсянку” at 09:10 -> `morning`.
- “вечером записываю, что с утра съел овсянку” -> `morning` with an appropriate past `consumed_at`.
- “перекусил сырком” -> `snack` regardless of clock time.

AI may provide `meal_type` when explicit or confidently resolved from language. If omitted, the application layer must derive it deterministically.

## `search_food`

Input concept:

```json
{
  "query": "чиз",
  "limit": 5
}
```

Output candidates should include:

- `food_id`;
- preferred `variant_id`;
- display name;
- brand/source;
- base serving amount/unit;
- nutrition for that base serving;
- match confidence;
- short match reason.

Ranking should consider normalized name similarity, brand/source, personal usage frequency, recency and relevant meal/time context.

High-confidence AI matching may auto-select a candidate. The confidence threshold belongs to application policy/configuration, not to a model prompt constant.

## `create_food`

Creates the semantic food and at least one concrete variant when no adequate personal-catalogue match exists.

A variant owns its natural serving system. Examples:

- 300 `g` cottage cheese;
- 250 `ml` drink;
- 1 `piece` burger.

Changing package size, serving weight or nutrition later should create a new variant rather than mutate historical meaning.

## `log_food`

Input concept:

```json
{
  "food_id": "optional uuid",
  "food_variant_id": "optional uuid",
  "snapshot_name": "Творог 5%",
  "snapshot_brand": "optional",
  "quantity": 125,
  "quantity_unit": "g",
  "calories": 181,
  "protein_g": 21,
  "fat_g": 6.3,
  "carbs_g": 4,
  "consumed_at": "ISO-8601 timestamp",
  "meal_type": "morning",
  "confidence": 0.96
}
```

Calories/macros are totals for the quantity being logged.

The application persists an immutable **nutritional basis snapshot** for every entry:

- when `food_variant_id` is present, copy that variant's base serving amount/unit and nutrition into the entry basis;
- when the entry is an AI-only estimate without a reusable variant basis, use the initially logged quantity/unit and totals as the basis.

The current diary quantity/totals are stored separately from that basis. Future quantity edits must recalculate from the immutable basis so catalogue changes or repeated rounding do not drift history.

The consumed quantity may be a fraction or multiple of the catalogue variant's base serving. If the catalogue variant is 300 g but the user ate 125 g, the diary quantity is 125 g with proportionally scaled totals. If a burger variant is 1 piece, `quantity: 2` represents two burgers.

If `meal_type` is omitted, application logic assigns it using the deterministic rules above.

Ordinary food logging executes immediately and is undoable.

## `update_log`

Corrections should mutate the intended prior entry/action rather than create compensating fake food rows.

Examples:

- “нет, съел 125 грамм” -> update current quantity and recalculate totals from the immutable nutritional basis.
- “это было утром” -> correct `meal_type`/consumed time as appropriate.
- “это было вчера” -> correct `consumed_at`.

The normal JetMeal UI intentionally exposes only quantity editing, but the AI tool may support the additional correction fields necessary to represent explicit user intent.

## `delete_log`

Deletion is soft deletion in the MVP. The entry remains available to audit/undo and is excluded from active day/week totals.

## `repeat_meal`

AI resolves natural-language references such as “как вчера” or “такой же завтрак”.

The backend receives explicit source entry/group identifiers and creates new diary rows at the new consumed time, preserving the source nutritional basis/current quantity unless the user requested a quantity change.

## `get_day`

Input concept:

```json
{
  "local_date": "2026-10-05"
}
```

Output should include:

- active diary entries;
- `meal_type` for each entry;
- current quantity/unit and nutrition totals;
- day calorie and macro totals;
- base/effective calorie target where available;
- macro targets.

The tool resolves date boundaries in the user's stored timezone.

## `get_week`

Input concept:

```json
{
  "local_date": "2026-10-05"
}
```

The tool resolves the Monday–Sunday calendar week containing that date.

Output should include enough deterministic state for the app/AI to explain the budget:

- week start/end;
- base daily calorie target;
- weekly base budget;
- daily actual calories;
- each day's effective target where calculable;
- total consumed so far;
- cumulative deviation from base target for completed days;
- remaining days;
- current effective target;
- configured `adjustment_limit_ratio`;
- any residual that cannot be reconciled within the configured ± limit.

The weekly-budget calculation is application logic, not model arithmetic.

### Budget rule

For `n` remaining days including today:

```text
cumulative_deviation = sum(actual_completed_day_calories - base_daily_calories)
unclamped_target = base_daily_calories - cumulative_deviation / n
lower_bound = base_daily_calories * (1 - adjustment_limit_ratio)
upper_bound = base_daily_calories * (1 + adjustment_limit_ratio)
effective_target = clamp(unclamped_target, lower_bound, upper_bound)
```

Both over- and under-consumption are redistributed. No residual carries into the next Monday–Sunday week.

## `get_targets`

Returns current target configuration:

```json
{
  "daily_calories_kcal": 2000,
  "daily_protein_g": 170,
  "daily_fat_g": 70,
  "daily_carbs_g": 180,
  "adjustment_limit_ratio": 0.10
}
```

JetMeal does not calculate these values from weight-loss goals. It stores values that were decided outside the app.

## `update_targets`

Target changes are sensitive writes and require explicit confirmation.

Input concept:

```json
{
  "daily_calories_kcal": 2000,
  "daily_protein_g": 170,
  "daily_fat_g": 70,
  "daily_carbs_g": 180,
  "adjustment_limit_ratio": 0.10,
  "confirmation": "application-confirmed-token-or-state"
}
```

The AI may calculate or explain a recommendation outside JetMeal, but the tool must reject an unconfirmed mutation.

Confirmation should be represented as application state or a signed/short-lived confirmation mechanism, not merely as the model asserting that the user said “yes”.

## `search_catalog`

Read-only constrained search for foods that fit nutritional requirements, for example “something from my catalogue under 400 kcal with at least 25 g protein”.

It may use the same ranking/search infrastructure as `search_food`, with additional nutrition filters.

## `undo_last_action`

Reverses the latest undoable action group for the authenticated user. A multi-row log created by one user intent should undo as one action where practical.

## Validation and security requirements

- Validate all numeric ranges even when supplied by a model.
- Reject zero/negative consumed quantities.
- Do not trust user-editable JWT metadata for authorization.
- Use authenticated-user ownership and RLS for user rows.
- Preserve append-only semantics for normal-client audit events.
- Catalogue merge/hard delete is not part of the public AI tool surface.
- AI-generated estimates should preserve a confidence/estimated marker when relevant.
- Do not make LLM output the source of truth for weekly-budget arithmetic, meal-time boundaries or authorization.