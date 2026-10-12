package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.PendingBrokerHolding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface PendingBrokerHoldingRepository extends JpaRepository<PendingBrokerHolding, String> {
    List<PendingBrokerHolding> findByUser_Id(String userId);
    Optional<PendingBrokerHolding> findByIdAndUser_Id(String id, String userId);
    Optional<PendingBrokerHolding> findByUser_IdAndBrokerSyncRef(String userId, String brokerSyncRef);

    @Transactional
    long deleteByUser_Id(String userId);
}
