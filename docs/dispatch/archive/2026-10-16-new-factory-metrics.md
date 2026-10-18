# 新厂指标自动初始化与双SKU本地验收

任务 P14-FACTORY-METRICS-20261016；Owner root；Base `1bbd30e626d24039e9d972f48ba7e693041206ed`。沿用独立分支 `codex/trace-analysis-vision-daily-review-20261015`，不合并、不部署。

范围：正式工厂生命周期与 FactoryBuildApply 的指标初始化连接、现有 indicator 服务内的初始化器与目标测试、`scripts/e2e/daily-review/new-factory/` 本地验收、相关架构与台账。Repository/Entity/迁移不预先扩大；如必须改变则另补真实JPA门禁。平台与采购Owner的其他改动保持只读。

完成条件：复用四项 actual-flow-v2 策略，正式建厂创建本厂指标定义及计算配置，无历史数值复制；已有配置/停用选择保持，重复调用不重复创建；失败同事务回滚。用正式平台接口建新厂、正式工厂账号和正常业务接口完成两种单位SKU收料到出货，追溯及同期间指标由真实业务产生，并保存UI/HTTP/PG证据。必要目标测试、构建与独立风险复核通过后交付；不以已有体验库seed冒充自动初始化。

P13 已在 `1bbd30e626` 本地冻结：93项Web目标测试、生产构建和双SKU入口/UI/HTTP/PG通过，证据沿用 `2026-10-16-platform-daily-integration.md` 与代码外 factory-data-p13；退款线P12保持冻结。

独立验收准备子任务 `P14-FLOW-HARNESS-20261016`：Owner p14_flow；Base同上；仅写 `scripts/e2e/daily-review/new-factory/` 及代码外 factory-data-p14，负责复用P0正常业务helper准备新厂API验收与隔离runtime脚本。禁止改Java/Web/原P0脚本/台账、禁止启动Maven/停止现有服务、禁止业务SQL写入。验收：Python语法与安全guard测试；根协调者运行真实平台建厂及双SKU，最终统一验证。完成后释放子scope。

复核发现：中文访谈选项与code-only预览/落库不一致，且软删除默认项受全量唯一索引约束。root scope明确扩展到 FactoryBuildPlanService、FactoryDecisionGuidanceCatalog、IndicatorRepository 的只读含删除存在查询；补 `FactoryIndicatorRepositoryQueryValidationTest` 真实JPA上下文，保留停用/删除决定，拒绝模糊名称猜测。建厂初始化的失败回滚另以真实Spring AOP/JPA与本地PG HTTP故障注入证明。

真实收货验收遇到测试工具限制：已有 `LocalOssAcceptanceConfiguration` 只允许旧A厂对象前缀/31761来源，新厂上传被测试工具拒绝，并非产品接口伪造通过。root 扩展该 test-only helper 的单一工厂ID/本地来源参数（默认原值不变），补签名PUT/GET与跨厂/来源/路径阴性测试，只编译到P14独立support目录；原10060/10062进程及support制品保持原样。

## 第一阶段本地证据

- 最新主仓核对点 `9dca765e14249b3cd97f5ba597fcf86062da3695`（采购#1007）；关键建厂文件与平台Owner HEAD同blob，未发现指标初始化。其在飞ArAp/CI改动未带入本任务，未合并主仓或平台分支。
- `POST /api/platform/factories` 创建 `FOOD_3101_001`（新厂双SKU体验厂），既有内部provision服务创建账号 `food_3101_001_admin`，真实登录证明该factoryId/工厂角色。四项定义、四项JPA_AGGREGATE配置自动创建；业务前四项NO_DATA、0批次、0计算快照，UI一致。无SQL写业务样本或手工配置指标。
- 真PG/HTTP故障注入：临时CHECK拒绝第四项配置插入，HTTP409；factories/indicators/indicator_computations/drools_rules计数逐项不变，故障厂及部分配置为0；临时约束已移除。
- 中文“完整生产成本”经正式preview/apply停用正确编码；重复apply保持停用、不重复创建；通过既有工厂API恢复启用。其余配置/历史/阈值均未覆盖。
- 新厂工厂账号复用P0正常API完成两SKU采购收货及真实附件、领料、工艺发布、报工、结单、不良清点、成品入库、成本补录/重算、订单分配和出货。散装：10kg→合格8kg/不良1kg，出货4kg、完整成本122.33；500g盒装：10kg→合格20盒/不良2盒，出货10盒、完整成本128.33。
- 2026-10-16同期间：出成率90%、净重良品率90%、完整成本250.66元、单位成本13.9256元/kg；四项AVAILABLE，来源actual-flow-v2，PG快照吻合。10月1日四项NO_DATA；新厂账号访问旧A厂指标403。生产过程中已有监控产生两条月期间中间快照，原样保留，不拿它们冒充当天最终值。
- 浏览器新厂登录、空数据、两SKU完工列表、同日成本重算及新增历史、盒装追溯/原料来源/实际出货和投入产出成本已查看。质量仍为PENDING且页面明确“已出货不代表质检放行”，未宣称正式QC放行已验收。
- Java目标：其他36项通过，真实JPA/AOP五项修正合法长度的并发测试ID后5/5通过；本地对象存储3/3通过，共44项最终通过证据复用。Python guard 7/7。完整4696源编译通过；后续仅换行相同及测试改动，测试阶段复用独立编译输出。首轮错误的逗号测试编译选择器没有执行测试，保留FAIL，不计通过。Web产品源码未改，复用P13的93项与build:check证据。

代码外证据：`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p14/`，包括platform-latest、empty-factory、postgres-http-rollback、optout-receipt、business-verification与截图/AX/日志。新runtime为10064/18096/31764/18394，私有库p14_new_factory_20261016；未用P10时钟代理、未改主机日期。现有schema克隆不代表空库Flyway已通过。

尚未收尾：真实日志发现成本重算操作action超出既有varchar(20)、出货后开票待办AFTER_COMMIT事务异常；正在核对已有实现后做窄修复。因此本阶段经营指标/追溯证据通过，不将整轮后台无异常或开票后续算通过。退款P12保持冻结。

## 收尾切片范围

第一阶段已本地提交 `79728916a5`。root 在同一分支继续：仅扩展 OperationLog.action 到64并附窄迁移/真实JPA及迁移验证；开票复用现成提交 `052f8179bd` 的 listener NOT_SUPPORTED 与 `SalesOrderInvoiceTodoAfterCommitJpaTest`，不合并该分支、不带其CI或其他变更。其余InvoiceService/Repository、ArAp及退款线保持原样。验收：目标Maven/JPA、P14隔离PG精确升级/审计读回、正常补充出货后即时开票待办与数据库一致；不以补偿扫描成功代替AFTER_COMMIT路径。

## 收尾结果与最终边界

- 最终目标Java共62项通过：沿用未变更切片39项；收尾22项（真实JPA6、精确升级1、开票事务边界3、AFTER_COMMIT真实JPA4、processor8）；迁移版本唯一性1项。日志分别为 java-corrected-target、java-local-objects、java-closeout-target、java-migration-version；早期错误与失败保留。Python guard 7项通过；Web无新增产品源码，沿用P13已有93项与构建，不重复构建。最终全量4696源编译与收尾目标一次通过，源码/测试/命令摘要见代码外 source-freeze-receipt.json。
- 精确扩容SQL在P14隔离PG完成20→64，原838条审计行逐行摘要、5个索引定义均不变；重复执行安全，未修改Flyway历史。两个真实成本重算接口均保存完整25字符action、正确工厂及actor，见 audit-migration.json / audit-business-readback.json。
- 追加散装1kg/30元正常出货验证已有listener修复：同一http-nio请求线程创建REQUESTED开票申请；待办DONE、attempt=1。再次确认既有发货单返回409，库存、客户应收、申请与待办读回不变，见 invoice-after-commit-http.json。最初工具误期望重放200，记作工具失败；没有重建订单、强改状态或修改产品允许重复确认。
- 最终散装两笔出货4kg+1kg，盒装仍一笔10盒；两SKU合格量/不良量/成本与同日四指标不变。最终HTTP/PG证明见 business-final-verification.json，原 business-verification.json 保留第一阶段证据。浏览器停在新厂2026-10-16经营指标，四项可用、两个完工批次，可跳转追溯；final-metrics.png / final-metrics-ui.txt 留存。
- 独立只读风险终审 SCOPED PASS，无本地切片剩余阻断。修复后业务运行日志无ERROR；单独Actuator整体健康仍503，日志明确为本地邮件健康检查失败，不宣称邮件或整服务健康通过。双SKU与指标所需HTTP/UI/数据库已独立验证，未为健康读数关闭检查。
- 独立分支仅本地提交，未push、PR、合并、部署、生产写入或真实开票；原daily_factory_a的31761/31762运行环境保持。未验证联网AI自由生成、正式QC放行、空库全量Flyway或未来主仓组合。主仓已有同版本不同SQL的63/64迁移须在未来集成前协调，不能改已执行checksum；本次65窄升级的本地通过不替代该集成工作。
