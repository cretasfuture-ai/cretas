import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { computed, defineComponent, h, inject, provide, type ComputedRef, type PropType } from 'vue';
import Setup from '../FactoryVisionTrainingSetup.vue';
import * as api from '@/api/visionTraining';
import type { VisionDraft, VisionPlan, VisionPreview, VisionTemplateCatalog } from '@/api/visionTraining';

vi.mock('@/api/visionTraining', () => ({ getVisionTemplates: vi.fn(), listVisionDrafts: vi.fn(), getVisionDraft: vi.fn(), getVisionPlan: vi.fn(), listVisionJobs: vi.fn(), previewVisionTraining: vi.fn(), saveVisionDraft: vi.fn(), enqueueVisionStage: vi.fn() }));
vi.mock('element-plus', async importOriginal => ({ ...await importOriginal<typeof import('element-plus')>(), ElMessage: { success: vi.fn(), warning: vi.fn() } }));

const tableStubs = {
  ElTable: defineComponent({ props: { data: { type: Array as PropType<VisionPlan['items']>, required: true } },
    setup(props, { slots }) { provide('vision-test-rows', computed(() => props.data)); return () => h('div', slots.default?.()); } }),
  ElTableColumn: defineComponent({ setup(_props, { slots }) {
    const rows = inject<ComputedRef<VisionPlan['items']>>('vision-test-rows');
    return () => h('div', rows?.value.flatMap(row => slots.default?.({ row }) || []));
  } }),
};

function catalog(factory: string): VisionTemplateCatalog {
  return { sopVersion: 'v1', taskSpecSchema: {}, templates: [{ template_id: 'pack', version: 'v1', task_family: 'pack', title: '包装检测', scope: 'generic', required_fields: [], stages: ['preview', 'collect', 'dataset', 'train', 'evaluate', 'independent_acceptance', 'enable'], ocr_rules_required: false, human_standard: '本厂人工标准', independent_acceptance: '本厂独立验收' }], recipes: [{ factory_id: factory, recipe_id: 'recipe-a', recipe_version: 'r1', task_family: 'pack', available: true, reason: '', data_version: 'd1', human_standard_version: 'h1', independent_acceptance_version: 'a1', ocr_rules_version: null, stage_input_hashes: { collect: 'collect-hash', dataset: 'dataset-hash', train: 'train-hash', evaluate: 'evaluate-hash' } }] };
}
function draft(factory = 'FA'): VisionDraft {
  return { project_id: 'vision-a', sop_version: 'v1', template: catalog(factory).templates[0]!, status: 'draft', preview_hash: 'a'.repeat(64), created_at: '2026-10-15T10:00:00Z', updated_at: '2026-10-15T10:00:00Z', receipts: [], spec: { factory_id: factory, workstation_id: '一号包装线', task_family: 'pack', product_scope: ['产品A'], packaging_scope: ['盒装'], data_version: 'd1', recipe_version: 'r1', human_standard_version: 'h1', independent_acceptance_version: 'a1', recipe_id: 'recipe-a', ocr_rules_version: null } };
}
function plan(): VisionPlan {
  return { project_id: 'vision-a', status: 'draft', items: [{ stage: 'preview', status: 'succeeded', reason: '' }, { stage: 'collect', status: 'pending', reason: '' }, { stage: 'dataset', status: 'pending', reason: '' }, { stage: 'train', status: 'pending', reason: '' }], training_success: false, accepted: false, enabled: false, blockers: [] };
}
async function mount() {
  const wrapper = shallowMount(Setup, { props: { factoryId: 'FA' }, global: { plugins: [ElementPlus], stubs: tableStubs, renderStubDefaultSlot: true } });
  await flushPromises(); return wrapper;
}
async function field(wrapper: VueWrapper, name: string, value: string | string[]) {
  if (name === 'template') {
    await wrapper.find(`[data-template="${value}"]`).trigger('click'); await flushPromises(); return;
  }
  const node = wrapper.findComponent(`[data-testid="vision-${name}"]`);
  expect(node.exists()).toBe(true);
  node.vm.$emit('update:modelValue', Array.isArray(value) ? value.join('、') : value);
  if (name === 'recipe') node.vm.$emit('change', value);
  await flushPromises();
}
async function fill(wrapper: VueWrapper, recipe: string | null = 'recipe-a') {
  await field(wrapper, 'template', 'pack'); await click(wrapper, '下一步'); await field(wrapper, 'workstation', '一号包装线');
  await field(wrapper, 'products', ['产品A']); await field(wrapper, 'packaging', ['盒装']);
  if (recipe) { await click(wrapper, '系统记录'); await field(wrapper, 'recipe', recipe); await click(wrapper, '收起系统记录'); }
}
async function click(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAllComponents({ name: 'ElButton' }).find(node => node.text().includes(text));
  expect(button).toBeDefined(); button!.vm.$emit('click'); await flushPromises();
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.getVisionTemplates).mockImplementation(async factory => catalog(factory));
  vi.mocked(api.listVisionDrafts).mockResolvedValue([]);
  vi.mocked(api.getVisionDraft).mockResolvedValue(draft());
  vi.mocked(api.getVisionPlan).mockResolvedValue(plan());
  vi.mocked(api.listVisionJobs).mockResolvedValue([]);
  vi.mocked(api.previewVisionTraining).mockImplementation(async (_factory, spec) => ({ preview_id: 'preview-a', preview_hash: 'a'.repeat(64), sop_version: 'v1', template: catalog('FA').templates[0]!, spec, stage_plan: ['preview', 'collect', 'dataset', 'train', 'evaluate', 'independent_acceptance', 'enable'], missing_requirements: [], warnings: [], generated_at: '2026-10-15T10:00:00Z' } satisfies VisionPreview));
  vi.mocked(api.saveVisionDraft).mockResolvedValue(draft());
});

describe('factory vision setup', () => {
  it('opens the linked learning record through the existing project loader and ignores foreign links', async () => {
    const source = draft(); source.project_id = 'source-a';
    const execution = draft(); execution.project_id = 'execution-a'; execution.spec.data_version = 'prep-' + 'b'.repeat(64);
    vi.mocked(api.listVisionDrafts).mockResolvedValue([source]);
    vi.mocked(api.getVisionDraft).mockResolvedValue(source);
    vi.mocked(api.getVisionPlan).mockResolvedValue({ ...plan(), project_id: source.project_id });
    const wrapper = await mount();
    const picker = wrapper.findComponent('[data-testid="vision-saved-project"]');
    picker.vm.$emit('update:modelValue', source.project_id); picker.vm.$emit('change', source.project_id); await flushPromises();
    const preparation = wrapper.findComponent({ name: 'VisionPhotoPreparation' });
    preparation.vm.$emit('execution-linked', { factoryId: 'FB', projectId: source.project_id, executionProjectId: execution.project_id }); await flushPromises();
    preparation.vm.$emit('execution-linked', { factoryId: 'FA', projectId: 'old-source', executionProjectId: execution.project_id }); await flushPromises();
    expect(wrapper.find('[data-testid="vision-linked-execution"]').exists()).toBe(false);
    preparation.vm.$emit('execution-linked', { factoryId: 'FA', projectId: source.project_id, executionProjectId: execution.project_id }); await flushPromises();
    expect(wrapper.find('[data-testid="vision-linked-execution"]').exists()).toBe(true);
    expect(wrapper.find('.execution-details').exists()).toBe(false);
    expect(api.getVisionDraft).toHaveBeenCalledTimes(1);
    vi.mocked(api.getVisionDraft).mockResolvedValue(execution);
    vi.mocked(api.getVisionPlan).mockResolvedValue({ ...plan(), project_id: execution.project_id });
    await click(wrapper, '查看本次学习进度');
    expect(api.getVisionDraft).toHaveBeenLastCalledWith('FA', execution.project_id);
    expect(api.getVisionPlan).toHaveBeenLastCalledWith('FA', execution.project_id);
    expect(api.listVisionJobs).toHaveBeenLastCalledWith('FA', execution.project_id);
    expect(wrapper.findComponent({ name: 'VisionPhotoPreparation' }).exists()).toBe(false);
    expect(wrapper.findAllComponents({ name: 'ElOption' }).some(option => option.props('value') === execution.project_id)).toBe(true);
    expect(api.enqueueVisionStage).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('identifies frozen preparation execution records without opening another photo workflow', async () => {
    const execution = draft(); execution.project_id = 'execution-a'; execution.spec.data_version = 'prep-' + 'b'.repeat(64);
    const source = draft(); source.project_id = 'source-a';
    vi.mocked(api.listVisionDrafts).mockResolvedValue([source, execution]);
    vi.mocked(api.getVisionDraft).mockResolvedValue(execution);
    vi.mocked(api.getVisionPlan).mockResolvedValue({ ...plan(), project_id: execution.project_id });
    const wrapper = await mount();
    expect(wrapper.findAllComponents({ name: 'ElOption' }).some(option => String(option.props('label')).startsWith('学习执行记录'))).toBe(true);
    const picker = wrapper.findComponent('[data-testid="vision-saved-project"]');
    picker.vm.$emit('update:modelValue', execution.project_id); picker.vm.$emit('change', execution.project_id); await flushPromises();
    expect(wrapper.findComponent({ name: 'VisionPhotoPreparation' }).exists()).toBe(false);
    expect(wrapper.text()).toContain('照片审核与合格要求请回到原检查查看');
    expect(wrapper.get('.execution-details').attributes('open')).toBeDefined();
    expect(wrapper.find('[data-testid="vision-next-action"]').exists()).toBe(false);
    expect(wrapper.find('[data-stage="collect"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('loads current factory truth without automatically saving or training', async () => {
    const wrapper = await mount();
    expect(api.getVisionTemplates).toHaveBeenCalledWith('FA');
    expect(api.previewVisionTraining).not.toHaveBeenCalled();
    expect(api.saveVisionDraft).not.toHaveBeenCalled();
    expect(api.enqueueVisionStage).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('requires explicit preview and save, retains the registered recipe on readback', async () => {
    const wrapper = await mount(); await fill(wrapper); await click(wrapper, '下一步');
    expect(api.previewVisionTraining).toHaveBeenCalledWith('FA', draft().spec, 'pack');
    expect(api.saveVisionDraft).not.toHaveBeenCalled();
    await click(wrapper, '保存检查任务');
    expect(api.saveVisionDraft).toHaveBeenCalledOnce();
    expect(wrapper.find('[data-testid="vision-technical-details"]').exists()).toBe(false);
    await click(wrapper, '系统记录');
    expect(wrapper.findComponent('[data-testid="vision-recipe"]').props('modelValue')).toBe('recipe-a');
    expect(wrapper.find('[data-testid="vision-project"]').exists()).toBe(true);
    expect(api.enqueueVisionStage).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('invalidates a reviewed preview after editing its specification', async () => {
    const wrapper = await mount(); await fill(wrapper); await click(wrapper, '下一步');
    expect(wrapper.find('[data-testid="vision-preview"]').exists()).toBe(true);
    await click(wrapper, '返回上一步'); await field(wrapper, 'workstation', '二号包装线');
    expect(wrapper.find('[data-testid="vision-preview"]').exists()).toBe(false);
    expect(api.saveVisionDraft).not.toHaveBeenCalled();
    await click(wrapper, '下一步');
    expect(api.previewVisionTraining).toHaveBeenCalledTimes(2);
    expect(api.previewVisionTraining).toHaveBeenLastCalledWith('FA', expect.objectContaining({ workstation_id: '二号包装线' }), 'pack');
    wrapper.unmount();
  });

  it('discards a previous factory response arriving after factory selection changes', async () => {
    let finish!: (value: VisionTemplateCatalog) => void;
    vi.mocked(api.getVisionTemplates).mockImplementation(factory => factory === 'FA' ? new Promise(resolve => { finish = resolve; }) : Promise.resolve(catalog(factory)));
    const wrapper = shallowMount(Setup, { props: { factoryId: 'FA' }, global: { plugins: [ElementPlus], stubs: tableStubs, renderStubDefaultSlot: true } });
    await wrapper.setProps({ factoryId: 'FB' }); await flushPromises();
    finish(catalog('FA')); await flushPromises();
    await fill(wrapper); await click(wrapper, '下一步');
    expect(api.previewVisionTraining).toHaveBeenCalledWith('FB', expect.objectContaining({ factory_id: 'FB' }), 'pack');
    expect(wrapper.text()).not.toContain('读回的视觉项目范围与当前工厂不一致');
    wrapper.unmount();
  });

  it('never substitutes an empty catalog for a service error', async () => {
    vi.mocked(api.getVisionTemplates).mockRejectedValue(new Error('训练服务暂不可用'));
    const wrapper = await mount();
    expect(wrapper.findComponent({ name: 'ElAlert' }).props('title')).toContain('训练服务暂不可用');
    expect(wrapper.find('[data-testid="vision-template"]').exists()).toBe(false);
    expect(api.saveVisionDraft).not.toHaveBeenCalled(); wrapper.unmount();
  });

  it('submits only a stage and the registered recipe, without browser hashes or paths', async () => {
    vi.mocked(api.enqueueVisionStage).mockResolvedValue({ job_id: 'job-a', identity: { factory_id: 'FA', project_id: 'vision-a' }, stage: 'collect', status: 'queued', updated_at: '2026-10-15T10:00:00Z' });
    const wrapper = await mount(); await fill(wrapper); await click(wrapper, '下一步'); await click(wrapper, '保存检查任务');
    await click(wrapper, '开始收集照片');
    expect(api.enqueueVisionStage).toHaveBeenCalledWith('FA', 'vision-a', 'collect', 'recipe-a');
    wrapper.unmount();
  });

  it('does not execute an existing draft when registered input versions have changed', async () => {
    vi.mocked(api.getVisionTemplates).mockImplementation(async factory => {
      const value = catalog(factory); value.recipes[0]!.human_standard_version = 'h2'; return value;
    });
    const wrapper = await mount(); await fill(wrapper); await click(wrapper, '下一步'); await click(wrapper, '保存检查任务');
    const collect = wrapper.findComponent('[data-testid="vision-next-stage"]');
    expect(collect?.props('disabled')).toBe(true);
    expect(api.enqueueVisionStage).not.toHaveBeenCalled(); wrapper.unmount();
  });

  it('keeps dataset and train unavailable when the recipe only registers collect', async () => {
    vi.mocked(api.getVisionTemplates).mockImplementation(async factory => {
      const value = catalog(factory);
      value.recipes[0]!.stage_input_hashes = { collect: 'collect-hash' };
      return value;
    });
    const readyPlan = plan();
    readyPlan.items = readyPlan.items.map(item =>
      item.stage === 'collect' || item.stage === 'dataset' ? { ...item, status: 'succeeded' } : item,
    );
    vi.mocked(api.getVisionPlan).mockResolvedValue(readyPlan);
    const wrapper = await mount(); await fill(wrapper); await click(wrapper, '下一步'); await click(wrapper, '保存检查任务');
    const next = wrapper.findComponent('[data-testid="vision-next-stage"]');
    expect(next.text()).toBe('开始学习');
    expect(next.props('disabled')).toBe(true);
    await next.vm.$emit('click'); await flushPromises();
    expect(api.enqueueVisionStage).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('clears previous execution evidence when a saved project refresh fails', async () => {
    const wrapper = await mount(); await fill(wrapper); await click(wrapper, '下一步'); await click(wrapper, '保存检查任务');
    expect(wrapper.find('[data-testid="vision-project"]').exists()).toBe(true);
    vi.mocked(api.getVisionPlan).mockRejectedValueOnce(new Error('无法读取执行记录'));
    await click(wrapper, '更新进度');
    expect(wrapper.find('[data-testid="vision-project"]').exists()).toBe(false);
    expect(wrapper.findAllComponents({ name: 'ElAlert' }).some(node => node.props('title') === '无法读取执行记录')).toBe(true);
    expect(wrapper.find('[data-testid="vision-step-purpose"]').exists()).toBe(false);
    expect(wrapper.findAllComponents({ name: 'ElButton' }).some(node => node.text().includes('重新读取这项检查'))).toBe(true);
    expect(api.enqueueVisionStage).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('needs only business fields and generates proposal references without claiming data readiness', async () => {
    vi.mocked(api.getVisionTemplates).mockImplementation(async factory => ({ ...catalog(factory), recipes: [] }));
    const wrapper = await mount(); await fill(wrapper, null);
    expect(wrapper.find('[data-testid="vision-data_version"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="vision-recipe"]').exists()).toBe(false);
    await click(wrapper, '下一步');
    const payload = vi.mocked(api.previewVisionTraining).mock.calls[0]![1];
    expect(payload).toMatchObject({ factory_id: 'FA', workstation_id: '一号包装线', product_scope: ['产品A'], packaging_scope: ['盒装'], recipe_id: null });
    for (const key of ['data_version', 'recipe_version', 'human_standard_version', 'independent_acceptance_version'] as const) {
      expect(payload[key].length).toBeGreaterThan(0);
    }
    expect(wrapper.text()).toContain('由质量负责人确认');
    expect(api.saveVisionDraft).not.toHaveBeenCalled();
    expect(api.enqueueVisionStage).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('does not silently select between two registered factory configurations', async () => {
    vi.mocked(api.getVisionTemplates).mockImplementation(async factory => {
      const value = catalog(factory); value.recipes.push({ ...value.recipes[0]!, recipe_id: 'recipe-b' }); return value;
    });
    const wrapper = await mount(); await fill(wrapper, null); await click(wrapper, '下一步');
    expect(api.previewVisionTraining).toHaveBeenCalledWith('FA', expect.objectContaining({ recipe_id: null }), 'pack');
    expect(api.enqueueVisionStage).not.toHaveBeenCalled(); wrapper.unmount();
  });

  it('keeps a typed separator and blocks duplicate product names before preview', async () => {
    const wrapper = await mount(); await fill(wrapper);
    await field(wrapper, 'products', '猪蹄、');
    expect(wrapper.findComponent('[data-testid="vision-products"]').props('modelValue')).toBe('猪蹄、');
    await field(wrapper, 'products', '猪蹄、猪蹄');
    const next = wrapper.findAllComponents({ name: 'ElButton' }).find(node => node.text() === '下一步');
    expect(next?.props('disabled')).toBe(true);
    await click(wrapper, '下一步'); expect(api.previewVisionTraining).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('同一名称不要重复');
    await field(wrapper, 'products', '猪蹄、鸭掌'); await click(wrapper, '下一步');
    expect(api.previewVisionTraining).toHaveBeenCalledWith('FA', expect.objectContaining({ product_scope: ['猪蹄', '鸭掌'] }), 'pack');
    wrapper.unmount();
  });

  it('translates the daily view but retains exact raw values and blockers behind system records', async () => {
    const saved = draft(); saved.spec.workstation_id = 'VISION-ENTRY-WS2'; saved.spec.product_scope = ['synthetic-label'];
    saved.spec.packaging_scope = ['tray'];
    vi.mocked(api.getVisionDraft).mockResolvedValue(saved);
    vi.mocked(api.getVisionPlan).mockResolvedValue({ ...plan(), blockers: ['no asset receipts supplied', 'model enablement is blocked until independent acceptance passes'] });
    const wrapper = await mount(); await fill(wrapper); await click(wrapper, '下一步'); await click(wrapper, '保存检查任务');
    expect(wrapper.text()).not.toMatch(/VISION-ENTRY-WS2|synthetic-label|no asset receipts|model enablement/);
    expect(wrapper.text()).toContain('还缺照片');
    expect(wrapper.text()).toContain('本厂新照片试用还没通过');
    await click(wrapper, '系统记录');
    expect(wrapper.text()).toContain('VISION-ENTRY-WS2'); expect(wrapper.text()).toContain('synthetic-label');
    expect(wrapper.text()).toContain('no asset receipts supplied');
    expect(wrapper.findComponent('[data-testid="vision-data_version"]').props('modelValue')).toBe('d1');
    expect(api.enqueueVisionStage).not.toHaveBeenCalled(); wrapper.unmount();
  });

  it('retains the reviewed proposal after a failed save and shows a readable recovery', async () => {
    vi.mocked(api.saveVisionDraft).mockRejectedValueOnce(new Error('temporary upstream failure'));
    const wrapper = await mount(); await fill(wrapper); await click(wrapper, '下一步'); await click(wrapper, '保存检查任务');
    expect(wrapper.find('[data-testid="vision-preview"]').text()).toContain('一号包装线');
    expect(wrapper.findAllComponents({ name: 'ElAlert' }).some(node => node.props('title').includes('已保留填写内容'))).toBe(true);
    expect(wrapper.findAllComponents({ name: 'ElButton' }).find(node => node.text() === '保存检查任务')?.props('disabled')).toBe(false);
    expect(api.enqueueVisionStage).not.toHaveBeenCalled(); wrapper.unmount();
  });

  it('never queues a second copy while a stage is waiting for the local worker', async () => {
    vi.mocked(api.listVisionJobs).mockResolvedValue([{ job_id: 'job-a', identity: { factory_id: 'FA', project_id: 'vision-a' }, stage: 'collect', status: 'queued', updated_at: '2026-10-15T10:00:00Z' }]);
    const wrapper = await mount(); await fill(wrapper); await click(wrapper, '下一步'); await click(wrapper, '保存检查任务');
    expect(wrapper.findComponent('[data-testid="vision-next-stage"]').props('disabled')).toBe(true);
    expect(wrapper.text()).toContain('请稍后点击“更新进度”');
    await click(wrapper, '开始收集照片'); expect(api.enqueueVisionStage).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('starts a fresh proposal instead of inheriting an old project and its evidence', async () => {
    const wrapper = await mount(); await fill(wrapper); await click(wrapper, '下一步'); await click(wrapper, '保存检查任务');
    await click(wrapper, '新增检查');
    expect(wrapper.find('[data-testid="vision-step-purpose"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="vision-project"]').exists()).toBe(false);
    await field(wrapper, 'template', 'pack'); await click(wrapper, '下一步');
    expect(wrapper.findComponent('[data-testid="vision-workstation"]').props('modelValue')).toBe('');
    expect(wrapper.findComponent('[data-testid="vision-products"]').props('modelValue')).toBe('');
    expect(api.enqueueVisionStage).not.toHaveBeenCalled(); wrapper.unmount();
  });
});
