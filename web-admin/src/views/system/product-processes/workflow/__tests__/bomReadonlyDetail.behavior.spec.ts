import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import ProductProcessWorkflowEditor from '../ProductProcessWorkflowEditor.vue';
import { BOM_OVERLAY_PREFIX } from '../bomOverlay';

// jsdom lacks the native top-layer API; real browser acceptance verifies showModal and hit targets.
const showModalDescriptor = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal');
beforeAll(() => Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
  configurable: true,
  value(this: HTMLDialogElement) { this.setAttribute('open', ''); },
}));
afterAll(() => {
  if (showModalDescriptor) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', showModalDescriptor);
  else Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal');
});

/**
 * 只读 BOM 明细抽屉 —— **行为**断言, 不是源码文本断言。
 *
 * 🔴 这份文件守的行为是: 「只读用户点辅料/包材行, 最终**没有**走到写编辑器」。
 *
 * ⛔ 不要把它降级成「子组件发出了事件」——越权真的发生时那条断言**同样成立**,
 *    它是恒真的那一半(形态 B′)。要读的必须是**终点**: 写弹窗有没有开、写入口
 *    有没有被进入。
 *
 * ⚠️ 「没走到写入口」是一条「X 没发生」的读数, 单独读它可以被任何一种「压根没跑完」
 *    伪造(点击没派发 / 组件没渲染 / 事件名写错)。所以每一条都配一个**设计上必然发生**
 *    的正向读数: 只读明细抽屉必须打开、并且必须列出那一行的物料名(硬约束 9)。
 *
 * ⚠️ 还配一个**阳性对照**: 同一个只读用户身上直接调写入口 openAuxiliaryEditor,
 *    仪器(ElMessage.info 探针 + auxDialogVisible)必须变红 —— 否则「没红」只说明
 *    仪器坏了, 不说明没越权。
 */

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
  getProductProcessWorkflowVersion: vi.fn(),
  getRecipePinnedToWorkflow: vi.fn(),
}));

vi.mock('@/api/request', () => ({
  get: apiMocks.get,
  post: apiMocks.post,
  put: apiMocks.put,
}));

// 同 ProductProcessWorkflowEditor.spec.ts: 把 BomUnifiedPanel 的动态 import 整个桩掉,
// 否则它在环境拆除之后才 resolve, 一条未处理拒绝把整个 job 判红。
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
  getProductProcessWorkflowVersion: apiMocks.getProductProcessWorkflowVersion,
  getRecipePinnedToWorkflow: apiMocks.getRecipePinnedToWorkflow,
}));

/**
 * 🔴 「写入口到底有没有被进入」的**主探针**。
 *
 * ⛔ 不用日志文案当判据(措辞一改就静默失效, 形态 D)。openAuxiliaryEditor /
 *    openPackagingEditor 越过 canEdit 闸之后, 两条路都必然落到 resolveWritableRecipeId
 *    → ensureDraft(建/复用可写草稿) —— 那是**写**动作, 只读用户身上出现一次就是越权。
 * ⇒ 「ensureDraft 被调用过没有」既是阴性对照的读数, 也是可写路径的阳性读数, 同一个仪器
 *    两侧都用, 不可能一侧坏了另一侧还绿。
 */
const bomApiMocks = vi.hoisted(() => ({
  ensureDraft: vi.fn(),
  getVersionsByProduct: vi.fn(),
  getWorkspace: vi.fn(),
  listSubstitutes: vi.fn(),
}));

vi.mock('@/api/bom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/bom')>();
  return {
    ...actual,
    bomRecipeApi: {
      ...actual.bomRecipeApi,
      ensureDraft: bomApiMocks.ensureDraft,
      getVersionsByProduct: bomApiMocks.getVersionsByProduct,
      listSubstitutes: bomApiMocks.listSubstitutes,
    },
    bomSeasoningApi: { ...actual.bomSeasoningApi, getWorkspace: bomApiMocks.getWorkspace },
  };
});

/**
 * 🔴 与既有 spec 的 VueFlow 桩最关键的一点不同: 这个桩**真的渲染 `#node-*` 具名槽**。
 *
 * 既有 spec 的桩是 `template: '<div />'`, 于是画布上的 cell 组件一个都不渲染 ——
 * 那种环境里根本点不到辅料行, 只能直接调 vm 上的函数, 而「模板把 @open-row-detail
 * 接到了哪个函数上」恰恰是本次改动最容易接错、也最危险的一环(形态 B: 机制在, 没接上)。
 * ⇒ 这里必须让真实的 DOM 行存在, 点的是**用户点的那个东西**。
 */
vi.mock('@vue-flow/core', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    MarkerType: { ArrowClosed: 'arrow-closed' },
    SelectionMode: { Partial: 'partial' },
    Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
    Handle: defineComponent({ name: 'Handle', template: '<span />' }),
    VueFlow: defineComponent({
      name: 'VueFlow',
      props: { nodes: { type: Array, default: () => [] } },
      setup(props, { slots }) {
        return () => h('div', { class: 'vue-flow-stub' }, (props.nodes as Array<{
          id: string; type?: string; data?: unknown; selected?: boolean;
        }>).map((node) => {
          const slot = slots[`node-${node.type}`];
          return h('div', { key: node.id, 'data-id': node.id },
            slot ? slot({ id: node.id, data: node.data, selected: !!node.selected }) : []);
        }));
      },
    }),
    useVueFlow: () => ({
      findNode: () => ({ dimensions: { width: 240, height: 400 } }),
      nodes: { value: [] },
      fitView: vi.fn(() => Promise.resolve()),
      getViewport: () => ({ x: 0, y: 0, zoom: 1 }),
      setViewport: vi.fn(() => Promise.resolve()),
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

interface OverlayEditorVm {
  flowNodes: Array<{ id: string; position?: { x: number; y: number }; data: Record<string, unknown> }>;
  flowEdges: Array<{ id: string; source: string; target: string }>;
  openAddProcess: (materialNodeId: string) => void;
  selectedWorkProcessId: string;
  confirmAddProcess: () => void;
  refreshBomOverlay: () => void;
  loadBomOverlayData: () => Promise<void>;
  bomOverlayAuxiliaryByProcess: Record<string, unknown>;
  bomOverlayPackagingByOutput: Record<string, unknown>;
  bomOverlayPackagingRawByOutput: Record<string, unknown>;
  bomOverlayPackagingMaterialsLoaded: boolean;
  bomOverlayMaterialArchive: unknown[] | null;
  loadBomOverlayMaterialArchive: () => Promise<unknown[] | null>;
  auxOwnerPickerVisible: boolean;
  auxOwnerPickerChoice: string;
  auxOwnerPickerCandidates: string[];
  confirmAuxOwner: () => Promise<void>;
  auxDialogVisible: boolean;
  packagingDialogVisible: boolean;
  bomDetailVisible: boolean;
  bomDetailContext: unknown;
  immersive: boolean;
  selectedNodeId: string;
  openAuxiliaryEditor: (processNodeId: string, rowId?: string) => Promise<void>;
  openPackagingEditor: (outputNodeId: string, rowId?: string) => Promise<void>;
  beginEditingBomDetail: () => Promise<void>;
  openQuickEditSku: (id: string) => void;
  openQuickEditProcess: (id: string) => void;
  saveQuickEditSku: () => Promise<void>;
  saveQuickEditProcess: () => Promise<void>;
  quickEditForm: { name: string; unit: string };
  processEditForm: { processName: string };
  skuOptions: unknown[];
  workProcessOptions: unknown[];
  dirty: boolean;
  previewVersion: (version: { definitionVersion: number }) => Promise<void>;
  exitVersionPreview: () => Promise<void>;
  previewingVersion: number | null;
  bomHistoryNotice: string;
}

const SKU_OPTIONS = [
  { id: 'PT-PIG-400', name: '五香去骨猪蹄 400g', unit: 'kg', productCategory: 'FINISHED_PRODUCT' },
];

describe('catalog access follows readonly and writable modes', () => {
  it.each([
    ['sku', 'factory'], ['sku', 'permission'], ['process', 'factory'], ['process', 'permission'],
  ] as const)('a late %s save response cannot change the current graph after %s changes', async (kind, change) => {
    const { wrapper, vm, outputNodeId, processNodeId } = await readonlyEditorWithBomRows();
    await wrapper.setProps({ canWrite: true });
    await flushPromises();
    const pending = deferred<{ success: boolean; data: Record<string, unknown> }>();
    const api = kind === 'sku' ? apiMocks.put : apiMocks.updateWorkProcess;
    api.mockReturnValue(pending.promise);
    if (kind === 'sku') {
      vm.openQuickEditSku(outputNodeId);
      vm.quickEditForm.name = 'late SKU';
    } else {
      vm.openQuickEditProcess(processNodeId);
      vm.processEditForm.processName = 'late process';
    }
    const saving = kind === 'sku' ? vm.saveQuickEditSku() : vm.saveQuickEditProcess();
    await flushPromises();
    expect(api).toHaveBeenCalledTimes(1);
    if (change === 'factory') await wrapper.setProps({ factoryId: 'F007' });
    else {
      await wrapper.setProps({ canWrite: false });
      await wrapper.setProps({ canWrite: true });
    }
    await flushPromises();
    vm.dirty = false;
    const before = JSON.stringify([vm.flowNodes, vm.skuOptions, vm.workProcessOptions]);
    apiMocks.saveProductProcessWorkflowDraft.mockClear();
    pending.resolve({ success: true, data: { id: 'WP-PACK', processName: 'late process', name: 'late SKU' } });
    await saving;
    await flushPromises();
    expect(JSON.stringify([vm.flowNodes, vm.skuOptions, vm.workProcessOptions])).toBe(before);
    expect(vm.dirty).toBe(false);
    expect(apiMocks.saveProductProcessWorkflowDraft).not.toHaveBeenCalled();
  });

  it.each(['sku', 'process'] as const)('a current %s save still updates the intended node', async (kind) => {
    const { wrapper, vm, outputNodeId, processNodeId } = await readonlyEditorWithBomRows();
    await wrapper.setProps({ canWrite: true });
    await flushPromises();
    vm.dirty = false;
    if (kind === 'sku') {
      apiMocks.put.mockResolvedValue({ success: true, data: {} });
      vm.openQuickEditSku(outputNodeId);
      vm.quickEditForm.name = 'updated SKU';
      await vm.saveQuickEditSku();
      expect(vm.flowNodes.find(node => node.id === outputNodeId)?.data.name).toBe('updated SKU');
    } else {
      apiMocks.updateWorkProcess.mockResolvedValue({ success: true, data: { id: 'WP-PACK', processName: 'updated process', processCategory: 'PACKING' } });
      vm.openQuickEditProcess(processNodeId);
      vm.processEditForm.processName = 'updated process';
      await vm.saveQuickEditProcess();
      expect(vm.flowNodes.find(node => node.id === processNodeId)?.data.processName).toBe('updated process');
    }
    expect(vm.dirty).toBe(true);
  });

  function persistEnsuredDraftInFixture(): void {
    const ensure = bomApiMocks.ensureDraft.getMockImplementation()!;
    const versions = bomApiMocks.getVersionsByProduct.getMockImplementation()!;
    bomApiMocks.ensureDraft.mockImplementation(async (...args) => {
      const draft = await ensure(...args);
      const response = await versions();
      bomApiMocks.getVersionsByProduct.mockResolvedValue({ ...response,
        data: response.data.map((recipe: { id: string; status: string }) => recipe.id === draft.id
          ? { ...recipe, status: 'DRAFT' } : recipe),
      });
      return { success: true, data: draft };
    });
  }

  it.each(['product', 'factory'] as const)('changing %s identity closes both existing write dialogs', async (change) => {
    persistEnsuredDraftInFixture();
    const { wrapper, vm, processNodeId, outputNodeId } = await readonlyEditorWithBomRows();
    await wrapper.setProps({ canWrite: true });
    await flushPromises();
    await vm.openAuxiliaryEditor(processNodeId, 'AUX-ROW-1');
    await vm.openPackagingEditor(outputNodeId, 'PACK-ROW-1');
    expect(vm.auxDialogVisible).toBe(true);
    expect(vm.packagingDialogVisible).toBe(true);
    bomApiMocks.ensureDraft.mockClear();
    const previousDefinition = await apiMocks.getProductProcessWorkflow();
    const nextDefinition = deferred<typeof previousDefinition>();
    apiMocks.getProductProcessWorkflow.mockReturnValue(nextDefinition.promise);
    await wrapper.setProps(change === 'product' ? { productTypeId: 'PT-B' } : { factoryId: 'F007' });
    await flushPromises();
    expect(vm.auxDialogVisible).toBe(false);
    expect(vm.packagingDialogVisible).toBe(false);
    expect(wrapper.findComponent({ name: 'SeasoningBindingDialog' }).props('canWrite')).toBe(false);
    expect(wrapper.findComponent({ name: 'PackagingBindingDialog' }).props('canWrite')).toBe(false);
    await vm.openAuxiliaryEditor(processNodeId, 'AUX-ROW-1');
    await vm.openPackagingEditor(outputNodeId, 'PACK-ROW-1');
    expect(bomApiMocks.ensureDraft).not.toHaveBeenCalled();
    nextDefinition.resolve({ ...previousDefinition, data: { ...previousDefinition.data,
      factoryId: change === 'factory' ? 'F007' : 'F006',
      productTypeId: change === 'product' ? 'PT-B' : 'PT-PIG-400',
    } });
    await flushPromises();
    expect(vm.auxDialogVisible).toBe(false);
    expect(vm.packagingDialogVisible).toBe(false);
    expect(bomApiMocks.ensureDraft).not.toHaveBeenCalled();
  });

  it.each(['auxiliary', 'packaging'] as const)('revoking write access cancels delayed %s entry even after access returns', async (kind) => {
    persistEnsuredDraftInFixture();
    const { wrapper, vm, processNodeId, outputNodeId } = await readonlyEditorWithBomRows();
    await wrapper.setProps({ canWrite: true });
    await flushPromises();
    const pending = deferred<{ success: boolean; data: never[] }>();
    const prior = apiMocks.get.getMockImplementation()!;
    apiMocks.get.mockImplementation((url: string) => url.endsWith('/raw-material-types/active') ? pending.promise : prior(url));
    const callsBefore = apiMocks.get.mock.calls.length;
    const opening = kind === 'auxiliary'
      ? vm.openAuxiliaryEditor(processNodeId, 'AUX-ROW-1')
      : vm.openPackagingEditor(outputNodeId, 'PACK-ROW-1');
    await flushPromises();
    expect(apiMocks.get.mock.calls.slice(callsBefore)).toContainEqual(['/F006/raw-material-types/active']);
    await wrapper.setProps({ canWrite: false });
    await wrapper.setProps({ canWrite: true });
    pending.resolve({ success: true, data: [] });
    await opening;
    await flushPromises();
    expect(vm.auxDialogVisible).toBe(false);
    expect(vm.packagingDialogVisible).toBe(false);
    expect(bomApiMocks.ensureDraft).not.toHaveBeenCalled();
    // A new action after restored access remains usable.
    if (kind === 'auxiliary') await vm.openAuxiliaryEditor(processNodeId, 'AUX-ROW-1');
    else await vm.openPackagingEditor(outputNodeId, 'PACK-ROW-1');
    expect(bomApiMocks.ensureDraft).toHaveBeenCalled();
    expect(kind === 'auxiliary' ? vm.auxDialogVisible : vm.packagingDialogVisible).toBe(true);
    await wrapper.setProps({ canWrite: false });
    expect(vm.auxDialogVisible).toBe(false);
    expect(vm.packagingDialogVisible).toBe(false);
  });

  it('loads readonly units and rows without requesting the write-only taxonomy', async () => {
    const wrapper = shallowMount(ProductProcessWorkflowEditor, {
      props: { factoryId: 'F006', productTypeId: 'PT-PIG-400', productName: '猪蹄', canWrite: false },
    });
    mountedWrappers.push(wrapper);
    await flushPromises();
    expect(apiMocks.get.mock.calls.some(([url]) => String(url).endsWith('/material-segments/tree'))).toBe(false);
    expect(apiMocks.get).toHaveBeenCalledWith('/F006/raw-material-types/active');
    const state = wrapper.vm as unknown as { loadedCatalogFactoryId: string | null; canEdit: boolean };
    expect(state.loadedCatalogFactoryId).toBe('F006');
    expect(state.canEdit).toBe(false);

    const pending = deferred<{ success: boolean; data: never[] }>();
    const prior = apiMocks.get.getMockImplementation()!;
    apiMocks.get.mockImplementation((url: string) => url.endsWith('/material-segments/tree') ? pending.promise : prior(url));
    await wrapper.setProps({ canWrite: true });
    await flushPromises();
    expect(apiMocks.get).toHaveBeenCalledWith('/F006/material-segments/tree');
    expect(state.canEdit).toBe(false);
    pending.resolve({ success: true, data: [] });
    await flushPromises();
    expect(state.canEdit).toBe(true);
  });

  it('keeps editing disabled when the required writable taxonomy fails', async () => {
    const prior = apiMocks.get.getMockImplementation()!;
    apiMocks.get.mockImplementation((url: string) => url.endsWith('/material-segments/tree')
      ? Promise.reject(new Error('taxonomy unavailable')) : prior(url));
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined);
    const wrapper = shallowMount(ProductProcessWorkflowEditor, {
      props: { factoryId: 'F006', productTypeId: 'PT-PIG-400', productName: '猪蹄', canWrite: true },
    });
    mountedWrappers.push(wrapper);
    await flushPromises();
    const state = wrapper.vm as unknown as { loadedCatalogFactoryId: string | null; canEdit: boolean };
    expect(state.loadedCatalogFactoryId).toBeNull();
    expect(state.canEdit).toBe(false);
    expect(ElMessage.error).toHaveBeenCalled();
  });
});

const mountedWrappers: VueWrapper[] = [];
const bomFixture = { processNodeId: '' };

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => { resolve = next; });
  return { promise, resolve };
}

afterEach(() => {
  while (mountedWrappers.length) {
    const wrapper = mountedWrappers.pop();
    const root = wrapper?.element;
    wrapper?.unmount();
    root?.remove();
  }
});

beforeEach(() => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
  bomFixture.processNodeId = '';
  apiMocks.get.mockImplementation((url: string) => {
    if (url.endsWith('/product-types/options')) {
      return Promise.resolve({ success: true, data: { content: [...SKU_OPTIONS] } });
    }
    if (url.includes('/product-types/')) {
      return Promise.resolve({ success: true, data: SKU_OPTIONS[0] });
    }
    return Promise.resolve({ success: true, data: [] });
  });
  apiMocks.post.mockResolvedValue({ success: true, data: {} });
  bomApiMocks.ensureDraft.mockReset();
  bomApiMocks.ensureDraft.mockResolvedValue({
    id: 'RECIPE-DRAFT-1', factoryId: 'F006', recipeCode: 'BOM-1', productTypeId: 'PT-PIG-400',
    productName: '五香去骨猪蹄 400g', version: 2, isCurrent: false, status: 'DRAFT',
  });
  bomApiMocks.getVersionsByProduct.mockReset();
  bomApiMocks.getVersionsByProduct.mockResolvedValue({ success: true, data: [{
    id: 'RECIPE-DRAFT-1', factoryId: 'F006', recipeCode: 'BOM-1', productTypeId: 'PT-PIG-400',
    productName: '五香去骨猪蹄 400g', version: 2, isCurrent: true, status: 'ACTIVE',
    items: [{
      id: 'PACK-ROW-1', recipeId: 'RECIPE-DRAFT-1', factoryId: 'F006', materialTypeId: 'PACK-BUCKET',
      materialName: '塑料桶 20L', materialCategory: 'PACKAGING', standardQuantity: 0.05, unit: '个',
      naturalQuantity: null,
    }],
  }] });
  bomApiMocks.getWorkspace.mockReset();
  bomApiMocks.getWorkspace.mockImplementation(() => Promise.resolve({ success: true, data: {
    recipeId: 'RECIPE-DRAFT-1', productTypeId: 'PT-PIG-400', productName: '五香去骨猪蹄 400g',
    status: 'DRAFT', editable: true, seasoningRevision: 1, materialSummaries: [], anomalies: [],
    processes: [{ workflowProcessNodeId: bomFixture.processNodeId, workProcessId: 'WP-PACK', processOrder: 1,
      processName: '装盒', standardUsageSupported: true, bindings: [{
        id: 'AUX-ROW-1', workflowProcessNodeId: bomFixture.processNodeId, materialTypeId: 'AUX-STAR', name: '八角',
        dosagePerKgG: 2, subsequentPotRatio: null, countInSeasoning: true,
      }] }],
  } }));
  bomApiMocks.listSubstitutes.mockReset();
  bomApiMocks.listSubstitutes.mockResolvedValue({ success: true, data: [] });
  apiMocks.getProductWorkProcesses.mockResolvedValue({ success: true, data: [] });
  apiMocks.getWorkProcessCategories.mockResolvedValue({ success: true, data: ['PACKING'] });
  apiMocks.getActiveWorkProcesses.mockResolvedValue({
    success: true,
    data: [{
      id: 'WP-PACK', processName: '装盒', processCategory: 'PACKING', unit: 'kg',
      estimatedMinutes: 10, sortOrder: 1, isActive: true,
      standardYieldMin: null, standardYieldMax: null, needsInput: true,
      outputUnit: '盒', defaultOutputMaterialKind: 'FINISHED_GOOD',
      semiFinishedOutputCode: null, standardHourlyRate: null, customFieldSchema: null,
      createdAt: '2026-07-10T00:00:00Z', updatedAt: '2026-07-10T00:00:00Z',
    }],
  });
  // 起手就有一个原料 Cell —— openAddProcess('raw') 要挂在它后面(同既有 spec 的底图)。
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
      nodes: [{
        id: 'raw',
        kind: 'RAW_MATERIAL',
        position: { x: 16, y: 32 },
        data: { name: '猪蹄原料', skuId: 'RM-PIG', baseUnit: 'kg' },
      }],
      edges: [],
      viewport: { x: 0, y: 0, zoom: 1 },
    },
  });
  apiMocks.getProductProcessWorkflowActivation.mockResolvedValue({ success: true, data: null });
  apiMocks.getWorkflowBomSyncPreflight.mockResolvedValue({
    success: true,
    data: {
      classification: 'READY',
      activeBomVersion: 1,
      syncDraftVersion: null,
      activeBomWorkflowRevisionId: 71,
      targetWorkflowRevisionId: 71,
      preservedItems: [],
      automaticMappings: [],
      missingItems: [],
      conflicts: [],
      canCompleteAutomatically: true,
    },
  });
});

/**
 * 建一张最小的图(原料 → 工序 → 成品), 往两个浮层 cell 里各塞一行真实数据,
 * 然后把用户降级成只读。返回 wrapper 与 vm。
 */
async function readonlyEditorWithBomRows(attachToDocument = false): Promise<{
  wrapper: VueWrapper; vm: OverlayEditorVm; processNodeId: string; outputNodeId: string;
}> {
  const wrapper = shallowMount(ProductProcessWorkflowEditor, {
    props: {
      factoryId: 'F006',
      productTypeId: 'PT-PIG-400',
      productName: '五香去骨猪蹄 400g',
      canWrite: true,
    },
    global: {
      // 只放开这三个 —— 要点的那个 DOM 行在 WorkflowAuxiliaryNode / WorkflowPackagingNode
      // 里, 而它们的 body 由 WorkflowFusedStrip 的默认槽渲染, 三者缺一就点不到。
      stubs: {
        Teleport: true,
        // ⚠️ VueFlow 也必须放开 —— shallowMount 会把**每一个**子组件桩掉, 包括上面那个
        //    自己写的 VueFlow 桩; 它一被 VTU 二次桩掉, `#node-*` 槽就一次都不求值,
        //    画布上一个 cell 都不渲染(实测: html 里只有 <vue-flow-stub>)。
        VueFlow: false,
        WorkflowAuxiliaryNode: false,
        WorkflowPackagingNode: false,
        WorkflowFusedStrip: false,
      },
    },
    attachTo: attachToDocument ? document.body : undefined,
  });
  mountedWrappers.push(wrapper);
  await flushPromises();

  const vm = wrapper.vm as unknown as OverlayEditorVm;
  vm.openAddProcess('raw');
  vm.selectedWorkProcessId = 'WP-PACK';
  vm.confirmAddProcess();
  await flushPromises();

  const processNode = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
  const outputNode = vm.flowNodes.find((node) => node.data.kind === 'FINISHED_GOOD');
  if (!processNode || !outputNode) throw new Error('Expected a PROCESS and a FINISHED_GOOD node');
  bomFixture.processNodeId = processNode.id;
  await vm.loadBomOverlayData();
  await flushPromises();

  // 降级成只读用户 —— 从这一刻起, 任何通往写编辑器的路径都是越权。
  await wrapper.setProps({ canWrite: false });
  await flushPromises();

  return { wrapper, vm, processNodeId: processNode.id, outputNodeId: outputNode.id };
}

describe('只读用户点 BOM 行: 到只读明细, 不到写编辑器', () => {
  it('阳性对照: 同一个只读用户直接走写入口时, 仪器确实会红', async () => {
    // ⚠️ 这条不是在测产品, 是在测**仪器**。没有它, 下面两条的「没红」既可能是
    //    「真的没越权」, 也可能是「探针根本量不到越权」—— 二者读数完全一样。
    const infoSpy = vi.spyOn(ElMessage, 'info').mockImplementation(() => undefined);
    const { vm, processNodeId, outputNodeId } = await readonlyEditorWithBomRows();

    await vm.openAuxiliaryEditor(processNodeId, 'AUX-ROW-1');
    expect(infoSpy.mock.calls.map((call) => String(call[0])).join('|'))
      .toContain('无法打开辅料编辑弹窗');
    // 只读用户身上, 写入口在第一行就被挡住 ⇒ 连 ensureDraft 都到不了。
    expect(bomApiMocks.ensureDraft).not.toHaveBeenCalled();

    infoSpy.mockClear();
    await vm.openPackagingEditor(outputNodeId, 'PACK-ROW-1');
    expect(infoSpy.mock.calls.map((call) => String(call[0])).join('|'))
      .toContain('无法打开包材编辑弹窗');
  });

  it('辅料行: 点一下开只读明细, 且没有进入写入口', async () => {
    const infoSpy = vi.spyOn(ElMessage, 'info').mockImplementation(() => undefined);
    const { wrapper, vm, processNodeId } = await readonlyEditorWithBomRows();

    const auxRow = wrapper.find(
      `[data-id="${BOM_OVERLAY_PREFIX}aux:${processNodeId}"] .aux-row`,
    );
    // 正向读数一: 只读用户看得见这一行(旧行为里它也可见, 只是点不动)。
    expect(auxRow.exists(), '只读用户必须仍然看得到辅料行').toBe(true);

    await auxRow.trigger('click');
    await flushPromises();

    // 🔴 正向读数二(硬约束 9): 抽屉**必然**打开并列出那一行 —— 它没打开就说明这条路径
    //    压根没跑完, 那么下面「没到写入口」的读数是无意义的。
    const panel = wrapper.find('[data-testid="bom-detail-panel"]');
    expect(panel.exists(), '只读明细抽屉必须打开').toBe(true);
    expect(panel.text()).toContain('八角');
    expect(panel.text()).toContain('2 g/kg');
    expect(wrapper.find('[data-testid="bom-detail-row-AUX-ROW-1"]').classes())
      .toContain('is-focused');

    // 🔴 被守的行为: 没有到达写编辑器。
    expect(vm.auxDialogVisible, '只读用户不得打开辅料编辑弹窗').toBe(false);
    expect(vm.packagingDialogVisible).toBe(false);
    expect(infoSpy.mock.calls.map((call) => String(call[0])).join('|'),
      '出现这句话就说明点击又走进了写入口 openAuxiliaryEditor')
      .not.toContain('无法打开辅料编辑弹窗');
    expect(bomApiMocks.ensureDraft, '只读用户不得触发任何建草稿(写)动作')
      .not.toHaveBeenCalled();

    // 只读用户没有「进入编辑」这颗按钮, 但必须被明确告知为什么(⛔ 不是静默少一颗按钮)。
    expect(wrapper.find('[data-testid="bom-detail-edit"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="bom-detail-readonly-hint"]').exists()).toBe(true);
  });

  it('包材行: 点一下开只读明细, 且没有进入写入口', async () => {
    const infoSpy = vi.spyOn(ElMessage, 'info').mockImplementation(() => undefined);
    const { wrapper, vm, outputNodeId } = await readonlyEditorWithBomRows();

    const packRow = wrapper.find(
      `[data-id="${BOM_OVERLAY_PREFIX}pack:${outputNodeId}"] .packaging-row`,
    );
    expect(packRow.exists(), '只读用户必须仍然看得到包材行').toBe(true);

    await packRow.trigger('click');
    await flushPromises();

    const panel = wrapper.find('[data-testid="bom-detail-panel"]');
    expect(panel.exists(), '只读明细抽屉必须打开').toBe(true);
    expect(panel.text()).toContain('塑料桶 20L');
    // 折算前的原始表达对仓管才有意义 —— 抽屉里必须留着它。
    expect(panel.text()).toContain('= 1 个 / 20 kg');

    expect(vm.packagingDialogVisible, '只读用户不得打开包材编辑弹窗').toBe(false);
    expect(vm.auxDialogVisible).toBe(false);
    expect(infoSpy.mock.calls.map((call) => String(call[0])).join('|'),
      '出现这句话就说明点击又走进了写入口 openPackagingEditor')
      .not.toContain('无法打开包材编辑弹窗');
    expect(bomApiMocks.ensureDraft, '只读用户不得触发任何建草稿(写)动作')
      .not.toHaveBeenCalled();
  });

  it('Esc 关抽屉, 而不是退出全屏 —— 它不是 el-drawer, 既有那句选择器看不见它', async () => {
    const { wrapper, vm, processNodeId } = await readonlyEditorWithBomRows(true);
    const auxRow = wrapper.find(
      `[data-id="${BOM_OVERLAY_PREFIX}aux:${processNodeId}"] .aux-row`,
    );
    (auxRow.element as HTMLElement).focus();
    await auxRow.trigger('click');
    await flushPromises();
    expect(vm.bomDetailVisible).toBe(true);
    expect(vm.immersive).toBe(true);
    expect(document.activeElement).toBe(wrapper.find('[data-testid="bom-detail-panel"]').element);

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await flushPromises();
    expect(vm.bomDetailVisible, 'Esc 必须关掉这个抽屉').toBe(false);
    expect(vm.immersive, 'Esc 关闭明细时不得退出沉浸态').toBe(true);
    expect(document.activeElement).toBe(auxRow.element);
    expect(wrapper.find('[data-testid="bom-detail-panel"]').exists()).toBe(false);
  });

  it('原生明细弹窗把焦点收进闭环, Tab/Shift+Tab 不会落到背景', async () => {
    const { wrapper, processNodeId } = await readonlyEditorWithBomRows(true);
    const auxRow = wrapper.find(
      `[data-id="${BOM_OVERLAY_PREFIX}aux:${processNodeId}"] .aux-row`,
    );
    (auxRow.element as HTMLElement).focus();
    await auxRow.trigger('click');
    await flushPromises();

    const panel = wrapper.find('[data-testid="bom-detail-panel"]');
    const close = wrapper.find('[data-testid="bom-detail-close"]');
    const dismiss = wrapper.find('[data-testid="bom-detail-dismiss"]');
    expect(panel.attributes('tabindex')).toBe('-1');
    expect(document.activeElement).toBe(panel.element);

    await panel.trigger('keydown', { key: 'Tab' });
    expect(document.activeElement).toBe(close.element);
    await close.trigger('keydown', { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(dismiss.element);
    await dismiss.trigger('keydown', { key: 'Tab' });
    expect(document.activeElement).toBe(close.element);
    (auxRow.element as HTMLElement).focus();
    expect(document.activeElement).toBe(panel.element);
  });

  it('明细打开期间 Delete/Backspace 被拦在弹窗内, 不触发画布删除', async () => {
    const { wrapper, vm, processNodeId } = await readonlyEditorWithBomRows(true);
    const auxRow = wrapper.find(
      `[data-id="${BOM_OVERLAY_PREFIX}aux:${processNodeId}"] .aux-row`,
    );
    await auxRow.trigger('click');
    await flushPromises();
    vm.selectedNodeId = processNodeId;
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue(new Error('cancelled'));
    const panel = wrapper.find('[data-testid="bom-detail-panel"]');
    for (const key of ['Delete', 'Backspace']) {
      const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      panel.element.dispatchEvent(event);
      expect(event.defaultPrevented, `${key} 必须被明细弹窗拦截`).toBe(true);
    }
    await flushPromises();

    expect(confirmSpy).not.toHaveBeenCalled();
    expect(vm.bomDetailVisible).toBe(true);
  });

  it.each([{ productTypeId: 'PT-NEW' }, { factoryId: 'F007' }])('切换身份 %j 关闭并清空旧明细, 旧行不能继续进入编辑', async (nextIdentity) => {
    const { wrapper, vm, processNodeId } = await readonlyEditorWithBomRows(true);
    const auxRow = wrapper.find(
      `[data-id="${BOM_OVERLAY_PREFIX}aux:${processNodeId}"] .aux-row`,
    );
    await auxRow.trigger('click');
    await flushPromises();
    expect(vm.bomDetailVisible).toBe(true);

    apiMocks.getProductProcessWorkflow.mockImplementation(() => new Promise(() => {}));
    await wrapper.setProps(nextIdentity);
    await nextTick();
    expect(vm.bomDetailVisible).toBe(false);
    expect(vm.bomDetailContext).toBe(null);
    await vm.beginEditingBomDetail();
    expect(bomApiMocks.ensureDraft).not.toHaveBeenCalled();
  });

  it('同工厂切换产品时, 延迟的旧 overlay 不显示也不能打开编辑, 新产品完成后才恢复正向路径', async () => {
    const { wrapper, vm, processNodeId, outputNodeId } = await readonlyEditorWithBomRows(true);
    await wrapper.setProps({ canWrite: true });
    const staleA = deferred<{ success: boolean; data: unknown[] }>();
    const delayedBDefinition = deferred<{ success: boolean; data: unknown }>();
    const delayedBOverlay = deferred<{ success: boolean; data: unknown[] }>();
    const bNodes = vm.flowNodes
      .filter((node) => !node.id.startsWith(BOM_OVERLAY_PREFIX))
      .map((node) => ({
        id: node.id,
        kind: String(node.data.kind),
        position: node.position,
        data: {
          ...node.data,
          ...(node.id === outputNodeId ? { skuId: 'PT-B', name: '产品 B' } : {}),
        },
      }));
    const bRecipe = {
      id: 'RECIPE-B', factoryId: 'F006', recipeCode: 'BOM-B', productTypeId: 'PT-B',
      productName: '产品 B', version: 3, isCurrent: false, status: 'DRAFT',
      items: [{
        id: 'B-PACK-ROW', recipeId: 'RECIPE-B', factoryId: 'F006', materialTypeId: 'PACK-BUCKET',
        materialName: 'B 包材', materialCategory: 'PACKAGING', standardQuantity: 0.1, unit: '个',
      }],
    };
    apiMocks.getProductProcessWorkflow.mockImplementation((_factory: string, product: string) => (
      product === 'PT-B'
        ? delayedBDefinition.promise
        : Promise.resolve({ success: true, data: { productTypeId: 'PT-PIG-400', nodes: [], edges: [] } })
    ));
    bomApiMocks.getVersionsByProduct.mockImplementation((_factory: string, product: string) => (
      product === 'PT-PIG-400' ? staleA.promise : delayedBOverlay.promise
    ));

    // A 的 overlay 请求仍在途时切到 B；B 定义和 B overlay 也刻意分开延迟。
    const staleLoad = vm.loadBomOverlayData();
    await wrapper.setProps({ productTypeId: 'PT-B', productName: '产品 B' });
    delayedBDefinition.resolve({ success: true, data: {
      id: 2, productTypeId: 'PT-B', schemaVersion: 1, status: 'DRAFT', version: 1,
      lockVersion: 0, revisionId: 72, revisionHash: 'revision-b', nodes: bNodes, edges: [],
      viewport: { x: 0, y: 0, zoom: 1 },
    } });
    await flushPromises();
    expect(wrapper.text()).not.toContain('旧产品 A 包材');

    staleA.resolve({ success: true, data: [{
      id: 'RECIPE-A-STALE', factoryId: 'F006', recipeCode: 'BOM-A', productTypeId: 'PT-PIG-400',
      productName: '旧产品 A', version: 9, isCurrent: true, status: 'ACTIVE', items: [{
        id: 'A-PACK-STALE', recipeId: 'RECIPE-A-STALE', factoryId: 'F006', materialTypeId: 'PACK-A',
        materialName: '旧产品 A 包材', materialCategory: 'PACKAGING', standardQuantity: 0.2, unit: '个',
      }],
    }] });
    await staleLoad;
    await flushPromises();
    expect(wrapper.text()).not.toContain('旧产品 A 包材');
    expect(bomApiMocks.getVersionsByProduct).toHaveBeenCalledWith('F006', 'PT-B');

    // 当前身份已切到 B，但 B overlay 尚未成功，旧 owner id 不能触发任何写路径。
    await vm.openPackagingEditor(outputNodeId, 'A-PACK-STALE');
    await vm.openAuxiliaryEditor(processNodeId, 'AUX-ROW-1');
    expect(bomApiMocks.ensureDraft).not.toHaveBeenCalled();
    expect(vm.packagingDialogVisible).toBe(false);
    expect(vm.auxDialogVisible).toBe(false);
    expect(apiMocks.post).not.toHaveBeenCalled();
    expect(apiMocks.put).not.toHaveBeenCalled();

    delayedBOverlay.resolve({ success: true, data: [bRecipe] });
    await flushPromises();
    const bPack = wrapper.find(`[data-id="${BOM_OVERLAY_PREFIX}pack:${outputNodeId}"] .packaging-row`);
    expect(bPack.exists()).toBe(true);
    expect(bPack.text()).toContain('B 包材');
    expect(wrapper.text()).not.toContain('旧产品 A 包材');
    await bPack.trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="bom-detail-panel"]').text()).toContain('B 包材');
    await wrapper.find('[data-testid="bom-detail-edit"]').trigger('click');
    await flushPromises();
    expect(vm.packagingDialogVisible).toBe(true);
    expect(bomApiMocks.ensureDraft).not.toHaveBeenCalled();
  });

  it('身份切换期间延迟的物料档案完成不会继续打开包材编辑器或污染缓存', async () => {
    const { wrapper, vm, outputNodeId } = await readonlyEditorWithBomRows(true);
    await wrapper.setProps({ canWrite: true });
    const delayedArchive = deferred<{ success: boolean; data: unknown[] }>();
    apiMocks.get.mockImplementation((url: string) => {
      if (url.includes('/raw-material-types/active')) return delayedArchive.promise;
      if (url.endsWith('/product-types/options')) {
        return Promise.resolve({ success: true, data: { content: [...SKU_OPTIONS] } });
      }
      return Promise.resolve({ success: true, data: [] });
    });
    apiMocks.getProductProcessWorkflow.mockResolvedValue({ success: true, data: {
      id: 3, productTypeId: 'PT-B', schemaVersion: 1, status: 'DRAFT', version: 1,
      nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 },
    } });

    const staleEditor = vm.openPackagingEditor(outputNodeId, 'PACK-ROW-1');
    await flushPromises();
    expect(apiMocks.get).toHaveBeenCalledWith('/F006/raw-material-types/active');
    await wrapper.setProps({ factoryId: 'F007', productTypeId: 'PT-B', productName: '产品 B' });
    await flushPromises();
    delayedArchive.resolve({ success: true, data: [{ id: 'OLD-FACTORY-MATERIAL', name: '旧工厂物料' }] });
    await staleEditor;
    await flushPromises();

    expect(vm.packagingDialogVisible).toBe(false);
    expect(vm.bomOverlayPackagingMaterialsLoaded).toBe(false);
    expect(vm.bomOverlayMaterialArchive).toBeNull();
    const currentMaterials = [{ id: 'B-MATERIAL', name: '当前工厂物料' }];
    apiMocks.get.mockResolvedValue({ success: true, data: currentMaterials });
    expect(await vm.loadBomOverlayMaterialArchive()).toEqual(currentMaterials);
    expect(apiMocks.get).toHaveBeenLastCalledWith('/F007/raw-material-types/active');
    expect(vm.bomOverlayMaterialArchive).toEqual(currentMaterials);
    expect(bomApiMocks.ensureDraft).not.toHaveBeenCalled();
  });

  it('联产归属选择器随产品失效，当前产品重新选择仍能进入写入口', async () => {
    const { wrapper, vm, processNodeId, outputNodeId } = await readonlyEditorWithBomRows(true);
    await wrapper.setProps({ canWrite: true });
    const output = vm.flowNodes.find(node => node.id === outputNodeId)!;
    vm.flowNodes.push({ ...output, id: 'finished-second', data: { ...output.data, skuId: 'PT-SECOND' } });
    vm.flowEdges.push({ id: 'second-output', source: processNodeId, target: 'finished-second' });
    bomApiMocks.getVersionsByProduct.mockResolvedValue({ success: true, data: [] });
    await vm.loadBomOverlayData();
    await flushPromises();
    await vm.openAuxiliaryEditor(processNodeId);
    expect(vm.auxOwnerPickerVisible).toBe(true);
    expect(vm.auxOwnerPickerCandidates).toContain('PT-SECOND');
    const bNodes = vm.flowNodes.filter(node => !node.id.startsWith(BOM_OVERLAY_PREFIX)).map(node => ({
      id: node.id, kind: node.data.kind, position: { x: 0, y: 0 },
      data: { ...node.data, ...(node.data.kind === 'FINISHED_GOOD' ? { skuId: `B-${node.id}` } : {}) },
    }));
    apiMocks.getProductProcessWorkflow.mockResolvedValue({ success: true, data: {
      id: 2, factoryId: 'F006', productTypeId: 'PT-B', schemaVersion: 1, status: 'DRAFT', version: 1,
      revisionId: 72, nodes: bNodes, edges: [...vm.flowEdges], viewport: { x: 0, y: 0, zoom: 1 },
    } });
    bomApiMocks.ensureDraft.mockClear();
    await wrapper.setProps({ productTypeId: 'PT-B', productName: '产品 B' });
    await flushPromises();
    expect(vm.auxOwnerPickerVisible).toBe(false);
    expect(vm.auxOwnerPickerChoice).toBe('');
    expect(vm.auxOwnerPickerCandidates).toEqual([]);
    await vm.confirmAuxOwner();
    expect(bomApiMocks.ensureDraft).not.toHaveBeenCalled();
    expect(vm.auxDialogVisible).toBe(false);
    await vm.openAuxiliaryEditor(processNodeId);
    expect(vm.auxOwnerPickerVisible).toBe(true);
    const currentChoice = vm.auxOwnerPickerChoice;
    expect(currentChoice).toMatch(/^B-/);
    await vm.confirmAuxOwner();
    expect(bomApiMocks.ensureDraft).toHaveBeenCalled();
    expect(bomApiMocks.ensureDraft.mock.calls[0]?.slice(0, 2)).toEqual(['F006', currentChoice]);
  });

  it('键盘可达: 只读用户按 Enter 同样开明细(行是 role=button)', async () => {
    const { wrapper, vm, processNodeId } = await readonlyEditorWithBomRows();
    const auxRow = wrapper.find(
      `[data-id="${BOM_OVERLAY_PREFIX}aux:${processNodeId}"] .aux-row`,
    );
    expect(auxRow.attributes('role')).toBe('button');
    expect(auxRow.attributes('tabindex')).toBe('0');

    await auxRow.trigger('keydown.enter');
    await flushPromises();

    expect(wrapper.find('[data-testid="bom-detail-panel"]').exists()).toBe(true);
    expect(vm.auxDialogVisible).toBe(false);
  });
});

describe('D-S2-11: 历史工艺版本只读预览读钉在该版本上的配方, 绝不显示当前配方', () => {
  /** 当前图 (含原料/工序/成品) 去掉浮层节点后, 当作 v1 的历史定义返回。 */
  function historicalDefinition(vm: OverlayEditorVm): Record<string, unknown> {
    const real = vm.flowNodes.filter((node) => !node.id.startsWith(BOM_OVERLAY_PREFIX));
    return {
      id: 5, productTypeId: 'PT-PIG-400', schemaVersion: 1, status: 'PUBLISHED', version: 1, lockVersion: 0,
      nodes: real.map((node) => ({ id: node.id, kind: node.data.kind, position: node.position ?? { x: 0, y: 0 }, data: node.data })),
      edges: vm.flowEdges.filter((edge) => !edge.source.startsWith(BOM_OVERLAY_PREFIX) && !edge.target.startsWith(BOM_OVERLAY_PREFIX))
        .map((edge) => ({ id: edge.id, source: edge.source, target: edge.target })),
      viewport: { x: 0, y: 0, zoom: 1 },
    };
  }

  /** 退出预览会重载「当前定义」—— 夹具默认只回一个原料节点, 这里让它回当前完整的图。 */
  function keepCurrentGraphForExit(vm: OverlayEditorVm): void {
    apiMocks.getProductProcessWorkflow.mockResolvedValue({ success: true, data: {
      ...historicalDefinition(vm), id: 1, status: 'DRAFT', version: 2, revisionId: 71, revisionHash: 'revision-current',
    } });
  }

  const overlayText = (vm: OverlayEditorVm) =>
    JSON.stringify([vm.bomOverlayPackagingByOutput, vm.bomOverlayAuxiliaryByProcess]);

  beforeEach(() => {
    // 工作台只对「当前配方」(RECIPE-DRAFT-1) 有辅料八角; v1 当时的配方 (RECIPE-V1) 没有辅料。
    bomApiMocks.getWorkspace.mockImplementation((_factory: string, recipeId: string) => Promise.resolve({ success: true, data: {
      recipeId, productTypeId: 'PT-PIG-400', productName: '五香去骨猪蹄 400g', status: 'ACTIVE', editable: false,
      seasoningRevision: 1, materialSummaries: [], anomalies: [],
      processes: recipeId === 'RECIPE-DRAFT-1' ? [{ workflowProcessNodeId: bomFixture.processNodeId, workProcessId: 'WP-PACK',
        processOrder: 1, processName: '装盒', standardUsageSupported: true, bindings: [{
          id: 'AUX-ROW-1', workflowProcessNodeId: bomFixture.processNodeId, materialTypeId: 'AUX-STAR', name: '八角',
          dosagePerKgG: 2, subsequentPotRatio: null, countInSeasoning: true,
        }] }] : [],
    } }));
  });

  it('v2 之后预览 v1: 格子是 v1 的配方 (纸盒, 无八角), 不是当前的 (塑料桶 + 八角); 退出预览换回当前', async () => {
    const { vm } = await readonlyEditorWithBomRows();
    expect(overlayText(vm)).toContain('塑料桶').toContain('八角');
    keepCurrentGraphForExit(vm);
    apiMocks.getProductProcessWorkflowVersion.mockResolvedValue({ success: true, data: historicalDefinition(vm) });
    apiMocks.getRecipePinnedToWorkflow.mockResolvedValue({ success: true, data: {
      id: 'RECIPE-V1', factoryId: 'F006', recipeCode: 'BOM-1', productTypeId: 'PT-PIG-400', productName: '五香去骨猪蹄 400g',
      version: 1, isCurrent: false, status: 'ARCHIVED',
      items: [{ id: 'PACK-V1', recipeId: 'RECIPE-V1', factoryId: 'F006', materialTypeId: 'PACK-BOX',
        materialName: '纸盒(v1)', materialCategory: 'PACKAGING', standardQuantity: 1, unit: '个', naturalQuantity: null }],
    } });

    await vm.previewVersion({ definitionVersion: 1 });
    await flushPromises();

    expect(vm.previewingVersion).toBe(1);
    expect(apiMocks.getRecipePinnedToWorkflow).toHaveBeenCalledWith('F006', 'PT-PIG-400', 5);
    const history = overlayText(vm);
    expect(history).toContain('纸盒(v1)');
    expect(history).not.toContain('塑料桶');
    expect(history).not.toContain('八角');
    expect(vm.bomHistoryNotice).toBe('');

    await vm.exitVersionPreview();
    await flushPromises();
    expect(overlayText(vm)).toContain('塑料桶');
    expect(overlayText(vm)).not.toContain('纸盒(v1)');
  });

  it('那个版本从没钉过配方: 不画任何配方格子, 并明说「历史版本不含配方快照」, 不拿当前配方顶替', async () => {
    const { wrapper, vm } = await readonlyEditorWithBomRows();
    keepCurrentGraphForExit(vm);
    apiMocks.getProductProcessWorkflowVersion.mockResolvedValue({ success: true, data: historicalDefinition(vm) });
    apiMocks.getRecipePinnedToWorkflow.mockResolvedValue({ success: true, data: null });

    await vm.previewVersion({ definitionVersion: 1 });
    await flushPromises();

    const history = overlayText(vm);
    expect(history).not.toContain('塑料桶');
    expect(history).not.toContain('八角');
    expect(vm.bomHistoryNotice).toBe('历史版本不含配方快照');
    expect(wrapper.find('[data-testid="bom-history-notice"]').text()).toBe('历史版本不含配方快照');
    // 阳性对照: 同一个编辑器不在预览时, 当前配方照常显示 (仪器没坏)
    await vm.exitVersionPreview();
    await flushPromises();
    expect(overlayText(vm)).toContain('塑料桶');
    expect(wrapper.find('[data-testid="bom-history-notice"]').exists()).toBe(false);
  });

  it('读历史配方失败: 同样不显示当前配方, 并说明是读取失败', async () => {
    const { vm } = await readonlyEditorWithBomRows();
    apiMocks.getProductProcessWorkflowVersion.mockResolvedValue({ success: true, data: historicalDefinition(vm) });
    apiMocks.getRecipePinnedToWorkflow.mockRejectedValue(new Error('network'));

    await vm.previewVersion({ definitionVersion: 1 });
    await flushPromises();

    expect(overlayText(vm)).not.toContain('塑料桶');
    expect(vm.bomHistoryNotice).toBe('历史版本的配方读取失败，未显示配方');
  });
});

describe('可写用户: 明细抽屉里「进入编辑」才是写入口', () => {
  it('有权限时抽屉给出「进入编辑」按钮, 点它才走写入口', async () => {
    const infoSpy = vi.spyOn(ElMessage, 'info').mockImplementation(() => undefined);
    vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined);
    vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined);
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined);
    const { wrapper, vm, processNodeId } = await readonlyEditorWithBomRows();
    await wrapper.setProps({ canWrite: true });
    await flushPromises();

    const auxRow = wrapper.find(
      `[data-id="${BOM_OVERLAY_PREFIX}aux:${processNodeId}"] .aux-row`,
    );
    await auxRow.trigger('click');
    await flushPromises();

    // 🔴 有权限也**不是**点一行就直接掉进编辑弹窗 —— 先看明细, 编辑是显式的第二步。
    expect(wrapper.find('[data-testid="bom-detail-panel"]').exists()).toBe(true);
    expect(vm.auxDialogVisible).toBe(false);

    const editButton = wrapper.find('[data-testid="bom-detail-edit"]');
    expect(editButton.exists(), '有权限时必须有「进入编辑」').toBe(true);
    await editButton.trigger('click');
    await flushPromises();

    // 抽屉关掉 = 确实交棒给了写入口。
    expect(vm.bomDetailVisible).toBe(false);
    // 那句「无编辑权限」是只读兜底, 这次不该出现。
    expect(infoSpy.mock.calls.map((call) => String(call[0])).join('|'))
      .not.toContain('无法打开辅料编辑弹窗');
    // 🔴 用与阴性对照**同一个仪器**读: 写入口真的被进入了 ⇒ ensureDraft 被调到。
    //    (它是三条只读用例里断言「一次都没有」的那个函数 —— 同一个探针两侧都读得到,
    //     不可能一侧坏掉另一侧还绿。)
    expect(bomApiMocks.ensureDraft).toHaveBeenCalled();
  });

  it('包材明细的「进入编辑」正向进入包材写入口', async () => {
    vi.spyOn(ElMessage, 'info').mockImplementation(() => undefined);
    vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined);
    vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined);
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined);
    const { wrapper, vm, outputNodeId } = await readonlyEditorWithBomRows();
    await wrapper.setProps({ canWrite: true });
    await flushPromises();

    const packRow = wrapper.find(
      `[data-id="${BOM_OVERLAY_PREFIX}pack:${outputNodeId}"] .packaging-row`,
    );
    await packRow.trigger('click');
    await flushPromises();
    const editButton = wrapper.find('[data-testid="bom-detail-edit"]');
    expect(editButton.exists()).toBe(true);
    await editButton.trigger('click');
    await flushPromises();

    expect(vm.bomDetailVisible).toBe(false);
    expect(vm.auxDialogVisible).toBe(false);
    expect(bomApiMocks.ensureDraft).toHaveBeenCalled();
    expect(ElMessage.info).not.toHaveBeenCalledWith(expect.stringContaining('无法打开包材编辑弹窗'));
  });
});
