package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.quality.SemiQcHoldLot;
import com.cretas.aims.repository.quality.SemiQcHoldLotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 半成品待检分笔账 (缺口 11 第二轮审查 H2/H3/M1) —— 「撤销一笔入账时, 待检要减多少」的<b>唯一口径</b>。
 *
 * <ul>
 *   <li>入账扣成待检 → {@link #record} 记一笔 (认得出是哪一笔: App 报工 id 或逐道录入凭据);</li>
 *   <li>撤销 / 驳回 / 数量改小 → {@link #unwindForReport} / {@link #unwindForRef}: 只解除<b>这一笔还没放行</b>的量,
 *       行上的待检数量同步减掉 —— 已放行的笔撤销时待检不动, 别人的待检不会被抵消;</li>
 *   <li>认不出是哪一笔的撤销 (没有凭据的旧调用) <b>不调这里</b> = 待检一律不减 (保守: 宁可多挡,
 *       行上待检数量本来就封顶为余额, 见 {@link SemiFinishedInventory#pendingInspectionQuantity()});</li>
 *   <li>质检放行 / 判不合格 → {@link #closeAll}: 这一行所有笔清零 (待检已经处理完)。</li>
 * </ul>
 * CCP 偏离把整个余额扣成待检时不记分笔 (偏离归不到某一笔): 之后撤销某笔只解除它自己记过的量,
 * 整行仍按余额封顶全部待检 —— 也是保守方向。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemiQcHoldLots {

    private final SemiQcHoldLotRepository lotRepo;

    /** 一笔入账扣成待检 {@code held} (≤0 不记)。 */
    public void record(SemiFinishedInventory sfi, BigDecimal held, Long reportId, String holdRef) {
        record(sfi, held, reportId, holdRef, null);
    }

    /** 同上, 带这一笔的生产批次 (放行时按未放行的笔反查计划)。 */
    public void record(SemiFinishedInventory sfi, BigDecimal held, Long reportId, String holdRef,
                       Long productionBatchId) {
        if (sfi == null || sfi.getId() == null || held == null || held.signum() <= 0) {
            return;
        }
        lotRepo.save(SemiQcHoldLot.builder()
                .factoryId(sfi.getFactoryId())
                .semiFinishedId(sfi.getId())
                .reportId(reportId)
                .productionBatchId(productionBatchId)
                .holdRef(holdRef)
                .quantityHeld(held)
                .quantityOpen(held)
                .build());
    }

    /** 撤销 / 驳回 / 数量改小某次 App 报工的入账: 解除它未放行的待检, 最多 {@code qty}。 */
    public BigDecimal unwindForReport(SemiFinishedInventory sfi, Long reportId, BigDecimal qty) {
        if (sfi == null || sfi.getId() == null || reportId == null) {
            return BigDecimal.ZERO;
        }
        return unwind(sfi, lotRepo.findOpenBySemiAndReport(sfi.getFactoryId(), sfi.getId(), reportId), qty);
    }

    /** 重存 / 删除某条逐道录入的入账: 解除它未放行的待检, 最多 {@code qty}。 */
    public BigDecimal unwindForRef(SemiFinishedInventory sfi, String holdRef, BigDecimal qty) {
        if (sfi == null || sfi.getId() == null || holdRef == null || holdRef.isBlank()) {
            return BigDecimal.ZERO;
        }
        return unwind(sfi, lotRepo.findOpenBySemiAndRef(sfi.getFactoryId(), sfi.getId(), holdRef), qty);
    }

    /** 这一行当前所有未放行的笔 (放行前判计划质检 FAILED 用)。 */
    public List<SemiQcHoldLot> openLots(String factoryId, Long semiId) {
        return lotRepo.findOpenBySemi(factoryId, semiId);
    }

    /** 质检放行 / 判不合格: 这一行所有未放行的笔清零。 */
    public void closeAll(String factoryId, Long semiId) {
        List<SemiQcHoldLot> open = lotRepo.findOpenBySemi(factoryId, semiId);
        for (SemiQcHoldLot lot : open) {
            lot.setQuantityOpen(BigDecimal.ZERO);
        }
        if (!open.isEmpty()) {
            lotRepo.saveAll(open);
        }
    }

    private BigDecimal unwind(SemiFinishedInventory sfi, List<SemiQcHoldLot> lots, BigDecimal qty) {
        BigDecimal left = qty == null ? BigDecimal.ZERO : qty.max(BigDecimal.ZERO);
        BigDecimal released = BigDecimal.ZERO;
        for (SemiQcHoldLot lot : lots) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal take = lot.getQuantityOpen().min(left);
            lot.setQuantityOpen(lot.getQuantityOpen().subtract(take));
            left = left.subtract(take);
            released = released.add(take);
        }
        if (released.signum() > 0) {
            lotRepo.saveAll(lots);
            if (SemiFinishedInventory.InspectionStatus.PENDING.equals(sfi.getInspectionStatus())
                    && sfi.getInspectionPendingQuantity() != null) {
                sfi.setInspectionPendingQuantity(
                        sfi.getInspectionPendingQuantity().subtract(released).max(BigDecimal.ZERO));
            }
            log.info("[质检分笔] semi={} 撤销解除未放行待检 {}", sfi.getId(), released);
        }
        return released;
    }
}
