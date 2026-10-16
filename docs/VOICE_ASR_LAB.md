# JetMeal ASR comparison lab — implementation status (2026-10-16)

## Benchmark contract
Record one 16 kHz, 16-bit mono PCM clip into app-private temporary storage.
Run Whisper Tiny multilingual, Qwen3-ASR-0.6B and Android SpeechRecognizer
sequentially on the SAME PCM bytes. Report exact transcript, engine
initialization, processing time, total wall time and errors. Model downloads
must be one-time, automatic and verified. No audio upload by JetMeal.

## Current prototype (not a completed 3-model benchmark)
A dedicated experimental Settings screen captures PCM, shows its duration and
bytes, and requests recognition via Android API EXTRA_AUDIO_SOURCE with ru-RU.
It shows the service startup and recognition timing. Whisper and Qwen are
clearly labelled UNAVAILABLE because their native inference backends are
NOT integrated. The UI never duplicates Google text as a model result.

Android explicitly allows a recognition service to ignore injected PCM and
reopen the microphone. We cannot validate a fair identical-audio comparison
on a vivo X300 without an actual device test. Google ownership and on-device
offline behavior cannot be assumed for an OEM speech service.

## Remaining engineering
- Whisper: link real whisper.cpp JNI implementation, load multilingual
  ggml-tiny model, consume this recorded PCM sample, track cold/warm times.
- Qwen: link official LiteRT-LM omni/speech2text native ASR runtime. The
  public Kotlin LLM conversation API is not a drop-in Qwen ASR interface.
- Public LiteRT-LM issue 3684 shows Qwen3-ASR GPU producing empty text
  on Galaxy S26; verify on CPU first before promising GPU.
- First-run automatic downloads into app private files, with integrity
  checks, resume, disk-space protection and user-acknowledged data usage.
- On physical vivo X300 check the Google/OEM service respects injected PCM,
  that all three transcripts correspond to the same recording, cold and warm
  startup time, RAM use and app background impact.

Whisper Tiny multilingual is about 75 MB; Qwen ASR int8 is considerably
larger. The models cannot run without their weights being downloaded to
the device in some form. No manual model-file downloads should be required.

## References
- https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE
- https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android
- https://github.com/google-ai-edge/LiteRT-LM/tree/main/omni/speech2text
- https://github.com/google-ai-edge/LiteRT-LM/issues/3684

This lab is a DRAFT and should not be merged until all three runtimes are
functional, the sample-input verification is sound and Android CI passes.
