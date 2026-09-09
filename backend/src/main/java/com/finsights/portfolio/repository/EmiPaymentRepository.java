package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.EmiPayment;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface EmiPaymentRepository extends JpaRepository<EmiPayment, String> {

    List<EmiPayment> findByUser_Id(String userId);

    List<EmiPayment> findByHolding_Id(String holdingId);

    Optional<EmiPayment> findByHolding_IdAndPeriod(String holdingId, LocalDate period);

    @Transactional
    long deleteByHolding_IdAndPeriod(String holdingId, LocalDate period);

    @Transactional
    long deleteByHolding_Id(String holdingId);

    @Transactional
    long deleteByUser_Id(String userId);
}
