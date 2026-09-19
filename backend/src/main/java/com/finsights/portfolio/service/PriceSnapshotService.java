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
        recordAt(subjectType, subjectId, user, value, Instant.now());
    }

    /** As {@link #record}, but backdated — used to backfill real historical closes (e.g. from
     * Yahoo Finance's chart feed) so longer lookback windows (monthly and up) have a genuine
     * baseline immediately rather than only after months of live use. */
    public void recordAt(SnapshotSubject subjectType, String subjectId, UserAccount user, BigDecimal value, Instant recordedAt) {
        PriceSnapshot snapshot = new PriceSnapshot();
        snapshot.setUser(user);
        snapshot.setSubjectType(subjectType);
        snapshot.setSubjectId(subjectId);
        snapshot.setValue(value);
        snapshot.setRecordedAt(recordedAt);
        snapshots.save(snapshot);
    }

    /** Whether a snapshot already exists at or before {@code cutoff} — used to backfill history
     * only once per subject, rather than re-fetching it on every refresh. */
    public boolean hasSnapshotAtOrBefore(SnapshotSubject subjectType, String subjectId, Instant cutoff) {
        return snapshots.existsBySubjectTypeAndSubjectIdAndRecordedAtLessThanEqual(subjectType, subjectId, cutoff);
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
