package com.travelgo.service;

import static com.travelgo.service.VisaDocumentStorage.unavailable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Optional private Supabase storage; credentials and provider responses never cross the server boundary. */
public final class SupabaseVisaDocumentStorage implements VisaDocumentStorage {
    private static final int METADATA_LIMIT=16*1024;
    private static final long HEALTH_CACHE_TTL_NANOS=Duration.ofSeconds(30).toNanos();
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(2048).maxNameLength(128)
                    .maxNumberLength(64).maxDocumentLength(METADATA_LIMIT).build()).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI origin;
    private final String bucket, serviceKey;
    private final Duration timeout;
    private final FilesystemVisaDocumentStorage filesystem;
    private final Connector connector;
    private final LongSupplier nanoTime;
    private final AtomicBoolean healthProbeInFlight=new AtomicBoolean();
    private volatile HealthCache healthCache;

    public SupabaseVisaDocumentStorage(String projectUrl,String bucket,String serviceKey,
            int connectTimeoutMs,int requestTimeoutMs,FilesystemVisaDocumentStorage filesystem) {
        this(projectUrl,bucket,serviceKey,connectTimeoutMs,requestTimeoutMs,filesystem,null);
    }

    SupabaseVisaDocumentStorage(String projectUrl,String bucket,String serviceKey,
            int connectTimeoutMs,int requestTimeoutMs,FilesystemVisaDocumentStorage filesystem,Connector connector) {
        this(projectUrl,bucket,serviceKey,connectTimeoutMs,requestTimeoutMs,filesystem,connector,System::nanoTime);
    }

    SupabaseVisaDocumentStorage(String projectUrl,String bucket,String serviceKey,
            int connectTimeoutMs,int requestTimeoutMs,FilesystemVisaDocumentStorage filesystem,Connector connector,
            LongSupplier nanoTime) {
        validateConfiguration(projectUrl,bucket,serviceKey,connectTimeoutMs,requestTimeoutMs);
        this.origin=URI.create(projectUrl); this.bucket=bucket; this.serviceKey=serviceKey;
        this.timeout=Duration.ofMillis(requestTimeoutMs); this.filesystem=filesystem;
        this.nanoTime=Objects.requireNonNull(nanoTime);
        this.connector=connector == null ? new BoundedStorageHttpConnector(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs)).followRedirects(HttpClient.Redirect.NEVER).build()) : connector;
    }

    /** Syntax-only validation is safe before any database/provider access. It never checks a credential online. */
    public static void validateConfiguration(String projectUrl,String bucket,String serviceKey,
            int connectTimeoutMs,int requestTimeoutMs) {
        try {
            URI uri=URI.create(projectUrl);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || !uri.getHost().matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.supabase\\.co")
                    || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                    || bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{0,62}")
                    || connectTimeoutMs < 500 || connectTimeoutMs > 10000
                    || requestTimeoutMs < 1000 || requestTimeoutMs > 30000 || requestTimeoutMs < connectTimeoutMs
                    || !isServiceKey(serviceKey)) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Private document storage configuration is invalid."); }
    }

    private static boolean isServiceKey(String key) {
        if (key == null || key.length() > 8192) return false;
        if (key.matches("sb_secret_[A-Za-z0-9_-]{20,}")) return true;
        if (!key.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) return false;
        try {
            byte[] payload=Base64.getUrlDecoder().decode(key.split("\\.")[1]);
            JsonNode claims=JSON.readTree(payload);
            return claims != null && claims.isObject() && "service_role".equals(claims.path("role").asText());
        } catch (IOException | RuntimeException invalid) { return false; }
    }

    private HttpRequest.Builder request(String path) {
        var builder=HttpRequest.newBuilder(origin.resolve("/storage/v1/"+path)).timeout(timeout)
                .header("apikey",serviceKey)
                .header("Accept-Encoding","identity");
        // Current secret keys are not JWTs. Supabase accepts them on apikey;
        // legacy service_role JWTs also authenticate the private download via Bearer.
        if (!serviceKey.startsWith("sb_secret_")) builder.header("Authorization","Bearer "+serviceKey);
        return builder;
    }

    private void requirePrivateBucket() throws IOException {
        try {
            Response response=connector.execute(request("bucket/"+bucket).GET().build(),METADATA_LIMIT);
            if (response.status() != 200) throw unavailable();
            JsonNode metadata=JSON.readTree(response.body());
            if (metadata == null || !metadata.isObject() || !metadata.path("id").isTextual()
                    || !bucket.equals(metadata.path("id").textValue())
                    || !metadata.path("public").isBoolean() || metadata.path("public").booleanValue()) throw unavailable();
        } catch (IOException | RuntimeException failure) { throw unavailable(); }
    }

    @Override public StoredDocument store(byte[] content,String extension) throws IOException {
        if (content == null || content.length < 1 || content.length > MAX_DOCUMENT_BYTES
                || extension == null || !Set.of("pdf","png","jpg").contains(extension)) throw unavailable();
        requirePrivateBucket();
        var reference=SupabaseDocumentReference.fresh(content,extension);
        String contentType=switch(extension) { case "pdf" -> "application/pdf"; case "png" -> "image/png"; default -> "image/jpeg"; };
        Response response;
        try {
            response=connector.execute(request("object/"+bucket+"/"+reference.key())
                    .header("x-upsert","false").header("Content-Type",contentType).header("Cache-Control","no-store")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(content)).build(),METADATA_LIMIT);
        } catch (IOException | RuntimeException ambiguous) {
            // Only this unpredictable, freshly generated object key can be cleaned up after an uncertain acceptance.
            cleanAmbiguous(reference);
            throw unavailable();
        }
        if (response.status() != 200 && response.status() != 201) {
            // 4xx/redirect responses did not accept an exclusive creation. In particular, never delete a conflict.
            if (response.status() >= 500) cleanAmbiguous(reference);
            throw unavailable();
        }
        return new StoredDocument(reference.encode(),() -> deleteFresh(reference));
    }

    private void cleanAmbiguous(SupabaseDocumentReference reference) {
        try { deleteFresh(reference); }
        catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger(SupabaseVisaDocumentStorage.class)
                    .error("Private document cleanup needs operator review.");
        }
    }

    private void deleteFresh(SupabaseDocumentReference reference) throws IOException {
        try {
            // Match storage-js remove(paths): even ambiguous acceptance can only
            // delete the one fresh server-generated key, never an arbitrary prefix.
            byte[] body=JSON.writeValueAsBytes(Map.of("prefixes",List.of(reference.key())));
            Response response=connector.execute(request("object/"+bucket).header("Content-Type","application/json")
                    .method("DELETE",HttpRequest.BodyPublishers.ofByteArray(body)).build(),METADATA_LIMIT);
            if (response.status() != 200) throw unavailable();
            JsonNode deleted=JSON.readTree(response.body());
            // The documented response is an array. [] covers an already absent
            // key; a routing/authentication 404 is never treated as confirmation.
            if (deleted == null || !deleted.isArray() || deleted.size() > 1) throw unavailable();
            if (!deleted.isEmpty()) {
                JsonNode object=deleted.get(0);
                if (!object.isObject() || !object.path("name").isTextual() || !reference.key().equals(object.path("name").textValue())
                        || (object.has("bucket_id") && (!object.path("bucket_id").isTextual()
                            || !bucket.equals(object.path("bucket_id").textValue())))) throw unavailable();
            }
        } catch (IOException | RuntimeException failure) { throw unavailable(); }
    }

    @Override public Content read(String value) throws IOException {
        if (!SupabaseDocumentReference.isRemote(value)) return filesystem.read(value);
        var reference=SupabaseDocumentReference.parse(value);
        requirePrivateBucket();
        try {
            Response response=connector.execute(request("object/authenticated/"+bucket+"/"+reference.key()).GET().build(),(int)reference.length());
            if (response.status() != 200 || !reference.matches(response.body())) throw unavailable();
            return new Content(response.body(),reference.extension());
        } catch (IOException | RuntimeException failure) { throw unavailable(); }
    }

    @Override public long retainedSize(String value) throws IOException {
        // The database reference was generated after validated upload admission; no remote HEAD under account locks.
        return SupabaseDocumentReference.isRemote(value) ? SupabaseDocumentReference.parse(value).length() : filesystem.retainedSize(value);
    }

    @Override public boolean healthy() {
        HealthCache cached=healthCache;
        if (isHealthCacheFresh(cached)) return cached.healthy();
        // Only the caller that acquires ownership waits for the provider. Other
        // health requests immediately receive the last result (initially false).
        if (!healthProbeInFlight.compareAndSet(false,true)) {
            cached=healthCache;
            return cached != null && cached.healthy();
        }
        try {
            // A preceding probe may have completed between our read and claim.
            cached=healthCache;
            if (isHealthCacheFresh(cached)) return cached.healthy();
            boolean healthy;
            try { requirePrivateBucket(); healthy=true; }
            catch (IOException | RuntimeException failure) { healthy=false; }
            healthCache=new HealthCache(healthy,nanoTime.getAsLong());
            return healthy;
        } finally { healthProbeInFlight.set(false); }
    }

    private boolean isHealthCacheFresh(HealthCache cached) {
        // Subtraction remains correct if the monotonic nanosecond counter wraps.
        return cached != null && nanoTime.getAsLong()-cached.checkedAtNanos() < HEALTH_CACHE_TTL_NANOS;
    }

    private record HealthCache(boolean healthy,long checkedAtNanos) {}
    @Override public long availableBytes() { return -1; }

    @FunctionalInterface interface Connector {
        Response execute(HttpRequest request,int responseLimit) throws IOException;
    }
    record Response(int status,byte[] body) {}
}
