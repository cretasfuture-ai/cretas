package com.cretas.aims.service.sales;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.util.InventoryExpiry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 成品出货闸的日期一维。口径 (InventoryExpiry): 到期日当天仍可发, 次日起拒; 到期日为空按状态判 (维持旧行为)。
 */
@DisplayName("FinishedGoodsBatchSaleabilityGuard — 过期成品不可分配/发货")
class FinishedGoodsBatchSaleabilityGuardTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    @Test
    @DisplayName("昨天到期 → 409 FINISHED_GOODS_BATCH_EXPIRED, 消息带批号和到期日")
    void expiredYesterdayIsRejectedWithBatchNumberAndDate() {
        FinishedGoodsBatch batch = batch("FG-OLD", TODAY.minusDays(1));

        assertThatThrownBy(() -> FinishedGoodsBatchSaleabilityGuard.assertSaleable(batch, "finishedGoodsBatchId", TODAY))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("FINISHED_GOODS_BATCH_EXPIRED");
                    assertThat(error.getMessage()).contains("FG-OLD", "2026-10-23", "已过期");
                });
    }

    @Test
    @DisplayName("今天到期 → 放行 (与原料定时任务同口径: 到期日当天仍可用)")
    void expiringTodayIsStillSaleable() {
        assertThatCode(() -> FinishedGoodsBatchSaleabilityGuard.assertSaleable(
                batch("FG-TODAY", TODAY), "finishedGoodsBatchId", TODAY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("到期日为空 → 维持现状: AVAILABLE 放行, 非 AVAILABLE 仍按状态拒")
    void nullExpireDateKeepsStatusOnlyBehaviour() {
        assertThatCode(() -> FinishedGoodsBatchSaleabilityGuard.assertSaleable(
                batch("FG-NODATE", null), "finishedGoodsBatchId", TODAY)).doesNotThrowAnyException();

        FinishedGoodsBatch frozen = batch("FG-NODATE-FROZEN", null);
        frozen.setStatus(FinishedGoodsBatch.Status.FROZEN);
        assertThatThrownBy(() -> FinishedGoodsBatchSaleabilityGuard.assertSaleable(frozen, "x", TODAY))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("FINISHED_GOODS_BATCH_NOT_SALEABLE"));
    }

    @Test
    @DisplayName("默认重载用东八区业务日: 业务日的昨天到期即被拒")
    void defaultOverloadUsesShanghaiBusinessDay() {
        assertThat(InventoryExpiry.today()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        FinishedGoodsBatch batch = batch("FG-BIZ", InventoryExpiry.today().minusDays(1));
        assertThatThrownBy(() -> FinishedGoodsBatchSaleabilityGuard.assertSaleable(batch, "x"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("FINISHED_GOODS_BATCH_EXPIRED"));
    }

    @Test
    @DisplayName("excludeExpired: 只剔过期的, 保留原顺序 (今天到期 / 无到期日都保留)")
    void excludeExpiredKeepsOrderAndBoundary() {
        List<FinishedGoodsBatch> kept = FinishedGoodsBatchSaleabilityGuard.excludeExpired(List.of(
                batch("OLD", TODAY.minusDays(3)),
                batch("TODAY", TODAY),
                batch("YESTERDAY", TODAY.minusDays(1)),
                batch("NODATE", null),
                batch("LATER", TODAY.plusDays(2))), TODAY);

        assertThat(kept).extracting(FinishedGoodsBatch::getBatchNumber)
                .containsExactly("TODAY", "NODATE", "LATER");
    }

    private static FinishedGoodsBatch batch(String batchNumber, LocalDate expireDate) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("id-" + batchNumber);
        batch.setBatchNumber(batchNumber);
        batch.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        batch.setExpireDate(expireDate);
        return batch;
    }
}
