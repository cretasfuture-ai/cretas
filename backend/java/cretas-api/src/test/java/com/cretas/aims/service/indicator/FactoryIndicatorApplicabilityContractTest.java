package com.cretas.aims.service.indicator;

import com.cretas.aims.controller.dto.indicator.IndicatorApplicabilityRequest.ProductSpec;
import com.cretas.aims.controller.dto.indicator.IndicatorApplicabilityResponse;
import com.cretas.aims.controller.dto.indicator.IndicatorApplicabilityResponse.Status;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.WorkflowProcessPath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FactoryIndicatorApplicabilityContractTest {
    private final UnitContractService units = mock(UnitContractService.class);
    private final ProductTypeRepository productTypes = mock(ProductTypeRepository.class);
    private final ProductProcessWorkflowRepository workflows = mock(ProductProcessWorkflowRepository.class);
    private final ProductProcessWorkflowActivationRepository activations = mock(ProductProcessWorkflowActivationRepository.class);
    private final ProductWorkflowResolutionService resolutions = mock(ProductWorkflowResolutionService.class);
    private final FactoryIndicatorApplicabilityService service =
            new FactoryIndicatorApplicabilityService(productTypes, units,
                    workflows, activations, new ObjectMapper(), resolutions);

    @Test
    void massAndCountRequireExplicitMultiOutputAndPositiveNetWeight() {
        when(units.describe("F1", "kg")).thenReturn(Optional.of(unit(UnitDimension.MASS)));
        when(units.describe("F1", "kg")).thenReturn(Optional.of(unit(UnitDimension.MASS)));
        var mass = service.assessPlanned("F1", List.of(new ProductSpec("p", "肉", "kg", "kg", null, false)));
        assertThat(mass.products().getFirst().metrics().getFirst().status()).isEqualTo(Status.APPLICABLE);

        when(units.describe("F1", "件")).thenReturn(Optional.of(unit(UnitDimension.COUNT)));
        var count = service.assessPlanned("F1", List.of(new ProductSpec("p", "盒", "件", "kg", BigDecimal.ZERO, false)));
        assertThat(count.products().getFirst().metrics().getFirst().status()).isEqualTo(Status.NEEDS_CONFIRMATION);
    }

    @Test
    void volumeIsFailClosedAndUnknownMultiOutputIsNotFalse() {
        when(units.describe("F1", "ml")).thenReturn(Optional.of(unit(UnitDimension.VOLUME)));
        var volume = service.assessPlanned("F1", List.of(new ProductSpec("p", "汁", "ml", "kg", null, false)));
        assertThat(volume.products().getFirst().metrics().getFirst().status()).isEqualTo(Status.NOT_APPLICABLE);

        when(units.describe("F1", "kg")).thenReturn(Optional.of(unit(UnitDimension.MASS)));
        var volumeInput = service.assessPlanned("F1", List.of(new ProductSpec("p", "汁", "kg", "ml", null, false)));
        assertThat(volumeInput.products().getFirst().metrics().getFirst().status()).isEqualTo(Status.NOT_APPLICABLE);

        when(units.describe("F1", "kg")).thenReturn(Optional.of(unit(UnitDimension.MASS)));
        var unknown = service.assessPlanned("F1", List.of(new ProductSpec("p", "肉", "kg", "kg", null, null)));
        assertThat(unknown.products().getFirst().metrics().getFirst().status()).isEqualTo(Status.NEEDS_CONFIRMATION);
    }

    @Test
    void multiOutputRequiresRuntimeAllocationFacts() {
        when(units.describe("F1", "kg")).thenReturn(Optional.of(unit(UnitDimension.MASS)));
        var report = service.assessPlanned("F1", List.of(new ProductSpec("p", "组合", "kg", "kg", null, true)));
        assertThat(report.overallStatus()).isEqualTo(IndicatorApplicabilityResponse.OverallStatus.COMPLETE);
        assertThat(report.products().getFirst().metrics().getFirst().status()).isEqualTo(Status.APPLICABLE);
    }

    @Test void cartonCannotBorrowBoxWeightWithoutPackagingSnapshot() {
        when(units.describe("F1", "kg")).thenReturn(Optional.of(unit(UnitDimension.MASS)));
        when(units.describe("F1", "case")).thenReturn(com.cretas.aims.service.unit.impl.UnitContractServiceImpl.describeBuiltIn("case"));
        var report = service.assessPlanned("F1", List.of(new ProductSpec("p", "整箱", "case", "kg", new BigDecimal("500"), false)));
        assertThat(report.metrics().getFirst().status()).isEqualTo(Status.NEEDS_CONFIRMATION);
        assertThat(report.products().getFirst().metrics().getFirst().requiredFacts()).contains("包装基单位及每箱数量");
    }

    @Test
    void mixedApplicableAndNotApplicableDoesNotClaimWholeFactoryReady() {
        when(units.describe("F1", "kg")).thenReturn(Optional.of(unit(UnitDimension.MASS)));
        when(units.describe("F1", "ml")).thenReturn(Optional.of(unit(UnitDimension.VOLUME)));
        var report = service.assessPlanned("F1", List.of(
                new ProductSpec("p1", "肉", "kg", "kg", null, false),
                new ProductSpec("p2", "汁", "ml", "kg", null, false)));
        assertThat(report.overallStatus()).isEqualTo(IndicatorApplicabilityResponse.OverallStatus.PARTIAL);
        assertThat(report.metrics().getFirst().status()).isEqualTo(Status.NEEDS_CONFIRMATION);
    }

    @Test
    void currentProfileUsesOnlyEnabledActiveSameProductBinding() {
        ProductType product = product("p1", "kg");
        when(productTypes.findVisibleByFactoryId("F1")).thenReturn(List.of(product));
        when(units.describe("F1", "kg")).thenReturn(Optional.of(unit(UnitDimension.MASS)));
        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F1"); activation.setProductTypeId("p1"); activation.setEnabled(true);
        activation.setActiveWorkflowId(7L); activation.setActiveDefinitionVersion(2);
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(7L); workflow.setFactoryId("F1"); workflow.setProductTypeId("p1");
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED); workflow.setDefinitionVersion(2);
        workflow.setNodesJson("[{\"kind\":\"RAW_MATERIAL\",\"data\":{\"baseUnit\":\"kg\"}},{\"kind\":\"FINISHED_GOOD\",\"data\":{\"skuId\":\"p1\"}}]");
        when(activations.findByFactoryIdAndProductTypeId("F1", "p1")).thenReturn(Optional.of(activation));
        when(workflows.findByIdAndFactoryId(7L, "F1")).thenReturn(Optional.of(workflow));
        when(resolutions.resolveProcessPath("F1", "p1")).thenReturn(Optional.of(path(7L, 2, "p1", "p1")));
        var report = service.assess("F1");
        assertThat(report.products().getFirst().profile().inputUnit()).isEqualTo("kg");
        assertThat(report.products().getFirst().profile().multiOutput()).isFalse();

        workflow.setNodesJson("[{\"kind\":\"RAW_MATERIAL\",\"data\":{}},{\"kind\":\"FINISHED_GOOD\",\"data\":{\"skuId\":\"p1\"}}]");
        assertThat(service.assess("F1").products().getFirst().profile().inputUnit()).isNull();
    }

    @Test
    void emptyOrCrossProductBindingRemainsUnknownAndMalformedJsonIsRejected() {
        ProductType product = product("p1", "kg");
        when(productTypes.findVisibleByFactoryId("F1")).thenReturn(List.of(product));
        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F1"); activation.setProductTypeId("p1"); activation.setEnabled(true);
        activation.setActiveWorkflowId(7L); activation.setActiveDefinitionVersion(2);
        ProductProcessWorkflow wrongOwner = new ProductProcessWorkflow();
        wrongOwner.setId(7L); wrongOwner.setFactoryId("F1"); wrongOwner.setProductTypeId("other");
        wrongOwner.setStatus(ProductProcessWorkflow.Status.PUBLISHED); wrongOwner.setDefinitionVersion(2);
        wrongOwner.setNodesJson("[]");
        when(activations.findByFactoryIdAndProductTypeId("F1", "p1")).thenReturn(Optional.of(activation));
        when(workflows.findByIdAndFactoryId(7L, "F1")).thenReturn(Optional.of(wrongOwner));
        when(resolutions.resolveProcessPath("F1", "p1")).thenReturn(Optional.of(path(7L, 2, "p1", "p1")));
        assertThat(service.assess("F1").products().getFirst().profile().inputUnit()).isNull();

        wrongOwner.setProductTypeId("p1"); wrongOwner.setNodesJson("{");
        var error = org.assertj.core.api.Assertions.catchThrowable(() -> service.assess("F1"));
        assertThat(error).isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        assertThat(((com.cretas.aims.exception.BusinessException) error).getCode()).isEqualTo(409);

        wrongOwner.setNodesJson(null);
        var nullJsonError = org.assertj.core.api.Assertions.catchThrowable(() -> service.assess("F1"));
        assertThat(nullJsonError).isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        assertThat(((com.cretas.aims.exception.BusinessException) nullJsonError).getCode()).isEqualTo(409);
    }

    @Test
    void sharedOwnerWorkflowMakesUnactivatedTerminalProductBlockedForMultiOutput() {
        ProductType product = product("pB", "kg");
        when(productTypes.findVisibleByFactoryId("F1")).thenReturn(List.of(product));
        when(units.describe("F1", "kg")).thenReturn(Optional.of(unit(UnitDimension.MASS)));
        when(resolutions.resolveProcessPath("F1", "pB")).thenReturn(Optional.of(path(9L, 3, "pA", "pB")));
        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F1"); activation.setProductTypeId("pA"); activation.setEnabled(true);
        activation.setActiveWorkflowId(9L); activation.setActiveDefinitionVersion(3);
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(9L); workflow.setFactoryId("F1"); workflow.setProductTypeId("pA");
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED); workflow.setDefinitionVersion(3);
        workflow.setNodesJson("[{\"kind\":\"RAW_MATERIAL\",\"data\":{\"baseUnit\":\"kg\"}},{\"kind\":\"FINISHED_GOOD\",\"data\":{\"skuId\":\"pA\"}},{\"kind\":\"FINISHED_GOOD\",\"data\":{\"skuId\":\"pB\"}}]");
        when(activations.findByFactoryIdAndProductTypeId("F1", "pA")).thenReturn(Optional.of(activation));
        when(workflows.findByIdAndFactoryId(9L, "F1")).thenReturn(Optional.of(workflow));
        var report = service.assess("F1");
        assertThat(report.products().getFirst().profile().multiOutput()).isTrue();
        assertThat(report.products().getFirst().metrics().getFirst().status()).isEqualTo(Status.APPLICABLE);
    }

    @Test
    void workflowResolutionAmbiguityIsNotSilentlyDowngraded() {
        ProductType product = product("pB", "kg");
        when(productTypes.findVisibleByFactoryId("F1")).thenReturn(List.of(product));
        when(resolutions.resolveProcessPath("F1", "pB"))
                .thenThrow(new com.cretas.aims.exception.BusinessException(409, "workflow ambiguous"));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.assess("F1")))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class);
    }

    private static ProductType product(String id, String unit) {
        ProductType product = new ProductType(); product.setId(id); product.setFactoryId("F1");
        product.setName(id); product.setCode(id); product.setUnit(unit); return product;
    }

    private static WorkflowProcessPath path(long workflowId, int version, String owner, String terminal) {
        return new WorkflowProcessPath(workflowId, version, owner, "FINISHED_GOOD", terminal,
                null, List.of());
    }

    private static CanonicalUnit unit(UnitDimension dimension) {
        return new CanonicalUnit("x", dimension, dimension.name().toLowerCase(), BigDecimal.ONE, "x", 3);
    }
}
