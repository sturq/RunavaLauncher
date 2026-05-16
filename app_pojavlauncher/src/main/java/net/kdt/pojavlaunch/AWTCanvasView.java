package net.kdt.pojavlaunch;

import android.content.*;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import net.kdt.pojavlaunch.utils.JREUtils;

/**
 * Hardware-accelerated AWT canvas. Reads the Cacio rgbArray frame buffer,
 * uploads it to a GLES texture, and draws it as a fullscreen quad. The CPU
 * compositing path (Bitmap.setPixels + Canvas.drawBitmap) is gone; the GPU
 * does the pixel work via a single texture-upload + textured draw.
 *
 * Still extends TextureView because backgrounding has to keep the surface
 * alive — that's the lifecycle property that fixed the JVM crash on
 * pause/resume. EGL renders into a window surface created from the
 * TextureView's SurfaceTexture; the texture stays valid while the
 * Activity is paused, so the render thread can keep ticking.
 */
public class AWTCanvasView extends TextureView implements TextureView.SurfaceTextureListener, Runnable {

    public static int AWT_CANVAS_WIDTH = 720;
    public static int AWT_CANVAS_HEIGHT = 600;
    public static volatile int AWT_VISIBLE_WIDTH = 720;
    public static volatile int AWT_VISIBLE_HEIGHT = 600;

    /** Legacy flag; the GLES path always renders opaque, the underlying
     *  window background fills any unused pixels. Kept for source compat. */
    public static boolean HIDE_FPS_OVERLAY = true;
    public static boolean TRANSPARENT_BACKGROUND = false;

    public static void setManagedScreenSize(int w, int h) {
        if (w >= 320 && h >= 240) {
            AWT_CANVAS_WIDTH = w;
            AWT_CANVAS_HEIGHT = h;
            AWT_VISIBLE_WIDTH = w;
            AWT_VISIBLE_HEIGHT = h;
        }
    }

    private boolean mIsDestroyed = false;

    public AWTCanvasView(Context ctx) { this(ctx, null); }

    public AWTCanvasView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setSurfaceTextureListener(this);
        post(this::refreshSize);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
        getSurfaceTexture().setDefaultBufferSize(
                Math.min(AWT_VISIBLE_WIDTH, AWT_CANVAS_WIDTH),
                Math.min(AWT_VISIBLE_HEIGHT, AWT_CANVAS_HEIGHT));
        mIsDestroyed = false;
        new Thread(this, "AndroidAWTRenderer").start();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        // Keep the texture alive across activity backgrounding — destroying
        // it triggers a Surface lifecycle event that historically crashed
        // the JVM. Return false → texture stays, render thread keeps running.
        return false;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
        getSurfaceTexture().setDefaultBufferSize(
                Math.min(AWT_VISIBLE_WIDTH, AWT_CANVAS_WIDTH),
                Math.min(AWT_VISIBLE_HEIGHT, AWT_CANVAS_HEIGHT));
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) { }

    /** ~16.67 ms = 60 Hz. */
    private static final long FRAME_INTERVAL_NS = 16_666_667L;

    @Override
    public void run() {
        Surface surface = new Surface(getSurfaceTexture());
        EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        int program = 0;
        int[] texIds = {0};
        int[] vboIds = {0};
        ByteBuffer pixelBuffer = null;
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay");
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw new RuntimeException("eglInitialize");
            }

            int[] configAttribs = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] == 0) {
                throw new RuntimeException("eglChooseConfig");
            }
            EGLConfig eglConfig = configs[0];

            int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE };
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig,
                    EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                throw new RuntimeException("eglCreateContext (ES3)");
            }

            int[] surfAttribs = { EGL14.EGL_NONE };
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig,
                    surface, surfAttribs, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw new RuntimeException("eglCreateWindowSurface");
            }

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw new RuntimeException("eglMakeCurrent");
            }

            program = buildProgram();
            int posLoc      = GLES30.glGetAttribLocation(program, "aPos");
            int tcLoc       = GLES30.glGetAttribLocation(program, "aTex");
            int texLoc      = GLES30.glGetUniformLocation(program, "uTex");
            int scaleLoc    = GLES30.glGetUniformLocation(program, "uTexScale");

            // Fullscreen quad: x, y, u, v. Texture origin (0,0) is top-left
            // for our purposes; flip V so y up in NDC = y down in texture.
            float[] verts = {
                    -1f,  1f, 0f, 0f,
                    -1f, -1f, 0f, 1f,
                     1f,  1f, 1f, 0f,
                     1f, -1f, 1f, 1f,
            };
            ByteBuffer vbb = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder());
            vbb.asFloatBuffer().put(verts);
            vbb.position(0);
            GLES30.glGenBuffers(1, vboIds, 0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[0]);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.length * 4, vbb, GLES30.GL_STATIC_DRAW);

            GLES30.glGenTextures(1, texIds, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texIds[0]);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                    AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);

            int pixelBytes = AWT_CANVAS_WIDTH * AWT_CANVAS_HEIGHT * 4;
            pixelBuffer = ByteBuffer.allocateDirect(pixelBytes).order(ByteOrder.nativeOrder());
            IntBuffer pixelInts = pixelBuffer.asIntBuffer();

            long nextFrameNs = System.nanoTime();
            while (!mIsDestroyed && surface.isValid()) {
                long now = System.nanoTime();
                long sleepNs = nextFrameNs - now;
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000L, (int)(sleepNs % 1_000_000L)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    nextFrameNs += FRAME_INTERVAL_NS;
                } else if (sleepNs < -FRAME_INTERVAL_NS) {
                    nextFrameNs = now + FRAME_INTERVAL_NS;
                } else {
                    nextFrameNs += FRAME_INTERVAL_NS;
                }

                int vw = Math.min(AWT_VISIBLE_WIDTH, AWT_CANVAS_WIDTH);
                int vh = Math.min(AWT_VISIBLE_HEIGHT, AWT_CANVAS_HEIGHT);
                getSurfaceTexture().setDefaultBufferSize(vw, vh);

                int[] rgbArray = JREUtils.renderAWTScreenFrame();
                if (rgbArray != null) {
                    pixelInts.clear();
                    pixelInts.put(rgbArray);
                    pixelBuffer.position(0);
                    // Stride = full canvas width, but we only upload the
                    // visible vw x vh top-left rect — GL_UNPACK_ROW_LENGTH
                    // lets GL skip the unused tail of each row.
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texIds[0]);
                    GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, AWT_CANVAS_WIDTH);
                    GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, vw, vh,
                            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuffer);
                    GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);
                }

                GLES30.glViewport(0, 0, vw, vh);
                GLES30.glClearColor(0f, 0f, 0f, 1f);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                GLES30.glUseProgram(program);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texIds[0]);
                GLES30.glUniform1i(texLoc, 0);
                GLES30.glUniform2f(scaleLoc,
                        (float) vw / AWT_CANVAS_WIDTH,
                        (float) vh / AWT_CANVAS_HEIGHT);
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[0]);
                GLES30.glEnableVertexAttribArray(posLoc);
                GLES30.glEnableVertexAttribArray(tcLoc);
                GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 16, 0);
                GLES30.glVertexAttribPointer(tcLoc, 2, GLES30.GL_FLOAT, false, 16, 8);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
                GLES30.glDisableVertexAttribArray(posLoc);
                GLES30.glDisableVertexAttribArray(tcLoc);

                EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            }
        } catch (Throwable t) {
            Log.e("AWTCanvasView", "GL render loop failed", t);
        } finally {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (program != 0) GLES30.glDeleteProgram(program);
                if (texIds[0] != 0) GLES30.glDeleteTextures(1, texIds, 0);
                if (vboIds[0] != 0) GLES30.glDeleteBuffers(1, vboIds, 0);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                EGL14.eglTerminate(eglDisplay);
            }
            surface.release();
        }
    }

    private static final String VS_SOURCE =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform vec2 uTexScale;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "    vTex = aTex * uTexScale;\n" +
            "}\n";

    /** Cacio rgbArray is ARGB ints. On the little-endian devices we target
     *  the byte order in memory is B,G,R,A. Reading as GL_RGBA gives
     *  c.r=B, c.g=G, c.b=R, c.a=A — swizzle .bgr back to actual RGB and
     *  force alpha 1.0 (we render opaque on the shared output). */
    private static final String FS_SOURCE =
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform sampler2D uTex;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(uTex, vTex);\n" +
            "    gl_FragColor = vec4(c.b, c.g, c.r, 1.0);\n" +
            "}\n";

    private static int buildProgram() {
        int vs = compileShader(GLES30.GL_VERTEX_SHADER, VS_SOURCE);
        int fs = compileShader(GLES30.GL_FRAGMENT_SHADER, FS_SOURCE);
        int prog = GLES30.glCreateProgram();
        GLES30.glAttachShader(prog, vs);
        GLES30.glAttachShader(prog, fs);
        GLES30.glLinkProgram(prog);
        int[] status = new int[1];
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == GLES30.GL_FALSE) {
            String log = GLES30.glGetProgramInfoLog(prog);
            GLES30.glDeleteProgram(prog);
            throw new RuntimeException("Link failed: " + log);
        }
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        return prog;
    }

    private static int compileShader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] status = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == GLES30.GL_FALSE) {
            String log = GLES30.glGetShaderInfoLog(s);
            GLES30.glDeleteShader(s);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return s;
    }

    /** Listener for visible-region changes; the activity uses this to forward
     *  the new size to the JVM-side agent so it can resize the JFrame. */
    public interface VisibleRegionListener {
        void onVisibleRegionChanged(int width, int height);
    }
    private static VisibleRegionListener sVisibleRegionListener;
    public static void setVisibleRegionListener(VisibleRegionListener l) {
        sVisibleRegionListener = l;
    }

    /** Recompute the visible region from the parent view's actual pixel
     *  dimensions and resize this view to fill the parent exactly. */
    public void refreshSize(){
        ViewParent vp = getParent();
        if (!(vp instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) vp;
        int pw = parent.getWidth();
        int ph = parent.getHeight();
        if (pw <= 0 || ph <= 0) {
            post(this::refreshSize);
            return;
        }
        int canvasDim = AWT_CANVAS_WIDTH;
        int newVisW, newVisH;
        if (pw >= ph) {
            newVisW = canvasDim;
            newVisH = Math.max(1, canvasDim * ph / pw);
        } else {
            newVisW = Math.max(1, canvasDim * pw / ph);
            newVisH = canvasDim;
        }
        if (newVisW != AWT_VISIBLE_WIDTH || newVisH != AWT_VISIBLE_HEIGHT) {
            AWT_VISIBLE_WIDTH = newVisW;
            AWT_VISIBLE_HEIGHT = newVisH;
            VisibleRegionListener l = sVisibleRegionListener;
            if (l != null) l.onVisibleRegionChanged(newVisW, newVisH);
        }
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        layoutParams.width = pw;
        layoutParams.height = ph;
        setLayoutParams(layoutParams);
    }
}
