/**
 * vitest 没装 Nuxt UI 插件: 迁到 Nuxt UI 的页面 spec 共用这一套最小桩。
 * 表格按列渲染 `<key>-cell` 插槽 (row.original), 弹窗按 open 内联渲染, 页签渲染全部 slot。
 * 只模拟页面用到的行为 (渲染 + v-model + 点击), 不模拟 Nuxt UI 的样式或可访问性。
 */
import { h } from 'vue';

type Slots = Record<string, (a?: unknown) => unknown>;
type Col = { id?: string; accessorKey?: string; header?: string };
type Item = { label?: string; value?: string; slot?: string };

export const nuxtUiStubs = {
  UTable: {
    props: ['data', 'columns', 'loading'],
    setup(props: { data: Record<string, unknown>[]; columns: Col[] }, { slots, attrs }: { slots: Slots; attrs: Record<string, unknown> }) {
      return () => h('div', { 'data-testid': attrs['data-testid'] }, [
        h('table', [
          h('thead', [h('tr', props.columns.map((c) => h('th', c.header ?? '')))]),
          h('tbody', (props.data ?? []).map(original => h('tr', props.columns.map((c) => {
            const key = c.id ?? c.accessorKey ?? '';
            const cell = slots[`${key}-cell`];
            return h('td', cell ? cell({ row: { original } }) as never : String(original[c.accessorKey ?? ''] ?? ''));
          })))),
        ]),
        !(props.data ?? []).length && slots.empty ? h('div', slots.empty() as never) : null,
      ]);
    },
  },
  UModal: {
    props: ['open', 'title'],
    setup(props: { open: boolean; title: string }, { slots }: { slots: Slots }) {
      return () => (props.open ? h('div', [h('h3', props.title), slots.body?.() as never, slots.footer?.() as never]) : null);
    },
  },
  UTabs: {
    props: ['modelValue', 'items'],
    emits: ['update:modelValue'],
    setup(props: { modelValue: string; items: Item[] }, { slots, emit }: { slots: Slots; emit: (e: string, v: string) => void }) {
      return () => h('div', [
        h('div', props.items.map(i => h('button', { type: 'button', onClick: () => emit('update:modelValue', i.value ?? '') }, i.label))),
        ...props.items.filter(i => i.value === props.modelValue).map(i => h('div', (i.slot && slots[i.slot] ? slots[i.slot]() : null) as never)),
      ]);
    },
  },
  UButton: {
    props: ['disabled', 'loading'],
    template: '<button type="button" :disabled="disabled"><slot /></button>',
  },
  UBadge: { template: '<span><slot /></span>' },
  UAlert: { props: ['title'], template: '<div>{{ title }}<slot name="description" /></div>' },
  UFormField: { template: '<div><slot /></div>' },
  UInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: `<input :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`,
  },
  UTextarea: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: `<textarea :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`,
  },
  USelect: { name: 'USelect', props: ['modelValue', 'items'], template: '<div class="u-select" />' },
  USelectMenu: { name: 'USelectMenu', props: ['modelValue', 'items'], template: '<div class="u-select-menu" />' },
  UPagination: { props: ['page', 'total'], template: '<div class="u-pagination" />' },
  UInputNumber: { props: ['modelValue'], template: '<input type="number" :value="modelValue" />' },
  URadioGroup: { props: ['modelValue', 'items'], template: '<div class="u-radio-group" />' },
  UPopover: {
    props: ['open'],
    emits: ['update:open'],
    template: `<div><span @click="$emit('update:open', !open)"><slot /></span><div v-if="open"><slot name="content" /></div></div>`,
  },
  USlideover: {
    props: ['open', 'title'],
    setup(props: { open: boolean; title: string }, { slots }: { slots: Slots }) {
      return () => (props.open ? h('div', [h('h3', props.title), slots.body?.() as never, slots.footer?.() as never]) : null);
    },
  },
  UCheckbox: { props: ['modelValue'], template: '<input type="checkbox" :checked="modelValue" />' },
};
