package com.travelgo.service;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class DocumentUploadLimitsTests {
    @Test void defaultsPreserveLocalByteAndPixelBounds() {
        var limits=new DocumentUploadLimits();
        assertEquals(10485760L,limits.getMaxFileBytes());
        assertEquals(25000000L,limits.getMaxImagePixels());
        assertEquals("10 MiB",limits.getMaxFileSizeLabel());
        assertEquals("25 megapixels",limits.getMaxImageSizeLabel());
    }

    @Test void lowerConfiguredPropertiesBindAndProduceExactUserLabels() {
        // This isolated bean context has no application, database or provider components.
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-limits",Map.of(
                    "travelgo.documents.max-file-bytes","3000000",
                    "travelgo.documents.max-image-pixels","8000000")));
            context.register(DocumentUploadLimits.class);context.refresh();
            var limits=context.getBean(DocumentUploadLimits.class);
            assertEquals(3000000L,limits.getMaxFileBytes());
            assertEquals(8000000L,limits.getMaxImagePixels());
            assertEquals("3 MB",limits.getMaxFileSizeLabel());
            assertEquals("8 megapixels",limits.getMaxImageSizeLabel());
        }
    }

    @Test void limitsCannotBeDisabledOrRaisedAboveExistingSafetyBounds() {
        for(long invalid:new long[]{Long.MIN_VALUE,0,10485761,Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->new DocumentUploadLimits(invalid,25000000));
        for(long invalid:new long[]{Long.MIN_VALUE,0,25000001,Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->new DocumentUploadLimits(10485760,invalid));
        assertDoesNotThrow(()->new DocumentUploadLimits(1,1));
    }

    @Test void displayedByteLimitNeverRoundsAwayConfiguredPrecision() {
        assertEquals("3000001 bytes",new DocumentUploadLimits(3000001,8000000).getMaxFileSizeLabel());
        assertEquals("1 byte",new DocumentUploadLimits(1,1).getMaxFileSizeLabel());
        assertEquals("1 pixel",new DocumentUploadLimits(1,1).getMaxImageSizeLabel());
    }
}
