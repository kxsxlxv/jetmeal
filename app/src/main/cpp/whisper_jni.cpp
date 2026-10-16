#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <cstdint>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

namespace {
void fail(JNIEnv *env, const std::string &message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message.c_str());
}
struct UtfString {
    JNIEnv *env;
    jstring source;
    const char *value;
    UtfString(JNIEnv *e, jstring s) : env(e), source(s),
        value(s == nullptr ? nullptr : e->GetStringUTFChars(s, nullptr)) {}
    ~UtfString() { if (value != nullptr) env->ReleaseStringUTFChars(source, value); }
};
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kxsxlxv_jetmeal_voice_WhisperNative_openModel(
    JNIEnv *env, jobject, jstring modelPath) {
    UtfString path(env, modelPath);
    if (!path.value) return 0;
    auto params = whisper_context_default_params();
    params.use_gpu = false; // Validated CPU path first; no assumed MediaTek NPU backend.
    whisper_context *context = whisper_init_from_file_with_params(path.value, params);
    if (!context) {
        fail(env, "whisper.cpp could not initialize the multilingual Tiny model");
        return 0;
    }
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kxsxlxv_jetmeal_voice_WhisperNative_transcribe(
    JNIEnv *env, jobject, jlong contextPtr, jstring pcmPath, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(contextPtr);
    if (!ctx) { fail(env, "Whisper context is closed"); return nullptr; }

    UtfString path(env, pcmPath);
    if (!path.value) return nullptr;
    std::ifstream file(path.value, std::ios::in | std::ios::binary | std::ios::ate);
    if (!file) { fail(env, "Cannot open recorded PCM audio"); return nullptr; }
    const std::streamoff bytes = file.tellg();
    // 16 kHz, mono, signed 16-bit little endian. Up to 30 seconds.
    if (bytes <= 0 || (bytes & 1) != 0 || bytes > 16000 * 2 * 30) {
        fail(env, "Invalid PCM audio duration or format");
        return nullptr;
    }
    file.seekg(0, std::ios::beg);
    std::vector<uint8_t> raw(static_cast<size_t>(bytes));
    if (!file.read(reinterpret_cast<char *>(raw.data()), bytes)) {
        fail(env, "Failed to read recorded PCM"); return nullptr;
    }
    std::vector<float> pcm(raw.size() / 2);
    for (size_t i = 0; i < pcm.size(); ++i) {
        const auto littleEndian = static_cast<uint16_t>(raw[2 * i]) |
                                  (static_cast<uint16_t>(raw[2 * i + 1]) << 8);
        const int16_t signedSample = static_cast<int16_t>(littleEndian);
        pcm[i] = static_cast<float>(signedSample) / 32768.0f;
    }

    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "ru";
    params.translate = false;
    params.no_context = true;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.n_threads = std::clamp(static_cast<int>(threads), 1, 8);
    params.suppress_blank = true;
    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        fail(env, "whisper.cpp transcription failed"); return nullptr;
    }
    std::string text;
    const int count = whisper_full_n_segments(ctx);
    for (int index = 0; index < count; ++index) {
        const char *part = whisper_full_get_segment_text(ctx, index);
        if (part != nullptr) text.append(part);
    }
    // JNI modified UTF-8 is valid for UTF-8 without embedded NUL, which Whisper
    // transcription segments do not ordinarily contain.
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_kxsxlxv_jetmeal_voice_WhisperNative_closeModel(
    JNIEnv *, jobject, jlong contextPtr) {
    if (contextPtr != 0) whisper_free(reinterpret_cast<whisper_context *>(contextPtr));
}
