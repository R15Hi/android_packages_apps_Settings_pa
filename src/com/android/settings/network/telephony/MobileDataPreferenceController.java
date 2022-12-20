/*
 * Copyright (C) 2018 The Android Open Source Project
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
 *
 * Changes from Qualcomm Innovation Center are provided under the following license:
 *
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.settings.network.telephony;

import static android.telephony.ims.feature.ImsFeature.FEATURE_MMTEL;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM;

import static androidx.lifecycle.Lifecycle.Event.ON_START;
import static androidx.lifecycle.Lifecycle.Event.ON_STOP;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.android.settings.R;
import com.android.settings.network.MobileDataContentObserver;
import com.android.settings.network.MobileNetworkRepository;
import com.android.settings.wifi.WifiPickerTrackerHelper;
import com.android.settingslib.core.lifecycle.Lifecycle;
import com.android.settingslib.mobile.dataservice.DataServiceUtils;
import com.android.settingslib.mobile.dataservice.MobileNetworkInfoEntity;
import com.android.settingslib.mobile.dataservice.SubscriptionInfoEntity;
import com.android.settingslib.mobile.dataservice.UiccInfoEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Preference controller for "Mobile data"
 */
public class MobileDataPreferenceController extends TelephonyTogglePreferenceController
        implements LifecycleObserver, MobileNetworkRepository.MobileNetworkCallback {

    private static final String DIALOG_TAG = "MobileDataDialog";
    private static final String TAG = "MobileDataPreferenceController";

    private SwitchPreference mPreference;
    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubscriptionManager;
    private MobileDataContentObserver mDataContentObserver;
    private FragmentManager mFragmentManager;
    @VisibleForTesting
    int mDialogType;
    @VisibleForTesting
    boolean mNeedDialog;

    private WifiPickerTrackerHelper mWifiPickerTrackerHelper;
    protected MobileNetworkRepository mMobileNetworkRepository;
    protected LifecycleOwner mLifecycleOwner;
    private List<SubscriptionInfoEntity> mSubscriptionInfoEntityList = new ArrayList<>();
    private List<MobileNetworkInfoEntity> mMobileNetworkInfoEntityList = new ArrayList<>();
    private int mDefaultSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    SubscriptionInfoEntity mSubscriptionInfoEntity;
    MobileNetworkInfoEntity mMobileNetworkInfoEntity;
    private DdsDataOptionStateTuner mDdsDataOptionStateTuner;

    public MobileDataPreferenceController(Context context, String key, Lifecycle lifecycle,
            LifecycleOwner lifecycleOwner, int subId) {
        super(context, key);
        mSubId = subId;
        mSubscriptionManager = context.getSystemService(SubscriptionManager.class);
        mDataContentObserver = new MobileDataContentObserver(new Handler(Looper.getMainLooper()));
        mDataContentObserver.setOnMobileDataChangedListener(() -> updateState(mPreference));
        mMobileNetworkRepository = MobileNetworkRepository.createBySubId(context, this, mSubId);
        mLifecycleOwner = lifecycleOwner;
        if (lifecycle != null) {
            lifecycle.addObserver(this);
        }
    }

    @Override
    public int getAvailabilityStatus(int subId) {
        return subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                ? AVAILABLE
                : AVAILABLE_UNSEARCHABLE;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mPreference = screen.findPreference(getPreferenceKey());
    }

    @OnLifecycleEvent(ON_START)
    public void onStart() {
        mMobileNetworkRepository.addRegister(mLifecycleOwner);
        if (mSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            // Register for nDDS sub events. What happens to the mobile data toggle in case
            // of a voice call is dependent on the device being in temp DDS state which is
            // checked in updateState()
            mDdsDataOptionStateTuner.register(mContext, mSubId);
        }
    }

    @OnLifecycleEvent(ON_STOP)
    public void onStop() {
        mMobileNetworkRepository.removeRegister();
        if (mSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            mDdsDataOptionStateTuner.unregister(mContext);
        }
    }

    @Override
    public boolean handlePreferenceTreeClick(Preference preference) {
        if (TextUtils.equals(preference.getKey(), getPreferenceKey())) {
            if (mNeedDialog) {
                showDialog(mDialogType);
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean setChecked(boolean isChecked) {
        mNeedDialog = isDialogNeeded();

        if (!mNeedDialog) {
            // Update data directly if we don't need dialog
            MobileNetworkUtils.setMobileDataEnabled(mContext, mSubId, isChecked, false);
            if (mWifiPickerTrackerHelper != null
                    && !mWifiPickerTrackerHelper.isCarrierNetworkProvisionEnabled(mSubId)) {
                mWifiPickerTrackerHelper.setCarrierNetworkEnabled(isChecked);
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean isChecked() {
        return mMobileNetworkInfoEntity == null ? false
                : mMobileNetworkInfoEntity.isMobileDataEnabled;
    }

    @Override
    public void updateState(Preference preference) {
        if (mTelephonyManager == null) {
            return;
        }
        super.updateState(preference);
        mPreference = (SwitchPreference) preference;
        update();
    }

    private void update() {

        if (mSubscriptionInfoEntity == null || mPreference == null) {
            return;
        }

        mPreference.setChecked(isChecked());
        if (mSubscriptionInfoEntity.isOpportunistic) {
            mPreference.setEnabled(false);
            mPreference.setSummary(R.string.mobile_data_settings_summary_auto_switch);
        } else {
            if (mDdsDataOptionStateTuner.isDisallowed()) {
                Log.d(TAG, "nDDS voice call in ongoing");
                // we will get inside this block only when the current instance is for the DDS
                if (isChecked()) {
                    Log.d(TAG, "Do not allow the user to turn off DDS mobile data");
                    mPreference.setEnabled(false);
                    mPreference.setSummary(
                            R.string.mobile_data_settings_summary_default_data_unavailable);
                }
            } else {
                if (TelephonyUtils.isSubsidyFeatureEnabled(mContext) &&
                        !TelephonyUtils.isSubsidySimCard(mContext,
                        mSubscriptionManager.getSlotIndex(mSubId))) {
                    mPreference.setEnabled(false);
                } else {
                    mPreference.setEnabled(true);
                }
                mPreference.setSummary(R.string.mobile_data_settings_summary);
            }
        }
        if (!mSubscriptionInfoEntity.isValidSubscription) {
            mPreference.setSelectable(false);
            mPreference.setSummary(R.string.mobile_data_settings_summary_unavailable);
        } else {
            mPreference.setSelectable(true);
        }
    }

    public void init(FragmentManager fragmentManager, int subId,
            SubscriptionInfoEntity subInfoEntity, MobileNetworkInfoEntity networkInfoEntity) {
        mFragmentManager = fragmentManager;
        mSubId = subId;

        mTelephonyManager = null;
        mTelephonyManager = getTelephonyManager();
        mSubscriptionInfoEntity = subInfoEntity;
        mMobileNetworkInfoEntity = networkInfoEntity;

        mDdsDataOptionStateTuner =
                new DdsDataOptionStateTuner(mTelephonyManager,
                        mSubscriptionManager,
                        () -> updateState(mPreference));
    }

    private TelephonyManager getTelephonyManager() {
        if (mTelephonyManager != null) {
            return mTelephonyManager;
        }
        TelephonyManager telMgr =
                mContext.getSystemService(TelephonyManager.class);
        if (mSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            telMgr = telMgr.createForSubscriptionId(mSubId);
        }
        mTelephonyManager = telMgr;
        return telMgr;
    }

    public void setWifiPickerTrackerHelper(WifiPickerTrackerHelper helper) {
        mWifiPickerTrackerHelper = helper;
    }

    @VisibleForTesting
    boolean isDialogNeeded() {
        final boolean enableData = !isChecked();
        mTelephonyManager = getTelephonyManager();
        final boolean isMultiSim = (mTelephonyManager.getActiveModemCount() > 1);
        boolean needToDisableOthers = mDefaultSubId != mSubId;
        if (mContext.getResources().getBoolean(
                 com.android.internal.R.bool.config_voice_data_sms_auto_fallback)) {
            // Mobile data of both subscriptions can be enabled
            // simultaneously. DDS setting will be controlled by the config.
            needToDisableOthers = false;
        }
        IImsRegistration imsRegistrationImpl = mTelephonyManager.getImsRegistration(
                mSubscriptionManager.getSlotIndex(mSubId), FEATURE_MMTEL);
        boolean isImsRegisteredOverCiwlan = false;
        try {
            isImsRegisteredOverCiwlan = imsRegistrationImpl.getRegistrationTechnology() ==
                    REGISTRATION_TECH_CROSS_SIM;
        } catch (RemoteException ex) {
            Log.e(TAG, "getRegistrationTechnology failed", ex);
        }
        final boolean isInNonDdsVoiceCall = mDdsDataOptionStateTuner.isInNonDdsVoiceCall();
        Log.d(TAG, "isDialogNeeded: " + "enableData=" + enableData  + ", isMultiSim=" + isMultiSim +
                ", needToDisableOthers=" + needToDisableOthers + ", isImsRegisteredOverCiwlan=" +
                isImsRegisteredOverCiwlan + ", isInNonDdsVoiceCall=" + isInNonDdsVoiceCall);
        if (enableData && isMultiSim && needToDisableOthers) {
            mDialogType = MobileDataDialogFragment.TYPE_MULTI_SIM_DIALOG;
            return true;
        }
        // If device is in a C_IWLAN call, and the user is trying to disable mobile data, display
        // the warning dialog.
        if (isInNonDdsVoiceCall && isImsRegisteredOverCiwlan && !enableData) {
            mDialogType = MobileDataDialogFragment.TYPE_DISABLE_CIWLAN_DIALOG;
            return true;
        }
        return false;
    }

    private void showDialog(int type) {
        final MobileDataDialogFragment dialogFragment = MobileDataDialogFragment.newInstance(type,
                mSubId);
        dialogFragment.show(mFragmentManager, DIALOG_TAG);
    }

    @VisibleForTesting
    public void setSubscriptionInfoEntity(SubscriptionInfoEntity subscriptionInfoEntity) {
        mSubscriptionInfoEntity = subscriptionInfoEntity;
    }

    @VisibleForTesting
    public void setMobileNetworkInfoEntity(MobileNetworkInfoEntity mobileNetworkInfoEntity) {
        mMobileNetworkInfoEntity = mobileNetworkInfoEntity;
    }

    @Override
    public void onAirplaneModeChanged(boolean airplaneModeEnabled) {
    }

    @Override
    public void onAvailableSubInfoChanged(List<SubscriptionInfoEntity> subInfoEntityList) {
    }

    @Override
    public void onActiveSubInfoChanged(List<SubscriptionInfoEntity> subInfoEntityList) {
        if (DataServiceUtils.shouldUpdateEntityList(mSubscriptionInfoEntityList,
                subInfoEntityList)) {
            mSubscriptionInfoEntityList = subInfoEntityList;
            mSubscriptionInfoEntityList.forEach(entity -> {
                if (Integer.parseInt(entity.subId) == mSubId) {
                    mSubscriptionInfoEntity = entity;
                }
            });
            if (mSubscriptionInfoEntity != null
                    && mSubscriptionInfoEntity.isDefaultDataSubscription) {
                mDefaultSubId = Integer.parseInt(mSubscriptionInfoEntity.subId);
            }
            update();
            refreshSummary(mPreference);
        }
    }

    @Override
    public void onAllUiccInfoChanged(List<UiccInfoEntity> uiccInfoEntityList) {
    }

    @Override
    public void onAllMobileNetworkInfoChanged(
            List<MobileNetworkInfoEntity> mobileNetworkInfoEntityList) {
        if (DataServiceUtils.shouldUpdateEntityList(mMobileNetworkInfoEntityList,
                mobileNetworkInfoEntityList)) {
            mMobileNetworkInfoEntityList = mobileNetworkInfoEntityList;
            mMobileNetworkInfoEntityList.forEach(entity -> {
                if (Integer.parseInt(entity.subId) == mSubId) {
                    mMobileNetworkInfoEntity = entity;
                    update();
                    refreshSummary(mPreference);
                    return;
                }
            });
        }
    }
}
