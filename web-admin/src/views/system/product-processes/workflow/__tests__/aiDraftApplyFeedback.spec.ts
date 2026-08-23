/**
 * 客户原话:「AI 改完我也看不到区别」。
 *
 * 姊妹测试 `aiDraftNeverFailsSilently.source.spec.ts` 守的是**源码形状**
 * (没有无提示的裸 return)。这一份不同: 它**挂载真实组件、走真实入口**
 * (`applyWorkflowAIDraft`, 与模板里 `@apply-draft` 绑的是同一个函数),
 * 只桩掉外部 IO。
 *
 * ⚠️ 为什么两份都要: 本仓的硬教训是「单测 + tsc 全绿而界面是坏的」。
 * 源码闸能被一句措辞改动绕过(它只认字符串), 真实入口这一份认的是
 * **用户点了按钮之后到底有没有话说**。
 */
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import ProductProcessWorkflowEditor from '../ProductProcessWorkflowEditor.vue';

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  getActiveWorkProcesses: vi.fn(),
  getWorkProcessCategories: vi.fn(),
  createWorkProcess: vi.fn(),
  updateWorkProcess: vi.fn(),
  getProductProcessWorkflow: vi.fn(),
  getProductProcessWorkflowActivation: vi.fn(),
  getWorkflowBomSyncPreflight: vi.fn(),
  getProductWorkProcesses: vi.fn(),
  publishProductProcessWorkflow: vi.fn(),
  publishAndActivateProductProcessWorkflow: vi.fn(),
  saveProductProcessWorkflowDraft: vi.fn(),
  snapshotProductProcessWorkflow: vi.fn(),
}));

vi.mock('@/api/request', () => ({
  get: apiMocks.get, post: apiMocks.post, put: apiMocks.put,
}));

// 同 ProductProcessWorkflowEditor.spec.ts: 不 stub 会在环境拆除后 resolve 动态 import。
vi.mock('../bomUnifiedPanelLoader', () => ({
  BomUnifiedPanel: { name: 'BomUnifiedPanelStub', render: () => null },
  preloadBomUnifiedPanel: vi.fn(() => Promise.resolve({})),
  scheduleBomUnifiedPanelPreload: vi.fn(() => () => { /* 无定时器可取消 */ }),
}));

vi.mock('@/api/processProduction', () => ({
  getActiveWorkProcesses: apiMocks.getActiveWorkProcesses,
  getWorkProcessCategories: apiMocks.getWorkProcessCategories,
  getProductWorkProcesses: apiMocks.getProductWorkProcesses,
  createWorkProcess: apiMocks.createWorkProcess,
  updateWorkProcess: apiMocks.updateWorkProcess,
}));

vi.mock('../workflowApi', () => ({
  getProductProcessWorkflow: apiMocks.getProductProcessWorkflow,
  getProductProcessWorkflowActivation: apiMocks.getProductProcessWorkflowActivation,
  getWorkflowBomSyncPreflight: apiMocks.getWorkflowBomSyncPreflight,
  publishProductProcessWorkflow: apiMocks.publishProductProcessWorkflow,
  publishAndActivateProductProcessWorkflow: apiMocks.publishAndActivateProductProcessWorkflow,
  saveProductProcessWorkflowDraft: apiMocks.saveProductProcessWorkflowDraft,
  snapshotProductProcessWorkflow: apiMocks.snapshotProductProcessWorkflow,
}));

vi.mock('@vue-flow/core', async () => {
  const { defineComponent } = await import('vue');
  return {
    MarkerType: { ArrowClosed: 'arrow-closed' },
    SelectionMode: { Partial: 'partial' },
    VueFlow: defineComponent({ name: 'VueFlow', template: '<div />' }),
    useVueFlow: () => ({
      findNode: () => ({ dimensions: { width: 240, height: 400 } }),
      fitView: vi.fn(),
      getViewport: () => ({ x: 0, y: 0, zoom: 1 }),
      setViewport: vi.fn(),
    }),
  };
});

vi.mock('@vue-flow/background', async () => {
  const { defineComponent } = await import('vue');
  return { Background: defineComponent({ name: 'Background', template: '<div />' }) };
});

vi.mock('@vue-flow/controls', async () => {
  const { defineComponent } = await import('vue');
  return { Controls: defineComponent({ name: 'Controls', template: '<div />' }) };
});

interface EditorVm {
  applyWorkflowAIDraft: (
    payload: Record<string, unknown>,
    sourceIdentity?: { factoryId: string; productTypeId: string },
  ) => Promise<void>;
}

const mountedWrappers: VueWrapper[] = [];

afterEach(() => {
  while (mountedWrappers.length) mountedWrappers.pop()?.unmount();
});

beforeEach(() => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
  apiMocks.get.mockResolvedValue({ success: true, data: [] });
  apiMocks.post.mockResolvedValue({ success: true, data: {} });
  apiMocks.getProductWorkProcesses.mockResolvedValue({ success: true, data: [] });
  apiMocks.getWorkProcessCategories.mockResolvedValue({ success: true, data: ['加工'] });
  apiMocks.getActiveWorkProcesses.mockResolvedValue({ success: true, data: [] });
  apiMocks.getProductProcessWorkflowActivation.mockResolvedValue({ success: true, data: null });
  apiMocks.getWorkflowBomSyncPreflight.mockResolvedValue({ success: true, data: null });
  apiMocks.getProductProcessWorkflow.mockResolvedValue({
    success: true,
    data: {
      id: 1,
      productTypeId: 'PT-PIG-400',
      schemaVersion: 1,
      status: 'DRAFT',
      version: 1,
      lockVersion: 0,
      revisionId: 71,
      revisionHash: 'revision-current',
      nodes: [],
      edges: [],
      viewport: { x: 0, y: 0, zoom: 1 },
    },
  });
});

async function mountEditor(canWrite = true): Promise<EditorVm> {
  const wrapper: VueWrapper = shallowMount(ProductProcessWorkflowEditor, {
    props: {
      factoryId: 'F006',
      productTypeId: 'PT-PIG-400',
      productName: '五香去骨猪蹄 400g',
      canWrite,
    },
  });
  mountedWrappers.push(wrapper);
  await flushPromises();
  return wrapper.vm as unknown as EditorVm;
}

/** 一份结构合法的 AI 规格 —— 用来确认「能走通」的那一侧。 */
const VALID_SPEC = { spec: { rawMaterials: ['猪蹄原料'], steps: [{ process: '卤制', processCategory: '加工' }] } };
const HERE = { factoryId: 'F006', productTypeId: 'PT-PIG-400' };

describe('「应用到草稿」失败时必须说话（真实组件入口）', () => {
  it('没有写权限：提示告诉他找谁开通，而不是毫无反应', async () => {
    const warn = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined);
    const vm = await mountEditor(false);

    await vm.applyWorkflowAIDraft(VALID_SPEC, HERE);

    expect(warn, '没有写权限时静默退出 —— 用户会读成「AI 没起作用」').toHaveBeenCalled();
    const text = String((warn.mock.calls[0]?.[0] as { message?: string })?.message ?? '');
    expect(text).toContain('联系工厂管理员');
    // sticky: 这条解释 3 秒消失等于没说。
    expect((warn.mock.calls[0]?.[0] as { duration?: number })?.duration).toBe(0);
  });

  it('AI 结果属于另一个产品：提示说清是哪个产品、该怎么办', async () => {
    const warn = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined);
    const vm = await mountEditor(true);

    await vm.applyWorkflowAIDraft(VALID_SPEC, { factoryId: 'F006', productTypeId: 'PT-OTHER-999' });

    expect(warn, '产品对不上时静默退出').toHaveBeenCalled();
    const text = String((warn.mock.calls[0]?.[0] as { message?: string })?.message ?? '');
    // 必须点名两边, 否则用户不知道该切到哪去。
    expect(text).toContain('PT-OTHER-999');
    expect(text).toContain('五香去骨猪蹄 400g');
    expect(text).toContain('切换到那个产品');
  });

  it('AI 结果没带产品信息：提示让他重新提问', async () => {
    const warn = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined);
    const vm = await mountEditor(true);

    await vm.applyWorkflowAIDraft(VALID_SPEC, undefined);

    expect(warn).toHaveBeenCalled();
    const text = String((warn.mock.calls[0]?.[0] as { message?: string })?.message ?? '');
    expect(text).toContain('重新提问');
  });

  it('⛔ 阴性对照：一切正常时不许弹这条「没生效」的提示', async () => {
    const warn = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined);
    const vm = await mountEditor(true);

    await vm.applyWorkflowAIDraft(VALID_SPEC, HERE);

    const blocked = warn.mock.calls.some((call) => {
      const message = String((call[0] as { message?: string })?.message ?? call[0] ?? '');
      return message.includes('联系工厂管理员')
        || message.includes('切换到那个产品')
        || message.includes('重新提问');
    });
    expect(blocked, '正常路径也弹了「没生效」提示 —— 那比不提示更糟(误报会让用户不再看提示)').toBe(false);
  });
});
