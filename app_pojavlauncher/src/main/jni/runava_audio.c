// AAudio passthrough for the RunavaAudio javax.sound.sampled provider.
//
// One AAudio stream per SourceDataLine. AAudio mixes them in shared mode
// at the kernel layer so RuneLite's overlapping SFX + music tracks Just
// Work without any Java-side mixing.
//
// Why dlopen instead of -laaudio: minSdk is 21 but AAudio shipped in API 26.
// Direct linking would prevent librunava_audio.so from loading on Android
// 5–7. dlopen lets the .so load everywhere; on pre-26 devices nativeOpen
// just returns 0 and Java throws LineUnavailableException for that line.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define TAG "runava-audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// AAudio result codes (subset, stable across API levels).
typedef int32_t aaudio_result_t;
#define AAUDIO_OK 0
// Format and direction constants from <aaudio/AAudio.h>.
#define AAUDIO_FORMAT_PCM_I16     1
#define AAUDIO_DIRECTION_OUTPUT   0
#define AAUDIO_SHARING_MODE_SHARED   0
#define AAUDIO_PERFORMANCE_MODE_NONE 10
#define AAUDIO_PERFORMANCE_MODE_LOW_LATENCY 12
#define CLOCK_MONOTONIC_ID 1  // CLOCK_MONOTONIC

// Opaque AAudio types.
typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;

// Function pointer table populated by dlsym on first use.
static struct {
    int loaded;
    int load_error;
    void *lib;
    aaudio_result_t (*createStreamBuilder)(AAudioStreamBuilder **);
    void (*sb_setDirection)(AAudioStreamBuilder *, int32_t);
    void (*sb_setSharingMode)(AAudioStreamBuilder *, int32_t);
    void (*sb_setPerformanceMode)(AAudioStreamBuilder *, int32_t);
    void (*sb_setFormat)(AAudioStreamBuilder *, int32_t);
    void (*sb_setChannelCount)(AAudioStreamBuilder *, int32_t);
    void (*sb_setSampleRate)(AAudioStreamBuilder *, int32_t);
    void (*sb_setBufferCapacityInFrames)(AAudioStreamBuilder *, int32_t);
    aaudio_result_t (*sb_openStream)(AAudioStreamBuilder *, AAudioStream **);
    aaudio_result_t (*sb_delete)(AAudioStreamBuilder *);
    aaudio_result_t (*s_write)(AAudioStream *, const void *, int32_t, int64_t);
    aaudio_result_t (*s_requestStart)(AAudioStream *);
    aaudio_result_t (*s_requestStop)(AAudioStream *);
    aaudio_result_t (*s_requestFlush)(AAudioStream *);
    aaudio_result_t (*s_close)(AAudioStream *);
    int32_t (*s_getFramesPerBurst)(AAudioStream *);
    int32_t (*s_getBufferCapacityInFrames)(AAudioStream *);
    int64_t (*s_getFramesWritten)(AAudioStream *);
    aaudio_result_t (*s_getTimestamp)(AAudioStream *, int32_t, int64_t *, int64_t *);
    const char *(*convertResultToText)(aaudio_result_t);
} aa;

#define LOAD(field, name) \
    aa.field = dlsym(aa.lib, name); \
    if (!aa.field) { LOGE("dlsym " name " failed: %s", dlerror()); aa.load_error = 1; }

static int aaudio_load(void) {
    if (aa.loaded) return 1;
    if (aa.load_error) return 0;
    aa.lib = dlopen("libaaudio.so", RTLD_NOW);
    if (!aa.lib) {
        LOGW("dlopen libaaudio.so failed: %s (Android < 8.0?)", dlerror());
        aa.load_error = 1;
        return 0;
    }
    LOAD(createStreamBuilder,         "AAudio_createStreamBuilder");
    LOAD(sb_setDirection,             "AAudioStreamBuilder_setDirection");
    LOAD(sb_setSharingMode,           "AAudioStreamBuilder_setSharingMode");
    LOAD(sb_setPerformanceMode,       "AAudioStreamBuilder_setPerformanceMode");
    LOAD(sb_setFormat,                "AAudioStreamBuilder_setFormat");
    LOAD(sb_setChannelCount,          "AAudioStreamBuilder_setChannelCount");
    LOAD(sb_setSampleRate,            "AAudioStreamBuilder_setSampleRate");
    LOAD(sb_setBufferCapacityInFrames,"AAudioStreamBuilder_setBufferCapacityInFrames");
    LOAD(sb_openStream,               "AAudioStreamBuilder_openStream");
    LOAD(sb_delete,                   "AAudioStreamBuilder_delete");
    LOAD(s_write,                     "AAudioStream_write");
    LOAD(s_requestStart,              "AAudioStream_requestStart");
    LOAD(s_requestStop,               "AAudioStream_requestStop");
    LOAD(s_requestFlush,              "AAudioStream_requestFlush");
    LOAD(s_close,                     "AAudioStream_close");
    LOAD(s_getFramesPerBurst,         "AAudioStream_getFramesPerBurst");
    LOAD(s_getBufferCapacityInFrames, "AAudioStream_getBufferCapacityInFrames");
    LOAD(s_getFramesWritten,          "AAudioStream_getFramesWritten");
    LOAD(s_getTimestamp,              "AAudioStream_getTimestamp");
    LOAD(convertResultToText,         "AAudio_convertResultToText");
    if (aa.load_error) {
        LOGE("AAudio symbol resolution incomplete; audio disabled");
        return 0;
    }
    aa.loaded = 1;
    LOGI("AAudio resolved via dlopen");
    return 1;
}

typedef struct {
    AAudioStream *stream;
    int channels;
    int sampleRate;
} runava_line;

static jlong as_handle(runava_line *l) { return (jlong)(intptr_t) l; }
static runava_line *from_handle(jlong h) { return (runava_line *)(intptr_t) h; }

// One-slot cache of a previously-opened stream. Opening AAudio costs
// 50-200ms and RuneLite closes+reopens a line every time a track changes,
// freezing the game thread for that duration. Stash the stream on close,
// hand it back on next open if sample-rate and channels match.
#include <pthread.h>
static pthread_mutex_t gCacheLock = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream *gCachedStream = NULL;
static int gCachedSampleRate = 0;
static int gCachedChannels = 0;

static AAudioStream *cache_take(int sampleRate, int channels) {
    pthread_mutex_lock(&gCacheLock);
    AAudioStream *s = NULL;
    if (gCachedStream != NULL
            && gCachedSampleRate == sampleRate
            && gCachedChannels == channels) {
        s = gCachedStream;
        gCachedStream = NULL;
    }
    pthread_mutex_unlock(&gCacheLock);
    return s;
}

static int cache_put(AAudioStream *stream, int sampleRate, int channels) {
    pthread_mutex_lock(&gCacheLock);
    int kept = 0;
    if (gCachedStream == NULL) {
        gCachedStream = stream;
        gCachedSampleRate = sampleRate;
        gCachedChannels = channels;
        kept = 1;
    }
    pthread_mutex_unlock(&gCacheLock);
    return kept;
}

JNIEXPORT jlong JNICALL
Java_com_sturq_runelite_audio_RunavaSourceDataLine_nativeOpen(
        JNIEnv *env, jclass clazz,
        jint sampleRate, jint channels, jint bufferFrames) {
    if (!aaudio_load()) return 0;

    // Reuse a cached stream of matching format if one's available — saves the
    // 50-200ms AAudio open cost on every music/SFX transition.
    AAudioStream *stream = cache_take(sampleRate, channels);
    if (stream != NULL) {
        LOGI("reused cached stream %dHz x%d", sampleRate, channels);
    } else {
        AAudioStreamBuilder *b = NULL;
        aaudio_result_t r = aa.createStreamBuilder(&b);
        if (r != AAUDIO_OK || b == NULL) {
            LOGE("createStreamBuilder failed: %d", r);
            return 0;
        }
        aa.sb_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
        aa.sb_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
        // LOW_LATENCY tripped a libaaudio UBSan add-overflow on Pixel 8 Pro
        // (Android 16) the moment the first write hit. Revert to NONE; audio
        // works, latency is somewhat higher. Caching across reopens already
        // takes the worst of the lag away.
        aa.sb_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
        aa.sb_setFormat(b, AAUDIO_FORMAT_PCM_I16);
        aa.sb_setChannelCount(b, channels);
        aa.sb_setSampleRate(b, sampleRate);
        if (bufferFrames > 0) {
            aa.sb_setBufferCapacityInFrames(b, bufferFrames * 2);
        }
        r = aa.sb_openStream(b, &stream);
        aa.sb_delete(b);
        if (r != AAUDIO_OK || stream == NULL) {
            LOGE("openStream failed: %d (%s)", r,
                 aa.convertResultToText ? aa.convertResultToText(r) : "?");
            return 0;
        }
        LOGI("opened stream %dHz x%d, framesPerBurst=%d, bufferCapFrames=%d",
             sampleRate, channels,
             aa.s_getFramesPerBurst(stream),
             aa.s_getBufferCapacityInFrames(stream));
    }

    runava_line *line = (runava_line *) calloc(1, sizeof(runava_line));
    if (line == NULL) {
        aa.s_close(stream);
        return 0;
    }
    line->stream = stream;
    line->channels = channels;
    line->sampleRate = sampleRate;
    return as_handle(line);
}

JNIEXPORT jint JNICALL
Java_com_sturq_runelite_audio_RunavaSourceDataLine_nativeWrite(
        JNIEnv *env, jclass clazz, jlong handle,
        jbyteArray buf, jint offset, jint len) {
    runava_line *line = from_handle(handle);
    if (!aa.loaded || line == NULL || line->stream == NULL || len <= 0) return 0;

    int frameBytes = line->channels * 2;
    if (len % frameBytes != 0) {
        len -= len % frameBytes;
        if (len <= 0) return 0;
    }

    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    if (data == NULL) return 0;
    int32_t frames = len / frameBytes;
    // 10s timeout, NOT INT64_MAX. AAudio adds the timeout to the monotonic
    // clock to get an absolute deadline; INT64_MAX + anything overflows and
    // UBSan traps on Android 16 (crashes the JVM). 10s is plenty for a single
    // write — the buffer's whole capacity drains in tens of ms.
    aaudio_result_t r = aa.s_write(line->stream, data + offset, frames,
                                   10LL * 1000LL * 1000LL * 1000LL);
    (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
    if (r < 0) {
        LOGW("write returned %d (%s)", r,
             aa.convertResultToText ? aa.convertResultToText(r) : "?");
        return 0;
    }
    return r * frameBytes;
}

JNIEXPORT void JNICALL
Java_com_sturq_runelite_audio_RunavaSourceDataLine_nativeStart(
        JNIEnv *env, jclass clazz, jlong handle) {
    runava_line *line = from_handle(handle);
    if (!aa.loaded || line == NULL || line->stream == NULL) return;
    aaudio_result_t r = aa.s_requestStart(line->stream);
    if (r != AAUDIO_OK) LOGW("requestStart: %d", r);
}

JNIEXPORT void JNICALL
Java_com_sturq_runelite_audio_RunavaSourceDataLine_nativeStop(
        JNIEnv *env, jclass clazz, jlong handle) {
    runava_line *line = from_handle(handle);
    if (!aa.loaded || line == NULL || line->stream == NULL) return;
    aa.s_requestStop(line->stream);
}

JNIEXPORT void JNICALL
Java_com_sturq_runelite_audio_RunavaSourceDataLine_nativeFlush(
        JNIEnv *env, jclass clazz, jlong handle) {
    runava_line *line = from_handle(handle);
    if (!aa.loaded || line == NULL || line->stream == NULL) return;
    aa.s_requestFlush(line->stream);
}

JNIEXPORT void JNICALL
Java_com_sturq_runelite_audio_RunavaSourceDataLine_nativeClose(
        JNIEnv *env, jclass clazz, jlong handle) {
    runava_line *line = from_handle(handle);
    if (line == NULL) return;
    if (aa.loaded && line->stream != NULL) {
        // Quiet the stream before caching so leftover queued frames don't
        // bleed into the next line that picks it up.
        aa.s_requestStop(line->stream);
        aa.s_requestFlush(line->stream);
        if (!cache_put(line->stream, line->sampleRate, line->channels)) {
            // Cache slot taken — just close this one.
            aa.s_close(line->stream);
        }
    }
    line->stream = NULL;
    free(line);
}

JNIEXPORT jlong JNICALL
Java_com_sturq_runelite_audio_RunavaSourceDataLine_nativeGetFramePosition(
        JNIEnv *env, jclass clazz, jlong handle) {
    runava_line *line = from_handle(handle);
    if (!aa.loaded || line == NULL || line->stream == NULL) return 0;
    int64_t framePos = 0, timeNs = 0;
    aaudio_result_t r = aa.s_getTimestamp(line->stream, CLOCK_MONOTONIC_ID, &framePos, &timeNs);
    if (r != AAUDIO_OK) return (jlong) aa.s_getFramesWritten(line->stream);
    return (jlong) framePos;
}
