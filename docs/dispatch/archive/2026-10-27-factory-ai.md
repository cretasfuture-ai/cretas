# FAD27 工厂 AI 对话分析

状态 review（最终候选冻结归档）；所有代码 Owner 已冻结并释放文件 scope，后续合入/发布事实以 Git 和发布回执为准；Root 负责 PR/发布/验收；Base SHA `5338bd8a4f4c206f8be67bbc6f3330a438e8dd51`；worktree `C:/Users/Steve/cretas-factory-ai-dialogue-20261027`；branch `codex/factory-ai-dialogue-20261027`。

用户目标：底层保留丰富图表与可分析数据，由自由问题驱动 AI 选图、分析、汇总、追问和多表多维度分析。每次最多四类相关图，不等于缩减图表库。产品是筛选对象；餐饮专属路径排除。

## 已确认基础和边界

Factory recommendations 在 Python，现有 API 已执行同厂、角色、金额权限及原始明细 owner 校验；Java 当前没有该 proxy。Chat、NL2SQL、ChartBuilder/Recommender、跨 Sheet 比较存在但未统一接通；跨 Sheet 比较不等于通用行级 JOIN。仅复用产品 ID、唯一批次、订单/行、WIP/工序任务等已验证关系；缺键、粒度或单位不符明确说明，不猜关联、不造数、不推断因果。

Java 新入口必须经过 ToolExecutionGateway；禁止 Controller 直接 Registry.execute 绕过 principal、descriptor、READ policy、RBAC 与审计。无需为显式固定查询入口新增 ai_intent_config seed 或迁移。Python 原有租户/金额门禁保留，Java 可靠转发受信任 factory/user/role；请求体不能作为身份来源。

## 代码 scope 锁与验收

- FAD27-PY，Owner python 执行者：仅 `backend/python/smartbi/api/financial_dashboard.py`、新 `services/factory_charts/conversation.py`、`test_conversation.py`。允许必要的新同目录会话辅助文件；不改 middleware、认证、DB schema、原图表计算和原始记录。验收新 conversation tests + 原 guided/source/contract/details/financial 目标 pytest。
- FAD27-JAVA，Owner Java 执行者：仅新 `controller/FactoryChartAnalysisController.java`、`ai/tool/impl/smartbi/FactoryChartAnalysisTool.java`、`dto/smartbi/FactoryChartAnalysisRequest.java`、`client/PythonSmartBIClient.java` 的窄 factory 方法，以及新 `*FactoryChartAnalysis*ContractTest.java`。Gateway 公共实现、descriptor inventory、权限映射若需改动，先报告给 Root，不自行扩大 scope。验收目标 Maven contract tests；同一 target 串行。
- Root：接口裁决、必要的 descriptor 元数据、Web/App UI 后续 scope 登记、最终测试/审查/PR/发布/F006 验收。当前两名代码执行者不创建孙代理，不改 ACTIVE、archive，不 commit/push，不修改其他人文件。

## 固定接口 v1

公开查询：`POST /api/mobile/{factoryId}/smart-bi/factory-analysis`；Controller 认证并固定调用 READ-only `smartbi_factory_analysis` Tool，通过 Gateway 执行。Python 内部固定 `POST /api/smartbi/financial-dashboard/factory-analysis`。

公开请求 camelCase：`question`（1..1000 字）、可选 `source` business/upload、`uploadId`、`year`、`periodType` all/year/month_range、`startMonth`、`endMonth`、`analysisSubject`、`contextId`。初次默认 business/all；后续可仅 question+contextId。Java 只映射这些白名单字段到 snake_case；factory_id/user/role 来自可信 principal，未知任意 endpoint/SQL/表名/角色字段不得转发。

统一 `{success,data,message}`。data 使用 camelCase，字段：`factoryId,source,sourceLabel,period,analysisSubject,analysisScopeLabel,question,status,answer,selectedIds,recommendations,catalog,facts,limitations,followUpQuestions,contextId,subjects`。

- status 为 `answered|clarification|unavailable`。LLM 只从当前授权候选目录选择真实 ID；最多四个不同分析族，可支持所有合格绘图类型，不裁成 line/bar/pie。catalog 保存全部合格族及画法/单位元数据；recommendations 是选中的原 FactoryRecommendation，对数字、表数据、detailContext 不重算。
- facts 为 `{id,candidateId,label,value,unit,source}`，来自真实 KPI/规则发现，不由 LLM 编造。answer 汇总真实事实并引用来源；AI 不可用/回复无效明确 unavailable，不能伪称 AI 成功。可以以规则拼出事实文字，但必须如实标注。
- 复用现有授权 recommendations 读取，不创建任意 SQL/JOIN 通道。可用的生产/质量/成本组合优先 `production.fused`；条件不足分别披露，不能把分开的图冒充已关联。
- 问题可识别期间、产品和分析主题；产品唯一匹配才切换，歧义给 clarification+subjects，未知产品不扩大到全厂。支持“再看质量/换个维度/更多图”等追问与有效上下文。
- contextId 为有 TTL、容量上限、factory/user/role 绑定的服务器会话范围，保存范围/选择而非信任客户端旧数值；身份或数据源切换拒绝旧上下文。生产不写业务数据。

必测：多维候选和真实事实、全图表目录不缩减、有效追问、未知/歧义产品、缺关联/异单位、金额低权限、F007/外厂上传/跨账号 context 拒绝、恶意写入/任意 JOIN 不执行、LLM 不可用/虚构 ID 不假通过。最终线上仅 F006；通用 AI chat 不加入只读白名单。

## UI scope 与并行扩容

两后端执行者已按独立模块工作，接口 v1 冻结；追加 Web/App 两个独立 UI scope，最多四个代码执行者并行。理由：Java target、Python pytest、Web node_modules/Vitest、Native node_modules/Jest 均独立，无共享生成物/锁文件；不同时修改同一文件。全部执行者完成后回收，不创建孙代理。Root 保留台账、共享元数据、合并和发布；依赖/锁文件仅 Root 处理。

- FAD27-WEB，Owner Web 执行者：`web-admin/src/views/smart-bi/AIQuery.vue` 的 factory 模式接线、新 `FactoryAnalysisChat.vue`（或必要同名前缀组件/helper）、`FactoryChartRecommendations.vue` 仅 AI 问答入口、新 `web-admin/src/api/smartbi/factory-analysis.ts`、新对应 `factoryAnalysis*.spec.ts`。复用现有聊天入口与完整 FactoryChartCanvas/原始明细/导出能力，不改通用聊天、安全过滤、路由权限或依赖。验收目标 Vitest + 后续 CI 类型门禁。
- FAD27-RN，Owner App 执行者：新 `screens/smartbi/FactoryAnalysisChatScreen.tsx`、`services/api/smartbiFactoryAnalysis.ts`、`types/factoryAnalysis.ts`，`FactoryChartsScreen.tsx` 仅 AI 入口，`navigation/SmartBIStackNavigator.tsx` 新只读聊天页、`types/smartbi.ts` 对应 route params、新对应 Jest tests。复用完整 FactoryChartCanvas、原始明细和现有导出能力。新页面使用 ScreenWrapper `statusBarTranslucent=false`，不得撤销既有 Header 修复。验收目标 Jest。

AI worktree 现 HEAD 已安全快进到 `ab4908ed7c2375689c3ed2063f43e840ad91aa9c`（仅两处已合 Native header 参数）；原登记 Base5338 保留，后端源码未变。`sourceErrors` 是响应额外保留字段，UI 必须显式显示。所有页面以同厂 user/role/auth epoch + source 绑定上下文，切换/卸载/过期或延迟响应不得恢复旧私有答案。问题明确只读；普通 generic chat 不加入生产只读白名单。
## Root 集成与后续修复接管

Root 已仅追加 `smartbi_factory_analysis` 的 D1 inventory/runtime 两个 YAML 记录（tool-descriptors 586→587、legacy569不变，runtime9→10），现有记录不改；FAD27 独立新增只读工具，不接管/合并 S6 B2/B3 冻结变更，不放开生产 cron 或已有 policy。精确 Root scope：`src/main/resources/ai/tool/gateway/{tool-descriptors.yaml,runtime-tool-policies.yaml}`；发布前独立风险复核与真实 Gateway 测试必过。

FAD27-PY 前执行者第二批已冻结；独立复核仍 BLOCK 首问/相对期间、泛指产品和 known-values 出境脱敏。3 文件写 Owner 顺序移交 `factory_ai_python_fix`，原执行者不得继续写。新执行者仅同 3 文件，保留原失败/通过日志，修复后重新通过业务反例，不信此前14项计数。其他 Java/Web/App scope 不变；UI 初版亦保留 BLOCK 回执，已分别回原执行者修复。Root 仍是唯一台账写者。

## 最终接线验收 scope

FAD27-JAVA 追加唯一写 scope：gateway/descriptor 测试下 `RuntimeToolPolicyLoaderTest.java`、`RuntimeToolDescriptorRegistryTest.java`、`ToolDescriptorInventoryDriftTest.java`，仅更新新增只读工具后的计数/fixture，保留所有旧断言。验收上述三项加两个新 ContractTest，同 Maven target 串行。

FAD27-HARNESS，Owner Web 执行者：仅 `scripts/e2e/production-readonly/config/readonly-post-whitelist.js`、新 `scenarios/smartbi-factory-analysis-readonly.js`、`core/run-suite.js` 的场景登记、新对应 tests、`tests/whitelist.test.js` 与生成的 `mcp-entry.js`。待 Java/Gateway 风险复核 PASS 后才加入精确只读 endpoint；通用 chat 仍拒绝。必须真实 UI 问题/追问、图表/事实/来源/详情/导出，F006且零业务写，provider unavailable 不能算 AI PASS。验收 Node 目标 tests + bundle drift gate。Base5338 不变，不改产品源或其他场景；冻结后由 Root 执行生产验收。

## 固定业务键的跨表联合

FAD27-JOIN，Owner cross_table 执行者。Base5338；允许 scope 仅 `backend/python/smartbi/services/factory_charts/{sources.py,business_metrics.py,engine.py,cross_table.py,test_cross_table.py}`，必要同前缀新测试。独立于 conversation/API 3文件 Owner，不互改。目标：事实生产批次 source_pk=production_batches.id 与 quality_inspections.production_batch_id 按同厂稳定键关联，质量先按批次聚合，避免多次质检放大产量/成本。区分生产良品率与检验合格率，按产品ID/单位隔离，不用名称JOIN；覆盖缺失/不完整/低权限明确限制。固定只读 SQL，不迁移、不写数据库、不修改 Entity/Repository/权限或其他租户数据。

验收：真实 builder/engine 候选 tests、唯一批次去重/多次质检/不完整分子分母/混单位/同名异ID/缺成本/金额撤权阴性；上线 F006 只读 SQL 聚合独立比对。当前单事实表 production.fused 不等同跨表联合，不宣称已完成 JOIN。与 Python conversation Owner 先协调新 family/source contract，最多四名代码执行者且共享 Maven 串行。

FAD27-JAVA 追加同功能 `ai/tool/impl/smartbi/FactoryChartAnalysisFailureRelay.java` request-scoped typed relay；现有 client 窄方法允许同功能 typed 403/410/422。原因：现 Gateway 有意清空失败 payload，导致 Python context 410 变成400且污染共享熔断器。只在固定路径使用有界单次HTTP读取；业务4xx不计服务故障；Tool仍真实失败经Gateway审计，Controller按自身requestId一次性consume，只透传封闭状态。不得改 Gateway/AbstractBusinessTool/MDC/ThreadLocal 或用假 success 错误包。验收真实Controller/Gateway及上游HTTP状态、熔断语义、跨requestId拒绝与无permit零HTTP。

FAD27-JOIN 初版3项 direct-builder 测试已通过，但新 prepare/loader/明细链尚未闭环。原 Owner 冻结，Root 接管该相同 scope 做最终校验与修复；其余 PY conversation/API Owner 不变。禁止用3项初版计数代表最终 JOIN 验收。

## 冻结验证与交付状态

- Java：7 类24项通过；真实 Gateway、unknown DTO、单层响应、上游403/410/422/503、request级错误隔离与熔断分类；独立风险复核 PASS。无迁移或 Entity/Repository 改动。
- Python：conversation/guided/source/details/contract/financial/JOIN 合并目标110项通过；日期及澄清、逐轮授权、Java snake_case绑定、opaque provider alias、预算内fallback、真实联合表与facts；JOIN14项独立复核 PASS（含101日期点拒绝）。最终 alias 改动再独立复核。
- Web：目标36项、clarification10项及真实 vue-tsc build mode通过；App最终9项screen及API/合并25项历史通过、tsc通过；RN首问继承route产品已追加回归。UI异步scope及导出风险复核通过。
- 只读 harness：overview、followup、真实canvas/facts/目录、原始明细、Excel以及多表clarification分支；精确白名单+场景12项、bundle/skill drift5项通过。通用chat仍拒绝。所有代码未部署的状态与已通过本地目标分开。
- 生产数据就绪：F006唯一完整质检关联批次10995，状态IN_PROGRESS、实际/良品数量NULL，Silver已完成事实无该批次。线上联合正向数据未满足；仅可验收明确缺数据分支，不能填假值、创建质检或改变生产状态来制造PASS。证据在主工作区 `.playwright-mcp/smartbi-guided-20261026/cross-table-data-readiness.json`。
- 当前尚需：合最新main、必要集成验证和候选制品、PR/必需CI、合并部署及F006 Web/原生App验收。此前header OTA已发布并真机验证；不能据此称FAD27已上线。

最后接线发现并保留的两项 FAIL：真实二维表/表头的客户或事业部名称出境注册不完整；自然语言改变期间后前端详情/导出仍引用初始筛选期间。修复同一批完成：受控授权表字符串脱敏、结构化 answer.period 与每轮独立的详情/导出快照。已合最新 main，merge HEAD `7e67afdf5da7960f0169267b0187aa6ad1b14f05`，没有冲突；该集成引入主线已批准的安全/质检代码，必须跑适用集成门禁。

Web 末项导出接线：追加允许 `FactoryChartCanvas.vue` 与其 `__tests__/factoryChartCanvas.spec.ts`，唯一 Owner 仍 Web。只新增 setOption/resize 完成后的 ready 事件；Chat 切回旧轮后必须等待该轮真实 Canvas 全部 ready，再抓图，等待有超时且持续校验身份/范围/请求代次。不用固定 nextTick 猜测，不改共享导出计算或其他页面行为。验收旧新轮同 candidate ID/不同option 的延迟 ready 图片回归与原Canvas目标测试。

最终冻结：Python111项、Java24项和可信JAR构建（backend tree `508dafae44b9e640a7db6f7523540580a7b1f6f8`，SHA256 `c2ffeb9759021d7beff44be3cf8f29e77fb9626f9958600cef666a80927bd6c3`）通过；Web Chat/Canvas15项及 vue-tsc build-mode通过；RN screen/API18项及类型检查通过；harness18项通过。隐私、跨表、Java与最后UI已知风险独立复核均PASS，无剩余代码BLOCK。原始FAIL均已记录，不计作生产验收；PR/CI/发布/F006 Web及原生验收仍待实际执行。
