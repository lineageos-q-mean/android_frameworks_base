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

package com.android.server.voiceinteraction;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionService;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.VoiceInteractionManagerInternal;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionServiceInfo;
import android.service.voice.VoiceInteractionSession;
import android.speech.RecognitionService;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.TimingsTraceLog;

import com.android.internal.app.IVoiceActionCheckCallback;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.app.IVoiceInteractionSessionListener;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.UiThread;
import com.android.server.soundtrigger.SoundTriggerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * SystemService that publishes an IVoiceInteractionManagerService.
 */
public class VoiceInteractionManagerService extends SystemService {
    static final String TAG = "VoiceInteractionManagerService";
    static final boolean DEBUG = false;

    final Context mContext;
    final ContentResolver mResolver;
    final DatabaseHelper mDbHelper;
    final ActivityManagerInternal mAmInternal;
    final ActivityTaskManagerInternal mAtmInternal;
    final UserManager mUserManager;
    final ArraySet<Integer> mLoadedKeyphraseIds = new ArraySet<>();
    ShortcutServiceInternal mShortcutServiceInternal;
    SoundTriggerInternal mSoundTriggerInternal;

    private final RemoteCallbackList<IVoiceInteractionSessionListener>
            mVoiceInteractionSessionListeners = new RemoteCallbackList<>();

    public VoiceInteractionManagerService(Context context) {
        super(context);
        mContext = context;
        mResolver = context.getContentResolver();
        mDbHelper = new DatabaseHelper(context);
        mServiceStub = new VoiceInteractionManagerServiceStub();
        mAmInternal = Preconditions.checkNotNull(
                LocalServices.getService(ActivityManagerInternal.class));
        mAtmInternal = Preconditions.checkNotNull(
                LocalServices.getService(ActivityTaskManagerInternal.class));
        mUserManager = Preconditions.checkNotNull(
                context.getSystemService(UserManager.class));

        PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        packageManagerInternal.setVoiceInteractionPackagesProvider(
                new PackageManagerInternal.PackagesProvider() {
            @Override
            public String[] getPackages(int userId) {
                mServiceStub.initForUser(userId);
                ComponentName interactor = mServiceStub.getCurInteractor(userId);
                if (interactor != null) {
                    return new String[] {interactor.getPackageName()};
                }
                return null;
            }
        });
    }

    @Override
    public void onStart() {
        publishBinderService(Context.VOICE_INTERACTION_MANAGER_SERVICE, mServiceStub);
        publishLocalService(VoiceInteractionManagerInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            mShortcutServiceInternal = Preconditions.checkNotNull(
                    LocalServices.getService(ShortcutServiceInternal.class));
            mSoundTriggerInternal = LocalServices.getService(SoundTriggerInternal.class);
        } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            mServiceStub.systemRunning(isSafeMode());
        }
    }

    @Override
    public void onStartUser(int userHandle) {
        mServiceStub.initForUser(userHandle);
    }

    @Override
    public void onUnlockUser(int userHandle) {
        mServiceStub.initForUser(userHandle);
        mServiceStub.switchImplementationIfNeeded(false);
    }

    @Override
    public void onSwitchUser(int userHandle) {
        mServiceStub.switchUser(userHandle);
    }

    class LocalService extends VoiceInteractionManagerInternal {
        @Override
        public void startLocalVoiceInteraction(IBinder callingActivity, Bundle options) {
            if (DEBUG) {
                Slog.i(TAG, "startLocalVoiceInteraction " + callingActivity);
            }
            VoiceInteractionManagerService.this.mServiceStub.startLocalVoiceInteraction(
                    callingActivity, options);
        }

        @Override
        public boolean supportsLocalVoiceInteraction() {
            return VoiceInteractionManagerService.this.mServiceStub.supportsLocalVoiceInteraction();
        }

        @Override
        public void stopLocalVoiceInteraction(IBinder callingActivity) {
            if (DEBUG) {
                Slog.i(TAG, "stopLocalVoiceInteraction " + callingActivity);
            }
            VoiceInteractionManagerService.this.mServiceStub.stopLocalVoiceInteraction(
                    callingActivity);
        }
    }

    // implementation entry point and binder service
    private final VoiceInteractionManagerServiceStub mServiceStub;

    class VoiceInteractionManagerServiceStub extends IVoiceInteractionManagerService.Stub {

        VoiceInteractionManagerServiceImpl mImpl;

        private boolean mSafeMode;
        private int mCurUser;
        private boolean mCurUserUnlocked;
        private final boolean mEnableService;

        VoiceInteractionManagerServiceStub() {
            mEnableService = shouldEnableService(mContext);
            new RoleObserver(mContext.getMainExecutor());
        }

        // TODO: VI Make sure the caller is the current user or profile
        void startLocalVoiceInteraction(final IBinder token, Bundle options) {
            if (mImpl == null) return;

            final long caller = Binder.clearCallingIdentity();
            try {
                mImpl.showSessionLocked(options,
                        VoiceInteractionSession.SHOW_SOURCE_ACTIVITY,
                        new IVoiceInteractionSessionShowCallback.Stub() {
                            @Override
                            public void onFailed() {
                            }

                            @Override
                            public void onShown() {
                                mAtmInternal.onLocalVoiceInteractionStarted(token,
                                        mImpl.mActiveSession.mSession,
                                        mImpl.mActiveSession.mInteractor);
                            }
                        },
                        token);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        public void stopLocalVoiceInteraction(IBinder callingActivity) {
            if (mImpl == null) return;

            final long caller = Binder.clearCallingIdentity();
            try {
                mImpl.finishLocked(callingActivity, true);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        public boolean supportsLocalVoiceInteraction() {
            if (mImpl == null) return false;

            return mImpl.supportsLocalVoiceInteraction();
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (RuntimeException e) {
                // The activity manager only throws security exceptions, so let's
                // log all others.
                if (!(e instanceof SecurityException)) {
                    Slog.wtf(TAG, "VoiceInteractionManagerService Crash", e);
                }
                throw e;
            }
        }

        public void initForUser(@UserIdInt int userHandle) {
            final TimingsTraceLog t = new TimingsTraceLog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
            t.traceBegin("initForUser(" + userHandle + ")");
            try {
                initForUserNoTracing(userHandle);
            } finally {
                t.traceEnd();
            }
        }

        private void initForUserNoTracing(@UserIdInt int userHandle) {
            if (DEBUG) Slog.d(TAG, "**************** initForUser user=" + userHandle);
            String curInteractorStr = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.VOICE_INTERACTION_SERVICE, userHandle);
            ComponentName curRecognizer = getCurRecognizer(userHandle);
            VoiceInteractionServiceInfo curInteractorInfo = null;
            if (DEBUG) Slog.d(TAG, "curInteractorStr=" + curInteractorStr
                    + " curRecognizer=" + curRecognizer);
            if (curInteractorStr == null && curRecognizer != null && mEnableService) {
                // If there is no interactor setting, that means we are upgrading
                // from an older platform version.  If the current recognizer is not
                // set or matches the preferred recognizer, then we want to upgrade
                // the user to have the default voice interaction service enabled.
                // Note that we don't do this for low-RAM devices, since we aren't
                // supporting voice interaction services there.
                curInteractorInfo = findAvailInteractor(userHandle, curRecognizer.getPackageName());
                if (curInteractorInfo != null) {
                    // Looks good!  We'll apply this one.  To make it happen, we clear the
                    // recognizer so that we don't think we have anything set and will
                    // re-apply the settings.
                    if (DEBUG) Slog.d(TAG, "No set interactor, found avail: "
                            + curInteractorInfo.getServiceInfo().name);
                    curRecognizer = null;
                }
            }

            // If forceInteractorPackage exists, try to apply the interactor from this package if
            // possible and ignore the regular interactor setting.
            String forceInteractorPackage =
                    getForceVoiceInteractionServicePackage(mContext.getResources());
            if (forceInteractorPackage != null) {
                curInteractorInfo = findAvailInteractor(userHandle, forceInteractorPackage);
                if (curInteractorInfo != null) {
                    // We'll apply this one. Clear the recognizer and re-apply the settings.
                    curRecognizer = null;
                }
            }

            // If we are on a svelte device, make sure an interactor is not currently
            // enabled; if it is, turn it off.
            if (!mEnableService && curInteractorStr != null) {
                if (!TextUtils.isEmpty(curInteractorStr)) {
                    if (DEBUG) Slog.d(TAG, "Svelte device; disabling interactor");
                    setCurInteractor(null, userHandle);
                    curInteractorStr = "";
                }
            }

            if (curRecognizer != null) {
                // If we already have at least a recognizer, then we probably want to
                // leave things as they are...  unless something has disappeared.
                IPackageManager pm = AppGlobals.getPackageManager();
                ServiceInfo interactorInfo = null;
                ServiceInfo recognizerInfo = null;
                ComponentName curInteractor = !TextUtils.isEmpty(curInteractorStr)
                        ? ComponentName.unflattenFromString(curInteractorStr) : null;
                try {
                    recognizerInfo = pm.getServiceInfo(curRecognizer,
                            PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userHandle);
                    if (curInteractor != null) {
                        interactorInfo = pm.getServiceInfo(curInteractor,
                                PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userHandle);
                    }
                } catch (RemoteException e) {
                }
                // If the apps for the currently set components still exist, then all is okay.
                if (recognizerInfo != null && (curInteractor == null || interactorInfo != null)) {
                    if (DEBUG) Slog.d(TAG, "Current interactor/recognizer okay, done!");
                    return;
                }
                if (DEBUG) Slog.d(TAG, "Bad recognizer (" + recognizerInfo + ") or interactor ("
                        + interactorInfo + ")");
            }

            // Initializing settings, look for an interactor first (but only on non-svelte).
            if (curInteractorInfo == null && mEnableService) {
                curInteractorInfo = findAvailInteractor(userHandle, null);
            }

            if (curInteractorInfo != null) {
                // Eventually it will be an error to not specify this.
                setCurInteractor(new ComponentName(curInteractorInfo.getServiceInfo().packageName,
                        curInteractorInfo.getServiceInfo().name), userHandle);
                if (curInteractorInfo.getRecognitionService() != null) {
                    setCurRecognizer(
                            new ComponentName(curInteractorInfo.getServiceInfo().packageName,
                                    curInteractorInfo.getRecognitionService()), userHandle);
                    return;
                }
            }

            // No voice interactor, we'll just set up a simple recognizer.
            initSimpleRecognizer(curInteractorInfo, userHandle);
        }

        public void initSimpleRecognizer(VoiceInteractionServiceInfo curInteractorInfo,
                int userHandle) {
            ComponentName curRecognizer = findAvailRecognizer(null, userHandle);
            if (curRecognizer != null) {
                if (curInteractorInfo == null) {
                    setCurInteractor(null, userHandle);
                }
                setCurRecognizer(curRecognizer, userHandle);
            }
        }

        private boolean shouldEnableService(Context context) {
            // VoiceInteractionService should not be enabled on devices that have not declared the
            // recognition feature (including low-ram devices where notLowRam="true" takes effect),
            // unless the device's configuration has explicitly set the config flag for a fixed
            // voice interaction service.
            if (getForceVoiceInteractionServicePackage(context.getResources()) != null) {
                return true;
            }
            return context.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_VOICE_RECOGNIZERS);
        }

        private String getForceVoiceInteractionServicePackage(Resources res) {
            String interactorPackage =
                    res.getString(com.android.internal.R.string.config_forceVoiceInteractionServicePackage);
            return TextUtils.isEmpty(interactorPackage) ? null : interactorPackage;
        }

        public void systemRunning(boolean safeMode) {
            mSafeMode = safeMode;

            mPackageMonitor.register(mContext, BackgroundThread.getHandler().getLooper(),
                    UserHandle.ALL, true);
            new SettingsObserver(UiThread.getHandler());

            synchronized (this) {
                mCurUser = ActivityManager.getCurrentUser();
                switchImplementationIfNeededLocked(false);
            }
        }

        public void switchUser(int userHandle) {
            FgThread.getHandler().post(() -> {
                synchronized (this) {
                    mCurUser = userHandle;
                    mCurUserUnlocked = false;
                    switchImplementationIfNeededLocked(false);
                }
            });
        }

        void switchImplementationIfNeeded(boolean force) {
            synchronized (this) {
                switchImplementationIfNeededLocked(force);
            }
        }

        void switchImplementationIfNeededLocked(boolean force) {
            final TimingsTraceLog t = new TimingsTraceLog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
            t.traceBegin("switchImplementation(" + mCurUser + ")");
            try {
                switchImplementationIfNeededNoTracingLocked(force);
            } finally {
                t.traceEnd();
            }
        }

        void switchImplementationIfNeededNoTracingLocked(boolean force) {
            if (!mSafeMode) {
                String curService = Settings.Secure.getStringForUser(
                        mResolver, Settings.Secure.VOICE_INTERACTION_SERVICE, mCurUser);
                ComponentName serviceComponent = null;
                ServiceInfo serviceInfo = null;
                if (curService != null && !curService.isEmpty()) {
                    try {
                        serviceComponent = ComponentName.unflattenFromString(curService);
                        serviceInfo = AppGlobals.getPackageManager()
                                .getServiceInfo(serviceComponent, 0, mCurUser);
                    } catch (RuntimeException | RemoteException e) {
                        Slog.wtf(TAG, "Bad voice interaction service name " + curService, e);
                        serviceComponent = null;
                        serviceInfo = null;
                    }
                }

                final boolean hasComponent = serviceComponent != null && serviceInfo != null;

                if (mUserManager.isUserUnlockingOrUnlocked(mCurUser)) {
                    if (hasComponent) {
                        mShortcutServiceInternal.setShortcutHostPackage(TAG,
                                serviceComponent.getPackageName(), mCurUser);
                        mAtmInternal.setAllowAppSwitches(TAG,
                                serviceInfo.applicationInfo.uid, mCurUser);
                    } else {
                        mShortcutServiceInternal.setShortcutHostPackage(TAG, null, mCurUser);
                        mAtmInternal.setAllowAppSwitches(TAG, -1, mCurUser);
                    }
                }

                if (force || mImpl == null || mImpl.mUser != mCurUser
                        || !mImpl.mComponent.equals(serviceComponent)) {
                    unloadAllKeyphraseModels();
                    if (mImpl != null) {
                        mImpl.shutdownLocked();
                    }
                    if (hasComponent) {
                        setImplLocked(new VoiceInteractionManagerServiceImpl(mContext,
                                UiThread.getHandler(), this, mCurUser, serviceComponent));
                        mImpl.startLocked();
                    } else {
                        setImplLocked(null);
                    }
                }
            }
        }

        VoiceInteractionServiceInfo findAvailInteractor(int userHandle, String packageName) {
            List<ResolveInfo> available =
                    mContext.getPackageManager().queryIntentServicesAsUser(
                            new Intent(VoiceInteractionService.SERVICE_INTERFACE),
                            PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                                    | PackageManager.MATCH_DEBUG_TRIAGED_MISSING, userHandle);
            int numAvailable = available.size();

            if (numAvailable == 0) {
                Slog.w(TAG, "no available voice interaction services found for user " + userHandle);
                return null;
            } else {
                // Find first system package.  We never want to allow third party services to
                // be automatically selected, because those require approval of the user.
                VoiceInteractionServiceInfo foundInfo = null;
                for (int i=0; i<numAvailable; i++) {
                    ServiceInfo cur = available.get(i).serviceInfo;
                    if ((cur.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                        ComponentName comp = new ComponentName(cur.packageName, cur.name);
                        try {
                            VoiceInteractionServiceInfo info = new VoiceInteractionServiceInfo(
                                    mContext.getPackageManager(), comp, userHandle);
                            if (info.getParseError() == null) {
                                if (packageName == null || info.getServiceInfo().packageName.equals(
                                        packageName)) {
                                    if (foundInfo == null) {
                                        foundInfo = info;
                                    } else {
                                        Slog.w(TAG, "More than one voice interaction service, "
                                                + "picking first "
                                                + new ComponentName(
                                                        foundInfo.getServiceInfo().packageName,
                                                        foundInfo.getServiceInfo().name)
                                                + " over "
                                                + new ComponentName(cur.packageName, cur.name));
                                    }
                                }
                            } else {
                                Slog.w(TAG, "Bad interaction service " + comp + ": "
                                        + info.getParseError());
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            Slog.w(TAG, "Failure looking up interaction service " + comp);
                        }
                    }
                }

                return foundInfo;
            }
        }

        ComponentName getCurInteractor(int userHandle) {
            String curInteractor = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.VOICE_INTERACTION_SERVICE, userHandle);
            if (TextUtils.isEmpty(curInteractor)) {
                return null;
            }
            if (DEBUG) Slog.d(TAG, "getCurInteractor curInteractor=" + curInteractor
                    + " user=" + userHandle);
            return ComponentName.unflattenFromString(curInteractor);
        }

        void setCurInteractor(ComponentName comp, int userHandle) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.VOICE_INTERACTION_SERVICE,
                    comp != null ? comp.flattenToShortString() : "", userHandle);
            if (DEBUG) Slog.d(TAG, "setCurInteractor comp=" + comp
                    + " user=" + userHandle);
        }

        ComponentName findAvailRecognizer(String prefPackage, int userHandle) {
            List<ResolveInfo> available =
                    mContext.getPackageManager().queryIntentServicesAsUser(
                            new Intent(RecognitionService.SERVICE_INTERFACE),
                            PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userHandle);
            int numAvailable = available.size();

            if (numAvailable == 0) {
                Slog.w(TAG, "no available voice recognition services found for user " + userHandle);
                return null;
            } else {
                if (prefPackage != null) {
                    for (int i=0; i<numAvailable; i++) {
                        ServiceInfo serviceInfo = available.get(i).serviceInfo;
                        if (prefPackage.equals(serviceInfo.packageName)) {
                            return new ComponentName(serviceInfo.packageName, serviceInfo.name);
                        }
                    }
                }
                if (numAvailable > 1) {
                    Slog.w(TAG, "more than one voice recognition service found, picking first");
                }

                ServiceInfo serviceInfo = available.get(0).serviceInfo;
                return new ComponentName(serviceInfo.packageName, serviceInfo.name);
            }
        }

        ComponentName getCurRecognizer(int userHandle) {
            String curRecognizer = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.VOICE_RECOGNITION_SERVICE, userHandle);
            if (TextUtils.isEmpty(curRecognizer)) {
                return null;
            }
            if (DEBUG) Slog.d(TAG, "getCurRecognizer curRecognizer=" + curRecognizer
                    + " user=" + userHandle);
            return ComponentName.unflattenFromString(curRecognizer);
        }

        void setCurRecognizer(ComponentName comp, int userHandle) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.VOICE_RECOGNITION_SERVICE,
                    comp != null ? comp.flattenToShortString() : "", userHandle);
            if (DEBUG) Slog.d(TAG, "setCurRecognizer comp=" + comp
                    + " user=" + userHandle);
        }

        ComponentName getCurAssistant(int userHandle) {
            String curAssistant = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.ASSISTANT, userHandle);
            if (TextUtils.isEmpty(curAssistant)) {
                return null;
            }
            if (DEBUG) Slog.d(TAG, "getCurAssistant curAssistant=" + curAssistant
                    + " user=" + userHandle);
            return ComponentName.unflattenFromString(curAssistant);
        }

        void resetCurAssistant(int userHandle) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.ASSISTANT, null, userHandle);
        }

        @Override
        public void showSession(IVoiceInteractionService service, Bundle args, int flags) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService(service);

                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.showSessionLocked(args, flags, null, null);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public boolean deliverNewSession(IBinder token, IVoiceInteractionSession session,
                IVoiceInteractor interactor) {
            synchronized (this) {
                if (mImpl == null) {
                    throw new SecurityException(
                            "deliverNewSession without running voice interaction service");
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.deliverNewSessionLocked(token, session, interactor);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public boolean showSessionFromSession(IBinder token, Bundle sessionArgs, int flags) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "showSessionFromSession without running voice interaction service");
                    return false;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.showSessionLocked(sessionArgs, flags, null, null);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public boolean hideSessionFromSession(IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "hideSessionFromSession without running voice interaction service");
                    return false;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.hideSessionLocked();
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int startVoiceActivity(IBinder token, Intent intent, String resolvedType) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "startVoiceActivity without running voice interaction service");
                    return ActivityManager.START_CANCELED;
                }
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.startVoiceActivityLocked(callingPid, callingUid, token,
                            intent, resolvedType);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int startAssistantActivity(IBinder token, Intent intent, String resolvedType) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "startAssistantActivity without running voice interaction service");
                    return ActivityManager.START_CANCELED;
                }
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.startAssistantActivityLocked(callingPid, callingUid, token,
                            intent, resolvedType);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void requestDirectActions(@NonNull IBinder token, int taskId,
                @NonNull IBinder assistToken, @Nullable RemoteCallback cancellationCallback,
                @NonNull RemoteCallback resultCallback) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "requestDirectActions without running voice interaction service");
                    resultCallback.sendResult(null);
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.requestDirectActionsLocked(token, taskId, assistToken,
                            cancellationCallback, resultCallback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void performDirectAction(@NonNull IBinder token, @NonNull String actionId,
                @NonNull Bundle arguments, int taskId, IBinder assistToken,
                @Nullable RemoteCallback cancellationCallback,
                @NonNull RemoteCallback resultCallback) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "performDirectAction without running voice interaction service");
                    resultCallback.sendResult(null);
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.performDirectActionLocked(token, actionId, arguments, taskId,
                            assistToken, cancellationCallback, resultCallback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void setKeepAwake(IBinder token, boolean keepAwake) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "setKeepAwake without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.setKeepAwakeLocked(token, keepAwake);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void closeSystemDialogs(IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "closeSystemDialogs without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.closeSystemDialogsLocked(token);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void finish(IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "finish without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.finishLocked(token, false);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void setDisabledShowContext(int flags) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "setDisabledShowContext without running voice interaction service");
                    return;
                }
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.setDisabledShowContextLocked(callingUid, flags);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int getDisabledShowContext() {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "getDisabledShowContext without running voice interaction service");
                    return 0;
                }
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.getDisabledShowContextLocked(callingUid);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int getUserDisabledShowContext() {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG,
                            "getUserDisabledShowContext without running voice interaction service");
                    return 0;
                }
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.getUserDisabledShowContextLocked(callingUid);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        //----------------- Model management APIs --------------------------------//

        @Override
        public KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId, String bcp47Locale) {
            enforceCallingPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES);

            if (bcp47Locale == null) {
                throw new IllegalArgumentException("Illegal argument(s) in getKeyphraseSoundModel");
            }

            final int callingUid = UserHandle.getCallingUserId();
            final long caller = Binder.clearCallingIdentity();
            try {
                return mDbHelper.getKeyphraseSoundModel(keyphraseId, callingUid, bcp47Locale);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public int updateKeyphraseSoundModel(KeyphraseSoundModel model) {
            enforceCallingPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES);
            if (model == null) {
                throw new IllegalArgumentException("Model must not be null");
            }

            final long caller = Binder.clearCallingIdentity();
            try {
                if (mDbHelper.updateKeyphraseSoundModel(model)) {
                    synchronized (this) {
                        // Notify the voice interaction service of a change in sound models.
                        if (mImpl != null && mImpl.mService != null) {
                            mImpl.notifySoundModelsChangedLocked();
                        }
                    }
                    return SoundTriggerInternal.STATUS_OK;
                } else {
                    return SoundTriggerInternal.STATUS_ERROR;
                }
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public int deleteKeyphraseSoundModel(int keyphraseId, String bcp47Locale) {
            enforceCallingPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES);

            if (bcp47Locale == null) {
                throw new IllegalArgumentException(
                        "Illegal argument(s) in deleteKeyphraseSoundModel");
            }

            final int callingUid = UserHandle.getCallingUserId();
            final long caller = Binder.clearCallingIdentity();
            boolean deleted = false;
            try {
                int unloadStatus = mSoundTriggerInternal.unloadKeyphraseModel(keyphraseId);
                if (unloadStatus != SoundTriggerInternal.STATUS_OK) {
                    Slog.w(TAG, "Unable to unload keyphrase sound model:" + unloadStatus);
                }
                deleted = mDbHelper.deleteKeyphraseSoundModel(keyphraseId, callingUid, bcp47Locale);
                return deleted ? SoundTriggerInternal.STATUS_OK : SoundTriggerInternal.STATUS_ERROR;
            } finally {
                if (deleted) {
                    synchronized (this) {
                        // Notify the voice interaction service of a change in sound models.
                        if (mImpl != null && mImpl.mService != null) {
                            mImpl.notifySoundModelsChangedLocked();
                        }
                        mLoadedKeyphraseIds.remove(keyphraseId);
                    }
                }
                Binder.restoreCallingIdentity(caller);
            }
        }

        //----------------- SoundTrigger APIs --------------------------------//
        @Override
        public boolean isEnrolledForKeyphrase(IVoiceInteractionService service, int keyphraseId,
                String bcp47Locale) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService(service);
            }

            if (bcp47Locale == null) {
                throw new IllegalArgumentException("Illegal argument(s) in isEnrolledForKeyphrase");
            }

            final int callingUid = UserHandle.getCallingUserId();
            final long caller = Binder.clearCallingIdentity();
            try {
                KeyphraseSoundModel model =
                        mDbHelper.getKeyphraseSoundModel(keyphraseId, callingUid, bcp47Locale);
                return model != null;
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public ModuleProperties getDspModuleProperties(IVoiceInteractionService service) {
            // Allow the call if it is granted CAPTURE_AUDIO_HOTWORD.
            enforceCallingPermission(Manifest.permission.CAPTURE_AUDIO_HOTWORD);

            // Allow the call if this is the current voice interaction service.
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService(service);

                final long caller = Binder.clearCallingIdentity();
                try {
                    return mSoundTriggerInternal.getModuleProperties();
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int startRecognition(IVoiceInteractionService service, int keyphraseId,
                String bcp47Locale, IRecognitionStatusCallback callback,
                RecognitionConfig recognitionConfig) {
            // Allow the call if it is granted RECORD_AUDIO and CAPTURE_AUDIO_HOTWORD.
            enforceAlwaysOnHotwordPermissions();

            // Allow the call if this is the current voice interaction service.
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService(service);

                if (callback == null || recognitionConfig == null || bcp47Locale == null) {
                    throw new IllegalArgumentException("Illegal argument(s) in startRecognition");
                }
            }

            int callingUid = UserHandle.getCallingUserId();
            final long caller = Binder.clearCallingIdentity();
            try {
                KeyphraseSoundModel soundModel =
                        mDbHelper.getKeyphraseSoundModel(keyphraseId, callingUid, bcp47Locale);
                if (soundModel == null
                        || soundModel.uuid == null
                        || soundModel.keyphrases == null) {
                    Slog.w(TAG, "No matching sound model found in startRecognition");
                    return SoundTriggerInternal.STATUS_ERROR;
                } else {
                    // Regardless of the status of the start recognition, we need to make sure
                    // that we unload this model if needed later.
                    synchronized (this) {
                        mLoadedKeyphraseIds.add(keyphraseId);
                    }
                    return mSoundTriggerInternal.startRecognition(
                            keyphraseId, soundModel, callback, recognitionConfig);
                }
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public int stopRecognition(IVoiceInteractionService service, int keyphraseId,
                IRecognitionStatusCallback callback) {
            // Allow the call if it is granted RECORD_AUDIO and CAPTURE_AUDIO_HOTWORD.
            enforceAlwaysOnHotwordPermissions();

            // Allow the call if this is the current voice interaction service.
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService(service);
            }

            final long caller = Binder.clearCallingIdentity();
            try {
                return mSoundTriggerInternal.stopRecognition(keyphraseId, callback);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        private synchronized void unloadAllKeyphraseModels() {
            for (int i = 0; i < mLoadedKeyphraseIds.size(); i++) {
                final long caller = Binder.clearCallingIdentity();
                try {
                    int status = mSoundTriggerInternal.unloadKeyphraseModel(
                            mLoadedKeyphraseIds.valueAt(i));
                    if (status != SoundTriggerInternal.STATUS_OK) {
                        Slog.w(TAG, "Failed to unload keyphrase " + mLoadedKeyphraseIds.valueAt(i)
                                + ":" + status);
                    }
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
            mLoadedKeyphraseIds.clear();
        }

        @Override
        public ComponentName getActiveServiceComponentName() {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                return mImpl != null ? mImpl.mComponent : null;
            }
        }

        @Override
        public boolean showSessionForActiveService(Bundle args, int sourceFlags,
                IVoiceInteractionSessionShowCallback showCallback, IBinder activityToken) {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "showSessionForActiveService without running voice interaction"
                            + "service");
                    return false;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.showSessionLocked(args,
                            sourceFlags
                                    | VoiceInteractionSession.SHOW_WITH_ASSIST
                                    | VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
                            showCallback, activityToken);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void hideCurrentSession() throws RemoteException {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                if (mImpl == null) {
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    if (mImpl.mActiveSession != null && mImpl.mActiveSession.mSession != null) {
                        try {
                            mImpl.mActiveSession.mSession.closeSystemDialogs();
                        } catch (RemoteException e) {
                            Log.w(TAG, "Failed to call closeSystemDialogs", e);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void launchVoiceAssistFromKeyguard() {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "launchVoiceAssistFromKeyguard without running voice interaction"
                            + "service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.launchVoiceAssistFromKeyguard();
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public boolean isSessionRunning() {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                return mImpl != null && mImpl.mActiveSession != null;
            }
        }

        @Override
        public boolean activeServiceSupportsAssist() {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                return mImpl != null && mImpl.mInfo != null && mImpl.mInfo.getSupportsAssist();
            }
        }

        @Override
        public boolean activeServiceSupportsLaunchFromKeyguard() throws RemoteException {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                return mImpl != null && mImpl.mInfo != null
                        && mImpl.mInfo.getSupportsLaunchFromKeyguard();
            }
        }

        @Override
        public void onLockscreenShown() {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                if (mImpl == null) {
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    if (mImpl.mActiveSession != null && mImpl.mActiveSession.mSession != null) {
                        try {
                            mImpl.mActiveSession.mSession.onLockscreenShown();
                        } catch (RemoteException e) {
                            Log.w(TAG, "Failed to call onLockscreenShown", e);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void registerVoiceInteractionSessionListener(
                IVoiceInteractionSessionListener listener) {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                mVoiceInteractionSessionListeners.register(listener);
            }
        }

        @Override
        public void getActiveServiceSupportedActions(List<String> voiceActions,
                IVoiceActionCheckCallback callback) {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                if (mImpl == null) {
                    try {
                        callback.onComplete(null);
                    } catch (RemoteException e) {
                    }
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.getActiveServiceSupportedActions(voiceActions, callback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        public void onSessionShown() {
            synchronized (this) {
                final int size = mVoiceInteractionSessionListeners.beginBroadcast();
                for (int i = 0; i < size; ++i) {
                    final IVoiceInteractionSessionListener listener =
                            mVoiceInteractionSessionListeners.getBroadcastItem(i);
                    try {
                        listener.onVoiceSessionShown();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error delivering voice interaction open event.", e);
                    }
                }
                mVoiceInteractionSessionListeners.finishBroadcast();
            }
        }

        public void onSessionHidden() {
            synchronized (this) {
                final int size = mVoiceInteractionSessionListeners.beginBroadcast();
                for (int i = 0; i < size; ++i) {
                    final IVoiceInteractionSessionListener listener =
                            mVoiceInteractionSessionListeners.getBroadcastItem(i);
                    try {
                        listener.onVoiceSessionHidden();

                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error delivering voice interaction closed event.", e);
                    }
                }
                mVoiceInteractionSessionListeners.finishBroadcast();
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
            synchronized (this) {
                pw.println("VOICE INTERACTION MANAGER (dumpsys voiceinteraction)");
                pw.println("  mEnableService: " + mEnableService);
                if (mImpl == null) {
                    pw.println("  (No active implementation)");
                    return;
                }
                mImpl.dumpLocked(fd, pw, args);
            }
            mSoundTriggerInternal.dump(fd, pw, args);
        }

        @Override
        public void setUiHints(IVoiceInteractionService service, Bundle hints) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService(service);

                final int size = mVoiceInteractionSessionListeners.beginBroadcast();
                for (int i = 0; i < size; ++i) {
                    final IVoiceInteractionSessionListener listener =
                            mVoiceInteractionSessionListeners.getBroadcastItem(i);
                    try {
                        listener.onSetUiHints(hints);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error delivering UI hints.", e);
                    }
                }
                mVoiceInteractionSessionListeners.finishBroadcast();
            }
        }

        private void enforceAlwaysOnHotwordPermissions() {
            enforceCallingPermission(Manifest.permission.RECORD_AUDIO);
            enforceCallingPermission(Manifest.permission.CAPTURE_AUDIO_HOTWORD);
        }

        private void enforceCallingPermission(String permission) {
            if (mContext.checkCallingOrSelfPermission(permission)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Caller does not hold the permission " + permission);
            }
        }

        private void enforceIsCurrentVoiceInteractionService(IVoiceInteractionService service) {
            if (mImpl == null || mImpl.mService == null
                    || service.asBinder() != mImpl.mService.asBinder()) {
                throw new
                    SecurityException("Caller is not the current voice interaction service");
            }
        }

        private void setImplLocked(VoiceInteractionManagerServiceImpl impl) {
            mImpl = impl;
            mAtmInternal.notifyActiveVoiceInteractionServiceChanged(
                    getActiveServiceComponentName());
        }

        class RoleObserver implements OnRoleHoldersChangedListener {
            private PackageManager mPm = mContext.getPackageManager();
            private RoleManager mRm = mContext.getSystemService(RoleManager.class);

            RoleObserver(@NonNull @CallbackExecutor Executor executor) {
                mRm.addOnRoleHoldersChangedListenerAsUser(executor, this, UserHandle.ALL);
                // Sync only if assistant role has been initialized.
                if (mRm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    UserHandle currentUser = UserHandle.of(LocalServices.getService(
                            ActivityManagerInternal.class).getCurrentUserId());
                    onRoleHoldersChanged(RoleManager.ROLE_ASSISTANT, currentUser);
                }
            }

            private @NonNull String getDefaultRecognizer(@NonNull UserHandle user) {
                ResolveInfo resolveInfo = mPm.resolveServiceAsUser(
                        new Intent(RecognitionService.SERVICE_INTERFACE),
                        PackageManager.GET_META_DATA, user.getIdentifier());

                if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                    Log.w(TAG, "Unable to resolve default voice recognition service.");
                    return "";
                }

                return new ComponentName(resolveInfo.serviceInfo.packageName,
                        resolveInfo.serviceInfo.name).flattenToShortString();
            }

            /**
             * Convert the assistant-role holder into settings. The rest of the system uses the
             * settings.
             *
             * @param roleName the name of the role whose holders are changed
             * @param user the user for this role holder change
             */
            @Override
            public void onRoleHoldersChanged(@NonNull String roleName, @NonNull UserHandle user) {
                if (!roleName.equals(RoleManager.ROLE_ASSISTANT)) {
                    return;
                }

                List<String> roleHolders = mRm.getRoleHoldersAsUser(roleName, user);

                int userId = user.getIdentifier();
                if (roleHolders.isEmpty()) {
                    Settings.Secure.putStringForUser(getContext().getContentResolver(),
                            Settings.Secure.ASSISTANT, "", userId);
                    Settings.Secure.putStringForUser(getContext().getContentResolver(),
                            Settings.Secure.VOICE_INTERACTION_SERVICE, "", userId);
                    Settings.Secure.putStringForUser(getContext().getContentResolver(),
                            Settings.Secure.VOICE_RECOGNITION_SERVICE, getDefaultRecognizer(user),
                            userId);
                } else {
                    // Assistant is singleton role
                    String pkg = roleHolders.get(0);

                    // Try to set role holder as VoiceInteractionService
                    List<ResolveInfo> services = mPm.queryIntentServicesAsUser(
                            new Intent(VoiceInteractionService.SERVICE_INTERFACE).setPackage(pkg),
                            PackageManager.GET_META_DATA
                                    | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);

                    for (ResolveInfo resolveInfo : services) {
                        ServiceInfo serviceInfo = resolveInfo.serviceInfo;

                        VoiceInteractionServiceInfo voiceInteractionServiceInfo =
                                new VoiceInteractionServiceInfo(mPm, serviceInfo);
                        if (!voiceInteractionServiceInfo.getSupportsAssist()) {
                            continue;
                        }

                        String serviceComponentName = serviceInfo.getComponentName()
                                .flattenToShortString();

                        String serviceRecognizerName = new ComponentName(pkg,
                                voiceInteractionServiceInfo.getRecognitionService())
                                .flattenToShortString();

                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.ASSISTANT, serviceComponentName, userId);
                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.VOICE_INTERACTION_SERVICE, serviceComponentName,
                                userId);
                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.VOICE_RECOGNITION_SERVICE, serviceRecognizerName,
                                userId);

                        return;
                    }

                    // If no service could be found try to set assist activity
                    final List<ResolveInfo> activities = mPm.queryIntentActivitiesAsUser(
                            new Intent(Intent.ACTION_ASSIST).setPackage(pkg),
                            PackageManager.MATCH_DEFAULT_ONLY
                                    | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);

                    for (ResolveInfo resolveInfo : activities) {
                        ActivityInfo activityInfo = resolveInfo.activityInfo;

                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.ASSISTANT,
                                activityInfo.getComponentName().flattenToShortString(), userId);
                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.VOICE_INTERACTION_SERVICE, "", userId);
                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.VOICE_RECOGNITION_SERVICE,
                                getDefaultRecognizer(user), userId);
                        return;
                    }
                }
            }
        }

        class SettingsObserver extends ContentObserver {
            SettingsObserver(Handler handler) {
                super(handler);
                ContentResolver resolver = mContext.getContentResolver();
                resolver.registerContentObserver(Settings.Secure.getUriFor(
                        Settings.Secure.VOICE_INTERACTION_SERVICE), false, this,
                        UserHandle.USER_ALL);
            }

            @Override public void onChange(boolean selfChange) {
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    switchImplementationIfNeededLocked(false);
                }
            }
        }

        PackageMonitor mPackageMonitor = new PackageMonitor() {
            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
                if (DEBUG) Slog.d(TAG, "onHandleForceStop uid=" + uid + " doit=" + doit);

                int userHandle = UserHandle.getUserId(uid);
                ComponentName curInteractor = getCurInteractor(userHandle);
                ComponentName curRecognizer = getCurRecognizer(userHandle);
                boolean hitInt = false;
                boolean hitRec = false;
                for (String pkg : packages) {
                    if (curInteractor != null && pkg.equals(curInteractor.getPackageName())) {
                        hitInt = true;
                        break;
                    } else if (curRecognizer != null
                            && pkg.equals(curRecognizer.getPackageName())) {
                        hitRec = true;
                        break;
                    }
                }
                if (hitInt && doit) {
                    // The user is force stopping our current interactor, restart the service.
                    synchronized (VoiceInteractionManagerServiceStub.this) {
                        Slog.i(TAG, "Force stopping current voice interactor: "
                                + getCurInteractor(userHandle));
                        unloadAllKeyphraseModels();
                        if (mImpl != null) {
                            mImpl.shutdownLocked();
                            setImplLocked(null);
                        }
                        switchImplementationIfNeededLocked(true);
                    }
                } else if (hitRec && doit) {
                    // We are just force-stopping the current recognizer, which is not
                    // also the current interactor.
                    synchronized (VoiceInteractionManagerServiceStub.this) {
                        Slog.i(TAG, "Force stopping current voice recognizer: "
                                + getCurRecognizer(userHandle));
                        initSimpleRecognizer(null, userHandle);
                    }
                }
                return hitInt || hitRec;
            }

            @Override
            public void onHandleUserStop(Intent intent, int userHandle) {
            }

            @Override
            public void onPackageModified(String pkgName) {
                // If the package modified is not in the current user, then don't bother making
                // any changes as we are going to do any initialization needed when we switch users.
                if (mCurUser != getChangingUserId()) {
                    return;
                }
                // Package getting updated will be handled by {@link #onSomePackagesChanged}.
                if (isPackageAppearing(pkgName) != PACKAGE_UNCHANGED) {
                    return;
                }
                final ComponentName curInteractor = getCurInteractor(mCurUser);
                if (curInteractor == null) {
                    final VoiceInteractionServiceInfo availInteractorInfo
                            = findAvailInteractor(mCurUser, pkgName);
                    if (availInteractorInfo != null) {
                        final ComponentName availInteractor = new ComponentName(
                                availInteractorInfo.getServiceInfo().packageName,
                                availInteractorInfo.getServiceInfo().name);
                        setCurInteractor(availInteractor, mCurUser);
                        if (getCurRecognizer(mCurUser) == null &&
                                availInteractorInfo.getRecognitionService() != null) {
                            setCurRecognizer(new ComponentName(
                                    availInteractorInfo.getServiceInfo().packageName,
                                    availInteractorInfo.getRecognitionService()), mCurUser);
                        }
                    }
                } else {
                    if (didSomePackagesChange()) {
                        // Package is changed
                        if (curInteractor != null && pkgName.equals(
                                curInteractor.getPackageName())) {
                            switchImplementationIfNeeded(true);
                        }
                    } else {
                        // Only some components are changed
                        if (curInteractor != null
                                && isComponentModified(curInteractor.getClassName())) {
                            switchImplementationIfNeeded(true);
                        }
                    }
                }
            }

            @Override
            public void onSomePackagesChanged() {
                int userHandle = getChangingUserId();
                if (DEBUG) Slog.d(TAG, "onSomePackagesChanged user=" + userHandle);

                synchronized (VoiceInteractionManagerServiceStub.this) {
                    ComponentName curInteractor = getCurInteractor(userHandle);
                    ComponentName curRecognizer = getCurRecognizer(userHandle);
                    ComponentName curAssistant = getCurAssistant(userHandle);
                    if (curRecognizer == null) {
                        // Could a new recognizer appear when we don't have one pre-installed?
                        if (anyPackagesAppearing()) {
                            curRecognizer = findAvailRecognizer(null, userHandle);
                            if (curRecognizer != null) {
                                setCurRecognizer(curRecognizer, userHandle);
                            }
                        }
                        return;
                    }

                    if (curInteractor != null) {
                        int change = isPackageDisappearing(curInteractor.getPackageName());
                        if (change == PACKAGE_PERMANENT_CHANGE) {
                            // The currently set interactor is permanently gone; fall back to
                            // the default config.
                            setCurInteractor(null, userHandle);
                            setCurRecognizer(null, userHandle);
                            resetCurAssistant(userHandle);
                            initForUser(userHandle);
                            return;
                        }

                        change = isPackageAppearing(curInteractor.getPackageName());
                        if (change != PACKAGE_UNCHANGED) {
                            // If current interactor is now appearing, for any reason, then
                            // restart our connection with it.
                            if (mImpl != null && curInteractor.getPackageName().equals(
                                    mImpl.mComponent.getPackageName())) {
                                switchImplementationIfNeededLocked(true);
                            }
                        }
                        return;
                    }

                    if (curAssistant != null) {
                        int change = isPackageDisappearing(curAssistant.getPackageName());
                        if (change == PACKAGE_PERMANENT_CHANGE) {
                            // If the currently set assistant is being removed, then we should
                            // reset back to the default state (which is probably that we prefer
                            // to have the default full voice interactor enabled).
                            setCurInteractor(null, userHandle);
                            setCurRecognizer(null, userHandle);
                            resetCurAssistant(userHandle);
                            initForUser(userHandle);
                            return;
                        }
                    }

                    // There is no interactor, so just deal with a simple recognizer.
                    int change = isPackageDisappearing(curRecognizer.getPackageName());
                    if (change == PACKAGE_PERMANENT_CHANGE
                            || change == PACKAGE_TEMPORARY_CHANGE) {
                        setCurRecognizer(findAvailRecognizer(null, userHandle), userHandle);

                    } else if (isPackageModified(curRecognizer.getPackageName())) {
                        setCurRecognizer(findAvailRecognizer(curRecognizer.getPackageName(),
                                userHandle), userHandle);
                    }
                }
            }
        };
    }
}
