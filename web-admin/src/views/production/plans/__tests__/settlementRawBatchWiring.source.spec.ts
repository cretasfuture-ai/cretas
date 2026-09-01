import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');

/**
 * 缺陷 48 —— 结单弹框接线断言。真正的行为验证在 settlementRawBatchDisplay.spec.ts
 * / rawWarehousePicker.spec.ts (纯函数, 可 revert->红/restore->绿)；这里只钉住
 * list.vue 是否真的接上了那两个纯函数，属于「机制在、没接上」(形态 B) 的兜底。
 */
describe('结单弹框接线 (缺陷 48)', () => {
  it('A: 摘要行不再直接兜底展示内部 UUID —— 硬约束 8, 全文件只此一处, 已改', () => {
    expect(source).not.toContain('line.batchNumber || line.materialBatchId');
    expect(source).toContain('{{ rawConsumptionDisplayLabel(line) }}');
    expect(source).toContain(
      "import { applyPickedMaterialBatchToLine, rawConsumptionDisplayLabel } from './settlementRawBatchDisplay';",
    );
  });

  it('A: 手工选批次的 el-select 接上了立即回填 batchNumber/物料名', () => {
    expect(source).toContain('@change="applyPickedRawMaterialBatch(line)"');
    expect(source).toContain('applyPickedMaterialBatchToLine(line, selectedMaterialBatch(line.materialBatchId))');
  });

  it('B: 可用库存参考不再只查 pickRawWarehouse 挑中的单一仓', () => {
    expect(source).not.toContain('pickRawWarehouse(warehouses)');
    expect(source).not.toContain("import { pickRawWarehouse } from './rawWarehousePicker'");
    expect(source).toContain('pickSettlementEligibleWarehouses(warehouses)');
    expect(source).toContain('eligibleWarehouseIds.has(batch.warehouseId)');
  });

  it('B: 查询原料批次不再传单一 warehouseId 参数 (改为按仓库并集本地过滤)', () => {
    expect(source).toContain("get<unknown>(`/${factoryId.value}/material-batches/status/AVAILABLE`, {\n        params: {\n          size: 200,\n        },\n      })");
  });

  it('B: 可用分布说明接上了 summarizeStockByWarehouse', () => {
    expect(source).toContain('materialWarehouseBreakdown(selectedMaterialBatch(line.materialBatchId))');
    expect(source).toContain('summarizeStockByWarehouse(entries, settlementWarehouseName)');
  });
});
