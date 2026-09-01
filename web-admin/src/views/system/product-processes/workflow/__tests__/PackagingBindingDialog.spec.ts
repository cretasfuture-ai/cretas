import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import PackagingBindingDialog, { type PackagingMaterialOption } from '../PackagingBindingDialog.vue';
import type { BomItemSubstituteView, BomRecipeItemView } from '@/api/bom';

const addItem = vi.fn();
const updateItem = vi.fn();
const getProductPackagingSpecs = vi.fn();

vi.mock('@/api/bom', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/bom')>();
  return {
    ...original,
    bomRecipeApi: {
      ...original.bomRecipeApi,
      addItem: (...args: unknown[]) => addItem(...args),
      updateItem: (...args: unknown[]) => updateItem(...args),
      getProductPackagingSpecs: (...args: unknown[]) => getProductPackagingSpecs(...args),
    },
  };
});

const elMessage = vi.hoisted(() => ({ error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() }));
vi.mock('element-plus', async (importOriginal) => {
  const original = await importOriginal<typeof import('element-plus')>();
  return { ...original, ElMessage: elMessage };
});

const BOXED_MATERIAL: PackagingMaterialOption = {
  id: 'PK-BOX', name: '塑料桶 20L', category: '包材', primaryCode: 'BOX01', unit: 'pcs', quantityUnit: 'pcs',
  movingAvgPrice: 12,
};
const BAG_MATERIAL: PackagingMaterialOption = {
  id: 'PK-BAG', name: '内袋', category: '包材', primaryCode: 'BOX01', unit: 'pcs', quantityUnit: 'pcs',
  movingAvgPrice: 3,
};
const LABEL_MATERIAL_KG: PackagingMaterialOption = {
  id: 'PK-LABEL', name: '称重标签', category: '包材', primaryCode: 'BOX01', unit: 'kg', quantityUnit: 'kg',
  movingAvgPrice: 1.5,
};

function mountDialog(overrides: Record<string, unknown> = {}) {
  return mount(PackagingBindingDialog, {
    props: {
      modelValue: true,
      factoryId: 'F006',
      recipeId: 'R1',
      outputName: '鸭油',
      productTypeId: 'FG-1',
      baseUnit: 'kg',
      row: null,
      materials: [BOXED_MATERIAL, BAG_MATERIAL, LABEL_MATERIAL_KG],
      substituteRelations: [],
      ...overrides,
    },
    global: {
      plugins: [ElementPlus],
      stubs: {
        teleport: true,
        transition: false,
        // Element Plus 2.13 + jsdom can recursively update ElSelect while a
        // test emits v-model directly. These contract tests only need the
        // component boundary, not its popup implementation.
        ElSelect: {
          name: 'ElSelect',
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<div><slot /></div>',
        },
        ElOption: {
          name: 'ElOption',
          props: ['label', 'value'],
          template: '<div />',
        },
        // 🔴 2026-09-01: 外壳从 el-dialog 换成 el-drawer(owner 指定「应该就是侧边栏」,
        //    已确认原型里这两个编辑器是停靠式侧栏)。桩不跟着换, 内容整块渲染不出来,
        //    读数会长成「字段没了」而不是「桩错了」。
        ElDrawer: {
          props: ['modelValue', 'title'],
          template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>',
        },
      },
    },
  });
}

function selectMaterial(wrapper: ReturnType<typeof mountDialog>, materialId: string) {
  wrapper.findAllComponents({ name: 'ElSelect' })
    .find((component) => component.attributes('data-testid') === 'packaging-material-select')!
    .vm.$emit('update:modelValue', materialId);
}

function setQuantity(wrapper: ReturnType<typeof mountDialog>, value: number | null) {
  wrapper.findAllComponents({ name: 'ElInputNumber' })
    .find((component) => component.attributes('data-testid') === 'packaging-natural-quantity')!
    .vm.$emit('update:modelValue', value);
}

function selectOption(wrapper: ReturnType<typeof mountDialog>, testId: string, value: string | string[]) {
  wrapper.findAllComponents({ name: 'ElSelect' })
    .find((component) => component.attributes('data-testid') === testId)!
    .vm.$emit('update:modelValue', value);
}

async function clickSave(wrapper: ReturnType<typeof mountDialog>) {
  await wrapper.findAll('button').find((button) => button.text().includes('保存'))?.trigger('click');
  await flushPromises();
}

function substitute(overrides: Partial<BomItemSubstituteView>): BomItemSubstituteView {
  return {
    id: 'sub-1',
    recipeId: 'R1',
    parentKind: 'RECIPE_ITEM',
    parentRecipeItemId: 42,
    parentMaterialTypeId: 'PK-BOX',
    materialCategory: 'PACKAGING',
    substituteMaterialTypeId: 'PK-BAG',
    conversionFactor: 1,
    ...overrides,
  };
}

describe('包材编辑弹窗', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    addItem.mockResolvedValue({ success: true, data: { id: 99 } });
    updateItem.mockResolvedValue({ success: true, data: { id: 42 } });
    getProductPackagingSpecs.mockResolvedValue({
      success: true,
      data: [{
        id: 'SPEC-CASE-8', name: '外箱装', packageUnit: '箱', baseUnit: 'kg',
        conversionFactor: 8, defaultSpec: true, active: true, sortOrder: 0,
      }],
    });
  });

  it('用量输入框的分母来自传入的 baseUnit, 不是写死的「盒」', () => {
    // 按重量卖的副产品 baseUnit 是 kg, 写死会算错
    const wrapper = mountDialog({ baseUnit: 'kg' });
    expect(wrapper.text()).toContain('kg');
    expect(wrapper.text()).not.toContain('每 1 盒成品');
  });

  it('按成品包装规格把自然用量折算成基本单位标准用量', async () => {
    const wrapper = mountDialog();
    await flushPromises();
    selectMaterial(wrapper, 'PK-BOX');
    selectOption(wrapper, 'packaging-role-select', 'OUTER_CASE');
    selectOption(wrapper, 'packaging-spec-select', 'SPEC-CASE-8');
    setQuantity(wrapper, 1);
    await flushPromises();

    expect(wrapper.text()).toContain('每 1 箱（=8 kg）');
    await clickSave(wrapper);
    expect(addItem).toHaveBeenCalledWith('F006', 'R1', expect.objectContaining({
      packagingSpecId: 'SPEC-CASE-8',
      packagingRole: 'OUTER_CASE',
      naturalQuantity: 1,
      standardQuantity: 0.125,
    }));
  });

  it('用量为空或非正数时不允许保存', async () => {
    // 禁止降级: 不能默默存 0
    const wrapper = mountDialog();
    selectMaterial(wrapper, 'PK-BOX');
    await flushPromises();
    await clickSave(wrapper);
    expect(addItem).not.toHaveBeenCalled();
    expect(elMessage.warning).toHaveBeenCalled();

    setQuantity(wrapper, 0);
    await flushPromises();
    await clickSave(wrapper);
    expect(addItem).not.toHaveBeenCalled();

    setQuantity(wrapper, -1);
    await flushPromises();
    await clickSave(wrapper);
    expect(addItem).not.toHaveBeenCalled();
  });

  it('移动平均库存成本为正数时优先于采购参考价', async () => {
    const wrapper = mountDialog({
      materials: [{
        ...BOXED_MATERIAL,
        movingAvgPrice: 12,
        materialReferencePrice: 10,
      }],
    });
    selectMaterial(wrapper, 'PK-BOX');
    setQuantity(wrapper, 2);
    await flushPromises();

    expect(wrapper.get('[data-testid="packaging-automatic-price"]').text()).toContain('移动平均库存成本');
    await clickSave(wrapper);
    expect(addItem).toHaveBeenCalledWith('F006', 'R1', expect.objectContaining({ unitPrice: 12 }));
  });

  it('移动平均库存成本无效时回退到 materialReferencePrice', async () => {
    const wrapper = mountDialog({
      materials: [{
        ...BOXED_MATERIAL,
        movingAvgPrice: 0,
        materialReferencePrice: 10.5,
      }],
    });
    selectMaterial(wrapper, 'PK-BOX');
    setQuantity(wrapper, 2);
    await flushPromises();

    expect(wrapper.get('[data-testid="packaging-automatic-price"]').text()).toContain('未税采购参考价');
    await clickSave(wrapper);
    expect(addItem).toHaveBeenCalledWith('F006', 'R1', expect.objectContaining({ unitPrice: 10.5 }));
  });

  it('两种价格均缺失或非正数时明确阻止保存', async () => {
    const wrapper = mountDialog({
      materials: [{
        ...BOXED_MATERIAL,
        movingAvgPrice: -1,
        materialReferencePrice: null,
      }],
    });
    selectMaterial(wrapper, 'PK-BOX');
    setQuantity(wrapper, 2);
    await flushPromises();

    expect(wrapper.get('[data-testid="packaging-automatic-price"]')
      .findComponent({ name: 'ElInput' }).props('modelValue')).toBe('保存前需先配置有效价格');
    expect(wrapper.text()).toContain('暂不能保存');
    await clickSave(wrapper);
    expect(addItem).not.toHaveBeenCalled();
    expect(elMessage.warning).toHaveBeenCalledWith(expect.stringContaining('缺少有效'));
  });

  it('跨单位替代物料未填等价系数时不允许保存', async () => {
    // 系统不猜换算关系
    const wrapper = mountDialog();
    selectMaterial(wrapper, 'PK-BOX');
    setQuantity(wrapper, 2);
    await flushPromises();
    selectOption(wrapper, 'packaging-substitute-select', ['PK-LABEL']);
    await flushPromises();
    await clickSave(wrapper);
    expect(addItem).not.toHaveBeenCalled();
    expect(elMessage.warning).toHaveBeenCalled();

    wrapper.get('[data-testid="packaging-substitute-factor-PK-LABEL"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:modelValue', 3);
    await flushPromises();
    await clickSave(wrapper);
    expect(addItem).toHaveBeenCalledWith('F006', 'R1', expect.objectContaining({
      substitutes: [{ materialTypeId: 'PK-LABEL', conversionFactor: 3 }],
    }));
  });

  it('同单位替代默认 1:1 且只读', async () => {
    const wrapper = mountDialog();
    selectMaterial(wrapper, 'PK-BOX');
    setQuantity(wrapper, 2);
    await flushPromises();
    selectOption(wrapper, 'packaging-substitute-select', ['PK-BAG']);
    await flushPromises();
    expect(wrapper.find('[data-testid="packaging-substitute-factor-PK-BAG"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('同单位默认 1:1');

    await clickSave(wrapper);
    expect(addItem).toHaveBeenCalledWith('F006', 'R1', expect.objectContaining({
      substitutes: [{ materialTypeId: 'PK-BAG', conversionFactor: null }],
    }));
  });

  it('编辑既有包装层级行时回填自然用量并保留层级和角色', async () => {
    const row: Partial<BomRecipeItemView> = {
      id: 42,
      materialTypeId: 'PK-BOX',
      materialName: '塑料桶 20L',
      standardQuantity: 0.125,
      naturalQuantity: 1,
      packagingSpecId: 'SPEC-CASE-8',
      packagingRole: 'OUTER_CASE',
      packagingConversionFactorSnapshot: 8,
      unit: 'pcs',
      isOptional: false,
      remark: '备注文本',
    };
    const wrapper = mountDialog({ row });
    await flushPromises();
    expect(wrapper.get('[data-testid="packaging-natural-quantity"]')
      .findComponent({ name: 'ElInputNumber' }).props('modelValue')).toBe(1);
    await clickSave(wrapper);
    expect(updateItem).toHaveBeenCalledWith('F006', 42, expect.objectContaining({
      standardQuantity: 0.125,
      naturalQuantity: 1,
      packagingSpecId: 'SPEC-CASE-8',
      packagingRole: 'OUTER_CASE',
    }));
  });

  it('编辑已有行的替代物料关系由父组件按行过滤后传入并回填', async () => {
    const row: Partial<BomRecipeItemView> = {
      id: 42, materialTypeId: 'PK-BOX', materialName: '塑料桶 20L', standardQuantity: 5, unit: 'pcs',
    };
    const wrapper = mountDialog({ row, substituteRelations: [substitute({ conversionFactor: 1 })] });
    await flushPromises();
    expect(wrapper.text()).toContain('内袋');
  });

  it('保存失败时不 emit saved, 并把后端 message 原样显示', async () => {
    // 禁止降级: 不吞错误、不显示「操作失败」这种 generic 文案
    addItem.mockRejectedValue({ message: '包材档案已停用，无法引用' });
    const wrapper = mountDialog();
    selectMaterial(wrapper, 'PK-BOX');
    setQuantity(wrapper, 2);
    await flushPromises();
    await clickSave(wrapper);

    expect(wrapper.emitted('saved')).toBeUndefined();
    expect(elMessage.error).toHaveBeenCalledWith('包材档案已停用，无法引用');
  });
});
