#include <jni.h>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>
#include "sentencepiece_processor.h"

namespace {
std::string readBytes(JNIEnv *env, jbyteArray bytes) {
    const auto count = env->GetArrayLength(bytes);
    std::string result(count, '\0');
    if (count) env->GetByteArrayRegion(bytes, 0, count, reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read tokenizer input");
    return result;
}
void checked(const sentencepiece::util::Status &status) {
    if (!status.ok()) throw std::runtime_error(status.ToString());
}
void report(JNIEnv *env, const std::exception &error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
jsize checkedLength(std::size_t count) {
    if (count > static_cast<std::size_t>(std::numeric_limits<jsize>::max()))
        throw std::length_error("Tokenizer output exceeds JNI array capacity");
    return static_cast<jsize>(count);
}
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_nikita_voice_NativeSentencePiece_encodeBytes(JNIEnv *env, jobject, jbyteArray path, jbyteArray text) {
    try {
        sentencepiece::SentencePieceProcessor processor;
        checked(processor.Load(readBytes(env, path)));
        std::vector<int> ids;
        checked(processor.Encode(readBytes(env, text), &ids));
        auto count = checkedLength(ids.size());
        auto result = env->NewIntArray(count);
        if (result && count) env->SetIntArrayRegion(result, 0, count, ids.data());
        return result;
    } catch (const std::exception &error) {
        report(env, error);
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_nikita_voice_NativeSentencePiece_decodeBytes(JNIEnv *env, jobject, jbyteArray path, jintArray values) {
    try {
        sentencepiece::SentencePieceProcessor processor;
        checked(processor.Load(readBytes(env, path)));
        auto count = env->GetArrayLength(values);
        std::vector<int> ids(count);
        if (count) env->GetIntArrayRegion(values, 0, count, ids.data());
        if (env->ExceptionCheck()) return nullptr;
        std::string text;
        checked(processor.Decode(ids, &text));
        auto n = checkedLength(text.size());
        auto result = env->NewByteArray(n);
        if (result && n) env->SetByteArrayRegion(result, 0, n, reinterpret_cast<const jbyte *>(text.data()));
        return result;
    } catch (const std::exception &error) {
        report(env, error);
        return nullptr;
    }
}
