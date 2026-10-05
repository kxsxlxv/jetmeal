# Reviewed additive hosted deployment

Date: 2026-10-06 (Europe/Istanbul). User selected existing project `nutrition-tracker` (`qfhemprjxezfbrcgdcpg`). The root agent applied all seven reviewed migrations through the authenticated connector, after fresh local migration and test proof.

The user subsequently selected email/password login for the existing single account, overriding the original Email OTP requirement. Backend authorization remains based on registered Supabase identity and RLS. The user personally completed secure password setup; inspection confirms its presence without reading its value or hash. Authenticated SDK reads on the connected phone passed for profile, targets, diary, catalogue and usage; see VERIFICATION.md for the final device results. SMTP/token-template configuration is no longer a dependency of this chosen login path.

## Inspected state and exact changes

Before deployment hosted Auth had one existing user and none of the six canonical public table names existed. The existing private schema contains two unrelated legacy recalculation functions, with EXECUTE only for postgres; no JetMeal private names collided. The operation migration revokes only its named JetMeal functions, preserving legacy functions and policies.

Apply repository migrations in filename order:

1. `20261005213154_jetmeal_initial.sql`: new canonical tables, ownership RLS and future-user profile trigger.
2. `20261005213247_nutrition_operations.sql`: atomic typed operations, audit/undo, immutable quantity basis, confirmation challenges and ownership constraints.
3. `20261005215217_estimated_snapshot.sql`: immutable estimation history marker.
4. `20261005222903_existing_auth_profiles.sql`: profiles for existing Auth IDs, without assigning a guessed location or reading user metadata for authorization. UTC is the schema default until Android synchronizes its system timezone.
5. `20261005223028_import_owned_legacy_nutrition.sql`: additive, complete, explicitly owned imports described below. All source rows and schemas remain intact.
6. `20261005224152_reject_anonymous_identity.sql`: all canonical ownership policies and the protected dispatcher reject anonymous signed identities using trusted top-level JWT claims, independently of Auth project settings or user-editable metadata.
7. `20261005224645_canonical_rls_initplan_and_variant_index.sql`: canonical JWT caching expression and covering index for the actual diary variant/owner FK. `meal_group_id` has no FK and already has its query index.

| Source | Original rows | Canonical copy |
| --- | ---: | --- |
| products | 5 | 5 foods + 5 variants; each retains the original UUID, owner, name, brand, source type and estimation status |
| meal_log | 16 | 15 complete owned diary rows; one record missing macros remains only in the original table |
| daily_targets | 2 | Current owner's latest effective complete target: 2000 kcal, 190 g protein, 65 g fat, 163.8 g carbs |
| food_catalog | 557 | None: source has no ownership column |
| meal_entries | 2 | None: source has no ownership column and is a separate diary source |
| daily_goals | 1 | None: no ownership column and missing macros |

Products explicitly contain nutrition per 100 g, so imported variants use a 100 g basis; existing serving-size/other metadata is retained verbatim in the variant source note. This does not guess nutrition for a package. The original serving quantities remain available in legacy records. The variant's `is_estimated` comes from the original `nutrition_status` (`verified`/`estimated`). Unrecognized or incomplete product nutrition is skipped rather than filled with zero.

Diary rows preserve their own stored grams and calorie/macro totals as the immutable basis, even when a current product has different nutrition. They optionally retain an owned semantic food link but do not attach a catalogue variant as if its current nutrition had been the historical basis. Legacy `logged_at` becomes `consumed_at`; breakfast/lunch/dinner/snack map directly to morning/day/evening/snack. Original timestamps and source metadata remain in the append-only import audit. For estimation presentation, explicit official_pdf/label/menu records are marked measured; otherwise a linked product retains its estimate marker and standalone undocumented records are conservatively marked estimated. No confidence score is invented.

Only `meal_log` with explicit owners is imported, so the ownerless `meal_entries` source is not merged or counted again. The incomplete meal is retained in its original table for later owner-confirmed nutritional correction; it is excluded from the new app rather than receiving invented macros.

Target import selects the latest effective source record per explicit owner, and skips an incomplete current record rather than silently falling back to an older value. ±10% is the MVP's documented new redistribution default; obsolete activity-burn/weight-loss/carryover rules are not transferred. No TDEE or nutrition recommendation is calculated.

Imports preserve their source UUIDs and use conflict-safe inserts. Their system audit events retain provenance but are not inserted into undoable user-action groups. A repeated import does not duplicate foods, history, targets or audit events. Existing canonical targets are never overwritten by the import.

## Proof completed before remote deployment

`python3 scripts/verify_additive_deployment.py` passed all seven migrations against a newly created disposable local database using actual Supabase Auth definitions, pre-existing users and legacy fixtures. It verified existing source-row fingerprints, unrelated private-function privileges and the legacy RLS policy remained unchanged; only complete owned rows imported; historical diary nutrition was preserved; incomplete latest targets did not fall back; import replay was idempotent. All 64 pgTAP assertions passed, including signed anonymous access denial and trusted-claim/user-metadata separation. This did not reset or alter the active app's local database.

`supabase/verification/hosted_rls_rollback.sql` passed against the selected hosted project after all seven migrations. It assumes no fixed owner UUID, creates a temporary second owner, tests authenticated PostgreSQL-role isolation, real scaling/correction/delete/undo and target challenges, then rejects that signed anonymous identity. It rolls back every test write. Post-check counts remained one Auth user/profile, five foods/variants, 15 diary rows and one target; original counts in the table above remained unchanged. These results prove database/RLS behavior; they do **not** prove hosted password login, token refresh, Android session persistence or an authenticated mobile connection.

The Android build uses only the selected hosted URL and publishable client key through ignored developer configuration. After all seven migrations, hosted security advisors do not flag canonical anonymous access. Remaining security warnings concern anonymous access in the existing cron.job/cron.job_run_details policies and the project's disabled leaked-password protection. Two internal JetMeal tables intentionally have RLS with no allow policy: they are outside exposed schemas, have no normal-client table grants and are accessed only by owner-checking application operations. Legacy ownerless daily_goals/meal_entries likewise retain their existing deny-by-default state. The four no-policy findings are informational.

Hosted performance advisors were rerun after the seventh migration: all 16 canonical RLS initialization-plan notices and the diary variant/owner FK notice are resolved. They retain 37 legacy RLS initialization-plan warnings, five legacy unindexed-FK informational findings and 20 unused-index informational findings, including newly deployed canonical indexes with no production traffic yet. No legacy policy, index or function was changed to silence an unrelated advisor.

Advisor references: [RLS initialization plans](https://supabase.com/docs/guides/database/database-linter?lint=0003_auth_rls_initplan), [foreign key indexes](https://supabase.com/docs/guides/database/database-linter?lint=0001_unindexed_foreign_keys), [deny-by-default RLS](https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy), [password protection](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection).
