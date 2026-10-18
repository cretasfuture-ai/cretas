# P29 SKU毛利至同期间利润表

Owner root，Base 0fd8d766f8c05244ad4b2aaef2d8949446fd2567，沿用独立分支 codex/trace-analysis-vision-daily-review-20261015。仅本地新厂FOOD_3101_001，禁止push/PR/合并/部署/生产操作。P22-P28成本/实际出货/凭证/利润表证据冻结复用，独立只读风险复核后台边界。

第三方检查已实测P1：10月SKU毛利“财务报表”链接落到资产负债表；利润表默认1-10月，不是同期间。切9月并对浏览器读报表请求注入503后，标题已是9月却仍显示10月720/346.12/373.88。证据factory-data-p29/before-ui.json及stale-report-before.png；仅浏览器故障注入，无业务写入。

scope：既有ShipmentSkuMargin.vue、report/index.vue、report/IncomeStatement.vue、期间helper（提取复用P28纯日期验证）、SKU与报表对应目标测试、本文和ACTIVE。不新增报表/业务接口/Java/权限/Repository/Entity/迁移；不得手改凭证、成本或期间。验收：整月SKU直接进入同厂同期间利润表；部分月不冒充同期间；读取失败清旧数并留可见错误；切厂/期间/卸载拒绝旧请求回写；校验返回工厂和期间。新厂正常账号真实接口/UI核对10月720/346.12/373.88及503/正常恢复、9月零收入，保存前后证据。目标Vue/构建通过；后端无变动复用P27门禁。

## 独立复核扩展scope（覆盖上段最初前端范围）

独立风险审查确认P1：普通业务红冲在损益结转排掉原REVERSED却保留POSTED镜像，结转与利润表不同口径；强制锁账和凭证写入未共锁，可漏并发过账；直接Income DTO无价格脱敏。另发现普通盘点/损耗/期初库存复用createManual绕过期间守卫，同一Voucher入口须只保留PL_CLOSING/COST_CARRYOVER的系统结转豁免。

新增明确scope：VoucherEntryRepository（结转直接复用既有利润表聚合）、VoucherServiceImpl（所有凭证变更入口先锁工厂，再拿凭证行锁）、AccountingPeriodServiceImpl（守卫刷新已托管期间，避免等锁前旧deadline缓存）、IncomeStatementDTO（金额及明细父列表价格脱敏）、financeReport.ts对应nullable类型、相关既有服务测试/真实JPA查询验证测试及价格Advice实测。无Entity/迁移/权限配置改动；不改库存模块业务算法，不绕过既有反结账流程。必须新增真实JPA启动执行、正常新厂API和真实PG并发/失败零写证据。同期未审草稿是否阻止强制锁账属于既有政策P2，暂不擅改规则。

## 最终实现与本地验收

P1已完成：整月SKU入口直达同厂同期间利润表；非整月不误导为同期间；切厂/期间/失败/卸载清理旧数据并拒绝过期响应，校验返回期间与工厂。损益结转直接复用利润表聚合，普通原凭证/红字相抵，成本结转只取有效版本。Voucher变更复用既有工厂事务锁，与强制锁账串行；刷新期间前先flush，保证正常反结账本事务的OPEN变更不丢失。createManual仅类型及来源同时匹配的系统结转可豁免期间守卫，期初/损耗/盘点仍走已有业务入口。利润表7金额和3明细列表复用价格Advice脱敏，界面接收nullable。

验收环境：FOOD_3101_001，正常新厂管理员userId=2；专用本地Java10064/Python18096/Web31764/PostgreSQL55447。新Java PID46300，P29独立classes；仅替换本任务旧PID52832，其他服务不动。凭据只在gitignored/私有文件。桌面内嵌浏览器连接仍缺少其运行模块；本批使用既有独立Playwright测试浏览器完成真实UI登录会话，未声称用户内嵌标签已导航。

证据根目录：`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p29`。

- `java-red.log`、`red-reports`：旧编译实现74项中5项按预期失败，覆盖聚合、价格、锁顺序、普通手工过期入账和期间缓存；只有red命令使用main.skip旧类。
- `java-fixture-failure.log`/`java-final-command.json`：最终产品源码真实编译36文件，无main.skip，153项中150通过；真实JPA启动及查询执行均通过。ManualTest的3项由于Mockito未注入字段失败，修正测试夹具后`java-manual-fixed.log` 3/3 BUILD SUCCESS；其余150项同产品源码证据直接复用。合计153项通过，未宣称一次全绿运行。原失败日志保留。
- `web-tests-final.log` 66/66；`web-build.log`构建成功；`web-typecheck.log` vue-tsc退出0。`source-final-freeze.json`包含全部21个tracked及untracked产品/测试文件哈希；docs不触发重复产品测试。
- `ui.json`/`failure-cleared.png`：实际同期间跳转、503清除旧金额、恢复及9月零数；`october-income.png`、`source-final.png`实际UI及截图检查通过。
- `price-before.json`真实viewer原API泄露已复现；`price-after.json`真实viewer返回200但7金额+3列表null，管理员正常金额，跨厂403。新专用viewer只作本地阴性账号，未变更共享角色。
- `business.json`：锁账期正常期初入库被期间守卫拒绝，原料批次、均价、调整、库存及财务快照完全回滚。PeriodClosedException既有HTTP语义为400；初验收脚本预期409误判，保留`business-status-expectation.log`，核验快照后从checkpoint继续，未重放入库或修改产品异常映射。
- 同一回执保存真实PostgreSQL advisory队列：force-lock-close先等锁，voucher post后等锁，两HTTP均阻塞；释放控制事务后结转200、过账400“期间已结账”，原草稿及分录完全不变。无SQL修改业务数据。随后通过正常反结账、作废未收货测试凭证、取消采购草稿、重新结账完成清理；状态及业务快照回读通过。生产/出货/应收/质检/原有指标历史均未被该模拟改写。
- `final-read.json`：散装9公斤收入270、成本137.62、毛利132.38；盒装30盒450/208.50/241.50；汇总720/346.12/373.88，SKU、已过账凭证、利润表一致。当前有效成本结转V-2026-0033、损益结转V-2026-0034，原V24/V25保留REVERSED及红字对应关系。第三出货行36为4盒×6.4165与6盒×7.4165，成本70.165按分70.17，收入仅150；盒装明细显示相加208.51与原精度汇总208.50的1分钱差异沿用P28解释。完整生产成本指标521.32与生产by-product合计一致，不冒充仅已出货成本346.12。读取指标只追加/命中正常历史，未改计算口径。

未扩展为本批验收：同期未审草稿是否阻止强制锁账属P2政策；退款外围业务行锁先于Voucher锁的路径未做退款对结账PG竞态验收，未发现已证死锁环，不宣称该外围已验收。未做生产/多实例/全库测试。所有改动仍在本地独立分支；未push、PR、合并或部署。

独立风险终审确认冻结21/21无漂移、本范围无未解决P0/P1，允许本地commit收口。类型检查命令`npx.cmd vue-tsc --noEmit`已由协调器取得exit0（工具chunk627c9c），空日志对应的命令和退出码另存`web-typecheck-command.json`，未因空输出重复运行。ACTIVE转review保留独立分支待体验标识，本批无进行中代码写入；无PR/CI合并背书。
