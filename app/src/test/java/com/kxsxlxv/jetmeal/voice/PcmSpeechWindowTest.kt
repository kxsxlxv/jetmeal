package com.kxsxlxv.jetmeal.voice

import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.*
import org.junit.Test

class PcmSpeechWindowTest {
    private fun makePcm(silenceMs: Int, speechMs: Int, amplitude: Int = 2500): File {
        val file=File.createTempFile("jetmeal-onset-", ".pcm")
        file.deleteOnExit()
        FileOutputStream(file).use { out ->
            repeat(silenceMs * 16) { out.write(0); out.write(0) }
            repeat(speechMs * 16) {
                out.write(amplitude and 255)
                out.write((amplitude ushr 8) and 255)
            }
        }
        return file
    }

    @Test fun longLeadingSilenceIsTrimmedWithPreRollForBothModes() {
        val input=makePcm(1000,2000)
        try {
            val cut=detectSpeechWindow(input,input.length(),trim=true)
            assertEquals(1000L,cut.firstVoiceMs)
            assertEquals(760L,cut.removedMs)
            assertEquals(input.length(),cut.sourceBytes)
            assertEquals(input.length()-760*32,cut.transmittedBytes)
            assertEquals(0L,cut.offsetBytes%2)
            assertEquals(2240L,cut.durationMs)
        } finally {input.delete()}
    }

    @Test fun zeroTrimmingModeAlwaysPreservesOriginalSamples() {
        val input=makePcm(1000,2000)
        try {
            val cut=detectSpeechWindow(input,input.length(),trim=false)
            assertEquals(1000L,cut.firstVoiceMs)
            assertEquals(0L,cut.offsetBytes)
            assertEquals(input.length(),cut.transmittedBytes)
        } finally {input.delete()}
    }

    @Test fun immediateSpeechDoesNotLoseItsBeginning() {
        val input=makePcm(20,700)
        try {
            val cut=detectSpeechWindow(input,input.length(),trim=true)
            assertEquals(20L,cut.firstVoiceMs)
            assertEquals(0L,cut.offsetBytes)
        } finally {input.delete()}
    }

    @Test fun silenceOnlyRemainsUnchangedInsteadOfTruncatingInput() {
        val input=makePcm(3000,0)
        try {
            val cut=detectSpeechWindow(input,input.length(),trim=true)
            assertNull(cut.firstVoiceMs)
            assertEquals(0L,cut.offsetBytes)
            assertEquals(input.length(),cut.transmittedBytes)
        } finally {input.delete()}
    }

    @Test fun shortTransientNoiseDoesNotPassSustainedVoiceCheck() {
        val input=File.createTempFile("jetmeal-noise-", ".pcm")
        try {
            FileOutputStream(input).use { out ->
                repeat(32000) { index ->
                    val value=if(index in 5000..5319) 3000 else 0
                    out.write(value and 255)
                    out.write((value ushr 8) and 255)
                }
            }
            val window=detectSpeechWindow(input,input.length(),trim=true)
            assertNull(window.firstVoiceMs)
            assertEquals(0L,window.offsetBytes)
        } finally {input.delete()}
    }
}
