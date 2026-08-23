/**
 * 原材料批次「账实字段」锁定清单 —— 与后端账实红线闸同源，不要在别处再抄一份。
 *
 * 权威定义在后端:
 *   backend/java/cretas-api/.../service/impl/MaterialBatchServiceImpl.java
 *   `inventoryFieldChangedByRequest()` / `INVENTORY_FIELD_LABELS`
 *
 * 2026-08-20 owner 裁定: PUT /material-batches/{id} 只要这些字段的**值真的变了**
 * (不是"请求里带了这个字段"), 一律 403 —— 对所有角色一视同仁, 唯一正路是
 * 「仓储管理 → 盘点与期初库存」(留痕)。
 *
 * ⚠️ 本仓形态 D「同一个东西两份必漂」: 后端加字段, 这里要跟。
 * ⚠️ 这个握手**已经真的发生过一次**(2026-08-20): 后端把 receiptDate 并入锁定清单后,
 * 那道跨端契约闸当场红了一条(`missingInApp = ["receiptDate"]`), 逼这里跟上 ——
 * 它按设计工作了。`materialBatchLockedFields.contract.test.ts` 直接对着后端源码
 * 断言两侧字段集合逐一相等, 后端再漂这道闸还会红。
 */
export const LOCKED_MATERIAL_BATCH_FIELDS = [
  'receiptQuantity',
  'unitPrice',
  'weightPerUnit',
  'totalWeight',
  'totalValue',
  'quantityUnit',
  'materialTypeId',
  'supplierId',
  // 2026-08-20 后续: 后端把入库日期也并入锁定清单(commit 56eebf9eb2)。
  // ⚠️ 它的正路**不是盘点** —— 盘点只改数量。日期走专门的「更正入库日期」端点
  //    (POST .../receipt-date-correction, 原因必填 + 写 material_batch_date_corrections 留痕)。
  'receiptDate',
] as const;

export type LockedMaterialBatchField = (typeof LOCKED_MATERIAL_BATCH_FIELDS)[number];

/** 给用户看的中文标签 (与后端 INVENTORY_FIELD_LABELS 同义, 仅用于 App 内展示)。 */
export const LOCKED_MATERIAL_BATCH_FIELD_LABELS: Record<LockedMaterialBatchField, string> = {
  receiptQuantity: '入库数量',
  unitPrice: '单价',
  weightPerUnit: '每单位重量',
  totalWeight: '入库总重量',
  totalValue: '入库总价值',
  quantityUnit: '数量单位',
  materialTypeId: '原料类型',
  supplierId: '供应商',
  receiptDate: '入库日期',
};

/** 从一个待提交的批次载荷里剔除账实字段 (原地修改, 不新建对象)。 */
export function stripLockedMaterialBatchFields(payload: Record<string, unknown>): void {
  LOCKED_MATERIAL_BATCH_FIELDS.forEach((field) => {
    delete payload[field];
  });
}
