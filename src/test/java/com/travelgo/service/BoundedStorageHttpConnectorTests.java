package com.travelgo.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BoundedStorageHttpConnectorTests {
    @Test void streamingBodyStopsBeforeAccumulatingMoreThanTheResponseBound() {
        var body=new BoundedStorageHttpConnector.LimitedBody(4);var subscription=mock(Flow.Subscription.class);
        body.onSubscribe(subscription);body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3})));body.onNext(List.of(ByteBuffer.wrap(new byte[]{4,5})));
        verify(subscription).cancel();assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void exactBoundaryBodyCompletesAndPreservesBytes() {
        var body=new BoundedStorageHttpConnector.LimitedBody(4);var subscription=mock(Flow.Subscription.class);
        body.onSubscribe(subscription);body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3,4})));body.onComplete();
        assertArrayEquals(new byte[]{1,2,3,4},body.getBody().toCompletableFuture().join());verify(subscription,never()).cancel();
    }
    @Test void clientThatFollowsRedirectsIsRejected() {
        HttpClient client=mock(HttpClient.class);when(client.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
        assertThrows(IllegalArgumentException.class,()->new BoundedStorageHttpConnector(client));verify(client,never()).sendAsync(any(),any());
    }
    @Test void completeBodyDeadlineCancelsSlowResponsesWithGenericFailure() {
        HttpClient client=mock(HttpClient.class);when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        CompletableFuture<HttpResponse<byte[]>> neverCompletes=new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(neverCompletes);
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://syntheticproject.supabase.co/storage/v1/bucket/academic"))
                .timeout(Duration.ofMillis(1)).GET().build();
        IOException failure=assertThrows(IOException.class,()->new BoundedStorageHttpConnector(client).execute(request,16));
        assertTrue(neverCompletes.isCancelled());assertNull(failure.getCause());assertFalse(failure.getMessage().contains("supabase"));
    }
    @Test void providerExceptionsCannotDiscloseBodyOrCredentials() {
        HttpClient client=mock(HttpClient.class);when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(client.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("secret-provider-response")));
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://syntheticproject.supabase.co/storage/v1/bucket/academic"))
                .timeout(Duration.ofMillis(10)).GET().build();
        IOException failure=assertThrows(IOException.class,()->new BoundedStorageHttpConnector(client).execute(request,16));
        assertNull(failure.getCause());assertFalse(failure.getMessage().contains("secret"));
    }
}
