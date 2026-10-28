import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DynamicQualityInspectionForm from '../DynamicQualityInspectionForm.vue';
import type { DynamicQualityForm } from '../dynamicQualityInspection';
import { ApiError } from '@/types/api';

const state = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => state.get(...args), post: (...args: unknown[]) => state.post(...args) }));

const form: DynamicQualityForm = {
  batchId: '11003',
  productTypeId: 'SKU-QA',
  productName: 'QA原料成品',
  standardRevision: 'rev-7',
  items: [{
    bindingId: 'binding-temp',
    itemId: 'item-temp',
    itemVersion: 3,
    label: '温度',
    valueType: 'RANGE',
    unit: '°C',
    effectiveMinValue: 0,
    effectiveMaxValue: 4,
    effectiveIsRequired: true,
  }],
};

const ok = (data: unknown) => ({ success: true, data });

function mountForm() {
  return mount(DynamicQualityInspectionForm, {
    props: { factoryId: 'F006', batchId: '11003', batchNumber: '11003', productName: 'QA原料成品', form },
    global: { plugins: [ElementPlus] },
  });
}

async function clickSubmit(wrapper: ReturnType<typeof mountForm>) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().includes('提交质检结果'));
  expect(button).toBeTruthy();
  await button!.trigger('click');
  await flushPromises();
}

beforeEach(() => {
  vi.clearAllMocks();
  state.get.mockResolvedValue(ok(form));
  state.post.mockResolvedValue(ok({ inspection: { id: 'qc-11003', result: 'PASS' }, standardRevision: 'rev-7' }));
});

describe('动态质检表单', () => {
  it('必检项目为空时阻止提交并明确提示', async () => {
    const wrapper = mountForm();
    await clickSubmit(wrapper);
    expect(state.post).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请填写必检项目：温度');
  });

  it('提交携带批次、revision、绑定身份和值', async () => {
    const wrapper = mountForm();
    expect(wrapper.text()).toContain('本次记录 1 份样本');
    await wrapper.find('input').setValue('2.5');
    await clickSubmit(wrapper);
    expect(state.post).toHaveBeenCalledWith(
      '/F006/processing/batches/11003/quality-inspections',
      expect.objectContaining({
        standardRevision: 'rev-7',
        sampleSize: 1,
        answers: [{ bindingId: 'binding-temp', itemId: 'item-temp', itemVersion: 3, value: '2.5' }],
      }),
    );
    expect(wrapper.emitted('submitted')).toHaveLength(1);
  });

  it('范围外实测值允许提交并由服务端记录为不合格', async () => {
    const wrapper = mountForm();
    await wrapper.find('input').setValue('5');
    expect(wrapper.text()).toContain('提交后将记录为不合格');
    await clickSubmit(wrapper);
    expect(state.post).toHaveBeenCalledTimes(1);
  });

  it('标准版本过期时保留答案并提供刷新标准动作', async () => {
    state.post.mockRejectedValue(new ApiError('质检项目已更新，请刷新标准', 'QUALITY_STANDARD_STALE', 409));
    const wrapper = mountForm();
    await wrapper.find('input').setValue('2');
    await clickSubmit(wrapper);
    expect(wrapper.text()).toContain('刷新标准（保留已填写答案）');
    state.get.mockResolvedValue(ok({ ...form, standardRevision: 'rev-8' }));
    const refresh = wrapper.findAll('button').find((candidate) => candidate.text().includes('刷新标准'));
    expect(refresh).toBeTruthy();
    await refresh!.trigger('click');
    await flushPromises();
    expect(state.get).toHaveBeenCalledWith('/F006/processing/batches/11003/quality-form');
    expect(wrapper.text()).toContain('当前产品质检标准');
    expect(wrapper.text()).not.toContain('rev-8');
    expect(wrapper.find('input').element.value).toBe('2');
    state.post.mockResolvedValue(ok({ inspection: { id: 'qc-11003', result: 'PASS' }, standardRevision: 'rev-8' }));
    await clickSubmit(wrapper);
    expect(state.post).toHaveBeenLastCalledWith(
      '/F006/processing/batches/11003/quality-inspections',
      expect.objectContaining({
        standardRevision: 'rev-8',
        answers: [{ bindingId: 'binding-temp', itemId: 'item-temp', itemVersion: 3, value: '2' }],
      }),
    );
  });
});
