package com.kxsxlxv.jetmeal

import android.app.Application
import android.app.LocaleManager
import android.os.LocaleList
import com.russhwolf.settings.SharedPreferencesSettings
import com.kxsxlxv.jetmeal.data.ProjectSessionStorage
import com.kxsxlxv.jetmeal.data.SupabaseRepository
import com.kxsxlxv.jetmeal.data.PicoocIntegration
import com.kxsxlxv.jetmeal.data.OfflineDiary
import com.kxsxlxv.jetmeal.data.ConnectionDiagnostics
import com.kxsxlxv.jetmeal.data.SafeSupabaseLogger
import com.kxsxlxv.jetmeal.data.SafeNetworkListenerFactory
import com.kxsxlxv.jetmeal.data.protectSessionRefresh
import com.kxsxlxv.jetmeal.widget.HeroWidgetCoordinator
import com.kxsxlxv.jetmeal.widget.HeroWidgetScheduler
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class JetMealApplication : Application() {
    internal val connectionDiagnostics by lazy { ConnectionDiagnostics(this) }
    override fun onCreate() {
        super.onCreate()
        // The product UI is Russian, including framework/Material accessibility strings.
        // App-local configuration leaves system language, timezone and night mode intact.
        val localeManager = getSystemService(LocaleManager::class.java)
        val russian = LocaleList.forLanguageTags("ru-RU")
        if (localeManager.applicationLocales != russian) {
            localeManager.applicationLocales = russian
        }
        HeroWidgetScheduler.ensurePeriodicIfPresent(this)
    }

    val repository: SupabaseRepository? by lazy {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_KEY.isBlank()) null
        else {
            require(!BuildConfig.SUPABASE_KEY.startsWith("sb_secret_")) { "Only a publishable client key is allowed." }
            SupabaseRepository(createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
                httpEngine = OkHttp.create {
                    config {
                        // OkHttp's 10s default read timeout caused partial HTTP 200 responses
                        // to fail after headers had already reached the device.
                        // Keep the transport limit above Supabase/Ktor's request deadline.
                        connectTimeout(10, TimeUnit.SECONDS)
                        readTimeout(25, TimeUnit.SECONDS)
                        writeTimeout(25, TimeUnit.SECONDS)
                        callTimeout(30, TimeUnit.SECONDS)
                        eventListenerFactory(SafeNetworkListenerFactory(connectionDiagnostics))
                    }
                }
                defaultLoggingFactory = { SafeSupabaseLogger(connectionDiagnostics) }
                protectSessionRefresh(connectionDiagnostics::captureAuthFailure)
                // Total request deadline is shorter than OkHttp's read/call deadlines.
                requestTimeout = 25.seconds
                install(Auth) {
                    // This same client serves headless WorkManager widgets. SDK Android hooks
                    // reset status to Initializing in background and wait for an Activity to
                    // restore it. Process-scoped SDK refresh needs neither Activity nor a
                    // second client competing for the rotating refresh token.
                    enableLifecycleCallbacks = false
                    // A separate preference file avoids the SDK's migration of legacy unscoped sessions.
                    // Keep its name independent of app version and publishable-key rotations.
                    sessionManager = SettingsSessionManager(SharedPreferencesSettings(
                        getSharedPreferences(ProjectSessionStorage.namespace(BuildConfig.SUPABASE_URL), MODE_PRIVATE)
                    ))
                }
                install(Postgrest) {
                    timeout = 25.seconds
                    // Retry only explicit, idempotent reads in the repository; never replay writes here.
                    maxRetries = 0
                }
            }).also {
                it.diagnostics = connectionDiagnostics
                it.authFailures = connectionDiagnostics.authFailures
            }
        }
    }

    val offlineDiary: OfflineDiary by lazy { OfflineDiary(this,repository) }

    val picoocIntegration: PicoocIntegration by lazy { PicoocIntegration(this, repository) }

    val widgetCoordinator: HeroWidgetCoordinator by lazy {
        HeroWidgetCoordinator(this, repository, offlineDiary)
    }
}
