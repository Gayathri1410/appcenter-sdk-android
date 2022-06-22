/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.crashes;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.doAnswer;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

import android.os.Looper;
import android.os.SystemClock;

import com.microsoft.appcenter.AppCenter;
import com.microsoft.appcenter.AppCenterHandler;
import com.microsoft.appcenter.crashes.ingestion.models.ErrorAttachmentLog;
import com.microsoft.appcenter.crashes.utils.ErrorLogHelper;
import com.microsoft.appcenter.utils.AppCenterLog;
import com.microsoft.appcenter.utils.HandlerUtils;
import com.microsoft.appcenter.utils.PrefStorageConstants;
import com.microsoft.appcenter.utils.async.AppCenterFuture;
import com.microsoft.appcenter.utils.context.UserIdContext;
import com.microsoft.appcenter.utils.storage.FileManager;
import com.microsoft.appcenter.utils.storage.SharedPreferencesManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.rule.PowerMockRule;

@PrepareForTest({
        AppCenter.class,
        AppCenterLog.class,
        Crashes.class,
        ErrorAttachmentLog.class,
        ErrorLogHelper.class,
        FileManager.class,
        HandlerUtils.class,
        Looper.class,
        SharedPreferencesManager.class,
        SystemClock.class
})
public class AbstractCrashesTest {

    static final String CRASHES_ENABLED_KEY = PrefStorageConstants.KEY_ENABLED + "_" + Crashes.getInstance().getServiceName();

    static final Exception EXCEPTION = new Exception("This is a test exception.");

    @Rule
    public PowerMockRule mPowerMockRule = new PowerMockRule();

    @Mock
    AppCenterHandler mAppCenterHandler;

    @Mock
    private AppCenter mAppCenter;

    @Before
    public void setUp() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        Crashes.unsetInstance();
        mockStatic(AppCenter.class);
        mockStatic(AppCenterLog.class);
        mockStatic(FileManager.class);
        mockStatic(SharedPreferencesManager.class);
        mockStatic(SystemClock.class);
        when(SystemClock.elapsedRealtime()).thenReturn(System.currentTimeMillis());
        when(AppCenter.getInstance()).thenReturn(mAppCenter);

        @SuppressWarnings("unchecked")
        AppCenterFuture<Boolean> future = (AppCenterFuture<Boolean>) mock(AppCenterFuture.class);
        when(AppCenter.isEnabled()).thenReturn(future);
        when(future.get()).thenReturn(true);

        when(SharedPreferencesManager.getBoolean(CRASHES_ENABLED_KEY, true)).thenReturn(true);

        /* Then simulate further changes to state. */
        doAnswer(new Answer<Object>() {

            @Override
            public Object answer(InvocationOnMock invocation) {

                /* Whenever the new state is persisted, make further calls return the new state. */
                boolean enabled = (Boolean) invocation.getArguments()[1];
                when(SharedPreferencesManager.getBoolean(CRASHES_ENABLED_KEY, true)).thenReturn(enabled);
                return null;
            }
        }).when(SharedPreferencesManager.class);
        SharedPreferencesManager.putBoolean(eq(CRASHES_ENABLED_KEY), anyBoolean());

        /* Mock handlers. */
        mockStatic(HandlerUtils.class);
        Answer<Void> runNow = new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                ((Runnable) invocation.getArguments()[0]).run();
                return null;
            }
        };
        doAnswer(runNow).when(HandlerUtils.class);
        HandlerUtils.runOnUiThread(any(Runnable.class));
        doAnswer(runNow).when(mAppCenterHandler).post(any(Runnable.class), any());
    }

    @After
    public void tearDown() {
        UserIdContext.unsetInstance();
    }
}
