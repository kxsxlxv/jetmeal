package com.kxsxlxv.jetmeal.voice

/** Google ML Kit may rewrite partial hypotheses on each streaming event.
 * Final segments are the only text retained after partial replacement. */
internal data class StreamingTranscript(
    val confirmed: String = "",
    val partial: String = "",
    val finalSegments: Int = 0,
    val partialUpdates: Int = 0,
) {
    val visible: String get() = listOf(confirmed, partial)
        .filter(String::isNotBlank).joinToString(" ")

    fun onPartial(text: String): StreamingTranscript =
        copy(partial = text.trim(), partialUpdates = partialUpdates + 1)

    fun onFinal(text: String): StreamingTranscript {
        val segment = text.trim()
        return copy(
            confirmed = listOf(confirmed, segment).filter(String::isNotBlank).joinToString(" "),
            partial = "",
            finalSegments = if (segment.isEmpty()) finalSegments else finalSegments + 1,
        )
    }
}
