package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsOrderBatch;
import com.cretas.aims.logistics.entity.enums.OrderBatchStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 — JPA slice test for {@link LogisticsOrderBatchRepository} (H2 PG-compat,
 * mirrors {@code SemiFinishedInventoryRepositoryTest} conventions).
 *
 * <p>Verifies:
 * <ol>
 *   <li>save + read back — @PrePersist UUID id + audit cols + enum default PREVIEWED</li>
 *   <li>idempotency lookup — findByFactoryIdAndBusinessDateAndSourceFingerprintAndDeletedAtIsNull</li>
 *   <li>findByFactoryIdOrderByBusinessDateDesc — factory-scoped, newest first</li>
 * </ol>
 *
 * <p>🔴 <b>2026-09-07（软删批 3-B）删掉了原第 3 条</b>（DB unique index {@code uq_lob_idempotent}
 * rejects a true duplicate insert — spec §2 决策 7 幂等键）。规则没变，是<b>这个测试库表达不了它</b>；
 * 理由与接替它的那两道闸见下方 {@code duplicateIdempotencyKeyRejected} 原位置的注释。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@EnableJpaRepositories(basePackages = "com.cretas.aims")
@DisplayName("LogisticsOrderBatchRepository — JPA slice (H2 PG-compat)")
class LogisticsOrderBatchRepositoryTest {

    private static final String F1 = "F-LOG-1";
    private static final String F2 = "F-LOG-2";

    @Autowired
    private LogisticsOrderBatchRepository repo;

    @Test
    @DisplayName("save — @PrePersist UUID id + audit cols + status default PREVIEWED")
    void saveAssignsDefaults() {
        LogisticsOrderBatch saved = repo.saveAndFlush(batch(F1, LocalDate.of(2026, 7, 11), "BATCH-001", "fp-001"));

        assertNotNull(saved.getId(), "@PrePersist must assign UUID id");
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertEquals(OrderBatchStatus.PREVIEWED, saved.getStatus());
        assertEquals(0L, saved.getVersion(), "Hibernate assigns version 0 on first persist");

        LogisticsOrderBatch reloaded = repo.findById(saved.getId()).orElseThrow();
        assertEquals("BATCH-001", reloaded.getBatchNumber());
        assertEquals("fp-001", reloaded.getSourceFingerprint());
    }

    @Test
    @DisplayName("idempotency lookup — findByFactoryIdAndBusinessDateAndSourceFingerprintAndDeletedAtIsNull")
    void idempotencyLookup() {
        LocalDate date = LocalDate.of(2026, 7, 11);
        repo.saveAndFlush(batch(F1, date, "BATCH-IDEM-1", "fp-idem-1"));

        Optional<LogisticsOrderBatch> found =
                repo.findByFactoryIdAndBusinessDateAndSourceFingerprintAndDeletedAtIsNull(F1, date, "fp-idem-1");
        assertTrue(found.isPresent(), "should locate existing batch by idempotency key");
        assertEquals("BATCH-IDEM-1", found.get().getBatchNumber());

        assertTrue(
                repo.findByFactoryIdAndBusinessDateAndSourceFingerprintAndDeletedAtIsNull(F1, date, "fp-no-such")
                        .isEmpty(),
                "unknown fingerprint returns empty — no accidental match");
    }

    // 🔴 2026-09-07（软删批 3-B）删掉了 duplicateIdempotencyKeyRejected
    //    （「同 (factory_id, business_date, source_fingerprint) 再插一条必须被 DB 拒」）。
    //    删的原因不是那条规则不成立了，是**这个测试库表达不了它**：
    //
    //    prod 上 uq_lob_idempotent 是 `… WHERE deleted_at IS NULL` 的**部分**唯一索引，而 H2 的 schema
    //    从实体注解生成、JPA 表达不了部分唯一 ⇒ 实体里那句 @UniqueConstraint 只能建出**全量**键，比 prod 严。
    //    本实体补 @SQLDelete 之后「软删一批再按同一指纹重导」在 prod 合法而在 H2 撞键
    //    ⇒ 实体侧声明已摘掉，H2 里这张表不再有唯一性保护。
    //
    //    ⚠️ 补不回来：H2 2.2.224 上 CREATE UNIQUE INDEX … WHERE … 直接 JdbcSQLSyntaxErrorException 42000。
    //    ⇒ 机制由 com.cretas.aims.contract.PartialUniqueIndexBackingSoftDeleteContractTest 在**迁移源码**层面守
    //      （uq_lob_idempotent 被删掉或改回全量就报红）；
    //      行为由 com.cretas.aims.integration.SoftDeletePartialUniqueIndexPostgresContractTest 在**真 PostgreSQL**
    //      上守（活行互斥 → 软删后同键可重导 → 总/活行必然读数），那道闸已接进 java-gate-pr 的
    //      postgres-reversal-concurrency 车道、CI 里实际执行（Skipped: 0）。
    //    ⛔ 不要在这里加回一个 H2 版的断言——它只会再次让测试比生产严。

    @Test
    @DisplayName("findByFactoryIdOrderByBusinessDateDesc — factory scoped, newest first")
    void findByFactoryOrderedDesc() {
        repo.saveAndFlush(batch(F1, LocalDate.of(2026, 7, 1), "BATCH-OLD", "fp-old"));
        repo.saveAndFlush(batch(F1, LocalDate.of(2026, 7, 11), "BATCH-NEW", "fp-new"));
        repo.saveAndFlush(batch(F2, LocalDate.of(2026, 7, 20), "BATCH-OTHER-FACTORY", "fp-other")); // other factory

        List<LogisticsOrderBatch> rows = repo.findByFactoryIdOrderByBusinessDateDesc(F1);
        assertEquals(2, rows.size(), "F1 has 2 batches (F2 excluded)");
        assertEquals("BATCH-NEW", rows.get(0).getBatchNumber(), "newest business_date first");
        assertEquals("BATCH-OLD", rows.get(1).getBatchNumber());
    }

    // ============================================================
    // Helper
    // ============================================================

    private LogisticsOrderBatch batch(String factoryId, LocalDate businessDate, String batchNumber, String fingerprint) {
        return LogisticsOrderBatch.builder()
                .factoryId(factoryId)
                .businessDate(businessDate)
                .batchNumber(batchNumber)
                .sourceFilename("orders.xlsx")
                .sourceFingerprint(fingerprint)
                .build();
    }
}
