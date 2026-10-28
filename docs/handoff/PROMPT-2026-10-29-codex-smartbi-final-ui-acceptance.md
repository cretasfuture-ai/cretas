# SmartBI F006 剩余验收交接（2026-10-29）

用户授权继续完成、验证后合并部署；线上只用 F006。目录采用问答引导＋分类浏览，需要项首批、可选项满足真实条件才推荐、餐饮专属排除。不得把接口、编译或本地测试通过写成网页/原生 App 已验收。

## 当前结果与版本

- Web PR1292 已合并部署：main `d75971a8f747056859e35e7dcc7e1cc36da13731`，四方 index SHA256 `3efb574c24c0cdd91d2072d6e6fdec4b2646e872edebde257ce3c90698b94e1d`。千位金额不再被 parseFloat/动画截成 2、1；全图明细不再发送不支持的点钻参数。
- Python PR1294 已合并部署：main `ce7841117f5df336d2cbe130d8721ad997872389`，9 文件哈希和运行进程时间核对通过，健康通过，148 个既有迁移校验一致、新迁移 0。Java 本批未部署。
- 新增并接通销售订单行→发货→成品库存、客户→显式订单渠道→产品两个受控关系；正常 allocation 与首批溯源指针不重复计数。未知跨域关系明确不可用，多类关系必须全部选中且重新核对，不将分开图表冒充一张跨域明细表。
- 本地冻结工厂套件 452 passed / 1 既有独立 schema skip；CI Python 10861 passed / 184 skipped / 153 deselected / 2 xfailed；独立终审通过。真实 PG commerce 9 项已执行，无 skip。
- 当前源码的容量验证完成：真实 PG 256 admitted / 44 capacity；32 MiB 总量、8 MiB 单帧、复用、300s 到期、60s 复用余量、租户/用户/角色隔离及不可变快照通过。容量不是目录图表数量上限；目录按需加载明细。本轮未提高这些保护值，未做生产压力测试。
- F006 真实服务 API 四项通过：双 commerce 关系得到 3/3/7 个与 30 元；同上下文追问；UI 新上传三表得到 200kg/96%/95%/2500元、2个日期行；未验证的生产—库存关系拒绝且零事实。它们不是 Web/App UI PASS。
- Android 已实际应用 OTA `b405f712-f332-6f8c-d171-ccb4cad58267`，runtime 1.0.5、production；本批无新 RN 源码或 OTA。设备 `f79c50d6`，App `com.cretas.foodtrace`，当前登录已失效。

## 当前阻塞和工作区

集成 Playwright 长目录检查在设备 OEE 明细处失去进展。25 份 Excel/25 张截图保留，但没有最终回执，不算通过。关闭该页后，navigate、code、tabs 均返回 `Transport closed`；当前会话没有可用重连入口。恢复集成 MCP 会话后继续，禁止启动第二浏览器或改用 standalone runner 冒充正式生产入口；禁止按进程名批量终止其他任务的 Node/浏览器。

手机自动代填凭据此前被自动审批拒绝，记录未提供更细原因。用户后来明确授权自动登录，但不能为本次代填修改审批、换工具输入、编码凭据或注入 token 来绕开该拒绝。不要重复询问账号密码，不得把 App 记成已验收。只有当前 Android；另一 Android 和 iOS 无设备，不算豁免或 PASS。

- 当前协调线程：`01a172c1-620c-7c43-9120-9b24f64e958a`。未创建后续线程，也未转移写 Owner；新线程接管前按 AGENTS 维护 worktree handoff 登记，确认唯一写 Owner。
- Source/UI/验收工具 WT：`C:/Users/Steve/cretas-factory-ai-dialogue-20261027`，分支 `codex/smartbi-focused-acceptance-20261029`。验收工具代码快照 `015f40598fe5e2c71cfc6e1e4f57a37d1358c0cd`；后续仅文档回执可能推进 HEAD，接管时读取实际 Git/PR。工具已通过 85 个相关 Node 测试、5 个本地异步 DOM fixture；没有生产 UI 通过声明。
- Python feature WT：`C:/Users/Steve/cretas-smartbi-planner-20261029`，PR1294 已合并，保留测试证据，不继续改旧已 squash 分支。
- Release WT：`C:/Users/Steve/cretas-smartbi-final-release-20261029`，clean detached `ce7841117f5df336d2cbe130d8721ad997872389`。独占旧 Java target 已归档到其 ignored test-output，勿删除证据。没有在跑的部署/迁移/事务。
- 唯一允许的生产 MCP filename：`C:/Users/Steve/my-prototype-logistics/.claude/worktrees/codex-factory-ai-mcp-20261027/scripts/e2e/production-readonly/mcp-entry.js`，当前 sparse HEAD 为 `015f40598fe5e2c71cfc6e1e4f57a37d1358c0cd`。

## 继续执行

先重新核验 origin/main、ACTIVE、工具可用性和实际设备身份。不要重建发布包、重复发布未变化组件、重复新建三表数据或把旧失败改成通过。

1. 恢复直接集成的 Playwright MCP，仍用 canonical filename。每次通过 `test-output/fad27-mcp-options.cjs` 创建新的单次本地选项地址；旧地址会被消费或到期，勿复用或输出凭据。
2. 运行 opt-in `smartbi-commerce-joint-readonly` 与 `smartbi-legacy-kpi-readonly`。前者配置两族 `commerce-joint.sales.stock`、`commerce-joint.customer.channel`；库存独立预期按所选单位：个 3/3/7，盒 16/16/226，袋 2/2/30；渠道金额 30 元。后者 uploadIds 为 production=5285、quality=5286、cost=5287，预期总成本2500、材料1600、人工500、设备300。必须核对当前 F006 源、可见事实、原始全图明细及保存导出；零业务写、零阻断才可 PASS。
3. 正式 Web 新上传联合问答用既有 `smartbi-factory-analysis-readonly`：`factoryAnalysisUploadedJoint={uploadId:5285,inspectionUploadId:5286,costUploadId:5287,subjectId:'product-dd7f518c745929a3',expectedSubjectCount:1,expectedRows:2,expectedKpis:{production:200,inspection:96,quality:95,cost:2500}}`。避免无变化地重复旧 12 轮/自然 TTL 大场景。
4. 目录现有 59 个候选、30 类分析。私有 `sc29-final-catalog-family-plan.json` 已按 8/8/8/6 类分组；逐批设置 `factoryCatalogFamilies`，每批留存 `flatFamilyResults`。每批控制在 MCP 300s 内，不再一次运行全部目录。未知/数据缺口/餐饮专属不能算通过。
5. 继续下表尚缺部分。业务写入只在当前授权的 F006 测试范围，先读身份/计数/ID，再走正常 UI/API 并独立回读；不为了变绿补造历史关系、关闭当前月或重复成品入库。恢复 App 正常登录后再做原生验收。

## 尚未完成或尚未完整验收（完整 22 项）

原 34 场景台账当前仍为 11 通过、1 原始失败待复验、21 未测/未完整、1 已有豁免。下面保留每个未完成项；接口补验通过没有替代 UI 状态。

| ID | 已有基础/证据 | 尚缺部分 |
|---|---|---|
| CTX-NATIVE | Web 上下文/跟进已通过，Android OTA 已应用 | 原生连续问答、来源/产品/期间保持 |
| TTL-NATIVE | Web 自然 300s 到期和刷新通过；当前 PG 到期/复用通过 | 原生真实到期、提示和恢复 |
| SALES-STOCK | 实现部署、真实 SQL/PG 和 F006 API 双关系通过 | Web 图表/原始明细/导出，随后 App |
| PURCHASE-STOCK | 已有确认收货↔原料批次↔库存适配器 | F006 合格正向关系与 UI 回读；草稿不能当正式库存 |
| CUSTOMER-CHANNEL | ACTIVE 显式订单渠道校验；F006 30 元与追问通过 | Web/App 可见图表、维度与原始明细 |
| UPLOAD-QA | 正常 UI 上传5285/5286/5287共8行，独立回读及新服务联合分析通过；显示截断修复已部署 | 原始失败为旧 UI 显示2/1；须复验可见2500/1600及新版网页问答，不能删失败记录 |
| NORMAL-QUALITY | 正常动态质检已真实提交和回读 | 修复后的表单标题/业务 UI 最后复验，原生质检角色 |
| JOINT-MATRIX | 单位/批次/日期/样本加权与拒绝混算测试；已有业务及上传正向样本 | 多场景 Web/App 组合矩阵完整复验 |
| WIP | 已发布 workflow365、kg→kg→kg→袋；44报工/13库存/43台账 | 当前合格逐批边为0；需真实来源/目标批次与 WIP_CONSUME，不得把 pooled SFI 视为逐批血缘 |
| OSS-RECEIVING | 曾取得签名授权200，草稿保留 | Inspector 阻断后无 PUT/register/confirm；恢复允许的上传路径再做正常闭环，不得换工具绕过拦截 |
| MONTH-CLOSE-STOCK | 草稿入库伪金额修复；三月226行历史修复完成且幂等重试0 | 非零正式收货/消耗及已关闭期间库存价值正向闭环；不要关闭当前十月来过测试 |
| QUALITY-WEB-ROLE | 已确认质检员 Web 为 mobile-only 的权限边界 | 这不等于质检工作流通过；按真实支持入口验收 |
| QUALITY-APP-ROLE | 原生质检流程与正常质检数据已有基础 | 登录后用正确质检角色完整操作和回读 |
| CATALOG-WEB | 单库存族曾通过；新30族计划、短批工具已就绪 | MCP恢复后完成30族的渲染/明细/导出，长轮 TOOL_ERROR 不算通过 |
| CATALOG-NATIVE | 原生分类/引导与禁点钻兼容代码已更新 | 管理员原生各合格族真机完整验收 |
| EXPORT-NATIVE-CONTENT | Web既有9份XLSX/PDF/PPT解析/渲染/目视通过 | App 真机下载、分享、打开及内容/来源/单位核对 |
| SCALE-100SKU | 已有100产品实际上传；Web精确选SKU、5天500kg/96%/95%/6250且无100图扇出证据 | 剩余分页/性能和 App 对等验收 |
| LONG-CONVERSATION | Web12轮 context-only、维度跟进、自然TTL通过 | 原生长对话与跨源/身份失效不串数据 |
| CONCURRENCY | 当前真实PG并发256/44和隔离通过 | 活跃多用户/多端实际业务会话并发；不冒充已做生产压测 |
| RECOVERY | 已有本地拒绝/异常/源漂移/403及Web到期恢复证据 | 真机弱网、后台、登录过期、重试与无旧结果覆盖 |
| SECOND-ANDROID | 用户仅有当前 Android | 缺另一机型，未验收且未豁免 |
| IOS | 当前无 iOS 设备 | 缺设备，未验收且未豁免 |

## 数据和证据保护

- 私有主目录：`C:/Users/Steve/my-prototype-logistics/.playwright-mcp/smartbi-complete-20261028`；唯一明细台账 `scenarios.json`。新增服务证据 `service-joint-after-ce784-v2/summary.json`；原 v1 不合契约请求 HTTP400 保留。
- 新 UI 文件 `QA-F006-UI-JOINT-20261029.xlsx`、IDs5285/5286/5287、2日期、产品 `product-dd7f518c745929a3`，不得再上传一套。
- 100SKU 上传：5278/5277/5279，subject `product-13dbfb8a219e694e`。旧 UI 三表5282/5280/5281保持历史证据，不混用。
- 正常批次11003、已入成品批次 `40d43097-c569-4b4a-b280-801a41341df8`、质检 `c057eeed-9be1-4e2f-8f07-ddb6aebd0e9a`；不要重复入库/改已确认产出。历史10997的缺失字段保持缺口，勿补零。
- WIP可从 workflow365 新计划首道解冻→第二道卤制入手，但需正常来源选择和实际库存守恒；已有计划11003不能复用。纯 pooled SFI 没有目标批次时会跳过血缘，不能假造 source_wip_no。
- 三月已修复226行、重试0；当前草稿凭证仍DRAFT且无正式物料批次，不得删除草稿以抹掉原失败。
- Web receipt `C:/Users/Steve/.cache/cretas/release-reports/cretas-1793222717-58839.json` 完整，不重复同样服务/哈希检查。Python日志 `test-output/sc29-commerce-python-deploy-v2.log`，源证明 `sc29-commerce-live-source-proof-v1-1793223953.json`。当前容量证据 `test-output/sc29-capacity-final-private-v1.json` 与 release WT下 `sc28-python-final-sc29-capacity-final-v1.{log,json}`。
- `.codex/rules/*-credentials.md` 只从主工作区读取，真值不得复制进 tracked 文件。不要改自动审批、共享发布脚本、其他任务工作区、Docker/WSL服务或Mac算力配置。

可复制续接提示：继续此文件中的 F006 剩余验收；先恢复直接集成 MCP 并核验 main/ACTIVE/唯一写 Owner，复用既有数据与通过证据，按8/8/8/6短批跑目录与两项focused场景；保持App登录、设备和签名上传原限制，分开报告实现、部署、接口、Web和原生验收。
