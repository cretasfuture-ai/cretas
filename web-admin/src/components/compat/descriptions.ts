import { defineComponent, h, type Slots } from 'vue';
import CompatDescriptionsSurface from './CompatDescriptionsSurface.vue';
import { length, slotNodes } from './vnode';

export const CompatDescriptionsItem = defineComponent({
  name: 'CompatDescriptionsItem',
  setup: () => (): null => null,
});

export const CompatDescriptions = defineComponent({
  name: 'CompatDescriptions',
  props: {
    title: String, column: { type: Number, default: 3 }, border: Boolean, size: String,
    direction: { type: String, default: 'horizontal' },
  },
  setup(props, { slots }) {
    return () => {
      const count = Math.max(1, props.column);
      const items = slotNodes(slots.default?.() ?? []).filter(node => node.type === CompatDescriptionsItem);
      const body = h('dl', {
        class: ['grid min-w-0', props.border ? 'overflow-hidden rounded border border-default' : 'gap-2'],
        style: { gridTemplateColumns: `repeat(${count}, minmax(0, 1fr))` },
      }, items.map((node, index) => {
        const item = node.props ?? {};
        const itemSlots = node.children as Slots | null;
        const cellPadding = props.size === 'small' ? 'px-2 py-1' : 'px-3 py-2';
        return h('div', {
          key: node.key ?? index,
          class: ['min-w-0 break-words', props.direction === 'vertical' ? 'flex flex-col' : 'flex flex-wrap items-baseline',
            props.border && 'border-b border-e border-default'],
          style: { gridColumn: `span ${Math.min(count, Math.max(1, Number(item.span) || 1))}`, width: length(item.width), minWidth: length(item.minWidth ?? item['min-width']) },
        }, [
          h('dt', { class: ['shrink-0 font-medium text-muted', cellPadding, props.border && 'bg-elevated'], style: { textAlign: item['label-align'] ?? item.labelAlign } }, itemSlots?.label?.() ?? String(item.label ?? '')),
          h('dd', { class: ['min-w-0 flex-1', cellPadding], style: { textAlign: item.align } }, itemSlots?.default?.() ?? []),
        ]);
      }));
      return h(CompatDescriptionsSurface, { title: props.title }, { default: () => body, title: slots.title, extra: slots.extra });
    };
  },
});
