/*
 * libcshim — satisfies librlawt.so's glibc-style NEEDED entries on Android.
 *
 * rlawt was linked on Linux and its ELF lists glibc SONAMEs:
 *   libc.so.6, libdl.so.2, libpthread.so.0, libm.so.6, librt.so.1
 * Android's bionic libc is named libc.so (no version suffix), so the linker
 * fails to resolve any of those by SONAME match.
 *
 * This shim exists with SONAME=libc.so.6 (set in Android.mk via -Wl,-soname).
 * Bionic's libc is already loaded in every Android process, so libc's
 * standard symbols (memcpy, strlen, malloc, dlopen, pthread_*, …) are
 * already available process-wide via RTLD_DEFAULT — when the linker resolves
 * rlawt's undefined libc references, they bind to bionic without us having
 * to do anything in this shim.
 *
 * The pattern is repeated for libdl, libpthread, libm, librt — see the
 * Android.mk modules. All of those are aliases of bionic libc (or built-in
 * to it) on Android, so the shims are all empty.
 */
#include <android/log.h>

__attribute__((constructor))
static void libcshim_init(void) {
    __android_log_print(ANDROID_LOG_INFO, "libcshim", "loaded — bionic libc serves the symbols");
}
