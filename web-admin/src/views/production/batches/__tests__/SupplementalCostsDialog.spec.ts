import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, shallowMount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import Dialog from '../SupplementalCostsDialog.vue';
import { put } from '@/api/request';
import { recalculateSettledPlanCosts } from '@/api/productionPlan';

vi.mock('@/api/request', () => ({ put: vi.fn() }));
vi.mock('@/api/productionPlan', () => ({ recalculateSettledPlanCosts: vi.fn() }));

function openDialog(overrides: Record<string, unknown> = {}) {
  return shallowMount(Dialog, {
    props: {
      modelValue: true,
      factoryId: 'F-TEST',
      productionPlanId: 'PLAN-1',
      equipmentCost: null,
      otherCost: null,
      ...overrides,
    },
    global: {
      stubs: {
        'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
        'el-form': { template: '<form><slot /></form>' },
        'el-form-item': { template: '<div><slot /></div>' },
        'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" />' },
        'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
        'el-alert': { template: '<div><slot /></div>' },
      },
    },
  });
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(put).mockResolvedValue({ success: true, data: {} });
  vi.mocked(recalculateSettledPlanCosts).mockResolvedValue({ success: true, data: {} });
});

describe('SupplementalCostsDialog', () => {
  it('uses the existing atomic settled-cost response without sending a second recalculation', async () => {
    vi.mocked(put).mockResolvedValue({ success: true, data: { settledCostsRecalculated: true } });
    const wrapper = openDialog({ equipmentCost: 22, otherCost: 3 });
    await (wrapper.vm as unknown as { submit: () => Promise<void> }).submit();
    expect(put).toHaveBeenCalledTimes(1);
    expect(recalculateSettledPlanCosts).not.toHaveBeenCalled();
    expect(wrapper.emitted('saved')).toHaveLength(1);
    wrapper.unmount();
  });
  it('shows the current batch and prevents editing or cancelling while the real form is saving', async () => {
    let finish!: (value: { success: boolean; data: object }) => void;
    vi.mocked(put).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const wrapper = mount(Dialog, { props: { modelValue: true, factoryId: 'F-TEST', productionPlanId: 'PLAN-1', batchNumber: '盒装体验批次', equipmentCost: 12, otherCost: 3 },
      global: { plugins: [ElementPlus], stubs: { 'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' } } } });
    await flushPromises();
    expect(wrapper.text()).toContain('当前批次：盒装体验批次');
    expect(wrapper.findAll('input').map(input => input.element.value)).toEqual(['12', '3']);
    await wrapper.findAll('button').find(button => button.text() === '保存并重新核算')!.trigger('click');
    expect(wrapper.findAll('input').every(input => input.element.disabled)).toBe(true);
    expect(wrapper.findAll('button').find(button => button.text() === '取消')!.attributes('disabled')).toBeDefined();
    finish({ success: true, data: {} }); await flushPromises();
    expect(wrapper.emitted('saved')).toHaveLength(1);
    expect(wrapper.emitted('costs-updated')).toBeUndefined();
    wrapper.unmount();
  });

  it('does not submit twice while a save is pending', async () => {
    let finish!: (value: { success: boolean; data: object }) => void;
    vi.mocked(put).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const wrapper = openDialog({ equipmentCost: 12 });
    const vm = wrapper.vm as unknown as { submit: () => Promise<void> };
    const first = vm.submit(); const second = vm.submit();
    expect(put).toHaveBeenCalledTimes(1);
    finish({ success: true, data: {} }); await Promise.all([first, second]);
    expect(recalculateSettledPlanCosts).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('does not recalculate a different plan after the original save completes', async () => {
    let finish!: (value: { success: boolean; data: object }) => void;
    vi.mocked(put).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const wrapper = openDialog({ equipmentCost: 12 });
    const pending = (wrapper.vm as unknown as { submit: () => Promise<void> }).submit();
    await wrapper.setProps({ factoryId: 'OTHER', productionPlanId: 'PLAN-2' });
    finish({ success: true, data: {} }); await pending;
    expect(recalculateSettledPlanCosts).not.toHaveBeenCalled();
    expect(wrapper.emitted('saved')).toBeUndefined();
    wrapper.unmount();
  });

  it('saves changed amounts again instead of silently discarding edits after a recalculation failure', async () => {
    const wrapper = openDialog({ equipmentCost: 12 });
    const vm = wrapper.vm as unknown as { equipmentInput: string; submit: () => Promise<void> };
    vi.mocked(recalculateSettledPlanCosts).mockRejectedValueOnce(new Error('服务不可用'));
    await vm.submit(); vm.equipmentInput = '15'; await vm.submit();
    expect(put).toHaveBeenCalledTimes(2);
    expect(put).toHaveBeenLastCalledWith('/F-TEST/production-plans/PLAN-1/costs', undefined, { params: { equipmentCost: 15 } });
    expect(wrapper.emitted('saved')).toHaveLength(1);
    wrapper.unmount();
  });

  it.each(['actor', 'close', 'unmount'])('does not continue an old save after %s changes', async kind => {
    let finish!: (value: { success: boolean; data: object }) => void;
    vi.mocked(put).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const wrapper = openDialog({ equipmentCost: 12, contextKey: 'USER-2' });
    const pending = (wrapper.vm as unknown as { submit: () => Promise<void> }).submit();
    if (kind === 'actor') await wrapper.setProps({ contextKey: 'USER-5' });
    if (kind === 'close') await wrapper.setProps({ modelValue: false });
    if (kind === 'unmount') wrapper.unmount();
    finish({ success: true, data: {} }); await pending;
    expect(recalculateSettledPlanCosts).not.toHaveBeenCalled();
    expect(wrapper.emitted('saved')).toBeUndefined();
    expect(wrapper.emitted('costs-updated')).toBeUndefined();
    if (kind !== 'unmount') wrapper.unmount();
  });

  it('does not publish an old recalculation success after the actor changes', async () => {
    let finish!: (value: { success: boolean; data: object }) => void;
    vi.mocked(recalculateSettledPlanCosts).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const wrapper = openDialog({ equipmentCost: 12, contextKey: 'USER-2' });
    const pending = (wrapper.vm as unknown as { submit: () => Promise<void> }).submit();
    await flushPromises(); await wrapper.setProps({ contextKey: 'USER-5' });
    finish({ success: true, data: {} }); await pending;
    expect(wrapper.emitted('saved')).toBeUndefined();
    wrapper.unmount();
  });

  it('refreshes the parent on leaving a saved-but-not-recalculated cost instead of retaining old input defaults', async () => {
    const wrapper = openDialog({ equipmentCost: 12 });
    vi.mocked(recalculateSettledPlanCosts).mockResolvedValueOnce({ success: false, message: '结单尚未入库' });
    await (wrapper.vm as unknown as { submit: () => Promise<void> }).submit();
    await flushPromises();
    expect(wrapper.emitted('saved')).toBeUndefined();
    await wrapper.findAll('button').find(b => b.text() === '稍后核算')!.trigger('click');
    expect(wrapper.emitted('costs-updated')).toHaveLength(1);
    expect(wrapper.emitted('update:modelValue')).toContainEqual([false]);
    wrapper.unmount();
  });
  it('keeps unknown values blank and preserves an explicit zero', async () => {
    const wrapper = openDialog({ equipmentCost: 0, otherCost: null });
    await flushPromises();
    const vm = wrapper.vm as unknown as { equipmentInput: string; otherInput: string };

    expect(vm.equipmentInput).toBe('0');
    expect(vm.otherInput).toBe('');
    wrapper.unmount();
  });

  it('sends only entered non-negative costs and emits after success', async () => {
    const wrapper = openDialog();
    const vm = wrapper.vm as unknown as { equipmentInput: string; otherInput: string; submit: () => Promise<void> };
    vm.equipmentInput = '0';
    vm.otherInput = '';

    await vm.submit();
    await flushPromises();

    expect(put).toHaveBeenCalledWith(
      '/F-TEST/production-plans/PLAN-1/costs',
      undefined,
      { params: { equipmentCost: 0 } },
    );
    expect(recalculateSettledPlanCosts).toHaveBeenCalledWith('F-TEST', 'PLAN-1');
    expect(wrapper.emitted('saved')).toHaveLength(1);
    expect(wrapper.emitted('update:modelValue')).toContainEqual([false]);
    wrapper.unmount();
  });

  it('requires one field and keeps the dialog open on a real backend error', async () => {
    const wrapper = openDialog();
    const vm = wrapper.vm as unknown as { equipmentInput: string; otherInput: string; errorMessage: string; submit: () => Promise<void> };

    await vm.submit();
    expect(put).not.toHaveBeenCalled();
    expect(vm.errorMessage).toContain('至少填写');

    vi.mocked(put).mockResolvedValue({ success: false, message: '该计划存在多个正式批次' });
    vm.otherInput = '12.50';
    await vm.submit();
    await flushPromises();

    expect(vm.errorMessage).toBe('该计划存在多个正式批次');
    expect(wrapper.emitted('saved')).toBeUndefined();
    expect(wrapper.emitted('update:modelValue')).toBeUndefined();
    wrapper.unmount();
  });

  it('keeps the dialog open and allows retry when saving succeeds but recalculation fails', async () => {
    const wrapper = openDialog();
    const vm = wrapper.vm as unknown as { equipmentInput: string; errorMessage: string; submit: () => Promise<void> };
    vm.equipmentInput = '12.50';
    vi.mocked(recalculateSettledPlanCosts).mockRejectedValueOnce(new Error('结单成本服务不可用'));

    await vm.submit();
    await flushPromises();

    expect(put).toHaveBeenCalledTimes(1);
    expect(recalculateSettledPlanCosts).toHaveBeenCalledTimes(1);
    expect(vm.errorMessage).toBe('费用已保存，重新核算失败：结单成本服务不可用');
    expect(wrapper.emitted('saved')).toBeUndefined();
    expect(wrapper.emitted('update:modelValue')).toBeUndefined();

    await vm.submit();
    await flushPromises();

    expect(put).toHaveBeenCalledTimes(1);
    expect(recalculateSettledPlanCosts).toHaveBeenCalledTimes(2);
    expect(wrapper.emitted('saved')).toHaveLength(1);
    expect(wrapper.emitted('update:modelValue')).toContainEqual([false]);
    wrapper.unmount();
  });
});
