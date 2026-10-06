package com.kxsxlxv.jetmeal

import android.app.Application
import android.app.LocaleManager
import android.os.LocaleList
import com.russhwolf.settings.SharedPreferencesSettings
import com.kxsxlxv.jetmeal.data.ProjectSessionStorage
import com.kxsxlxv.jetmeal.data.SupabaseRepository
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.time.Duration.Companion.seconds

class JetMealApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The product UI is Russian, including framework/Material accessibility strings.
        // App-local configuration leaves system language, timezone and night mode intact.
        val localeManager = getSystemService(LocaleManager::class.java)
        val russian = LocaleList.forLanguageTags("ru-RU")
        if (localeManager.applicationLocales != russian) {
            localeManager.applicationLocales = russian
        }
    }

    val repository: SupabaseRepository? by lazy {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_KEY.isBlank()) null
        else {
            require(!BuildConfig.SUPABASE_KEY.startsWith("sb_secret_")) { "Only a publishable client key is allowed." }
            SupabaseRepository(createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
                httpEngine = OkHttp.create()
                // Bound Auth/refresh and other HTTP calls; Postgrest has its own override below.
                requestTimeout = 15.seconds
                install(Auth) {
                    // A separate preference file avoids the SDK's migration of legacy unscoped sessions.
                    // Keep its name independent of app version and publishable-key rotations.
                    sessionManager = SettingsSessionManager(SharedPreferencesSettings(
                        getSharedPreferences(ProjectSessionStorage.namespace(BuildConfig.SUPABASE_URL), MODE_PRIVATE)
                    ))
                }
                install(Postgrest) {
                    timeout = 15.seconds
                    // Foreground requests return promptly; the user can explicitly retry.
                    maxRetries = 0
                }
            })
        }
    }
}
