## vivo X300: Advanced fromPfd NO_SPEECH_DETECTED diagnostic (2026-10-17)

One 29.5-second PCM file produced a multi-segment Basic transcription while
Advanced returned ERROR_TYPE_NO_SPEECH_DETECTED (zero partial/final events).
Both clients were fed 944000 bytes through independently paced PFD streams.
This is **not evidence that Advanced is worse at recognising Russian**:
Advanced worked in the separate live-microphone experiment.

The SDK's `fromPfd()` handling may be sensitive to leading silence:
upstream https://github.com/googlesamples/mlkit/issues/1027
shows a related Advanced bug for ~800ms of silence, though that report
returns INVALID_REQUEST, *not* NO_SPEECH_DETECTED. It is a hypothesis,
not a confirmed fix for vivo X300.

The experiment now scans the beginning of the **original, app-private** mono
16kHz signed-16-bit PCM for sustained energy (20ms frames, ≥400 RMS in
three consecutive frames). If speech starts ≥350ms into the recording, the
common replay interval begins 240ms ahead of estimated speech onset.
No detected onset, very early voice, or disabled trimming → retain the
original file. This is deliberately a conservative **energy heuristic,
not a trained voice activity detector**, and may fail for quiet speakers
or noise. It changes neither the original file nor any Whisper input.

Both ML Kit clients receive the exact same calculated byte window.
A user-visible toggle enables running **trimmed vs original on the same
recording**, and the UI reports estimated onset, trimmed milliseconds,
bytes and wall-clock elapsed time. Retain a full diagnostic failure
if Advanced still does not return final segments. Live ML Kit streaming
remains untouched. Verify on the physical vivo X300 before claiming a fix.

## Basic vs Advanced: same-recording experiment (2026-10-17)

The streaming ML Kit screen now also contains a **«Basic vs Advanced · одна запись»**
panel. It does NOT require switching away from the existing live dictation
experiment. It records a 16 kHz, 16-bit little-endian mono PCM clip, limited
to 30 seconds, **once**, and replays that exact file sequentially to two
separately instantiated Google ML Kit Speech Recognition clients:
`MODE_BASIC` followed by `MODE_ADVANCED`, locale `ru-RU`.

**Important input constraint:** Official Google ML Kit docs say
`AudioSource.fromPfd(ParcelFileDescriptor)` accepts *raw headerless* PCM
but **only when samples are streamed at real-time speed**. Plain file-backed
descriptors, which read the file instantly, are explicitly unsupported.
The JetMeal experiment creates a fresh pipe per mode, sends 3200 bytes
per 100 ms (16000 mono samples per second, 16-bit), waits for EOF and
allows Google a brief extra interval to emit final transcript segments.
It never re-records audio for the second mode.

Both modes report the final transcript, model availability check (and
optional consent-based model download), first partial, first final,
complete elapsed recognition time and exact number of PCM bytes written.
A single model's failure does not copy the other model's text.
The total elapsed *includes real-time input replay*, so it is not a
pure inference-speed benchmark and cannot be compared to Whisper's
faster-than-real-time offline decoding as if they measured identical work.
Model preparation/download are timed separately.

**Known caveat:** `preferredMode` requests MODE_ADVANCED or MODE_BASIC,
but the SDK does not expose a separate cryptographic/diagnostic attestation
of which underlying Google model was actually used. Our UI names the
requested mode without inventing confirmation. The vivo X300 sample
already demonstrated `checkStatus=AVAILABLE` for requested Advanced,
with live streaming text, but this does not prove the effective backend.

This is an experiment: SDK alpha and OEM/AICore behavior for
`AudioSource.fromPfd` requires physical vivo X300 confirmation. If
Google's service does not process supplied PCM, that mode must report
a failure (not a fabricated transcript). Both existing direct microphone
modes remain available.

Docs: https://developers.google.com/ml-kit/genai/speech-recognition/android

## ML Kit Advanced availability experiment (2026-10-17)

The existing Settings → Google ML Kit streaming screen now offers explicit
**Basic / Advanced** chips. **Basic remains the default and is unchanged.**
The optional Advanced path requests the official
`SpeechRecognizerOptions.Mode.MODE_ADVANCED` for `ru-RU` via the existing
`genai-speech-recognition:1.0.0-alpha1` package. No Gboard implementation,
food parsing, widget microphone, or speech-to-diary write was added.

Selecting Advanced does **not** download a model, start a microphone, or
silently fall back to Basic. Press **«Проверить доступность Advanced»** to
invoke `checkStatus()` without requiring microphone permission. JetMeal
displays AVAILABLE / DOWNLOADABLE / DOWNLOADING / UNAVAILABLE, elapsed check
time, selected requested mode, build manufacturer/model and API errors.

- **AVAILABLE:** the user can press Start to experiment with streaming
  microphone capture, incremental text and manual Stop.
- **DOWNLOADABLE:** only a separate explicit confirmation when trying Start
  invokes Google's `download()` with progress.
- **DOWNLOADING / UNAVAILABLE:** show the true provider result, not a fake
  transcript and not an automatic Basic fallback.
- Some AICore builds throw `606-FEATURE_NOT_FOUND` rather than return
  UNAVAILABLE if Google has not provisioned the requested model. JetMeal
  recognises this result and shows the AICore error without crashing or
  silently switching to Basic.
- Advanced is a **preferredMode** request; unless the Google service
  verifies the effective backend, the UI must not claim that a Gemini
  Advanced model was definitely used.
- API availability is Google/AICore-controlled. Official public support
  currently lists select Pixel 10 / Pixel 11 devices; vivo X300 may return UNAVAILABLE
  even though its hardware is powerful and ML Kit Basic works.

Google official support list (may change; verified October 2026):
https://developers.google.com/ml-kit/genai/speech-recognition/android

## Google ML Kit Speech — isolated streaming experiment (2026-10-17)

The lab now offers **Google ML Kit GenAI Speech Recognition 1.0.0-alpha1**
through Settings → Experiments → Google ML Kit · потоковая речь and directly
from the existing Whisper comparison screen.

- Uses official Google's `SpeechRecognition.getClient` and
  `SpeechRecognizerOptions.Mode.MODE_BASIC`, locale `ru-RU` (beta), with
  `AudioSource.fromMic()`. No saved PCM is passed to this test.
- `checkStatus()` must report AVAILABLE. If DOWNLOADABLE, JetMeal asks for
  explicit consent and downloads using ML Kit's own `download()` Flow, with
  progress, model-check timing, and error diagnostics. DOWNLOADING and
  UNAVAILABLE are reported without pretending the microphone worked.
- Collects `PartialTextResponse` and `FinalTextResponse` in real time;
  partial hypotheses replace one another and finalized segments accumulate
  without overwriting earlier text. Users can call `stopRecognition()`;
  the final result and elapsed times remain displayed. A bounded watchdog
  prevents indefinite waits on close.
- Shows time to first partial, time to first final, model-check overhead,
  download time, number of updates and total recognition duration.
- **Important:** this is not Gboard and does not guarantee the same accuracy.
  Basic is the generally available on-device model, not the newer Advanced
  model restricted to Pixel 10/11. Google classifies this API as alpha, so
  availability on vivo X300 depends on the installed Google services/model.
- The experiment is an independent **live microphone** take; it must not be
  displayed as a four-way identical-recording contest with Whisper or the
  legacy Android PCM injection experiment.
- No food parsing, database mutations, microphone background services or
  permanent audio storage are added.

Official guide: https://developers.google.com/ml-kit/genai/speech-recognition/android
Official sample: https://github.com/googlesamples/mlkit/tree/master/android/speech

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
