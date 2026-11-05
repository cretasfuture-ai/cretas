# Canvas 每项方案服务端绑定

任务 FOOD-DAG-CANVAS-PROOF-20261105；Base SHA 50ec3c63e1b08fde335895af4b6818169c0476b5。
唯一写Owner 食品DAG session 01a1a954-13e5-7182-9572-00ff306c8d31；执行者已安全交回源码，当前由根协调完成交付。
Worktree C:/Users/Steve/cretas-food-dag-canvas-20261105；branch codex/food-dag-canvas-proof-20261105。

## 文件范围锁

- backend/java/cretas-api/src/main/java/com/cretas/aims/controller/CanvasAIController.java
- 新 backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/CanvasPlanConfirmationService.java（若无必要可以不新增）
- 新 backend/java/cretas-api/src/test/java/com/cretas/aims/controller/CanvasAIPlanConfirmationContractTest.java
- 新 backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/CanvasPlanConfirmationContractTest.java（若无独立service不新增）
- 仅必要夹具维护：原 CanvasAIApplyDiffsResultTest.java、CanvasAIEgressRedactionTest.java、CanvasAIWorkflowConfigTest.java
- 已核实耦合的既有测试夹具：controller/WorkProcessPreviewApplyContractTest.java、controller/WorkProcessCatalogChatContractTest.java、ai/tool/impl/canvas/CanvasToolActorGuardTest.java；只补proof/协议/依赖夹具，保留原权限/真实预览至保存的业务断言。
- web-admin/src/types/canvas.ts
- web-admin/src/views/platform/canvas-editor/components/AIChatPanel.vue
- web-admin/src/views/platform/canvas-editor/index.vue
- web-admin/src/views/system/work-processes/WorkProcessAIChatPanel.vue
- web-admin/src/api/canvasApi.ts 与现有 web-admin/src/api/__tests__/canvasApplyProtocol.spec.ts
- 如需可测映射helper：新 web-admin/src/utils/canvasDiffProof.ts 与 web-admin/src/utils/__tests__/canvasDiffProof.spec.ts
- 根协调独立写scope：新 web-admin/src/utils/__tests__/canvasProofFlow.spec.ts，真实挂载通用AIChatPanel与工序目录面板验证proof从响应、选择到提交的保留；执行者不得编辑此文件。其余前后端文件仍由原执行者唯一写，不再等待前端交接。
- 根协调另写既有 web-admin/src/views/platform/canvas-editor/__tests__/applyChangesResult.spec.ts 的proof行为用例，实际CanvasEditor.handleAIDiff→applyChanges→API参数验证，补上通用面板stage后的中段映射；执行者不得编辑。
- 本归档由协调者维护；执行者不得改ACTIVE、依赖/lockfile、其他配置/AI配置、Entity/Repository/迁移、共享发布脚本。

## 执行与验收

#1440 P1-2：HTTP apply-diffs不能接受未经服务端生成/绑定的tool/params。复用PreviewTokenService逐diff令牌，兄弟diff使用唯一intentCode，保留逐项选择/应用交互；HTTP协议升级3、旧客户端拒绝并提示刷新。claim后仅执行持久化命令；无proof、篡改、跨租户/用户、过期、重放、版本不符全部零执行。认领后异常诚实UNKNOWN且不可再次派发；未治理Canvas不能冒充已有统一ledger覆盖。

目标 Maven 新ContractTest与既有上述3个Canvas测试；同一target顺序运行。前端 Vitest canvasApplyProtocol/canvasDiffProof 与TypeScript生产构建。原语义Workflow预览/工序目录/审批模块专用路径不得因proof遗漏而意外全阻断，需明确生成proof的分支与对应测试。
执行者只实现及目标验证，不提交/推送/合并/部署，不占发布锁；必须回传实际修改文件、命令/执行数/结果/日志、剩余风险。源码与测试冻结后复用通过证据，不重复全量。

补充目标选择器必须包含上述工序预览至保存、目录chat、Canvas actor三个既有测试；已实读其Controller调用点，不能等CI才发现旧协议2夹具不兼容。

## 最终实现与验证

- 每项独立持久化proof绑定工厂、用户、工具版本/参数；HTTP协议3，旧客户端拒绝并提示刷新。claim后只用持久化命令，用途限定CANVAS_DIFF；无proof、篡改、跨租户、过期/重放、purpose或版本不符不得派发。
- claim与resolve异常明确UNKNOWN，写后异常或无法确认结果不重试；认领标识只记fingerprint。产品工序本地草稿、Workflow/审批预览不铸执行proof，通用LLM也不能用专用产品工序工具绕过该限制。
- Canvas局部JSON规范化在铸proof前验证数值语义等价，25.00/0.30能真实create→claim，HTTP 25/0.3不误判篡改，精度有实际损失则不生成可执行项；未改共享PreviewTokenService或全局digest契约。
- Maven原七类72/0/0/0；独立终审要求的resolve异常/false与同proof重放两例补入后，定向Contract14/0/0/0、31.602秒；其余六类及生产字节未变，合计74唯一后端用例。真实PreviewTokenServiceImpl+仓储stub验证十进制链，不是mock令牌服务自证。
- 根协调前端六套56项通过，含真实通用面板、CanvasEditor中段映射、工序面板：选择第二项proof/参数不混，确认前zero apply；执行者npm ci与生产build成功。最终可信制品仍由统一发布入口另行构建/取证。
- 独立高风险终审全部P1闭合，可commit/PR；Controller SHA256 b1f15cb8a783976423790c40ded7000e68da52d842832f37a8f2378ad296cda4，Contract7288dc3c38428b63c583560b50dee603d1aed89634a3dc7ed26b9360dacc0e38。
- ACTIVE仅本批临时行移出，本归档保留任务ID/Base/scope与证据；不改他人在飞任务。未宣称已合并、部署、迁移或生产业务验收。

私有证据：B:/moved-from-d/Documents/Codex/2026-11-05/wo/outputs/食品DAG-恢复/（canvas-proof-resolution-final.log、canvas-frontend-proof-flow.log、canvas-editor-proof-apply.log）；原Maven七类日志C:/Users/Steve/.codex/tmp/food-dag-canvas-20261105/maven-final-7classes-post-p1.log。

CI架构门禁补齐：原内部applyDiffs仍保留raw执行循环，导致直调站点1→2。删除该循环，内部签名仅委托同一proof引擎，所有调用都需服务端凭据，保持唯一executor.execute；不改架构baseline/扫描器。原结果测试逐项设置已持久化参数/版本的claim夹具并补内部raw拒绝，安全引擎纵深拒绝两种本地预览工具。追加ToolExecutionArchitectureTest到目标与发布选择器。

统一入口最终冻结验证：canvas-unified-engine-accepted.log，Maven八类（含ToolExecutionArchitectureTest）82 tests / 0 failures / 0 errors / 0 skipped，2026-11-05 13:41:56 BUILD SUCCESS，113秒。内部入口和HTTP均经过proof引擎；旧Workflow夹具补真实身份与本地凭据依赖，未恢复raw旁路。前端字节未变，复用六套56项及build证据。旧74项/hash记录为此前版本历史，不作为本补丁通过证据。
