import { mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({
  permission: { canViewPrice: true, canViewPurchasePrice: false },
}));

vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => harness.permission,
}));

import TableFooter from '../TableFooter.vue';

// S5-12: 采购单 / 退货合计的金额按配置的采购读权限显示, 不看旧名单 canViewPrice。
const stats = [
  { label: '共', value: 3, format: 'number' },
  { label: '合计金额', value: 450, format: 'currency', canViewPrice: true },
];

function render(priceGate?: 'default' | 'purchase' | 'both') {
  return mount(TableFooter, {
    props: { stats, showAi: false, showExport: false, ...(priceGate ? { priceGate } : {}) },
    global: { stubs: { ElPagination: true, ElButton: true, ElIcon: true } },
  }).text();
}

describe('TableFooter priceGate', () => {
  beforeEach(() => {
    harness.permission = { canViewPrice: true, canViewPurchasePrice: false };
  });

  it('purchase gate hides amounts for a role in the old list without configured procurement read', () => {
    expect(render('purchase')).not.toContain('合计金额');
    expect(render('purchase')).toContain('共');
    expect(render()).toContain('合计金额'); // 默认口径不变 (销售等页面)
  });

  it('both gate needs the old list AND procurement read', () => {
    harness.permission = { canViewPrice: false, canViewPurchasePrice: true };
    expect(render('both')).not.toContain('合计金额');
    expect(render('purchase')).toContain('合计金额');
    harness.permission = { canViewPrice: true, canViewPurchasePrice: true };
    expect(render('both')).toContain('合计金额');
  });
});
