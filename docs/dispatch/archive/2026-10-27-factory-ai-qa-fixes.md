# FADQA27 — 工厂问答生产验收修复

状态：review；Owner：Root DIRECT；Base SHA：`b97c81ae6f37cbbe05f56ee637ac52d8ee5c4e13`。
代码已冻结，写 scope 释放；修改后的必需 CI、构建、合并、发布与线上验收分别以 Git 和本批回执为准。

## 范围

- `web-admin/src/views/smart-bi/FactoryAnalysisChat.vue` 与 `__tests__/factoryAnalysisChat.spec.ts`。
- `web-admin/src/views/smart-bi/SmartBIAnalysis.vue` 与新 `__tests__/SmartBIAnalysis.queryMode.spec.ts`。
- `web-admin/src/api/smartbi/analysis.ts` 与新 `__tests__/analysis-publication.spec.ts`。
- 本归档；不修改生产只读白名单、Java/Python、原生代码、Entity/Repository、迁移或权限。

## 触发与结果

F006 网页真实验收发现：外层上传分析工作区在工厂问答模式中仍默认加载历史并启动 enrichment，随后尝试保存分析缓存；只读防护在发送前阻止，实际业务写入为 0。另发现产品澄清中选择产品会清空原问题和继续分析按钮。

工厂问答模式现在跳过外层上传初始化、默认选择与空闲预取；明确切回 Excel 后按需初始化。延迟历史响应重新检查当前视图，切回 Excel 可恢复加载。每个 enrichment 携带工作区 epoch，进入工厂问答、身份变化、离开或卸载后，不再发布旧结果、进度或缓存写入；普通分析仍保留缓存路径。

产品澄清选定产品时只保留待确认问题和已解析的期间元数据，清掉旧图表、事实、明细与上下文令牌；继续按钮发起新的可信读取。

## 验证

- 问答页目标 14 项通过，含真实选择器事件后的原问题和期间保留，以及普通产品切换后的旧图表清理。
- 生命周期目标 8 项通过：真实父页面挂载、普通 query 正向初始化、视图切换、延迟历史恢复，以及真实 enrichment 编排中的撤销后不保存、正常保存和完成回调撤销。
- 独立窄范围复核 PASS；最终完整类型检查与 PR CI 必须完成后才合并。
- 命令：`node node_modules/vitest/vitest.mjs run src/views/smart-bi/__tests__/factoryAnalysisChat.spec.ts src/views/smart-bi/__tests__/SmartBIAnalysis.queryMode.spec.ts src/api/smartbi/__tests__/analysis-publication.spec.ts`；类型门禁：`node node_modules/vue-tsc/bin/vue-tsc.js -b --force`。

首次生产失败、类型/目标测试、构建及后续验收证据保留在本机本批 ignored evidence。线上验收仅 F006，必须实际业务写入与被拦截写入尝试都为 0，不将缓存保存放入只读白名单。
