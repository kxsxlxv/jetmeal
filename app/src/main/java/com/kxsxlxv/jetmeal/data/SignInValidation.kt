package com.kxsxlxv.jetmeal.data

internal object SignInValidation {
    private val emailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun canSubmit(email: String, password: String): Boolean =
        emailPattern.matches(email.trim()) && password.isNotBlank()

    /** Only the email is normalized. The password must reach Supabase exactly as entered. */
    fun normalizedEmail(email: String, password: String): String {
        val normalized = email.trim()
        require(emailPattern.matches(normalized)) { "Enter a valid email address." }
        require(password.isNotBlank()) { "Enter your password." }
        return normalized
    }
}
