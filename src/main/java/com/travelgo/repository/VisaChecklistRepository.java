package com.travelgo.repository;

import com.travelgo.entity.VisaChecklist;
import com.travelgo.enums.VisaType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VisaChecklistRepository extends JpaRepository<VisaChecklist, Long> {
    List<VisaChecklist> findAllByOrderByCreatedAtDescIdDesc();
    List<VisaChecklist> findByDestination_IdAndVisaTypeAndStatus(Long destinationId, VisaType type, VisaChecklist.Status status);
    Optional<VisaChecklist> findFirstByDestination_IdAndVisaTypeOrderByRevisionDesc(Long destinationId, VisaType type);
}
