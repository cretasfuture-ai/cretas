import { defineComponent, h, ref, watch, type PropType, type Slots } from 'vue';
import CompatTableSurface from './CompatTableSurface.vue';
import CompatSelectionCheckbox from './CompatSelectionCheckbox.vue';
import type { TableColumn } from '@nuxt/ui/components/Table.vue';
import { fieldValue, length, slotNodes } from './vnode';

type Row = object;
type ColumnProps = {
  prop?: string;
  label?: string;
  width?: string | number;
  minWidth?: string | number;
  fixed?: boolean | '' | 'left' | 'right';
  align?: 'left' | 'center' | 'right';
  type?: 'default' | 'selection' | 'index';
  index?: number | ((index: number) => number);
  showOverflowTooltip?: boolean | '';
  formatter?: (...args: never[]) => unknown;
  selectable?: (...args: never[]) => boolean;
};

export const CompatTableColumn = defineComponent({
  name: 'CompatTableColumn',
  setup: () => (): null => null,
});

export const TableAdapter = defineComponent({
  name: 'TableAdapter',
  props: {
    data: { type: Array as PropType<Row[]>, default: (): Row[] => [] },
    rowKey: [String, Function] as PropType<string | ((row: never) => string | number)>,
    border: Boolean,
    stripe: Boolean,
    size: String,
    height: [String, Number],
    maxHeight: [String, Number],
    emptyText: String,
    highlightCurrentRow: Boolean,
  },
  emits: {
    'selection-change': (_rows: Row[]) => true,
    'row-click': (_row: Row, _column: undefined, _event: Event) => true,
    'current-change': (_current: Row | undefined, _previous: Row | undefined) => true,
  },
  setup(props, { slots, attrs, emit, expose }) {
    const selected = ref<Set<string>>(new Set());
    const current = ref<Row>();
    const keyFor = (row: Row, index: number) => String(typeof props.rowKey === 'function' ? props.rowKey(row as never)
      : props.rowKey ? fieldValue(row, props.rowKey) ?? index : index);
    const getSelectionRows = () => props.data.filter((row, index) => selected.value.has(keyFor(row, index)));
    const setSelection = (keys: Set<string>) => {
      if (keys.size === selected.value.size && [...keys].every(key => selected.value.has(key))) return;
      selected.value = keys;
      emit('selection-change', getSelectionRows());
    };
    const clearSelection = () => setSelection(new Set());
    const toggleRowSelection = (row: Row, checked?: boolean) => {
      const index = props.data.indexOf(row);
      if (index < 0) return;
      const key = keyFor(row, index);
      const keys = new Set(selected.value);
      if (checked ?? !keys.has(key)) keys.add(key);
      else keys.delete(key);
      setSelection(keys);
    };
    const setCurrentRow = (row?: Row) => {
      const previous = current.value;
      current.value = row;
      if (row !== previous) emit('current-change', row, previous);
    };
    watch(() => props.data, (rows) => {
      const keys = new Set(rows.map(keyFor));
      setSelection(new Set([...selected.value].filter(key => props.rowKey && keys.has(key))));
      if (current.value && !rows.includes(current.value)) setCurrentRow();
    });
    expose({ clearSelection, getSelectionRows, toggleRowSelection, setCurrentRow });

    return () => {
      const source = slotNodes(slots.default?.() ?? []).filter(node => node.type === CompatTableColumn);
      const pinning: { left: string[]; right: string[] } = { left: [], right: [] };
      const columns: TableColumn<Row>[] = source.map((node, index) => {
        const column = node.props as ColumnProps | null ?? {};
        const minWidth = column.minWidth ?? node.props?.['min-width'];
        const tooltip = column.showOverflowTooltip ?? node.props?.['show-overflow-tooltip'];
        const cellSlot = (node.children as Slots | null)?.default;
        const headerSlot = (node.children as Slots | null)?.header;
        const id = `column-${String(node.key ?? index)}`;
        if (column.fixed !== undefined && column.fixed !== false) {
          pinning[column.fixed === 'right' ? 'right' : 'left'].push(id);
        }
        const eligible = () => props.data.filter((row, itemIndex) => column.selectable?.(row as never, itemIndex as never) ?? true);
        const eligibleKeys = () => eligible().map(row => keyFor(row, props.data.indexOf(row)));
        const size = Number.parseFloat(String(column.width ?? minWidth ?? '150'));
        const style = {
          ...(column.width !== undefined ? { width: length(column.width)!, minWidth: length(column.width)! } : {}),
          ...(minWidth !== undefined ? { minWidth: length(minWidth)! } : {}),
          ...(column.align ? { textAlign: column.align } : {}),
        };
        return {
          id,
          size: Number.isFinite(size) ? size : 150,
          accessorFn: row => column.prop ? fieldValue(row, column.prop) : undefined,
          header: () => {
            if (column.type !== 'selection') return headerSlot?.({ column, $index: index }) ?? column.label ?? '';
            const keys = eligibleKeys();
            const count = keys.filter(key => selected.value.has(key)).length;
            return h(CompatSelectionCheckbox, {
              'aria-label': '全选',
              modelValue: keys.length > 0 && count === keys.length ? true : count > 0 ? 'indeterminate' : false,
              disabled: !keys.length,
              'onUpdate:modelValue': checked => {
                const next = new Set(selected.value);
                for (const key of keys) { if (checked === true) next.add(key); else next.delete(key); }
                setSelection(next);
              },
            });
          },
          cell: ({ row, getValue }) => {
            const scope = { row: row.original, column, $index: row.index };
            if (column.type === 'index') return typeof column.index === 'function' ? column.index(row.index) : row.index + (column.index ?? 1);
            if (column.type === 'selection') return h(CompatSelectionCheckbox, {
              'aria-label': `选择第 ${row.index + 1} 行`,
              modelValue: selected.value.has(row.id),
              disabled: !(column.selectable?.(row.original as never, row.index as never) ?? true),
              onClick: (event: Event) => event.stopPropagation(),
              'onUpdate:modelValue': checked => toggleRowSelection(row.original, checked === true),
            });
            if (cellSlot) return cellSlot(scope);
            const formatted = column.formatter?.(row.original as never, column as never, getValue() as never, row.index as never) ?? getValue();
            const display = formatted == null ? '' : String(formatted);
            return tooltip !== undefined && tooltip !== false ? h('span', { title: display, class: 'block truncate' }, display) : display;
          },
          meta: { style: { th: style, td: style } },
        };
      });
      const padding = props.size === 'small' ? 'px-2 py-1.5' : 'px-3 py-2';
      return h(CompatTableSurface<Row>, {
        ...attrs,
        class: [attrs.class, 'overflow-auto', props.border && 'rounded border border-default'],
        style: [attrs.style, { height: length(props.height), maxHeight: length(props.maxHeight) }],
        data: props.data, columns, getRowId: keyFor, columnPinning: pinning,
        empty: props.emptyText, sticky: Boolean(props.height || props.maxHeight),
        ui: { th: padding, td: padding },
        meta: { class: { tr: row => props.highlightCurrentRow && row.original === current.value ? 'bg-primary/10'
          : props.stripe && row.index % 2 !== 0 ? 'bg-elevated/50' : '' } },
        onSelect: (event, row) => {
          if (props.highlightCurrentRow) setCurrentRow(row.original);
          emit('row-click', row.original, undefined, event);
        },
      }, { ...(slots.empty ? { empty: slots.empty } : {}), ...(slots.append ? { expanded: slots.append } : {}) });
    };
  },
});
