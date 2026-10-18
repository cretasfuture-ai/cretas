# 退款撤销完整跨月 HTTP 验收

P10-REFUND-HTTP-20261016；Base 914cdc0751553b560d2880155cd25fd088a2a1f5；root唯一协调/运行时/DB/台账Owner；沿用C:\Users\Steve\cretas-analysis-provenance-20261015及codex/trace-analysis-vision-daily-review-20261015。用户授权完整跨月正常业务接口链路、本地模拟daily_factory_a、发现问题直接修；禁止push/PR/merge/deploy、生产/外部资金操作。

切片：复用完整Spring Boot应用、退款/审批/流水/凭证/报表HTTP接口及现有体验账号。原本地10060/18090/31761与私有原库保持可用且只读保护。通过只读pg_dump建立独立p10_refund_http_*本地副本，业务改动只走副本的正常HTTP；不SQL重写旧业务日期。测试Java agent替换局部财务服务及审计时钟调用，在隔离服务模拟月末和次月日期；JWT/认证时钟保持真实。时钟控制文件仅由root写入，按工厂HTTP请求ThreadLocal生效，测试代码不进入正式应用制品。

P10-CLOCK-SUPPORT；Owner reversal_voucher；Base同上；scope仅scripts/e2e/daily-review/cross-month-http/java/及该子目录README.md。实现测试专用MemberSubstitution agent、时间ThreadLocal、Filter/Bootstrap与自检，严格绑定loopback独立端口/副本DB/私有控制文件；不得改任何产品源码、Maven/依赖、原运行时、DB、browser、Git/dispatch或其他文件。可独立编译到Documents/cretas-connect-20261015/factory-data-p10/clock-classes，返回编译命令、hash、错误/未验边界；root负责集成和HTTP/PG/UI最终验收。

root scope：本档案/ACTIVE、scripts/e2e/daily-review/cross-month-http/下Python运行时及验收脚本（与java/不重叠）；private控制文件/账号/日志/pg_dump保留Documents/factory-data-p10，不提交任何凭据或真实业务快照。完成标准：daily_factory_a发起、daily_finance_a独立审批，月末付款/过账、次月申请撤销/批准、幂等/自批/跨厂阴性，AR/资金/原凭证/红字同会计日且实际审批日为次月；对账月份窗口及现金流与真实PG独立读回一致，必要DRAFT分支复用VOID；原体验库/运行时不受影响，浏览器能登录副本查看新记录。隔离完整应用/真实HTTP证据与历史隔离方法/JPA证据分别报告；本地最终测试、风险复核和本地commit后等待体验。

## 本地验收结果（2026-10-16）

状态：review，完整本地HTTP切片通过，等待用户体验；未push/PR/merge/deploy。产品Java/Web源码相对Base无变化，直接复用原退款/独立审批/资金/凭证/报表业务。新代码仅为隔离测试辅助及可恢复验收脚本，不进入正式应用制品。

独立库p10_refund_http_20261016；Java10062、Python18094、Web31762、本地对象存储18392。初选Python18092被其他任务占用，启动前门禁拒绝后改用18094，没有停止或修改其他任务。原10060/18090/31761/18391仍运行，源库与完整历史保护基线精确一致。主机时钟不变；只模拟工厂A业务请求日期，认证使用真实时间。启动严格限定loopback/副本库、ddl=none、Flyway关闭、SQL init=never；账号及DB设置从私有文件读取，不入Git。

| 场景 | 正常接口操作 | PG/报表读回 |
|---|---|---|
| 已过账退款跨月撤销 | 10/31申请、提交、财务审批、登记15元银行退款、过账V-2026-0038；11/1由daily_factory_a申请撤销、daily_finance_a批准 | 原V0038为REVERSED，红字V0039为POSTED；二者会计日10/31，撤销申请/创建/审批/取消审计为11/1；AR525→540→525，已过账现金合计15→0→15；1122/1002分录逐项相反，净额0 |
| 未过账退款跨月撤销 | 11/30登记另一笔15元退款，保留DRAFT；12/1申请及独立审批撤销 | V-2026-0040为VOID，无红字；会计日11/30、撤销审计12/1；AR525→540→525，现金合计始终15 |

两场景均验证自批409、跨厂actor与factory路径403、重复审批无新流水/凭证、余额及原始行不变。原退款AR流水与原分录整行精确保持；POSTED原凭证审批人/时间保持，只允许预期状态/关联/追加说明/ORM元数据；DRAFT按现有规则作废。request版本严格+1。次月对账期初/期末均525、当期借贷均0，次月现金合计15。会计日期与实际业务审批审计分开核对，没有SQL回填业务日期。

副本新增恰为2笔退款、4条AR、3张凭证、6条分录；其他既有业务行未改，客户仅ORM updated_at/version变化，余额恢复525。源库金融表及更广历史基线均exact unchanged。SQL仅只读核对（建立副本的CREATE/pg_restore单独记录），无生产写入/真实转账。

验收边界：本轮完整真实Spring Boot/HTTP/事务/PG链路覆盖开放会计期间的两种跨月路径；已结账调整窗口和锁定回滚沿用既有产品目标测试证据，本轮未新增其HTTP演练。不把测试时钟日期称为主机实际日期。现金15是此本地样本报表的已过账合计，不是实际银行余额。

## 验证与故障归类

- 目标Java helper编译/jar及四日期、启动guard、ThreadLocal自检通过；agent SHA256 `75E167E666CC7EC6881D876BBFA3E423812B1C90D6BE1AD2D176BEE70274D73E`。真实应用日志七个不同目标均成功转换、0转换错误。首次严格ByteBuddy无法解析无关Spring Data泛型调用，保留启动失败日志且未开始业务；改用relaxed解析后精确类与零参now匹配范围不变。
- `acceptance.py NON_PRODUCTION_ONLY`完整HTTP+PG通过；首次原记录保护漏列ORM version字段而失败，保留日志；补充仅允许版本严格+1后复用既有建单状态继续，没有重复退款。最终冻结版从私有DB设置读取凭据后再次通过幂等读回。
- 真实HTTP时钟阴性：非法phase/未允许日期400、合法日期仍需认证401、工厂B无模拟时钟头且未认证401。私有日期文件恢复12/1。
- 既有P8 163项Java、24项Web及build与P9跨月13项/变异红/恢复绿证据按无产品源码变化复用，没有声称本轮重跑；本轮不改Entity/Repository/迁移。Python编译及dispatch检查通过。
- reversal_risk独立只读最终SCOPED PASS：核对源码、实际七个转换标记、两组HTTP/PG/原记录保护与源库保护证据；root另完成真实浏览器验收。README缺phase的旧表述已修正文档，无需重复产品测试。

## 体验入口与证据

浏览器通过可见登录daily_factory_a，确认应收525；实点V0039→原V0038→退款资金流水，并查看V0040已作废。最终停在：

`http://127.0.0.1:31762/finance/return-refunds/2b3e1b62-3836-4bee-a9e0-bace3edfbfd7?refundRequestId=0ed68071-e2dc-4e06-b71d-79392348e7cd&ledgerId=ee05f317-1dc0-4ad5-9661-e4c5885172ee`

私有证据根：`C:\Users\Steve\Documents\cretas-connect-20261015\factory-data-p10`。关键文件：business-final.json、http-events.json、http-final-frozen-acceptance.log、posted/draft-completed.json、*-original-integrity.json、*-statement-*.json、*-next-month-cash.json、protected-readback.json、http-clock-controls.json、clock-validation.log、java.log、product-source-reuse.json及browser/截图。原始数据库dump、账号、DB设置、运行时参数与真实业务快照均仅在私有目录。
