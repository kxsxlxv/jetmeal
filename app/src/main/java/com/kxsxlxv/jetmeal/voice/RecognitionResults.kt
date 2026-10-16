package com.kxsxlxv.jetmeal.voice

/** A callback with no non-blank transcription is NOT a successful ASR run.
 * This was the cause of the misleading "Получен текст" result on vivo X300.
 */
internal fun bestRecognitionText(alternatives: List<String>?): String? =
    alternatives.orEmpty().firstOrNull { it.isNotBlank() }?.trim()?.takeIf(String::isNotEmpty)
