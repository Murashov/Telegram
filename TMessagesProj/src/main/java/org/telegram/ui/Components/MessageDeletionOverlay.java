package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.util.Log;

public class MessageDeletionOverlay extends FrameLayout  {
    public static String TAG = "MessageDeletionOverlay";

    public MessageDeletionOverlay(@NonNull Context context) {
        super(context);
    }

    public MessageDeletionOverlay(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MessageDeletionOverlay(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void launchAnimation(View view) {
        view.setDrawingCacheEnabled(true);
        view.buildDrawingCache();
        Bitmap bitmap = Bitmap.createBitmap(view.getDrawingCache());
        view.setDrawingCacheEnabled(false);
        Log.i(TAG, "Bitmap width: " + bitmap.getWidth());
        Log.i(TAG, "Bitmap height: " + bitmap.getHeight());
        bitmap.recycle();
    }
}
