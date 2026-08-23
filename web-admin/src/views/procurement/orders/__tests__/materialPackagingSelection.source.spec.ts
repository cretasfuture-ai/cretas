import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const orderSource = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');
// 2026-08-19: 原来读 StartPurchaseDialog.vue —— 那个组件自 2026-07-20 (`bb17530017`
// 删掉「开始采购」按钮) 起就没有任何 view 挂载, 已删除。同一份契约现在由**合并弹窗**承担,
// 单张 SO 也走它 (sales/orders/list.vue:「单张时等价于开始采购但走合并弹窗」)。
const suggestionSource = readFileSync(
  resolve(import.meta.dirname, '../../../../components/dialog/MergePurchaseDialog.vue'),
  'utf8',
);

describe('采购原料多包装选择契约', () => {
  it('供应商未维护专属规格时回退原料类型包装规格', () => {
    // 2026-08-19: 原断言钉的是 `loadMaterialSpecs(relation.materialTypeId)` 这个**字面写法**。
    // 回退行为没变, 变的是签名 —— 现在必须把供应关系一起传进去, 后端才能按那条关系把
    // 采购价折算到包装单位 (客户 2026-07-30「设完换算规格后不再带出采购价」的物料侧那一半)。
    // 按「改写不删除」抬成: 仍然要用 materialTypeId 去取回退规格, 且必须带上 relation.id。
    expect(orderSource).toContain('loadMaterialSpecs(relation.materialTypeId, relation.id)');
    expect(orderSource).toContain('supplierSpecs.length > 0');
    expect(orderSource).toContain("id: `material:${row.id}`");
    expect(orderSource).toContain('materialPackagingSpecId: i.purchasePackagingSpecId?.startsWith');
  });

  it('采购建议弹窗读取全部动态包装规格并提交具体规格身份', () => {
    expect(suggestionSource).toContain('packagingSpecs?: Array');
    expect(suggestionSource).toContain('materialPackagingSpecId: packagingByMaterial.value');
    expect(suggestionSource).toContain('dynamic.join');
  });
});
