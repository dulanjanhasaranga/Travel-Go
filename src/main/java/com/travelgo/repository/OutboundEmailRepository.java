package com.travelgo.repository;
import com.travelgo.entity.OutboundEmail;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
public interface OutboundEmailRepository extends JpaRepository<OutboundEmail,Long> {
 boolean existsByEventKey(String key);
 Optional<OutboundEmail> findByEventKey(String key);
 List<OutboundEmail> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderById(String status,Instant now);
 List<OutboundEmail> findTop20ByStatusAndEventKeyStartingWithAndNextAttemptAtLessThanEqualOrderById(String status,String prefix,Instant now);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select e from OutboundEmail e where e.id=:id")
 Optional<OutboundEmail> lock(@Param("id") Long id);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select e from OutboundEmail e where e.feedbackReference=:reference")
 Optional<OutboundEmail> lockByFeedbackReference(@Param("reference") String reference);
 long countByStatus(String status);
 /** Metadata only: do not load bearer links or attachment bytes into administrative lists. */
 @Query("select e.id as id, e.eventKey as eventKey, e.recipient as recipient, e.subject as subject, "
     + "e.status as status, e.createdAt as createdAt, e.sentAt as sentAt, e.attempts as attempts, "
     + "e.recipientDeliveryStatus as recipientDeliveryStatus, e.recipientDeliveryUpdatedAt as recipientDeliveryUpdatedAt, "
     + "e.recipientFeedbackReceivedAt as recipientFeedbackReceivedAt, e.providerMessageId as providerMessageId "
     + "from OutboundEmail e where (:status is null or e.status=:status "
     + "or (:status='REVIEW_REQUIRED' and e.status='REVIEW_REQUIRED_CONFLICT'))")
 org.springframework.data.domain.Page<DeliveryMetadata> findDeliveryMetadata(@Param("status") String status,
     org.springframework.data.domain.Pageable pageable);
 interface DeliveryMetadata {
  Long getId(); String getEventKey(); String getRecipient(); String getSubject(); String getStatus();
  String getRecipientDeliveryStatus(); Instant getRecipientDeliveryUpdatedAt(); Instant getRecipientFeedbackReceivedAt();
  String getProviderMessageId();
  Instant getCreatedAt(); Instant getSentAt(); int getAttempts();
 }
}

