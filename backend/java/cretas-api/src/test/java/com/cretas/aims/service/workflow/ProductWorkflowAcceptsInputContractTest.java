package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.workflow.impl.ProductWorkflowResolutionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 二次加工建计划预检 {@link ProductWorkflowResolutionService#activeWorkflowAcceptsInput}:
 * 目标成品的已启用 Workflow 里, 有没有工序的 INPUT 端口接这种半成品 (按 SKU 对)。
 * 走真实 ProductWorkflowResolutionServiceImpl + 真实节点 JSON, 只桩仓库。
 */
class ProductWorkflowAcceptsInputContractTest {

    private ProductProcessWorkflowActivationRepository activations;
    private ProductProcessWorkflowRepository workflows;
    private ProductTypeRepository products;
    private ProductWorkflowResolutionService service;

    @BeforeEach
    void setUp() {
        activations = mock(ProductProcessWorkflowActivationRepository.class);
        workflows = mock(ProductProcessWorkflowRepository.class);
        products = mock(ProductTypeRepository.class);
        UnitContractService units = mock(UnitContractService.class);
        service = new ProductWorkflowResolutionServiceImpl(
                activations, workflows, products, mock(RawMaterialTypeRepository.class),
                new ObjectMapper(), units,
                mock(com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator.class));
        CanonicalUnit kg = new CanonicalUnit("kg", UnitDimension.MASS, "g", new BigDecimal("1000"), "千克", 3);
        when(units.normalize("F006", "kg")).thenReturn(new UnitNormalizationResult("kg", "kg", kg));
    }

    /** raw → 工序 A(产出 SEMI-1) → 工序 B(投入 SEMI-1, 产出 FG-A)。工序 B 的 INPUT 端口接 SEMI-1。 */
    private void publishWorkflowForFgA() {
        ProductType target = new ProductType();
        target.setId("FG-A");
        target.setFactoryId("F006");
        target.setName("成品 A");
        target.setProductCategory(ProductCategory.FINISHED_PRODUCT);
        when(products.findByIdAndFactoryId("FG-A", "F006")).thenReturn(Optional.of(target));
        when(products.findByIdIn(anyList())).thenReturn(List.of(target));

        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F006");
        activation.setProductTypeId("FG-A");
        activation.setActiveWorkflowId(41L);
        activation.setActiveDefinitionVersion(3);
        activation.setEnabled(true);
        when(activations.findByFactoryIdAndProductTypeId("F006", "FG-A")).thenReturn(Optional.of(activation));
        when(activations.findByFactoryIdAndEnabledTrue("F006")).thenReturn(List.of(activation));

        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(41L);
        workflow.setFactoryId("F006");
        workflow.setProductTypeId("FG-A");
        workflow.setDefinitionVersion(3);
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setUnitReviewRequired(false);
        workflow.setNodesJson("""
                [
                  {"id":"raw","kind":"RAW_MATERIAL","data":{"skuId":"RAW-1"}},
                  {"id":"p-a","kind":"PROCESS","data":{"workProcessId":"WP-A","ports":[
                    {"direction":"INPUT","materialNodeId":"raw","unit":"kg"},
                    {"direction":"OUTPUT","materialNodeId":"semi","unit":"kg"}]}},
                  {"id":"semi","kind":"SEMI_FINISHED","data":{"skuId":"SEMI-1"}},
                  {"id":"p-b","kind":"PROCESS","data":{"workProcessId":"WP-B","ports":[
                    {"direction":"INPUT","materialNodeId":"semi","unit":"kg"},
                    {"direction":"OUTPUT","materialNodeId":"fg-a","unit":"kg"}]}},
                  {"id":"fg-a","kind":"FINISHED_GOOD","data":{"skuId":"FG-A"}}
                ]
                """);
        workflow.setEdgesJson("""
                [
                  {"id":"e1","source":"raw","sourceHandle":"output","target":"p-a","targetHandle":"in"},
                  {"id":"e2","source":"p-a","sourceHandle":"out","target":"semi","targetHandle":"input"},
                  {"id":"e3","source":"semi","sourceHandle":"output","target":"p-b","targetHandle":"in"},
                  {"id":"e4","source":"p-b","sourceHandle":"out","target":"fg-a","targetHandle":"input"}
                ]
                """);
        when(workflows.findByIdAndFactoryId(41L, "F006")).thenReturn(Optional.of(workflow));
    }

    @Test
    void processWithInputPortForTheSemiFinishedSku_accepts() {
        publishWorkflowForFgA();

        assertEquals(Optional.of(true), service.activeWorkflowAcceptsInput("F006", "FG-A", "SEMI-1"));
    }

    @Test
    void noProcessTakesTheSku_rejectsInsteadOfDegradingToEmpty() {
        publishWorkflowForFgA();

        // 阴性对照: 同一张图, 换一个图里没有的 SKU => false (不是 empty, 也不是 true)
        assertEquals(Optional.of(false), service.activeWorkflowAcceptsInput("F006", "FG-A", "SEMI-OTHER"));
    }

    @Test
    void targetWithoutAnyActiveWorkflow_isUndecidable_notRejected() {
        ProductType legacy = new ProductType();
        legacy.setId("FG-LEGACY");
        legacy.setFactoryId("F006");
        legacy.setProductCategory(ProductCategory.FINISHED_PRODUCT);
        when(products.findByIdAndFactoryId("FG-LEGACY", "F006")).thenReturn(Optional.of(legacy));
        when(products.findByIdIn(anyList())).thenReturn(List.of(legacy));
        when(activations.findByFactoryIdAndEnabledTrue("F006")).thenReturn(List.of());

        assertEquals(Optional.empty(), service.activeWorkflowAcceptsInput("F006", "FG-LEGACY", "SEMI-1"));
    }

    @Test
    void blankArguments_areUndecidable() {
        assertEquals(Optional.empty(), service.activeWorkflowAcceptsInput("F006", "FG-A", " "));
        assertEquals(Optional.empty(), service.activeWorkflowAcceptsInput("F006", null, "SEMI-1"));
    }
}
