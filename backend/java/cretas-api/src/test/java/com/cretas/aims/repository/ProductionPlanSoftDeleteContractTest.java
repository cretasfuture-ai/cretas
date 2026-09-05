package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@code repository.delete()} 对生产三实体是<b>软删</b>, 不是物理 DELETE。
 *
 * <h2>它守的是什么 (2026-09-06, ARCH-PRODUCTION-PLAN-HARD-DELETE-PATH)</h2>
 *
 * <p>{@code BaseEntity}(@MappedSuperclass) 上写着
 * {@code @SQLDelete(sql = "UPDATE {h-domain} SET deleted_at = NOW() WHERE id = ?")},
 * 而它<b>被 Hibernate 静默忽略</b> —— 这件事 BaseEntity 自己的 R68 注释 (2026-04-29)
 * 就记着, 并说明 {@code @Where} 是靠 R68-FIX-A <b>批量补进 342 个子类</b>才生效的。
 * 🔴 <b>{@code @SQLDelete} 当年没有跟着批量补</b>: 改之前全仓只有 20 个实体带类级
 * {@code @SQLDelete}, 而带类级 {@code @Where(deleted_at IS NULL)} 的有 352 个。
 *
 * <p>于是 {@code ProductionPlan} / {@code ProductionBatch} / {@code ProcessSheetRow}
 * 三个实体一直是 <b>@Where ✅ / @SQLDelete ❌</b> 的组合 ——
 * {@code productionPlanRepository.delete(plan)} 发出去的是<b>物理 DELETE</b>。
 * prod 实证: {@code operation_logs} 里 3 条成功的 {@code PRODUCTION_PLAN/DELETE},
 * 对应的 3 行全部 ROW_ABSENT。
 *
 * <h2>为什么单测/编译都拦不住</h2>
 *
 * <p>形态 B(机制在, 没接上): 注解在, 位置看起来也对(就在父类上), 只是<b>那个位置不生效</b>。
 * mock 掉 repository 的单测一律绿 —— {@code verify(repo).delete(plan)} 对软删/硬删
 * 完全无法区分。只有<b>真库 + 删完之后回头查物理行</b>才分得开。
 *
 * <p>⚠️ 因此本测试<b>不</b>断言 {@code findById} 返回空 就收工 ——
 * 软删和硬删的 {@code findById} 都是空(类级 {@code @Where} 会把软删行滤掉)。
 * 唯一分得开两者的读数是<b>绕过 JPA 的原生查询</b>: 行还在不在, {@code deleted_at} 是不是非空。
 *
 * <h2>阴性对照</h2>
 *
 * <p>{@link #nativeReaderCanSayRowAbsent} 用同一个原生读数器读一行<b>真的被物理删掉</b>的计划,
 * 证明「行还在」不是恒真式 —— 没有这一条, 上面三条断言可能只是读数器永远说 present。
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
@DisplayName("生产三实体 repository.delete() 必须是软删 (R68 @SQLDelete 漏批量补)")
class ProductionPlanSoftDeleteContractTest {

    private static final String FID = "F-TEST";
    private static final String PT = "PT-ZS";
    private static final Long UID = 900001L;   // restock-fixtures.sql

    @Autowired private ProductionPlanRepository planRepo;
    @Autowired private ProductionBatchRepository batchRepo;
    @Autowired private ProcessSheetRowRepository rowRepo;
    @Autowired private EntityManager em;

    // ── ① ProductionPlan ────────────────────────────────────────────────────

    @Test
    @DisplayName("ProductionPlan: delete() 之后物理行仍在, deleted_at 非空")
    void productionPlanDeleteIsSoft() {
        ProductionPlan plan = newPlan("PP-SD-1", "PLAN-SD-0001");
        planRepo.saveAndFlush(plan);

        // 形态 C″: 先把可观测量打出来, 再断言。变异(去掉 @SQLDelete)之后
        // 这一行会打成 ROW_ABSENT / deleted_at=null, 一眼看得出变异确实到达了被测代码。
        System.out.println("BEHAVIOUR_BEFORE_DELETE production_plans PP-SD-1 -> " + rowState("production_plans", "PP-SD-1"));

        planRepo.delete(plan);
        planRepo.flush();

        String after = rowState("production_plans", "PP-SD-1");
        System.out.println("BEHAVIOUR_AFTER_DELETE  production_plans PP-SD-1 -> " + after);

        assertEquals(1, physicalRowCount("production_plans", "PP-SD-1"),
                "delete() 之后 production_plans 里应该还有这一行(软删)。实际读数: " + after
                        + "\n  行没了 = @SQLDelete 没生效, repository.delete() 又变回物理 DELETE。"
                        + "\n  ⚠️ 这正是 process_sheet_rows 在 prod 上留下 56 行孤儿的那条路径。");
        assertNotNull(deletedAt("production_plans", "PP-SD-1"),
                "行还在但 deleted_at 是 NULL —— 那不是软删, 是别的东西把行留下了。实际读数: " + after);

        assertTrue(planRepo.findById("PP-SD-1").isEmpty(),
                "软删之后类级 @Where(deleted_at IS NULL) 必须把它从 findById 里滤掉 —— "
                        + "这一条不区分软删硬删, 只是确认 @Where 那半边没被这次改动弄坏。");
    }

    // ── ② ProductionBatch ───────────────────────────────────────────────────

    @Test
    @DisplayName("ProductionBatch: delete() 之后物理行仍在, deleted_at 非空")
    void productionBatchDeleteIsSoft() {
        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(FID);
        batch.setBatchNumber("PB-SD-0001");
        batch.setProductTypeId(PT);
        batch.setQuantity(new BigDecimal("100.00"));
        batch.setUnit("kg");
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        Long id = batchRepo.saveAndFlush(batch).getId();

        System.out.println("BEHAVIOUR_BEFORE_DELETE production_batches " + id + " -> " + rowState("production_batches", id));

        batchRepo.delete(batch);
        batchRepo.flush();

        String after = rowState("production_batches", id);
        System.out.println("BEHAVIOUR_AFTER_DELETE  production_batches " + id + " -> " + after);

        assertEquals(1, physicalRowCount("production_batches", id),
                "delete() 之后 production_batches 里应该还有这一行(软删)。实际读数: " + after);
        assertNotNull(deletedAt("production_batches", id),
                "行还在但 deleted_at 是 NULL。实际读数: " + after);
        assertTrue(batchRepo.findById(id).isEmpty(), "软删行不该出现在 findById 里");
    }

    // ── ③ ProcessSheetRow ───────────────────────────────────────────────────

    @Test
    @DisplayName("ProcessSheetRow: delete() 之后物理行仍在, deleted_at 非空")
    void processSheetRowDeleteIsSoft() {
        ProductionPlan plan = newPlan("PP-SD-2", "PLAN-SD-0002");
        planRepo.saveAndFlush(plan);

        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FID);
        row.setPlanId("PP-SD-2");
        row.setProcessCode("chaoshui");
        row.setProcessOrder(1);
        row.setClientRowId("row-sd-1");
        row.setRowPayload("{}");
        row.setRowStatus("SAVED");
        Long id = rowRepo.saveAndFlush(row).getId();

        System.out.println("BEHAVIOUR_BEFORE_DELETE process_sheet_rows " + id + " -> " + rowState("process_sheet_rows", id));

        rowRepo.delete(row);
        rowRepo.flush();

        String after = rowState("process_sheet_rows", id);
        System.out.println("BEHAVIOUR_AFTER_DELETE  process_sheet_rows " + id + " -> " + after);

        assertEquals(1, physicalRowCount("process_sheet_rows", id),
                "delete() 之后 process_sheet_rows 里应该还有这一行(软删)。实际读数: " + after);
        assertNotNull(deletedAt("process_sheet_rows", id),
                "行还在但 deleted_at 是 NULL。实际读数: " + after);
        assertTrue(rowRepo.findById(id).isEmpty(), "软删行不该出现在 findById 里");
    }

    // ── 阴性对照 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("阴性对照: 同一个原生读数器对真的被物理删掉的行必须说 ROW_ABSENT")
    void nativeReaderCanSayRowAbsent() {
        ProductionPlan plan = newPlan("PP-SD-3", "PLAN-SD-0003");
        planRepo.saveAndFlush(plan);
        assertEquals(1, physicalRowCount("production_plans", "PP-SD-3"),
                "阳性对照: 存进去之后读数器必须读得到这一行, 否则它对上面三条断言毫无证明力");

        // ⚠️ 故意绕过 JPA 直接物理删 —— 这正是 @SQLDelete 缺失时 repository.delete() 干的事。
        em.createNativeQuery("DELETE FROM production_plans WHERE id = :id")
                .setParameter("id", "PP-SD-3")
                .executeUpdate();
        em.flush();
        em.clear();

        String after = rowState("production_plans", "PP-SD-3");
        System.out.println("NEGATIVE_CONTROL after physical DELETE -> " + after);

        assertEquals(0, physicalRowCount("production_plans", "PP-SD-3"),
                "读数器必须能说出「行没了」。它说不出来的话, 上面三条「行还在」的断言就是恒真式。"
                        + " 实际读数: " + after);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** 绕过 JPA 与类级 {@code @Where} 的原生读数: 这一行物理上还在不在。 */
    private int physicalRowCount(String table, Object id) {
        Number n = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
        return n.intValue();
    }

    /** 绕过 JPA 的原生读数: 这一行的 {@code deleted_at}(不存在则 null)。 */
    private Object deletedAt(String table, Object id) {
        List<?> rows = em.createNativeQuery(
                        "SELECT deleted_at FROM " + table + " WHERE id = :id")
                .setParameter("id", id)
                .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 打印用: 一行字同时说清「在不在」和「deleted_at 是什么」。 */
    private String rowState(String table, Object id) {
        int n = physicalRowCount(table, id);
        return n == 0 ? "ROW_ABSENT" : ("ROW_PRESENT deleted_at=" + deletedAt(table, id));
    }

    private ProductionPlan newPlan(String id, String planNumber) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(FID);
        p.setPlanNumber(planNumber);
        p.setProductTypeId(PT);
        p.setPlannedQuantity(new BigDecimal("100"));
        p.setPlannedUnit("kg");
        p.setStatus(ProductionPlanStatus.PENDING);
        p.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        p.setCreatedBy(UID);
        p.setIsLocked(false);
        p.setSkipProcessReporting(false);
        return p;
    }
}
