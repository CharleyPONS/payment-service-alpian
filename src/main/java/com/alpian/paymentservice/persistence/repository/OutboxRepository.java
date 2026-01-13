package com.alpian.paymentservice.persistence.repository;

import com.alpian.paymentservice.persistence.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEventEntity, UUID> {

    @Query(value = """
    SELECT *
    FROM outbox_event
    WHERE event_type = :eventType
      AND (
            event_status = 'PENDING'
         OR (event_status = 'PROCESSING'
             AND processing_started_at IS NOT NULL
             AND processing_started_at < (NOW() - (:timeoutSeconds || ' seconds')::interval)
            )
      )
    ORDER BY created_at
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
    List<OutboxEventEntity> lockNextBatchRecoverable(
            @Param("eventType") String eventType,
            @Param("batchSize") int batchSize,
            @Param("timeoutSeconds") long timeoutSeconds
    );

    @Modifying
    @Query(value = """
        UPDATE outbox_event
        SET event_status = 'PROCESSING',
            attempt_count = attempt_count + 1,
            processing_started_at = NOW()
        WHERE id IN (:ids)
        """, nativeQuery = true)
    int markProcessingAndIncrementAttempt(@Param("ids") List<UUID> ids);

    @Modifying
    @Query(value = """
        UPDATE outbox_event
        SET event_status='SENT',
        processed_at=:processedAt,
        processing_started_at=NULL
        WHERE id=:id
        """, nativeQuery = true)
    int updateStatusSent(@Param("id") UUID id, @Param("processedAt") Instant processedAt);

    @Modifying
    @Query(value = """
        UPDATE outbox_event
        SET event_status='FAILED',
        attempt_count=:attemptCount,
        last_error=:lastError,
        processed_at=:processedAt,
        processing_started_at=NULL
        WHERE id=:id
        """, nativeQuery = true)
    int updateStatusFailed(@Param("id") UUID id,
                           @Param("attemptCount") int attemptCount,
                           @Param("lastError") String lastError,
                           @Param("processedAt") Instant processedAt);

    @Modifying
    @Query(value = """
        UPDATE outbox_event
        SET event_status='PENDING',
        attempt_count=:attemptCount,
        last_error=:lastError,
        processing_started_at=NULL
        WHERE id=:id
        """, nativeQuery = true)
    int updateStatusRetry(@Param("id") UUID id,
                          @Param("attemptCount") int attemptCount,
                          @Param("lastError") String lastError);

}

