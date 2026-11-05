# 产品销售排行语义切片审计

任务 SEMANTIC-SALES-20261105。Owner 01a1a59f-7fcf-76f2-82a2-2cfd0d5fce98。
起点 origin/main ecfb04cbac6283ac4e1b70592f4643be5feb44b8，独立 worktree `C:/Users/Steve/cretas-semantic-sales-20261105`。
输入为 Documents/Cretas语义架构方案-20261105/主Chat审计交接.md 和 语义场景候选.json。
该输入是方案，未转移写 Owner；20 组场景是已公开开发候选，非独立 gold、未获人工真值审批、不得训练。

## 节点与门禁

旧训练/教师 worktree HEAD 946fc6e5422c7a352b0cfcf65c9a39ed1b68df6a 保留；PR1473 与本任务分开。
ACTIVE 的 SEC-PLAN-AUDIT-MAIN-FREEZE-20261105 仍有效，允许分支开发/测试，禁止本任务合 main、推 main、部署。
本次 scope 是 ranking Tool、execution 普通/SSE/dispatch、既有 Conversation EntitySlot DTO、目标回归与审计；未改 Entity/Repository/JPQL/模型/训练数据。
READ_QUERY 是原 ConversationMemory 的 JSON 实体槽类型，复用同一归属/过期/持久化服务，没有新会话表、IntentHandler 或平行状态存储。

## 审计裁定

| 提案项 | 源码证据 / 分类 | 裁定与动作 |
|---|---|---|
| 排行固定金额，无 metric | ProductSalesRankingTool schema/date/limit；ProductSalesRankingService amount 降序；已证实缺口 | 采纳：显式金额能力，拒绝 quantity，不能把金额榜称为销量榜 |
| 默认本月/前10 | Tool 必需 start/end，默认 limit20；SmartClarification 局部默认 TODAY；本月不是现有统一口径 | 修改：本切片显式定义产品排行 MTD（Asia/Shanghai）+金额+前20；结果展示日期/口径；不全局改默认 |
| 时间续句 / 已填指标纠正 | 普通 continuation 与 SSE SlotAnswerRule 不同；仅凭源码不能断言所有模型路径失败 | 修改：为已有排行 READ 增加共同参数策略；服务入口回放分别验证，不宣称全局会话重写 |
| 取消/换话题的已有能力 | ConversationServiceImpl 已有取消、归属、过期；SSE 不像补槽答案时取消旧收集 | 采纳“复用”建议；若解读成完全缺失则不成立。复用 cancelConversation，不重造数量纠正和写确认 |
| 工厂续句映射覆盖不足 | 旧专用短句映射偏餐饮，但还存在模型/通用分支 | 仅待复现风险：限定目标改动；不能据此宣称所有工厂短句必失败 |
| 快速路径绕语境 | early phrase 在 continuation 之前 | 修改：共享排行续句政策位于 shortcut/slot 之前；仍经既有显式意图/权限/Tool 路由 |
| 中文数字纠正机制缺失 | FollowUpResolver.parseNumber 和已有数量纠正规则存在 | 不成立：前五个复用数字解析；不重写物料 quantity 纠正机制 |
| 迟到结果恢复旧任务 | 原排行没有本轮查询标识；存在异步抽参与 Tool 调用 | 采纳限定修复：现有记忆槽保存本轮标识；抽参结束和 Tool 结果返回核对；不是数据库原子并发/传输交付完整证明 |
| 扩到库存、订单只读 | MaterialBatchQueryTool、crm/OrderListTool 及 Tool/权限链已存在；订单 schema 已支持 startDate/endDate/status | 修改：仅扩当前查询取消/切换/无旧排行条件继承；既有业务 Tool 不复制；未宣称其全部日期/状态纠正已闭环 |
| 无约束组合“第一名→库存” | 候选场景不是已有 Skill 执行/ID 绑定证明 | 暂缓：只在真实 Skill 与真实产品 ID 依赖已核验后扩展，不凭名称拼接 |
| 品类/赠品/退货过滤 | 当前 ranking schema 没有这些业务过滤；字段/来源含义不能由模型猜 | 暂缓扩能力，不能把这些过滤承诺为支持；未来需对应 Tool/数据契约和回归 |
| 所有问题靠新训练/多 Agent | 97.11% 是773/796离线标签判定；非多轮 E2E | 不采纳：本轮无训练、教师投票或外部 API；不把测试 fixture 当独立验收数据 |

## 业务口径

Service/Repository 保持原样：订单日期含首尾；SO_ALL 排除 DRAFT/CANCELLED/FINANCE_REJECTED（不能称为仅审批完成订单）。
金额是订单行 quantity×unitPrice×折扣聚合，非收款/会计营收。包装归一化、SELF/部门/工厂权限与金额可见性保持既有规则。
数量只按单位分桶展示；此切片不实现数量排行，也不询问单位后假装支持。明确提示可改看金额。
默认 MTD 只补无日期范围的排行，不补造部分日期，不覆盖已有明确范围。

## 验证范围与限制

新增公开开发回归：SalesRankingSemanticContractTest、SalesRankingEntryParityContractTest。
服务入口回放使用真实普通/SSE 入口、共享政策、dispatch 与排行 Tool；初始意图配置、权限、模型/抽参及业务数据依赖受控。
它能证明给定前提下参数/能力/状态行为，不能证明真实模型识别率、线上端到端成功率、实际延迟和费用。
任何传输前最后一瞬间的取消与跨 JVM 同时修改仍需真实并发验证，不凭本轮顺序测试宣称全覆盖。
首轮完整 Maven 目标回归在6fe3a82375为197/197（0失败/错误/跳过），包含新增语义回归19项；同次 package 退出0。
随后补充了条数0/1000的边界拒绝，语义回归增至21项；先前197项证据不冒充此追加变更的最终验证。当前提交的精确测试数、命令和package结果以结构化回执及PR1476为准。
命令选择10个测试类：SalesRankingSemanticContractTest、SalesRankingEntryParityContractTest、ProductSalesRankingScopeContractTest、FactoryCatalogToolBehaviorContractTest、FollowUpResolverTest、SlotAnswerRuleTest、SseStreamingServiceFollowUpTest、ToolDispatchServiceLastActionTest、ConversationMemoryOwnershipContractTest、IntentExecutionOrchestratorSuggestedActionSecurityTest。
普通/SSE初始意图和其他业务Tool由fixture给定，模型/抽参及权限依赖受控；库存/订单仅验证转交现有READ和取消，没有宣称它们的SQL/日期/状态纠正完整通过。两个入口的会话归属拒绝状态名沿用各自既有契约，安全动作均为拒绝且不查询。
最终命令、源码/测试哈希、JAR哈希与报告哈希在 `C:/Users/Steve/Documents/Cretas语义架构方案-20261105/execution-receipt.json` 归档；失败/未验证项未计入成功。
重新fetch时main前进至9043d24214d6fb827cc67f9214de4cf40560ce16，目标Java scope无变化；其ACTIVE冻结仍有效，本分支未合并/部署。

## 飞轮处置

本轮所有公开场景及衍生表达设 training_eligible=false / independent_acceptance_set=false。
35/796、已打开校准/验证集及近邻改写继续隔离，不复制到训练目录，不重打标签，不训练或替换线上模型。
能力缺失、参数合并、默认值、会话归属、取消/迟到结果分别列入契约/会话回归，不能记成分类器负例。
只有新采集且预先人工审核、通过既有保护集/模板族近邻检查的完整独立问句，才可进266类飞轮候选。
“上个月呢”“不是金额，按销量”裸句不进入无上下文分类器；教师同意和用户否定都不是自动真值。
