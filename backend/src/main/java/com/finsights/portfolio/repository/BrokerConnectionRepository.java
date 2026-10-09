package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.BrokerConnection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrokerConnectionRepository extends JpaRepository<BrokerConnection, String> {
    Optional<BrokerConnection> findByUser_IdAndBrokerKey(String userId, String brokerKey);

    /** Every user's connection for one broker — what BrokerSyncScheduler iterates daily. */
    List<BrokerConnection> findAllByBrokerKey(String brokerKey);
}
