# SmartBI 问答引导与分类浏览

Base SHA: `8af2b2576bfee33e4950aef6addb9d96d53ac9ff`。Root 为唯一协调者/台账写者；复用 smartbi-closure-verify-20261025 隔离 worktree。用户确认问答引导＋分类浏览，沿用本任务验证后合并、部署、F006 验收授权。

## Scope 锁与执行

- `SMARTBI-GUIDED-SERVER-20261026`，Owner server worker：`backend/python/smartbi/services/factory_charts/`、`backend/python/smartbi/api/financial_dashboard.py`；仅引导请求、产品对象和图表分组契约及目标测试。验收：该目录 pytest + API contract tests；禁止数据库/迁移/权限/发布脚本更改。
- `SMARTBI-GUIDED-APP-20261026`，Owner app worker（已回交 Root）：`frontend/CretasFoodTrace/src/{screens/smartbi/FactoryChartsScreen.tsx,types/factoryCharts.ts,utils/smartbiFactoryChartGuide.ts,utils/smartbiFactoryChartModel.ts,__tests__/}`；原生引导/分类/分组与目标测试。model 扩展仅序列化新增请求。验收：目标 Jest + tsc；不得修改 API 认证、图表引擎、原始明细、导出协议、依赖/锁文件。
- `SMARTBI-GUIDED-E2E-20261026`，Owner harness worker（已回交 Root）：仅 `scripts/e2e/production-readonly/scenarios/smartbi-guided-readonly.js`、`core/run-suite.js`、对应 tests fixture/unit、生成的 `mcp-entry.js`；新增实际问答/分类只读验收，不改白名单/核心安全防线。验收 node unit/drift + local Playwright fixture，生产执行由 Root。
- Root：Web `FactoryChartRecommendations.vue`、新引导组件/helper/测试、`web-admin/src/api/smartbi/factory-charts.ts`；协调契约、最终验证、合并、发布与 F006 验收。验收：相关 Vitest、vue-tsc/build、浏览器交互、原生真机。

Root 追加窄 scope：Web/RN 的 `factoryChartExport` helper、对应 tests 和 context type，仅把真实产品范围带入既有导出 source/结论字段，不改数字或导出协议。后端产品身份、原始明细与同厂换账号边界已通过独立 risk review，无剩余 P1/P2；这不替代最终测试和发布证据。

所有执行者共享 worktree，严格分文件；不撤销其他人的编辑，不创建孙代理，不改 ACTIVE，不自动 commit/push。原始 OSS UserDisable、三月零快照、10943 历史账本及独立质检角色验收边界保留于 2026-10-26-smartbi-native-factory.md。

## 产品约定

入口默认问答引导，并提供按业务分类浏览。业务问题→必要对象/期间→少量相关图；产品是对象，不是图表菜单。每个指标族一项，单位与画法是二级选择。AI 仍仅排序合格候选，失败如实说明规则推荐，不伪称 AI 生成数值或已完成自然语言问答。用户偏好保留，问答生成仅修改当前视图，保存必须显式操作。导出只包含当前实际展示图表。

## 跨端兼容契约

请求新增可选 `guided: boolean`（默认 false），`analysis_subject: string | null`（当前数据可选产品的 opaque id）。引导端始终发送 guided=true。响应新增可选 `subjects: {id:string,label:string}[]`、`analysisScopeLabel:string`；候选新增可选 `analysis: {familyId:string,familyLabel:string}`。旧请求/返回保持兼容。未选产品时不逐产品生成专业视图；选产品时只用可识别且匹配产品的来源，不能把全厂金额/OEE冒充该产品。对象选项不得绕过工厂/金额权限。没有对应数据显式说明；不补数。

## 实现与验证回执

实现集中在 PR #1209；代码 scope 在本 PR 合入时释放，由 Root 完成最终合并、发布、F006 Web/原生验收。生产切流和业务验收不得从代码状态推断；真实后续回执写入 gitignored `.playwright-mcp/smartbi-guided-20261026/`。

- Web/RN：业务问题→分析主题→产品对象；新组合最多四类，每主题一行，单位/画法二级切换。Web 产品下拉虚拟化，App 最多八个匹配产品并支持搜索；已有明确保存的旧选择保留。查看分析仅本地选择，保存由用户显式触发。
- Server：按真实产品 ID 区分同名 SKU；唯一 ID 的同名无 ID 来源才可关联，歧义来源排除；无产品列、全厂 OEE/现金等不进入产品答案。未知产品/所选期间无数据返回真实缺口，不回退全厂。原始明细使用过滤后数据集。
- 回显 `analysisSubject`（包括 null）在两端严格校验；Web 同工厂换用户/角色、App 身份/上传源变化均清空旧范围并拒收旧响应。
- 导出沿用原协议，真实产品范围进入 source 和分析结论，数值不重算。
- 独立风险终审：无剩余可达 P1/P2；仍须取得最终内容的 CI 和发布/真机证据。
- 本地后端 guided/source/contract/details/financial 62 PASS；目标 Web 34 PASS，目标 Native 22 PASS。旧失败与工具异常日志保留。
- 首轮提交 `30d4dd2a8b2215b51b4af12458d884e0d8500246` CI：RN TypeScript PASS，329 suites / 3423 tests PASS；Python 10462 PASS /169 SKIP /153 deselected /2 xfail（跳过项不计通过）；source/secret/field/script gates PASS。后续只读 harness 修正与 App 搜索显示上限仍以最终 head 的 CI 为准。
- 本地浏览器夹具曾因内联脚本和控件定位失败；仅修夹具/场景，未削弱 mutation guard、脱敏深度、业务断言或生产白名单。最终报告须引用重跑结果。

产品目录来自当前授权数据源；上传报表的对象目录不随期间裁剪，图表结果严格按期间筛选，期间外产品会明确无数据。没有把真实自由输入 AI 问答或所有产品/角色的业务验收宣称完成。原 OSS/历史账本等边界保持不变。

最终代码 `994148a91d754a1aef530ca5cac74cc733d2c122` 的全部七项 CI 已通过（Web/RN 类型与完整测试、Python、field/source/secret/script gates）。本地 canonical fixture 13 PASS，最终真实控件定位补验 1 PASS，unit/drift 66 PASS；实际渲染组件验证了 100 产品中搜索产品99、按业务问题筛选和分类浏览，无 pageerror。后续仅本文和原 OSS blocked 台账恢复，不改变上述已验证源码；通过证据按源码树哈希复用，不手动重跑产品测试。

## GI26 引导问题范围修复登记

状态 in-progress；Base SHA `4ba25f44ea938ccbe94f8dbcc4376c5651bb96b3`；唯一协调者 Root；分支 `codex/smartbi-guide-intent-20261026`。已完成首版 Java/Web/Python 发布，原生 OTA 尚未发布；本批修复产品改变导致库存问题被重置/无关默认图表回填。

- GI26-WEB，Owner web 执行者：仅 Web `src/views/smart-bi/FactoryChartGuide.vue`、`FactoryChartRecommendations.vue`、`__tests__/factoryChartGuide.spec.ts`、`__tests__/factoryChartRecommendations.spec.ts`。先写真实 mounted 红例，再修复保留问题、无匹配显式空结果，不影响未 Generate 的既有收藏偏好。验收相关 Vitest；不得修改导出、依赖、API/auth、后端、台账。
- GI26-RN，Owner app 执行者：仅 Native `src/screens/smartbi/FactoryChartsScreen.tsx`、`src/__tests__/integration/screens/FactoryChartsScreen.test.tsx`。先红例再修复 Generate 意图跨产品/期间重算，不恢复无关收藏；保留问题提示、来源/身份切换清理。验收对应 Jest；不得修改凭证、依赖、协议、其他页面或台账。
- Root：diff/目标验证、必要 CI、PR 合入、Web 与 OTA 发布、F006 验收、归档释放。代码执行者互不覆盖；不创建孙代理、不提交、不回滚他人改动。发生公共文件需求则停并回报。
Root GI26 scope：canonical smartbi-guided-readonly.js、其 unit test、生成 mcp-entry.js，只增加问题保留/无跨类图/来源错误断言；异步问卷在加载后严格验证，静态壳 landmark 不冒充问卷已就绪。原第一次生产 UNVERIFIED 证据保留。

GI26 验证与收尾：RN mounted RED 12 PASS/1 FAIL，修复后 13 PASS；Web mounted RED 24 PASS/1 FAIL，修复后目标测试通过（最终计数见 CI）。Root canonical unit/drift 5 PASS、guided local browser fixture 1 PASS，保留第一轮生产异步 landmark 的 UNVERIFIED 原始证据。生成意图与收藏分离，产品/期间改变按原问题重新选合格图表，无匹配不回填其他问题；显式分类编辑沿用原有收藏行为，旧超过四类选择不截断。Root 将在最终 head CI 通过后合入、释放本批 scope，后续仅 exact-main 发布 Web/OTA 与 F006 验收；未在此处宣称真机已通过。