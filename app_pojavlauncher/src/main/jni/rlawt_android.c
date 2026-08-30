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
typedef EGLBoolean (*fn_querySurface)(EGLDisplay, EGLSurface, EGLint, EGLint *);

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
    fn_querySurface   querySurface;
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
    int builtForWidth, builtForHeight;
    int failedForWidth, failedForHeight;
    char lastState[192];
} AndroidAWTContext;

/* logcat overflows during a client startup, so anything worth reading also goes
   to the file the launcher points at. */
static void rlawtNote(const char *msg) {
    const char *path = getenv("RUNAVA_RLAWT_LOG");
    if (path == NULL) return;
    FILE *f = fopen(path, "a");
    if (f == NULL) return;
    fprintf(f, "          rlawt: %s\n", msg);
    fclose(f);
}

static void rlawtThrow(JNIEnv *env, const char *msg) {
    LOGE("%s", msg);
    rlawtNote(msg);
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

    /* Mesa explains its own failures under this, in logcat as EGL-MAIN. Every
       eglInitialize failure so far has been a driver it could not find or load,
       and the message says which — far cheaper than guessing at the env. Not
       forced: an outer setting wins. */
    setenv("EGL_LOG_LEVEL", "debug", 0);

    const char *dir = getenv("POJAV_NATIVEDIR");
    if (dir != NULL) setenv("LIBGL_DRIVERS_PATH", dir, 1);
    {
        char msg[320];
        snprintf(msg, sizeof(msg), "POJAV_NATIVEDIR=%s", dir != NULL ? dir : "(unset)");
        rlawtNote(msg);
    }
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

        /* This Mesa is patched to take the loader as a handle and pull
           vkGetInstanceProcAddr off it. If that symbol is missing the failure
           surfaces much later and much less clearly, as zink not creating a
           screen at all, which is what the emulator does. */
        char msg[128];
        snprintf(msg, sizeof(msg), "libvulkan handle %p, vkGetInstanceProcAddr %p",
                 vulkan, dlsym(vulkan, "vkGetInstanceProcAddr"));
        rlawtNote(msg);
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
    egl.querySurface        = (fn_querySurface)   dlsym(h, "eglQuerySurface");

    if (!egl.getDisplay || !egl.initialize || !egl.bindAPI || !egl.chooseConfig
        || !egl.createContext || !egl.createWindowSurface || !egl.makeCurrent
        || !egl.swapBuffers || !egl.getError) {
        rlawtThrow(env, "rlawt: libEGL_mesa.so is missing EGL entry points");
        return 0;
    }
    /* The shim links against no EGL and dlopens whichever library POJAVEXEC_EGL
       names, exactly as ctxbridges/egl_loader.c does. Only JREUtils sets that,
       and only on the Minecraft zink path we do not take, so in GPU mode it is
       unset: the shim finds no EGL, its eglGetProcAddress stays null, and the
       first GL lookup LWJGL makes is a jump to address zero. */
    setenv("POJAVEXEC_EGL", "libEGL_mesa.so", 1);
    openLib("libglxshim.so", RTLD_GLOBAL | RTLD_NOW);

    egl.loaded = 1;
    return 1;
}


/* Enough GL to describe a frame from inside the swap. Resolved through the
   same shim LWJGL uses, so a null here means LWJGL sees null too. */
static void (*gl_getIntegerv)(unsigned int, int *) = NULL;
static void (*gl_readPixels)(int, int, int, int, unsigned int, unsigned int, void *) = NULL;
static void (*gl_bindFramebuffer)(unsigned int, unsigned int) = NULL;

/* Ask through the same path LWJGL will use and report what comes back, one step
   at a time. Nothing resolved here is called: a bad pointer that is not null
   takes the process down, which is exactly what LWJGL then does to itself. */
static void reportWhatLwjglWillSee(void) {
    rlawtNote("checking what LWJGL will resolve");

    void *shim = openLib("libglxshim.so", RTLD_GLOBAL | RTLD_NOW);
    if (shim == NULL) {
        rlawtNote("libglxshim.so did not load, LWJGL will find no GL");
        return;
    }
    rlawtNote("libglxshim.so loaded");

    void *(*getProc)(const char *) = dlsym(shim, "glXGetProcAddress");
    if (getProc == NULL) {
        rlawtNote("libglxshim.so has no glXGetProcAddress");
        return;
    }
    rlawtNote("glXGetProcAddress found");

    char msg[192];
    const char *names[] = {"glGetString", "glClear", "glGenBuffers"};
    for (unsigned i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        void *fn = getProc(names[i]);
        snprintf(msg, sizeof(msg), "%s resolves to %p", names[i], fn);
        rlawtNote(msg);
    }

    gl_getIntegerv     = getProc("glGetIntegerv");
    gl_readPixels      = getProc("glReadPixels");
    gl_bindFramebuffer = getProc("glBindFramebuffer");
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

    /* Read the window as a pointer out of the environment rather than out of
       pojav_environ. This library is loaded by the JVM, not by Dalvik, so it
       lives in a different linker namespace and gets its own copy of
       libpojavexec whose pojav_environ is empty. The environment is shared. */
    ANativeWindow *window = NULL;
    const char *windowPtr = getenv("RUNAVA_WINDOW_PTR");
    if (windowPtr != NULL) {
        window = (ANativeWindow *) (uintptr_t) strtoull(windowPtr, NULL, 16);
    }
    if (window == NULL && pojav_environ != NULL) {
        window = pojav_environ->pojavWindow;
    }
    if (window == NULL) {
        char msg[256];
        snprintf(msg, sizeof(msg),
                 "rlawt: no surface. RUNAVA_WINDOW_PTR=%s, pojav_environ=%p, its window=%p",
                 windowPtr != NULL ? windowPtr : "(unset)",
                 (void *) pojav_environ,
                 pojav_environ != NULL ? (void *) pojav_environ->pojavWindow : NULL);
        rlawtThrow(env, msg);
        return;
    }
    {
        char msg[128];
        snprintf(msg, sizeof(msg), "got the scene window at %p", (void *) window);
        rlawtNote(msg);
    }
    ctx->window = window;
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

    /* Zero width and height mean "follow the window", which is how Pojav sets
       this up in ctxbridges/gl_bridge.c. The surface then tracks a rotation on
       its own and never has to be torn down for one — and tearing it down is
       exactly what its neighbouring comment warns about: some drivers take
       most of a second to finish destroying a surface and fault if you release
       during it. */
    ANativeWindow_setBuffersGeometry(ctx->window, 0, 0, 0);
    ctx->surface = egl.createWindowSurface(ctx->dpy, ctx->config,
                                           (EGLNativeWindowType) ctx->window, NULL);
    if (ctx->surface == EGL_NO_SURFACE) {
        char msg[128];
        snprintf(msg, sizeof(msg), "rlawt: eglCreateWindowSurface failed: 0x%04x", egl.getError());
        rlawtThrow(env, msg);
        return;
    }
    ctx->builtForWidth = ANativeWindow_getWidth(ctx->window);
    ctx->builtForHeight = ANativeWindow_getHeight(ctx->window);
    /* Leave it current, as the other backends do. RuneLite calls
       GL.createCapabilities right after this, and LWJGL resolves every GL entry
       point through the current context: without one they all come back null and
       the first call through them is a jump to address zero. */
    if (!egl.makeCurrent(ctx->dpy, ctx->surface, ctx->surface, ctx->context)) {
        char msg[128];
        snprintf(msg, sizeof(msg), "rlawt: eglMakeCurrent after create failed: 0x%04x",
                 egl.getError());
        rlawtThrow(env, msg);
        return;
    }
    LOGI("GL context up and current, EGL %d.%d", major, minor);
    rlawtNote("GL context up and current");
    reportWhatLwjglWillSee();
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
    if (ctx == NULL || ctx->dpy == NULL) return;
    if (ctx->surface == EGL_NO_SURFACE) return;

    /* Everything that decides whether a frame reaches the screen, measured in
       the one place that can see all of it. Eight rounds of rotation fixes were
       argued from inference about these numbers rather than the numbers, and
       at least one conclusion drawn that way was simply wrong. Logged when it
       changes, so a rotation prints a line and a steady state prints nothing. */
    int windowW = ANativeWindow_getWidth(ctx->window);
    int windowH = ANativeWindow_getHeight(ctx->window);
    EGLint surfaceW = -1, surfaceH = -1;
    if (egl.querySurface != NULL) {
        egl.querySurface(ctx->dpy, ctx->surface, EGL_WIDTH, &surfaceW);
        egl.querySurface(ctx->dpy, ctx->surface, EGL_HEIGHT, &surfaceH);
    }
    int viewport[4] = {-1, -1, -1, -1};
    int readFbo = 0;
    if (gl_getIntegerv != NULL) {
        gl_getIntegerv(0x0BA2 /* GL_VIEWPORT */, viewport);
        gl_getIntegerv(0x8CAA /* GL_READ_FRAMEBUFFER_BINDING */, &readFbo);
    }

    /* The geometry above is free to read. A readback is not: glReadPixels
       stalls until the GPU catches up, and doing it every frame is a real cost
       — it made the game feel slower, which is a poor trade for a diagnostic.
       Only look when the geometry changed, which is the moment of interest,
       and once in a while so a steady state is still checked. */
    static long swaps = 0;
    char geometry[160];
    snprintf(geometry, sizeof(geometry),
             "window %dx%d surface %dx%d viewport %d,%d %dx%d fbo %d",
             windowW, windowH, surfaceW, surfaceH,
             viewport[0], viewport[1], viewport[2], viewport[3], readFbo);
    int geometryChanged = strcmp(geometry, ctx->lastState) != 0;

    /* The scene rectangle is not derived here any more. It used to be
       computed as windowH - viewportH, which is only where AWT put the canvas
       when the canvas happens to reach the bottom of the frame. Measured, it
       did not: AWT had the canvas at row 24 while this arithmetic said 84, so
       the picture was drawn 60 rows below where clicks were interpreted, and
       at the login screen the same arithmetic was out by 1657. The agent reads
       the rectangle off the canvas component itself and writes it instead. */
    int sampleNow = geometryChanged || (swaps % 600) == 0;
    swaps++;

    /* Sample a grid over the whole viewport, not one point. The single centre
       sample said DARK in portrait and lit in landscape, which reads like the
       renderer failing — but the login art is 503 rows tall at one end of a
       2160-row canvas, so the centre simply falls outside it. Where the
       drawing is matters as much as whether there is any.

       glReadPixels takes its source from whatever framebuffer is bound, and
       the client leaves its own bound, so several earlier "the frame is black"
       readings were looks into RuneLite's scene buffer rather than the window. */
    /* Where the picture actually is, as a bounding box over the whole window.
       Everything logged until now described the space the client was given -
       window, surface, viewport - and those have all agreed with each other for
       a while now while the picture was still wrong. The one thing never
       measured is where the lit pixels ended up, and OSRS in fixed mode paints
       only 765x503 somewhere inside a viewport many times that size, so the
       viewport says nothing about it.

       Sixteen full-width rows rather than a grid of small reads: glReadPixels
       synchronises, so the cost is in the number of calls, not the pixels.
       Reported in Android's coordinates so it can be compared against the hole
       rectangle directly. */
    char grid[64] = "";
    if (sampleNow && gl_readPixels != NULL && gl_bindFramebuffer != NULL
            && windowW > 16 && windowW <= 4096 && windowH > 16) {
        static unsigned char row[4096 * 4];
        gl_bindFramebuffer(0x8CA8 /* GL_READ_FRAMEBUFFER */, 0);
        int x0 = windowW, x1 = -1, yTop = -1, yBottom = -1;
        for (int i = 0; i < 16; i++) {
            int y = (windowH - 1) * i / 15;
            memset(row, 0, (size_t) windowW * 4);
            gl_readPixels(0, y, windowW, 1, 0x1908 /* GL_RGBA */,
                          0x1401 /* GL_UNSIGNED_BYTE */, row);
            int first = -1, last = -1;
            for (int x = 0; x < windowW; x++) {
                const unsigned char *p = row + x * 4;
                if (p[0] > 24 || p[1] > 24 || p[2] > 24) {
                    if (first < 0) first = x;
                    last = x;
                }
            }
            if (first < 0) continue;
            if (first < x0) x0 = first;
            if (last > x1) x1 = last;
            int androidY = windowH - 1 - y;
            if (yTop < 0 || androidY < yTop) yTop = androidY;
            if (androidY > yBottom) yBottom = androidY;
        }
        if (x1 >= 0) {
            snprintf(grid, sizeof(grid), "%d,%d %dx%d",
                     x0, yTop, x1 - x0 + 1, yBottom - yTop + 1);
        } else {
            snprintf(grid, sizeof(grid), "nothing lit");
        }
        gl_bindFramebuffer(0x8CA8, (unsigned int) readFbo);
    }

    EGLBoolean ok = egl.swapBuffers(ctx->dpy, ctx->surface);
    if (!ok && egl.getError() == 0x300D /* EGL_BAD_SURFACE */) {
        /* The window went away underneath us. Pojav's bridge handles the same
           case by detaching and waiting rather than carrying on against a dead
           surface; rebuilding here instead is what left the context with no
           surface at all and no way back. */
        egl.makeCurrent(ctx->dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (egl.destroySurface != NULL) egl.destroySurface(ctx->dpy, ctx->surface);
        ANativeWindow_setBuffersGeometry(ctx->window, 0, 0, 0);
        ctx->surface = egl.createWindowSurface(ctx->dpy, ctx->config,
                                               (EGLNativeWindowType) ctx->window, NULL);
        if (ctx->surface != EGL_NO_SURFACE) {
            egl.makeCurrent(ctx->dpy, ctx->surface, ctx->surface, ctx->context);
            rlawtNote("surface was bad, rebuilt against the same window");
        } else {
            rlawtNote("surface was bad and would not come back");
        }
    }

    if (sampleNow) {
        snprintf(ctx->lastState, sizeof(ctx->lastState), "%s", geometry);
        char msg[256];
        snprintf(msg, sizeof(msg), "%s ok %d, content %s",
                 geometry, (int) ok, grid[0] ? grid : " unread");
        rlawtNote(msg);
    }
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
