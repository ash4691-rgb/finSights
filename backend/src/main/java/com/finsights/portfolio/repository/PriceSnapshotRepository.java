package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.PriceSnapshot;
import com.finsights.portfolio.domain.SnapshotSubject;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshot, String> {
    Optional<PriceSnapshot> findFirstBySubjectTypeAndSubjectIdOrderByRecordedAtDesc(SnapshotSubject subjectType, String subjectId);

    Optional<PriceSnapshot> findFirstBySubjectTypeAndSubjectIdAndRecordedAtLessThanEqualOrderByRecordedAtDesc(
            SnapshotSubject subjectType, String subjectId, Instant cutoff);

    @Transactional
    long deleteBySubjectTypeAndSubjectId(SnapshotSubject subjectType, String subjectId);

    @Transactional
    long deleteByUser_Id(String userId);
}
