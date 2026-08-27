/*
 * rlawt for Android.
 *
 * RuneLite's GPU plugin talks to its native half, rlawt, to get an OpenGL
 * context attached to the AWT canvas. Upstream has three backends and all of
 * them start from JAWT: on Linux it takes an X11 Display and Drawable out of
 * the drawing surface and builds a GLX context on it. None of that exists here,
 * which is why the plugin was written off as impossible on Android.
 *
 * It is not. The context does not have to come from the AWT canvas at all: the
 * game already draws the RuneLite UI through Caciocavallo onto a transparent
 * overlay, so the 3D scene only needs its own surface underneath. This backend
 * ignores the Component it is handed and renders to the SurfaceView the
 * launcher sets up, which reaches it through pojav_environ->pojavWindow.
 *
 * The EGL below is Mesa's, not the system's, and it needs four things that were
 * each found the hard way while probing this device:
 *
 *   - LIBGL_DRIVERS_PATH, because Mesa's driver search path is compiled in and
 *     points at the CI runner that built these libraries.
 *   - VULKAN_PTR, because this Mesa is patched to take the Vulkan loader as a
 *     pointer rather than opening libvulkan itself.
 *   - a real window, because this is the kopper build of zink and its window
 *     system layer segfaults without one.
 *   - EGL_OPENGL_API, since the plugin needs desktop GL rather than GLES.
 *
 * Measured on a Pixel 8 that yields "4.6 (Compatibility Profile) Mesa 23.0.4,
 * zink (Mali-G715)", comfortably above the 4.3 the plugin's compute shaders
 * require.
 *
 * Loaded by RuneLite through -Drunelite.rlawtpath, which upstream provides.
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <inttypes.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <android/native_window.h>
#include <android/log.h>
#include <environ/environ.h>

#define TAG "rlawt"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef EGLDisplay (*fn_getDisplay)(EGLNativeDisplayType);
typedef EGLBoolean (*fn_initialize)(EGLDisplay, EGLint *, EGLint *);
typedef EGLBoolean (*fn_bindAPI)(EGLenum);
typedef EGLBoolean (*fn_chooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
typedef EGLContext (*fn_createContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
typedef EGLSurface (*fn_createWindow)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *);
typedef EGLBoolean (*fn_makeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef EGLBoolean (*fn_swapBuffers)(EGLDisplay, EGLSurface);
typedef EGLBoolean (*fn_swapInterval)(EGLDisplay, EGLint);
typedef EGLBoolean (*fn_destroyContext)(EGLDisplay, EGLContext);
typedef EGLBoolean (*fn_destroySurface)(EGLDisplay, EGLSurface);
typedef EGLint (*fn_getError)(void);

static struct {
    int loaded;
    fn_getDisplay     getDisplay;
    fn_initialize     initialize;
    fn_bindAPI        bindAPI;
    fn_chooseConfig   chooseConfig;
    fn_createContext  createContext;
    fn_createWindow   createWindowSurface;
    fn_makeCurrent    makeCurrent;
    fn_swapBuffers    swapBuffers;
    fn_swapInterval   swapInterval;
    fn_destroyContext destroyContext;
    fn_destroySurface destroySurface;
    fn_getError       getError;
} egl;

typedef struct {
    EGLDisplay dpy;
    EGLConfig config;
    EGLContext context;
    EGLSurface surface;
    ANativeWindow *window;

    int alphaDepth;
    int depthDepth;
    int stencilDepth;
    int multisamples;
    int insetX, insetY;
} AndroidAWTContext;

static void rlawtThrow(JNIEnv *env, const char *msg) {
    LOGE("%s", msg);
    jclass clazz = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (clazz != NULL) (*env)->ThrowNew(env, clazz, msg);
}

static AndroidAWTContext *ctxOf(JNIEnv *env, jobject self) {
    jclass clazz = (*env)->GetObjectClass(env, self);
    jfieldID field = (*env)->GetFieldID(env, clazz, "instance", "J");
    if (field == NULL) return NULL;
    return (AndroidAWTContext *) (intptr_t) (*env)->GetLongField(env, self, field);
}

static void *openLib(const char *name, int flags) {
    const char *dir = getenv("POJAV_NATIVEDIR");
    if (dir != NULL) {
        char path[512];
        snprintf(path, sizeof(path), "%s/%s", dir, name);
        void *h = dlopen(path, flags);
        if (h != NULL) return h;
    }
    return dlopen(name, flags);
}

/* Everything Mesa needs before it will hand out a desktop GL context here. */
static int loadEgl(JNIEnv *env) {
    if (egl.loaded) return 1;

    const char *dir = getenv("POJAV_NATIVEDIR");
    if (dir != NULL) setenv("LIBGL_DRIVERS_PATH", dir, 1);
    setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 1);
    setenv("GALLIUM_DRIVER", "zink", 1);
    setenv("MESA_ANDROID_NO_KMS_SWRAST", "1", 1);
    setenv("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT", 1);
    setenv("MESA_GLSL_VERSION_OVERRIDE", "460", 1);

    openLib("libcutils.so", RTLD_GLOBAL | RTLD_NOW);
    openLib("libglapi.so", RTLD_GLOBAL | RTLD_NOW);

    if (getenv("VULKAN_PTR") == NULL) {
        void *vulkan = dlopen("libvulkan.so", RTLD_LAZY | RTLD_LOCAL);
        if (vulkan == NULL) {
            rlawtThrow(env, "rlawt: libvulkan.so did not load, zink cannot run");
            return 0;
        }
        char ptr[64];
        snprintf(ptr, sizeof(ptr), "%" PRIxPTR, (uintptr_t) vulkan);
        setenv("VULKAN_PTR", ptr, 1);
    }

    void *h = openLib("libEGL_mesa.so", RTLD_GLOBAL | RTLD_NOW);
    if (h == NULL) {
        rlawtThrow(env, "rlawt: libEGL_mesa.so did not load");
        return 0;
    }

    egl.getDisplay          = (fn_getDisplay)     dlsym(h, "eglGetDisplay");
    egl.initialize          = (fn_initialize)     dlsym(h, "eglInitialize");
    egl.bindAPI             = (fn_bindAPI)        dlsym(h, "eglBindAPI");
    egl.chooseConfig        = (fn_chooseConfig)   dlsym(h, "eglChooseConfig");
    egl.createContext       = (fn_createContext)  dlsym(h, "eglCreateContext");
    egl.createWindowSurface = (fn_createWindow)   dlsym(h, "eglCreateWindowSurface");
    egl.makeCurrent         = (fn_makeCurrent)    dlsym(h, "eglMakeCurrent");
    egl.swapBuffers         = (fn_swapBuffers)    dlsym(h, "eglSwapBuffers");
    egl.swapInterval        = (fn_swapInterval)   dlsym(h, "eglSwapInterval");
    egl.destroyContext      = (fn_destroyContext) dlsym(h, "eglDestroyContext");
    egl.destroySurface      = (fn_destroySurface) dlsym(h, "eglDestroySurface");
    egl.getError            = (fn_getError)       dlsym(h, "eglGetError");

    if (!egl.getDisplay || !egl.initialize || !egl.bindAPI || !egl.chooseConfig
        || !egl.createContext || !egl.createWindowSurface || !egl.makeCurrent
        || !egl.swapBuffers || !egl.getError) {
        rlawtThrow(env, "rlawt: libEGL_mesa.so is missing EGL entry points");
        return 0;
    }
    egl.loaded = 1;
    return 1;
}

/*
 * The Component is ignored on purpose. Upstream would take a drawable off it
 * through JAWT; here the scene goes to the launcher's own surface instead, and
 * Caciocavallo keeps painting the UI over the top.
 */
JNIEXPORT jlong JNICALL
Java_net_runelite_rlawt_AWTContext_create0(JNIEnv *env, jclass clazz, jobject component) {
    AndroidAWTContext *ctx = calloc(1, sizeof(AndroidAWTContext));
    if (ctx == NULL) {
        rlawtThrow(env, "rlawt: out of memory");
        return 0;
    }
    ctx->alphaDepth = 8;
    ctx->depthDepth = 24;
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_net_runelite_rlawt_AWTContext_destroy(JNIEnv *env, jobject self) {
    AndroidAWTContext *ctx = ctxOf(env, self);
    if (ctx == NULL) return;
    if (ctx->dpy != NULL) {
        egl.makeCurrent(ctx->dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (ctx->surface != NULL && egl.destroySurface) egl.destroySurface(ctx->dpy, ctx->surface);
        if (ctx->context != NULL && egl.destroyContext) egl.destroyContext(ctx->dpy, ctx->context);
    }
    if (ctx->window != NULL) ANativeWindow_release(ctx->window);
    free(ctx);
}

JNIEXPORT void JNICALL
Java_net_runelite_rlawt_AWTContext_configureInsets(JNIEnv *env, jobject self, jint x, jint y) {
    AndroidAWTContext *ctx = ctxOf(env, self);
    if (ctx == NULL) return;
    ctx->insetX = x;
    ctx->insetY = y;
}

JNIEXPORT void JNICALL
Java_net_runelite_rlawt_AWTContext_configurePixelFormat(JNIEnv *env, jobject self,
                                                        jint alpha, jint depth, jint stencil) {
    AndroidAWTContext *ctx = ctxOf(env, self);
    if (ctx == NULL) return;
    ctx->alphaDepth = alpha;
    ctx->depthDepth = depth;
    ctx->stencilDepth = stencil;
}

JNIEXPORT void JNICALL
Java_net_runelite_rlawt_AWTContext_configureMultisamples(JNIEnv *env, jobject self, jint samples) {
    AndroidAWTContext *ctx = ctxOf(env, self);
    if (ctx == NULL) return;
    ctx->multisamples = samples;
}

JNIEXPORT void JNICALL
Java_net_runelite_rlawt_AWTContext_createGLContext(JNIEnv *env, jobject self) {
    AndroidAWTContext *ctx = ctxOf(env, self);
    if (ctx == NULL) {
        rlawtThrow(env, "rlawt: no context");
        return;
    }
    if (!loadEgl(env)) return;

    if (pojav_environ == NULL || pojav_environ->pojavWindow == NULL) {
        rlawtThrow(env, "rlawt: the launcher has not handed over a surface yet");
        return;
    }
    ctx->window = pojav_environ->pojavWindow;
    ANativeWindow_acquire(ctx->window);

    ctx->dpy = egl.getDisplay(EGL_DEFAULT_DISPLAY);
    if (ctx->dpy == EGL_NO_DISPLAY) {
        rlawtThrow(env, "rlawt: no EGL display");
        return;
    }
    EGLint major = 0, minor = 0;
    if (!egl.initialize(ctx->dpy, &major, &minor)) {
        char msg[128];
        snprintf(msg, sizeof(msg), "rlawt: eglInitialize failed: 0x%04x", egl.getError());
        rlawtThrow(env, msg);
        return;
    }
    if (!egl.bindAPI(EGL_OPENGL_API)) {
        rlawtThrow(env, "rlawt: this driver will not bind desktop OpenGL");
        return;
    }

    EGLint attrs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, ctx->alphaDepth,
        EGL_DEPTH_SIZE, ctx->depthDepth,
        EGL_STENCIL_SIZE, ctx->stencilDepth,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_SAMPLES, ctx->multisamples,
        EGL_NONE
    };
    /* Multisampling is a preference, not a requirement: drop it rather than
       fail the whole context if the driver has no such config. */
    EGLint numConfigs = 0;
    if (!egl.chooseConfig(ctx->dpy, attrs, &ctx->config, 1, &numConfigs) || numConfigs == 0) {
        attrs[16] = EGL_NONE;
        if (!egl.chooseConfig(ctx->dpy, attrs, &ctx->config, 1, &numConfigs) || numConfigs == 0) {
            char msg[128];
            snprintf(msg, sizeof(msg), "rlawt: no usable GL config: 0x%04x", egl.getError());
            rlawtThrow(env, msg);
            return;
        }
        LOGI("no config with %dx multisampling, continuing without", ctx->multisamples);
    }

    /* 4.3 is what the plugin's compute shaders need. */
    const EGLint ctxAttrs[] = {
        EGL_CONTEXT_MAJOR_VERSION, 4,
        EGL_CONTEXT_MINOR_VERSION, 3,
        EGL_NONE
    };
    ctx->context = egl.createContext(ctx->dpy, ctx->config, EGL_NO_CONTEXT, ctxAttrs);
    if (ctx->context == EGL_NO_CONTEXT) {
        ctx->context = egl.createContext(ctx->dpy, ctx->config, EGL_NO_CONTEXT, NULL);
    }
    if (ctx->context == EGL_NO_CONTEXT) {
        char msg[128];
        snprintf(msg, sizeof(msg), "rlawt: eglCreateContext failed: 0x%04x", egl.getError());
        rlawtThrow(env, msg);
        return;
    }

    ctx->surface = egl.createWindowSurface(ctx->dpy, ctx->config,
                                           (EGLNativeWindowType) ctx->window, NULL);
    if (ctx->surface == EGL_NO_SURFACE) {
        char msg[128];
        snprintf(msg, sizeof(msg), "rlawt: eglCreateWindowSurface failed: 0x%04x", egl.getError());
        rlawtThrow(env, msg);
        return;
    }
    LOGI("GL context up, EGL %d.%d", major, minor);
}

JNIEXPORT jint JNICALL
Java_net_runelite_rlawt_AWTContext_setSwapInterval(JNIEnv *env, jobject self, jint interval) {
    AndroidAWTContext *ctx = ctxOf(env, self);
    if (ctx == NULL || ctx->dpy == NULL || egl.swapInterval == NULL) return 0;
    return egl.swapInterval(ctx->dpy, interval) ? interval : 0;
}

JNIEXPORT void JNICALL
Java_net_runelite_rlawt_AWTContext_makeCurrent(JNIEnv *env, jobject self) {
    AndroidAWTContext *ctx = ctxOf(env, self);
    if (ctx == NULL || ctx->dpy == NULL) return;
    if (!egl.makeCurrent(ctx->dpy, ctx->surface, ctx->surface, ctx->context)) {
        char msg[128];
        snprintf(msg, sizeof(msg), "rlawt: eglMakeCurrent failed: 0x%04x", egl.getError());
        rlawtThrow(env, msg);
    }
}

JNIEXPORT void JNICALL
Java_net_runelite_rlawt_AWTContext_detachCurrent(JNIEnv *env, jobject self) {
    AndroidAWTContext *ctx = ctxOf(env, self);
    if (ctx == NULL || ctx->dpy == NULL) return;
    egl.makeCurrent(ctx->dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

JNIEXPORT void JNICALL
Java_net_runelite_rlawt_AWTContext_swapBuffers(JNIEnv *env, jobject self) {
    AndroidAWTContext *ctx = ctxOf(env, self);
    if (ctx == NULL || ctx->dpy == NULL || ctx->surface == NULL) return;
    egl.swapBuffers(ctx->dpy, ctx->surface);
}

/* The scene renders into the window's own buffer, so the default framebuffer is
   the right answer, as it is for the Linux backend. */
JNIEXPORT jint JNICALL
Java_net_runelite_rlawt_AWTContext_getFramebuffer(JNIEnv *env, jobject self, jboolean front) {
    return 0;
}

JNIEXPORT jlong JNICALL
Java_net_runelite_rlawt_AWTContext_getGLContext(JNIEnv *env, jobject self) {
    AndroidAWTContext *ctx = ctxOf(env, self);
    return ctx == NULL ? 0 : (jlong) (intptr_t) ctx->context;
}

/* These three describe window systems that do not exist here. LWJGL only reads
   them on the platform they belong to. */
JNIEXPORT jlong JNICALL
Java_net_runelite_rlawt_AWTContext_getCGLShareGroup(JNIEnv *env, jobject self) {
    return 0;
}

JNIEXPORT jlong JNICALL
Java_net_runelite_rlawt_AWTContext_getGLXDisplay(JNIEnv *env, jobject self) {
    AndroidAWTContext *ctx = ctxOf(env, self);
    return ctx == NULL ? 0 : (jlong) (intptr_t) ctx->dpy;
}

JNIEXPORT jlong JNICALL
Java_net_runelite_rlawt_AWTContext_getWGLHDC(JNIEnv *env, jobject self) {
    return 0;
}
