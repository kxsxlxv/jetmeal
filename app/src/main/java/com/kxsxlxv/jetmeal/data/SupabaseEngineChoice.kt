package com.kxsxlxv.jetmeal.data

import android.content.Context

/**
 * Select at process startup only: do not create two Supabase clients with the
 * same rotating refresh token. This preference never contains credentials.
 */
internal object SupabaseEngineChoice {
    private const val FILE = "jetmeal_http_engine"
    private const val KEY = "use_cio"

    fun isCio(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setCio(context: Context, selected: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY, selected).apply()
    }
}
