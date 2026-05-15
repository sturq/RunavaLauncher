// OpenSL ES PCM playback for RuneLiteDroid.
//
// RuneLite's javax.sound.sampled.AudioSystem.getLine() fails on the bundled
// OpenJDK because libjsound isn't present. We register our own MixerProvider
// on the Java side; this native lib gives that provider a working backend.
//
// One AAudio call per Java SourceDataLine.open(). Each open() creates an
// independent OpenSL ES audio player wrapped in an opaque jlong handle that
// the Java side passes back on writes/closes. No global mixer state — the
// engine + output-mix objects are lazy-initialized on first open().
//
// Audio formats supported: PCM_SIGNED, 8 or 16 bit, mono or stereo, sample
// rate >= 8000 (we map down to OpenSL ES's discrete sample-rate enum).

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/log.h>

#define TAG "rldroid_audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// One global OpenSL ES engine + output mix shared across all players.
static SLObjectItf g_engine_obj = NULL;
static SLEngineItf g_engine = NULL;
static SLObjectItf g_output_mix_obj = NULL;
static pthread_mutex_t g_engine_lock = PTHREAD_MUTEX_INITIALIZER;

// Per-stream state. Handle returned to Java is a pointer to this struct.
typedef struct {
    SLObjectItf player_obj;
    SLPlayItf player;
    SLAndroidSimpleBufferQueueItf queue;
    int bytes_per_frame;

    // Round-robin double buffer so we can have a write enqueued while the
    // previous one drains. Java's SourceDataLine.write() returns immediately;
    // OpenSL ES enqueue is also non-blocking, so a 2-slot ring is enough.
    uint8_t *buffers[2];
    int buffer_caps[2];
    int next_buffer;

    pthread_mutex_t lock;
    pthread_cond_t  drain_cond;
    int             in_flight;   // bytes currently queued and not yet played
    int             closed;
} StreamCtx;

static void buffer_done(SLAndroidSimpleBufferQueueItf q, void *user) {
    StreamCtx *s = (StreamCtx *) user;
    pthread_mutex_lock(&s->lock);
    // OpenSL ES guarantees buffers complete in FIFO order. Subtract the just-
    // completed buffer's byte count and notify any thread waiting in drain().
    s->in_flight -= s->buffer_caps[(s->next_buffer + 0) % 2];
    if (s->in_flight < 0) s->in_flight = 0;
    pthread_cond_broadcast(&s->drain_cond);
    pthread_mutex_unlock(&s->lock);
    (void) q;
}

static int ensure_engine(void) {
    pthread_mutex_lock(&g_engine_lock);
    if (g_engine != NULL) {
        pthread_mutex_unlock(&g_engine_lock);
        return 0;
    }
    SLresult r = slCreateEngine(&g_engine_obj, 0, NULL, 0, NULL, NULL);
    if (r != SL_RESULT_SUCCESS) { LOGE("slCreateEngine: %d", r); goto fail; }
    r = (*g_engine_obj)->Realize(g_engine_obj, SL_BOOLEAN_FALSE);
    if (r != SL_RESULT_SUCCESS) { LOGE("engine Realize: %d", r); goto fail; }
    r = (*g_engine_obj)->GetInterface(g_engine_obj, SL_IID_ENGINE, &g_engine);
    if (r != SL_RESULT_SUCCESS) { LOGE("engine GetInterface: %d", r); goto fail; }
    r = (*g_engine)->CreateOutputMix(g_engine, &g_output_mix_obj, 0, NULL, NULL);
    if (r != SL_RESULT_SUCCESS) { LOGE("CreateOutputMix: %d", r); goto fail; }
    r = (*g_output_mix_obj)->Realize(g_output_mix_obj, SL_BOOLEAN_FALSE);
    if (r != SL_RESULT_SUCCESS) { LOGE("mix Realize: %d", r); goto fail; }
    LOGI("OpenSL ES engine ready");
    pthread_mutex_unlock(&g_engine_lock);
    return 0;

fail:
    if (g_engine_obj) { (*g_engine_obj)->Destroy(g_engine_obj); g_engine_obj = NULL; }
    g_engine = NULL;
    g_output_mix_obj = NULL;
    pthread_mutex_unlock(&g_engine_lock);
    return -1;
}

// Map an arbitrary sample rate (Hz) to OpenSL ES's discrete millihertz enum.
// OpenSL ES is fine with non-enum values too, just clamp to a sensible range.
static SLuint32 sample_rate_to_sl(int hz) {
    if (hz < 8000)  hz = 8000;
    if (hz > 48000) hz = 48000;
    return (SLuint32) hz * 1000;  // OpenSL ES uses millihertz
}

JNIEXPORT jlong JNICALL
Java_com_sturq_runelitedroid_audio_AndroidSourceDataLine_nativeOpen(
        JNIEnv *env, jclass cls,
        jint sample_rate, jint channels, jint bits_per_sample) {
    (void) env; (void) cls;

    if (ensure_engine() != 0) return 0;
    if (channels < 1 || channels > 2) {
        LOGW("unsupported channels=%d", channels); return 0;
    }
    if (bits_per_sample != 8 && bits_per_sample != 16) {
        LOGW("unsupported bits=%d", bits_per_sample); return 0;
    }

    StreamCtx *s = calloc(1, sizeof(StreamCtx));
    if (!s) return 0;
    s->bytes_per_frame = (bits_per_sample / 8) * channels;
    pthread_mutex_init(&s->lock, NULL);
    pthread_cond_init(&s->drain_cond, NULL);

    SLDataLocator_AndroidSimpleBufferQueue loc_bufq = {
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2
    };
    SLDataFormat_PCM format_pcm = {
        SL_DATAFORMAT_PCM,
        (SLuint32) channels,
        sample_rate_to_sl(sample_rate),
        (SLuint32) bits_per_sample,
        (SLuint32) bits_per_sample,
        channels == 1 ? SL_SPEAKER_FRONT_CENTER
                      : (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT),
        SL_BYTEORDER_LITTLEENDIAN
    };
    SLDataSource source = {&loc_bufq, &format_pcm};
    SLDataLocator_OutputMix loc_mix = {SL_DATALOCATOR_OUTPUTMIX, g_output_mix_obj};
    SLDataSink sink = {&loc_mix, NULL};

    const SLInterfaceID ids[1] = {SL_IID_BUFFERQUEUE};
    const SLboolean    req[1] = {SL_BOOLEAN_TRUE};
    SLresult r = (*g_engine)->CreateAudioPlayer(g_engine, &s->player_obj,
                                                &source, &sink, 1, ids, req);
    if (r != SL_RESULT_SUCCESS) { LOGE("CreateAudioPlayer: %d", r); goto fail; }
    r = (*s->player_obj)->Realize(s->player_obj, SL_BOOLEAN_FALSE);
    if (r != SL_RESULT_SUCCESS) { LOGE("player Realize: %d", r); goto fail; }
    r = (*s->player_obj)->GetInterface(s->player_obj, SL_IID_PLAY, &s->player);
    if (r != SL_RESULT_SUCCESS) { LOGE("GetInterface PLAY: %d", r); goto fail; }
    r = (*s->player_obj)->GetInterface(s->player_obj, SL_IID_BUFFERQUEUE, &s->queue);
    if (r != SL_RESULT_SUCCESS) { LOGE("GetInterface BUFFERQUEUE: %d", r); goto fail; }
    (*s->queue)->RegisterCallback(s->queue, buffer_done, s);
    (*s->player)->SetPlayState(s->player, SL_PLAYSTATE_PLAYING);

    LOGI("opened stream rate=%d ch=%d bits=%d handle=%p",
         sample_rate, channels, bits_per_sample, s);
    return (jlong)(intptr_t) s;

fail:
    if (s->player_obj) (*s->player_obj)->Destroy(s->player_obj);
    pthread_mutex_destroy(&s->lock);
    pthread_cond_destroy(&s->drain_cond);
    free(s);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_sturq_runelitedroid_audio_AndroidSourceDataLine_nativeWrite(
        JNIEnv *env, jclass cls, jlong handle, jbyteArray data,
        jint offset, jint length) {
    (void) cls;
    if (handle == 0 || length <= 0) return 0;
    StreamCtx *s = (StreamCtx *)(intptr_t) handle;
    if (s->closed) return -1;

    int slot = s->next_buffer;
    if (length > s->buffer_caps[slot]) {
        uint8_t *nb = realloc(s->buffers[slot], (size_t) length);
        if (!nb) return -1;
        s->buffers[slot] = nb;
        s->buffer_caps[slot] = length;
    }
    (*env)->GetByteArrayRegion(env, data, offset, length, (jbyte *) s->buffers[slot]);

    pthread_mutex_lock(&s->lock);
    s->in_flight += length;
    pthread_mutex_unlock(&s->lock);

    SLresult r = (*s->queue)->Enqueue(s->queue, s->buffers[slot], (SLuint32) length);
    if (r != SL_RESULT_SUCCESS) {
        // Buffer queue is full — wait for one to drain, then retry once. Java
        // SourceDataLine.write() is allowed to block, so callers expect this.
        pthread_mutex_lock(&s->lock);
        while (!s->closed && s->in_flight >= length * 2) {
            pthread_cond_wait(&s->drain_cond, &s->lock);
        }
        pthread_mutex_unlock(&s->lock);
        if (s->closed) return -1;
        r = (*s->queue)->Enqueue(s->queue, s->buffers[slot], (SLuint32) length);
        if (r != SL_RESULT_SUCCESS) {
            pthread_mutex_lock(&s->lock);
            s->in_flight -= length;
            pthread_mutex_unlock(&s->lock);
            return -1;
        }
    }
    s->next_buffer = (slot + 1) % 2;
    return length;
}

JNIEXPORT void JNICALL
Java_com_sturq_runelitedroid_audio_AndroidSourceDataLine_nativeDrain(
        JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle == 0) return;
    StreamCtx *s = (StreamCtx *)(intptr_t) handle;
    pthread_mutex_lock(&s->lock);
    while (!s->closed && s->in_flight > 0) {
        pthread_cond_wait(&s->drain_cond, &s->lock);
    }
    pthread_mutex_unlock(&s->lock);
}

JNIEXPORT void JNICALL
Java_com_sturq_runelitedroid_audio_AndroidSourceDataLine_nativeClose(
        JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle == 0) return;
    StreamCtx *s = (StreamCtx *)(intptr_t) handle;
    pthread_mutex_lock(&s->lock);
    s->closed = 1;
    pthread_cond_broadcast(&s->drain_cond);
    pthread_mutex_unlock(&s->lock);

    if (s->player) (*s->player)->SetPlayState(s->player, SL_PLAYSTATE_STOPPED);
    if (s->queue)  (*s->queue)->Clear(s->queue);
    if (s->player_obj) (*s->player_obj)->Destroy(s->player_obj);

    free(s->buffers[0]);
    free(s->buffers[1]);
    pthread_mutex_destroy(&s->lock);
    pthread_cond_destroy(&s->drain_cond);
    free(s);
    LOGI("closed stream handle=%p", s);
}
