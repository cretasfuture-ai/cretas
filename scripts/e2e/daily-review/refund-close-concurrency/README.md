# 本地新厂退款与结账并发验收

仅用于2026年10月、本机`127.0.0.1:55447`专用测试PostgreSQL。复用P29基础及P14新厂账号，在独立`p30_refund_close_20261018`数据库和Java10066/Web31766运行；原Java10064/Web31764与体验库保留。固定P30产品类补齐实物退回净成本和统一锁序。没有外部付款、时钟替换、直接SQL业务写入、生产迁移或部署。凭据和全部证据只写当前Windows用户Documents下的`cretas-connect-20261015/factory-data-p30`，不得提交。

先确认没有旧P30 runtime/同名数据库/端口占用，且P29源码冻结匹配。`runtime.py NON_PRODUCTION_ONLY`只启动专用进程，输出STARTED不代表应用就绪。正常登录可用后运行：

```powershell
python scripts/e2e/daily-review/refund-close-concurrency/prepare.py NON_PRODUCTION_ONLY
python scripts/e2e/daily-review/refund-close-concurrency/acceptance.py NON_PRODUCTION_ONLY --case pay-close
python scripts/e2e/daily-review/refund-close-concurrency/acceptance.py NON_PRODUCTION_ONLY --case reverse-posted-close
python scripts/e2e/daily-review/refund-close-concurrency/acceptance.py NON_PRODUCTION_ONLY --case pay-refund
python scripts/e2e/daily-review/refund-close-concurrency/acceptance.py NON_PRODUCTION_ONLY --case reverse-posted-refund
python scripts/e2e/daily-review/refund-close-concurrency/acceptance.py NON_PRODUCTION_ONLY --case reverse-draft-close
python scripts/e2e/daily-review/refund-close-concurrency/acceptance.py NON_PRODUCTION_ONLY --case reverse-draft-refund
python scripts/e2e/daily-review/refund-close-concurrency/acceptance.py NON_PRODUCTION_ONLY --case finalize
python scripts/e2e/daily-review/refund-close-concurrency/verify.py NON_PRODUCTION_ONLY
```

准备复用现有SKU/可用批次，通过正常销售、出货分配、实际出库、独立收款审核、实物退货、AR调整审批，建立一笔15元退款。创建的新厂专用财务审核人负责与申请人分离的审核/付款；不修改共享角色权限。

每次竞态前用既有`/finance/month-close/execute`建立真实`OPEN_WINDOW`，验证未结转，然后两个真实HTTP请求按序排到同一PostgreSQL工厂事务锁。普通`/accounting-periods/confirm-close`会硬锁，不能冒充调整窗口。只用SQL取得/释放测试控制事务锁并只读核验；不伪造HTTP结果。

`close`结尾：结账先取得锁，退款/撤销返回既有400期间异常；除允许的结转产物外整份财务及库存快照一致。锁账重复失败无变更，正常反结账后同申请成功。`refund`结尾：退款/撤销先取得锁，提交后结转读取一致结果。已过账撤销保留原凭证+红字逐科目相抵；未过账撤销只作废草稿。登记付款生成DRAFT，必须单独正常过账，不将草稿算已过账资金；通过`客户余额=已过账应收+待过账应收`及资金报表和真实分录对账。

所有写步骤和错误响应保留在checkpoint。验收文件已存在时拒绝重放；失败后先查HTTP返回、实际业务状态和快照，再做精确续跑，不能删证据或盲目重跑。正常UI查看可用Web31766，新厂账号仍为`food_3101_001_admin`。本工具不会停止、删除原库/原运行时或自动合并部署。

本次修复后已完成全部六组、finalize以及实物退回成本验收，不应再运行以上写命令。修复前证据保存在pre-fix-concurrency。build.py将编译/测试输出隔离到私有目录，当前P30服务已加载该目录时拒绝覆盖；restart.py只进行一次专用副本类路径切换，不重启原服务。

本次实际顺序为：14类目标构建/测试、最后9项fixture修正重跑；restart专用副本；return_cost_acceptance.py NON_PRODUCTION_ONLY correct；reset_cases.py NON_PRODUCTION_ONLY（正常撤销旧退款并归档旧并发证据，仅一次）；六组并发及finalize；return_cost_acceptance.py NON_PRODUCTION_ONLY partial；同入口multi；verify.py NON_PRODUCTION_ONLY最终只读验证。reset_cases不能用来清空失败状态重试。

partial遇到已成功完成业务而最终金额断言错误时，只允许从已有complete/lock checkpoint和完整listener/失败快照证据续做最终读回，不重新运行异步竞争或写业务。原净成本346.1167，部分退回后367.3662→367.37，不能拿舍入后的旧成本或假定346.115计算。multi结束收入720、净成本346.12、利润373.88；两名演练客户及银行净余额均0。

独立Java启动会刷新4项原有指标缓存时间、增加4条同值同来源历史；verify只允许这一精确集合且检查全部旧历史不变。原体验库仍整份exact unchanged。验收中的每次失败回滚仍要求当次完整业务快照相同，不对回滚放宽指标断言。

未验收：退回后源生产成本再次更正的自动同步、报废费用自动过账、既有退货驳回null actor、完整跨月HTTP、多实例压力及真实银行。不要把这些列为已接通；详细范围和证据见docs/dispatch/archive/2026-10-18-refund-close-concurrency.md。
