/*
 * Does this device give us a desktop GL context good enough for RuneLite's GPU
 * plugin? The plugin wants OpenGL 4.3, because its scene upload runs in compute
 * shaders.
 *
 * This deliberately does not go through Pojav's GL bridge. That bridge only
 * ever ran on the Minecraft path and assumes things the RuneLite path does not
 * provide: a renderer name in the environment, a live runtime VM, LIBGL_ES set,
 * a graphics library already dlopen'd, LD_LIBRARY_PATH pointing at Mesa. Five
 * separate failures came out of those assumptions before this was rewritten to
 * stand alone.
 *
 * It also renders to a pbuffer rather than a window, which is what makes it
 * runnable unattended: no SurfaceView, so no visible activity and no unlocked
 * screen required.
 *
 * Nothing here is part of the render path.
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <inttypes.h>
#include <EGL/egl.h>

#define GL_VENDOR                   0x1F00
#define GL_RENDERER                 0x1F01
#define GL_VERSION                  0x1F02
#define GL_SHADING_LANGUAGE_VERSION 0x8B8C

typedef const char *(*glGetString_t)(unsigned int name);
typedef EGLDisplay (*fn_getDisplay)(EGLNativeDisplayType);
typedef EGLBoolean (*fn_initialize)(EGLDisplay, EGLint *, EGLint *);
typedef EGLBoolean (*fn_bindAPI)(EGLenum);
typedef EGLBoolean (*fn_chooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
typedef EGLContext (*fn_createContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
typedef EGLSurface (*fn_createPbuffer)(EGLDisplay, EGLConfig, const EGLint *);
typedef EGLBoolean (*fn_makeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef EGLint (*fn_getError)(void);
typedef void *(*fn_getProcAddress)(const char *);

static const char *str_or(const char *s, const char *fallback) {
    return (s != NULL && s[0] != '\0') ? s : fallback;
}

static void *open_in(const char *dir, const char *name, int flags) {
    char path[512];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    return dlopen(path, flags);
}

JNIEXPORT jstring JNICALL
Java_net_kdt_pojavlaunch_GlProbe_probeDesktopGL(JNIEnv *env, jclass clazz, jstring jLibDir) {
    char out[1400];
    const char *libDir = (*env)->GetStringUTFChars(env, jLibDir, NULL);

    /* Mesa reads these when it picks a driver, so they have to be in place
       before eglInitialize rather than merely before the JVM. */
    setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 1);
    setenv("GALLIUM_DRIVER", "zink", 1);
    setenv("MESA_ANDROID_NO_KMS_SWRAST", "1", 1);
    setenv("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT", 1);
    setenv("MESA_GLSL_VERSION_OVERRIDE", "460", 1);

    /* Kopper pulls symbols out of libcutils and zink needs the loader. Both ship
       in the APK, so open them by full path rather than relying on a search path
       that only gets set up for the JVM. */
    open_in(libDir, "libcutils.so", RTLD_GLOBAL | RTLD_NOW);
    open_in(libDir, "libglapi.so", RTLD_GLOBAL | RTLD_NOW);

    /* This Mesa is patched to take the Vulkan loader as a pointer in the
       environment rather than dlopening it itself, which is what zink needs to
       come up at all. Without it eglInitialize returns EGL_NOT_INITIALIZED. */
    void *vulkan = dlopen("libvulkan.so", RTLD_LAZY | RTLD_LOCAL);
    if (vulkan == NULL) {
        snprintf(out, sizeof(out), "libvulkan.so did not load: %s",
                 str_or(dlerror(), "no dlerror"));
        goto done;
    }
    char vulkanPtr[64];
    snprintf(vulkanPtr, sizeof(vulkanPtr), "%" PRIxPTR, (uintptr_t) vulkan);
    setenv("VULKAN_PTR", vulkanPtr, 1);

    void *egl = open_in(libDir, "libEGL_mesa.so", RTLD_GLOBAL | RTLD_NOW);
    if (egl == NULL) {
        snprintf(out, sizeof(out), "libEGL_mesa.so did not load: %s",
                 str_or(dlerror(), "no dlerror"));
        goto done;
    }


    fn_getDisplay    p_eglGetDisplay          = (fn_getDisplay)    dlsym(egl, "eglGetDisplay");
    fn_initialize    p_eglInitialize          = (fn_initialize)    dlsym(egl, "eglInitialize");
    fn_bindAPI       p_eglBindAPI             = (fn_bindAPI)       dlsym(egl, "eglBindAPI");
    fn_chooseConfig  p_eglChooseConfig        = (fn_chooseConfig)  dlsym(egl, "eglChooseConfig");
    fn_createContext p_eglCreateContext       = (fn_createContext) dlsym(egl, "eglCreateContext");
    fn_createPbuffer p_eglCreatePbufferSurface= (fn_createPbuffer) dlsym(egl, "eglCreatePbufferSurface");
    fn_makeCurrent   p_eglMakeCurrent         = (fn_makeCurrent)   dlsym(egl, "eglMakeCurrent");
    fn_getError      p_eglGetError            = (fn_getError)      dlsym(egl, "eglGetError");
    fn_getProcAddress p_eglGetProcAddress     = (fn_getProcAddress)dlsym(egl, "eglGetProcAddress");

    if (!p_eglGetDisplay || !p_eglInitialize || !p_eglBindAPI || !p_eglChooseConfig
        || !p_eglCreateContext || !p_eglCreatePbufferSurface || !p_eglMakeCurrent
        || !p_eglGetError || !p_eglGetProcAddress) {
        snprintf(out, sizeof(out), "libEGL_mesa.so is missing EGL entry points");
        goto done;
    }

    EGLDisplay dpy = p_eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY) {
        snprintf(out, sizeof(out), "eglGetDisplay returned EGL_NO_DISPLAY");
        goto done;
    }
    EGLint major = 0, minor = 0;
    if (!p_eglInitialize(dpy, &major, &minor)) {
        snprintf(out, sizeof(out), "eglInitialize failed: 0x%04x", p_eglGetError());
        goto done;
    }

    /* EGL_OPENGL_API rather than ES. This is the whole question: the GPU plugin
       needs desktop GL, not GLES. */
    if (!p_eglBindAPI(EGL_OPENGL_API)) {
        snprintf(out, sizeof(out),
                 "EGL %d.%d is up but refuses desktop OpenGL: eglBindAPI failed with "
                 "0x%04x, so only OpenGL ES is available here.",
                 major, minor, p_eglGetError());
        goto done;
    }

    const EGLint configAttrs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs = 0;
    if (!p_eglChooseConfig(dpy, configAttrs, &config, 1, &numConfigs) || numConfigs == 0) {
        snprintf(out, sizeof(out),
                 "EGL %d.%d took the desktop GL binding but offers no config for it "
                 "(eglChooseConfig gave %d, error 0x%04x)",
                 major, minor, numConfigs, p_eglGetError());
        goto done;
    }

    /* Ask for 4.3 outright. If the driver cannot serve it, that is the answer. */
    const EGLint ctxAttrs[] = {
        EGL_CONTEXT_MAJOR_VERSION, 4,
        EGL_CONTEXT_MINOR_VERSION, 3,
        EGL_NONE
    };
    int granted43 = 1;
    EGLContext ctx = p_eglCreateContext(dpy, config, EGL_NO_CONTEXT, ctxAttrs);
    if (ctx == EGL_NO_CONTEXT) {
        granted43 = 0;
        ctx = p_eglCreateContext(dpy, config, EGL_NO_CONTEXT, NULL);
    }
    if (ctx == EGL_NO_CONTEXT) {
        snprintf(out, sizeof(out),
                 "no desktop GL context at all: eglCreateContext failed with 0x%04x",
                 p_eglGetError());
        goto done;
    }

    const EGLint pbufAttrs[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
    EGLSurface surf = p_eglCreatePbufferSurface(dpy, config, pbufAttrs);
    if (!p_eglMakeCurrent(dpy, surf, surf, ctx)) {
        snprintf(out, sizeof(out), "eglMakeCurrent failed: 0x%04x", p_eglGetError());
        goto done;
    }

    glGetString_t getString = (glGetString_t) p_eglGetProcAddress("glGetString");
    if (getString == NULL) getString = (glGetString_t) dlsym(egl, "glGetString");
    if (getString == NULL) {
        void *gl = open_in(libDir, "libglxshim.so", RTLD_GLOBAL | RTLD_NOW);
        if (gl != NULL) getString = (glGetString_t) dlsym(gl, "glGetString");
    }
    if (getString == NULL) {
        snprintf(out, sizeof(out),
                 "context is current (EGL %d.%d, 4.3 request %s) but glGetString could "
                 "not be resolved", major, minor, granted43 ? "granted" : "refused");
        goto done;
    }

    snprintf(out, sizeof(out),
             "EGL %d.%d\n4.3 context request: %s\nGL_VERSION: %s\nGL_RENDERER: %s\n"
             "GL_VENDOR: %s\nGLSL: %s",
             major, minor, granted43 ? "granted" : "refused, fell back to default",
             str_or(getString(GL_VERSION), "(null)"),
             str_or(getString(GL_RENDERER), "(null)"),
             str_or(getString(GL_VENDOR), "(null)"),
             str_or(getString(GL_SHADING_LANGUAGE_VERSION), "(null)"));

done:
    (*env)->ReleaseStringUTFChars(env, jLibDir, libDir);
    return (*env)->NewStringUTF(env, out);
}
