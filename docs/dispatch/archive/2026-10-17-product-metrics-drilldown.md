# P19 同期间成品经营结果与追溯

任务P19-PRODUCT-METRICS-20261017；Owner root；Base 41c2a953393e428610ec1afd17ebb4f7a7166d76；独立分支本地，禁止push/PR/合并/部署/生产操作。复用原14-17批，不重复审批、出货或建业务样本。

发现：指标中心现有成品筛选只作用于完工批次；顶部四卡仍为全厂，无法直接比较各成品同期间出成率、生产良品率和完整成本。已有报工分析按报工日/数量聚合、订单成本按下单日，不能冒充完工期间的四指标分解。

scope：FactoryActualProductionMetrics复用原聚合/投产/成本口径，新增只读成品分组投影；只读Controller/DTO及目标测试；Web成品结果组件/API、指标中心挂载与测试；本档案/ACTIVE。不修改Entity/Repository/JPQL、原公式、指标快照写入、建厂、质检/库存/退款。生产成本沿用PriceMaskResolver.shouldMaskOperationalCost，缺数不填0，无法证明折算不发布比率。UI沿用项目Element Plus规范，按同期间展示两个SKU，直接回到已有批次追溯。

完成条件：新厂同期间成品分组与四批/成本服务核对，kg与盒不原数相加、比率按原分子分母计算而非简单平均；空期间返回空、无权限成本不返回/不计算、跨厂403；真实UI按成品查看并进入盒装批次追溯，原业务主表不变。源码冻结后目标Java/Web测试、Web构建、本地HTTP/PG/UI验收并保留证据。

实现：已有FactoryActualProductionMetrics的完工查询提取为同一私有方法，补充租户限定product_types名称/ID投影；原aggregate计算、完工/结单/CLERK_WIP/共享投入/折算/质量/成本完整性条件未改。成品分组按ID而非名称，逐组调用原aggregate，避免重写另一套公式。新只读GET `/api/mobile/{factoryId}/production/actual-metrics/by-product?periodStart=&periodEnd=` 使用已有production模块/读权限和成本财务权限解析器，最多90天；脱敏时不调用成本计算。没有新增或修改Entity、Repository、JPQL、枚举映射与迁移；新增native投影已在真实本地PG接口执行。

Web入口：经营指标中“按成品查看本期结果”，直接沿用页面所选期间，显示两类比率、完整成本及每公斤合格品成本。“查看批次”展开计算覆盖的原批次，调用既有追溯页。缺数/零/权限隐藏/请求失败/空期分别显示；期间或口径错配、重复SKU/批次、无效数值拒绝展示。账号/工厂/期间/权限切换与keep-alive取消旧请求并清理旧数据。

本地目标验证：Java全主源码4699文件编译通过，FactoryActualProductionMetricsContractTest 18项、ProductProductionMetricsControllerTest 2项合计20通过；包括原公式兼容、同名不同ID不混并、加权比率、单SKU不完整成本、成本脱敏时零计算。Web六文件62项通过（含新组件实际挂载与展开、来源跳转、挂载点期间绑定、权限/异步/错误/空期以及P18历史兼容）；Vite构建通过。日志退出码均0，产品/测试11文件SHA256冻结。未把这些结果表述为完整后端单测或CI通过。

运行时：仅将本批自有Java10064重载为P19独立classes（PID60340），PG克隆仍p14_new_factory_20261016，Web31764/Python18096保留，无时钟改动。旧运行时新路由404基线→新运行时200；首次50秒探测仍启动中，第二轮业务health及Web均200。既有mail健康限制仍保留，不声称聚合health全部通过。

真实HTTP/PG验收（新厂FOOD_3101_001/user2）：16日散装2批，投入20kg/合格16kg/不良2kg，出成率80%、良品率88.8889%、完整成本244.66元、单位成本15.2913元/kg；盒装2批，投入20kg/合格20kg/不良2kg，100%/90.9091%、256.66元/12.833元每公斤。逐批复用yield和cost-breakdown接口核对，不对产品比率简单平均。合计36kg合格/40kg投入/4kg不良/501.32元，与全厂90%/90%/13.9256元每公斤一致。17日products为空；逆序/超90日期400、跨厂F001 403、未登录401。现有质检复核账号user5/quality_manager可读非成本结果，costMasked=true且成本字段为空。

只读性：新分析API调用前后生产、库存版本、出货、应收、质检和指标版本均逐值相同；UI验收后业务主表仍与P18基线相同，旧快照保留。没有重建样本、重复审批、出货或产生新业务批次；指标中心原有正常缓存刷新可追加指标快照，与分析只读性分开验证。

真实UI已走通16日成品结果→展开盒装→准确跳转PB-PLAN-1792162905579-2675C830-5661；原始抽检仍不合格40%、独立复核人及批准2026-10-16 23:49:51、实际出货2026-10-17 00:00:21/10盒均保留。返回经营指标仍保持16日期间；选择“今天”后四卡暂无业务数据、成品结果与完工批次均为空。最后恢复16日并展开盒装来源，浏览器错误0。截图final-page.png、boxed-trace.png、empty-period.png及AX均已保存。

证据目录：`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p19/`中的before-route.json、baseline-business.json、product-api.json、after-ui.json、source-freeze.json、Java/Web日志与截图。命令：私有build_java.py final（临时derived POM已从worktree移除）；`npm test -- src/views/indicator-center/__tests__ src/api/__tests__/productProductionMetrics.spec.ts src/api/__tests__/indicatorHistory.spec.ts`；`npm run build`；check_products.py baseline/final/after-ui。仅本地独立分支待用户体验，无push、PR、合并、部署与生产访问。适用范围为已验本地双SKU，不代替未知工厂/包装/联产品的全量业务验收。
