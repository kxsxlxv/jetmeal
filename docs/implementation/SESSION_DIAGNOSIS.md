# Session and connectivity diagnosis — 2026-10-08

## What the incident evidence can establish

The owner described daytime failures on mobile data, without Wi-Fi or VPN: first session restoration, then connectivity errors. No exact device timestamp or old device-side trace was available. Hosted inspection on October 8 found successful refresh/password grants and successful diary/catalogue/target/profile requests in the preceding 24 hours, with no Auth or edge HTTP errors in the inspected window. The project was healthy. Existing session rows were not timeboxed; the connector did not expose all Auth configuration, so this does not establish that inactivity or single-session limits are disabled.

This evidence does **not** identify an historical provider/mobile-network fault. Requests that fail during DNS, TLS or transport may never reach the hosted logs. Nor does a successful HTTP response prove the phone received and processed it. The old app combined these possibilities with session revocation in its message, making attribution impossible.

## Confirmed client defects

1. In the pinned supabase-kt 3.8.0 SDK, Android lifecycle `onStop` stops token refresh and sets `SessionStatus.Initializing`. The application shares this client with headless WorkManager widget tasks. A background task's `awaitInitialization()` can then wait for an Activity `onStart`, before any HTTP request exists. Request timeouts cannot terminate that wait.
2. After an expired saved token fails to refresh because of network/server trouble, SDK `RefreshFailure` means it retains storage and automatically retries. JetMeal previously cleared `authLoading` while its email remained null at cold startup, thus showing the password-login screen as if the session had ended. Widget sync likewise interpreted `currentUserOrNull() == null` during recovery as signed out and replaced its snapshot.
3. The SDK 3.8.0 auto-refresh catch block clears stored sessions for REST statuses outside a fixed 5xx list. An expired saved session plus HTTP 429 reproducibly deleted the session and emitted `NotAuthenticated`. HTTP 408, additional 5xx statuses, and a structured `request_timeout` can take the same terminal path. This is a reproducible failure mode, **not evidence that the owner's incident received HTTP 429**.
4. Every `Authenticated` emission triggered a complete diary/catalogue reload, including JWT rotation for the same owner. This could cancel a foreground refresh despite unchanged food data.
5. SDK `KtorSupabaseHttpClient` replaces transport exceptions with `HttpRequestException` without preserving their cause. Classifying only the resulting Auth event loses DNS/TLS details; it also hides the typed temporary response status. Auth diagnostics therefore capture safe metadata in Ktor response/exception validation before this wrapper, then retain that metadata when SDK events arrive.

## Changes

- The one application-scoped Auth client uses process-scoped SDK refresh (`enableLifecycleCallbacks = false`), supporting Activity and headless widget consumers without a second client or independent token-refresh loop.
- A narrow Ktor response validator handles only failed `/auth/v1/token?grant_type=refresh_token` responses. HTTP 408/425/429/5xx and known temporary Auth codes raise a typed, payload-free retryable exception before SDK 3.8.0's destructive REST catch. The SDK retains its own retry loop and session storage. Real revoked-token rejection remains terminal. Password grants and nutrition mutations are unchanged.
- Expired-session recovery has a separate UI state. Cold startup shows the failure category and automatic retry; an already open diary retains its data. The recovery view can open Android connection settings. It does not start a competing manual token refresh. Widget recovery preserves its last snapshot and schedules a retry.
- Same-owner JWT rotation updates Auth state without reloading the diary. First sign-in and recovery still load data.
- Safe diagnostics retain the most recent 60 local events: UTC time, operation/state, DNS/TLS/timeout/transport/Auth/service category, HTTP status, allowlisted Auth code, UUID request reference, exception class and coarse connectivity state. No addresses, network names, IPs, request URLs/bodies, passwords or tokens are recorded. SDK exception text is suppressed. All shared preferences remain excluded from backup.
- Refresh diagnostics are captured before SDK exception wrapping. Tests verify injected DNS/TLS and HTTP 429 through the actual SDK retain their original safe categories and response reference, and that DNS remains visible in the ViewModel's recovery message.
- Expand **Диагностика подключения** in Settings, login or recovery; **Скопировать диагностику** explicitly copies this safe history and also works in release builds. A backend request UUID can be correlated with hosted logs. `network=cellular:validated` indicates Android's general connectivity validation, not proof that Supabase was reachable.

The response guard is a workaround for pinned SDK **3.8.0**. On a future SDK update, run the raw and guarded regression cases; remove the guard only after upstream retains storage for temporary refresh failures and still clears revoked sessions.

## Verification scope

Unit tests exercise the actual SDK and ViewModel with **test-only** HTTP/storage fixtures: the destructive raw-429 reproduction, guarded 408/429/5xx/request_timeout retention, revoked-token removal, offline expired-session restoration without login, headless initialization, unchanged password-grant errors, same-owner rotation without a second data load, and metadata/error-message safety. These tests are not substitutes for real Auth/RLS integration.

`SessionLifecycleIntegrationTest` uses a generated account on the real local stack, an actual Activity/process `ON_STOP`, the shared application client and actual widget reads. Its execution result, the complete Android/database pipeline and hosted read results are recorded by the task's final verification report.

## Sources inspected

- [Pinned SDK AuthImpl](https://github.com/supabase-community/supabase-kt/blob/3.8.0/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/AuthImpl.kt), [Android lifecycle implementation](https://github.com/supabase-community/supabase-kt/blob/3.8.0/Auth/src/androidMain/kotlin/io/github/jan/supabase/auth/setupPlatform.kt), [Auth configuration](https://github.com/supabase-community/supabase-kt/blob/3.8.0/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/AuthConfig.kt). The matching cached source archive was read directly.
- [Supabase session lifetime, refresh rotation and reuse protection](https://supabase.com/docs/guides/auth/sessions).
- [Ktor 3.6 response validation](https://ktor.io/docs/client-response-validation.html).
- [Supabase Auth events reference](https://supabase.com/docs/reference/kotlin/auth-onauthstatechange), checked against pinned SDK sources because the documentation index still contains obsolete status names.

The Supabase changelog markdown fetch was attempted through the web tool and host HTTPS; the former rejected its content type and the latter's connection was interrupted. No dependency or hosted Auth-setting change was made on an unverified changelog assumption.
