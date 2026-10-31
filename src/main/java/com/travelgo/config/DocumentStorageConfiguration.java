package com.travelgo.config;

import com.travelgo.service.FilesystemVisaDocumentStorage;
import com.travelgo.service.SupabaseVisaDocumentStorage;
import com.travelgo.service.VisaDocumentStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class DocumentStorageConfiguration {
    @Bean public VisaDocumentStorage visaDocumentStorage(
            @Value("${travelgo.documents.storage-backend:filesystem}") String backend,
            @Value("${travelgo.upload-directory:uploads/visa-documents}") String directory,
            @Value("${travelgo.documents.supabase.project-url:}") String projectUrl,
            @Value("${travelgo.documents.supabase.bucket:}") String bucket,
            @Value("${travelgo.documents.supabase.service-key:}") String key,
            @Value("${travelgo.documents.supabase.connect-timeout-ms:3000}") int connectTimeout,
            @Value("${travelgo.documents.supabase.request-timeout-ms:15000}") int requestTimeout) {
        var filesystem=new FilesystemVisaDocumentStorage(directory);
        return switch (backend) {
            case "filesystem" -> filesystem;
            case "supabase" -> new SupabaseVisaDocumentStorage(projectUrl,bucket,key,connectTimeout,requestTimeout,filesystem);
            default -> throw new IllegalArgumentException("Document storage configuration is invalid.");
        };
    }
}
