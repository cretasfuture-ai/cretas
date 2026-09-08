import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import ProductProcessWorkflowEditor from '../ProductProcessWorkflowEditor.vue';
import { BOM_OVERLAY_PREFIX } from '../bomOverlay';

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
}));

vi.mock('@/api/bom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/bom')>();
  return {
    ...actual,
    bomRecipeApi: { ...actual.bomRecipeApi, ensureDraft: bomApiMocks.ensureDraft },
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
          return h('div', { key: node.id, 'data-node-id': node.id },
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
  flowNodes: Array<{ id: string; data: Record<string, unknown> }>;
  openAddProcess: (materialNodeId: string) => void;
  selectedWorkProcessId: string;
  confirmAddProcess: () => void;
  refreshBomOverlay: () => void;
  bomOverlayAuxiliaryByProcess: Record<string, unknown>;
  bomOverlayPackagingByOutput: Record<string, unknown>;
  auxDialogVisible: boolean;
  packagingDialogVisible: boolean;
  bomDetailVisible: boolean;
  openAuxiliaryEditor: (processNodeId: string, rowId?: string) => Promise<void>;
  openPackagingEditor: (outputNodeId: string, rowId?: string) => Promise<void>;
}

const SKU_OPTIONS = [
  { id: 'PT-PIG-400', name: '五香去骨猪蹄 400g', unit: 'kg', productCategory: 'FINISHED_PRODUCT' },
];

const mountedWrappers: VueWrapper[] = [];

afterEach(() => {
  while (mountedWrappers.length) mountedWrappers.pop()?.unmount();
});

beforeEach(() => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
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
  bomApiMocks.ensureDraft.mockResolvedValue({ id: 'RECIPE-DRAFT-1', status: 'DRAFT', version: 2 });
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
async function readonlyEditorWithBomRows(): Promise<{
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
        // ⚠️ VueFlow 也必须放开 —— shallowMount 会把**每一个**子组件桩掉, 包括上面那个
        //    自己写的 VueFlow 桩; 它一被 VTU 二次桩掉, `#node-*` 槽就一次都不求值,
        //    画布上一个 cell 都不渲染(实测: html 里只有 <vue-flow-stub>)。
        VueFlow: false,
        WorkflowAuxiliaryNode: false,
        WorkflowPackagingNode: false,
        WorkflowFusedStrip: false,
      },
    },
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

  vm.bomOverlayAuxiliaryByProcess = {
    [processNode.id]: {
      usageSupported: true,
      rows: [{ id: 'AUX-ROW-1', materialName: '八角', dosageText: '2 g/kg', markers: [] }],
    },
  };
  vm.bomOverlayPackagingByOutput = {
    [outputNode.id]: {
      rows: [{
        id: 'PACK-ROW-1', materialName: '塑料桶 20L', dosageText: '0.05 个/kg',
        naturalHint: '= 1 个 / 20 kg', markers: [],
      }],
    },
  };
  vm.refreshBomOverlay();
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
      `[data-node-id="${BOM_OVERLAY_PREFIX}aux:${processNodeId}"] .aux-row`,
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
      `[data-node-id="${BOM_OVERLAY_PREFIX}pack:${outputNodeId}"] .packaging-row`,
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
    const { wrapper, vm, processNodeId } = await readonlyEditorWithBomRows();
    const auxRow = wrapper.find(
      `[data-node-id="${BOM_OVERLAY_PREFIX}aux:${processNodeId}"] .aux-row`,
    );
    await auxRow.trigger('click');
    await flushPromises();
    expect(vm.bomDetailVisible).toBe(true);

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await flushPromises();
    expect(vm.bomDetailVisible, 'Esc 必须关掉这个抽屉').toBe(false);
    expect(wrapper.find('[data-testid="bom-detail-panel"]').exists()).toBe(false);
  });

  it('键盘可达: 只读用户按 Enter 同样开明细(行是 role=button)', async () => {
    const { wrapper, vm, processNodeId } = await readonlyEditorWithBomRows();
    const auxRow = wrapper.find(
      `[data-node-id="${BOM_OVERLAY_PREFIX}aux:${processNodeId}"] .aux-row`,
    );
    expect(auxRow.attributes('role')).toBe('button');
    expect(auxRow.attributes('tabindex')).toBe('0');

    await auxRow.trigger('keydown.enter');
    await flushPromises();

    expect(wrapper.find('[data-testid="bom-detail-panel"]').exists()).toBe(true);
    expect(vm.auxDialogVisible).toBe(false);
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
      `[data-node-id="${BOM_OVERLAY_PREFIX}aux:${processNodeId}"] .aux-row`,
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
});
