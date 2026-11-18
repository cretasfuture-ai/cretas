package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LSM-COSTBI 第 12 项: 时间段成本分析屏的「批次数量」以前写死 0。新查询
 * {@link ProductionPlanRepository#countEndedBetweenDates} 数结束时间在区间内的计划, 口径必须和
 * {@link ProductionPlanRepository#calculateTotalCostBetweenDates} 一致 (同一组计划, 平均每计划成本才有意义),
 * 且只数本厂。真实 JPA 上下文跑 JPQL (Mockito 证明不了查询能解析、条件对)。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Sql(
    scripts = "/test-data/restock-fixtures.sql",
    config = @SqlConfig(errorMode = SqlConfig.ErrorMode.FAIL_ON_ERROR),
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
@DisplayName("ProductionPlanRepository — 完工计划数 (与成本合计同口径)")
class ProductionPlanEndedCountRepositoryQueryValidationTest {

    @Autowired
    private ProductionPlanRepository repo;

    @Autowired
    private TestEntityManager em;

    /** 第二个工厂 (证明只数本厂): 夹具只有 F-TEST, 这里补一行, 列与 restock-fixtures.sql 相同。 */
    private void otherFactory() {
        em.getEntityManager().createNativeQuery("MERGE INTO factories (id, name, type, level, is_active, "
                + "manually_verified, ai_weekly_quota, created_at, updated_at) KEY(id) VALUES ('F-OTHER', "
                + "'Other Factory', 'FACTORY', 0, TRUE, FALSE, 20, NOW(), NOW())").executeUpdate();
    }

    private static final String FID = "F-TEST";
    private static final Long UID = 900001L;   // matches restock-fixtures.sql
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 14, 23, 59, 59);

    private void plan(String id, String factoryId, LocalDateTime endTime, String materialCost) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(factoryId);
        p.setPlanNumber("PLAN-" + id);
        p.setProductTypeId("PT-ZS");
        p.setPlannedQuantity(new BigDecimal("100"));
        p.setPlannedUnit("kg");
        p.setStatus(ProductionPlanStatus.COMPLETED);
        p.setCreatedBy(UID);
        p.setEndTime(endTime);
        p.setActualMaterialCost(materialCost == null ? null : new BigDecimal(materialCost));
        repo.saveAndFlush(p);
    }

    @Test
    @DisplayName("只数结束时间在区间内 (含两端) 的本厂计划, 与成本 SUM 是同一组")
    void countsSamePlansAsCostSum_inclusiveBounds_ownFactoryOnly() {
        otherFactory();
        plan("PP-EC-1", FID, FROM, "100");                                  // 起点 (含)
        plan("PP-EC-2", FID, LocalDateTime.of(2026, 11, 7, 12, 0), null);   // 成本没录也算一个完工计划
        plan("PP-EC-3", FID, TO, "50");                                     // 终点 (含)
        plan("PP-EC-4", FID, TO.plusSeconds(1), "999");                     // 区间外
        plan("PP-EC-5", FID, null, "999");                                  // 没结束
        plan("PP-EC-6", "F-OTHER", LocalDateTime.of(2026, 11, 7, 12, 0), "999");   // 别的厂

        assertEquals(3L, repo.countEndedBetweenDates(FID, FROM, TO));
        // 同口径: 成本合计正好是这 3 个计划的 (100 + 0 + 50), 没混进区间外 / 未结束 / 别厂的 999
        assertEquals(0, new BigDecimal("150").compareTo(repo.calculateTotalCostBetweenDates(FID, FROM, TO)));
        assertEquals(1L, repo.countEndedBetweenDates("F-OTHER", FROM, TO));
    }

    @Test
    @DisplayName("区间内没有完工计划 → 0 (不是 null)")
    void noPlans_isZero() {
        plan("PP-EC-7", FID, FROM.minusDays(1), "10");
        assertEquals(0L, repo.countEndedBetweenDates(FID, FROM, TO));
    }
}
