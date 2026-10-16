package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import jakarta.persistence.EntityManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReversalConsumptionCleanupServiceContractTest {
    private static final String FACTORY = "F006";
    private static final String PLAN = "plan-1";
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 10, 16, 0, 0);
    private static final LocalDateTime CLEANED_AT = CUTOFF.plusMinutes(1);

    @Mock private MaterialConsumptionRepository consumptionRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private ProductionPlanRepository planRepository;
    @Mock private ReportReversalLogRepository reversalLogRepository;
    @Mock private EntityManager entityManager;
    private ReversalConsumptionCleanupService service;

    @BeforeEach
    void setUp() {
        service = new ReversalConsumptionCleanupService(
                consumptionRepository, batchRepository, planRepository, reversalLogRepository);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(consumptionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        com.cretas.aims.entity.ProductionPlan plan = new com.cretas.aims.entity.ProductionPlan();
        plan.setId(PLAN); plan.setFactoryId(FACTORY);
        when(planRepository.findByIdForUpdate(PLAN)).thenReturn(Optional.of(plan));
    }

    @Test
    void actualReversalDeletesOnlySameFactoryBatchUnpostedRowsIncludingNullPlan() {
        MaterialConsumption nullPlan = row(1, FACTORY, 7L, null, null);
        nullPlan.setSourceType("RAW_MATERIAL");
        MaterialConsumption packaging = row(5, FACTORY, 7L, null, null);
        packaging.setSourceType("PACKAGING");
        MaterialConsumption seasoning = row(6, FACTORY, 7L, null, null);
        seasoning.setSourceType("SEASONING");
        MaterialConsumption posted = row(2, FACTORY, 7L, PLAN, CUTOFF);
        MaterialConsumption otherBatch = row(3, FACTORY, 8L, null, null);
        MaterialConsumption otherFactory = row(4, "F007", 7L, null, null);
        when(batchRepository.findByIdAndFactoryIdForUpdate(7L, FACTORY))
                .thenReturn(Optional.of(batch(7L, FACTORY, null)));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(7L, FACTORY))
                .thenReturn(new ArrayList<>(List.of(nullPlan, packaging, seasoning, posted, otherBatch, otherFactory)));

        int count = service.softDeleteUnpostedForReversedBatch(FACTORY, 7L, null, CLEANED_AT);

        assertThat(count).isEqualTo(3);
        assertThat(nullPlan.getDeletedAt()).isEqualTo(CLEANED_AT);
        assertThat(packaging.getDeletedAt()).isEqualTo(CLEANED_AT);
        assertThat(seasoning.getDeletedAt()).isEqualTo(CLEANED_AT);
        assertThat(posted.getDeletedAt()).isNull();
        assertThat(otherBatch.getDeletedAt()).isNull();
        assertThat(otherFactory.getDeletedAt()).isNull();
        assertThat(nullPlan.getQuantity()).isEqualByComparingTo("3");
        verify(consumptionRepository).save(nullPlan);
        verify(consumptionRepository, never()).save(posted);
    }

    @Test
    void historicalCleanupUsesAllPlanBatchesAndCreatedAtCutoff() {
        ProductionBatch oldBatch = batch(7L, FACTORY, PLAN);
        ProductionBatch newBatch = batch(8L, FACTORY, PLAN);
        MaterialConsumption old = row(1, FACTORY, 7L, null, null);
        old.setSourceType("RAW_MATERIAL");
        old.setCreatedAt(CUTOFF.minusSeconds(1));
        MaterialConsumption later = row(2, FACTORY, 7L, null, null);
        later.setCreatedAt(CUTOFF.plusSeconds(1));
        MaterialConsumption newBatchRow = row(3, FACTORY, 8L, null, null);
        newBatchRow.setCreatedAt(CUTOFF.plusSeconds(1));
        when(batchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN))
                .thenReturn(List.of(oldBatch, newBatch));
        when(batchRepository.findByIdAndFactoryIdForUpdate(7L, FACTORY))
                .thenReturn(Optional.of(oldBatch));
        when(batchRepository.findByIdAndFactoryIdForUpdate(8L, FACTORY))
                .thenReturn(Optional.of(newBatch));
        ReportReversalLog done = log(7L, FACTORY, PLAN, ReportReversalLog.ReversalStatus.DONE);
        done.setCreatedAt(CUTOFF);
        done.setApprovedAt(CUTOFF);
        done.setUpdatedAt(CUTOFF);
        when(reversalLogRepository.findByBatchIdAndReversalScopeAndDeletedAtIsNull(
                eq(7L), eq(ReportReversalLog.ReversalScope.WHOLE_ORDER)))
                .thenReturn(Optional.of(done));
        when(reversalLogRepository.findByBatchIdAndReversalScopeAndDeletedAtIsNull(
                eq(8L), eq(ReportReversalLog.ReversalScope.WHOLE_ORDER)))
                .thenReturn(Optional.empty());
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(7L, FACTORY))
                .thenReturn(new ArrayList<>(List.of(old, later)));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(8L, FACTORY))
                .thenReturn(new ArrayList<>(List.of(newBatchRow)));

        int count = service.cleanupHistoricalDoneReversals(FACTORY, PLAN, CLEANED_AT);

        assertThat(count).isEqualTo(1);
        assertThat(old.getDeletedAt()).isEqualTo(CLEANED_AT);
        assertThat(later.getDeletedAt()).isNull();
        assertThat(newBatchRow.getDeletedAt()).isNull();
    }

    @Test
    void historicalCleanupFailsClosedWithoutDoneAndReliableCutoff() {
        ProductionBatch batch = batch(7L, FACTORY, PLAN);
        when(batchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN))
                .thenReturn(List.of(batch));
        when(batchRepository.findByIdAndFactoryIdForUpdate(7L, FACTORY))
                .thenReturn(Optional.of(batch));
        ReportReversalLog pending = log(7L, FACTORY, PLAN, ReportReversalLog.ReversalStatus.PENDING);
        when(reversalLogRepository.findByBatchIdAndReversalScopeAndDeletedAtIsNull(
                eq(7L), eq(ReportReversalLog.ReversalScope.WHOLE_ORDER)))
                .thenReturn(Optional.of(pending));

        assertThat(service.cleanupHistoricalDoneReversals(FACTORY, PLAN, CLEANED_AT)).isZero();
        verify(consumptionRepository, never()).findByProductionBatchIdAndFactoryId(7L, FACTORY);
    }

    @Test
    void historicalCleanupFailsClosedForMissingCreatedAtFutureCutoffAndWrongLineage() {
        ProductionBatch batch = batch(7L, FACTORY, PLAN);
        when(batchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN)).thenReturn(List.of(batch));
        when(batchRepository.findByIdAndFactoryIdForUpdate(7L, FACTORY)).thenReturn(Optional.of(batch));
        ReportReversalLog done = log(7L, FACTORY, PLAN, ReportReversalLog.ReversalStatus.DONE);
        done.setCreatedAt(CUTOFF);
        done.setUpdatedAt(CUTOFF);
        when(reversalLogRepository.findByBatchIdAndReversalScopeAndDeletedAtIsNull(
                eq(7L), eq(ReportReversalLog.ReversalScope.WHOLE_ORDER))).thenReturn(Optional.of(done));
        MaterialConsumption missingCreated = row(9, FACTORY, 7L, PLAN, null);
        missingCreated.setCreatedAt(null);
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(7L, FACTORY))
                .thenReturn(new ArrayList<>(List.of(missingCreated)));

        assertThat(service.cleanupHistoricalDoneReversals(FACTORY, PLAN, CUTOFF.plusSeconds(1))).isZero();
        assertThat(missingCreated.getDeletedAt()).isNull();

        done.setUpdatedAt(CUTOFF.plusSeconds(1));
        assertThat(service.cleanupHistoricalDoneReversals(FACTORY, PLAN, CUTOFF)).isZero();
        done.setUpdatedAt(CUTOFF);
        done.setPlanId("other-plan");
        assertThat(service.cleanupHistoricalDoneReversals(FACTORY, PLAN, CUTOFF.plusSeconds(1))).isZero();
    }

    @Test
    void mutableUpdatedAtCannotAuthorizeRowsCreatedAfterStableAnchor() {
        ProductionBatch batch = batch(7L, FACTORY, PLAN);
        when(batchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN)).thenReturn(List.of(batch));
        when(batchRepository.findByIdAndFactoryIdForUpdate(7L, FACTORY)).thenReturn(Optional.of(batch));
        ReportReversalLog done = log(7L, FACTORY, PLAN, ReportReversalLog.ReversalStatus.DONE);
        done.setCreatedAt(CUTOFF);
        done.setApprovedAt(CUTOFF);
        done.setUpdatedAt(CUTOFF.plusHours(1));
        when(reversalLogRepository.findByBatchIdAndReversalScopeAndDeletedAtIsNull(
                eq(7L), eq(ReportReversalLog.ReversalScope.WHOLE_ORDER))).thenReturn(Optional.of(done));
        MaterialConsumption original = row(11, FACTORY, 7L, PLAN, null);
        original.setSourceType("RAW_MATERIAL");
        original.setCreatedAt(CUTOFF);
        MaterialConsumption reReport = row(12, FACTORY, 7L, PLAN, null);
        reReport.setCreatedAt(CUTOFF.plusMinutes(30));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(7L, FACTORY))
                .thenReturn(new ArrayList<>(List.of(original, reReport)));

        assertThat(service.cleanupHistoricalDoneReversals(FACTORY, PLAN, CUTOFF.plusHours(2))).isEqualTo(1);
        assertThat(original.getDeletedAt()).isEqualTo(CUTOFF.plusHours(2));
        assertThat(reReport.getDeletedAt()).isNull();
    }

    @Test
    void refreshRecheckPreservesRowPostedConcurrentlyAndEqualCutoffIsEligible() {
        when(batchRepository.findByIdAndFactoryIdForUpdate(7L, FACTORY))
                .thenReturn(Optional.of(batch(7L, FACTORY, PLAN)));
        MaterialConsumption equal = row(10, FACTORY, 7L, PLAN, null);
        equal.setSourceType("RAW_MATERIAL");
        equal.setCreatedAt(CUTOFF);
        doAnswer(invocation -> { equal.setInterimSettledAt(CUTOFF.plusSeconds(1)); return null; })
                .when(entityManager).refresh(eq(equal), any(jakarta.persistence.LockModeType.class));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(7L, FACTORY))
                .thenReturn(new ArrayList<>(List.of(equal)));

        assertThat(service.softDeleteUnpostedForReversedBatch(FACTORY, 7L, PLAN, CUTOFF)).isZero();
        assertThat(equal.getDeletedAt()).isNull();
    }

    @Test
    void immediateOrUnknownSourceTypesAreNeverRetiredByReversalCleanup() {
        when(batchRepository.findByIdAndFactoryIdForUpdate(7L, FACTORY))
                .thenReturn(Optional.of(batch(7L, FACTORY, PLAN)));
        MaterialConsumption autoBom = row(20, FACTORY, 7L, PLAN, null);
        autoBom.setSourceType("AUTO_BOM");
        MaterialConsumption adjustment = row(21, FACTORY, 7L, PLAN, null);
        adjustment.setSourceType("ADJUSTMENT");
        MaterialConsumption unknown = row(22, FACTORY, 7L, PLAN, null);
        unknown.setSourceType(null);
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(7L, FACTORY))
                .thenReturn(new ArrayList<>(List.of(autoBom, adjustment, unknown)));

        assertThat(service.softDeleteUnpostedForReversedBatch(FACTORY, 7L, PLAN, CLEANED_AT)).isZero();
        assertThat(autoBom.getDeletedAt()).isNull();
        assertThat(adjustment.getDeletedAt()).isNull();
        assertThat(unknown.getDeletedAt()).isNull();
    }

    @Test
    void parentCallsitesRequireCollaboratorAndContainRealInvocations() throws Exception {
        Field reportField = Class.forName("com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl")
                .getDeclaredField("reversalConsumptionCleanupService");
        Field settleField = Class.forName("com.cretas.aims.service.yield.impl.InterimSettleServiceImpl")
                .getDeclaredField("reversalConsumptionCleanupService");
        assertThat(reportField.getAnnotation(org.springframework.beans.factory.annotation.Autowired.class).required()).isTrue();
        assertThat(settleField.getAnnotation(org.springframework.beans.factory.annotation.Autowired.class).required()).isTrue();
        String reportSource = Files.readString(Path.of("src/main/java/com/cretas/aims/service/reversal/impl/ReportReversalServiceImpl.java"));
        String settleSource = Files.readString(Path.of("src/main/java/com/cretas/aims/service/yield/impl/InterimSettleServiceImpl.java"));
        assertThat(reportSource).contains("softDeleteUnpostedForReversedBatch(");
        assertThat(settleSource).contains("cleanupHistoricalDoneReversals(");
        assertThat(reportSource).contains("reversalConsumptionCleanupService.lockReversalScope(");
    }

    @Test
    void reversalScopeLocksPlanBeforeBatchAndValidatesLineage() {
        com.cretas.aims.entity.ProductionPlan plan = new com.cretas.aims.entity.ProductionPlan();
        plan.setId(PLAN); plan.setFactoryId(FACTORY);
        ProductionBatch batch = batch(7L, FACTORY, PLAN);
        when(planRepository.findByIdForUpdate(PLAN)).thenReturn(Optional.of(plan));
        when(batchRepository.findByIdAndFactoryIdForUpdate(7L, FACTORY)).thenReturn(Optional.of(batch));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(planRepository, batchRepository);

        service.lockReversalScope(FACTORY, 7L, PLAN);

        order.verify(planRepository).findByIdForUpdate(PLAN);
        order.verify(batchRepository).findByIdAndFactoryIdForUpdate(7L, FACTORY);
    }

    private static MaterialConsumption row(int id, String factory, Long batch, String plan,
                                           LocalDateTime settledAt) {
        MaterialConsumption row = new MaterialConsumption();
        row.setId(id);
        row.setFactoryId(factory);
        row.setProductionBatchId(batch);
        row.setProductionPlanId(plan);
        row.setQuantity(new BigDecimal("3"));
        row.setInterimSettledAt(settledAt);
        row.setCreatedAt(CUTOFF.minusMinutes(1));
        return row;
    }

    private static ProductionBatch batch(Long id, String factory, String plan) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(id);
        batch.setFactoryId(factory);
        batch.setProductionPlanId(plan);
        return batch;
    }

    private static ReportReversalLog log(Long batch, String factory, String plan,
                                         ReportReversalLog.ReversalStatus status) {
        ReportReversalLog log = ReportReversalLog.builder()
                .batchId(batch).factoryId(factory).planId(plan)
                .submittedBy(1L).status(status)
                .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                .build();
        log.setDeletedAt(null);
        return log;
    }
}
