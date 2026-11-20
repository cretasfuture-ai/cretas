/**
 * BOM 调价建议 API (BomPriceAdjustmentController, D-S5-09)。
 *
 * - GET  /{factoryId}/bom/price-adjustments?status=&page=&size=   列表 (production / rd / finance 可读, 或有审批权)
 * - GET  /{factoryId}/bom/price-adjustments/{id}                  详情
 * - POST /{factoryId}/bom/price-adjustments/{id}/approve          批准 (approval_bom_price_adjustment:approve)
 * - POST /{factoryId}/bom/price-adjustments/{id}/reject           驳回, 原因必填 (同一权限码)
 *
 * 价格字段 (@PriceSensitive) 对看不到采购价的账号返回 null, 页面显示「—」。
 */
import { get, post } from './request';

export type BomPriceProposalStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type BomPriceProposalSource = 'PURCHASE_RECEIVE' | 'MANUAL_CHECK';

export interface BomPriceProposal {
  id: number;
  factoryId: string;
  recipeId: string;
  recipeItemId: number;
  recipeCode?: string | null;
  productTypeId?: string | null;
  productName?: string | null;
  materialTypeId: string;
  materialName?: string | null;
  currentUnitPrice?: number | null;
  proposedUnitPrice?: number | null;
  deltaAmount?: number | null;
  deltaPercent?: number | null;
  affectedProductCount: number;
  status: BomPriceProposalStatus;
  sourceType: BomPriceProposalSource;
  sourceReceiveRecordId?: string | null;
  approvedBy?: number | null;
  approvedAt?: string | null;
  approvalComment?: string | null;
  appliedAt?: string | null;
  createdAt?: string | null;
}

export interface BomPriceProposalPage {
  content: BomPriceProposal[];
  totalElements: number;
  totalPages: number;
}

export function listBomPriceProposals(
  factoryId: string,
  params: { status?: BomPriceProposalStatus; page: number; size: number },
) {
  return get<BomPriceProposalPage>(`/${factoryId}/bom/price-adjustments`, { params });
}

export function getBomPriceProposal(factoryId: string, id: number) {
  return get<BomPriceProposal>(`/${factoryId}/bom/price-adjustments/${id}`);
}

/**
 * ⚠️ approverId 仍在请求体里 (后端暂缓修复, Steve 2026-11-09); 这里传当前登录人。
 * expectedUnitPrice = 审批人看到的建议价: 建议在他看完之后被新收货更新过时后端返回 409, 不会悄悄批成新价。
 */
export function approveBomPriceProposal(
  factoryId: string,
  id: number,
  body: { approverId: number; comment?: string; expectedUnitPrice?: number | null },
) {
  return post<BomPriceProposal>(`/${factoryId}/bom/price-adjustments/${id}/approve`, body);
}

export function rejectBomPriceProposal(factoryId: string, id: number, reason: string) {
  return post<BomPriceProposal>(`/${factoryId}/bom/price-adjustments/${id}/reject`, { reason });
}
