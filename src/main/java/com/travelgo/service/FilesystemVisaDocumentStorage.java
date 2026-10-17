package com.travelgo.service;

import static com.travelgo.service.VisaDocumentStorage.unavailable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** The original filesystem backend and the compatibility reader for existing private paths. */
public final class FilesystemVisaDocumentStorage implements VisaDocumentStorage {
    private final Path root;

    public FilesystemVisaDocumentStorage(String directory) {
        root=Path.of(directory).toAbsolutePath().normalize();
    }

    @Override public StoredDocument store(byte[] content, String extension) throws IOException {
        if (content == null || content.length < 1 || content.length > MAX_DOCUMENT_BYTES
                || extension == null || !Set.of("pdf","png","jpg").contains(extension)) throw unavailable();
        Path target;
        try {
            Files.createDirectories(root);
            target=root.toRealPath().resolve(UUID.randomUUID()+"."+extension);
        } catch (IOException | RuntimeException failure) { throw unavailable(); }
        boolean created=false;
        try {
            var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW);
            created=true;
            try(output) { output.write(content); }
        } catch (IOException failure) {
            // An exclusive-create conflict must never delete somebody else's file.
            if (created) {
                try { Files.deleteIfExists(target); } catch (IOException cleanupFailure) { /* Generic caller failure. */ }
            }
            throw unavailable();
        }
        return new StoredDocument(target.toString(),() -> {
            try { Files.deleteIfExists(target); }
            catch (IOException | RuntimeException failure) { throw unavailable(); }
        });
    }

    private Path checked(String reference) throws IOException {
        try {
            Path stored=Path.of(reference).toAbsolutePath().normalize();
            Path actualRoot=root.toRealPath();
            if (!stored.startsWith(root) || !stored.toRealPath().startsWith(actualRoot)) throw unavailable();
            BasicFileAttributes attributes=Files.readAttributes(stored,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() < 1 || attributes.size() > MAX_DOCUMENT_BYTES) throw unavailable();
            return stored;
        } catch (IOException | RuntimeException failure) { throw unavailable(); }
    }

    @Override public Content read(String reference) throws IOException {
        Path stored=checked(reference);
        byte[] bytes;
        try(var input=Files.newInputStream(stored)) { bytes=input.readNBytes(MAX_DOCUMENT_BYTES+1); }
        catch (IOException failure) { throw unavailable(); }
        if (bytes.length < 1 || bytes.length > MAX_DOCUMENT_BYTES) throw unavailable();
        String filename=stored.getFileName().toString().toLowerCase(Locale.ROOT);
        String extension=filename.contains(".") ? filename.substring(filename.lastIndexOf('.')+1) : "bin";
        if (!Set.of("pdf","png","jpg","jpeg").contains(extension)) extension="bin";
        return new Content(bytes,extension);
    }

    @Override public long retainedSize(String reference) throws IOException {
        try { return Files.size(checked(reference)); }
        catch (IOException | RuntimeException failure) { throw unavailable(); }
    }

    @Override public boolean healthy() {
        try { return com.travelgo.config.ProductionEnvironmentValidator.isAccessiblePrivateDirectory(root); }
        catch (SecurityException failure) { return false; }
    }

    @Override public long availableBytes() {
        try { return healthy() ? Files.getFileStore(root).getUsableSpace() : -1; }
        catch (IOException | RuntimeException failure) { return -1; }
    }
}
