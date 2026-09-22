package com.travelgo.controller;
import com.travelgo.service.VisaDocumentService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class VisaDocumentController {
    private final VisaDocumentService documents;
    public VisaDocumentController(VisaDocumentService documents) { this.documents=documents; }
    @GetMapping("/visa-documents/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable("id") Long id) {
        var file=documents.download(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.filename()).build().toString())
            .header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).body(file.content());
    }
}
