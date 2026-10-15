# 追溯、分析与视觉训练 SOP 第一批

用户授权：已有功能直接复用，优先接通并验收数据追溯和数据分析；整理现有 VisionLab 为可接入对话建厂的标准训练 SOP，各工厂独立训练和验收，不将六膳门模型当通用模型。

Base SHA: `8ffe5a551704ba962af75566292d1fe8656db06a`。关联建厂仓库只读基线 `79e2e9b78fd5fee77a8c3d3704e7ef32572266de`；本批不修改其封闭 v2 能力目录。

## Scope 和唯一 Owner

| ID | Owner/worktree | 允许修改 | 禁止修改 | 验收 |
|---|---|---|---|---|
| OPT-DATA-20261015-TR1 | root；cretas-trace-analysis-sop-20261015 | 追溯 Controller/DTO/Service、既有 Web traceability 页面/API/helper、对应目标测试；协调者独占 ACTIVE/归档及 Python main.py 路由挂载 | 不改已有 Entity/Repository 查询，不改生产数据或猜补历史血缘；不改其他任务 scope | Maven TraceabilityServiceImplTest,TraceabilityServiceImplLineageTest,TraceabilityControllerScanTest 及新增目标契约；Web 追溯目标测试、类型检查、构建与真实浏览器目标路径 |
| OPT-DATA-20261015-AN1 | analytics executor；cretas-analysis-provenance-20261015 | service/indicator/dto/IndicatorValueResult.java、service/indicator/impl/IndicatorQueryServiceImpl.java、service/indicator/strategy 仅确证同因修复、相关 Controller/Tool 最小兼容消费与目标测试；必要 Web IndicatorValueCard/指标值渲染和对应测试先回报扩锁 | 不改 Entity/Repository/Flyway/pom、全局权限、ReportTrendsTool、共享 ACTIVE、追溯或视觉文件 | mvn test -Dcretas.testIncludes=**/IndicatorQueryServiceImplTest.java,**/IndicatorControllerTest.java,**/IndicatorQueryToolTest.java -Dtest=IndicatorQueryServiceImplTest,IndicatorControllerTest,IndicatorQueryToolTest；补新真实方法测试进入同一选择器 |
| OPT-DATA-20261015-VS1 | vision executor；cretas-vision-training-sop-20261015 | 新 backend/python/vision_training/**，新 backend/python/tests/test_vision_training_*.py；如需现有文件接线先回报协调者 | 不改 tools/vision-lab 现有代码/测试，不动人工标注、原图、保护集、模型、计划任务、发布/训练配置；不启动真实训练或发布，不改 main.py/ACTIVE | B:/anaconda3/python.exe -m pytest backend/python/tests/test_vision_training_*.py；任务规范、模板、工厂隔离、阶段/回执约束与失败对照 |

执行代理只提交自有路径并返回结构化回执，不合并、不部署；协调者审查后整合。不同 worktree 的 Maven target 互不共享；同一 target 的 Maven 顺序执行。

## 第一批完成标准

1. 追溯真实入口明确显示实际证据与缺失，不以空图/数量零冒充完整；沿用既有图和业务服务。
2. 指标真实零、未计算/无样本和失败可区分；来源与更新时间可信，兼容既有消费方，不新增指标真值库。
3. 视觉 SOP 支持版本化任务/配方、数据与模型适用范围、阶段回执及每厂独立验收；对话建厂可调用受约束模板/项目接口；只有被明确注册的任务族可用，不默认跨厂套用权重。
4. 原有六膳门流程和固定测试保持兼容，不启动真实训练；本批只验证编排与隔离，不签跨厂模型效果。
5. 实现、目标测试、浏览器/API读回、commit/push/PR、合并、部署分别记录。生产业务写入为零。

## UX Flow Analysis

用户：工厂/质量负责人确认追溯和指标；操作员复用现有批次入口；实施人员通过结构化配置创建视觉训练项目。

旅程：进入既有批次或指标 → 查看来源/完整性 → 明确缺项与下一步；视觉项目：选择受支持模板 → 核对工厂/工位/产品范围 → 保存草稿 → 查看可执行阶段和缺少证据。

摩擦与回应：F1 身份不清（HIGH，防呆2）→显式批次/工厂上下文；F2 未计算显示0（HIGH，真实数据规则）→明确不可用状态；F3 空图假完整（HIGH，证据规则）→显示缺失且提供补关联入口；F4 长任务假进度（HIGH，AI交互规则）→仅显示真实阶段回执；F5 误触/重复（MED，防呆4）→沿用幂等与现有组件，保留输入。

界面实现复用现有组件，单一主动作；错误说明发生什么和如何处理，保留输入，触控至少44pt、间距8px。不得通过新增无数据大屏代替真实入口。

## 回执

三项实现与目标验证完成，状态 `review`；写 scope 已收口。PR、合并与部署以 Git/发布回执为准，本文不预签这些状态。

追溯范围细化：TR1 包含 service/lineage/LineageGraphAssembler.java 和对应目标测试。仅在无图证据时核实召回根实体属于当前工厂，未知根不得返回全零；已有图证据的归档节点保留 forensic 查询能力。复用现有 Repository 方法，不改查询或 Entity。

协调者接线扩展：root 独占 web-admin/src/api/indicator.ts、views/indicator-center/IndicatorDetailDrawer.vue 及其目标测试；取消当前值 null/失败时静默使用旧 lastValue，显示状态/来源/时间。root 独占新 vision_training/runtime.py 和 test_vision_training_entry_contract.py：持久化目录必须在代码树外并启动验证。执行者继续独占其余新 vision_training 文件。

### 已完成

- TR1：复用既有追溯 API/Service/血缘图/Web；空图根不存在或跨厂时返回明确失败，保留已有图的归档证据查询。
- AN1：AVAILABLE/NO_DATA/NOT_COMPUTED/FAILED；真实零不变，未计算不造零/新时间，Controller/Tool 保留来源。Web 当前值不回退旧 lastValue，显示状态/来源/时间。
- VS1：有限模板、JSON Schema、可信工厂与角色、只预览/保存/读回/计划 API；专用模板默认拒绝，工厂 allowlist；草稿树外持久化、跨进程原子创建；回执隔离与训练/验收/启用分离。标准流程见 `docs/vision-training-sop.md`。

### 验证与证据

本机证据根目录：`C:/Users/Steve/Documents/cretas-implementation-20261015`。

- Java 最终 `package`：精确选择 Traceability/Lineage 6 类、Indicator Service/Controller/Tool 3 类及 opt-in TraceabilityPostgresAcceptanceTest，67/67、0 skipped、rc=0，`trace/final-package.log`。Windows 使用原生 Java Maven launcher，compiler 的单 include 用 `%regex[...]`；逗号仅用于 surefire `-Dtest`。早先误用逗号 include 的 No tests matched 失败原样保留。
- 隔离 PG17：127.0.0.1:55458 / cretas_workflow_verify_trace_20261015，非共享 DB；实际 Controller→Service→JPA→PG，批次、原料、血缘、质检、指标及版本读回；无样本 null、10 件合格/0 不合格得到真实 0%、未计算 null/null 时间；跨厂批次不返回数据。测试未导入生产认证链、不执行生产 Flyway，不代表全库 schema 验收。旧 String/jsonb fixture INSERT 不兼容通过显式隔离 SQL fixture 处理，未改 Entity。
- 浏览器：实际 Vue 追溯页与指标抽屉→本地实际 API→隔离 PG，通过；截图/读回见 `trace/postgres-*.png/json`。LLM chart-insight 明确排除，以本地 honest-null fixture 隔离，不能签完整 AI 分析效果；生产业务写入 0。fixture 关闭后补拍失败为环境已关闭，不覆盖此前通过的查询/数值证据；不签布局验收。
- Web：追溯 44 与指标 3，共 47/47；最终指标目标再验 3/3；vue-tsc -b rc=0，Vite build rc=0。
- Python：最终 28/28、rc=0，含真实 JWT middleware、数值 userId、跨厂/平台拒绝、模板篡改、非法回执、双进程同 key 保存、重建 service 后读回与 storage probe。实际 main.app 预览→保存→读回与匿名/跨厂对照通过；未启动其余 cron 的完整 lifespan。专用模型精度/真实 runner/启用均未验。
- Analytics mutation：强制物理时间 now，13 条中 4 条失败；恢复后 13/13。Trace unknown/foreign/malformed 根修复前 3 条断言失败，修复后通过。原始失败日志保留。
- 独立风险复核：此前 preview 篡改、无效资产冒成功、专用租户、numeric userId、角色、幂等与部署删草稿问题已修，无剩余源码 blocker。断电级目录 fsync 不在本版草稿持久化承诺内。

### 尚未交付

关联建厂仓库封闭 v2 目录与完整无代码 UI 未改；五个 API 为显式后续接入口。未启动真实采集/训练，未动原图、人工标签、保护集、历史模型或计划任务；未签任何新工厂模型效果。生产部署/生产只读业务验收分别依发布回执和后续验收记录判定。

发布前补查：底层 PythonSmartBIClient.fetchIndicatorValue 原有未实现 stub 直接返 ZERO。scope 增加该方法、supportsIndicatorValueEndpoint 能力声明和 PythonIndicatorSourceContractTest（root 唯一 Owner）。改为显式 UnsupportedOperationException，经既有服务返回 FAILED/null/null 时间；CACHED 在已配置但未实现的 Python 源上也不能返回旧占位缓存，不删除历史快照。真实业务 JPA 聚合与 SmartBI 已有分析接口直接复用，通用 Python 指标桥接仍未接入，不能把此项写成已接通。
