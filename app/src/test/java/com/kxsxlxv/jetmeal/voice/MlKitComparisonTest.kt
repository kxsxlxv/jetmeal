package com.kxsxlxv.jetmeal.voice

import org.junit.Assert.*
import org.junit.Test

class MlKitComparisonTest {
    @Test fun pfdAudioIsPacedInSampleAlignedHundredMillisecondFrames() {
        assertEquals(32000,MLKIT_AUDIO_BYTES_PER_SECOND)
        assertEquals(3200,MLKIT_AUDIO_CHUNK_BYTES)
        assertEquals(3200,pcmFrameLength(6400))
        assertEquals(2400,pcmFrameLength(2400))
        assertEquals(2400,pcmFrameLength(2401))
        assertEquals(0,pcmFrameLength(0))
    }

    @Test fun producerNeverFeedsMoreThanRecordedAudio() {
        val bytes = 179840L // Same format as verified on vivo X300
        var emitted=0L
        while(emitted<bytes) {
            val frame=pcmFrameLength(bytes-emitted)
            assertTrue(frame>0)
            assertEquals(0,frame%2)
            emitted+=frame
        }
        assertEquals(bytes,emitted)
        assertEquals(0,pcmFrameLength(bytes-emitted))
    }

    @Test fun resultsRemainExplicitlySeparatedByRequestedMlKitMode() {
        val basic=MlKitComparisonResult(MlKitMode.Basic,"Готово",transcript="два яйца")
        val advanced=MlKitComparisonResult(MlKitMode.Advanced,
            "Google не вернул финальную транскрипцию.")
        assertEquals("два яйца",basic.transcript)
        assertNull(advanced.transcript)
        assertNotEquals(basic.mode,advanced.mode)
    }
}
