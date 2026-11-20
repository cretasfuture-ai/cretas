/**
 * 产线人数配置 (2026-11-14 Steve「标准人数得详细配置」第 1 步):
 * 每条产线可配最少人数 / 最多人数 / 所需技能等级; 界面要说清楚用在插单、不够只提示不拦、不填不比较。
 * 不 mock element-plus: 弹窗 / 数字框 / 按钮禁用正是用户实际碰到的东西。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

const harness = vi.hoisted(() => ({ get: vi.fn(), setStaffing: vi.fn(), getLines: vi.fn() }));

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));
vi.mock('@/api/request', () => ({ get: harness.get }));
vi.mock('@/api/scheduling', () => ({
  getProductionLines: harness.getLines,
  createProductionLine: vi.fn(),
  getLineCapacityConfigs: vi.fn().mockResolvedValue({ success: true, data: [] }),
  setLineCapacity: vi.fn(),
  deleteLineCapacity: vi.fn(),
  getProductEquipmentRequirements: vi.fn().mockResolvedValue({ success: true, data: [] }),
  setProductEquipment: vi.fn(),
  setProductionLineStaffing: harness.setStaffing,
}));

import LineCapacity from '../index.vue';

const LINES = [
  { id: 'L1', name: 'QA-卤制一线', status: 'active', standardWorkers: null, maxWorkers: 10, requiredSkillLevel: 1 },
  { id: 'L2', name: 'QA-卤制二线', status: 'active', standardWorkers: 4, maxWorkers: 8, requiredSkillLevel: 3 },
];

async function mountPage() {
  harness.getLines.mockResolvedValue({ success: true, data: LINES });
  harness.get.mockImplementation((url: string) =>
    Promise.resolve({ success: true, data: url.includes('equipment') ? { content: [] } : [] }));
  const wrapper = mount(LineCapacity, { attachTo: document.body, global: { plugins: [ElementPlus] } });
  await flushPromises();
  return wrapper;
}

const dialog = () => document.querySelector('[aria-label^="人数配置"]') as HTMLElement;
const saveButton = () =>
  Array.from(dialog().querySelectorAll('button')).find(b => b.textContent?.includes('保存')) as HTMLButtonElement;

async function openFor(wrapper: ReturnType<typeof mount>, lineName: string) {
  const row = wrapper.findAll('tr').find(r => r.text().includes(lineName))!;
  await row.findAll('button').find(b => b.text().includes('人数配置'))!.trigger('click');
  await flushPromises();
}

async function typeNumber(label: string, value: string) {
  const item = Array.from(dialog().querySelectorAll('.el-form-item'))
    .find(i => i.querySelector('.el-form-item__label')?.textContent?.includes(label))!;
  const input = item.querySelector('input') as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  input.dispatchEvent(new Event('change'));
  input.dispatchEvent(new Event('blur'));
  await flushPromises();
}

describe('产线人数配置', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    harness.setStaffing.mockReset();
  });

  it('表格里说清每条线配了什么; 没填最少人数写明插单不比较', async () => {
    const wrapper = await mountPage();
    const rows = wrapper.findAll('tr');
    expect(rows.find(r => r.text().includes('QA-卤制一线'))!.text()).toContain('插单最少人数未填（不比较）');
    const configured = rows.find(r => r.text().includes('QA-卤制二线'))!.text();
    expect(configured).toContain('插单最少 4 人');
    expect(configured).toContain('最多 8 人');
    expect(configured).toContain('技能 3 级');
    wrapper.unmount();
  });

  it('弹窗说明用在插单、只提示不拦、不填不比较, 并带出这条线现有的值', async () => {
    const wrapper = await mountPage();
    await openFor(wrapper, 'QA-卤制二线');
    const text = dialog().textContent ?? '';
    expect(text).toContain('QA-卤制二线');
    expect(text).toContain('紧急插单');
    expect(text).toContain('不会拦住');
    expect(text).toContain('不填');
    expect(text).toContain('现在只保存，插单不用');
    const inputs = Array.from(dialog().querySelectorAll('.el-input-number input')) as HTMLInputElement[];
    expect(inputs.map(i => i.value)).toEqual(['4', '8']);
    wrapper.unmount();
  });

  it('最少多于最多: 当场说明并禁用保存, 不发请求; 改对了才能保存', async () => {
    const wrapper = await mountPage();
    await openFor(wrapper, 'QA-卤制二线');
    await typeNumber('最少人数', '9');
    expect(dialog().textContent).toContain('最少人数 9 人不能多于最多人数 8 人');
    expect(saveButton().disabled).toBe(true);
    saveButton().click();
    await flushPromises();
    expect(harness.setStaffing).not.toHaveBeenCalled();

    await typeNumber('最少人数', '8');
    expect(saveButton().disabled).toBe(false);
    wrapper.unmount();
  });

  it('保存送出三个值; 清空的项送 null (= 不比较)', async () => {
    harness.setStaffing.mockResolvedValue({
      success: true,
      data: { ...LINES[0], standardWorkers: 5, maxWorkers: null, requiredSkillLevel: 1 },
    });
    const wrapper = await mountPage();
    await openFor(wrapper, 'QA-卤制一线');
    await typeNumber('最少人数', '5');
    await typeNumber('最多人数', '');
    saveButton().click();
    await flushPromises();
    expect(harness.setStaffing).toHaveBeenCalledWith('F006', 'L1', {
      insertMinWorkers: 5,
      maxWorkers: null,
      requiredSkillLevel: 1,
    });
    wrapper.unmount();
  });
});
