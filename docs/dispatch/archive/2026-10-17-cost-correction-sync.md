# P22 已结单成本更正与财务、指标同步

任务P22-COST-CORRECTION-SYNC-20261017，Owner root，Base b51437554065580465e442c803a114b0d0ca8365。沿用独立分支与本地新厂FOOD_3101_001/user2。用户已授权本地成本更正模拟，冻结P19-P21证据不覆盖，不push/PR/合并/部署或访问生产。

scope：ProductionPlanServiceImpl/ProductionPlanDTO，AccountingPeriodServiceImpl和新增小型成本一致性守卫，既有SupplementalCostsDialog、相关Java/Web测试、本档案和ACTIVE。现有期末CostCarryover/Voucher/经营指标能力直接复用。必要发现的财务聚合运行错误只在明确证据后修复并记录。

补充scope：原计划Controller记录成本更正审计；IndicatorQueryServiceImpl的两个成本指标读取与更正共用工厂事务锁，并在加锁后刷新一级缓存，避免Controller提前读取实体导致并发缓存回写旧值。没有Repository/Entity映射或迁移变更。成本更正、确认结账、锁定结转与反结账共用工厂事务锁，原指标版本不改写。

独立风险终审发现并纳入本轮：成本PUT权限与独立重算统一，不再允许仅排程权限改写已结单成本；CanvasIndicatorsController阻断两个成本指标进入旧占位重算；期间守卫复用不可变出货血缘event_time，覆盖直接/分配出货，不用updated_at冒充发货时间；结单/仓库确认日期也入守卫，兼容旧批次缺生产日期。多批次分配发货的现有COGS只读首批成本，故本轮对此形态更正先明确拒绝且零写入，不宣称已支持；后续应改造已有COGS的分配聚合而非另建财务模块。

真实首次结转失败：数据库现有发货成本265.9517元，但原CostCarryover把Spring Data单行Object[]的嵌套投影当未知值静默返回0，接口成功且closingPostedAt已记却没有COGS凭证。原失败HTTP回执保留initial-close.json；追加既有CostCarryoverServiceImpl的结果解包，无法理解的投影显式失败，新增两条回归先失败。用既有reopen恢复期间，再重新按正常业务结转，不直接修改期间或凭证。MonthCloseServiceImpl.executeClose和审批驳回也共用同一工厂事务锁，补足独立风险复核发现的月结入口旁路。

真实更正/重结后V-2026-0011已过账270.95元，进一步GET利润表却为536.90元：已有VoucherEntryRepository利润表聚合保留旧COST_CARRYOVER的REVERSED原凭证，却排除了其红冲，导致265.95+270.95双计。只调整该查询的COGS特例过滤：旧COGS原凭证及红冲均排除，保留当前active COgs；普通业务原凭证与红冲仍同时计入抵消。此处新增Repository scope，并新增真实Hibernate Context的VoucherCostCorrectionRepositoryQueryValidationTest，必须通过后才收口；不以Mockito代替启动/真实查询执行。原失败financial-readback.json保留，修复后只重读，不重放业务更正或结转。

凭证实际页面另发现既有Web VoucherType缺少后台已实现的COST_CARRYOVER/PL_CLOSING，显示英文枚举。只在既有api/voucher.ts联合类型和中文标签表补齐“销售成本结转/损益结转”，列表和详情直接复用，不新建页面。补跑已有VoucherDetail挂载测试和Web构建，已通过的成本/指标153测试证据保持复用。

## 本地业务验收结果

使用新厂admin/user2，原14-17批业务直接复用，未新增/重复发货。原COGS结转265.95元，V-2026-0007；锁账后成本PUT返回409且逐表快照不变。按既有反结账流程将V7置REVERSED、生成V-2026-0010红冲，原分录金额保留。浏览器17批在既有补录框将设备12→22、其他3不变，一次保存即完成同事务重算；同金额API重试返回settledCostsRecalculated=true，不重复生凭证。盒装成本128.33→138.33元、成品单位成本6.4165→6.9165元/盒；已出货10盒与剩余10盒各69.165元，展示69.17，不能用展示舍入后的6.92×10反推成本。

生产完工期间2026-10-16：总成本501.32→511.32元，每公斤合格品13.9256→14.2033，出成率/良品率仍90%；散装244.66不变、盒装两批256.66→266.66，合格净重36kg不变。实际UI完整生产成本详情表格同时展示旧501.32与新511.32，来源为“真实业务·实际流”，旧版本逐行保留。当前值缓存失效后正常GET产生新证据，未借用旧值或改公式。

在目标成品成本UPDATE处设置临时、仅限新厂目标成品的本地故障触发器，通过真实PUT尝试设备23触发500：计划、批次、结单、库存成本、缓存、版本与凭证逐表完全回滚；finally移除触发器/函数并最终PG确认不存在。此为明确的本地故障注入，不是业务补单、生产迁移或普通UI成功路径。

重结后V-2026-0011已POSTED，借6401主营业务成本/贷1405库存商品各270.95元，损益结转V-2026-0012包含270.95成本；旧V7/V10不被覆盖。重复锁定无新凭证；锁账后的PUT与独立POST重算均409、完整快照不变。修复报表后真实GET利润表totalCost=270.95，不再双计旧265.95；原失败536.90回执保留。财务原有6张采购/销售业务草稿未代为审批过账，因此利润表诚实提示无已过账收入/待审草稿，不能把本次成本验收叫作完整财务上线验收。

权限与并发：只读质检user5成本PUT403；同厂URL引用其他工厂计划403；Canvas成本占位重算409，业务与缓存快照均不变。入口权限契约测试排除仅scheduling授权；本轮没有新建或冒用纯排程账户、修改角色权限配置。实际PG外部事务持有工厂advisory锁时，成本PUT和另一个月结execute入口同时等待，pg_stat_activity明确2个advisory waiters；释放后两请求均409且业务全表不变，证明不是只在单一结账入口加锁。

## 测试、运行时和证据

最终Java12类166项通过（含真实DataJpa/Hibernate的查询启动与执行，普通费用红冲抵消/跨厂/草稿负控）；原两项原子重算回归、两项聚合解析回归、真实JPA双计回归均保留先失败证据。JPA初次测试夹具缺source字段的错误也单列保留，修复夹具后才取得真正断言红。实际运行中新原生查询、PG JDBC/Hibernate和只读利润表回查均通过，不以H2代替PG业务验收。Web原17文件153项通过，后续仅凭证标签改变，相关既有挂载测试和最终Vite构建结果另存；不反复重跑无关产品测试。独立risk reviewer两轮只读复核发现的高风险入口均已收紧，最后利润表查询复核无阻断；运行门禁由root实际完成。

仅本地Java重载，Web31764/Python18096和PG55447原clone复用，最终Java独立build-report-fix/classes。报表修复重载前后所有业务/财务表逐行不变；原有startup指标任务正常新增4条本月等值快照，lastValue和配置不变，旧版本全部保留。该后台新增与业务写入分开报告，未将其误判为报表接口的写入。故障注入制品已清理，源文件哈希冻结，后续只有档案/台账收尾不重复产品构建。

私有证据目录`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p22/`：baseline、initial-close（真实失败）、initial-close-recovered、reopen、verify-correction、rollback-before/rollback、final-close、negative-controls、concurrency、financial-readback（真实失败）/financial-readback-fixed、final-readback的JSON；java-red/java-projection-red/java-report-fixture/java-report-red/java-report-final日志、JPA surefire报告、web-final/web-voucher/web-voucher-build日志、source-freeze、runtime、UI的cost/indicators/history/final-voucher AX和PNG。凭据、启动私有参数和工厂业务明细均不进入tracked文件。

边界：单条出货分配多个批次的成本更正目前明确拒绝，未冒充已支持；其现有COGS按allocation分摊聚合仍是后续优先项。非本地CI、生产性能/并发压测、生产迁移/发布、完整财务业务草稿审批都未执行。仍留独立分支，本地commit；无push/PR/合并/部署。

最终收口：相关凭证挂载11项通过，最终Web构建退出0；中文“销售成本结转”已在真实V11页面显示。最终source-freeze共19个源码/测试文件（含后补voucher.ts），内容相同的原153测试证据复用。浏览器新厂账号停在V-2026-0011/5069be5e-3ed1-498b-8bdd-51c8a930f9f4，6401/1405各270.95且借贷平衡。测试10月期间保留CLOSED_LOCKED，用于体验锁账拦截；后续更正需通过现有反结账。reportQueryFix最终Java runtime和只读利润表270.95已验证，原始166个Java测试、真实JPA启动和执行均通过。未合并、未部署；本地源码提交与业务模拟验收是分开的事实。

完成标准：已结单费用保存和权威重算同事务，失败全部回滚；影响的生产/出货期间已关账则拒绝，不改原过账凭证；与结转串行；成品单位成本和现有已出货成本来源同步；使当前成本指标缓存失效但保留历史版本；通过既有结转流程核对6401/1405分录。新厂真实接口/PG及浏览器验收，复用旧业务，不重复发货。必要目标Java/Web测试、构建，清楚记录边界。
