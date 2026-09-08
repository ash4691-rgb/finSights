package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.PriceSnapshot;
import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.PriceSnapshotRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Records and reads the value-over-time trail behind Insights' Hot picks feature. */
@Service
public class PriceSnapshotService {
    private final PriceSnapshotRepository snapshots;

    public PriceSnapshotService(PriceSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    public void record(SnapshotSubject subjectType, String subjectId, UserAccount user, BigDecimal value) {
        PriceSnapshot snapshot = new PriceSnapshot();
        snapshot.setUser(user);
        snapshot.setSubjectType(subjectType);
        snapshot.setSubjectId(subjectId);
        snapshot.setValue(value);
        snapshots.save(snapshot);
    }

    public BigDecimal latest(SnapshotSubject subjectType, String subjectId) {
        return snapshots.findFirstBySubjectTypeAndSubjectIdOrderByRecordedAtDesc(subjectType, subjectId)
                .map(PriceSnapshot::getValue).orElse(null);
    }

    public Instant latestRecordedAt(SnapshotSubject subjectType, String subjectId) {
        return snapshots.findFirstBySubjectTypeAndSubjectIdOrderByRecordedAtDesc(subjectType, subjectId)
                .map(PriceSnapshot::getRecordedAt).orElse(null);
    }

    /** The best available "value back then" — the latest snapshot recorded at or before the cutoff. */
    public BigDecimal closestAtOrBefore(SnapshotSubject subjectType, String subjectId, Instant cutoff) {
        return snapshots.findFirstBySubjectTypeAndSubjectIdAndRecordedAtLessThanEqualOrderByRecordedAtDesc(subjectType, subjectId, cutoff)
                .map(PriceSnapshot::getValue).orElse(null);
    }

    public void deleteFor(SnapshotSubject subjectType, String subjectId) {
        snapshots.deleteBySubjectTypeAndSubjectId(subjectType, subjectId);
    }

    public void deleteForUser(String userId) {
        snapshots.deleteByUser_Id(userId);
    }
}
