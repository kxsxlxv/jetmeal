package com.kxsxlxv.jetmeal.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.kxsxlxv.jetmeal.ui.theme.JetmealTheme
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Independent ML Kit live microphone experiment. Not the recorded Whisper PCM. */
class MlKitSpeechActivity : ComponentActivity() {
    private var client: SpeechRecognizer? = null
    private var work: Job? = null
    private var forcedStop: Job? = null
    private var stopping = false
    private var streamStartedAt = 0L

    private var phase by mutableStateOf(MlKitPhase.Idle)
    private var transcript by mutableStateOf(StreamingTranscript())
    private var status by mutableStateOf("Google ML Kit · Basic · русский (ru-RU).")
    private var diagnostic by mutableStateOf("")
    private var setupMs by mutableStateOf<Long?>(null)
    private var downloadMs by mutableStateOf<Long?>(null)
    private var firstPartialMs by mutableStateOf<Long?>(null)
    private var firstFinalMs by mutableStateOf<Long?>(null)
    private var listeningMs by mutableStateOf<Long?>(null)
    private var downloadedBytes by mutableLongStateOf(0)
    private var downloadTotal by mutableLongStateOf(0)
    private var askDownload by mutableStateOf(false)

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start() else status = "Нужен доступ к микрофону."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JetmealTheme {
                Surface(Modifier.fillMaxSize()) {
                    MlKitSpeechScreen(phase, transcript, status, diagnostic,
                        setupMs, downloadMs, firstPartialMs, firstFinalMs, listeningMs,
                        downloadedBytes, downloadTotal, askDownload,
                        onStart=::requestStart, onStop=::stop,
                        onDownload=::confirmDownload, onCancelDownload=::declineDownload,
                        onClose=::finish)
                }
            }
        }
    }

    private fun requestStart() {
        if (phase !in listOf(MlKitPhase.Idle,MlKitPhase.Finished,MlKitPhase.Failed)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) start()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun start() {
        if (work?.isActive == true || client != null) return
        transcript = StreamingTranscript()
        diagnostic = ""
        setupMs = null; downloadMs = null; firstPartialMs = null
        firstFinalMs = null; listeningMs = null
        downloadedBytes = 0; downloadTotal = 0
        askDownload = false; stopping = false
        phase = MlKitPhase.Preparing
        status = "Проверка доступности ML Kit Basic ru-RU…"
        val setupStart = SystemClock.elapsedRealtime()
        work = lifecycleScope.launch {
            try {
                val recognition = SpeechRecognition.getClient(speechRecognizerOptions {
                    locale = Locale.forLanguageTag("ru-RU")
                    preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
                })
                client = recognition
                val availability = recognition.checkStatus()
                setupMs = SystemClock.elapsedRealtime() - setupStart
                when (availability) {
                    FeatureStatus.AVAILABLE -> recognize(recognition)
                    FeatureStatus.DOWNLOADABLE -> {
                        phase = MlKitPhase.WaitingForDownload
                        askDownload = true
                        status = "Для русского ML Kit требуется загрузить системную модель Google."
                    }
                    FeatureStatus.DOWNLOADING -> fail("Модель ещё загружается Google. Повторите позже.",
                        "checkStatus=DOWNLOADING")
                    FeatureStatus.UNAVAILABLE -> fail("ML Kit Basic ru-RU недоступен на телефоне.",
                        "checkStatus=UNAVAILABLE")
                    else -> fail("Неизвестное состояние речевой модели.",
                        "checkStatus=" + availability)
                }
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) { fail("ML Kit: " + e.message,e.javaClass.simpleName) }
            finally {
                if (phase !in listOf(MlKitPhase.WaitingForDownload,MlKitPhase.Downloading,
                        MlKitPhase.Listening,MlKitPhase.Stopping)) closeClient()
            }
        }
    }

    private fun confirmDownload() {
        if (phase != MlKitPhase.WaitingForDownload) return
        val recognition = client ?: return
        askDownload = false
        phase = MlKitPhase.Downloading
        work = lifecycleScope.launch {
            val startMs = SystemClock.elapsedRealtime()
            var complete = false
            try {
                recognition.download().collect { event ->
                    when (event) {
                        is DownloadStatus.DownloadStarted -> {
                            downloadTotal = event.bytesToDownload
                            status = "Google загружает модель для русского языка…"
                        }
                        is DownloadStatus.DownloadProgress -> {
                            downloadedBytes = event.totalBytesDownloaded
                            status = "Загружено " + (downloadedBytes/1_000_000) + " МБ."
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            complete = true
                            downloadMs = SystemClock.elapsedRealtime() - startMs
                            status = "Модель готова. Включаю микрофон…"
                        }
                        is DownloadStatus.DownloadFailed -> {
                            fail("Ошибка загрузки ML Kit: " + event.e.message, "DownloadFailed")
                        }
                    }
                }
                if (phase == MlKitPhase.Failed) return@launch
                if (!complete || recognition.checkStatus() != FeatureStatus.AVAILABLE) {
                    fail("Google не подтвердил готовность модели.",
                        "downloadComplete=" + complete)
                    return@launch
                }
                recognize(recognition)
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) { fail("Сбой модели Google: " + e.message,e.javaClass.simpleName) }
            finally { closeClient() }
        }
    }

    private fun declineDownload() {
        if (phase != MlKitPhase.WaitingForDownload) return
        askDownload = false
        phase = MlKitPhase.Finished
        status = "Загрузка отменена; можно повторить тест позже."
        closeClient()
    }

    /** Partial updates replace each other, final segments append. No fixed 30s limit. */
    private suspend fun recognize(recognition: SpeechRecognizer) {
        phase = MlKitPhase.Listening
        status = "Слушаю… Говорите, затем нажмите «Остановить»."
        streamStartedAt = SystemClock.elapsedRealtime()
        var completed = false
        try {
            val request = speechRecognizerRequest { audioSource = AudioSource.fromMic() }
            recognition.startRecognition(request).collect { response ->
                val elapsed = SystemClock.elapsedRealtime() - streamStartedAt
                when (response) {
                    is SpeechRecognizerResponse.PartialTextResponse -> {
                        if (firstPartialMs == null) firstPartialMs = elapsed
                        transcript = transcript.onPartial(response.text)
                    }
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        if (firstFinalMs == null) firstFinalMs = elapsed
                        transcript = transcript.onFinal(response.text)
                    }
                    is SpeechRecognizerResponse.CompletedResponse -> {
                        completed = true
                        listeningMs = elapsed
                        phase = MlKitPhase.Finished
                        status = if (stopping) "Остановлено пользователем."
                            else "Google самостоятельно завершил распознавание."
                    }
                    is SpeechRecognizerResponse.ErrorResponse -> {
                        listeningMs = elapsed
                        fail("Ошибка ML Kit: " + response.e.message,
                            "errorCode=" + response.e.errorCode)
                    }
                }
            }
            if (phase != MlKitPhase.Failed) {
                listeningMs = listeningMs ?: (SystemClock.elapsedRealtime() - streamStartedAt)
                phase = MlKitPhase.Finished
                if (!completed) status = "Поток завершился без CompletedResponse."
            }
        } catch (c:CancellationException) { throw c }
        catch (e:Exception) { fail("Ошибка потока ML Kit: " + e.message,e.javaClass.simpleName) }
        finally {
            forcedStop?.cancel(); forcedStop = null
            closeClient()
            if (phase in listOf(MlKitPhase.Listening,MlKitPhase.Stopping)) {
                phase = MlKitPhase.Finished
                status = "Поток завершён."
                listeningMs = SystemClock.elapsedRealtime() - streamStartedAt
            }
        }
    }

    private fun stop() {
        if (phase != MlKitPhase.Listening || stopping) return
        stopping = true
        phase = MlKitPhase.Stopping
        status = "Останавливаю… Ожидаю окончательный текст."
        lifecycleScope.launch {
            try { client?.stopRecognition() }
            catch (e: Exception) {
                fail("Ошибка остановки ML Kit: " + e.message,e.javaClass.simpleName)
                work?.cancel()
            }
        }
        forcedStop?.cancel()
        forcedStop = lifecycleScope.launch {
            delay(8_000)
            if (phase == MlKitPhase.Stopping) {
                diagnostic = "stopRecognition() не завершил поток за 8 секунд."
                status = "Ожидание превышено; показан последний текст."
                listeningMs = SystemClock.elapsedRealtime() - streamStartedAt
                phase = MlKitPhase.Finished
                work?.cancel()
                closeClient()
            }
        }
    }

    private fun fail(message: String, detail: String) {
        status = message
        diagnostic = detail
        phase = MlKitPhase.Failed
    }

    private fun closeClient() {
        val previous = client
        client = null
        if (previous != null) runCatching { previous.close() }
    }

    override fun onDestroy() {
        forcedStop?.cancel()
        work?.cancel()
        closeClient()
        super.onDestroy()
    }
}

internal enum class MlKitPhase {
    Idle, Preparing, WaitingForDownload, Downloading, Listening, Stopping, Finished, Failed
}

@Composable
private fun MlKitSpeechScreen(
    phase:MlKitPhase, transcript:StreamingTranscript, status:String, diagnostic:String,
    setupMs:Long?, downloadMs:Long?, firstPartialMs:Long?, firstFinalMs:Long?,
    listeningMs:Long?, downloadedBytes:Long, downloadTotal:Long, askDownload:Boolean,
    onStart:()->Unit, onStop:()->Unit, onDownload:()->Unit,
    onCancelDownload:()->Unit, onClose:()->Unit,
) {
    if (askDownload) AlertDialog(
        onDismissRequest=onCancelDownload,
        title={Text("Загрузить модель Google?")},
        text={Text("ML Kit Basic потребовал системную модель для русского языка. "+
            "Google скачает её автоматически; вручную ничего искать не нужно. "+
            "Размер зависит от системы, возможен расход мобильного трафика.")},
        confirmButton={TextButton(onClick=onDownload){Text("Загрузить")}},
        dismissButton={TextButton(onClick=onCancelDownload){Text("Отмена")}}
    )
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("Google ML Kit · потоковая речь",style=MaterialTheme.typography.headlineMedium)
        Text("Basic · ru-RU (beta) · локальный микрофон. "+
            "Это отдельный тест, не запись Whisper.",
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(status)
        if (diagnostic.isNotBlank()) Text(diagnostic,color=MaterialTheme.colorScheme.error,
            style=MaterialTheme.typography.labelMedium)
        if (phase==MlKitPhase.Downloading && downloadTotal>0) {
            LinearProgressIndicator(progress={ (downloadedBytes.toFloat() / downloadTotal)
                .coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
            Text((downloadedBytes/1_000_000).toString() + " / " +
                (downloadTotal/1_000_000) + " МБ")
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick=onStart,
                enabled=phase in listOf(MlKitPhase.Idle,MlKitPhase.Finished,MlKitPhase.Failed)) {
                Text("Начать диктовку")
            }
            Button(onClick=onStop,enabled=phase==MlKitPhase.Listening) {
                Text("Остановить")
            }
        }
        Text("Текст в реальном времени",style=MaterialTheme.typography.titleLarge)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(transcript.visible.isBlank()) Text("Промежуточная транскрипция появится здесь…",
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    if(transcript.confirmed.isNotBlank())
                        Text(transcript.confirmed,style=MaterialTheme.typography.bodyLarge)
                    if(transcript.partial.isNotBlank())
                        Text(transcript.partial,style=MaterialTheme.typography.bodyLarge,
                            color=MaterialTheme.colorScheme.primary)
                }
                Text("Финальных сегментов: " + transcript.finalSegments +
                    " · обновлений: " + transcript.partialUpdates,
                    style=MaterialTheme.typography.labelSmall)
            }
        }
        Text("Диагностика",style=MaterialTheme.typography.titleLarge)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Google ML Kit genai-speech-recognition:1.0.0-alpha1")
                Text("MODE_BASIC · ru-RU · AudioSource.fromMic()")
                setupMs?.let{Text("Инициализация и проверка модели: " + it + " мс")}
                downloadMs?.let{Text("Загрузка модели: " + it + " мс")}
                firstPartialMs?.let{Text("Первый промежуточный текст: " + it + " мс")}
                firstFinalMs?.let{Text("Первый окончательный фрагмент: " + it + " мс")}
                listeningMs?.let{Text("Длительность сессии: " + it + " мс")}
                Text("Состояние: " + phase.name,style=MaterialTheme.typography.labelSmall)
            }
        }
        Text("Модель Basic отличается от движка Gboard; качество и длительность "+
            "непрерывной диктовки проверяем на vivo X300. "+
            "Продукты не добавляются в дневник.",
            color=MaterialTheme.colorScheme.onSurfaceVariant,
            style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick=onClose,modifier=Modifier.fillMaxWidth()) { Text("Закрыть") }
    }
}
