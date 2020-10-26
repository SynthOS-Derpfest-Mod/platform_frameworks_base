/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import android.annotation.ColorInt;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.AlphaOptimizedFrameLayout;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.SectionHeaderView;

public class FooterView extends StackScrollerDecorView {
    private final int mClearAllTopPadding;
    private FooterViewButton mDismissButton;
    private FooterViewButton mManageButton;
    private FooterViewButton mDismissSynthButton;
    private FooterViewButton mManageSynthButton;
    private AlphaOptimizedFrameLayout mSectionSynthHeader;
    private TextView mLabel;
    private AlphaOptimizedFrameLayout mContentSynthButtons;
    private boolean mIsManageVisible = true;
    private boolean mIsManageSynthVisible = true;
    private boolean mIsDismissSynthVisible = true;
    private boolean mIsButtonsSynthVisible = true;

    public FooterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mClearAllTopPadding = context.getResources().getDimensionPixelSize(
                R.dimen.clear_all_padding_top);
    }

    @Override
    protected View findContentView() {
        return findViewById(R.id.content);
    }

    protected View findSecondaryView() {
        return findViewById(R.id.dismiss_text);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mDismissButton = (FooterViewButton) findSecondaryView();
        mManageButton = findViewById(R.id.manage_text);
        mContentSynthButtons = findViewById(R.id.content_synth_buttons);
        mDismissSynthButton = findViewById(R.id.dismiss_synth_text);
        mManageSynthButton = findViewById(R.id.manage_synth_text);
        mSectionSynthHeader = findViewById(R.id.section_synth_header);
        mLabel = findViewById(R.id.header_label);
    }

    private boolean getShowHeaders() {
        return Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.NOTIFICATION_HEADERS, 0, UserHandle.USER_CURRENT) == 1;
    }

    private boolean getCenterHeaders() {
        return Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.SYNTHOS_CENTER_NOTIFICATION_HEADERS, 1, UserHandle.USER_CURRENT) == 1;
    }

    public void setTextColor(@ColorInt int color) {
        mManageButton.setTextColor(color);
        mDismissButton.setTextColor(color);
        mManageSynthButton.setTextColor(color);
        mDismissSynthButton.setTextColor(color);
    }

    public void setManageButtonClickListener(OnClickListener listener) {
        mManageButton.setOnClickListener(listener);
        mManageSynthButton.setOnClickListener(listener);
    }

    public void setDismissButtonClickListener(OnClickListener listener) {
        mDismissButton.setOnClickListener(listener);
        mDismissSynthButton.setOnClickListener(listener);
    }

    public void setButtonsSynthVisible(boolean nowVisible, boolean animate) {
        if (mIsButtonsSynthVisible != nowVisible) {
            this.setViewVisible(mSectionSynthHeader, nowVisible && getShowHeaders(), animate, null /* endRunnable */);
            this.setViewVisible(mContentSynthButtons, nowVisible, animate, null /* endRunnable */);
            mContentSynthButtons.setVisibility(nowVisible ? View.VISIBLE : View.GONE);
            mLabel.setText(mContext.getString(R.string.notification_section_header_buttons));
            mIsButtonsSynthVisible = nowVisible;
            updateHeaderVisibility();
        }
    }

    public void setDismissSynthVisible(boolean nowVisible, boolean animate) {
        if (mIsDismissSynthVisible != nowVisible) {
            this.setViewVisible(mDismissSynthButton, nowVisible, animate, null /* endRunnable */);
            mIsDismissSynthVisible = nowVisible;
            updateHeaderVisibility();
        }
    }

    public void setManageVisible(boolean nowVisible, boolean animate) {
        if (mIsManageVisible != nowVisible) {
            this.setViewVisible(mManageButton, (nowVisible && !mIsButtonsSynthVisible), animate, null /* endRunnable */);
            mIsManageVisible = nowVisible;
        }
    }

    public void setManageSynthVisible(boolean nowVisible, boolean animate) {
        if (mIsManageSynthVisible != nowVisible) {
            this.setViewVisible(mManageSynthButton, (nowVisible && mIsButtonsSynthVisible), animate, null /* endRunnable */);
            mManageSynthButton.setVisibility(nowVisible ? View.VISIBLE : View.GONE);
            mIsManageSynthVisible = nowVisible;
            updateHeaderVisibility();
        }
    }

    public void updateHeaderVisibility() {
        boolean nowVisible;
        if (mIsManageSynthVisible || mIsDismissSynthVisible) {
            nowVisible = true;
        } else {
            nowVisible = false;
        }
        if (getCenterHeaders()) {
            mLabel.setGravity(Gravity.CENTER);
        } else {
            mLabel.setGravity(Gravity.START);
        }
        this.setViewVisible(mSectionSynthHeader, nowVisible && getShowHeaders(), true, null /* endRunnable */);
        mSectionSynthHeader.setVisibility(nowVisible && getShowHeaders() ? View.VISIBLE : View.GONE);
    }

    public boolean isOnEmptySpace(float touchX, float touchY) {
        return touchX < mContent.getX()
                || touchX > mContent.getX() + mContent.getWidth()
                || touchY < mContent.getY()
                || touchY > mContent.getY() + mContent.getHeight();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDismissButton.setText(R.string.clear_all_notifications_text);
        mDismissButton.setContentDescription(
                mContext.getString(R.string.accessibility_clear_all));
        mManageButton.setText(R.string.manage_notifications_text);
        mManageButton.setContentDescription(
                mContext.getString(R.string.accessibility_manage_notification));
        mDismissSynthButton.setText(R.string.clear_all_notifications_text);
        mDismissSynthButton.setContentDescription(
                mContext.getString(R.string.accessibility_clear_all));
        mManageSynthButton.setText(R.string.manage_notifications_text);
        mManageSynthButton.setContentDescription(
                mContext.getString(R.string.accessibility_manage_notification));
        mLabel.setText(
                mContext.getString(R.string.notification_section_header_buttons));
    }

    public boolean isButtonVisible() {
        return mManageButton.getAlpha() != 0.0f;
    }

    @Override
    public ExpandableViewState createExpandableViewState() {
        return new FooterViewState();
    }

    public class FooterViewState extends ExpandableViewState {
        @Override
        public void applyToView(View view) {
            super.applyToView(view);
            if (view instanceof FooterView) {
                FooterView footerView = (FooterView) view;
                boolean visible = this.clipTopAmount < mClearAllTopPadding;
                footerView.setContentVisible(visible && footerView.isVisible());
            }
        }
    }
}
