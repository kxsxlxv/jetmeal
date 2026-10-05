package com.kxsxlxv.jetmeal.data

import java.net.URI
import java.security.MessageDigest

/** Stable across app updates and key rotations; separate endpoints never share Auth storage. */
internal object ProjectSessionStorage {
    fun namespace(projectUrl: String): String {
        val uri = URI(projectUrl.trim())
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        val port = when {
            uri.port == -1 || uri.scheme == "https" && uri.port == 443 || uri.scheme == "http" && uri.port == 80 -> ""
            else -> ":${uri.port}"
        }
        val endpoint = "${uri.scheme.lowercase()}://${uri.host.lowercase()}$port${uri.rawPath.orEmpty().trimEnd('/')}"
        val digest = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "jetmeal_auth_$digest"
    }
}
