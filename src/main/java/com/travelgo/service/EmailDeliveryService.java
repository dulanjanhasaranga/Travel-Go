package com.travelgo.service;
import com.travelgo.repository.OutboundEmailRepository;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class EmailDeliveryService {
 private final OutboundEmailRepository emails; private final ObjectProvider<JavaMailSender> sender; private final Clock clock; private final String from; private final boolean enabled;
 private final org.springframework.transaction.support.TransactionTemplate tx;
 public EmailDeliveryService(OutboundEmailRepository emails,ObjectProvider<JavaMailSender> sender,Clock clock,org.springframework.transaction.PlatformTransactionManager manager,@Value("${travelgo.mail.from:no-reply@travelgo.example}")String from,@Value("${travelgo.mail.enabled:false}")boolean enabled){this.emails=emails;this.sender=sender;this.clock=clock;this.from=from;this.enabled=enabled;this.tx=new org.springframework.transaction.support.TransactionTemplate(manager);this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
 public boolean isConfigured(){return enabled&&sender.getIfAvailable()!=null;}
 public void deliver(Long id){
  if(!isConfigured())return;
  var email=tx.execute(s->{var e=emails.lock(id).orElseThrow();if(!e.getStatus().equals("QUEUED")||e.getNextAttemptAt().isAfter(clock.instant()))return null;e.setStatus("SENDING");e.setAttempts(e.getAttempts()+1);return e;});
  if(email==null)return;
  // Commit the claim before SMTP. A crash leaves SENDING for staff reconciliation, never automatic resend.
  try{
   var mail=sender.getObject();var message=mail.createMimeMessage();var helper=new MimeMessageHelper(message,true,"UTF-8");
   helper.setFrom(from);helper.setTo(email.getRecipient());helper.setSubject(email.getSubject());helper.setText(email.getHtmlBody(),true);
   helper.addInline("travelgo-logo",new org.springframework.core.io.ClassPathResource("static/images/travelgo-logo.png"),"image/png");
   if(email.getAttachmentName() != null && email.getAttachmentData() != null) {
       helper.addAttachment(email.getAttachmentName(), new org.springframework.core.io.ByteArrayResource(email.getAttachmentData()));
   }
   message.saveChanges();message.setHeader("Message-ID","<travelgo-"+email.getId()+"@notifications.travelgo>");
   mail.send(message);tx.executeWithoutResult(s->{var e=emails.lock(id).orElseThrow();e.setStatus("SENT");e.setSentAt(clock.instant());});
  }catch(Exception failure){
   // Delivery errors can be ambiguous (server accepted but disconnected). Do not auto-resend.
   tx.executeWithoutResult(s->emails.lock(id).orElseThrow().setStatus("REVIEW_REQUIRED"));
   org.slf4j.LoggerFactory.getLogger(getClass()).warn("Email event {} requires delivery review",email.getId());
  }
 }
}
