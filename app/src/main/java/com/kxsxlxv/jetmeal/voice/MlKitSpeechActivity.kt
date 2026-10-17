package com.kxsxlxv.jetmeal.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Comparison is isolated from the existing live microphone session.
    private val compareStopFlag = AtomicBoolean(false)
    private var compareRecord: AudioRecord? = null
    private var compareCaptureJob: Job? = null
    private var compareRunJob: Job? = null
    private var compareClient: SpeechRecognizer? = null
    private var compareRecording by mutableStateOf(false)
    private var compareRunning by mutableStateOf(false)
    private var compareAudio by mutableStateOf<RecordedPcm?>(null)
    private var compareTrimSilence by mutableStateOf(true)
    private var compareWindow by mutableStateOf<PcmSpeechWindow?>(null)
    private var compareStatus by mutableStateOf("Запишите одну фразу для двух режимов.")
    private var compareResults by mutableStateOf(emptyList<MlKitComparisonResult>())
    private var comparePrompt by mutableStateOf<MlKitMode?>(null)
    private var compareDownloadConsent: CompletableDeferred<Boolean>? = null
    private var permissionForComparison = false

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (permissionForComparison) beginCompareRecording() else start()
            } else {
                if (permissionForComparison) compareStatus="Нужен доступ к микрофону."
                else status="Нужен доступ к микрофону."
            }
            permissionForComparison=false
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
                        compareRecording,compareRunning,compareAudio,compareResults,
                        compareStatus,comparePrompt,compareTrimSilence,compareWindow,
                        onCompareTrimChange={ enabled ->
                            if(!compareRunning && !compareRecording) {
                                compareTrimSilence=enabled
                                compareWindow=null
                                compareResults=emptyList()
                            }
                        },
                        onCompareRecord=::requestCompareRecording,
                        onCompareStop=::stopCompareRecording,
                        onCompareRun=::runComparison,
                        onCompareConsent=::onCompareDownloadConsent,
                        deviceName="${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
                        onModeChange=::switchMode, onCheckAvailability=::probeSelectedMode,
                        onStart=::requestStart, onStop=::stop,
                        onDownload=::confirmDownload, onCancelDownload=::declineDownload,
                        onClose=::finish)
                }
            }
        }
    }

    private fun requestCompareRecording() {
        if (compareRecording || compareRunning || !canInteract()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) beginCompareRecording()
        else {
            permissionForComparison=true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginCompareRecording() {
        if(compareRecording || compareRunning || !canInteract()) return
        compareAudio?.file?.delete()
        compareAudio=null
        compareWindow=null
        compareResults=emptyList()
        val minBuffer=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if(minBuffer<=0) {
            compareStatus="Невозможно открыть микрофон (код $minBuffer)."
            return
        }
        val file=File(cacheDir,"mlkit-mode-compare.pcm")
        val capacity=16000 * 2L * 30
        try {
            @Suppress("MissingPermission")
            val rec=AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,maxOf(minBuffer*2,4096))
            if(rec.state!=AudioRecord.STATE_INITIALIZED) {
                rec.release()
                compareStatus="Микрофон не инициализировался."
                return
            }
            rec.startRecording()
            compareRecord=rec
            compareStopFlag.set(false)
            compareRecording=true
            compareStatus="Идёт запись. Продиктуйте фразу и нажмите «Стоп» (максимум 30 с)."
            compareCaptureJob=lifecycleScope.launch(Dispatchers.IO) {
                var count=0L
                var error:String?=null
                try {
                    FileOutputStream(file).use { output ->
                        val block=ByteArray(4096)
                        while(!compareStopFlag.get() && count < capacity) {
                            val remaining=remainingPcmBytes(count,capacity,block.size)
                            if(remaining==0) break
                            val length=rec.read(block,0,remaining,AudioRecord.READ_BLOCKING)
                            if(length>0) {
                                output.write(block,0,length)
                                count+=length
                            } else if(length<0 && !compareStopFlag.get()) {
                                error="Ошибка чтения микрофона: $length"
                                break
                            } else if(length<=0) break
                        }
                    }
                } catch(e:Exception) {
                    error=e.javaClass.simpleName+": "+e.message
                } finally {
                    runCatching { rec.stop() }
                    rec.release()
                    if(compareRecord===rec) compareRecord=null
                    withContext(Dispatchers.Main) {
                        compareRecording=false
                        if(count>0 && error==null) {
                            val duration=count*1000/MLKIT_AUDIO_BYTES_PER_SECOND
                            compareAudio=RecordedPcm(file,count,duration)
                            compareStatus="Готово: ${duration} мс, $count байт. Оба режима получат этот PCM."
                        } else {
                            file.delete()
                            compareAudio=null
                            compareStatus=error ?: "Запись пуста; попробуйте ещё раз."
                        }
                    }
                }
            }
        } catch(e:Exception) {
            compareRecording=false
            compareRecord=null
            compareStatus="Ошибка микрофона: "+e.message
        }
    }

    private fun stopCompareRecording() {
        if(!compareRecording) return
        compareStopFlag.set(true)
        runCatching {compareRecord?.stop()}
    }

    private fun onCompareDownloadConsent(accept:Boolean) {
        comparePrompt=null
        compareDownloadConsent?.complete(accept)
        compareDownloadConsent=null
    }

    /** Basic first, Advanced second. Each receives *the exact same file* via
     * a separately paced pipe. Preparation/download are excluded from
     * recognition latency; neither mode reopens the microphone.
     */
    private fun runComparison() {
        val audio=compareAudio ?: return
        if (compareRunning || compareRecording || !canInteract()) return
        compareRunning=true
        compareWindow=null
        compareResults=MlKitMode.entries.map {
            MlKitComparisonResult(it,"В очереди",totalBytes=audio.bytes,sourceBytes=audio.bytes)
        }
        compareStatus="Анализирую начало записи перед одинаковой подачей обоим режимам…"
        compareRunJob=lifecycleScope.launch {
            try {
                val speechWindow=withContext(Dispatchers.IO) {
                    detectSpeechWindow(audio.file,audio.bytes,compareTrimSilence)
                }
                compareWindow=speechWindow
                compareStatus=if(speechWindow.removedMs>0)
                    "Убрано ${speechWindow.removedMs} мс начальной тишины; "+
                        "оба режима получат один и тот же фрагмент."
                else "Длительной начальной тишины не обнаружено; "+
                    "оба режима получат исходный PCM."
                for(mode in MlKitMode.entries) {
                    compareStatus="ML Kit ${mode.label}: проверяю модель…"
                    var recognition:SpeechRecognizer?=null
                    val result=try {
                        val checkStart=SystemClock.elapsedRealtime()
                        val recognizer=SpeechRecognition.getClient(optionsFor(mode))
                        recognition=recognizer
                        compareClient=recognizer
                        var feature=recognizer.checkStatus()
                        val checkMs=SystemClock.elapsedRealtime()-checkStart
                        var downloadMs:Long?=null
                        if(feature==FeatureStatus.DOWNLOADABLE) {
                            comparePrompt=mode
                            compareStatus="Google предлагает скачать модель ${mode.label}. Ожидаю решения."
                            val consent=CompletableDeferred<Boolean>()
                            compareDownloadConsent=consent
                            val accepted=consent.await()
                            if(!accepted) {
                                MlKitComparisonResult(mode,"Загрузка отменена пользователем.",
                                    modelCheckMs=checkMs,totalBytes=speechWindow.transmittedBytes,sourceBytes=audio.bytes,
                                    firstVoiceMs=speechWindow.firstVoiceMs,
                                    removedSilenceMs=speechWindow.removedMs)
                            } else {
                                val started=SystemClock.elapsedRealtime()
                                var failed:String?=null
                                recognizer.download().collect { event ->
                                    when(event) {
                                        is DownloadStatus.DownloadProgress ->
                                            compareStatus="${mode.label}: загрузка ${event.totalBytesDownloaded/1_000_000} МБ"
                                        is DownloadStatus.DownloadFailed -> failed=event.e.message
                                        else -> Unit
                                    }
                                }
                                downloadMs=SystemClock.elapsedRealtime()-started
                                feature=recognizer.checkStatus()
                                if(failed!=null || feature!=FeatureStatus.AVAILABLE) {
                                    MlKitComparisonResult(mode,"Ошибка загрузки модели: "+(failed ?:
                                        "состояние $feature"),modelCheckMs=checkMs,
                                        downloadMs=downloadMs,totalBytes=speechWindow.transmittedBytes,sourceBytes=audio.bytes,
                                    firstVoiceMs=speechWindow.firstVoiceMs,
                                    removedSilenceMs=speechWindow.removedMs)
                                } else {
                                    transcribeComparedMode(recognizer,mode,audio,speechWindow,checkMs,downloadMs)
                                }
                            }
                        } else if(feature!=FeatureStatus.AVAILABLE) {
                            MlKitComparisonResult(mode,"Недоступно: checkStatus = $feature",
                                modelCheckMs=checkMs,totalBytes=speechWindow.transmittedBytes,sourceBytes=audio.bytes,
                                    firstVoiceMs=speechWindow.firstVoiceMs,
                                    removedSilenceMs=speechWindow.removedMs)
                        } else transcribeComparedMode(recognizer,mode,audio,speechWindow,checkMs,downloadMs)
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) {
                        MlKitComparisonResult(mode,"Ошибка: "+e.javaClass.simpleName+": "+e.message,
                            totalBytes=speechWindow.transmittedBytes,sourceBytes=audio.bytes,
                                    firstVoiceMs=speechWindow.firstVoiceMs,
                                    removedSilenceMs=speechWindow.removedMs)
                    } finally {
                        runCatching{recognition?.close()}
                        compareClient=null
                    }
                    compareResults=compareResults.map { if(it.mode==mode) result else it }
                }
                compareStatus="Сравнение завершено. Использовался один PCM для обоих режимов."
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                compareStatus="Ошибка подготовки записи: "+e.javaClass.simpleName+": "+e.message
                compareResults=compareResults.map { it.copy(status=compareStatus) }
            } finally {
                compareRunning=false
                comparePrompt=null
                compareDownloadConsent?.cancel()
                compareDownloadConsent=null
                compareClient=null
            }
        }
    }

    private suspend fun transcribeComparedMode(
        recognizer: SpeechRecognizer,mode:MlKitMode,audio:RecordedPcm,
        speechWindow:PcmSpeechWindow,checkMs:Long,downloadMs:Long?,
    ):MlKitComparisonResult {
        compareStatus="${mode.label}: обрабатываю записанный PCM…"
        return recognizeMlKitRecordedAudio(recognizer,mode,audio,speechWindow,checkMs,downloadMs) { result ->
            compareResults=compareResults.map { if(it.mode==mode) result else it }
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
        if (!canInteract() || compareRunning || compareRecording || mode == selectedMode) return
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
        if (!canInteract() || compareRunning || compareRecording) return
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
        if (phase !in listOf(MlKitPhase.Idle,MlKitPhase.Finished,MlKitPhase.Failed) ||
            compareRunning || compareRecording) return
        permissionForComparison=false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) start()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun start() {
        if (work?.isActive == true || client != null ||
            compareRunning || compareRecording) return
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
        compareStopFlag.set(true)
        runCatching{compareRecord?.stop()}
        compareCaptureJob?.cancel()
        compareRunJob?.cancel()
        compareDownloadConsent?.cancel()
        runCatching{compareClient?.close()}
        compareAudio?.file?.delete()
        File(cacheDir,"mlkit-mode-compare.pcm").delete()
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
    compareRecording:Boolean,compareRunning:Boolean,
    compareAudio:RecordedPcm?,compareResults:List<MlKitComparisonResult>,
    compareStatus:String,comparePrompt:MlKitMode?,
    compareTrimSilence:Boolean,compareWindow:PcmSpeechWindow?,
    onCompareTrimChange:(Boolean)->Unit,
    onCompareRecord:()->Unit,onCompareStop:()->Unit,onCompareRun:()->Unit,
    onCompareConsent:(Boolean)->Unit,
    deviceName:String,
    onModeChange:(MlKitMode)->Unit, onCheckAvailability:()->Unit,
    onStart:()->Unit, onStop:()->Unit, onDownload:()->Unit,
    onCancelDownload:()->Unit, onClose:()->Unit,
) {
    if(comparePrompt!=null) AlertDialog(
        onDismissRequest={ onCompareConsent(false) },
        title={ Text("Загрузить модель ${comparePrompt.label}?") },
        text={ Text("Google ML Kit запросил модель ${comparePrompt.label} для сравнения "+
            "двух режимов. Загрузка может расходовать мобильный трафик.") },
        confirmButton={ TextButton(onClick={onCompareConsent(true)}){Text("Загрузить")} },
        dismissButton={ TextButton(onClick={onCompareConsent(false)}){Text("Пропустить")} }
    )
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
                    enabled=phase in listOf(MlKitPhase.Idle,MlKitPhase.Finished,MlKitPhase.Failed)
                        && !compareRecording && !compareRunning,
                    onClick={onModeChange(mode)},label={Text(mode.label)}
                )
            }
        }
        Text(selectedMode.selectionHint,style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick=onCheckAvailability,
            enabled=phase in listOf(MlKitPhase.Idle,MlKitPhase.Finished,MlKitPhase.Failed)
                && !compareRecording && !compareRunning,
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
                enabled=phase in listOf(MlKitPhase.Idle,MlKitPhase.Finished,MlKitPhase.Failed)
                    && !compareRecording && !compareRunning) {
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
        HorizontalDivider()
        MlKitComparisonPanel(
            compareRecording,compareRunning,compareAudio,compareResults,compareStatus,
            trimSilence=compareTrimSilence,window=compareWindow,
            onTrimChange=onCompareTrimChange,
            canStart=phase in listOf(MlKitPhase.Idle,MlKitPhase.Finished,MlKitPhase.Failed),
            onRecord=onCompareRecord,onStop=onCompareStop,onRun=onCompareRun,
        )
        HorizontalDivider()
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
