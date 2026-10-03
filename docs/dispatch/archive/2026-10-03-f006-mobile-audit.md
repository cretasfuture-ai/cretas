# F006 手机功能核验（进行中）

- Owner: 当前 Codex 手机测试任务，DIRECT；Base SHA: `c3eefe0c6af48cd690f770d7a6ec890d3aa8cebf`。
- Worktree: `C:\Users\Steve\cretas-f006-mobile-audit-20261003`。
- 设备：Mi 11 Pro，Android 14；安装版 `com.cretas.foodtrace` 1.0.5 / 16。
- 当前手机端点：生产网关下的 F006 测试租户。其他租户业务写入为 0；已在手机创建带本次测试标记的 F006 销售草稿（1 盒、1 元、状态 DRAFT），写前无目标记录、写后回读一张订单/一条明细，其他租户同标记 0。登录与操作遥测另计。
- 本机证据：`C:\Users\Steve\Documents\Cretas-F006-手机测试-20261003`。不提交账号口令、令牌、数据库密码、原始用户数据或设备位置。

## 修改范围

- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ResultFormatterServiceImpl.java` 及 `ResultFormatterMaterialInventoryTest.java`。
- `frontend/CretasFoodTrace/src/components/ai/{RichContentRenderer.tsx,richListPresentation.ts,__tests__/RichContentRenderer.test.tsx,__tests__/richListPresentation.test.ts}`。
- `frontend/CretasFoodTrace/src/screens/attendance/TimeClockScreen.tsx`、`src/utils/currentPosition.ts`、对应目标测试、`src/i18n/locales/{zh-CN,en-US}/hr.json`。
- 当前台账条目和本记录。后端权限、实体、Repository、全局配置、历史关联账务不在当前修复范围。
- 扩展：`src/utils/mobileRoleBoundaries.ts`、`visibleOrderAmount.ts` 及测试；`screens/factory-admin/inventory/{SalesOrderListScreen,SalesOrderDetailScreen,PurchaseOrderListScreen,PurchaseOrderDetailScreen}.tsx`、`services/api/{salesApiClient,purchaseApiClient}.ts`、既有销售列表屏级集成测试与 `OrderDetailReadBoundary.test.tsx`。仅收紧 viewer 的移动端写入口与正确显示空金额，不更改后端权限矩阵；长按菜单绑定只读状态，确认与编辑回调发送前重读当前角色，隐藏单价禁止整单更新。
- nullable 关联范围：`screens/warehouse/inbound/WHReceiptCreateScreen.tsx` 的收货草稿省略遮罩单价，真实零值保留。API 创建请求类型不改成可空，也不补假零。
- 首页入口权限范围：`screens/main/homeModules.ts` 及既有 `homeModulesNonEmptyPerRole.test.ts`。采购主管真机送货签收 GET 被 warehouse/finance 权限拒绝，首页卡片须同时匹配页面实际要求的任一读模块；不替角色授予额外权限。

## 当前证据与限制

- AI 修复新增范围：`service/execution/SseStreamingService.java` 和 `SseStreamingFoodFallbackContractTest.java`。食品知识静态短语仅在对应意图配置仍 active 时保留专用路由；停用时通用概念问题走既有只读对话链，不全局启用意图、不绕写确认、不更改权限或 Repository。最终目标 Java 回归及独立风险审查须通过；部署另记。

- 第二批范围：`screens/factory-admin/inventory/{ArApOverviewScreen,PriceListScreen}.tsx`，`utils/{priceViewAccess,mobileFinancialAccess}.ts`，`store/canViewPriceStore.ts`、`config/rowActionsConfig.ts`、首页模块及相应权限/屏幕测试。只收紧移动 UI，不授予权限。应收应付写回调及迟到读取绑定当前用户、工厂、角色和权限；身份变化重建页面并清空表单。价格角色名单以 `PermissionServiceImpl.PRICE_VIEW_ROLES` 为真值。
- 第一批验证 APK 已构建成功，独立包名与 runtime，debug 签名只用于本地真机验证，原应用保留。第二批尚未进入该 APK，安装与验收分别记录。
- 第一批真机对照：viewer 销售/采购列表与草稿详情无写入口，隐藏金额明确显示未提供；采购主管写入口及真实价格保留，首页送货签收已隐藏。采购列表额外发现横向快捷条默认伸长占据半屏，第二批在原 scope 页面中约束其自然高度，须真机截图验收。

1. 原料库存查询读取 `quantity`，实际 DTO 返回 `currentQuantity`；真机回答显示 `?kg`。表格只取 DTO 前五个字段，数量和单位被元数据挤掉。修复优先读账面数量和批次 `quantityUnit`，零值保留，未知数量明确提示，状态显示中文。
2. 成本问题进入 `report_cost_query` 后被 W0 写确认保护拦截。该工具会生成分析报告；当前手机无可见后续确认操作。保护规则未修改，尚待确认链完整核验。
3. 考勤页面截屏在 19:10 与 19:18 都显示定位中。页面没有获取位置的截止时间，权限拒绝/失败后 `gpsLocation=null` 仍被渲染为定位中。修复须有超时、取消和重试，保持无真实定位时禁止打卡。
4. 独立加拿大 Mac mini 测试环境配置指向 `cretas_testenv`；实际 PostgreSQL 5432、Java 10010 均拒绝连接，launchd 明确 disabled。没有将不存在的实时账号读数当成空账号表，也没有启动停用服务。
5. 角色记录仅为 L1 登录/首页/主要页签观察；页面许可弹窗或测试选择器中断不能计为业务失败或完整通过。L2 表单/CRUD、L3 跨模块、L4 生命周期和完整权限拦截尚未全部完成。
6. 18 个活跃角色的 90 项只读接口检查均与登录返回模块权限一致。查看员实际显示销售新建按钮，点击无法导航；后台隐藏的金额被数字格式化为 ¥0。修复收紧其移动只读边界，并显式显示未提供金额，保留真实零值。

## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像

工厂操作员及其他需要打卡的岗位；手机熟练度有限，工作场所可能在室内，定位信号差或权限未开启。

### 用户旅程

| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|---|---|---|---|
| 1 | 考勤页面与定位状态 | 首次允许定位 | 使用真实位置，成功后开放合法的打卡按钮 |
| 2 | 定位中 | 等待至多 15 秒 | 无定位时显示明确原因与重试入口 |
| 3 | 定位失败/权限未授予 | 开启定位后点击重试 | 保留今日记录，重新请求位置，不自动提交打卡 |
| 4 | 返回其他页面 | 离开页面 | 取消定位监听；迟到结果不覆盖下一次请求 |

### 摩擦点清单

| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|---|---|---|
| F1 | 定位请求无时间边界，加载可能持续数分钟 | HIGH | Rule 1 与错误恢复 |
| F2 | 拒绝权限/定位失败仍显示定位中 | HIGH | 明确错误，禁止伪装状态 |
| F3 | 刷新按钮只刷新考勤记录，没有定位重试入口 | MEDIUM | Rule 5，避免死路 |

### 每个摩擦点的设计回应

- F1：真实位置监听设置 15 秒截止时间，成功、失败、取消均清理监听，不使用假坐标或历史位置代替。
- F2：区分加载、成功、权限拒绝与失败；无位置时保持打卡按钮禁用，不自动保存业务数据。
- F3：失败状态显示现有 NeoButton 重试入口，触摸高度至少 44；保留原页面、时间与今日记录，刷新同时重试定位。
- 其他防呆规则：没有新增自由输入或默认勾选；不改变打卡确认及用户/工厂身份绑定。

## 验收命令与状态

- `mvn -Dtest=ResultFormatterMaterialInventoryTest -Dcretas.testIncludes=**/ResultFormatterMaterialInventoryTest.java test`：5 项通过。
- `node node_modules/jest/bin/jest.js --runInBand --runTestsByPath src/components/ai/__tests__/richListPresentation.test.ts src/components/ai/__tests__/RichContentRenderer.test.tsx`：6 项通过。
- `node node_modules/jest/bin/jest.js --runInBand --runTestsByPath src/utils/__tests__/currentPosition.test.ts src/__tests__/screens/TimeClockLocationRecovery.test.tsx`：9 项通过，覆盖超时、取消、迟到监听清理、权限拒绝与重试；不会自动打卡。
- `node node_modules/typescript/bin/tsc --noEmit`：库存与定位修复后均通过。
- 同步 `origin/main` 到 `05369c9893` 后，9 套目标 Jest 共 72 项通过、全量 TypeScript 通过；首页模块闸补测 11 项通过，随后 TypeScript 通过。独立只读风险复核未发现本批实现阻断，不代表真机或发布完成。
- 18 角色的 54 次修改权限 HTTP 探测只指向事前 SQL 证明不存在的 F006 随机订单 ID / 负数设备 ID。有写权返回 404、无写权返回 403 全部符合；事后 SQL 三类目标仍各 0 行。修改 HTTP 尝试为 54，目标 ERP 行变更为 0，登录/审计元数据另计。
- 新发现：sales_manager 应收应付读取受 finance 权限拒绝；viewer 价格表受采购价格查看权限拒绝。当前首页送货签收修复不覆盖创建送货后“去验收”的详情路径，也不代表所有只读共享屏写入口已清零。
- 18 个活跃角色已完成 L1 登录和主要页签观察，设备管理员的登录/三页签已补核验。首道报工空表提交正确拦截，提示选择领料批次，未录入历史批次投入。
- 手机 Cretas 前台定位权限已开启（fine/coarse），USB 相关权限原本均开启；没有授予其他应用权限。
- 第一批 APK 真机已核验 viewer 销售/采购列表及草稿详情只读、遮罩金额明确、采购主管正常写入口与真实价格、首页送货签收隐藏；设备管理员定位 15 秒失败后可重试，打卡按钮 `enabled=false`，未自动打卡。额外发现重试译文键错误，第二批修复后须重新验机。
- 后续 18 项财务读取检查全部匹配权限。送货单读取另有逐账号 `restaurant` 模块访问闸，仓管/出纳即使有 warehouse/finance 读权仍可能 403；角色矩阵不能替代此闸。错误路径 `/ar-ap/overview` 的首轮 404 属测试工具错误，已修正为 `/finance/overview`，不计产品失败。
- AI 实时配置已只读核验：`FOOD_KNOWLEDGE_QUERY` inactive，手机 HACCP 问句仍返回无可见候选的澄清；SSE 候选响应丢失为源码问题，Mac 独立文本推理成功不证明手机调用它。
- 第二批源码独立只读风险审查无阻断；目标 Jest 6 套 64 项、完整 TypeScript 均通过。覆盖 AR/AP 正向 factory 绑定、失权/换工厂旧回调拒绝、财务/价格迟到回包隔离，价格角色与后端 enum 集精确一致。
- 第二批独立验证 APK 构建成功（源码 `47aae1f173`），已安装；原应用 versionCode 16 保留。第二批真机验收、PR、合并和发布尚未完成。
- SSE 最终 5 类回归 27 项通过（exit 0 / BUILD SUCCESS）；包含缺失或停用食品意图的只读对话路由、active 食品意图保留、active 情况下分析优先、工厂受控路由与既有澄清行为。独立风险审查条件已落实：全局精确食品意图查询仅有一条 platform inactive、非删除配置，无 active 副本；分析优先测试传 active 配置。
- 最新前端证据按测试文件去重为 14 套 133 项通过，不叠加同一套重跑次数。完整 TypeScript 检查通过。
- 销售草稿前置检查发现名称含空格的 SQL 经 Windows SSH 命令转义后失真，不能计产品串租户问题；接口精确 ID 回读与 SQL stdin 重查均确认客户和产品属于 F006。查询工具已修正，无其他租户写请求。
- 第二批真机确认：viewer 首页价格入口隐藏、finance:r 应收应付四页只读无录入入口；销售主管价格表可读、应收应付入口隐藏；采购主管快捷条高度正常；设备管理员定位失败显示中文重试，重试恢复定位中，无位置时打卡按钮 disabled。此 APK 的财务交易空金额仍显示假零，采购 FAB 覆盖统计/AI 按钮，销售详情未显示已录入 requiredDeliveryDate，第三批在既有页面 scope 修复。
- 第三批范围增加 `services/api/financeApiClient.ts` 响应 nullable 字段、财务页交易/账龄空金额显示；采购 FAB 依据汇总实际高度避让；销售 DTO 增加 requiredDeliveryDate 与详情显示。创建请求金额仍为 number，无假零/写权限扩大。目标三套测试 37 项通过（失败 fixture 已修正，财务套重跑 14 项通过），完整 TypeScript 通过；未代表第三批真机通过。
- 实际手机销售 L2：空客户提交被拦；选 F006 客户/产品创建 1 盒/1 元隔离测试草稿；列表与详情可读，手机取消后显示已取消；SQL 回读 DRAFT→CANCELLED，订单一张/明细一条，其他工厂同标记 0。未确认订单、未发货、未记收付款，不将此算作完整销售/仓储/财务 L3/L4。
- 发布构建已通过 6 类 Java 32 项，可信 JAR 已仅预上传 SHA 缓存（未安装/重启/切流）。随后 fresh origin/main 发现上游 #872 的 AI 澄清点选/流式权限修复和 #873 测试已合入，须同步并补集成验证，旧树制品不可冒充最新 main 发布。
- 最终只读审查发现发布阻断：ArAp overview/aging 的真实 Map 字段与 RN 不一致，且聚合字段未命中价格遮罩。扩展明确 scope 为 `controller/finance/ArApController.java`、新增 `dto/finance/ArApQueryResponses.java` 和真实 response-advice MockMvc ContractTest；保留现有 JSON key，金额加既有 PriceSensitive 注解，不改权限矩阵、Entity/Repository/Service 查询。RN 对齐真实 91–120/120+ 桶与逾期字段，数量口径显示家未结。修复及正反向测试完成前 PR #876 保持 draft，未上线。
- 财务 wire 修复已完成；初轮真实 MVC/advice/序列化与现有 Controller 共 13 项通过，补有价无财务写权后 16 项通过；终版增加出纳型「无价有财务写权」组合，随最终可信构建运行。Statement 四个余额及四个实体金额均覆盖，RN 类型使用真实 key 与 nullable 金额；未改 JPA 查询。
- CI 首轮 RN 全套发现三处本批集成失败，已修复而未放宽基线：两处新增报错改用现有 appAlert；精确增加 `.github/workflows/rn-gate.yml` push/PR 对 PermissionServiceImpl.java 的触发路径；`homeModulesSectionHiddenWhenEmpty.test.tsx` 正向对照改为销售权限四卡且财务卡缺席。该批允许 scope 含这两个 gate/CI 文件；相关五套 28 项通过，最终全套 CI 待最后推送。
- 第三版本地验证 APK 已构建并安装，原官方应用保留；取消的隔离销售单详情真实显示 requiredDeliveryDate 2026-10-04。该版不含后续 wire 修复，最终 APK 与最终后台发布后仍须金额正反向及六桶真机对照。
- 价格表源码确认详情/删除仅 findById、缺 factoryId 二次校验；新增窄 scope `controller/inventory/PriceListController.java` 与 `PriceListFactoryScopeContractTest.java`，返回/删除前拒绝异厂、空工厂或不存在 ID。保持查询/Entity/Repository/权限不变，不对其他租户进行线上删除探测；目标门禁合入最终可信构建选择器，最终风险复核和通过证据为必需。
