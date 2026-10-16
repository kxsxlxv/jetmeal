package com.kxsxlxv.jetmeal.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
    private val whisperModels = WhisperModel.entries.toList()
    private val systemEngine = "Системный распознаватель (Google / OEM)"

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
                Surface(Modifier.fillMaxSize()) {
                VoiceBenchmarkScreen(recording.value,busy.value,info.value,recorded.value,runs.value,
                    downloadProgress.value,downloadDialog.value,
                    onRecord={ requestRecording() },onStop={ stopRecording() },
                    onRun={ requestBenchmark() },
                    onLiveCheck={ runLiveMicrophoneTest() },
                    onOpenMlKit={ startActivity(Intent(this, MlKitSpeechActivity::class.java)) },
                    onConfirmDownload={ downloadDialog.value=false; runComparison() },
                    onCancelDownload={ downloadDialog.value=false },
                    onClose={ finish() })
                }
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
                var bytes=0L
                try {
                    FileOutputStream(output).use { stream ->
                        val chunk=ByteArray(4096)
                        while (recording.value && bytes < MAX_BYTES) {
                            // Never request more bytes than the native decoder accepts.
                            // Previously the final 4096-byte read could exceed 30 s.
                            val remaining=remainingPcmBytes(bytes,MAX_BYTES,chunk.size)
                            if(remaining==0) break
                            val read=input.read(chunk,0,remaining,AudioRecord.READ_BLOCKING)
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

    private fun missingModelBytes():Long = whisperModels.sumOf { model ->
        val file=File(File(filesDir,"asr_models"),model.fileName)
        if(file.isFile && file.length()==model.bytes) 0L else model.bytes
    }

    private fun requestBenchmark() {
        if (busy.value || recording.value || recorded.value == null) return
        if (missingModelBytes()>0) downloadDialog.value=true
        else runComparison()
    }

    /** Order and audio are identical for both Whisper models. Android may
     * ignore PCM injection; an explicit live-mic diagnostic remains separate.
     */
    private fun runComparison() {
        if (busy.value || recording.value) return
        val pcm=recorded.value ?: return
        busy.value=true
        downloadProgress.value=null
        runs.value=listOf(
            BenchmarkResult(systemEngine,status="Ожидание очереди (PCM будет передан после Whisper)…")
        ) + whisperModels.map { BenchmarkResult(it.displayName,status="Ожидание очереди…") }
        lifecycleScope.launch {
            try {
                for(model in whisperModels) {
                    runs.value=runs.value.map {
                        if(it.engine==model.displayName) it.copy(
                            status="Подготовка ${model.displayName}…") else it
                    }
                    val result=try {
                        val provision=WhisperModelRepository(this@VoiceBenchmarkActivity)
                            .prepare(model) { downloaded,total ->
                                withContext(Dispatchers.Main) {
                                    downloadProgress.value=downloaded to total
                                    info.value="Загрузка ${model.name}: "+
                                        "${downloaded/1_000_000} из ${total/1_000_000} МБ"
                                }
                            }
                        downloadProgress.value=null
                        runs.value=runs.value.map {
                            if(it.engine==model.displayName) it.copy(
                                status="Инициализация и транскрибация ${model.name}…") else it
                        }
                        runWhisperTranscription(pcm,provision,model)
                    } catch(c:CancellationException) { throw c }
                    catch(e:Exception) { BenchmarkResult(model.displayName,
                        status="Ошибка: ${e.message}") }
                    catch(e:LinkageError) { BenchmarkResult(model.displayName,
                        status="Нативная библиотека не загрузилась: ${e.message}") }
                    runs.value=runs.value.map { if(it.engine==model.displayName) result else it }
                }
                runs.value=runs.value.map {
                    if(it.engine==systemEngine) it.copy(status="Системное распознавание PCM…") else it
                }
                val system = try { transcribeSystemAudio(pcm) }
                catch(c:CancellationException) { throw c }
                catch(e:Exception) { BenchmarkResult(systemEngine,status="Ошибка: ${e.message}",
                    errorCode=-1) }
                runs.value=runs.value.map { if(it.engine==systemEngine) system else it }
                info.value="Сравнение закончено. Whisper Tiny и Base обработали один PCM; " +
                    "приём PCM системным сервисом Android не гарантирован."
            } finally {
                busy.value=false
                downloadProgress.value=null
            }
        }
    }

    /** Try strict on-device first, then (if NO_MATCH or timeout) the system default
     * recognition service on the exact same recorded bytes. This is NOT a
     * guarantee that either OEM service actually consumes EXTRA_AUDIO_SOURCE.
     */
    private suspend fun transcribeSystemAudio(pcm:RecordedPcm):BenchmarkResult {
        if(!SpeechRecognizer.isRecognitionAvailable(this))
            return BenchmarkResult(SYSTEM_ENGINE,status="Системная служба распознавания недоступна.")
        val onDevice=SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        suspend fun run(onDeviceMode:Boolean):BenchmarkResult = try {
            transcribeProviderPcm(pcm,onDeviceMode)
        } catch(e:TimeoutCancellationException) {
            BenchmarkResult(SYSTEM_ENGINE,status="Тайм-аут системного распознавания",
                errorCode=-1,diagnostic="Сервис не вернул результат после ожидания записи.")
        }
        val first=run(onDevice)
        if(first.errorCode !in listOf(SpeechRecognizer.ERROR_NO_MATCH,-1) || !onDevice) return first
        val second=run(false)
        return second.copy(
            // The full system attempt includes both recognizers, not just
            // the successful (or failed) second pass.
            totalMs=(first.totalMs ?: 0L)+(second.totalMs ?: 0L),
            status=if(second.errorCode==null)
                "Обычный системный провайдер распознал запись после отказа on-device."
                else "On-device: ${first.status}; обычный провайдер: ${second.status}",
            mode="Попытка 1: on-device (${first.totalMs ?: 0} мс); "+
                "попытка 2: системный/возможно сетевой. Данные PCM переданы повторно.",
            diagnostic=listOfNotNull(first.diagnostic,second.diagnostic)
                .joinToString(" | "),
        )
    }

    /** Send real-time paced PCM (100 ms chunks) instead of dumping 30 seconds
     * into the pipe instantly. Wait for onReadyForSpeech to avoid dropping
     * the beginning while service startup is in progress.
     */
    private suspend fun transcribeProviderPcm(pcm:RecordedPcm,onDevice:Boolean):BenchmarkResult {
        val engine=SYSTEM_ENGINE
        val recognizer=if(onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else SpeechRecognizer.createSpeechRecognizer(this)
        val pipe=ParcelFileDescriptor.createPipe()
        val ready=CompletableDeferred<Unit>()
        val sent=AtomicLong(0L)
        val completelySent=AtomicBoolean(false)
        val resultCallbackCount=AtomicLong(0L)
        val candidateCount=AtomicLong(-1L)
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
                    // The provider may not emit onReady, so allow an eventual
                    // stream start after a short bounded wait.
                    withTimeoutOrNull(2_000) { ready.await() }
                    try {
                        FileInputStream(pcm.file).use { input ->
                            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                                val chunk=ByteArray(PCM_STREAM_CHUNK_BYTES)
                                while (true) {
                                    val read=input.read(chunk)
                                    if(read<0) break
                                    output.write(chunk,0,read)
                                    sent.addAndGet(read.toLong())
                                    // Maintain approximately microphone-equivalent
                                    // pacing; do not decode 30 s as a 1 s burst.
                                    delay((read.toLong()*1000 / (SAMPLE_RATE*BYTES_PER_SAMPLE))
                                        .coerceAtLeast(1))
                                }
                                output.flush()
                                completelySent.set(true)
                            }
                        }
                    } catch(c:CancellationException) { throw c }
                    catch (_:Exception) { /* Consumer may stop early; report bytes. */ }
                }
                try {
                    val outcome=withTimeout(pcm.durationMs+12_000L) {
                        suspendCancellableCoroutine<Pair<String?,Int?>> { continuation ->
                            fun finish(text:String?,error:Int?) {
                                if(continuation.isActive) continuation.resume(text to error)
                            }
                            recognizer.setRecognitionListener(object:RecognitionListener {
                                override fun onReadyForSpeech(params:Bundle?) {
                                    readyNs=SystemClock.elapsedRealtimeNanos()
                                    ready.complete(Unit)
                                }
                                override fun onBeginningOfSpeech()=Unit
                                override fun onRmsChanged(rmsdB:Float)=Unit
                                override fun onBufferReceived(buffer:ByteArray?)=Unit
                                override fun onEndOfSpeech()=Unit
                                override fun onError(error:Int)=finish(null,error)
                                override fun onResults(results:Bundle?) {
                                    val alternatives=results?.getStringArrayList(
                                        SpeechRecognizer.RESULTS_RECOGNITION)
                                    resultCallbackCount.incrementAndGet()
                                    candidateCount.set(alternatives?.size?.toLong() ?: -1L)
                                    val text=bestRecognitionText(alternatives)
                                    finish(text,if(text==null) SpeechRecognizer.ERROR_NO_MATCH else null)
                                }
                                override fun onPartialResults(partialResults:Bundle?)=Unit
                                override fun onEvent(eventType:Int,params:Bundle?)=Unit
                            })
                            recognizer.startListening(intent)
                        }
                    }
                    val ended=SystemClock.elapsedRealtimeNanos()
                    val total=(ended-started)/1_000_000
                    val bytesSent=sent.get()
                    val defaultProvider=runCatching {
                        Settings.Secure.getString(contentResolver,"voice_recognition_service")
                            ?.substringBefore('/') ?: "не определён"
                    }.getOrDefault("не определён")
                    val diagnostic="PCM: ${bytesSent} из ${pcm.bytes} байт; "+
                        "EOF: ${completelySent.get()}; ready: ${readyNs!=null}; "+
                        "onResults: ${resultCallbackCount.get()} (вариантов: ${candidateCount.get()}); "+
                        "распознаватель по умолчанию: ${defaultProvider}"
                    BenchmarkResult(engine,
                        transcript=outcome.first,
                        status=when(outcome.second) {
                            null -> "Получен текст; подтверждение приёма PCM сервисом недоступно"
                            SpeechRecognizer.ERROR_NO_MATCH ->
                                "NO_MATCH (7): сервис не распознал речь из аудиоисточника"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "SPEECH_TIMEOUT (6): сервис не обнаружил речь"
                            else -> "Ошибка распознавателя: код ${outcome.second}"
                        },
                        initializationMs=readyNs?.let { (it-started)/1_000_000 },
                        processingMs=readyNs?.let { (ended-it)/1_000_000 },
                        totalMs=total,
                        mode=if(onDevice) "On-device, ru-RU, PCM 16 кГц" else
                            "Стандартный провайдер, ru-RU; сеть может использоваться",
                        errorCode=outcome.second,
                        diagnostic=diagnostic,
                    )
                } finally {
                    writer.cancel()
                    runCatching {pipe[1].close()}
                    runCatching {pipe[0].close()}
                }
            }
        } finally { recognizer.destroy() }
    }

    /** Separate diagnostic when a system service rejects recorded PCM.
     * The user must speak again. Never mix this new audio into the Whisper
     * comparison or claim that it belongs to the original recording. */
    private fun runLiveMicrophoneTest() {
        if(busy.value || recording.value) return
        busy.value=true
        val title="Системный микрофон (отдельная запись)"
        runs.value=runs.value.filterNot {it.engine==title}+
            BenchmarkResult(title,status="Говорите сейчас; используется новая запись…")
        lifecycleScope.launch {
            try {
                val result=try { transcribeLiveMicrophone() }
                catch(e:TimeoutCancellationException) {
                    BenchmarkResult(title,status="Тайм-аут ожидания речи (18 секунд)",errorCode=-1)
                }
                catch(c:CancellationException) { throw c }
                catch(e:Exception) { BenchmarkResult(title,status="Ошибка: ${e.message}") }
                runs.value=runs.value.filterNot {it.engine==title}+result
            } finally { busy.value=false }
        }
    }

    private suspend fun transcribeLiveMicrophone():BenchmarkResult {
        val title="Системный микрофон (отдельная запись)"
        if(!SpeechRecognizer.isRecognitionAvailable(this))
            return BenchmarkResult(title,status="Служба недоступна")
        val recognizer=SpeechRecognizer.createSpeechRecognizer(this)
        val started=SystemClock.elapsedRealtimeNanos()
        var readyNs:Long?=null
        return try {
            val result=withTimeout(18_000) {
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
                            val text=bestRecognitionText(results?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION))
                            finish(text,if(text==null) SpeechRecognizer.ERROR_NO_MATCH else null)
                        }
                        override fun onPartialResults(partialResults:Bundle?)=Unit
                        override fun onEvent(eventType:Int,params:Bundle?)=Unit
                    })
                    recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU")
                    })
                }
            }
            val ended=SystemClock.elapsedRealtimeNanos()
            BenchmarkResult(title,
                transcript=result.first,
                status=if(result.second==null)
                    "Микрофон распознал речь. Это НОВАЯ запись, не исходный PCM."
                    else "Системный сервис не распознал микрофон: ошибка ${result.second}",
                initializationMs=readyNs?.let { (it-started)/1_000_000 },
                processingMs=readyNs?.let {(ended-it)/1_000_000},
                totalMs=(ended-started)/1_000_000,
                mode="Обычный системный сервис (возможно сетевой), прямой микрофон",
                errorCode=result.second)
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
        const val PCM_STREAM_CHUNK_BYTES=3200
        const val SYSTEM_ENGINE="Системный распознаватель (Google / OEM)"
    }
}

/** Exact read bound: the last AudioRecord buffer must not overshoot the cap.
 * Keeping reads sample-aligned avoids accidentally writing half an int16. */
internal fun remainingPcmBytes(written:Long,maxBytes:Long,bufferSize:Int):Int =
    (minOf((maxBytes-written).coerceAtLeast(0),bufferSize.toLong()).toInt() and -2)

internal data class RecordedPcm(val file:File,val bytes:Long,val durationMs:Long)
internal data class BenchmarkResult(
    val engine:String, val transcript:String?=null, val status:String,
    val initializationMs:Long?=null,val processingMs:Long?=null,
    val totalMs:Long?=null,val mode:String?=null,
    val downloadMs:Long?=null,val verificationMs:Long?=null,
    val errorCode:Int?=null,val diagnostic:String?=null)

@Composable
private fun VoiceBenchmarkScreen(
    recording:Boolean,busy:Boolean,info:String,audio:RecordedPcm?,
    results:List<BenchmarkResult>,
    downloadProgress:Pair<Long,Long>?,downloadDialog:Boolean,
    onRecord:()->Unit,onStop:()->Unit,onRun:()->Unit,onLiveCheck:()->Unit,
    onOpenMlKit:()->Unit,
    onConfirmDownload:()->Unit,onCancelDownload:()->Unit,onClose:()->Unit,
) {
    if(downloadDialog) AlertDialog(
        onDismissRequest=onCancelDownload,
        title={ Text("Скачать модели Whisper?") },
        text={ Text("Jetmeal автоматически загрузит недостающие модели: " +
            "Tiny (77,7 МБ) и/или Base (148 МБ). Уже загруженная Tiny останется на устройстве. " +
            "При первом запуске Base потребуется около 148 МБ интернет-трафика. " +
            "При использовании мобильного интернета расходуется трафик.") },
        confirmButton={ TextButton(onClick=onConfirmDownload) { Text("Скачать и протестировать") } },
        dismissButton={ TextButton(onClick=onCancelDownload) { Text("Отмена") } }
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .safeDrawingPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Лаборатория распознавания",style=MaterialTheme.typography.headlineMedium)
        Text("Произнесите короткую фразу и сразу нажмите «Остановить». " +
            "Автоостановка через 30 секунд. Затем Whisper Tiny, Whisper Base " +
            "и системное распознавание. В дневник ничего не записывается.",
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
        if(!busy && results.firstOrNull()?.errorCode!=null) {
            Text("Система не распознала сохранённый PCM. Проверьте отдельно, "+
                "работает ли штатный микрофон: это потребует повторно произнести фразу.",
                style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick=onLiveCheck,modifier=Modifier.fillMaxWidth()) {
                Text("Проверить системный микрофон отдельно")
            }
        }
        OutlinedButton(
            onClick=onOpenMlKit,enabled=!recording && !busy,
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            Text("Google ML Kit · потоковый микрофон")
        }
        Text("Транскрипции и задержки",style=MaterialTheme.typography.titleLarge)
        val cards=if(results.isNotEmpty()) results else listOf(
            BenchmarkResult("Системный распознаватель (Google / OEM)",status="Не запускался"),
            BenchmarkResult(WhisperModel.Tiny.displayName,status="Не запускалась"),
            BenchmarkResult(WhisperModel.Base.displayName,status="Не запускалась"))
        cards.forEach { item ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(item.engine,style=MaterialTheme.typography.titleMedium)
                    Text(item.status,style=MaterialTheme.typography.bodySmall)
                    item.mode?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
                    item.diagnostic?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
                    item.transcript?.let { Text(it,style=MaterialTheme.typography.bodyLarge) }
                    item.downloadMs?.let { Text("Загрузка файла модели: ${it} мс") }
                    item.verificationMs?.let { Text("Проверка SHA-256: ${it} мс") }
                    item.initializationMs?.let { Text("Инициализация: ${it} мс") }
                    item.processingMs?.let { Text("Транскрибация: ${it} мс") }
                    if(item.engine.startsWith("Whisper") && item.processingMs!=null && audio!=null) {
                        Text("Скорость относительно аудио: "+
                            "${String.format(java.util.Locale.ROOT,"%.2f",
                                audio.durationMs.toDouble()/item.processingMs.coerceAtLeast(1))}×")
                    }
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
