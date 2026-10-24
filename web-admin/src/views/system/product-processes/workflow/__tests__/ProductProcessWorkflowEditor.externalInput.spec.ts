import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ProductProcessWorkflowEditor from '../ProductProcessWorkflowEditor.vue';

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  getActiveWorkProcesses: vi.fn(),
  getWorkProcessCategories: vi.fn(),
  getProductWorkProcesses: vi.fn(),
  createWorkProcess: vi.fn(),
  updateWorkProcess: vi.fn(),
  getProductProcessWorkflow: vi.fn(),
  getProductProcessWorkflowActivation: vi.fn(),
  getWorkflowBomSyncPreflight: vi.fn(),
  publishProductProcessWorkflow: vi.fn(),
  publishAndActivateProductProcessWorkflow: vi.fn(),
  saveProductProcessWorkflowDraft: vi.fn(),
}));

vi.mock('@/api/request', () => ({
  default: { get: api.get, post: api.post, put: api.put },
  get: api.get,
  post: api.post,
  put: api.put,
}));

vi.mock('@/api/processProduction', () => ({
  getActiveWorkProcesses: api.getActiveWorkProcesses,
  getWorkProcessCategories: api.getWorkProcessCategories,
  getProductWorkProcesses: api.getProductWorkProcesses,
  createWorkProcess: api.createWorkProcess,
  updateWorkProcess: api.updateWorkProcess,
}));

vi.mock('../workflowApi', () => ({
  getProductProcessWorkflow: api.getProductProcessWorkflow,
  getProductProcessWorkflowActivation: api.getProductProcessWorkflowActivation,
  getWorkflowBomSyncPreflight: api.getWorkflowBomSyncPreflight,
  publishProductProcessWorkflow: api.publishProductProcessWorkflow,
  publishAndActivateProductProcessWorkflow: api.publishAndActivateProductProcessWorkflow,
  saveProductProcessWorkflowDraft: api.saveProductProcessWorkflowDraft,
}));

vi.mock('../bomUnifiedPanelLoader', () => ({
  BomUnifiedPanel: { name: 'BomUnifiedPanelStub', render: () => null },
  preloadBomUnifiedPanel: vi.fn(() => Promise.resolve({})),
  scheduleBomUnifiedPanelPreload: vi.fn(() => () => {}),
}));

vi.mock('@vue-flow/core', async () => {
  const { defineComponent, ref } = await import('vue');
  return {
    MarkerType: { ArrowClosed: 'arrow-closed' },
    SelectionMode: { Partial: 'partial' },
    Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
    Handle: defineComponent({ name: 'Handle', template: '<div />' }),
    VueFlow: defineComponent({ name: 'VueFlow', template: '<div />' }),
    useVueFlow: () => ({
      findNode: () => ({ dimensions: { width: 240, height: 400 } }),
      nodes: ref([]),
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

interface EditorNode {
  id: string;
  position: { x: number; y: number };
  data: Record<string, unknown> & { kind?: string; ports?: TestPort[] };
}

interface TestPort {
  id: string;
  direction: 'INPUT' | 'OUTPUT';
  materialNodeId?: string;
  materialKind?: string;
  skuId?: string;
  unit?: string;
  ordinal: number;
}

interface EditorVm {
  flowNodes: EditorNode[];
  flowEdges: Array<{ id: string; source: string; target: string; sourceHandle?: string; targetHandle?: string }>;
  addInputToProcess: (processId: string) => void;
  confirmAddRawInput: () => void;
  rawInputDialog: {
    visible: boolean;
    processId: string;
    skuId: string;
    inputSource: 'RAW_MATERIAL' | 'EXTERNAL';
    relation: '' | 'SUBSTITUTE' | 'PARALLEL';
  };
}

const mounted: VueWrapper[] = [];

afterEach(() => {
  while (mounted.length) mounted.pop()?.unmount();
});

beforeEach(() => {
  vi.clearAllMocks();
  api.get.mockImplementation((url: string) => {
    if (url.endsWith('/product-types/options')) {
      return Promise.resolve({ success: true, data: { content: [
        { id: 'SKU-SEMI-EXT', name: '外部半成品', code: 'SFI-EXT', unit: 'kg', productCategory: 'SEMI_FINISHED', isActive: true },
        { id: 'SKU-SEMI-COUNT', name: '计数半成品', code: 'SFI-COUNT', unit: '只', productCategory: 'SEMI_FINISHED', isActive: true },
        { id: 'SKU-SEMI-INACTIVE', name: '停用半成品', code: 'SFI-OFF', unit: 'kg', productCategory: 'SEMI_FINISHED', isActive: false },
        { id: 'SKU-FINISHED', name: '成品', code: 'FG-1', unit: 'kg', productCategory: 'FINISHED_PRODUCT', isActive: true },
        { id: 'SKU-WRONG', name: '原料分类', code: 'RAW-1', unit: 'kg', productCategory: 'RAW_MATERIAL', isActive: true },
      ] } });
    }
    if (url.endsWith('/raw-material-types/active')) {
      return Promise.resolve({ success: true, data: [
        { id: 'RM-1', name: '主原料', code: 'RM-1', unit: 'kg', category: '原料' },
        { id: 'RM-2', name: '第二原料', code: 'RM-2', unit: 'kg', category: '原料' },
      ] });
    }
    return Promise.resolve({ success: true, data: [] });
  });
  api.getActiveWorkProcesses.mockResolvedValue({ success: true, data: [{
    id: 'WP-PACK', processName: '包装', processCategory: 'PACKING', unit: 'kg', outputUnit: 'kg',
    needsInput: true, defaultOutputMaterialKind: 'FINISHED_GOOD', isActive: true,
  }] });
  api.getWorkProcessCategories.mockResolvedValue({ success: true, data: ['PACKING'] });
  api.getProductWorkProcesses.mockResolvedValue({ success: true, data: [] });
  const workflowData = {
    id: 1, productTypeId: 'SKU-FINISHED', schemaVersion: 1, status: 'DRAFT', version: 1, lockVersion: 0,
    nodes: [
      { id: 'raw', kind: 'RAW_MATERIAL', position: { x: 0, y: 0 }, data: { name: '主原料', skuId: 'RM-1', skuCode: 'RM-1', baseUnit: 'kg', bound: true } },
      { id: 'process:pack', kind: 'PROCESS', position: { x: 400, y: 0 }, data: {
        workProcessId: 'WP-PACK', processName: '包装', inputUnit: 'kg', outputUnit: 'kg', reportingSelectionMode: 'ACTUAL_IO',
        ports: [
          { id: 'input:raw', direction: 'INPUT', materialNodeId: 'raw', materialKind: 'RAW_MATERIAL', unit: 'kg', ordinal: 0 },
          { id: 'output:finished', direction: 'OUTPUT', materialNodeId: 'finished', materialKind: 'FINISHED_GOOD', unit: 'kg', ordinal: 0 },
        ],
      } },
      { id: 'finished', kind: 'FINISHED_GOOD', position: { x: 800, y: 0 }, data: { name: '成品', skuId: 'SKU-FINISHED', skuCode: 'FG-1', baseUnit: 'kg', bound: true } },
    ],
    edges: [
      { id: 'edge:raw', source: 'raw', sourceHandle: 'output', target: 'process:pack', targetHandle: 'input:raw' },
      { id: 'edge:finished', source: 'process:pack', sourceHandle: 'output:finished', target: 'finished', targetHandle: 'input' },
    ],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
  api.getProductProcessWorkflow.mockImplementation(() => Promise.resolve({
    success: true,
    data: structuredClone(workflowData),
  }));
  api.getProductProcessWorkflowActivation.mockResolvedValue({ success: true, data: null });
  api.getWorkflowBomSyncPreflight.mockResolvedValue({ success: true, data: { classification: 'READY', missingItems: [], conflicts: [] } });
});

async function mountEditor(): Promise<{ wrapper: VueWrapper; vm: EditorVm }> {
  const wrapper = shallowMount(ProductProcessWorkflowEditor, {
    props: { factoryId: 'F006', productTypeId: 'SKU-FINISHED', productName: '成品', canWrite: true },
  });
  mounted.push(wrapper);
  await flushPromises();
  return { wrapper, vm: wrapper.vm as unknown as EditorVm };
}

describe('external SEMI_FINISHED source input', () => {
  it('renders and persists an external semi input with its real unit and INPUT port', async () => {
    const { wrapper, vm } = await mountEditor();
    vm.addInputToProcess('process:pack');
    await wrapper.vm.$nextTick();
    expect(wrapper.find('[data-testid="raw-input-modal"]').exists()).toBe(true);

    vm.rawInputDialog.inputSource = 'EXTERNAL';
    vm.rawInputDialog.skuId = 'SKU-SEMI-EXT';
    vm.confirmAddRawInput();
    await wrapper.vm.$nextTick();

    const node = vm.flowNodes.find((item) => item.data.inputSource === 'EXTERNAL');
    expect(node?.data).toMatchObject({ kind: 'SEMI_FINISHED', skuId: 'SKU-SEMI-EXT', baseUnit: 'kg' });
    const process = vm.flowNodes.find((item) => item.id === 'process:pack');
    const port = process?.data.ports?.find((item) => item.materialNodeId === node?.id);
    expect(port).toMatchObject({ direction: 'INPUT', materialKind: 'SEMI_FINISHED', skuId: 'SKU-SEMI-EXT', unit: 'kg' });
    expect(vm.flowEdges).toContainEqual(expect.objectContaining({ source: node?.id, target: 'process:pack', targetHandle: port?.id }));
    expect(wrapper.find('[data-testid="raw-input-modal"]').exists()).toBe(false);
  });

  it('opens the chooser for the first source and allows a RAW input without a relation', async () => {
    const { wrapper, vm } = await mountEditor();
    const process = vm.flowNodes.find((item) => item.id === 'process:pack');
    const rawNode = vm.flowNodes.find((item) => item.id === 'raw');
    vm.flowNodes.splice(vm.flowNodes.findIndex((item) => item.id === rawNode?.id), 1);
    if (process?.data.ports) {
      process.data.ports.splice(
        process.data.ports.findIndex((port) => port.materialNodeId === rawNode?.id), 1);
    }
    vm.flowEdges.splice(
      0,
      vm.flowEdges.length,
      ...vm.flowEdges.filter((edge) => edge.source !== rawNode?.id && edge.target !== rawNode?.id));

    vm.addInputToProcess('process:pack');
    await wrapper.vm.$nextTick();
    expect(wrapper.find('[data-testid="raw-input-modal"]').exists()).toBe(true);
    vm.rawInputDialog.skuId = 'RM-2';
    expect(vm.rawInputDialog.relation).toBe('');
    vm.confirmAddRawInput();
    await wrapper.vm.$nextTick();

    expect(vm.flowNodes.filter((item) => item.data.kind === 'RAW_MATERIAL')).toHaveLength(1);
    expect(wrapper.find('[data-testid="raw-input-modal"]').exists()).toBe(false);
  });

  it('explains the RAW-route limitation when external mode is selected', async () => {
    const { wrapper, vm } = await mountEditor();
    vm.addInputToProcess('process:pack');
    vm.rawInputDialog.inputSource = 'EXTERNAL';
    await wrapper.vm.$nextTick();
    expect(wrapper.get('[data-testid="external-semi-route-hint"]').text()).toContain('仍需保留原料投入');
  });

  it('cancels external selection without changing the graph', async () => {
    const { wrapper, vm } = await mountEditor();
    const process = vm.flowNodes.find((item) => item.id === 'process:pack');
    const before = process?.data.ports?.length;
    vm.addInputToProcess('process:pack');
    await wrapper.vm.$nextTick();
    vm.rawInputDialog.inputSource = 'EXTERNAL';
    vm.rawInputDialog.skuId = 'SKU-SEMI-EXT';
    await wrapper.get('[data-testid="raw-input-cancel"]').trigger('click');
    expect(process?.data.ports).toHaveLength(before);
    expect(vm.flowNodes.some((item) => item.data.inputSource === 'EXTERNAL')).toBe(false);
  });

  it('keeps the existing RAW relation path intact', async () => {
    const { wrapper, vm } = await mountEditor();
    vm.addInputToProcess('process:pack');
    vm.rawInputDialog.skuId = 'RM-2';
    vm.rawInputDialog.relation = 'PARALLEL';
    vm.confirmAddRawInput();
    await wrapper.vm.$nextTick();
    const rawNodes = vm.flowNodes.filter((item) => item.data.kind === 'RAW_MATERIAL');
    expect(rawNodes).toHaveLength(2);
    expect(rawNodes.at(-1)?.data).toMatchObject({ skuId: 'RM-2' });
    expect(rawNodes.at(-1)?.data).not.toHaveProperty('inputSource');
    expect(rawNodes.at(-1)?.data.substituteOfNodeId).toBeUndefined();
  });

  it.each([
    ['SKU-SEMI-COUNT', '计数单位'],
    ['SKU-SEMI-INACTIVE', '停用半成品'],
    ['SKU-WRONG', '错误分类'],
    ['SKU-MISSING', '不存在'],
  ])('refuses %s external source candidate (%s)', async (skuId) => {
    const { wrapper, vm } = await mountEditor();
    vm.addInputToProcess('process:pack');
    vm.rawInputDialog.inputSource = 'EXTERNAL';
    vm.rawInputDialog.skuId = skuId;
    vm.confirmAddRawInput();
    await wrapper.vm.$nextTick();
    expect(vm.flowNodes.some((item) => item.data.inputSource === 'EXTERNAL')).toBe(false);
    expect(wrapper.find('[data-testid="raw-input-modal"]').exists()).toBe(true);
  });
});
