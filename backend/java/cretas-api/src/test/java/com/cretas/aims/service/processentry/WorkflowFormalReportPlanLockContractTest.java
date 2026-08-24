package com.cretas.aims.service.processentry;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowFormalReportPlanLockContractTest {

    @Test
    void formalReportLocksWorkflowPlanBeforeValidationOrPersistence() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java"),
                StandardCharsets.UTF_8);
        int submit = source.indexOf("public ProcessSheetRowResult submitRow(");
        int lock = source.indexOf("lockWorkflowPlanForFormalReport(factoryId, planId);", submit);
        int workflowValidation = source.indexOf(
                "validateWorkflowSubmissionSelections(factoryId, planId, req);", submit);
        int persistence = source.indexOf("ProcessSheetRowResult result = saveRow(", submit);

        assertTrue(submit >= 0 && lock > submit, "正式报工入口必须调用计划锁");
        assertTrue(lock < workflowValidation, "计划锁必须早于 Workflow 正式选择校验");
        assertTrue(lock < persistence, "计划锁必须早于任何正式报工持久化");
        assertTrue(source.contains("productionPlanRepository.findByIdForUpdate(planId)"),
                "必须与 Workflow 重绑共用 production_plans 行锁");
    }
}
