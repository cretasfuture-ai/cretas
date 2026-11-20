/**
 * 发货单(母单)提交的单价口径: 缺价/被遮的单价不能被写成 0。
 *
 * 背景: 无价格查看权限时订单行的 unitPrice 被后端遮掉(缺字段/null), 旧快速发货用
 * `Number(item.unitPrice || 0)` 把它发成 0。后端 SalesServiceImpl.createDeliveryRecord
 * 原样落 `item.setUnitPrice(dto.unitPrice)`, 并把「发货行与订单行单价都为 0」当零金额赠品跳过挂账;
 * 前端制造的假 0 会污染发货数据(项目原则: 禁止降级处理, 不返回假数据)。
 * 规则: 有确定数值(含真实的 0)原样传; 缺失/null/空串/非有限数 => 不带 unitPrice 字段。
 */
export function knownUnitPrice(value: unknown): number | undefined {
  if (value === null || value === undefined) return undefined;
  if (typeof value === 'string' && value.trim() === '') return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

/** 返回 item 的浅拷贝: unitPrice 已知则规范成 number, 否则整个字段去掉 (不是 0, 也不是 undefined 键)。 */
export function withKnownUnitPrice<T extends { unitPrice?: unknown }>(item: T): Omit<T, 'unitPrice'> & { unitPrice?: number } {
  const { unitPrice, ...rest } = item;
  const price = knownUnitPrice(unitPrice);
  return price === undefined ? rest : { ...rest, unitPrice: price };
}

type OrderLineLike = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

/** 订单列表「快速发货」: 由订单行生成发货行(计划数量默认=下单量)。 */
export function buildQuickDeliveryItems(orderItems: OrderLineLike[] | null | undefined): OrderLineLike[] {
  if (!Array.isArray(orderItems)) return [];
  return orderItems
    .filter((item) => item.productTypeId || item.productType?.id)
    .map((item) => withKnownUnitPrice({
      salesOrderItemId: item.id,
      productTypeId: item.productTypeId || item.productType?.id,
      productName: item.productName || item.productType?.name,
      deliveredQuantity: item.quantity || 0,
      // 计划数量输入框上限: 只兜「不超过下单量」, 精确判定仍由后端负责。
      maxQuantity: Number(item.quantity) > 0 ? Number(item.quantity) : undefined,
      unit: item.unit || 'kg',
      packagingSpecId: item.packagingSpecId,
      unitPrice: item.unitPrice,
    }));
}
