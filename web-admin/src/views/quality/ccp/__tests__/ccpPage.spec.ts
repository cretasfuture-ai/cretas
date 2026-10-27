import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * CCP 监控记录页 (缺口 13): 未处理偏离列表 → 处理 (纠偏措施必填) → 发出 handle 请求;
 * 路由带 batchNumber 时直接按批次查询。
 */
const state = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), push: vi.fn(), query: {} as Record<string, string> }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: state.query }), useRouter: () => ({ push: state.push }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F-CCP' }) }));
vi.mock('@/api/request', () => ({
  default: { get: vi.fn() },
  get: (...args: unknown[]) => state.get(...args),
  post: (...args: unknown[]) => state.post(...args),
}));
import Page from '../index.vue';

const ok = (data: unknown) => ({ success: true, data });
const openRow = {
  id: 91, checkpointId: 1, checkpointCode: 'CCP-T', checkpointName: '中心温度', batchNumber: 'CLK-W-9',
  processOrder: 2, workProcessId: 'WP', monitoringTime: '2026-10-26T09:00:00', measuredValue: 70,
  criticalLimitMin: 75, criticalLimitMax: 100, unit: '℃', result: 'DEVIATION_OPEN', operatorName: '操作员甲',
  operatorUserId: 5, source: 'WEB_PROCESS_SHEET', notes: null, deviationAction: null, deviationHandledAt: null,
  deviationHandledByName: null, correctiveActionGuide: '继续加热 5 分钟后复测',
};

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
  state.query = {};
  state.get.mockImplementation((url: string) => Promise.resolve(ok(url.endsWith('/deviations/open') ? [openRow] : [openRow])));
  state.post.mockResolvedValue(ok({ ...openRow, result: 'DEVIATION_HANDLED' }));
});

describe('CCP 监控记录页', () => {
  it('列出未处理偏离; 纠偏措施为空不能提交; 点规定措施后提交 handle', async () => {
    const wrapper = mount(Page, { global: { plugins: [ElementPlus] }, attachTo: document.body });
    await flushPromises();
    expect(state.get).toHaveBeenCalledWith('/F-CCP/ccp/deviations/open');
    expect(wrapper.text()).toContain('CLK-W-9');
    await wrapper.find('[data-testid="ccp-handle-91"]').trigger('click');
    await flushPromises();
    const submit = document.querySelector('[data-testid="ccp-handle-submit"]') as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    const preset = Array.from(document.querySelectorAll('.preset')).find((el) => el.textContent?.includes('按规定')) as HTMLElement;
    preset.click();
    await flushPromises();
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushPromises();
    expect(state.post).toHaveBeenCalledWith('/F-CCP/ccp/records/91/handle', { action: '继续加热 5 分钟后复测' });
    wrapper.unmount();
  });

  it('路由带 batchNumber → 切到按批次页签并查询', async () => {
    state.query = { batchNumber: 'CLK-B-1' };
    const wrapper = mount(Page, { global: { plugins: [ElementPlus] }, attachTo: document.body });
    await flushPromises();
    expect(state.get).toHaveBeenCalledWith('/F-CCP/ccp/records', { params: { batchNumber: 'CLK-B-1' } });
    wrapper.unmount();
  });
});
