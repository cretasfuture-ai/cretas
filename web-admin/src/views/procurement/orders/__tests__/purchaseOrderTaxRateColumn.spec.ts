import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const detailSource = readFileSync(resolve(__dirname, '..', 'detail.vue'), 'utf8');

describe('procurement order tax rate column', () => {
  it('renders the real per-line taxRate field in the detail table', () => {
    expect(detailSource).toContain('prop="taxRate"');
    expect(detailSource).toContain('label="税率"');
    expect(detailSource).toContain('{{ formatTaxRate(row.taxRate) }}');
    expect(detailSource).toContain('v-if="canViewPrice" prop="taxRate"');
    expect(detailSource).toContain('label="含税单价"');
  });

  it('formats backend taxRate values without mock defaults', () => {
    expect(detailSource).toContain('function formatTaxRate(rate: unknown): string');
    // 后端 tax_rate 恒为百分数: 详情页委托 formatPurchaseTaxRate (行为断言在 purchaseOrderMoney.spec.ts)。
    // ⛔ 不再把 ≤1 的值当小数乘 100 —— prod 有 0.13% 的行, 旧写法显示 13% 而税额按 0.13% 算。
    expect(detailSource).toContain('return formatPurchaseTaxRate(rate);');
    expect(detailSource).not.toContain('numeric <= 1 ? numeric * 100');
    expect(detailSource).not.toContain('mockTaxRate');
    expect(detailSource).not.toContain('13%');
  });
});
