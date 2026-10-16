package com.kxsxlxv.jetmeal.voice

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Official multilingual Whisper Tiny (NOT tiny.en). No bundled model weights. */
internal object WhisperModelMetadata {
    const val NAME = "ggml-tiny.bin"
    const val BYTES = 77_691_713L
    const val SHA256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
    const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
}

internal data class WhisperProvision(
    val file: File, val downloadedBytes: Long, val downloadMs: Long, val verificationMs: Long,
)

/** App-private, checksum-verified install with atomic promotion and no manual download. */
internal class WhisperModelRepository(private val context: Context) {
    private val dir get() = File(context.filesDir, "asr_models")

    suspend fun prepare(onProgress: suspend (Long, Long) -> Unit): WhisperProvision =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val installed = File(dir, WhisperModelMetadata.NAME)
            val start = SystemClock.elapsedRealtimeNanos()
            if (installed.isFile && installed.length() == WhisperModelMetadata.BYTES) {
                val verifyStart = SystemClock.elapsedRealtimeNanos()
                if (verify(installed)) return@withContext WhisperProvision(
                    installed, 0, 0, (SystemClock.elapsedRealtimeNanos() - verifyStart) / 1_000_000,
                )
                installed.delete()
            }
            val available = StatFs(dir.absolutePath).availableBytes
            require(available > WhisperModelMetadata.BYTES + 20_000_000L) {
                "Недостаточно места для модели Whisper Tiny (нужно около 100 МБ)."
            }
            val temp = File(dir, "${WhisperModelMetadata.NAME}.download")
            temp.delete()
            var connection: HttpURLConnection? = null
            try {
                val target = URL(WhisperModelMetadata.URL).openConnection()
                require(target is HttpURLConnection) { "Недоступен HTTPS источник модели." }
                connection = target
                target.connectTimeout = 15_000
                target.readTimeout = 40_000
                target.instanceFollowRedirects = true
                target.setRequestProperty("Accept-Encoding", "identity")
                target.setRequestProperty("User-Agent", "JetMeal-Whisper-ASR/1")
                val response = target.responseCode
                require(response in 200..299) { "Не удалось загрузить модель: HTTP $response." }
                var received = 0L
                target.inputStream.use { input ->
                    FileOutputStream(temp).buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var lastShown = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            received += n
                            require(received <= WhisperModelMetadata.BYTES) {
                                "Размер модели отличается от ожидаемого."
                            }
                            if (received - lastShown >= 512 * 1024L) {
                                onProgress(received, WhisperModelMetadata.BYTES)
                                lastShown = received
                            }
                        }
                        output.flush()
                    }
                }
                require(received == WhisperModelMetadata.BYTES) {
                    "Модель загружена не полностью: $received байт."
                }
                val downloadMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                val verifyStart = SystemClock.elapsedRealtimeNanos()
                require(verify(temp)) { "SHA-256 модели не совпал. Файл отклонён." }
                val verificationMs = (SystemClock.elapsedRealtimeNanos() - verifyStart) / 1_000_000
                check(temp.renameTo(installed)) { "Не удалось сохранить проверенную модель." }
                onProgress(received, WhisperModelMetadata.BYTES)
                WhisperProvision(installed, received, downloadMs, verificationMs)
            } finally {
                connection?.disconnect()
                temp.delete()
            }
        }

    private fun verify(file: File): Boolean {
        if (!file.isFile || file.length() != WhisperModelMetadata.BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") {
            String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)
        }
        return actual == WhisperModelMetadata.SHA256
    }
}

/** JNI binds to a pinned whisper.cpp revision, CPU only on arm64-v8a. */
internal object WhisperNative {
    init { System.loadLibrary("jetmeal_whisper") }
    external fun openModel(modelPath: String): Long
    external fun transcribe(contextPtr: Long, pcmPath: String, threads: Int): String
    external fun closeModel(contextPtr: Long)
}

internal suspend fun runWhisperTranscription(
    audio: RecordedPcm, provision: WhisperProvision,
): BenchmarkResult = withContext(Dispatchers.Default) {
    val begin = SystemClock.elapsedRealtimeNanos()
    var pointer = 0L
    try {
        pointer = WhisperNative.openModel(provision.file.absolutePath)
        require(pointer != 0L) { "Не удалось загрузить whisper.cpp." }
        val loaded = SystemClock.elapsedRealtimeNanos()
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        val text = WhisperNative.transcribe(pointer, audio.file.absolutePath, threads).trim()
        val completed = SystemClock.elapsedRealtimeNanos()
        BenchmarkResult(
            engine = "Whisper Tiny (whisper.cpp)",
            transcript = text,
            status = if (text.isBlank()) "Пустая транскрипция" else "Готово · CPU · русский язык",
            initializationMs = (loaded - begin) / 1_000_000,
            processingMs = (completed - loaded) / 1_000_000,
            totalMs = (completed - begin) / 1_000_000,
            downloadMs = provision.downloadMs,
            verificationMs = provision.verificationMs,
            mode = "whisper.cpp, multilingual Tiny, ${threads} CPU-потока",
        )
    } finally {
        if (pointer != 0L) WhisperNative.closeModel(pointer)
    }
}
