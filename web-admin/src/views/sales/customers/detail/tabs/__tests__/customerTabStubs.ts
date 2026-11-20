/**
 * 客户详情页签 spec 共用的 Nuxt UI 桩: 在通用桩（__tests__/helpers/nuxtUiStubs）上补
 * 这些页签用到、通用桩没有的 UEmpty / USkeleton / UIcon / UPagination，
 * 并把 UAlert 的 title、UEmpty 的 description 与 actions 插槽渲染成文本（空态/错误态断言读它们）。
 * UTable 用通用桩：按列渲染 `<key>-cell` 插槽，所以权限遮蔽（****）与金额格式是真渲染出来的。
 */
import { h } from 'vue';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';

type Slots = Record<string, (a?: unknown) => unknown>;

export const customerTabStubs = {
  ...nuxtUiStubs,
  USkeleton: { template: '<div class="u-skeleton" />' },
  UIcon: { template: '<i />' },
  UPagination: {
    props: ['page', 'total', 'itemsPerPage'],
    template: '<div class="u-pagination" :data-total="total" />',
  },
  UAlert: {
    props: ['title'],
    template: '<div class="u-alert">{{ title }}</div>',
  },
  UEmpty: {
    props: ['description'],
    setup(props: { description: string }, { slots }: { slots: Slots }) {
      return () => h('div', { class: 'u-empty' }, [props.description, slots.actions?.() as never]);
    },
  },
};
