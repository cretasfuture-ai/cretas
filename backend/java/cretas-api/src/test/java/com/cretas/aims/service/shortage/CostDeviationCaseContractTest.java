package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.dto.yield.MaterialBalanceTableDTO;
import com.cretas.aims.entity.shortage.CostDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.shortage.CostDeviationCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.production.PlanCostVarianceService;
import com.cretas.aims.service.shortage.CostDeviationCaseWriter.Applied;
import com.cretas.aims.service.shortage.CostDeviationEvaluator.Outcome;
import com.cretas.aims.service.shortage.CostDeviationEvaluator.Reading;
import com.cretas.aims.service.yield.balance.MaterialBalanceService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CostDeviationCaseContractTest {

    private static final BigDecimal FIVE = new BigDecimal("5");

    private static PlanCostVarianceDTO variance(String rate, int incomparable) {
        return PlanCostVarianceDTO.builder().planId("P1").planNumber("PL-1").comparable(true)
                .varianceRate(new BigDecimal(rate)).totalVariance(new BigDecimal("120.00"))
                .incomparableItemCount(incomparable).build();
    }

    private static MaterialBalanceTableDTO.Row row(String name, String pct) {
        return MaterialBalanceTableDTO.Row.builder().processName(name)
                .unexplainedPct(pct == null ? null : new BigDecimal(pct)).build();
    }

    private static MaterialBalanceTableDTO.PlanBlock block(MaterialBalanceTableDTO.Row... rows) {
        return MaterialBalanceTableDTO.PlanBlock.builder().planId("P1").rows(List.of(rows)).build();
    }

    @Test
    void cost_overThreshold_bothDirections_atThresholdIsWithin() {
        assertThat(CostDeviationEvaluator.evaluateCost(variance("5.01", 0), FIVE).outcome()).isEqualTo(Outcome.DEVIATED);
        assertThat(CostDeviationEvaluator.evaluateCost(variance("-8", 0), FIVE).outcome()).isEqualTo(Outcome.DEVIATED);
        assertThat(CostDeviationEvaluator.evaluateCost(variance("5", 0), FIVE).outcome()).isEqualTo(Outcome.WITHIN);
    }

    @Test
    void cost_notComparable_isUnknown_notWithin() {
        PlanCostVarianceDTO v = PlanCostVarianceDTO.builder().planId("P1").comparable(false)
                .comparabilityReason("没有快照").build();
        Reading r = CostDeviationEvaluator.evaluateCost(v, FIVE);
        assertThat(r.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(r.metricPct()).isNull();
        assertThat(r.amount()).isNull();
    }

    @Test
    void cost_partlyIncomparable_carriesUnknownCountIntoSummary() {
        Reading r = CostDeviationEvaluator.evaluateCost(variance("9", 2), FIVE);
        assertThat(r.unknownItems()).isEqualTo(2);
        assertThat(r.summary()).contains("2 项无法比较");
    }

    @Test
    void balance_amountStaysUnknown_andIncomparableRowsAreNotZero() {
        Reading r = CostDeviationEvaluator.evaluateBalance(block(row("切配", "12.5"), row("腌制", null)), FIVE);
        assertThat(r.outcome()).isEqualTo(Outcome.DEVIATED);
        assertThat(r.amount()).isNull();
        assertThat(r.unknownItems()).isEqualTo(1);
        assertThat(CostDeviationEvaluator.evaluateBalance(block(row("腌制", null)), FIVE).outcome())
                .isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void balance_unnamedProcessAndInternalProduct_notShownRaw() {
        var b = MaterialBalanceTableDTO.PlanBlock.builder().planId("P1").productName("__RAW_WORKFLOW_OWNER__X")
                .rows(List.of(row(null, "9"))).build();
        Reading r = CostDeviationEvaluator.evaluateBalance(b, FIVE);
        assertThat(r.summary()).contains("未命名工序").doesNotContain("null");
        assertThat(r.productName()).isNull();
    }

    @Test
    void fingerprint_ignoresThreshold() {
        assertThat(CostDeviationEvaluator.evaluateCost(variance("9", 0), FIVE).fingerprint())
                .isEqualTo(CostDeviationEvaluator.evaluateCost(variance("9", 0), new BigDecimal("3")).fingerprint());
    }

    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final CostDeviationCaseDetailRepository details = mock(CostDeviationCaseDetailRepository.class);
    private final CostDeviationCaseWriter writer = new CostDeviationCaseWriter(cases,
            mock(ShortageCaseEventRepository.class), details, mock(UserRepository.class),
            mock(CostDeviationOwnerResolver.class, inv -> new CostDeviationOwnerResolver.Owner("finance_manager", null, "DEFAULT")));

    private Reading deviated() {
        return CostDeviationEvaluator.evaluateCost(variance("9", 0), FIVE);
    }

    @Test
    void apply_createsCase_ownedByFinance() {
        when(cases.findOpenForUpdate(any(), any(), any())).thenReturn(Optional.empty());
        when(details.findClosedCases(any(), any(), any(), any())).thenReturn(List.of());
        when(cases.save(any())).thenAnswer(i -> {
            ShortageCase c = i.getArgument(0);
            c.setId("c1");
            return c;
        });
        assertThat(writer.apply("F1", deviated(), "MANUAL_SCAN")).isEqualTo(Applied.CREATED);
        verify(cases).save(argThat(c -> "finance_manager".equals(c.getOwnerRole())
                && c.getStage() == ShortageCase.Stage.DETECTED));
        verify(details).save(argThat(d -> "c1".equals(d.getCaseId()) && d.getAmountVariance() != null));
    }

    @Test
    void apply_withinWithNoOpenCase_writesNothing() {
        when(cases.findOpenForUpdate(any(), any(), any())).thenReturn(Optional.empty());
        assertThat(writer.apply("F1", CostDeviationEvaluator.evaluateCost(variance("1", 0), FIVE), "T"))
                .isEqualTo(Applied.NONE);
        verify(cases, never()).save(any());
    }

    @Test
    void apply_sameReadingAfterClosed_doesNotReopen_changedReadingDoes() {
        ShortageCase closed = new ShortageCase();
        closed.setInputFingerprint(deviated().fingerprint());
        closed.setClosedAt(LocalDateTime.now());
        when(cases.findOpenForUpdate(any(), any(), any())).thenReturn(Optional.empty());
        when(details.findClosedCases(any(), any(), any(), any())).thenReturn(List.of(closed));
        assertThat(writer.apply("F1", deviated(), "T")).isEqualTo(Applied.ALREADY_RESOLVED);
        verify(cases, never()).save(any());

        when(cases.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(writer.apply("F1", CostDeviationEvaluator.evaluateCost(variance("20", 0), FIVE), "T"))
                .isEqualTo(Applied.CREATED);
    }

    @Test
    void apply_backWithinThreshold_keepsCaseOpen() {
        ShortageCase open = new ShortageCase();
        open.setId("c1");
        open.setStage(ShortageCase.Stage.EXECUTING);
        open.setInputFingerprint("old");
        when(cases.findOpenForUpdate(any(), any(), any())).thenReturn(Optional.of(open));
        when(details.findById("c1")).thenReturn(Optional.of(new CostDeviationCaseDetail()));
        assertThat(writer.apply("F1", CostDeviationEvaluator.evaluateCost(variance("1", 0), FIVE), "T"))
                .isEqualTo(Applied.BELOW_THRESHOLD);
        assertThat(open.getClosedAt()).isNull();
        assertThat(open.getStage()).isEqualTo(ShortageCase.Stage.EXECUTING);
    }

    @Test
    void startVerify_needsOwner_resolve_needsVerifyAndNote() {
        ShortageCase c = new ShortageCase();
        c.setId("c1");
        c.setFactoryId("F1");
        c.setStage(ShortageCase.Stage.DETECTED);
        when(cases.findByIdAndFactoryIdAndSourceTypeIn(eq("c1"), eq("F1"), any())).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> writer.startVerify("F1", "c1", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("负责人");
        assertThatThrownBy(() -> writer.resolve("F1", "c1", CostDeviationCaseDetail.Resolution.CORRECTED, "x", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("开始核查");
        c.setStage(ShortageCase.Stage.EXECUTING);
        assertThatThrownBy(() -> writer.resolve("F1", "c1", CostDeviationCaseDetail.Resolution.CORRECTED, " ", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("都要填写");
    }

    @Test
    void shortageEntries_onlySeeSalesOrderCases() {
        ShortageCaseRepository repo = mock(ShortageCaseRepository.class);
        when(repo.findByIdAndFactoryId(any(), any())).thenCallRealMethod();
        repo.findByIdAndFactoryId("c1", "F1");
        verify(repo).findByIdAndFactoryIdAndSourceTypeIn("c1", "F1", List.of(ShortageCase.SourceType.SALES_ORDER));
    }

    @Test
    void scan_notConfigured_computesAndWritesNothing() {
        FactorySettingsRepository settings = mock(FactorySettingsRepository.class);
        when(settings.findCostDeviationThresholdsByFactoryId("F1"))
                .thenReturn(List.<Object[]>of(new Object[]{null, null}));
        ProductionBatchRepository batches = mock(ProductionBatchRepository.class);
        MaterialBalanceService balance = mock(MaterialBalanceService.class);
        CostDeviationCaseWriter w = mock(CostDeviationCaseWriter.class);
        var result = new CostDeviationScanService(settings, batches, mock(PlanCostVarianceService.class), balance,
                mock(ProcessSecretLlmCostScope.class), w, mock(CostDeviationNotifier.class),
                CostDeviationScanDefaults.none()).scan("F1", "T");
        assertThat(result.varianceConfigured()).isFalse();
        assertThat(result.balanceConfigured()).isFalse();
        assertThat(result.evaluated()).isZero();
        verifyNoInteractions(batches, balance, w);
    }
}
