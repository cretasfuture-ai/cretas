import { defineComponent, h, type PropType, type VNodeChild } from 'vue';

const pass = { template: '<div><slot name="header" /><slot /><slot name="footer" /></div>' };
type Column = {
  id?: string; accessorKey?: string; accessorFn?: (row: object) => unknown;
  cell?: (scope: { row: { original: object; index: number; id: string }; getValue: () => unknown }) => VNodeChild;
};

export const nuxtPilotStubs = {
  UButton: {
    props: ['disabled', 'loading', 'type'], emits: ['click'],
    template: '<button :disabled="disabled || loading" :type="type || \'button\'" @click="$emit(\'click\', $event)"><slot /></button>',
  },
  UInput: {
    props: ['modelValue', 'disabled', 'type', 'size'], emits: ['update:modelValue'],
    template: '<input :value="modelValue" :disabled="disabled" :type="type || \'text\'" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  USelectMenu: {
    props: ['items', 'modelValue', 'disabled', 'multiple'], emits: ['update:modelValue'],
    template: '<select :value="modelValue" :multiple="multiple" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.selectedOptions[0]?._value)"><option v-for="item in items" :key="item.value" :disabled="item.disabled" :value="item.value">{{ item.label }}</option></select>',
  },
  UForm: { template: '<form><slot /></form>' },
  UFormField: {
    props: ['label', 'error'], template: '<div class="compat-field"><label><slot name="label">{{ label }}</slot></label><slot /><span v-if="error" role="alert">{{ error }}</span></div>',
  },
  UCard: pass,
  UBadge: pass,
  UCheckbox: {
    props: ['modelValue', 'disabled'], emits: ['update:modelValue'],
    template: '<input type="checkbox" :disabled="disabled" :checked="modelValue === true" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
  UPagination: true,
  USelect: true,
  UAlert: { props: ['title', 'description'], template: '<div class="alert" role="alert">{{ title }}{{ description }}<slot name="description" /></div>' },
  UModal: {
    props: ['open', 'title'], template: '<div class="dialog-stub" role="dialog" :data-title="title" v-if="open"><slot name="content" /></div>',
  },
  UTable: defineComponent({
    props: {
      data: { type: Array as PropType<object[]>, default: (): object[] => [] },
      columns: {
        type: Array as PropType<Column[]>,
        default: (): Column[] => [],
      },
      getRowId: Function,
    },
    setup(props, { slots }) {
      return () => {
        if (!props.data.length) return h('div', slots.empty?.());
        const rows = props.data.map((original, index) => {
          const row = { original, index, id: props.getRowId?.(original, index) ?? String(index) };
          const cells = props.columns.map((column, columnIndex) => {
            const key = column.id ?? column.accessorKey ?? String(columnIndex);
            const getValue = () => column.accessorFn ? column.accessorFn(original)
              : (original as Record<string, unknown>)[column.accessorKey ?? ''];
            const slot = slots[`${key}-cell`];
            const value = slot ? slot({ row, getValue }) : column.cell ? column.cell({ row, getValue }) : String(getValue() ?? '');
            return h('td', { key }, [value]);
          });
          return h('tr', { key: index }, cells);
        });
        return h('table', [h('tbody', rows)]);
      };
    },
  }),
};
