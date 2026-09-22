package com.travelgo.service;
import com.travelgo.entity.OutboundEmail;
import com.travelgo.repository.OutboundEmailRepository;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
@Service
public class TransactionalEmailService {
 private final OutboundEmailRepository emails; private final TemplateEngine templates; private final Clock clock;
 public TransactionalEmailService(OutboundEmailRepository emails,TemplateEngine templates,Clock clock){this.emails=emails;this.templates=templates;this.clock=clock;}
 // The business transaction owns the queue record; a rolled-back operation cannot leave an email.
 @Transactional(propagation=Propagation.MANDATORY)
  public void queue(String eventKey,String recipient,String subject,Map<String,Object> data){
    queueWithAttachment(eventKey, recipient, subject, data, null, null);
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public void queueWithAttachment(String eventKey,String recipient,String subject,Map<String,Object> data, String attachmentName, byte[] attachmentData){
   if(emails.existsByEventKey(eventKey))return;
   if(recipient==null||recipient.length()>150||!recipient.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))throw new IllegalStateException("The account email needs staff correction.");
   Context context=new Context();context.setVariables(data);
   OutboundEmail email=new OutboundEmail();email.setEventKey(eventKey);email.setRecipient(recipient);email.setSubject(subject);
   email.setHtmlBody(templates.process("email/transaction",context));email.setCreatedAt(clock.instant());email.setNextAttemptAt(clock.instant());
   if(attachmentName != null && attachmentData != null) {
       email.setAttachmentName(attachmentName);
       email.setAttachmentData(attachmentData);
   }
   emails.save(email);
  }
}
