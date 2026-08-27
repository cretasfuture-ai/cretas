package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.enums.DefectCountOutcome;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 盘点补录待办的取数：<b>只列没回答过的，已清点确认零不许进来</b>。
 *
 * <h2>它守什么</h2>
 *
 * <p>三态存在的全部意义，就是把「已清点确认零」和「没人填」分开。
 * 如果待办把 {@code NONE_CONFIRMED} 也列进来，盘点人就会被要求去补录一个
 * <b>已经有人清点确认过</b>的数 —— 那道待办会立刻变成噪音，然后被忽略，
 * 最后所有人都不看它。<b>一道天天误报的待办，最终覆盖率归零。</b></p>
 *
 * <p>反过来，如果它漏掉 {@code NOT_COUNTED}，那这条链路就白做了 ——
 * 结单时「不填也能提交」的那些批次永远不会被人想起来。</p>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class PendingDefectCountRepositoryQueryValidationTest {

    private static final String FACTORY = "F-JPA-DEFECT-TODO";

    @Autowired ProductionSettlementRepository repository;

    private ProductionSettlement settlement(String planNumber, DefectCountOutcome outcome,
                                            BigDecimal defectQuantity) {
        ProductionSettlement s = new ProductionSettlement();
        s.setId(UUID.randomUUID().toString());
        s.setFactoryId(FACTORY);
        s.setProductionPlanId("plan-" + planNumber);
        s.setPlanNumber(planNumber);
        s.setIdempotencyKey("idem-" + planNumber);
        s.setPlannedQuantity(new BigDecimal("10"));
        s.setActualSemiFinishedQuantity(BigDecimal.ZERO);
        s.setPlanStatusAfter(ProductionPlanStatus.COMPLETED);
        s.setPostingStatus("PENDING_WAREHOUSE_RECEIPT");
        s.setSettledAt(java.time.LocalDateTime.now());
        s.setDefectCountOutcome(outcome);
        s.setDefectQuantity(defectQuantity);
        return s;
    }

    @Test
    void listsOnlyUnansweredAndNeverTheConfirmedZero() {
        repository.saveAllAndFlush(List.of(
                settlement("P-NEVER", null, null),                                        // 存量: 从没被问过
                settlement("P-NOTCOUNTED", DefectCountOutcome.NOT_COUNTED, null),         // 人答了没清点
                settlement("P-CONFIRMED", DefectCountOutcome.NONE_CONFIRMED, BigDecimal.ZERO), // 已清点确认零
                settlement("P-COUNTED", DefectCountOutcome.COUNTED, new BigDecimal("3"))  // 已清点有 3 个
        ));

        List<String> pending = repository.findPendingDefectCounts(FACTORY).stream()
                .map(ProductionSettlement::getPlanNumber)
                .toList();

        // 阳性对照: 查询确实跑起来了并且取到了东西 (防「查询恒空」后下面两条断言假绿)
        assertThat(pending).isNotEmpty();

        assertThat(pending)
                .as("两种「没回答」都要进待办")
                .contains("P-NEVER", "P-NOTCOUNTED");

        assertThat(pending)
                .as("⛔ 已清点确认零【不许】进待办 —— 那是真实读数, 让盘点人去补录它 "
                        + "会把这道待办变成噪音, 然后被忽略, 最后覆盖率归零")
                .doesNotContain("P-CONFIRMED");

        assertThat(pending)
                .as("已清点且有数的当然也不在待办里")
                .doesNotContain("P-COUNTED");
    }
}
