# Supabase backend and local verification

Research/implementation date: 2026-10-06 (Europe/Istanbul).

The user selected the existing hosted legacy nutrition project for deployment, and subsequently chose email/password authentication for its existing account. See `HOSTED_DEPLOYMENT.md` for additive migration, import, hosted RLS verification and credential-setup status. The local OTP checks below record the original implementation verification; they do not prove the newly chosen hosted password login.

The real local stack uses official Supabase CLI 2.119.0, PostgreSQL 17, Auth, PostgREST, Kong and Mailpit. `supabase/config.toml` disables unused services. Both signup and returning-user email templates show `{{ .Token }}`, so the app enters a code rather than requiring a browser link. Refresh-token rotation is enabled. Never check in CLI status output, session tokens or signing keys.

## Reproduce

On Windows this host's Docker daemon runs inside WSL Ubuntu. Execute the CLI and Docker commands there, with the repository at `/mnt/c/Users/kisel/AndroidStudioProjects/jetmeal`. In a normal Linux/macOS environment execute from the repository directory.

```sh
supabase start
supabase db reset --local
supabase test db --local
supabase db lint --local
supabase db advisors --local --type all --level warn --fail-on error
python3 scripts/verify_local_supabase.py --cli supabase
python3 scripts/configure_local_supabase.py --cli supabase
```

If the default ECR image route times out, this CLI supports `SUPABASE_INTERNAL_IMAGE_REGISTRY=docker.io supabase start`; it maps the same official services to their Docker Hub image repositories. The initial ECR attempt on this host encountered CloudFront response-header timeouts; the Docker Hub route downloaded images successfully.

On this Windows/WSL host, keeping only Docker/systemd services running did not keep WSL alive after the last `wsl.exe` invocation exited. The distro repeatedly shut down gracefully, recreated Windows localhost forwarding on boot and restarted containers together. Auth's initial template reload could then race Kong's template HTTP server (8088), causing transient OTP failures. Keep one foreground WSL process or terminal alive throughout device integration testing; wait for Auth/Kong/database health before requesting the code. A held WSL `sleep` process stabilized this session and a fresh complete two-user OTP integration passed again. This is a development-host lifecycle requirement, not an app/backend mock or a schema reset.

This session holds `wsl -d Ubuntu -- bash -lc 'exec sleep 28800'` in background tool process session `89313`, keeping WSL available for eight hours while Android testing and visual review continue. For later sessions, leave the same command running in a terminal during local development or hold another WSL foreground process. CLI installation was moved from transient `/tmp` into persistent `/home/kisel/.local/bin/jetmeal-cli/supabase` after the first reboot. Container volumes survive WSL shutdown; there is no reason to reset user data when fixing host lifecycle or readiness.

`bootstrap.sql` is the concatenation of the seven repository migrations; migrations are the reset/deployment history. Fresh projects skip legacy import automatically when those source tables do not exist. No runtime food fixtures or fake authentication are supplied. pgTAP fixtures exist only inside a rolled-back database test transaction. The original HTTP integration script creates two real local users through Email OTP and their test food data; reset clears that isolated local data.

## Operation interface

Public RPCs all accept one named argument `p_input` (JSON object) and return `{ok, action_id, data, warnings, undoable}`. PostgREST validation/authorization failures use HTTP errors and roll back every write in the operation.

| RPC suffix (`jetmeal_…`) | Input fields | `data` |
| --- | --- | --- |
| `create_food` | name, kind (optional generic), brand/source optional, serving_amount, serving_unit, calories_kcal, protein_g, fat_g, carbs_g, is_estimated/source_note optional | food and variant rows |
| `log_food` | food_variant_id, quantity, consumed_at; meal_type, quantity_unit, meal_group_id, confidence optional | diary row |
| `log_food` estimate | snapshot_name, snapshot_brand optional, quantity, quantity_unit, calories, protein_g, fat_g, carbs_g, consumed_at, confidence optional | diary row |
| `update_log` | entry_id; quantity, consumed_at, meal_type optional | diary row |
| `delete_log` | entry_id | soft-deleted diary row |
| `repeat_meal` | entry_ids UUID array, consumed_at, meal_type optional | array of copied diary rows |
| `undo_last_action` | empty object | undone_action_id and affected count |
| `prepare_targets` | all five target values | confirmation UUID |
| `update_targets` | same five target values plus confirmation UUID | nutrition_targets row |

Every mutation optionally accepts `action_id` to group several writes for one intent, and `actor` (`user`/`ai`). Ownership always comes from `auth.uid()`. Clients read owned raw tables with RLS and update only `profiles.timezone` directly. All nutrition table mutations and audit inserts are forbidden directly; typed RPCs write audit snapshots atomically. Basis snapshots cannot change even through privileged updates, totals recalculate from that basis, variant ownership and food identity use composite FKs, and all numeric values must be finite and bounded. `is_estimated_snapshot` is immutable history metadata: copied from the selected variant, true for ad-hoc estimates even without confidence, retained when copying meals. User writes/undo are serialized using their profile row lock. Undo reverses every audit event in reverse insertion order and adds new append-only undo events.

Target confirmation is a one-use, five-minute challenge bound to the exact proposed values and owner. The application must obtain it only after its human confirmation state accepts the proposal. A challenge alone cannot establish human provenance: a future external AI transport must restrict access to the preparation path and enforce its own confirmation UI. This remains dependent on the explicitly open external ChatGPT linking design.

The Kotlin application computes deterministic weekly budgets, read/search responses and display day boundaries; the database also infers omitted meal periods using the synchronized profile timezone. No unsettled calendar adherence threshold is embedded in the schema.

## Verification results

Executed against the real local stack:

- Final `db reset --local`: all seven migrations reproduced successfully from a fresh database, followed by 64 passing pgTAP assertions. Earlier migration-up checks also preserved existing local data; the separate disposable deployment proof below verifies import behavior with pre-existing legacy sources. The root agent performed the final active-stack reset before its latest Android rerun.
- Latest database suite: PASS, 64 pgTAP assertions against all seven migrations in the isolated additive-deployment database. Covers two-user ownership/RLS, protected audit, immutable basis and estimate marker, quantity validation/scaling, nonfinite values and unit mismatch, timezone inference/override, targets confirmation/replay rejection, soft delete and undo, multi-row intent undo, AI estimate basis, repeat meal and owned catalogue writes. New checks seed an anonymous Auth user with owned rows, prove signed anonymous reads/profile edits/RPCs fail, and prove user metadata cannot override trusted JWT authorization. A privileged catalogue-value/estimation change verifies subsequent quantity edits still use historical basis and marker.
- `db lint --local --schema public,private --fail-on error`: no schema errors.
- `db advisors --local --type all --level warn --fail-on error`: no issues.
- `python3 scripts/verify_local_supabase.py`: PASS. Two real OTP emails captured in Mailpit and verified by Auth; authenticated catalogue → variant → diary → local-day read; cross-user API failures; 125 g/300 g scaling, quantity correction, soft deletion/undo; one-use target confirmation; audit protection; anonymous rejection; token refresh and refresh revocation after sign-out.
- `configure_local_supabase.py`: wrote only API URL and client publishable key into ignored `jetmeal.local.properties`; no privileged key in Android configuration.

Expiry timing of the one-hour JWT is not claimed verified by an immediate smoke test; sign-out revokes refresh and the client must clear its local session, while already-issued JWTs remain valid until expiry as documented by Supabase. Android-specific client/session persistence and device UI paths are verified separately by the Android implementation; this script uses the same live Auth/PostgREST HTTP protocols and does not substitute a fake repository.

## Official sources consulted

- [Supabase changelog](https://supabase.com/changelog.md)
- [Local CLI workflow](https://supabase.com/docs/guides/local-development/cli/getting-started)
- [Testing, pgTAP and lint](https://supabase.com/docs/guides/local-development/cli/testing-and-linting)
- [Email passwordless OTP](https://supabase.com/docs/guides/auth/auth-email-passwordless)
- [RLS](https://supabase.com/docs/guides/database/postgres/row-level-security)
- [Current CLI releases](https://github.com/supabase/cli/releases)
