package com.cretas.aims.service;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.ProductProcessWorkflowVersionSummaryDTO;
import com.cretas.aims.dto.workflow.WorkflowBomSyncPreflightResponse;
import com.cretas.aims.dto.workflow.WorkflowPublishAndActivateResponse;

import java.util.List;
import java.util.Optional;
import com.cretas.aims.security.ActorRef;

public interface ProductProcessWorkflowService {

    Optional<ProductProcessWorkflowDTO> getEditorDefinition(String factoryId, String productTypeId);

    ProductProcessWorkflowDTO saveDraft(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflowDTO definition);

    ProductProcessWorkflowDTO publish(
            String factoryId, String productTypeId, Long lockVersion, Long operatorId);

    default ProductProcessWorkflowDTO publish(
            String factoryId, String productTypeId, Long lockVersion) {
        return publish(factoryId, productTypeId, lockVersion, null);
    }

    WorkflowBomSyncPreflightResponse bomSyncPreflight(String factoryId, String productTypeId);

    WorkflowPublishAndActivateResponse publishAndActivate(
            String factoryId,
            String productTypeId,
            Long lockVersion,
            String idempotencyKey,
            Long revisionId,
            String revisionHash,
            Integer definitionVersion,
            Long operatorId);

    default WorkflowPublishAndActivateResponse publishAndActivateAsActor(
            String factoryId, String productTypeId, Long lockVersion, String idempotencyKey,
            Long revisionId, String revisionHash, Integer definitionVersion, ActorRef actor) {
        throw new UnsupportedOperationException("Typed Workflow publication is required");
    }

    ProductProcessWorkflowDTO snapshot(String factoryId, String productTypeId, Long lockVersion);

    /** 只读: 当前配置以及仍被已正式报工计划绑定的历史版本。 */
    List<ProductProcessWorkflowVersionSummaryDTO> listVersions(String factoryId, String productTypeId);

    /** 只读: 某产品指定 definitionVersion 的完整图定义。 */
    ProductProcessWorkflowDTO getVersion(String factoryId, String productTypeId, Integer definitionVersion);
}
