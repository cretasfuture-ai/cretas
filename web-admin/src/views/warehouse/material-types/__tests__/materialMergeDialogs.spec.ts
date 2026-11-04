import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h as vh } from 'vue';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus, { ElSelect } from 'element-plus';
import MaterialMergeDialog from '../MaterialMergeDialog.vue';
import MaterialAliasDialog from '../MaterialAliasDialog.vue';
import type { MaterialMergeCheck } from '@/api/materialAliasMerge';

const h = vi.hoisted(() => ({
  get: vi.fn(),
  push: vi.fn(),
  checkMaterialMerge: vi.fn(),
  mergeMaterial: vi.fn(),
  listMaterialAliases: vi.fn(),
  addMaterialAlias: vi.fn(),
  deleteMaterialAlias: vi.fn(),
  success: vi.fn(),
}));

vi.mock('@/api/request', () => ({ get: h.get }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: h.push }) }));
vi.mock('@/api/materialAliasMerge', () => ({
  checkMaterialMerge: h.checkMaterialMerge,
  mergeMaterial: h.mergeMaterial,
  listMaterialAliases: h.listMaterialAliases,
  addMaterialAlias: h.addMaterialAlias,
  deleteMaterialAlias: h.deleteMaterialAlias,
}));
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>();
  return { ...actual, ElMessage: Object.assign(vi.fn(), { success: h.success, error: vi.fn() }) };
});

const material = { id: 'm1', code: 'RM-001', name: '海天酱油', unit: 'kg', category: '调味料' };
const master = { id: 'm2', code: 'RM-002', name: '生抽', unit: 'kg', category: '调味料' };
const brief = (m: typeof master) => ({ id: m.id, code: m.code, name: m.name, unit: m.unit, mainCategory: m.category });

function checkResult(over: Partial<MaterialMergeCheck> = {}): MaterialMergeCheck {
  return {
    canMerge: true,
    duplicate: brief(material),
    master: brief(master),
    blockers: [],
    replacements: [],
    warnings: [],
    aliasesToAdd: ['海天酱油'],
    ...over,
  };
}

// el-dialog 会 teleport 到 body, 这里换成原地渲染的桩, 便于在 wrapper 内查找
const DialogStub = defineComponent({
  props: { modelValue: Boolean, title: { type: String, default: '' } },
  setup(props, { slots }) {
    return () => (props.modelValue
      ? vh('div', { class: 'dialog-stub' }, [vh('h2', props.title), slots.default?.(), slots.footer?.()])
      : null);
  },
});
const globalOpts = { plugins: [ElementPlus], stubs: { ElDialog: DialogStub } };

const wrappers: VueWrapper[] = [];
async function mountMerge() {
  const w = mount(MaterialMergeDialog, {
    props: { modelValue: true, factoryId: 'F1', material },
    global: globalOpts,
  });
  wrappers.push(w);
  await flushPromises();
  return w;
}
async function chooseMaster(w: VueWrapper) {
  const select = w.findAllComponents(ElSelect).find((s) => s.attributes('data-test') === 'merge-master')!;
  select.vm.$emit('update:modelValue', 'm2');
  select.vm.$emit('change', 'm2');
  await flushPromises();
}
async function chooseReason(w: VueWrapper, reason: string) {
  const select = w.findAllComponents(ElSelect).find((s) => s.attributes('data-test') === 'merge-reason')!;
  select.vm.$emit('update:modelValue', reason);
  await flushPromises();
}
const confirmBtn = (w: VueWrapper) => w.find('[data-test="merge-confirm"]');

beforeEach(() => {
  Object.values(h).forEach((f) => f.mockReset());
  h.get.mockResolvedValue({ success: true, data: [material, master] });
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
});

describe('合并重复物料弹窗', () => {
  it('标题带名称与料号, 候选里排除自己', async () => {
    const w = await mountMerge();
    expect(w.text()).toContain('合并重复物料 — 海天酱油（RM-001）');
  });

  it('拦截项: 显示 message / items / hint, 给去处链接, 确认按钮禁用', async () => {
    h.checkMaterialMerge.mockResolvedValue({
      success: true,
      data: checkResult({
        canMerge: false,
        blockers: [
          { code: 'BOM', message: '被 2 个在用配方引用', items: ['卤猪蹄 v3'], hint: '先到产品工艺里把配方改成主物料' },
          { code: 'CATEGORY', message: '类别不一致', items: [], hint: '请选同类别物料' },
        ],
      }),
    });
    const w = await mountMerge();
    await chooseMaster(w);
    await chooseReason(w, '重复录入');

    expect(h.checkMaterialMerge).toHaveBeenCalledWith('F1', 'm1', 'm2');
    const bom = w.find('[data-test="blocker-BOM"]');
    expect(bom.text()).toContain('被 2 个在用配方引用');
    expect(bom.text()).toContain('卤猪蹄 v3');
    expect(bom.text()).toContain('先到产品工艺里把配方改成主物料');
    expect(w.find('[data-test="blocker-link-CATEGORY"]').exists()).toBe(false);
    await w.find('[data-test="blocker-link-BOM"]').trigger('click');
    expect(h.push).toHaveBeenCalledWith('/system/product-processes');
    expect(confirmBtn(w).attributes('disabled')).toBeDefined();
  });

  it('canMerge=true: 确认可点, 调 merge 带 masterId+reason, 成功提示并通知刷新列表', async () => {
    h.checkMaterialMerge.mockResolvedValue({
      success: true,
      data: checkResult({ warnings: ['主物料单位不同'] }),
    });
    h.mergeMaterial.mockResolvedValue({ success: true, data: { duplicateId: 'm1', masterId: 'm2', aliasesAdded: ['海天酱油'] } });
    const w = await mountMerge();
    await chooseMaster(w);
    expect(w.find('[data-test="merge-warning"]').text()).toContain('主物料单位不同');
    expect(w.find('[data-test="merge-aliases"]').text()).toContain('海天酱油');
    // 原因没选之前不能确认
    expect(confirmBtn(w).attributes('disabled')).toBeDefined();
    await chooseReason(w, '同类不同品牌整合');
    expect(confirmBtn(w).attributes('disabled')).toBeUndefined();

    await confirmBtn(w).trigger('click');
    await flushPromises();
    expect(h.mergeMaterial).toHaveBeenCalledTimes(1);
    expect(h.mergeMaterial).toHaveBeenCalledWith('F1', 'm1', 'm2', '同类不同品牌整合');
    expect(h.success).toHaveBeenCalledWith('已合并，「海天酱油」已成为「生抽」的别名');
    expect(w.emitted('merged')).toHaveLength(1);
  });

  it('会自动替换的引用合并前列出来; 合并后要人核对的结果用不自动消失的提示显示', async () => {
    h.checkMaterialMerge.mockResolvedValue({
      success: true,
      data: checkResult({
        replacements: [
          { code: 'WORKFLOW', message: '2 张草稿工艺图', items: ['卤牛肉', '酱鸭'] },
          { code: 'SUPPLIER', message: '1 家供应商的供货关系', items: ['张记调料'] },
        ],
      }),
    });
    h.mergeMaterial.mockResolvedValue({
      success: true,
      data: {
        duplicateId: 'm1',
        masterId: 'm2',
        aliasesAdded: [],
        replaced: { draftWorkflows: 2, suppliers: 1, purchaseOrderItems: 0, notes: ['「张记调料」已在供主物料，沿用主物料的价格，旧的供货关系已停用'] },
      },
    });
    const w = await mountMerge();
    await chooseMaster(w);
    const block = w.find('[data-test="merge-replacements"]');
    expect(block.text()).toContain('合并时会自动换成主物料「生抽」');
    expect(w.find('[data-test="replacement-WORKFLOW"]').text()).toContain('2 张草稿工艺图：卤牛肉、酱鸭');
    expect(w.find('[data-test="replacement-SUPPLIER"]').text()).toContain('张记调料');

    await chooseReason(w, '重复录入');
    await confirmBtn(w).trigger('click');
    await flushPromises();
    const { ElMessage } = await import('element-plus');
    expect(ElMessage).toHaveBeenCalledWith({
      message: '「张记调料」已在供主物料，沿用主物料的价格，旧的供货关系已停用',
      type: 'warning',
      duration: 0,
      showClose: true,
    });
  });

  it('没有要替换的引用时不显示这一块', async () => {
    h.checkMaterialMerge.mockResolvedValue({ success: true, data: checkResult() });
    const w = await mountMerge();
    await chooseMaster(w);
    expect(w.find('[data-test="merge-replacements"]').exists()).toBe(false);
  });

  it('原因选「其他」必须填说明, 填了才可确认且以说明为 reason', async () => {
    h.checkMaterialMerge.mockResolvedValue({ success: true, data: checkResult() });
    h.mergeMaterial.mockResolvedValue({ success: true, data: { duplicateId: 'm1', masterId: 'm2', aliasesAdded: [] } });
    const w = await mountMerge();
    await chooseMaster(w);
    await chooseReason(w, '其他');
    expect(w.find('[data-test="merge-reason-text"]').exists()).toBe(true);
    expect(confirmBtn(w).attributes('disabled')).toBeDefined();

    await w.find('[data-test="merge-reason-text"]').setValue('客户改了叫法');
    expect(confirmBtn(w).attributes('disabled')).toBeUndefined();
    await confirmBtn(w).trigger('click');
    await flushPromises();
    expect(h.mergeMaterial).toHaveBeenCalledWith('F1', 'm1', 'm2', '客户改了叫法');
  });

  it('merge-check 本身报错(如主物料已合并): 原样显示 message, 确认禁用', async () => {
    h.checkMaterialMerge.mockRejectedValue(new Error('主物料「生抽」已合并到别的物料'));
    const w = await mountMerge();
    await chooseMaster(w);
    await chooseReason(w, '重复录入');
    expect(w.find('[data-test="merge-error"]').text()).toContain('主物料「生抽」已合并到别的物料');
    expect(confirmBtn(w).attributes('disabled')).toBeDefined();
  });
});

describe('别名弹窗', () => {
  async function mountAlias() {
    h.listMaterialAliases.mockResolvedValue({ success: true, data: [{ id: 'a1', alias: '生抽', source: 'MANUAL' }] });
    const w = mount(MaterialAliasDialog, {
      props: { modelValue: true, factoryId: 'F1', material },
      global: globalOpts,
    });
    wrappers.push(w);
    await flushPromises();
    return w;
  }

  it('列出别名并显示提示文案', async () => {
    const w = await mountAlias();
    expect(w.text()).toContain('生抽');
    expect(w.text()).toContain('配方单、供应商单据上的叫法都可以加成别名，搜索时能直接找到这个物料');
  });

  it('添加失败: 后端 message 原样显示', async () => {
    const w = await mountAlias();
    h.addMaterialAlias.mockRejectedValue(new Error('「老抽」已是物料「酱油B」的别名'));
    await w.find('[data-test="alias-input"]').setValue('老抽');
    await w.find('[data-test="alias-add"]').trigger('click');
    await flushPromises();
    expect(h.addMaterialAlias).toHaveBeenCalledWith('F1', 'm1', '老抽');
    expect(w.text()).toContain('「老抽」已是物料「酱油B」的别名');
  });

  it('添加成功: 重新加载并通知列表刷新; 删除调用 delete', async () => {
    const w = await mountAlias();
    h.addMaterialAlias.mockResolvedValue({ success: true, data: { id: 'a2', alias: '老抽', source: 'MANUAL' } });
    await w.find('[data-test="alias-input"]').setValue('老抽');
    await w.find('[data-test="alias-add"]').trigger('click');
    await flushPromises();
    expect(h.listMaterialAliases).toHaveBeenCalledTimes(2);
    expect(w.emitted('changed')).toHaveLength(1);

    h.deleteMaterialAlias.mockResolvedValue({ success: true, data: null });
    await w.find('[data-test="alias-delete-a1"]').trigger('click');
    await flushPromises();
    expect(h.deleteMaterialAlias).toHaveBeenCalledWith('F1', 'm1', 'a1');
  });
});
