// web-admin/src/views/sales/orders/components/__tests__/OrderItemsEditor.spec.ts
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import OrderItemsEditor from '../OrderItemsEditor.vue';

const products = [
  { id: 'P1', name: '卤牛腱', unit: 'kg' },
  { id: 'P2', name: '卤猪蹄', unit: '份' },
];
const stubs = {
  'el-table': { template: '<table><slot/></table>' },
  'el-table-column': { template: '<td><slot :row="$attrs.row"/></td>' },
  'el-select': { props: ['modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value); $emit(\'change\',$event.target.value)"><slot/></select>' },
  'el-option': { props: ['value','label'], template: '<option :value="value">{{label}}</option>' },
  'el-input-number': { props: ['modelValue'], template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))"/>' },
  'el-input': { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\',$event.target.value)"/>' },
  'el-button': { template: '<button @click="$emit(\'click\')"><slot/></button>' },
};

// 🔒 2026-09-02 含税单价/未税展示的验收需要真实的 row + $index 传导 (上面那份 `stubs`
// 里的 el-table 根本不迭代 `:data`, 每列拿到的 row/$index 恒为 undefined, 测不出
// "只在有税率时渲染未税" 这条行为)。这份 stub 用 provide/inject 让 el-table-column
// 的默认插槽真的拿到当前行数据 + 下标, 参照 cell-audit.spec.ts 的既有写法。
const rowAwareStubs = {
  ...stubs,
  'el-table': {
    props: ['data'],
    template: `
      <table>
        <tbody>
          <fake-row v-for="(row, idx) in (data || [])" :key="idx" :row="row" :idx="idx">
            <slot />
          </fake-row>
        </tbody>
      </table>
    `,
    components: {
      'fake-row': {
        props: ['row', 'idx'],
        provide() {
          return {
            currentRow: () => (this as unknown as { row: unknown }).row,
            currentIndex: () => (this as unknown as { idx: number }).idx,
          };
        },
        template: '<tr class="item-row"><slot /></tr>',
      },
    },
  },
  'el-table-column': {
    inject: {
      currentRow: { default: () => () => ({}) },
      currentIndex: { default: () => () => undefined },
    },
    computed: {
      rowSlot(): Record<string, unknown> {
        return (this as unknown as { currentRow: () => Record<string, unknown> }).currentRow();
      },
      indexSlot(): number | undefined {
        return (this as unknown as { currentIndex: () => number | undefined }).currentIndex();
      },
    },
    template: '<td class="item-cell"><slot :row="rowSlot" :$index="indexSlot" /></td>',
  },
};

describe('OrderItemsEditor', () => {
  it('渲染传入的行 + 增行', async () => {
    const wrapper = mount(OrderItemsEditor, {
      props: { items: [{ productTypeId: 'P1', quantity: 10, unit: 'kg', unitPrice: 5, taxRate: 13 }], products },
      global: { stubs },
    });
    await wrapper.find('button').trigger('click');
    const emitted = wrapper.emitted('update:items');
    expect(emitted).toBeTruthy();
    const last = emitted![emitted!.length - 1][0] as unknown[];
    expect(last.length).toBe(2);
  });

  it('产品为空 → 渲染默认插槽内容', () => {
    const wrapper = mount(OrderItemsEditor, {
      props: { items: [], products: [] },
      slots: { 'empty-products': '<div class="np">无产品引导</div>' },
      global: { stubs },
    });
    expect(wrapper.find('.np').exists()).toBe(true);
  });
  it('supports searching product options by code and category label', () => {
    const wrapper = mount(OrderItemsEditor, {
      props: {
        items: [{ productTypeId: '', quantity: 0, unit: 'kg', unitPrice: 0, taxRate: 13 }],
        products: [
          { id: 'P1', code: 'YL0001', name: 'Raw beef', unit: 'kg', productCategory: 'RAW_MATERIAL' },
          { id: 'P2', code: 'KH0001', name: 'Customer supplied pork', unit: 'kg', productCategory: 'CUSTOMER_MATERIAL' },
        ],
      },
      global: { stubs },
    });

    const vm = wrapper.vm as unknown as {
      productOptionLabel: (product: Record<string, unknown>) => string;
    };
    const rawLabel = vm.productOptionLabel({ code: 'YL0001', name: 'Raw beef', productCategory: 'RAW_MATERIAL' });
    const customerMaterialLabel = vm.productOptionLabel({
      code: 'KH0001',
      name: 'Customer supplied pork',
      productCategory: 'CUSTOMER_MATERIAL',
    });

    expect(rawLabel).toContain('YL0001');
    expect(rawLabel).toContain('原料');
    expect(customerMaterialLabel).toContain('KH0001');
    expect(customerMaterialLabel).toContain('客供料');
  });

  // 🔒 2026-09-02 Owner 裁定: 含税单价是录入项, 未税单价由它与税率实时反算、只作展示。
  describe('tax-inclusive entry derives the untaxed price (owner ruling 2026-09-02)', () => {
    it('renders the arithmetically correct untaxed price only for the row that has a tax rate — never for the row that lacks one', () => {
      const wrapper = mount(OrderItemsEditor, {
        props: {
          items: [
            // row 0: 含税 113 + 税率 13% → 未税必须是 100, 且必须真的渲染出来 (正对照)。
            { productTypeId: 'P1', quantity: 1, unit: 'kg', unitPrice: 100, taxIncludedUnitPrice: 113, taxRate: 13 },
            // row 1: 税率未设 → 未税「算不出来」, 必须不渲染 (负对照, 与上面同一次挂载配对)。
            { productTypeId: 'P2', quantity: 1, unit: '份', unitPrice: undefined, taxIncludedUnitPrice: 100, taxRate: undefined },
          ],
          products,
        },
        global: { stubs: rowAwareStubs },
      });

      const dataRows = wrapper.findAll('tr.item-row');
      expect(dataRows.length).toBe(2);

      // 正对照: 有税率的那一行, 未税展示必须出现且数值正确 (113 / 1.13 = 100)。
      const derivedInRow0 = dataRows[0].find('.derived-untaxed-price');
      expect(derivedInRow0.exists()).toBe(true);
      expect(derivedInRow0.text()).toContain('100');

      // 负对照: 没有税率的那一行, 未税展示必须整体不渲染 —— 不是 0, 不是空字符串。
      expect(dataRows[1].find('.derived-untaxed-price').exists()).toBe(false);
    });

    it('emits the exact converted untaxed unit price when the tax-inclusive price is edited', () => {
      const wrapper = mount(OrderItemsEditor, {
        props: {
          items: [{ productTypeId: 'P1', quantity: 1, unit: 'kg', unitPrice: 100, taxIncludedUnitPrice: 113, taxRate: 13 }],
          products,
        },
        global: { stubs: rowAwareStubs },
      });
      const vm = wrapper.vm as unknown as {
        patchTaxIncludedPrice: (idx: number, val: number) => void;
        patchTaxRate: (idx: number, val: number) => void;
        derivedUntaxedUnitPrice: (row: Record<string, unknown>) => number | null;
      };

      // 换一个税点重算含税价 226 → 未税必须是 200 (226 / 1.13), 不是 226, 不是 0。
      vm.patchTaxIncludedPrice(0, 226);
      let emitted = wrapper.emitted('update:items')!;
      let lastRow = (emitted[emitted.length - 1][0] as Array<Record<string, unknown>>)[0];
      expect(lastRow.taxIncludedUnitPrice).toBe(226);
      expect(lastRow.unitPrice).toBe(200);
      expect(vm.derivedUntaxedUnitPrice(lastRow)).toBe(200);

      // 税率被清空 (用户手动清掉输入框) → unitPrice 必须变成「未知」而不是残留旧值/0。
      vm.patchTaxRate(0, undefined as unknown as number);
      emitted = wrapper.emitted('update:items')!;
      lastRow = (emitted[emitted.length - 1][0] as Array<Record<string, unknown>>)[0];
      expect(lastRow.unitPrice).toBeUndefined();
      expect(vm.derivedUntaxedUnitPrice(lastRow)).toBeNull();
    });
  });
});
