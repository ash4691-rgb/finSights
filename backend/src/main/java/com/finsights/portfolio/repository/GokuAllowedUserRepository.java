package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.GokuAllowedUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GokuAllowedUserRepository extends JpaRepository<GokuAllowedUser, String> {
    boolean existsByEmailIgnoreCase(String email);
    Optional<GokuAllowedUser> findByEmailIgnoreCase(String email);
    List<GokuAllowedUser> findAllByOrderByCreatedAtAsc();
    void deleteByEmailIgnoreCase(String email);
}
