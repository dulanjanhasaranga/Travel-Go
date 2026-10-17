package com.travelgo.service;
import com.travelgo.entity.PasswordReset;
import com.travelgo.repository.*;
import java.time.*;
import java.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.travelgo.util.PasswordPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class AccountRecoveryService {
 private final UserRepository users;private final PasswordResetRepository resets;private final PasswordEncoder encoder;private final Clock clock;private final ObjectProvider<JavaMailSender> mail;
 private final String baseUrl,from;
 private final boolean mailEnabled;
 private final AccountEmailService accountEmails;
 private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AccountRecoveryService.class);
 public AccountRecoveryService(UserRepository users,PasswordResetRepository resets,PasswordEncoder encoder,Clock clock,ObjectProvider<JavaMailSender> mail,@Value("${travelgo.public-base-url:http://localhost:8081}") String baseUrl,@Value("${travelgo.mail.from:no-reply@travelgo.example}") String from,@Value("${travelgo.mail.enabled:false}") boolean mailEnabled,AccountEmailService accountEmails){this.users=users;this.resets=resets;this.encoder=encoder;this.clock=clock;this.mail=mail;this.baseUrl=baseUrl;this.from=from;this.mailEnabled=mailEnabled;this.accountEmails=accountEmails;}
 public boolean isConfigured(){return com.travelgo.util.MailConfigurationStatus.isConfigured(mailEnabled,mail.getIfAvailable());}
 @Transactional public void request(String email){
  if(!isConfigured())throw new IllegalStateException("Password reset is unavailable. Contact the agency for help.");
  if(email==null||email.isBlank()||email.length()>150)return;
  var found=users.findByEmailIgnoreCase(email.trim());if(found.isEmpty())return;
  // A browser-session limit alone can be bypassed by opening a new session.
  // Serialize requests for this account and retain the cooldown in its existing token records.
  var user=users.lockAccount(found.get().getId()).orElseThrow();if(!user.isActive())return;
  Instant now=clock.instant();
  var previous=resets.findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(user.getId());
  if(previous.isPresent()&&user.getPassword().equals(previous.get().getPasswordAtIssue())
      &&now.isBefore(previous.get().getExpiresAt().minusSeconds(1800).plusSeconds(60)))return;
  byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  var reset=new PasswordReset();reset.setTokenHash(hash(token));reset.setUser(user);reset.setExpiresAt(now.plusSeconds(1800));reset.setPasswordAtIssue(user.getPassword());resets.saveAndFlush(reset);
  logger.info("Created a password reset request for account {}", user.getId());
  accountEmails.recovery(user,token,reset.getTokenHash());
 }
 @Transactional public void reset(String token,String password,String confirmation){
  if(!PasswordPolicy.isValid(password)||!password.equals(confirmation))throw new IllegalArgumentException("Use at least 12 characters, shorten the password if it is too long, and enter the same password in both fields.");
  String tokenHash=hash(token);
  Long userId=resets.findUserIdByHash(tokenHash).orElseThrow(()->new IllegalArgumentException("This reset link is invalid or expired. Request a new one."));
  // Match the request path's account-then-token order; different reset links serialize without lock inversion.
  var user=users.lockAccount(userId).orElseThrow();
  var reset=resets.lockByHash(tokenHash).orElseThrow(()->new IllegalArgumentException("This reset link is invalid or expired. Request a new one."));
  if(reset.isUsed()||!clock.instant().isBefore(reset.getExpiresAt())||!user.isActive()||!user.getPassword().equals(reset.getPasswordAtIssue()))throw new IllegalArgumentException("This reset link is invalid or expired. Request a new one.");
  user.setPassword(encoder.encode(password));users.save(user);reset.setUsed(true);resets.save(reset);
 }
 private String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((token==null?"":token).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
