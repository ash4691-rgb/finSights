package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.UserPersona;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface UserPersonaRepository extends JpaRepository<UserPersona, String> {
    Optional<UserPersona> findByUser_Id(String userId);

    @Transactional
    long deleteByUser_Id(String userId);
}
