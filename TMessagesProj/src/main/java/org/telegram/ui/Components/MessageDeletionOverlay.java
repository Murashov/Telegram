package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.spoilers.SpoilerEffect2;

import java.util.List;

public class MessageDeletionOverlay extends FrameLayout  {
    public static String TAG = "MessageDeletionOverlay";
    private TextureView textureView;
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
        textureView = new TextureView(getContext());
        textureView.setSurfaceTextureListener(createSurfaceListener());
        addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    public void launchAnimation(List<View> views) {
        for (View view : views) {
            view.setDrawingCacheEnabled(true);
            view.buildDrawingCache();
            Bitmap bitmap = Bitmap.createBitmap(view.getDrawingCache());
            view.setDrawingCacheEnabled(false);
            Log.i(TAG, "Bitmap width: " + bitmap.getWidth());
            Log.i(TAG, "Bitmap height: " + bitmap.getHeight());
            bitmap.recycle();
        }
    }

    private TextureView.SurfaceTextureListener createSurfaceListener() {
        return new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (thread == null) {
                    thread = new AnimationThread(surface, width, height);
                    thread.start();
                }
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

    private class AnimationThread extends Thread {
        private final double MIN_DELTA = 1.0 / AndroidUtilities.screenRefreshRate;
        private final double MAX_DELTA = MIN_DELTA * 4;
        private final Object resizeLock = new Object();
        private final SurfaceTexture surfaceTexture;
        private int width, height;
        private boolean resize;
        private volatile boolean running = true;

        public AnimationThread(SurfaceTexture surfaceTexture, int width, int height) {
            this.surfaceTexture = surfaceTexture;
            this.width = width;
            this.height = height;
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
    }
}
