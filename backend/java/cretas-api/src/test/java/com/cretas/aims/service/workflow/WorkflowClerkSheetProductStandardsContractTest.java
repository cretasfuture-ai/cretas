package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.ProcessParamCheck;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.workflow.impl.WorkflowClerkSheetServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工序表(ProcessSheet)工作流路径的录入页与判定共用 {@code ProcessDescriptor.customFieldSchema}:
 * 它必须带上「本计划锁定的那一版画布」里该工序节点的标准。两个产品共用同一道工序时各取各的;
 * 画布没配的产品拿到的就是工序 schema 原件(旧行为)。判定函数与 ProcessSheetServiceImpl 同一个
 * {@link ProcessParamCheck#evaluate}。
 */
class WorkflowClerkSheetProductStandardsContractTest {

    private final ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
    private final ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
    private final ProductionWorkflowInstanceRepository instanceRepo = mock(ProductionWorkflowInstanceRepository.class);
    private final WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
    private final WorkflowTaskPortRepository portRepo = mock(WorkflowTaskPortRepository.class);
    private final WorkProcessRepository processRepo = mock(WorkProcessRepository.class);
    private final WorkflowReportingUnitResolver unitResolver = mock(WorkflowReportingUnitResolver.class);

    private final List<Map<String, Object>> schema = List.of(oilField());

    private static Map<String, Object> oilField() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", "oilTemp");
        f.put("label", "油温");
        f.put("type", "number");
        f.put("enabled", true);
        f.put("unit", "℃");
        f.put("min", 100);
        f.put("max", 200);
        return f;
    }

    private WorkflowClerkSheetServiceImpl service() {
        when(unitResolver.resolve(anyString(), anyString(), any(), anyString()))
                .thenAnswer(i -> i.getArgument(3));
        WorkProcess wp = new WorkProcess();
        wp.setId("FRY");
        wp.setFactoryId("F006");
        wp.setProcessName("炒制");
        wp.setCustomFieldSchema(schema);
        when(processRepo.findByFactoryIdAndId("F006", "FRY")).thenReturn(Optional.of(wp));
        return new WorkflowClerkSheetServiceImpl(batchRepo, planRepo, instanceRepo, taskRepo, portRepo, processRepo,
                mock(ProductWorkProcessRepository.class), mock(RawMaterialTypeRepository.class),
                mock(ProductTypeRepository.class), mock(BomRecipeRepository.class),
                mock(BomRecipeItemRepository.class), mock(BomItemSubstituteService.class), unitResolver);
    }

    /** 给一个计划铺好: 批次 → 运行时实例(带该产品的画布快照) → 一道炒制任务 + 进出端口。 */
    private void arrange(String planId, long batchId, long instanceId, String productTypeId, String nodesJson) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId("F006");
        when(planRepo.findByIdAndFactoryId(planId, "F006")).thenReturn(Optional.of(plan));
        ProductionBatch batch = new ProductionBatch();
        batch.setId(batchId);
        batch.setFactoryId("F006");
        batch.setProductTypeId(productTypeId);
        batch.setWorkflowSelectionMode(ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        batch.setSelectedWorkflowId(44L);
        batch.setSelectedWorkflowVersion(3);
        when(batchRepo.findByFactoryIdAndProductionPlanId("F006", planId)).thenReturn(List.of(batch));
        ProductionWorkflowInstance instance = ProductionWorkflowInstance.create(
                "F006", batchId, productTypeId, 44L, 3, nodesJson, "[]", LocalDateTime.of(2026, 11, 14, 8, 0));
        instance.setId(instanceId);
        when(instanceRepo.findByFactoryIdAndProductionBatchId("F006", batchId)).thenReturn(Optional.of(instance));
        WorkProcessTask task = WorkProcessTask.builder().id(instanceId * 10).factoryId("F006")
                .productionBatchId(batchId).productTypeId(productTypeId).workflowInstanceId(instanceId)
                .workflowNodeId("process:1").workProcessId("FRY").processOrder(1).plannedUnit("kg")
                .status(WorkProcessTask.Status.PENDING).build();
        when(taskRepo.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc("F006", instanceId))
                .thenReturn(List.of(task));
        when(portRepo.findByFactoryIdAndWorkflowInstanceId("F006", instanceId)).thenReturn(List.of(
                port(task.getId(), instanceId, "in", WorkflowTaskPort.Direction.INPUT, "RAW_MATERIAL"),
                port(task.getId(), instanceId, "out", WorkflowTaskPort.Direction.OUTPUT, "SEMI_FINISHED")));
    }

    private static WorkflowTaskPort port(Long taskId, long instanceId, String id,
                                         WorkflowTaskPort.Direction direction, String kind) {
        WorkflowTaskPort p = new WorkflowTaskPort();
        p.setFactoryId("F006");
        p.setWorkflowInstanceId(instanceId);
        p.setTaskId(taskId);
        p.setWorkflowPortId(id + instanceId);
        p.setDirection(direction);
        p.setOrdinal(direction == WorkflowTaskPort.Direction.INPUT ? 1 : 2);
        p.setMaterialNodeId("material-" + id + instanceId);
        p.setMaterialKind(kind);
        p.setSkuId("SKU-" + id);
        p.setUnit("kg");
        p.setRequired(true);
        return p;
    }

    private static String nodes(Integer min, Integer max) {
        String params = min == null ? "" : ",\"processParams\":[{\"label\":\"油温\",\"unit\":\"℃\",\"min\":"
                + min + ",\"max\":" + max + "}]";
        return "[{\"id\":\"process:1\",\"kind\":\"PROCESS\",\"data\":{\"workProcessId\":\"FRY\"" + params + "}}]";
    }

    private List<Map<String, Object>> schemaFor(WorkflowClerkSheetServiceImpl svc, String planId) {
        WorkflowClerkSheetConfigDTO cfg = svc.getWorkflowSheetConfig("F006", planId);
        return castSchema(cfg.getProcesses().get(0).getCustomFieldSchema());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castSchema(Object o) {
        return (List<Map<String, Object>>) o;
    }

    private static String statusAt(List<Map<String, Object>> schema, int value) {
        List<Map<String, Object>> checks = ProcessParamCheck.evaluate(schema, Map.of("oilTemp", value));
        assertEquals(1, checks.size(), "判定一定发生 (阳性读数)");
        return String.valueOf(checks.get(0).get("status"));
    }

    @Test
    void twoProductsSharingOneProcessAreJudgedByTheirOwnLockedCanvas() {
        WorkflowClerkSheetServiceImpl svc = service();
        arrange("PLAN-A", 901L, 501L, "PT-A", nodes(150, 160));
        arrange("PLAN-B", 902L, 502L, "PT-B", nodes(100, 120));

        List<Map<String, Object>> a = schemaFor(svc, "PLAN-A");
        List<Map<String, Object>> b = schemaFor(svc, "PLAN-B");

        assertEquals("OK", statusAt(a, 155));
        assertEquals("HIGH", statusAt(b, 155));
        assertEquals("HIGH", statusAt(a, 165));
        assertEquals(150, a.get(0).get("min"), "录入页看到的也是本产品的标准");
    }

    @Test
    void aProductWithoutCanvasParamsGetsTheProcessSchemaUnchanged() {
        WorkflowClerkSheetServiceImpl svc = service();
        arrange("PLAN-C", 903L, 503L, "PT-C", nodes(null, null));

        List<Map<String, Object>> c = schemaFor(svc, "PLAN-C");

        assertSame(schema.get(0), c.get(0), "没配画布参数: 原样返回, 旧行为");
        assertEquals("OK", statusAt(c, 165));
    }
}
