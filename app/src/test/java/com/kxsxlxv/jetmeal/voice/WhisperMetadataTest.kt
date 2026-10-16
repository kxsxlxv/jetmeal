package com.kxsxlxv.jetmeal.voice

import org.junit.Assert.*
import org.junit.Test

class WhisperMetadataTest {
    @Test fun officialModelUsesMultilingualTinyAndPinnedDigest() {
        assertEquals("ggml-tiny.bin",WhisperModelMetadata.NAME)
        assertFalse(WhisperModelMetadata.NAME.contains(".en."))
        assertEquals(77_691_713L,WhisperModelMetadata.BYTES)
        assertTrue(WhisperModelMetadata.URL.startsWith("https://huggingface.co/ggerganov/whisper.cpp/"))
        assertTrue(WhisperModelMetadata.SHA256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun metricsNeverRequireFabricatingAnotherRecognizersText() {
        val google=BenchmarkResult("Android",transcript="Творог",status="Готово",totalMs=800)
        val whisper=BenchmarkResult("Whisper",transcript=null,status="Нет модели")
        assertEquals("Творог",google.transcript)
        assertNull(whisper.transcript)
        assertNull(whisper.totalMs)
    }
}
