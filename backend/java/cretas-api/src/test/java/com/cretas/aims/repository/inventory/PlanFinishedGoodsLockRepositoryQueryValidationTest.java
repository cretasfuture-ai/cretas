package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q2/Q4 (2026-11-09) 质检处置放行成品用到的两条查询在真实映射上跑 (H2; 设了 CRETAS_WORKFLOW_PG_URL 时在 PG 上跑):
 * <ul>
 *   <li>{@code findByFactoryIdAndProductionPlanIdForUpdate}: 只锁本厂本计划未删除的成品, 按 id 排序 (并发方同序拿锁);</li>
 *   <li>{@code BatchLineageEdgeRepository.findByFactoryIdAndTargetIdIn}: 成品归属读血缘边, 别厂的边不算。</li>
 * </ul>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class PlanFinishedGoodsLockRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            String bound = url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    @Autowired private FinishedGoodsBatchRepository goods;
    @Autowired private BatchLineageEdgeRepository edges;
    @Autowired private EntityManager entityManager;

    @Test
    void planLockReturnsOnlyThisFactoryAndPlanLiveRowsInIdOrder() {
        seedFactory("F-LOCK", "PT-LOCK");
        seedFactory("F-OTHER", null);
        goods.saveAndFlush(fg("Q-LOCK-B", "F-LOCK", "PLAN-LOCK"));
        goods.saveAndFlush(fg("Q-LOCK-A", "F-LOCK", "PLAN-LOCK"));
        goods.saveAndFlush(fg("Q-LOCK-OTHER-PLAN", "F-LOCK", "PLAN-OTHER"));
        goods.saveAndFlush(fg("Q-LOCK-OTHER-FACTORY", "F-OTHER", "PLAN-LOCK"));
        FinishedGoodsBatch deleted = fg("Q-LOCK-DELETED", "F-LOCK", "PLAN-LOCK");
        deleted.setDeletedAt(LocalDateTime.of(2026, 11, 9, 8, 0));
        goods.saveAndFlush(deleted);
        entityManager.clear();

        List<FinishedGoodsBatch> locked = goods.findByFactoryIdAndProductionPlanIdForUpdate("F-LOCK", "PLAN-LOCK");

        assertThat(locked).extracting(FinishedGoodsBatch::getId).containsExactly("Q-LOCK-A", "Q-LOCK-B");
    }

    /** Owner 2026-11-09: 「成品仍在隔离的已放行批次」列表的数据源 —— 只要本厂、挂在计划上、DEFECTIVE、未删除的。 */
    @Test
    void quarantinedPlanGoodsAreThisFactoryDefectiveAndPlanBound() {
        seedFactory("F-LOCK", "PT-LOCK");
        seedFactory("F-OTHER", null);
        goods.saveAndFlush(fg("Q-HELD-1", "F-LOCK", "PLAN-HELD"));
        FinishedGoodsBatch available = fg("Q-HELD-AVAILABLE", "F-LOCK", "PLAN-HELD");
        available.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        goods.saveAndFlush(available);
        goods.saveAndFlush(fg("Q-HELD-NO-PLAN", "F-LOCK", null));
        goods.saveAndFlush(fg("Q-HELD-OTHER-FACTORY", "F-OTHER", "PLAN-HELD"));
        entityManager.clear();

        assertThat(goods.findQuarantinedPlanGoods("F-LOCK"))
                .extracting(FinishedGoodsBatch::getId).containsExactly("Q-HELD-1");
    }

    @Test
    void lineageLookupByFinishedGoodsIsFactoryScoped() {
        edges.saveAndFlush(edge("F-LOCK", "101", "Q-EDGE-1"));
        edges.saveAndFlush(edge("F-OTHER", "202", "Q-EDGE-1"));
        entityManager.clear();

        assertThat(edges.findByFactoryIdAndTargetIdIn("F-LOCK", List.of("Q-EDGE-1", "Q-EDGE-NONE")))
                .extracting(BatchLineageEdge::getSourceId).containsExactly("101");
    }

    private void seedFactory(String factoryId, String productId) {
        com.cretas.aims.entity.Factory factory = new com.cretas.aims.entity.Factory();
        factory.setId(factoryId);
        factory.setName("Lock fixture " + factoryId);
        entityManager.persist(factory);
        if (productId == null) {
            return;
        }
        com.cretas.aims.entity.User creator = new com.cretas.aims.entity.User();
        creator.setFactoryId(factoryId);
        creator.setUsername("lock-fixture-" + factoryId);
        creator.setPasswordHash("not-used");
        entityManager.persist(creator);
        com.cretas.aims.entity.ProductType product = new com.cretas.aims.entity.ProductType();
        product.setId(productId);
        product.setFactoryId(factoryId);
        product.setCode(productId);
        product.setName("Lock fixture product");
        product.setUnit("kg");
        product.setCreatedBy(creator.getId());
        entityManager.persist(product);
        entityManager.flush();
    }

    private static FinishedGoodsBatch fg(String id, String factoryId, String planId) {
        FinishedGoodsBatch row = new FinishedGoodsBatch();
        row.setId(id);
        row.setFactoryId(factoryId);
        row.setBatchNumber(id);
        row.setProductTypeId("PT-LOCK");
        row.setProducedQuantity(BigDecimal.ONE);
        row.setShippedQuantity(BigDecimal.ZERO);
        row.setReservedQuantity(BigDecimal.ZERO);
        row.setUnit("kg");
        row.setWarehouseId("WH-LOCK");
        row.setProductionPlanId(planId);
        row.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        return row;
    }

    private static BatchLineageEdge edge(String factoryId, String productionBatchId, String finishedGoodsId) {
        BatchLineageEdge row = new BatchLineageEdge();
        row.setFactoryId(factoryId);
        row.setEdgeType("PRODUCTION_TO_FINISHED");
        row.setSourceType("PRODUCTION_BATCH");
        row.setSourceId(productionBatchId);
        row.setTargetType("FINISHED_BATCH");
        row.setTargetId(finishedGoodsId);
        row.setEventTime(LocalDateTime.of(2026, 11, 9, 8, 0));
        row.setMeta(null);   // H2 读不回 jsonb 的 "{}" (PG 车道上同一用例照常带 meta 也能跑, 这里不测 meta)
        return row;
    }
}
