package com.kxsxlxv.jetmeal.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
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
    private var selectedMode by mutableStateOf(MlKitMode.Basic)
    private var featureStatus by mutableStateOf("Не проверено")
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
                    MlKitSpeechScreen(phase, selectedMode, featureStatus,
                        transcript, status, diagnostic,
                        setupMs, downloadMs, firstPartialMs, firstFinalMs, listeningMs,
                        downloadedBytes, downloadTotal, askDownload,
                        deviceName="${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
                        onModeChange=::switchMode, onCheckAvailability=::probeSelectedMode,
                        onStart=::requestStart, onStop=::stop,
                        onDownload=::confirmDownload, onCancelDownload=::declineDownload,
                        onClose=::finish)
                }
            }
        }
    }

    private fun optionsFor(mode: MlKitMode): SpeechRecognizerOptions =
        speechRecognizerOptions {
            locale = Locale.forLanguageTag("ru-RU")
            preferredMode = when(mode) {
                MlKitMode.Basic -> SpeechRecognizerOptions.Mode.MODE_BASIC
                MlKitMode.Advanced -> SpeechRecognizerOptions.Mode.MODE_ADVANCED
            }
        }

    private fun canInteract(): Boolean =
        phase in listOf(MlKitPhase.Idle, MlKitPhase.Finished, MlKitPhase.Failed) &&
            work?.isActive != true && client == null

    private fun switchMode(mode: MlKitMode) {
        if (!canInteract() || mode == selectedMode) return
        selectedMode = mode
        featureStatus = "Не проверено"
        transcript = StreamingTranscript()
        status = "Выбран ${mode.label}. Нажмите «Проверить доступность»."
        diagnostic = ""
        setupMs = null; downloadMs = null; firstPartialMs = null
        firstFinalMs = null; listeningMs = null
        downloadedBytes = 0; downloadTotal = 0
        phase = MlKitPhase.Idle
    }

    /** Probe without touching the microphone or requesting permission.
     * AICore/Google may restrict Advanced even on a powerful non-Pixel SoC.
     * The SDK's preferredMode is a request; do not silently claim confirmed
     * Advanced inference simply because the check succeeds.
     */
    private fun probeSelectedMode() {
        if (!canInteract()) return
        val mode = selectedMode
        phase = MlKitPhase.Preparing
        status = "Проверяю поддержку ML Kit ${mode.label} ru-RU…"
        featureStatus = "Проверка…"
        diagnostic = ""
        val started = SystemClock.elapsedRealtime()
        work = lifecycleScope.launch {
            var recognizer: SpeechRecognizer? = null
            try {
                recognizer = SpeechRecognition.getClient(optionsFor(mode))
                val availability = recognizer.checkStatus()
                setupMs = SystemClock.elapsedRealtime() - started
                featureStatus = availabilityLabel(availability)
                phase = MlKitPhase.Finished
                status = when(availability) {
                    FeatureStatus.AVAILABLE ->
                        "${mode.label}: API сообщил AVAILABLE. Можно попробовать диктовку."
                    FeatureStatus.DOWNLOADABLE ->
                        "${mode.label}: модель доступна для загрузки. Запустите диктовку, чтобы запросить загрузку."
                    FeatureStatus.DOWNLOADING ->
                        "${mode.label}: Google ещё загружает модель. Повторите позже."
                    FeatureStatus.UNAVAILABLE -> mode.unavailableMessage()
                    else -> "${mode.label}: неизвестное состояние ($availability)."
                }
                diagnostic = if(availability == FeatureStatus.UNAVAILABLE && mode == MlKitMode.Advanced)
                    "Ограничение Google/AICore, не недостаток производительности смартфона. "+
                        "Никакие модели не загружались, Basic не изменён."
                else "Проверка выполнена без микрофона. Advanced — запрошенный preferredMode."
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) {
                if(mlKitFeatureNotProvisioned(e.message)) {
                    phase = MlKitPhase.Finished
                    featureStatus = "FEATURE_NOT_FOUND (606)"
                    status = "Google AICore пока не предоставил модель ${mode.label}. "+
                        "Проверьте обновления Google/AICore или повторите проверку позже."
                    diagnostic = "AICore 606: ${e.message}; "+
                        "устройство: ${Build.MANUFACTURER} ${Build.MODEL}"
                } else {
                    fail("Ошибка проверки ${mode.label}: ${e.message}",
                        "${e.javaClass.simpleName}; устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
                    featureStatus = "Ошибка API"
                }
            } finally {
                runCatching { recognizer?.close() }
            }
        }
    }

    private fun availabilityLabel(value:Int):String = when(value) {
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN ($value)"
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
        featureStatus = "Проверка…"
        askDownload = false; stopping = false
        phase = MlKitPhase.Preparing
        status = "Проверка доступности ML Kit ${selectedMode.label} ru-RU…"
        val setupStart = SystemClock.elapsedRealtime()
        work = lifecycleScope.launch {
            try {
                val mode = selectedMode
                val recognition = SpeechRecognition.getClient(optionsFor(mode))
                client = recognition
                val availability = recognition.checkStatus()
                setupMs = SystemClock.elapsedRealtime() - setupStart
                featureStatus = availabilityLabel(availability)
                when (availability) {
                    FeatureStatus.AVAILABLE -> recognize(recognition)
                    FeatureStatus.DOWNLOADABLE -> {
                        phase = MlKitPhase.WaitingForDownload
                        askDownload = true
                        status = selectedMode.downloadableMessage()
                    }
                    FeatureStatus.DOWNLOADING -> fail("Модель ещё загружается Google. Повторите позже.",
                        "checkStatus=DOWNLOADING; requestedMode=${mode.label}")
                    FeatureStatus.UNAVAILABLE -> fail(mode.unavailableMessage(),
                        "checkStatus=UNAVAILABLE; requestedMode=${mode.label}; "+
                            "device=${Build.MANUFACTURER} ${Build.MODEL}")
                    else -> fail("Неизвестное состояние речевой модели.",
                        "checkStatus=" + availability)
                }
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) {
                if(mlKitFeatureNotProvisioned(e.message)) {
                    fail("AICore не предоставил модель ${selectedMode.label} (606). "+
                            "Вернитесь к Basic или повторите проверку после обновления Google.",
                        "FEATURE_NOT_FOUND (606): ${e.message}")
                    featureStatus="FEATURE_NOT_FOUND (606)"
                } else fail("ML Kit: " + e.message,e.javaClass.simpleName)
            }
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
        status = "ML Kit ${selectedMode.label}: слушаю… Нажмите «Остановить» после диктовки."
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
    phase:MlKitPhase, selectedMode:MlKitMode, featureStatus:String,
    transcript:StreamingTranscript, status:String, diagnostic:String,
    setupMs:Long?, downloadMs:Long?, firstPartialMs:Long?, firstFinalMs:Long?,
    listeningMs:Long?, downloadedBytes:Long, downloadTotal:Long, askDownload:Boolean,
    deviceName:String,
    onModeChange:(MlKitMode)->Unit, onCheckAvailability:()->Unit,
    onStart:()->Unit, onStop:()->Unit, onDownload:()->Unit,
    onCancelDownload:()->Unit, onClose:()->Unit,
) {
    if (askDownload) AlertDialog(
        onDismissRequest=onCancelDownload,
        title={Text("Загрузить модель Google?")},
        text={Text("ML Kit ${selectedMode.label} запросил системную модель для русского языка. "+
            "Google скачает её автоматически после подтверждения; "+
            "размер зависит от системы, возможен расход мобильного трафика.")},
        confirmButton={TextButton(onClick=onDownload){Text("Загрузить")}},
        dismissButton={TextButton(onClick=onCancelDownload){Text("Отмена")}}
    )
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("Google ML Kit · потоковая речь",style=MaterialTheme.typography.headlineMedium)
        Text("Выбери режим и проверь его доступность на устройстве.",
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            MlKitMode.entries.forEach { mode ->
                FilterChip(
                    selected=selectedMode==mode,
                    enabled=phase in listOf(MlKitPhase.Idle,MlKitPhase.Finished,MlKitPhase.Failed),
                    onClick={onModeChange(mode)},label={Text(mode.label)}
                )
            }
        }
        Text(selectedMode.selectionHint,style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick=onCheckAvailability,
            enabled=phase in listOf(MlKitPhase.Idle,MlKitPhase.Finished,MlKitPhase.Failed),
            modifier=Modifier.fillMaxWidth()) {
            Text("Проверить доступность ${selectedMode.label}")
        }
        Text("Результат checkStatus(): $featureStatus",
            style=MaterialTheme.typography.labelMedium)
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
                Text("Запрошено: MODE_${selectedMode.name.uppercase(Locale.ROOT)} · ru-RU · fromMic()")
                Text("Устройство: $deviceName")
                setupMs?.let{Text("Инициализация и проверка модели: " + it + " мс")}
                downloadMs?.let{Text("Загрузка модели: " + it + " мс")}
                firstPartialMs?.let{Text("Первый промежуточный текст: " + it + " мс")}
                firstFinalMs?.let{Text("Первый окончательный фрагмент: " + it + " мс")}
                listeningMs?.let{Text("Длительность сессии: " + it + " мс")}
                Text("Состояние: " + phase.name,style=MaterialTheme.typography.labelSmall)
            }
        }
        Text("Advanced пока официально ограничен отдельными моделями Pixel. "+
            "На vivo X300 поддержка может отсутствовать. Проверка не обходит AICore: "+
            "если Advanced недоступен, переключись обратно на Basic. "+
            "Это не Gboard; продукты не добавляются в дневник.",
            color=MaterialTheme.colorScheme.onSurfaceVariant,
            style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick=onClose,modifier=Modifier.fillMaxWidth()) { Text("Закрыть") }
    }
}
