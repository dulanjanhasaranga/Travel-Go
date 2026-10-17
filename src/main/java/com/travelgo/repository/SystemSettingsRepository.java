package com.travelgo.repository;

import com.travelgo.entity.SystemSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for SystemSettings entity.
 * Only one row should exist. Preserve its generated ID when restoring records.
 */
@Repository
public interface SystemSettingsRepository extends JpaRepository<SystemSettings, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select settings from SystemSettings settings order by settings.id")
    java.util.List<SystemSettings> lockSettings();
    java.util.Optional<SystemSettings> findFirstByOrderByIdAsc();
    // Uses default JpaRepository methods.
    // Existing records keep their IDs; mutations lock and validate the singleton row.
}
