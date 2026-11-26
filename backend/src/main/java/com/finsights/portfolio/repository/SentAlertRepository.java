package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.SentAlert;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface SentAlertRepository extends JpaRepository<SentAlert, String> {
    @Query("select s.alertKey from SentAlert s where s.userId = :userId")
    List<String> findKeysByUserId(String userId);

    @Modifying
    @Transactional
    @Query("delete from SentAlert s where s.userId = :userId")
    void deleteByUserId(String userId);

    @Modifying
    @Transactional
    @Query("delete from SentAlert s where s.sentAt < :cutoff")
    void deleteOlderThan(Instant cutoff);
}
