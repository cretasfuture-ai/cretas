/**
 * 详情抽屉的负责人以服务端最新值为准: 列表行可能是指派之前的旧数据, 改派时下拉要回填当前负责人。
 * 风险标签不走进场动画, 刷新后马上看得到。
 */
import { describe, it, expect, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

vi.mock('@/api/deliveryRisks', () => ({
  listDeliveryRisks: vi.fn().mockResolvedValue({ success: true, data: [] }),
  scanDeliveryRisks: vi.fn(),
  getDeliveryRisk: vi.fn().mockResolvedValue({
    success: true,
    data: {
      case: { id: 'c1', orderNumber: 'SO-1', stageLabel: '执行中', ownerRole: 'production_manager', ownerUserId: 9, closedAt: null },
      assessment: { level: 'RISK', dueDate: '2026-11-10', signals: [], evidence: [], unknowns: [], impact: [], suggestions: [] },
      actions: [],
      events: [],
      checks: [],
    },
  }),
  listAssignees: vi.fn().mockResolvedValue({ success: true, data: [{ id: 9, name: '车间主管', role: null }] }),
  assignDeliveryRisk: vi.fn(),
  recheckDeliveryRisk: vi.fn(),
  finishDeliveryRisk: vi.fn(),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));

import DeliveryRisks from '../index.vue';

interface PageVm {
  openDetail(row: { id: string; ownerUserId: number | null }): Promise<void>;
  ownerUserId: number | null;
}

describe('交付风险详情刷新', () => {
  it('负责人回填服务端的值, 不用列表行里的旧值', async () => {
    const wrapper = mount(DeliveryRisks, { global: { stubs: { 'el-table-column': true, teleport: true } } });
    await flushPromises();
    const vm = wrapper.vm as unknown as PageVm;
    await vm.openDetail({ id: 'c1', ownerUserId: null });
    await flushPromises();
    expect(vm.ownerUserId).toBe(9);
  });
});
