/*
 * Does this device give us a desktop GL context good enough for RuneLite's GPU
 * plugin?
 *
 * The plugin wants OpenGL 4.3, because its scene upload runs in compute
 * shaders. Everything needed to answer that is already in the APK: zink over
 * Vulkan for desktop GL, libglxshim.so for the GLX entry points the plugin's
 * native half calls, and Mesa's EGL. This brings that stack up against the
 * game's own SurfaceView and reports what it actually got, so the port can be
 * decided on a measurement rather than on the strings in the APK.
 *
 * Nothing here is part of the render path. It runs once, from a menu entry.
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <environ/environ.h>

/* egl_bridge.c. pojavInit is deliberately not used: it opens by attaching to
   pojav_environ->runtimeJavaVMPtr, which is only ever set when the JVM itself
   loads libpojavexec. That happens on the Minecraft path, where LWJGL's GLFW
   stub pulls it in, and never on the RuneLite one, so the pointer is null and
   the attach dereferences it. A GL context does not need the JVM anyway. */
extern int pojavInitOpenGL(void);
extern void *pojavCreateContext(void *contextSrc);
extern void pojavMakeCurrent(void *window);

/* Called directly rather than through the bridge's function-pointer table, so a
   failure can be attributed to a step instead of just yielding a null context. */
#include <ctxbridges/gl_bridge.h>

#define GL_VENDOR                   0x1F00
#define GL_RENDERER                 0x1F01
#define GL_VERSION                  0x1F02
#define GL_SHADING_LANGUAGE_VERSION 0x8B8C

typedef const char *(*glGetString_t)(unsigned int name);

static const char *str_or(const char *s, const char *fallback) {
    return (s != NULL && s[0] != '\0') ? s : fallback;
}

JNIEXPORT jstring JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_probeDesktopGL(JNIEnv *env, jclass clazz) {
    char out[1024];

    const char *renderer = getenv("AMETHYST_RENDERER");
    if (renderer == NULL) {
        return (*env)->NewStringUTF(env,
            "AMETHYST_RENDERER is unset, so the bridge would dereference a null "
            "renderer name. The activity has to pick a renderer before the JVM starts.");
    }

    if (pojav_environ->pojavWindow == NULL) {
        return (*env)->NewStringUTF(env,
            "no surface: setupBridgeWindow has not been called yet");
    }

    /* The part of pojavInit that actually matters here. */
    ANativeWindow_acquire(pojav_environ->pojavWindow);
    pojav_environ->savedWidth = ANativeWindow_getWidth(pojav_environ->pojavWindow);
    pojav_environ->savedHeight = ANativeWindow_getHeight(pojav_environ->pojavWindow);
    ANativeWindow_setBuffersGeometry(pojav_environ->pojavWindow,
                                     pojav_environ->savedWidth,
                                     pojav_environ->savedHeight,
                                     AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
    pojavInitOpenGL();

    /* pojavInitOpenGL calls this too and ignores the result, which is how a
       failed EGL setup turns into a null context two calls later. */
    if (!gl_init()) {
        snprintf(out, sizeof(out),
                 "gl_init failed: EGL never came up (renderer=%s, egl=%s)",
                 renderer, str_or(getenv("POJAVEXEC_EGL"), "unset"));
        return (*env)->NewStringUTF(env, out);
    }
    gl_setup_window();

    gl_render_window_t *ctx = gl_init_context(NULL);
    if (ctx == NULL) {
        snprintf(out, sizeof(out),
                 "EGL is up but no context: gl_init_context failed "
                 "(renderer=%s, LIBGL_ES=%s). Check E/jrelog for the EGL error code.",
                 renderer, str_or(getenv("LIBGL_ES"), "unset"));
        return (*env)->NewStringUTF(env, out);
    }
    gl_make_current(ctx);

    /* The renderer library is already dlopen'd by loadGraphicsLibrary, so its
       GL entry points are resolvable in this process. */
    glGetString_t getString = (glGetString_t) dlsym(RTLD_DEFAULT, "glGetString");
    if (getString == NULL) {
        snprintf(out, sizeof(out),
                 "context is current but glGetString is not resolvable: %s",
                 str_or(dlerror(), "no dlerror"));
        return (*env)->NewStringUTF(env, out);
    }

    snprintf(out, sizeof(out),
             "renderer arg: %s\nGL_VERSION: %s\nGL_RENDERER: %s\nGL_VENDOR: %s\nGLSL: %s",
             renderer,
             str_or(getString(GL_VERSION), "(null)"),
             str_or(getString(GL_RENDERER), "(null)"),
             str_or(getString(GL_VENDOR), "(null)"),
             str_or(getString(GL_SHADING_LANGUAGE_VERSION), "(null)"));
    return (*env)->NewStringUTF(env, out);
}
