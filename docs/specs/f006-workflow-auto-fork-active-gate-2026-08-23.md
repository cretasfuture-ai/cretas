# F006 Workflow 自动分叉与活动生产引用门禁

## 目标

编辑已启用 Workflow 时，保存动作自动创建可编辑的草稿版本，生产继续引用创建时固定的 Workflow 版本。历史版本只读预览不应改变草稿编辑入口。

## 已确认的不可变边界

- `ProductProcessWorkflowServiceImpl.saveDraft` 在没有现存草稿且业务图发生变化时，按所有 Workflow 状态的最大版本号分配新版本并创建 `DRAFT`；业务图未变化时返回已发布版本，不重复分叉。
- `snapshot` 复制当前草稿为 `SNAPSHOT`，随后提升草稿版本并清空当前修订身份；快照内容通过 Revision 保存，历史记录不可由草稿更新覆盖。
- 生产计划和生产批次通过 `selectedWorkflowId` 固定创建时的 Workflow 权威。该门禁不修改计划快照、批次或任何生产数据。
- 若当前可编辑草稿已被任一计划或批次快照引用，保存会先将旧 Workflow 行冻结为 `SNAPSHOT`，再以更高版本号创建新的 `DRAFT`；未被引用的草稿仍原位保存。旧 Workflow、Revision、计划和批次 identity 均保持不变。

## 迁移与并发验证门禁

本变更包含 Flyway V20261030_21：生产计划/批次进入运行状态时，触发器必须与停用服务使用同一
工厂、产品和字面 \x1f advisory transaction key。发布前必须扫描版本唯一性，并在一次真实 PostgreSQL
验证中执行 V20261028_53、V20261028_65、V20261028_83 与 V20261030_21 的组合；分别证明“生产开始阻塞停用”
和“停用先持锁阻塞生产开始”两种事务顺序均无 deadlock。仅执行 Mockito、复制 advisory SQL 或
flyway=false + create-drop 不能替代该门禁；auto-fork 保存失败还必须证明事务回滚，不留下冻结旧草稿、
新草稿或 Revision 半成品。

## 活动生产引用门禁

停用前按工厂和活动 Workflow ID 统计：

- `ProductionPlanStatus.IN_PROGRESS`、`PAUSED` 的生产计划；
- `ProductionBatchStatus.IN_PROGRESS`、`PRODUCING`、`PAUSED` 的生产批次。

任一计数非零时返回 `409 WORKFLOW_DEACTIVATION_ACTIVE_PRODUCTION_REFERENCE`，错误消息包含计划数、批次数和完成/取消后重试提示。活动 Workflow 身份缺失时返回 `409 WORKFLOW_DEACTIVATION_REFERENCE_UNKNOWN`，安全拒绝停用。

已完成、已取消和仅待处理的历史/未开工引用不阻塞停用；它们仍按原创建时的 Workflow 继续保留历史事实。

## Web 行为

- 已启用版本发生编辑时，保存按钮明确显示“保存草稿（自动新版本）”，提示无需先手工另存。
- “另存独立版本”仍保留为可选的历史快照动作，不再作为发布前置步骤。
- 停用失败展示后端明确原因与动作提示。
- 历史预览时 `canEdit` 统一关闭；退出预览或恢复为草稿后，主料、辅料、工序、半成品、包装、连线和参数入口恢复由草稿编辑权限控制。

## 非目标

本变更不涉及 WorkProcess 治理、BOM 替代/投入派生、计划快照业务规则改写、生产数据写入或部署。
