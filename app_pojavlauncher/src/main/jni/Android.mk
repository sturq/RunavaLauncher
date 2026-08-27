LOCAL_PATH := $(call my-dir)
HERE_PATH := $(LOCAL_PATH)
# include $(HERE_PATH)/crash_dump/libbase/Android.mk
# include $(HERE_PATH)/crash_dump/libbacktrace/Android.mk
# include $(HERE_PATH)/crash_dump/debuggerd/Android.mk


LOCAL_PATH := $(HERE_PATH)

$(call import-module,prefab/bytehook)
LOCAL_PATH := $(HERE_PATH)

include $(CLEAR_VARS)
# Link GLESv2 for test
LOCAL_LDLIBS := -ldl -llog -landroid
# -lGLESv2
LOCAL_MODULE := pojavexec
# LOCAL_CFLAGS += -DDEBUG
# -DGLES_TEST
LOCAL_SRC_FILES := \
    bigcoreaffinity.c \
    egl_bridge.c \
    ctxbridges/loader_dlopen.c \
    ctxbridges/gl_bridge.c \
    ctxbridges/osm_bridge.c \
    ctxbridges/egl_loader.c \
    ctxbridges/osmesa_loader.c \
    ctxbridges/swap_interval_no_egl.c \
    environ/environ.c \
    jvm_hooks/emui_iterator_fix_hook.c \
    jvm_hooks/java_exec_hooks.c \
    jvm_hooks/lwjgl_dlopen_hook.c \
    input_bridge_v3.c \
    jre_launcher.c \
    runava_gl_probe.c \
    utils.c \
    stdio_is.c \
    driver_helper/nsbypass.c

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_CFLAGS += -DADRENO_POSSIBLE
endif
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := exithook
LOCAL_LDLIBS := -ldl -llog
LOCAL_SHARED_LIBRARIES := bytehook pojavexec
LOCAL_SRC_FILES := \
    native_hooks/exit_hook.c \
    native_hooks/chmod_hook.c
include $(BUILD_SHARED_LIBRARY)

#ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
include $(CLEAR_VARS)
LOCAL_MODULE := linkerhook
LOCAL_SRC_FILES := driver_helper/hook.c
LOCAL_LDFLAGS := -z global
include $(BUILD_SHARED_LIBRARY)
#endif

include $(CLEAR_VARS)
LOCAL_MODULE := pojavexec_awt
# -landroid for ANativeWindow: the AWT frame is written straight into the
# surface buffer rather than round-tripped through a Bitmap and a Canvas.
LOCAL_LDLIBS := -landroid -llog
LOCAL_SRC_FILES := \
    awt_bridge.c
include $(BUILD_SHARED_LIBRARY)

# AAudio passthrough for the RunavaAudio javax.sound.sampled MixerProvider.
# librunava_audio.so is dlopen'd lazily from RunavaSourceDataLine.ensureNative,
# not as part of the main JVM startup path, so a missing libaaudio.so (e.g.
# Android < 26) only breaks audio playback, not the rest of the launcher.
include $(CLEAR_VARS)
LOCAL_MODULE := runava_audio
LOCAL_LDLIBS := -llog -ldl
LOCAL_SRC_FILES := \
    runava_audio.c
include $(BUILD_SHARED_LIBRARY)

# Helper to get current thread
# include $(CLEAR_VARS)
# LOCAL_MODULE := thread64helper
# LOCAL_SRC_FILES := thread_helper.cpp
# include $(BUILD_SHARED_LIBRARY)

# fake lib for linker
include $(CLEAR_VARS)
LOCAL_MODULE := awt_headless
include $(BUILD_SHARED_LIBRARY)

# libawt_xawt without X11, used to get Caciocavallo working
LOCAL_PATH := $(HERE_PATH)/awt_xawt
include $(CLEAR_VARS)
LOCAL_MODULE := awt_xawt
# LOCAL_CFLAGS += -DHEADLESS
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)
LOCAL_SHARED_LIBRARIES := awt_headless
LOCAL_SRC_FILES := xawt_fake.c
include $(BUILD_SHARED_LIBRARY)

# delete fake libs after linked
$(info $(shell (rm $(HERE_PATH)/../jniLibs/*/libawt_headless.so)))

# NOTE: rlawt SONAME shims (libGL.so.1, libc.so.6, libdl.so.2, etc.) were
# previously built here to satisfy librlawt.so's NEEDED entries when we
# attempted to enable RuneLite's GPU plugin. The GPU plugin still won't
# work on Android (rlawt's actual GL calls go through GLX/X11 which we
# can't emulate), so the shims accomplished nothing beyond startup cost
# (seven dlopens + libmobileglues.so pull-in via GLshim's constructor).
# Removed. Source files in libGLshim/ and libcshim/ are kept on disk in
# case GPU work resumes — see RuneLiteGameActivity history.
LOCAL_PATH := $(HERE_PATH)

