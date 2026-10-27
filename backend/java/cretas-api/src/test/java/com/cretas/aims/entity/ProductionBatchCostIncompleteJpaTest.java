package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Hibernate/JPA Context: cost_incomplete 批次在真实 @PreUpdate 之后仍是成本未知。
 *
 * <p>Mockito 里看不到这个钩子 (ClerkProcessEntryServiceImplTest T17 一直是绿的), 生产上每次 save 都会
 * 用 material/labor 分项把 total_cost 重新求和 (S3-13 批次 10998: 114.80 / unit_cost 空)。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("ProductionBatch cost_incomplete 扛过 @PreUpdate")
class ProductionBatchCostIncompleteJpaTest {

    @Autowired
    private ProductionBatchRepository repository;

    @Autowired
    private EntityManager entityManager;

    private ProductionBatch batch(String number, boolean incomplete) {
        ProductionBatch b = ProductionBatch.builder()
                .factoryId("F-JPA-COST-INCOMPLETE")
                .batchNumber(number)
                .productTypeId("PT-JPA-COST")
                .productName("成本未知测试产品")
                .plannedQuantity(new BigDecimal("16"))
                .quantity(new BigDecimal("16"))
                .actualQuantity(new BigDecimal("16"))
                .unit("袋")
                .status(ProductionBatchStatus.IN_PROGRESS)
                .materialCost(new BigDecimal("10.80"))
                .laborCost(new BigDecimal("104.00"))
                .costIncomplete(incomplete)
                .build();
        return b;
    }

    /** 读库里真实落下的列 (原生 SQL, 不经实体反序列化; H2 下 jsonb 列无法回读成实体)。 */
    private Object[] persisted(Long id) {
        return (Object[]) entityManager.createNativeQuery(
                        "select cost_incomplete, total_cost, unit_cost, material_cost"
                                + " from production_batches where id = :id")
                .setParameter("id", id)
                .getSingleResult();
    }

    @Test
    @DisplayName("成本不完整: 保存后再更新一次无关字段, total/unit 仍为 null, 标记仍为 true")
    void incompleteBatchSurvivesUpdateHook() {
        ProductionBatch saved = repository.saveAndFlush(batch("PB-JPA-COST-INCOMPLETE-1", true));
        saved.setNotes("触发 @PreUpdate");
        repository.saveAndFlush(saved);
        entityManager.clear();

        Object[] row = persisted(saved.getId());
        assertThat(row[0]).as("cost_incomplete").isEqualTo(Boolean.TRUE);
        assertThat(row[1]).as("total_cost").isNull();
        assertThat(row[2]).as("unit_cost").isNull();
        assertThat((BigDecimal) row[3]).as("已知分项照常保存").isEqualByComparingTo("10.80");
    }

    @Test
    @DisplayName("对照: 成本完整的批次照旧由分项汇总")
    void completeBatchStillRollsUp() {
        ProductionBatch saved = repository.saveAndFlush(batch("PB-JPA-COST-COMPLETE-1", false));
        saved.setNotes("触发 @PreUpdate");
        repository.saveAndFlush(saved);
        entityManager.clear();

        Object[] row = persisted(saved.getId());
        assertThat(row[0]).as("cost_incomplete").isEqualTo(Boolean.FALSE);
        assertThat((BigDecimal) row[1]).isEqualByComparingTo("114.80");
        assertThat((BigDecimal) row[2]).isEqualByComparingTo("7.1750");
    }
}
