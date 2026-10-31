package com.travelgo.service;

import com.travelgo.entity.VisaDocument;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Counts retained documents, including historical/verified ones. Call under the owner-account lock. */
@Component
public class DocumentUploadQuota {
    private final int visaCount;
    private final int accountCount;
    private final long visaBytes;
    private final long accountBytes;

    public DocumentUploadQuota(
            @Value("${travelgo.documents.max-per-visa:30}") int visaCount,
            @Value("${travelgo.documents.max-per-account:120}") int accountCount,
            @Value("${travelgo.documents.max-bytes-per-visa:104857600}") long visaBytes,
            @Value("${travelgo.documents.max-bytes-per-account:524288000}") long accountBytes) {
        if (visaCount < 1 || accountCount < visaCount || accountCount > 10000
                || visaBytes < 10L * 1024 * 1024 || accountBytes < visaBytes)
            throw new IllegalArgumentException("Document quota configuration is invalid.");
        this.visaCount=visaCount; this.accountCount=accountCount;
        this.visaBytes=visaBytes; this.accountBytes=accountBytes;
    }

    /** Fetching this many rows is enough to reject a full account without unbounded historical loading. */
    public int inspectionLimit() { return accountCount; }

    /** Count rejection precedes opening the incoming stream or inspecting retained files. */
    public Usage inspect(List<VisaDocument> documents, Long visaId, Path configuredRoot) throws IOException {
        return inspect(documents,visaId,new FilesystemVisaDocumentStorage(configuredRoot.toString()));
    }

    public Usage inspect(List<VisaDocument> documents, Long visaId, VisaDocumentStorage storage) throws IOException {
        if (documents == null || visaId == null) throw unavailable();
        long count=documents.stream().filter(d -> d != null && d.getVisaApplication() != null
                && visaId.equals(d.getVisaApplication().getId())).count();
        if (count >= visaCount) throw new IllegalArgumentException("The document limit for this visa application has been reached. Contact our team to review your documents.");
        if (documents.size() >= accountCount) throw new IllegalArgumentException("Your account document limit has been reached. Contact our team to review your documents.");
        if (documents.isEmpty()) return new Usage(0,0);
        try {
            long visaTotal=0, accountTotal=0;
            for (VisaDocument document : documents) {
                if (document == null || document.getVisaApplication() == null || document.getDocumentUrl() == null) throw unavailable();
                long size=storage.retainedSize(document.getDocumentUrl());
                if (size < 1) throw unavailable();
                accountTotal=Math.addExact(accountTotal,size);
                if (visaId.equals(document.getVisaApplication().getId())) visaTotal=Math.addExact(visaTotal,size);
            }
            return new Usage(visaTotal,accountTotal);
        } catch (IOException | RuntimeException failure) {
            throw unavailable();
        }
    }

    public void admit(Usage usage, long proposedBytes) {
        if (usage == null || proposedBytes < 1 || usage.visaBytes < 0 || usage.accountBytes < 0)
            throw new IllegalArgumentException("Document upload size could not be checked.");
        if (usage.visaBytes > visaBytes || proposedBytes > visaBytes-usage.visaBytes)
            throw new IllegalArgumentException("The storage allowance for this visa application has been reached. Contact our team to review your documents.");
        if (usage.accountBytes > accountBytes || proposedBytes > accountBytes-usage.accountBytes)
            throw new IllegalArgumentException("Your account document storage allowance has been reached. Contact our team to review your documents.");
    }

    private static IOException unavailable() {
        return new IOException("Document storage could not be checked. Contact our team before uploading another file.");
    }
    public record Usage(long visaBytes,long accountBytes) {}
}
