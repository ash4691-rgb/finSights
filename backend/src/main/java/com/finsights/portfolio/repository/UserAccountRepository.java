package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.UserAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, String> {
    Optional<UserAccount> findByEmail(String email);
    Optional<UserAccount> findByVerificationToken(String verificationToken);
    /** Typeahead lookup for the Goku admin panel — capped at 10 so a one-character query can't dump the table. */
    List<UserAccount> findTop10ByEmailContainingIgnoreCaseOrderByEmailAsc(String emailFragment);
}

