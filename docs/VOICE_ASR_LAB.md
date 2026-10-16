## Three-engine comparison: multilingual Whisper Base (2026-10-17)

Whisper Base multilingual is included as the second local model with exactly
the same native whisper.cpp decoder used by Tiny. Both process the identical
recorded PCM; they are run sequentially, not as two resident contexts.

- ggml-tiny.bin: 77,691,713 bytes; SHA-256
  be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21
- ggml-base.bin: 147,951,465 bytes; SHA-256
  60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe
- Source: https://huggingface.co/ggerganov/whisper.cpp/tree/main

First-use automatic app-private download asks user before consuming network
data. Checksums, independent files, disk capacity, download timing and
per-run model initialization/decoding timings are retained. The first run
will be slower due to downloading; comparisons should use subsequent runs.

Android's on-device speech service on vivo X300 sent all 5.5 s of prerecorded
PCM but returned an empty onResults list, while Whisper transcribed correctly.
This is now treated as NO_MATCH, not as a successful empty transcript.
As before, an on-device no-match retries the default system provider with
the same PCM; a separately labelled live-mic button can verify whether
Google/OEM recognizes a NEW utterance. Android's EXTRA_AUDIO_SOURCE is
optional: it is not possible to force Google/OEM to support recording replay.
Results must not claim a transcript that the service did not provide.

The voice experiment still does not parse or save any food entries.

## Vivo X300 debugging, 2026-10-16

Real-world report on vivo X300:
- 30,080 ms PCM (962,560 bytes) was rejected by whisper.cpp JNI because
  native code caps PCM at 30 seconds (960,000 bytes). The audio recorder
  requested 4096-byte chunks without clipping the FINAL read. This was an
  implementation error rather than a Whisper model issue.
- System on-device recognizer returned error 7 = ERROR_NO_MATCH after roughly
  one second; it did not produce a usable transcript. The old PCM writer
  streamed the entire recorded audio as fast as possible before the service
  had finished initialization. Whether the OEM recognizer supports the
  EXTRA_AUDIO_SOURCE extra remains unknown.

Fix:
- Never read more PCM bytes than the native 30-second cap; keep 16-bit
  sample alignment. Add regression tests using the exact problematic bytes.
- Wait for recognizer onReadyForSpeech (up to two seconds), stream PCM via
  pipe paced at roughly 100 ms audio per 100 ms wall-time, extend the ASR
  deadline to accommodate the actual audio duration. Report sent bytes,
  service readiness, EOF and the default provider package.
- If an on-device recognizer returns NO_MATCH (7), try the ordinary system
  recognizer *with the same recorded PCM* and explicitly flag possible network
  use. Neither path is falsely represented as confirmed PCM support.
- If the recorded PCM still fails, offer a **separate live-microphone** test
  (a new spoken take, clearly distinguished from the original recording).
  This helps distinguish unsupported PCM injection from unavailable speech
  recognition. Do not merge live-microphone text with the Whisper comparison.
- Wrap the lab in a Material Surface for proper light/dark text contrast.

This still requires a physical vivo X300 regression test to confirm OEM
services respect the audio source and timing. Fully successful Android CI does
not substitute for real-device validation.

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
