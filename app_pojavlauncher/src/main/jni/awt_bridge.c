#include <jni.h>
#include <assert.h>
#include <string.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>
/* The AWT copy is a byte swap inside each pixel, which every SIMD unit does as
   a single table lookup. Both architectures we build for have one. */
#if defined(__aarch64__)
#include <arm_neon.h>
#define BLIT_VECTOR 1
#elif defined(__x86_64__)
#include <tmmintrin.h>
#define BLIT_VECTOR 1
#endif
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

/* How much of a frame the AWT copy actually costs. The software renderer's
   speed has so far been argued about rather than measured, and the two
   candidate culprits — the client's own rasteriser and this copy — call for
   completely different fixes. Logged once every few seconds; the counters are
   only touched from the single AWT render thread. */
static struct {
    unsigned long posted;
    unsigned long skipped;
    unsigned long long blitNanos;
    unsigned long long lastReportNanos;
} blitStats;

static unsigned long long monotonicNanos(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long) ts.tv_sec * 1000000000ULL + (unsigned long long) ts.tv_nsec;
}

static JavaVM* dalvikJavaVMPtr;

static JavaVM* runtimeJavaVMPtr;
static JNIEnv* runtimeJNIEnvPtr_GRAPHICS;
static JNIEnv* runtimeJNIEnvPtr_INPUT;
jclass class_CTCScreen;
jmethodID method_GetRGB;

jclass class_CTCAndroidInput;
jmethodID method_ReceiveInput;

jclass class_MainActivity;
jmethodID method_OpenLink;
jmethodID method_OpenPath;
jmethodID method_QuerySystemClipboard;
jmethodID method_PutClipboardData;

jclass class_Frame;
jclass class_Rectangle;
jclass class_CTCClipboard = NULL;
jmethodID constructor_Rectangle;
jmethodID method_GetFrames;
jmethodID method_GetBounds;
jmethodID method_SetBounds;
jmethodID method_SystemClipboardDataReceived = NULL;

jfieldID field_x;
jfieldID field_y;

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    if (dalvikJavaVMPtr == NULL) {
        //Save dalvik global JavaVM pointer
        dalvikJavaVMPtr = vm;
        JNIEnv *env = NULL;
        (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_4);
        class_MainActivity = (*env)->NewGlobalRef(env,(*env)->FindClass(env, "net/kdt/pojavlaunch/MainActivity"));
        method_OpenLink= (*env)->GetStaticMethodID(env, class_MainActivity, "openLink", "(Ljava/lang/String;)V");
        method_OpenPath= (*env)->GetStaticMethodID(env, class_MainActivity, "openLink", "(Ljava/lang/String;)V");
        method_QuerySystemClipboard = (*env)->GetStaticMethodID(env, class_MainActivity, "querySystemClipboard", "()V");
        method_PutClipboardData = (*env)->GetStaticMethodID(env, class_MainActivity, "putClipboardData", "(Ljava/lang/String;Ljava/lang/String;)V");
    } else if (dalvikJavaVMPtr != vm) {
        runtimeJavaVMPtr = vm;
    }

    return JNI_VERSION_1_4;
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_AWTInputBridge_nativeSendData(JNIEnv* env, jclass clazz, jint type, jint i1, jint i2, jint i3, jint i4) {
    if (runtimeJNIEnvPtr_INPUT == NULL) {
        if (runtimeJavaVMPtr == NULL) {
            return;
        } else {
            (*runtimeJavaVMPtr)->AttachCurrentThread(runtimeJavaVMPtr, &runtimeJNIEnvPtr_INPUT, NULL);
        }
    }

    if (method_ReceiveInput == NULL) {
        class_CTCAndroidInput = (*runtimeJNIEnvPtr_INPUT)->FindClass(runtimeJNIEnvPtr_INPUT, "net/java/openjdk/cacio/ctc/CTCAndroidInput");
        if ((*runtimeJNIEnvPtr_INPUT)->ExceptionCheck(runtimeJNIEnvPtr_INPUT) == JNI_TRUE) {
            (*runtimeJNIEnvPtr_INPUT)->ExceptionClear(runtimeJNIEnvPtr_INPUT);
            class_CTCAndroidInput = (*runtimeJNIEnvPtr_INPUT)->FindClass(runtimeJNIEnvPtr_INPUT, "com/github/caciocavallosilano/cacio/ctc/CTCAndroidInput");
        }
        assert(class_CTCAndroidInput != NULL);
        method_ReceiveInput = (*runtimeJNIEnvPtr_INPUT)->GetStaticMethodID(runtimeJNIEnvPtr_INPUT, class_CTCAndroidInput, "receiveData", "(IIIII)V");
        assert(method_ReceiveInput != NULL);
    }
    (*runtimeJNIEnvPtr_INPUT)->CallStaticVoidMethod(
        runtimeJNIEnvPtr_INPUT,
        class_CTCAndroidInput,
        method_ReceiveInput,
        type, i1, i2, i3, i4
    );
}

// TODO: check for memory leaks
// int printed = 0;
int threadAttached = 0;

/* The surface the AWT frame is written into, held across frames. */
static ANativeWindow *awtWindow = NULL;
static int awtWindowWidth = -1;
static int awtWindowHeight = -1;

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setAWTSurface(JNIEnv* env, jclass clazz, jobject surface) {
    if (awtWindow != NULL) {
        ANativeWindow_release(awtWindow);
        awtWindow = NULL;
        awtWindowWidth = awtWindowHeight = -1;
    }
    if (surface != NULL) {
        awtWindow = ANativeWindow_fromSurface(env, surface);
    }
}

/* Look up CTCScreen.getCurrentScreenRGB once. Cacio ships under two different
   package names depending on which build is installed. */
static int resolveGetRGB(void) {
    if (method_GetRGB != NULL) return 1;
    class_CTCScreen = (*runtimeJNIEnvPtr_GRAPHICS)->FindClass(runtimeJNIEnvPtr_GRAPHICS, "net/java/openjdk/cacio/ctc/CTCScreen");
    if ((*runtimeJNIEnvPtr_GRAPHICS)->ExceptionCheck(runtimeJNIEnvPtr_GRAPHICS) == JNI_TRUE) {
        (*runtimeJNIEnvPtr_GRAPHICS)->ExceptionClear(runtimeJNIEnvPtr_GRAPHICS);
        class_CTCScreen = (*runtimeJNIEnvPtr_GRAPHICS)->FindClass(runtimeJNIEnvPtr_GRAPHICS, "com/github/caciocavallosilano/cacio/ctc/CTCScreen");
    }
    if (class_CTCScreen == NULL) return 0;
    method_GetRGB = (*runtimeJNIEnvPtr_GRAPHICS)->GetStaticMethodID(runtimeJNIEnvPtr_GRAPHICS, class_CTCScreen, "getCurrentScreenRGB", "()[I");
    return method_GetRGB != NULL;
}

/*
 * Write the current AWT frame straight into the surface buffer.
 *
 * This used to hand a fresh jintArray back to Java, which then went through
 * Bitmap.setPixels and Canvas.drawBitmap: four copies of the frame per frame,
 * plus a 1.7 MB allocation per frame at 720x600. Measured on a Pixel 8, the
 * thread doing that cost as much CPU as the entire game did rendering.
 * ANativeWindow_lock lets the pixels be converted once, on the way in.
 *
 * Cacio hands out 0xAARRGGBB ints; the surface wants R in the low byte, so red
 * and blue are swapped as they are written. `opaque` forces alpha for the
 * callers that used to clear the buffer to black first.
 *
 * Returns true when a frame was posted. False means AWT had nothing new, and
 * the surface keeps showing the last frame.
 */
JNIEXPORT jboolean JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_blitAWTScreenFrame(
        JNIEnv* env, jclass clazz, jint canvasWidth, jint visibleWidth, jint visibleHeight, jboolean opaque) {
    if (awtWindow == NULL) return JNI_FALSE;
    if (runtimeJNIEnvPtr_GRAPHICS == NULL) {
        if (runtimeJavaVMPtr == NULL) return JNI_FALSE;
        (*runtimeJavaVMPtr)->AttachCurrentThread(runtimeJavaVMPtr, &runtimeJNIEnvPtr_GRAPHICS, NULL);
    }
    if (!resolveGetRGB()) return JNI_FALSE;

    unsigned long long t0 = monotonicNanos();

    jintArray jreRgbArray = (jintArray) (*runtimeJNIEnvPtr_GRAPHICS)->CallStaticObjectMethod(
        runtimeJNIEnvPtr_GRAPHICS, class_CTCScreen, method_GetRGB);
    if (jreRgbArray == NULL) {
        /* Cacio returns null when nothing repainted. Counting these separates
           "the client is slow" from "our copy is slow", which is the whole
           question about whether the software path can be made fast enough. */
        blitStats.skipped++;
        return JNI_FALSE;
    }

    if (visibleWidth != awtWindowWidth || visibleHeight != awtWindowHeight) {
        ANativeWindow_setBuffersGeometry(awtWindow, visibleWidth, visibleHeight, WINDOW_FORMAT_RGBA_8888);
        awtWindowWidth = visibleWidth;
        awtWindowHeight = visibleHeight;
    }

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(awtWindow, &buf, NULL) != 0) {
        (*runtimeJNIEnvPtr_GRAPHICS)->DeleteLocalRef(runtimeJNIEnvPtr_GRAPHICS, jreRgbArray);
        return JNI_FALSE;
    }

    jboolean posted = JNI_FALSE;
    jint *src = (*runtimeJNIEnvPtr_GRAPHICS)->GetPrimitiveArrayCritical(runtimeJNIEnvPtr_GRAPHICS, jreRgbArray, NULL);
    if (src != NULL) {
        int w = buf.width  < visibleWidth  ? buf.width  : visibleWidth;
        int h = buf.height < visibleHeight ? buf.height : visibleHeight;
        uint32_t forceAlpha = opaque ? 0xFF000000u : 0u;
#ifdef BLIT_VECTOR
        /* Cacio's 0xAARRGGBB lands in memory as B,G,R,A and the surface wants
           R,G,B,A, so the whole conversion is a byte swap inside each pixel —
           one table lookup for four pixels at a time. Measured at 24% of a
           60 Hz frame as a scalar loop, which is the largest single cost in
           the software renderer that is ours to remove. */
        static const uint8_t swapRBIndices[16] = {
                2, 1, 0, 3, 6, 5, 4, 7, 10, 9, 8, 11, 14, 13, 12, 15};
#if defined(__aarch64__)
        const uint8x16_t swapRB = vld1q_u8(swapRBIndices);
        const uint8x16_t alphaBits = vreinterpretq_u8_u32(vdupq_n_u32(forceAlpha));
#define BLIT_SWIZZLE4(srcPtr, dstPtr) \
        vst1q_u8((uint8_t *) (dstPtr), \
                 vorrq_u8(vqtbl1q_u8(vld1q_u8((const uint8_t *) (srcPtr)), swapRB), alphaBits))
#else
        const __m128i swapRB = _mm_loadu_si128((const __m128i *) swapRBIndices);
        const __m128i alphaBits = _mm_set1_epi32((int) forceAlpha);
#define BLIT_SWIZZLE4(srcPtr, dstPtr) \
        _mm_storeu_si128((__m128i *) (dstPtr), \
                 _mm_or_si128(_mm_shuffle_epi8( \
                         _mm_loadu_si128((const __m128i *) (srcPtr)), swapRB), alphaBits))
#endif

        /* A wrong index in that table tints the whole UI, and each vector path
           only ever runs on its own architecture — so a mistake in one of them
           cannot be caught by testing the other. Check against the scalar
           conversion once per process instead. */
        static int swizzleVerified = 0;
        if (!swizzleVerified) {
            swizzleVerified = 1;
            const uint32_t probe[4] = {0x11223344u, 0x55667788u, 0x99aabbccu, 0xddeeff00u};
            uint32_t got[4];
            BLIT_SWIZZLE4(probe, got);
            for (int i = 0; i < 4; i++) {
                uint32_t p = probe[i];
                uint32_t want = (p & 0xFF00FF00u) | ((p >> 16) & 0x000000FFu)
                                | ((p & 0x000000FFu) << 16) | forceAlpha;
                if (got[i] != want) {
                    __android_log_print(ANDROID_LOG_ERROR, "awtblit",
                            "vector swizzle is wrong: %08x became %08x, expected %08x",
                            p, got[i], want);
                }
            }
        }
#endif
        for (int y = 0; y < h; y++) {
            const uint32_t *s = (const uint32_t *) src + (size_t) y * (size_t) canvasWidth;
            uint32_t *d = (uint32_t *) buf.bits + (size_t) y * (size_t) buf.stride;
            int x = 0;
#ifdef BLIT_VECTOR
            for (; x + 4 <= w; x += 4) {
                BLIT_SWIZZLE4(s + x, d + x);
            }
#endif
            for (; x < w; x++) {
                uint32_t p = s[x];
                d[x] = (p & 0xFF00FF00u) | ((p >> 16) & 0x000000FFu) | ((p & 0x000000FFu) << 16) | forceAlpha;
            }
        }
        (*runtimeJNIEnvPtr_GRAPHICS)->ReleasePrimitiveArrayCritical(runtimeJNIEnvPtr_GRAPHICS, jreRgbArray, src, JNI_ABORT);
        posted = JNI_TRUE;
    }

    ANativeWindow_unlockAndPost(awtWindow);
    /* The old code never released this. Nothing frees local refs on a thread
       attached to the runtime VM outside a native call boundary, so one
       reference to a 1.7 MB array leaked into the JVM's handle table per frame. */
    (*runtimeJNIEnvPtr_GRAPHICS)->DeleteLocalRef(runtimeJNIEnvPtr_GRAPHICS, jreRgbArray);

    unsigned long long now = monotonicNanos();
    blitStats.posted++;
    blitStats.blitNanos += now - t0;
    if (blitStats.lastReportNanos == 0) blitStats.lastReportNanos = now;
    unsigned long long window = now - blitStats.lastReportNanos;
    if (window >= 5000000000ULL) {
        unsigned long total = blitStats.posted + blitStats.skipped;
        __android_log_print(ANDROID_LOG_INFO, "awtblit",
                "%dx%d: %lu frames posted, %lu skipped in %llums — %llu us each, "
                "%lu%% of the frame budget, %llu posted/s",
                visibleWidth, visibleHeight, blitStats.posted, blitStats.skipped,
                window / 1000000ULL,
                blitStats.blitNanos / (blitStats.posted * 1000ULL),
                (unsigned long) (blitStats.blitNanos / (total ? total : 1) * 100ULL / 16666667ULL),
                blitStats.posted * 1000000000ULL / window);
        blitStats.posted = 0;
        blitStats.skipped = 0;
        blitStats.blitNanos = 0;
        blitStats.lastReportNanos = now;
    }
    return posted;
}

JNIEXPORT void JNICALL Java_net_java_openjdk_cacio_ctc_CTCClipboard_nQuerySystemClipboard(JNIEnv *env, jclass clazz) {
    JNIEnv *dalvikEnv;char detachable = 0;
    if((*dalvikJavaVMPtr)->GetEnv(dalvikJavaVMPtr, (void **) &dalvikEnv, JNI_VERSION_1_6) == JNI_EDETACHED) {
        (*dalvikJavaVMPtr)->AttachCurrentThread(dalvikJavaVMPtr, &dalvikEnv, NULL);
        detachable = 1;
    }
    if(method_SystemClipboardDataReceived == NULL) {
        class_CTCClipboard = (*env)->NewGlobalRef(env, clazz);
        method_SystemClipboardDataReceived = (*env)->GetStaticMethodID(env, clazz, "systemClipboardDataReceived", "(Ljava/lang/String;Ljava/lang/String;)V");
    }
    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, class_MainActivity, method_QuerySystemClipboard);
    if(detachable) (*dalvikJavaVMPtr)->DetachCurrentThread(dalvikJavaVMPtr);
}

JNIEXPORT void JNICALL Java_net_java_openjdk_cacio_ctc_CTCClipboard_nPutClipboardData(JNIEnv* env, jclass clazz, jstring clipboardData, jstring clipboardDataMime) {
    JNIEnv *dalvikEnv;char detachable = 0;
    if((*dalvikJavaVMPtr)->GetEnv(dalvikJavaVMPtr, (void **) &dalvikEnv, JNI_VERSION_1_6) == JNI_EDETACHED) {
        (*dalvikJavaVMPtr)->AttachCurrentThread(dalvikJavaVMPtr, &dalvikEnv, NULL);
        detachable = 1;
    }

    const char* dataChars = (*env)->GetStringUTFChars(env, clipboardData, NULL);
    const char* mimeChars = (*env)->GetStringUTFChars(env, clipboardDataMime, NULL);
    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, class_MainActivity, method_PutClipboardData,
                                       (*dalvikEnv)->NewStringUTF(dalvikEnv, dataChars),
                                       (*dalvikEnv)->NewStringUTF(dalvikEnv, mimeChars));
    (*env)->ReleaseStringUTFChars(env, clipboardData, dataChars);
    (*env)->ReleaseStringUTFChars(env, clipboardDataMime, mimeChars);
    if(detachable) (*dalvikJavaVMPtr)->DetachCurrentThread(dalvikJavaVMPtr);
}

JNIEXPORT void JNICALL Java_com_github_caciocavallosilano_cacio_ctc_CTCClipboard_nQuerySystemClipboard(JNIEnv *env, jclass clazz) {
    Java_net_java_openjdk_cacio_ctc_CTCClipboard_nQuerySystemClipboard(env, clazz);
}

JNIEXPORT void JNICALL Java_com_github_caciocavallosilano_cacio_ctc_CTCClipboard_nPutClipboardData(JNIEnv* env, jclass clazz, jstring clipboardData, jstring clipboardDataMime) {
    Java_net_java_openjdk_cacio_ctc_CTCClipboard_nPutClipboardData(env, clazz, clipboardData, clipboardDataMime);
}

JNIEXPORT void JNICALL Java_net_java_openjdk_cacio_ctc_CTCDesktopPeer_openFile(JNIEnv *env, jclass clazz, jstring filePath) {
    JNIEnv *dalvikEnv;char detachable = 0;
    if((*dalvikJavaVMPtr)->GetEnv(dalvikJavaVMPtr, (void **) &dalvikEnv, JNI_VERSION_1_6) == JNI_EDETACHED) {
        (*dalvikJavaVMPtr)->AttachCurrentThread(dalvikJavaVMPtr, &dalvikEnv, NULL);
        detachable = 1;
    }
    const char* stringChars = (*env)->GetStringUTFChars(env, filePath, NULL);
    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, class_MainActivity, method_OpenPath, (*dalvikEnv)->NewStringUTF(dalvikEnv, stringChars));
    (*env)->ReleaseStringUTFChars(env, filePath, stringChars);
    if(detachable) (*dalvikJavaVMPtr)->DetachCurrentThread(dalvikJavaVMPtr);
}

JNIEXPORT void JNICALL Java_net_java_openjdk_cacio_ctc_CTCDesktopPeer_openUri(JNIEnv *env, jclass clazz, jstring uri) {
    JNIEnv *dalvikEnv;char detachable = 0;
    if((*dalvikJavaVMPtr)->GetEnv(dalvikJavaVMPtr, (void **) &dalvikEnv, JNI_VERSION_1_6) == JNI_EDETACHED) {
        (*dalvikJavaVMPtr)->AttachCurrentThread(dalvikJavaVMPtr, &dalvikEnv, NULL);
        detachable = 1;
    }
    const char* stringChars = (*env)->GetStringUTFChars(env, uri, NULL);
    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, class_MainActivity, method_OpenLink, (*dalvikEnv)->NewStringUTF(dalvikEnv, stringChars));
    (*env)->ReleaseStringUTFChars(env, uri, stringChars);
    if(detachable) (*dalvikJavaVMPtr)->DetachCurrentThread(dalvikJavaVMPtr);
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_AWTInputBridge_nativeClipboardReceived(JNIEnv *env, jclass clazz, jstring clipboardData, jstring clipboardDataMime) {
    if(method_SystemClipboardDataReceived == NULL || class_CTCClipboard == NULL) return;
    if (runtimeJNIEnvPtr_INPUT == NULL) {
        if (runtimeJavaVMPtr == NULL) {
            return;
        } else {
            (*runtimeJavaVMPtr)->AttachCurrentThread(runtimeJavaVMPtr, &runtimeJNIEnvPtr_INPUT, NULL);
        }
    }
    const char* dataChars = clipboardData != NULL ? (*env)->GetStringUTFChars(env, clipboardData, NULL) : NULL;
    const char* mimeChars = clipboardDataMime != NULL ? (*env)->GetStringUTFChars(env, clipboardDataMime, NULL) : NULL;
    (*runtimeJNIEnvPtr_INPUT)->CallStaticVoidMethod(runtimeJNIEnvPtr_INPUT, class_CTCClipboard, method_SystemClipboardDataReceived,
                                                    clipboardData != NULL ? (*runtimeJNIEnvPtr_INPUT)->NewStringUTF(runtimeJNIEnvPtr_INPUT, dataChars) : NULL,
                                                    clipboardDataMime != NULL ? (*runtimeJNIEnvPtr_INPUT)->NewStringUTF(runtimeJNIEnvPtr_INPUT, mimeChars) : NULL);
    if(dataChars != NULL) (*env)->ReleaseStringUTFChars(env, clipboardData, dataChars);
    if(mimeChars != NULL) (*env)->ReleaseStringUTFChars(env, clipboardDataMime, mimeChars);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_AWTInputBridge_nativeMoveWindow(JNIEnv *env, jclass clazz, jint xoff, jint yoff) {
    if (runtimeJNIEnvPtr_INPUT == NULL) {
        if (runtimeJavaVMPtr == NULL) {
            return;
        } else {
            (*runtimeJavaVMPtr)->AttachCurrentThread(runtimeJavaVMPtr, &runtimeJNIEnvPtr_INPUT, NULL);
        }
    }
    if(field_y == NULL) {
        class_Frame = (*runtimeJNIEnvPtr_INPUT)->FindClass(runtimeJNIEnvPtr_INPUT, "java/awt/Frame");
        method_GetFrames = (*runtimeJNIEnvPtr_INPUT)->GetStaticMethodID(runtimeJNIEnvPtr_INPUT, class_Frame, "getFrames", "()[Ljava/awt/Frame;");
        method_GetBounds = (*runtimeJNIEnvPtr_INPUT)->GetMethodID(runtimeJNIEnvPtr_INPUT, class_Frame, "getBounds", "(Ljava/awt/Rectangle;)Ljava/awt/Rectangle;");
        method_SetBounds = (*runtimeJNIEnvPtr_INPUT)->GetMethodID(runtimeJNIEnvPtr_INPUT, class_Frame, "setBounds", "(Ljava/awt/Rectangle;)V");
        class_Rectangle = (*runtimeJNIEnvPtr_INPUT)->FindClass(runtimeJNIEnvPtr_INPUT, "java/awt/Rectangle");
        constructor_Rectangle = (*runtimeJNIEnvPtr_INPUT)->GetMethodID(runtimeJNIEnvPtr_INPUT, class_Rectangle, "<init>", "()V");
        field_x = (*runtimeJNIEnvPtr_INPUT)->GetFieldID(runtimeJNIEnvPtr_INPUT, class_Rectangle, "x", "I");
        field_y = (*runtimeJNIEnvPtr_INPUT)->GetFieldID(runtimeJNIEnvPtr_INPUT, class_Rectangle, "y", "I");
    }
    jobject rectangle = (*runtimeJNIEnvPtr_INPUT)->NewObject(runtimeJNIEnvPtr_INPUT, class_Rectangle, constructor_Rectangle);
    jobjectArray frames = (*runtimeJNIEnvPtr_INPUT)->CallStaticObjectMethod(runtimeJNIEnvPtr_INPUT, class_Frame, method_GetFrames);
    for(jsize i = 0; i < (*runtimeJNIEnvPtr_INPUT)->GetArrayLength(runtimeJNIEnvPtr_INPUT, frames); i++) {
        jobject frame = (*runtimeJNIEnvPtr_INPUT)->GetObjectArrayElement(runtimeJNIEnvPtr_INPUT, frames, i);
        (*runtimeJNIEnvPtr_INPUT)->CallObjectMethod(runtimeJNIEnvPtr_INPUT, frame, method_GetBounds, rectangle);
        (*runtimeJNIEnvPtr_INPUT)->SetIntField(runtimeJNIEnvPtr_INPUT, rectangle,  field_x, (*runtimeJNIEnvPtr_INPUT)->GetIntField(runtimeJNIEnvPtr_INPUT, rectangle, field_x) + xoff);
        (*runtimeJNIEnvPtr_INPUT)->SetIntField(runtimeJNIEnvPtr_INPUT, rectangle,  field_y, (*runtimeJNIEnvPtr_INPUT)->GetIntField(runtimeJNIEnvPtr_INPUT, rectangle, field_y) + yoff);
        (*runtimeJNIEnvPtr_INPUT)->CallVoidMethod(runtimeJNIEnvPtr_INPUT, frame, method_SetBounds, rectangle);
        (*runtimeJNIEnvPtr_INPUT)->DeleteLocalRef(runtimeJNIEnvPtr_INPUT, frame);
    }
    (*runtimeJNIEnvPtr_INPUT)->DeleteLocalRef(runtimeJNIEnvPtr_INPUT, rectangle);
    (*runtimeJNIEnvPtr_INPUT)->DeleteLocalRef(runtimeJNIEnvPtr_INPUT, frames);
}
