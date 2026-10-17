package com.kxsxlxv.jetmeal.voice

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Both ML Kit modes decode the same immutable 16 kHz, mono, 16-bit PCM file,
 * never a new microphone recording. Google requires the pipe to receive data
 * at real-time speed (a normal file-backed PFD is NOT supported).
 *
 * This measures end-to-end streaming latency, not offline model inference RTF.
 */
internal data class MlKitComparisonResult(
    val mode: MlKitMode,
    val status: String,
    val transcript: String? = null,
    val modelCheckMs: Long? = null,
    val downloadMs: Long? = null,
    val recognitionMs: Long? = null,
    val firstPartialMs: Long? = null,
    val firstFinalMs: Long? = null,
    val bytesWritten: Long = 0,
    val totalBytes: Long = 0,
    val partialUpdates: Int = 0,
    val finalSegments: Int = 0,
    val streamFinished: Boolean = false,
)

internal const val MLKIT_AUDIO_BYTES_PER_SECOND = 16000 * 2
internal const val MLKIT_AUDIO_CHUNK_BYTES = MLKIT_AUDIO_BYTES_PER_SECOND / 10

internal fun pcmFrameLength(remaining: Long): Int =
    minOf(MLKIT_AUDIO_CHUNK_BYTES.toLong(), remaining.coerceAtLeast(0)).toInt() and -2

internal suspend fun recognizeMlKitRecordedAudio(
    recognizer: SpeechRecognizer,
    mode: MlKitMode,
    audio: RecordedPcm,
    modelCheckMs: Long,
    downloadMs: Long?,
    onChange: (MlKitComparisonResult) -> Unit,
): MlKitComparisonResult = coroutineScope {
    val descriptors = ParcelFileDescriptor.createPipe()
    var transcript = StreamingTranscript()
    val start = SystemClock.elapsedRealtime()
    var bytesWritten = 0L
    var finalResponseSeen = false
    var streamFinished = false
    var firstPartialMs: Long? = null
    var firstFinalMs: Long? = null
    var feedError: String? = null
    var failure: String? = null
    var complete = false

    fun snapshot(status: String) = MlKitComparisonResult(
        mode = mode,
        status = status,
        transcript = transcript.visible.ifBlank { null },
        modelCheckMs = modelCheckMs,
        downloadMs = downloadMs,
        recognitionMs = SystemClock.elapsedRealtime() - start,
        firstPartialMs = firstPartialMs,
        firstFinalMs = firstFinalMs,
        bytesWritten = bytesWritten,
        totalBytes = audio.bytes,
        partialUpdates = transcript.partialUpdates,
        finalSegments = transcript.finalSegments,
        streamFinished = streamFinished,
    )

    // No audio-source API provides a 'ready' callback. A short startup
    // headroom and a paced writer let the server open the read end safely.
    val writer = launch(Dispatchers.IO) {
        delay(200)
        try {
            FileInputStream(audio.file).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { output ->
                    val chunk = ByteArray(MLKIT_AUDIO_CHUNK_BYTES)
                    val pacedAt = SystemClock.elapsedRealtime()
                    while (isActive) {
                        val size = input.read(chunk)
                        if (size == -1) break
                        if (size == 0) continue
                        output.write(chunk, 0, size)
                        bytesWritten += size
                        val target = pacedAt + bytesWritten * 1000 / MLKIT_AUDIO_BYTES_PER_SECOND
                        val wait = target - SystemClock.elapsedRealtime()
                        if (wait > 0) delay(wait)
                    }
                    output.flush()
                    streamFinished = bytesWritten == audio.bytes
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            feedError = e.javaClass.simpleName + ": " + (e.message ?: "unknown")
        }
    }

    // If EOF does not itself finish the Google recognition Flow, request the
    // final hypothesis after a short drain. A hard outer deadline protects
    // against ML Kit alpha hangs.
    val endAfterEof = launch {
        writer.join()
        delay(2300)
        if (!complete && isActive) {
            runCatching { recognizer.stopRecognition() }
        }
    }

    try {
        val request = speechRecognizerRequest {
            audioSource = AudioSource.fromPfd(descriptors[0])
        }
        withTimeout(audio.durationMs + 13_000L) {
            recognizer.startRecognition(request).collect { response ->
                when (response) {
                    is SpeechRecognizerResponse.PartialTextResponse -> {
                        if (firstPartialMs == null) firstPartialMs = SystemClock.elapsedRealtime() - start
                        transcript = transcript.onPartial(response.text)
                        onChange(snapshot("Промежуточное распознавание…"))
                    }
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        if (firstFinalMs == null) firstFinalMs = SystemClock.elapsedRealtime() - start
                        transcript = transcript.onFinal(response.text)
                        finalResponseSeen = true
                        onChange(snapshot("Получен финальный фрагмент…"))
                    }
                    is SpeechRecognizerResponse.CompletedResponse -> {
                        complete = true
                    }
                    is SpeechRecognizerResponse.ErrorResponse -> {
                        failure = "Google ML Kit: " + (response.e.message ?: "Error") +
                            " (code " + response.e.errorCode + ")"
                    }
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        failure = "Тайм-аут распознавания (ожидание аудио и финального результата)"
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        failure = e.javaClass.simpleName + ": " + (e.message ?: "unknown")
    } finally {
        endAfterEof.cancel()
        writer.cancel()
        runCatching { descriptors[1].close() }
        runCatching { descriptors[0].close() }
    }

    val result = snapshot(
        when {
            failure != null -> "Ошибка: " + failure
            feedError != null -> "Ошибка передачи PCM: " + feedError
            !streamFinished -> "Не все байты PCM переданы; результат не подтверждён."
            !finalResponseSeen || transcript.confirmed.isBlank() ->
                "Google не вернул финальную транскрипцию."
            else -> "Готово: запись обработана через " + mode.label +
                " (запрошенный режим)."
        }
    )
    onChange(result)
    result
}
