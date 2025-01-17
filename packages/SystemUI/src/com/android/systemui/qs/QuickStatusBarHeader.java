/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.annotation.ColorInt;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PorterDuff.Mode;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Shader.TileMode;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.ContextThemeWrapper;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.TextClock;

import androidx.annotation.VisibleForTesting;
import com.android.internal.util.aosip.aosipUtils;
import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.DualToneHandler;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.omni.CurrentWeatherView;
import com.android.systemui.statusbar.info.DataUsageView;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.DateView;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;

import com.android.systemui.synth.gamma.Gamma;

import java.util.Locale;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * View that contains the top-most bits of the screen (primarily the status bar with date, time, and
 * battery) and also contains the {@link QuickQSPanel} along with some of the panel's inner
 * contents.
 */
public class QuickStatusBarHeader extends RelativeLayout implements
        View.OnClickListener, NextAlarmController.NextAlarmChangeCallback,
        ZenModeController.Callback {
    private static final String TAG = "QuickStatusBarHeader";
    private static final boolean DEBUG = false;

    /** Delay for auto fading out the long press tooltip after it's fully visible (in ms). */
    private static final long AUTO_FADE_OUT_DELAY_MS = DateUtils.SECOND_IN_MILLIS * 6;
    private static final int FADE_ANIMATION_DURATION_MS = 300;
    private static final int TOOLTIP_NOT_YET_SHOWN_COUNT = 0;
    public static final int MAX_TOOLTIP_SHOWN_COUNT = 2;

    private final Handler mHandler = new Handler();
    private final NextAlarmController mAlarmController;
    private final ZenModeController mZenController;
    private final StatusBarIconController mStatusBarIconController;
    private final ActivityStarter mActivityStarter;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mListening;
    private boolean mQsDisabled;
    private boolean isShowDragHandle;
    private boolean isAlwaysShowSettings;
    private boolean isHideBattIcon;

    private QSCarrierGroup mCarrierGroup;
    protected QuickQSPanel mHeaderQsPanel;
    protected QSTileHost mHost;
    private TintedIconManager mIconManager;
    private TouchAnimator mStatusIconsAlphaAnimator;
    private TouchAnimator mStatusIconsExpandedAlphaAnimator;
    private TouchAnimator mHeaderTextContainerAlphaAnimator;
    private DualToneHandler mDualToneHandler;

    private View mSystemIconsView;
    private View mQuickQsStatusIcons;
    private View mHeaderTextContainerView;

    private int mRingerMode = AudioManager.RINGER_MODE_NORMAL;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    // Data Usage
    private View mDataUsageLayout;
    private ImageView mDataUsageImage;
    private DataUsageView mDataUsageView;

    private ImageView mNextAlarmIcon;
    /** {@link TextView} containing the actual text indicating when the next alarm will go off. */
    private TextView mNextAlarmTextView;
    private View mNextAlarmContainer;
    private View mStatusSeparator;
    private ImageView mRingerModeIcon;
    private TextView mRingerModeTextView;
    private View mRingerContainer;
    private Clock mClockView;
    private DateView mDateView;
    private TextClock mSynthClockExpandedView;
    private TextClock mSynthClockStatusView;
    private TextClock mSynthDateExpandedView;
    private View mStatusIconsContainer;
    private View mStatusIconsExpanded;
    private ViewGroup mSynthClockContainer;
    private View mStatusInfoContainer;
    private CurrentWeatherView mWeatherView;
    private BatteryMeterView mBatteryMeterView;

    private TextView mExpandedText;

    private Gamma mGamma;

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_BATTERY_MODE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_BATTERY_STYLE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_HIDE_BATTERY), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SHOW_QS_CLOCK), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_DRAG_HANDLE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_ALWAYS_SHOW_SETTINGS), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.OMNI_STATUS_BAR_CUSTOM_HEADER), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.OMNI_STATUS_BAR_CUSTOM_HEADER_IMAGE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.OMNI_STATUS_BAR_FILE_HEADER_IMAGE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_DATAUSAGE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SYNTHUI_QSEXPANDED_TEXT_SHOW), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SYNTHUI_QSEXPANDED_TEXT_STRING), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SYNTHUI_COLOR_TYPE_CLOCK_QSEXPANDED), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SYNTHUI_COLOR_TYPE_DATE_QSEXPANDED), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SYNTHUI_FONT_CLOCK_QSEXPANDED), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SYNTHUI_FONT_DATE_QSEXPANDED), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SYNTHUI_STATUSICONS_QSEXPANDED), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SYNTHUI_STATUSICONS_QS_STATUS), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SYNTHUI_STATUSINFO_QSEXPANDED), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SYNTHUI_QSEXPANDED_CLOCK_STYLE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SYNTHUI_WEATHER), true,
                    this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private SettingsObserver mSettingsObserver = new SettingsObserver(mHandler);

    // omni additions start
    private boolean mLandscape;
    private boolean mHeaderImageEnabled;
    private String mCustomHeaderImage;
    private String mCustomHeaderFile;

    private class OmniSettingsObserver extends ContentObserver {
        OmniSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.OMNI_STATUS_BAR_CUSTOM_HEADER), false,
                    this, UserHandle.USER_ALL);
            }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }
    private OmniSettingsObserver mOmniSettingsObserver = new OmniSettingsObserver(mHandler);

    private final BroadcastReceiver mRingerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mRingerMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
            updateStatusText();
        }
    };
    private boolean mHasTopCutout = false;

    @Inject
    public QuickStatusBarHeader(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            NextAlarmController nextAlarmController, ZenModeController zenModeController,
            StatusBarIconController statusBarIconController,
            ActivityStarter activityStarter) {
        super(context, attrs);
        mAlarmController = nextAlarmController;
        mZenController = zenModeController;
        mStatusBarIconController = statusBarIconController;
        mActivityStarter = activityStarter;
        mDualToneHandler = new DualToneHandler(
                new ContextThemeWrapper(context, R.style.QSHeaderTheme));
        mGamma = Dependency.get(Gamma.class);
        mSettingsObserver.observe();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mSystemIconsView = findViewById(R.id.quick_status_bar_system_icons);
        mQuickQsStatusIcons = findViewById(R.id.quick_qs_status_icons);
        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);
        iconContainer.setShouldRestrictIcons(false);
        mIconManager = new TintedIconManager(iconContainer);

        mBatteryMeterView = findViewById(R.id.battery);
        mBatteryMeterView.setForceShowPercent(true);
        mBatteryMeterView.setIgnoreTunerUpdates(true);
        mBatteryMeterView.setOnClickListener(this);
        mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE);

        // Views corresponding to the header info section (e.g. ringer and next alarm).
        mHeaderTextContainerView = findViewById(R.id.header_text_container);
        mStatusSeparator = findViewById(R.id.status_separator);
        mNextAlarmIcon = findViewById(R.id.next_alarm_icon);
        mNextAlarmTextView = findViewById(R.id.next_alarm_text);
        mNextAlarmContainer = findViewById(R.id.alarm_container);
        mNextAlarmContainer.setOnClickListener(this::onClick);
        mRingerModeIcon = findViewById(R.id.ringer_mode_icon);
        mRingerModeTextView = findViewById(R.id.ringer_mode_text);
        mRingerContainer = findViewById(R.id.ringer_container);
        mCarrierGroup = findViewById(R.id.carrier_group);

        mStatusIconsExpanded = findViewById(R.id.synthStatusIconsExpanded);
        mStatusIconsContainer = findViewById(R.id.synthStatusIconsContainer);
        mStatusInfoContainer = findViewById(R.id.synth_info_container);
        mSynthClockContainer = findViewById(R.id.synth_clock_date_container);

        @ColorInt int textColor = Utils.getColorAttrDefaultColor(getContext(),
                R.attr.wallpaperTextColor);
        float intensity = textColor == Color.WHITE ? 0 : 1;

        Rect tintArea = new Rect(0, 0, 0, 0);
        //int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
        //        android.R.attr.colorForeground);
        //float intensity = getColorIntensity(colorForeground);
        //int fillColor = mDualToneHandler.getSingleColor(intensity);

        // Set light text on the header icons because they will always be on a black background
        applyDarkness(R.id.clock, tintArea, 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        // Set the correct tint for the status icons so they contrast
        mIconManager.setTint(textColor);
        mNextAlarmIcon.setImageTintList(ColorStateList.valueOf(textColor));
        mRingerModeIcon.setImageTintList(ColorStateList.valueOf(textColor));

        mClockView = findViewById(R.id.clock);
        mClockView.setOnClickListener(this);
        mClockView.setQsHeader();
        mDateView = findViewById(R.id.date);
        mDateView.setOnClickListener(this);
        mDataUsageLayout = findViewById(R.id.daily_data_usage_layout);
        mDataUsageImage = findViewById(R.id.daily_data_usage_icon);
        mDataUsageView = findViewById(R.id.data_sim_usage);
        mWeatherView = findViewById(R.id.qs_weather_container);

        updateResources();

        mSynthClockStatusView = findViewById(R.id.SynthClock);

        mSynthClockExpandedView.setTextColor(textColor);
        mSynthClockStatusView.setTextColor(textColor);
        mSynthDateExpandedView.setTextColor(textColor);
        mDateView.setTextColor(textColor);

        //qs Expanded text by.tikkiX2

        mExpandedText = findViewById(R.id.expanded_text);

        mRingerModeTextView.setSelected(true);
        mNextAlarmTextView.setSelected(true);
        updateSettings();
    }

    private void updateStatusText() {
        boolean changed = updateRingerStatus() || updateAlarmStatus();

        if (changed) {
            boolean alarmVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
            boolean ringerVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
            mStatusSeparator.setVisibility(alarmVisible && ringerVisible ? View.VISIBLE
                    : View.GONE);
        }
    }

    private void setExpandedText() {

      boolean isShow = Settings.System.getIntForUser(mContext.getContentResolver(),
                      Settings.System.SYNTHUI_QSEXPANDED_TEXT_SHOW, 1,
                      UserHandle.USER_CURRENT) == 1;

      String text = Settings.System.getStringForUser(mContext.getContentResolver(),
                      Settings.System.SYNTHUI_QSEXPANDED_TEXT_STRING,
                      UserHandle.USER_CURRENT);

                            if(mExpandedText != null){
      if (isShow) {
          if (text == null || text == "") {
              mExpandedText.setText("#SynthOS");
              mExpandedText.setVisibility(View.VISIBLE);
          } else {
              mExpandedText.setText(text);
              mExpandedText.setVisibility(View.VISIBLE);
          }
      } else {
            mExpandedText.setVisibility(View.GONE);
      }
    }
    }

    private void setQsClockExpandedStyle() {
        int style = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.SYNTHUI_QSEXPANDED_CLOCK_STYLE, 0,
                    UserHandle.USER_CURRENT);

        int id = 0;
        LayoutInflater li = LayoutInflater.from(mContext);

        mSynthClockContainer.removeAllViews();

        switch (style) {
            case 0: // default
                id = R.layout.synth_clock_expanded_default;
                break;
            case 1: // material
                id = R.layout.synth_clock_expanded_material;
                break;
            case 2: // sammy
                id = R.layout.synth_clock_expanded_sammy;
                break;
            case 3: // material sammy
                id = R.layout.synth_clock_expanded_material_sammy;
                break;
        }

        View clockContainer = li.inflate(id, mSynthClockContainer);
        mSynthClockExpandedView = clockContainer.findViewById(R.id.SynthClockExpanded);
        mSynthDateExpandedView = clockContainer.findViewById(R.id.SynthDateExpanded);
        updateSynthText();
        updateQSClock();

    }

    private boolean updateRingerStatus() {
        boolean isOriginalVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
        CharSequence originalRingerText = mRingerModeTextView.getText();

        boolean ringerVisible = false;
        if (!ZenModeConfig.isZenOverridingRinger(mZenController.getZen(),
                mZenController.getConsolidatedPolicy())) {
            if (mRingerMode == AudioManager.RINGER_MODE_VIBRATE) {
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_vibrate);
                mRingerModeTextView.setText(R.string.qs_status_phone_vibrate);
                ringerVisible = true;
            } else if (mRingerMode == AudioManager.RINGER_MODE_SILENT) {
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_mute);
                mRingerModeTextView.setText(R.string.qs_status_phone_muted);
                ringerVisible = true;
            }
        }
        mRingerModeIcon.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerModeTextView.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerContainer.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != ringerVisible ||
                !Objects.equals(originalRingerText, mRingerModeTextView.getText());
    }

    private boolean updateAlarmStatus() {
        boolean isOriginalVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
        CharSequence originalAlarmText = mNextAlarmTextView.getText();

        boolean alarmVisible = false;
        if (mNextAlarm != null) {
            alarmVisible = true;
            mNextAlarmTextView.setText(formatNextAlarm(mNextAlarm));
        }
        mNextAlarmIcon.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmTextView.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmContainer.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != alarmVisible ||
                !Objects.equals(originalAlarmText, mNextAlarmTextView.getText());
    }

    private void applyDarkness(int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        updateResources();

        // Update color schemes in landscape to use wallpaperTextColor
        boolean shouldUseWallpaperTextColor =
                newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        mClockView.useWallpaperTextColor(shouldUseWallpaperTextColor);
        updateStatusbarProperties();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    /**
     * The height of QQS should always be the status bar height + 128dp. This is normally easy, but
     * when there is a notch involved the status bar can remain a fixed pixel size.
     */
    private void updateMinimumHeight() {
        int sbHeight = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        int qqsHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.qs_quick_header_panel_height);
        setMinimumHeight(sbHeight + qqsHeight);
    }

    private void updateResources() {
        Resources resources = mContext.getResources();
        updateMinimumHeight();

        // Update height for a few views, especially due to landscape mode restricting space.
        /*mHeaderTextContainerView.getLayoutParams().height =
                resources.getDimensionPixelSize(R.dimen.qs_header_tooltip_height);
        mHeaderTextContainerView.setLayoutParams(mHeaderTextContainerView.getLayoutParams());*/
        boolean headerImageSelected = mHeaderImageEnabled &&
                (mCustomHeaderImage != null || mCustomHeaderFile != null);

        int topMargin = resources.getDimensionPixelSize(
                R.dimen.qs_panel_secondary_top_margin) + (mHeaderImageEnabled ?
                resources.getDimensionPixelSize(R.dimen.qs_header_image_offset) : 0);

        mSystemIconsView.getLayoutParams().height = topMargin;
        mSystemIconsView.setLayoutParams(mSystemIconsView.getLayoutParams());

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (mQsDisabled) {
            lp.height = topMargin;
        } else {
            int qsHeight = resources.getDimensionPixelSize(
                    R.dimen.qs_panel_secondary_top_margin);

            if (headerImageSelected) {
                qsHeight += resources.getDimensionPixelSize(R.dimen.qs_header_image_offset);
            }

            lp.height = Math.max(getMinimumHeight(), qsHeight);
        }

        if (!isShowDragHandle && !isAlwaysShowSettings)
            lp.height -= 36; // save some space if not showing drag handle & settings icon

        setLayoutParams(lp);
        setQsClockExpandedStyle();
        updateStatusIconExpandedAlphaAnimator();
        updateStatusIconAlphaAnimator();
        updateHeaderTextContainerAlphaAnimator();
    }

    private void updateQSBatteryMode() {
        int showEstimate = Settings.System.getInt(mContext.getContentResolver(),
        Settings.System.QS_BATTERY_MODE, 0);
        if (showEstimate == 0) {
            mBatteryMeterView.setShowPercent(0);
            mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_OFF);
        } else if (showEstimate == 1) {
            mBatteryMeterView.setShowPercent(0);
            mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ON);
        } else if (showEstimate == 2) {
            mBatteryMeterView.setShowPercent(1);
            mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_OFF);
        } else if (showEstimate == 3 || showEstimate == 4) {
            mBatteryMeterView.setShowPercent(0);
            mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE);
        }
        mBatteryMeterView.updatePercentView();
        mBatteryMeterView.updateVisibility();
        mBatteryMeterView.setVisibility(isHideBattIcon ? View.GONE : View.VISIBLE);
    }

    private void updateDataUsageView() {
        if (mDataUsageView.isDataUsageEnabled() != 0) {
            if (aosipUtils.isConnected(mContext)) {
                DataUsageView.updateUsage();
                mDataUsageLayout.setVisibility(View.VISIBLE);
                mDataUsageImage.setVisibility(View.VISIBLE);
                mDataUsageView.setVisibility(View.VISIBLE);
            } else {
                mDataUsageView.setVisibility(View.GONE);
                mDataUsageImage.setVisibility(View.GONE);
                mDataUsageLayout.setVisibility(View.GONE);
            }
        } else {
            mDataUsageView.setVisibility(View.GONE);
            mDataUsageImage.setVisibility(View.GONE);
            mDataUsageLayout.setVisibility(View.GONE);
        }
    }

    private void updateSBBatteryStyle() {
        mBatteryMeterView.setBatteryStyle(Settings.System.getInt(mContext.getContentResolver(),
        Settings.System.STATUS_BAR_BATTERY_STYLE, 0));
        mBatteryMeterView.updateBatteryStyle();
        mBatteryMeterView.updatePercentView();
        mBatteryMeterView.updateVisibility();
    }

    private void updateQSClock() {
        int show = Settings.System.getInt(mContext.getContentResolver(),
        Settings.System.SHOW_QS_CLOCK, 1);
        mClockView.setClockVisibleByUser(show == 1);

        if (mSynthClockExpandedView != null) {
            mSynthClockExpandedView.setVisibility(show == 1 ? View.VISIBLE : View.GONE);
        }

        if (mSynthDateExpandedView != null) {
            mSynthDateExpandedView.setVisibility(show == 1 ? View.VISIBLE : View.GONE);
        }
    }

    private void updateStatusIconAlphaAnimator() {
        mStatusIconsAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mQuickQsStatusIcons, "alpha", 1, 0, 0)
                .build();
    }

    private void updateStatusIconExpandedAlphaAnimator() {
        mStatusIconsExpandedAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mStatusIconsExpanded, "alpha", 0, 0, 1)
                .build();
    }

    private void updateHeaderTextContainerAlphaAnimator() {
        mHeaderTextContainerAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mHeaderTextContainerView, "alpha", 0, 0, 1)
                .build();
    }

    private void updateSynthText() {

        String SYNTHUI_FONT_CLOCK_QSEXPANDED = "synthui_font_clock_qsexpanded";
        String SYNTHUI_FONT_DATE_QSEXPANDED = "synthui_font_date_qsexpanded";
        String SYNTHUI_COLOR_TYPE_CLOCK_QSEXPANDED = "synthui_color_type_clock_qsexpanded";
        String SYNTHUI_COLOR_TYPE_DATE_QSEXPANDED = "synthui_color_type_date_qsexpanded";

        if (mSynthClockExpandedView != null) {
            mGamma.setTextFontFromVarible(mSynthClockExpandedView, SYNTHUI_FONT_CLOCK_QSEXPANDED);
            mGamma.setTextColorTypeFromVariable(mSynthClockExpandedView, SYNTHUI_COLOR_TYPE_CLOCK_QSEXPANDED, null);
        }

        if (mSynthDateExpandedView != null) {
            mGamma.setTextFontFromVarible(mSynthDateExpandedView, SYNTHUI_FONT_DATE_QSEXPANDED);
            mGamma.setTextColorTypeFromVariable(mSynthDateExpandedView, SYNTHUI_COLOR_TYPE_DATE_QSEXPANDED, null);
        }

    }

    private void updateSynthStatusIcons() {

        boolean isShowExpanded = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SYNTHUI_STATUSICONS_QSEXPANDED, 0,
                        UserHandle.USER_CURRENT) == 1;
        boolean isShow = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SYNTHUI_STATUSICONS_QS_STATUS, 1,
                        UserHandle.USER_CURRENT) == 1;

        if (isShowExpanded) {
            mStatusIconsExpanded.setVisibility(View.VISIBLE);
        } else {
            mStatusIconsExpanded.setVisibility(View.GONE);
        }
        if (isShow) {
            mStatusIconsContainer.setVisibility(View.VISIBLE);
        } else {
            mStatusIconsContainer.setVisibility(View.GONE);
        }

    }

    private void updateSynthStatusInfo() {

        boolean isShow = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SYNTHUI_STATUSINFO_QSEXPANDED, 0,
                        UserHandle.USER_CURRENT) == 1;

        if (isShow) {
            mStatusInfoContainer.setVisibility(View.VISIBLE);
        } else {
            mStatusInfoContainer.setVisibility(View.GONE);
        }

    }

    private void updateWeather() {

        boolean isShow = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SYNTHUI_WEATHER, 1,
                        UserHandle.USER_CURRENT) == 1;

        if (isShow) {
            mWeatherView.setVisibility(View.VISIBLE);
            mWeatherView.disableUpdates();
            mWeatherView.enableUpdates();
        } else {
            mWeatherView.setVisibility(View.GONE);
            mWeatherView.disableUpdates();
        }

    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mHeaderQsPanel.setExpanded(expanded);
        updateEverything();
        updateDataUsageView();
        setExpandedText();
    }

    /**
     * Animates the inner contents based on the given expansion details.
     *
     * @param forceExpanded whether we should show the state expanded forcibly
     * @param expansionFraction how much the QS panel is expanded/pulled out (up to 1f)
     * @param panelTranslationY how much the panel has physically moved down vertically (required
     *                          for keyguard animations only)
     */
    public void setExpansion(boolean forceExpanded, float expansionFraction,
                             float panelTranslationY) {
        final float keyguardExpansionFraction = forceExpanded ? 1f : expansionFraction;
        if (mStatusIconsAlphaAnimator != null) {
            mStatusIconsAlphaAnimator.setPosition(keyguardExpansionFraction);
        }

        if (forceExpanded) {
            // If the keyguard is showing, we want to offset the text so that it comes in at the
            // same time as the panel as it slides down.
            mHeaderTextContainerView.setTranslationY(panelTranslationY);
        } else {
            mHeaderTextContainerView.setTranslationY(0f);
        }

        if (mHeaderTextContainerAlphaAnimator != null) {
            mHeaderTextContainerAlphaAnimator.setPosition(keyguardExpansionFraction);
            if (keyguardExpansionFraction > 0) {
                mHeaderTextContainerView.setVisibility(VISIBLE);
            } else {
                mHeaderTextContainerView.setVisibility(INVISIBLE);
            }
        }

        if (mStatusIconsExpandedAlphaAnimator != null) {
            mStatusIconsExpandedAlphaAnimator.setPosition(keyguardExpansionFraction);
        }
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        mHeaderTextContainerView.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        mQuickQsStatusIcons.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        updateResources();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mStatusBarIconController.addIconGroup(mIconManager);
        requestApplyInsets();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        DisplayCutout cutout = insets.getDisplayCutout();
        Pair<Integer, Integer> padding = PhoneStatusBarView.cornerCutoutMargins(
                cutout, getDisplay());
        if (padding == null) {
            mSystemIconsView.setPaddingRelative(
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_start),
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top),
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end),
                    0);
        } else {
            mSystemIconsView.setPadding(
                    padding.first,
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top),
                    padding.second, 0);

        }
        return super.onApplyWindowInsets(insets);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        mStatusBarIconController.removeIconGroup(mIconManager);
        super.onDetachedFromWindow();
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;
        mCarrierGroup.setListening(mListening);

        if (listening) {
            mZenController.addCallback(this);
            mAlarmController.addCallback(this);
            mContext.registerReceiver(mRingerReceiver,
                    new IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));
        } else {
            mZenController.removeCallback(this);
            mAlarmController.removeCallback(this);
            mContext.unregisterReceiver(mRingerReceiver);
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mClockView) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    AlarmClock.ACTION_SHOW_ALARMS), 0);
        } else if (v == mNextAlarmContainer && mNextAlarmContainer.isVisibleToUser()) {
            if (mNextAlarm.getShowIntent() != null) {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        mNextAlarm.getShowIntent());
            } else {
                Log.d(TAG, "No PendingIntent for next alarm. Using default intent");
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                        AlarmClock.ACTION_SHOW_ALARMS), 0);
            }
        } else if (v == mRingerContainer && mRingerContainer.isVisibleToUser()) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    Settings.ACTION_SOUND_SETTINGS), 0);
        } else if (v == mBatteryMeterView) {
            Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(new Intent(
                    Intent.ACTION_POWER_USAGE_SUMMARY),0);
        } else if (v == mDateView) {
            Uri.Builder builder = CalendarContract.CONTENT_URI.buildUpon();
            builder.appendPath("time");
            builder.appendPath(Long.toString(System.currentTimeMillis()));
            Intent todayIntent = new Intent(Intent.ACTION_VIEW, builder.build());
            Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(todayIntent, 0);
        }
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        updateStatusText();
    }

    @Override
    public void onZenChanged(int zen) {
        updateStatusText();
    }

    @Override
    public void onConfigChanged(ZenModeConfig config) {
        updateStatusText();
    }

    public void updateEverything() {
        post(() -> setClickable(!mExpanded));
    }

    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
    }

    public void setupHost(final QSTileHost host) {
        mHost = host;
        //host.setHeaderView(mExpandIndicator);
        mHeaderQsPanel.setQSPanelAndHeader(mQsPanel, this);
        mHeaderQsPanel.setHost(host, null /* No customization in header */);


        Rect tintArea = new Rect(0, 0, 0, 0);
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = getColorIntensity(colorForeground);
        int fillColor = mDualToneHandler.getSingleColor(intensity);

        // Use SystemUI context to get battery meter colors, and let it use the default tint (white)
        mBatteryMeterView.setColorsFromContext(mHost.getContext());
        mBatteryMeterView.onDarkChanged(new Rect(), 0, DarkIconDispatcher.DEFAULT_ICON_TINT);
    }

    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    private String formatNextAlarm(AlarmManager.AlarmClockInfo info) {
        if (info == null) {
            return "";
        }
        String skeleton = android.text.format.DateFormat
                .is24HourFormat(mContext, ActivityManager.getCurrentUser()) ? "EHm" : "Ehma";
        String pattern = android.text.format.DateFormat
                .getBestDateTimePattern(Locale.getDefault(), skeleton);
        return android.text.format.DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    public static float getColorIntensity(@ColorInt int color) {
        return color == Color.WHITE ? 0 : 1;
    }

    public void setMargins(int sideMargins) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            // Prevents these views from getting set a margin.
            // The Icon views all have the same padding set in XML to be aligned.
            if (v == mSystemIconsView || v == mQuickQsStatusIcons || v == mHeaderQsPanel
                    || v == mHeaderTextContainerView) {
                continue;
            }
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) v.getLayoutParams();
            lp.leftMargin = sideMargins;
            lp.rightMargin = sideMargins;
        }
    }

    private void updateSettings() {
        mHeaderImageEnabled = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.OMNI_STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        mCustomHeaderImage = Settings.System.getStringForUser(getContext().getContentResolver(),
                Settings.System.OMNI_STATUS_BAR_CUSTOM_HEADER_IMAGE,
                UserHandle.USER_CURRENT);
        mCustomHeaderFile = Settings.System.getStringForUser(getContext().getContentResolver(),
                Settings.System.OMNI_STATUS_BAR_FILE_HEADER_IMAGE,
                UserHandle.USER_CURRENT);
        isShowDragHandle = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.QS_DRAG_HANDLE, 1,
                UserHandle.USER_CURRENT) == 1;
        isAlwaysShowSettings = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.QS_ALWAYS_SHOW_SETTINGS, 0,
                UserHandle.USER_CURRENT) == 1;
        isHideBattIcon = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.QS_HIDE_BATTERY, 0,
                UserHandle.USER_CURRENT) == 1;
        updateQSBatteryMode();
        setQsClockExpandedStyle();
        updateSBBatteryStyle();
        updateQSClock();
        updateResources();
        updateStatusbarProperties();
        updateDataUsageView();
        updateSynthText();
        updateSynthStatusIcons();
        updateSynthStatusInfo();
        updateWeather();
        setExpandedText();
    }

    // Update color schemes in landscape to use wallpaperTextColor
    private void updateStatusbarProperties() {
        boolean shouldUseWallpaperTextColor = mLandscape && !mHeaderImageEnabled;
        mClockView.useWallpaperTextColor(shouldUseWallpaperTextColor);
    }
}
