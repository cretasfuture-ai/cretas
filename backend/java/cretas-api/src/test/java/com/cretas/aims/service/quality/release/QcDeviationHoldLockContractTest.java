package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.jpa.repository.Lock;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 13 审查中 8 —— CCP 偏离扣留不覆盖并发扣减。
 *
 * <p>做法: 先用不加锁的查询只收集 id, 再<b>加行锁</b> (SELECT … FOR UPDATE) 读出最新的行, 在锁内判断并修改。
 * 这里用「两份不同的行」把它钉住: 收集 id 的查询返回的是<b>过期</b>副本 (还没扣 20), 加锁读返回的是<b>最新</b>副本
 * (另一事务刚领走 20)。扣留必须改最新那份、按最新余额 30 记待检; 过期副本一次都不能被保存 —— 保存它就是把并发扣减盖掉。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CCP 偏离扣留: 行锁内按最新状态改, 不盖掉并发扣减")
class QcDeviationHoldLockContractTest {

    private static final String F = "F-QDH";

    @Mock private SemiFinishedInventoryRepository semiRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private FinishedGoodsBatchRepository fgRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;

    private QcDeviationHold hold;

    private static SemiFinishedInventory semi(long id, String consumed, String status, String inspection, String pending) {
        BigDecimal produced = new BigDecimal("50");
        BigDecimal used = new BigDecimal(consumed);
        return SemiFinishedInventory.builder().id(id).factoryId(F).intermediateBatchNo("S-" + id).unit("kg")
                .producedQuantity(produced).consumedQuantity(used).availableQuantity(produced.subtract(used))
                .status(status).inspectionStatus(inspection)
                .inspectionPendingQuantity(pending == null ? null : new BigDecimal(pending)).build();
    }

    @BeforeEach
    void setUp() {
        hold = new QcDeviationHold(semiRepo, materialBatchRepo, fgRepo, productionBatchRepo);
        ProductionBatch pb = new ProductionBatch();
        pb.setId(11L);
        pb.setFactoryId(F);
        pb.setBatchNumber("PB-11");
        pb.setProductionPlanId("PLAN-QDH");
        when(productionBatchRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, "PLAN-QDH"))
                .thenReturn(List.of(pb));
        when(semiRepo.findSemiIdsPostedByProductionBatches(eq(F), anyCollection())).thenReturn(List.of());
        when(semiRepo.findByFactoryIdAndBatchNoOrAnchorPrefixForHold(eq(F), anyCollection(), anyString()))
                .thenReturn(List.of());
        when(materialBatchRepo.lockWipBatchesForDeviationHold(eq(F), anyCollection())).thenReturn(List.of());
        when(fgRepo.lockForDeviationHold(eq(F), anyCollection(), anyCollection())).thenReturn(List.of());
    }

    @Test
    @DisplayName("半成品: 收集用过期副本 (未扣), 锁内读到最新 (已被并发领走 20) → 按最新余额 30 记待检, 过期副本不保存")
    void semiHoldUsesTheLockedFreshRow() {
        SemiFinishedInventory stale = semi(1L, "0", SemiFinishedInventory.Status.AVAILABLE, null, null);
        SemiFinishedInventory fresh = semi(1L, "20", SemiFinishedInventory.Status.AVAILABLE, null, null);
        SemiFinishedInventory depleted = semi(2L, "50", SemiFinishedInventory.Status.DEPLETED, null, null);
        SemiFinishedInventory alreadyHeld = semi(3L, "30", SemiFinishedInventory.Status.AVAILABLE,
                SemiFinishedInventory.InspectionStatus.PENDING, "20");
        when(semiRepo.findByFactoryIdAndBatchIdInForHold(eq(F), anyCollection()))
                .thenReturn(List.of(stale, semi(2L, "0", SemiFinishedInventory.Status.AVAILABLE, null, null),
                        semi(3L, "0", SemiFinishedInventory.Status.AVAILABLE, null, null)));
        when(semiRepo.lockForDeviationHold(eq(F), anyCollection())).thenReturn(List.of(fresh, depleted, alreadyHeld));

        QcDeviationHold.HoldResult r = hold.holdForDeviation(F, "PB-11", 11L, "PLAN-QDH");
        System.out.println("HOLD semi=" + r.semiRows() + " fresh.pending=" + fresh.getInspectionPendingQuantity()
                + " fresh.consumed=" + fresh.getConsumedQuantity());

        assertThat(r.semiRows()).isEqualTo(1);
        assertThat(fresh.getInspectionStatus()).isEqualTo(SemiFinishedInventory.InspectionStatus.PENDING);
        assertThat(fresh.getInspectionPendingQuantity()).isEqualByComparingTo("30");
        assertThat(fresh.getConsumedQuantity()).as("并发扣减保留").isEqualByComparingTo("20");
        verify(semiRepo).save(fresh);
        verify(semiRepo, never()).save(stale);
        verify(semiRepo, never()).save(depleted);
        verify(semiRepo, never()).save(alreadyHeld);
    }

    @Test
    @DisplayName("在制批次 / 成品: 只改锁内读到仍为可用的; 已用完 / 已发完的不动")
    void wipAndFinishedGoodsOnlyTouchAvailable() {
        when(semiRepo.findByFactoryIdAndBatchIdInForHold(eq(F), anyCollection())).thenReturn(List.of());
        MaterialBatch wipOk = new MaterialBatch();
        wipOk.setId("W1");
        wipOk.setStatus(MaterialBatchStatus.AVAILABLE);
        MaterialBatch wipUsed = new MaterialBatch();
        wipUsed.setId("W2");
        wipUsed.setStatus(MaterialBatchStatus.USED_UP);
        when(materialBatchRepo.lockWipBatchesForDeviationHold(eq(F), anyCollection())).thenReturn(List.of(wipOk, wipUsed));
        FinishedGoodsBatch fgOk = new FinishedGoodsBatch();
        fgOk.setId("G1");
        fgOk.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        FinishedGoodsBatch fgGone = new FinishedGoodsBatch();
        fgGone.setId("G2");
        fgGone.setStatus(FinishedGoodsBatch.Status.DEPLETED);
        when(fgRepo.lockForDeviationHold(eq(F), anyCollection(), anyCollection())).thenReturn(List.of(fgOk, fgGone));

        QcDeviationHold.HoldResult r = hold.holdForDeviation(F, "PB-11", 11L, "PLAN-QDH");

        assertThat(r.wipBatches()).isEqualTo(1);
        assertThat(r.finishedGoodsBatches()).isEqualTo(1);
        assertThat(wipOk.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        assertThat(wipUsed.getStatus()).isEqualTo(MaterialBatchStatus.USED_UP);
        assertThat(fgOk.getStatus()).isEqualTo(FinishedGoodsBatch.Status.PENDING_INSPECTION);
        assertThat(fgGone.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEPLETED);
        verify(materialBatchRepo, never()).save(wipUsed);
        verify(fgRepo, never()).save(fgGone);
    }

    @Test
    @DisplayName("三个加锁读取都声明了 PESSIMISTIC_WRITE (不加锁就退化成读改写覆盖)")
    void lockQueriesArePessimisticWrite() throws Exception {
        assertThat(lockOf(SemiFinishedInventoryRepository.class, "lockForDeviationHold"))
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(lockOf(MaterialBatchRepository.class, "lockWipBatchesForDeviationHold"))
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(lockOf(FinishedGoodsBatchRepository.class, "lockForDeviationHold"))
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private static LockModeType lockOf(Class<?> repo, String name) {
        Method m = Arrays.stream(repo.getMethods()).filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
        Lock lock = m.getAnnotation(Lock.class);
        return lock == null ? null : lock.value();
    }
}
