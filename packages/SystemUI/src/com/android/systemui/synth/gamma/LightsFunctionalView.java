/*
* Copyright (C) 2019 The OmniROM Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 2 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*
*/
package com.android.systemui.synth.gamma;

import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.app.WallpaperInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.LightingColorFilter;
import android.media.MediaMetadata;
import android.media.audiofx.Visualizer;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import androidx.palette.graphics.Palette;

import com.android.settingslib.Utils;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.R;

public class LightsFunctionalView extends RelativeLayout {
    private static final boolean DEBUG = true;
    private static final String TAG = "LightsFunctionalView";
    private ValueAnimator mLightAnimator;
    private WallpaperManager mWallManager;
    private Visualizer mVisualizer;

    private Context mContext;
    private NotificationMediaManager mMediaManager;
    private MediaController mMediaController;

    private SettingsObserver mObserver;

    private ValueAnimator[] mValueAnimators;
    private float[] mFFTPoints;
    private float rightViewProgress;
    private float leftViewProgress;
    private int mUnits = 128;
    private float mDbFuzzFactor = 16f;
    private int mWidth, mHeight;
    private int mOpacity = 255;

    private Visualizer.OnDataCaptureListener mVisualizerListener =
            new Visualizer.OnDataCaptureListener() {
        byte rfk, ifk;
        int dbValue;
        float magnitude;

        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] bytes, int samplingRate) {
        }

        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
            for (int i = 0; i < mUnits; i++) {
                mValueAnimators[i].cancel();
                rfk = fft[i * 2 + 2];
                ifk = fft[i * 2 + 3];
                magnitude = rfk * rfk + ifk * ifk;
                dbValue = magnitude > 0 ? (int) (10 * Math.log10(magnitude)) : 0;

                mValueAnimators[i].setFloatValues(mFFTPoints[i * 4 + 1],
                        mFFTPoints[3] - (dbValue * mDbFuzzFactor));
                mValueAnimators[i].start();
            }
        }
    };

    private final Runnable mLinkVisualizer = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) {
                Log.w(TAG, "+++ mLinkVisualizer run()");
            }

            try {
                if (mVisualizer == null) {
                    mVisualizer = new Visualizer(0);
                }
            } catch (Exception e) {
                Log.e(TAG, "error initializing visualizer", e);
                return;
            }

            try {
                mVisualizer.setEnabled(false);
                mVisualizer.setCaptureSize(66);
                mVisualizer.setDataCaptureListener(mVisualizerListener,Visualizer.getMaxCaptureRate(),
                        false, true);
                mVisualizer.setEnabled(true);
            } catch (Exception e) {
                Log.e(TAG, "error initializing visualizer", e);
                return;
            }

            if (DEBUG) {
                Log.w(TAG, "--- mLinkVisualizer run()");
            }
        }
    };

    private final Runnable mUnlinkVisualizer = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) {
                Log.w(TAG, "+++ mUnlinkVisualizer run(), mVisualizer: " + mVisualizer);
            }

            if (mVisualizer != null) {
                mVisualizer.setEnabled(false);
                mVisualizer.release();
                mVisualizer = null;
            }

            if (DEBUG) {
                Log.w(TAG, "--- mUninkVisualizer run()");
            }
        }
    };

    public LightsFunctionalView(Context context) {
        this(context, null);
    }

    public LightsFunctionalView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LightsFunctionalView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public LightsFunctionalView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        if (DEBUG) Log.d(TAG, "new");
        mContext = context;

        mFFTPoints = new float[mUnits * 4];
        mHeight = getHeight();
        mWidth = getWidth();

        loadValueAnimators();
        setPortraitPoints();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mObserver = new SettingsObserver(new Handler());
        mObserver.observe();
        mObserver.update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mObserver.unobserve();
        mObserver = null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w > 0 && mWidth != w) {
            mWidth = w;
        }
        if (h > 0 && mHeight != h) {
            mHeight = h;
        }
        loadValueAnimators();
        setPortraitPoints();

        setLoopAnimation();

        super.onSizeChanged(mWidth, mHeight, oldw, oldh);

    }

    private void loadValueAnimators() {
        if (mValueAnimators != null) {
            for (int i = 0; i < mValueAnimators.length; i++) {
                mValueAnimators[i].cancel();
            }
        }
        mValueAnimators = new ValueAnimator[mUnits];
        for (int i = 0; i < mUnits; i++) {
            final int j = i * 4 + 1;
            mValueAnimators[i] = new ValueAnimator();
            mValueAnimators[i].setDuration(128);
            mValueAnimators[i].addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mFFTPoints[j] = (float) animation.getAnimatedValue();
                    postInvalidate();
                }
            });
        }
    }

    private void setPortraitPoints() {
        float units = Float.valueOf(mUnits);
        float barUnit = mWidth / units;
        float barWidth = barUnit * 8f / 9f;
        barUnit = barWidth + (barUnit - barWidth) * units / (units - 1);

        for (int i = 0; i < mUnits; i++) {
            mFFTPoints[i * 4] = mFFTPoints[i * 4 + 2] = i * barUnit + (barWidth / 2);
            mFFTPoints[i * 4 + 1] = mHeight;
            mFFTPoints[i * 4 + 3] = mHeight;
        }

        if (DEBUG) Log.d(TAG, "setPortraitPoints");
    }

    public int getNotificationLightsColor() {
        int colorMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.PULSE_AMBIENT_LIGHT_COLOR_MODE,
                3, UserHandle.USER_CURRENT);
        int color = getDefaultNotificationLightsColor(); // custom color (fallback)
        if (colorMode == 1) {  // follow accent
            color = Utils.getColorAccentDefaultColor(getContext());
        } else if (colorMode == 2) { // follow wallapaper
            try {
                WallpaperManager wallpaperManager = WallpaperManager.getInstance(mContext);
                WallpaperInfo wallpaperInfo = wallpaperManager.getWallpaperInfo();
                if (wallpaperInfo == null) { // if not a live wallpaper
                    Drawable wallpaperDrawable = wallpaperManager.getDrawable();
                    Bitmap bitmap = ((BitmapDrawable)wallpaperDrawable).getBitmap();
                    if (bitmap != null) { // if wallpaper is not blank
                        Palette p = Palette.from(bitmap).generate();
                        int wallColor = p.getDominantColor(color);
                        if (color != wallColor)
                            color = wallColor;
                    }
                }
            } catch (Exception e) {
                // Nothing to do
            }
        }
        return color;
    }

    public int getDefaultNotificationLightsColor() {
        int defaultColor = Utils.getColorAccentDefaultColor(getContext());
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.PULSE_AMBIENT_LIGHT_COLOR, defaultColor,
                    UserHandle.USER_CURRENT);
    }

    private void setSolidFudgeFactor() {
        mDbFuzzFactor = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCKSCREEN_SOLID_FUDGE_FACTOR, 16, UserHandle.USER_CURRENT);
    }

    private void setSolidUnitsCount() {
        int oldUnits = mUnits;
        mUnits = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCKSCREEN_SOLID_UNITS_COUNT, 32, UserHandle.USER_CURRENT);
        if (mUnits != oldUnits) {
            mFFTPoints = new float[mUnits * 4];
            onSizeChanged(0, 0, 0, 0);
        }
    }

    public void setVisualizerVisibility(boolean visible) {
        if (visible) {
            AsyncTask.execute(mLinkVisualizer);
        } else {
            AsyncTask.execute(mUnlinkVisualizer);
        }
    }

    public void checkState(boolean state) {
        if (state) {
            animate()
                    .alpha(1f)
                    .withEndAction(null)
                    .setDuration(400);
        } else {
            animate()
                    .alpha(0f)
                    .withEndAction(null)
                    .setDuration(400);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mVisualizer != null) {
            ImageView leftView = (ImageView) findViewById(R.id.light_animation_left);
            ImageView rightView = (ImageView) findViewById(R.id.light_animation_right);
            leftViewProgress = mFFTPoints[1 * 4 + 1];
            rightViewProgress = mFFTPoints[(mUnits - 1) * 4 + 1];
            float newLeftViewProgress = (leftViewProgress * 100f / (float) mHeight) / 100f;
            float newRightViewProgress = (rightViewProgress * 100f / (float) mHeight) / 100f;
            if (newLeftViewProgress != 1 || newRightViewProgress != 1) {
                leftView.setColorFilter(getNotificationLightsColor());
                rightView.setColorFilter(getNotificationLightsColor());
                leftView.setScaleY((1f - newLeftViewProgress) - ((1f - newLeftViewProgress) * 2f));
                rightView.setScaleY((1f - newRightViewProgress) - ((1f - newRightViewProgress) * 2f));
            }
            if (DEBUG) Log.d(TAG, "RightViewScale: " + (rightViewProgress * 100f / (float) mHeight) / 100f);
            if (DEBUG) Log.d(TAG, "LeftViewScale: " + (leftViewProgress * 100f / (float) mHeight) / 100f);

            if (DEBUG) Log.d(TAG, "Height: " + getMeasuredHeight());
            if (DEBUG) Log.d(TAG, "mHeight Value: " + mHeight);
        }
    }

    public void animateNotificationWithColor(int color) {
        ImageView leftView = (ImageView) findViewById(R.id.light_animation_left);
        ImageView rightView = (ImageView) findViewById(R.id.light_animation_right);
        leftView.setColorFilter(color);
        rightView.setColorFilter(color);
        mLightAnimator = ValueAnimator.ofFloat(new float[]{0.0f, 2.0f});
        mLightAnimator.setDuration(1000);
        mLightAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mLightAnimator.setRepeatMode(ValueAnimator.RESTART);
        mLightAnimator.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                if (DEBUG) Log.d(TAG, "onAnimationUpdate");
                float progress = ((Float) animation.getAnimatedValue()).floatValue();
                float alpha = 1.0f;
                float newLeftViewProgress = (leftViewProgress * 100f / (float) mHeight) / 100f;
                float newRightViewProgress = (rightViewProgress * 100f / (float) mHeight) / 100f;
                boolean show = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SYNTH_FUNCTIONAL_LIGHTS_LOOP_ANIMATION, 1, UserHandle.USER_CURRENT) != 0;
                if (show && (mVisualizer == null || (newLeftViewProgress == 1 && newRightViewProgress == 1))) {
                    if (progress <= 0.3f) {
                        alpha = progress / 0.3f;
                    } else if (progress >= 1.0f) {
                        alpha = 2.0f - progress;
                    }
                    leftView.setScaleY(progress);
                    rightView.setScaleY(progress);
                }
                leftView.setAlpha(alpha);
                rightView.setAlpha(alpha);
            }
        });
        if (DEBUG) Log.d(TAG, "start");
        mLightAnimator.start();
    }

    public void stopAnimateNotification() {
        if (mLightAnimator != null) {
            mLightAnimator.end();
            mLightAnimator = null;
        }
    }

    private void setLoopAnimation() {
        stopAnimateNotification();
        animateNotificationWithColor(getNotificationLightsColor());
    }

    private class SettingsObserver extends ContentObserver {

        public SettingsObserver(Handler handler) {
            super(handler);
        }

        protected void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.LOCKSCREEN_SOLID_UNITS_COUNT),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.LOCKSCREEN_SOLID_FUDGE_FACTOR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SYNTH_FUNCTIONAL_LIGHTS_LOOP_ANIMATION),
                    false, this, UserHandle.USER_ALL);
            update();
        }

        protected void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            ContentResolver resolver = mContext.getContentResolver();
            if (uri.equals(Settings.Secure.getUriFor(
                    Settings.Secure.LOCKSCREEN_SOLID_UNITS_COUNT))) {
                setSolidUnitsCount();
            } else if (uri.equals(Settings.Secure.getUriFor(
                    Settings.Secure.LOCKSCREEN_SOLID_FUDGE_FACTOR))) {
                setSolidFudgeFactor();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.SYNTH_FUNCTIONAL_LIGHTS_LOOP_ANIMATION))) {
                setLoopAnimation();
            }
        }

        protected void update() {
            setSolidFudgeFactor();
            setSolidUnitsCount();
            setLoopAnimation();
        }
    }

}
