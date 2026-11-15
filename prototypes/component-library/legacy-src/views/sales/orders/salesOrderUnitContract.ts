import { canonicalUnitCode, storageUnitCode } from '@/utils/unitPricing';

export interface SalesOrderPackagingSpecContract {
  id: string;
  packageUnit: string;
  baseUnit: string;
  conversionFactor: number;
  active?: boolean;
}

export interface SalesOrderUnitLineContract {
  unit?: unknown;
  packagingSpecId?: string;
  packagingSpecs?: SalesOrderPackagingSpecContract[];
}

const UI_ONLY_FIELDS = [
  'packagingSpecs',
  'packagingSpecName',
  'packagingUnit',
  'packagingBaseUnit',
  'packagingFactor',
  'packagingLoadError',
  // 🔒 2026-09-02 Owner 裁定 (list.vue 是本裁定的主录入面): taxIncludedUnitPrice 仅供
  // 界面录入含税单价, 后端存储口径不变 (unitPrice 仍是未税, 见 SalesOrderItem.getLineAmount())。
  // 与 OrderItemsEditor.vue / detail.vue 的 `taxIncludedUnitPrice 仅供界面录入...不进后端 DTO`
  // 同一条约束 —— 提交前必须剥掉, 不进 CreateSalesOrderRequest.SalesOrderItemDTO。
  'taxIncludedUnitPrice',
] as const;

/**
 * The backend associates a selected packaging spec with its outer/package unit.
 * A spec such as 1 case = 8 boxes is therefore selectable only for a case order;
 * a base-unit box order needs no packagingSpecId even though boxQuantity may still
 * carry the informational case conversion.
 *
 * ⚠️ 这里<b>刻意</b>继续用 {@code canonicalUnitCode}(前端内部比较键)。匹配只需要一个稳定的
 * 内部标识, 两侧同源就够 —— 它与「发给后端的值」是两件事, 见
 * {@code storageUnitCode} 的注释。2026-08-18 那次改动没有动这条路径,
 * `__tests__/salesOrderUnitContract.spec.ts` 里每条 payload 断言都连着断言一遍匹配结论不变。
 */
export function packagingOptionsForUnit<T extends SalesOrderPackagingSpecContract>(
  line: SalesOrderUnitLineContract,
): T[] {
  const transactionUnit = canonicalUnitCode(line.unit);
  if (!transactionUnit) return [];
  return ((line.packagingSpecs || []) as T[]).filter(
    (spec) => spec.active !== false
      && canonicalUnitCode(spec.packageUnit) === transactionUnit,
  );
}

export function packagingSelectionError(line: SalesOrderUnitLineContract): string | null {
  const selectedId = String(line.packagingSpecId || '').trim();
  if (!selectedId) return null;
  const selected = (line.packagingSpecs || []).find((spec) => spec.id === selectedId);
  if (!selected || selected.active === false) {
    return '所选包装规格已失效，请重新选择';
  }
  if (canonicalUnitCode(selected.packageUnit) !== canonicalUnitCode(line.unit)) {
    return '所选包装规格与下单单位不一致，请切换单位或选择“不涉及”';
  }
  return null;
}

export function canonicalSalesOrderItemPayload(
  line: SalesOrderUnitLineContract,
): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    ...line,
    // 🔴 2026-08-13: 保留 只/个 —— 只在 UI 里保留没用, 发货单/送货单读的是【落库】的那个值,
    // 抹成 pcs 之后仓管看到的还是「件」。后端 crossLanguageCode 同样保留这两个标签。
    //
    // 🔴 2026-08-18 (单位管子最后一段): 从 canonicalUnitCodeKeepingCount 换成 storageUnitCode。
    // 前者是【前端内部比较键】, 它把用户选的「盒」翻成 `box` 再发出去; 而后端权威表 #2628 之后
    // 「盒」的规范码就是「盒」—— 方向相反。请求体必须带用户看到的那个字, 中间那趟翻译不该存在。
    // 只/个/件 仍然逐字保留(storageUnitCode 内部走的还是 KeepingCount 那条判据)。
    unit: storageUnitCode(line.unit),
  };
  UI_ONLY_FIELDS.forEach((field) => delete payload[field]);
  if (!String(line.packagingSpecId || '').trim()) delete payload.packagingSpecId;
  return payload;
}
