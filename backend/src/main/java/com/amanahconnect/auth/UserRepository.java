package com.amanahconnect.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Case-insensitive (the column is citext and the parameter is bound as citext). */
    Optional<User> findByEmail(String email);

    boolean existsByRole(UserRole role);
}
