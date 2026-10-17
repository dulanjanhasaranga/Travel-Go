package com.travelgo.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic bytes and fake connectors only; no sockets, existing documents, Spring context or provider account. */
class CloudmersiveDocumentScannerTests {
    private static final String API_KEY = "synthetic-test-key-never-live";
    private static final String FAILURE = "This document could not pass the security check. Please try again later or contact your visa officer.";

    private static CloudmersiveDocumentScanner scanner(CloudmersiveDocumentScanner.Connector connector) {
        return scanner(connector, new AtomicLong());
    }
    private static CloudmersiveDocumentScanner scanner(CloudmersiveDocumentScanner.Connector connector, AtomicLong clock) {
        return new CloudmersiveDocumentScanner(API_KEY, 2000, 15000, 3_000_000, connector, clock::get);
    }
    private static CloudmersiveDocumentScanner.Response response(int status, String body) {
        return new CloudmersiveDocumentScanner.Response(status, body.getBytes(StandardCharsets.UTF_8));
    }
    private static IOException failure(org.junit.jupiter.api.function.Executable action) {
        IOException failure = assertThrows(IOException.class, action);
        assertEquals(FAILURE, failure.getMessage());
        assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
        return failure;
    }

    @ParameterizedTest @ValueSource(strings={"{\"CleanResult\":true,\"FoundViruses\":null}",
            "{\"CleanResult\":true,\"FoundViruses\":[]}"})
    void acceptsOnlyExplicitCleanResultWithNoViruses(String body) {
        assertDoesNotThrow(() -> scanner((request, limit) -> response(200, body)).scan(new byte[]{1, 2, 3}));
    }

    @ParameterizedTest @ValueSource(strings={"", "{", "null", "[]", "true", "{}",
            "{\"FoundViruses\":null}", "{\"CleanResult\":true}", "{\"CleanResult\":null,\"FoundViruses\":null}",
            "{\"CleanResult\":\"true\",\"FoundViruses\":null}", "{\"CleanResult\":1,\"FoundViruses\":null}",
            "{\"CleanResult\":false,\"FoundViruses\":[]}", "{\"CleanResult\":true,\"FoundViruses\":{}}",
            "{\"CleanResult\":true,\"FoundViruses\":false}", "{\"CleanResult\":true,\"FoundViruses\":\"\"}",
            "{\"CleanResult\":true,\"FoundViruses\":null,\"Error\":\"quota-rejected\"}",
            "{\"CleanResult\":true,\"FoundViruses\":[{\"FileName\":\"private-path\",\"VirusName\":\"malware-signature\"}]}",
            "{\"CleanResult\":true,\"CleanResult\":false,\"FoundViruses\":null}",
            "{\"CleanResult\":true,\"FoundViruses\":null,\"FoundViruses\":[]}",
            "{\"CleanResult\":true,\"FoundViruses\":null} {\"CleanResult\":false}"})
    void malwareMissingFieldsWrongTypesDuplicateAndTrailingJsonAllFailClosed(String body) {
        failure(() -> scanner((request, limit) -> response(200, body)).scan(new byte[]{1}));
    }

    @Test void rejectsErrorsQuotaRedirectsAndNullOrOversizedResponsesWithoutRetry() {
        for (int status : new int[]{201, 204, 301, 302, 307, 400, 401, 403, 413, 429, 500, 503}) {
            AtomicInteger calls = new AtomicInteger();
            failure(() -> scanner((request, limit) -> {
                calls.incrementAndGet(); return response(status, "private-response-" + API_KEY);
            }).scan(new byte[]{1}));
            assertEquals(1, calls.get());
        }
        failure(() -> scanner((request, limit) -> null).scan(new byte[]{1}));
        failure(() -> scanner((request, limit) -> new CloudmersiveDocumentScanner.Response(200, null)).scan(new byte[]{1}));
        failure(() -> scanner((request, limit) -> response(200, " ".repeat(16 * 1024 + 1))).scan(new byte[]{1}));
        failure(() -> scanner((request, limit) -> response(200,
                "{\"CleanResult\":true,\"FoundViruses\":null,\"nested\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}")).scan(new byte[]{1}));
    }

    @Test void boundsDocumentBeforeAnyProviderAdmission() {
        AtomicInteger calls = new AtomicInteger();
        var scanner = scanner((request, limit) -> { calls.incrementAndGet(); return response(200, "{\"CleanResult\":true,\"FoundViruses\":null}"); });
        for (byte[] content : Arrays.asList(null, new byte[0], new byte[3_000_001])) failure(() -> scanner.scan(content));
        assertEquals(0, calls.get());
        assertDoesNotThrow(() -> scanner.scan(new byte[3_000_000]));
        assertEquals(1, calls.get());
    }

    @Test void sendsFixedHttpsPostWithApikeyMultipartInputFileExactBytesAndBoundedDeadline() throws Exception {
        byte[] document = new byte[]{0, 1, (byte)255, 13, 10, 2};
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        scanner((request, limit) -> {
            sent.set(request); assertEquals(16 * 1024, limit);
            return response(200, "{\"CleanResult\":true,\"FoundViruses\":[]}");
        }).scan(document);
        HttpRequest request = sent.get();
        assertEquals("https://api.cloudmersive.com/virus/scan/file", request.uri().toString());
        assertEquals("POST", request.method()); assertEquals(Duration.ofMillis(15000), request.timeout().orElseThrow());
        assertEquals(API_KEY, request.headers().firstValue("Apikey").orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
        assertEquals("identity", request.headers().firstValue("Accept-Encoding").orElseThrow());
        String contentType = request.headers().firstValue("Content-Type").orElseThrow();
        assertTrue(contentType.startsWith("multipart/form-data; boundary=TravelGO"));
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"inputFile\"; filename=\"document.bin\""
                + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        var expected = new ByteArrayOutputStream(); expected.write(prefix); expected.write(document); expected.write(suffix);
        assertArrayEquals(expected.toByteArray(), body(request));
        assertFalse(new String(body(request), StandardCharsets.ISO_8859_1).contains(API_KEY));
    }

    @Test void rejectsOverlappingScanImmediatelyAndReleasesAdmissionAfterCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(); AtomicLong clock = new AtomicLong();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        var scanner = scanner((request, limit) -> {
            calls.incrementAndGet(); entered.countDown();
            try { if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("fake transport wait expired"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(); }
            return response(200, "{\"CleanResult\":true,\"FoundViruses\":null}");
        }, clock);
        Thread first = new Thread(() -> { try { scanner.scan(new byte[]{1}); } catch (Throwable failure) { threadFailure.set(failure); } });
        first.start();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTimeout(Duration.ofMillis(250), () -> failure(() -> scanner.scan(new byte[]{2})));
            assertEquals(1, calls.get());
        } finally { release.countDown(); first.join(2000); }
        assertFalse(first.isAlive()); assertNull(threadFailure.get());
        clock.set(TimeUnit.SECONDS.toNanos(1));
        assertDoesNotThrow(() -> scanner.scan(new byte[]{3})); assertEquals(2, calls.get());
    }

    @Test void monotonicRequestStartsAreAtLeastOneSecondApartIncludingFailedRequests() {
        AtomicLong clock = new AtomicLong(); AtomicInteger calls = new AtomicInteger();
        var scanner = scanner((request, limit) -> {
            if (calls.incrementAndGet() == 1) throw new IOException("private-path " + API_KEY);
            return response(200, "{\"CleanResult\":true,\"FoundViruses\":null}");
        }, clock);
        failure(() -> scanner.scan(new byte[]{1}));
        failure(() -> scanner.scan(new byte[]{1}));
        clock.set(999_999_999); failure(() -> scanner.scan(new byte[]{1}));
        assertEquals(1, calls.get());
        clock.set(1_000_000_000); assertDoesNotThrow(() -> scanner.scan(new byte[]{1})); assertEquals(2, calls.get());
    }

    @Test void transportRuntimeAndCheckedFailuresNeverExposeKeysPathsOrCauses() {
        failure(() -> scanner((request, limit) -> { throw new IOException("private-path " + API_KEY); }).scan(new byte[]{1}));
        failure(() -> scanner((request, limit) -> { throw new IllegalStateException("provider-body " + API_KEY); }).scan(new byte[]{1}));
        try {
            Thread.currentThread().interrupt();
            failure(() -> scanner((request, limit) -> { fail("Interrupted scan must not send"); return null; }).scan(new byte[]{1}));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void configurationCannotEnlargeCapsDisableDeadlinesOrInjectHeaderData() {
        for (String key : new String[]{"", "short", API_KEY + "\r\nInjected: yes", "x".repeat(257), "secret key with spaces"})
            assertThrows(IllegalArgumentException.class, () -> CloudmersiveDocumentScanner.validateConfiguration(key, 2000, 15000, 3_000_000));
        for (int timeout : new int[]{-1, 0, 499, 10001})
            assertThrows(IllegalArgumentException.class, () -> CloudmersiveDocumentScanner.validateConfiguration(API_KEY, timeout, 15000, 3_000_000));
        for (int timeout : new int[]{-1, 0, 999, 30001})
            assertThrows(IllegalArgumentException.class, () -> CloudmersiveDocumentScanner.validateConfiguration(API_KEY, 500, timeout, 3_000_000));
        assertThrows(IllegalArgumentException.class, () -> CloudmersiveDocumentScanner.validateConfiguration(API_KEY, 2000, 1000, 3_000_000));
        for (long size : new long[]{-1, 0, 3_000_001})
            assertThrows(IllegalArgumentException.class, () -> CloudmersiveDocumentScanner.validateConfiguration(API_KEY, 2000, 15000, size));
    }

    @Test void pureEarlyGuardRejectsCloudOutsideSoleEvaluationProfileEvenWhenDisabled() {
        for (String[] profiles : new String[][]{{}, {"prod"}, {"demo"}, {"local"}, {"cloud-evaluation", "prod"}, {"cloud-evaluation", "demo"}}) {
            for (boolean enabled : new boolean[]{true, false}) {
                MockEnvironment environment = cloudEnvironment(); environment.setActiveProfiles(profiles);
                environment.setProperty("travelgo.documents.scan.enabled", Boolean.toString(enabled));
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> VisaDocumentScanner.validateEnvironment(environment));
                assertNull(failure.getCause()); assertFalse(failure.toString().contains(API_KEY));
                assertThrows(IllegalArgumentException.class, () -> new VisaDocumentScanner(enabled, "127.0.0.1", 3310, 2000, 15000,
                        3_000_000, "cloudmersive", "", environment));
            }
        }
        var disabled = cloudEnvironment().withProperty("travelgo.documents.scan.enabled", "false");
        assertThrows(IllegalArgumentException.class, () -> VisaDocumentScanner.validateEnvironment(disabled));
        assertDoesNotThrow(() -> VisaDocumentScanner.validateEnvironment(cloudEnvironment()));
    }

    @Test void earlyGuardRejectsInvalidCloudSettingsWithoutRetainingValuesOrConversionCause() {
        for (String property : new String[]{"travelgo.documents.cloudmersive.api-key", "travelgo.documents.cloudmersive.connect-timeout-ms",
                "travelgo.documents.cloudmersive.request-timeout-ms", "travelgo.documents.scan.max-bytes"}) {
            // A credential with whitespace is syntactically invalid; a hyphenated
            // string alone could legitimately satisfy the documented key bounds.
            var environment = cloudEnvironment().withProperty(property, "private invalid value");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> VisaDocumentScanner.validateEnvironment(environment));
            assertNull(failure.getCause()); assertFalse(failure.toString().contains("private invalid value"));
        }
        assertThrows(IllegalArgumentException.class, () -> VisaDocumentScanner.validateEnvironment(
                cloudEnvironment().withProperty("travelgo.documents.scan.max-bytes", "3000001")));
    }

    @Test void defaultAndProductionRemainClamdWithExistingConstructorAndBounds() {
        MockEnvironment environment = new MockEnvironment();
        assertDoesNotThrow(() -> VisaDocumentScanner.validateEnvironment(environment));
        environment.setActiveProfiles("prod");
        assertDoesNotThrow(() -> VisaDocumentScanner.validateEnvironment(environment));
        assertDoesNotThrow(() -> VisaDocumentScanner.validateBackend("clamd", true));
        assertThrows(IllegalArgumentException.class, () -> VisaDocumentScanner.validateBackend("cloudmersive", true));
        assertThrows(IllegalArgumentException.class, () -> VisaDocumentScanner.validateBackend("cloudmersive", false));
        var scanner = new VisaDocumentScanner(false, "127.0.0.1", 3310, 2000, 15000, 10485760, "clamd", "", environment);
        assertDoesNotThrow(() -> scanner.scan(null));
    }

    @Test void httpsConnectorRefusesRedirectPolicyAndAnyAlternateEndpointWithoutSending() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
        assertThrows(IllegalArgumentException.class, () -> new CloudmersiveDocumentScanner.HttpsConnector(client));
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        var connector = new CloudmersiveDocumentScanner.HttpsConnector(client);
        failure(() -> connector.execute(HttpRequest.newBuilder(java.net.URI.create("http://api.cloudmersive.com/virus/scan/file"))
                .timeout(Duration.ofMillis(50)).POST(HttpRequest.BodyPublishers.noBody()).build(), 16 * 1024));
        failure(() -> connector.execute(HttpRequest.newBuilder(CloudmersiveDocumentScanner.ENDPOINT).GET().build(), 16 * 1024));
        verify(client, never()).sendAsync(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test void entireBodyDeadlineCancelsPendingExchangeAfterHeadersAndPartialBody() {
        HttpClient client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(call -> {
            HttpResponse.BodyHandler<byte[]> handler = call.getArgument(1);
            var subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            subscriber.onSubscribe(mock(Flow.Subscription.class));
            subscriber.onNext(List.of(ByteBuffer.wrap("{\"CleanResult\":true".getBytes(StandardCharsets.UTF_8))));
            assertFalse(subscriber.getBody().toCompletableFuture().isDone());
            return pending;
        });
        var connector = new CloudmersiveDocumentScanner.HttpsConnector(client);
        HttpRequest request = HttpRequest.newBuilder(CloudmersiveDocumentScanner.ENDPOINT)
                .timeout(Duration.ofMillis(40)).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertTimeout(Duration.ofSeconds(1), () -> failure(() -> connector.execute(request, 16 * 1024)));
        assertTrue(pending.isCancelled());
        verify(client, times(1)).sendAsync(eq(request), any(HttpResponse.BodyHandler.class));
    }

    @Test void httpsConnectorCompletesOnlyAfterWholeBoundedBodyAndPreservesInterruptOnCancellation() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        byte[] cleanBody = "{\"CleanResult\":true,\"FoundViruses\":null}".getBytes(StandardCharsets.UTF_8);
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(call -> {
            HttpResponse.BodyHandler<byte[]> handler = call.getArgument(1);
            var subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            subscriber.onSubscribe(mock(Flow.Subscription.class));
            subscriber.onNext(List.of(ByteBuffer.wrap(cleanBody)));
            assertFalse(subscriber.getBody().toCompletableFuture().isDone());
            subscriber.onComplete();
            HttpResponse<byte[]> reply = mock(HttpResponse.class);
            when(reply.statusCode()).thenReturn(200);
            when(reply.body()).thenReturn(subscriber.getBody().toCompletableFuture().join());
            return CompletableFuture.completedFuture(reply);
        });
        var connector = new CloudmersiveDocumentScanner.HttpsConnector(client);
        HttpRequest request = HttpRequest.newBuilder(CloudmersiveDocumentScanner.ENDPOINT)
                .timeout(Duration.ofSeconds(1)).POST(HttpRequest.BodyPublishers.noBody()).build();
        var result = connector.execute(request, 16 * 1024);
        assertEquals(200, result.status()); assertArrayEquals(cleanBody, result.body());

        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(pending);
        try {
            Thread.currentThread().interrupt();
            failure(() -> connector.execute(request, 16 * 1024));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(pending.isCancelled());
        } finally { Thread.interrupted(); }
    }

    @Test void responseSubscriberBoundsMemoryCancelsOversizeAndRedactsErrors() {
        var subscriber = new CloudmersiveDocumentScanner.LimitedBody(16 * 1024);
        Flow.Subscription subscription = mock(Flow.Subscription.class); subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[16 * 1024]), ByteBuffer.wrap(new byte[]{1})));
        verify(subscription).cancel();
        var exception = assertThrows(java.util.concurrent.CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
        assertEquals(FAILURE, exception.getCause().getMessage()); assertNull(exception.getCause().getCause());
        var failed = new CloudmersiveDocumentScanner.LimitedBody(16 * 1024);
        failed.onError(new IOException("private-provider-body " + API_KEY));
        var error = assertThrows(java.util.concurrent.CompletionException.class, () -> failed.getBody().toCompletableFuture().join());
        assertEquals(FAILURE, error.getCause().getMessage()); assertNull(error.getCause().getCause());
    }

    private static MockEnvironment cloudEnvironment() {
        var environment = new MockEnvironment().withProperty("travelgo.documents.scan.backend", "cloudmersive")
                .withProperty("travelgo.documents.scan.enabled", "true").withProperty("travelgo.documents.scan.max-bytes", "3000000")
                .withProperty("travelgo.documents.cloudmersive.api-key", API_KEY);
        environment.setActiveProfiles("cloud-evaluation"); return environment;
    }

    private static byte[] body(HttpRequest request) {
        var bytes = new ByteArrayOutputStream(); var completed = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) { byte[] data = new byte[buffer.remaining()]; buffer.get(data); bytes.writeBytes(data); }
            @Override public void onError(Throwable failure) { completed.completeExceptionally(failure); }
            @Override public void onComplete() { completed.complete(bytes.toByteArray()); }
        });
        return completed.join();
    }
}
