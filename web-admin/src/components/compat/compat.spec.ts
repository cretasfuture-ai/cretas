import { defineComponent, h, nextTick, ref, type PropType } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import type { TableColumn, TableRow } from '@nuxt/ui/components/Table.vue';
import {
  CompatAlert, CompatBadge, CompatDatePicker, CompatDescriptions, CompatDescriptionsItem, CompatDialog,
  CompatForm, CompatFormItem, CompatOption, CompatPagination, CompatRadio, CompatSelect, CompatSkeleton, CompatTable, CompatTableColumn,
} from './index';
import { useCompatMessages } from './messages';
import CompatMessageDialog from './CompatMessageDialog.vue';

const nuxt = vi.hoisted(() => ({
  add: vi.fn(() => ({ id: 'test' })), remove: vi.fn(), close: vi.fn(),
  open: vi.fn(), create: vi.fn(),
}));
vi.mock('@nuxt/ui/composables/useToast', () => ({ useToast: () => nuxt }));
vi.mock('@nuxt/ui/composables/useOverlay', () => ({ useOverlay: () => nuxt }));

const slotsOnly = defineComponent({
  setup: (_, { slots }) => () => h('section', [slots.header?.(), slots.default?.(), slots.footer?.()]),
});
const input = defineComponent({
  props: { modelValue: [String, Number], type: String }, emits: ['update:modelValue'],
  setup: (props, { emit }) => () => h('input', {
    value: props.modelValue, type: props.type,
    onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value),
  }),
});
const button = defineComponent({ setup: (_, { slots }) => () => h('button', { type: 'button' }, slots.default?.()) });
const modal = defineComponent({
  props: { open: Boolean, title: String, content: Object }, emits: ['update:open', 'after:enter', 'after:leave'],
  setup: (props, { slots }) => () => props.open ? h('section', { role: 'dialog', 'aria-label': props.title }, slots.content?.()) : null,
});
const stubs = { UForm: slotsOnly, UFormField: defineComponent({
  props: { error: [String, Boolean], label: String },
  setup: (props, { slots }) => () => h('div', [slots.label?.() ?? props.label, slots.default?.(), props.error ? h('span', { role: 'alert' }, String(props.error)) : null]),
}), UModal: modal, UButton: button, UInput: input, UCard: slotsOnly };

describe('Element 到 Nuxt UI 的适配组件', () => {
  it('传递表格列、原始行和索引，并转发勾选和固定列', async () => {
    type Row = { id: string; item: { name: string } };
    const rows: Row[] = [{ id: 'one', item: { name: '番茄' } }, { id: 'two', item: { name: '豆角' } }];
    const tableStub = defineComponent({
      props: {
        data: { type: Array as PropType<Row[]>, required: true },
        columns: { type: Array as PropType<TableColumn<Row>[]>, required: true },
        getRowId: { type: Function as PropType<(row: Row, index: number) => string>, required: true },
      },
      setup: (props) => () => h('section', props.data.flatMap((item, index) => props.columns.map(column => {
        const row = { original: item, index, id: props.getRowId(item, index) } as TableRow<Row>;
        const value = 'accessorFn' in column ? column.accessorFn(item, index) : undefined;
        return h('div', typeof column.cell === 'function'
          ? [column.cell({ row, getValue: () => value } as never)] : String(value ?? ''));
      }))),
    });
    const checkbox = defineComponent({
      props: { modelValue: [Boolean, String] }, emits: ['update:modelValue'],
      setup: (props, { emit }) => () => h('button', { onClick: () => emit('update:modelValue', !props.modelValue) }, '勾选'),
    });
    const selected = vi.fn();
    const wrapper = mount(defineComponent({
      setup: () => () => h(CompatTable<Row>, { data: rows, rowKey: 'id', onSelectionChange: selected }, {
        default: () => [
          h(CompatTableColumn, { type: 'selection' }),
          h(CompatTableColumn, { type: 'index' }),
          h(CompatTableColumn, { prop: 'item.name', label: '物料', width: '120', fixed: 'left', formatter: (_row: Row, _column: unknown, value: string) => `名称 ${value}` }),
          h(CompatTableColumn, { fixed: 'right' }, { default: ({ row, $index }: { row: Row; $index: number }) => `${row.id}-${$index}` }),
        ],
      }),
    }), { global: { stubs: { UTable: tableStub, UCheckbox: checkbox } } });
    expect(wrapper.text()).toContain('1名称 番茄one-0');
    expect(wrapper.text()).toContain('2名称 豆角two-1');
    await wrapper.get('[aria-label="选择第 2 行"]').trigger('click');
    expect(selected).toHaveBeenLastCalledWith([rows[1]]);
    const surface = wrapper.findComponent({ name: 'CompatTableSurface' });
    expect(surface.props('columnPinning')).toEqual({ left: ['column-2'], right: ['column-3'] });
    expect(surface.props('columns')[2].meta?.style?.th).toEqual({ width: '120px', minWidth: '120px' });
  });

  it('按 prop 运行必填与异步规则，校验失败可见，resetFields 恢复字段快照', async () => {
    const model = ref({ detail: { name: '' } });
    const wrapper = mount(CompatForm, {
      props: { model: model.value, rules: { 'detail.name': { required: true, message: '请输入名称' } } },
      slots: { default: () => h(CompatFormItem, {
        prop: 'detail.name', label: '名称', rules: { validator: () => model.value.detail.name === '番茄' },
      }, { default: () => h('input') }) },
      global: { stubs },
    });
    await expect(wrapper.vm.validate()).rejects.toHaveProperty('detail.name');
    expect(wrapper.get('[role=alert]').text()).toBe('请输入名称');
    const field = wrapper.get('input').element.parentElement!;
    field.scrollIntoView = vi.fn();
    wrapper.vm.scrollToField('detail.name', { block: 'center' });
    expect(field.scrollIntoView).toHaveBeenCalledWith({ block: 'center' });
    model.value.detail.name = '番茄';
    const callback = vi.fn();
    await expect(wrapper.vm.validate(callback)).resolves.toBe(true);
    expect(callback).toHaveBeenCalledWith(true, undefined);
    wrapper.vm.resetFields();
    expect(model.value.detail.name).toBe('');
    await nextTick();
    expect(wrapper.find('[role=alert]').exists()).toBe(false);
  });

  it('对话框提供标题、内容和 footer，关闭由 before-close 决定', async () => {
    let proceed: (() => void) | undefined;
    const wrapper = mount(CompatDialog, {
      props: { modelValue: true, title: '创建批次', beforeClose: done => { proceed = done; } },
      slots: { default: '内容', footer: '提交区' }, global: { stubs },
    });
    expect(wrapper.get('[role=dialog]').attributes('aria-label')).toBe('创建批次');
    expect(wrapper.text()).toContain('内容提交区');
    await wrapper.get('[aria-label=关闭]').trigger('click');
    expect(wrapper.emitted('update:modelValue')).toBeUndefined();
    proceed?.();
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]]);
  });

  it('日期范围只在两端完整时发出 value-format 指定的字符串，清除时发出空值', async () => {
    const wrapper = mount(CompatDatePicker, {
      props: { type: 'daterange', valueFormat: 'YYYY/MM/DD', startPlaceholder: '开始', endPlaceholder: '结束' },
      global: { stubs },
    });
    await wrapper.get('[aria-label=开始]').setValue('2026-11-01');
    expect(wrapper.emitted('change')).toBeUndefined();
    await wrapper.get('[aria-label=结束]').setValue('2026-11-21');
    expect(wrapper.emitted('change')).toEqual([[['2026/11/01', '2026/11/21']]]);
    await wrapper.get('[aria-label=清除日期]').trigger('click');
    expect(wrapper.emitted('change')?.at(-1)).toEqual([null]);
    expect(wrapper.emitted('clear')).toHaveLength(1);
    const single = mount(CompatDatePicker, { props: { type: 'date' }, global: { stubs } });
    await single.get('input').setValue('2026-11-21');
    expect(single.emitted('change')).toEqual([[new Date(2026, 10, 21)]]);
  });

  it('详情保留标题、跨列和自定义标签、内容插槽', () => {
    const wrapper = mount(CompatDescriptions, {
      props: { title: '批次详情', column: 3, border: true },
      slots: { default: () => h(CompatDescriptionsItem, { label: '标识', span: 2 }, {
        label: () => '批次号', default: () => 'P-01',
      }) }, global: { stubs },
    });
    expect(wrapper.text()).toContain('批次详情');
    expect(wrapper.get('dt').text()).toBe('批次号');
    expect(wrapper.get('dd').text()).toBe('P-01');
    expect(wrapper.get('dl > div').attributes('style')).toContain('grid-column: span 2');
  });

  it('分页更新页和页大小，并支持 total 与 slot', async () => {
    const pagination = defineComponent({
      props: { page: Number, total: Number }, emits: ['update:page'],
      setup: (props, { emit }) => () => h('button', { onClick: () => emit('update:page', (props.page ?? 1) + 1) }, '页码'),
    });
    const select = defineComponent({
      emits: ['update:modelValue'],
      setup: (_, { emit }) => () => h('button', { onClick: () => emit('update:modelValue', 20) }, '每页'),
    });
    const wrapper = mount(CompatPagination, {
      props: { total: 65, layout: 'total, sizes, pager, slot' },
      slots: { default: '批量筛选' }, global: { stubs: { UPagination: pagination, USelect: select } },
    });
    expect(wrapper.text()).toContain('共 65 条');
    expect(wrapper.text()).toContain('批量筛选');
    await wrapper.findAll('button')[1].trigger('click');
    expect(wrapper.emitted('current-change')).toEqual([[2]]);
    await wrapper.findAll('button')[0].trigger('click');
    expect(wrapper.emitted('size-change')).toEqual([[20]]);
  });

  it('选择器收集 option 的值、标签和禁用状态，传递选中值', async () => {
    const menu = defineComponent({
      props: { items: Array, searchInput: Boolean }, emits: ['update:modelValue'],
      setup: (_, { emit }) => () => h('button', { onClick: () => emit('update:modelValue', 2) }, '选中'),
    });
    const wrapper = mount(CompatSelect, {
      props: { filterable: true, placeholder: '物料' },
      slots: { default: () => [h(CompatOption, { value: 2, label: '鱼', disabled: '' })] },
      global: { stubs: { USelectMenu: menu, UButton: button } },
    });
    expect(wrapper.getComponent(menu).props()).toMatchObject({ items: [{ value: 2, label: '鱼', disabled: true }], searchInput: true });
    await wrapper.get('button').trigger('click');
    expect(wrapper.emitted('change')).toEqual([[2]]);
  });

  it('单选保留值和标签插槽，警告关闭后消失并发出事件', async () => {
    const radio = defineComponent({
      props: { items: Array }, emits: ['update:modelValue'],
      setup: (_, { emit, slots }) => () => h('button', { onClick: () => emit('update:modelValue', 'a') }, slots.label?.()),
    });
    const wrapper = mount(CompatRadio, { props: { value: 'a', label: '甲' }, slots: { default: '自定义' }, global: { stubs: { URadioGroup: radio } } });
    expect(wrapper.text()).toBe('自定义');
    await wrapper.get('button').trigger('click');
    expect(wrapper.emitted('change')).toEqual([['a']]);
    const alert = defineComponent({
      props: { title: String }, emits: ['close'],
      setup: (props, { emit }) => () => h('button', { onClick: event => emit('close', event) }, props.title),
    });
    const notice = mount(CompatAlert, { props: { title: '库存不足' }, global: { stubs: { UAlert: alert } } });
    await notice.get('button').trigger('click');
    expect(notice.emitted('close')).toHaveLength(1);
    expect(notice.find('button').exists()).toBe(false);
  });

  it('徽标保留标签、数值上限和隐藏状态，骨架屏保留自定义模板与内容', async () => {
    const chip = defineComponent({
      props: { show: Boolean, text: String },
      setup: (props, { slots }) => () => h('div', [slots.default?.(), props.show ? h('span', props.text) : null]),
    });
    const badge = mount(CompatBadge, {
      props: { value: 115, max: 99 }, slots: { default: '待审核' }, global: { stubs: { UChip: chip } },
    });
    expect(badge.text()).toBe('待审核99+');
    await badge.setProps({ hidden: true });
    expect(badge.text()).toBe('待审核');
    const skeleton = mount(CompatSkeleton, { props: { count: 2 }, slots: { template: '加载中', default: '实际内容' } });
    expect(skeleton.text()).toBe('加载中加载中');
    await skeleton.setProps({ loading: false });
    expect(skeleton.text()).toBe('实际内容');
  });

  it('消息和通知送入 useToast，确认框保留确认、取消和输入结果', async () => {
    nuxt.create.mockReturnValue({ open: nuxt.open, close: nuxt.close });
    const wrapper = mount(defineComponent({
      setup() { return useCompatMessages(); }, render: () => h('div'),
    }));
    wrapper.vm.ElMessage.error('库存不足');
    wrapper.vm.ElNotification({ title: '已审核', message: '下一步安排发货', duration: 1200 });
    await flushPromises();
    await vi.waitFor(() => expect(nuxt.add).toHaveBeenCalledTimes(2));
    expect(nuxt.add).toHaveBeenNthCalledWith(1, expect.objectContaining({ description: '库存不足', duration: Infinity, close: true }));
    expect(nuxt.add).toHaveBeenNthCalledWith(2, expect.objectContaining({ title: '已审核', duration: 1200 }));
    nuxt.open.mockResolvedValueOnce({ action: 'confirm', value: '' });
    await expect(wrapper.vm.ElMessageBox.alert('库存不足', '提示')).resolves.toBe('confirm');
    nuxt.open.mockResolvedValueOnce({ action: 'cancel', value: '' });
    await expect(wrapper.vm.ElMessageBox.confirm('确认?')).rejects.toBe('cancel');
    nuxt.open.mockResolvedValueOnce({ action: 'confirm', value: '补充原因' });
    await expect(wrapper.vm.ElMessageBox.prompt('请输入原因')).resolves.toEqual({ action: 'confirm', value: '补充原因' });
    expect(nuxt.create).toHaveBeenLastCalledWith(CompatMessageDialog, expect.objectContaining({ destroyOnClose: true }));
  });

  it('输入框先展示校验错误，再传出通过校验的用户输入', async () => {
    const wrapper = mount(CompatMessageDialog, {
      props: { mode: 'prompt', title: '原因', message: '请填写', options: { inputValidator: value => value.length >= 2 || '至少两个字符' } },
      global: { stubs },
    });
    await wrapper.get('footer button:last-child').trigger('click');
    expect(wrapper.get('[role=alert]').text()).toBe('至少两个字符');
    expect(wrapper.emitted('close')).toBeUndefined();
    await wrapper.get('input').setValue('合格');
    await wrapper.get('footer button:last-child').trigger('click');
    expect(wrapper.emitted('close')).toEqual([[{ action: 'confirm', value: '合格' }]]);
  });
});
