package com.kxsxlxv.jetmeal.voice

import java.io.File
import java.io.FileInputStream
import kotlin.math.sqrt

/** One immutable PCM window is calculated before BOTH ML Kit runs.
 * Keep a pre-roll before the detected onset to preserve quiet consonants.
 */
internal data class PcmSpeechWindow(
    val offsetBytes: Long,
    val sourceBytes: Long,
    val firstVoiceMs: Long?,
    val analyzedMs: Long,
) {
    val transmittedBytes get() = sourceBytes - offsetBytes
    val removedMs get() = offsetBytes * 1000 / MLKIT_AUDIO_BYTES_PER_SECOND
    val durationMs get() = transmittedBytes * 1000 / MLKIT_AUDIO_BYTES_PER_SECOND
}

internal fun detectSpeechWindow(
    file: File, sourceBytes: Long, trim: Boolean,
): PcmSpeechWindow {
    require(sourceBytes > 0 && sourceBytes % 2 == 0L)
    val frameBytes = 640 // 20 ms at 16 kHz, 16-bit mono
    val scanLimit = minOf(sourceBytes, 7000L * MLKIT_AUDIO_BYTES_PER_SECOND / 1000)
    var scanned = 0L
    var consecutive = 0
    var voiceStart = 0L
    var onset: Long? = null
    FileInputStream(file).use { stream ->
        val frame = ByteArray(frameBytes)
        while (scanned + frameBytes <= scanLimit) {
            var filled = 0
            while (filled < frameBytes) {
                val n = stream.read(frame, filled, frameBytes - filled)
                if (n <= 0) break
                filled += n
            }
            if (filled != frameBytes) break
            if (frameRms(frame) >= 400.0) {
                if (consecutive == 0) voiceStart = scanned
                consecutive++
                if (consecutive >= 3) {
                    onset = voiceStart * 1000 / MLKIT_AUDIO_BYTES_PER_SECOND
                    scanned += frameBytes
                    break
                }
            } else consecutive = 0
            scanned += frameBytes
        }
    }
    val startMs = onset
    val cut = if (trim && startMs != null && startMs >= 350L) {
        val keepMs = (startMs - 240L).coerceAtLeast(0)
        ((keepMs * MLKIT_AUDIO_BYTES_PER_SECOND / 1000) and -2L)
            .coerceAtMost(sourceBytes - 2)
    } else 0L
    return PcmSpeechWindow(cut, sourceBytes, startMs,
        scanned * 1000 / MLKIT_AUDIO_BYTES_PER_SECOND)
}

private fun frameRms(frame: ByteArray): Double {
    var sum = 0L
    for (index in frame.indices step 2) {
        val sample = ((frame[index].toInt() and 0xff) or
            (frame[index + 1].toInt() shl 8)).toShort().toLong()
        sum += sample * sample
    }
    return sqrt(sum.toDouble() / (frame.size / 2))
}
