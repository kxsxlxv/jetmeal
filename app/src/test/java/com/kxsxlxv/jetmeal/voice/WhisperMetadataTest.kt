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

    @Test fun baseAndTinyAreIndependentlyPinnedMultilingualModels() {
        assertEquals(2,WhisperModel.entries.size)
        assertEquals("ggml-tiny.bin",WhisperModel.Tiny.fileName)
        assertEquals("ggml-base.bin",WhisperModel.Base.fileName)
        assertEquals(147_951_465L,WhisperModel.Base.bytes)
        assertEquals("60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            WhisperModel.Base.sha256)
        assertTrue(WhisperModel.entries.all {
            it.sha256.matches(Regex("[a-f0-9]{64}")) &&
                it.url.startsWith("https://huggingface.co/ggerganov/whisper.cpp/") &&
                !it.fileName.contains(".en.")
        })
        assertEquals(2,WhisperModel.entries.map {it.fileName}.toSet().size)
    }

    @Test fun googleOnResultsMustContainNonblankText() {
        assertNull(bestRecognitionText(null))
        assertNull(bestRecognitionText(emptyList()))
        assertNull(bestRecognitionText(listOf("","  ","\\t")))
        assertEquals("300 граммов творога",
            bestRecognitionText(listOf(" ", " 300 граммов творога ", "творог")))
    }

    @Test fun metricsNeverRequireFabricatingAnotherRecognizersText() {
        val google=BenchmarkResult("Android",transcript="Творог",status="Готово",totalMs=800)
        val whisper=BenchmarkResult("Whisper",transcript=null,status="Нет модели")
        assertEquals("Творог",google.transcript)
        assertNull(whisper.transcript)
        assertNull(whisper.totalMs)
    }
}
