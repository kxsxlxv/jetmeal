package com.kxsxlxv.jetmeal.voice

import org.junit.Assert.*
import org.junit.Test

class StreamingTranscriptTest {
    @Test fun partialHypothesesReplacePreviousWithoutDuplicatingWords() {
        val first=StreamingTranscript().onPartial("три")
        val second=first.onPartial("триста граммов")
        assertEquals("триста граммов",second.visible)
        assertEquals("",second.confirmed)
        assertEquals(2,second.partialUpdates)
    }

    @Test fun finalChunksAccumulateAndNextPartialDoesNotReplaceEarlierFinals() {
        val transcript=StreamingTranscript().onPartial("триста")
            .onFinal("триста граммов творога")
            .onPartial("два яйца")
        assertEquals("триста граммов творога два яйца",transcript.visible)
        assertEquals("триста граммов творога",transcript.confirmed)
        val complete=transcript.onFinal("два больших яйца")
        assertEquals("триста граммов творога два больших яйца",complete.visible)
        assertEquals("",complete.partial)
        assertEquals(2,complete.finalSegments)
    }

    @Test fun emptyFinalDoesNotDestroyEarlierText() {
        val transcript=StreamingTranscript().onFinal("два яйца")
            .onPartial("возможно, творог").onFinal("  ")
        assertEquals("два яйца",transcript.visible)
        assertEquals(1,transcript.finalSegments)
    }
}
