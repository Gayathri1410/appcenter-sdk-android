/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.utils;

import android.util.Log;

import com.microsoft.appcenter.AppCenter;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.verification.VerificationMode;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.when;

import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("unused")
@RunWith(PowerMockRunner.class)
@PrepareForTest(Log.class)
public class AppCenterLogTest {

    private static void callLogs() {
        AppCenterLog.logAssert("my-tag", "error with my-tag");
        AppCenterLog.logAssert("my-tag", "error with my-tag with exception", new Exception());
        AppCenterLog.error("my-tag", "error with my-tag");
        AppCenterLog.error("my-tag", "error with my-tag with exception", new Exception());
        AppCenterLog.warn("my-tag", "warn with my-tag");
        AppCenterLog.warn("my-tag", "warn with my-tag with exception", new Exception());
        AppCenterLog.info("my-tag", "info with my-tag");
        AppCenterLog.info("my-tag", "info with my-tag with exception", new Exception());
        AppCenterLog.debug("my-tag", "debug with my-tag");
        AppCenterLog.debug("my-tag", "debug with my-tag with exception", new Exception());
        AppCenterLog.verbose("my-tag", "verbose with my-tag");
        AppCenterLog.verbose("my-tag", "verbose with my-tag with exception", new Exception());
    }

    private static void verifyAssert(VerificationMode verificationMode) {
        verifyStatic(Log.class, verificationMode);
        Log.println(Log.ASSERT, "my-tag", "error with my-tag");
        verifyStatic(Log.class, verificationMode);
        Log.println(eq(Log.ASSERT), eq("my-tag"), eq("error with my-tag with exception\nmock stack trace"));
    }

    private static void verifyError(VerificationMode verificationMode) {
        verifyStatic(Log.class, verificationMode);
        Log.e(eq("my-tag"), eq("error with my-tag"));
        verifyStatic(Log.class, verificationMode);
        Log.e(eq("my-tag"), eq("error with my-tag with exception"), any(Exception.class));
    }

    private static void verifyWarn(VerificationMode verificationMode) {
        verifyStatic(Log.class, verificationMode);
        Log.w(eq("my-tag"), eq("warn with my-tag"));
        verifyStatic(Log.class, verificationMode);
        Log.w(eq("my-tag"), eq("warn with my-tag with exception"), any(Exception.class));
    }

    private static void verifyInfo(VerificationMode verificationMode) {
        verifyStatic(Log.class, verificationMode);
        Log.i(eq("my-tag"), eq("info with my-tag"));
        verifyStatic(Log.class, verificationMode);
        Log.i(eq("my-tag"), eq("info with my-tag with exception"), any(Exception.class));
    }

    private static void verifyDebug(VerificationMode verificationMode) {
        verifyStatic(Log.class, verificationMode);
        Log.d(eq("my-tag"), eq("debug with my-tag"));
        verifyStatic(Log.class, verificationMode);
        Log.d(eq("my-tag"), eq("debug with my-tag with exception"), any(Exception.class));
    }

    private static void verifyVerbose(VerificationMode verificationMode) {
        verifyStatic(Log.class, verificationMode);
        Log.v(eq("my-tag"), eq("verbose with my-tag"));
        verifyStatic(Log.class, verificationMode);
        Log.v(eq("my-tag"), eq("verbose with my-tag with exception"), any(Exception.class));
    }

    @BeforeClass
    public static void setUpBeforeClass() {

        /* Default initial state can be tested only once in the entire test suite... */
        assertEquals(Log.ASSERT, AppCenter.getLogLevel());
        assertEquals(AppCenter.getLogLevel(), AppCenterLog.getLogLevel());
    }

    @Before
    public void setUp() {
        AppCenter.setLogger(null);
        mockStatic(Log.class);
        when(Log.getStackTraceString(any(Throwable.class))).thenReturn("mock stack trace");
    }

    @Test
    public void none() {
        AppCenter.setLogLevel(AppCenterLog.NONE);
        assertEquals(AppCenter.getLogLevel(), AppCenterLog.getLogLevel());
        callLogs();
        verifyVerbose(never());
        verifyDebug(never());
        verifyInfo(never());
        verifyWarn(never());
        verifyError(never());
    }

    @Test
    public void assertLevel() {
        AppCenter.setLogLevel(Log.ASSERT);
        assertEquals(AppCenter.getLogLevel(), AppCenterLog.getLogLevel());
        callLogs();
        verifyVerbose(never());
        verifyDebug(never());
        verifyInfo(never());
        verifyWarn(never());
        verifyError(never());
    }

    @Test
    public void error() {
        AppCenter.setLogLevel(Log.ERROR);
        assertEquals(AppCenter.getLogLevel(), AppCenterLog.getLogLevel());
        callLogs();
        verifyVerbose(never());
        verifyDebug(never());
        verifyInfo(never());
        verifyWarn(never());
        verifyError(times(1));
    }

    @Test
    public void warn() {
        AppCenter.setLogLevel(Log.WARN);
        assertEquals(AppCenter.getLogLevel(), AppCenterLog.getLogLevel());
        callLogs();
        verifyVerbose(never());
        verifyDebug(never());
        verifyInfo(never());
        verifyWarn(times(1));
        verifyError(times(1));
    }

    @Test
    public void info() {
        AppCenter.setLogLevel(Log.INFO);
        assertEquals(AppCenter.getLogLevel(), AppCenterLog.getLogLevel());
        callLogs();
        verifyVerbose(never());
        verifyDebug(never());
        verifyInfo(times(1));
        verifyWarn(times(1));
        verifyError(times(1));
    }

    @Test
    public void debug() {
        AppCenter.setLogLevel(Log.DEBUG);
        assertEquals(AppCenter.getLogLevel(), AppCenterLog.getLogLevel());
        callLogs();
        verifyVerbose(never());
        verifyDebug(times(1));
        verifyInfo(times(1));
        verifyWarn(times(1));
        verifyError(times(1));
    }

    @Test
    public void verbose() {
        AppCenter.setLogLevel(Log.VERBOSE);
        assertEquals(AppCenter.getLogLevel(), AppCenterLog.getLogLevel());
        callLogs();
        verifyVerbose(times(1));
        verifyDebug(times(1));
        verifyInfo(times(1));
        verifyWarn(times(1));
        verifyError(times(1));
    }

    @Test
    public void setCustomLogger() {
        AppCenter.setLogLevel(Log.VERBOSE);
        Logger mockLogger = mock(Logger.class);
        AppCenter.setLogger(mockLogger);
        callLogs();
        verify(mockLogger).log(eq(Level.ALL), anyString());
        verify(mockLogger).log(eq(Level.ALL), anyString(), any(Throwable.class));
        verify(mockLogger).log(eq(Level.INFO), anyString());
        verify(mockLogger).log(eq(Level.INFO), anyString(), any(Throwable.class));
        verify(mockLogger).log(eq(Level.FINE), anyString());
        verify(mockLogger).log(eq(Level.FINE), anyString(), any(Throwable.class));
        verify(mockLogger).log(eq(Level.SEVERE), anyString());
        verify(mockLogger).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
        verify(mockLogger).log(eq(Level.WARNING), anyString());
        verify(mockLogger).log(eq(Level.WARNING), anyString(), any(Throwable.class));
    }

    @Test
    public void checkPrintLogWithMessageAndTag() {

        /* Prepare data. */
        String mockTag = "my-tag";
        String mockMessage = "message";
        String expectedMessage = String.format("%s: %s", mockTag, mockMessage);

        /* Prepare logger. */
        AppCenter.setLogLevel(Log.VERBOSE);
        Logger mockLogger = mock(Logger.class);
        AppCenter.setLogger(mockLogger);

        /* Call print log. */
        AppCenterLog.verbose(mockTag, mockMessage);

        /* Verify. */
        ArgumentCaptor<String> captorMessage = ArgumentCaptor.forClass(String.class);
        verify(mockLogger).log(eq(Level.ALL), captorMessage.capture());
        assertEquals(captorMessage.getValue(), expectedMessage);
    }
}
