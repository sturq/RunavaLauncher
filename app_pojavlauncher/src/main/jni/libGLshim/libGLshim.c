/*
 * libGLshim — satisfies librlawt.so's NEEDED libGL.so.1 dependency on Android.
 *
 * Built with -Wl,-soname,libGL.so.1 so when the Android dynamic linker
 * resolves librlawt's NEEDED list, it finds this lib by SONAME (regardless
 * of the file's on-disk name). On load we dlopen libmobileglues.so with
 * RTLD_GLOBAL so its desktop-GL implementation symbols (glClear,
 * glDrawArrays, glGenBuffers, etc.) become visible process-wide. rlawt's
 * undefined GL references then bind to libmobileglues.
 *
 * Falls back to libng_gl4es.so if libmobileglues isn't present (older
 * Pojav builds).
 */
#include <dlfcn.h>
#include <stdio.h>
#include <android/log.h>

#define TAG "libGLshim"

__attribute__((constructor))
static void libGLshim_init(void) {
    void *h = dlopen("libmobileglues.so", RTLD_NOW | RTLD_GLOBAL);
    const char *which = "libmobileglues.so";
    if (!h) {
        h = dlopen("libng_gl4es.so", RTLD_NOW | RTLD_GLOBAL);
        which = "libng_gl4es.so";
    }
    if (h) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
                "loaded GL backend %s -> %p", which, h);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                "failed to load GL backend: %s", dlerror());
    }
}
