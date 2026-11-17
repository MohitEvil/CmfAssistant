// Bridge to the Cactus engine that runs Needle 3 (on-device tool calling).
// Only built when the engine library was downloaded (see scripts/fetch-needle.sh).
#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

extern "C" {
int cactus_load(const char *data, uint64_t size);
int cactus_init(const char *system, const char *tools, const char *tool_index);
int cactus_complete(const char *text, const float *samples, int count, int max_new_tokens, char *buffer, int buffer_size);
void cactus_reset();
void cactus_set_threads(int threads);
}

static std::vector<char> g_buf(65536);

extern "C" JNIEXPORT jboolean JNICALL
Java_com_demon_cmfassistant_agent_NeedleEngine_nativeLoad(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    FILE *f = fopen(p, "rb");
    env->ReleaseStringUTFChars(path, p);
    if (!f) return JNI_FALSE;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<char> data((size_t) n);
    size_t got = fread(data.data(), 1, (size_t) n, f);
    fclose(f);
    if (got != (size_t) n) return JNI_FALSE;
    cactus_set_threads(0);
    return cactus_load(data.data(), (uint64_t) n) >= 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_demon_cmfassistant_agent_NeedleEngine_nativeInit(JNIEnv *env, jobject, jstring system, jstring tools) {
    const char *s = env->GetStringUTFChars(system, nullptr);
    const char *t = env->GetStringUTFChars(tools, nullptr);
    int rc = cactus_init(s, t, nullptr);
    env->ReleaseStringUTFChars(system, s);
    env->ReleaseStringUTFChars(tools, t);
    return rc >= 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_demon_cmfassistant_agent_NeedleEngine_nativeComplete(JNIEnv *env, jobject, jstring text, jint maxNew) {
    const char *t = env->GetStringUTFChars(text, nullptr);
    g_buf[0] = 0;
    int rc = cactus_complete(t, nullptr, 0, maxNew, g_buf.data(), (int) g_buf.size());
    env->ReleaseStringUTFChars(text, t);
    if (rc < 0) return nullptr;
    return env->NewStringUTF(g_buf.data());
}

extern "C" JNIEXPORT void JNICALL
Java_com_demon_cmfassistant_agent_NeedleEngine_nativeReset(JNIEnv *, jobject) {
    cactus_reset();
}
