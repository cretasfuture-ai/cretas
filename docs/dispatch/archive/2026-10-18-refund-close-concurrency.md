# P30 退款与结账并发验收

任务 `P30-REFUND-CLOSE-RACE-20261018`，Base `7d80003c86bd985a02f0e87847585d89fd2a2219`。worktree `C:/Users/Steve/cretas-analysis-provenance-20261015`，branch `codex/trace-analysis-vision-daily-review-20261015`，root唯一写Owner。用户授权新厂本地模拟、修复和验收；无push、PR、合并、部署、生产写入或真实银行付款。

## 实现

复用P29退款、撤销、期间锁、红冲、库存和财务接口。终审发现实物退货已入库、收入已冲回，但期末仍包含退回商品销售成本；修复为原精度出货成本减原精度实物退货成本后统一取分，不重建退款或会计引擎。

- 新增SalesReturnCostService，按工厂/销售退货/完成状态/日期范围汇总；复用稳定退货行来源、原批次、原生数量/单位及历史成本解析器。工厂、SKU、归属、入库链、成本或数量证据缺失/歧义则阻断。
- 成本恢复须绑定唯一、同工厂、同退货日期且POSTED的RETURN_ORDER凭证，缺失/草稿/错期阻断。既有退货收入凭证不重复登记第二份成本。
- CostCarryoverServiceImpl复用工厂事务锁，出货减退回后取分；净负成本反向借库存/贷销售成本，零净额无操作。
- 实物销售退货完成先取工厂锁再取退货行锁，并检查退货期间。异步退货凭证生成及失败恢复同步统一锁顺序，防止factory→row与row→factory死锁。
- ReturnOrderRepository新增带明细EntityGraph的范围查询，真实JPA持久化查询门禁通过。没有Entity、迁移、权限或前端改动。

## 隔离和身份

从p14_new_factory_20261016复制专用p30_refund_close_20261018，本机PG55447，loopback Java10066 PID50512 / Web31766 PID61540。固定产品类位于私有P30 build目录。原Java10064 PID46300、Web31764及原体验库保留，source-before受保护业务快照逐行完全相同。原服务未重启；工作区源码已含本批修复，不能说P29源码完全未变。

新厂FOOD_3101_001管理员user2、正常用户管理API创建的独立财务审核人user12，申请/审批分离。所有业务通过正常HTTP API，直接SQL业务DML为0；SQL只读和取得/释放测试控制事务锁。无时钟替换或真实转账。

独立Java启动复用已有指标初始化：4项指标值/配置不变，只刷新计算/更新时间、追加4条同值同来源历史。最终核验严格限制工厂/指标代码/值/来源/日期及早于首轮修复后读回的时间；旧历史不变。其他原生产批次/结算/原料数据不变，订单仅正常派生两条FROM_INVENTORY计划。原体验库没有这些副本变化。

## 修复后接口验收

每组先通过month-close/execute建立真实OPEN_WINDOW并断言尚未结转；两个真实HTTP依次进入PG同一工厂事务锁队列，保存两个等待者、pg_blocking_pids及两个请求均未完成的证据，释放测试控制锁后验证实际结果。

| 场景 | 先取得锁 | 结果 |
|---|---|---|
| pay-close | 强制锁账 | 付款400，整份业务回滚；反结账同申请成功 |
| pay-refund | 退款付款 | 退款提交后结账读取新状态，后续正常过账一致 |
| reverse-posted-close | 强制锁账 | 已过账撤销400，凭证/流水/余额不变；反结账重试成功 |
| reverse-posted-refund | 撤销审批 | 原凭证REVERSED、红字POSTED，双向关联正确且逐科目净0 |
| reverse-draft-close | 强制锁账 | 草稿退款撤销400且回滚；重试后草稿VOID，无红字 |
| reverse-draft-refund | 撤销审批 | 草稿VOID、无红字，应收和恢复流水同步，再结账成功 |

六组均PASS。锁账重复失败整份快照相同；已批准撤销再次提交精确无操作。失败快照只排除同事务正常结转凭证/分录及期间字段，其余客户、订单、退款、付款、应收流水、普通凭证、出货分配及库存逐行一致。

登记付款生成DRAFT是已有行为，须单独正常过账。始终按客户余额=已过账应收+待过账应收对账，并核对overview/statement/cash-flow与实际账簿。最终两名演练客户业务余额/已过账应收/待过账差额均0，工厂银行现金净余额0（模拟收款全部退回）。保留一笔15元PAID/POSTED退款，借1122应收15、贷1002银行15；每申请仅一条同额退款流水，每个撤销仅一条经审批恢复流水。

## 实物退货成本

- 全退：额外1盒出货并实物退回，净销售成本回到346.12，收入720、利润373.88；重复结账不新增记录。
- 部分退：另出货4盒，A/B各2盒，单价成本6.4165/7.4165；先退A1盒。异步凭证生成/完成双等待无死锁。退货收入凭证未过账时结账409且整份快照回滚；正常过账后收入765、成本367.37、利润397.63。
- 多批次余量退：同单A1盒+B2盒，锁账完成400、整份快照相同；反结账正常完成，15/45元退款审批/登记/过账。重复完成409且无新增。最终收入720、成本346.12、利润373.88，与有效成本结转及损益结转分录逐项一致。
- 工具预期纠正：原净成本未舍入值346.1167；部分退时实际出货381.1992减退回13.833=367.3662→367.37。脚本原367.36预期错误，保留diagnostic和失败checkpoint，仅续做已完成结果读回，没有重放业务。修复前353.53/366.47仅为缺陷证据。

## 验证及证据

14个目标类、127个不同测试通过：完整选择器118个通过证据复用，SalesReturnCostServiceTest仅修正fixture/错误消息断言后9个重跑通过；不是一次全量127绿，不是126+9。含退款/撤销事务、期间锁、退货完成、listener、成本/负成本/利润表/损益结转，以及真实JPA查询启动和持久化过滤。产品源码这两轮间未变，最终源码/测试哈希冻结。

命令入口build.py NON_PRODUCTION_ONLY（14类）与build.py NON_PRODUCTION_ONLY --tests SalesReturnCostServiceTest（9项）。确切Maven命令、退出码、日志、127项XML汇总、源码哈希在私有证据；未改前端证据复用，无重复前端构建或原服务重启。

证据根C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p30：六组receipt/before/after、prepared/business-state、cost-correct/partial/multi、listener-race/unposted-blocked/locked-complete、final-readback、java-combined-results、product-final-freeze。修复前结果在pre-fix-concurrency。早期硬锁误当窗口、重复付款凭证号、测试fixture错误独立保留，不计通过。

只读scout核对既有API；子任务P30-RETURN-COST，Base同上，Owner p30_return_cost，四文件scope仅SalesReturnCostService/Test和ReturnOrderRepository/JPA测试，不改台账/提交/运行服务。root修改其最终fixture并负责其余产品、脚本及统一串行验证。p23_cost_risk只读终审财务/锁序/证据。

## 明确未通过或未验收的边界

- 已完成退货后再次更正源生产成本：旧退回成本快照不一致会409阻断结账，自动同步尚未接通，需后续单独修复验收。
- 报废物理扣减及损失额不能冒充费用过账完成；本批未执行报废财务验收。
- 既有退货驳回listener的null actor问题未处理，该驳回链路未验收。
- 全局允许未审DRAFT强制结账政策未改；仅对完成实物退货收入凭证加必要守卫。
- 月结初始冻结快照可能在成本结转前标MISSING_COST_DATA；验收最终已过账利润表/结转，不宣称冻结报表全口径通过。
- 跨期净负成本服务回归通过，但跨月完整HTTP、采购退货期间、多实例/压力、真实银行、原内嵌浏览器UI及生产未验收。

保持独立分支。实现、自动测试、本地提交分别记录，未push/PR/合并/部署。

终审回执：p23_cost_risk在最终product14/14、验收脚本10/10冻结及最终verify PASS后确认，本轮窄范围无新增P0/P1阻断，可本地提交；上述未验边界继续保留，不扩大为完整退货生命周期或生产通过。
