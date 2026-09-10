import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { compileStyle, parse } from '@vue/compiler-sfc';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import Editor from '../ProductProcessWorkflowEditor.vue';
import Material from '../WorkflowMaterialNode.vue';
import Process from '../WorkflowProcessNode.vue';

vi.mock('@/api/request', () => ({ get: vi.fn(async () => ({ success: true, data: [] })), post: vi.fn(), put: vi.fn(), del: vi.fn() }));
vi.mock('../workflowApi', () => ({
  getProductProcessWorkflow: vi.fn(async () => ({ success: true, data: { schemaVersion: 1, status: 'DRAFT', nodes: [
    { id: 'raw', kind: 'RAW_MATERIAL', position: { x: 0, y: 0 }, data: { name: 'Raw', skuId: 'RM' } },
  ], edges: [], viewport: { x: 0, y: 0, zoom: 1 } } })),
  getProductProcessWorkflowActivation: vi.fn(async () => ({ success: true, data: null })),
  getWorkflowBomSyncPreflight: vi.fn(async () => ({ success: true, data: null })),
  saveProductProcessWorkflowDraft: vi.fn(), publishProductProcessWorkflow: vi.fn(), publishAndActivateProductProcessWorkflow: vi.fn(),
}));
vi.mock('../bomUnifiedPanelLoader', () => ({ BomUnifiedPanel: { render: () => null }, scheduleBomUnifiedPanelPreload: () => () => {}, preloadBomUnifiedPanel: vi.fn() }));

// DOM event adapter only: editor connection/mode state and node target classification remain real.
// Real VueFlow geometry and OS cursor pixels are separate browser/manual acceptance boundaries.
vi.mock('@vue-flow/core', async () => {
  const { defineComponent, ref } = await import('vue');
  return {
    MarkerType: { ArrowClosed: 'arrowclosed' }, SelectionMode: { Partial: 'partial' },
    Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
    Handle: defineComponent({ name: 'Handle', props: { connectable: { default: true }, position: String }, template: '<span class="vue-flow__handle" :class="[{connectable}, `vue-flow__handle-${position}`]" />' }),
    VueFlow: defineComponent({ name: 'VueFlow', emits: ['connectStart', 'connectEnd'], template: '<div><span class="vue-flow__handle connectable" data-testid="source" @mousedown="$emit(\'connectStart\', {nodeId: \'raw\'})" /><div class="vue-flow__pane draggable" @mouseup="$emit(\'connectEnd\')" /></div>' }),
    useVueFlow: () => ({ nodes: ref([]), findNode: () => undefined, fitView: vi.fn(), getViewport: () => ({ x: 0, y: 0, zoom: 1 }), setViewport: vi.fn(), getSelectedNodes: ref([]), removeSelectedNodes: vi.fn() }),
  };
});

const styleElements: HTMLStyleElement[] = [];
beforeAll(() => {
  const libraryStyle = document.createElement('style');
  libraryStyle.textContent = readFileSync(resolve(process.cwd(), 'node_modules/@vue-flow/core/dist/style.css'), 'utf8');
  document.head.append(libraryStyle); styleElements.push(libraryStyle);
  // Load production selectors, including Vue SFC scoping, rather than duplicating cursor rules.
  for (const [component, filename] of [[Editor, 'ProductProcessWorkflowEditor.vue'], [Material, 'WorkflowMaterialNode.vue'], [Process, 'WorkflowProcessNode.vue']] as const) {
    const source = readFileSync(resolve(__dirname, '..', filename), 'utf8');
    const id = (component as { __scopeId: string }).__scopeId;
    for (const block of parse(source).descriptor.styles) {
      const style = document.createElement('style');
      style.textContent = compileStyle({ source: block.content, filename, id, scoped: block.scoped }).code;
      document.head.append(style); styleElements.push(style);
    }
  }
});
afterAll(() => { styleElements.forEach(style => style.remove()); });
afterEach(() => { document.body.innerHTML = ''; });

const Handle = defineComponent({ props: { connectable: { default: true }, position: String }, setup: props => () => h('span', { class: ['vue-flow__handle', `vue-flow__handle-${props.position}`, { connectable: props.connectable }] }) });
const cursor = (element: Element) => getComputedStyle(element).cursor;

describe('workflow mounted cursor lifecycle', () => {
  it('targets the pane during connection and releases that state; selection mode remains independent', async () => {
    const wrapper = shallowMount(Editor, { attachTo: document.body, props: { factoryId: 'LOCAL', productTypeId: 'PT', productName: 'Local', canWrite: true }, global: { stubs: { VueFlow: false }, mocks: {}, directives: { loading: () => {} } } });
    await flushPromises();
    const pane = wrapper.get('.vue-flow__pane');
    expect(wrapper.get('.canvas-shell').classes()).not.toContain('is-connecting');
    expect(cursor(pane.element)).toBe('grab');
    await wrapper.get('[data-testid="source"]').trigger('mousedown');
    expect(wrapper.get('.canvas-shell').classes()).toContain('is-connecting');
    expect(cursor(pane.element)).toContain('--cretas-cursor-crosshair');
    await pane.trigger('mouseup');
    expect(wrapper.get('.canvas-shell').classes()).not.toContain('is-connecting');
    expect(cursor(pane.element)).toBe('grab');
    const select = wrapper.findAll('el-button, el-button-stub').find(button => button.text().includes('批量选择'));
    expect(select).toBeDefined();
    await select!.trigger('click');
    expect(wrapper.get('.workflow-canvas').classes()).toContain('is-batch-selecting');
    expect(cursor(pane.element)).toContain('--cretas-cursor-crosshair');
    wrapper.unmount();
  });

  it.each(['material', 'process'] as const)('%s preserves invalid-target and button cursors through connection changes', async (kind) => {
    const common = { canWrite: true, connectingFromKind: '' as '' | 'MATERIAL' | 'PROCESS' };
    const wrapper = kind === 'material'
      ? shallowMount(Material, { attachTo: document.body, props: { ...common, kind: 'SEMI_FINISHED', data: { name: 'Semi', skuId: 'S' }, rawMaterialOptions: [], semiOptions: [], finishedOptions: [] }, global: { stubs: { Handle, RouterLink: true } } })
      : shallowMount(Process, { attachTo: document.body, props: { ...common, semiOptions: [], finishedOptions: [], data: { processName: 'Process', workProcessId: 'P', ports: [{ id: 'in', direction: 'INPUT', ordinal: 0 }, { id: 'out', direction: 'OUTPUT', ordinal: 1 }] } }, global: { stubs: { Handle } } });
    const handle = wrapper.get('.vue-flow__handle.connectable');
    expect(cursor(handle.element)).toContain('--cretas-cursor-crosshair');
    await wrapper.setProps({ connectingFromKind: kind === 'material' ? 'PROCESS' : 'MATERIAL' });
    expect(wrapper.classes()).toContain('wf-valid');
    expect(cursor(handle.element)).toContain('--cretas-cursor-crosshair');
    await wrapper.setProps({ connectingFromKind: kind === 'material' ? 'MATERIAL' : 'PROCESS' });
    expect(wrapper.classes()).toContain('wf-dim');
    expect(cursor(handle.element)).toBe('not-allowed');
    expect(cursor(wrapper.get('.cell-delete').element)).toBe('pointer');
    await wrapper.setProps({ connectingFromKind: '' });
    expect(wrapper.classes()).not.toContain('wf-dim');
    expect(cursor(handle.element)).toContain('--cretas-cursor-crosshair');
    wrapper.unmount();
  });
});
