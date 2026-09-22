package com.travelgo.repository;

import com.travelgo.entity.SystemSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for SystemSettings entity.
 * Only one row should exist (id = 1).
 */
@Repository
public interface SystemSettingsRepository extends JpaRepository<SystemSettings, Long> {
    // Uses default JpaRepository methods.
    // findById(1L) will be used to get/update the singleton settings row.
}
