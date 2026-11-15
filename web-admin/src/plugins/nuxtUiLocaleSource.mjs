// 刻意写成 .mjs: 在 TS 里 import '@nuxt/ui/locale' 会把 Nuxt UI 整套类型图 (含 motion-v 对 DragControls
// 的增补) 拉进 vue-tsc, 令 vue-flow 的 Node.domAttributes 不兼容 (approval-workflow-editor /
// ProductProcessWorkflowEditor 共 30 条 TS2322/TS2345, #1766 CI 实测: 去掉该 import 后 0 条)。
// 本仓 allowJs 关闭, TS 只看同名 .d.ts 里的最小声明; 运行时仍是真实的 Nuxt UI locale。
export { zh_cn, en } from '@nuxt/ui/locale';
