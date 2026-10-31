package com.travelgo.service;

import java.io.IOException;

/** Server-only private document storage. References must never be returned as download URLs. */
public interface VisaDocumentStorage {
    int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;

    StoredDocument store(byte[] content, String extension) throws IOException;
    Content read(String reference) throws IOException;
    /** Uses only trusted local metadata; implementations must not make a request for each retained document. */
    long retainedSize(String reference) throws IOException;
    boolean healthy();
    /** -1 means the backend cannot provide a trustworthy available-byte figure. */
    long availableBytes();

    @FunctionalInterface
    interface Cleanup { void run() throws IOException; }

    /** Cleanup is a capability for this newly created object, never for an arbitrary retained reference. */
    record StoredDocument(String reference, Cleanup cleanup) {}
    record Content(byte[] bytes, String extension) {}

    static IOException unavailable() {
        return new IOException("Document storage is unavailable. Contact our team and try again later.");
    }
}
