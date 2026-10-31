package com.travelgo.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Pure in-memory protocol tests: never creates a socket, daemon, file, Spring context or database. */
class VisaDocumentScannerTests {
    private static VisaDocumentScanner.Configuration configuration(boolean enabled) {
        return new VisaDocumentScanner.Configuration(enabled, "127.0.0.1", 3310, 2000, 15000, 10L * 1024 * 1024);
    }

    @Test void sendsNulCommandBigEndianChunksExactContentAndZeroTerminator() throws Exception {
        byte[] document = new byte[32 * 1024 + 7];
        for (int i = 0; i < document.length; i++) document[i] = (byte)(i % 251);
        MemoryTransport transport = new MemoryTransport("stream: OK\0");
        var scanner = new VisaDocumentScanner(configuration(true), ignored -> transport);
        scanner.scan(document);
        var wire = new DataInputStream(new ByteArrayInputStream(transport.written.toByteArray()));
        assertArrayEquals("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII), wire.readNBytes(10));
        var received = new ByteArrayOutputStream();
        int length; int chunks = 0;
        while ((length = wire.readInt()) != 0) {
            assertTrue(length > 0 && length <= 16 * 1024);
            byte[] chunk = wire.readNBytes(length); assertEquals(length, chunk.length);
            received.write(chunk); chunks++;
        }
        assertEquals(3, chunks); assertEquals(-1, wire.read());
        assertArrayEquals(document, received.toByteArray()); assertTrue(transport.closed);
    }

    @Test void configuredLocalDisableDoesNotConnectOrInspectDocument() {
        var connected = new AtomicBoolean();
        var scanner = new VisaDocumentScanner(configuration(false), ignored -> { connected.set(true); throw new IOException("unexpected"); });
        assertDoesNotThrow(() -> scanner.scan(null)); assertFalse(connected.get());
    }

    @ParameterizedTest @ValueSource(strings={"", "stream: OK", "stream: OK\n", "stream: Eicar-Signature FOUND\0",
            "stream: private-document-path ERROR\0", "INSTREAM size limit exceeded. ERROR\0", "UNKNOWN COMMAND\0",
            "stream: OK\0stream: FOUND\0", "stream: OK\0\0", " stream: OK\0", "stream: OK \0", "stream: ok\0"})
    void onlyExactCleanTerminatedReplyIsAccepted(String reply) {
        var transport = new MemoryTransport(reply);
        var scanner = new VisaDocumentScanner(configuration(true), ignored -> transport);
        IOException failure = assertThrows(IOException.class, () -> scanner.scan(new byte[]{1, 2, 3}));
        assertNull(failure.getCause()); assertFalse(failure.toString().contains("private-document-path"));
        assertFalse(failure.toString().contains("Eicar")); assertTrue(transport.closed);
    }

    @Test void oversizedReplyFailsClosedAndClosesTransport() {
        var transport = new MemoryTransport("x".repeat(1025) + "\0");
        var scanner = new VisaDocumentScanner(configuration(true), ignored -> transport);
        assertThrows(IOException.class, () -> scanner.scan(new byte[]{1})); assertTrue(transport.closed);
        assertTrue(transport.response.available() > 0);
    }

    @Test void emptyNullAndOversizedContentNeverConnect() {
        var connected = new AtomicBoolean();
        var limited = new VisaDocumentScanner.Configuration(true, "127.0.0.1", 3310, 2000, 15000, 3);
        var scanner = new VisaDocumentScanner(limited, ignored -> { connected.set(true); return new MemoryTransport("stream: OK\0"); });
        for (byte[] data : Arrays.asList(null, new byte[0], new byte[4])) assertThrows(IOException.class, () -> scanner.scan(data));
        assertFalse(connected.get());
    }

    @Test void contentAtConfiguredLimitIsScanned() {
        var limited = new VisaDocumentScanner.Configuration(true, "127.0.0.1", 3310, 2000, 15000, 3);
        assertDoesNotThrow(() -> new VisaDocumentScanner(limited, ignored -> new MemoryTransport("stream: OK\0")).scan(new byte[3]));
    }

    @Test void connectionAndTimeoutFailuresUseFixedMessageWithoutCause() {
        for (IOException original : new IOException[]{new IOException("private-connection-address"), new SocketTimeoutException("private-timeout-value")}) {
            var scanner = new VisaDocumentScanner(configuration(true), ignored -> { throw original; });
            IOException failure = assertThrows(IOException.class, () -> scanner.scan(new byte[]{1}));
            assertNull(failure.getCause()); assertFalse(failure.toString().contains("private-"));
        }
    }

    @Test void writeReadAndCloseFailuresCloseConnectionAndNeverExposeDaemonData() {
        for (String stage : new String[]{"write", "read", "close", "runtime"}) {
            var transport = new MemoryTransport("stream: OK\0") {
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (stage.equals("write")) throw new IOException("private-write-value");
                    if (stage.equals("runtime")) throw new IllegalStateException("private-runtime-value");
                    super.write(bytes, offset, length);
                }
                @Override public int read() throws IOException {
                    if (stage.equals("read")) throw new SocketTimeoutException("private-read-value");
                    return super.read();
                }
                @Override public void close() throws IOException {
                    super.close(); if (stage.equals("close")) throw new IOException("private-close-value");
                }
            };
            var scanner = new VisaDocumentScanner(configuration(true), ignored -> transport);
            IOException failure = assertThrows(IOException.class, () -> scanner.scan(new byte[]{1}));
            assertTrue(transport.closed); assertNull(failure.getCause()); assertFalse(failure.toString().contains("private-"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"localhost", "127.0.0.2", "127.1", "2130706433", "[::1]", "::ffff:127.0.0.1",
            "192.168.1.2", "scanner.private-value.invalid", "127.0.0.1 ", "https://127.0.0.1"})
    void scannerCannotResolveDnsOrSendToExternalOrAmbiguousHosts(String host) {
        Exception failure = assertThrows(IllegalArgumentException.class,
                () -> VisaDocumentScanner.validateConfiguration(host, 3310, 2000, 15000, 10L * 1024 * 1024));
        assertFalse(failure.toString().contains("private-value")); assertNull(failure.getCause());
    }

    @Test void literalIpv4AndIpv6LoopbackConfigurationNeedsNoConnection() {
        for (String host : new String[]{"127.0.0.1", "::1"})
            assertDoesNotThrow(() -> VisaDocumentScanner.validateConfiguration(host, 3310, 2000, 15000, 10485760));
    }

    @Test void configurationBoundsCannotDisableDeadlinesOrEnlargeFileLimit() {
        for (int port : new int[]{0, -1, 65536}) assertThrows(IllegalArgumentException.class,
                () -> VisaDocumentScanner.validateConfiguration("127.0.0.1", port, 2000, 15000, 10485760));
        for (int timeout : new int[]{0, -1, 99, 10001}) assertThrows(IllegalArgumentException.class,
                () -> VisaDocumentScanner.validateConfiguration("127.0.0.1", 3310, timeout, 15000, 10485760));
        for (int timeout : new int[]{0, -1, 99, 60001}) assertThrows(IllegalArgumentException.class,
                () -> VisaDocumentScanner.validateConfiguration("127.0.0.1", 3310, 2000, timeout, 10485760));
        for (long size : new long[]{0, -1, 10485761L}) assertThrows(IllegalArgumentException.class,
                () -> VisaDocumentScanner.validateConfiguration("127.0.0.1", 3310, 2000, 15000, size));
    }

    private static class MemoryTransport implements VisaDocumentScanner.Transport {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final ByteArrayInputStream response;
        boolean closed;
        MemoryTransport(String reply) { response = new ByteArrayInputStream(reply.getBytes(StandardCharsets.US_ASCII)); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { written.write(bytes, offset, length); }
        @Override public int read() throws IOException { return response.read(); }
        @Override public void close() throws IOException { closed = true; }
    }
}
