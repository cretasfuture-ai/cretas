// 从销售订单生成采购单的**唯一**弹窗 —— 多张 SO 合并成一张 (转录行3650 "加号逐个追加合并"),
// 单张 SO 也走它 (sales/orders/list.vue:「单张时等价于开始采购但走合并弹窗」)。
//
// 🔴 2026-08-19: 删掉了并列的 StartPurchaseDialog。它自 2026-07-20 `bb17530017`
// (从销售订单列表移除「开始采购」按钮) 起就没有被任何 view 挂载, 但此后仍持续收到修复 ——
// 7-17/7-18 的单位契约 (referencePriceUnit + 「参考单价/计价单位」列)、7-26 的包装规格、
// 7-31 的供应商简称, 全部做进了一个用户看不见的组件, 而真实入口一条都没拿到。
// ⛔ 不要再建第二个「从 SO 生成 PO」的入口: 两个入口做同一件事必然漂移。
export { default as MergePurchaseDialog } from './MergePurchaseDialog.vue';
// U-DESKTOP-MODAL-1 Sprint 4 Wave 2 Chat L — desktop-class draggable / resizable dialog.
export { default as EnhancedDialog } from './EnhancedDialog.vue';
// U-DESKTOP-MODAL-1 Sprint 4 Wave 2 followup — el-dialog wrapper variant
// (delegates a11y/animation to ElDialog) + global multi-modal dock.
export { default as DesktopModal } from './DesktopModal.vue';
export { default as ModalDock } from './ModalDock.vue';
export {
  useModalDock,
  generateModalId,
  registerMinimized,
  unregisterMinimized,
  type MinimizedModalEntry,
} from './useModalDock';
