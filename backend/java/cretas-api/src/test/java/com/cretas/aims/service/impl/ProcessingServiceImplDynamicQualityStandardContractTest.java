package com.cretas.aims.service.impl;

import com.cretas.aims.dto.quality.DynamicQualityInspectionRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.config.QualityCheckItem;
import com.cretas.aims.entity.config.QualityCheckItemBinding;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.*;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessingServiceImplDynamicQualityStandardContractTest {
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private EquipmentRepository equipmentRepository;
    @Mock private EquipmentAlertRepository equipmentAlertRepository;
    @Mock private BatchEquipmentUsageRepository batchEquipmentUsageRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private SystemLogRepository systemLogRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private BatchWorkSessionRepository batchWorkSessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AIAnalysisService aiAnalysisService;
    @Mock private CacheService cacheService;
    @Mock private ProcessingStageRecordService processingStageRecordService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private ProductionAlertRepository productionAlertRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    @Mock private QualityCheckItemBindingRepository bindingRepository;

    @InjectMocks private ProcessingServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String OTHER_FACTORY = "F007";
    private static final String BATCH = "1885";
    private QualityCheckItemBinding binding;
    private ProductionBatch batch;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "qualityCheckItemBindingRepository", bindingRepository);
        QualityCheckItem item = QualityCheckItem.builder().id("item-temp").factoryId(FACTORY)
                .itemCode("TEMP").itemName("中心温度").version(3).valueType("RANGE")
                .unit("°C").minValue(BigDecimal.ZERO).maxValue(BigDecimal.TEN)
                .isRequired(true).enabled(true).build();
        binding = QualityCheckItemBinding.builder().id("binding-temp").factoryId(FACTORY)
                .productTypeId("QA-PRODUCT").qualityCheckItemId("item-temp").qualityCheckItem(item)
                .enabled(true).overrideIsRequired(true).build();
        batch = ProductionBatch.builder().id(1885L).factoryId(FACTORY).batchNumber("PB-1885")
                .productTypeId("QA-PRODUCT").productName("QA product").build();
        lenient().when(productionBatchRepository.findByIdAndFactoryId(1885L, FACTORY)).thenReturn(Optional.of(batch));
        lenient().when(bindingRepository.findByProductTypeIdWithItems("QA-PRODUCT")).thenReturn(List.of(binding));
        lenient().when(qualityInspectionService.createInspection(eq(FACTORY), any(QualityInspection.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private DynamicQualityInspectionRequest request(String revision, String bindingId, String itemId, Integer version, Object value) {
        DynamicQualityInspectionRequest request = new DynamicQualityInspectionRequest();
        request.setStandardRevision(revision);
        request.setSampleSize(BigDecimal.ONE);
        DynamicQualityInspectionRequest.Answer answer = new DynamicQualityInspectionRequest.Answer();
        answer.setBindingId(bindingId); answer.setItemId(itemId); answer.setItemVersion(version); answer.setValue(value);
        request.setAnswers(List.of(answer));
        return request;
    }

    @Test
    void outOfRangeMeasurementPersistsServerFailAndCompleteSnapshot() {
        String revision = (String) service.getDynamicQualityForm(FACTORY, BATCH).get("standardRevision");
        Map<String, Object> result = service.submitDynamicInspection(FACTORY, BATCH,
                request(revision, "binding-temp", "item-temp", 3, "15"), 1557L);
        QualityInspection saved = (QualityInspection) result.get("inspection");
        assertEquals("FAIL", saved.getResult());
        assertEquals(BigDecimal.ONE, saved.getFailCount());
        Map<?, ?> snapshot = (Map<?, ?>) saved.getCustomFields();
        assertEquals("DYNAMIC_PRODUCT_BINDING", snapshot.get("standardBasis"));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) snapshot.get("items")).get(0);
        assertEquals("RANGE", item.get("valueType"));
        assertEquals("°C", item.get("unit"));
        assertEquals("FAIL", item.get("status"));
        assertEquals("15", item.get("value"));
    }

    @Test
    void inRangeMeasurementPersistsLegalPass() {
        String revision = (String) service.getDynamicQualityForm(FACTORY, BATCH).get("standardRevision");
        Map<String, Object> result = service.submitDynamicInspection(FACTORY, BATCH,
                request(revision, "binding-temp", "item-temp", 3, "5"), 1557L);

        QualityInspection saved = (QualityInspection) result.get("inspection");
        assertEquals("PASS", saved.getResult());
        assertEquals(BigDecimal.ONE, saved.getPassCount());
        assertEquals(BigDecimal.ZERO, saved.getFailCount());
        verify(qualityInspectionService).createInspection(eq(FACTORY), any(QualityInspection.class));
    }

    @Test
    void blankBooleanStandardInheritsAcrossFormEvaluationAndNewSnapshot() {
        QualityCheckItem booleanItem = QualityCheckItem.builder().id("item-boolean").factoryId(FACTORY)
                .itemCode("BOOL").itemName("包装完整").version(4).valueType("BOOLEAN")
                .standardValue("true").isRequired(true).enabled(true).build();
        binding.setQualityCheckItem(booleanItem);
        binding.setQualityCheckItemId(booleanItem.getId());
        for (String blankOverride : new String[]{null, "", " \t"}) {
            binding.setOverrideStandardValue(blankOverride);
            Map<String, Object> blankForm = service.getDynamicQualityForm(FACTORY, BATCH);
            Map<?, ?> blankItem = (Map<?, ?>) ((List<?>) blankForm.get("items")).get(0);
            assertEquals("true", blankItem.get("effectiveStandardValue"));
        }
        binding.setOverrideIsRequired(null);
        when(bindingRepository.findByProductTypeIdWithItems("QA-PRODUCT")).thenReturn(List.of(binding));

        Map<String, Object> form = service.getDynamicQualityForm(FACTORY, BATCH);
        assertEquals("true", ((Map<?, ?>) ((List<?>) form.get("items")).get(0)).get("effectiveStandardValue"));
        assertEquals(true, ((Map<?, ?>) ((List<?>) form.get("items")).get(0)).get("effectiveIsRequired"));

        binding.setOverrideStandardValue("false");
        Map<String, Object> falseForm = service.getDynamicQualityForm(FACTORY, BATCH);
        assertEquals("false", ((Map<?, ?>) ((List<?>) falseForm.get("items")).get(0)).get("effectiveStandardValue"));
        String falseRevision = (String) falseForm.get("standardRevision");
        Map<String, Object> falseResult = service.submitDynamicInspection(FACTORY, BATCH,
                request(falseRevision, "binding-temp", "item-boolean", 4, false), 1557L);
        QualityInspection falseInspection = (QualityInspection) falseResult.get("inspection");
        assertEquals("PASS", falseInspection.getResult());
        Map<?, ?> falseSnapshotItem = (Map<?, ?>) ((List<?>) falseInspection.getCustomFields().get("items")).get(0);
        assertEquals("false", falseSnapshotItem.get("effectiveStandardValue"));

        binding.setOverrideStandardValue("0");
        Map<String, Object> zeroForm = service.getDynamicQualityForm(FACTORY, BATCH);
        assertEquals("0", ((Map<?, ?>) ((List<?>) zeroForm.get("items")).get(0)).get("effectiveStandardValue"));

        binding.setOverrideStandardValue(null);
        String whitespaceRevision = (String) form.get("standardRevision");
        assertThrows(BusinessException.class, () -> service.submitDynamicInspection(FACTORY, BATCH,
                request(whitespaceRevision, "binding-temp", "item-boolean", 4, true), 1557L));
        Map<String, Object> inheritedForm = service.getDynamicQualityForm(FACTORY, BATCH);
        String revision = (String) inheritedForm.get("standardRevision");
        DynamicQualityInspectionRequest request = request(revision, "binding-temp", "item-boolean", 4, true);
        Map<String, Object> result = service.submitDynamicInspection(FACTORY, BATCH, request, 1557L);

        QualityInspection saved = (QualityInspection) result.get("inspection");
        assertEquals("PASS", saved.getResult());
        Map<?, ?> snapshotItem = (Map<?, ?>) ((List<?>) saved.getCustomFields().get("items")).get(0);
        assertEquals("true", snapshotItem.get("effectiveStandardValue"));
        assertEquals(true, snapshotItem.get("isRequired"));
        assertEquals("PASS", snapshotItem.get("status"));
    }

    @Test
    void missingRequiredOrInactiveStandardIsRejectedBeforeSave() {
        String revision = (String) service.getDynamicQualityForm(FACTORY, BATCH).get("standardRevision");
        DynamicQualityInspectionRequest missing = request(revision, "binding-temp", "item-temp", 3, "");
        assertThrows(BusinessException.class, () -> service.submitDynamicInspection(FACTORY, BATCH, missing, 1557L));

        when(bindingRepository.findByProductTypeIdWithItems("QA-PRODUCT")).thenReturn(List.of());
        DynamicQualityInspectionRequest noActive = request(revision, "binding-temp", "item-temp", 3, "5");
        assertThrows(BusinessException.class, () -> service.submitDynamicInspection(FACTORY, BATCH, noActive, 1557L));
        verify(qualityInspectionService, never()).createInspection(anyString(), any(QualityInspection.class));
    }

    @Test
    void optionalUnansweredItemIsSavedAsNotAnsweredInCompleteSnapshot() {
        binding.setOverrideIsRequired(false);
        String revision = (String) service.getDynamicQualityForm(FACTORY, BATCH).get("standardRevision");
        DynamicQualityInspectionRequest request = request(revision, "binding-temp", "item-temp", 3, "");
        Map<String, Object> result = service.submitDynamicInspection(FACTORY, BATCH, request, 1557L);

        QualityInspection saved = (QualityInspection) result.get("inspection");
        Map<?, ?> snapshot = (Map<?, ?>) saved.getCustomFields();
        Map<?, ?> item = (Map<?, ?>) ((List<?>) snapshot.get("items")).get(0);
        assertEquals("NOT_ANSWERED", item.get("status"));
        assertNull(item.get("value"));
        assertEquals(false, item.get("isRequired"));
        verify(qualityInspectionService).createInspection(eq(FACTORY), any(QualityInspection.class));
    }

    @Test
    void staleRevisionUnknownAnswerAndDuplicateAnswerAreRejectedBeforeSave() {
        String revision = (String) service.getDynamicQualityForm(FACTORY, BATCH).get("standardRevision");
        assertThrows(BusinessException.class, () -> service.submitDynamicInspection(FACTORY, BATCH,
                request(revision + "-stale", "binding-temp", "item-temp", 3, "5"), 1557L));
        assertThrows(BusinessException.class, () -> service.submitDynamicInspection(FACTORY, BATCH,
                request(revision, "unknown", "item-temp", 3, "5"), 1557L));
        DynamicQualityInspectionRequest duplicate = request(revision, "binding-temp", "item-temp", 3, "5");
        DynamicQualityInspectionRequest.Answer second = new DynamicQualityInspectionRequest.Answer();
        second.setBindingId("binding-temp"); second.setItemId("item-temp"); second.setItemVersion(3); second.setValue("6");
        duplicate.setAnswers(List.of(duplicate.getAnswers().get(0), second));
        assertThrows(BusinessException.class, () -> service.submitDynamicInspection(FACTORY, BATCH, duplicate, 1557L));
        verify(qualityInspectionService, never()).createInspection(anyString(), any(QualityInspection.class));
    }

    @Test
    void crossFactoryBatchAndLegacyBoundProductAreRejectedWithoutSave() {
        when(productionBatchRepository.findByIdAndFactoryId(1885L, OTHER_FACTORY)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.getDynamicQualityForm(OTHER_FACTORY, BATCH));
        when(productionBatchRepository.findById(1885L)).thenReturn(Optional.of(batch));
        Map<String, Object> legacy = Map.of("sampleSize", 1, "passCount", 1, "failCount", 0, "result", "PASS");
        assertThrows(BusinessException.class, () -> service.submitInspection(FACTORY, BATCH, legacy));
        verify(qualityInspectionService, never()).createInspection(anyString(), any(QualityInspection.class));
    }

    @Test
    void revisionIsBoundToBatchAndCannotBeReplayedOnAnotherBatch() {
        String revisionA = (String) service.getDynamicQualityForm(FACTORY, BATCH).get("standardRevision");
        ProductionBatch batchB = ProductionBatch.builder().id(1886L).factoryId(FACTORY).batchNumber("PB-1886")
                .productTypeId("QA-PRODUCT").productName("QA product").build();
        when(productionBatchRepository.findByIdAndFactoryId(1886L, FACTORY)).thenReturn(Optional.of(batchB));
        assertThrows(BusinessException.class, () -> service.submitDynamicInspection(FACTORY, "1886",
                request(revisionA, "binding-temp", "item-temp", 3, "5"), 1557L));
        verify(qualityInspectionService, never()).createInspection(anyString(), any(QualityInspection.class));
    }
}
