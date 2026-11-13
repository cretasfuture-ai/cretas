/**
 * 产线产能页回归网 (2026-11-13 验收发现两个问题):
 * 1. 「本厂单位」提示直接露出英文配置 key (production.capacity.package_unit_mode)。取值 FACTORY_UNIT 是用户要填的值, 保留; key 不许露。
 * 2. 「设定产能」弹窗里的下拉点一下就关, 选不了。
 * 不 mock element-plus: 弹窗/下拉的挂载与点击行为正是缺陷发生的地方。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

const harness = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));
vi.mock('@/api/request', () => ({ get: harness.get }));
vi.mock('@/api/scheduling', () => ({
  getProductionLines: vi.fn().mockResolvedValue({
    success: true,
    data: [{ id: 'L1', name: 'QA-卤制一线' }, { id: 'L2', name: 'QA-卤制二线' }],
  }),
  createProductionLine: vi.fn(),
  getLineCapacityConfigs: vi.fn().mockResolvedValue({ success: true, data: [] }),
  setLineCapacity: vi.fn(),
  deleteLineCapacity: vi.fn(),
  getProductEquipmentRequirements: vi.fn().mockResolvedValue({ success: true, data: [] }),
  setProductEquipment: vi.fn(),
}));

import LineCapacity from '../index.vue';

async function mountPage() {
  harness.get.mockImplementation((url: string) =>
    Promise.resolve({
      success: true,
      data: url.includes('equipment') ? { content: [] } : [{ id: 'P1', name: 'QA-猪蹄' }],
    }));
  const wrapper = mount(LineCapacity, { attachTo: document.body, global: { plugins: [ElementPlus] } });
  await flushPromises();
  return wrapper;
}

async function openDialog(wrapper: ReturnType<typeof mount>) {
  const btn = wrapper.findAll('button').find(b => b.text().includes('设定产能'))!;
  await btn.trigger('click');
  await flushPromises();
}

const dialogVisible = () => {
  const overlay = document.querySelector('.el-overlay:has([aria-label^="设定产能"])') as HTMLElement | null;
  return !!overlay && overlay.style.display !== 'none';
};

describe('产线产能 — 设定产能弹窗', () => {
  beforeEach(() => { document.body.innerHTML = ''; });

  it('选「本厂单位」后的提示是中文, 不露出英文配置 key', async () => {
    const wrapper = await mountPage();
    await openDialog(wrapper);
    await flushPromises();
    const radios = Array.from(document.querySelectorAll('.el-radio-button')) as HTMLElement[];
    radios.find(r => r.textContent?.includes('本厂单位'))!.querySelector('input')!.click();
    await flushPromises();
    const hint = document.querySelector('.unit-hint')?.textContent ?? '';
    expect(hint).not.toMatch(/package_unit_mode|production\.capacity/);
    expect(hint).toContain('业务阈值');
    wrapper.unmount();
  });

  it('在弹窗里点开下拉并选中一项: 弹窗不关, 值写进去了', async () => {
    const wrapper = await mountPage();
    await openDialog(wrapper);
    await flushPromises();
    expect(dialogVisible()).toBe(true);

    const select = document.querySelector('[aria-label^="设定产能"] .el-select .el-select__wrapper') as HTMLElement;
    select.click();
    await flushPromises();
    const option = Array.from(document.querySelectorAll('.el-select-dropdown__item'))
      .find(o => o.textContent?.includes('QA-卤制二线')) as HTMLElement;
    expect(option).toBeTruthy();
    // 真实鼠标点击 = mousedown + mouseup + click, 落在下拉项上
    for (const t of ['mousedown', 'mouseup', 'click']) {
      option.dispatchEvent(new MouseEvent(t, { bubbles: true }));
    }
    await flushPromises();
    expect(dialogVisible()).toBe(true);
    expect(document.querySelector('.el-dialog__title')?.textContent).toContain('QA-卤制二线');
    wrapper.unmount();
  });
});
