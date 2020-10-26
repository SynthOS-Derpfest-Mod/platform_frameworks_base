/*
* Copyright (C) 2020 SynthOS
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
import android.content.Context;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Outline;
import android.graphics.PorterDuff.Mode;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.UserHandle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import com.android.internal.graphics.palette.Palette;
import com.android.internal.graphics.ColorUtils;

import java.util.Timer;
import java.util.TimerTask;

public class HeadsUpMediaNotification extends SynthMusic {

    private boolean mAnimate = false;
    private boolean mUpdate = false;
    private int mState;
    private boolean mOnAnimation;
    private boolean mVisible;
    private boolean mHide = false;
    private boolean mWaiting = false;
    private int mDuration = 3000;
    private int mStartTime = 0;
    private int mRecord = 0;

    private Handler customHandler = new Handler();

    private int timeInMilliseconds = 0;
    private int timeSwapBuff = 0;
    private int updatedTime = 0;

    private View mTextContainer;
    private View mInfoControlContainer;

    public HeadsUpMediaNotification(Context context) {
        this(context, null);
    }

    public HeadsUpMediaNotification(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HeadsUpMediaNotification(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public HeadsUpMediaNotification(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        this.setWillNotDraw(false);
    }

    /**
     * Called whenever new media metadata is available.
     * @param metadata New metadata.
     */
    @Override
    public void onMetadataOrStateChanged(MediaMetadata metadata, @PlaybackState.State int state) {

          CharSequence title = metadata == null ? null : metadata.getText(
                 MediaMetadata.METADATA_KEY_TITLE);
          CharSequence artist = metadata == null ? null : metadata.getText(
                 MediaMetadata.METADATA_KEY_ARTIST);
          Bitmap artwork = metadata == null ? null : metadata.getBitmap(
                 MediaMetadata.METADATA_KEY_ALBUM_ART);
          Drawable d = new BitmapDrawable(mContext.getResources(), artwork);
          updateAnimation(state, mMediaTitle != title);

          mMediaTitle = title;
          mMediaArtist = artist;
          mMediaArtwork = d;

          if (mMediaArtwork != null && (state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_PLAYING)) update();
    }

    @Override
    public void initDependencies(NotificationMediaManager mediaManager, Context context) {
        super.initDependencies(mediaManager, context);
        this.mMediaController = mediaManager.getMediaController();
        this.setWillNotDraw(false);
    }

    @Override
    public void update() {
        updateObjects();
        updateViews();
        updateIconPlayPause();
    }

    public void updateAnimation(@PlaybackState.State int state, boolean change) {
        boolean animate = (change) && state == PlaybackState.STATE_PLAYING && !mOnAnimation;
        if (animate) {
            if (!mHide) {
                setAnimation(true, false, true);
            } else {
                customHandler.removeCallbacksAndMessages(null);
                customHandler.removeCallbacks(setAnimationFalse);
                customHandler.postDelayed(setAnimationFalse, mDuration);
            }
            mState = state;
        } else {
            customHandler.removeCallbacksAndMessages(null);
            customHandler.removeCallbacks(setAnimationFalse);
            customHandler.postDelayed(setAnimationFalse, mDuration);
        }
    }

    private Runnable setAnimationFalse = new Runnable() {

        public void run() {
            if (!mOnAnimation) {
                setAnimation(false, false, true);
            }
        }

    };

    public void setUpdate(boolean value) {
        mUpdate = value;
        setState(value);
        if (value) {
            setVisibility(View.VISIBLE);
        } else {
            setVisibility(View.GONE);
        }
    }

    public void setAnimation(boolean show, boolean height, boolean width) {
        Log.d("HEADSUP", "start animation: " + show);
        if (show) {
            setTranslationX(width ? ((getWidth() / 3.0f)) : 0);
            setTranslationY(height ? ((getHeight() / 3.0f)) : 0);
            setAlpha(0);
            mOnAnimation = true;
            animate()
                    .alpha(1)
                    .translationX(0)
                    .translationY(0)
                    .setDuration(500)
                    .withEndAction(() -> {
                        mOnAnimation = false;
                        mVisible = true;
                        mHide = true;
                        updateAnimation(mState, false);
                        setVisibility(mUpdate ? View.VISIBLE : View.GONE);
                    })
                    .start();
        } else if (mHide && !mOnAnimation) {
            setTranslationX(0);
            setTranslationY(0);
            setAlpha(1);
            mOnAnimation = true;
            animate()
                    .alpha(0)
                    .translationX(width ? ((getWidth() / 3.0f)) : 0)
                    .translationY(height ? ((getHeight() / 3.0f)) : 0)
                    .setDuration(500)
                    .withEndAction(() -> {
                        mOnAnimation = false;
                        mVisible = false;
                        mHide = false;
                        setVisibility(View.GONE);
                    })
                    .start();
        }
    }

}
