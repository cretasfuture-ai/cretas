package com.cretas.aims.service.foodsafety.ccp;

import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.quality.release.QcDeviationHold;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 13 第二轮审查 M1 —— 报工驳回 / 撤回后, 它随带的 CCP 偏离作废: 按「已处理」收口并写明原因
 * (记录保留可查), 判据与挡放行的 {@link CcpOpenDeviationLookup#isOpenDeviation} 同一个。
 * 已处理的偏离、限值内的读数不动; 别的报工的偏离不动。
 */
@DisplayName("CCP: 报工撤回后它的偏离作废, 不再挡放行")
class CcpVoidDeviationContractTest {

    private final HaccpMonitoringRecordRepository recordRepo = mock(HaccpMonitoringRecordRepository.class);
    private final CcpMonitoringService service = new CcpMonitoringService(mock(HaccpCheckpointRepository.class),
            recordRepo, mock(ProductionBatchRepository.class), mock(FinishedGoodsBatchRepository.class),
            mock(QcDeviationHold.class));

    private static HaccpMonitoringRecord rec(long id, boolean deviation, boolean handled) {
        return HaccpMonitoringRecord.builder().id(id).factoryId("F1").checkpointId(1L).batchNumber("PB-1")
                .measuredValue(new BigDecimal("70")).isDeviation(deviation).reportRef("YR:9")
                .deviationHandledAt(handled ? LocalDateTime.now().minusDays(1) : null)
                .deviationAction(handled ? "人工处理: 复测合格" : null).build();
    }

    @Test
    @DisplayName("只作废未处理的偏离; 已处理的偏离 / 限值内读数原样; 作废后不再算未处理偏离")
    void voidsOnlyOpenDeviations() {
        HaccpMonitoringRecord open = rec(1, true, false);
        HaccpMonitoringRecord handled = rec(2, true, true);
        HaccpMonitoringRecord ok = rec(3, false, false);
        when(recordRepo.findByFactoryIdAndReportRef("F1", "YR:9")).thenReturn(List.of(open, handled, ok));
        when(recordRepo.save(any(HaccpMonitoringRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        int n = service.voidDeviationsOfReport("F1", "YR:9", 7L, "所属报工已撤回");

        assertThat(n).isEqualTo(1);
        assertThat(CcpOpenDeviationLookup.isOpenDeviation(open)).isFalse();
        assertThat(open.getDeviationAction()).contains("所属报工已撤回").contains("YR:9");
        assertThat(handled.getDeviationAction()).isEqualTo("人工处理: 复测合格");
        assertThat(ok.getDeviationHandledAt()).isNull();
        verify(recordRepo).save(open);
        verify(recordRepo, never()).save(handled);
    }

    @Test
    @DisplayName("阴性对照: 别的报工凭据下没有记录 → 一条都不动")
    void otherReportUntouched() {
        when(recordRepo.findByFactoryIdAndReportRef("F1", "YR:10")).thenReturn(List.of());
        assertThat(service.voidDeviationsOfReport("F1", "YR:10", 7L, "所属报工已撤回")).isZero();
        verify(recordRepo, never()).save(any());
    }
}
