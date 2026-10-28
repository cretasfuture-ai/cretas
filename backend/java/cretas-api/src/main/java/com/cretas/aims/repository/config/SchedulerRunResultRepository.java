package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.SchedulerRunResult;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SchedulerRunResultRepository extends JpaRepository<SchedulerRunResult, Long> {

    Page<SchedulerRunResult> findByFactoryIdAndTaskCodeOrderByStartedAtDesc(
            String factoryId, String taskCode, Pageable pageable);

    Optional<SchedulerRunResult> findFirstByFactoryIdAndTaskCodeOrderByStartedAtDesc(
            String factoryId, String taskCode);

    Optional<SchedulerRunResult> findByRunIdAndFactoryId(String runId, String factoryId);

    /** Row-locked read for a ruling, so two people cannot rule on the same run at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM SchedulerRunResult r WHERE r.runId = :runId AND r.factoryId = :factoryId")
    Optional<SchedulerRunResult> findForUpdate(@Param("runId") String runId, @Param("factoryId") String factoryId);
}
