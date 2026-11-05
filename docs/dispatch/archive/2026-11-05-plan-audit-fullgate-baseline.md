# 安全 PR 最终门禁：main 既有失败修复

任务：SEC-PARAM-SCHEMA-GATE-20261105、SEC-FACTORY-CATALOG-TEST-20261105、SEC-INVENTORY-FIXTURE-GATE-20261105。
Base：072bf5681969a55029d929d00a6101a28ec92a15。

## 失败证据

#1451 full run 41949436789（19301 项）和 #1457 full run 41951587344（19316 项）均有相同 31 项失败，PostgreSQL job 均通过。在不含三条开放安全 PR 的干净 main 上重跑六类测试：67 项，29 failures、2 errors、0 skips；失败方法集合与 CI 完全一致。未修改已知失败基线或 CI 选择器。

## 参数描述扫描

扫描器曾把测试输出中的 AbstractBusinessTool 夹具当作生产工具，Objenesis 构造后字段为空导致扫描失败。按测试输出 code source 排除夹具，其他模块及 JAR 的工具仍纳入，未知 code source 仍纳入；无法识别测试输出时明确失败。新增 ContractTest 调用真实扫描入口，证明生产工具保留及带脏描述的测试夹具被排除。原覆盖数量、禁止 UUID/类型标注断言和 optional camelCase 上限 29 均保留。

UserDisableTool、CustomerCrmStatusQueryTool、InvoiceStatusTool 的三处描述改为业务编号用语；参数键、必填项和执行行为未改。

验证：`mvn -B test -Dtest=ToolParameterDescriptionLeakScanTest,ToolParameterDescriptionScannerScopeContractTest -DargLine="-XX:+EnableDynamicAgentLoading -Xshare:off -Xmx2g"`，5 项，0 failures/errors/skips，退出码 0。执行时间 2026-11-05 10:54 +08:00；日志位于本机证据目录 `C:/Users/Steve/cretas-audit-evidence-20261105/schema-gate-after.log`。

## 其余门禁

目录退役相关四类测试只改测试以守住当前 main 的过滤和只读目录契约。返回值比较意图、置信度、匹配方法、输入及非空候选，保留 cache/Python/legacy 的调用次数与上下文断言；缓存 put 保留原 `eq(py)` 契约，生产先缓存原结果再过滤返回值。两条餐饮上下文测试仍验证绕开 cache/Python，但退役意图必须 no-match。

收入报告生成已退役，要求 `UNSUPPORTED_CAPABILITY` 且整个派发器无交互。三个餐饮 Skill 用例验证默认缺席及真实 register 后仍被拒绝，并用允许的工厂 Skill 注册成功作阳性对照。召回评估的工具列表精确为五项只读工具，明确不含冻结、通知、监管上报三个旧写工具；按正式“召回评估”触发词验证，不恢复旧混合写链或扩展“启动召回”的产品语义。Mockito 保持严格模式。

独立执行者提交 `4c5bc0c669`，root 整合后补强缓存原值、完整零派发、注册阴性/阳性对照，六类最终冻结验证：`mvn -B test -Dtest=AIIntentServiceImplPythonIntegrationTest,WriteGuardWiringTest,RestaurantSkillsRegistrationTest,FoodSafetySkillRegistrationTest,FactoryIntentCatalogContractTest,FactoryCatalogRuntimeBindingContractTest -DargLine="-XX:+EnableDynamicAgentLoading -Xshare:off -Xmx2g"`。40 项全部执行，0 failures/errors/skips，退出码 0，2026-11-05 11:12 +08:00；本机日志 `C:/Users/Steve/cretas-audit-evidence-20261105/catalog-final-targeted.log`。独立只读复核确认三处补强关闭，未改产品源码、目录、失败基线或 CI 选择器。

## 库存流水测试夹具

实时核对两组状态与 ACTIVE 均无该文件 Owner 后，本组登记并接手唯一 InventoryLedgerServiceTest 文件。#1449 新增 FREE_GIFT 的数量、金额、条数查询，旧夹具把所有查询复用同一 Query 并连续返回预置结果，导致赠品查询消耗后续生产领料、调拨和盘点结果。测试按 JPQL 将本场景不存在的赠品查询路由到独立空结果，不消耗原流水序列。原金额、数量、期初滚算、盘盈/盘亏与价格脱敏断言保留。严格修复读取的异常按 MaterialConsumption 查询注入，不再假设它是第三次 createQuery 调用。不改库存生产逻辑或查询。

验证：`mvn -B test -Dtest=InventoryLedgerServiceTest -DargLine="-XX:+EnableDynamicAgentLoading -Xshare:off -Xmx2g"`，完整该类 33 项，0 failures/errors/skips，退出码 0，2026-11-05 11:03 +08:00。日志 `C:/Users/Steve/cretas-audit-evidence-20261105/inventory-fixture-after.log`。参数扫描与库存夹具均独立只读复核，无实质阻塞。

目标修复通过不代表完整 Java 门禁成功。三条安全 PR 的合并和生产发布仍须等待最终门禁；本切片未部署或生产写入。

## 原接手记录的后续证据

早期接手记录中的“离线私钥路径待提供”已解除：本机自行找到现存私钥，仅本地使用，未输出、复制或上传其内容。现有平台 F006 备份 run 14（export 22）已完成 A 级导出包恢复核验：加密包 8,523,464 字节，解密包 SHA-256 `f6f1d2adbe04a6c60b395286bf42887e745f2b9c49e661707f09e4b4cb3233b9` 与台账一致，251 个文件哈希、249 张表及 123,263 行逐表核对通过。明文仅在进程内存，未还原到生产数据库；未再次删除任何工厂。证据为本机 `C:/Users/Steve/cretas-audit-evidence-20261105/backup-restore-result.json`。


## 12:53 阶段交付与 main 冻结

#1451 已于 04:52:28Z 合并，squash `1fd507de1e3dee2fd7482a8d617c4a512a4dd278`；#1457 已于 04:52:48Z 合并，squash `0b2bdd6b8844ff865f898bcea1cea763d6442d76`。共同31项修复随1451进入main。上述四个已合并实现任务归档并释放文件scope；1438仍在飞，不提前标完成。

有效当前head全量证据：1451 `6b90e4128c` / run41963776528，19318例=19027 passed+291 skipped、0失败错误；1457 `b9604684f5` / run41963854007，19335例=19044 passed+291 skipped、0失败错误。辅助1462 `1b6e37a3eb` / run41960359191，19316例=19025 passed+291 skipped、0失败错误。三份库存/目录/扫描的九类78项均逐一实际passed，HEAD和整仓tree与本地Git对象一致；instrument/ratchet通过，不把条件skip算执行。

原1438整合head `382dd7fbe6` / run41963966444亦已全量通过：19400例=19108 passed+292 skipped、0失败错误，平台AI57与真实JPA7均实际passed；其PG job127036541353执行真实迁移1项、0失败错误跳过。但合并前main新增1464/1465/1466，推进至df325，Java/Python/Web都有新源码，故不能用原96ca制品部署新main。1438将同步新main再跑最终整合门禁与构建，不动这些已合并实现。

登记 `SEC-PLAN-AUDIT-MAIN-FREEZE-20261105`：Owner为本组当前聊天，main合并/发布准备窗口优先；其他Owner继续feature开发测试，暂缓推进main，已有有效发布锁/原子操作仍优先。此次登记不等于已进入生产运行时窗口，也不授予新增生产业务写入/删除权限。正式生产与业务验证以本组发布回执和PR最终说明为准。
