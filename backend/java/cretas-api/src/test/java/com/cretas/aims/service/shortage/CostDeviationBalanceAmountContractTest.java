package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.yield.MaterialBalanceTableDTO;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.entity.shortage.CostDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.repository.shortage.CostDeviationCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.production.PlanCostVarianceService;
import com.cretas.aims.service.shortage.CostDeviationCaseWriter.Applied;
import com.cretas.aims.service.shortage.CostDeviationEvaluator.Reading;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitConversionStatus;
import com.cretas.aims.service.yield.balance.MaterialBalanceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * LSM-COSTBI 第 11 项: 物料平衡异常以前只有数量, 金额一律「未知」。现在超阈值工序的未解释差额 × 开计划时主料单价快照
 * (单位走单位契约换算) 估出金额; 定不了价就把原因写进摘要。金额是估价, 不进指纹: 已关闭的实例不因多了估价被重开,
 * 开着的实例在报工事实不变时只补写估价。
 */
class CostDeviationBalanceAmountContractTest {

    private static final BigDecimal FIVE = new BigDecimal("5");

    // ---------- 产品真实入口: CostDeviationScanService.scan → scanBalance → balancePricer → writer.apply ----------

    private final FactorySettingsRepository settings = mock(FactorySettingsRepository.class);
    private final MaterialBalanceService balance = mock(MaterialBalanceService.class);
    private final CostDeviationCaseWriter scanWriter = mock(CostDeviationCaseWriter.class);
    private final PlanStandardCostLineRepository costLines = mock(PlanStandardCostLineRepository.class);
    private final UnitContractService units = mock(UnitContractService.class);

    private CostDeviationScanService scanService() {
        when(settings.findCostDeviationThresholdsByFactoryId("F1"))
                .thenReturn(List.<Object[]>of(new Object[]{null, FIVE}));
        when(scanWriter.apply(any(), any(), any())).thenReturn(Applied.CREATED);
        CostDeviationScanService s = new CostDeviationScanService(settings, mock(ProductionBatchRepository.class),
                mock(PlanCostVarianceService.class), balance, mock(ProcessSecretLlmCostScope.class), scanWriter,
                mock(CostDeviationNotifier.class), CostDeviationScanDefaults.none());
        ReflectionTestUtils.setField(s, "standardCostLineRepository", costLines);
        ReflectionTestUtils.setField(s, "unitContractService", units);
        return s;
    }

    private static MaterialBalanceTableDTO.Row row(String name, String unexplained, String pct, String unit) {
        return MaterialBalanceTableDTO.Row.builder().processName(name).unit(unit)
                .unexplained(unexplained == null ? null : new BigDecimal(unexplained))
                .unexplainedPct(pct == null ? null : new BigDecimal(pct)).build();
    }

    private static MaterialBalanceTableDTO.PlanBlock block(MaterialBalanceTableDTO.Row... rows) {
        return MaterialBalanceTableDTO.PlanBlock.builder().planId("P1").planNumber("PL-1").rows(List.of(rows)).build();
    }

    private void table(MaterialBalanceTableDTO.Row... rows) {
        when(balance.table(eq("F1"), any(), any(), any()))
                .thenReturn(MaterialBalanceTableDTO.builder().plans(List.of(block(rows))).build());
    }

    private static PlanStandardCostLine main(String name, String price, String priceUnit) {
        PlanStandardCostLine l = new PlanStandardCostLine();
        l.setLineType(PlanStandardCostLine.TYPE_MATERIAL);
        l.setStandardBasis(PlanStandardCostLine.BASIS_RAW_INPUT_SHARE);
        l.setMaterialName(name);
        l.setUnitPrice(price == null ? null : new BigDecimal(price));
        l.setPriceUnit(priceUnit);
        return l;
    }

    private static UnitConversionResult converted(String qty) {
        return new UnitConversionResult(UnitConversionStatus.CONVERTED, new BigDecimal(qty), "kg", "斤",
                List.of("kg", "斤"), null, null, null);
    }

    private Reading scannedReading() {
        ArgumentCaptor<Reading> captor = ArgumentCaptor.forClass(Reading.class);
        verify(scanWriter).apply(eq("F1"), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void scan_pricesBalanceGap_withMainMaterialSnapshot_throughUnitContract() {
        table(row("切配", "10", "12.5", "kg"), row("腌制", "3", "2", "kg"));   // 腌制在阈值内, 不计金额
        PlanStandardCostLine seasoning = main("盐", "3", "kg");
        seasoning.setStandardBasis(PlanStandardCostLine.BASIS_PER_RAW_INPUT_KG);  // 调料不是主料
        when(costLines.findByFactoryIdAndPlanIdOrderByIdAsc("F1", "P1"))
                .thenReturn(List.of(main("猪后腿", "20", "斤"), seasoning));
        when(units.convert(eq(BigDecimal.ONE), any(UnitConversionContext.class))).thenReturn(converted("2"));

        scanService().scan("F1", "T");

        Reading r = scannedReading();
        // 1 kg = 2 斤, 20 元/斤 → 40 元/kg; 超阈值的只有切配 10 kg → 400.00
        assertThat(r.amount()).isEqualByComparingTo("400.00");
        assertThat(r.summary()).contains("差异金额按主料「猪后腿」开计划时单价估算");
        ArgumentCaptor<UnitConversionContext> ctx = ArgumentCaptor.forClass(UnitConversionContext.class);
        verify(units).convert(eq(BigDecimal.ONE), ctx.capture());
        assertThat(ctx.getValue().factoryId()).isEqualTo("F1");   // 换算按本厂单位契约, 不跨厂
        assertThat(ctx.getValue().fromUnit()).isEqualTo("kg");
        assertThat(ctx.getValue().toUnit()).isEqualTo("斤");
        verify(costLines).findByFactoryIdAndPlanIdOrderByIdAsc("F1", "P1");
    }

    @Test
    void scan_multipleMainMaterials_amountStaysUnknown_andSaysWhy() {
        table(row("切配", "10", "12.5", "kg"));
        when(costLines.findByFactoryIdAndPlanIdOrderByIdAsc("F1", "P1"))
                .thenReturn(List.of(main("猪后腿", "20", "斤"), main("鸡腿", "12", "kg")));

        scanService().scan("F1", "T");

        Reading r = scannedReading();
        assertThat(r.amount()).isNull();
        assertThat(r.summary()).contains("差异金额算不出: 有多个主料");
        verifyNoInteractions(units);
    }

    @Test
    void scan_unitNotConvertible_amountStaysUnknown_noGuessedRate() {
        table(row("切配", "10", "12.5", "盒"));
        when(costLines.findByFactoryIdAndPlanIdOrderByIdAsc("F1", "P1")).thenReturn(List.of(main("猪后腿", "20", "斤")));
        when(units.convert(eq(BigDecimal.ONE), any(UnitConversionContext.class))).thenReturn(new UnitConversionResult(
                UnitConversionStatus.INCOMPATIBLE_DIMENSION, null, "盒", "斤", List.of(), null, null, "量纲不同"));

        scanService().scan("F1", "T");

        Reading r = scannedReading();
        assertThat(r.amount()).isNull();
        assertThat(r.summary()).contains("差异金额算不出: 报工单位 盒 换算不到单价单位 斤");
    }

    @Test
    void scan_noSnapshot_amountUnknown_withReason() {
        table(row("切配", "10", "12.5", "kg"));
        when(costLines.findByFactoryIdAndPlanIdOrderByIdAsc("F1", "P1")).thenReturn(List.of());
        scanService().scan("F1", "T");
        assertThat(scannedReading().summary()).contains("差异金额算不出: 这张计划没有主料单价快照");
    }

    // ---------- 指纹: 金额不进指纹 (阴性对照: 报工事实变了指纹要变; 成本差异的金额仍进指纹) ----------

    private static final MaterialBalanceTableDTO.PlanBlock GAP = block(row("切配", "10", "12.5", "kg"));

    private static Reading priced(MaterialBalanceTableDTO.PlanBlock b, String price) {
        return CostDeviationEvaluator.evaluateBalance(b, FIVE,
                r -> new CostDeviationEvaluator.RowPrice(new BigDecimal(price), "按主料单价估算", null));
    }

    @Test
    void balanceFingerprint_ignoresEstimatedAmount_butFollowsReportedFacts() {
        Reading unpriced = CostDeviationEvaluator.evaluateBalance(GAP, FIVE);
        Reading a = priced(GAP, "40");
        Reading b = priced(GAP, "55");
        assertThat(a.amount()).isEqualByComparingTo("400.00");
        assertThat(b.amount()).isEqualByComparingTo("550.00");
        assertThat(a.fingerprint()).isEqualTo(unpriced.fingerprint()).isEqualTo(b.fingerprint());
        // 阴性对照: 未解释差额占比变了 = 事实变了, 指纹必须变
        assertThat(priced(block(row("切配", "10", "13", "kg")), "40").fingerprint()).isNotEqualTo(a.fingerprint());
    }

    // ---------- writer: 指纹不变时补写估价; 已关闭的实例不被重开 ----------

    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ShortageCaseEventRepository events = mock(ShortageCaseEventRepository.class);
    private final CostDeviationCaseDetailRepository details = mock(CostDeviationCaseDetailRepository.class);
    private final CostDeviationCaseWriter writer = new CostDeviationCaseWriter(cases, events, details,
            mock(UserRepository.class),
            mock(CostDeviationOwnerResolver.class, inv -> new CostDeviationOwnerResolver.Owner("finance_manager", null, "DEFAULT")));

    @Test
    void writer_sameFacts_backfillsEstimate_withoutRefreshEventOrStageChange() {
        Reading before = CostDeviationEvaluator.evaluateBalance(GAP, FIVE);   // 上线前落库的读数: 金额未知
        ShortageCase open = new ShortageCase();
        open.setId("c1");
        open.setStage(ShortageCase.Stage.EXECUTING);
        open.setInputFingerprint(before.fingerprint());
        CostDeviationCaseDetail d = new CostDeviationCaseDetail();
        d.setSummary(before.summary());
        when(cases.findOpenForUpdate(any(), any(), any())).thenReturn(Optional.of(open));
        when(details.findById("c1")).thenReturn(Optional.of(d));

        Reading now = priced(GAP, "40");
        assertThat(writer.apply("F1", now, "T")).isEqualTo(Applied.UNCHANGED);
        assertThat(d.getAmountVariance()).isEqualByComparingTo("400.00");
        assertThat(d.getSummary()).isEqualTo(now.summary());
        verify(details).save(d);
        verifyNoInteractions(events);                    // 不记「读数变化」
        verify(cases, never()).save(any());              // 实例本身 (阶段 / 指纹) 不动
        assertThat(open.getStage()).isEqualTo(ShortageCase.Stage.EXECUTING);

        // 同一估价再扫一次: 不重复写
        clearInvocations(details);
        when(details.findById("c1")).thenReturn(Optional.of(d));
        assertThat(writer.apply("F1", priced(GAP, "40"), "T")).isEqualTo(Applied.UNCHANGED);
        verify(details, never()).save(any());
    }

    @Test
    void writer_closedBeforeEstimatesExisted_isNotReopenedByTheEstimate() {
        ShortageCase closed = new ShortageCase();
        closed.setInputFingerprint(CostDeviationEvaluator.evaluateBalance(GAP, FIVE).fingerprint());
        closed.setClosedAt(LocalDateTime.now());
        when(cases.findOpenForUpdate(any(), any(), any())).thenReturn(Optional.empty());
        when(details.findClosedCases(any(), any(), any(), any())).thenReturn(List.of(closed));

        assertThat(writer.apply("F1", priced(GAP, "40"), "T")).isEqualTo(Applied.ALREADY_RESOLVED);
        verify(cases, never()).save(any());
        verify(details, never()).save(any());
    }

    // ---------- 说明里的金额事实: 有采购价权限才出现 ----------

    @Test
    void explanation_showsEstimatedAmount_onlyWhenNotMasked() {
        CostDeviationCaseDetail d = new CostDeviationCaseDetail();
        d.setPlanNumber("PL-1");
        d.setMetricPct(new BigDecimal("12.5"));
        d.setThresholdPct(FIVE);
        d.setAmountVariance(new BigDecimal("400.00"));
        var shown = CostDeviationExplanation.build(ShortageCase.SourceType.MATERIAL_BALANCE, d, null, null, false);
        assertThat(shown.knownFacts()).anyMatch(f -> f.text().contains("折合约 400 元"));
        var masked = CostDeviationExplanation.build(ShortageCase.SourceType.MATERIAL_BALANCE, d, null, null, true);
        assertThat(masked.knownFacts()).noneMatch(f -> f.text().contains("400"));
    }
}
