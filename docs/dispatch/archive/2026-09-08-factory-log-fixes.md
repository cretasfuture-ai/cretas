# 2026-09-08 工厂库存及日志修复收尾

## FIX-FACTORY-STOCK-SCOPE-20260908

Owner: Codex current task。Base SHA: `a62d9e6d8c2b419adcae77e33b78653db5c8eb07`。
PR #755 的同一收尾提交归档并释放本批 scope；合并状态以 PR 为准，生产发布以独立回执为准。
客户选择与供料方式分离；F006 指定计划已纠正为工厂供料，库存批次快照未变。Java 最终 115 项、Web 17 项及可信构建通过。
详情：`docs/audits/2026-09-08-factory-operation-log-review.md`。

## FIX-FACTORY-LOG-DETAIL-20260908

Owner: Codex current task。Base SHA 同上。含 SCHED、ETL、DIFF 子 scope，一并随 PR #755 归档释放。
统一请求及操作审计、脱敏/可信身份/异步完成、打印连接恢复、失效调度隔离、Python JSONB 与模拟食材来源键修复；无 Entity/Repository/迁移变更。
Java 最终 115 项、Python 17 项通过；网关日志已在线验证。GitHub CI billing 阻断与额外脚本基线失败保留，未假称通过。
详情及未解决项：`docs/audits/2026-09-08-factory-log-followup.md`。旧报工核账、标准出成率和真实设备登记仍需业务依据。
