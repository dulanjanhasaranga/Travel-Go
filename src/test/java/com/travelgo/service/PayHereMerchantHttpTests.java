package com.travelgo.service;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Capture HTTP requests at the transport seam. No DNS or provider requests are made. */
class PayHereMerchantHttpTests {
    static class Capture extends PayHereMerchantClient {
        final List<HttpRequest> requests=new ArrayList<>();final Queue<Response> responses=new ArrayDeque<>();
        Capture(){super("SyntheticApp123456","SyntheticSecret123456","test");}
        @Override protected Response exchange(HttpRequest request){requests.add(request);return responses.remove();}
        void response(String body){responses.add(new Response(200,body));}
        void token(){response("{\"access_token\":\"SyntheticAccessToken\",\"token_type\":\"bearer\",\"expires_in\":599,\"scope\":\"SANDBOX\"}");}
    }
    static String body(HttpRequest request)throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();var done=new CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer buffer){while(buffer.hasRemaining())bytes.write(buffer.get());}
            public void onError(Throwable failure){done.completeExceptionally(failure);}public void onComplete(){done.complete(null);}
        });done.get(2,TimeUnit.SECONDS);return bytes.toString(StandardCharsets.UTF_8);
    }
    @Test void oauthSearchAndPartialRefundUseFixedHostsAndStoredAmount()throws Exception{
        var client=new Capture();client.token();client.response("{\"status\":1,\"data\":[]}");client.payment("TGPH-7-abcdef0123456789abcdef01");
        assertEquals("https://sandbox.payhere.lk/merchant/v1/oauth/token",client.requests.get(0).uri().toString());assertEquals("grant_type=client_credentials",body(client.requests.get(0)));
        assertTrue(client.requests.get(0).headers().firstValue("Authorization").orElseThrow().startsWith("Basic "));
        assertEquals("GET",client.requests.get(1).method());assertEquals("Bearer SyntheticAccessToken",client.requests.get(1).headers().firstValue("Authorization").orElseThrow());
        client.token();client.response("{\"status\":1,\"data\":560000000001}");client.refund("320000000001","10.00","Synthetic refund evidence");
        var outbound=client.requests.get(3);assertEquals("https://sandbox.payhere.lk/merchant/v1/payment/refund",outbound.uri().toString());assertEquals("POST",outbound.method());
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body(outbound));assertEquals("10.00",json.path("amount").asText());assertEquals("320000000001",json.path("payment_id").asText());assertFalse(json.has("authorization_token"));
        assertTrue(outbound.timeout().orElseThrow().toSeconds()<=20);
    }
    @Test void redirectsUnexpectedTokenAndInvalidPaymentAreNotFollowed(){
        var client=new Capture();client.responses.add(new PayHereMerchantClient.Response(302,"{}"));var redirectClient=client;assertThrows(IllegalStateException.class,()->redirectClient.payment("TGPH-7-abcdef0123456789abcdef01"));assertEquals(1,client.requests.size());
        client=new Capture();client.response("{\"access_token\":\"token\",\"token_type\":\"bearer\",\"expires_in\":599,\"scope\":\"LIVE\"}");var invalidTokenClient=client;
        assertThrows(IllegalStateException.class,()->invalidTokenClient.payment("TGPH-7-abcdef0123456789abcdef01"));assertEquals(1,client.requests.size());
        assertThrows(IllegalArgumentException.class,()->invalidTokenClient.payment("https://other.test/"));assertThrows(IllegalArgumentException.class,()->invalidTokenClient.refund("pi_invalid","10.00","invalid"));
    }
    static class Subscription implements Flow.Subscription {
        long requests; boolean cancelled;
        public void request(long count) { requests += count; }
        public void cancel() { cancelled = true; }
    }
    @Test void boundedBodyCompletesOnlyAfterAllChunksAndAcceptsExactByteLimit()throws Exception{
        var subscriber=new PayHereMerchantClient.BoundedBodySubscriber(6);var subscription=new Subscription();subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap("abc".getBytes(StandardCharsets.UTF_8)),ByteBuffer.wrap("def".getBytes(StandardCharsets.UTF_8))));
        assertFalse(subscriber.getBody().toCompletableFuture().isDone());subscriber.onComplete();
        assertEquals("abcdef",subscriber.getBody().toCompletableFuture().get());assertFalse(subscription.cancelled);
    }
    @Test void oversizedBodyCancelsSubscriptionBeforeAccumulatingTheExtraChunk(){
        var subscriber=new PayHereMerchantClient.BoundedBodySubscriber(6);var subscription=new Subscription();subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[4])));var extra=ByteBuffer.wrap(new byte[3]);subscriber.onNext(List.of(extra));
        assertTrue(subscription.cancelled);assertEquals(0,extra.position());subscriber.onComplete();
        var failure=assertThrows(ExecutionException.class,()->subscriber.getBody().toCompletableFuture().get());assertInstanceOf(java.io.IOException.class,failure.getCause());
    }
    @Test void totalDeadlineCancelsAStalledBodyAndItsHttpFuture(){
        var subscriber=new PayHereMerchantClient.BoundedBodySubscriber(200_000);var subscription=new Subscription();subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap("partial".getBytes(StandardCharsets.UTF_8))));
        var pending=new CompletableFuture<java.net.http.HttpResponse<String>>();
        assertThrows(java.io.IOException.class,()->PayHereMerchantClient.awaitResponse(pending,subscriber,Duration.ofMillis(25)));
        assertTrue(pending.isCancelled());assertTrue(subscription.cancelled);assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void interruptionCancelsThePendingBodyAndHttpFuture(){
        var subscriber=new PayHereMerchantClient.BoundedBodySubscriber(200_000);var subscription=new Subscription();subscriber.onSubscribe(subscription);
        var pending=new CompletableFuture<java.net.http.HttpResponse<String>>();Thread.currentThread().interrupt();
        try{assertThrows(InterruptedException.class,()->PayHereMerchantClient.awaitResponse(pending,subscriber,Duration.ofSeconds(20)));
            assertTrue(pending.isCancelled());assertTrue(subscription.cancelled);
        }finally{Thread.interrupted();}
    }
}
