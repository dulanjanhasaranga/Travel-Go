package com.travelgo.repository;

import com.travelgo.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {
    boolean existsByBooking_Id(Long id);
    List<Review> findByBooking_Id(Long id);
    List<Review> findByTourPackage_Id(Long packageId);
    List<Review> findByUser_Id(Long userId);
}
