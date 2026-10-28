package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.workflow.ProductionWorkflowRuntimeDTO;

import java.util.List;
import java.util.Optional;

public interface ProductProcessWorkflowRuntimeService {

    Optional<List<WorkProcessTaskDTO>> materializeIfActive(
            String factoryId,
            Long productionBatchId,
            String productTypeId);

    ProductionWorkflowRuntimeDTO getRuntime(String factoryId, Long productionBatchId);

    /**
     * 只读编译: 按工艺版本 id (工厂作用域, 必须 PUBLISHED) 编译出任务图, 不落库。
     * 不存在 / 跨厂 → 404; 未发布 → 409。
     */
    CompiledProductProcessWorkflow compilePublishedRevision(String factoryId, Long revisionId);
}
