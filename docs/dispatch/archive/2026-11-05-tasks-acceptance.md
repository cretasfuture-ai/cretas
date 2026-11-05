# Claude 任务与业务验收接手

- ID: CLAUDE-TASKS-ACCEPTANCE-20261105
- Owner: 01a1a954-93ee-71c1-82a7-8e2b4ae0de7e / root
- Base SHA: 5e59baa7e9f6a798cd66cd0032cc34aa2b27dd4f
- Worktree: C:/Users/Steve/cretas-tasks-acceptance-20261105
- Branch: codex/claude-tasks-acceptance-20261105
- 场景表: C:/Users/Steve/Documents/cretas-optimization-20261019/execution/takeover-tasks-20261105/scenarios.csv
- 原验收证据保留，canonical 场景表只读；原始失败不得覆盖。

## Scope 锁

- 本组协调者独占本文件和本 worktree ACTIVE 条目，主目录 dirty 文件不动。
- D-S5-01 修复执行者：PurchaseInvoiceThreeWayMatcher.java、PurchaseInvoiceServiceTest.java、PurchaseInvoiceThreeWayMatchHttpTest.java（各为 backend/java/cretas-api/src/{main,test}/java/com/cretas/aims 下对应路径）。禁止 Entity/Repository/迁移/权限与其他模块修改。
- 验收证据独占 execution/takeover-tasks-20261105/，不修改历史证据和其他组目录。
- 下一必要验证：mvn test -Dtest=PurchaseInvoiceServiceTest,PurchaseInvoiceThreeWayMatchHttpTest -Dcretas.testIncludes=**/PurchaseInvoiceServiceTest.java,**/PurchaseInvoiceThreeWayMatchHttpTest.java；实际命令和退出码随回执追加。
- 当前阶段：复用已通过证据、逐条复核开放缺陷与部署前置；未进行生产写入或部署。
- S4 验收执行者独占私有证据目录下 s4-* 文件与 F006 `QA-ACPT-20261105-S4-*` 新夹具。只通过厂内 API/UI 创建/取消自己单据，禁止修改历史 case、已有排程或共享仓库/权限；自动排程必须显式锁定自己的生产计划，不能扫描全厂写入。验收生成候选→应用→独立读回、过期409与零额外排程写；无前置则保存具体阻塞，不自造业务事实。清理只取消自己的临时计划/订单/case，保留实际ID台账。无源码/dispatch/Maven/设备/发布写权。

## D-S5-01 交付切片

错料发票核对备注原先直接显示两个内部物料ID。现按当前工厂查询名称及业务编号；无本厂主数据时明确显示“未知物料”，不读取外厂名称或回退未经验证的快照。核对状态与金额/数量阻断逻辑保持原判据。

同因扫描：本类 `classify` 的物料差异提示为直接拼内部ID的命中点；数量/收货超开提示共用 `materialLabel`，一并使用厂内名称读取。未修改 Repository/Entity/权限/迁移。

验证：`mvn test "-Dtest=PurchaseInvoiceServiceTest,PurchaseInvoiceThreeWayMatchHttpTest" "-Dcretas.testIncludes=**/*PurchaseInvoice*Test.java"` 退出0，32 tests / 0 failures / 0 errors / 0 skipped；含真实 HTTP/JPA 本厂名称与外厂引用隔离。源码 SHA256 `A46C7118848878FADD9DB0DD90968A9D6C4361304F7C52F8D89ADFED4A3EA5E9`；测试 SHA256 `6DB234B486043955F58A94A0813453DDC2EF5097AD65284DD6BC367F0BE17058`。原逗号testIncludes未匹配测试，退出1，仅属验证命令错误，不算产品失败，已改为单通配符成功。

`git diff --check` 和 `python scripts/ci/check_dispatch_active.py` 退出0。此为实现及本地测试证据；尚未部署，不宣称线上D-S5-01关闭。
