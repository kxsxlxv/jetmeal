# JetMeal voice lab: Google/Android SpeechRecognizer + Whisper Tiny

## Status
The first working phase supports exactly two engines, sequentially:
- Android system speech recognition (Google if that is the device's configured
  recognizer; OEM services are also possible). The system is requested to
  decode the recorded 16 kHz mono PCM with ru-RU via EXTRA_AUDIO_SOURCE.
- Real multilingual Whisper Tiny via upstream whisper.cpp C++ on arm64 Android
  and JetMeal's own JNI wrapper, using the same recorded PCM file.

Qwen is deliberately deferred. There is no speech-to-diary mutation here.

## Workflow
Settings → Experiments → Speech recognition test → Record → Stop → Compare.
A single 16-bit signed little-endian mono sample is stored in temporary app
cache, capped at 30 seconds. The system recognizer runs first, then Whisper.

On first use, JetMeal prompts before automatically downloading the 77,691,713
byte multilingual ggml-tiny.bin model, over Wi-Fi or mobile data depending on
the device's current network. Models are stored privately (no manual model
file handling). The SHA-256 must exactly match:
be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21
An incomplete, corrupt, or truncated download is discarded; a successful
verified file is atomically installed. Subsequent runs reuse it, while
verifying its checksum. Note that re-verification time is reported separately.

## Timing
Whisper shows download and checksum time (both outside ASR totals), cold
native-model initialization, and transcription. It explicitly frees the
native context after each run, so repeated tests still measure a newly
opened model (OS filesystem cache may remain warm).
System ASR shows time until the service reports ready, time between ready
and final transcript, and total wall time. Google/OEM model load time and
network use cannot be inferred from the API.

## Fairness caveat
Android's official RecognizerIntent.EXTRA_AUDIO_SOURCE is optional. The
recognition service can ignore the passed ParcelFileDescriptor and listen
directly to the microphone instead. It is **not possible to establish** on
every phone, without instrumented testing, that it used the stored PCM.
The UI therefore labels Android timings as experimental, not guaranteed
same-audio or offline. On vivo X300, this needs physical-device verification.
Whisper always uses the captured PCM bytes directly and does not access the
microphone after recording.

## Native build
Pinned upstream whisper.cpp Git SHA:
0c7be696371069e0baa2515ed7aa08d5a5473aa6
NDK: 29.0.14206865; CMake 3.22.1; ABI arm64-v8a. Native sources are
fetched while building the Android APK and linked to JetMeal's JNI layer.
The model weights are not in Git or the APK. Whisper currently uses CPU
inference and explicit Russian decoding; no MediaTek NPU claims.

## Verification
Run CI assembleDebug/testDebugUnitTest/lintDebug/assembleRelease. JNI loading,
network download and latency must be verified on vivo X300. No emulator or
physical-device run has been performed by this environment.

References:
- https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE
- https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android
- https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-tiny.bin
