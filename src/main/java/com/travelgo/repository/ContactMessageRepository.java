package com.travelgo.repository;

import com.travelgo.entity.ContactMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContactMessageRepository extends JpaRepository<ContactMessage, Long> {
 java.util.List<ContactMessage> findByUser_IdOrderByCreatedAtDesc(Long id);
 java.util.Optional<ContactMessage> findBySubmissionKey(String key);
 long countByStatus(com.travelgo.enums.ContactMessageStatus status);
}
