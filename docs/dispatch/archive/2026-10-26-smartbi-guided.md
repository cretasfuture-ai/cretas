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
