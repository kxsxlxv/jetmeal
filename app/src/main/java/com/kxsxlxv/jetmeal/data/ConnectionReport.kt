package com.kxsxlxv.jetmeal.data

/** Small clipboard-safe excerpt; full diagnostic events are exported as a UTF-8 file. */
internal fun lastConnectionEvents(events: String, count: Int): String {
    require(count > 0)
    return events.lineSequence().filter { it.isNotBlank() }.toList()
        .takeLast(count).joinToString("\n")
}
