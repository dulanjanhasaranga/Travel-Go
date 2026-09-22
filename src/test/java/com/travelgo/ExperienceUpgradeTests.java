package com.travelgo;
import com.travelgo.service.AccountRecoveryService;
import com.travelgo.repository.PasswordResetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
public class ExperienceUpgradeTests extends WorkflowIntegrationTests {
 @Autowired AccountRecoveryService recovery;
 @Autowired PasswordResetRepository resets;
 @MockitoBean JavaMailSender sender;
 @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
 @Test void unsupportedStoredRoleGetsClearAccountGuidance()throws Exception {
  tx.executeWithoutResult(s->{var role=new com.travelgo.entity.Role();role.setRoleName("LEGACY_"+java.util.UUID.randomUUID().toString().substring(0,8));role.setDescription("Unsupported test role");roleRepo.save(role);var account=userRepo.findById(consultant.getId()).orElseThrow();account.setRole(role);account.setPassword(passwordEncoder.encode("Legacy-password!"));});
  org.springframework.security.core.context.SecurityContextHolder.clearContext();
  mvc.perform(post("/auth/login").with(csrf()).param("email",consultant.getEmail()).param("password","Legacy-password!")).andExpect(redirectedUrl("/auth/login?roleUnavailable=true"));
 }
 String requestToken(){
  recovery.request(customer.getEmail());var capture=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(sender).send(capture.capture());
  return capture.getValue().getText().split("token=")[1].split("\\n")[0];
 }
 @Test void recoveryTokensAreHashedSingleUseAndExpireAtBoundary(){
  String token=requestToken();assertFalse(resets.existsById(token));
  recovery.reset(token,"A-strong-new-password!","A-strong-new-password!");
  assertThrows(IllegalArgumentException.class,()->recovery.reset(token,"Another-password!","Another-password!"));
  reset(sender);String expired=requestToken();clock.instant=clock.instant.plus(Duration.ofMinutes(30));
  assertThrows(IllegalArgumentException.class,()->recovery.reset(expired,"Another-password!","Another-password!"));
 }
 @Test void unknownRecoveryAddressDoesNotSendMail(){recovery.request("unknown@example.invalid");verifyNoInteractions(sender);}
 @Test void recoveryMismatchDoesNotConsumeToken(){String token=requestToken();assertThrows(IllegalArgumentException.class,()->recovery.reset(token,"A-strong-new-password!","Mismatch"));recovery.reset(token,"A-strong-new-password!","A-strong-new-password!");}
 @Test void customerProfileAlwaysUpdatesAuthenticatedOwner()throws Exception {
  String otherName=userRepo.findById(other.getId()).orElseThrow().getName();
  mvc.perform(post("/customer/profile").with(user(customer.getEmail()).roles("CUSTOMER")).with(csrf()).param("id",other.getId().toString()).param("name","Updated owner").param("phone","12345").param("address","Test address")).andExpect(redirectedUrl("/customer/profile"));
  assertEquals("Updated owner",userRepo.findById(customer.getId()).orElseThrow().getName());assertEquals(otherName,userRepo.findById(other.getId()).orElseThrow().getName());
 }
 @Test void publicPackageDetailFiltersInactiveRecordsAndPrice()throws Exception {
  mvc.perform(get("/packages/"+tour.getId())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Choose date")));
  mvc.perform(get("/packages").param("maxPrice","0")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("No Packages Found")));
  tx.executeWithoutResult(s->packageRepo.findById(tour.getId()).orElseThrow().setActive(false));
  mvc.perform(get("/packages/"+tour.getId())).andExpect(status().isNotFound());
 }
}
