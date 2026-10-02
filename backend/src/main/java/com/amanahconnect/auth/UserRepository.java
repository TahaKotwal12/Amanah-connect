package com.amanahconnect.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Case-insensitive (the column is citext and the parameter is bound as citext). */
    Optional<User> findByEmail(String email);

    boolean existsByRole(UserRole role);

    boolean existsByIdAndRoleAndStatus(UUID id, UserRole role, UserStatus status);

    java.util.List<User> findByRoleAndStatus(UserRole role, UserStatus status);

    /**
     * Row-locks the user. Login and 2FA take this lock so that concurrent guesses against one account
     * are serialised and every failure is counted (no lost updates on failed_attempts).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
