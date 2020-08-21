/*
*  Copyright (C) 2018 The OmniROM Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see <http://www.gnu.org/licenses/>.
*
*/

package com.android.systemui.synth.gamma;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.graphics.Color;
import android.graphics.PorterDuff.Mode;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;

import com.android.systemui.R;
import com.android.systemui.Dependency;

public class AmbientCustomVideo extends FrameLayout {

   private final String AMBIENT_VIDEO_FILE_NAME = "custom_file_ambient_video";

   private VideoView mCustomVideo;
   private String mVideo;

   private Gamma mGamma;

   public AmbientCustomVideo(Context context) {
       this(context, null);
   }

   public AmbientCustomVideo(Context context, AttributeSet attrs) {
       this(context, attrs, 0);
   }

   public AmbientCustomVideo(Context context, AttributeSet attrs, int defStyleAttr) {
       this(context, attrs, defStyleAttr, 0);
   }

   public AmbientCustomVideo(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
       super(context, attrs, defStyleAttr, defStyleRes);
       mCustomVideo = (VideoView) findViewById(R.id.custom_video);
       mGamma = Dependency.get(Gamma.class);
   }

   public void update() {
        mCustomVideo = (VideoView) findViewById(R.id.custom_video);
        mGamma = Dependency.get(Gamma.class);
        String videoUri = Settings.System.getStringForUser(mContext.getContentResolver(),
              Settings.System.SYNTHOS_AMBIENT_CUSTOM_VIDEO,
              UserHandle.USER_CURRENT);
        if (videoUri != null) {
              saveAmbientVideo(Uri.parse(videoUri));
        }
        loadAmbientVideo();
        setCustomVideo(mVideo);
   }

   private void saveAmbientVideo(Uri videoUri) {
       mGamma.saveCustomFileFromString(videoUri, AMBIENT_VIDEO_FILE_NAME);
   }

   private void loadAmbientVideo() {
       mVideo = mGamma.getCustomVideoPathFromString(AMBIENT_VIDEO_FILE_NAME);
   }

   public String getCurrent() {
       return mVideo;
   }

   private void setCustomVideo(final String videoPath) {
      mCustomVideo = (VideoView) findViewById(R.id.custom_video);
      mCustomVideo.setVideoPath(videoPath);
   }

   public void setState(boolean state) {
      mCustomVideo = (VideoView) findViewById(R.id.custom_video);
      boolean looping = Settings.System.getIntForUser(mContext.getContentResolver(), Settings.System.SYNTHOS_AMBIENT_VIDEO_LOOPING, 1, UserHandle.USER_CURRENT) != 0;
      mCustomVideo.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);

      if (state) {
          mCustomVideo.start();
          mCustomVideo.setOnPreparedListener(new OnPreparedListener() {
              @Override
              public void onPrepared(MediaPlayer mp) {
                  mp.setLooping(looping);
              }
          });
      } else {
          mCustomVideo.stopPlayback();
      }
   }
}
