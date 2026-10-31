package com.travelgo.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic bytes, protected temporary fixtures and fake child processes only; never starts Defender. */
class WindowsDefenderDocumentScannerTests {
    @TempDir Path directory;

    @BeforeEach void protectSyntheticScratch() throws Exception {
        var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        if (acl != null) {
            var lookup = directory.getFileSystem().getUserPrincipalLookupService();
            var entries = new java.util.ArrayList<AclEntry>();
            for (String name : List.of(System.getProperty("user.name"), "NT AUTHORITY\\SYSTEM", "BUILTIN\\Administrators"))
                entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(lookup.lookupPrincipalByName(name))
                        .setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class)).build());
            acl.setAcl(entries);
        } else {
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test void cleanScanUsesOnlyProtectedRandomScratchAndRemovesItBeforeReturning() throws Exception {
        byte[] document = "synthetic-document".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var selected = new AtomicReference<Path>();
        var scanner = new WindowsDefenderDocumentScanner(directory, 100, 2000, (file, timeout) -> {
            selected.set(file);
            assertEquals(2000, timeout);
            assertTrue(file.startsWith(directory));
            assertEquals("document.bin", file.getFileName().toString());
            assertTrue(file.getParent().getFileName().toString().startsWith("travelgo-scan-"));
            assertArrayEquals(document, Files.readAllBytes(file));
            assertPrivate(file); assertPrivate(file.getParent());
            return 0;
        });
        scanner.scan(document);
        assertNotNull(selected.get());
        assertFalse(Files.exists(selected.get()));
        try (var children = Files.list(directory)) { assertEquals(0, children.count()); }
    }

    @ParameterizedTest @ValueSource(ints={1, 2, 3, -1, Integer.MAX_VALUE})
    void detectionAndEveryNonzeroResultFailClosedAndRemoveScratch(int exit) throws Exception {
        var scanner = new WindowsDefenderDocumentScanner(directory, 100, 2000, (file, timeout) -> exit);
        IOException failure = assertThrows(IOException.class, () -> scanner.scan(new byte[]{1}));
        assertNull(failure.getCause());
        try (var children = Files.list(directory)) { assertEquals(0, children.count()); }
    }

    @Test void processErrorsNeverDiscloseScannerDiagnosticsAndStillRemoveScratch() throws Exception {
        var scanner = new WindowsDefenderDocumentScanner(directory, 100, 2000, (file, timeout) -> {
            throw new IOException("private-file customer-name signature-result");
        });
        IOException failure = assertThrows(IOException.class, () -> scanner.scan(new byte[]{1}));
        assertNull(failure.getCause()); assertFalse(failure.toString().contains("private-file"));
        assertFalse(failure.toString().contains("signature-result"));
        try (var children = Files.list(directory)) { assertEquals(0, children.count()); }
    }

    @Test void emptyAndOversizedFilesNeverCreateScratchOrInvokeTheProcess() throws Exception {
        var called = new AtomicBoolean();
        var scanner = new WindowsDefenderDocumentScanner(directory, 3, 2000, (file, timeout) -> { called.set(true); return 0; });
        for (byte[] content : Arrays.asList(null, new byte[0], new byte[4]))
            assertThrows(IOException.class, () -> scanner.scan(content));
        assertFalse(called.get());
        try (var children = Files.list(directory)) { assertEquals(0, children.count()); }
    }

    @Test void cleanupFailureCannotProduceACleanResultAndDoesNotRecursivelyDeleteOtherFiles() throws Exception {
        var extra = new AtomicReference<Path>();
        var scanner = new WindowsDefenderDocumentScanner(directory, 100, 2000, (file, timeout) -> {
            extra.set(Files.write(file.getParent().resolve("unrelated-synthetic.bin"), new byte[]{9}));
            return 0;
        });
        assertThrows(IOException.class, () -> scanner.scan(new byte[]{1}));
        assertTrue(Files.exists(extra.get()));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(extra.get()));
    }

    @Test void twoScanCapacityFailsClosedBeforeCreatingAnotherFile() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var scanner = new WindowsDefenderDocumentScanner(directory, 100, 2000, (file, timeout) -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("synthetic timeout"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("synthetic interruption"); }
            return 0;
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { scanner.scan(new byte[]{1}); return null; });
            var second = pool.submit(() -> { scanner.scan(new byte[]{2}); return null; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> scanner.scan(new byte[]{3}));
            try (var children = Files.list(directory)) { assertEquals(2, children.count()); }
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
        scanner.scan(new byte[]{4});
        try (var children = Files.list(directory)) { assertEquals(0, children.count()); }
    }

    @Test void commandUsesTheTrustedExeAnArgumentVectorNoRemediationAndNoApplicationEnvironment() throws Exception {
        var child = new FakeProcess(0, true, false);
        Path file = directory.resolve("synthetic scan.bin");
        var runner = new WindowsDefenderDocumentScanner.DefenderProcessRunner(builder -> {
            assertEquals(List.of(Path.of("C:/Program Files/Windows Defender/MpCmdRun.exe").toString(),
                    "-Scan", "-ScanType", "3", "-File", file.toString(), "-DisableRemediation"), builder.command());
            assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectOutput());
            assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError());
            var operatingSystemNames = java.util.Set.of("SystemRoot", "WINDIR", "SystemDrive", "ProgramData",
                    "ProgramFiles", "ProgramFiles(x86)", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA",
                    "HOMEDRIVE", "HOMEPATH", "USERNAME", "USERDOMAIN");
            assertTrue(builder.environment().keySet().stream().allMatch(operatingSystemNames::contains));
            assertFalse(builder.environment().keySet().stream().anyMatch(name -> name.startsWith("TRAVELGO_")
                    || name.startsWith("SPRING_") || name.equals("JAVA_TOOL_OPTIONS") || name.equals("CLASSPATH")));
            builder.environment().forEach((name, value) -> assertEquals(System.getenv(name), value));
            return child;
        });
        assertEquals(0, runner.scan(file, 1500));
        assertEquals(1500, child.lastTimeoutMillis); assertFalse(child.destroyed);
    }

    @Test void timedOutChildIsTerminatedAndCannotBeTreatedAsClean() {
        var child = new FakeProcess(0, false, false);
        var runner = new WindowsDefenderDocumentScanner.DefenderProcessRunner(builder -> child);
        assertThrows(IOException.class, () -> runner.scan(directory.resolve("synthetic.bin"), 1500));
        assertTrue(child.destroyed); assertFalse(child.isAlive());
        assertEquals(2000, child.lastTimeoutMillis);
    }

    @Test void interruptionStopsTheOwnedChildAndPreservesTheThreadInterrupt() {
        var child = new FakeProcess(0, true, true);
        var runner = new WindowsDefenderDocumentScanner.DefenderProcessRunner(builder -> child);
        try {
            assertThrows(IOException.class, () -> runner.scan(directory.resolve("synthetic.bin"), 1500));
            assertTrue(child.destroyed); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void configurationCannotUseRelativeMissingDirectoriesOrUnboundedWork() {
        assertThrows(IllegalArgumentException.class, () -> new WindowsDefenderDocumentScanner(Path.of("relative"), 100, 1000, (f,t) -> 0));
        assertThrows(IllegalArgumentException.class, () -> new WindowsDefenderDocumentScanner(directory.resolve("missing"), 100, 1000, (f,t) -> 0));
        assertThrows(IllegalArgumentException.class, () -> new WindowsDefenderDocumentScanner(directory, 10485761, 1000, (f,t) -> 0));
        assertThrows(IllegalArgumentException.class, () -> new WindowsDefenderDocumentScanner(directory, 100, 60001, (f,t) -> 0));
    }

    @Test void aBroadReadGrantRejectsTheRootBeforeAnyDocumentOrProcessExists() throws Exception {
        Path readable = Files.createDirectory(directory.resolve("broad-read-fixture"));
        var acl = Files.getFileAttributeView(readable, AclFileAttributeView.class);
        if (acl != null) {
            var entries = new java.util.ArrayList<>(acl.getAcl());
            var users = readable.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("BUILTIN\\Users");
            entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(users)
                    .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_NAMED_ATTRS).build());
            acl.setAcl(entries);
        } else {
            var permissions = new java.util.HashSet<>(Files.getPosixFilePermissions(readable));
            permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_READ);
            Files.setPosixFilePermissions(readable, permissions);
        }
        var called = new AtomicBoolean();
        assertThrows(IllegalArgumentException.class, () -> new WindowsDefenderDocumentScanner(readable, 100, 2000,
                (file, timeout) -> { called.set(true); return 0; }));
        assertFalse(called.get());
        try (var files = Files.list(readable)) { assertEquals(0, files.count()); }
    }

    @Test void backendSelectionNeverChangesTheProductionClamdRequirement() {
        assertDoesNotThrow(() -> VisaDocumentScanner.validateBackend("clamd", true));
        assertDoesNotThrow(() -> VisaDocumentScanner.validateBackend("windows-defender", false));
        assertThrows(IllegalArgumentException.class, () -> VisaDocumentScanner.validateBackend("windows-defender", true));
        assertThrows(IllegalArgumentException.class, () -> VisaDocumentScanner.validateBackend("unknown", false));
    }

    private void assertPrivate(Path path) throws IOException {
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            var owner = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            var system = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("NT AUTHORITY\\SYSTEM");
            assertTrue(acl.getAcl().stream().filter(entry -> entry.type() == AclEntryType.ALLOW)
                    .allMatch(entry -> entry.principal().equals(owner) || entry.principal().equals(system)));
        } else assertTrue(Files.getPosixFilePermissions(path).stream()
                .noneMatch(permission -> permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")));
    }

    private static final class FakeProcess extends Process {
        final int exit; final boolean completes; final boolean interrupts;
        boolean alive = true; boolean destroyed; long lastTimeoutMillis;
        FakeProcess(int exit, boolean completes, boolean interrupts) { this.exit=exit; this.completes=completes; this.interrupts=interrupts; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { alive=false; return exit; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            lastTimeoutMillis=unit.toMillis(timeout);
            if (interrupts) throw new InterruptedException();
            if (destroyed || completes) { alive=false; return true; }
            return false;
        }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return exit; }
        @Override public void destroy() { destroyed=true; alive=false; }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public boolean isAlive() { return alive; }
    }
}
