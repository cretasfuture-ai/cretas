package com.cretas.aims.service.impl;

import com.cretas.aims.engine.DynamicFieldService;
import com.cretas.aims.engine.ValidationRuleEvaluator;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.ProductionAlert;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.QualityStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * Round 10 Task 4 — verifies Canvas Integration Template wiring in
 * QualityInspectionServiceImpl.createInspection:
 * <ol>
 *   <li>dynamicFieldService.setDynamicFields is called with module "quality_inspection"
 *       when customFields is populated</li>
 *   <li>setDynamicFields is skipped when customFields is empty (no wasted DB call)</li>
 * </ol>
 *
 * <p>A refactor that removes the dynamic-field hook would regress customer-configured
 * fields to "silently dropped" — this test catches that at build time.</p>
 */
@ExtendWith(MockitoExtension.class)
class QualityInspectionServiceImplTemplateTest {

    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private ProductionAlertRepository productionAlertRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ValidationRuleEvaluator validationRuleEvaluator;
    @Mock private DynamicFieldService dynamicFieldService;
    @Mock private ProductionBatchRepository productionBatchRepository;

    @InjectMocks
    private QualityInspectionServiceImpl service;

    @BeforeEach
    void setUp() {
        // @InjectMocks handles constructor-injected final fields; the two optional
        // @Autowired fields (validationRuleEvaluator, dynamicFieldService) are set
        // via reflection because Mockito does not populate field-level @Autowired.
        ReflectionTestUtils.setField(service, "validationRuleEvaluator", validationRuleEvaluator);
        ReflectionTestUtils.setField(service, "dynamicFieldService", dynamicFieldService);
        ReflectionTestUtils.setField(service, "productionBatchRepository", productionBatchRepository);
        lenient().when(productionAlertRepository.save(any(ProductionAlert.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(productionBatchRepository.findById(any())).thenAnswer(invocation -> {
            ProductionBatch batch = new ProductionBatch();
            batch.setId(invocation.getArgument(0));
            batch.setFactoryId("F001");
            return java.util.Optional.of(batch);
        });
    }

    @Test
    void createInspection_withCustomFields_invokesSetDynamicFields() {
        QualityInspection input = new QualityInspection();
        input.setId("qi-1");
        input.setProductionBatchId(42L);
        input.setResult("PASS");
        input.setPassRate(new BigDecimal("99.0"));
        Map<String, Object> cf = new HashMap<>();
        cf.put("reinspectResult", "PASS");
        cf.put("temperatureDeviation", new BigDecimal("0.5"));
        input.setCustomFields(cf);

        when(qualityInspectionRepository.save(any(QualityInspection.class))).thenAnswer(inv -> inv.getArgument(0));

        QualityInspection saved = service.createInspection("F001", input);

        assertEquals("qi-1", saved.getId());
        verify(dynamicFieldService, times(1))
                .setDynamicFields(eq("F001"), eq("quality_inspection"), eq("qi-1"), eq(cf));
    }

    @Test
    void createInspection_withoutCustomFields_skipsSetDynamicFields() {
        QualityInspection input = new QualityInspection();
        input.setId("qi-2");
        input.setProductionBatchId(43L);
        input.setResult("PASS");
        input.setPassRate(new BigDecimal("98.0"));
        input.setCustomFields(new HashMap<>());

        when(qualityInspectionRepository.save(any(QualityInspection.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createInspection("F001", input);

        verify(dynamicFieldService, never())
                .setDynamicFields(any(), any(), any(), any());
    }

    @Test
    void createInspection_completesExistingPendingTaskWithoutCreatingHistoryRow() {
        QualityInspection pending = new QualityInspection();
        pending.setId("qi-pending");
        pending.setFactoryId("F001");
        pending.setProductionBatchId(45L);
        pending.setResult("PENDING");
        pending.setInspectionDate(java.time.LocalDate.of(2026, 10, 28));
        QualityInspection completion = new QualityInspection();
        completion.setProductionBatchId(45L);
        completion.setResult("PASS");
        completion.setSampleSize(BigDecimal.ONE);
        completion.setPassCount(BigDecimal.ONE);
        completion.setFailCount(BigDecimal.ZERO);
        completion.setPassRate(BigDecimal.valueOf(100));
        completion.setInspectionDate(java.time.LocalDate.of(2026, 10, 29));
        when(qualityInspectionRepository.findByFactoryIdAndProductionBatchId("F001", 45L))
                .thenReturn(List.of(pending));
        when(qualityInspectionRepository.save(any(QualityInspection.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        QualityInspection saved = service.createInspection("F001", completion);

        assertEquals("qi-pending", saved.getId());
        assertEquals("PASS", saved.getResult());
        verify(qualityInspectionRepository, times(1)).save(pending);
        ArgumentCaptor<ProductionBatch> batchCaptor = ArgumentCaptor.forClass(ProductionBatch.class);
        verify(productionBatchRepository, times(1)).save(batchCaptor.capture());
        assertEquals(QualityStatus.PASSED,
                batchCaptor.getValue().getQualityStatus());
    }

    @Test
    void createInspection_completesPendingTaskAsFailAndKeepsFailAlertPath() {
        QualityInspection pending = new QualityInspection();
        pending.setId("qi-pending-fail");
        pending.setFactoryId("F001");
        pending.setProductionBatchId(46L);
        pending.setResult("PENDING");
        QualityInspection completion = new QualityInspection();
        completion.setProductionBatchId(46L);
        completion.setResult("FAIL");
        completion.setSampleSize(BigDecimal.ONE);
        completion.setPassCount(BigDecimal.ZERO);
        completion.setFailCount(BigDecimal.ONE);
        completion.setPassRate(BigDecimal.ZERO);
        when(qualityInspectionRepository.findByFactoryIdAndProductionBatchId("F001", 46L))
                .thenReturn(List.of(pending));
        when(qualityInspectionRepository.save(any(QualityInspection.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        QualityInspection saved = service.createInspection("F001", completion);

        assertEquals("qi-pending-fail", saved.getId());
        assertEquals("FAIL", saved.getResult());
        verify(qualityInspectionRepository, times(1)).save(pending);
        verify(productionBatchRepository, times(1)).save(any(ProductionBatch.class));
        verify(productionAlertRepository, times(1)).save(any(ProductionAlert.class));
    }

    @Test
    void updateInspection_preservesDynamicStandardSnapshot() {
        QualityInspection existing = new QualityInspection();
        existing.setId("qi-snapshot");
        existing.setFactoryId("F001");
        existing.setProductionBatchId(44L);
        existing.setResult("PASS");
        existing.setPassRate(new BigDecimal("100"));
        existing.setCustomFields(new HashMap<>(Map.of(
                "standardBasis", "DYNAMIC_PRODUCT_BINDING",
                "standardRevision", "rev-old",
                "items", java.util.List.of(Map.of("itemId", "item-old")))));
        QualityInspection incoming = new QualityInspection();
        incoming.setCustomFields(new HashMap<>(Map.of("standardRevision", "rev-client", "operatorNote", "ok")));
        when(qualityInspectionRepository.findById("qi-snapshot")).thenReturn(java.util.Optional.of(existing));
        when(qualityInspectionRepository.save(any(QualityInspection.class))).thenAnswer(inv -> inv.getArgument(0));

        QualityInspection saved = service.updateInspection("F001", "qi-snapshot", incoming);

        assertEquals("rev-old", saved.getCustomFields().get("standardRevision"));
        assertEquals("item-old", ((java.util.List<?>) saved.getCustomFields().get("items")).get(0) instanceof Map
                ? ((Map<?, ?>) ((java.util.List<?>) saved.getCustomFields().get("items")).get(0)).get("itemId") : null);
        assertEquals("ok", saved.getCustomFields().get("operatorNote"));
    }

    @Test
    void updateInspection_rejectsDynamicDecisionChanges_butAllowsNotes() {
        QualityInspection existing = new QualityInspection();
        existing.setId("qi-immutable");
        existing.setFactoryId("F001");
        existing.setProductionBatchId(44L);
        existing.setResult("FAIL");
        existing.setSampleSize(BigDecimal.ONE);
        existing.setPassCount(BigDecimal.ZERO);
        existing.setFailCount(BigDecimal.ONE);
        existing.setPassRate(BigDecimal.ZERO);
        existing.setCustomFields(new HashMap<>(Map.of("standardBasis", "DYNAMIC_PRODUCT_BINDING", "items", java.util.List.of())));
        when(qualityInspectionRepository.findById("qi-immutable")).thenReturn(java.util.Optional.of(existing));
        QualityInspection decision = new QualityInspection();
        decision.setResult("PASS");
        decision.setSampleSize(BigDecimal.TEN);
        decision.setPassCount(BigDecimal.TEN);
        decision.setFailCount(BigDecimal.ZERO);
        decision.setPassRate(BigDecimal.valueOf(100));
        org.junit.jupiter.api.Assertions.assertThrows(com.cretas.aims.exception.BusinessException.class,
                () -> service.updateInspection("F001", "qi-immutable", decision));
        verify(qualityInspectionRepository, never()).save(any(QualityInspection.class));

        QualityInspection notes = new QualityInspection();
        notes.setNotes("复核备注");
        when(qualityInspectionRepository.save(any(QualityInspection.class))).thenAnswer(inv -> inv.getArgument(0));
        QualityInspection saved = service.updateInspection("F001", "qi-immutable", notes);
        assertEquals("复核备注", saved.getNotes());
        verify(qualityInspectionRepository, times(1)).save(existing);
    }
}
