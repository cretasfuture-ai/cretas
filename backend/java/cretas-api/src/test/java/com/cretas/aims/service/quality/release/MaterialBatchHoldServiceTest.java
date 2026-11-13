package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1590 遗留 M5: 扣留记下原状态, 放行还原; 判不合格 / 报废不受影响; 离开质检中就清掉记录。
 * 真库上的读写与并发见 {@code MaterialBatchHoldPostgresContractTest}。
 */
class MaterialBatchHoldServiceTest {

    private static MaterialBatch batch(MaterialBatchStatus st, String receipt, String used, String reserved) {
        MaterialBatch b = new MaterialBatch();
        b.setBatchNumber("QA-HOLD-1");
        b.setStatus(st);
        b.setReceiptQuantity(new BigDecimal(receipt));
        b.setUsedQuantity(new BigDecimal(used));
        b.setReservedQuantity(new BigDecimal(reserved));
        return b;
    }

    @Test
    @DisplayName("冻品 / 鲜品 / 库存中 / 可用 扣留后放行 → 回到扣留前状态")
    void releaseRestoresPriorStatus() {
        for (MaterialBatchStatus prior : new MaterialBatchStatus[]{MaterialBatchStatus.FROZEN, MaterialBatchStatus.FRESH,
                MaterialBatchStatus.IN_STOCK, MaterialBatchStatus.AVAILABLE}) {
            MaterialBatch b = batch(prior, "50", "10", "0");
            MaterialBatchHoldService.hold(b);
            assertThat(b.getStatus()).as(prior.name()).isEqualTo(MaterialBatchStatus.INSPECTING);
            assertThat(b.getHoldPriorStatus()).as(prior.name()).isEqualTo(prior);
            assertThat(MaterialBatchHoldService.releaseTarget(b, MaterialBatchStatus.AVAILABLE)).as(prior.name())
                    .isEqualTo(prior);
        }
    }

    @Test
    @DisplayName("已耗尽 (全被预留) 扣留: 放行时仍全被预留 → 已耗尽; 扣留期间预留释放了 → 可用; 实物没了 → 已用完")
    void quantityStatesAreRecomputedOnRelease() {
        MaterialBatch b = batch(MaterialBatchStatus.DEPLETED, "50", "0", "50");
        MaterialBatchHoldService.hold(b);
        assertThat(MaterialBatchHoldService.releaseTarget(b, MaterialBatchStatus.AVAILABLE))
                .isEqualTo(MaterialBatchStatus.DEPLETED);
        b.setReservedQuantity(new BigDecimal("20"));
        assertThat(MaterialBatchHoldService.releaseTarget(b, MaterialBatchStatus.AVAILABLE))
                .isEqualTo(MaterialBatchStatus.AVAILABLE);
        b.setUsedQuantity(new BigDecimal("50"));
        b.setReservedQuantity(BigDecimal.ZERO);
        assertThat(MaterialBatchHoldService.releaseTarget(b, MaterialBatchStatus.AVAILABLE))
                .isEqualTo(MaterialBatchStatus.USED_UP);
    }

    @Test
    @DisplayName("首检中 (没有扣留记录) 放行 → 可用, 与原行为一致; 判不合格 / 报废不被改写")
    void firstInspectionAndNonReleaseTargetsUnchanged() {
        MaterialBatch first = batch(MaterialBatchStatus.INSPECTING, "50", "0", "0");
        assertThat(MaterialBatchHoldService.releaseTarget(first, MaterialBatchStatus.AVAILABLE))
                .isEqualTo(MaterialBatchStatus.AVAILABLE);

        MaterialBatch held = batch(MaterialBatchStatus.FROZEN, "50", "0", "0");
        MaterialBatchHoldService.hold(held);
        assertThat(MaterialBatchHoldService.releaseTarget(held, MaterialBatchStatus.DEFECTIVE))
                .isEqualTo(MaterialBatchStatus.DEFECTIVE);
        assertThat(MaterialBatchHoldService.releaseTarget(held, MaterialBatchStatus.SCRAPPED))
                .isEqualTo(MaterialBatchStatus.SCRAPPED);
    }

    @Test
    @DisplayName("送回复检 (不良品 → 质检中) 不记原状态: 复检放行是放成可用, 绝不还原成不良品")
    void reinspectionDoesNotRecordBlockedPrior() {
        MaterialBatch b = batch(MaterialBatchStatus.DEFECTIVE, "50", "0", "0");
        MaterialBatchHoldService.hold(b);
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        assertThat(b.getHoldPriorStatus()).isNull();
        assertThat(MaterialBatchHoldService.isHeld(b)).isFalse();
    }

    @Test
    @DisplayName("已在质检中再扣留不覆盖原记录; 离开质检中 (判不合格) 清掉记录, 之后不会还原出一个过期的原状态")
    void invariantPriorOnlyWhileInspecting() {
        MaterialBatch b = batch(MaterialBatchStatus.FROZEN, "50", "0", "0");
        MaterialBatchHoldService.hold(b);
        MaterialBatchHoldService.hold(b);
        assertThat(b.getHoldPriorStatus()).isEqualTo(MaterialBatchStatus.FROZEN);
        assertThat(MaterialBatchHoldService.isHeld(b)).isTrue();

        b.setStatus(MaterialBatchStatus.DEFECTIVE);
        assertThat(b.getHoldPriorStatus()).isNull();
        b.setStatus(MaterialBatchStatus.INSPECTING);   // 送回复检
        assertThat(MaterialBatchHoldService.releaseTarget(b, MaterialBatchStatus.AVAILABLE))
                .isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("冻结的可扣判定: 不可投产 / 实物为 0 不扣; 已耗尽照扣")
    void notHoldableReason() {
        assertThat(MaterialBatchHoldService.notHoldableReason(batch(MaterialBatchStatus.DEFECTIVE, "5", "0", "0")))
                .contains("不良品");
        assertThat(MaterialBatchHoldService.notHoldableReason(batch(MaterialBatchStatus.USED_UP, "5", "5", "0")))
                .contains("没有实物");
        assertThat(MaterialBatchHoldService.notHoldableReason(batch(MaterialBatchStatus.DEPLETED, "5", "0", "5")))
                .isNull();
    }

    @Test
    @DisplayName("已预留 → 冻结 → 预留取消 (预留量 0) → 放行 = 可用; 阳性对照: 预留仍在 → 回已预留")
    void reservedPriorRecomputedFromCurrentReservation() {
        MaterialBatch cancelled = batch(MaterialBatchStatus.RESERVED, "50", "0", "10");
        MaterialBatchHoldService.hold(cancelled);
        assertThat(cancelled.getHoldPriorStatus()).isEqualTo(MaterialBatchStatus.RESERVED);
        cancelled.setReservedQuantity(BigDecimal.ZERO);   // 扣留期间预留被取消
        assertThat(MaterialBatchHoldService.releaseTarget(cancelled, MaterialBatchStatus.AVAILABLE))
                .isEqualTo(MaterialBatchStatus.AVAILABLE);

        MaterialBatch still = batch(MaterialBatchStatus.RESERVED, "50", "0", "10");
        MaterialBatchHoldService.hold(still);
        assertThat(MaterialBatchHoldService.releaseTarget(still, MaterialBatchStatus.AVAILABLE))
                .isEqualTo(MaterialBatchStatus.RESERVED);
    }

    @Test
    @DisplayName("离开在制口径: 可用为 0 → 已耗尽; 仍有预留 → 已预留; 否则可用; 没有扣留记录的不跟进")
    void wipExitStatusAndFollowWip() {
        assertThat(MaterialBatchHoldService.wipExitStatus(batch(MaterialBatchStatus.PRODUCING_RESERVED, "50", "50", "0")))
                .isEqualTo(MaterialBatchStatus.DEPLETED);
        assertThat(MaterialBatchHoldService.wipExitStatus(batch(MaterialBatchStatus.PRODUCING_RESERVED, "50", "0", "5")))
                .isEqualTo(MaterialBatchStatus.RESERVED);
        assertThat(MaterialBatchHoldService.wipExitStatus(batch(MaterialBatchStatus.PRODUCING_RESERVED, "50", "0", "0")))
                .isEqualTo(MaterialBatchStatus.AVAILABLE);
        MaterialBatch first = batch(MaterialBatchStatus.INSPECTING, "50", "0", "0");
        assertThat(MaterialBatchHoldService.followWip(first, false)).isFalse();
        assertThat(first.getHoldPriorStatus()).isNull();
    }
}
