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
    int awtWidth, awtHeight, awtStride;
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
static void (*gl_scissor)(int, int, int, int) = NULL;
static void (*gl_enable)(unsigned int) = NULL;
static void (*gl_disable)(unsigned int) = NULL;
static unsigned char (*gl_isEnabled)(unsigned int) = NULL;
static void (*gl_clearColor)(float, float, float, float) = NULL;
static void (*gl_clear)(unsigned int) = NULL;
static void (*gl_getFloatv)(unsigned int, float *) = NULL;
static void (*gl_genTextures)(int, unsigned int *) = NULL;
static void (*gl_bindTexture)(unsigned int, unsigned int) = NULL;
static void (*gl_texImage2D)(unsigned int, int, int, int, int, int, unsigned int, unsigned int, const void *) = NULL;
static void (*gl_texSubImage2D)(unsigned int, int, int, int, int, int, unsigned int, unsigned int, const void *) = NULL;
static void (*gl_texParameteri)(unsigned int, unsigned int, int) = NULL;
static void (*gl_pixelStorei)(unsigned int, int) = NULL;
static void (*gl_blendFunc)(unsigned int, unsigned int) = NULL;
static void (*gl_pushAttrib)(unsigned int) = NULL;
static void (*gl_popAttrib)(void) = NULL;
static void (*gl_matrixMode)(unsigned int) = NULL;
static void (*gl_pushMatrix)(void) = NULL;
static void (*gl_popMatrix)(void) = NULL;
static void (*gl_loadIdentity)(void) = NULL;
static void (*gl_ortho)(double, double, double, double, double, double) = NULL;
static void (*gl_viewport)(int, int, int, int) = NULL;
static void (*gl_begin)(unsigned int) = NULL;
static void (*gl_end)(void) = NULL;
static void (*gl_texCoord2f)(float, float) = NULL;
static void (*gl_vertex2f)(float, float) = NULL;
static void (*gl_color4f)(float, float, float, float) = NULL;
static void (*gl_useProgram)(unsigned int) = NULL;

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
    gl_scissor         = getProc("glScissor");
    gl_enable          = getProc("glEnable");
    gl_disable         = getProc("glDisable");
    gl_isEnabled       = getProc("glIsEnabled");
    gl_clearColor      = getProc("glClearColor");
    gl_clear           = getProc("glClear");
    gl_getFloatv       = getProc("glGetFloatv");
    gl_genTextures     = getProc("glGenTextures");
    gl_bindTexture     = getProc("glBindTexture");
    gl_texImage2D      = getProc("glTexImage2D");
    gl_texSubImage2D   = getProc("glTexSubImage2D");
    gl_texParameteri   = getProc("glTexParameteri");
    gl_pixelStorei     = getProc("glPixelStorei");
    gl_blendFunc       = getProc("glBlendFunc");
    gl_pushAttrib      = getProc("glPushAttrib");
    gl_popAttrib       = getProc("glPopAttrib");
    gl_matrixMode      = getProc("glMatrixMode");
    gl_pushMatrix      = getProc("glPushMatrix");
    gl_popMatrix       = getProc("glPopMatrix");
    gl_loadIdentity    = getProc("glLoadIdentity");
    gl_ortho           = getProc("glOrtho");
    gl_viewport        = getProc("glViewport");
    gl_begin           = getProc("glBegin");
    gl_end             = getProc("glEnd");
    gl_texCoord2f      = getProc("glTexCoord2f");
    gl_vertex2f        = getProc("glVertex2f");
    gl_color4f         = getProc("glColor4f");
    gl_useProgram      = getProc("glUseProgram");
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

/* Rotating the device resizes the ANativeWindow under a surface EGL has already
   built, and the surface does not follow: a first frame has been logged with a
   2244x1008 surface on a 1008x2244 window. This is the kopper build of zink,
   which presents nothing whenever the drawable and the window disagree, so from
   the first rotation onwards the scene is simply absent while the software
   layer above it keeps drawing the window chrome — the frame is there and the
   game is not.

   An earlier attempt at this failed with EGL_BAD_ALLOC and was wrongly written
   off as unnecessary. The error was the call order: EGL will not hold two
   window surfaces on one native window, so the old one has to be released
   first, which in turn means dropping it as current before releasing it. */
static void rebuildSurfaceIfWindowResized(AndroidAWTContext *ctx) {
    if (ctx->window == NULL || egl.destroySurface == NULL) return;

    int width = ANativeWindow_getWidth(ctx->window);
    int height = ANativeWindow_getHeight(ctx->window);
    if (width <= 0 || height <= 0) return;
    if (ctx->surface != EGL_NO_SURFACE
            && width == ctx->builtForWidth && height == ctx->builtForHeight) {
        return;
    }

    int wasWidth = ctx->builtForWidth, wasHeight = ctx->builtForHeight;
    egl.makeCurrent(ctx->dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (ctx->surface != EGL_NO_SURFACE) {
        egl.destroySurface(ctx->dpy, ctx->surface);
        ctx->surface = EGL_NO_SURFACE;
    }
    EGLSurface fresh = egl.createWindowSurface(ctx->dpy, ctx->config,
                                               (EGLNativeWindowType) ctx->window, NULL);

    char msg[160];
    /* Record the size only once the surface is actually up. Marking it first
       and returning on failure left the context with no surface at all, and
       swapBuffers gives up on exactly that — so no frame was ever drawn again,
       while the recorded size stopped any retry. That is the rotation that
       goes black and stays black until you turn the device the other way. */
    if (fresh == EGL_NO_SURFACE) {
        if (ctx->failedForWidth != width || ctx->failedForHeight != height) {
            ctx->failedForWidth = width;
            ctx->failedForHeight = height;
            snprintf(msg, sizeof(msg), "surface rebuild for %dx%d failed: 0x%04x, will retry",
                     width, height, egl.getError());
            rlawtNote(msg);
        }
        return;
    }
    if (!egl.makeCurrent(ctx->dpy, fresh, fresh, ctx->context)) {
        egl.destroySurface(ctx->dpy, fresh);
        if (ctx->failedForWidth != width || ctx->failedForHeight != height) {
            ctx->failedForWidth = width;
            ctx->failedForHeight = height;
            snprintf(msg, sizeof(msg), "rebuilt %dx%d would not go current: 0x%04x, will retry",
                     width, height, egl.getError());
            rlawtNote(msg);
        }
        return;
    }

    ctx->surface = fresh;
    ctx->builtForWidth = width;
    ctx->builtForHeight = height;
    ctx->failedForWidth = ctx->failedForHeight = 0;
    snprintf(msg, sizeof(msg), "surface rebuilt %dx%d -> %dx%d after a window resize",
             wasWidth, wasHeight, width, height);
    rlawtNote(msg);
}


/* Composite the AWT layer into this same context, instead of a second Android
   view stacked on top.
 *
 * That second view is why rotation falls apart: two surfaces plus a rectangle
 * passed between processes, none of which turn at the same moment. Software
 * mode has none of that — one surface, nothing to synchronise — and it rotates
 * perfectly. This gets the same property back while keeping the scene on the
 * GPU.
 *
 * Caciocavallo paints the game canvas area opaque black, so drawing its frame
 * straight over the scene would hide it again, one layer lower. The chrome is
 * drawn as four strips around the canvas instead. Where the canvas is comes
 * from configureInsets and the viewport, both of which are right here, in the
 * frame they change — no poller, no file, nothing to fall out of step.
 *
 * The pixels need no conversion: Cacio's 0xAARRGGBB is GL_BGRA byte order, so
 * this uploads what the CPU path used to swizzle by hand. */
static unsigned int overlayTexture = 0;
static int overlayW = 0, overlayH = 0;
static jclass overlayScreenClass = NULL;
static jmethodID overlayGetRGB = NULL;

static int overlayReady(void) {
    return gl_genTextures && gl_bindTexture && gl_texImage2D && gl_texSubImage2D
        && gl_texParameteri && gl_pixelStorei && gl_blendFunc && gl_pushAttrib
        && gl_popAttrib && gl_matrixMode && gl_pushMatrix && gl_popMatrix
        && gl_loadIdentity && gl_ortho && gl_begin && gl_end && gl_texCoord2f
        && gl_vertex2f && gl_color4f && gl_enable && gl_disable;
}

/* Cacio ships under two package names depending on the build. */
static int overlayResolveScreen(JNIEnv *env) {
    if (overlayGetRGB != NULL) return 1;
    jclass c = (*env)->FindClass(env, "net/java/openjdk/cacio/ctc/CTCScreen");
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        c = (*env)->FindClass(env, "com/github/caciocavallosilano/cacio/ctc/CTCScreen");
    }
    if (c == NULL) {
        (*env)->ExceptionClear(env);
        return 0;
    }
    overlayScreenClass = (*env)->NewGlobalRef(env, c);
    overlayGetRGB = (*env)->GetStaticMethodID(env, overlayScreenClass,
                                              "getCurrentScreenRGB", "()[I");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return overlayGetRGB != NULL;
}

static void overlayQuad(float x0, float y0, float x1, float y1, int w, int h) {
    if (x1 <= x0 || y1 <= y0) return;
    gl_begin(0x0007 /* GL_QUADS */);
    gl_texCoord2f(x0 / w, y0 / h); gl_vertex2f(x0, y0);
    gl_texCoord2f(x1 / w, y0 / h); gl_vertex2f(x1, y0);
    gl_texCoord2f(x1 / w, y1 / h); gl_vertex2f(x1, y1);
    gl_texCoord2f(x0 / w, y1 / h); gl_vertex2f(x0, y1);
    gl_end();
}

static void drawAwtOverlay(JNIEnv *env, AndroidAWTContext *ctx,
                           int surfaceW, int surfaceH, const int *viewport) {
    if (!overlayReady() || !overlayResolveScreen(env)) return;
    if (surfaceW <= 0 || surfaceH <= 0) return;

    /* Visible AWT region and the stride of Cacio's square screen, published by
       the activity the same way the window pointer is. */
    const char *geom = getenv("RUNAVA_AWT_VISIBLE");
    if (geom != NULL) {
        int w = 0, h = 0, stride = 0;
        if (sscanf(geom, "%dx%dx%d", &w, &h, &stride) == 3 && w > 0 && h > 0 && stride > 0) {
            ctx->awtWidth = w; ctx->awtHeight = h; ctx->awtStride = stride;
        }
    }
    int canvasW = ctx->awtWidth, canvasH = ctx->awtHeight;
    if (canvasW <= 0 || canvasH <= 0) return;

    jintArray pixels = (jintArray) (*env)->CallStaticObjectMethod(
            env, overlayScreenClass, overlayGetRGB);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return; }

    gl_pushAttrib(0x000FFFFF /* GL_ALL_ATTRIB_BITS */);
    if (gl_useProgram != NULL) gl_useProgram(0);
    gl_matrixMode(0x1701 /* GL_PROJECTION */); gl_pushMatrix(); gl_loadIdentity();
    gl_ortho(0, surfaceW, surfaceH, 0, -1, 1);
    gl_matrixMode(0x1700 /* GL_MODELVIEW */); gl_pushMatrix(); gl_loadIdentity();
    if (gl_viewport != NULL) gl_viewport(0, 0, surfaceW, surfaceH);

    gl_enable(0x0DE1 /* GL_TEXTURE_2D */);
    if (overlayTexture == 0) {
        gl_genTextures(1, &overlayTexture);
        gl_bindTexture(0x0DE1, overlayTexture);
        gl_texParameteri(0x0DE1, 0x2801 /* MIN_FILTER */, 0x2601 /* LINEAR */);
        gl_texParameteri(0x0DE1, 0x2800 /* MAG_FILTER */, 0x2601);
        gl_texParameteri(0x0DE1, 0x2802 /* WRAP_S */, 0x812F /* CLAMP_TO_EDGE */);
        gl_texParameteri(0x0DE1, 0x2803 /* WRAP_T */, 0x812F);
    } else {
        gl_bindTexture(0x0DE1, overlayTexture);
    }

    if (pixels != NULL) {
        jint *src = (*env)->GetPrimitiveArrayCritical(env, pixels, NULL);
        if (src != NULL) {
            /* Cacio hands out a square screen; only the visible corner matters. */
            gl_pixelStorei(0x0CF2 /* GL_UNPACK_ROW_LENGTH */, ctx->awtStride);
            if (overlayW != canvasW || overlayH != canvasH) {
                overlayW = canvasW; overlayH = canvasH;
                gl_texImage2D(0x0DE1, 0, 0x1908 /* GL_RGBA */, canvasW, canvasH, 0,
                              0x80E1 /* GL_BGRA */, 0x1401 /* GL_UNSIGNED_BYTE */, src);
            } else {
                gl_texSubImage2D(0x0DE1, 0, 0, 0, canvasW, canvasH,
                                 0x80E1, 0x1401, src);
            }
            gl_pixelStorei(0x0CF2, 0);
            (*env)->ReleasePrimitiveArrayCritical(env, pixels, src, JNI_ABORT);
        }
        (*env)->DeleteLocalRef(env, pixels);
    }

    gl_disable(0x0B71 /* GL_DEPTH_TEST */);
    gl_disable(0x0BC0 /* GL_ALPHA_TEST */);
    gl_disable(0x0C11 /* GL_SCISSOR_TEST */);
    gl_enable(0x0BE2 /* GL_BLEND */);
    gl_blendFunc(0x0302 /* SRC_ALPHA */, 0x0303 /* ONE_MINUS_SRC_ALPHA */);
    gl_color4f(1, 1, 1, 1);

    /* Everything except the rectangle the client draws into. */
    float scale = (float) surfaceW / canvasW;
    float hx0 = ctx->insetX * scale, hy0 = ctx->insetY * scale;
    float hx1 = hx0 + viewport[2] * scale, hy1 = hy0 + viewport[3] * scale;
    if (hx0 < 0) hx0 = 0;
    if (hy0 < 0) hy0 = 0;
    if (hx1 > surfaceW) hx1 = surfaceW;
    if (hy1 > surfaceH) hy1 = surfaceH;

    int tw = canvasW, th = canvasH;
    overlayQuad(0, 0, surfaceW, hy0, tw, th);
    overlayQuad(0, hy1, surfaceW, surfaceH, tw, th);
    overlayQuad(0, hy0, hx0, hy1, tw, th);
    overlayQuad(hx1, hy0, surfaceW, hy1, tw, th);

    gl_matrixMode(0x1700); gl_popMatrix();
    gl_matrixMode(0x1701); gl_popMatrix();
    gl_popAttrib();
}

JNIEXPORT void JNICALL
Java_net_runelite_rlawt_AWTContext_swapBuffers(JNIEnv *env, jobject self) {
    AndroidAWTContext *ctx = ctxOf(env, self);
    if (ctx == NULL || ctx->dpy == NULL) return;
    /* Before the surface check, not after: a failed rebuild leaves no surface,
       and bailing out here is what made that state permanent. */
    rebuildSurfaceIfWindowResized(ctx);
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

    /* Publish where the scene is, from inside the render path.
       The launcher cuts a transparent hole in the software layer so this scene
       shows through, and it used to get the rectangle from the JVM-side agent,
       which polls. On a rotation the two moved at different times: for a
       moment the hole sat where the canvas used to be, so half the picture was
       covered and half was not — which is what "half the image loads late"
       looks like.
       This is the same instant the scene geometry changes, and the offset is
       RuneLite's own, handed to configureInsets and ignored until now. */
    if (geometryChanged && viewport[2] > 0 && viewport[3] > 0) {
        const char *dir = getenv("RUNAVA_RLAWT_LOG");
        if (dir != NULL) {
            char path[512];
            snprintf(path, sizeof(path), "%s", dir);
            char *slash = strrchr(path, '/');
            if (slash != NULL) {
                snprintf(slash + 1, sizeof(path) - (slash + 1 - path), ".runelitedroid_canvas");
                FILE *f = fopen(path, "w");
                if (f != NULL) {
                    fprintf(f, "%d %d %d %d", ctx->insetX, ctx->insetY, viewport[2], viewport[3]);
                    fclose(f);
                }
            }
        }
    }
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
    char grid[64] = "";
    if (sampleNow && gl_readPixels != NULL && gl_bindFramebuffer != NULL
            && viewport[2] > 16 && viewport[3] > 16) {
        gl_bindFramebuffer(0x8CA8 /* GL_READ_FRAMEBUFFER */, 0);
        char *out = grid;
        for (int gy = 2; gy >= 0; gy--) {
            for (int gx = 0; gx < 3; gx++) {
                unsigned char px[8 * 8 * 4];
                memset(px, 0, sizeof(px));
                gl_readPixels(viewport[0] + (viewport[2] - 8) * gx / 2,
                              viewport[1] + (viewport[3] - 8) * gy / 2,
                              8, 8, 0x1908 /* GL_RGBA */, 0x1401 /* GL_UNSIGNED_BYTE */, px);
                int brightest = 0;
                for (unsigned i = 0; i < sizeof(px); i++) {
                    if ((i % 4) != 3 && px[i] > brightest) brightest = px[i];
                }
                out += snprintf(out, sizeof(grid) - (out - grid), "%3d", brightest);
            }
            if (gy > 0) out += snprintf(out, sizeof(grid) - (out - grid), " /");
        }
        gl_bindFramebuffer(0x8CA8, (unsigned int) readFbo);
    }

    drawAwtOverlay(env, ctx, surfaceW, surfaceH, viewport);

    EGLBoolean ok = egl.swapBuffers(ctx->dpy, ctx->surface);

    if (sampleNow) {
        snprintf(ctx->lastState, sizeof(ctx->lastState), "%s", geometry);
        char msg[256];
        snprintf(msg, sizeof(msg), "%s ok %d, window top to bottom:%s",
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
