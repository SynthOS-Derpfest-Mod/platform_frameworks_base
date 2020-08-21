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

public class MediaCardNotification extends SynthMusic {

    private TextView mPackage;
    private ImageView mIcon;
    private boolean mAnimate = false;

    private View mTextContainer;
    private View mInfoControlContainer;

    public MediaCardNotification(Context context) {
        this(context, null);
    }

    public MediaCardNotification(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MediaCardNotification(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MediaCardNotification(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        this.setWillNotDraw(false);
    }

    @Override
    public void initDependencies(NotificationMediaManager mediaManager, Context context) {
        super.initDependencies(mediaManager, context);
        this.mMediaController = mediaManager.getMediaController();
        this.setWillNotDraw(false);
    }

    @Override
    public void updateObjects() {
        super.updateObjects();
        mIcon = findViewById(R.id.icon);
        mPackage = (TextView) findViewById(R.id.application);
        mTextContainer = findViewById(R.id.media_text_container);
        mInfoControlContainer = findViewById(R.id.media_info_control_container);
    }

    @Override
    public void updateViews() {

        try {
            NotificationEntry entry = this.mMediaManager.getNotificationEntry();
            StatusBarIconView expandedIcon = entry.icon;
            Drawable icon = StatusBarIconView.getIcon(this.mContext, expandedIcon.getStatusBarIcon());
            ColorFilter newColorText = icon.getColorFilter();
            int newColorArtwork = entry.getRow().getNotificationColor();
            String appName = entry.getRow().getAppName();

            mIcon.setImageDrawable(icon);
            mPackage.setText(appName);
            mPackage.setSelected(true);
            mIcon.setColorFilter(newColorText);
            mPackage.setTextColor(newColorArtwork);
            mIcon.setVisibility(View.VISIBLE);
            mPackage.setVisibility(View.VISIBLE);

        } catch (Exception e) {
            mIcon.setVisibility(View.GONE);
            mPackage.setVisibility(View.GONE);
            Log.d("MediaCard", e.getMessage());
        }

        try {
            mTextContainer.animate()
                  .alpha(0)
                  .translationX((mTextContainer.getWidth() / 2.0f) * -1)
                  .setDuration(250)
                  .withEndAction(() -> {
                      try {
                          this.mTitle.setText(this.mMediaTitle.toString());
                          this.mTitle.setSelected(true);
                          this.mArtist.setText(this.mMediaArtist.toString());
                          this.mArtist.setSelected(true);
                      } catch (Exception e) {
                          Log.d("MediaCard", e.getMessage());
                      }
                      mTextContainer.animate()
                              .alpha(1)
                              .translationX(0)
                              .setDuration(250)
                              .start();
                  })
                  .start();
            this.mArtwork.setImageDrawable(mMediaArtwork);
            this.mArtwork.setClipToOutline(true);

            if (this.mMediaManager.getMediaMetadata().getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null) {
                Palette.generateAsync((this.mMediaManager.getMediaMetadata().getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)), this);
            }
        } catch (Exception e) {
            Log.d("MediaCard", e.getMessage());
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        boolean animate = (visibility == View.GONE) ? false : true;
        if (changedView == this && mAnimate != animate) {
          try {
            mTextContainer.setTranslationX((mTextContainer.getWidth() / 2.0f) * -1);
            mTextContainer.setAlpha(0);
            mTextContainer.animate()
                    .alpha(1)
                    .translationX(0)
                    .setDuration(500)
                    .start();
            mInfoControlContainer.setTranslationY((mInfoControlContainer.getHeight() / 2.0f));
            mInfoControlContainer.setAlpha(0);
            mInfoControlContainer.animate()
                    .alpha(1)
                    .translationY(0)
                    .setDuration(500)
                    .start();
            mAnimate = animate;
          } catch (Exception e) {
              Log.d("MediaCard", e.getMessage());
          }
        }
    }

}
