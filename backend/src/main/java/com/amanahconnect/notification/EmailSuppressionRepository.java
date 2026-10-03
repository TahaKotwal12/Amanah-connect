package com.amanahconnect.notification;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Global (not tenant) table: the address list is shared by every community. */
public interface EmailSuppressionRepository extends Repository<EmailSuppression, UUID> {

    <S extends EmailSuppression> S save(S suppression);

    Optional<EmailSuppression> findByEmail(String email);

    List<EmailSuppression> findByEmailIn(Collection<String> emails);

    boolean existsByEmail(String email);

    long count();

    void delete(EmailSuppression suppression);

    org.springframework.data.domain.Page<EmailSuppression> findAll(org.springframework.data.domain.Pageable pageable);
}
