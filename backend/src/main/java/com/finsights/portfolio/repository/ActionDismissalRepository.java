package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.ActionDismissal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ActionDismissalRepository extends JpaRepository<ActionDismissal, String> {

    List<ActionDismissal> findByUser_Id(String userId);

    Optional<ActionDismissal> findByUser_IdAndActionKey(String userId, String actionKey);

    @Transactional
    long deleteByUser_Id(String userId);
}
