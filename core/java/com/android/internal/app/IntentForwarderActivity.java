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
 * limitations under the License.
 */

package com.android.internal.app;

import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;

import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UnsupportedAppUsage;
import android.app.Activity;
import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This is used in conjunction with
 * {@link DevicePolicyManager#addCrossProfileIntentFilter} to enable intents to
 * be passed in and out of a managed profile.
 */
public class IntentForwarderActivity extends Activity  {
    @UnsupportedAppUsage
    public static String TAG = "IntentForwarderActivity";

    public static String FORWARD_INTENT_TO_PARENT
            = "com.android.internal.app.ForwardIntentToParent";

    public static String FORWARD_INTENT_TO_MANAGED_PROFILE
            = "com.android.internal.app.ForwardIntentToManagedProfile";

    private static final Set<String> ALLOWED_TEXT_MESSAGE_SCHEMES
            = new HashSet<>(Arrays.asList("sms", "smsto", "mms", "mmsto"));

    private static final String TEL_SCHEME = "tel";

    private Injector mInjector;

    private MetricsLogger mMetricsLogger;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInjector = createInjector();

        Intent intentReceived = getIntent();
        String className = intentReceived.getComponent().getClassName();
        final int targetUserId;
        final int userMessageId;
        if (className.equals(FORWARD_INTENT_TO_PARENT)) {
            userMessageId = com.android.internal.R.string.forward_intent_to_owner;
            targetUserId = getProfileParent();

            getMetricsLogger().write(
                    new LogMaker(MetricsEvent.ACTION_SWITCH_SHARE_PROFILE)
                    .setSubtype(MetricsEvent.PARENT_PROFILE));
        } else if (className.equals(FORWARD_INTENT_TO_MANAGED_PROFILE)) {
            userMessageId = com.android.internal.R.string.forward_intent_to_work;
            targetUserId = getManagedProfile();

            getMetricsLogger().write(
                    new LogMaker(MetricsEvent.ACTION_SWITCH_SHARE_PROFILE)
                    .setSubtype(MetricsEvent.MANAGED_PROFILE));
        } else {
            Slog.wtf(TAG, IntentForwarderActivity.class.getName() + " cannot be called directly");
            userMessageId = -1;
            targetUserId = UserHandle.USER_NULL;
        }
        if (targetUserId == UserHandle.USER_NULL) {
            // This covers the case where there is no parent / managed profile.
            finish();
            return;
        }

        final int callingUserId = getUserId();
        final Intent newIntent = canForward(intentReceived, targetUserId);
        if (newIntent != null) {
            if (Intent.ACTION_CHOOSER.equals(newIntent.getAction())) {
                Intent innerIntent = newIntent.getParcelableExtra(Intent.EXTRA_INTENT);
                // At this point, innerIntent is not null. Otherwise, canForward would have returned
                // false.
                innerIntent.prepareToLeaveUser(callingUserId);
                innerIntent.fixUris(callingUserId);
            } else {
                newIntent.prepareToLeaveUser(callingUserId);
            }

            final ResolveInfo ri = mInjector.resolveActivityAsUser(newIntent, MATCH_DEFAULT_ONLY,
                    targetUserId);
            try {
                startActivityAsCaller(newIntent, null, null, false, targetUserId);
            } catch (RuntimeException e) {
                int launchedFromUid = -1;
                String launchedFromPackage = "?";
                try {
                    launchedFromUid = ActivityTaskManager.getService().getLaunchedFromUid(
                            getActivityToken());
                    launchedFromPackage = ActivityTaskManager.getService().getLaunchedFromPackage(
                            getActivityToken());
                } catch (RemoteException ignored) {
                }

                Slog.wtf(TAG, "Unable to launch as UID " + launchedFromUid + " package "
                        + launchedFromPackage + ", while running in "
                        + ActivityThread.currentProcessName(), e);
            }

            if (shouldShowDisclosure(ri, intentReceived)) {
                mInjector.showToast(userMessageId, Toast.LENGTH_LONG);
            }
        } else {
            Slog.wtf(TAG, "the intent: " + intentReceived + " cannot be forwarded from user "
                    + callingUserId + " to user " + targetUserId);
        }
        finish();
    }

    private boolean shouldShowDisclosure(@Nullable ResolveInfo ri, Intent intent) {
        if (ri == null || ri.activityInfo == null) {
            return true;
        }
        if (ri.activityInfo.applicationInfo.isSystemApp()
                && (isDialerIntent(intent) || isTextMessageIntent(intent))) {
            return false;
        }
        return !isTargetResolverOrChooserActivity(ri.activityInfo);
    }

    private boolean isTextMessageIntent(Intent intent) {
        return (Intent.ACTION_SENDTO.equals(intent.getAction()) || isViewActionIntent(intent))
                && ALLOWED_TEXT_MESSAGE_SCHEMES.contains(intent.getScheme());
    }

    private boolean isDialerIntent(Intent intent) {
        return Intent.ACTION_DIAL.equals(intent.getAction())
                || Intent.ACTION_CALL.equals(intent.getAction())
                || Intent.ACTION_CALL_PRIVILEGED.equals(intent.getAction())
                || Intent.ACTION_CALL_EMERGENCY.equals(intent.getAction())
                || (isViewActionIntent(intent) && TEL_SCHEME.equals(intent.getScheme()));
    }

    private boolean isViewActionIntent(Intent intent) {
        return Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_BROWSABLE);
    }

    private boolean isTargetResolverOrChooserActivity(ActivityInfo activityInfo) {
        if (!"android".equals(activityInfo.packageName)) {
            return false;
        }
        return ResolverActivity.class.getName().equals(activityInfo.name)
            || ChooserActivity.class.getName().equals(activityInfo.name);
    }

    /**
     * Check whether the intent can be forwarded to target user. Return the intent used for
     * forwarding if it can be forwarded, {@code null} otherwise.
     */
    Intent canForward(Intent incomingIntent, int targetUserId)  {
        int sourceUserId = getUserId();
        IPackageManager packageManager = mInjector.getIPackageManager();
        ContentResolver contentResolver = getContentResolver();
        Intent forwardIntent = new Intent(incomingIntent);
        forwardIntent.addFlags(
                Intent.FLAG_ACTIVITY_FORWARD_RESULT | Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
        sanitizeIntent(forwardIntent);

        if (!canForwardInner(forwardIntent, sourceUserId, targetUserId, packageManager,
                contentResolver)) {
            return null;
        }
        if (forwardIntent.getSelector() != null) {
            sanitizeIntent(forwardIntent.getSelector());
            if (!canForwardInner(forwardIntent.getSelector(), sourceUserId, targetUserId,
                    packageManager, contentResolver)) {
                return null;
            }
        }
        return forwardIntent;
    }

    private static boolean canForwardInner(Intent intent, int sourceUserId, int targetUserId,
            IPackageManager packageManager, ContentResolver contentResolver) {
        if (Intent.ACTION_CHOOSER.equals(intent.getAction())) {
            return false;
        }
        String resolvedType = intent.resolveTypeIfNeeded(contentResolver);
        try {
            if (packageManager.canForwardTo(
                    intent, resolvedType, sourceUserId, targetUserId)) {
                return true;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "PackageManagerService is dead?");
        }
        return false;
    }

    /**
     * Returns the userId of the managed profile for this device or UserHandle.USER_NULL if there is
     * no managed profile.
     *
     * TODO: Remove the assumption that there is only one managed profile
     * on the device.
     */
    private int getManagedProfile() {
        List<UserInfo> relatedUsers = mInjector.getUserManager().getProfiles(UserHandle.myUserId());
        for (UserInfo userInfo : relatedUsers) {
            if (userInfo.isManagedProfile()) return userInfo.id;
        }
        Slog.wtf(TAG, FORWARD_INTENT_TO_MANAGED_PROFILE
                + " has been called, but there is no managed profile");
        return UserHandle.USER_NULL;
    }

    /**
     * Returns the userId of the profile parent or UserHandle.USER_NULL if there is
     * no parent.
     */
    private int getProfileParent() {
        UserInfo parent = mInjector.getUserManager().getProfileParent(UserHandle.myUserId());
        if (parent == null) {
            Slog.wtf(TAG, FORWARD_INTENT_TO_PARENT
                    + " has been called, but there is no parent");
            return UserHandle.USER_NULL;
        }
        return parent.id;
    }

    /**
     * Sanitize the intent in place.
     */
    private void sanitizeIntent(Intent intent) {
        // Apps should not be allowed to target a specific package/ component in the target user.
        intent.setPackage(null);
        intent.setComponent(null);
    }

    protected MetricsLogger getMetricsLogger() {
        if (mMetricsLogger == null) {
            mMetricsLogger = new MetricsLogger();
        }
        return mMetricsLogger;
    }

    @VisibleForTesting
    protected Injector createInjector() {
        return new InjectorImpl();
    }

    private class InjectorImpl implements Injector {

        @Override
        public IPackageManager getIPackageManager() {
            return AppGlobals.getPackageManager();
        }

        @Override
        public UserManager getUserManager() {
            return getSystemService(UserManager.class);
        }

        @Override
        public PackageManager getPackageManager() {
            return IntentForwarderActivity.this.getPackageManager();
        }

        @Override
        public ResolveInfo resolveActivityAsUser(Intent intent, int flags, int userId) {
            return getPackageManager().resolveActivityAsUser(intent, flags, userId);
        }

        @Override
        public void showToast(int messageId, int duration) {
            Toast.makeText(IntentForwarderActivity.this, getString(messageId), duration).show();
        }
    }

    public interface Injector {
        IPackageManager getIPackageManager();

        UserManager getUserManager();

        PackageManager getPackageManager();

        ResolveInfo resolveActivityAsUser(Intent intent, int flags, int userId);

        void showToast(@StringRes int messageId, int duration);
    }
}
