import { computed, defineComponent, h, type PropType } from 'vue';

/**
 * 供应商三个子组件（详情抽屉 / 导入弹窗 / 档案区）的最小 Nuxt UI 桩。
 *
 * 为什么要桩：vitest 里没装 Nuxt UI 插件（见 docs/web-admin/nuxt-ui-migration-batches.md 做法第 4 条），
 * 未注册的 U* 组件不会渲染插槽，断言就读不到任何东西。
 *
 * 桩的原则（与迁移前的 Element 桩同一条）：只做「把插槽/标题/行渲染出来」，⛔ 不模仿 Nuxt UI 的别的行为。
 * 特别是 UTable —— 必须真的把每一行每一列渲染出来，否则「没看到某个值」和「压根没渲染」是同一个读数。
 */

type Row = Record<string, unknown>;
type Column = { id?: string; accessorKey?: string; header?: string };

export const UTableStub = defineComponent({
  name: 'UTable',
  props: {
    data: { type: Array as PropType<Row[]>, default: () => [] },
    columns: { type: Array as PropType<Column[]>, default: () => [] },
  },
  setup(props, { slots }) {
    const cols = computed(() => props.columns);
    return () => h('div', { class: 'stub-table' }, [
      ...cols.value.map((col) => {
        const key = col.id ?? col.accessorKey ?? '';
        return h('div', { 'data-col': col.header ?? '' }, props.data.map((original, index) => h('div', { class: 'cell' }, (
          slots[`${key}-cell`]
            ? slots[`${key}-cell`]!({ row: { original, index } })
            : String(col.accessorKey ? (original[col.accessorKey] ?? '') : '')
        ))));
      }),
      props.data.length ? null : h('div', { class: 'stub-empty' }, slots.empty?.()),
    ]);
  },
});

const passThrough = (name: string, tag = 'div') => defineComponent({
  name,
  setup: (_p, { slots }) => () => h(tag, slots.default?.()),
});

/** 受控输入桩：把值/禁用态落到 DOM 上，输入时真的 emit。 */
const modelStub = (name: string) => defineComponent({
  name,
  props: { modelValue: { type: null, default: null }, disabled: { type: Boolean, default: false } },
  emits: ['update:modelValue'],
  setup: (props, { emit }) => () => h('input', {
    'data-value': String(props.modelValue ?? ''),
    disabled: props.disabled,
    onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value),
  }),
});

const UButtonStub = defineComponent({
  name: 'UButton',
  emits: ['click'],
  setup: (_p, { slots, emit }) => () => h('button', { onClick: () => emit('click') }, slots.default?.()),
});

/** 弹窗：标题 + body + footer 都渲染（⛔ 不看 open，开合由具体用例读 vm 状态）。 */
const UModalStub = defineComponent({
  name: 'UModal',
  props: { title: { type: String, default: '' } },
  setup: (props, { slots }) => () => h('div', { class: 'stub-dialog', 'data-title': props.title },
    [slots.body?.(), slots.footer?.()]),
});

const USlideoverStub = defineComponent({
  name: 'USlideover',
  setup: (_p, { slots }) => () => h('div', { class: 'stub-slideover' },
    [slots.title?.(), slots.description?.(), slots.actions?.(), slots.body?.()]),
});

const UTabsStub = defineComponent({
  name: 'UTabs',
  props: { items: { type: Array as PropType<Array<{ slot?: string; label: string }>>, default: () => [] } },
  setup: (props, { slots }) => () => h('div', { class: 'stub-tabs' },
    props.items.map((item) => h('section', { 'data-tab': item.label }, item.slot ? slots[item.slot]?.() : undefined))),
});

const UFormFieldStub = defineComponent({
  name: 'UFormField',
  props: { label: { type: String, default: '' }, help: { type: String, default: '' } },
  setup: (props, { slots }) => () => h('div', { class: 'form-item' }, [
    h('label', props.label), slots.default?.(), props.help ? h('small', props.help) : slots.help?.(),
  ]),
});

const UFormStub = defineComponent({
  name: 'UForm',
  setup(_p, { slots, expose }) {
    expose({ submit: async () => undefined });
    return () => h('div', slots.default?.());
  },
});

const UAlertStub = defineComponent({
  name: 'UAlert',
  props: { title: { type: String, default: '' }, description: { type: String, default: '' } },
  setup: (props) => () => h('div', { class: 'stub-alert' }, [props.title, props.description]),
});

const UEmptyStub = defineComponent({
  name: 'UEmpty',
  props: { description: { type: String, default: '' } },
  setup: (props, { slots }) => () => h('div', { class: 'stub-empty-state' }, [props.description, slots.actions?.()]),
});

const USeparatorStub = defineComponent({
  name: 'USeparator',
  props: { label: { type: String, default: '' } },
  setup: (props) => () => h('div', { class: 'stub-separator' }, props.label),
});

const UTimelineStub = defineComponent({
  name: 'UTimeline',
  props: { items: { type: Array as PropType<Array<{ title: string }>>, default: () => [] } },
  setup: (props) => () => h('ol', props.items.map((item) => h('li', item.title))),
});

export const nuxtUiStubs = {
  UTable: UTableStub,
  UButton: UButtonStub,
  UModal: UModalStub,
  USlideover: USlideoverStub,
  UTabs: UTabsStub,
  UFormField: UFormFieldStub,
  UForm: UFormStub,
  UAlert: UAlertStub,
  UEmpty: UEmptyStub,
  USeparator: USeparatorStub,
  UTimeline: UTimelineStub,
  UBadge: passThrough('UBadge', 'span'),
  USkeleton: passThrough('USkeleton'),
  UInput: modelStub('UInput'),
  UTextarea: modelStub('UTextarea'),
  UInputNumber: modelStub('UInputNumber'),
  USwitch: modelStub('USwitch'),
  USelect: modelStub('USelect'),
  USelectMenu: modelStub('USelectMenu'),
  UCheckbox: modelStub('UCheckbox'),
  SupplierUnitSelect: modelStub('SupplierUnitSelect'),
  SupplierProfileCollections: passThrough('SupplierProfileCollections'),
};
