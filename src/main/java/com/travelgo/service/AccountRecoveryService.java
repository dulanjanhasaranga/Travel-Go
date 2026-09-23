package com.travelgo.service;
import com.travelgo.entity.PasswordReset;
import com.travelgo.repository.*;
import java.time.*;
import java.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class AccountRecoveryService {
 private final UserRepository users;private final PasswordResetRepository resets;private final PasswordEncoder encoder;private final Clock clock;private final ObjectProvider<JavaMailSender> mail;
 private final String baseUrl,from;
 private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AccountRecoveryService.class);
 public AccountRecoveryService(UserRepository users,PasswordResetRepository resets,PasswordEncoder encoder,Clock clock,ObjectProvider<JavaMailSender> mail,@Value("${travelgo.public-base-url:http://localhost:8080}") String baseUrl,@Value("${travelgo.mail.from:no-reply@travelgo.example}") String from){this.users=users;this.resets=resets;this.encoder=encoder;this.clock=clock;this.mail=mail;this.baseUrl=baseUrl;this.from=from;}
 public boolean isConfigured(){return mail.getIfAvailable()!=null;}
 @Transactional public void request(String email){
  var sender=mail.getIfAvailable();if(sender==null)throw new IllegalStateException("Email recovery is not configured. Contact the travel team for account assistance.");
  var user=users.findByEmailIgnoreCase(email.trim());if(user.isEmpty()||!user.get().isActive())return;
  byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  var reset=new PasswordReset();reset.setTokenHash(hash(token));reset.setUser(user.get());reset.setExpiresAt(clock.instant().plusSeconds(1800));reset.setPasswordAtIssue(user.get().getPassword());resets.saveAndFlush(reset);
  logger.info("Password reset token for {}: {}", email, token);
  var message=new SimpleMailMessage();message.setFrom(from);message.setTo(user.get().getEmail());message.setSubject("Reset your TravelGO password");message.setText("Use this link within 30 minutes to choose a new password:\n"+baseUrl+"/auth/reset-password?token="+token+"\nIf you did not request this change, ignore this email.");sender.send(message);
 }
 @Transactional public void reset(String token,String password,String confirmation){
  if(password==null||password.length()<12||password.length()>72||!password.equals(confirmation))throw new IllegalArgumentException("Use 12–72 characters and enter the same password twice.");
  var reset=resets.lockByHash(hash(token)).orElseThrow(()->new IllegalArgumentException("This reset link is invalid or expired. Request a new one."));
  // Lock the account as well: different reset links for one user must serialize.
  var user=users.lockAccount(reset.getUser().getId()).orElseThrow();
  if(reset.isUsed()||!clock.instant().isBefore(reset.getExpiresAt())||!user.isActive()||!user.getPassword().equals(reset.getPasswordAtIssue()))throw new IllegalArgumentException("This reset link is invalid or expired. Request a new one.");
  user.setPassword(encoder.encode(password));users.save(user);reset.setUsed(true);resets.save(reset);
 }
 private String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((token==null?"":token).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
