package com.cretas.aims.service.impl;

import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.repository.config.ApprovalWorkflowRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual save/validate contract, plus parsed-data parity with the frontend's consumed mode lists. */
class ApprovalNodeModeContractTest {
    final ObjectMapper mapper = new ObjectMapper();
    final ApprovalWorkflowRepository repository = mock(ApprovalWorkflowRepository.class);
    final ApprovalWorkflowServiceImpl service = new ApprovalWorkflowServiceImpl(repository, mapper);

    ApprovalWorkflow graph(String mode) throws Exception {
        var workflow = new ApprovalWorkflow();
        workflow.setId("local-workflow"); workflow.setFactoryId("F006"); workflow.setPublishStatus("draft");
        workflow.setStartNodeId("start");
        workflow.setNodesJson("""
            [{"id":"start","type":"start","label":"开始","config":{}},
             {"id":"quality","type":"approval","label":"质量经理审核",
              "config":{"mode":"%s","approverRoles":["quality_manager"],"requiredApprovers":1}},
             {"id":"end","type":"end","label":"结束","config":{"outcome":"APPROVED"}}]
            """.formatted(mode));
        workflow.setEdgesJson("""
            [{"id":"e1","source":"start","target":"quality"},{"id":"e2","source":"quality","target":"end"}]
            """);
        ReflectionTestUtils.setField(service,"workflowInstanceRepository", mock(ApprovalWorkflowInstanceRepository.class));
        when(repository.findById("local-workflow")).thenReturn(Optional.of(workflow));
        return workflow;
    }

    @ParameterizedTest @ValueSource(strings={"ANY","ALL"})
    void renameSavesWithoutChangingRolesModesOrEdges(String mode) throws Exception {
        var workflow=graph(mode);
        var before=service.deserializeNodes(workflow.getNodesJson());
        var originalConfig=mapper.valueToTree(before.get(1).getConfig());
        var originalEdges=workflow.getEdgesJson();
        before.get(1).setLabel("质量经理验收");
        var patch=new ApprovalWorkflow(); patch.setNodesJson(service.serializeNodes(before));
        when(repository.save(workflow)).thenAnswer(i->i.getArgument(0));
        var saved=service.update("F006","local-workflow",patch,false);
        var nodes=service.deserializeNodes(saved.getNodesJson());
        assertEquals(3,nodes.size()); assertEquals("质量经理验收",nodes.get(1).getLabel());
        assertEquals(originalConfig,mapper.valueToTree(nodes.get(1).getConfig()));
        assertEquals(originalEdges,saved.getEdgesJson()); verify(repository).save(workflow);
    }

    @ParameterizedTest @ValueSource(strings={"INVALID","N_OF_M","any",""})
    void illegalApprovalModeCannotBeSaved(String mode) throws Exception {
        graph(mode);
        var patch=new ApprovalWorkflow(); patch.setName("应拒绝");
        var error=assertThrows(BusinessException.class,()->service.update("F006","local-workflow",patch,false));
        assertTrue(error.getMessage().contains("mode")); verify(repository,never()).save(any());
    }

    @Test void frontendConsumedModesMatchActualBackendValidationSets() throws Exception {
        Path repo=Path.of("").toAbsolutePath();
        while(repo!=null&&!Files.isDirectory(repo.resolve("web-admin"))) repo=repo.getParent();
        assertNotNull(repo,"repository root missing; parity must not silently skip");
        var contract=mapper.readTree(repo.resolve("web-admin/src/views/platform/approval-workflow-editor/lib/approvalNodeModes.json").toFile());
        for(var pair:Map.of("approval","VALID_APPROVAL_MODES","join","VALID_JOIN_MODES").entrySet()) {
            Set<String> frontend=new HashSet<>(); contract.path(pair.getKey()).forEach(n->frontend.add(n.asText()));
            assertFalse(frontend.isEmpty());
            assertEquals(ReflectionTestUtils.getField(ApprovalWorkflowServiceImpl.class,pair.getValue()),frontend);
        }
    }

    @Test void optionalModeMayBeAbsentButNullOrNumbersAreRejected() throws Exception {
        var workflow=graph("ANY");
        var nodes=service.deserializeNodes(workflow.getNodesJson());
        var config=new HashMap<>(nodes.get(1).getConfig()); nodes.get(1).setConfig(config);
        config.remove("mode"); workflow.setNodesJson(service.serializeNodes(nodes));
        assertEquals(true,service.validateGraph(workflow).get("valid"));
        for(Object mode:Arrays.asList(null,1)) {
            config.put("mode",mode); workflow.setNodesJson(service.serializeNodes(nodes));
            assertEquals(false,service.validateGraph(workflow).get("valid"));
        }
    }
}
