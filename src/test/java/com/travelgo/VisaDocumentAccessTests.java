package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.VisaDocumentScanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real account/role lookup and security chain; private files and recipients are synthetic. */
@SpringBootTest(properties={"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:visa-access-recheck;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
class VisaDocumentAccessTests {
    @TempDir static Path storage;
    @DynamicPropertySource static void storage(DynamicPropertyRegistry properties) {
        properties.add("travelgo.upload-directory", () -> storage.toString());
    }
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired PasswordEncoder passwords;
    @MockitoBean VisaDocumentRepository documents;
    @MockitoBean VisaDocumentScanner scanner;
    MockMvc mvc;
    User owner;
    final byte[] content="%PDF-1.4\nSynthetic passport fixture\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    static final String PASSWORD="Isolated-recheck-123!";

    @BeforeEach void fixture() throws Exception {
        SecurityContextHolder.clearContext();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner=account("CUSTOMER", List.of());
        Booking booking=new Booking(); booking.setId(401L); booking.setUser(owner);
        VisaApplication visa=new VisaApplication(); visa.setId(402L); visa.setBooking(booking);
        VisaDocument document=new VisaDocument(); document.setId(403L); document.setVisaApplication(visa);
        document.setDocumentUrl(Files.write(storage.resolve(UUID.randomUUID()+".pdf"),content).toString());
        when(documents.findById(403L)).thenReturn(Optional.of(document));
    }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }
    User account(String name, List<String> grants) {
        Role role=roles.findByRoleName(name).orElseGet(() -> roles.saveAndFlush(new Role(name,"Synthetic recheck role")));
        Set<Permission> assigned=new HashSet<>();
        for(String grant:grants) assigned.add(permissions.findByPermissionName(grant).orElseGet(() -> permissions.saveAndFlush(new Permission(grant,"Synthetic recheck permission","VISA"))));
        role.setPermissions(assigned); roles.saveAndFlush(role);
        User user=new User("Synthetic recheck",UUID.randomUUID()+"@example.invalid",passwords.encode(PASSWORD),role);
        user.setActive(true); return users.saveAndFlush(user);
    }
    MockHttpSession login(User user) throws Exception {
        var result=mvc.perform(post("/auth/login").with(csrf()).param("email",user.getEmail()).param("password",PASSWORD))
            .andExpect(status().is3xxRedirection()).andReturn();
        assertFalse(result.getResponse().getRedirectedUrl().contains("error=true"));
        SecurityContextHolder.clearContext();
        return (MockHttpSession)result.getRequest().getSession(false);
    }
    @Test void freshOfficerWithOnlyUnrelatedPermissionCannotReadAnyDocument() throws Exception {
        var session=login(account("VISA_OFFICER",List.of("PAYMENT_VIEW")));
        mvc.perform(get("/staff/visas").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/visa-documents/403/download").session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/visa-documents/999999/download").session(session)).andExpect(status().isNotFound());
        verify(documents,never()).findById(any()); verifyNoInteractions(scanner);
    }
    @ParameterizedTest @ValueSource(strings={"VISA_VIEW","VISA_MANAGE"})
    void officerWithEitherVisaPermissionCanDownload(String grant) throws Exception {
        var session=login(account("VISA_OFFICER",List.of(grant)));
        mvc.perform(get("/visa-documents/403/download").session(session)).andExpect(status().isOk())
            .andExpect(content().bytes(content)).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("X-Content-Type-Options","nosniff"));
        verify(scanner).scan(argThat(bytes -> Arrays.equals(content,bytes)));
    }
    @Test void revokingVisaPermissionsDeniesTheOldSessionAndTheNextFreshLoginBeforeFileLookup() throws Exception {
        User officer=account("VISA_OFFICER",List.of("VISA_VIEW"));
        var previousSession=login(officer);
        Role role=roles.findByRoleName("VISA_OFFICER").orElseThrow();
        role.setPermissions(Set.of(permissions.findByPermissionName("PAYMENT_VIEW")
                .orElseGet(() -> permissions.saveAndFlush(new Permission("PAYMENT_VIEW","Synthetic unrelated grant","PAYMENT")))));
        roles.saveAndFlush(role);
        mvc.perform(get("/visa-documents/403/download").session(previousSession))
                .andExpect(redirectedUrl("/auth/login?sessionChanged=true"));
        assertTrue(previousSession.isInvalid());
        mvc.perform(get("/visa-documents/403/download").session(login(officer))).andExpect(status().isNotFound());
        verify(documents,never()).findById(any()); verifyNoInteractions(scanner);
    }
    @Test void customersRetainOwnDocumentAccessAndCannotReadAnotherCustomersFile() throws Exception {
        mvc.perform(get("/visa-documents/403/download").session(login(owner))).andExpect(status().isOk()).andExpect(content().bytes(content));
        reset(scanner);
        var other=account("CUSTOMER",List.of());
        mvc.perform(get("/visa-documents/403/download").session(login(other))).andExpect(status().isNotFound());
        verifyNoInteractions(scanner);
    }
    @Test void visaPermissionDoesNotGrantAnAdministratorOfficerDocumentAccess() throws Exception {
        mvc.perform(get("/visa-documents/403/download").session(login(account("ADMIN",List.of("VISA_MANAGE")))))
            .andExpect(status().isNotFound());
        verify(documents,never()).findById(any()); verifyNoInteractions(scanner);
    }
    @Test void anonymousRequestsDoNotRevealWhetherADocumentExists() throws Exception {
        mvc.perform(get("/visa-documents/403/download")).andExpect(status().isNotFound());
        mvc.perform(get("/visa-documents/999999/download")).andExpect(status().isNotFound());
        verify(documents,never()).findById(any()); verifyNoInteractions(scanner);
    }
}
