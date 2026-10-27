# FADQA27 — 工厂问答生产验收修复

状态：review；Owner：Root DIRECT；Base SHA：`b97c81ae6f37cbbe05f56ee637ac52d8ee5c4e13`。
代码已冻结，写 scope 释放；修改后的必需 CI、构建、合并、发布与线上验收分别以 Git 和本批回执为准。

## 范围

- `web-admin/src/views/smart-bi/FactoryAnalysisChat.vue` 与 `__tests__/factoryAnalysisChat.spec.ts`。
- `web-admin/src/views/smart-bi/SmartBIAnalysis.vue` 与新 `__tests__/SmartBIAnalysis.queryMode.spec.ts`。
- `web-admin/src/api/smartbi/analysis.ts` 与新 `__tests__/analysis-publication.spec.ts`。
- 本归档；不修改生产只读白名单、Java/Python、原生代码、Entity/Repository、迁移或权限。

## FADQA27-GATE 补充 scope

CI 实测 6514 项通过、2 项失败：权限分支分析器把混合的动态问答模式和可变生命周期初始值当成静态权限结论。Owner `reachability_gate`；Base 同上。仅允许 `web-admin/src/__tests__/gates/lib/branchReachability.ts` 与 `web-admin/src/__tests__/gates/branchReachability.spec.ts`。修正仪器对可变状态和运行时/权限混合表达式的测量，增加阴阳性对照；不得提高预算、排除产品文件、修改权限矩阵或降低断言。验收：该 gate、gates 类型检查及既有三个本批目标文件。协调者 Root 保留台账、提交和发布责任。

该仪器扩展尝试未通过，完整差异私下保留后精确恢复，未交付。Root 收窄为产品职责修正：外层上传工作区是否初始化只取决于导航模式；租户专属子页面与访问检查仍由原 AIQuery 负责。移除工作区调度对租户权限谓词的不必要依赖，保留普通 query/餐饮入口的原有子页面行为；不改变权限分析器或其预算。

初始化流程明确声明为返回 void 的普通函数，流程内部实际权限判断保持可见。父页测试覆盖 FACTORY/RESTAURANT 的显式问答导航，并隔离与调度无关的延迟展示组件，避免测试环境卸载后继续加载样式。原始失败、资源受限的 worker 启动错误与异步组件卸载错误均保留，不把工具错误算产品通过；最终提交由目标门禁、完整类型检查、必需 CI 和候选构建共同判定。

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
