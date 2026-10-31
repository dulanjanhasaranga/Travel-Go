package com.travelgo.repository;

import com.travelgo.entity.PackageImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PackageImageRepository extends JpaRepository<PackageImage, Long> {
    List<PackageImage> findByTourPackage_IdOrderByDisplayOrderAsc(Long packageId);
}
