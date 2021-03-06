/*
 * Copyright 2016, The Android Open Source Project
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

package com.android.managedprovisioning.uiflows;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.managedprovisioning.IntentStore;
import com.android.managedprovisioning.ProvisionLogger;
import com.android.managedprovisioning.R;
import com.android.managedprovisioning.common.Utils;
import com.android.managedprovisioning.model.ProvisioningParams;
import com.android.managedprovisioning.parser.MessageParser;

/**
 * This controller manages all things related to the encryption reboot.
 *
 * <p>An encryption reminder can be scheduled using {@link #setEncryptionReminder}. This will store
 * the provisioning data to disk and enable a HOME intent receiver. After the reboot, the HOME
 * intent receiver calls {@link #resumeProvisioning} at which point a new provisioning intent is
 * sent. The reminder can be cancelled using {@link #cancelEncryptionReminder}.
 */
public class EncryptionController {

    private static final String BOOT_REMINDER_INTENT_STORE_NAME = "boot-reminder";
    private static final int NOTIFICATION_ID = 1;

    private final Context mContext;
    private final IntentStore mIntentStore;
    private final Utils mUtils;
    private final MessageParser mMessageParser;
    private final ComponentName mHomeReceiver;
    private final ResumeNotificationHelper mResumeNotificationHelper;
    private final int mUserId;

    private boolean mProvisioningResumed = false;

    private final PackageManager mPackageManager;

    private static EncryptionController sInstance;

    public static synchronized EncryptionController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new EncryptionController(context);
        }
        return sInstance;
    }

    private EncryptionController(Context context) {
        this(context,
                new IntentStore(context, BOOT_REMINDER_INTENT_STORE_NAME),
                new Utils(),
                new MessageParser(),
                new ComponentName(context, PostEncryptionActivity.class),
                new ResumeNotificationHelper(context),
                UserHandle.myUserId());
    }

    @VisibleForTesting
    EncryptionController(Context context, IntentStore intentStore, Utils utils,
            MessageParser messageParser, ComponentName homeReceiver,
            ResumeNotificationHelper resumeNotificationHelper, int userId) {
        mContext = checkNotNull(context, "Context must not be null").getApplicationContext();
        mIntentStore = checkNotNull(intentStore, "IntentStore must not be null");
        mUtils = checkNotNull(utils, "Utils must not be null");
        mMessageParser = checkNotNull(messageParser, "MessageParser must not be null");
        mHomeReceiver = checkNotNull(homeReceiver, "HomeReceiver must not be null");
        mResumeNotificationHelper = checkNotNull(resumeNotificationHelper,
                "ResumeNotificationHelper must not be null");
        mUserId = userId;

        mPackageManager = context.getPackageManager();
    }

    /**
     * Store a resume intent into the {@link IntentStore}. Provisioning will be resumed after reboot
     * using the stored intent.
     *
     * @param resumeIntent the intent to be stored.
     */
    public void setEncryptionReminder(ProvisioningParams params) {
        ProvisionLogger.logd("Setting provisioning reminder for action: "
                + params.provisioningAction);
        mIntentStore.save(mMessageParser.getIntentFromProvisioningParams(params));
        // Only enable the HOME intent receiver for flows inside SUW, as showing the notification
        // for non-SUW flows is less time cricital.
        if (!mUtils.isUserSetupCompleted(mContext)) {
            ProvisionLogger.logd("Enabling PostEncryptionActivity");
            mUtils.enableComponent(mHomeReceiver, mUserId);
            // To ensure that the enabled state has been persisted to disk, we flush the
            // restrictions.
            mPackageManager.flushPackageRestrictionsAsUser(mUserId);
        }
    }

    /**
     * Cancel the encryption reminder to avoid further resumption of encryption.
     */
    public void cancelEncryptionReminder() {
        ProvisionLogger.logd("Cancelling provisioning reminder.");
        mIntentStore.clear();
        mUtils.disableComponent(mHomeReceiver, mUserId);
    }

    /**
     * Resume provisioning after encryption has happened.
     *
     * <p>If the device has already been set up, we show a notification to resume provisioning,
     * otherwise we continue provisioning direclty.
     *
     * <p>Note that this method has to be called on the main thread.
     */
    public void resumeProvisioning() {
        // verify that this method was called on the main thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("resumeProvisioning must be called on the main thread");
        }

        if (mProvisioningResumed) {
            // If provisioning has already been resumed, don't resume it again.
            // This can happen if the HOME intent receiver was launched multiple times or the
            // BOOT_COMPLETED was received after the HOME intent receiver had already been launched.
            return;
        }

        Intent resumeIntent = mIntentStore.load();

        if (resumeIntent != null) {
            mProvisioningResumed = true;
            String action = resumeIntent.getStringExtra(MessageParser.EXTRA_PROVISIONING_ACTION);
            ProvisionLogger.logd("Provisioning resumed after encryption with action: " + action);

            if (!mUtils.isPhysicalDeviceEncrypted()) {
                ProvisionLogger.loge("Device is not encrypted after provisioning with"
                        + " action " + action + " but it should");
                return;
            }
            resumeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (mUtils.isProfileOwnerAction(action)) {
                if (mUtils.isUserSetupCompleted(mContext)) {
                    mResumeNotificationHelper.showResumeNotification(resumeIntent);
                } else {
                    mContext.startActivity(resumeIntent);
                }
            } else if (mUtils.isDeviceOwnerAction(action)) {
                mContext.startActivity(resumeIntent);
            } else {
                ProvisionLogger.loge("Unknown intent action loaded from the intent store: "
                        + action);
            }
        }
    }

    public boolean isResumePending() {
        return mIntentStore.load() != null;
    }

    @VisibleForTesting
    public static class ResumeNotificationHelper {
        private final Context mContext;

        public ResumeNotificationHelper(Context context) {
            mContext = context;
        }

        /** Create and show the provisioning reminder notification. */
        public void showResumeNotification(Intent intent) {
            NotificationManager notificationManager = (NotificationManager)
                    mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            final PendingIntent resumePendingIntent = PendingIntent.getActivity(
                    mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
            final Notification.Builder notify = new Notification.Builder(mContext)
                    .setContentIntent(resumePendingIntent)
                    .setContentTitle(mContext
                            .getString(R.string.continue_provisioning_notify_title))
                    .setContentText(mContext.getString(R.string.continue_provisioning_notify_text))
                    .setSmallIcon(com.android.internal.R.drawable.ic_corp_statusbar_icon)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setColor(mContext.getResources().getColor(
                            com.android.internal.R.color.system_notification_accent_color))
                    .setAutoCancel(true);
            notificationManager.notify(NOTIFICATION_ID, notify.build());
        }
    }
}
