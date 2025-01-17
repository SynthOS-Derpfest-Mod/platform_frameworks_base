/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Html;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.FrameLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.android.internal.util.aosip.ThemeConstants;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.clock.CustomTextClock;
import com.android.systemui.Dependency;
import com.android.systemui.omni.CurrentWeatherView;
import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.synth.gamma.SynthMusic;

import java.lang.Math;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.TimeZone;

public class KeyguardStatusView extends GridLayout implements
        ConfigurationController.ConfigurationListener {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";
    private static final int MARQUEE_DELAY_MS = 2000;
    private static final int SMART_MEDIA_ANIMATION_DURATION = 300;

    private final LockPatternUtils mLockPatternUtils;
    private final IActivityManager mIActivityManager;

    private Context mContext;

    private LinearLayout mStatusViewContainer;
    private TextView mLogoutView;
    private SynthMusic mSmartMedia;
    private FrameLayout mSmartMediaContainer;
    private KeyguardClockSwitch mClockView;
    private CustomTextClock mTextClock;
    private TextView mOwnerInfo;
    private TextClock mDefaultClockView;
    private KeyguardSliceView mKeyguardSlice;
    private View mNotificationIcons;
    private View mKeyguardSliceView;
    private View mSmallClockView;
    private Runnable mPendingMarqueeStart;
    private Handler mHandler;

    private boolean mPulsing;
    private float mDarkAmount = 0;
    private int mTextColor;
    private CurrentWeatherView mWeatherView;
    private boolean mShowWeather;
    private boolean mOmniStyle;

    /**
     * Bottom margin that defines the margin between bottom of smart space and top of notification
     * icons on AOD.
     */
    private int mIconTopMargin;
    private int mIconTopMarginWithHeader;
    private boolean mShowingHeader;

    private int mClockSelection;

    // Date styles paddings
    private int mDateVerPadding;
    private int mDateHorPadding;

    //Cool dividers YEAH MOOOOREEEEE GRADDIIIIIIEEEEENTTT HAHAHAAHAHAHA
    private View mCoolDividerOne;
    private View mCoolDividerTwo;
    private View mCoolDividerThree;
    private boolean mShowDividers;

    //Align Clock, date and weather to left -- yeah, like oneUI
    private boolean mAlignLeft;

    private boolean mSmartMediaVisibility = false;
    private boolean mSmartMediaOnAnimation = false;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            refreshTime();
        }

        @Override
        public void onTimeZoneChanged(TimeZone timeZone) {
            updateTimeZone(timeZone);
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refreshTime();
                updateOwnerInfo();
                updateLogoutView();
                mClockView.refreshLockFont();
                refreshLockDateFont();
                mClockView.refreshclocksize();
                mKeyguardSlice.refreshDateSize();
                refreshOwnerInfoSize();
                refreshOwnerInfoFont();
                updateSettings();
                mClockView.updateClockColor();
                updateClockDateColor();
                updateOwnerInfoColor();
            }
        }

        @Override
        public void onStartedWakingUp() {
            setEnableMarquee(true);
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            setEnableMarquee(false);
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            refreshFormat();
            updateOwnerInfo();
            updateLogoutView();
            mClockView.refreshLockFont();
            refreshLockDateFont();
            mClockView.refreshclocksize();
            mKeyguardSlice.refreshDateSize();
            updateDateStyles();
            refreshOwnerInfoSize();
            refreshOwnerInfoFont();
            updateSettings();
            mClockView.updateClockColor();
            updateClockDateColor();
            updateOwnerInfoColor();
        }

        @Override
        public void onLogoutEnabledChanged() {
            updateLogoutView();
        }
    };

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mIActivityManager = ActivityManager.getService();
        mLockPatternUtils = new LockPatternUtils(getContext());
        mHandler = new Handler(Looper.myLooper());
        onDensityOrFontScaleChanged();
    }

    /**
     * If we're presenting a custom clock of just the default one.
     */
    public boolean hasCustomClock() {
        return mClockView.hasCustomClock();
    }

    public boolean hasCustomClockInBigContainer() {
        return mClockView.hasCustomClockInBigContainer();
    }

    /**
     * Set whether or not the lock screen is showing notifications.
     */
    public void setHasVisibleNotifications(boolean hasVisibleNotifications) {
        mClockView.setHasVisibleNotifications(hasVisibleNotifications);
    }

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, "Schedule setEnableMarquee: " + (enabled ? "Enable" : "Disable"));
        if (enabled) {
            if (mPendingMarqueeStart == null) {
                mPendingMarqueeStart = () -> {
                    setEnableMarqueeImpl(true);
                    mPendingMarqueeStart = null;
                };
                mHandler.postDelayed(mPendingMarqueeStart, MARQUEE_DELAY_MS);
            }
        } else {
            if (mPendingMarqueeStart != null) {
                mHandler.removeCallbacks(mPendingMarqueeStart);
                mPendingMarqueeStart = null;
            }
            setEnableMarqueeImpl(false);
        }
    }

    private void setEnableMarqueeImpl(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mOwnerInfo != null) mOwnerInfo.setSelected(enabled);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mStatusViewContainer = findViewById(R.id.status_view_container);
        mLogoutView = findViewById(R.id.logout);
        mNotificationIcons = findViewById(R.id.clock_notification_icon_container);
        if (mLogoutView != null) {
            mLogoutView.setOnClickListener(this::onLogoutClicked);
        }

        mClockView = findViewById(R.id.keyguard_clock_container);
        mClockView.setShowCurrentUserTime(true);
        mSmartMedia = findViewById(R.id.synth_smart_media);
        mSmartMediaContainer = findViewById(R.id.smart_media_container);
        mTextClock = findViewById(R.id.custom_text_clock_view);
        mOwnerInfo = findViewById(R.id.owner_info);
        mKeyguardSlice = findViewById(R.id.keyguard_status_area);
        mCoolDividerOne = findViewById(R.id.cool_divider);
        mCoolDividerTwo = findViewById(R.id.cool_divider_two);
        mCoolDividerThree = findViewById(R.id.cool_divider_three);

        mWeatherView = (CurrentWeatherView) findViewById(R.id.weather_container);

        setOnClickListener(this::onKeyguardClick);
        setClickable(true);
        updateSettings();
        setSmartMediaInvisible();

        mKeyguardSliceView = findViewById(R.id.keyguard_status_area);
        mClockView.refreshLockFont();
        refreshLockDateFont();
        mClockView.refreshclocksize();
        updateDateStyles();
        mKeyguardSlice.refreshDateSize();
        refreshOwnerInfoSize();
        refreshOwnerInfoFont();
	      mClockView.updateClockColor();
	      updateClockDateColor();
	      updateOwnerInfoColor();
        mTextColor = mClockView.getCurrentTextColor();

        mKeyguardSlice.setContentChangeListener(this::onSliceContentChanged);
        onSliceContentChanged();

        boolean shouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setEnableMarquee(shouldMarquee);
        refreshFormat();
        updateOwnerInfo();
        updateLogoutView();
        updateDark();
        updateSettings();
    }

    public KeyguardSliceView getKeyguardSliceView() {
        return mKeyguardSlice;
    }

    /**
     * Moves clock, adjusting margins when slice content changes.
     */
    private void onSliceContentChanged() {
        final boolean hasHeader = mKeyguardSlice.hasHeader();
        mClockView.setKeyguardShowingHeader(hasHeader);
        if (mShowingHeader == hasHeader) {
            return;
        }
        mShowingHeader = hasHeader;
        if (mNotificationIcons != null) {
            // Update top margin since header has appeared/disappeared.
            MarginLayoutParams params = (MarginLayoutParams) mNotificationIcons.getLayoutParams();
            params.setMargins(params.leftMargin,
                    hasHeader ? mIconTopMarginWithHeader : mIconTopMargin,
                    params.rightMargin,
                    params.bottomMargin);
            mNotificationIcons.setLayoutParams(params);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        layoutOwnerInfo();
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        if (mClockView != null) {
            mClockView.refreshclocksize();
        }
        if (mOwnerInfo != null) {
            mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX, (float) 18);
        }
        if (mWeatherView != null) {
            mWeatherView.onDensityOrFontScaleChanged();
        }
        loadBottomMargin();
    }

    public void dozeTimeTick() {
        refreshTime();
        mKeyguardSlice.refresh();
    }

    private void refreshTime() {
        mClockView.refresh();

        switch (mClockSelection) {
            case 0: // default
                mClockView.setFormat12Hour(Patterns.clockView12);
                mClockView.setFormat24Hour(Patterns.clockView24);
                break;
            case 1: // bold
                mClockView.setFormat12Hour(Html.fromHtml("<strong>h</strong>:mm"));
                mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong>:mm"));
                break;
            case 2: // accent
                mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">h:mm</font>"));
                mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">kk:mm</font>"));
                break;
            case 3: // accent hour
                mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">h</font>:mm"));
                mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">kk</font>:mm"));
                break;
            case 4: // accent min
                mClockView.setFormat12Hour(Html.fromHtml("h<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">:mm</font>"));
                mClockView.setFormat24Hour(Html.fromHtml("kk<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">:mm</font>"));
                break;
            case 5: // sammy
            case 12:
            case 14:
            case 15:
            case 16:
            case 21:
            case 22:
                mClockView.setFormat12Hour("hh\nmm");
                mClockView.setFormat24Hour("kk\nmm");
                break;
            case 6: // sammy bold
                mClockView.setFormat12Hour(Html.fromHtml("<strong>hh</strong><br>mm"));
                mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong><br>mm"));
                break;
            case 7: // sammy accent
                mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">hh<br>mm</font>"));
                mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">kk<br>mm</font>"));
                break;
            case 8: // sammy accent hour
                mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">hh</font><br>mm"));
                mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">kk</font><br>mm"));
                break;
            case 9: // sammy accent min
                mClockView.setFormat12Hour(Html.fromHtml("hh<br><font color=" + getResources().getColor(R.color.accent_device_default_light) + ">mm</font>"));
                mClockView.setFormat24Hour(Html.fromHtml("kk<br><font color=" + getResources().getColor(R.color.accent_device_default_light) + ">mm</font>"));
                break;
            case 10: // text
            case 11:
                mTextClock.onTimeChanged();
                break;
            case 13:
                mClockView.setFormat12Hour(Html.fromHtml("hh mm"));
                mClockView.setFormat24Hour(Html.fromHtml("kk mm"));
                break;
            case 17:
            case 18:
                mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.clock_gradient_full_clock_text_color) + ">hh mm</font>"));
                mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.clock_gradient_full_clock_text_color) + ">kk mm</font>"));
                break;
            case 19:
            case 20:
                mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.clock_gradient_full_clock_text_color) + ">hh<br>mm</font>"));
                mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.clock_gradient_full_clock_text_color) + ">kk<br>mm</font>"));
                break;
        }
    }

    private void updateTimeZone(TimeZone timeZone) {
        mClockView.onTimeZoneChanged(timeZone);
    }

    private int getLockDateFont() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCK_DATE_FONTS, 28);
    }

    private int getOwnerInfoFont() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCK_OWNERINFO_FONTS, 28);
    }

    private int getOwnerInfoSize() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKOWNER_FONT_SIZE, 18);
    }

    private void updateClockDateColor() {
        ContentResolver resolver = getContext().getContentResolver();
        int color = Settings.System.getInt(resolver,
                Settings.System.LOCKSCREEN_CLOCK_DATE_COLOR, 0xFFFFFFFF);

        if (mKeyguardSlice != null) {
            mKeyguardSlice.setTextColor(color);
       	}
    }

    private void updateOwnerInfoColor() {
        ContentResolver resolver = getContext().getContentResolver();
        int color = Settings.System.getInt(resolver,
                Settings.System.LOCKSCREEN_OWNER_INFO_COLOR, 0xFFFFFFFF);

        if (mOwnerInfo != null) {
            mOwnerInfo.setTextColor(color);
        }
    }

    private void refreshFormat() {
        Patterns.update(mContext);
        mClockView.setFormat12Hour(Patterns.clockView12);
        mClockView.setFormat24Hour(Patterns.clockView24);
    }

    public int getLogoutButtonHeight() {
        if (mLogoutView == null) {
            return 0;
        }
        return mLogoutView.getVisibility() == VISIBLE ? mLogoutView.getHeight() : 0;
    }

    private void updateDateStyles() {
        final ContentResolver resolver = getContext().getContentResolver();

        int dateSelection = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.LOCKSCREEN_DATE_SELECTION, 0, UserHandle.USER_CURRENT);

        switch (dateSelection) {
            case 0: // default
            default:
                mKeyguardSlice.setViewBackgroundResource(0);
                mWeatherView.setBackgroundResource(0);
                mDateVerPadding = 0;
                mDateHorPadding = 0;
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mWeatherView.setPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.05f, false);
                break;
            case 1: // semi-transparent box
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_box_str_border));
                mKeyguardSlice.setTitleBackground(getResources().getDrawable(R.drawable.date_box_str_border));
                mWeatherView.setBackground(getResources().getDrawable(R.drawable.date_box_str_border));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mWeatherView.setPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.05f, false);
                break;
            case 2: // semi-transparent box (round)
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_str_border));
                mKeyguardSlice.setTitleBackground(getResources().getDrawable(R.drawable.date_str_border));
                mWeatherView.setBackground(getResources().getDrawable(R.drawable.date_str_border));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mWeatherView.setPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.05f, false);
                break;
            case 3: // Q-Now Playing background
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.ambient_indication_pill_background));
                mKeyguardSlice.setTitleBackground(getResources().getDrawable(R.drawable.ambient_indication_pill_background));
                mWeatherView.setBackground(getResources().getDrawable(R.drawable.ambient_indication_pill_background));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.q_nowplay_pill_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.q_nowplay_pill_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mWeatherView.setPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.05f, false);
                break;
            case 4: // accent box
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_str_accent));
                mKeyguardSlice.setTitleBackground(getResources().getDrawable(R.drawable.date_str_accent));
                mWeatherView.setBackground(getResources().getDrawable(R.drawable.date_str_accent));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mWeatherView.setPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.15f, true);
                break;
            case 5: // accent box transparent
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_str_accent), 160);
                mKeyguardSlice.setTitleBackground(getResources().getDrawable(R.drawable.date_str_accent), 160);
                mWeatherView.setBackground(getResources().getDrawable(R.drawable.date_str_accent));
                mWeatherView.getBackground().setAlpha(160);
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mWeatherView.setPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.15f, true);
                break;
            case 6: // gradient box
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_str_gradient));
                mKeyguardSlice.setTitleBackground(getResources().getDrawable(R.drawable.date_str_gradient));
                mWeatherView.setBackground(getResources().getDrawable(R.drawable.date_str_gradient));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mWeatherView.setPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.15f, true);
                break;
            case 7: // Dark Accent border
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_str_borderacc));
                mKeyguardSlice.setTitleBackground(getResources().getDrawable(R.drawable.date_str_borderacc));
                mWeatherView.setBackground(getResources().getDrawable(R.drawable.date_str_borderacc));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mWeatherView.setPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.08f, true);
                break;
            case 8: // Dark Gradient border
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_str_bordergrad));
                mKeyguardSlice.setTitleBackground(getResources().getDrawable(R.drawable.date_str_bordergrad));
                mWeatherView.setBackground(getResources().getDrawable(R.drawable.date_str_bordergrad));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mWeatherView.setPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.08f, true);
                break;
            case 9: // gradient box
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_box_str_gradient));
                mKeyguardSlice.setTitleBackground(getResources().getDrawable(R.drawable.date_box_str_gradient));
                mWeatherView.setBackground(getResources().getDrawable(R.drawable.date_box_str_gradient));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mWeatherView.setPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.15f, true);
                break;
            case 10: // Dark Accent border
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_box_str_borderacc));
                mKeyguardSlice.setTitleBackground(getResources().getDrawable(R.drawable.date_box_str_borderacc));
                mWeatherView.setBackground(getResources().getDrawable(R.drawable.date_box_str_borderacc));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mWeatherView.setPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.08f, true);
                break;
            case 11: // Dark Gradient border
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_box_str_bordergrad));
                mKeyguardSlice.setTitleBackground(getResources().getDrawable(R.drawable.date_box_str_bordergrad));
                mWeatherView.setBackground(getResources().getDrawable(R.drawable.date_box_str_bordergrad));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mWeatherView.setPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.08f, true);
                break;
        }
        updateClockAlignment();
    }

    private void refreshLockDateFont() {
        String[][] fontsArray = ThemeConstants.FONTS;
        int lockDateFont = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LOCK_DATE_FONTS, 28, UserHandle.USER_CURRENT);

        int fontType = Typeface.NORMAL;
        switch (fontsArray[lockDateFont][1]) {
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

        mKeyguardSlice.setViewsTypeface(Typeface.create(fontsArray[lockDateFont][0], fontType));
        mClockView.setTextDateFont(Typeface.create(fontsArray[lockDateFont][0], fontType));
    }

    public float getClockTextSize() {
        return mClockView.getTextSize();
    }

    /**
     * Returns the preferred Y position of the clock.
     *
     * @param totalHeight The height available to position the clock.
     * @return Y position of clock.
     */
    public int getClockPreferredY(int totalHeight) {
        return mClockView.getPreferredY(totalHeight);
    }

    private void updateLogoutView() {
        if (mLogoutView == null) {
            return;
        }
        mLogoutView.setVisibility(shouldShowLogout() ? VISIBLE : GONE);
        // Logout button will stay in language of user 0 if we don't set that manually.
        mLogoutView.setText(mContext.getResources().getString(
                com.android.internal.R.string.global_action_logout));
    }

    private void updateOwnerInfo() {
        if (mOwnerInfo == null) return;
        String info = mLockPatternUtils.getDeviceOwnerInfo();
        if (info == null) {
            final ContentResolver resolver = mContext.getContentResolver();
            boolean mClockSelection = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.LOCKSCREEN_CLOCK_SELECTION, 0, UserHandle.USER_CURRENT) == 9
                    || Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.LOCKSCREEN_CLOCK_SELECTION, 0, UserHandle.USER_CURRENT) == 10;
            int mTextClockAlign = Settings.System.getIntForUser(resolver,
                    Settings.System.TEXT_CLOCK_ALIGNMENT, 0, UserHandle.USER_CURRENT);

            if (mClockSelection) {
                switch (mTextClockAlign) {
                    case 0:
                    case 4:
                    default:
                        mOwnerInfo.setPaddingRelative(updateTextClockPadding() + 8, 0, 0, 0);
                        mOwnerInfo.setGravity(Gravity.START);
                        break;
                    case 1:
                        mOwnerInfo.setPaddingRelative(0, 0, 0, 0);
                        mOwnerInfo.setGravity(Gravity.CENTER);
                        break;
                    case 2:
                    case 3:
                        mOwnerInfo.setPaddingRelative(0, 0, updateTextClockPadding() + 8, 0);
                        mOwnerInfo.setGravity(Gravity.END);
                        break;
                }
            } else {
                if (mAlignLeft) {
                    mOwnerInfo.setPaddingRelative(updateLeftMargin() + 8, 0, 0, 0);
                    mOwnerInfo.setGravity(Gravity.START);
                } else  {
                    mOwnerInfo.setPaddingRelative(0, 0, 0, 0);
                    mOwnerInfo.setGravity(Gravity.CENTER);
                }
            }

            // Use the current user owner information if enabled.
            final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                    KeyguardUpdateMonitor.getCurrentUser());
            if (ownerInfoEnabled) {
                info = mLockPatternUtils.getOwnerInfo(KeyguardUpdateMonitor.getCurrentUser());
            }
        }
        mOwnerInfo.setText(info);
	      updateOwnerInfoColor();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mInfoCallback);
        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mInfoCallback);
        Dependency.get(ConfigurationController.class).removeCallback(this);
    }

    @Override
    public void onLocaleListChanged() {
        refreshFormat();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardStatusView:");
        pw.println("  mOwnerInfo: " + (mOwnerInfo == null
                ? "null" : mOwnerInfo.getVisibility() == VISIBLE));
        pw.println("  mPulsing: " + mPulsing);
        pw.println("  mDarkAmount: " + mDarkAmount);
        pw.println("  mTextColor: " + Integer.toHexString(mTextColor));
        if (mLogoutView != null) {
            pw.println("  logout visible: " + (mLogoutView.getVisibility() == VISIBLE));
        }
        if (mClockView != null) {
            mClockView.dump(fd, pw, args);
        }
        if (mKeyguardSlice != null) {
            mKeyguardSlice.dump(fd, pw, args);
        }
    }

    private void loadBottomMargin() {
        mIconTopMargin = getResources().getDimensionPixelSize(R.dimen.widget_vertical_padding);
        mIconTopMarginWithHeader = getResources().getDimensionPixelSize(
                R.dimen.widget_vertical_padding_with_header);
    }

    public void refreshOwnerInfoSize() {
        final Resources res = getContext().getResources();
        int ownerInfoSize = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LOCKOWNER_FONT_SIZE, 18, UserHandle.USER_CURRENT);
        mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, ownerInfoSize);
    }

    private void refreshOwnerInfoFont() {
        String[][] fontsArray = ThemeConstants.FONTS;
        int ownerinfoFont = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LOCK_OWNERINFO_FONTS, 28, UserHandle.USER_CURRENT);

        int fontType = Typeface.NORMAL;
        switch (fontsArray[ownerinfoFont][1]) {
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

        mOwnerInfo.setTypeface(Typeface.create(fontsArray[ownerinfoFont][0], fontType));
    }

    private void onKeyguardClick(View view) {
        updateSmartMediaVisibility();
    }

    private void setSmartMedia() {
        ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) mSmartMedia.getLayoutParams();
        if (mClockSelection != 10 && mClockSelection != 11) {
            lp.height = mSmallClockView.getHeight();
        } else {
            lp.height = mTextClock.getHeight();
        }
        mSmartMedia.initDependencies(Dependency.get(NotificationMediaManager.class), mContext);
        mSmartMedia.setState(true);
        mSmartMedia.setLayoutParams(lp);
        mSmartMedia.setPadding(0,20,0,20);
    }

    private void updateSmartMediaVisibility() {
        boolean showSynthSmartMedia = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SYNTH_SMART_MEDIA, 1, UserHandle.USER_CURRENT) == 1;

        boolean isPlaying = Dependency.get(NotificationMediaManager.class).getPlaybackStateIsEqual(PlaybackState.STATE_PLAYING) ||
                                Dependency.get(NotificationMediaManager.class).getPlaybackStateIsEqual(PlaybackState.STATE_PAUSED);

        if (!mSmartMediaOnAnimation) {
            if ((showSynthSmartMedia && !mSmartMediaVisibility) && isPlaying) {
                setSmartMediaVisible();
            } else if (mSmartMediaVisibility) {
                setSmartMediaInvisible();
            } else if (!showSynthSmartMedia && mSmartMediaVisibility) {
                setSmartMediaInvisible();
            }
        }
    }

    private void setSmartMediaVisible() {
        mSmartMediaContainer.setAlpha(0);
        mSmartMediaContainer.setTranslationX(mSmartMediaContainer.getWidth());
        mSmartMediaContainer.animate()
                                  .alpha(1)
                                  .translationX(0)
                                  .setDuration(SMART_MEDIA_ANIMATION_DURATION)
                                  .withStartAction(() -> {
                                      mSmartMediaOnAnimation = true;
                                  })
                                  .withEndAction(() -> {
                                        mSmartMediaVisibility = true;
                                        mSmartMediaOnAnimation = false;
                                      })
                                  .start();
        mSmallClockView.setAlpha(1);
        mSmallClockView.setTranslationX(0);
        mSmallClockView.animate()
                              .alpha(0)
                              .translationX(-(mSmallClockView.getWidth()))
                              .setDuration(SMART_MEDIA_ANIMATION_DURATION)
                              .start();
        mTextClock.setAlpha(1);
        mTextClock.setTranslationX(0);
        mTextClock.animate()
                        .alpha(0)
                        .translationX(-(mTextClock.getWidth()))
                        .setDuration(SMART_MEDIA_ANIMATION_DURATION)
                        .start();
    }

    private void setSmartMediaInvisible() {
        mSmartMediaContainer.setAlpha(1);
        mSmartMediaContainer.setTranslationX(0);
        mSmartMediaContainer.animate()
                                  .alpha(0)
                                  .translationX(mSmartMediaContainer.getWidth())
                                  .setDuration(SMART_MEDIA_ANIMATION_DURATION)
                                  .withStartAction(() -> {
                                      mSmartMediaOnAnimation = true;
                                  })
                                  .withEndAction(() -> {
                                      mSmartMediaVisibility = false;
                                      mSmartMediaOnAnimation = false;
                                      })
                                  .start();
        mSmallClockView.setAlpha(0);
        mSmallClockView.setTranslationX(-(mSmallClockView.getWidth()));
        mSmallClockView.animate()
                              .alpha(1)
                              .translationX(0)
                              .setDuration(SMART_MEDIA_ANIMATION_DURATION)
                              .start();
        mTextClock.setAlpha(0);
        mTextClock.setTranslationX(-(mTextClock.getWidth()));
        mTextClock.animate()
                        .alpha(1)
                        .translationX(0)
                        .setDuration(SMART_MEDIA_ANIMATION_DURATION)
                        .start();

    }

    private void updateSettings() {
        final ContentResolver resolver = getContext().getContentResolver();

        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams)
                mKeyguardSlice.getLayoutParams();

        mShowDividers = Settings.System.getIntForUser(resolver,
                Settings.System.SYNTHOS_LOCK_COOL_DIVIDER_SHOW, 1, UserHandle.USER_CURRENT) == 1;

        mClockSelection = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.LOCKSCREEN_CLOCK_SELECTION, 0, UserHandle.USER_CURRENT);

        mAlignLeft = Settings.System.getIntForUser(resolver,
                Settings.System.SYNTHOS_ALIGN_LOCKSCREEN_LEFT, 0, UserHandle.USER_CURRENT) == 1;

        final boolean mShowClock = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_CLOCK, 1, UserHandle.USER_CURRENT) == 1;

        final Resources res = getContext().getResources();

        mClockView = findViewById(R.id.keyguard_clock_container);
        mSmartMediaContainer = findViewById(R.id.smart_media_container);
        mDefaultClockView = findViewById(R.id.default_clock_view);
        mClockView.setVisibility(mDarkAmount != 1
                ? (mShowClock ? View.VISIBLE : View.GONE) : View.VISIBLE);
        mSmallClockView = findViewById(R.id.clock_view);

        RelativeLayout.LayoutParams coolDividerOneParams = (RelativeLayout.LayoutParams)
        mCoolDividerOne.getLayoutParams();

        if (mClockSelection >= 5 && mClockSelection <= 9)
            mDefaultClockView.setLineSpacing(0, 0.8f);
            mDefaultClockView.setBackgroundResource(0);
            mDefaultClockView.setGravity(Gravity.CENTER);
            mDefaultClockView.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
            mDefaultClockView.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            mKeyguardSlice.setPadding(0,(int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
            getResources().getDimensionPixelSize(R.dimen.widget_clock_normal_clock_padding),
            getResources().getDisplayMetrics()),0,0);

        if (mClockSelection != 10 && mClockSelection != 11) {
            mTextClock.setVisibility(View.GONE);
            mSmallClockView.setVisibility(View.VISIBLE);
            params.addRule(RelativeLayout.BELOW, R.id.cool_divider);
            coolDividerOneParams.addRule(RelativeLayout.BELOW, R.id.clock_view);
            mDefaultClockView.setBackgroundResource(0);
            mDefaultClockView.setGravity(Gravity.CENTER);
            mCoolDividerOne.setLayoutParams(coolDividerOneParams);
        } else {
            mTextClock.setVisibility(View.VISIBLE);
            mSmallClockView.setVisibility(View.GONE);
            params.addRule(RelativeLayout.BELOW, R.id.cool_divider);
            coolDividerOneParams.addRule(RelativeLayout.BELOW, R.id.custom_text_clock_view);
            mCoolDividerOne.setLayoutParams(coolDividerOneParams);
        }

        if (mClockSelection == 12){
                mDefaultClockView.setBackground(getResources().getDrawable(R.drawable.clock_shishu_normalbg));
                mDefaultClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.setPadding(20,20,20,20);
                mDefaultClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_shishu_accent_font_size));
                mDefaultClockView.setLineSpacing(0,1f);
                mDefaultClockView.setGravity(Gravity.CENTER);
        } else if (mClockSelection == 13) {
                mDefaultClockView.setBackground(getResources().getDrawable(R.drawable.clock_shishu_diamondbg));
                mDefaultClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.setLineSpacing(0,1f);
                mDefaultClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_shishu_inmensity_font_size));
                mDefaultClockView.setPadding(20,20,20,20);
                mDefaultClockView.setGravity(Gravity.CENTER);
        } else if (mClockSelection == 14) {
                mDefaultClockView.setBackground(getResources().getDrawable(R.drawable.clock_shishu_nerves_bg));
                mDefaultClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_width);
                mDefaultClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_height);
                mDefaultClockView.setLineSpacing(0,1f);
                mDefaultClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_font_size));
                mDefaultClockView.setPadding(0,20,0,20);
                mDefaultClockView.setGravity(Gravity.CENTER);
        } else if (mClockSelection == 15) {
                mDefaultClockView.setBackground(getResources().getDrawable(R.drawable.clock_bootleg_gradient));
                mDefaultClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.setLineSpacing(0,1f);
                mDefaultClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_shishu_accent_font_size));
                mDefaultClockView.setPadding(0,20,0,20);
                mKeyguardSlice.setPadding(0,(int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
        getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_date_padding),
        getResources().getDisplayMetrics()),0,0
                );
                mDefaultClockView.setGravity(Gravity.CENTER);
        } else if (mClockSelection == 16) {
                mDefaultClockView.setBackground(getResources().getDrawable(R.drawable.clock_bootleg_gradient_shishu));
                mDefaultClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.setLineSpacing(0,1f);
                mDefaultClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_shishu_accent_font_size));
                mDefaultClockView.setPadding(0,20,0,20);
                mKeyguardSlice.setPadding(0,(int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
        getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_date_padding),
        getResources().getDisplayMetrics()),0,0
                );
                mDefaultClockView.setGravity(Gravity.CENTER);
        } else if (mClockSelection == 17) {
                mDefaultClockView.setBackground(getResources().getDrawable(R.drawable.clock_bootleg_gradient_shadow));
                mDefaultClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.setLineSpacing(0,1f);
                mDefaultClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_font_size));
                mDefaultClockView.setPadding(0,20,0,20);
                mKeyguardSlice.setPadding(0,(int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
        getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_date_padding),
        getResources().getDisplayMetrics()),0,0
                );
                mDefaultClockView.setGravity(Gravity.CENTER);
        } else if (mClockSelection == 18) {
                mDefaultClockView.setBackground(getResources().getDrawable(R.drawable.clock_bootleg_gradient_box_shadow));
                mDefaultClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_height);
                mDefaultClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_width);
                mDefaultClockView.setLineSpacing(0,1f);
                mDefaultClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_font_size));
                mDefaultClockView.setPadding(0,20,0,20);
                mKeyguardSlice.setPadding(0,(int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
        getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_date_padding),
        getResources().getDisplayMetrics()),0,0
                );
                mDefaultClockView.setGravity(Gravity.CENTER);
        } else if (mClockSelection == 19) {
                mDefaultClockView.setBackground(getResources().getDrawable(R.drawable.clock_bootleg_qsgradient));
                mDefaultClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.setLineSpacing(0,1f);
                mDefaultClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_font_size));
                mDefaultClockView.setPadding(0,20,0,20);
                mKeyguardSlice.setPadding(0,(int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
        getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_date_padding),
        getResources().getDisplayMetrics()),0,0
                );
                mDefaultClockView.setGravity(Gravity.CENTER);
        } else if (mClockSelection == 20) {
                mDefaultClockView.setBackground(getResources().getDrawable(R.drawable.clock_bootleg_qsgradient_box));
                mDefaultClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_width);
                mDefaultClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_height);
                mDefaultClockView.setLineSpacing(0,1f);
                mDefaultClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_font_size));
                mDefaultClockView.setPadding(0,20,0,20);
                mKeyguardSlice.setPadding(0,(int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
        getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_date_padding),
        getResources().getDisplayMetrics()),0,0
                );
                mDefaultClockView.setGravity(Gravity.CENTER);
        } else if (mClockSelection == 21) {
                mDefaultClockView.setBackground(getResources().getDrawable(R.drawable.clock_synthos_zerotwo));
                mDefaultClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.setLineSpacing(0,1f);
                mDefaultClockView.setPadding(0,20,0,20);
                mKeyguardSlice.setPadding(0,(int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
        getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_date_padding),
        getResources().getDisplayMetrics()),0,0
                );
                mDefaultClockView.setGravity(Gravity.CENTER);
        } else if (mClockSelection == 22) {
                mDefaultClockView.setBackground(getResources().getDrawable(R.drawable.clock_synthos_diamond));
                mDefaultClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mDefaultClockView.setLineSpacing(0,1f);
                mDefaultClockView.setPadding(0,20,0,20);
                mKeyguardSlice.setPadding(0,(int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
        getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_date_padding),
        getResources().getDisplayMetrics()),0,0
                );
                mDefaultClockView.setGravity(Gravity.CENTER);
        }

            mShowWeather = Settings.System.getIntForUser(resolver,
                    Settings.System.OMNI_LOCKSCREEN_WEATHER_ENABLED, 0,
                    UserHandle.USER_CURRENT) == 1;

            mOmniStyle = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_WEATHER_STYLE, 1,
                    UserHandle.USER_CURRENT) == 0;

            if (mWeatherView != null) {
                if (mShowWeather && mOmniStyle) {
                    mWeatherView.setVisibility(View.VISIBLE);
                    mWeatherView.enableUpdates();
                }
                if (!mShowWeather || !mOmniStyle) {
                    mWeatherView.setVisibility(View.GONE);
                    mWeatherView.disableUpdates();
                }
            }

            if (mShowDividers){
                mCoolDividerOne.setVisibility(View.VISIBLE);
                mCoolDividerTwo.setVisibility(View.VISIBLE);
                mKeyguardSlice.setDividerVisibility(View.VISIBLE);
                if (mShowWeather && mOmniStyle){
                    mCoolDividerThree.setVisibility(View.VISIBLE);
                } else {
                    mCoolDividerThree.setVisibility(View.GONE);
                }
            } else {
                mCoolDividerOne.setVisibility(View.GONE);
                mCoolDividerTwo.setVisibility(View.GONE);
                mCoolDividerThree.setVisibility(View.GONE);
                mKeyguardSlice.setDividerVisibility(View.GONE);
            }
        updateDateStyles();
        updateClockAlignment();
        setSmartMedia();

        boolean isPlaying = Dependency.get(NotificationMediaManager.class).getPlaybackStateIsEqual(PlaybackState.STATE_PLAYING) ||
                              Dependency.get(NotificationMediaManager.class).getPlaybackStateIsEqual(PlaybackState.STATE_PAUSED);

        if (!mSmartMediaOnAnimation) {
            if (mSmartMediaVisibility && !isPlaying) setSmartMediaInvisible();
        }
    }

    public void updateAll() {
        updateSettings();
    }

    private void updateClockAlignment() {
        final ContentResolver resolver = getContext().getContentResolver();

        int textClockAlignment = Settings.System.getIntForUser(resolver,
                Settings.System.TEXT_CLOCK_ALIGNMENT, 0, UserHandle.USER_CURRENT);

        mTextClock = findViewById(R.id.custom_text_clock_view);

        RelativeLayout.LayoutParams coolDividerOneParams = (RelativeLayout.LayoutParams) mCoolDividerOne.getLayoutParams();
        LinearLayout.LayoutParams coolDividerTwoParams = (LinearLayout.LayoutParams) mCoolDividerTwo.getLayoutParams();
        RelativeLayout.LayoutParams coolDividerThreeParams = (RelativeLayout.LayoutParams) mCoolDividerThree.getLayoutParams();
        RelativeLayout.LayoutParams weatherViewParams = (RelativeLayout.LayoutParams) mWeatherView.getLayoutParams();
        RelativeLayout.LayoutParams smallClockViewParams = (RelativeLayout.LayoutParams) mSmallClockView.getLayoutParams();
        RelativeLayout.LayoutParams smartMediaContainerParams = (RelativeLayout.LayoutParams) mSmartMediaContainer.getLayoutParams();

        final Resources res = getContext().getResources();

        int margin = res.getDimensionPixelSize(R.dimen.date_owner_info_margin);

        if (mClockSelection == 10 || mClockSelection == 11) {
            switch (textClockAlignment) {
                case 0:
                default:
                    mTextClock.setGravity(Gravity.START);
                    mTextClock.setPaddingRelative(updateTextClockPadding(), 0, 0, 0);
                    mKeyguardSlice.setGravity(Gravity.START);
                    mKeyguardSlice.setPaddingRelative(updateTextClockPadding(), 0, 0, 0);
                    updateGravity(coolDividerOneParams,Gravity.START);
                    coolDividerOneParams.setMargins(updateTextClockPadding(), margin, 0, margin);
                    coolDividerTwoParams.gravity = (Gravity.START);
                    coolDividerTwoParams.setMargins(updateTextClockPadding(),margin,0,margin);
                    updateGravity(coolDividerThreeParams,Gravity.START);
                    coolDividerThreeParams.setMargins(updateTextClockPadding(),margin,0,margin);
                    updateGravity(weatherViewParams,Gravity.START);
                    weatherViewParams.setMargins(updateTextClockPadding(),margin,0,margin);
                    break;
                case 1:
                    mTextClock.setGravity(Gravity.CENTER);
                    mTextClock.setPaddingRelative(0, 0, 0, 0);
                    mKeyguardSlice.setGravity(Gravity.CENTER);
                    mKeyguardSlice.setPaddingRelative(0, 0, 0, 0);
                    updateGravity(coolDividerOneParams,Gravity.CENTER_HORIZONTAL);
                    coolDividerOneParams.setMargins(0, margin, 0, margin);
                    coolDividerTwoParams.gravity = (Gravity.CENTER);
                    coolDividerTwoParams.setMargins(0,margin,0,margin);
                    updateGravity(coolDividerThreeParams,Gravity.CENTER_HORIZONTAL);
                    coolDividerThreeParams.setMargins(0,margin,0,margin);
                    updateGravity(weatherViewParams,Gravity.CENTER_HORIZONTAL);
                    weatherViewParams.setMargins(0,margin,0,margin);
                    break;
                case 2:
                    mTextClock.setGravity(Gravity.END);
                    mTextClock.setPaddingRelative(0, 0, updateTextClockPadding(), 0);
                    mKeyguardSlice.setGravity(Gravity.END);
                    mKeyguardSlice.setPaddingRelative(0, 0, updateTextClockPadding(), 0);
                    updateGravity(coolDividerOneParams,Gravity.END);
                    coolDividerOneParams.setMargins(0, margin, updateTextClockPadding(), margin);
                    coolDividerTwoParams.gravity = (Gravity.END);
                    coolDividerTwoParams.setMargins(0,margin,updateTextClockPadding(),margin);
                    updateGravity(coolDividerThreeParams,Gravity.END);
                    coolDividerThreeParams.setMargins(0,margin,updateTextClockPadding(),margin);
                    updateGravity(weatherViewParams,Gravity.END);
                    weatherViewParams.setMargins(0,margin,updateTextClockPadding(),margin);
                    break;
                case 3:
                    mTextClock.setGravity(Gravity.START);
                    mTextClock.setPaddingRelative(updateTextClockPadding(), 0, 0, 0);
                    mKeyguardSlice.setGravity(Gravity.END);
                    mKeyguardSlice.setPaddingRelative(0, 0, updateTextClockPadding(), 0);
                    updateGravity(coolDividerOneParams,Gravity.END);
                    coolDividerOneParams.setMargins(0, margin, updateTextClockPadding(), margin);
                    coolDividerTwoParams.gravity = (Gravity.END);
                    coolDividerTwoParams.setMargins(0,margin,updateTextClockPadding(),margin);
                    updateGravity(coolDividerThreeParams,Gravity.END);
                    coolDividerThreeParams.setMargins(0,margin,updateTextClockPadding(),margin);
                    updateGravity(weatherViewParams,Gravity.END);
                    weatherViewParams.setMargins(0,margin,updateTextClockPadding(),margin);
                    break;
                case 4:
                    mTextClock.setGravity(Gravity.END);
                    mTextClock.setPaddingRelative(0, 0, updateTextClockPadding(), 0);
                    mKeyguardSlice.setGravity(Gravity.START);
                    mKeyguardSlice.setPaddingRelative(updateTextClockPadding(), 0, 0, 0);
                    updateGravity(coolDividerOneParams,Gravity.START);
                    coolDividerOneParams.setMargins(updateTextClockPadding(), margin, 0, margin);
                    coolDividerTwoParams.gravity = (Gravity.START);
                    coolDividerTwoParams.setMargins(updateTextClockPadding(),margin,0,margin);
                    updateGravity(coolDividerThreeParams,Gravity.START);
                    coolDividerThreeParams.setMargins(updateTextClockPadding(),margin,0,margin);
                    updateGravity(weatherViewParams,Gravity.START);
                    weatherViewParams.setMargins(updateTextClockPadding(),margin,0,margin);
                    break;
            }
        } else {
            if (mAlignLeft){
                mKeyguardSlice.setGravity(Gravity.START);
                mKeyguardSlice.setPaddingRelative(updateLeftMargin(), 0, 0, 0);
                updateGravity(coolDividerOneParams,Gravity.START);
                coolDividerOneParams.setMargins(updateLeftMargin() + 12,margin,12,margin);
                coolDividerTwoParams.gravity = (Gravity.START);
                coolDividerTwoParams.setMargins(updateLeftMargin() + 12,margin,12,margin);
                updateGravity(coolDividerThreeParams,Gravity.START);
                coolDividerThreeParams.setMargins(updateLeftMargin() + 12,margin,12,margin);
                updateGravity(weatherViewParams,Gravity.START);
                weatherViewParams.setMargins(updateLeftMargin(),margin,0,margin);
                mOwnerInfo.setGravity(Gravity.START);
                mOwnerInfo.setPaddingRelative(updateLeftMargin(), 0, 0, 0);
                updateGravity(smallClockViewParams,Gravity.START);
                smallClockViewParams.setMargins(updateLeftMargin(),margin,0,margin);
                updateGravity(smartMediaContainerParams,Gravity.START);
                smartMediaContainerParams.setMargins(updateLeftMargin(),margin,0,margin);
                mClockView.setGravity(Gravity.TOP|Gravity.START);
            } else {
                mKeyguardSlice.setGravity(Gravity.CENTER_HORIZONTAL);
                mKeyguardSlice.setPaddingRelative(0, 0, 0, 0);
                updateGravity(coolDividerOneParams,Gravity.CENTER_HORIZONTAL);
                coolDividerOneParams.setMargins(0, margin, 0, margin);
                coolDividerTwoParams.gravity = (Gravity.CENTER_HORIZONTAL);
                coolDividerTwoParams.setMargins(0,margin,0,margin);
                updateGravity(coolDividerThreeParams,Gravity.CENTER_HORIZONTAL);
                coolDividerThreeParams.setMargins(0,margin,0,margin);
                updateGravity(weatherViewParams,Gravity.CENTER_HORIZONTAL);
                weatherViewParams.setMargins(0,margin,0,margin);
                mOwnerInfo.setGravity(Gravity.CENTER_HORIZONTAL);
                mOwnerInfo.setPaddingRelative(0, 0, 0, 0);
                updateGravity(smallClockViewParams,Gravity.CENTER_HORIZONTAL);
                smallClockViewParams.setMargins(0,margin,0,margin);
                updateGravity(smartMediaContainerParams,Gravity.CENTER_HORIZONTAL);
                smartMediaContainerParams.setMargins(24,margin,24,margin);
                mClockView.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
            }
        }

        mCoolDividerOne.setLayoutParams(coolDividerOneParams);
        mCoolDividerTwo.setLayoutParams(coolDividerTwoParams);
        mCoolDividerThree.setLayoutParams(coolDividerThreeParams);
        mWeatherView.setLayoutParams(weatherViewParams);
        mSmallClockView.setLayoutParams(smallClockViewParams);
        mSmartMediaContainer.setLayoutParams(smartMediaContainerParams);

    }

    private RelativeLayout.LayoutParams updateGravity(RelativeLayout.LayoutParams params, int gravity) {

          if (gravity == Gravity.START) {
              params.removeRule(RelativeLayout.ALIGN_END);
              params.addRule(RelativeLayout.ALIGN_START);
              params.removeRule(RelativeLayout.CENTER_HORIZONTAL);
          } else if (gravity == Gravity.CENTER_HORIZONTAL) {
              params.removeRule(RelativeLayout.ALIGN_END);
              params.removeRule(RelativeLayout.ALIGN_START);
              params.addRule(RelativeLayout.CENTER_HORIZONTAL);
          } else if (gravity == Gravity.END) {
              params.addRule(RelativeLayout.ALIGN_END);
              params.removeRule(RelativeLayout.ALIGN_START);
              params.removeRule(RelativeLayout.CENTER_HORIZONTAL);
          }

          return params;

    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private static final class Patterns {
        static String clockView12;
        static String clockView24;
        static String cacheKey;

        static void update(Context context) {
            final Locale locale = Locale.getDefault();
            final Resources res = context.getResources();
            final String clockView12Skel = res.getString(R.string.clock_12hr_format);
            final String clockView24Skel = res.getString(R.string.clock_24hr_format);
            final String key = locale.toString() + clockView12Skel + clockView24Skel;
            if (key.equals(cacheKey)) return;

            clockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel);
            // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
            // format.  The following code removes the AM/PM indicator if we didn't want it.
            if (!clockView12Skel.contains("a")) {
                clockView12 = clockView12.replaceAll("a", "").trim();
            }

            clockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel);

            cacheKey = key;
        }
    }

    public void setDarkAmount(float darkAmount) {
        if (mDarkAmount == darkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        mClockView.setDarkAmount(darkAmount);
        updateDark();
    }

    private void updateDark() {
        boolean dark = mDarkAmount == 1;
        if (mLogoutView != null) {
            mLogoutView.setAlpha(dark ? 0 : 1);
        }

        if (mOwnerInfo != null) {
            boolean hasText = !TextUtils.isEmpty(mOwnerInfo.getText());
            mOwnerInfo.setVisibility(hasText ? VISIBLE : GONE);
            layoutOwnerInfo();
        }

        final int blendedTextColor = ColorUtils.blendARGB(mTextColor, Color.WHITE, mDarkAmount);
        updateSettings();
    }

    private void layoutOwnerInfo() {
        if (mOwnerInfo != null && mOwnerInfo.getVisibility() != GONE) {
            // Animate owner info during wake-up transition
            mOwnerInfo.setAlpha(1f - mDarkAmount);

            float ratio = mDarkAmount;
            // Calculate how much of it we should crop in order to have a smooth transition
            int collapsed = mOwnerInfo.getTop() - mOwnerInfo.getPaddingTop();
            int expanded = mOwnerInfo.getBottom() + mOwnerInfo.getPaddingBottom();
            int toRemove = (int) ((expanded - collapsed) * ratio);
            setBottom(getMeasuredHeight() - toRemove);
            if (mNotificationIcons != null) {
                // We're using scrolling in order not to overload the translation which is used
                // when appearing the icons
                mNotificationIcons.setScrollY(toRemove);
            }
        } else if (mNotificationIcons != null){
            mNotificationIcons.setScrollY(0);
        }
    }

    public void setPulsing(boolean pulsing) {
        if (mPulsing == pulsing) {
            return;
        }
        mPulsing = pulsing;
    }

    private boolean shouldShowLogout() {
        return KeyguardUpdateMonitor.getInstance(mContext).isLogoutEnabled()
                && KeyguardUpdateMonitor.getCurrentUser() != UserHandle.USER_SYSTEM;
    }

    private void onLogoutClicked(View view) {
        int currentUserId = KeyguardUpdateMonitor.getCurrentUser();
        try {
            mIActivityManager.switchUser(UserHandle.USER_SYSTEM);
            mIActivityManager.stopUser(currentUserId, true /*force*/, null);
        } catch (RemoteException re) {
            Log.e(TAG, "Failed to logout user", re);
        }
    }

    private int updateLeftMargin() {
        final ContentResolver resolver = getContext().getContentResolver();
        int mLeftMargin = Settings.System.getIntForUser(resolver,
                Settings.System.SYNTHOS_ALIGN_LEFT_MARGIN, 55, UserHandle.USER_CURRENT);

        switch (mLeftMargin) {
            case 0:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_0);
            case 1:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_1);
            case 2:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_2);
            case 3:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_3);
            case 4:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_4);
            case 5:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_5);
            case 6:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_6);
            case 7:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_7);
            case 8:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_8);
            case 9:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_9);
            case 10:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_10);
            case 11:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_11);
            case 12:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_12);
            case 13:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_13);
            case 14:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_14);
            case 15:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_15);
            case 16:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_16);
            case 17:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_17);
            case 18:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_18);
            case 19:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_19);
            case 20:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_20);
            case 21:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_21);
            case 22:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_22);
            case 23:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_23);
            case 24:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_24);
            case 25:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_25);
            case 26:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_26);
            case 27:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_27);
            case 28:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_28);
            case 29:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_29);
            case 30:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_30);
            case 31:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_31);
            case 32:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_32);
            case 33:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_33);
            case 34:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_34);
            case 35:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_35);
            case 36:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_36);
            case 37:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_37);
            case 38:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_38);
            case 39:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_39);
            case 40:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_40);
            case 41:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_41);
            case 42:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_42);
            case 43:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_43);
            case 44:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_44);
            case 45:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_45);
            case 46:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_46);
            case 47:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_47);
            case 48:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_48);
            case 49:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_49);
            case 50:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_50);
            case 51:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_51);
            case 52:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_52);
            case 53:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_53);
            case 54:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_54);
            case 55:
            default:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_55);
            case 56:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_56);
            case 57:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_57);
            case 58:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_58);
            case 59:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_59);
            case 60:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_60);
            case 61:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_61);
            case 62:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_62);
            case 63:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_63);
            case 64:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_64);
            case 65:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_65);
            case 66:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_66);
            case 67:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_67);
            case 68:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_68);
            case 69:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_69);
            case 70:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_70);
            case 71:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_71);
            case 72:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_72);
            case 73:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_73);
            case 74:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_74);
            case 75:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_75);
            case 76:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_76);
            case 77:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_77);
            case 78:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_78);
            case 79:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_79);
            case 80:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_80);
            case 81:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_81);
            case 82:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_82);
            case 83:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_83);
            case 84:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_84);
            case 85:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_85);
            case 86:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_86);
            case 87:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_87);
            case 88:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_88);
            case 89:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_89);
            case 90:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_90);
            case 91:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_91);
            case 92:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_92);
            case 93:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_93);
            case 94:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_94);
            case 95:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_95);
            case 96:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_96);
            case 97:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_97);
            case 98:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_98);
            case 99:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_99);
            case 100:
                return (int) mContext.getResources().getDimension(R.dimen.volume_panel_padding_100);
        }
    }

    private int updateTextClockPadding() {
        final ContentResolver resolver = getContext().getContentResolver();
        int mTextClockPadding = Settings.System.getIntForUser(resolver,
                Settings.System.TEXT_CLOCK_PADDING, 55, UserHandle.USER_CURRENT);
        return mTextClockPadding;
    }
}
