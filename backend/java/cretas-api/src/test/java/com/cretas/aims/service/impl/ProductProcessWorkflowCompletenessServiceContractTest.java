package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.ProductProcessWorkflowDTO.Node;
import com.cretas.aims.dto.workflow.WorkflowCompletenessIssue;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.datacenter.OperationLogService;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.service.validation.ProductProcessWorkflowCompletenessChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ProductProcessWorkflowCompletenessServiceContractTest {

    private final ProductTypeRepository products = mock(ProductTypeRepository.class);
    private final RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
    private final ProductProcessWorkflowCompletenessChecker checker = mock(ProductProcessWorkflowCompletenessChecker.class);
    private final ProcessSecretRecipeGuard secrecy = mock(ProcessSecretRecipeGuard.class);
    private final OperationLogService operationLog = mock(OperationLogService.class);
    private final ProductProcessWorkflowServiceImpl service = new ProductProcessWorkflowServiceImpl(
            null, null, null, null, null, null, null, null, null, null, null, null, products, materials);

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(service, "completenessChecker", checker);
        ReflectionTestUtils.setField(service, "operationLogService", operationLog);
        ReflectionTestUtils.setField(service, "processSecretGuard", secrecy);
    }

    @Test
    void checkingARawOwnerDoesNotCreateAProductAnchorAndChecksProtectedOutput() {
        when(materials.findByIdAndFactoryId("raw-owner", "F006")).thenReturn(Optional.of(new RawMaterialType()));
        var graph = definition();
        when(checker.check("F006", "raw-owner", graph)).thenReturn(List.of());

        assertThat(service.checkCompleteness("F006", "raw-owner", graph)).isEmpty();
        verify(products).findByIdAndFactoryId("raw-owner", "F006");
        verify(materials).findByIdAndFactoryId("raw-owner", "F006");
        verifyNoMoreInteractions(products, materials);
        verify(secrecy).requireProtectedAmong(eq("F006"), eq(Set.of("protected-output")),
                eq(ProcessSecretAction.EDIT), anyString());
        verifyNoInteractions(operationLog);
    }

    @Test
    void protectedGraphStopsBeforeCatalogInspection() {
        var graph = definition();
        when(materials.findByIdAndFactoryId("raw-owner", "F006")).thenReturn(Optional.of(new RawMaterialType()));
        doThrow(new BusinessException(403, "该产品工艺受保护")).when(secrecy).requireProtectedAmong(
                eq("F006"), eq(Set.of("protected-output")), eq(ProcessSecretAction.EDIT), anyString());

        assertThatThrownBy(() -> service.checkCompleteness("F006", "raw-owner", graph))
                .isInstanceOf(BusinessException.class).hasMessage("该产品工艺受保护");
        verifyNoInteractions(checker, operationLog);
    }

    @Test
    void recordsEveryIssueAgainstTheSavedRevisionWithoutCopyingRecipeNames() {
        var graph = definition();
        when(checker.check("F006", "raw-owner", graph)).thenReturn(List.of(
                new WorkflowCompletenessIssue("MATERIAL_UNCONNECTED", "finished", null, "内部配方名未连接", "连接"),
                new WorkflowCompletenessIssue("RAW_INPUT_REQUIRED", null, null, "缺入口", "添加")));

        service.recordAcknowledgedCompleteness("F006", "raw-owner", graph, "仍然保存工艺配置");

        verify(operationLog).recordDiff("F006", "WORKFLOW", "ProductProcessWorkflow", "43", Map.of(),
                Map.of("version", 3, "revisionId", 72L, "issues", List.of(
                        Map.of("rule", "MATERIAL_UNCONNECTED", "nodeNumber", 1),
                        Map.of("rule", "RAW_INPUT_REQUIRED", "nodeNumber", 0))),
                "仍然保存工艺配置（已确认 2 个遗漏）");
    }

    private ProductProcessWorkflowDTO definition() {
        var graph = new ProductProcessWorkflowDTO();
        graph.setFactoryId("F006");
        graph.setProductTypeId("raw-owner");
        graph.setId(43L);
        graph.setVersion(3);
        graph.setRevisionId(72L);
        graph.setNodes(List.of(new Node("finished", "FINISHED_GOOD", null,
                Map.of("skuId", "protected-output", "name", "内部配方名"))));
        return graph;
    }
}
