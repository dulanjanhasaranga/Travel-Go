package com.travelgo.repository;

import com.travelgo.entity.Transport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransportRepository extends JpaRepository<Transport, Long> {
    List<Transport> findByIsActiveTrue();
    List<Transport> findByDestination_Id(Long destinationId);
}
