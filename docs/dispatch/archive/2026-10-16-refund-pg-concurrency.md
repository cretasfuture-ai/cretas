# 退款对账真实 PostgreSQL 并发验收

P9-REFUND-PG-20261016；Base fefc47d33b76a215f61add36e19bd7d89d92462c；root DIRECT；继续同一独立worktree/branch。用户要求打开V-2026-0037后继续，不合并部署。浏览器保留daily_factory_a的V-2026-0037，已核对借银行存款15、贷应收15及原凭证0036链接。

本轮补P8未验的真实PG跨500行并发读，不重复实现退款或会计引擎。代码scope仅scripts/tests/ArApStatementPgConcurrencyProbe.java及本档案、ACTIVE；测试直接调用冻结的ArApServiceImpl、真实Spring事务代理和ArApTransactionRepository。使用独立编译输出Documents/cretas-connect-20261015/factory-data-p9，不写Maven target/classes，不停止正在体验的服务。

测试数据只进入私有PG55447的随机p9_refund_probe_前缀schema，启动前严格核验loopback URL及数据库名，禁止触及public业务表。schema内创建独立Hibernate表和合成fixtures，结束时仅清理本次创建且owner匹配的schema。现有体验工厂、105/30/15退款及V0036/0037只读保护。无生产、外部支付、push/PR/merge/deploy；实际银行接入不在本地模拟授权范围内。

完成标准：504条有效流水加软删/异厂/未审批对照；读完第一页后另一个真实PG事务提交晚批准的原期撤销及本期发票；生产REPEATABLE_READ方法返回一致旧快照，下一次读取看到提交后的余额；READ_COMMITTED阴性对照确实破坏同一快照断言。对照必须来自真实数据库行为，不能伪造HTTP数据。最终readback证明public体验数据/已有schema不变，运行服务/浏览器继续可用；记录版本、隔离级别、金额、命令/退出码和截图。未改产品源码，不重跑已冻结且已通过的P8产品测试。

追加root测试scope：既有ReturnRefundReversalContractTest.java新增跨月日期用例，复用其既有退款/撤销fixture。仅在独立测试输出编译并执行该类；把原期日期绑定改回当天的变异只存在Documents临时副本/隔离class目录，不改tracked产品源码或运行中target/classes。目标类最终全部通过并要求变异被新增跨月断言打红；P8其他未变测试证据直接复用，不重复全量。

执行完成：独立编译输出factory-data-p9/test-classes及cross-month-classes，运行服务未停止。PG首次失败为测试EMF漏纳ArApPaymentAllocation导致Repository exists子查询无法启动，清理成功，日志bootstrap-missing-entity.log保留；补同schema关联实体后真实PG探针exit0。实际RR repeatable read/readOnly=on：本次期初517、本期60、期末577，下一次读取639；RC read committed/readOnly=on对照：本次期初503、本期137、期末640（出现跨页重复/混合快照），下一次639。因此弱隔离对照确实打破同快照，生产注解路径保持正确。PROBE_SCHEMA_CREATED与REMOVED精确同一schema且只读回查零残留，不能仅凭清理前PASS行签收。

新增跨月目标用例：将原退款设为上月末，撤销申请/批准发生在当前时间，验证原流水/原凭证/撤销/镜像仍沿用原期，approvedAt/cancelledAt保留真实当前时刻，应收540→525。复用既有fixture，13项目标类GREEN；隔离副本将日期绑定改成LocalDate.now，新用例RED（expected 2026-09-30 but was 2026-10-16）；移除变异class覆盖后该1项restored GREEN。产品文件从未改写，不能把“恢复1项”描述为另一次13项全量。该服务测试含mock凭证fixture，不冒充完整私有PG退款HTTP跨月演练。

安全/保护：额外3种错误目标（端口、数据库、public schema覆盖参数）均在连接/创建schema前被目标校验拒绝。public基线18个顶层组完全相等（含P8既有保护内容），V0036/0037、105/30/15及其他工厂历史未改；首次/最终probe schema均已清理。正常API再读4个statement窗口及旧退款均PASS，已过账资金仍15。完整回执probe-receipt.json、cross-month-receipt.json、target-guard-receipt.json、protected-readback.json和日志位于C:\Users\Steve\Documents\cretas-connect-20261015\factory-data-p9。未变产品的P8回归163项和Web24项/最终构建证据复用，不再次构建或重启。

本地交付状态review；只新增可重复执行的隔离探针和回归测试，产品功能直接复用P8。浏览器daily_factory_a停在V-2026-0037，已实点“查看原始凭证”到V0036（已冲销）再“查看红冲凭证”返回V0037（已过账）；分录借银行存款15/贷应收15，与原分录逐科目抵消。截图browser/mirror-v0037.png及original-v0036.png。无push/PR/merge/deploy/生产写/外部付款；仍未验完整HTTP链真实跨月及银行实盘到账，不用隔离样本冒充这两项。

reversal_risk最终独立只读终审：读取最终源码、回执并核对probe/test/生产Service三个SHA256匹配，FINAL SCOPED PASS；未代跑测试或DB。确认13项初始GREEN、变异1项RED、恢复1项GREEN的口径，以及隔离EMF/真实Repository不等于完整Spring Boot应用验收。root执行证据与reviewer静态证据分别保留。
