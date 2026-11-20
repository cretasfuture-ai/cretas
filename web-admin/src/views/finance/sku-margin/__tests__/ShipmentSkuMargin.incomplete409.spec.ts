import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios';
import { reactive } from 'vue';

const el = vi.hoisted(() => ({ message: vi.fn(), notification: vi.fn() }));
vi.mock('element-plus', () => ({
  ElMessage: el.message,
  ElMessageBox: { alert: vi.fn(), confirm: vi.fn() },
  ElNotification: el.notification,
}));
const testRoute = reactive({ query: {} as Record<string, unknown> });
vi.mock('vue-router', () => ({ useRoute: () => testRoute }));

import request from '@/api/request';
import Panel from '../ShipmentSkuMargin.vue';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';

// 真 API 函数 + 真拦截器, 只替换传输层: 守的是「该 409 不弹全局红窗」这个真实行为。
const originalAdapter = request.defaults.adapter;
function reject(status: number, errorCode: string, message: string) {
  request.defaults.adapter = async (config) => {
    const response: AxiosResponse = {
      status, statusText: 'x', headers: new AxiosHeaders(), config,
      data: { success: false, code: status, errorCode, message, severity: 'warning' },
    };
    throw new AxiosError('failed', 'ERR_BAD_REQUEST', config, undefined, response);
  };
}
function page() {
  return mount(Panel, { props: { factoryId: 'F006', period: ['2026-10-01', '2026-10-31'], refreshKey: 0 }, global: { stubs: nuxtUiStubs } });
}
const settle = () => new Promise(resolve => setTimeout(resolve, 500));

describe('SKU 毛利页：出货扣库不完整 409', () => {
  beforeEach(() => { vi.clearAllMocks(); el.notification.mockReturnValue({ close: vi.fn() }); });
  afterEach(() => { request.defaults.adapter = originalAdapter; });

  it('SHIPMENT_COST_SOURCE_INCOMPLETE 在页内显示说明与补齐入口, 不弹全局提示, 不显示数字', async () => {
    reject(409, 'SHIPMENT_COST_SOURCE_INCOMPLETE', '出货批次扣库记录或单位不完整，本次未结转');
    const wrapper = page(); await flushPromises(); await settle();
    const box = wrapper.get('[data-testid="shipment-margin-cost-incomplete"]');
    expect(box.text()).toContain('缺扣库批次或单位，暂不能计算 SKU 毛利');
    expect(wrapper.get('[data-testid="shipment-margin-fix-link"]').attributes('href')).toBe('/warehouse/shipments');
    expect(wrapper.find('[data-testid="shipment-margin-table"]').exists()).toBe(false);
    expect(wrapper.find('dl.totals').exists()).toBe(false);
    expect(el.notification).not.toHaveBeenCalled();
    expect(el.message).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('阴性对照: 其它 409 错误码仍走全局提示, 页内不显示该说明', async () => {
    reject(409, 'SOME_OTHER_CONFLICT', '别的冲突');
    const wrapper = page(); await flushPromises();
    for (let i = 0; i < 400 && !el.notification.mock.calls.length && !el.message.mock.calls.length; i++) await new Promise(r => setTimeout(r, 5));
    expect(el.notification.mock.calls.length + el.message.mock.calls.length).toBeGreaterThan(0);
    expect(wrapper.find('[data-testid="shipment-margin-cost-incomplete"]').exists()).toBe(false);
    expect(wrapper.find('[data-type="error"]').exists()).toBe(true);
    wrapper.unmount();
  });
});
