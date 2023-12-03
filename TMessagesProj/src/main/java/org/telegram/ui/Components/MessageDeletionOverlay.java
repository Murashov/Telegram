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
import java.util.Random;
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

    /*
     TODO:
     Large bitmap crash
     Artifacts
     Change ease-in
     Overlay position
     Test scheduling animation while one is already running
     Handle resize
     Only play on deletion
     Handle large size
     */
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
        @NonNull
        public final Bitmap bitmap;
        @NonNull
        public final ViewFrame[] frames;

        private AnimationConfig(@NonNull Bitmap bitmap, @NonNull ViewFrame[] frames) {
            this.bitmap = bitmap;
            this.frames = frames;
        }
    }

    private static class ViewFrame {
        @NonNull
        public final Point location;
        @NonNull
        public final Point size;

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

        public AnimationThread(SurfaceTexture surfaceTexture, int width, int height) {
            MAX_FPS = (int) AndroidUtilities.screenRefreshRate;
            MIN_DELTA = 1.0 / MAX_FPS;
            MAX_DELTA = MIN_DELTA * 4;

            this.surfaceTexture = surfaceTexture;
            this.width = width;
            this.height = height;
        }

        private void scheduleAnimation(AnimationConfig config) {
            synchronized (lock) {
                animationQueue.add(config);
                lock.notifyAll();
            }
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

        private final Object lock = new Object();

        private void loop() {
            synchronized (lock) {
                long lastTime = 0;
                while (running) {
                    if (time > ANIMATION_DURATION) {
                        while (!pollBitmap()) {
                            try {
                                lock.wait();
                            } catch (InterruptedException ignore) {
                            }
                        }
                        time = 0f;
                    }

                    final long now = System.nanoTime();
                    if (lastTime == 0) {
                        lastTime = now;
                    }
                    double deltaTime = (now - lastTime) / 1_000_000_000.;
                    lastTime = now;

                    if (deltaTime < MIN_DELTA) {
                        double wait = MIN_DELTA - deltaTime;
                        long milli = (long) (wait * 1000L);
                        int nano = (int) ((wait - milli / 1000.) * 1_000_000_000);
                        try {
                            lock.wait(milli, nano);
                        } catch (InterruptedException ignore) {
                        }
                        deltaTime = MIN_DELTA;
                    } else if (deltaTime > MAX_DELTA) {
                        deltaTime = MAX_DELTA;
                    }

                    time += deltaTime;
                    checkResize();
                    drawFrame((float) deltaTime);
                }
            }
        }
        @Override
        public void run() {
            init();
            loop();
            die();
        }

        private EGL10 egl;
        private EGLDisplay eglDisplay;
        private EGLConfig eglConfig;
        private EGLSurface eglSurface;
        private EGLContext eglContext;

        private float time = Float.MAX_VALUE;
        private final Random random = new Random();

        private int drawProgram;
        private int currentBuffer = 0;
        private int textureId = 0;
        private int[] particlesData;
        private int textureUniformHandle = 0;
        private int deltaTimeHandle = 0;
        private int timeHandle = 0;

        private static final int PARTICLE_SIZE = 6;
        private static final int HALF_SIZE = PARTICLE_SIZE / 2;
        private static final int S_FLOAT = 4;
        private static final int SIZE_POSITION = 2;
        private static final int SIZE_TEX_COORD = 2;
        private static final int SIZE_VELOCITY = 2;
        private static final int SIZE_LIFETIME = 1;
        private static final int SIZE_SEED = 1;
        private static final int SIZE_X_SHARE = 1;
        private static final int ATTRIBUTES_PER_VERTEX = SIZE_POSITION + SIZE_TEX_COORD + SIZE_VELOCITY + SIZE_LIFETIME + SIZE_SEED + SIZE_X_SHARE;
        private static final int VERTICES_PER_PARTICLE = 6;
        private static final int STRIDE = ATTRIBUTES_PER_VERTEX * S_FLOAT; // Change if non-float attrs
        private static final float MAX_SPEED = 1700f;
        private static final float UP_ACCELERATION = 300f;
        private static final float EASE_IN_DURATION = 0.8f;
        private static final float MIN_LIFETIME = 0.7f;
        private static final float MAX_LIFETIME = 1.5f;
        private static final float ANIMATION_DURATION = EASE_IN_DURATION + MAX_LIFETIME;

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
            String[] feedbackVaryings = {
                    "outPosition",
                    "outTexCoord",
                    "outVelocity",
                    "outLifetime",
                    "outSeed",
                    "outXShare"
            };
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

            GLES31.glUniform2f(
                    GLES31.glGetUniformLocation(drawProgram, "maxSpeed"),
                    MAX_SPEED / width,
                    MAX_SPEED / height
            );
            GLES31.glUniform1f(
                    GLES31.glGetUniformLocation(drawProgram, "acceleration"),
                    UP_ACCELERATION / height
            );
            GLES31.glUniform1f(
                    GLES31.glGetUniformLocation(drawProgram, "easeInDuration"),
                    EASE_IN_DURATION
            );
            GLES31.glUniform1f(
                    GLES31.glGetUniformLocation(drawProgram, "minLifetime"),
                    MIN_LIFETIME
            );
            GLES31.glUniform1f(
                    GLES31.glGetUniformLocation(drawProgram, "maxLifetime"),
                    MAX_LIFETIME
            );
        }

        private void drawFrame(float deltaTime) {
            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);

            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[currentBuffer]);
            bindAttributes();
            GLES31.glBindBufferBase(GLES31.GL_TRANSFORM_FEEDBACK_BUFFER, 0, particlesData[1 - currentBuffer]);
            bindAttributes();

            // Uniforms
            GLES31.glUniform1f(deltaTimeHandle, deltaTime);
            GLES31.glUniform1f(timeHandle, time);

            GLES31.glBeginTransformFeedback(GLES31.GL_TRIANGLES);
            GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, attributeCount);
            GLES31.glEndTransformFeedback();

            currentBuffer = 1 - currentBuffer;

            egl.eglSwapBuffers(eglDisplay, eglSurface);

            checkGlErrors();
        }

        private void bindAttributes() {
            int offset = 0;
            int index = 0;
            // Position
            offset = bindFloatAttribute(index++, 2, offset);
            // Texture
            offset = bindFloatAttribute(index++, 2, offset);
            // Velocity
            offset = bindFloatAttribute(index++, 2, offset);
            // Lifetime
            offset = bindFloatAttribute(index++, 1, offset);
            // Seed
            offset = bindFloatAttribute(index++, 1, offset);
            // X Share
            offset = bindFloatAttribute(index++, 1, offset);
        }

        private int bindFloatAttribute(int index, int size, int offset) {
            GLES31.glVertexAttribPointer(index, size, GLES31.GL_FLOAT, false, STRIDE, offset);
            GLES31.glEnableVertexAttribArray(index);
            return offset + size * S_FLOAT;
        }

        private boolean pollBitmap() {
            AnimationConfig config = animationQueue.poll();
            if (config != null) {
                genParticlesData(config.frames);
                Bitmap bitmap = config.bitmap;

                GLES31.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
                GLES31.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES31.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES31.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES31.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                int textureUnit = 0;
                GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + textureUnit);
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, textureId);
                GLES31.glUniform1i(textureUniformHandle, textureUnit);

                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);

                bitmap.recycle();
                return true;
            }
            return false;
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
            if (particlesData == null) {
                particlesData = new int[2];
                GLES31.glGenBuffers(2, particlesData, 0);
            }

            final FloatBuffer attributes = generateAttributes(frames);
            final int size = attributes.capacity() * S_FLOAT;

            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[0]);
            GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, size, attributes, GLES31.GL_DYNAMIC_DRAW);

            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[1]);
            GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, size, null, GLES31.GL_DYNAMIC_DRAW);

            currentBuffer = 0;
            checkGlErrors();
        }

        private FloatBuffer generateAttributes(ViewFrame[] frames) {
            final int particleCount = calculateParticleCount(frames);
            attributeCount = particleCount * VERTICES_PER_PARTICLE * ATTRIBUTES_PER_VERTEX;
            final int halfSize = PARTICLE_SIZE / 2;
            int i = 0;
            final float[] attributes = new float[attributeCount];
            for (ViewFrame frame : frames) {
                final int top = frame.location.y;
                final int bottom = top + frame.size.y + halfSize;
                final int left = frame.location.x;
                final int right = left + frame.size.x + halfSize;
                for (int y = top + halfSize; y < bottom; y += PARTICLE_SIZE) {
                    for (int x = left + halfSize; x < right; x += PARTICLE_SIZE) {
                        final float seed = random.nextFloat(); // TODO Test performance
                        // Top left triangle
                        // Top left
                        i = initVertex(attributes, i, x, y, -1, 1, seed);
                        // Bottom left
                        i = initVertex(attributes, i, x, y, -1, -1, seed);
                        // Top right
                        i = initVertex(attributes, i, x, y, 1, 1, seed);
                        // Bottom right triangle
                        // Bottom right
                        i = initVertex(attributes, i, x, y, 1, -1, seed);
                        // Top right
                        i = initVertex(attributes, i, x, y, 1, 1, seed);
                        // Bottom left
                        i = initVertex(attributes, i, x, y, -1, -1, seed);
                    }
                }
            }
            ByteBuffer vertexByteBuffer = ByteBuffer.allocateDirect(attributes.length * S_FLOAT);
            vertexByteBuffer.order(ByteOrder.nativeOrder());
            FloatBuffer vertexBuffer = vertexByteBuffer.asFloatBuffer();
            vertexBuffer.put(attributes);
            vertexBuffer.position(0);
            return vertexBuffer;
        }

        private int calculateParticleCount(ViewFrame frame) {
            int xCount = frame.size.x / PARTICLE_SIZE;
            if (frame.size.x % PARTICLE_SIZE != 0) {
                xCount++;
            }
            int yCount = frame.size.y / PARTICLE_SIZE;
            if (frame.size.y % PARTICLE_SIZE != 0) {
                yCount++;
            }
            return xCount * yCount;
        }

        private int calculateParticleCount(ViewFrame[] frames) {
            int count = 0;
            for (ViewFrame frame : frames) {
                count += calculateParticleCount(frame);
            }
            return count;
        }

        private int initVertex(
                float[] vertices,
                int index,
                int x,
                int y,
                int xSign,
                int ySign,
                float seed
        ) {
            // Position
            vertices[index++] = toGlX(x + xSign * HALF_SIZE);
            vertices[index++] = toGlY(y + ySign * HALF_SIZE);
            // Texture
            vertices[index++] = 0f;
            vertices[index++] = 0f;
            // Velocity
            vertices[index++] = 0f;
            vertices[index++] = 0f;
            // Lifetime
            vertices[index++] = 0f;
            // Seed
            vertices[index++] = seed;
            // X Share
            vertices[index++] = x / (float) width;
            return index;
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
