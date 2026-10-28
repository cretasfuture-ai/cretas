package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.SchedulerRunResolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SchedulerRunResolutionRepository extends JpaRepository<SchedulerRunResolution, Long> {
    Optional<SchedulerRunResolution> findByRunIdAndFactoryId(String runId, String factoryId);
}
