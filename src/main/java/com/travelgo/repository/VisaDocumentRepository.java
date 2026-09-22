package com.travelgo.repository;

import com.travelgo.entity.VisaDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VisaDocumentRepository extends JpaRepository<VisaDocument, Long> {
    List<VisaDocument> findByVisaApplication_Id(Long visaApplicationId);
}
