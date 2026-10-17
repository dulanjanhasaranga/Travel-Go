package com.travelgo.service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Explicit native-Windows local scanning; production continues to require the reviewed ClamD backend. */
public final class WindowsDefenderDocumentScanner {
    private static final String FAILURE = "This document could not pass the security check. Please try again later or contact your visa officer.";
    private static final Path EXECUTABLE = Path.of("C:/Program Files/Windows Defender/MpCmdRun.exe");
    private final Path temporaryRoot;
    private final long maxBytes;
    private final int timeoutMillis;
    private final ScanRunner runner;
    private final Semaphore admission = new Semaphore(2);

    public WindowsDefenderDocumentScanner(Path temporaryRoot, long maxBytes, int timeoutMillis) {
        this(temporaryRoot, maxBytes, timeoutMillis, new DefenderProcessRunner(ProcessBuilder::start));
        if (!System.getProperty("os.name", "").startsWith("Windows")
                || !Files.isRegularFile(EXECUTABLE, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(EXECUTABLE))
            throw new IllegalArgumentException("The reviewed Windows Defender command is unavailable.");
    }

    WindowsDefenderDocumentScanner(Path temporaryRoot, long maxBytes, int timeoutMillis, ScanRunner runner) {
        if (temporaryRoot == null || !temporaryRoot.isAbsolute()
                || !temporaryRoot.equals(temporaryRoot.normalize()) || temporaryRoot.getParent() == null
                || maxBytes < 1 || maxBytes > 10L * 1024 * 1024 || timeoutMillis < 100 || timeoutMillis > 60000)
            throw new IllegalArgumentException("Windows document scanning requires a private absolute temporary directory and bounded limits.");
        this.temporaryRoot = temporaryRoot;
        this.maxBytes = maxBytes;
        this.timeoutMillis = timeoutMillis;
        this.runner = java.util.Objects.requireNonNull(runner);
        try { validatePrivateRoot(); }
        catch (IOException | RuntimeException unavailable) {
            throw new IllegalArgumentException("Windows document scanning requires an existing private unlinked temporary directory.");
        }
    }

    public void scan(byte[] content) throws IOException {
        if (content == null || content.length == 0 || content.length > maxBytes || !admission.tryAcquire())
            throw new IOException(FAILURE);
        Path directory = null;
        Path file = null;
        boolean clean = false;
        try {
            validatePrivateRoot();
            directory = Files.createTempDirectory(temporaryRoot, "travelgo-scan-", privateAttribute(true));
            file = directory.resolve("document.bin");
            try (var output = Files.newByteChannel(file,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), privateAttribute(false))) {
                ByteBuffer bytes = ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) output.write(bytes);
            }
            // The fixed argument list contains only a random private path, never the user's filename.
            if (runner.scan(file, timeoutMillis) != 0) throw new IOException(FAILURE);
            clean = true;
        } catch (IOException | RuntimeException unavailable) {
            throw new IOException(FAILURE);
        } finally {
            boolean removed = true;
            try {
                if (file != null) Files.deleteIfExists(file);
                if (directory != null) Files.deleteIfExists(directory);
            } catch (IOException | RuntimeException cleanupFailure) { removed = false; }
            admission.release();
            // No recursive deletion: only the exact file and exclusively created random directory.
            // Cleanup failure must never turn an incomplete scan into a permitted upload/download.
            if (clean && !removed) throw new IOException(FAILURE);
        }
    }

    private void validatePrivateRoot() throws IOException {
        if (!Files.isDirectory(temporaryRoot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(temporaryRoot) || !Files.isWritable(temporaryRoot)) throw new IOException(FAILURE);
        for (Path ancestor = temporaryRoot; ancestor != null; ancestor = ancestor.getParent())
            if (Files.isSymbolicLink(ancestor) || !ancestor.toRealPath().equals(ancestor.toAbsolutePath().normalize()))
                throw new IOException(FAILURE);
        var acl = Files.getFileAttributeView(temporaryRoot, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal owner = currentUser();
            if (!Files.getOwner(temporaryRoot, LinkOption.NOFOLLOW_LINKS).equals(owner)) throw new IOException(FAILURE);
            UserPrincipal system = principal("NT AUTHORITY\\SYSTEM");
            UserPrincipal administrators = principal("BUILTIN\\Administrators");
            var accessing = EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.READ_NAMED_ATTRS,
                    AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                    AclEntryPermission.DELETE_CHILD, AclEntryPermission.DELETE, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);
            for (AclEntry entry : acl.getAcl())
                if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)
                        && !entry.principal().equals(system) && !entry.principal().equals(administrators)
                        && entry.permissions().stream().anyMatch(accessing::contains)) throw new IOException(FAILURE);
        } else if (Files.getFileStore(temporaryRoot).supportsFileAttributeView("posix")) {
            // Permits isolated file-lifecycle tests on other platforms; the real process backend is Windows-only.
            var permissions = Files.getPosixFilePermissions(temporaryRoot, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
                throw new IOException(FAILURE);
        } else throw new IOException(FAILURE);
    }

    private FileAttribute<?> privateAttribute(boolean directory) throws IOException {
        if (Files.getFileAttributeView(temporaryRoot, AclFileAttributeView.class) != null) {
            var owner = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(currentUser())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            var system = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal("NT AUTHORITY\\SYSTEM"))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) {
                owner.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
                system.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            }
            List<AclEntry> entries = List.of(owner.build(), system.build());
            return new FileAttribute<List<AclEntry>>() {
                @Override public String name() { return "acl:acl"; }
                @Override public List<AclEntry> value() { return entries; }
            };
        }
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }

    private UserPrincipal currentUser() throws IOException { return principal(System.getProperty("user.name")); }
    private UserPrincipal principal(String name) throws IOException {
        return temporaryRoot.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);
    }

    @FunctionalInterface interface ScanRunner { int scan(Path file, int timeoutMillis) throws IOException; }
    @FunctionalInterface interface ProcessStarter { Process start(ProcessBuilder builder) throws IOException; }

    static final class DefenderProcessRunner implements ScanRunner {
        private final ProcessStarter starter;
        DefenderProcessRunner(ProcessStarter starter) { this.starter = starter; }
        @Override public int scan(Path file, int timeoutMillis) throws IOException {
            var builder = new ProcessBuilder(EXECUTABLE.toString(), "-Scan", "-ScanType", "3", "-File",
                    file.toString(), "-DisableRemediation");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            // Do not pass the application's database/mail/security environment to the scanner process.
            builder.environment().clear();
            // MpCmdRun needs its normal profile/log paths; an empty profile yields ERROR_BAD_PATHNAME.
            // Copy only fixed operating-system variables, never the application's inherited environment.
            for (String name : List.of("SystemRoot", "WINDIR", "SystemDrive", "ProgramData", "ProgramFiles",
                    "ProgramFiles(x86)", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA",
                    "HOMEDRIVE", "HOMEPATH", "USERNAME", "USERDOMAIN")) {
                String value = System.getenv(name);
                if (value != null) builder.environment().put(name, value);
            }
            Process process = starter.start(builder);
            try {
                process.getOutputStream().close();
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw new IOException(FAILURE);
                return process.exitValue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException(FAILURE);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    try { process.waitFor(2000, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }
}
