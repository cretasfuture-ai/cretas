# AI 写操作入口契约覆盖核查

**日期**：2026-11-04　**受测代码**：`e83f2bdebb`（origin/main）
**方式**：只读静态核查。没有运行代码，没有查生产库，本文所有行为结论都来自读代码。
**路径缩写**：`A/` = `backend/java/cretas-api/src/main/java/com/cretas/aims/`，`R/` = `backend/java/cretas-api/src/main/resources/`

## 1. 结论

「统一执行入口」目前还不存在。代码里有一个写得很严的网关 `DefaultToolExecutionGateway`，但 AI 写操作的主路径不走它。

| 问题 | 答案 |
|---|---|
| 写入口总数 | **20 个**：14 个能真实写入，6 个写操作已被堵死 |
| 12 项契约全部做到的入口 | **0 个** |
| 最接近的入口 | 受控采购（E2–E4）、AI 建产品（E5）、定时委托写（E7） |
| 对话确认卡主路径（E1）走网关吗 | **不走**，确认后直接 `tool.execute` |
| 最缺的三项 | 审批绑定方案版本（真正生效的入口只有 E2–E4，定时任务另有授权绑定）、状态版本重校验、补偿 |

**三个需要先处理的问题**（详见第 5 节）：

1. **审批没有真正实现。** 系统回复「审批请求已提交」，但什么都没有提交。预览后确认这条路完全不查审批。
2. **同样的写操作 5 分钟内做第二次，可能不执行却报成功。** 冗余缓存没有排除写工具。这是代码推导，尚未实测。
3. **餐饮菜品删除配了 `requires_approval=TRUE` 但不生效。** 判定还要求敏感度是 CRITICAL，而它是 HIGH。

下面这些已经做得好，不用重做：

- 确认令牌：绑定命令摘要、原子认领、一次性使用。
- 写工具判定：未声明的工具默认按写处理。
- 进程内确认标记：无法从请求里伪造。
- 网关的幂等账本和 `OUTCOME_UNKNOWN` 语义。
- 对话之外的执行点（E15–E18）里，写工具都过不了 W0 写确认闸。

## 2. 入口怎么数

**写入口**：一条能让 AI 或 Agent 发起的动作落库的执行路径。判定方法：

1. 搜全部 `executor.execute` / `tool.execute` / `gateway.execute` 调用点，共 16 处。
2. 搜全部 `createBoundToken`、`claimToken`、`withServerConfirmation` 调用点，确认没有漏掉的确认入口。
3. 加上任务点名的缺料处理接口。

**不按行展开的两类**（第 6 节给总数）：

- 227 个写工具：它们是入口背后的执行体，能走到哪个入口由第 3.2 节决定。
- 1429 个通用 REST 写接口：人工操作接口，不是 AI 入口。

## 3. 矩阵

### 3.1 判定标准

- **有**：该入口上这一项由框架强制，绕不过去。
- **部分**：有实现，但只覆盖一部分情况，或者靠工具自觉。
- **无**：代码里找不到。
- **未核实**：结论取决于生产配置或逐工具代码，本次没有看。
- **—**：对该入口不适用。

12 项契约的列名：

| 列 | 契约项 | 列 | 契约项 |
|---|---|---|---|
| ① | 输入 Schema | ⑦ | 审批绑定方案版本 |
| ② | 资源范围 | ⑧ | 幂等 |
| ③ | 前置条件 | ⑨ | 状态版本与执行前重校验 |
| ④ | 硬约束 | ⑩ | 执行验证 |
| ⑤ | 权限交集 | ⑪ | 补偿 |
| ⑥ | 影响预览 | ⑫ | 审计 |

### 3.2 能真实写入的入口（14 个）

| ID | 入口 | ① | ② | ③ | ④ | ⑤ | ⑥ | ⑦ | ⑧ | ⑨ | ⑩ | ⑪ | ⑫ |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E1 | 对话确认卡 `POST /confirm` → `ToolDispatchService` 直调 | 部分 | 部分 | 部分 | 未核实 | 部分 | 部分 | 无 | 部分 | 部分 | 无 | 无 | 部分 |
| E2 | 受控采购确认 `confirmControlledPurchase` | 部分 | 有 | 有 | 有 | 部分 | 有 | 有 | 有 | 有 | 部分 | 无 | 有 |
| E3 | 采购授权稍后执行 `AiPurchaseTaskGrantController` / `PurchaseGrantCallerController` | 部分 | 有 | 有 | 有 | 有 | 有 | 有 | 有 | 有 | 部分 | 部分 | 有 |
| E4 | 采购授权定时执行 `PurchaseGrantTimerExecutionService` | 部分 | 有 | 有 | 有 | 有 | 有 | 有 | 有 | 有 | 部分 | 部分 | 有 |
| E5 | AI 建产品 `AiProductCreateController` → 网关 | 部分 | 有 | 未核实 | 未核实 | 部分 | 有 | — | 有 | 部分 | 部分 | 无 | 有 |
| E6 | Canvas `POST /apply-diffs` 直调 | 部分 | 部分 | 未核实 | 未核实 | 部分 | 部分 | 无 | 无 | 无 | 部分 | 无 | 部分 |
| E7 | 定时委托写 `AutonomousToolRunner`（目前只有 `alert_rule_toggle`） | 部分 | 有 | 有 | 有 | 有 | — | 有 | 有 | 有 | 部分 | 无 | 有 |
| E8 | SOP 工具链 `SopAgentOrchestratorImpl` | 部分 | 部分 | 无 | 未核实 | 部分 | 无 | 无 | 无 | 无 | 无 | 无 | 无 |
| E9 | 餐饮 Agent 动作确认 `RestaurantAgentActionWorkflowService` | 部分 | 有 | 有 | 未核实 | 部分 | 部分 | 无 | 有 | 有 | 部分 | 无 | 部分 |
| E10 | 缺料：生成采购草稿 | 部分 | 有 | 有 | 部分 | 部分 | 部分 | 无 | 有 | 部分 | 有 | 无 | 有 |
| E11 | 缺料：应用重排方案 | 部分 | 有 | 有 | 有 | 部分 | 有 | 无 | 有 | 有 | 有 | 无 | 有 |
| E12 | 缺料：取消 / 转人工 / 关闭 | 部分 | 有 | 有 | 部分 | 部分 | 无 | 无 | 未核实 | 部分 | 无 | 无 | 有 |
| E13 | 缺料：生成重排方案（派生写） | 部分 | 有 | 有 | — | 部分 | — | — | 未核实 | — | — | — | 部分 |
| E14 | 缺料：刷新 / 手动再核对（派生写） | 部分 | 有 | 部分 | — | 部分 | — | — | 无 | — | — | — | 部分 |

E7 的影响预览标「—」：授权在配置时发放，运行时无人可看。E5 的审批标「—」：该工具策略为不需要审批，没有审批需求。

### 3.3 写操作已被堵死的入口（6 个）

这些入口能执行工具，但写工具到不了业务层。原因是 `withServerConfirmation` 全仓只有两个调用点，都不在这些路径上。

| ID | 入口 | 怎么堵的 | 证据 |
|---|---|---|---|
| E15 | LLM 兜底工具调用 | W0 对空 Map 判确认，恒为未确认 | `A/service/impl/LlmIntentFallbackClientImpl.java:3048` |
| E16 | 工具链 `ToolRouterServiceImpl` | W0 拦截 | `A/service/impl/ToolRouterServiceImpl.java:593` |
| E17 | Auto-Planner | W0 拦截 | `A/service/execution/DynamicToolSelectionService.java:726` |
| E18 | Skill 编排（采购桥除外） | W0 拦截；受治理工具走网关且不代确认 | `A/service/skill/impl/SkillExecutorImpl.java:968` |
| E19 | MCP | 只放行 READ / ANALYZE | `A/mcp/MCPServerAdapter.java:319` |
| E20 | 事件触发链 `TriggerChainExecutor` | 非 SCHEDULER 来源的写一律拒绝 | `A/engine/AutonomousToolRunner.java:114` |

E18 的 `SkillExecutorImpl` 里，采购桥是唯一持有确认标记的调用方，算作 E3 的一部分。

### 3.4 写工具实际能走到哪里

静态统计 `tool-descriptors.yaml`（587 个工具）和工具源码：

| 读数 | 数值 | 含义 |
|---|---|---|
| 写工具 | 227 | 声明为 WRITE，或名字命中写动词 |
| 支持预览 | 48 | 只有这些才能拿到确认令牌 |
| 不支持预览 | 179 | 对话里永远走不到执行，停在确认提示 |
| 工具层无任何权限声明 | 82 | 其中 9 个支持预览 |
| 写工具但 `actionType=READ` | 109 | 确认卡显示的类型和风险不可靠 |
| 写工具 `riskLevel=LOW` | 150 | 同上，例如 `inventory_operation` |

这是**故障安全**：179 个工具因此无法执行，而不是绕过确认。代价是用户在对话里提出这些操作时会碰壁。

## 4. 逐入口证据

### E1 对话确认卡

| 项 | 判定 | 证据 |
|---|---|---|
| ① | 部分 | 框架只查必填是否存在，不校验类型、枚举、范围：`A/ai/tool/AbstractBusinessTool.java:128` |
| ② | 部分 | 工厂由令牌绑定：`A/service/impl/PreviewTokenServiceImpl.java:178`。对象是否属于本厂靠各工具自己查，未逐个核实 |
| ③ | 部分 | 工厂开关、一线开放表：`A/service/execution/ToolDispatchService.java:193`、`:214`。业务前置靠工具 `doPreview`；预览说不行就不铸令牌：`:980`。确认路径没看到 Drools 校验，它只在 `A/service/execution/IntentExecutionOrchestrator.java:909` 的主识别路径 |
| ⑤ | 部分 | 见第 5 节 P1-5 |
| ⑥ | 部分 | 改前改后和影响说明写进确认卡：`ToolDispatchService.java:1017`。只有 48 个工具有；`riskLevel` 不可靠 |
| ⑦ | 无 | 见第 5 节 P0-1 |
| ⑧ | 部分 | 令牌一次性使用：`PreviewTokenServiceImpl.java:230`。没有业务幂等键，换一张令牌可以重做 |
| ⑨ | 部分 | 绑定工具名和工具版本：`IntentExecutionOrchestrator.java:1341`、`:1362`。不绑定对象版本：令牌只有 `descriptorVersion`，`A/entity/intent/IntentPreviewToken.java:87` |
| ⑩ | 无 | `success=true` 就算成功：`ToolDispatchService.java:562`。D7 规则见 `:1788`，`success=true` 恒判有效，所以 `:613` 起的 LLM 结果校验在成功时走不到 |
| ⑪ | 无 | 没有补偿机制 |
| ⑫ | 部分 | `tool_call_records` 有参数和结果，但写入时没填 `userId`：`ToolDispatchService.java:567`（实体有此字段：`A/entity/calibration/ToolCallRecord.java:44`）。令牌表保存改前改后。不进网关审计表 |

### E2–E4 受控采购

| 项 | 判定 | 证据 |
|---|---|---|
| ⑤ | 部分 / 有 | E2 重跑意图权限：`A/service/execution/IntentExecutionOrchestrator.java:1464`。E3 走完整权限链：`A/service/intent/PurchaseGrantCallerService.java:58` |
| ⑦⑨ | 有 | 持久授权带版本，行锁下校验：`A/service/intent/ControlledPurchaseTaskExecutionService.java:108`、`:257` |
| ⑧ | 有 | 完成后按预览身份回放：`IntentExecutionOrchestrator.java:1281`（调用 `controlledPurchase.replay`） |
| ⑩ | 部分 | 校验结果绑定本授权且状态为 DRAFT：`PurchaseGrantCallerService.java:97`。没有业务达成核对 |
| ⑪ | 部分 | 可撤销未执行的授权：`ControlledPurchaseTaskExecutionService.java:333`。已生成的草稿无补偿 |
| ⑫ | 有 | 拒绝也留痕：`PurchaseGrantCallerService.java:135` |

### E5 AI 建产品

- ⑧ 有：网关幂等预留，`A/ai/tool/gateway/DefaultToolExecutionGateway.java:157`。
- ⑨ 部分：摘要绑定，无对象状态版本。
- ⑩ 部分：结果未知时报 `OUTCOME_UNKNOWN`，`DefaultToolExecutionGateway.java:262`。没有业务核对。
- ⑫ 有：`A/ai/tool/gateway/ToolExecutionLedgerService.java:31`。
- ⑤ 部分：`@RequirePermission` 加网关策略，`A/controller/AiProductCreateController.java:95`。没有 Agent 维度。

### E6 Canvas apply-diffs

- ⑤ 部分：`@RequireRole`，`A/controller/CanvasAIController.java:797`。工具只按 `canvas_` 前缀放行：`:248`。
- ⑥⑦ 部分 / 无：diff 由客户端提交，服务端没有绑定 `/chat` 生成的方案：`:820`。**审计含义**：用户看到的方案和实际执行的方案没有服务端绑定。
- ⑧ 无：`operationId` 每次新生成：`:815`。
- ⑩ 部分：`false` 和异常都如实报 UNKNOWN，这部分写得好：`:859`、`:871`。
- ⑫ 部分：日志和 HTTP 请求审计，不进工具账本。

### E7 定时委托写

授权绑定配置版本、工具版本、参数和用户：`A/engine/AutonomousToolRunner.java:161`。按计划触发时刻幂等：`:128`。总开关：`:138`。拒绝也写审计：`:218`。

### E8 SOP 工具链

- 没有 W0：全文件不引用 `writeGuardService`，权限后直接执行：`A/service/impl/SopAgentOrchestratorImpl.java:304`、`:318`。
- 默认链最后一步写 SKU 复杂度，参数来自 LLM 分析：`:156`（`sku_update_complexity`，声明为写：`A/ai/tool/impl/sop/SkuUpdateComplexityTool.java:312`）。
- 工具链来自 `ai_agent_rules` 配置，理论上可配任何工具：`A/service/impl/SopAgentOrchestratorImpl.java:87`。配置需要管理员权限：`A/controller/AiAgentRuleController.java:123`。
- 触发需 `production:read_write`：`A/controller/SopController.java:84`。

### E9 餐饮 Agent 动作

- 令牌认领没传命令摘要：`A/service/restaurant/RestaurantAgentActionWorkflowService.java:129`。
- 执行前重放并比对结果摘要：`:139`，这一项做得好。
- 餐饮已废弃，只记录不建议投入。

### E10–E14 缺料处理（#1407 #1411）

| 项 | 判定 | 证据 |
|---|---|---|
| ② | 有 | 按工厂取实例：`A/controller/ShortageCaseActionController.java:151`；身份校验：`:157` |
| ⑧ E10 | 有 | 同物料已有在走的单就复用：`A/service/shortage/ShortageCaseActionService.java:111` |
| ⑨ E11 | 有 | 行锁加排程变更检查，变过就 409：`A/service/shortage/ShortageRescheduleService.java:148`、`:159`、`:168` |
| ⑩ E10/E11 | 有 | `ShortageOutcomeCheckService` 四态结论，只有 ACHIEVED 算达成：`A/service/shortage/ShortageOutcomeCheckService.java:45` |
| ⑩ E12 | 无 | `decide` 后不核对：`ShortageCaseActionController.java:146` |
| ⑫ | 有 | `ShortageCaseAction` 台账：`ShortageCaseActionService.java:161` |

**#1407 的一个缺口**：核对在业务事务之外，排在写入之后。写入已提交后，`check()` 抛异常时接口返回失败，用户会以为没成功。例如 `ShortageOutcomeCheckService.java:49` 的 409。证据：`A/controller/ShortageRescheduleController.java:90` 先 apply，`:97` 再核对。

缺料接口是**人工操作接口**，目前没有 AI Tool 调用它们。它们的权限是 `@RequirePermission`，没有 Agent 和任务授权维度。

## 5. 最小补齐清单（按风险排序）

工作量：S 不到半天，M 一到两天，L 需要设计。每条都是最小改法，不改架构。

### P0：可能造成错误数据或虚假说法

**P0-1 审批没有真正实现**（M，需要 Steve 定产品口径）

- 回复说「审批请求已提交」，代码只返回一个状态，没有创建任何记录：`A/service/execution/IntentExecutionOrchestrator.java:3001`。这违反「禁止假数据」原则。
- 审批闸只拦非预览、非 `forceExecute`：`:1169`。多意图和对话续接会把 `forceExecute` 硬设为 true：`A/service/execution/MultiIntentExecutionService.java:200`。这两条路径上，W0 仍要确认，但审批不再拦。
- `confirmInScope` 全程不看 `needsApproval`：`IntentExecutionOrchestrator.java:1256`。用户先预览、再确认，就绕过了审批。
- 网关对需要审批的工具一律拒绝，`ApprovalProof` 从未被校验：`A/ai/tool/gateway/DefaultToolExecutionGateway.java:90`。
- `needsApproval()` 要求 `requires_approval` 且 CRITICAL：`A/entity/config/AIIntentConfig.java:371`。而 `RESTAURANT_DISH_DELETE` 是 HIGH 加 TRUE：`R/db/flyway/V20261029_28__bind_restaurant_dish_delete_tool.sql:7`。配置了审批，实际不审批。

最小修法：先让 `confirmInScope` 对 `needsApproval` 的意图 fail closed，并把回复改成真话。真正的审批流再按 Steve 的口径做，绑定 `commandDigest`。

**P0-2 冗余缓存可能吞掉写操作**（S）

- 冗余检查只排除供应商比价，没排除写工具：`A/service/execution/ToolDispatchService.java:497`。
- 确认请求不带 sessionId，全部落到 `"default"`：`:496`，以及 `IntentExecutionOrchestrator.java:1401` 的构造。
- 成功结果缓存 5 分钟：`A/service/calibration/impl/ToolCallRedundancyServiceImpl.java:65`。键是会话、工具和参数哈希，不含令牌：`:298`。

推导出的后果：同一用户 5 分钟内用相同参数第二次确认（比如领料 30 公斤做两次），工具不执行，返回缓存的成功，令牌记 CONFIRMED。库存会少扣。

**这是静态推导，没有实测。** 修之前先写一条失败的回归测试证实。最小修法：写工具跳过冗余检查和缓存。

**P0-3 受治理的写工具在对话路径绕过网关**（M）

- `restaurant_dish_delete` 的网关策略要求确认和幂等：`R/ai/tool/gateway/runtime-tool-policies.yaml:26`。
- 但 `ToolDispatchService` 只把 `material_stock_summary` 和迁移名单送网关：`ToolDispatchService.java:475`。迁移开关默认关：`R/application.properties:107`，名单也只有两个读工具。
- Skill 路径已有「受治理工具绝不走旧路径」：`A/service/skill/impl/SkillExecutorImpl.java:954`。对话路径没有。

最小修法：在 `executeWithTool` 加同样的判断。这是把 E1 迁到网关的第一步，之后每迁一个工具就登记一条策略。

### P1：缺少防护，暂未发现直接错误

**P1-1 SOP 工具链没有 W0**（S）。见 E8。最小修法：`stepDenied` 加写判定，非白名单写工具拒绝，`sku_update_complexity` 单独登记并说明理由。

**P1-2 Canvas 方案没有服务端绑定**（M）。见 E6。最小修法：`/chat` 生成方案时铸令牌绑定 diffs 摘要，`/apply-diffs` 校验。

**P1-3 结果不明的状态在 E1 上不存在**（M）。`confirmInScope` 异常一律记 FAILED：`IntentExecutionOrchestrator.java:1453`。写已提交后抛异常时，用户会看到失败并重做。网关和 Canvas 都已正确处理这种情况。最小修法：写工具异常改报 `OUTCOME_UNKNOWN`，令牌记不可重试。随 P0-3 迁网关后自然解决。

**P1-4 确认卡不绑定对象状态**（L）。预览到确认最长 5 分钟，令牌里没有对象版本。91 个实体有 `@Version`，但令牌不用。最小修法：`BoundTokenRequest` 增加可选 `entityVersion`，先给库存、放行、月结三类高风险工具接上。

**P1-5 权限交集不完整**（M）。按「用户 ∩ Agent ∩ 任务授权 ∩ 对象/工厂 ∩ 审批结果」逐项看：

| 维度 | 现状 | 证据 |
|---|---|---|
| 用户 | 有。82 个写工具工具层无声明，靠意图层 | 未映射直接放行：`A/ai/tool/ToolRbacEnforcer.java:273` |
| Agent | **无**。没有 Agent 身份这个概念 | 只有角色 × 意图开放表：`A/service/intent/FrontlineAiWriteGrantService.java:124` |
| 任务授权 | 只有采购和定时写 | `A/engine/AutonomousToolRunner.java:161` |
| 对象 / 工厂 | 工厂有，对象靠工具自己 | `A/service/impl/PreviewTokenServiceImpl.java:178` |
| 审批结果 | **无**（见 P0-1） | — |

另外确认时不重跑意图权限门：只有采购分支调用 `checkIntentPermission`。工具层无声明的写工具，确认时不再校验意图权限。最小修法：`confirmInScope` 通用分支补一次 `checkIntentPermission`（S）；再把 9 个支持预览但工具层无声明的工具补上权限声明（S）。

**P1-6 #1407 核对失败会把成功报成失败**（S）。见 E10–E14。最小修法：核对失败时捕获异常，写入成功照报，并提示核对未完成。

### P2：完善项

| 编号 | 问题 | 最小修法 | 量 |
|---|---|---|---|
| P2-1 | 确认后仍可能 LLM 增补参数，摘要不再计算：`ToolDispatchService.java:447` | 确认路径跳过抽参和规则抽参 | S |
| P2-2 | 输入只查必填：`AbstractBusinessTool.java:128` | 在基类按 `getParametersSchema` 校验类型和枚举 | M |
| P2-3 | `actionType` 按名字猜，109 个写工具标 READ：`AbstractBusinessTool.java:310` | 确认卡改用访问模式；高风险工具显式声明 | M |
| P2-4 | 执行验证只有缺料有 | 按 #1407 的模式给库存、放行各做一个，不做通用框架 | L |
| P2-5 | 补偿全无 | 先列哪些写可逆，不急于实现 | L |
| P2-6 | `tool_call_records` 没写 `userId`：`ToolDispatchService.java:567` | 补字段 | S |
| P2-7 | 179 个写工具对话里走不通 | 按真实需求补预览，不要批量补 | 按需 |

## 6. 未核实

| 项 | 为什么没核实 | 怎么补 |
|---|---|---|
| 生产有多少意图 `requires_approval` 且 CRITICAL | 没查库 | `SELECT intent_code, sensitivity_level, requires_approval, tool_name FROM ai_intent_configs WHERE requires_approval;` |
| 写意图的 `required_permission` 是否都配了 | 同上。82 个工具的实际保护取决于它 | 同表查询 |
| P0-2 的实际后果 | 只做了静态推导 | 写回归测试 |
| 生产 `CRETAS_AI_TOOL_GATEWAY_INTENT_DISPATCH_MIGRATION_ENABLED` 取值 | 没看 `.env.prod`。代码默认关 | 读活进程环境 |
| 48 个可确认工具各自的前置条件和对象范围校验 | 没有逐个读，标「部分 / 未核实」 | 按风险抽查 |
| 1429 个通用 REST 写接口 | 不是 AI 入口。启发式扫描 1278 个带权限注解，151 个没扫到（可能在服务层校验，如 `SavedSkillService`，不等于没保护） | 单独任务 |
| AI 配置类写接口（意图、规则、触发链、定时任务） | 归入上一行，改的是「AI 能做什么」，值得单独核 | 单独任务 |
| Python 服务里的写操作 | 本次范围只含 `backend/java` | 单独任务 |

未碰 LIUSHANMEN 数据和黑湖相关代码。
