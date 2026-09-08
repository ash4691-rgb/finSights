package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.Transaction;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findByUser_IdOrderByDateDescCreatedAtDesc(String userId);
    List<Transaction> findByHolding_IdOrderByDateAscCreatedAtAsc(String holdingId);
    boolean existsByHolding_Id(String holdingId);

    @Query("select distinct t.holding.id from Transaction t where t.user.id = ?1")
    Set<String> findHoldingIdsWithTransactions(String userId);
    Optional<Transaction> findByIdAndUser_Id(String id, String userId);

    @Transactional
    long deleteByUser_Id(String userId);

    @Transactional
    long deleteByHolding_Id(String holdingId);
}
