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

    @Test void frontendIgnoredDisplayFieldsMatchBackendSaveContract() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.isDirectory(root.resolve("web-admin"))) root=root.getParent();
        assertNotNull(root);
        var contract=mapper.readTree(root.resolve("web-admin/src/views/platform/approval-workflow-editor/lib/approvalDisplayFields.json").toFile());
        Map<String,List<String>> expected=Map.of("approval",List.of("approverRoleLabels","approverUserLabels","delegateUserLabel"),
            "notify",List.of("notifyRoleLabels"));
        assertEquals(expected,mapper.convertValue(contract,Map.class));
        var workflow=graph("ANY");
        var nodes=service.deserializeNodes(workflow.getNodesJson());
        var config=new HashMap<>(nodes.get(1).getConfig());
        expected.get("approval").forEach(key->config.put(key,key.equals("delegateUserLabel")?"展示标签":List.of("展示标签")));
        nodes.get(1).setConfig(config);
        var notice=new ApprovalWorkflowNode(); notice.setId("notice"); notice.setType("notify"); notice.setLabel("通知");
        notice.setConfig(Map.of("notifyRoles",List.of("quality_manager"),"notifyRoleLabels",List.of("质量经理")));
        nodes.add(notice);
        workflow.setEdgesJson("""
            [{"id":"e1","source":"start","target":"quality"},{"id":"e2","source":"quality","target":"notice"},
             {"id":"e3","source":"notice","target":"end"}]
            """);
        var patch=new ApprovalWorkflow(); patch.setNodesJson(service.serializeNodes(nodes));
        when(repository.save(workflow)).thenAnswer(i->i.getArgument(0));
        var saved=service.update("F006","local-workflow",patch,false);
        assertEquals(config,service.deserializeNodes(saved.getNodesJson()).get(1).getConfig());
        verify(repository).save(workflow);
        config.put("mode","INVALID");
        patch.setNodesJson(service.serializeNodes(nodes));
        assertThrows(BusinessException.class,()->service.update("F006","local-workflow",patch,false));
        verify(repository,times(1)).save(workflow);
    }

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
