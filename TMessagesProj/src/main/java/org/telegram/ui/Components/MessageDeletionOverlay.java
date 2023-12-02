package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.media.MediaScannerConnection;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLES31;
import android.opengl.GLUtils;
import android.os.Environment;
import android.util.AttributeSet;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class MessageDeletionOverlay extends TextureView {
    public static String TAG = "MessageDeletionOverlay";
    private AnimationThread thread;

    public MessageDeletionOverlay(@NonNull Context context) {
        super(context);
        init();
    }

    public MessageDeletionOverlay(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MessageDeletionOverlay(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSurfaceTextureListener(createSurfaceListener());
        setOpaque(false);
    }

    public void launchAnimation(List<View> views) {
        Bitmap atlas = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(atlas);
        int[] myLocation = new int[2];
        getLocationOnScreen(myLocation);
        ViewFrame[] frames = new ViewFrame[views.size()];
        for (int i = 0; i < views.size(); i++) {
            View view = views.get(i);
            Bitmap bitmap = getViewBitmap(view);
            int[] relativeLocation = getRelativeLocation(view, myLocation);
            int x = relativeLocation[0];
            int y = relativeLocation[1];
            frames[i] = new ViewFrame(new Point(x, y), new Point(bitmap.getWidth(), bitmap.getHeight()));
            canvas.drawBitmap(bitmap, x, y, null);
            bitmap.recycle();
        }
        thread.scheduleAnimation(new AnimationConfig(atlas, frames));
    }

    private void saveBitmap(Bitmap bitmap) {
        String filename = "telegram_bitmap.png";
        File storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File imageFile = new File(storageDir, filename);
        try {
            FileOutputStream fos = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        MediaScannerConnection.scanFile(getContext(), new String[]{imageFile.getAbsolutePath()}, null, null);
    }

    private Bitmap getViewBitmap(View view) {
        view.setDrawingCacheEnabled(true);
        view.buildDrawingCache();
        Bitmap bitmap = Bitmap.createBitmap(view.getDrawingCache());
        view.setDrawingCacheEnabled(false);
        return bitmap;
    }

    private int[] getRelativeLocation(View view, int[] myLocation) {
        int[] viewLocation = new int[2];
        view.getLocationOnScreen(viewLocation);
        viewLocation[0] -= myLocation[0];
        viewLocation[1] -= myLocation[1];
        return viewLocation;
    }

    private TextureView.SurfaceTextureListener createSurfaceListener() {
        return new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                thread = new AnimationThread(surface, getWidth(), getHeight());
                thread.start();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                if (thread != null) {
                    thread.updateSize(width, height);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (thread != null) {
                    thread.halt();
                    thread = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        };
    }

    private static class AnimationConfig {
        @NonNull public final Bitmap bitmap;
        @NonNull public final ViewFrame[] frames;

        private AnimationConfig(@NonNull Bitmap bitmap, @NonNull ViewFrame[] frames) {
            this.bitmap = bitmap;
            this.frames = frames;
        }
    }

    private static class ViewFrame {
        @NonNull public final Point location;
        @NonNull public final Point size;

        private ViewFrame(@NonNull Point location, @NonNull Point size) {
            this.location = location;
            this.size = size;
        }
    }

    private class AnimationThread extends Thread {
        private volatile boolean running = true;
        private ConcurrentLinkedQueue<AnimationConfig> animationQueue = new ConcurrentLinkedQueue<>();

        public final int MAX_FPS;
        private final double MIN_DELTA;
        private final double MAX_DELTA;

        private final SurfaceTexture surfaceTexture;
        private final Object resizeLock = new Object();
        private boolean resize;
        private int width, height;
        private int attributeCount;
        private int step = 30;
        private volatile boolean isWaiting = true;

        public AnimationThread(SurfaceTexture surfaceTexture, int width, int height) {
            MAX_FPS = (int) AndroidUtilities.screenRefreshRate;
            MIN_DELTA = 1.0 / MAX_FPS;
            MAX_DELTA = MIN_DELTA * 4;

            this.surfaceTexture = surfaceTexture;
            this.width = width;
            this.height = height;
        }

        private void scheduleAnimation(AnimationConfig config) {
            animationQueue.add(config);
            isWaiting = false;
        }

        public void updateSize(int width, int height) {
            synchronized (resizeLock) {
                resize = true;
                this.width = width;
                this.height = height;
            }
        }

        public void halt() {
            running = false;
        }

        @Override
        public void run() {
            init();
            while (isWaiting) {
                // TODO Wait
            }
            long lastTime = System.nanoTime();
            while (running) {
                final long now = System.nanoTime();
                double Δt = (now - lastTime) / 1_000_000_000.;
                lastTime = now;

                if (Δt < MIN_DELTA) {
                    double wait = MIN_DELTA - Δt;
                    try {
                        long milli = (long) (wait * 1000L);
                        int nano = (int) ((wait - milli / 1000.) * 1_000_000_000);
                        sleep(milli, nano);
                    } catch (Exception ignore) {
                    }
                    Δt = MIN_DELTA;
                } else if (Δt > MAX_DELTA) {
                    Δt = MAX_DELTA;
                }

                checkResize();
                drawFrame((float) Δt);
            }
            die();
        }

        private EGL10 egl;
        private EGLDisplay eglDisplay;
        private EGLConfig eglConfig;
        private EGLSurface eglSurface;
        private EGLContext eglContext;

        private int drawProgram;
        private int currentBuffer = 0;
        private int textureId = 0;
        private int[] particlesData;
        private int textureUniformHandle = 0;
        private int deltaTimeHandle = 0;
        private int timeHandle = 0;

        private void init() {
            egl = (EGL10) javax.microedition.khronos.egl.EGLContext.getEGL();

            eglDisplay = egl.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == egl.EGL_NO_DISPLAY) {
                running = false;
                return;
            }
            int[] version = new int[2];
            if (!egl.eglInitialize(eglDisplay, version)) {
                running = false;
                return;
            }

            int[] configAttributes = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_NONE
            };
            EGLConfig[] eglConfigs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!egl.eglChooseConfig(eglDisplay, configAttributes, eglConfigs, 1, numConfigs)) {
                running = false;
                return;
            }
            eglConfig = eglConfigs[0];

            int[] contextAttributes = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL14.EGL_NONE
            };
            eglContext = egl.eglCreateContext(eglDisplay, eglConfig, egl.EGL_NO_CONTEXT, contextAttributes);
            if (eglContext == null) {
                running = false;
                return;
            }

            eglSurface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, null);
            if (eglSurface == null) {
                running = false;
                return;
            }

            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }

            // draw program (vertex and fragment shaders)
            int vertexShader = GLES31.glCreateShader(GLES31.GL_VERTEX_SHADER);
            int fragmentShader = GLES31.glCreateShader(GLES31.GL_FRAGMENT_SHADER);
            if (vertexShader == 0 || fragmentShader == 0) {
                running = false;
                return;
            }
            GLES31.glShaderSource(vertexShader, RLottieDrawable.readRes(null, R.raw.deletion_vertex) + "\n// " + Math.random());
            GLES31.glCompileShader(vertexShader);
            int[] status = new int[1];
            GLES31.glGetShaderiv(vertexShader, GLES31.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "Compile vertex shader error: " + GLES31.glGetShaderInfoLog(vertexShader));
                GLES31.glDeleteShader(vertexShader);
                running = false;
                return;
            }
            GLES31.glShaderSource(fragmentShader, RLottieDrawable.readRes(null, R.raw.deletion_fragment) + "\n// " + Math.random());
            GLES31.glCompileShader(fragmentShader);
            GLES31.glGetShaderiv(fragmentShader, GLES31.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "Compile fragment shader error: " + GLES31.glGetShaderInfoLog(fragmentShader));
                GLES31.glDeleteShader(fragmentShader);
                running = false;
                return;
            }
            drawProgram = GLES31.glCreateProgram();
            if (drawProgram == 0) {
                running = false;
                return;
            }
            GLES31.glAttachShader(drawProgram, vertexShader);
            GLES31.glAttachShader(drawProgram, fragmentShader);
            String[] feedbackVaryings = {"outPosition"};
            GLES31.glTransformFeedbackVaryings(drawProgram, feedbackVaryings, GLES31.GL_INTERLEAVED_ATTRIBS);

            GLES31.glLinkProgram(drawProgram);
            GLES31.glGetProgramiv(drawProgram, GLES31.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "Link draw program error: " + GLES31.glGetProgramInfoLog(drawProgram));
                running = false;
                return;
            }

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];

            GLES31.glViewport(0, 0, width, height);
            GLES31.glEnable(GLES31.GL_BLEND);
            GLES31.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES31.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

            GLES31.glUseProgram(drawProgram);

            textureUniformHandle = GLES31.glGetUniformLocation(drawProgram, "uTexture");
            deltaTimeHandle = GLES31.glGetUniformLocation(drawProgram, "deltaTime");
            timeHandle = GLES31.glGetUniformLocation(drawProgram, "time");
        }

        private float t;
        private final float timeScale = .65f;

        private void drawFrame(float Δt) {
            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }

            t += Δt * timeScale;
            if (t > 1000.f) {
                t = 0;
            }

            drawView();
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[currentBuffer]);
            GLES31.glVertexAttribPointer(0, 2, GLES31.GL_FLOAT, false, 8, 0); // Position (vec2)
            GLES31.glEnableVertexAttribArray(0);
            GLES31.glBindBufferBase(GLES31.GL_TRANSFORM_FEEDBACK_BUFFER, 0, particlesData[1 - currentBuffer]);
            GLES31.glVertexAttribPointer(0, 2, GLES31.GL_FLOAT, false, 8, 0); // Position (vec2)
            GLES31.glEnableVertexAttribArray(0);

            GLES31.glUniform1f(deltaTimeHandle, Δt * timeScale);
            GLES31.glUniform1f(timeHandle, t);

            GLES31.glBeginTransformFeedback(GLES31.GL_TRIANGLES);
            GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, attributeCount);
            GLES31.glEndTransformFeedback();

            currentBuffer = 1 - currentBuffer;

            egl.eglSwapBuffers(eglDisplay, eglSurface);

            checkGlErrors();
        }

        private void drawView() {
            AnimationConfig config = animationQueue.poll();
            // TODO Handle reset on new animation
            if (config != null) {
                genParticlesData(config.frames);
                Bitmap bitmap = config.bitmap;

                GLES31.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
                GLES31.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES31.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES31.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES31.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);

                bitmap.recycle();
            }
            int textureUnit = 0;
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + textureUnit);
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, textureId);
            GLES31.glUniform1i(textureUniformHandle, textureUnit);
        }

        private void die() {
            if (particlesData != null) {
                try {
                    GLES31.glDeleteBuffers(2, particlesData, 0);
                } catch (Exception e) {
                    Log.e(TAG, e.toString());
                }
                particlesData = null;
            }
            if (drawProgram != 0) {
                try {
                    GLES31.glDeleteProgram(drawProgram);
                } catch (Exception e) {
                    Log.e(TAG, e.toString());
                }
                drawProgram = 0;
            }
            if (egl != null) {
                try {
                    egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                } catch (Exception e) {
                    Log.e(TAG, e.toString());
                }
                try {
                    egl.eglDestroySurface(eglDisplay, eglSurface);
                } catch (Exception e) {
                    Log.e(TAG, e.toString());
                }
                try {
                    egl.eglDestroyContext(eglDisplay, eglContext);
                } catch (Exception e) {
                    Log.e(TAG, e.toString());
                }
            }
            try {
                surfaceTexture.release();
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }

            checkGlErrors();
        }

        private void checkResize() {
            synchronized (resizeLock) {
                if (resize) {
                    GLES31.glViewport(0, 0, width, height);
                    // TODO Handle resizing
//                    this.attributeCount = particlesCount();
//                    genParticlesData();
                    resize = false;
                }
            }
        }

        private void genParticlesData(ViewFrame[] frames) {
            if (particlesData != null) {
                GLES31.glDeleteBuffers(2, particlesData, 0);
            }

            particlesData = new int[2];
            GLES31.glGenBuffers(2, particlesData, 0);

            final FloatBuffer coordinates = generateCoordinates(frames);
            final int size = coordinates.capacity() * 4;

            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[0]);
            GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, size, coordinates, GLES31.GL_DYNAMIC_DRAW);

            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[1]);
            GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, size, coordinates, GLES31.GL_DYNAMIC_DRAW);

            checkGlErrors();
        }

        private FloatBuffer generateCoordinates(ViewFrame[] frames) {
            final int pointCount = 6;
            final int coordinateCount = 2;
            final int size = ((width * height) / step) * coordinateCount * pointCount;
            final int halfStep = step / 2;
            int i = 0;
            final float[] tempArray = new float[size]; // TODO pre-calculate the size
            for (ViewFrame frame : frames) {
                final int top = frame.location.y;
                final int bottom = top + frame.size.y;
                final int left = frame.location.x;
                final int right = left + frame.size.x;
                for (int y = top + halfStep; y < bottom; y += step) {
                    for (int x = left + halfStep; x < right; x += step) {
                        // Top left triangle
                        // Top left
                        tempArray[i++] = toGlX(x - halfStep);
                        tempArray[i++] = toGlY(y + halfStep);
                        // Bottom left
                        tempArray[i++] = toGlX(x - halfStep);
                        tempArray[i++] = toGlY(y - halfStep);
                        // Top right
                        tempArray[i++] = toGlX(x + halfStep);
                        tempArray[i++] = toGlY(y + halfStep);
                        // Bottom right triangle
                        // Bottom right
                        tempArray[i++] = toGlX(x + halfStep);
                        tempArray[i++] = toGlY(y - halfStep);
                        // Top right
                        tempArray[i++] = toGlX(x + halfStep);
                        tempArray[i++] = toGlY(y + halfStep);
                        // Bottom left
                        tempArray[i++] = toGlX(x - halfStep);
                        tempArray[i++] = toGlY(y - halfStep);

                        Log.i(TAG, "X=" + x + "Y=" + y);
                    }
                }
            }
            attributeCount = i;
            final float[] resultArray = new float[attributeCount];
            System.arraycopy(tempArray, 0, resultArray, 0, attributeCount);
            ByteBuffer vertexByteBuffer = ByteBuffer.allocateDirect(resultArray.length * 4);
            vertexByteBuffer.order(ByteOrder.nativeOrder());
            FloatBuffer vertexBuffer = vertexByteBuffer.asFloatBuffer();
            vertexBuffer.put(resultArray);
            vertexBuffer.position(0);
            return vertexBuffer;
        }

        private float toGlX(int x) {
            final float xShare = x / (float) width;
            return (xShare - 0.5f) * 2f;
        }

        private float toGlY(int y) {
            final float yShare = y / (float) height;
            return (yShare - 0.5f) * -2f;
        }

        private void checkGlErrors() {
            int err;
            while ((err = GLES31.glGetError()) != GLES31.GL_NO_ERROR) {
                Log.e(TAG, "gles error " + err);
            }
        }
    }
}
