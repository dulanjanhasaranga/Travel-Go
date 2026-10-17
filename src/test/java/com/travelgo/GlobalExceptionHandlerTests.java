package com.travelgo;

import com.travelgo.exception.GlobalExceptionHandler;
import com.travelgo.service.CustomerErrorMessage;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tests MVC failure responses without an application context or database connection. */
class GlobalExceptionHandlerTests {
    private static final String PRIVATE_DETAIL = "jdbc:mysql://private-db/customer_records provider-token=private-secret";
    private static final String SAFE_MESSAGE = CustomerErrorMessage.from(new RuntimeException());
    private MockMvc mvc;

    @BeforeEach void setup() {
        InternalResourceViewResolver views = new InternalResourceViewResolver("/WEB-INF/views/", ".jsp");
        mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler()).setViewResolvers(views).build();
    }

    @Test void unexpectedDatabaseAndProviderFailuresReturnSafeServerErrorPage() throws Exception {
        for (String path : new String[]{"/failure/database", "/failure/runtime", "/failure/provider"}) {
            MvcResult result = mvc.perform(get(path)).andExpect(status().isInternalServerError())
                    .andExpect(view().name("error")).andExpect(model().attribute("errorMessage", SAFE_MESSAGE)).andReturn();
            assertNoDiagnosticLeak(result);
        }
    }

    @Test void unexpectedCheckedFailureAlsoReturnsServerErrorWithNoDiagnostics() throws Exception {
        MvcResult result = mvc.perform(get("/failure/checked")).andExpect(status().isInternalServerError())
                .andExpect(view().name("error")).andExpect(model().attribute("errorMessage", SAFE_MESSAGE)).andReturn();
        assertNoDiagnosticLeak(result);
    }

    @Test void exactDomainValidationFailuresKeepUsefulSafeRedirectMessages() throws Exception {
        mvc.perform(get("/failure/argument")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("errorMessage", "Choose an available departure."));
        mvc.perform(get("/failure/state")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("errorMessage", "The payment deadline has passed."));
    }

    @Test void missingAndMalformedParametersReturnBadRequestWithoutEchoingInput() throws Exception {
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                get("/failure/parameter"), get("/failure/parameter").param("id", PRIVATE_DETAIL)}) {
            MvcResult result = mvc.perform(request).andExpect(status().isBadRequest()).andReturn();
            assertNoDiagnosticLeak(result);
        }
    }

    @Test void unsupportedMethodRetainsMethodNotAllowedStatus() throws Exception {
        MvcResult result = mvc.perform(post("/failure/parameter").param("id", "1"))
                .andExpect(status().isMethodNotAllowed()).andReturn();
        assertNoDiagnosticLeak(result);
    }

    @Test void missingHandlerAndResourceRemainNotFoundWithoutDisclosingPaths() throws Exception {
        for (String path : new String[]{"/unmapped-page", "/failure/resource", "/failure/status"}) {
            MvcResult result = mvc.perform(get(path)).andExpect(status().isNotFound()).andReturn();
            assertNoDiagnosticLeak(result);
        }
    }

    @Test void accessDeniedRetainsForbiddenStatusWithoutItsInternalReason() throws Exception {
        MvcResult result = mvc.perform(get("/failure/denied")).andExpect(status().isForbidden()).andReturn();
        assertNoDiagnosticLeak(result);
    }

    @Test void uncommittedBinaryFailureBecomesSafeHtmlWithoutAWriterCollision() throws Exception {
        MvcResult result = streamingMvc().perform(get("/failure/binary"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string("<p>" + SAFE_MESSAGE + "</p>"))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(header().doesNotExist("Content-Length"))
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "script-src 'nonce-synthetic'"))
                .andExpect(header().stringValues("Set-Cookie", "fixture=one; HttpOnly", "second=two; HttpOnly"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        assertNoDiagnosticLeak(result);
        assertFalse(result.getResponse().getContentAsString().contains("partial-download"));
    }

    @Test void uncommittedBinaryHttpErrorDiscardsPartialDataAndKeepsItsStatus() throws Exception {
        MvcResult result = streamingMvc().perform(get("/failure/binary-status"))
                .andExpect(status().isNotFound()).andExpect(content().bytes(new byte[0]))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(header().doesNotExist("Content-Length"))
                .andExpect(header().string("X-Frame-Options", "DENY")).andReturn();
        assertNoDiagnosticLeak(result);
    }

    @Test void uncommittedBinaryValidationFailureRetainsTheNormalSafeRedirect() throws Exception {
        streamingMvc().perform(get("/failure/binary-state"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("errorMessage", "The payment deadline has passed."))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test void committedBinaryFailuresAreHandledWithoutRenderingOrChangingTheTransfer() throws Exception {
        for (String path : new String[]{"/failure/committed-binary", "/failure/committed-state", "/failure/committed-status"}) {
            MvcResult result = streamingMvc().perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().string("partial-download"))
                    .andReturn();
            assertNull(result.getModelAndView());
            assertTrue(result.getFlashMap().isEmpty());
            assertTrue(result.getResponse().isCommitted());
        }
    }

    @Test void handlerDoesNotResetOrChangeCommittedHeaders() throws Exception {
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=fixture.pdf");
        response.getOutputStream().write("partial-download".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        response.flushBuffer();
        assertNull(new GlobalExceptionHandler().handleGenericException(new IOException(PRIVATE_DETAIL),
                new org.springframework.ui.ExtendedModelMap(), response));
        assertEquals("application/pdf", response.getContentType());
        assertEquals("attachment; filename=fixture.pdf", response.getHeader("Content-Disposition"));
        assertEquals("partial-download", response.getContentAsString());
        assertEquals(200, response.getStatus());
    }

    private MockMvc streamingMvc() {
        return MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setViewResolvers((viewName, locale) -> {
                    if (viewName.startsWith("redirect:"))
                        return new org.springframework.web.servlet.view.RedirectView(viewName.substring("redirect:".length()));
                    return (model, request, response) -> {
                    assertEquals("error", viewName);
                    response.setContentType("text/html");
                    response.getWriter().write("<p>" + model.get("errorMessage") + "</p>");
                    };
                })
                .addFilters((request, response, chain) ->
                        chain.doFilter(request, new StrictOutputResponse((HttpServletResponse) response))).build();
    }

    /** Mock responses normally permit both APIs; a servlet container does not. */
    private static final class StrictOutputResponse extends HttpServletResponseWrapper {
        private boolean streamSelected;
        private boolean writerSelected;
        StrictOutputResponse(HttpServletResponse response) { super(response); }
        @Override public ServletOutputStream getOutputStream() throws IOException {
            if (writerSelected) throw new IllegalStateException("getWriter() has already been called for this response");
            streamSelected = true;
            return super.getOutputStream();
        }
        @Override public PrintWriter getWriter() throws IOException {
            if (streamSelected) throw new IllegalStateException("getOutputStream() has already been called for this response");
            writerSelected = true;
            return super.getWriter();
        }
        @Override public void reset() {
            super.reset();
            streamSelected = false;
            writerSelected = false;
        }
    }

    private void assertNoDiagnosticLeak(MvcResult result) throws Exception {
        assertTrue(result.getFlashMap().isEmpty());
        assertFalse(result.getResponse().getContentAsString().contains(PRIVATE_DETAIL));
        if (result.getModelAndView() != null)
            assertFalse(result.getModelAndView().getModel().values().stream().anyMatch(value -> String.valueOf(value).contains(PRIVATE_DETAIL)));
    }

    @Controller
    static class FailureController {
        @GetMapping("/failure/database") String database() {
            throw new DataIntegrityViolationException(PRIVATE_DETAIL, new SQLException(PRIVATE_DETAIL));
        }
        @GetMapping("/failure/runtime") String runtime() { throw new RuntimeException(PRIVATE_DETAIL); }
        @GetMapping("/failure/provider") String provider() { throw new ProviderFailure(PRIVATE_DETAIL); }
        @GetMapping("/failure/checked") String checked() throws IOException { throw new IOException(PRIVATE_DETAIL); }
        @GetMapping("/failure/argument") String argument() { throw new IllegalArgumentException("Choose an available departure."); }
        @GetMapping("/failure/state") String state() { throw new IllegalStateException("The payment deadline has passed."); }
        @GetMapping("/failure/parameter") String parameter(@RequestParam("id") Long id) { return "ok"; }
        @GetMapping("/failure/resource") String resource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/failure/resource", PRIVATE_DETAIL);
        }
        @GetMapping("/failure/status") String statusError() { throw new ResponseStatusException(HttpStatus.NOT_FOUND, PRIVATE_DETAIL); }
        @GetMapping("/failure/denied") String denied() { throw new AccessDeniedException(PRIVATE_DETAIL); }
        @GetMapping("/failure/binary") String binary(HttpServletResponse response) throws IOException {
            partialDownload(response); throw new IOException(PRIVATE_DETAIL);
        }
        @GetMapping("/failure/binary-status") String binaryStatus(HttpServletResponse response) throws IOException {
            partialDownload(response); throw new ResponseStatusException(HttpStatus.NOT_FOUND, PRIVATE_DETAIL);
        }
        @GetMapping("/failure/binary-state") String binaryState(HttpServletResponse response) throws IOException {
            partialDownload(response); throw new IllegalStateException("The payment deadline has passed.");
        }
        @GetMapping("/failure/committed-binary") String committedBinary(HttpServletResponse response) throws IOException {
            partialDownload(response); response.flushBuffer(); throw new IOException(PRIVATE_DETAIL);
        }
        @GetMapping("/failure/committed-state") String committedState(HttpServletResponse response) throws IOException {
            partialDownload(response); response.flushBuffer(); throw new IllegalStateException(PRIVATE_DETAIL);
        }
        @GetMapping("/failure/committed-status") String committedStatus(HttpServletResponse response) throws IOException {
            partialDownload(response); response.flushBuffer(); throw new ResponseStatusException(HttpStatus.NOT_FOUND, PRIVATE_DETAIL);
        }
        private void partialDownload(HttpServletResponse response) throws IOException {
            response.setContentType("application/pdf");
            response.setHeader("Content-Disposition", "attachment; filename=fixture.pdf");
            response.setContentLength(4096);
            response.setHeader("Content-Encoding", "gzip");
            response.setHeader("ETag", "\"synthetic-download\"");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Content-Security-Policy", "script-src 'nonce-synthetic'");
            response.addHeader("Set-Cookie", "fixture=one; HttpOnly");
            response.addHeader("Set-Cookie", "second=two; HttpOnly");
            response.getOutputStream().write("partial-download".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
    }

    private static class ProviderFailure extends IllegalStateException {
        ProviderFailure(String message) { super(message); }
    }
}
