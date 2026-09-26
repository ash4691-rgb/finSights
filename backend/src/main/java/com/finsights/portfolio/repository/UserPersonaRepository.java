package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.UserPersona;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPersonaRepository extends JpaRepository<UserPersona, String> {
    Optional<UserPersona> findByUser_Id(String userId);
}
