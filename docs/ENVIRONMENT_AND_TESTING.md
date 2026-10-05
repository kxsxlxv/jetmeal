# Environment, Supabase access, and testing

This document defines how Codex should determine whether it has enough infrastructure to implement and verify JetMeal honestly.

## Principle

Do not assume that being signed into Codex with the same ChatGPT account automatically grants the Codex environment the same app/plugin/MCP access as another ChatGPT conversation.

Access must be detected in the active Codex environment.

Missing access is a blocker to that integration path, not permission to invent a runtime mock.

## Preferred Supabase development model

Use a **real local Supabase stack** as the default development/integration environment whenever the Codex host has a Docker-compatible container runtime.

This gives the agent real Postgres, Auth, RLS and email capture without needing production credentials. It is not a mock backend.

Hosted Supabase access is useful for:

- inspecting an existing user project;
- comparing the remote schema/configuration;
- applying reviewed migrations/config changes;
- performing a final remote smoke test.

Hosted access is not required to prove most repository-level behavior if the local stack is available and configured faithfully.

## Access modes to check

Check these in order and record what is actually available.

### 1. Supabase MCP / plugin

Supabase provides an MCP server specifically so AI assistants can inspect and act on Supabase projects.

If a Supabase MCP/plugin is already available in Codex, use it for hosted-project inspection/actions as appropriate.

If it exists but is not authenticated, tell the user that the MCP connection needs authorization. Do not request long-lived credentials in chat and do not paste credentials into repository files.

Current setup entry point:

- https://supabase.com/docs/guides/getting-started/mcp

### 2. Authenticated Supabase CLI

If the Supabase CLI is installed, inspect its current command surface with `supabase --help` (or the project-local package-runner equivalent) before assuming command names.

If hosted access is needed, determine whether the CLI is already authenticated and whether the intended project is linked/identifiable.

If login/link authorization is missing, report that exact requirement before trying to deploy remote changes.

Do not commit CLI tokens, database passwords, or project-owner credentials.

### 3. Local Supabase stack

If a Docker-compatible runtime is available, initialize/use the repository's `supabase/` local-development configuration and run the local stack according to current Supabase CLI documentation.

The current official workflow uses a repository-local `supabase/config.toml` and a local stack started by the Supabase CLI. Verify the exact current commands via `--help` and official docs rather than relying on memory.

Current documentation entry points:

- https://supabase.com/docs/guides/local-development
- https://supabase.com/docs/guides/local-development/cli/getting-started
- https://supabase.com/docs/guides/local-development/cli-workflows

## Mandatory preflight checks

Run/inspect the current equivalents of the following and report failures that matter:

```text
git status
./gradlew --version
java -version
<Android SDK / adb availability>
<container runtime> --version
<container runtime health check>
supabase --help    # or npx/pnpm/yarn/bun equivalent when project-local
```

Also inspect whether the Codex session exposes MCP/plugin tools for Supabase.

If the environment has no network path to current official Android/Supabase documentation, report that before making "latest" dependency/design claims.

## Repository Supabase workflow

`supabase/bootstrap.sql` is the canonical initial schema intent, not permanent migration history.

During implementation:

1. initialize current Supabase local-development metadata if not already present;
2. create proper migration files using the **current CLI-supported migration workflow**;
3. make migrations reproduce the intended schema/RLS from an empty local database;
4. keep `bootstrap.sql` aligned with the intended fresh-project schema unless the project explicitly moves away from maintaining it;
5. use local reset/rebuild to prove migrations are reproducible;
6. add database tests for ownership/RLS/business invariants;
7. run current Supabase lint/advisor tooling where available.

Do not invent migration filenames or deprecated CLI syntax from memory. Discover commands with `--help` and use current official docs.

## Auth: existing-account email/password

The product owner's 2026-10-06 decision replaces the earlier OTP UI with **email/password sign-in for the existing account**. The app exposes only email, password and Sign in. It does not provision accounts, send login emails, or offer signup/reset-password UI. The existing hosted account must already have a password and confirmed email; perform any explicitly authorized account update through the administrative environment, never a privileged mobile key.

Current Supabase Kotlin flow should be verified in official docs at implementation time. Current entry points:

- https://supabase.com/docs/reference/kotlin/auth-signinwithpassword
- https://supabase.com/docs/guides/auth/passwords
- https://supabase.com/docs/reference/kotlin/initializing

Use `auth.signInWith(Email) { email = address; password = enteredPassword }`. Normalize email only; send the password exactly as entered. Keep it in temporary UI memory, never saved instance/ViewModel state, configuration, preferences, screenshots or logs. Store only the authenticated SDK session in private project-specific preferences excluded from backups. Changing project URL must not reuse a different project's JWT.

### Real local Auth testing

Use real local Supabase Auth to create isolated test-only email/password accounts via the SDK, confirm them through captured local mail when required, sign out, and then exercise the app's password login UI. Confirm that typing does not authenticate, blank input cannot submit, password whitespace is preserved, and session-backed operations/RLS and session reopening work.

The lower-level repository/RLS integration test may still use the SDK's OTP methods directly to create two independent authenticated local test users. This is test-only setup, not an app login feature. Mailpit capture and any confirmation token must remain within local test source sets. Production has no OTP request/verification methods.

Do not hard-code a password or token into production code or use a fake account/session as integration evidence. Local fixture passwords are generated at test runtime and never logged. Cloud-configured builds intentionally skip local Auth tests; a skipped test is not a pass.

Official testing entry point:

- https://supabase.com/docs/guides/local-development/cli/testing-and-linting

## Android project configuration

The public repository must not contain personal hosted-project credentials.

The Android app may need client-safe configuration such as:

- Supabase project URL;
- current publishable client key.

Provide these through an uncommitted developer configuration mechanism suitable for Android/Gradle. Check in only a template/example and document setup.

Never place privileged server/database credentials in:

- Kotlin source;
- `strings.xml` or other packaged resources;
- committed Gradle properties;
- committed `.env` files;
- test fixtures that are part of the repository;
- screenshots/log output.

Ensure relevant local files are ignored by Git.

## RLS integration tests

Use at least two authenticated test users against the real local Supabase stack.

Prove both positive and negative behavior:

- user A can read/write A-owned foods/variants/diary/targets as intended;
- user B can read/write B-owned data;
- A cannot read B data;
- A cannot update/delete B data through exposed operations;
- attempts to spoof `owner_id` fail;
- target updates obey ownership;
- audit access follows the intended policy;
- soft-deleted diary entries do not appear in active queries/totals.

A successful client call against a fake repository does not satisfy these tests.

## Database tests

Use the current Supabase-supported database-test mechanism where practical (currently pgTAP through the Supabase CLI) and current database lint/advisor tooling.

Tests should validate schema constraints and RLS independently from Android UI tests.

## Android integration testing

The repository implementation should have a real Supabase repository client.

When local Supabase is running, configure a development/test build to reach that local instance without changing production configuration. Determine the correct host routing for the actual test environment (local JVM, Android emulator, physical device, or Codex-hosted emulator) and document the choice. Do not assume desktop `localhost` means the same thing inside an Android emulator/device.

Verify at least one end-to-end repository path against real Supabase:

```text
Auth session -> RLS -> food/variant read -> diary write -> day read
```

Also verify quantity correction and soft deletion against the real data layer.

## Hosted Supabase policy

Do not make destructive or schema-changing writes to a hosted user project merely to see whether access works.

Before applying remote migrations/configuration:

1. establish that the intended project is unambiguous;
2. inspect current remote state;
3. compare expected changes;
4. ensure the action is authorized by the task/user context;
5. preserve user data;
6. run post-change security/performance advisors where the available Supabase tooling supports them;
7. run a small authenticated smoke test.

If hosted access is unavailable, say so early. Complete and verify the local integration if possible, then report remote deployment as unverified rather than fabricating a success path.

## What counts as a blocker

Report immediately when a missing capability prevents honest implementation/verification, for example:

- no current documentation/network access for the required "latest" research;
- no container runtime and no hosted Supabase access;
- no way to authenticate/link the intended hosted project when a remote operation is required;
- missing Android SDK/JDK required by chosen current toolchain;
- missing emulator/device for a verification that cannot be replaced by a JVM/local integration test.

A blocker report should be specific. Good example:

> Hosted Supabase is not accessible in this Codex session: no Supabase MCP tool is present and `supabase projects list` is unauthenticated. Local Docker/Supabase is available, so I can implement and integration-test against the local stack, but I cannot apply or smoke-test the hosted project until you connect Supabase MCP or authorize the CLI.

Do not respond with a vague "I need Supabase credentials" when a safer connector/MCP/CLI authorization flow is available.
