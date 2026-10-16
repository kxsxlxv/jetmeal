package com.kxsxlxv.jetmeal.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureBoundsTest {
    private val thirtySeconds=16000*2L*30

    @Test fun oldRecordingWouldCrossNativeWhisperLimit() {
        // vivo X300 screenshot: 962560 bytes / 30080 ms. The old code
        // read one more 4096-byte chunk without checking the remaining cap.
        val recordedInScreenshot=962560L
        assertTrue(recordedInScreenshot>thirtySeconds)
        assertEquals(960000L,thirtySeconds)
        assertEquals(2560,remainingPcmBytes(957440,thirtySeconds,4096))
        assertEquals(0,remainingPcmBytes(thirtySeconds,thirtySeconds,4096))
    }

    @Test fun pcmReadIsBoundedToEvenByteSamples() {
        assertEquals(4096,remainingPcmBytes(0,thirtySeconds,4096))
        assertEquals(2,remainingPcmBytes(thirtySeconds-3,thirtySeconds,4096))
        assertEquals(0,remainingPcmBytes(thirtySeconds+2560,thirtySeconds,4096))
        assertEquals(0,remainingPcmBytes(thirtySeconds,thirtySeconds,4096))
    }

    @Test fun entireCaptureCanNeverExceedThirtySeconds() {
        var written=0L
        var attempts=0
        while(written<thirtySeconds) {
            val request=remainingPcmBytes(written,thirtySeconds,4096)
            assertTrue(request>0)
            written+=request
            attempts++
        }
        assertEquals(thirtySeconds,written)
        assertEquals(0,remainingPcmBytes(written,thirtySeconds,4096))
        assertTrue(attempts>1)
    }
}
