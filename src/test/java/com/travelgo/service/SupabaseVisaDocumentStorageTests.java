package com.travelgo.service;

import com.travelgo.config.DocumentStorageConfiguration;
import com.travelgo.entity.VisaApplication;
import com.travelgo.entity.VisaDocument;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Fake connector only: no Supabase account, network, environment secret or working files. */
class SupabaseVisaDocumentStorageTests {
    @TempDir Path root;
    static final String ORIGIN="https://syntheticproject.supabase.co";
    static final String KEY="sb_secret_synthetic_storage_test_key_123456789";
    static final byte[] PDF="%PDF-1.4\nSynthetic academic fixture\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    static final class FakeConnector implements SupabaseVisaDocumentStorage.Connector {
        final List<HttpRequest> requests=new ArrayList<>();
        final List<Integer> limits=new ArrayList<>();
        String metadata="{\"id\":\"academic-documents\",\"public\":false}";
        String deleteBody="[]";
        int uploadStatus=200, downloadStatus=200, deleteStatus=200;
        byte[] downloaded=PDF;
        boolean failUpload;
        @Override public SupabaseVisaDocumentStorage.Response execute(HttpRequest request,int limit) throws IOException {
            requests.add(request); limits.add(limit);
            if (request.uri().getPath().contains("/bucket/")) return response(200,metadata.getBytes(StandardCharsets.UTF_8));
            if (request.method().equals("POST")) {
                if (failUpload) throw new IOException("provider secret body/private path");
                return response(uploadStatus,"provider secret body/private path".getBytes(StandardCharsets.UTF_8));
            }
            if (request.method().equals("DELETE")) return response(deleteStatus,deleteBody.getBytes(StandardCharsets.UTF_8));
            return response(downloadStatus,downloaded);
        }
        SupabaseVisaDocumentStorage.Response response(int status,byte[] body) { return new SupabaseVisaDocumentStorage.Response(status,body); }
    }
    SupabaseVisaDocumentStorage storage(FakeConnector connector) {
        return storage(connector,System::nanoTime);
    }
    SupabaseVisaDocumentStorage storage(SupabaseVisaDocumentStorage.Connector connector,LongSupplier nanoTime) {
        return new SupabaseVisaDocumentStorage(ORIGIN,"academic-documents",KEY,3000,15000,
                new FilesystemVisaDocumentStorage(root.toString()),connector,nanoTime);
    }

    @Test void filesystemRemainsDefaultAndDoesNotValidateUnusedRemoteSecrets() {
        assertInstanceOf(FilesystemVisaDocumentStorage.class,new DocumentStorageConfiguration().visaDocumentStorage(
                "filesystem",root.toString(),"not a url","public-bucket","publishable",0,0));
    }
    @Test void unknownBackendFailsClosed() {
        assertThrows(IllegalArgumentException.class,()->new DocumentStorageConfiguration().visaDocumentStorage(
                "unknown",root.toString(),ORIGIN,"academic-documents",KEY,3000,15000));
    }
    @Test void constructorMakesNoProviderCalls() {
        var connector=new FakeConnector(); storage(connector); assertTrue(connector.requests.isEmpty());
    }
    @Test void acceptsOnlyExactProviderHttpsOriginsWithoutArbitraryPathsPortsOrCredentials() {
        for (String url:List.of("http://syntheticproject.supabase.co","https://evil.invalid","https://syntheticproject.supabase.co.evil.invalid",
                "https://user@syntheticproject.supabase.co","https://syntheticproject.supabase.co:443","https://syntheticproject.supabase.co/",
                "https://syntheticproject.supabase.co/path","https://syntheticproject.supabase.co?q=x","https://syntheticproject.supabase.co#fragment")) {
            var failure=assertThrows(IllegalArgumentException.class,()->SupabaseVisaDocumentStorage.validateConfiguration(url,"academic-documents",KEY,3000,15000));
            assertFalse(failure.getMessage().contains(url)); assertNull(failure.getCause());
        }
    }
    @Test void clientKeysAndNonServiceRoleJwtsAreRejectedAndLegacyServiceRoleIsSupported() {
        for (String key:List.of("sb_publishable_synthetic_1234567890123456789","anon-key",jwt("anon"),jwt("authenticated"),KEY+"\n"))
            assertThrows(IllegalArgumentException.class,()->SupabaseVisaDocumentStorage.validateConfiguration(ORIGIN,"academic-documents",key,3000,15000));
        assertDoesNotThrow(()->SupabaseVisaDocumentStorage.validateConfiguration(ORIGIN,"academic-documents",jwt("service_role"),3000,15000));
    }
    private String jwt(String role) {
        return "e30."+Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"role\":\""+role+"\"}").getBytes(StandardCharsets.US_ASCII))+".c3ludGhldGlj";
    }
    @Test void invalidBucketAndUnboundedTimeoutsAreRejected() {
        for (String bucket:List.of("../other","with/slash","PUBLIC","a?x=1",""))
            assertThrows(IllegalArgumentException.class,()->SupabaseVisaDocumentStorage.validateConfiguration(ORIGIN,bucket,KEY,3000,15000));
        assertThrows(IllegalArgumentException.class,()->SupabaseVisaDocumentStorage.validateConfiguration(ORIGIN,"academic-documents",KEY,499,15000));
        assertThrows(IllegalArgumentException.class,()->SupabaseVisaDocumentStorage.validateConfiguration(ORIGIN,"academic-documents",KEY,3000,30001));
        assertThrows(IllegalArgumentException.class,()->SupabaseVisaDocumentStorage.validateConfiguration(ORIGIN,"academic-documents",KEY,3000,1000));
    }
    @Test void privateBucketCheckedBeforeExclusiveCreationAndServerOnlyHeadersAreUsed() throws Exception {
        var connector=new FakeConnector(); var backend=storage(connector);
        var stored=backend.store(PDF,"pdf");
        assertEquals(2,connector.requests.size());
        assertEquals("/storage/v1/bucket/academic-documents",connector.requests.get(0).uri().getPath());
        HttpRequest upload=connector.requests.get(1);
        var reference=SupabaseDocumentReference.parse(stored.reference());
        assertEquals("POST",upload.method());
        assertEquals("/storage/v1/object/academic-documents/"+reference.key(),upload.uri().getPath());
        assertEquals("false",upload.headers().firstValue("x-upsert").orElseThrow());
        assertTrue(upload.headers().firstValue("Authorization").isEmpty());
        assertEquals(KEY,upload.headers().firstValue("apikey").orElseThrow());
        assertEquals("application/pdf",upload.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(15000,upload.timeout().orElseThrow().toMillis());
        assertEquals(PDF.length,upload.bodyPublisher().orElseThrow().contentLength());
        assertEquals(PDF.length,reference.length()); assertTrue(reference.matches(PDF));
        assertTrue(stored.reference().length()<255); assertFalse(stored.reference().contains("https"));
        assertFalse(stored.reference().contains(KEY));
    }
    @Test void legacyServiceRoleJwtUsesBothAuthenticatedBearerAndApiKeyHeaders() throws Exception {
        var connector=new FakeConnector();String jwt=jwt("service_role");
        var backend=new SupabaseVisaDocumentStorage(ORIGIN,"academic-documents",jwt,3000,15000,
                new FilesystemVisaDocumentStorage(root.toString()),connector);
        backend.store(PDF,"pdf");
        for(HttpRequest request:connector.requests) {
            assertEquals("Bearer "+jwt,request.headers().firstValue("Authorization").orElseThrow());
            assertEquals(jwt,request.headers().firstValue("apikey").orElseThrow());
        }
    }
    @Test void publicMissingOrAmbiguousBucketMetadataRejectsBeforeUpload() {
        for (String metadata:List.of("{\"id\":\"academic-documents\",\"public\":true}","{\"id\":\"academic-documents\"}",
                "{\"id\":\"other\",\"public\":false}","{\"id\":\"academic-documents\",\"public\":\"false\"}",
                "{\"id\":\"academic-documents\",\"public\":true,\"public\":false}","{} {}","not-json")) {
            var connector=new FakeConnector();connector.metadata=metadata;
            IOException failure=assertThrows(IOException.class,()->storage(connector).store(PDF,"pdf"));
            assertEquals(1,connector.requests.size()); assertNull(failure.getCause()); assertFalse(failure.getMessage().contains(metadata));
        }
    }
    @Test void numericBucketIdentityMustStillBeTextual() {
        var connector=new FakeConnector();connector.metadata="{\"id\":123,\"public\":false}";
        var backend=new SupabaseVisaDocumentStorage(ORIGIN,"123",KEY,3000,15000,
                new FilesystemVisaDocumentStorage(root.toString()),connector);
        assertThrows(IOException.class,()->backend.store(PDF,"pdf"));
        assertEquals(1,connector.requests.size());
    }
    @Test void exactContentIsFetchedThroughAuthenticatedEndpointAndComparedWithImmutableSizeAndHash() throws Exception {
        var connector=new FakeConnector(); var backend=storage(connector);
        String reference=backend.store(PDF,"pdf").reference(); connector.requests.clear();connector.limits.clear();
        assertArrayEquals(PDF,backend.read(reference).bytes());
        assertEquals(2,connector.requests.size());
        assertEquals("/storage/v1/object/authenticated/academic-documents/"+SupabaseDocumentReference.parse(reference).key(),connector.requests.get(1).uri().getPath());
        assertEquals(PDF.length,connector.limits.get(1));
        for(byte[] corruption:List.of(new byte[PDF.length-1],new byte[PDF.length],new byte[PDF.length+1])) {
            connector.downloaded=corruption;
            assertThrows(IOException.class,()->backend.read(reference));
        }
    }
    @Test void publicBucketChangePreventsDownloadEvenWhenObjectExists() throws Exception {
        var connector=new FakeConnector();var backend=storage(connector,new AtomicLong()::get);
        assertTrue(backend.healthy());String reference=backend.store(PDF,"pdf").reference();
        connector.requests.clear(); connector.metadata="{\"id\":\"academic-documents\",\"public\":true}";
        assertTrue(backend.healthy());
        assertThrows(IOException.class,()->backend.read(reference));assertEquals(1,connector.requests.size());
    }
    @Test void publicBucketChangePreventsUploadEvenWithPositiveCachedHealth() {
        var connector=new FakeConnector();var backend=storage(connector,new AtomicLong()::get);
        assertTrue(backend.healthy());
        connector.metadata="{\"id\":\"academic-documents\",\"public\":true}";
        assertTrue(backend.healthy());
        assertThrows(IOException.class,()->backend.store(PDF,"pdf"));
        assertEquals(2,connector.requests.size());
        assertTrue(connector.requests.stream().allMatch(request->request.method().equals("GET")));
    }
    @Test void arbitraryUrlsTraversalMalformedVersionLengthOrHashNeverReachProvider() {
        String valid=SupabaseDocumentReference.fresh(PDF,"pdf").encode();
        for(String value:List.of("https://evil.invalid/private.pdf","supabase:v1:../private.pdf:1:"+"a".repeat(64),
                valid.replace("v1:","v2:"),valid.replace(":"+PDF.length+":",":0:"),
                valid.replace(":"+PDF.length+":",":99999999:"),valid+"?x=1",valid.substring(0,valid.length()-1))) {
            var connector=new FakeConnector(); var backend=storage(connector);
            assertThrows(IOException.class,()->backend.read(value));assertTrue(connector.requests.isEmpty());
        }
    }
    @Test void quotaUsesAcceptedReferenceLengthsWithoutHoldingLocksForRemoteHeadCalls() throws Exception {
        var connector=new FakeConnector();var backend=storage(connector);
        var visa=new VisaApplication();visa.setId(1L);
        var document=new VisaDocument();document.setVisaApplication(visa);document.setDocumentUrl(SupabaseDocumentReference.fresh(PDF,"pdf").encode());
        var quota=new DocumentUploadQuota(30,120,104857600,524288000);
        assertEquals(new DocumentUploadQuota.Usage(PDF.length,PDF.length),quota.inspect(List.of(document),1L,backend));
        assertTrue(connector.requests.isEmpty());
        document.setDocumentUrl(document.getDocumentUrl()+":tampered");
        assertThrows(IOException.class,()->quota.inspect(List.of(document),1L,backend)); assertTrue(connector.requests.isEmpty());
    }
    @Test void existingFilesystemReferencesRemainReadableAndCountableWhenRemoteBackendSelected() throws Exception {
        Path file=Files.write(root.resolve("legacy.pdf"),PDF);var connector=new FakeConnector();var backend=storage(connector);
        assertArrayEquals(PDF,backend.read(file.toString()).bytes());assertEquals(PDF.length,backend.retainedSize(file.toString()));
        assertTrue(connector.requests.isEmpty());
    }
    @Test void rollbackCleanupDeletesOnlyTheFreshGeneratedObjectAndCannotOverwrite() throws Exception {
        var connector=new FakeConnector();var stored=storage(connector).store(PDF,"pdf");
        var upload=connector.requests.get(1);
        stored.cleanup().run();
        var cleanup=connector.requests.get(2);assertEquals("DELETE",cleanup.method());
        assertEquals(ORIGIN+"/storage/v1/object/academic-documents",cleanup.uri().toString());
        assertEquals("application/json",cleanup.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("{\"prefixes\":[\""+SupabaseDocumentReference.parse(stored.reference()).key()+"\"]}",requestBody(cleanup));
        assertTrue(upload.uri().getPath().endsWith("/"+SupabaseDocumentReference.parse(stored.reference()).key()));
        assertFalse(connector.requests.stream().anyMatch(request->request.method().equals("PUT")));
    }
    @Test void ambiguousUploadFailureAttemptsCleanupAndSuppressesProviderDiagnostics() throws Exception {
        var connector=new FakeConnector();connector.failUpload=true;
        var failure=assertThrows(IOException.class,()->storage(connector).store(PDF,"pdf"));
        assertEquals(3,connector.requests.size());assertEquals("DELETE",connector.requests.get(2).method());
        String generatedKey=connector.requests.get(1).uri().getPath().substring(connector.requests.get(1).uri().getPath().lastIndexOf('/')+1);
        assertEquals(ORIGIN+"/storage/v1/object/academic-documents",connector.requests.get(2).uri().toString());
        assertEquals("{\"prefixes\":[\""+generatedKey+"\"]}",requestBody(connector.requests.get(2)));
        assertFalse(failure.getMessage().contains("provider"));assertFalse(failure.getMessage().contains(KEY));assertNull(failure.getCause());
    }
    @Test void cleanupRequiresDocumentedSuccessfulArrayAndRejects404Or204AsProofOfRemoval() throws Exception {
        var connector=new FakeConnector();var stored=storage(connector).store(PDF,"pdf");
        for(int status:List.of(204,301,401,403,404,500)) {
            connector.deleteStatus=status;
            var failure=assertThrows(IOException.class,()->stored.cleanup().run());
            assertNull(failure.getCause());assertFalse(failure.getMessage().contains(KEY));
        }
        connector.deleteStatus=200;
        for(String malformed:List.of("","{}","null","not-json","[] []","[{\"name\":\"other.pdf\"}]",
                "[{},{}]","[{\"name\":false}]")) {
            connector.deleteBody=malformed;assertThrows(IOException.class,()->stored.cleanup().run());
        }
        String key=SupabaseDocumentReference.parse(stored.reference()).key();
        connector.deleteBody="[{\"name\":\""+key+"\",\"bucket_id\":\"other-bucket\"}]";
        assertThrows(IOException.class,()->stored.cleanup().run());
        connector.deleteBody="[{\"name\":\""+key+"\",\"bucket_id\":\"academic-documents\"}]";
        assertDoesNotThrow(()->stored.cleanup().run());
        connector.deleteBody="[]";assertDoesNotThrow(()->stored.cleanup().run());
    }
    @Test void deeplyNestedAndOverlongMetadataFailsBeforeUploadWithoutProviderDetail() {
        for(String metadata:List.of(
                "{\"id\":\"academic-documents\",\"public\":false,\"nested\":"+"[".repeat(17)+"0"+"]".repeat(17)+"}",
                "{\"id\":\"academic-documents\",\"public\":false,\"text\":\""+"x".repeat(3000)+"\"}",
                "{\"id\":\"academic-documents\",\"public\":false,\"number\":"+"1".repeat(65)+"}",
                "{\"id\":\"academic-documents\",\"public\":false,\""+"x".repeat(129)+"\":0}")) {
            var connector=new FakeConnector();connector.metadata=metadata;
            var failure=assertThrows(IOException.class,()->storage(connector).store(PDF,"pdf"));
            assertNull(failure.getCause());assertEquals(1,connector.requests.size());
        }
    }
    private String requestBody(HttpRequest request) throws Exception {
        var body=HttpResponse.BodySubscribers.ofByteArray();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { body.onSubscribe(subscription); }
            @Override public void onNext(ByteBuffer bytes) { body.onNext(List.of(bytes)); }
            @Override public void onError(Throwable failure) { body.onError(failure); }
            @Override public void onComplete() { body.onComplete(); }
        });
        return new String(body.getBody().toCompletableFuture().get(1,TimeUnit.SECONDS),StandardCharsets.UTF_8);
    }
    @Test void serverFailureAttemptsFreshCleanupButConflictsAndRedirectsNeverDeleteSharedObjects() {
        for(int status:List.of(400,401,403,409,302,503)) {
            var connector=new FakeConnector();connector.uploadStatus=status;
            assertThrows(IOException.class,()->storage(connector).store(PDF,"pdf"));
            assertEquals(status>=500 ? 3:2,connector.requests.size());
            assertEquals(status>=500,connector.requests.stream().anyMatch(request->request.method().equals("DELETE")));
        }
    }
    @Test void malformedOrOversizedInputRejectedWithoutProviderCalls() {
        var connector=new FakeConnector();var backend=storage(connector);
        assertThrows(IOException.class,()->backend.store(new byte[0],"pdf"));
        assertThrows(IOException.class,()->backend.store(null,"pdf"));
        assertThrows(IOException.class,()->backend.store(PDF,null));
        assertThrows(IOException.class,()->backend.store(new byte[VisaDocumentStorage.MAX_DOCUMENT_BYTES+1],"pdf"));
        assertThrows(IOException.class,()->backend.store(PDF,"../pdf"));assertTrue(connector.requests.isEmpty());
    }
    @Test void positiveHealthExpiresAfterThirtySecondsEvenAcrossMonotonicCounterWrap() {
        var connector=new FakeConnector();var now=new AtomicLong(Long.MAX_VALUE-TimeUnit.SECONDS.toNanos(15));
        var backend=storage(connector,now::get);
        assertTrue(backend.healthy());assertEquals(-1,backend.availableBytes());
        connector.metadata="{\"id\":\"academic-documents\",\"public\":true}";
        now.addAndGet(TimeUnit.SECONDS.toNanos(30)-1);
        assertTrue(backend.healthy());assertEquals(1,connector.requests.size());
        now.incrementAndGet();
        assertFalse(backend.healthy());assertEquals(2,connector.requests.size());
    }
    @Test void negativeHealthAlsoExpiresAfterThirtySeconds() {
        var connector=new FakeConnector();connector.metadata="{\"id\":\"academic-documents\",\"public\":true}";
        var now=new AtomicLong(-TimeUnit.SECONDS.toNanos(100));var backend=storage(connector,now::get);
        assertFalse(backend.healthy());
        connector.metadata="{\"id\":\"academic-documents\",\"public\":false}";
        now.addAndGet(TimeUnit.SECONDS.toNanos(30)-1);
        assertFalse(backend.healthy());assertEquals(1,connector.requests.size());
        now.incrementAndGet();
        assertTrue(backend.healthy());assertEquals(2,connector.requests.size());
    }
    @Test void initialHealthProbeDoesNotBlockConcurrentHealthCallers() throws Exception {
        var connector=new FakeConnector();var probes=new AtomicInteger();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var backend=storage((request,limit)->{
            probes.incrementAndGet();entered.countDown();awaitProbeRelease(release);
            return connector.execute(request,limit);
        },new AtomicLong()::get);
        var executor=Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first=executor.submit(backend::healthy);
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<Boolean> concurrent=executor.submit(backend::healthy);
            assertFalse(concurrent.get(2,TimeUnit.SECONDS));
            assertEquals(1,probes.get());assertFalse(first.isDone());
            release.countDown();assertTrue(first.get(2,TimeUnit.SECONDS));
            assertTrue(backend.healthy());assertEquals(1,probes.get());
        } finally { release.countDown();executor.shutdownNow(); }
    }
    @Test void expiredHealthHasOneProbeWhileConcurrentCallersImmediatelyReceiveLastResult() throws Exception {
        for(boolean initialHealth:new boolean[]{true,false}) {
            var connector=new FakeConnector();
            connector.metadata="{\"id\":\"academic-documents\",\"public\":"+!initialHealth+"}";
            var probes=new AtomicInteger();var now=new AtomicLong();
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var backend=storage((request,limit)->{
                if (probes.incrementAndGet() == 2) { entered.countDown();awaitProbeRelease(release); }
                return connector.execute(request,limit);
            },now::get);
            assertEquals(initialHealth,backend.healthy());
            connector.metadata="{\"id\":\"academic-documents\",\"public\":"+initialHealth+"}";
            now.addAndGet(TimeUnit.SECONDS.toNanos(30));
            var executor=Executors.newFixedThreadPool(9);
            try {
                Future<Boolean> refresh=executor.submit(backend::healthy);
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                List<Future<Boolean>> concurrent=new ArrayList<>();
                for(int caller=0;caller<8;caller++) concurrent.add(executor.submit(backend::healthy));
                for(Future<Boolean> caller:concurrent)
                    assertEquals(initialHealth,caller.get(2,TimeUnit.SECONDS).booleanValue());
                assertEquals(2,probes.get());assertFalse(refresh.isDone());
                release.countDown();assertEquals(!initialHealth,refresh.get(2,TimeUnit.SECONDS).booleanValue());
                assertEquals(!initialHealth,backend.healthy());assertEquals(2,probes.get());
            } finally { release.countDown();executor.shutdownNow(); }
        }
    }
    private static void awaitProbeRelease(CountDownLatch release) throws IOException {
        try {
            if (!release.await(10,TimeUnit.SECONDS)) throw new IOException("Synthetic health probe timed out.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IOException("Synthetic health probe interrupted.");
        }
    }
}
