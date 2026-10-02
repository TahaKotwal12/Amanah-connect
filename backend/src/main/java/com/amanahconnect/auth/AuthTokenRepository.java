package com.amanahconnect.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    Optional<AuthToken> findByTokenHashAndPurpose(String tokenHash, AuthTokenPurpose purpose);

    long countByUserIdAndPurposeAndCreatedAtAfter(UUID userId, AuthTokenPurpose purpose, Instant since);

    /**
     * Atomically consumes a token. Returns 1 only for the single caller that wins the race, so a token
     * can never be used twice even under concurrent requests.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update AuthToken t set t.usedAt = :now"
                    + " where t.id = :id and t.usedAt is null and t.expiresAt > :now")
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    /** Invalidates every other unused token of this purpose for the user (e.g. after a reset). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update AuthToken t set t.usedAt = :now"
                    + " where t.user.id = :userId and t.purpose = :purpose and t.usedAt is null")
    int invalidateUnused(
            @Param("userId") UUID userId,
            @Param("purpose") AuthTokenPurpose purpose,
            @Param("now") Instant now);
}
