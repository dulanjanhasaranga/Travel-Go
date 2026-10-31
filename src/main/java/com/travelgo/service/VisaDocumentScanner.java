package com.travelgo.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Selects a reviewed scanner; bounded bytes are scanned without sending a stored path. */
@Component
public class VisaDocumentScanner {
    private static final byte[] COMMAND = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CLEAN_REPLY = "stream: OK\0".getBytes(StandardCharsets.US_ASCII);
    private static final int CHUNK_BYTES = 16 * 1024;
    private static final int MAX_REPLY_BYTES = 1024;
    private static final String SCAN_FAILURE = "This document could not pass the security check. Please try again later or contact your visa officer.";
    private final Configuration configuration;
    private final Connector connector;
    private final WindowsDefenderDocumentScanner windowsDefender;
    private final CloudmersiveDocumentScanner cloudmersive;

    @Autowired
    public VisaDocumentScanner(
            @Value("${travelgo.documents.scan.enabled:false}") boolean enabled,
            @Value("${travelgo.documents.scan.host:127.0.0.1}") String host,
            @Value("${travelgo.documents.scan.port:3310}") int port,
            @Value("${travelgo.documents.scan.connect-timeout-ms:2000}") int connectTimeoutMillis,
            @Value("${travelgo.documents.scan.read-timeout-ms:15000}") int readTimeoutMillis,
            @Value("${travelgo.documents.scan.max-bytes:10485760}") long maxBytes,
            @Value("${travelgo.documents.scan.backend:clamd}") String backend,
            @Value("${travelgo.documents.scan.temp-directory:}") String temporaryDirectory,
            Environment environment) {
        this(new Configuration(enabled, host, port, connectTimeoutMillis, readTimeoutMillis, maxBytes), LocalTransport::connect,
                selectedBackend(enabled, backend, temporaryDirectory, maxBytes, readTimeoutMillis, environment));
    }

    VisaDocumentScanner(Configuration configuration, Connector connector) {
        this(configuration, connector, new Backends(null, null));
    }

    private VisaDocumentScanner(Configuration configuration, Connector connector, Backends backends) {
        validateConfiguration(configuration.host(), configuration.port(), configuration.connectTimeoutMillis(),
                configuration.readTimeoutMillis(), configuration.maxBytes());
        this.configuration = configuration;
        this.connector = Objects.requireNonNull(connector);
        this.windowsDefender = backends.windowsDefender();
        this.cloudmersive = backends.cloudmersive();
    }

    private static Backends selectedBackend(boolean enabled, String backend, String temporaryDirectory,
            long maxBytes, int timeoutMillis, Environment environment) {
        try {
            validateSelectedBackend(enabled, backend, environment);
            if ("cloudmersive".equals(backend)) {
                return new Backends(null, new CloudmersiveDocumentScanner(
                        environment.getProperty("travelgo.documents.cloudmersive.api-key", ""),
                        environment.getProperty("travelgo.documents.cloudmersive.connect-timeout-ms", Integer.class, 2000),
                        environment.getProperty("travelgo.documents.cloudmersive.request-timeout-ms", Integer.class, 15000), maxBytes));
            }
            if (!enabled || "clamd".equals(backend)) return new Backends(null, null);
            if (temporaryDirectory == null || temporaryDirectory.isBlank())
                throw new IllegalArgumentException("Windows document scanning requires its private temporary directory.");
            return new Backends(new WindowsDefenderDocumentScanner(java.nio.file.Path.of(temporaryDirectory), maxBytes, timeoutMillis), null);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Document scanner configuration is invalid.");
        }
    }

    private static void validateSelectedBackend(boolean enabled, String backend, Environment environment) {
        var profiles = Arrays.asList(environment.getActiveProfiles());
        if ("cloudmersive".equals(backend)) {
            if (!enabled || profiles.size() != 1 || !profiles.contains("cloud-evaluation"))
                throw new IllegalArgumentException("Cloud document scanning requires the guarded cloud-evaluation profile and enabled scanning.");
            return;
        }
        validateBackend(backend, profiles.contains("prod"));
    }

    /** Syntax-only early guard: no files, DNS, daemon, database or provider calls. */
    public static void validateEnvironment(Environment environment) {
        try {
            String backend = environment.getProperty("travelgo.documents.scan.backend", "clamd");
            boolean enabled = environment.getProperty("travelgo.documents.scan.enabled", Boolean.class, false);
            long maxBytes = environment.getProperty("travelgo.documents.scan.max-bytes", Long.class, 10485760L);
            validateSelectedBackend(enabled, backend, environment);
            validateConfiguration(environment.getProperty("travelgo.documents.scan.host", "127.0.0.1"),
                    environment.getProperty("travelgo.documents.scan.port", Integer.class, 3310),
                    environment.getProperty("travelgo.documents.scan.connect-timeout-ms", Integer.class, 2000),
                    environment.getProperty("travelgo.documents.scan.read-timeout-ms", Integer.class, 15000), maxBytes);
            if ("cloudmersive".equals(backend)) {
                CloudmersiveDocumentScanner.validateConfiguration(
                        environment.getProperty("travelgo.documents.cloudmersive.api-key", ""),
                        environment.getProperty("travelgo.documents.cloudmersive.connect-timeout-ms", Integer.class, 2000),
                        environment.getProperty("travelgo.documents.cloudmersive.request-timeout-ms", Integer.class, 15000), maxBytes);
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Document scanner configuration is invalid.");
        }
    }

    /** Production keeps its maintained ClamD/signature acceptance requirement. */
    public static void validateBackend(String backend, boolean production) {
        if (!("clamd".equals(backend) || "windows-defender".equals(backend))
                || (production && !"clamd".equals(backend)))
            throw new IllegalArgumentException("Choose a supported document scanner; production requires the reviewed ClamD backend.");
    }

    /** Configuration-only validation, also used by the early production guard. No DNS or connection. */
    public static void validateConfiguration(String host, int port, int connectTimeoutMillis,
            int readTimeoutMillis, long maxBytes) {
        if (!Set.of("127.0.0.1", "::1").contains(Objects.toString(host, ""))
                || port < 1 || port > 65535
                || connectTimeoutMillis < 100 || connectTimeoutMillis > 10000
                || readTimeoutMillis < 100 || readTimeoutMillis > 60000
                || maxBytes < 1 || maxBytes > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("Document scanner settings must use a supported loopback address and bounded port, timeouts and size.");
        }
    }

    /** Disabled only for reviewed local operation. Enabled checks fail closed with a fixed diagnostic. */
    public void scan(byte[] content) throws IOException {
        if (!configuration.enabled()) return;
        if (content == null || content.length == 0 || content.length > configuration.maxBytes())
            throw new IOException(SCAN_FAILURE);
        if (cloudmersive != null) { cloudmersive.scan(content); return; }
        if (windowsDefender != null) { windowsDefender.scan(content); return; }
        try (Transport transport = connector.connect(configuration)) {
            transport.write(COMMAND, 0, COMMAND.length);
            for (int offset = 0; offset < content.length; offset += CHUNK_BYTES) {
                int length = Math.min(CHUNK_BYTES, content.length - offset);
                byte[] size = ByteBuffer.allocate(Integer.BYTES).putInt(length).array();
                transport.write(size, 0, size.length);
                transport.write(content, offset, length);
            }
            byte[] end = new byte[Integer.BYTES];
            transport.write(end, 0, end.length);
            var reply = new ByteArrayOutputStream(CLEAN_REPLY.length);
            int next;
            while ((next = transport.read()) != -1) {
                if (reply.size() >= MAX_REPLY_BYTES) throw new IOException(SCAN_FAILURE);
                reply.write(next);
            }
            // INSTREAM is non-session: require exactly one NUL-terminated clean reply and EOF.
            if (!Arrays.equals(reply.toByteArray(), CLEAN_REPLY)) throw new IOException(SCAN_FAILURE);
        } catch (IOException | RuntimeException unavailable) {
            // Daemon messages may contain signatures or private paths. Never retain their text or cause.
            throw new IOException(SCAN_FAILURE);
        }
    }

    record Configuration(boolean enabled, String host, int port, int connectTimeoutMillis,
            int readTimeoutMillis, long maxBytes) {}

    private record Backends(WindowsDefenderDocumentScanner windowsDefender, CloudmersiveDocumentScanner cloudmersive) {}

    @FunctionalInterface interface Connector {
        Transport connect(Configuration configuration) throws IOException;
    }

    interface Transport extends AutoCloseable {
        void write(byte[] bytes, int offset, int length) throws IOException;
        int read() throws IOException;
        @Override void close() throws IOException;
    }

    /** Nonblocking I/O bounds stalled writes as well as trickled replies without creating worker threads. */
    private static final class LocalTransport implements Transport {
        private final SocketChannel channel;
        private final Selector selector;
        private final SelectionKey key;
        private final long exchangeDeadline;
        private final ByteBuffer singleByte = ByteBuffer.allocate(1);

        private LocalTransport(SocketChannel channel, Selector selector, SelectionKey key, long exchangeDeadline) {
            this.channel = channel;
            this.selector = selector;
            this.key = key;
            this.exchangeDeadline = exchangeDeadline;
        }

        static Transport connect(Configuration configuration) throws IOException {
            SocketChannel channel = SocketChannel.open();
            Selector selector = null;
            try {
                selector = Selector.open();
                channel.configureBlocking(false);
                SelectionKey key = channel.register(selector, 0);
                byte[] address = configuration.host().equals("::1") ? new byte[16] : new byte[]{127, 0, 0, 1};
                if (address.length == 16) address[15] = 1;
                var endpoint = new InetSocketAddress(InetAddress.getByAddress(address), configuration.port());
                long connectDeadline = deadline(configuration.connectTimeoutMillis());
                if (!channel.connect(endpoint)) {
                    while (!channel.finishConnect()) await(selector, key, SelectionKey.OP_CONNECT, connectDeadline);
                }
                return new LocalTransport(channel, selector, key, deadline(configuration.readTimeoutMillis()));
            } catch (IOException | RuntimeException failure) {
                try { channel.close(); } catch (IOException ignored) {}
                if (selector != null) try { selector.close(); } catch (IOException ignored) {}
                throw failure;
            }
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
            while (buffer.hasRemaining()) {
                remaining(exchangeDeadline);
                if (channel.write(buffer) == 0) await(selector, key, SelectionKey.OP_WRITE, exchangeDeadline);
            }
        }

        @Override public int read() throws IOException {
            singleByte.clear();
            while (true) {
                remaining(exchangeDeadline);
                int count = channel.read(singleByte);
                if (count < 0) return -1;
                if (count == 1) return Byte.toUnsignedInt(singleByte.get(0));
                await(selector, key, SelectionKey.OP_READ, exchangeDeadline);
            }
        }

        private static long deadline(int milliseconds) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds); }

        private static long remaining(long deadline) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Document scanner interrupted.");
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0) throw new SocketTimeoutException("Document scanner deadline exceeded.");
            return Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
        }

        private static void await(Selector selector, SelectionKey key, int operation, long deadline) throws IOException {
            key.interestOps(operation);
            while (true) {
                long milliseconds = remaining(deadline);
                int ready = selector.select(milliseconds);
                boolean expected = ready > 0 && key.isValid() && (key.readyOps() & operation) != 0;
                selector.selectedKeys().clear();
                if (expected) return;
            }
        }

        @Override public void close() throws IOException {
            try { channel.close(); } finally { selector.close(); }
        }
    }
}
