import { del, get, post, put } from '@/api/request';
import type { BomRecipeSummary } from '@/api/bom';
import type {
  ProductProcessWorkflowActivation,
  ProductProcessWorkflowDefinition,
  WorkflowBomSyncPreflight,
  WorkflowPublishAndActivateRequest,
  WorkflowPublishAndActivateResponse,
} from './types';
import type { ProductConfigurationIssue } from './workflowPublishGate';

const workflowConflictConfig = {
  _handledErrorCodes: [
    'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
    'OPTIMISTIC_LOCK_CONFLICT',
  ],
};

const workflowPublishConfig = {
  _handledErrorCodes: [
    ...workflowConflictConfig._handledErrorCodes,
    'WORKFLOW_ACTIVE_BOM_REVISION_MISMATCH',
    'WORKFLOW_ACTIVE_BOM_FAMILY_INCOMPLETE',
    // 2026-08-07 阶段 3-3: 'WORKFLOW_ACTIVE_BOM_REQUIRED' 已删 —— 后端不再产出它
    // (没有生效 BOM 时改为从画布定义投影出一份, 见 BomRecipeServiceImpl#projectActiveBomFromRevision)。
    // 留着这个死条目会让下一个人以为它还是个可能的失败态。
    'BOM_WORKFLOW_UPGRADE_SLOT_AMBIGUOUS',
    'BOM_WORKFLOW_UPGRADE_MATERIAL_AMBIGUOUS',
    'BOM_WORKFLOW_INPUT_ITEM_MISSING',
    'BOM_WORKFLOW_UPGRADE_UNIT_INCOMPATIBLE',
    'PRODUCT_PROCESS_WORKFLOW_DRAFT_MISSING',
    'PRODUCT_PROCESS_WORKFLOW_REVISION_INCOMPLETE',
    'WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_REQUIRED',
    'WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_INVALID',
    'WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_CONFLICT',
    'WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_MISMATCH',
    'WORKFLOW_PUBLISH_REVISION_IDENTITY_CONFLICT',
    'WORKFLOW_PUBLISH_REPLAY_CONFLICT',
    'WORKFLOW_BOM_SYNC_USER_INPUT_REQUIRED',
    'WORKFLOW_BOM_SYNC_CONFLICT',
  ],
};

export function getProductProcessWorkflow(factoryId: string, productTypeId: string) {
  return get<ProductProcessWorkflowDefinition | null>(
    `/${factoryId}/product-process-workflows/${productTypeId}`,
  );
}

export function saveProductProcessWorkflowDraft(
  factoryId: string,
  productTypeId: string,
  definition: ProductProcessWorkflowDefinition,
) {
  return put<ProductProcessWorkflowDefinition>(
    `/${factoryId}/product-process-workflows/${productTypeId}/draft`,
    definition,
    workflowConflictConfig,
  );
}

/**
 * 产品配置就绪报告（BOM 完整性 / 包材 / 辅料提示等）。
 *
 * <p>发布前用来取「只提示不拦人」的项（如成品没配辅料）。
 * ⛔ 调用失败时调用方应当**放行**：它是提示，不是闸，不该让发布更脆弱。
 */
export function getProductConfigurationReadiness(
  factoryId: string,
  productTypeId: string,
  recipeId?: string,
) {
  return get<{ issues?: ProductConfigurationIssue[] }>(
    `/${factoryId}/product-configuration-readiness/${productTypeId}`,
    recipeId ? { recipeId } : undefined,
  );
}

export function publishProductProcessWorkflow(
  factoryId: string,
  productTypeId: string,
  lockVersion: number,
) {
  return post<ProductProcessWorkflowDefinition>(
    `/${factoryId}/product-process-workflows/${productTypeId}/publish`,
    { lockVersion },
    workflowPublishConfig,
  );
}

export function getWorkflowBomSyncPreflight(
  factoryId: string,
  productTypeId: string,
) {
  return get<WorkflowBomSyncPreflight>(
    `/${factoryId}/product-process-workflows/${productTypeId}/bom-sync-preflight`,
  );
}

export function publishAndActivateProductProcessWorkflow(
  factoryId: string,
  productTypeId: string,
  request: WorkflowPublishAndActivateRequest,
) {
  return post<WorkflowPublishAndActivateResponse>(
    `/${factoryId}/product-process-workflows/${productTypeId}/publish-and-activate`,
    request,
    workflowPublishConfig,
  );
}

// #12: 生产版本记录。只展示当前配置和仍被已正式报工计划绑定的旧配置。
export interface WorkflowVersionSummary {
  definitionVersion: number;
  status: 'DRAFT' | 'SNAPSHOT' | 'PUBLISHED';
  updatedAt: string | null;
  active: boolean;
  productionPlans: Array<{
    planId: string;
    planNumber: string | null;
    status: string | null;
    productionStarted: boolean;
  }>;
}

export function listProductProcessWorkflowVersions(factoryId: string, productTypeId: string) {
  return get<WorkflowVersionSummary[]>(
    `/${factoryId}/product-process-workflows/${productTypeId}/versions`,
  );
}

export function getProductProcessWorkflowVersion(
  factoryId: string,
  productTypeId: string,
  version: number,
) {
  return get<ProductProcessWorkflowDefinition>(
    `/${factoryId}/product-process-workflows/${productTypeId}/versions/${version}`,
  );
}

/**
 * 历史工艺版本当时用的 BOM 配方 (只读历史预览用): 返回钉在该工艺版本上的配方; 从来没钉过 → data 为 null,
 * ⛔ 绝不回落成「当前配方」—— 追溯视图把今天的配方当历史展示就是错的。
 */
export function getRecipePinnedToWorkflow(factoryId: string, productTypeId: string, workflowId: number) {
  return get<BomRecipeSummary | null>(
    `/${factoryId}/bom/recipes/by-product/${productTypeId}/pinned-to-workflow/${workflowId}`,
  );
}

export function getProductProcessWorkflowActivation(
  factoryId: string,
  productTypeId: string,
) {
  return get<ProductProcessWorkflowActivation | null>(
    `/${factoryId}/product-process-workflows/${productTypeId}/activation`,
  );
}

export function activateProductProcessWorkflow(factoryId: string, workflowId: number) {
  return put<ProductProcessWorkflowActivation>(
    `/${factoryId}/product-process-workflows/${workflowId}/activation`,
  );
}

export function deactivateProductProcessWorkflow(
  factoryId: string,
  productTypeId: string,
  lockVersion: number,
) {
  const params = new URLSearchParams({
    productTypeId,
    lockVersion: String(lockVersion),
  });
  return del<ProductProcessWorkflowActivation>(
    `/${factoryId}/product-process-workflows/activation?${params.toString()}`,
  );
}
