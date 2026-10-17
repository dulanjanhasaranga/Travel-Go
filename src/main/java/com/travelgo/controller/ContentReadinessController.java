package com.travelgo.controller;

import com.travelgo.service.ContentReadinessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/content-review")
public class ContentReadinessController {
    private final ContentReadinessService service;
    public ContentReadinessController(ContentReadinessService service) { this.service = service; }

    @GetMapping
    public String review(Model model, jakarta.servlet.http.HttpServletResponse response) {
        model.addAttribute("review", service.overview());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return "admin/content-review";
    }

    @GetMapping("/catalogue.csv")
    public ResponseEntity<byte[]> catalogue() {
        return download(service.catalogueWorksheet(), "travelgo-catalogue-review.csv", "text/csv;charset=UTF-8");
    }

    @GetMapping("/workflow.md")
    public ResponseEntity<byte[]> workflow() {
        return download(service.workflowWorksheet(), "travelgo-workflow-acceptance.md", "text/markdown;charset=UTF-8");
    }

    private ResponseEntity<byte[]> download(byte[] body, String name, String type) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header("X-Content-Type-Options", "nosniff").body(body);
    }
}
