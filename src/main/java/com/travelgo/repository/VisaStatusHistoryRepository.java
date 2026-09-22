package com.travelgo.repository;

import com.travelgo.entity.VisaStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VisaStatusHistoryRepository extends JpaRepository<VisaStatusHistory, Long> {
    boolean existsByVisaApplication_IdAndStatus(Long visaId, com.travelgo.enums.VisaStatus status);
    java.util.List<VisaStatusHistory> findByVisaApplication_IdOrderByChangedAtAsc(Long visaId);
}
