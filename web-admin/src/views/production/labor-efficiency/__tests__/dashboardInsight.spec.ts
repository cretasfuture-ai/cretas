/** 人效看板：三张图下的「一图一分析」按页面真实接口数据现算（问题 40） */
import { describe, it, expect, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const summary = vi.hoisted(() => vi.fn());
const stepBreakdown = vi.hoisted(() => vi.fn());
vi.mock('@/api/laborEfficiency', () => ({
  getLaborAchievementSummary: summary,
  getLaborStepBreakdown: stepBreakdown,
}));
vi.mock('@/api/request', () => ({ get: vi.fn(async () => ({ success: true, data: [] })) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('echarts', () => ({ init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }) }));

import { ElTable, ElTableColumn, ElTag } from 'element-plus';
import Dashboard from '../dashboard.vue';

const row = (id: string | null, name: string, rate: number | null, alert: string | null, batches: unknown[] = []) => ({
  productTypeId: id, productName: name, totalActualWorkMinutes: 100, totalPlannedWorkMinutes: 80,
  achievementRatePct: rate, achievementAlert: alert, batchCount: batches.length, batchItems: batches,
});

const box = { template: '<div><slot name="header"/><slot/></div>' };
const stubs = {
  ElCard: box, ElRow: box, ElCol: box, ElForm: box, ElFormItem: box, ElTable, ElTableColumn,
  ElAlert: box, ElTag, ElButton: box, ElDatePicker: box, ElSelect: box, ElOption: box, ElDrawer: box,
  ElDescriptions: box, ElDescriptionsItem: box, ElEmpty: box, ElIcon: box,
  DateWindowFallbackNotice: true,
};

describe('人效看板 一图一分析', () => {
  it('产品图、批次图的分析来自接口数据；未配置计划工时如实说', async () => {
    summary.mockResolvedValue({
      success: true,
      data: [
        row(null, '全部产品', 110, 'OK', [
          { batchNumber: 'B1', productName: 'x', actualWorkMinutes: 90, plannedWorkMinutes: 50, achievementRatePct: 180, achievementAlert: 'ABOVE_ALERT' },
          { batchNumber: 'B2', productName: 'x', actualWorkMinutes: 50, plannedWorkMinutes: 50, achievementRatePct: 100, achievementAlert: 'OK' },
        ]),
        row('1', '牛肉酱', 160, 'ABOVE_ALERT'),
        row('2', '猪蹄', 60, 'BELOW_ALERT'),
        row('3', '新品', null, null),
      ],
    });
    const w = mount(Dashboard, { global: { stubs, directives: { loading: () => undefined } } });
    await flushPromises();
    const p = w.get('[data-testid="labor-insight-product"]').text();
    expect(p).toContain('最高 160%（牛肉酱），最低 60%（猪蹄）');
    expect(p).toContain('1 个未配置计划工时');
    const b = w.get('[data-testid="labor-insight-batch"]').text();
    expect(b).toContain('全部产品：');
    expect(b).toContain('最高 180%（B1），最低 100%（B2）');
    w.unmount();
  });
});
