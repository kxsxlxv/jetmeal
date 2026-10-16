package com.kxsxlxv.jetmeal.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kxsxlxv.jetmeal.ui.theme.JetmealTheme
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Diagnostic lab only: does not save audio or add diary entries to Supabase.
 * All engines must process the same recorded 16kHz/16-bit/mono PCM file.
 * Never report a transcription for a model that was not actually run.
 */
class VoiceBenchmarkActivity : ComponentActivity() {
    private var recordingJob: Job? = null
    private var recording = mutableStateOf(false)
    private var busy = mutableStateOf(false)
    private var info = mutableStateOf("Нажмите «Записать». Все распознаватели должны получить один и тот же звук.")
    private var recorded = mutableStateOf<RecordedPcm?>(null)
    private var runs = mutableStateOf(emptyList<BenchmarkResult>())
    private var recorder: AudioRecord? = null
    private var downloadProgress = mutableStateOf<Pair<Long,Long>?>(null)
    private var downloadDialog = mutableStateOf(false)

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) beginRecording()
            else info.value = "Нужен доступ к микрофону для записи тестовой фразы."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JetmealTheme {
                VoiceBenchmarkScreen(recording.value,busy.value,info.value,recorded.value,runs.value,
                    downloadProgress.value,downloadDialog.value,
                    onRecord={ requestRecording() },onStop={ stopRecording() },
                    onRun={ requestBenchmark() },
                    onConfirmDownload={ downloadDialog.value=false; runComparison() },
                    onCancelDownload={ downloadDialog.value=false },
                    onClose={ finish() })
            }
        }
    }

    private fun requestRecording() {
        if (recording.value || busy.value) return
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) beginRecording()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun beginRecording() {
        if (recording.value || busy.value) return
        recorded.value?.file?.delete()
        recorded.value=null
        runs.value=emptyList()
        val minBytes=AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(minBytes<=0) {
            info.value="Не удалось получить параметры микрофона."
            return
        }
        val output=File(cacheDir,"speech-benchmark.pcm")
        try {
            @Suppress("MissingPermission")
            val input=AudioRecord(MediaRecorder.AudioSource.MIC,SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes*2,4096))
            if(input.state!=AudioRecord.STATE_INITIALIZED) {
                input.release(); info.value="Микрофон недоступен."; return
            }
            input.startRecording()
            recorder=input
            recording.value=true
            info.value="Идёт запись… Произнесите фразу и нажмите «Стоп»."
            recordingJob=lifecycleScope.launch(Dispatchers.IO) {
                val started=SystemClock.elapsedRealtimeNanos()
                var bytes=0L
                try {
                    FileOutputStream(output).use { stream ->
                        val chunk=ByteArray(4096)
                        while (recording.value && bytes < MAX_BYTES) {
                            val read=input.read(chunk,0,chunk.size,AudioRecord.READ_BLOCKING)
                            if(read>0) { stream.write(chunk,0,read); bytes+=read }
                            else if(read<0) break
                        }
                    }
                } finally {
                    runCatching { input.stop() }
                    input.release()
                    recorder=null
                    withContext(Dispatchers.Main) {
                        recording.value=false
                        val durationMs=bytes*1000/(SAMPLE_RATE*BYTES_PER_SAMPLE)
                        if(bytes>0) {
                            recorded.value=RecordedPcm(output,bytes,durationMs)
                            info.value="Записано ${durationMs} мс (${bytes} байт PCM)."
                        } else {
                            output.delete()
                            info.value="Пустая запись. Повторите попытку."
                        }
                    }
                }
            }
        } catch(error:Exception) {
            recorder?.release();recorder=null;recording.value=false
            info.value="Ошибка записи: ${error.message}"
        }
    }

    private fun stopRecording() {
        recording.value=false
        // stop() unblocks read(), allowing the PCM file to be finalized.
        runCatching {recorder?.stop()}
    }

    private fun requestBenchmark() {
        if (busy.value || recording.value || recorded.value == null) return
        val model=File(File(filesDir,"asr_models"),WhisperModelMetadata.NAME)
        if (!model.isFile || model.length()!=WhisperModelMetadata.BYTES) {
            downloadDialog.value=true
        } else runComparison()
    }

    /** Google/Android and Whisper are executed sequentially from exactly the
     * same file; a failure in one never substitutes the other's output. */
    private fun runComparison() {
        if (busy.value || recording.value) return
        val pcm=recorded.value ?: return
        busy.value=true
        downloadProgress.value=null
        runs.value=listOf(
            BenchmarkResult("Системный распознаватель (Google / OEM)",
                status="Распознавание PCM…"),
            BenchmarkResult("Whisper Tiny (whisper.cpp)",
                status="Ожидание завершения системного распознавателя…"))
        lifecycleScope.launch {
            try {
                val system = try { transcribeSystemAudio(pcm) }
                catch(c:CancellationException) { throw c }
                catch(e:Exception) { BenchmarkResult("Системный распознаватель (Google / OEM)",
                    status="Ошибка: ${e.message}") }
                runs.value=listOf(system,runs.value[1].copy(status="Подготовка Whisper Tiny…"))
                val whisper=try {
                    val provision=WhisperModelRepository(this@VoiceBenchmarkActivity)
                        .prepare { downloaded,total ->
                            withContext(Dispatchers.Main) {
                                downloadProgress.value=downloaded to total
                                info.value="Загрузка Whisper Tiny: ${downloaded / 1_000_000} / ${total / 1_000_000} МБ"
                            }
                        }
                    runs.value=listOf(system,runs.value[1].copy(status="Инициализация и транскрибация Whisper…"))
                    runWhisperTranscription(pcm,provision)
                } catch(c:CancellationException) { throw c }
                catch(e:Exception) { BenchmarkResult("Whisper Tiny (whisper.cpp)",
                    status="Ошибка: ${e.message}") }
                catch(e:LinkageError) { BenchmarkResult("Whisper Tiny (whisper.cpp)",
                    status="Нативная библиотека не загрузилась: ${e.message}") }
                runs.value=listOf(system,whisper)
                downloadProgress.value=null
                info.value="Сравнение завершено. Результат системного сервиса зависит от поддержки PCM на устройстве."
            } finally {
                busy.value=false
                downloadProgress.value=null
            }
        }
    }

    /** API 33+ optional PCM audio injection. Android explicitly permits services
     * to ignore it and reopen the microphone. Do not interpret a transcript as
     * validated same-audio benchmarking until checked on the physical device.
     */
    private suspend fun transcribeSystemAudio(pcm:RecordedPcm):BenchmarkResult {
        if(!SpeechRecognizer.isRecognitionAvailable(this))
            return BenchmarkResult("Системный распознаватель (Google / OEM)",status="Системная служба распознавания недоступна.")
        val onDevice=SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val recognizer=if(onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else SpeechRecognizer.createSpeechRecognizer(this)
        val pipe=ParcelFileDescriptor.createPipe()
        val started=SystemClock.elapsedRealtimeNanos()
        var readyNs:Long?=null
        val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
        }
        return try {
            kotlinx.coroutines.coroutineScope {
                val writer=launch(Dispatchers.IO) {
                    try {
                        FileInputStream(pcm.file).use { input ->
                            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                                input.copyTo(output,8192)
                            }
                        }
                    } catch (_:Exception) { /* Consumer can close when finished. */ }
                }
                try {
                    val outcome=withTimeout(25000) {
                        suspendCancellableCoroutine<Pair<String?,Int?>> { continuation ->
                            fun finish(text:String?,error:Int?) {
                                if(continuation.isActive) continuation.resume(text to error)
                            }
                            recognizer.setRecognitionListener(object:RecognitionListener {
                                override fun onReadyForSpeech(params:Bundle?) {
                                    readyNs=SystemClock.elapsedRealtimeNanos()
                                }
                                override fun onBeginningOfSpeech()=Unit
                                override fun onRmsChanged(rmsdB:Float)=Unit
                                override fun onBufferReceived(buffer:ByteArray?)=Unit
                                override fun onEndOfSpeech()=Unit
                                override fun onError(error:Int)=finish(null,error)
                                override fun onResults(results:Bundle?) {
                                    finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                        ?.firstOrNull(),null)
                                }
                                override fun onPartialResults(partialResults:Bundle?)=Unit
                                override fun onEvent(eventType:Int,params:Bundle?)=Unit
                            })
                            recognizer.startListening(intent)
                        }
                    }
                    val ended=SystemClock.elapsedRealtimeNanos()
                    val total=(ended-started)/1_000_000
                    BenchmarkResult("Системный распознаватель (Google / OEM)",
                        transcript=outcome.first,
                        status=if(outcome.second==null) "Получен результат (источник PCM не подтверждён)"
                            else "Ошибка распознавателя: код ${outcome.second}",
                        initializationMs=readyNs?.let { (it-started)/1_000_000 },
                        processingMs=readyNs?.let { (ended-it)/1_000_000 },
                        totalMs=total,
                        mode=if(onDevice) "Запрошен on-device" else "Системный провайдер; офлайн не гарантирован")
                } finally {
                    pipe[1].close()
                    pipe[0].close()
                    writer.cancel()
                }
            }
        } finally { recognizer.destroy() }
    }

    override fun onDestroy() {
        stopRecording()
        recordingJob?.cancel()
        recorded.value?.file?.delete()
        super.onDestroy()
    }

    private companion object {
        const val SAMPLE_RATE=16000
        const val BYTES_PER_SAMPLE=2L
        const val MAX_BYTES=16000*2L*30
    }
}

internal data class RecordedPcm(val file:File,val bytes:Long,val durationMs:Long)
internal data class BenchmarkResult(
    val engine:String, val transcript:String?=null, val status:String,
    val initializationMs:Long?=null,val processingMs:Long?=null,
    val totalMs:Long?=null,val mode:String?=null,
    val downloadMs:Long?=null,val verificationMs:Long?=null)

@Composable
private fun VoiceBenchmarkScreen(
    recording:Boolean,busy:Boolean,info:String,audio:RecordedPcm?,
    results:List<BenchmarkResult>,
    downloadProgress:Pair<Long,Long>?,downloadDialog:Boolean,
    onRecord:()->Unit,onStop:()->Unit,onRun:()->Unit,
    onConfirmDownload:()->Unit,onCancelDownload:()->Unit,onClose:()->Unit,
) {
    if(downloadDialog) AlertDialog(
        onDismissRequest=onCancelDownload,
        title={ Text("Скачать Whisper Tiny?") },
        text={ Text("Jetmeal автоматически загрузит модель (~78 МБ) в память телефона. " +
            "Повторно скачивать её не понадобится. При использовании мобильного интернета расходуется трафик.") },
        confirmButton={ TextButton(onClick=onConfirmDownload) { Text("Скачать и протестировать") } },
        dismissButton={ TextButton(onClick=onCancelDownload) { Text("Отмена") } }
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .safeDrawingPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Лаборатория распознавания",style=MaterialTheme.typography.headlineMedium)
        Text("Запишите фразу один раз. Сначала работает системное распознавание, " +
            "затем Whisper Tiny. Данные не добавляются в дневник.",
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(info,style=MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick=if(recording) onStop else onRecord,enabled=!busy) {
                Text(if(recording) "Остановить" else "Записать")
            }
            Button(onClick=onRun,enabled=audio!=null && !busy && !recording) {
                Text(if(busy) "Обработка…" else "Проверить распознавание")
            }
        }
        if(audio!=null) Text("Аудио: ${audio.durationMs} мс · ${audio.bytes} байт, 16 кГц / mono PCM.",
            style=MaterialTheme.typography.labelMedium)
        if(downloadProgress!=null) {
            LinearProgressIndicator(progress={ downloadProgress.first.toFloat() /
                downloadProgress.second.coerceAtLeast(1).toFloat() },
                modifier=Modifier.fillMaxWidth())
        }
        Text("Транскрипции и задержки",style=MaterialTheme.typography.titleLarge)
        val cards=if(results.isNotEmpty()) results else listOf(
            BenchmarkResult("Системный распознаватель (Google / OEM)",status="Не запускался"),
            BenchmarkResult("Whisper Tiny (whisper.cpp)",status="Не запускалась"))
        cards.forEach { item ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(item.engine,style=MaterialTheme.typography.titleMedium)
                    Text(item.status,style=MaterialTheme.typography.bodySmall)
                    item.mode?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
                    item.transcript?.let { Text(it,style=MaterialTheme.typography.bodyLarge) }
                    item.downloadMs?.let { Text("Загрузка файла модели: ${it} мс") }
                    item.verificationMs?.let { Text("Проверка SHA-256: ${it} мс") }
                    item.initializationMs?.let { Text("Инициализация: ${it} мс") }
                    item.processingMs?.let { Text("Транскрибация: ${it} мс") }
                    item.totalMs?.let { Text("Загрузка в RAM + распознавание: ${it} мс") }
                }
            }
        }
        Text("Важно: Android может проигнорировать EXTRA_AUDIO_SOURCE и повторно включить микрофон. " +
            "Поэтому полученный системный текст пока нельзя считать доказанным сравнением одной записи.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick=onClose) { Text("Закрыть") }
    }
}
