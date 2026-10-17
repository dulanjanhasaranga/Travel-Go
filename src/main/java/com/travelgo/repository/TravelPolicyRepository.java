package com.travelgo.repository;
import com.travelgo.entity.TravelPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface TravelPolicyRepository extends JpaRepository<TravelPolicy,Long> {
    Optional<TravelPolicy> findFirstByPublishedTrueOrderByPublishedAtDescIdDesc();
    List<TravelPolicy> findAllByOrderByIdDesc();
}
