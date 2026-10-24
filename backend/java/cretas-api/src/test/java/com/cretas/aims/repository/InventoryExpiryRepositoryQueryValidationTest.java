package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过期管控涉及的两条查询, 在真实 Hibernate + H2(PG 兼容) 上<b>执行</b>, 不只是解析:
 *
 * <ol>
 *   <li>{@code MaterialBatchRepository#findAvailableBatchesFEFOByWarehouseForUpdate} —— 正式报工自动分摊的
 *       候选顺序必须是真 FEFO: 到期日升序、无到期日最后, 同到期日再按转入顺序。此前它只按 createdAt 排,
 *       「B 先入库但后到期」时会先吃 B, 让先到期的 A 留在库里过期。</li>
 *   <li>{@code FinishedGoodsBatchRepository#findAllExpiredAvailableBatches} —— 成品过期定时任务的选择口径:
 *       只选「AVAILABLE 且到期日早于今天」, 到期日当天 / 无到期日 / 非 AVAILABLE 一律不选。</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("过期管控查询: 原料 FEFO 排序 + 成品过期选择口径")
class InventoryExpiryRepositoryQueryValidationTest {

    private static final String FACTORY = "F-EXPIRY-1";
    private static final String MATERIAL = "MT-EXPIRY-1";
    private static final String WORKSHOP = "WH-WKS-EXPIRY";

    @Autowired private MaterialBatchRepository materialBatchRepository;
    @Autowired private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void relaxFk() {
        // material_batches.created_by → users(id) 等外键: 只 seed 批次, 不建全依赖树 (同 MaterialBatchFefoWipExclusionTest)
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
    }

    @Test
    @DisplayName("FEFO: A 24 日到期、B 26 日到期、B 先入库 → 先推荐 A; 无到期日的排最后")
    void workshopFefoForUpdateOrdersByExpireDateThenTransferOrder() {
        // 入库(转入)顺序: NOEXP 最早, 然后 B, 最后 A —— 旧的 createdAt 排序会给出 NOEXP, B, A
        saveMaterial("NOEXP", null, LocalDateTime.of(2026, 10, 1, 8, 0));
        saveMaterial("B", LocalDate.of(2026, 10, 26), LocalDateTime.of(2026, 10, 2, 8, 0));
        saveMaterial("A", LocalDate.of(2026, 10, 24), LocalDateTime.of(2026, 10, 3, 8, 0));
        // 同到期日的两批: 按转入顺序 (createdAt) 定先后
        saveMaterial("A2-LATER", LocalDate.of(2026, 10, 24), LocalDateTime.of(2026, 10, 5, 8, 0));
        // ⚠️ 不 clear(): H2 读回 material_batches 的 jsonb 列会炸 ("{}" cannot be transformed),
        // 与 MaterialBatchFefoWipExclusionTest 一样走持久化上下文。排序由数据库按改过的 created_at 执行,
        // 不受实体缓存影响。

        List<MaterialBatch> result = materialBatchRepository
                .findAvailableBatchesFEFOByWarehouseForUpdate(FACTORY, MATERIAL, WORKSHOP);

        System.out.println("FEFO_ORDER=" + result.stream().map(MaterialBatch::getId).toList());
        assertThat(result).extracting(MaterialBatch::getId)
                .containsExactly("A", "A2-LATER", "B", "NOEXP");
    }

    @Test
    @DisplayName("成品过期选择: 只选 AVAILABLE 且到期日早于今天; 当天到期 / 无到期日 / 非 AVAILABLE 不选")
    void finishedGoodsExpiredSelectionUsesStrictlyBeforeToday() {
        LocalDate today = LocalDate.of(2026, 10, 24);
        persistFinished("YESTERDAY", "AVAILABLE", today.minusDays(1));
        persistFinished("TODAY", "AVAILABLE", today);
        persistFinished("TOMORROW", "AVAILABLE", today.plusDays(1));
        persistFinished("NO-DATE", "AVAILABLE", null);
        persistFinished("DEFECTIVE-OLD", "DEFECTIVE", today.minusDays(10));
        persistFinished("ALREADY-EXPIRED", "EXPIRED", today.minusDays(10));
        entityManager.flush();
        entityManager.clear();

        List<FinishedGoodsBatch> result = finishedGoodsBatchRepository.findAllExpiredAvailableBatches(today);

        assertThat(result).extracting(FinishedGoodsBatch::getId)
                .filteredOn(id -> id.startsWith("FGX-"))
                .containsExactly("FGX-YESTERDAY");
    }

    private void saveMaterial(String id, LocalDate expireDate, LocalDateTime createdAt) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("BN-" + id);
        batch.setMaterialTypeId(MATERIAL);
        batch.setReceiptDate(LocalDate.of(2026, 10, 1));
        batch.setWarehouseId(WORKSHOP);
        batch.setReceiptQuantity(new BigDecimal("10"));
        batch.setQuantityUnit("kg");
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setExpireDate(expireDate);
        batch.setCreatedBy(1L);
        materialBatchRepository.saveAndFlush(batch);
        // created_at 由审计监听器写入, 这里显式改成可控的转入顺序
        int updated = jdbcTemplate.update(
                "UPDATE material_batches SET created_at = ? WHERE id = ?", createdAt, id);
        assertThat(updated).isEqualTo(1);
    }

    private void persistFinished(String suffix, String status, LocalDate expireDate) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("FGX-" + suffix);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("FGX-" + suffix);
        batch.setProductTypeId("FGX-PRODUCT");
        batch.setProductName("Expiry fixture product");
        batch.setUnit("kg");
        batch.setWarehouseId(WORKSHOP);
        batch.setStatus(status);
        batch.setExpireDate(expireDate);
        batch.setProducedQuantity(new BigDecimal("10"));
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setOwnership(InventoryOwnership.COMPANY_OWNED);
        entityManager.persist(batch);
    }
}
