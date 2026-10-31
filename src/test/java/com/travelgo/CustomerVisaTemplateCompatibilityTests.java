package com.travelgo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/** Reproduce the installed controller's older model without touching a real DB. */
@Import(CustomerVisaTemplateCompatibilityTests.LegacyModel.class)
class CustomerVisaTemplateCompatibilityTests extends WorkflowIntegrationTests {
    @TestConfiguration
    static class LegacyModel implements WebMvcConfigurer {
        @Override public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override public void postHandle(HttpServletRequest request, HttpServletResponse response,
                        Object handler, ModelAndView model) {
                    if (model != null && "true".equals(request.getHeader("X-Test-Legacy-Visa-Model")))
                        model.getModel().remove("documentUploadLimits");
                }
            }).addPathPatterns("/customer/visas");
        }
    }

    @Test void ownedVisaPageRendersWithConfiguredLimitsAndWithTheInstalledLegacyModel() throws Exception {
        apply(create(false));
        String configured=mvc.perform(get("/customer/visas").with(user(customer.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(configured.contains("data-max-file-bytes=\"10485760\""));
        String legacy=mvc.perform(get("/customer/visas").header("X-Test-Legacy-Visa-Model","true")
                        .with(user(customer.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(legacy.contains("File size and image limits are checked on upload."));
        assertTrue(legacy.contains("Upload document"));
        assertFalse(legacy.contains("data-max-file-bytes="));
        if(Boolean.getBoolean("travelgo.audit.export-ui")) {
            var directory=java.nio.file.Path.of("target/showcase-readiness-render");
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve("customer-visas-legacy.html"),legacy);
        }
    }
}
