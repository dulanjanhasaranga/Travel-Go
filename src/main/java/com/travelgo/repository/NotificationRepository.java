package com.travelgo.repository;

import com.travelgo.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long>, JpaSpecificationExecutor<Notification> {
    List<Notification> findByUser_IdOrderByCreatedAtDesc(Long userId);
    List<Notification> findTop5ByUser_IdOrderByCreatedAtDescIdDesc(Long userId);
    long countByUser_IdAndIsReadFalse(Long userId);
    Optional<Notification> findByIdAndUser_Id(Long id, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.isRead=true, n.readAt=:now where n.id=:id and n.user.id=:userId and n.isRead=false")
    int markRead(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.isRead=true, n.readAt=:now where n.user.id=:userId and n.isRead=false")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
