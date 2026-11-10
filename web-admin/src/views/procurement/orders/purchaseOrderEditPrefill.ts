/** 供货关系至少要能判断是否停用; 其余字段由调用方的具体类型决定。 */
export interface SupplierRelationLike {
  active?: boolean | null;
}

/** 读取某供应商在本厂的供货关系。 */
export type SupplierMaterialFetcher<T extends SupplierRelationLike> = (
  factoryId: string,
  supplierId: string,
) => Promise<T[]>;

/**
 * 解析编辑/建单时可选的供货关系。
 *
 * 没有供应商时**不发请求** —— 采购单的 supplierId 允许为空 (「开始采购」按销售订单
 * 自动生成的草稿单就没有供应商, 见 Sheet 第 22 行), 而供货关系接口的路径是
 * `/{factoryId}/suppliers/{supplierId}/materials`, 空 id 会拼成 `/suppliers//materials`
 * 并 404, 把调用方整个打断。这里返回空表是诚实的: 还没选供应商, 本来就没有供货关系。
 *
 * 供应商存在却读取失败时**照常抛出**, 不吞成空表 —— 那是错误, 不是「该供应商没有物料」,
 * 吞掉会让用户在残缺的物料下拉里存出错单。
 */
export async function resolveSupplierMaterialRelations<T extends SupplierRelationLike>(
  factoryId: string,
  supplierId: string | null | undefined,
  fetchRelations: SupplierMaterialFetcher<T>,
): Promise<T[]> {
  const resolvedSupplierId = String(supplierId ?? '').trim();
  if (!resolvedSupplierId) return [];
  const relations = await fetchRelations(factoryId, resolvedSupplierId);
  return relations.filter((row) => row.active !== false);
}

export interface SupplierLockInput {
  /** 弹窗是否处于「编辑已有单据」模式 (新建时为 false)。 */
  isEditing: boolean;
  /** 打开编辑时读到的单据状态。 */
  orderStatus: string | null | undefined;
  /** 打开编辑时单据上**已经保存**的供应商 (不是表单里正在选的那个)。 */
  originalSupplierId: string | null | undefined;
}

/**
 * 编辑弹窗里供应商下拉要不要锁住。
 *
 * D-S411R-01 (2026-11-10, F006 实测): 缺料自动生成的采购草稿 (PO-AUTO-*) 没有供应商,
 * 提交被后端以 422 PURCHASE_SUPPLIER_REQUIRED 拒绝, 提示「请编辑采购草稿并选择供应商」——
 * 而编辑弹窗里供应商下拉写的是 `:disabled="isEditing"`, 对所有已存在单据一刀切锁住,
 * 于是这张草稿既提交不了、也补不了供应商, 只能取消重建。
 *
 * 口径:
 * - 新建: 不锁。
 * - 草稿 且 原本没有供应商: 不锁 —— 这正是编辑入口要服务的「事后补录供应商」场景。
 * - 草稿 且 已有供应商: 仍锁 (保持原行为; 换供应商会让已选的供应关系/规格/价格全部作废,
 *   不在本缺陷范围内)。
 * - 非草稿 (已提交 / 审批中 / 已审批 …): 锁。正常情况下非草稿根本进不了编辑弹窗
 *   (openEditDialog 会拒绝, 后端 updateDraftOrder 也只放行 DRAFT), 这里再锁一层是纵深防御。
 *
 * ⚠️ 判断依据是**打开时读到的**供应商, ⛔ 不是表单当前值 —— 否则用户选了第一个供应商的
 * 瞬间下拉就锁死, 选错了也改不回来。
 */
export function isSupplierLockedInEditor(input: SupplierLockInput): boolean {
  if (!input.isEditing) return false;
  if (String(input.orderStatus ?? '') !== 'DRAFT') return true;
  return String(input.originalSupplierId ?? '').trim() !== '';
}

export interface DraftLineLike {
  materialTypeId: string;
}

export interface RebindRelationLike {
  id: string;
  materialTypeId: string;
}

export interface DraftLineRebindResult<L, R> {
  /** 该供应商对这个原料恰好有一条生效供应关系的行。 */
  matched: Array<{ line: L; relation: R }>;
  /** 该供应商不供应 (或供应关系不唯一) 的行 —— 由用户处理, ⛔ 不猜。 */
  unmatched: L[];
}

/**
 * 给「原本没有供应商」的草稿补选供应商后, 把草稿里已有的明细行按原料对应到该供应商的供应关系。
 *
 * 为什么不能沿用 onSupplierChange 的「清空明细」: 自动草稿的明细 (原料 + 缺口数量) 是系统按缺料
 * 算出来的, 清空等于让采购员凭记忆重填一遍, 而且容易填错数量。
 *
 * 只按 materialTypeId 精确匹配; 同一原料出现多条生效关系时视为不唯一, 放进 unmatched,
 * 由用户在行内下拉里自己选 (⛔ 不挑第一条)。传入的 relations 应当已经滤掉停用关系
 * (见 resolveSupplierMaterialRelations)。
 */
export function matchDraftLinesToSupplierRelations<L extends DraftLineLike, R extends RebindRelationLike>(
  lines: L[],
  relations: R[],
): DraftLineRebindResult<L, R> {
  const byMaterial = new Map<string, R[]>();
  for (const relation of relations) {
    const key = String(relation.materialTypeId || '');
    if (!key) continue;
    byMaterial.set(key, [...(byMaterial.get(key) ?? []), relation]);
  }
  const result: DraftLineRebindResult<L, R> = { matched: [], unmatched: [] };
  for (const line of lines) {
    const candidates = byMaterial.get(String(line.materialTypeId || '')) ?? [];
    if (candidates.length === 1) {
      result.matched.push({ line, relation: candidates[0] });
    } else {
      result.unmatched.push(line);
    }
  }
  return result;
}
