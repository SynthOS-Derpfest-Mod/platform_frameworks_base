/*
 * Copyright (C) 2014-2015 The MoKee OpenSource Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.aosip.carrierlabel;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.internal.util.aosip.aosipUtils;
import com.android.internal.util.aosip.ThemeConstants;
import com.android.internal.telephony.TelephonyIntents;

import com.android.systemui.Dependency;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.aosip.carrierlabel.SpnOverride;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

import com.android.systemui.R;

public class CarrierLabel extends TextView implements NotificationMediaManager.MediaListener, DarkReceiver {

    private Context mContext;
    private boolean mAttached;
    private static boolean isCN;
    private int mCarrierColor = 0xffffffff;
    private int mTintColor = Color.WHITE;

    private NotificationMediaManager mMediaManager;

    private CharSequence mMediaTitle;
    private CharSequence mMediaAlbum;
    private CharSequence mMediaArtist;

    Handler mHandler;

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_CARRIER_COLOR), false, this);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_CARRIER_FONT_SIZE), false, this);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_CARRIER_FONT_STYLE), false, this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CARRIER_COLOR))) {
                updateColor();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CARRIER_FONT_SIZE))) {
                updateSize();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CARRIER_FONT_STYLE))) {
                updateStyle();
            }
        }
    }

    public CarrierLabel(Context context) {
        this(context, null);
    }

    public CarrierLabel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CarrierLabel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mMediaManager = Dependency.get(NotificationMediaManager.class);
        mMediaManager.addCallback(this);
        updateNetworkName(true, null, false, null);
        /* Force carrier label to the lockscreen. This helps us avoid
        the carrier label on the statusbar if for whatever reason
        the user changes notch overlays */
        if (aosipUtils.hasNotch(mContext)) {
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_SHOW_CARRIER, 1);
        }
        mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        updateColor();
        updateSize();
        updateStyle();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);
            filter.addAction(Intent.ACTION_CUSTOM_CARRIER_LABEL_CHANGED);
            mContext.registerReceiver(mIntentReceiver, filter, null, getHandler());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mTintColor = DarkIconDispatcher.getTint(area, this, tint);
        if (mCarrierColor == 0xFFFFFFFF) {
            setTextColor(mTintColor);
        } else {
            setTextColor(mCarrierColor);
        }
    }

    /**
     * Called whenever new media metadata is available.
     * @param metadata New metadata.
     */
    @Override
    public void onMetadataOrStateChanged(MediaMetadata metadata, @PlaybackState.State int state) {

           CharSequence title = metadata == null ? null : metadata.getText(
                   MediaMetadata.METADATA_KEY_TITLE);
           CharSequence album = metadata == null ? null : metadata.getText(
                   MediaMetadata.METADATA_KEY_ALBUM);
           CharSequence artist = metadata == null ? null : metadata.getText(
                   MediaMetadata.METADATA_KEY_ARTIST);

           mMediaTitle = title;
           mMediaAlbum = album;
           mMediaArtist = artist;
           mState = state;

           updateNetworkName(true, null, false, null);
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TelephonyIntents.SPN_STRINGS_UPDATED_ACTION.equals(action)
                    || Intent.ACTION_CUSTOM_CARRIER_LABEL_CHANGED.equals(action)) {
                        updateNetworkName(intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_SPN, true),
                        intent.getStringExtra(TelephonyIntents.EXTRA_SPN),
                        intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_PLMN, false),
                        intent.getStringExtra(TelephonyIntents.EXTRA_PLMN));
                isCN = aosipUtils.isChineseLanguage();
            }
        }
    };

    void updateNetworkName(boolean showSpn, String spn, boolean showPlmn, String plmn) {
        final String str;
        final boolean plmnValid = showPlmn && !TextUtils.isEmpty(plmn);
        final boolean spnValid = showSpn && !TextUtils.isEmpty(spn);
        if (spnValid) {
            str = spn;
        } else if (plmnValid) {
            str = plmn;
        } else {
            str = "";
        }
        String customCarrierLabel = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.CUSTOM_CARRIER_LABEL, UserHandle.USER_CURRENT);
        int typeCarrierLabel = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CARRIER_TEXT_TYPE, 0);
        boolean setDefaultCustom =  typeCarrierLabel == 0 ||
                                    (TextUtils.isEmpty(mMediaTitle) || TextUtils.isEmpty(mMediaAlbum) || TextUtils.isEmpty(mMediaArtist)) ||
                                    mState != PlaybackState.STATE_PLAYING;
        if (setDefaultCustom) {
            if (!TextUtils.isEmpty(customCarrierLabel)) {
                setText(customCarrierLabel);
            } else {
                setText(TextUtils.isEmpty(str) ? getOperatorName() : str);
            }
        } else if (typeCarrierLabel == 1) {
            setText(mMediaTitle);
        } else if (typeCarrierLabel == 2) {
            setText(mMediaAlbum);
        } else if (typeCarrierLabel == 3) {
            setText(mMediaArtist);
        }
    }

    private String getOperatorName() {
        String operatorName = getContext().getString(R.string.quick_settings_wifi_no_network);
        TelephonyManager telephonyManager = (TelephonyManager) getContext().getSystemService(
                Context.TELEPHONY_SERVICE);
        if (isCN) {
            String operator = telephonyManager.getNetworkOperator();
            if (TextUtils.isEmpty(operator)) {
                operator = telephonyManager.getSimOperator();
            }
            SpnOverride mSpnOverride = new SpnOverride();
            operatorName = mSpnOverride.getSpn(operator);
        } else {
            operatorName = telephonyManager.getNetworkOperatorName();
        }
        if (TextUtils.isEmpty(operatorName)) {
            operatorName = telephonyManager.getSimOperatorName();
        }
        return operatorName;
    }

    public void getFontStyle(int font) {
        String[][] fontsArray = ThemeConstants.FONTS;

        int fontType = Typeface.NORMAL;
        switch (fontsArray[font][1]) {
            case "BOLD":
                fontType = Typeface.BOLD;
                break;
            case "ITALIC":
                fontType = Typeface.ITALIC;
                break;
            case "BOLD_ITALIC":
                fontType = Typeface.BOLD_ITALIC;
                break;
            default:
                break;
        }

        setTypeface(Typeface.create(fontsArray[font][0], fontType));
    }

    private void updateColor() {
        mCarrierColor = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CARRIER_COLOR, 0xffffffff);
        if (mCarrierColor == 0xFFFFFFFF) {
            setTextColor(mTintColor);
        } else {
            setTextColor(mCarrierColor);
        }
    }

    private void updateSize() {
        int carrierFontSize = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CARRIER_FONT_SIZE, 11);
        setTextSize(carrierFontSize);
    }

    private void updateStyle() {
        int carrierLabelFontStyle = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CARRIER_FONT_STYLE, 28);
        getFontStyle(carrierLabelFontStyle);
    }
}
