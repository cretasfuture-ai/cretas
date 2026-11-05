# Claude 任务与业务验收组：软件交付及业务实收收尾

Owner：`01a1a954/root`。仅本组质量、返工及接手验收范围；食品 DAG 与方案审计交付由各自 Owner 负责。

## 实现、验证与发布

| 交付 | 已确认事实 |
| --- | --- |
| 原任务 #1469 | 已合并并正式部署；原 S2/S4/S5/S6 通过证据继续复用，不重复全量验收 |
| 质量与返工 #1480 | 已合并/Java-Web 发布/Android OTA；官方 793 项通过，最终全量 CI 19664 通过、311 跳过；真实 PG 并发与 JSON 保留门禁通过。迁移 93 已有序应用 |
| 财务刷新 #1484 | 已合并并正式 Web 发布；finance_manager 实际只读复测 disposal32 APPROVED，无越权质量选项请求、无重复审批或库存写入 |
| 结单 UI #1486 | 已合并并正式 Web 发布；共享半成品回库量输入、长差异说明与原因码分字段。两原计划实际 UI 结单成功；历史 409 与事务回滚证据保留 |
| 成本 #1488 | `65b2c123` 提交并推送，正常 squash 合并为 `898877a2a981159291b16c523f99f822d7c4a2f0`。修复已按领用比例追溯成本后再扣剩余半成品转库成本的问题，无 Entity/Repository/迁移改动 |

成本回归先保留真实拓扑红测：预期 9.852、实际 6.104。最终官方 14 类 144 项全通过，独立风险审查无未解问题。head65b2 的 fast-contracts、真实 PostgreSQL concurrency 及其他检查成功；本轮 full suite 按配置 SKIPPED，不能冒充组合全量 CI。

食品 #1485 合入后的组合 bc302 官方 144 项/build/stage 成功，Java subtree `ef212353da0b13bd3e500305f78f61d9105fe7c9`、JAR SHA256 `73131c3b56ec6198c9f5a47577e576e5e8dfa939ad4d83552dd111a6e4e761a1`。后来 #1489 只改 Web/dispatch，Java 内容与选择器未变，故复用上述证据；最新 Web 单独构建通过。重复 stage 被 exact-build-head 门禁拒绝，保留该失败记录；未修改门禁、未伪造 manifest，随后统一 build 复用双制品成功，原同哈希服务器 stage 仍有效。

合并前核验 main、PR head、正常 CLEAN 与无他组发布进程；实际合并整树等于审查组合树 `cc5cc5ceeca6862363c9e8ace4e9c8ec428a2ab2`。从新建干净 exact-main release worktree 运行统一入口，152 秒、exit0、`RELEASE_FINAL_STATUS=deployed` 恰好一次。Java/Web 均复用制品、零次构建；blue10010 健康通过，Web 四方 hash 均为 `42db2a16b09f50c812073822f07ecda97f38a75404a8948556a2bd70206bf3bb`。预热因运输主机不可达正常跳过，未重复等待；发布包含最新 #1489，不覆盖其修复。生产服务回执成功后未重复相同服务探针。

## 原计划真实业务闭环

全部对象限定 F006。没有重建或重复结单，没有 SQL 改账；仓管用户 1554，软件 QA 备注明确区分真机与模拟器，不声称实物交接或用户签署。

| 对象 | 最终读回 |
| --- | --- |
| 正常计划 `364ea0ba-8193-49e9-bf5b-3e12361b942e` | 原批次 11125、结单 `5fe1ef96-9f6f-410f-9c76-6d6b57376991`。上线后先只读确认成本 9.852，再由 Xiaomi `f79c50d6` 实际 UI 确认一次 5 袋；POSTED、待收投影移除 |
| 正常成品 `58c3574a-1a30-4f88-9a4e-465862413a71` | AVAILABLE 5 袋、unitCost 1.9704、allocatedCost 9.852、shipped/reserved 均 0；与原计划/SKU/批次一致 |
| 正常剩余半成品 409 | 0.4kg 可用，unitCost 9.37、accumulatedCost 3.75；原工序 WIP 已转出，未重复形成库存 |
| 返工计划 `de598877-5fe8-4c19-a709-885183bdeaf6` | 原批次 11127、结单 `d33585ed-0250-4853-8f65-dcb23c46d3f3`。模拟器5558实际仓管 UI 实收一次 5 袋，POSTED |
| 返工成品 `4d390a64-b103-4f3f-8f5f-fa377ff20811` | AVAILABLE 5 袋、unitCost4.83、allocatedCost24.15；独立来源403耗0.6kg、余0.4kg |

真实 PG 只读最终核验两结单的仓管、备注、不同 receipt idempotency key、POSTED/5 与成品 ID。原预览及长说明 409 失败的十表 count/digest 不变证据保留。无源创建实际 UI 阻断且零写；输入0.6超过源余0.4时 UI 钳位0.40且未提交。并发、重复持源和 JSON 保留沿用已通过真实 PG/契约证据。

S3-02 保留原三条 FAIL，异常温度无备注实际 UI 阻断；新增合格记录未自动洗白成品，废品审批、不同人复核放行及仓储状态门禁闭环已通过。S3-09 规格96/8冲突及缺货负例已验证；只用本组获准成品实发1箱/8基本单位，原受保护120/96箱库存不变。已发子单保留审计，不清已完成分配；余29箱未安排，不创建无人管理调度。

## 保护、保留与未完事项

- 唯一场景表 `execution/takeover-tasks-20261105/scenarios.csv`：50 行，**47 通过、0 失败、1 未测、2 原豁免**。原始 FAIL、历史未测与测试工具失败原件保留。
- 最终保护快照：154 条 DEMO、两批受保护成品、原三条 FAIL 的 digest 均与基线一致。临时 QA inspectionRequired 已恢复 false、额外 BOX12 已 inactive。
- QA 领料单 `6a4cfd10-c4d6-473c-a4e9-541178162fc1` 已完成发料与生产接收。车间盐料批次 `097d8d83-7cc4-46e7-935c-c2d7566545a5` 实耗1.1kg、余0.1kg；包材 `cfef747e-f7ec-4b77-b2e9-523576b63056` 余5个。由本组 Owner 保留库存及来源审计，不伪造消耗、不 SQL 清除；两新成品和半成品余量按上述清单保留。
- 代码 scope、生产发布锁、小米设备均释放；本组5558已正常关闭并保留AVD文件，不碰食品5560。既有 Pixel_9a 的原 userdata 保全仍未证实，继续 HOLD，不能宣称无损恢复。
- **S6-10 仅余真实工厂业务代表与 QA 的试点签署，未提供、不能代签。** 手机和账号不构成阻塞。S5-09/10 原联产品成本豁免及 B03 默认仓库正式业务确认未签署状态保留，不扩大为已具备能力或已签署。

本机证据根：`C:/Users/Steve/Documents/cretas-optimization-20261019/execution/takeover-tasks-20261105/`。关键证据：`s315-cost-deploy-receipt.json`、`s315-native-receipt-normal.json`、`s315-native-receipt-rework.json`、`s315-final-receipt-sql.json`、`s3-protected-s315-final-xiaomi.json`、`s315-qa-material-retention.json`、`s315-device-handoff.json`。秘密凭证未纳入跟踪文件。

`TASKS-S315-COST-BASIS-20261106` 已交付并释放 scope；父任务 `CLAUDE-TASKS-ACCEPTANCE-20261105` 仅以 blocked 记录本人签署缺口，不占代码或发布资源。本次归档为文档变更，不重跑已经通过的产品测试。
