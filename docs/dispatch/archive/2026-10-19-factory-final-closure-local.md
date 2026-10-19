# FACTORY-FINAL-CLOSURE-20261019

Base：`a1d096b8198374c027d335ce770a7bc66cd36373`。
Worktree：`C:/Users/Steve/cretas-daily-closure-release-20261018`。
Branch：`codex/factory-generality-units-readiness-20261018`。Owner：root。

本地9/9场景通过、未关闭产品缺陷0。唯一场景表和最终回执：
`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p37/final-closure-20261019/scenarios.json`、同目录`final-receipt.json`。
业务范围、复用项、9项清单和使用顺序见`docs/acceptance/factory-generality-final-local-20261019.md`。

## 实现与子任务

- `FACTORY-FINAL-TRACE-20261019`：原trace_fix负责Service与测试，root接收后补完终审剩余exact-group号码约束和真实独立阴性测试；子代理写scope已释放。DTO和Web说明/来源链接由root完成。
- `FACTORY-FINAL-MIGRATION-20261019`：migration_probe交回脚本、真实Flyway/Spring/JPA启动及HTTP200证据，root独立SQL读回V63/V64/V65成功。该JVM已结束，库及失败准备日志保留；子代理scope已回收。未改迁移SQL、Entity或Repository查询。
- 独立复审发现的owner实体身份和目标组唯一性两项P1均已修正，最终复审无新增P0/P1。材料、工序均按真实组归属；B自身forensic lineage保留，不合并A的边。

## 验证

- 目标Java27项通过，前序两项真实产品红测保留；Web49项、类型检查、正式构建通过。
- 四个联产批次API/DB/UI及两次实际来源链接跳转通过：组1共用1kg、组2共用2kg，每批1条本组工序，总成本31元不重复。
- 新厂运行盒装工艺/BOM第2版；旧、新计划固定引用不变。19日四项指标和18日缺费用未知对照读回通过；10月净成本400.53元、净利润359.76元仍MATCH。
- P37业务单据、库存、财务及forensic edges在重启/补验前后不变。指标GET按原机制可刷新last_value/计算时间并追加计算记录，旧指标历史不改写；不是业务单据写入。
- 正常迁移采用独立新目标库的仓库基线bootstrap→真实Flyway，非在手工修补的P37启用Flyway。历史两轮均`20261103.68:43`，哨兵不变。

## 运行与交付

P37已加载最终测试类，Java PID从82072正常切换为4224，Web31772保持；数据库仍`p37_factory_generality_20261018`，Flyway仍关闭。原参数备份只在私有证据中，禁止提交。
浏览器保留新厂账号的副产品`CLK-B-20261019-73331`追溯页，可点击同组主批次来源链。未重放采购、报工、审批、过账或结转。

必要补验入口：`closure.py NON_PRODUCTION_ONLY inspect`、`verify-traces`、`verify-migration`、`finish`。
`activate`是已完成的一次本地重启检查点，不得重放；`migration_probe.py`正常迁移已通过，不为收口重复创建临时库。
仅文档/台账变化复用已通过证据，不重跑产品测试。

独立分支、本地提交；未CI、push、PR、合并、部署或生产业务写入。技术9项不代替平台Web首版11项负责人签署、手机真机、生产数据升级或最终main组合版本线上验收。
