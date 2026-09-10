# B09 Workflow 光标传播修复回执

- ID：DRIVE-20260911-B09-WORKFLOW-CURSOR。
- Owner：Codex task 01a08b7f-e9d0-7312-9138-2255e979a0a8。
- Base SHA：`0a5d10347d0918c33cc5db2ffa1613690cdb158e`。
- Worktree：`C:/Users/Steve/cretas-drive-20260911-b09`；branch：`codex/drive-20260911-b09`。
- 状态：实现和本地目标验证完成；待协调者集成/正式构建/部署及用户环境验收。无 scope 阻塞；未编辑 ACTIVE，未创建任务/子代理。

## 实际修改文件与行为

前缀 `web-admin/src/views/system/product-processes/workflow/`：

1. `ProductProcessWorkflowEditor.vue`：连接状态直接给实际 VueFlow pane 设置已有双描边光标变量，修复外层 cursor 被 pane 的 grab 覆盖。
2. `WorkflowMaterialNode.vue`：connectable handle 使用已有变量；移除左右 handle hover 的原生 crosshair；非法目标 handle 保持 not-allowed。
3. `WorkflowProcessNode.vue`：同上。非连接时 pane/节点的 grab、grabbing 和普通按钮 pointer 未全局覆盖，非 connectable 投影锚点不匹配新增规则。
4. `__tests__/workflowCursorInteractions.spec.ts`：3 条 mounted 生命周期测试，编译加载生产 SFC scoped CSS 和 VueFlow CSS，验证真实编辑器状态、目标节点状态、pane/handle/button 的 computed cursor、释放与框选。事件适配器替代 VueFlow 几何；真实几何另用浏览器验证。
5. 本回执。

未改全局 style.css、光标资产、依赖、接口、后端或业务规则。协调者已告知 main 前进到 PR763；本任务按登记 Base 完成，不重写或回退其他任务改动。

## 验证

在 worktree 的 `web-admin` 运行：

```
npx vitest run src/views/system/product-processes/workflow/__tests__/workflowCursorInteractions.spec.ts src/views/system/product-processes/workflow/__tests__/ProductProcessWorkflowEditor.spec.ts src/views/system/product-processes/workflow/__tests__/WorkflowMaterialNode.spec.ts src/views/system/product-processes/workflow/__tests__/WorkflowProcessNode.spec.ts src/views/system/product-processes/workflow/__tests__/evaluateWorkflowConnection.spec.ts --maxWorkers=1
npx vue-tsc -b --pretty false
node .playwright-mcp/b09/browser.mjs
git diff --check
```

- 最终目标测试：5 files / 107 tests PASS，exit 0；43.48s。
- 类型检查：exit 0，无诊断。
- 浏览器：完整 Editor、真实 VueFlow、真实 Material/Process 组件，本地 LOCAL API 拦截夹具；8 组 PASS、108 条状态记录、page errors=[]。使用 Chromium 设置接口切换真实浏览器 80%/100%/125%/200% zoom，分别断言 devicePixelRatio 与 CSS viewport 变化；每个 zoom 覆盖浅色与深色画布背景。
- 每组覆盖：物料和工序 source hover、连接中 pane、双向合法/非法目标、松手恢复、普通按钮、框选、平移 idle/drag、普通节点拖动且位置实际改变。没有把截图当作鼠标像素证据。
- 最终矩阵共 50 个 GET、12 个 PUT，业务请求全部本地拦截，无生产业务写入；本地 draft PUT 以真实 payload echo 作为模拟回读，不访问后端。早期浏览器 zoom 探索页曾触发 LOCAL GET 到本地开发代理，均 ECONNREFUSED；该探索不算矩阵通过证据。
- 调试阶段修正了单测的子组件 stub/按钮选择器，以及本地 draft 返回空数组的夹具错误。浏览器初次等待过慢被中止，加超时与步骤日志后持续取得证据；最终完整矩阵明确 exit 0。初次失败/中止不算通过。
- 本人已完成最终 scope、diff、CSS 作用范围只读检查。B05 独立复核无新增阻塞 finding，Base/候选 CSS 两种加载顺序对照成功；复核测试文件 SHA256 `21157ac5ffab20143d3d1d4626ca9f607cbe286c0d3f1a829fba024f1554694c` 与提交一致。报告：`C:/Users/Steve/cretas-drive-20260910-b05/.codex-evidence/b05/B09-readonly-review.md`。未自行重复官方发布构建。

证据根目录：`C:/Users/Steve/cretas-drive-20260911-b09/web-admin/.playwright-mcp/b09/`。
主要文件：`target-tests.log`、`typecheck.log`、`browser-result.json`、`browser-matrix.log`、`browser.mjs`、`main.js`、`index.html`、8 张 `matrix-*.png`、`source-hashes.json`。临时 Vite 与浏览器已停止；未删除其他任务的目录或进程。

## 未验证与下一步

未验证 Windows 原生鼠标像素、SVG cursor 平台 fallback、真实 Windows 100%/150%/200% DPI、多浏览器或用户线上会话。浏览器 zoom 不是 OS DPI；浅/深背景夹具也不代表全应用暗色主题已验收。表格光标本批无代码改动，既有本地 computed 命中证据不等于用户看得清。

下一步由协调者核对最新 main 的不重叠差异、独立复核结果，完成正式构建和适用发布流程；部署后仍需用户环境原生光标验收。

Drive 已精确更新并独立读回：spreadsheet `1TyIqNP_z8bBiPyLeXbr3YYvwE1WCuHFhgYAHiJC4w4M` 的 main!G13、main!G61、'UX&UI'!G12 仅追加备注，保留全部原文与历史。读回逐字匹配；main13/main61 状态仍为“正在解决”，UX&UI12 仍为“修复中”，未标已解决/已部署。证据：上述目录 `drive-readback.json`。

代码 commit：`1567e13b2ff87d5a436d928e60b526a20f20d521`；后续提交仅补充本回执的独立复核/Drive 读回事实，不改变已验证的 Web 源码。未 push、未创建 PR、未合并、未部署、未迁移。
