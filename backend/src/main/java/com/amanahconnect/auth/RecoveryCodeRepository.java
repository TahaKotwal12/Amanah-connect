package com.amanahconnect.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, UUID> {

    List<RecoveryCode> findByUserIdAndUsedAtIsNull(UUID userId);

    Optional<RecoveryCode> findByUserIdAndCodeHashAndUsedAtIsNull(UUID userId, String codeHash);

    long countByUserIdAndUsedAtIsNull(UUID userId);

    void deleteByUserId(UUID userId);
}
