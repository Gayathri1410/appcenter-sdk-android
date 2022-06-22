/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.http;

import static com.microsoft.appcenter.http.DefaultHttpClient.METHOD_GET;
import static com.microsoft.appcenter.http.DefaultHttpClient.METHOD_POST;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.doAnswer;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.whenNew;

import android.net.TrafficStats;
import android.os.Build;
import android.util.Log;
import android.util.Pair;

import com.microsoft.appcenter.test.TestUtils;
import com.microsoft.appcenter.utils.AppCenterLog;
import com.microsoft.appcenter.utils.HandlerUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.rule.PowerMockRule;
import org.powermock.reflect.Whitebox;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;

@SuppressWarnings("unused")
@PrepareForTest({
        AppCenterLog.class,
        DefaultHttpClient.class,
        DefaultHttpClientCallTask.class,
        HttpUtils.class,
        TrafficStats.class
})
public class DefaultHttpClientTest {

    @Rule
    public PowerMockRule mRule = new PowerMockRule();

    @Captor
    private ArgumentCaptor<HttpResponse> mHttpResponseCaptor;

    @After
    public void tearDown() throws Exception {
        TestUtils.setInternalState(Build.VERSION.class, "SDK_INT", 0);
    }

    /**
     * Simulate AsyncTask.
     */
    private static void mockCall(final Consumer<DefaultHttpClientCallTask> callback) throws Exception {

        /* Mock AsyncTask. */
        whenNew(DefaultHttpClientCallTask.class).withAnyArguments().thenAnswer(new Answer<Object>() {

            @Override
            public Object answer(InvocationOnMock invocation) {

                @SuppressWarnings("unchecked") final DefaultHttpClientCallTask call = spy(new DefaultHttpClientCallTask(
                        invocation.getArguments()[0].toString(),
                        invocation.getArguments()[1].toString(),
                        (Map<String, String>) invocation.getArguments()[2],
                        (HttpClient.CallTemplate) invocation.getArguments()[3],
                        (ServiceCallback) invocation.getArguments()[4],
                        (DefaultHttpClientCallTask.Tracker) invocation.getArguments()[5],
                        (boolean) invocation.getArguments()[6]));
                when(call.executeOnExecutor(any())).then(new Answer<DefaultHttpClientCallTask>() {

                    @Override
                    public DefaultHttpClientCallTask answer(InvocationOnMock invocation) {
                        call.onPreExecute();
                        Object result = call.doInBackground();
                        if (call.isCancelled()) {
                            call.onCancelled(result);
                        } else {
                            call.onPostExecute(result);
                        }
                        return call;
                    }
                });
                if (callback != null) {
                    callback.accept(call);
                }
                return call;
            }
        });
        whenNew(Pair.class).withArguments(anyString(), anyMap()).then(new Answer<Object>() {

            @SuppressWarnings("rawtypes")
            @Override
            public Object answer(InvocationOnMock invocation) {
                Pair pair = mock(Pair.class);
                Whitebox.setInternalState(pair, "first", invocation.getArguments()[0]);
                Whitebox.setInternalState(pair, "second", invocation.getArguments()[1]);
                return pair;
            }
        });
        mockStatic(TrafficStats.class);
    }

    private static void mockCall() throws Exception {
        mockCall(null);
    }

    private static HttpsURLConnection mockConnection(String urlString) throws Exception {
        URL url = mock(URL.class);
        whenNew(URL.class).withArguments(urlString).thenReturn(url);
        when(url.getProtocol()).thenReturn("https");
        HttpsURLConnection urlConnection = mock(HttpsURLConnection.class);
        when(url.openConnection()).thenReturn(urlConnection);
        return urlConnection;
    }

    @Test
    public void tls1_2Enforcement() throws Exception {

        /* Configure mock HTTPS. */
        mockCall();
        testTls1_2Setting(Build.VERSION_CODES.LOLLIPOP, 1);
        for (int apiLevel = Build.VERSION_CODES.LOLLIPOP_MR1; apiLevel <= Build.VERSION_CODES.O_MR1; apiLevel++) {
            testTls1_2Setting(apiLevel, 0);
        }
    }

    private void testTls1_2Setting(int apiLevel, int tlsSetExpectedCalls) throws Exception {
        String urlString = "https://mock/logs?api-version=1.0.0";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        TestUtils.setInternalState(Build.VERSION.class, "SDK_INT", apiLevel);
        httpClient.callAsync(urlString, METHOD_POST, new HashMap<>(), null, mock(ServiceCallback.class));
        verify(urlConnection, times(tlsSetExpectedCalls)).setSSLSocketFactory(isA(TLS1_2SocketFactory.class));
    }

    @Test
    public void post200() throws Exception {

        /* Mock related to pretty json logging. */
        mockStatic(AppCenterLog.class);
        when(AppCenterLog.getLogLevel()).thenReturn(Log.VERBOSE);
        JSONObject jsonObject = mock(JSONObject.class);
        whenNew(JSONObject.class).withAnyArguments().thenReturn(jsonObject);
        String prettyString = "{\n" +
                "  \"a\": 1,\n" +
                "  \"b\": 2\n" +
                "}";
        when(jsonObject.toString(2)).thenReturn(prettyString);

        /* Configure mock HTTPS. */
        String urlString = "https://mock/logs?api-version=1.0.0";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(200);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        when(urlConnection.getInputStream()).thenReturn(new ByteArrayInputStream("OK".getBytes()));

        /* Configure API client. */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        when(callTemplate.buildRequestBody()).thenReturn("{a:1,b:2}");
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. Use shorter but valid app secret. */
        String appId = "SHORT";
        UUID installId = UUID.randomUUID();
        Map<String, String> headers = new HashMap<>();
        headers.put("App-Secret", appId);
        headers.put("Install-ID", installId.toString());
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_POST, headers, callTemplate, serviceCallback);
        verify(serviceCallback).onCallSucceeded(new HttpResponse(200, "OK", Collections.emptyMap()));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection).setRequestProperty("Content-Type", "application/json");
        verify(urlConnection, never()).setRequestProperty(eq("Content-Encoding"), anyString());
        verify(urlConnection).setRequestProperty("App-Secret", appId);
        verify(urlConnection).setRequestProperty("Install-ID", installId.toString());
        verify(urlConnection).setRequestMethod("POST");
        verify(urlConnection).setDoOutput(true);
        verify(urlConnection).disconnect();
        verify(callTemplate).onBeforeCalling(any(URL.class), anyMap());
        verify(callTemplate).buildRequestBody();
        httpClient.close();

        /* Verify payload. */
        String sentPayload = buffer.toString("UTF-8");
        assertEquals("{a:1,b:2}", sentPayload);

        /* Verify socket tagged to avoid strict mode error. */
        verifyStatic(TrafficStats.class);
        TrafficStats.setThreadStatsTag(anyInt());
        verifyStatic(TrafficStats.class);
        TrafficStats.clearThreadStatsTag();

        /* We enabled verbose and it's json, check pretty print. */
        verifyStatic(AppCenterLog.class);
        AppCenterLog.verbose(AppCenterLog.LOG_TAG, prettyString);
    }

    @Test
    public void post200WithoutCallTemplate() throws Exception {

        /* Configure mock HTTPS. */
        String urlString = "https://mock/logs?api-version=1.0.0";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(200);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        when(urlConnection.getInputStream()).thenReturn(new ByteArrayInputStream("OK".getBytes()));

        /* Configure API client. */
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. Use shorter but valid app secret. */
        String appId = "SHORT";
        UUID installId = UUID.randomUUID();
        Map<String, String> headers = new HashMap<>();
        headers.put("App-Secret", appId);
        headers.put("Install-ID", installId.toString());
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_POST, headers, null, serviceCallback);
        verify(serviceCallback).onCallSucceeded(new HttpResponse(200, "OK", Collections.emptyMap()));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection, never()).setRequestProperty(eq("Content-Type"), anyString());
        verify(urlConnection, never()).setRequestProperty(eq("Content-Encoding"), anyString());
        verify(urlConnection).setRequestProperty("App-Secret", appId);
        verify(urlConnection).setRequestProperty("Install-ID", installId.toString());
        verify(urlConnection).setRequestMethod("POST");
        verify(urlConnection, never()).setDoOutput(true);
        verify(urlConnection).disconnect();
        httpClient.close();

        /* Verify payload. */
        String sentPayload = buffer.toString("UTF-8");
        assertEquals("", sentPayload);
    }

    @Test
    public void get200() throws Exception {

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(200);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        ByteArrayInputStream inputStream = spy(new ByteArrayInputStream("OK".getBytes()));
        when(urlConnection.getInputStream()).thenReturn(inputStream);

        /* Configure API client. */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. */
        String appSecret = UUID.randomUUID().toString();
        UUID installId = UUID.randomUUID();
        Map<String, String> headers = new HashMap<>();
        headers.put("App-Secret", appSecret);
        headers.put("Install-ID", installId.toString());
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_GET, headers, callTemplate, serviceCallback);
        verify(serviceCallback).onCallSucceeded(new HttpResponse(200, "OK", Collections.emptyMap()));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection, never()).setRequestProperty(eq("Content-Type"), anyString());
        verify(urlConnection, never()).setRequestProperty(eq("Content-Encoding"), anyString());
        verify(urlConnection).setRequestProperty("App-Secret", appSecret);
        verify(urlConnection).setRequestProperty("Install-ID", installId.toString());
        verify(urlConnection).setRequestMethod("GET");
        verify(urlConnection, never()).setDoOutput(true);
        verify(urlConnection).disconnect();
        verify(inputStream).close();
        verify(callTemplate).onBeforeCalling(any(URL.class), anyMap());
        verify(callTemplate, never()).buildRequestBody();
        httpClient.close();
    }

    @Test
    public void get2xx() throws Exception {

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(outputStream);
        ByteArrayInputStream inputStream = new ByteArrayInputStream("OK".getBytes());
        when(urlConnection.getInputStream()).thenReturn(inputStream);

        /* Configure API client. */
        DefaultHttpClient httpClient = new DefaultHttpClient();
        Map<String, String> headers = new HashMap<>();
        mockCall();

        for (int statusCode = 200; statusCode < 300; statusCode++) {

            /* Configure status code. */
            when(urlConnection.getResponseCode()).thenReturn(statusCode);

            /* Test calling code. */
            ServiceCallback serviceCallback = mock(ServiceCallback.class);
            httpClient.callAsync(urlString, METHOD_GET, headers, null, serviceCallback);
            verify(serviceCallback).onCallSucceeded(eq(new HttpResponse(statusCode, "OK", Collections.emptyMap())));
            verifyNoMoreInteractions(serviceCallback);

            /* Reset response stream. */
            inputStream.reset();
        }
        httpClient.close();
    }

    @Test
    public void get100() throws Exception {

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(100);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        when(urlConnection.getErrorStream()).thenReturn(new ByteArrayInputStream("Continue".getBytes()));

        /* Configure API client. */
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. */
        Map<String, String> headers = new HashMap<>();
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_POST, headers, null, serviceCallback);
        verify(serviceCallback).onCallFailed(new HttpException(new HttpResponse(100, "Continue")));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection).disconnect();
    }

    @Test
    public void get200WithoutCallTemplate() throws Exception {

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(200);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        when(urlConnection.getInputStream()).thenReturn(new ByteArrayInputStream("OK".getBytes()));

        /* Configure API client. */
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. */
        String appSecret = UUID.randomUUID().toString();
        UUID installId = UUID.randomUUID();
        Map<String, String> headers = new HashMap<>();
        headers.put("App-Secret", appSecret);
        headers.put("Install-ID", installId.toString());
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_GET, headers, null, serviceCallback);
        verify(serviceCallback).onCallSucceeded(new HttpResponse(200, "OK", Collections.emptyMap()));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection, never()).setRequestProperty(eq("Content-Type"), anyString());
        verify(urlConnection, never()).setRequestProperty(eq("Content-Encoding"), anyString());
        verify(urlConnection).setRequestProperty("App-Secret", appSecret);
        verify(urlConnection).setRequestProperty("Install-ID", installId.toString());
        verify(urlConnection).setRequestMethod("GET");
        verify(urlConnection, never()).setDoOutput(true);
        verify(urlConnection).disconnect();
        httpClient.close();
    }

    private void testPayloadLogging(final String payload, String mimeType) throws Exception {

        /* Set log level to verbose to test shorter app secret as well. */
        mockStatic(AppCenterLog.class);
        when(AppCenterLog.getLogLevel()).thenReturn(Log.VERBOSE);

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(200);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        ByteArrayInputStream inputStream = spy(new ByteArrayInputStream(payload.getBytes()));
        when(urlConnection.getInputStream()).thenReturn(inputStream);
        when(urlConnection.getHeaderField("Content-Type")).thenReturn(mimeType);

        /* Configure API client. */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. */
        Map<String, String> headers = new HashMap<>();
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_GET, headers, callTemplate, serviceCallback);
        verify(serviceCallback).onCallSucceeded(new HttpResponse(200, payload, Collections.emptyMap()));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection).setRequestMethod("GET");
        verify(urlConnection, never()).setDoOutput(true);
        verify(urlConnection).disconnect();
        verify(inputStream).close();
        verify(callTemplate).onBeforeCalling(any(URL.class), anyMap());
        verify(callTemplate, never()).buildRequestBody();
        httpClient.close();

        /* Test binary placeholder used in logging code instead of real payload. */
        verifyStatic(AppCenterLog.class);
        AppCenterLog.verbose(anyString(), contains(payload));
    }

    @Test
    public void get200text() throws Exception {
        testPayloadLogging("Some text", "text/plain");
    }

    @Test
    public void get200json() throws Exception {
        testPayloadLogging("{}", "application/json");
    }

    @Test
    public void hideSecretsInResponse() throws Exception {
        final String payload = "{\"client_id\":\"not deleted\",\"redirect_uri\":\"some redirect Uri\",\"token\":\"some token\"}";
        final String expectedResponseString = "{\"client_id\":\"not deleted\",\"redirect_uri\":\"***\",\"token\":\"***\"}";
        final String mimeType = "application/json";

        /* Set log level to verbose to test shorter app secret as well. */
        mockStatic(AppCenterLog.class);
        when(AppCenterLog.getLogLevel()).thenReturn(Log.VERBOSE);

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(200);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        ByteArrayInputStream inputStream = spy(new ByteArrayInputStream(payload.getBytes()));
        when(urlConnection.getInputStream()).thenReturn(inputStream);
        when(urlConnection.getHeaderField("Content-Type")).thenReturn(mimeType);

        /* Configure API client. */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. */
        Map<String, String> headers = new HashMap<>();
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_GET, headers, callTemplate, serviceCallback);
        verify(serviceCallback).onCallSucceeded(new HttpResponse(200, payload, Collections.emptyMap()));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection).setRequestMethod("GET");
        verify(urlConnection, never()).setDoOutput(true);
        verify(urlConnection).disconnect();
        verify(inputStream).close();
        verify(callTemplate).onBeforeCalling(any(URL.class), anyMap());
        verify(callTemplate, never()).buildRequestBody();
        httpClient.close();

        /* Test binary placeholder used in logging code instead of real payload. */
        verifyStatic(AppCenterLog.class);
        AppCenterLog.verbose(anyString(), contains(expectedResponseString));
    }

    @Test
    public void get200image() throws Exception {

        /* Mock verbose logs. */
        mockStatic(AppCenterLog.class);
        when(AppCenterLog.getLogLevel()).thenReturn(Log.VERBOSE);

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(200);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        ByteArrayInputStream inputStream = spy(new ByteArrayInputStream("fake binary".getBytes()));
        when(urlConnection.getInputStream()).thenReturn(inputStream);
        when(urlConnection.getHeaderField("Content-Type")).thenReturn("image/png");
        Map<String, List<String>> responseHeaders = new HashMap<>();
        responseHeaders.put("ETag", Collections.singletonList("\"0x1234\""));
        when(urlConnection.getHeaderFields()).thenReturn(responseHeaders);

        /* Configure API client. */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. */
        Map<String, String> headers = new HashMap<>();
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_GET, headers, callTemplate, serviceCallback);
        verify(serviceCallback).onCallSucceeded(mHttpResponseCaptor.capture());
        assertNotNull(mHttpResponseCaptor.getValue());
        assertEquals("fake binary", mHttpResponseCaptor.getValue().getPayload());
        assertEquals(1, mHttpResponseCaptor.getValue().getHeaders().size());
        assertEquals("\"0x1234\"", mHttpResponseCaptor.getValue().getHeaders().get("ETag"));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection).setRequestMethod("GET");
        verify(urlConnection, never()).setDoOutput(true);
        verify(urlConnection).disconnect();
        verify(inputStream).close();
        verify(callTemplate).onBeforeCalling(any(URL.class), anyMap());
        verify(callTemplate, never()).buildRequestBody();
        httpClient.close();

        /* Test binary placeholder used in logging code instead of real payload. */
        verifyStatic(AppCenterLog.class);
        AppCenterLog.verbose(anyString(), argThat(new ArgumentMatcher<String>() {

            @Override
            public boolean matches(String logMessage) {
                return logMessage.contains("<binary>") && !logMessage.contains("fake binary");
            }
        }));
    }

    @Test
    public void get304() throws Exception {

        /* Mock verbose logs. */
        mockStatic(AppCenterLog.class);
        when(AppCenterLog.getLogLevel()).thenReturn(Log.VERBOSE);

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(304);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        ByteArrayInputStream inputStream = spy(new ByteArrayInputStream("".getBytes()));
        when(urlConnection.getInputStream()).thenReturn(inputStream);

        /* Configure API client. */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. */
        Map<String, String> headers = new HashMap<>();
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_GET, headers, callTemplate, serviceCallback);
        verify(serviceCallback).onCallFailed(new HttpException(new HttpResponse(304)));
        verifyNoMoreInteractions(serviceCallback);
        httpClient.close();
    }

    @Test
    public void error503() throws Exception {

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(503);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        when(urlConnection.getErrorStream()).thenReturn(new ByteArrayInputStream("Busy".getBytes()));

        /* Configure API client. */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        when(callTemplate.buildRequestBody()).thenReturn("mockPayload");
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. */
        String appSecret = UUID.randomUUID().toString();
        UUID installId = UUID.randomUUID();
        Map<String, String> headers = new HashMap<>();
        headers.put("App-Secret", appSecret);
        headers.put("Install-ID", installId.toString());
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_POST, headers, callTemplate, serviceCallback);
        verify(serviceCallback).onCallFailed(new HttpException(new HttpResponse(503, "Busy")));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection).disconnect();

        /* Verify socket tagged to avoid strict mode error. */
        verifyStatic(TrafficStats.class);
        TrafficStats.setThreadStatsTag(anyInt());
        verifyStatic(TrafficStats.class);
        TrafficStats.clearThreadStatsTag();
    }

    @Test
    public void cancel() throws Exception {

        /* Mock AsyncTask. */
        String urlString = "https://mock/get";
        DefaultHttpClientCallTask mockCall = mock(DefaultHttpClientCallTask.class);
        whenNew(DefaultHttpClientCallTask.class).withAnyArguments().thenReturn(mockCall);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        ServiceCall call = httpClient.callAsync(urlString, "", new HashMap<>(), mock(HttpClient.CallTemplate.class), serviceCallback);

        /* Cancel and verify. */
        call.cancel();
        verify(mockCall).cancel(true);
    }

    @Test
    public void cancelCurrentCallsOnClose() throws Exception {

        /* Mock AsyncTask. */
        String urlString = "https://mock/get";
        final AtomicReference<DefaultHttpClientCallTask> callTask = new AtomicReference<>();
        whenNew(DefaultHttpClientCallTask.class).withAnyArguments().thenAnswer(new Answer<Object>() {

            @Override
            public Object answer(InvocationOnMock invocation) {
                @SuppressWarnings("unchecked") final DefaultHttpClientCallTask call = spy(new DefaultHttpClientCallTask(
                        invocation.getArguments()[0].toString(),
                        invocation.getArguments()[1].toString(),
                        (Map<String, String>) invocation.getArguments()[2],
                        (HttpClient.CallTemplate) invocation.getArguments()[3],
                        (ServiceCallback) invocation.getArguments()[4],
                        (DefaultHttpClientCallTask.Tracker) invocation.getArguments()[5],
                        (boolean) invocation.getArguments()[6]));
                callTask.set(call);
                when(call.executeOnExecutor(any())).then(new Answer<DefaultHttpClientCallTask>() {

                    @Override
                    public DefaultHttpClientCallTask answer(InvocationOnMock invocation) {
                        call.onPreExecute();

                        /* Simulate we will cancel before doInBackground. */
                        return call;
                    }
                });
                return call;
            }
        });

        /* Simulate HTTP call. */
        DefaultHttpClient httpClient = new DefaultHttpClient();
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        httpClient.callAsync(urlString, "", new HashMap<>(), mock(HttpClient.CallTemplate.class), serviceCallback);

        /* Close and verify. */
        httpClient.close();
        verify(callTask.get()).cancel(true);
        assertEquals(0, httpClient.getTasks().size());
    }

    @Test
    public void cancelledBeforeSending() throws Exception {

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        mockCall(new Consumer<DefaultHttpClientCallTask>() {

            @Override
            public void accept(final DefaultHttpClientCallTask call) {
                when(call.isCancelled()).thenReturn(true);
            }
        });

        /* Call and verify */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        ServiceCall call = httpClient.callAsync(urlString, METHOD_GET, new HashMap<>(), callTemplate, serviceCallback);
        verify(urlConnection, never()).getResponseCode();
        verifyNoMoreInteractions(serviceCallback);
        assertEquals(0, httpClient.getTasks().size());
    }

    @Test
    public void cancelledOnSending() throws Exception {

        /* Configure mock HTTPS. */
        String urlString = "https://mock/post";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        mockCall(new Consumer<DefaultHttpClientCallTask>() {

            @Override
            public void accept(final DefaultHttpClientCallTask call) {
                when(call.isCancelled()).thenReturn(false, true);
            }
        });

        /* Call and verify */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        when(callTemplate.buildRequestBody()).thenReturn("{a:1,b:2}");
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        ServiceCall call = httpClient.callAsync(urlString, METHOD_POST, new HashMap<>(), callTemplate, serviceCallback);
        verify(urlConnection, never()).getResponseCode();
        verifyNoMoreInteractions(serviceCallback);
        assertEquals(0, httpClient.getTasks().size());
    }

    @Test
    public void cancelledBeforeReceiving() throws Exception {

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        mockCall(new Consumer<DefaultHttpClientCallTask>() {

            @Override
            public void accept(final DefaultHttpClientCallTask call) {
                when(call.isCancelled()).thenReturn(false, true);
            }
        });

        /* Call and verify */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        ServiceCall call = httpClient.callAsync(urlString, METHOD_GET, new HashMap<>(), callTemplate, serviceCallback);
        verify(urlConnection, never()).getResponseCode();
        verifyNoMoreInteractions(serviceCallback);
        assertEquals(0, httpClient.getTasks().size());
    }

    @Test
    public void cancelledOnReceiving() throws Exception {

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(200);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        when(urlConnection.getInputStream()).thenReturn(new ByteArrayInputStream("OK".getBytes()));
        mockCall(new Consumer<DefaultHttpClientCallTask>() {

            @Override
            public void accept(final DefaultHttpClientCallTask call) {
                when(call.isCancelled()).thenReturn(false, false, true);
            }
        });

        /* Call and verify */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        ServiceCall call = httpClient.callAsync(urlString, METHOD_GET, new HashMap<>(), callTemplate, serviceCallback);
        //verify(serviceCallback).onCallSucceeded(anyString(), anyMap());
        assertEquals(0, httpClient.getTasks().size());
    }

    @Test
    public void cancelledOnReceivingError() throws Exception {

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(503);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        when(urlConnection.getErrorStream()).thenReturn(new ByteArrayInputStream("Busy".getBytes()));
        mockCall(new Consumer<DefaultHttpClientCallTask>() {

            @Override
            public void accept(final DefaultHttpClientCallTask call) {
                when(call.isCancelled()).thenReturn(false, false, true);
            }
        });

        /* Call and verify */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        ServiceCall call = httpClient.callAsync(urlString, METHOD_GET, new HashMap<>(), callTemplate, serviceCallback);
        verify(serviceCallback).onCallFailed(new HttpException(new HttpResponse(503, "Busy")));
        assertEquals(0, httpClient.getTasks().size());
    }

    @Test
    public void failedConnection() throws Exception {
        String urlString = "https://mock/get";
        URL url = mock(URL.class);
        whenNew(URL.class).withAnyArguments().thenReturn(url);
        when(url.getProtocol()).thenReturn("https");
        IOException exception = new IOException("mock");
        when(url.openConnection()).thenThrow(exception);
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        mockCall();
        httpClient.callAsync(urlString, "", new HashMap<>(), callTemplate, serviceCallback);
        verify(serviceCallback).onCallFailed(exception);
        verifyNoInteractions(callTemplate);
        verifyNoMoreInteractions(serviceCallback);
    }

    @Test
    public void failedToWritePayload() throws Exception {
        String urlString = "https://mock/get";
        URL url = mock(URL.class);
        whenNew(URL.class).withAnyArguments().thenReturn(url);
        when(url.getProtocol()).thenReturn("https");
        IOException exception = new IOException("mock");
        HttpsURLConnection urlConnection = mock(HttpsURLConnection.class);
        when(url.openConnection()).thenReturn(urlConnection);
        OutputStream out = mock(OutputStream.class);
        when(urlConnection.getOutputStream()).thenReturn(out);
        doThrow(exception).when(out).write(any(byte[].class), anyInt(), anyInt());
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        when(callTemplate.buildRequestBody()).thenReturn("{}");
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        mockCall();
        httpClient.callAsync(urlString, METHOD_POST, new HashMap<>(), callTemplate, serviceCallback);
        verify(serviceCallback).onCallFailed(exception);
        verifyNoMoreInteractions(serviceCallback);
        verify(out).close();
        verifyStatic(TrafficStats.class);
        TrafficStats.setThreadStatsTag(anyInt());
        verifyStatic(TrafficStats.class);
        TrafficStats.clearThreadStatsTag();
    }

    @Test
    public void failedToReadResponse() throws Exception {
        String urlString = "https://mock/get";
        URL url = mock(URL.class);
        whenNew(URL.class).withAnyArguments().thenReturn(url);
        when(url.getProtocol()).thenReturn("https");
        IOException exception = new IOException("mock");
        HttpsURLConnection urlConnection = mock(HttpsURLConnection.class);
        when(url.openConnection()).thenReturn(urlConnection);
        when(urlConnection.getResponseCode()).thenReturn(200);
        InputStream inputStream = mock(InputStream.class);
        when(urlConnection.getInputStream()).thenReturn(inputStream);
        InputStreamReader inputStreamReader = mock(InputStreamReader.class);
        whenNew(InputStreamReader.class).withAnyArguments().thenReturn(inputStreamReader);
        when(inputStreamReader.read(any(char[].class))).thenThrow(exception);
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        mockCall();
        httpClient.callAsync(urlString, "", new HashMap<>(), callTemplate, serviceCallback);
        verify(serviceCallback).onCallFailed(exception);
        verifyNoMoreInteractions(serviceCallback);
        verify(inputStream).close();
        verifyStatic(TrafficStats.class);
        TrafficStats.setThreadStatsTag(anyInt());
        verifyStatic(TrafficStats.class);
        TrafficStats.clearThreadStatsTag();
    }

    @SuppressWarnings("MaskedAssertion")
    @Test
    public void failedWithError() throws Exception {
        String urlString = "https://mock/get";
        URL url = mock(URL.class);
        whenNew(URL.class).withAnyArguments().thenReturn(url);
        when(url.getProtocol()).thenReturn("https");
        when(url.openConnection()).thenThrow(new Error());
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        mockCall();
        try {
            httpClient.callAsync(urlString, "", new HashMap<>(), callTemplate, serviceCallback);
            fail();
        } catch (Error ignored) {
        }
        verifyNoInteractions(serviceCallback);
        verifyStatic(TrafficStats.class);
        TrafficStats.setThreadStatsTag(anyInt());
        verifyStatic(TrafficStats.class);
        TrafficStats.clearThreadStatsTag();
    }

    @Test
    public void failedSerialization() throws Exception {

        /* Configure mock HTTPS. */
        String urlString = "https://mock/get";
        HttpsURLConnection urlConnection = mockConnection(urlString);

        /* Configure API client. */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        JSONException exception = new JSONException("mock");
        when(callTemplate.buildRequestBody()).thenThrow(exception);
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. */
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_POST, new HashMap<>(), callTemplate, serviceCallback);
        verify(serviceCallback).onCallFailed(exception);
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection).disconnect();
        verifyStatic(TrafficStats.class);
        TrafficStats.setThreadStatsTag(anyInt());
        verifyStatic(TrafficStats.class);
        TrafficStats.clearThreadStatsTag();
    }

    @Test(timeout = 5000)
    @PrepareForTest(HandlerUtils.class)
    public void rejectedAsyncTask() throws Exception {

        /* Mock HandlerUtils to simulate call from background (this unit test) to main (mock) thread. */
        final Semaphore semaphore = new Semaphore(0);
        mockStatic(HandlerUtils.class);
        doAnswer(new Answer<Object>() {

            @Override
            public Object answer(final InvocationOnMock invocation) {
                new Thread("rejectedAsyncTask.handler") {

                    @Override
                    public void run() {
                        ((Runnable) invocation.getArguments()[0]).run();
                        semaphore.release();
                    }
                }.start();
                return null;
            }
        }).when(HandlerUtils.class);
        HandlerUtils.runOnUiThread(any(Runnable.class));

        /* Mock ingestion to fail on saturated executor in AsyncTask. */
        DefaultHttpClientCallTask call = mock(DefaultHttpClientCallTask.class);
        whenNew(DefaultHttpClientCallTask.class).withAnyArguments().thenReturn(call);
        RejectedExecutionException exception = new RejectedExecutionException();
        when(call.executeOnExecutor(any())).thenThrow(exception);
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test. */
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        assertNotNull(httpClient.callAsync("", "", new HashMap<>(), mock(HttpClient.CallTemplate.class), serviceCallback));

        /* Verify the callback call from "main" thread. */
        semaphore.acquireUninterruptibly();
        verify(serviceCallback).onCallFailed(exception);
        verify(serviceCallback, never()).onCallSucceeded(any(HttpResponse.class));
    }

    @Test
    public void sendGzipWithoutVerboseLogging() throws Exception {

        /* Mock no verbose logging. */
        mockStatic(AppCenterLog.class);
        when(AppCenterLog.getLogLevel()).thenReturn(Log.DEBUG);

        /* Configure mock HTTPS. */
        String urlString = "https://mock";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(200);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        when(urlConnection.getInputStream()).thenReturn(new ByteArrayInputStream("OK".getBytes()));

        /* Long mock payload. */
        StringBuilder payloadBuilder = new StringBuilder();
        for (int i = 0; i < 1400; i++) {
            payloadBuilder.append('a');
        }
        final String payload = payloadBuilder.toString();

        /* Compress payload for verification. */
        ByteArrayOutputStream gzipBuffer = new ByteArrayOutputStream(payload.length());
        GZIPOutputStream gzipStream = new GZIPOutputStream(gzipBuffer);
        gzipStream.write(payload.getBytes(StandardCharsets.UTF_8));
        gzipStream.close();
        byte[] compressedBytes = gzipBuffer.toByteArray();

        /* Configure API client. */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        when(callTemplate.buildRequestBody()).thenReturn(payload);
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. */
        String appSecret = UUID.randomUUID().toString();
        UUID installId = UUID.randomUUID();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "custom");
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_POST, headers, callTemplate, serviceCallback);
        verify(serviceCallback).onCallSucceeded(new HttpResponse(200, "OK", Collections.emptyMap()));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection).setRequestProperty("Content-Type", "custom");

        /* Also verify content type was set only once, json not applied. */
        verify(urlConnection).setRequestProperty(eq("Content-Type"), anyString());
        verify(urlConnection).setRequestProperty("Content-Encoding", "gzip");
        verify(urlConnection).setRequestMethod("POST");
        verify(urlConnection).setDoOutput(true);
        verify(urlConnection).disconnect();
        verify(callTemplate).onBeforeCalling(any(URL.class), anyMap());
        verify(callTemplate).buildRequestBody();
        httpClient.close();

        /* Verify payload compressed. */
        assertArrayEquals(compressedBytes, buffer.toByteArray());

        /* Check no payload logging since log level not enabled. */
        verifyStatic(AppCenterLog.class, never());
        AppCenterLog.verbose(anyString(), contains(payload));
    }

    @Test
    public void sendNoGzipWhenCompressionDisabled() throws Exception {

        /* Mock verbose logging. */
        mockStatic(AppCenterLog.class);
        when(AppCenterLog.getLogLevel()).thenReturn(Log.VERBOSE);

        /* Configure mock HTTP. */
        String urlString = "https://mock";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(200);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        when(urlConnection.getInputStream()).thenReturn(new ByteArrayInputStream("OK".getBytes()));

        /* Long mock payload. */
        StringBuilder payloadBuilder = new StringBuilder();
        for (int i = 0; i < 1400; i++) {
            payloadBuilder.append('a');
        }
        final String payload = payloadBuilder.toString();

        /* Configure API client. */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        when(callTemplate.buildRequestBody()).thenReturn(payload);
        DefaultHttpClient httpClient = new DefaultHttpClient(false);

        /* Test calling code. */
        String appSecret = UUID.randomUUID().toString();
        UUID installId = UUID.randomUUID();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "custom");
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_POST, headers, callTemplate, serviceCallback);
        verify(serviceCallback).onCallSucceeded(new HttpResponse(200, "OK", Collections.emptyMap()));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection).setRequestProperty("Content-Type", "custom");

        /* Also verify content type was set only once, json not applied. */
        verify(urlConnection).setRequestProperty(eq("Content-Type"), anyString());
        verify(urlConnection, never()).setRequestProperty("Content-Encoding", "gzip");
        verify(urlConnection).setRequestMethod("POST");
        verify(urlConnection).setDoOutput(true);
        verify(urlConnection).disconnect();
        verify(callTemplate).onBeforeCalling(any(URL.class), anyMap());
        verify(callTemplate).buildRequestBody();
        httpClient.close();

        /* Verify payload not compressed. */
        assertEquals(payload, buffer.toString());

        /* Check payload logged but not as JSON since different content type. */
        verifyStatic(AppCenterLog.class);
        AppCenterLog.verbose(anyString(), contains(payload));
    }

    @Test
    public void sendNoGzipWithPlainTextVerboseLogging() throws Exception {

        /* Mock verbose logging. */
        mockStatic(AppCenterLog.class);
        when(AppCenterLog.getLogLevel()).thenReturn(Log.VERBOSE);

        /* Configure mock HTTPS. */
        String urlString = "https://mock";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(200);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        when(urlConnection.getInputStream()).thenReturn(new ByteArrayInputStream("OK".getBytes()));

        /* Long mock payload. */
        StringBuilder payloadBuilder = new StringBuilder();
        for (int i = 0; i < 1399; i++) {
            payloadBuilder.append('a');
        }
        final String payload = payloadBuilder.toString();

        /* Configure API client. */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        when(callTemplate.buildRequestBody()).thenReturn(payload);
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. */
        String appSecret = UUID.randomUUID().toString();
        UUID installId = UUID.randomUUID();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "custom");
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_POST, headers, callTemplate, serviceCallback);
        verify(serviceCallback).onCallSucceeded(new HttpResponse(200, "OK", Collections.emptyMap()));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection).setRequestProperty("Content-Type", "custom");

        /* Also verify content type was set only once, json not applied. */
        verify(urlConnection).setRequestProperty(eq("Content-Type"), anyString());
        verify(urlConnection, never()).setRequestProperty("Content-Encoding", "gzip");
        verify(urlConnection).setRequestMethod("POST");
        verify(urlConnection).setDoOutput(true);
        verify(urlConnection).disconnect();
        verify(callTemplate).onBeforeCalling(any(URL.class), anyMap());
        verify(callTemplate).buildRequestBody();
        httpClient.close();

        /* Verify payload not compressed. */
        assertEquals(payload, buffer.toString());

        /* Check payload logged but not as JSON since different content type. */
        verifyStatic(AppCenterLog.class);
        AppCenterLog.verbose(anyString(), contains(payload));
    }

    @Test
    public void sendGzipWithHugeTextAndVerboseLogging() throws Exception {

        /* Mock verbose logging. */
        mockStatic(AppCenterLog.class);
        when(AppCenterLog.getLogLevel()).thenReturn(Log.VERBOSE);

        /* Configure mock HTTPS. */
        String urlString = "https://mock";
        HttpsURLConnection urlConnection = mockConnection(urlString);
        when(urlConnection.getResponseCode()).thenReturn(200);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(urlConnection.getOutputStream()).thenReturn(buffer);
        when(urlConnection.getInputStream()).thenReturn(new ByteArrayInputStream("OK".getBytes()));

        /* Long mock payload. */
        StringBuilder payloadBuilder = new StringBuilder();
        for (int i = 0; i < 8000; i++) {
            payloadBuilder.append('a');
        }
        final String payload = payloadBuilder.toString();

        /* Compress payload for verification. */
        ByteArrayOutputStream gzipBuffer = new ByteArrayOutputStream(payload.length());
        GZIPOutputStream gzipStream = new GZIPOutputStream(gzipBuffer);
        gzipStream.write(payload.getBytes(StandardCharsets.UTF_8));
        gzipStream.close();
        byte[] compressedBytes = gzipBuffer.toByteArray();

        /* Configure API client. */
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        when(callTemplate.buildRequestBody()).thenReturn(payload);
        DefaultHttpClient httpClient = new DefaultHttpClient();

        /* Test calling code. */
        String appSecret = UUID.randomUUID().toString();
        UUID installId = UUID.randomUUID();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "custom");
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        mockCall();
        httpClient.callAsync(urlString, METHOD_POST, headers, callTemplate, serviceCallback);
        verify(serviceCallback).onCallSucceeded(new HttpResponse(200, "OK", Collections.emptyMap()));
        verifyNoMoreInteractions(serviceCallback);
        verify(urlConnection).setRequestProperty("Content-Type", "custom");

        /* Also verify content type was set only once, json not applied. */
        verify(urlConnection).setRequestProperty(eq("Content-Type"), anyString());
        verify(urlConnection).setRequestProperty("Content-Encoding", "gzip");
        verify(urlConnection).setRequestMethod("POST");
        verify(urlConnection).setDoOutput(true);
        verify(urlConnection).disconnect();
        verify(callTemplate).onBeforeCalling(any(URL.class), anyMap());
        verify(callTemplate).buildRequestBody();
        httpClient.close();

        /* Verify payload compressed. */
        assertArrayEquals(compressedBytes, buffer.toByteArray());

        /* Check payload logged. */
        verifyStatic(AppCenterLog.class);
        AppCenterLog.verbose(anyString(), contains(payload));
    }

    @Test
    public void failedToConnectWithHttpUrl() throws Exception {
        String urlString = "http://mock/get";
        URL url = mock(URL.class);
        whenNew(URL.class).withAnyArguments().thenReturn(url);
        when(url.getProtocol()).thenReturn("http");
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        mockCall();
        httpClient.callAsync(urlString, "", new HashMap<>(), callTemplate, serviceCallback);
        verify(serviceCallback).onCallFailed(any(IOException.class));
        verifyNoInteractions(callTemplate);
        verifyNoMoreInteractions(serviceCallback);
    }

    @Test
    public void failedToConnectWithInvalidUrl() throws Exception {
        String urlString = "bad url";
        URL url = mock(URL.class);
        whenNew(URL.class).withAnyArguments().thenReturn(url);
        when(url.getProtocol()).thenReturn(null);
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        mockCall();
        httpClient.callAsync(urlString, "", new HashMap<>(), callTemplate, serviceCallback);
        verify(serviceCallback).onCallFailed(any(IOException.class));
        verifyNoInteractions(callTemplate);
        verifyNoMoreInteractions(serviceCallback);
    }

    @Test
    public void failedToConnectWithHttpConnect() throws Exception {
        String urlString = "https://mock/get";
        URL url = mock(URL.class);
        whenNew(URL.class).withAnyArguments().thenReturn(url);
        when(url.getProtocol()).thenReturn("https");
        HttpURLConnection urlConnection = mock(HttpURLConnection.class);
        when(url.openConnection()).thenReturn(urlConnection);
        HttpClient.CallTemplate callTemplate = mock(HttpClient.CallTemplate.class);
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        mockCall();
        httpClient.callAsync(urlString, "", new HashMap<>(), callTemplate, serviceCallback);
        verify(serviceCallback).onCallFailed(any(IOException.class));
        verifyNoInteractions(callTemplate);
        verifyNoMoreInteractions(serviceCallback);
    }
}
