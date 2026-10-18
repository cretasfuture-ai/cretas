# 已锁账期间跨月退款撤销 HTTP 验收

P11-REFUND-LOCKED-20261016；Base 6707b97aebd7de988b596599ee9c26957d12ad64；root唯一写Owner；worktree C:\Users\Steve\cretas-analysis-provenance-20261015；branch codex/trace-analysis-vision-daily-review-20261015。用户授权独立分支本地daily_factory_a模拟锁账拦截/失败回滚/余额流水凭证一致，发现问题直接修；禁止push/PR/merge/deploy、生产、外部资金操作。

scope：复用P10已运行的完整Spring Boot/PG副本p10_refund_http_20261016与10062/31762测试入口、四日期请求时钟。仅新增scripts/e2e/daily-review/cross-month-http/locked_period_acceptance.py，P10 acceptance.py入口加main guard以复用现有辅助函数，本档案及ACTIVE。无产品源码/Entity/Repository/迁移/凭据改动；如验收发现实际产品问题再明确扩展scope并跑适用目标测试。

完成标准：两种原凭证状态POSTED/DRAFT；正常退款后通过现有期间open/request-close/confirm-close确认CLOSED且adjustWindowState=LOCKED；次月申请撤销，独立财务审批返回真正期间锁定业务错误，两次失败后整行核对customer/request/原与反向AR/voucher/entries/期间不变，原退款保持PAID、待审反向流水不生效、无新增红字。正常带原因反结账后同一审批成功，余额/资金报表/凭证一致、重复审批无副作用。原体验源库和P10已有业务记录保持。root真实浏览器截图并保留证据；风险终审只读检查；本地commit等待体验，不合并部署。

## 实际结果（2026-10-16）

两组真实HTTP/PG验收均通过，没有发现需修改产品代码的问题；全部复用现有退款、四眼审批、期间结账/反结账、资金流水、凭证和报表。未重启原运行时或P10副本。新增测试仅用main guard复用P10辅助函数、状态和身份校验，不会导入时重跑旧建单；P10已有业务原始行保持。

| 场景 | 锁账拒绝与失败回滚 | 反结账后同一审批 |
|---|---|---|
| 10/31已过账退款，11/1撤销 | 原V-2026-0041保持POSTED；独立财务两次批准均HTTP400、中文“2026-10期间已结账”；AR540、现金报表合计0，原退款PAID、反向流水PENDING且无approvedAt/By，无红字 | 财务带原因反结账，重试同一requestId和reversalTransactionId成功；V0041 REVERSED、V0042 POSTED，AR525、现金合计15 |
| 11/30草稿退款，12/1撤销 | 原V-2026-0043保持DRAFT；两次批准及一次直接过账均HTTP400、中文“2026-11期间已结账”；AR540、现金合计15、原退款PAID、反向流水待审，无红字 | 同样反结账后批准成功；V0043 VOID、无红字，AR525、现金合计15 |

每次400均从独立只读PG连接捕获前后16张存在的财务/订单/库存表整行快照，字段/行数/ID精确相等，包含客户版本/余额、退款请求取消状态、原与反向流水、凭证/分录、期间以及原材料/成品批次。原退款request/ledger/voucher/entries也单独与申请撤销前基线精确比较。事务内先保存反向AR审批及客户余额、后进入凭证期间门禁（产品既有调用链），HTTP拒绝后实库无这些中间结果。

反结账后复用同一审批payload及同一财务身份作为阳性对照，排除权限/错单/错接口造成假拦截。批准后再次审批不改变余额、原始行、审批元数据、红字数量或关联。原AR流水/原凭证分录保持；POSTED原凭证原审批人/时间保持。两组账务日分别10/31和11/30，撤销申请/批准/取消审计分别11/1和12/1；次月对账期初/期末525、当期借贷0，次月现金合计15。

锁账方式是现有open/request-close/confirm-close接口生成CLOSED、adjustDeadline为空并派生LOCKED，按既有“无deadline立即硬锁”规则。没有伪造数据库锁状态、SQL回填日期、跳过期间校验或自动解锁；反结账是另一次明确带原因的正常业务操作。未执行20天窗口自然过期或force-lock-close结转损益流程，不把这些分支计为本轮通过；主机时间、认证时间均保持真实。

## 隔离与验证

- 本轮副本新增2笔退款、4条AR、3张凭证、6条分录、2个期间；其他旧业务/库存行精确保持，唯一既有行变动为客户updated_at/version，余额恢复525。源体验库16张保护表整行精确不变。原10060/31761环境未动，P10的10062/31762继续保留。
- Python目标编译通过；四个目标执行均rc=0：`locked_period_acceptance.py NON_PRODUCTION_ONLY --case posted|draft --stage blocked|complete`。设置`P10_ACCEPTANCE_DIR`为既有私有P10目录。每次只跑指定阶段，不重复建单；新脚本不在生产应用扫描/制品中。
- Java/Web产品源码及P10时钟源码/制品均未改，本轮没有新Maven/JPA/Web构建。既有产品目标测试、真实应用启动和P10七个时钟转换证据直接复用，不声称重新执行。
- root浏览器daily_factory_a实看已过账/草稿的待审批原状与反结账后的结果，查看V0043作废及V0042→V0041→资金流水回链。页面加载短等待曾超时，后续AX/截图证实正常，是工具等待而非产品失败。

私有证据：`C:\Users\Steve\Documents\cretas-connect-20261015\factory-data-p10\locked-period`。核心文件：`p11-*-locked-approve-first.json`、`p11-*-locked-approve-repeat.json`、`p11-draft-locked-direct-post.json`（5次完整前后PG快照及中文HTTP响应）、`p11-*-blocked-final.json`、`p11-*-completed.json`、`protected-readback.json`、`business-final.json`、`baseline.json`及`browser/`。P10根目录另有p11前缀原记录保护/账务快照，http-events.json追加本轮真实请求，四个阶段日志保留p11-*.log。没有向Git写入凭据、快照或数据库dump。

已过账退款request：da8fe844-18e9-45de-aa09-8eacc210d720；原凭证297ecc10-170c-42e9-9e7f-ca38c3ffef18（V0041）；红字0ccb1598-82e5-46bb-9f68-5407b724ba71（V0042）；反向流水386f3d4e-df55-49a0-ac6f-da75d8f1a2a5。草稿request：c4d60934-93fc-4d48-9bf8-62068f19f7db；作废凭证177464a9-ae35-45c9-ae0f-dc267f8b9a9e（V0043）。无push/PR/merge/deploy、生产写入或真实资金支付。

独立终审：reversal_risk只读源码/JSON/事件日志，5份拒绝制品的before/after独立重算相等；确认两案锁账/反结账阳性、重复审批、日期、旧记录保护和无产品源码变动，最终SCOPED PASS，无阻断。UI由root另行执行，浏览器保留daily_factory_a；`browser/ui-receipt.json`与10张截图证明待审原状、恢复、作废、红字/原凭证/资金回链及最终应收525。当前期已通过正常反结账恢复OPEN，没有留下锁账待处理退款。

状态：review，已完成本地切片，等待用户体验。最终停在 `http://127.0.0.1:31762/finance/return-refunds/2b3e1b62-3836-4bee-a9e0-bace3edfbfd7?refundRequestId=da8fe844-18e9-45de-aa09-8eacc210d720&ledgerId=78bfa5c7-100c-45ad-a1ae-66d48ad90e09`，可点V0042或V0041查看分录与对应关系。
