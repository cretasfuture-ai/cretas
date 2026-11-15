/**
 * 分仓库存查询「原料库存 / 成品库存」表格的空态判定 (缺陷 B, 2026-09-02)。
 *
 * <h2>为什么重做</h2>
 * 旧版无条件用固定文案 `empty-text="该仓库暂无原料批次"`，与「有没有搜索词」
 * 「仓库到底有没有批次」两件事完全脱钩。LIUSHANMEN 生产实测：搜索一个不存在的
 * 批次号（如 `1300`），表格显示「该仓库暂无原料批次」，而同一屏统计卡片明明写着
 * 「原料批次 56」——工人会读成「这个仓库是空的」，而真相是「你搜的这个词没搜到」。
 *
 * ⚠️ 与 `procurement/orders/materialDropdownEmptyState.ts`（缺陷 #459）同一个
 * 判定顺序：**先看这一批到底有没有东西（totalCount），再看搜索词**——
 * 而不是反过来按「有没有搜索词」二分。⛔ 不要发明第二套判定模式，两处保持同构。
 *
 * <h2>两个状态，⛔ 不许塌成一个</h2>
 * - `warehouse-empty`：这个仓库这一类批次总数就是 0（与搜索词无关，搜什么都一样空）。
 * - `no-match`：仓库里确实有批次（totalCount > 0），只是当前搜索词没匹配上任何一条。
 *
 * <p>判定顺序固定为 **先 totalCount === 0，再看有没有 query**——
 * `totalCount === 0` 时无论 query 是什么，filteredList 恒为空，都该说「仓库没有」，
 * 不该被误判成「没搜到」（那会暗示"仓库有货，换个词再试"，一个不可能成功的指令）。
 */

export type WarehouseInventoryEmptyState = 'warehouse-empty' | 'no-match';

export type WarehouseInventoryBatchKind = 'materials' | 'products';

export interface WarehouseInventoryEmptyInput {
  /** 这一类批次（原料/成品）在当前仓库的**总数**（后端权威计数，不是过滤后的条数）。 */
  totalCount: number;
  /** 当前搜索关键词（未 trim 也可）。 */
  query: string;
  kind: WarehouseInventoryBatchKind;
}

export interface WarehouseInventoryEmptyPresentation {
  state: WarehouseInventoryEmptyState;
  /** el-table `empty-text` 展示文案。 */
  text: string;
}

const KIND_LABEL: Record<WarehouseInventoryBatchKind, string> = {
  materials: '原料',
  products: '成品',
};

export function resolveWarehouseInventoryEmpty(
  input: WarehouseInventoryEmptyInput,
): WarehouseInventoryEmptyPresentation {
  const label = KIND_LABEL[input.kind];
  const total = Number(input.totalCount) || 0;

  // 判定顺序：先问「这一类批次总数是不是 0」——与搜索词无关，恒成立就恒该说「暂无」。
  if (total <= 0) {
    return { state: 'warehouse-empty', text: `该仓库暂无${label}批次` };
  }

  // 到这里 totalCount > 0，仓库确实有货，表格却是空的 ⇒ 只可能是搜索词没匹配上。
  const query = input.query.trim();
  const querySuffix = query ? `与"${query}"匹配的` : '匹配的';
  return {
    state: 'no-match',
    text: `没有找到${querySuffix}${label}批次（该仓库共有 ${total} 个${label}批次，试试清空搜索词）`,
  };
}
