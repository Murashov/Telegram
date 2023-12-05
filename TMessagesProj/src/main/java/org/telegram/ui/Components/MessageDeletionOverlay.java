package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLES31;
import android.opengl.GLUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Cells.ChatMessageCell;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
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

    public void stopAndClear() {
        if (thread != null) {
            thread.stopAndClear();
        }
    }

    private void init() {
        setSurfaceTextureListener(createSurfaceListener());
        setOpaque(false);
    }

    /*
     TODO:
     Check android version
     Draw group background
     */
    public void launchAnimation(List<ChatMessageCell> cells) {
        Bitmap atlas = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        if (atlas == null) {
            return;
        }
        Canvas canvas = new Canvas(atlas);
        int[] myLocation = new int[2];
        getLocationOnScreen(myLocation);
        List<ViewFrame> frames = new ArrayList<>(cells.size());
        for (ChatMessageCell cell : cells) {
            Bitmap bitmap = getCellBitmap(cell);
            if (bitmap == null) {
                continue;
            }
            int[] relativeLocation = getRelativeLocation(cell, myLocation);
            int x = relativeLocation[0];
            int y = relativeLocation[1];
            frames.add(new ViewFrame(new Point(x, y), new Point(bitmap.getWidth(), bitmap.getHeight())));

            canvas.drawBitmap(bitmap, x, y, null);
            bitmap.recycle();
        }
        thread.scheduleAnimation(new AnimationConfig(atlas, frames));
    }

    @Nullable
    private Bitmap getCellBitmap(ChatMessageCell cell) {
        Bitmap bitmap = Bitmap.createBitmap(cell.getWidth(), cell.getHeight(), Bitmap.Config.ARGB_8888);
        if (bitmap == null) {
            return null;
        }
        Canvas canvas = new Canvas(bitmap);
        if (cell.drawBackgroundInParent()) {
            cell.drawBackgroundInternal(canvas, true);
        }
        cell.draw(canvas);
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
        public final List<ViewFrame> frames;

        private AnimationConfig(@NonNull Bitmap bitmap, @NonNull List<ViewFrame> frames) {
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

    private static class AnimationThread extends Thread {
        private volatile boolean running = true;
        private volatile boolean shouldStop = false;
        private final ConcurrentLinkedQueue<AnimationConfig> animationQueue = new ConcurrentLinkedQueue<>();
        private final SurfaceTexture surfaceTexture;
        private final Object resizeLock = new Object();
        private boolean resize;
        private int width, height;
        private int particleCount;

        public AnimationThread(SurfaceTexture surfaceTexture, int width, int height) {
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

        public void stopAndClear() {
            shouldStop = true;
        }

        public void halt() {
            running = false;
        }

        private void loop() {
            synchronized (lock) {
                long lastTime = 0;
                while (running) {
                    if (shouldStop) {
                        Log.i(TAG, "Stopped");
                        time = Float.MAX_VALUE;
                    }
                    if (time > ANIMATION_DURATION) {
                        Log.i(TAG, "Clearing screen. Time=" + time);
                        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);
                        egl.eglSwapBuffers(eglDisplay, eglSurface);
                        while (!hasNewAnimation()) {
                            try {
                                lock.wait();
                            } catch (InterruptedException ignore) {
                            }
                        }
                        shouldStop = false;
                    }
                    if (pollAnimation()) {
                        Log.i(TAG, "New animation scheduled");
                        time = 0f;
                        lastGenerationTime = 0.0;
                        isAdjustmentPhase = true;
                        isFirstFrame = true;
                        lastTime = System.nanoTime();
                    }

                    final long now = System.nanoTime();
                    double deltaTime = (now - lastTime) / 1_000_000_000.;
                    lastTime = now;
//                    Log.i(TAG, "Delta time=" + deltaTime);

                    if (deltaTime < MIN_DELTA && !isFirstFrame) {
                        double wait = MIN_DELTA - deltaTime;
                        long milli = (long) (wait * 1000L);
                        int nano = (int) ((wait - milli / 1000.) * 1_000_000_000);
                        try {
                            lock.wait(milli, nano);
                        } catch (InterruptedException ignore) {
                        }
                        deltaTime = MIN_DELTA;
                    } else if (deltaTime > MAX_DELTA && isAdjustmentPhase) {
                        double adjustedForGeneration = deltaTime - lastGenerationTime;
                        if (adjustedForGeneration > MAX_DELTA && particleSize < maxPointSize) {
                            Log.i(TAG, "Adjusted Delta more than max delta:" + adjustedForGeneration);
                            maxPointCount = (int) (particleCount / 1.5);
                            Log.i(TAG, "Generating buffer capped at " + maxPointCount);
                            lastGenerationTime = genParticlesData(currentFrames);
                        } else {
                            Log.i(TAG, "Adjustment phase finished");
                            lastGenerationTime = 0.0;
                            isAdjustmentPhase = false;
                        }
                    }

                    time += deltaTime;
                    checkResize();
                    drawFrame((float) deltaTime);
                    isFirstFrame = false;
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

        private static final int maxPointSize = AndroidUtilities.dp2(8f) * 2;
        private final int visibleSize = Math.max(1, AndroidUtilities.dp2(1f) * 2);
        private float time = Float.MAX_VALUE;
        private final Random random = new Random();
        private int particleSize = visibleSize;
        private final PointF localPointSize = new PointF(0f, 0f);
        private int maxPointCount = getMaxPointCountCeiling();
        private final Object lock = new Object();
        private double lastGenerationTime = 0.0;
        private boolean isAdjustmentPhase = false;
        private boolean isFirstFrame = false;

        private int drawProgram;
        private int currentBuffer = 0;
        private int textureId = 0;
        private int[] particlesData;
        private int textureUniformHandle = 0;
        private int deltaTimeHandle = 0;
        private int timeHandle = 0;
        private int pointSizeHandle = 0;
        private int localPointSizeHandle = 0;
        private int maxSpeedHandle = 0;
        private int accelerationHandle = 0;

        private static final double MIN_DELTA = 1.0 / AndroidUtilities.screenRefreshRate;
        private static final double MAX_DELTA = MIN_DELTA * 2f;
        private static final int S_FLOAT = 4;
        private static final int SIZE_POSITION = 2;
        private static final int SIZE_TEX_COORD = 2;
        private static final int SIZE_VELOCITY = 2;
        private static final int SIZE_LIFETIME = 1;
        private static final int SIZE_SEED = 1;
        private static final int SIZE_X_SHARE = 1;
        private static final int ATTRIBUTES_PER_VERTEX = SIZE_POSITION + SIZE_TEX_COORD + SIZE_VELOCITY + SIZE_LIFETIME + SIZE_SEED + SIZE_X_SHARE;
        private static final int VERTICES_PER_PARTICLE = 1;
        private static final int STRIDE = ATTRIBUTES_PER_VERTEX * S_FLOAT; // Change if non-float attrs
        private static final float TIME_SCALE = 1f;
        private static final float MAX_SPEED = 3000 * TIME_SCALE;
        private static final float UP_ACCELERATION = 600 * TIME_SCALE;
        private static final float EASE_IN_DURATION = 1.0f / TIME_SCALE;
        private static final float MIN_LIFETIME = 0.5f / TIME_SCALE;
        private static final float MAX_LIFETIME = 1.3f / TIME_SCALE;
        private static final float ANIMATION_DURATION = EASE_IN_DURATION + MAX_LIFETIME;

        private static int getMaxPointCountCeiling() {
            switch (SharedConfig.getDevicePerformanceClass()) {
                case SharedConfig.PERFORMANCE_CLASS_HIGH:
                    return 32768;
                case SharedConfig.PERFORMANCE_CLASS_AVERAGE:
                    return 16384;
                default:
                case SharedConfig.PERFORMANCE_CLASS_LOW:
                    return 8192;
            }
        }

        private void init() {
            egl = (EGL10) EGLContext.getEGL();

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

            GLES31.glUseProgram(drawProgram);

            GLES31.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            textureUniformHandle = GLES31.glGetUniformLocation(drawProgram, "uTexture");
            deltaTimeHandle = GLES31.glGetUniformLocation(drawProgram, "deltaTime");
            timeHandle = GLES31.glGetUniformLocation(drawProgram, "time");
            pointSizeHandle = GLES31.glGetUniformLocation(drawProgram, "pointSize");
            localPointSizeHandle = GLES31.glGetUniformLocation(drawProgram, "localPointSize");
            maxSpeedHandle = GLES31.glGetUniformLocation(drawProgram, "maxSpeed");
            accelerationHandle = GLES31.glGetUniformLocation(drawProgram, "acceleration");

            GLES31.glUniform2f(maxSpeedHandle, MAX_SPEED / width, MAX_SPEED / height);
            GLES31.glUniform1f(accelerationHandle, UP_ACCELERATION / height);
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
            GLES31.glUniform1f(
                    GLES31.glGetUniformLocation(drawProgram, "visibleSize"),
                    visibleSize
            );
        }

        private void drawFrame(float deltaTime) {
            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);
            // Uniforms
            GLES31.glUniform1f(deltaTimeHandle, deltaTime);
            GLES31.glUniform1f(timeHandle, time);
            GLES31.glUniform1f(pointSizeHandle, particleSize);
            GLES31.glUniform2f(localPointSizeHandle, localPointSize.x, localPointSize.y);

            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[currentBuffer]);
            bindAttributes();

            GLES31.glBindBufferBase(GLES31.GL_TRANSFORM_FEEDBACK_BUFFER, 0, particlesData[1 - currentBuffer]);
            int mode = GLES31.GL_POINTS;
            GLES31.glBeginTransformFeedback(mode);
            GLES31.glDrawArraysInstanced(mode, 0, 1, particleCount);
            GLES31.glEndTransformFeedback();

            currentBuffer = 1 - currentBuffer;

            egl.eglSwapBuffers(eglDisplay, eglSurface);

            checkGlErrors();
        }

        private void bindAttributes() {
            int offset = 0;
            int index = 0;
            // Position
            offset = bindFloatAttribute(index++, SIZE_POSITION, offset);
            // Texture
            offset = bindFloatAttribute(index++, SIZE_TEX_COORD, offset);
            // Velocity
            offset = bindFloatAttribute(index++, SIZE_VELOCITY, offset);
            // Lifetime
            offset = bindFloatAttribute(index++, SIZE_LIFETIME, offset);
            // Seed
            offset = bindFloatAttribute(index++, SIZE_SEED, offset);
            // X Share
            offset = bindFloatAttribute(index++, SIZE_X_SHARE, offset);
        }

        private int bindFloatAttribute(int index, int size, int offset) {
            GLES31.glVertexAttribPointer(index, size, GLES31.GL_FLOAT, false, STRIDE, offset);
            GLES31.glEnableVertexAttribArray(index);
            GLES31.glVertexAttribDivisor(index, 1);
            return offset + size * S_FLOAT;
        }

        private List<ViewFrame> currentFrames = new ArrayList<>(0);
        private boolean pollAnimation() {
            AnimationConfig config = animationQueue.poll();
            if (config != null) {
                maxPointCount = getMaxPointCountCeiling();
                currentFrames = config.frames;
                genParticlesData(currentFrames);
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

        private boolean hasNewAnimation() {
            return !animationQueue.isEmpty();
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
                    GLES31.glUniform2f(maxSpeedHandle, MAX_SPEED / width, MAX_SPEED / height);
                    GLES31.glUniform1f(accelerationHandle, UP_ACCELERATION / height);
                    resize = false;
                }
            }
        }

        private double genParticlesData(List<ViewFrame> frames) {
            long now = System.currentTimeMillis();
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
            return (System.currentTimeMillis() - now) / 1000.0;
        }

        private FloatBuffer generateAttributes(List<ViewFrame> frames) {
            final Pair<Integer, Integer> countAndSize = calculateParticleCountAndSize(frames);
            particleCount = countAndSize.first;
            particleSize = countAndSize.second;
            localPointSize.set(particleSize / (float) width, particleSize / (float) height);
            Log.i(TAG, "Number of particles=" + particleCount);
            Log.i(TAG, "Particle size=" + particleSize);

            int size = particleCount * VERTICES_PER_PARTICLE * ATTRIBUTES_PER_VERTEX;
            final int halfSize = particleSize / 2;
            int i = 0;
            final float[] attributes = new float[size];
            for (ViewFrame frame : frames) {
                final int top = frame.location.y;
                final int bottom = top + frame.size.y + halfSize;
                final int left = frame.location.x;
                final int right = left + frame.size.x + halfSize;
                for (int y = top + halfSize; y < bottom; y += particleSize) {
                    for (int x = left + halfSize; x < right; x += particleSize) {
                        final float seed = random.nextFloat();
                        i = initVertex(attributes, i, x, y, seed);
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

        private static int calculateParticleCountAndSize(ViewFrame frame, int particleSize) {
            int xCount = frame.size.x / particleSize;
            if (frame.size.x % particleSize != 0) {
                xCount++;
            }
            int yCount = frame.size.y / particleSize;
            if (frame.size.y % particleSize != 0) {
                yCount++;
            }
            return xCount * yCount;
        }

        private Pair<Integer, Integer> calculateParticleCountAndSize(List<ViewFrame> frames) {
            int size = visibleSize - 2;
            int count;
            do {
                count = 0;
                size += 2;
                for (ViewFrame frame : frames) {
                    count += calculateParticleCountAndSize(frame, size);
                }
            } while (count > maxPointCount && size < maxPointSize);

            return new Pair<>(count, size);
        }

        private int initVertex(
                float[] vertices,
                int index,
                int x,
                int y,
                float seed
        ) {
            // Position
            vertices[index++] = toGlX(x);
            vertices[index++] = toGlY(y);
            // Texture
            vertices[index++] = 0f;
            vertices[index++] = 0f;
            // Velocity
            vertices[index++] = 0f;
            vertices[index++] = 0f;
            // Lifetime
            vertices[index++] = -1f;
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

        private static void checkGlErrors() {
            int err;
            while ((err = GLES31.glGetError()) != GLES31.GL_NO_ERROR) {
                Log.e(TAG, "gles error " + err);
            }
        }
    }
}
