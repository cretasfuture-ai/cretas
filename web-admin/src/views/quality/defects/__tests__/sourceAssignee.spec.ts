import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus, { ElSelect, ElMessage } from 'element-plus';
import { reactive } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  push: vi.fn(),
}));
const route = reactive<{ query: Record<string, string> }>({ query: {} });
const authState = reactive<{ factoryId: string }>({ factoryId: 'F006' });

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push: (...args: unknown[]) => state.push(...args) }),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));
vi.mock('@/composables/useListSummary', () => ({ useListSummary: () => ({ summary: null, loading: false }) }));
vi.mock('@/composables/useCreateAndReturn', () => ({ useCreateAndReturn: () => ({ goCreate: vi.fn() }) }));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => state.get(...args),
  post: (...args: unknown[]) => state.post(...args),
  put: (...args: unknown[]) => state.put(...args),
}));

import DefectsPage from '../list.vue';
import InspectionsPage from '../../inspections/list.vue';

const ok = (data: unknown) => ({ success: true, data });
const source = {
  id: 'inspection-f006',
  batchNumber: 'B-F006-01',
  productName: '冷冻鱼排',
  inspectionDate: '2026-10-23',
  result: 'FAIL',
};

function mountPage() {
  return mount(DefectsPage, { global: { plugins: [ElementPlus] } });
}

function mountInspectionsPage() {
  return mount(InspectionsPage, { global: { plugins: [ElementPlus], stubs: {
    CanvasAwareWrapper: { template: '<div><slot /></div>' },
    CanvasDynamicFields: true,
    TableFooter: true,
    UpstreamMissingHint: true,
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
  route.query = {};
  authState.factoryId = 'F006';
  state.get.mockImplementation((url: string) => {
    if (url.endsWith('/quality-defects')) {
      return Promise.resolve(ok({ content: [], totalElements: 0 }));
    }
    if (url.endsWith('/processing/quality/inspections')) {
      return Promise.resolve(ok({ content: [source], totalElements: 1 }));
    }
    if (url.endsWith('/processing/quality/inspections/inspection-f006')) {
      return Promise.resolve(ok(source));
    }
    if (url.endsWith('/users/directory')) {
      return Promise.resolve(ok([
        { id: 7, fullName: '李质检', department: '质量部', isActive: true },
        { id: 8, fullName: '停用人员', department: '质量部', isActive: false },
      ]));
    }
    return Promise.resolve(ok([]));
  });
  state.post.mockResolvedValue(ok({ id: 'defect-new' }));
  state.put.mockResolvedValue(ok({ id: 'defect-1' }));
});

describe('S3-11 source and assignee workflow', () => {
  it('inspection page routes a PASS source into defect registration', async () => {
    state.get.mockImplementation((url: string) => {
      if (url.endsWith('/processing/quality/inspections')) {
        return Promise.resolve(ok({
          content: [{ id: 'pass-qc', result: 'PASS', batchNumber: 'B-F006-PASS' }],
          totalElements: 1,
        }));
      }
      return Promise.resolve(ok({ content: [], totalElements: 0 }));
    });
    const wrapper = mountInspectionsPage();
    await flushPromises();
    await clickText(wrapper, '登记不良');
    expect(state.push).toHaveBeenCalledWith({
      path: '/quality/defects',
      query: { inspectionId: 'pass-qc' },
    });
    wrapper.unmount();
  });

  it('loads the real inspection source, saves, and refreshes the defect list', async () => {
    route.query = { inspectionId: source.id };
    const wrapper = mountPage();
    await flushPromises();
    await clickText(wrapper, '登记不良');
    expect(wrapper.text()).toContain('B-F006-01');
    expect(wrapper.text()).toContain('冷冻鱼排');
    const sourceSelect = wrapper.findAllComponents(ElSelect)
      .find(select => select.props('placeholder') === '选择真实质检来源')!;
    expect(sourceSelect.props('modelValue')).toBe(source.id);
    await clickText(wrapper, '提交');

    expect(state.post).toHaveBeenCalledWith('/F006/quality-defects', expect.objectContaining({
      qualityInspectionId: source.id,
      defectType: 'OTHER',
      quantity: 1,
    }));
    expect(state.post.mock.calls[0][1]).not.toHaveProperty('materialId');
    expect(state.get.mock.calls.filter(call => call[0] === '/F006/quality-defects')).toHaveLength(2);
    wrapper.unmount();
  });

  it('only offers active same-factory names and rejects an old invalid selection before save', async () => {
    state.get.mockImplementation((url: string) => {
      if (url.endsWith('/quality-defects')) {
        return Promise.resolve(ok({
          content: [{
            id: 'defect-1', status: 'OPEN', handlingAction: '返工', assignedTo: 99,
            qualityInspectionId: source.id, defectType: 'OTHER', quantity: 1,
          }],
          totalElements: 1,
        }));
      }
      if (url.endsWith('/users/directory')) {
        return Promise.resolve(ok([
          { id: 7, fullName: '李质检', department: '质量部', isActive: true },
          { id: 99, fullName: '未知状态人员', department: '质量部' },
        ]));
      }
      return Promise.resolve(ok([]));
    });
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: vi.fn() }));
    const wrapper = mountPage();
    await flushPromises();
    await clickText(wrapper, '分派');
    expect(wrapper.text()).toContain('当前处理人已失效或不属于当前工厂');
    await clickText(wrapper, '提交');
    expect(state.put).not.toHaveBeenCalled();
    expect(warning).toHaveBeenCalledWith('处理人已失效或不属于当前工厂，请重新选择');
    wrapper.unmount();
    warning.mockRestore();
  });

  it('saves a selected active assignee by id and refreshes the list', async () => {
    state.get.mockImplementation((url: string) => {
      if (url.endsWith('/quality-defects')) {
        return Promise.resolve(ok({
          content: [{
            id: 'defect-1', status: 'OPEN', handlingAction: '返工', assignedTo: null,
            qualityInspectionId: source.id, defectType: 'OTHER', quantity: 1,
          }],
          totalElements: 1,
        }));
      }
      if (url.endsWith('/users/directory')) {
        return Promise.resolve(ok([{ id: 7, fullName: '李质检', department: '质量部', isActive: true }]));
      }
      return Promise.resolve(ok([]));
    });
    const wrapper = mountPage();
    await flushPromises();
    await clickText(wrapper, '分派');
    const assigneeSelect = wrapper.findAllComponents(ElSelect).at(-1)!;
    expect(state.get).toHaveBeenCalledWith('/F006/users/directory');
    assigneeSelect.vm.$emit('update:modelValue', 7);
    await clickText(wrapper, '提交');

    expect(state.put).toHaveBeenCalledWith('/F006/quality-defects/defect-1/assign', {
      handlingAction: '返工',
      assignedTo: 7,
    });
    expect(state.get.mock.calls.filter(call => call[0] === '/F006/quality-defects')).toHaveLength(2);
    wrapper.unmount();
  });

  it('discards delayed source and directory responses after switching factories', async () => {
    let resolveSourceList!: (value: unknown) => void;
    let resolveSourceDetail!: (value: unknown) => void;
    state.get.mockImplementation((url: string) => {
      if (url.endsWith('/quality-defects')) {
        return Promise.resolve(ok({ content: [], totalElements: 0 }));
      }
      if (url.endsWith('/processing/quality/inspections')) {
        return new Promise(resolve => { resolveSourceList = resolve; });
      }
      if (url.endsWith('/processing/quality/inspections/inspection-f006')) {
        return new Promise(resolve => { resolveSourceDetail = resolve; });
      }
      return Promise.resolve(ok([]));
    });
    route.query = { inspectionId: source.id };
    const sourceWrapper = mountPage();
    await flushPromises();
    await clickText(sourceWrapper, '登记不良');
    authState.factoryId = 'F007';
    await flushPromises();
    resolveSourceList(ok({ content: [source], totalElements: 1 }));
    resolveSourceDetail(ok(source));
    await flushPromises();
    expect(state.post).not.toHaveBeenCalled();
    sourceWrapper.unmount();

    authState.factoryId = 'F006';
    await flushPromises();
    state.put.mockClear();
    let resolveDirectory!: (value: unknown) => void;
    state.get.mockImplementation((url: string) => {
      if (url.endsWith('/quality-defects')) {
        return Promise.resolve(ok({
          content: [{
            id: 'defect-1', status: 'OPEN', handlingAction: '返工', assignedTo: null,
            qualityInspectionId: source.id, defectType: 'OTHER', quantity: 1,
          }],
          totalElements: 1,
        }));
      }
      if (url.endsWith('/users/directory')) {
        return new Promise(resolve => { resolveDirectory = resolve; });
      }
      return Promise.resolve(ok([]));
    });
    const directoryWrapper = mountPage();
    await flushPromises();
    await clickText(directoryWrapper, '分派');
    authState.factoryId = 'F007';
    await flushPromises();
    resolveDirectory(ok([{ id: 7, fullName: '李质检', isActive: true }]));
    await flushPromises();
    expect(state.put).not.toHaveBeenCalled();
    directoryWrapper.unmount();
  });
});
