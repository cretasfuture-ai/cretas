package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.workflow.ReworkRatioDeviation;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("缺口 03 回料比例上限: 超限必须填原因, 没设不拦")
class ReworkRatioCheckContractTest {

    private static final String F = "F006";
    private SemiFinishedInventoryRepository sfi;
    private ProductTypeRepository products;
    private ReworkRatioCheck check;

    @BeforeEach
    void setUp() {
        sfi = mock(SemiFinishedInventoryRepository.class);
        products = mock(ProductTypeRepository.class);
        check = new ReworkRatioCheck(sfi, products);
        SemiFinishedInventory rework = new SemiFinishedInventory();
        rework.setIsRework(true);
        when(sfi.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, "RW-1")).thenReturn(Optional.of(rework));
        SemiFinishedInventory plain = new SemiFinishedInventory();
        plain.setIsRework(false);
        when(sfi.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, "SF-1")).thenReturn(Optional.of(plain));
    }

    private void limit(String percent) {
        ProductType p = new ProductType();
        p.setMaxReworkRatioPercent(percent == null ? null : new BigDecimal(percent));
        when(products.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.of(p));
    }

    /** 主料 100kg + 回料 reworkKg (inputQuantity = 主料 + 回料)。 */
    private ProcessSheetRowRequest req(String reworkKg) {
        ProcessSheetRowRequest r = new ProcessSheetRowRequest();
        r.setProductTypeId("P1");
        r.setInputQuantity(new BigDecimal("100").add(new BigDecimal(reworkKg)));
        List<ProcessSheetRowRequest.UpstreamRef> refs = new ArrayList<>();
        ProcessSheetRowRequest.UpstreamRef rw = new ProcessSheetRowRequest.UpstreamRef();
        rw.setSourceBatchNumber("RW-1");
        rw.setSemiFinished(true);
        rw.setFeedQuantityKg(new BigDecimal(reworkKg));
        refs.add(rw);
        r.setUpstreamSources(refs);
        return r;
    }

    @Test
    @DisplayName("没设上限: 回料再多也不拦, 也不留快照")
    void noLimitNeverBlocks() {
        limit(null);
        ProcessSheetRowRequest r = req("90");
        check.enforce(F, "plan", r);
        assertThat(r.getReworkRatio()).isNull();
    }

    @Test
    @DisplayName("设了上限, 没超: 放行, 快照 WITHIN")
    void withinLimit() {
        limit("10");
        ProcessSheetRowRequest r = req("8.5");
        check.enforce(F, "plan", r);
        assertThat(r.getReworkRatio().getStatus()).isEqualTo(ReworkRatioCheck.STATUS_WITHIN);
    }

    @Test
    @DisplayName("设了上限, 超限不填原因: 409 REWORK_RATIO_REASON_REQUIRED")
    void overWithoutReasonRejected() {
        limit("10");
        assertThatThrownBy(() -> check.enforce(F, "plan", req("15")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ReworkRatioCheck.ERROR_CODE);
                    // 诊断来自回料占比闸 (归因), 带怎么解开
                    assertThat(((BusinessException) e).getDiagnoses()).hasSize(1);
                    assertThat(((BusinessException) e).getDiagnoses().get(0).gate())
                            .isEqualTo(com.cretas.aims.service.gate.GateId.REWORK_RATIO);
                    assertThat(((BusinessException) e).getDiagnoses().get(0).howToResolve()).contains("超限原因");
                });
    }

    @Test
    @DisplayName("超限填了标准原因: 放行并留痕 (原因/占比/上限)")
    void overWithReasonPassesAndLeavesTrace() {
        limit("10");
        ProcessSheetRowRequest r = req("15");
        r.setReworkRatio(ReworkRatioDeviation.builder().reason("主料不足").build());
        check.enforce(F, "plan", r);
        ReworkRatioDeviation d = r.getReworkRatio();
        assertThat(d.getStatus()).isEqualTo(ReworkRatioCheck.STATUS_OVER_WITH_REASON);
        assertThat(d.getReason()).isEqualTo("主料不足");
        assertThat(d.getActualPercent()).isEqualByComparingTo("15");
        assertThat(d.getLimitPercent()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("原因选「其他」不写说明 / 原因不在标准里: 拒")
    void otherNeedsNoteAndUnknownReasonRejected() {
        limit("10");
        ProcessSheetRowRequest other = req("15");
        other.setReworkRatio(ReworkRatioDeviation.builder().reason("其他").build());
        assertThatThrownBy(() -> check.enforce(F, "plan", other)).isInstanceOf(BusinessException.class);
        ProcessSheetRowRequest bogus = req("15");
        bogus.setReworkRatio(ReworkRatioDeviation.builder().reason("随便").build());
        assertThatThrownBy(() -> check.enforce(F, "plan", bogus))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ReworkRatioCheck.INVALID_REASON_CODE));
    }

    @Test
    @DisplayName("客户端伪造的快照不作数: 没超限时被清掉重算")
    void forgedSnapshotOverwritten() {
        limit("10");
        ProcessSheetRowRequest r = req("5");
        r.setReworkRatio(ReworkRatioDeviation.builder().reason("主料不足").status("OVER_WITH_REASON").build());
        check.enforce(F, "plan", r);
        assertThat(r.getReworkRatio().getStatus()).isEqualTo(ReworkRatioCheck.STATUS_WITHIN);
        assertThat(r.getReworkRatio().getReason()).isNull();
    }

    @Test
    @DisplayName("普通半成品(非回料)不计入回料占比")
    void nonReworkSfiIgnored() {
        limit("10");
        ProcessSheetRowRequest r = req("0.1");
        r.getUpstreamSources().get(0).setSourceBatchNumber("SF-1");
        r.getUpstreamSources().get(0).setFeedQuantityKg(new BigDecimal("50"));
        r.setInputQuantity(new BigDecimal("150"));
        check.enforce(F, "plan", r);
        assertThat(r.getReworkRatio()).isNull();
    }

    // ------------------------------------------------------------------
    // App 报工路径 (YieldReportServiceImpl): 领用回料半成品 (sourceWipNo)
    // ------------------------------------------------------------------

    private static com.cretas.aims.entity.workprocess.WorkProcessTask task() {
        com.cretas.aims.entity.workprocess.WorkProcessTask t = new com.cretas.aims.entity.workprocess.WorkProcessTask();
        t.setId(7L);
        t.setProductTypeId("P1");
        return t;
    }

    private static SemiFinishedInventory wip(boolean rework) {
        SemiFinishedInventory w = new SemiFinishedInventory();
        w.setIsRework(rework);
        return w;
    }

    private void enforceAppYield(boolean rework, String reworkKg, String totalKg, ReworkRatioDeviation submitted,
                       com.cretas.aims.entity.ProductionReport report) {
        check.enforceYieldReport(F, task(), wip(rework), new BigDecimal(reworkKg), new BigDecimal(totalKg),
                submitted, report);
    }

    @Test
    @DisplayName("App 路径: 回料占比超限不填原因 => 409, 同一个错误码 (App 与 web 共用)")
    void yieldOverWithoutReasonRejected() {
        limit("10");
        com.cretas.aims.entity.ProductionReport report = new com.cretas.aims.entity.ProductionReport();
        assertThatThrownBy(() -> enforceAppYield(true, "15", "115", null, report))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ReworkRatioCheck.ERROR_CODE));
        assertThat(report.getCustomFields()).isNull();
    }

    @Test
    @DisplayName("App 路径: 超限填原因放行, 快照落 customFields.reworkRatio; 没超限只留 WITHIN")
    void yieldReasonLeavesTraceInCustomFields() {
        limit("10");
        com.cretas.aims.entity.ProductionReport over = new com.cretas.aims.entity.ProductionReport();
        enforceAppYield(true, "15", "115", ReworkRatioDeviation.builder().reason("品质允许").build(), over);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> snap = (java.util.Map<String, Object>) over.getCustomFields().get("reworkRatio");
        assertThat(snap.get("status")).isEqualTo(ReworkRatioCheck.STATUS_OVER_WITH_REASON);
        assertThat(snap.get("reason")).isEqualTo("品质允许");
        assertThat((BigDecimal) snap.get("actualPercent")).isEqualByComparingTo("15");

        com.cretas.aims.entity.ProductionReport within = new com.cretas.aims.entity.ProductionReport();
        enforceAppYield(true, "5", "105", null, within);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> ok = (java.util.Map<String, Object>) within.getCustomFields().get("reworkRatio");
        assertThat(ok.get("status")).isEqualTo(ReworkRatioCheck.STATUS_WITHIN);
        assertThat(ok.get("reason")).isNull();
    }

    @Test
    @DisplayName("App 路径: 没设上限 / 不是回料 / 投入量未知都不拦、不留快照")
    void yieldNotApplicableNeverBlocks() {
        com.cretas.aims.entity.ProductionReport a = new com.cretas.aims.entity.ProductionReport();
        limit(null);
        enforceAppYield(true, "90", "100", null, a);
        assertThat(a.getCustomFields()).isNull();

        limit("10");
        com.cretas.aims.entity.ProductionReport b = new com.cretas.aims.entity.ProductionReport();
        enforceAppYield(false, "90", "100", null, b);
        assertThat(b.getCustomFields()).isNull();

        com.cretas.aims.entity.ProductionReport c = new com.cretas.aims.entity.ProductionReport();
        check.enforceYieldReport(F, task(), wip(true), new BigDecimal("90"), null, null, c);
        assertThat(c.getCustomFields()).isNull();
    }

    @Test
    @DisplayName("App 路径接线: YieldReportServiceImpl 真的调了 enforceYieldReport, 请求带 reworkRatio")
    void yieldServiceIsWired() throws Exception {
        java.nio.file.Path svc = java.nio.file.Path.of(
                "src/main/java/com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java");
        String src = java.nio.file.Files.readString(svc);
        assertThat(src).contains("reworkRatioCheck.enforceYieldReport(");
        assertThat(src).contains("req.getReworkRatio()");
        assertThat(com.cretas.aims.dto.yield.YieldReportRequest.class.getDeclaredField("reworkRatio")).isNotNull();
    }
}
