package com.travelgo.service;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Admission limits may be reduced for a deployment but never exceed local safety bounds. */
@Component
public final class DocumentUploadLimits {
    public static final long DEFAULT_MAX_FILE_BYTES=10L*1024*1024;
    public static final long DEFAULT_MAX_IMAGE_PIXELS=25000000L;
    private final long maxFileBytes;
    private final long maxImagePixels;

    public DocumentUploadLimits() {
        this(DEFAULT_MAX_FILE_BYTES,DEFAULT_MAX_IMAGE_PIXELS);
    }

    @Autowired
    public DocumentUploadLimits(
            @Value("${travelgo.documents.max-file-bytes:10485760}") long maxFileBytes,
            @Value("${travelgo.documents.max-image-pixels:25000000}") long maxImagePixels) {
        if (maxFileBytes < 1 || maxFileBytes > DEFAULT_MAX_FILE_BYTES
                || maxImagePixels < 1 || maxImagePixels > DEFAULT_MAX_IMAGE_PIXELS)
            throw new IllegalArgumentException("Document upload limit configuration is invalid.");
        this.maxFileBytes=maxFileBytes;
        this.maxImagePixels=maxImagePixels;
    }

    public long getMaxFileBytes() { return maxFileBytes; }
    public long getMaxImagePixels() { return maxImagePixels; }

    /** Exact units keep the displayed cap consistent with byte admission. */
    public String getMaxFileSizeLabel() {
        if (maxFileBytes % (1024*1024) == 0) return maxFileBytes/(1024*1024)+" MiB";
        if (maxFileBytes % 1000000 == 0) return maxFileBytes/1000000+" MB";
        if (maxFileBytes % 1024 == 0) return maxFileBytes/1024+" KiB";
        return maxFileBytes+(maxFileBytes == 1 ? " byte" : " bytes");
    }

    public String getMaxImageSizeLabel() {
        if (maxImagePixels < 1000000) return maxImagePixels+(maxImagePixels == 1 ? " pixel" : " pixels");
        return BigDecimal.valueOf(maxImagePixels,6).stripTrailingZeros().toPlainString()+" megapixels";
    }
}
