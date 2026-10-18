import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import ElementPlus, { ElInputNumber, ElSelect, ElMessage } from 'element-plus';

const state = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), push: vi.fn(), canWrite: true }));
const route = reactive<{ query: Record<string, string> }>({ query: {} });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: state.push }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'VISION-ENTRY-A' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => state.canWrite }) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => state.get(...args), post: (...args: unknown[]) => state.post(...args) }));
vi.mock('@/composables/useListSummary', () => ({ useListSummary: () => ({ summary: null, loading: false }) }));
vi.mock('@/composables/useCreateAndReturn', () => ({ useCreateAndReturn: () => ({ goCreate: vi.fn() }) }));
import Page from '../list.vue';

const batch = { id: 8, batchNumber: 'BOX-8', productName: '盒装产品' };
const ok = (data: unknown) => ({ success: true, data });
function mountPage() {
  return mount(Page, { global: { plugins: [ElementPlus], stubs: {
    CanvasAwareWrapper: { template: '<div><slot /></div>' }, CanvasDynamicFields: true,
    TableFooter: true, UpstreamMissingHint: true,
  } } });
}
async function clickText(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find(item => item.text() === text);
  expect(button, text).toBeTruthy();
  await button!.trigger('click');
  await flushPromises();
}
beforeEach(() => {
  vi.clearAllMocks();
  state.canWrite = true;
  route.query = { batchId: '8', factoryId: 'VISION-ENTRY-B', batchNumber: 'FORGED-NUMBER' };
  state.get.mockImplementation((url: string) => Promise.resolve(ok(url.endsWith('/batches/8') ? batch : { content: [], totalElements: 0 })));
  state.post.mockResolvedValue(ok({ inspection: { id: 'new-qc' } }));
});
describe('追溯进入既有质检页面', () => {
  it('固定抽样5份，连续修改合格数量时只平衡不合格数', async () => {
    const wrapper = mountPage(); await flushPromises(); await clickText(wrapper, '新建质检');
    const inputs = wrapper.findAllComponents(ElInputNumber);
    const change = async (index: number, value: number | null) => {
      inputs[index].vm.$emit('update:modelValue', value); inputs[index].vm.$emit('change', value);
      await flushPromises();
    };
    await change(0, 5);
    for (let value = 1; value <= 4; value++) await change(1, value);
    expect(inputs.map(input => input.props('modelValue'))).toEqual([5, 4, 1]);
    await change(2, 2);
    expect(inputs.map(input => input.props('modelValue'))).toEqual([5, 3, 2]);
    const selects = wrapper.findAllComponents(ElSelect);
    selects.at(-1)!.vm.$emit('update:modelValue', 'FAIL'); await clickText(wrapper, '确定');
    expect(state.post).toHaveBeenCalledWith('/VISION-ENTRY-A/processing/quality/inspections?batchId=8',
      expect.objectContaining({ sampleSize: 5, passCount: 3, failCount: 2, result: 'FAIL' }));
    wrapper.unmount();
  });
  it('先填合格与不合格可计算抽样；清空抽样不篡改原结果，超量不能提交', async () => {
    const wrapper = mountPage(); await flushPromises(); await clickText(wrapper, '新建质检');
    const inputs = wrapper.findAllComponents(ElInputNumber);
    const change = async (index: number, value: number | null) => {
      inputs[index].vm.$emit('update:modelValue', value); inputs[index].vm.$emit('change', value);
      await flushPromises();
    };
    await change(1, 4); await change(2, 1);
    expect(inputs.map(input => input.props('modelValue'))).toEqual([5, 4, 1]);
    await change(0, null);
    expect(inputs.map(input => input.props('modelValue'))).toEqual([null, 4, 1]);
    await change(0, 3);
    expect(inputs.map(input => input.props('modelValue'))).toEqual([3, 4, 0]);
    expect(wrapper.text()).toContain('请调整'); await clickText(wrapper, '确定');
    expect(state.post).not.toHaveBeenCalled(); wrapper.unmount();
  });
  it('不合格行直接进入原异常复核；正常行不展示复核动作', async () => {
    state.get.mockImplementation((url: string) => Promise.resolve(ok(url.endsWith('/batches/8') ? batch : {
      content: [
        { id: 'failed-qc', productionBatchId: 8, result: 'FAIL', batchNumber: 'BOX-8' },
        { id: 'passed-qc', productionBatchId: 8, result: 'PASS', batchNumber: 'BOX-8' },
      ], totalElements: 2,
    })));
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.findAll('button').filter(button => button.text() === '异常复核')).toHaveLength(1);
    await clickText(wrapper, '异常复核');
    expect(state.push).toHaveBeenCalledWith({ path: '/quality/disposition', query: { batchId: '8', inspectionId: 'failed-qc' } });
    wrapper.unmount();
  });
  it('缓存页面切换批次清除旧结果筛选，补录后不会被此前FAIL筛选隐藏', async () => {
    state.get.mockImplementation((url: string) => Promise.resolve(ok(url.endsWith('/batches/8') ? batch : url.endsWith('/batches/9') ? { ...batch, id: 9, batchNumber: 'BOX-9' } : { content: [], totalElements: 0 })));
    const wrapper = mountPage();
    await flushPromises();
    wrapper.findAllComponents(ElSelect)[0].vm.$emit('update:modelValue', 'FAIL');
    await clickText(wrapper, '搜索');
    expect(state.get.mock.calls.at(-1)![1].params.result).toBe('FAIL');
    route.query = { batchId: '9' };
    await flushPromises();
    expect(wrapper.text()).toContain('BOX-9');
    expect(state.get.mock.calls.at(-1)![1].params).toMatchObject({ batchId: '9', result: undefined });
    wrapper.unmount();
  });
  it('当前厂取真实批次并过滤，返回使用服务端批号，表单锁定本批次并提交原API', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.text()).toContain('BOX-8');
    expect(wrapper.text()).not.toContain('FORGED-NUMBER');
    expect(state.get).toHaveBeenCalledWith('/VISION-ENTRY-A/processing/batches/8');
    expect(state.get).toHaveBeenCalledWith('/VISION-ENTRY-A/processing/quality/inspections', expect.objectContaining({ params: expect.objectContaining({ batchId: '8' }) }));
    await clickText(wrapper, '返回批次追溯');
    expect(state.push).toHaveBeenCalledWith({ path: '/production/traceability', query: { batchNumber: 'BOX-8' } });
    await clickText(wrapper, '新建质检');
    const inputs = wrapper.findAllComponents(ElInputNumber);
    inputs[0].vm.$emit('update:modelValue', 5);
    inputs[1].vm.$emit('update:modelValue', 5);
    inputs[2].vm.$emit('update:modelValue', 0);
    const selects = wrapper.findAllComponents(ElSelect);
    expect(selects[selects.length - 2].props('disabled')).toBe(true);
    selects[selects.length - 1].vm.$emit('update:modelValue', 'PASS');
    await clickText(wrapper, '确定');
    expect(state.post).toHaveBeenCalledWith('/VISION-ENTRY-A/processing/quality/inspections?batchId=8', expect.objectContaining({ sampleSize: 5, passCount: 5, failCount: 0, result: 'PASS', customFields: {} }));
    expect(state.post.mock.calls[0][1]).not.toHaveProperty('inspectorId');
    wrapper.unmount();
  });
  it('无权写入仍可查看，隐藏新建；批次读取失败不能落成全厂列表', async () => {
    state.canWrite = false;
    state.get.mockRejectedValue(new Error('当前工厂未找到该批次'));
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.text()).toContain('当前工厂未找到该批次');
    expect(wrapper.text()).not.toContain('新建质检');
    expect(state.get).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });
  it('提交失败保留原表单并显示业务原因', async () => {
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() }));
    state.post.mockRejectedValue(new Error('抽样数与结果不一致'));
    const wrapper = mountPage();
    await flushPromises();
    await clickText(wrapper, '新建质检');
    wrapper.findAllComponents(ElInputNumber).forEach((input, i) => input.vm.$emit('update:modelValue', [5, 5, 0][i]));
    wrapper.findAllComponents(ElSelect).at(-1)!.vm.$emit('update:modelValue', 'PASS');
    await clickText(wrapper, '确定');
    expect(error).toHaveBeenCalledWith('抽样数与结果不一致');
    expect(wrapper.findAllComponents(ElInputNumber)[0].props('modelValue')).toBe(5);
    wrapper.unmount();
    error.mockRestore();
  });
});
