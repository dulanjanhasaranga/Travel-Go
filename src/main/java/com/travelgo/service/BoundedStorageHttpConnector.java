package com.travelgo.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Bounds response memory while receiving and applies a deadline through completion of the entire body. */
final class BoundedStorageHttpConnector implements SupabaseVisaDocumentStorage.Connector {
    private final HttpClient client;
    BoundedStorageHttpConnector(HttpClient client) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) throw new IllegalArgumentException("Private storage redirects must be disabled.");
        this.client=client;
    }

    @Override public SupabaseVisaDocumentStorage.Response execute(HttpRequest request,int responseLimit) throws IOException {
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            pending=client.sendAsync(request,info -> new LimitedBody(responseLimit));
            HttpResponse<byte[]> response=pending.get(request.timeout().orElseThrow().toMillis(),TimeUnit.MILLISECONDS);
            return new SupabaseVisaDocumentStorage.Response(response.statusCode(),response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw VisaDocumentStorage.unavailable();
        } catch (Exception failure) { throw VisaDocumentStorage.unavailable(); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) {
            if (limit < 1 || limit > VisaDocumentStorage.MAX_DOCUMENT_BYTES) throw new IllegalArgumentException("Storage response bound is invalid.");
            this.limit=limit;
        }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription=subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (result.isDone()) return;
            for (ByteBuffer chunk:chunks) {
                int remaining=chunk.remaining();
                if (remaining > limit-bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(VisaDocumentStorage.unavailable()); return;
                }
                byte[] data=new byte[remaining]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(VisaDocumentStorage.unavailable()); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
