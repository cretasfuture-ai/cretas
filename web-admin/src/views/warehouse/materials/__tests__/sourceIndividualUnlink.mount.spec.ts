import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus, { ElMessageBox } from 'element-plus';
import { nuxtPilotStubs } from '@/components/compat/testStubs';

vi.mock('@/components/compat/messages', async () => {
  const { ElMessageBox } = await import('element-plus');
  return { useCompatMessages: () => ({ ElMessageBox }) };
});

/**
 * #1844 终审 L-d: 批次编辑弹窗里的「解除来源个体挂载」—— 挂载真实 list.vue, 真点按钮。
 * 守: 只有挂了个体的批次才出现入口; 点了先问原因, 用户取消就一次请求都不发;
 * 填了原因走独立 unlink 端点 (不是编辑批次的 PUT), 成功后入口消失。
 */

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { view: 'BATCHES' }, fullPath: '/warehouse/materials' }),
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006', hasRole: () => false }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canAccess: () => true, canViewPrice: true }),
}));
vi.mock('@/api/manufacturer', () => ({
  listManufacturers: vi.fn().mockResolvedValue({ success: true, data: [] }),
}));
const getSourceIndividual = vi.fn();
vi.mock('@/api/sourceIndividual', () => ({
  getSourceIndividual: (...args: unknown[]) => getSourceIndividual(...args),
}));

const apiGet = vi.fn();
const apiPut = vi.fn();
const apiPost = vi.fn();
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
  put: (...args: unknown[]) => apiPut(...args),
}));

import ListVue from '../list.vue';

const LINKED_ROW = {
  id: 'MB-SI-1',
  batchNumber: 'MB-F006-20261119-0001',
  materialTypeId: 'MAT-BEEF',
  materialTypeName: '牛后腿',
  supplierId: 'SUP-1',
  receiptDate: '2026-11-19',
  receiptQuantity: 120,
  quantityUnit: 'kg',
  status: 'AVAILABLE',
  sourceIndividualId: 'SI-UUID-1',
};

function mockApiGet(rows: Record<string, unknown>[]) {
  apiGet.mockReset();
  apiGet.mockImplementation((url: string) => {
    if (url.includes('/material-batches')) {
      return Promise.resolve({ success: true, data: { content: rows, totalElements: rows.length } });
    }
    return Promise.resolve({ success: true, data: [] });
  });
}

function mountList() {
  return mount(ListVue, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        ...nuxtPilotStubs,
        teleport: true,
        transition: false,
        ElDialog: {
          props: ['modelValue', 'title'],
          template: '<div class="dialog-stub" :data-title="title" v-if="modelValue"><slot /><slot name="footer" /></div>',
        },
      },
    },
  });
}

async function openEdit(wrapper: ReturnType<typeof mountList>) {
  const editButtons = wrapper.findAll('button').filter((b) => b.text() === '编辑');
  expect(editButtons.length, '编辑按钮没渲染出来 —— 列表没加载出行').toBeGreaterThan(0);
  await editButtons[0].trigger('click');
  await flushPromises();
  await flushPromises();
}

beforeEach(() => {
  apiPost.mockReset();
  apiPut.mockReset();
  getSourceIndividual.mockReset();
  getSourceIndividual.mockResolvedValue({ success: true, data: { id: 'SI-UUID-1', individualCode: 'NB-0007' } });
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('批次编辑弹窗: 解除来源个体挂载', () => {
  it('挂了个体: 显示档案编号; 填原因后 POST 独立 unlink 端点, 成功后入口消失', async () => {
    mockApiGet([LINKED_ROW]);
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '  挂错了个体  ', action: 'confirm' } as never);
    apiPost.mockResolvedValue({ success: true, data: { ...LINKED_ROW, sourceIndividualId: null } });
    const wrapper = mountList();
    await flushPromises();
    await openEdit(wrapper);

    const block = wrapper.find('[data-testid="batch-source-individual"]');
    expect(block.exists(), '挂了个体的批次应出现「来源个体」一栏').toBe(true);
    expect(block.text()).toContain('档案 NB-0007');
    expect(getSourceIndividual).toHaveBeenCalledWith('F006', 'SI-UUID-1');

    await wrapper.find('[data-testid="batch-source-individual-unlink"]').trigger('click');
    await flushPromises();

    expect(prompt).toHaveBeenCalledTimes(1);
    expect(apiPost).toHaveBeenCalledTimes(1);
    expect(apiPost).toHaveBeenCalledWith(
      '/F006/material-batches/MB-SI-1/source-individual/unlink',
      { reason: '挂错了个体' },
    );
    // 不是走编辑批次的 PUT
    expect(apiPut).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="batch-source-individual"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('用户在原因框点取消: 一次请求都不发, 入口还在', async () => {
    mockApiGet([LINKED_ROW]);
    vi.spyOn(ElMessageBox, 'prompt').mockRejectedValue('cancel');
    const wrapper = mountList();
    await flushPromises();
    await openEdit(wrapper);

    await wrapper.find('[data-testid="batch-source-individual-unlink"]').trigger('click');
    await flushPromises();

    expect(apiPost).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="batch-source-individual"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it('阴性对照: 没挂个体的批次不出现解除入口', async () => {
    mockApiGet([{ ...LINKED_ROW, sourceIndividualId: null }]);
    const wrapper = mountList();
    await flushPromises();
    await openEdit(wrapper);

    expect(wrapper.find('.dialog-stub').exists(), '编辑弹窗没打开 —— 本条阴性对照无意义').toBe(true);
    expect(wrapper.find('[data-testid="batch-source-individual"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="batch-source-individual-unlink"]').exists()).toBe(false);
    wrapper.unmount();
  });
});
