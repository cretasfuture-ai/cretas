package com.cretas.aims.repository.integration;

import com.cretas.aims.entity.integration.ExternalSourceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/** 外部系统只读快照。⛔ 每个查询都带 factoryId + system: 跨厂读不到。 */
public interface ExternalSourceSnapshotRepository extends JpaRepository<ExternalSourceSnapshot, Long> {

    Optional<ExternalSourceSnapshot> findFirstByFactoryIdAndSystemAndDatasetOrderByCollectedAtDesc(
            String factoryId, String system, String dataset);

    Optional<ExternalSourceSnapshot> findFirstByFactoryIdAndSystemOrderByCollectedAtDesc(String factoryId, String system);

    Optional<ExternalSourceSnapshot> findByFactoryIdAndSystemAndDatasetAndCollectedAt(
            String factoryId, String system, String dataset, LocalDateTime collectedAt);
}
