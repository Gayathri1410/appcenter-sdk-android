/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.distribute;

import static android.content.Context.NOTIFICATION_SERVICE;
import static com.microsoft.appcenter.distribute.DistributeConstants.PREFERENCE_KEY_RELEASE_DETAILS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.whenNew;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import com.microsoft.appcenter.utils.storage.SharedPreferencesManager;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.rule.PowerMockRule;
import org.powermock.reflect.Whitebox;

@PrepareForTest({
        DistributeUtils.class,
        SharedPreferencesManager.class,
        ReleaseDetails.class
})
public class DistributeUtilsTest {

    @Rule
    public PowerMockRule mPowerMockRule = new PowerMockRule();

    @Mock
    private Context mContext;

    @Before
    public void setUp() {
        mockStatic(SharedPreferencesManager.class);
        mockStatic(ReleaseDetails.class);
    }

    @SuppressWarnings("InstantiationOfUtilityClass")
    @Test
    public void init() {
        new DistributeUtils();
        new DistributeConstants();
    }

    @Test
    public void notificationId() {
        assertNotEquals(0, DistributeUtils.getNotificationId());
    }

    @Test
    public void loadCachedReleaseDetails() throws JSONException {
        ReleaseDetails mock = mock(ReleaseDetails.class);
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_RELEASE_DETAILS)).thenReturn("test");
        when(ReleaseDetails.parse(anyString())).thenReturn(mock);

        /* Load. */
        ReleaseDetails releaseDetails = DistributeUtils.loadCachedReleaseDetails();

        /* Verify. */
        assertEquals(mock, releaseDetails);
        verifyStatic(SharedPreferencesManager.class, never());
        SharedPreferencesManager.remove(eq(PREFERENCE_KEY_RELEASE_DETAILS));
    }

    @Test
    public void loadCachedReleaseDetailsNull() {
        mockStatic(SharedPreferencesManager.class);
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_RELEASE_DETAILS)).thenReturn(null);

        /* Load. */
        ReleaseDetails releaseDetails = DistributeUtils.loadCachedReleaseDetails();

        /* Verify. */
        assertNull(releaseDetails);
        verifyStatic(SharedPreferencesManager.class, never());
        SharedPreferencesManager.remove(eq(PREFERENCE_KEY_RELEASE_DETAILS));
    }

    @Test
    public void loadCachedReleaseDetailsJsonException() throws JSONException {
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_RELEASE_DETAILS)).thenReturn("test");
        when(ReleaseDetails.parse(anyString())).thenThrow(new JSONException("test"));

        /* Load. */
        ReleaseDetails releaseDetails = DistributeUtils.loadCachedReleaseDetails();

        /* Verify. */
        assertNull(releaseDetails);
        verifyStatic(SharedPreferencesManager.class);
        SharedPreferencesManager.remove(eq(PREFERENCE_KEY_RELEASE_DETAILS));
    }

    @Test
    public void firstDownloadNotificationApi26() throws Exception {
        firstDownloadNotification(Build.VERSION_CODES.O);
    }

    @Test
    public void firstDownloadNotificationApi21() throws Exception {
        firstDownloadNotification(Build.VERSION_CODES.LOLLIPOP);
    }

    private void firstDownloadNotification(int apiLevel) throws Exception {
        Whitebox.setInternalState(Build.VERSION.class, "SDK_INT", apiLevel);
        NotificationManager manager = mock(NotificationManager.class);
        when(mContext.getSystemService(NOTIFICATION_SERVICE)).thenReturn(manager);
        when(mContext.getApplicationInfo()).thenReturn(mock(ApplicationInfo.class));
        Notification.Builder builder = mock(Notification.Builder.class, RETURNS_SELF);
        whenNew(Notification.Builder.class).withAnyArguments().thenReturn(builder);
        Notification notification = mock(Notification.class);
        when(builder.build()).thenReturn(notification);

        /* Post notification. */
        Intent intent = mock(Intent.class);
        DistributeUtils.postNotification(mContext,"Title", "Message", intent);

        /* Verify. */
        verify(builder).setContentTitle(eq("Title"));
        verify(builder).setContentText(eq("Message"));
        verify(manager).notify(eq(DistributeUtils.getNotificationId()), eq(notification));
    }

    @Test
    public void checkNotificationState() {
        NotificationManager manager = mock(NotificationManager.class);
        when(mContext.getSystemService(NOTIFICATION_SERVICE)).thenReturn(manager);

        /* Cancel notification. */
        DistributeUtils.cancelNotification(mContext);
        verify(manager).cancel(eq(DistributeUtils.getNotificationId()));
    }
}
