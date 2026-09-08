package com.ojt.board.auth.oauth;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    @EntityGraph(attributePaths = "user")
    Optional<OAuthAccount> findByProviderAndSubject(String provider, String subject);
}
