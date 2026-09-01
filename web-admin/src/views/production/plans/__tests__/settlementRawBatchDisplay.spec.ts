import { describe, expect, it } from 'vitest';

import {
    RAW_BATCH_NUMBER_PENDING_LABEL,
    applyPickedMaterialBatchToLine,
    rawConsumptionDisplayLabel,
} from '../settlementRawBatchDisplay';

/**
 * 结单弹框「实际原料领用」摘要行 —— 缺陷 48 (A)。
 *
 * 事故形态：原写法 `line.batchNumber || line.materialBatchId` 在 batchNumber
 * 缺失时把内部主键 (UUID) 直接显示给仓库用户，与 #484「发货行 255（产品：
 * 熟肉饼）可用库存不足」内部行ID泄露同一族——内部标识符对仓管没有任何
 * 可操作性。
 */
describe('结单摘要行展示文案 (缺陷 48 A)', () => {
    it('批次号存在时原样展示 —— 阴性对照: 不改变既有正确行为', () => {
        expect(rawConsumptionDisplayLabel({ batchNumber: 'BATCH-2026-001', materialBatchId: 'uuid-1' }))
            .toBe('BATCH-2026-001');
    });

    it('批次号 + 物料名都在时展示「批次号 · 物料名」', () => {
        expect(rawConsumptionDisplayLabel({
            batchNumber: 'BATCH-2026-001',
            materialName: 'L1纸箱',
            materialBatchId: 'uuid-1',
        })).toBe('BATCH-2026-001 · L1纸箱');
    });

    it('批次号缺失时给出诚实占位 —— ⛔ 绝不回退到 materialBatchId (UUID)', () => {
        const label = rawConsumptionDisplayLabel({
            batchNumber: null,
            materialName: 'L1纸箱',
            materialBatchId: '08071e5c-90e2-4a4d-8b8f-000000000000',
        });
        expect(label).toBe(RAW_BATCH_NUMBER_PENDING_LABEL);
        expect(label).not.toContain('08071e5c');
    });

    it('批次号、物料名都缺失时同样给占位, 不是空字符串', () => {
        expect(rawConsumptionDisplayLabel({ materialBatchId: 'uuid-only' }))
            .toBe(RAW_BATCH_NUMBER_PENDING_LABEL);
    });

    it('反向断言: 无论输入什么, 输出里都不会出现原始 materialBatchId', () => {
        const uuids = ['08071e5c-90e2-4a4d-8b8f-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111'];
        for (const materialBatchId of uuids) {
            expect(rawConsumptionDisplayLabel({ materialBatchId })).not.toContain(materialBatchId);
        }
    });
});

describe('结单弹框手工选批次立即带上 batchNumber/物料名 (缺陷 48 A)', () => {
    it('选中批次后, 该行立即拿到 batchNumber 与物料名 —— 不必等下一次 prefill 回填', () => {
        const line: { materialBatchId: string; batchNumber?: string | null; materialName?: string | null } = {
            materialBatchId: 'batch-9',
            batchNumber: null,
            materialName: null,
        };
        applyPickedMaterialBatchToLine(line, {
            batchNumber: 'BATCH-2026-009',
            materialName: '客户来料纸箱',
        });
        expect(line.batchNumber).toBe('BATCH-2026-009');
        expect(line.materialName).toBe('客户来料纸箱');
    });

    it('物料名缺失时退到 materialTypeName', () => {
        const line: { batchNumber?: string | null; materialName?: string | null } = {};
        applyPickedMaterialBatchToLine(line, { batchNumber: 'B-1', materialTypeName: '纸箱类' });
        expect(line.materialName).toBe('纸箱类');
    });

    it('批次为 null/undefined (尚未加载完/被清空) 时不覆盖既有值', () => {
        const line = { batchNumber: 'KEEP-ME', materialName: '别动我' };
        applyPickedMaterialBatchToLine(line, null);
        applyPickedMaterialBatchToLine(line, undefined);
        expect(line.batchNumber).toBe('KEEP-ME');
        expect(line.materialName).toBe('别动我');
    });
});
