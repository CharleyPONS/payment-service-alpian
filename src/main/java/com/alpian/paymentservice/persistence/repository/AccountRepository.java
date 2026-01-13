package com.alpian.paymentservice.persistence.repository;

import com.alpian.paymentservice.persistence.entity.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = """
        SELECT a
        FROM AccountEntity a
        WHERE a.id = :accountId AND a.userId = :userId
    """)
    Optional<AccountEntity> findForUpdate(UUID accountId, UUID userId);
}
