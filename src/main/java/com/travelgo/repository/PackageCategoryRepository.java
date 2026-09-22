package com.travelgo.repository;

import com.travelgo.entity.PackageCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PackageCategoryRepository extends JpaRepository<PackageCategory, Long> {
}
