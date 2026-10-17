package com.travelgo;

import com.travelgo.entity.VisaApplication;
import com.travelgo.entity.VisaDocument;
import com.travelgo.service.DocumentUploadQuota;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentUploadQuotaTests {
    @TempDir Path root;
    private final DocumentUploadQuota quota=new DocumentUploadQuota(2,3,10*1024*1024L,20*1024*1024L);

    private VisaDocument document(long visaId,Path path) {
        VisaApplication visa=new VisaApplication(); visa.setId(visaId);
        VisaDocument document=new VisaDocument();document.setVisaApplication(visa);document.setDocumentUrl(path.toString());
        return document;
    }
    @Test void firstUploadDoesNotRequireAnExistingDirectory() throws Exception {
        var usage=quota.inspect(List.of(),1L,root.resolve("not-created"));
        assertEquals(new DocumentUploadQuota.Usage(0,0),usage);
        assertDoesNotThrow(()->quota.admit(usage,10*1024*1024L));
    }
    @Test void retainedFilesCountForTheirVisaAndAcrossTheWholeAccount() throws Exception {
        Path a=Files.write(root.resolve("a.pdf"),new byte[11]);
        Path b=Files.write(root.resolve("b.pdf"),new byte[19]);
        var usage=quota.inspect(List.of(document(1,a),document(2,b)),1L,root);
        assertEquals(new DocumentUploadQuota.Usage(11,30),usage);
    }
    @Test void visaCountRejectsBeforeAnyFileAccounting() {
        var missing=root.resolve("missing");
        assertThrows(IllegalArgumentException.class,()->quota.inspect(List.of(document(1,missing),document(1,missing)),1L,root));
    }
    @Test void accountCountIncludesOtherAndHistoricalApplications() {
        var missing=root.resolve("missing");
        assertThrows(IllegalArgumentException.class,()->quota.inspect(List.of(document(1,missing),document(2,missing),document(3,missing)),4L,root));
    }
    @Test void exactVisaByteBoundaryIsAllowedButOneExtraByteIsNot() {
        var usage=new DocumentUploadQuota.Usage(10*1024*1024L-1,10*1024*1024L-1);
        assertDoesNotThrow(()->quota.admit(usage,1));
        assertThrows(IllegalArgumentException.class,()->quota.admit(usage,2));
    }
    @Test void accountByteBoundaryCannotBeBypassedByUsingAnotherVisa() {
        var usage=new DocumentUploadQuota.Usage(0,20*1024*1024L-1);
        assertDoesNotThrow(()->quota.admit(usage,1));
        assertThrows(IllegalArgumentException.class,()->quota.admit(usage,2));
    }
    @Test void arithmeticCannotWrapToAdmitOversizedUsage() {
        assertThrows(IllegalArgumentException.class,()->quota.admit(new DocumentUploadQuota.Usage(Long.MAX_VALUE,Long.MAX_VALUE),1));
        assertThrows(IllegalArgumentException.class,()->quota.admit(new DocumentUploadQuota.Usage(-1,0),1));
        assertThrows(IllegalArgumentException.class,()->quota.admit(new DocumentUploadQuota.Usage(0,0),0));
    }
    @Test void unavailableOrEmptyStoredFilesFailClosedWithoutDisclosingTheirPath() throws Exception {
        for(Path path:List.of(root.resolve("private-missing.pdf"),Files.write(root.resolve("empty.pdf"),new byte[0]),root)) {
            IOException failure=assertThrows(IOException.class,()->quota.inspect(List.of(document(1,path)),1L,root));
            assertFalse(failure.getMessage().contains(path.toString()));
            assertNull(failure.getCause());
        }
    }
    @Test void fileOutsideThePrivateRootIsNotCountedAsTrustedUsage() throws Exception {
        Path other=Files.createTempFile(root.getParent(),"quota-outside-",".pdf");
        try {Files.write(other,new byte[1]); assertThrows(IOException.class,()->quota.inspect(List.of(document(1,other)),1L,root));}
        finally {Files.deleteIfExists(other);}
    }
    @Test void invalidConfigurationFailsAtConstruction() {
        assertThrows(IllegalArgumentException.class,()->new DocumentUploadQuota(0,3,10*1024*1024L,20*1024*1024L));
        assertThrows(IllegalArgumentException.class,()->new DocumentUploadQuota(3,2,10*1024*1024L,20*1024*1024L));
        assertThrows(IllegalArgumentException.class,()->new DocumentUploadQuota(2,3,1,20*1024*1024L));
        assertThrows(IllegalArgumentException.class,()->new DocumentUploadQuota(2,3,20*1024*1024L,10*1024*1024L));
    }
}
