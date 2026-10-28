package com.kxsxlxv.jetmeal.data

/**
 * Safe exception metadata captured *before* supabase-kt replaces its cause.
 *
 * No request address, path, HTTP headers, response bodies, exception messages,
 * IPs, tokens or arbitrary toString() values enter this report.
 */
internal fun safeKtorFailureLine(path: String, error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(12).toList()
    val root = chain.last()
    fun typeName(failure: Throwable): String =
        failure.javaClass.simpleName.takeIf { Regex("[A-Za-z0-9_]{1,64}").matches(it) }
            ?: "Unknown"
    return "layer=Ktor route=${routeForPath(path)}" +
        " cause=${networkCause(error)}" +
        " kind=${ConnectionFailure.from(error).kind}" +
        " exception=${typeName(error)}" +
        " root=${typeName(root)}"
}
