// 调整审批 spec 共用的桩与确认弹窗辅助 (不含 vi.mock, 其它 spec 可以安全引用)。
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';

// 共享最小桩的 UCheckbox 不回传 v-model; 页面用它做勾选, 这里补上 change -> update:modelValue。
export const adjustmentStubs = {
  ...nuxtUiStubs,
  UIcon: { template: '<i />' },
  UCheckbox: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: `<input type="checkbox" :checked="modelValue" @change="$emit('update:modelValue', $event.target.checked)" />`,
  },
};
// 确认弹窗 (SalesConfirmModal) 的状态在模块级, 测试之间要手动收起, 免得下一个用例一挂载就是开着的
export function closeConfirmModal() {
  document.body.querySelector<HTMLButtonElement>('[data-testid="sales-confirm-cancel"]')?.click();
}
export const confirmButton = (text: string) => Array.from(document.body.querySelectorAll<HTMLButtonElement>(
  '[data-testid="sales-confirm-cancel"], [data-testid="sales-confirm-ok"]')).find(n => n.textContent?.trim() === text);
