import { describe, expect, it } from 'vitest';

import {
  aggregateMaterialInventoryOptions,
  isManuallyTransferableBatch,
  type MaterialInventoryBatch,
} from '../transferCreate';

/**
 * 🔴 2026-08-28 六膳门生产实测的回归闸。
 *
 * 手动新建调拨单里选「汉堡排原料 YL068」，页面「现有库存 100,000 kg」，
 * 提交却报「可用 0 kg，请减少调拨数量或选择有库存的调出仓库」——
 * 两条建议都没用，因为货就在那个仓，只是**这一类库存不允许手动调拨**。
 *
 * 这道闸守的**行为**是：不可手动调拨的批次既不进下拉，也不计入「现有库存」。
 * 口径必须与后端 `findAvailableBatchesFEFOByWarehouse` 的两条排除一致。
 */
const COMPANY: MaterialInventoryBatch = {
  materialTypeId: 'RAW-OK',
  materialName: '自有原料',
  materialCategory: '主材',
  quantityUnit: 'kg',
  currentQuantity: 950,
  status: 'AVAILABLE',
  ownership: 'COMPANY_OWNED',
};

/** 六膳门那条真批次的形状: CMA- 客供料, 100000 kg, 在主仓, AVAILABLE。 */
const CUSTOMER_OWNED: MaterialInventoryBatch = {
  materialTypeId: 'RMT_fc6c9c71-d37a-4888-b345-01bf95a227ca',
  materialName: '汉堡排原料',
  materialCategory: '主材',
  quantityUnit: 'kg',
  currentQuantity: 100000,
  status: 'AVAILABLE',
  ownership: 'CUSTOMER_OWNED',
};

/** 生产仓那 16 条的形状: 在制半成品工件, materialTypeId 是原料血缘。 */
const WIP_WORKPIECE: MaterialInventoryBatch = {
  materialTypeId: 'RAW-WIP',
  materialName: '在制半成品',
  materialCategory: '主材',
  quantityUnit: 'kg',
  currentQuantity: 500,
  status: 'AVAILABLE',
  ownership: 'COMPANY_OWNED',
  sourceDocType: 'PRODUCTION_BATCH',
};

describe('不可手动调拨的库存不出现在调拨页', () => {
  it('阳性对照: 自有、非在制的批次照常进下拉', () => {
    expect(isManuallyTransferableBatch(COMPANY)).toBe(true);
    const options = aggregateMaterialInventoryOptions([COMPANY]);
    expect(options.map((o) => o.id)).toEqual(['RAW-OK']);
    expect(options[0].currentStock).toBe(950);
  });

  it('客供料(来料加工)不进下拉, 也不计入现有库存', () => {
    expect(isManuallyTransferableBatch(CUSTOMER_OWNED)).toBe(false);
    const options = aggregateMaterialInventoryOptions([COMPANY, CUSTOMER_OWNED]);
    expect(options.map((o) => o.id)).toEqual(['RAW-OK']);
  });

  it('在制半成品工件不进下拉, 也不计入现有库存', () => {
    expect(isManuallyTransferableBatch(WIP_WORKPIECE)).toBe(false);
    const options = aggregateMaterialInventoryOptions([COMPANY, WIP_WORKPIECE]);
    expect(options.map((o) => o.id)).toEqual(['RAW-OK']);
  });

  it('🔴 同物料混合时, 现有库存只累计可调拨的那部分 —— 这正是「100,000 对 0」的成因', () => {
    const sameMaterialCompanyPart: MaterialInventoryBatch = {
      ...CUSTOMER_OWNED, ownership: 'COMPANY_OWNED', currentQuantity: 20,
    };
    const options = aggregateMaterialInventoryOptions([sameMaterialCompanyPart, CUSTOMER_OWNED]);
    expect(options).toHaveLength(1);
    // 20 可调拨 + 100000 客供 —— 页面只能显示 20, 否则又回到「看着有货、选了必被拒」
    expect(options[0].currentStock).toBe(20);
  });

  it('整批都不可调拨时, 该物料整个从下拉里消失(而不是显示 0)', () => {
    expect(aggregateMaterialInventoryOptions([CUSTOMER_OWNED])).toEqual([]);
  });

  it('⛔ 阴性对照: 字段缺失(老数据/未透出)时不许误伤 —— 默认可调拨', () => {
    const noOwnershipField: MaterialInventoryBatch = {
      materialTypeId: 'RAW-LEGACY', materialName: '历史批次',
      quantityUnit: 'kg', currentQuantity: 10, status: 'AVAILABLE',
    };
    expect(isManuallyTransferableBatch(noOwnershipField)).toBe(true);
    expect(aggregateMaterialInventoryOptions([noOwnershipField]).map((o) => o.id)).toEqual(['RAW-LEGACY']);
  });
});
