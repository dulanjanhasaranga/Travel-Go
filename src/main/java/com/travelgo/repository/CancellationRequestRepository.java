package com.travelgo.repository;
import com.travelgo.entity.CancellationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CancellationRequestRepository extends JpaRepository<CancellationRequest,Long>{
    boolean existsByBooking_Id(Long bookingId);
    List<CancellationRequest> findByBooking_IdOrderByIdDesc(Long id);
    List<CancellationRequest> findAllByOrderByIdDesc();
}
