package com.cretas.aims.service.impl;

import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProductionPlanServiceImplActualCostsContractTest {

    @Test void costSaveUsesTheSamePermissionsAsSettledRecalculation() throws Exception {
        var method = com.cretas.aims.controller.ProductionPlanController.class.getMethod("updateActualCosts",
                String.class,String.class,BigDecimal.class,BigDecimal.class,BigDecimal.class,BigDecimal.class);
        var permission = method.getAnnotation(com.cretas.aims.annotation.RequirePermission.class);
        assertThat(permission.value()).containsExactly("production:read_write", "finance:read_write");
    }

    @Test
    void postedSettlementRecalculatesInsideTheExistingCostSave() throws Exception {
        ProductionPlanRepository plans = mockPlanRepo(plan());
        var service = spy(service(plans, mock(ProductionBatchRepository.class)));
        var settlements = mock(com.cretas.aims.repository.ProductionSettlementRepository.class);
        var settlement = new com.cretas.aims.entity.ProductionSettlement();
        settlement.setPostingStatus("POSTED");
        when(settlements.findByFactoryIdAndProductionPlanIdForUpdate(FACTORY, PLAN_ID)).thenReturn(Optional.of(settlement));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "productionSettlementRepository", settlements);
        doReturn(null).when(service).recalculateSettledPlanCosts(FACTORY, PLAN_ID);
        service.updateActualCosts(FACTORY, PLAN_ID, null, null, BigDecimal.TEN, null);
        verify(service).recalculateSettledPlanCosts(FACTORY, PLAN_ID);
    }

    @Test
    void recalculationFailurePropagatesToTheOuterTransaction() throws Exception {
        ProductionPlanRepository plans = mockPlanRepo(plan());
        var service = spy(service(plans, mock(ProductionBatchRepository.class)));
        var settlements = mock(com.cretas.aims.repository.ProductionSettlementRepository.class);
        var settlement = new com.cretas.aims.entity.ProductionSettlement();
        settlement.setPostingStatus("POSTED");
        when(settlements.findByFactoryIdAndProductionPlanIdForUpdate(FACTORY, PLAN_ID)).thenReturn(Optional.of(settlement));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "productionSettlementRepository", settlements);
        doThrow(new BusinessException(409, "成本未完整")).when(service).recalculateSettledPlanCosts(FACTORY, PLAN_ID);
        assertThatThrownBy(() -> service.updateActualCosts(FACTORY, PLAN_ID, null, null, BigDecimal.TEN, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("成本未完整");
    }

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN-COST-1";

    @Test
    void uniqueOfficialBatchReceivesSupplementalCostsAndClerkWipIsIgnored() throws Exception {
        ProductionPlan plan = plan();
        ProductionBatch official = batch(1L, FACTORY, "REGULAR");
        ProductionBatch clerkWip = batch(2L, FACTORY, "CLERK_WIP");
        ProductionBatch foreign = batch(3L, "F007", "REGULAR");
        ProductionPlanRepository planRepo = mockPlanRepo(plan);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(official, clerkWip, foreign));

        ProductionPlanServiceImpl service = service(planRepo, batchRepo);
        service.updateActualCosts(FACTORY, PLAN_ID, null, null,
                new BigDecimal("12.50"), new BigDecimal("0"));

        assertThat(official.getEquipmentCost()).isEqualByComparingTo("12.50");
        assertThat(official.getOtherCost()).isEqualByComparingTo("0");
        assertThat(clerkWip.getEquipmentCost()).isNull();
        assertThat(foreign.getEquipmentCost()).isNull();
        verify(batchRepo).save(official);
        verify(planRepo).save(plan);
    }

    @Test
    void multipleOfficialBatchesRejectBeforeAnyWrite() throws Exception {
        ProductionPlan plan = plan();
        ProductionPlanRepository planRepo = mockPlanRepo(plan);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(1L, FACTORY, "REGULAR"), batch(2L, FACTORY, "REGULAR")));

        ProductionPlanServiceImpl service = service(planRepo, batchRepo);
        assertThatThrownBy(() -> service.updateActualCosts(FACTORY, PLAN_ID, null, null,
                new BigDecimal("1"), null))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));

        verify(planRepo, never()).save(any());
        verify(batchRepo, never()).save(any());
        assertThat(plan.getActualEquipmentCost()).isNull();
    }

    @Test
    void omittedSupplementalCostDoesNotChangeBatchButExplicitZeroDoes() throws Exception {
        ProductionPlan plan = plan();
        ProductionBatch official = batch(1L, FACTORY, "REGULAR");
        official.setEquipmentCost(new BigDecimal("7"));
        ProductionPlanRepository planRepo = mockPlanRepo(plan);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(official));
        ProductionPlanServiceImpl service = service(planRepo, batchRepo);

        service.updateActualCosts(FACTORY, PLAN_ID, null, null, null, null);
        assertThat(official.getEquipmentCost()).isEqualByComparingTo("7");
        verify(batchRepo, never()).findByFactoryIdAndProductionPlanId(anyString(), anyString());

        service.updateActualCosts(FACTORY, PLAN_ID, null, null, BigDecimal.ZERO, null);
        assertThat(official.getEquipmentCost()).isZero();
        verify(batchRepo).save(official);
    }

    @Test void multipleBatchesCanConfirmOnlyUnrecordedZeroCosts() throws Exception {
        var planRepo = mockPlanRepo(plan()); var batchRepo = mock(ProductionBatchRepository.class);
        var first = batch(1L, FACTORY, "REGULAR"); var second = batch(2L, FACTORY, "REGULAR");
        var clerk = batch(3L, FACTORY, "CLERK_WIP"); var foreign = batch(4L, "F007", "REGULAR");
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(first,second,clerk,foreign));
        service(planRepo,batchRepo).updateActualCosts(FACTORY,PLAN_ID,null,null,BigDecimal.ZERO,BigDecimal.ZERO);
        for (var batch : List.of(first,second)) {
            assertThat(batch.getEquipmentCost()).isZero(); assertThat(batch.getOtherCost()).isZero(); verify(batchRepo).save(batch);
        }
        assertThat(clerk.getEquipmentCost()).isNull(); assertThat(foreign.getEquipmentCost()).isNull();
    }

    @Test void multiBatchZeroCannotEraseARecordedExpenseOrPartiallySave() throws Exception {
        var planRepo = mockPlanRepo(plan()); var batchRepo = mock(ProductionBatchRepository.class);
        var first = batch(1L, FACTORY, "REGULAR"); var second = batch(2L, FACTORY, "REGULAR");
        second.setEquipmentCost(BigDecimal.TEN);
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(first,second));
        assertThatThrownBy(() -> service(planRepo,batchRepo).updateActualCosts(FACTORY,PLAN_ID,null,null,BigDecimal.ZERO,BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);
        assertThat(second.getEquipmentCost()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(first.getEquipmentCost()).isNull(); verify(batchRepo,never()).save(any()); verify(planRepo,never()).save(any());
    }

    @Test void multiBatchZeroCannotEraseAnExistingPlanLevelExpense() throws Exception {
        var plan = plan(); plan.setActualEquipmentCost(BigDecimal.TEN);
        var planRepo = mockPlanRepo(plan); var batchRepo = mock(ProductionBatchRepository.class);
        var first = batch(1L, FACTORY, "REGULAR"); var second = batch(2L, FACTORY, "REGULAR");
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(first,second));
        assertThatThrownBy(() -> service(planRepo,batchRepo).updateActualCosts(FACTORY,PLAN_ID,null,null,BigDecimal.ZERO,BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);
        assertThat(plan.getActualEquipmentCost()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(first.getEquipmentCost()).isNull(); verify(batchRepo,never()).save(any()); verify(planRepo,never()).save(any());
    }

    @Test
    void noOfficialBatchKeepsLegacyPlanOnlyUpdate() throws Exception {
        ProductionPlan plan = plan();
        ProductionPlanRepository planRepo = mockPlanRepo(plan);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID)).thenReturn(List.of());
        ProductionPlanServiceImpl service = service(planRepo, batchRepo);

        service.updateActualCosts(FACTORY, PLAN_ID, null, null,
                new BigDecimal("2"), new BigDecimal("3"));

        assertThat(plan.getActualEquipmentCost()).isEqualByComparingTo("2");
        assertThat(plan.getActualOtherCost()).isEqualByComparingTo("3");
        verify(planRepo).save(plan);
        verify(batchRepo, never()).save(any());
    }

    @Test
    void negativeCostIsBadRequestBeforeMutation() throws Exception {
        ProductionPlan plan = plan();
        ProductionPlanRepository planRepo = mockPlanRepo(plan);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        ProductionPlanServiceImpl service = service(planRepo, batchRepo);

        assertThatThrownBy(() -> service.updateActualCosts(FACTORY, PLAN_ID,
                new BigDecimal("-0.01"), null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));

        verify(planRepo, never()).save(any());
        verify(batchRepo, never()).save(any());
    }

    private ProductionPlan plan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setProductTypeId("SKU-1");
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        return plan;
    }

    private ProductionBatch batch(Long id, String factoryId, String batchType) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(id);
        batch.setFactoryId(factoryId);
        batch.setProductionPlanId(PLAN_ID);
        batch.setBatchType(batchType);
        return batch;
    }

    private ProductionPlanRepository mockPlanRepo(ProductionPlan plan) {
        ProductionPlanRepository repo = mock(ProductionPlanRepository.class);
        when(repo.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(repo.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.empty());
        when(repo.save(any(ProductionPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return repo;
    }

    private ProductionPlanServiceImpl service(ProductionPlanRepository planRepo,
                                               ProductionBatchRepository batchRepo) throws Exception {
        Constructor<?> constructor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        ProductionPlanMapper mapper = mock(ProductionPlanMapper.class);
        ConversionRepository conversionRepo = mock(ConversionRepository.class);
        for (int i = 0; i < args.length; i++) {
            Class<?> type = constructor.getParameterTypes()[i];
            if (type == ProductionPlanRepository.class) args[i] = planRepo;
            else if (type == ProductionBatchRepository.class) args[i] = batchRepo;
            else if (type == ProductionPlanMapper.class) args[i] = mapper;
            else if (type == ConversionRepository.class) args[i] = conversionRepo;
            else args[i] = mock(type);
        }
        when(mapper.toDTO(any(ProductionPlan.class))).thenReturn(ProductionPlanDTO.builder().build());
        when(conversionRepo.findByFactoryIdAndProductTypeId(anyString(), anyString())).thenReturn(List.of());
        constructor.setAccessible(true);
        return (ProductionPlanServiceImpl) constructor.newInstance(args);
    }
}
