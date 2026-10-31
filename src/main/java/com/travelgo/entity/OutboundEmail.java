package com.travelgo.entity;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
@Table(name="outbound_emails")
public class OutboundEmail {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,unique=true,length=120) private String eventKey;
 @Column(nullable=false,length=150) private String recipient;
 @Column(nullable=false) private String subject;
 @Column(nullable=false,columnDefinition="TEXT") private String htmlBody;
 @Column(nullable=false) private String status="QUEUED";
 // Nullable additive fields keep existing outbox records unchanged. Correlation is opaque, never a row ID.
 @Column(unique=true,length=64) private String feedbackReference;
 @Column(length=30) private String recipientDeliveryStatus;
 private Instant recipientDeliveryUpdatedAt;
 private Instant recipientFeedbackReceivedAt;
 @Column(length=255) private String providerMessageId;
 public String getFeedbackReference(){return feedbackReference;} public void setFeedbackReference(String v){feedbackReference=v;}
 public String getRecipientDeliveryStatus(){return recipientDeliveryStatus;} public void setRecipientDeliveryStatus(String v){recipientDeliveryStatus=v;}
 public Instant getRecipientDeliveryUpdatedAt(){return recipientDeliveryUpdatedAt;} public void setRecipientDeliveryUpdatedAt(Instant v){recipientDeliveryUpdatedAt=v;}
 public Instant getRecipientFeedbackReceivedAt(){return recipientFeedbackReceivedAt;} public void setRecipientFeedbackReceivedAt(Instant v){recipientFeedbackReceivedAt=v;}
 public String getProviderMessageId(){return providerMessageId;} public void setProviderMessageId(String v){providerMessageId=v;}
 private Instant createdAt; private Instant sentAt; private Instant nextAttemptAt; private int attempts;
 public Long getId(){return id;} public String getEventKey(){return eventKey;} public void setEventKey(String v){eventKey=v;}
 public String getRecipient(){return recipient;} public void setRecipient(String v){recipient=v;}
 public String getSubject(){return subject;} public void setSubject(String v){subject=v;}
 public String getHtmlBody(){return htmlBody;} public void setHtmlBody(String v){htmlBody=v;}
 public String getStatus(){return status;} public void setStatus(String v){status=v;}
 public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
 public Instant getSentAt(){return sentAt;} public void setSentAt(Instant v){sentAt=v;}
 public Instant getNextAttemptAt(){return nextAttemptAt;} public void setNextAttemptAt(Instant v){nextAttemptAt=v;}
 public int getAttempts(){return attempts;} public void setAttempts(int v){attempts=v;}
 @Column(length=255) private String attachmentName;
 @Lob @Column(columnDefinition="LONGBLOB") private byte[] attachmentData;
 public String getAttachmentName(){return attachmentName;} public void setAttachmentName(String v){attachmentName=v;}
 public byte[] getAttachmentData(){return attachmentData;} public void setAttachmentData(byte[] v){attachmentData=v;}
}
