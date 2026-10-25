# Codex 交接：SmartBI 工厂图表与分析（财务经营看板之后）

**写于**：2026-10-25，Claude Code（Opus）交给 Codex。
**用法**：把下面「给 Codex 的任务」整段贴给 Codex，附录是它要读的事实底账。开工前必须按 AGENTS.md 重新核验仓库与线上真值；本文写的状态以 2026-10-25 为准，可能已经过期。

---

## 给 Codex 的任务

你接手 Cretas 食品溯源系统 web-admin「数据与分析 / 经营分析」里的 SmartBI 工作。Owner（Steve）的原话是「之前做餐饮或者本来 SmartBI 就有候选的大量图表，都是要做出来的，只是这些图表是否是工厂需要的，需要靠 AI 给建议，按照工厂的指标给出不同的图表和对应的分析」。所以目标分两层：

1. **把财务经营看板剩下的几项做完**（阶段 1，小而确定）。
2. **把仓库里已有的大量候选图表变成「工厂需要的图表」**：先给出一份工厂指标 → 图表的目录，再做「按这家工厂有的数据推荐图表 + 每张图的规则分析」，AI 只负责在合格候选里排序和说理由（阶段 2–4）。

### 先读

- `AGENTS.md`、`.claude/rules/execution-and-verification.md`、`.claude/rules/measurement-and-wiring.md`、`.claude/rules/worktree-and-main-only-deploy.md`、`.claude/rules/windows-shell-and-paths.md`、`.claude/rules/python-services-architecture.md`。
- 本文附录 A–F。

### 阶段 1：财务经营看板收尾

1. **PPT / PDF 导出带上规则结论**。Excel 已经做了（#1164，第一张工作表「分析结论」）。PPT 和 PDF 现在只吃 `analysis_results`（AI 文本），页面上的页首结论和「做得好的 / 需要关注 / 建议」带不出去。
   - 照 Excel 的做法：请求加可选 `headline` / `findings`，生成器在最前面加一页「分析结论」，没有结论时不出这页。
   - 前端 `FinancialDashboardPBI.vue` 的 PPT / PDF 导出调用传 `dashboardResponse.value?.headline / findings`。
2. **按收入加权的毛利率**。比率表（样图⑤，F006 上传 5254）只有各月毛利率百分比，所以现在是各月简单平均，页首和图上都注明了「各月简单平均」。
   - 当表里同时有收入和成本（或收入和毛利）时，改为加权：总毛利 ÷ 总收入。页首、结论、毛利率趋势图 KPI 走同一个口径（都从 `financial/metrics.py` 取）。
   - 只有百分比时保持简单平均并继续注明。
   - 需要一份带收入 / 成本的样表来验收：自己造一份上传到 F006，记录 upload id。
3. **看板视觉与交互打磨**。先在线上逐张截图评估（6 份样表 × 各自出得来的图），列问题清单，再改。按 `docs/ux/` 与现有设计 token，不新造风格。

### 阶段 2：工厂图表目录（先产出文档，经 Steve 看过再写代码）

在 `docs/` 下产出一份「工厂图表目录」，每一行：

- 工厂指标（对应客户 11 条里的哪一条，或工厂业务库的哪类指标：产量 / 出成率 / 成本结构 / 单位成本 / 质量 / 库存 / 采购 / 销售 / 现金）
- 候选图表（从附录 C 的已有库里选：财务 19 张、通用 `services/charts` 28 种、物化模板里通用的几种、工厂 gold 接口）
- 数据要求（哪些列 / 哪张业务表；缺了就说缺什么）
- 工厂是否需要（需要 / 可选 / 不需要），附一句理由
- 已有实现在哪、还缺什么

餐饮专属的模板（dish_*、member_*、groupon、桌型、餐段等）标「不需要」，不移植。

### 阶段 3：按工厂数据推荐图表

- **候选只来自规则**：根据上传表识别出的列（`financial_data_normalizer.py`）或工厂业务库里这家厂真有数据的指标，筛出「数据要求满足」的图表。
- **AI 只排序和说理由**：用现有 `common.llm_router.call_chain`（`SLOT.CHART`，参照 `chart_recommender.py` 的接法）把合格候选排序，每张给一句「为什么推荐、对应哪个指标」。AI 不能加候选以外的图，也不能产出数字。
- **AI 不可用时**按规则顺序给，页面照常可用，并明确标出「未经 AI 排序」。
- **页面上**显示推荐理由，用户可以改选，改选按工厂记住（可参照 `list-factory-templates` 的按厂频次做法）。
- 缺数据的图不画、不造数，放进现有「还缺数据」并写清缺哪一列或哪张表。

### 阶段 4：每张图的规则分析

- 仿 `financial_findings.py`：每张工厂图给「做得好的 / 需要关注 / 建议」，每条带表里或库里算得出的数字。
- 只说算得出来的事实，不推测原因。
- 行业基准（`api/benchmark.py` 的 `FOOD_PROCESSING_BENCHMARKS`，静态常量）只能标成「行业参考值」，不能当本厂目标。
- AI 段落放在规则结论下面作补充（现有「AI 补充」区），和规则结论分开显示。

### 每个改动的完成条件

- **能红的测试 + 变异验证**：断言读用户看得见的输出（KPI、页首、结论文字、导出文件内容），每条新断言做一次变异证明它会红，并在 PR 描述里列出变异。
- **数字与图同一口径**：结论里的数和图上、KPI 上的数来自同一个函数（`financial/metrics.py` 或新的工厂指标库），不另算一份。
- **线上复验**：合并部署后在 F006 线上真点，读 `[data-testid=dashboard-headline]` / `[data-testid=findings]` 等，截图留证；要有阳性和阴性对照（该出的出了，缺数据的没被画出来）。
- **一个切片一个 PR**，PR 描述写做了什么、验证命令和结果、变异清单。

### 发布与权限

- **授权边界**：Steve 常设授权「验证绿就合并部署，不要再问」。prod 就是测试环境，只用 F006 租户，测试账号见 `.codex/rules/test-credentials.md`。
  - 需要新样表就上传到 F006，并在 PR 里记录 upload id。
  - 不写其它租户，不改业务数据。
- **分支与提交**：worktree off `origin/main`，分支 `codex/*`；`git commit -- <列出的路径>`。
- **推送与合并**：推送与合并用有写权限的账号（`gh` 里 Stevenjxie 只读，Cretasfuture 可写）。合并用 `gh pr merge <n> --squash --match-head-commit <完整 head SHA>`。
  - ⛔ 不用 `--auto`：本仓 main 无分支保护，会当场合并。
- **CI**：全部是 GitHub 托管 runner（仓库已 public），⛔ 不恢复自建 runner。vitest job 约 13 分钟，pytest 约 5 分钟。
- **发布前检查外来改动**：先跑 `git log <上次部署SHA>..origin/main -- backend/python web-admin`。web 发布会带上 main 上所有人的前端，别人前端调用的 Java 路由必须已在线上 jar 里，否则先等那条线发 Java，或者用只读 GET 确认。
- **部署**：在 clean 的 exact `origin/main` 上（`git fetch && git checkout --detach origin/main`）：
  - Python：`./scripts/deploy/deploy-smartbi-python.sh --env prod`，判据退出码 0 + 服务器文件含新代码 + 进程启动晚于文件。
  - web-admin：`./scripts/deploy/release-cretas.sh --phase deploy --base-sha <你的合并提交>^ --tests '<任意选择器>' --no-prefer-ci-artifact --confirm-prod YES-PROD`。
    - Base SHA 取你合并提交的父提交，这样只检测到 Web、不动 Java。
    - 判据 `DEPLOY_EXIT=0`、日志里 `RELEASE_FINAL_STATUS` 恰好 1 次、四方哈希一致。⛔ 不跑 prewarm。
  - 如果发布被「不是 exact origin/main」拒绝，是构建期间 main 前进了（正确拒绝），重新 detach 到新 tip 再发，并检查新进来的改动。

### 停下来的情况

- 判据自相矛盾、阳性对照不过、拿不到租户证明：停下修仪器，不硬做。
- 阶段 2 的目录要 Steve 看过再进阶段 3（目录决定后面写哪些图）。
- 需要 Steve 拍板的事，一次给选择题问完。

### 不要重复做

附录 B 里的已完成项不要重做。不跑完整 Java 测试（预期不改 Java）。

---

## 附录 A：客户需求与样图

**客户 11 条需求（Steve 2026-10 贴入，原样）**

> 1.从预算完成情况（收入、毛利、费用、净利等）；
> 2.从实际与同期环（收入、毛利、费用、净利等)；
> 3.从产品结构上（收入、毛利等）
> 4.从成本结构上
> 5.从渠道上
> 6.从重点产品上
> 7.从各业务单上等
> 8.给出分析结论：好的与不好的
> 9.建议
> 10.也可参照帆软数据看板进行设计
> 11.期间可自定义（比如：可选择各单月，各单年，各单季，或1-2月，1-7月，1-8月等，灵活提取数据）

「同期环」原文如此，指同期 + 环比；「业务单」原文如此，指业务单元。

**6 张样图**：客户发的是图片，仓库里没有原图。下面是逐张核对线上时记下的标题与关键数字，F006 上传 5250–5255 就是照这些录入的。

| 样图 | 内容 | F006 upload |
|---|---|---|
| ① 预算完成情况 | 年度目标 35,429 / 实际 17,891 / 达成 50.50%；Q1 20.82% / Q2 67.57% / Q3 80.23% / Q4 32.16%；柱（目标 / 实际）+ 达成率线 + 季度进度 | 5250（录入后目标合计 35,430.33，差 1.33 万，疑为样图各月四舍五入） |
| ② 业绩同比 | 上年 17,424 / 本年 28,191 / 增长 10,767 / 61.79%；本年上年对比柱 + 同比线 | 5251 |
| ③ 损益表瀑布 | 收入 8,333、成本 1,471、销售 540、管理 329、研发 999、财务 604、其他收益 65、营业外收入 42、营业外支出 86、所得税 1,103、净利润 3,308 | 5252 |
| ④ 费用同比及预算达成 | 各月费用柱 + 预算达成率折线，最高 277.73%（10 月），还有 162.93%、258.51% | 5253 |
| ⑤ 各月毛利率 | 本年 / 上年各月毛利率折线，合计 26.20%（表里只有百分比，系统只能算简单平均 26.33%，差 0.1 个百分点） | 5254 |
| ⑥ 品类结构双环 | 内外两环本年 / 上年品类占比，酱料今年 34%、2024 年 37% | 5255 |

线上地址：`https://admin.cretaceousfuture.com/smart-bi/analysis-hub?tab=finance&section=dashboard&upload_id=<id>`，点「分析结论与建议」。

## 附录 B：已完成（不要重做）

财务经营看板（`web-admin/src/views/smart-bi/FinancialDashboardPBI.vue` + `backend/python/smartbi/services/financial*`）已上线，主要 PR：

- #1074 #1078 #1081 #1085 #1092 #1094 #1106 #1113 #1122 #1123：租户安全读上传、标准明细、指标库、不造图、页首结论、一行期间选择、AI 抽屉、月份筛选后重画、失败回滚、精简工具栏和预算图、「还缺数据」写清原因、经营总览卡读工厂数据。
- #1127 #1134：环比、业务单元图、规则结论（预算 / 同比 / 费用 / 业务单元 / 品类占比）、季度都没达标时也说最好的月份。
- #1162：比率表（毛利率）页首和结论、损益表结论（毛利率 / 期间费用占比和最大一项 / 营业外 / 净利润同比）、同比表增长最多月份和复盘建议、最近一月环比。
- #1164：品类表结论与建议（增减金额 / 集中度 / 复盘建议）、Excel 导出第一张表「分析结论」、毛利率差值用显示出来的两个数相减。
- #1172：毛利率差值为 0 时写「与上年持平」，语气中性，不进「做得好的」。

2026-10-25 线上 F006 已逐张复验 5250–5255 的页首、结论和 Excel 导出。

之前三轮审查修掉的错算 / 错显，回归测试在 `backend/python/smartbi/tests/test_financial_dashboard_correctness.py`，改动前先跑它：渠道造数、期间不通、毛利率简单平均未注明、其他收支漏算、费用混入收入、汇总行重复、缺数据当下滑、失败算成功、单位丢失、日期 / 百分比识别、重点产品按品类排、负收入被排除、导出丢倍率、上传读取契约不匹配。

```bash
cd backend/python
python -m pytest smartbi/tests/test_financial_dashboard.py smartbi/tests/test_financial_dashboard_correctness.py smartbi/tests/test_financial_data.py -q -p no:cacheprovider   # 2026-10-25: 116 passed
cd web-admin
npx vitest run src/views/smart-bi/__tests__/financialDashboardState.spec.ts   # 31 passed
```

## 附录 C：已有候选图表（2026-10-25 盘点）

路径相对 `backend/python/`。线上入口是 `backend/python/main.py`（不是 `smartbi/main.py`），很多图表路由不以 `/api/smartbi` 开头。

- **财务看板 19 张**（`smartbi/services/financial/`，注册表 `registry.py`，`POST /api/smartbi/financial-dashboard/batch`）：kpi_scorecard、budget_achievement、yoy_mom_comparison、pnl_waterfall、expense_yoy_budget、category_yoy_comparison、gross_margin_trend、category_structure_donut、cost_flow_sankey、variance_analysis、cash_flow_waterfall、channel_analysis、product_ranking、ar_aging、cashflow_trend、hr_cost_analysis、bullet_chart、small_multiples、business_unit_analysis。
- **通用图表库 28 种**（`smartbi/services/charts/`，`@register_chart`，输入行数据，输出 ECharts option；路由 `/api/chart/build|batch|types|preview|recommend|smart-recommend`）：line、bar、bar_horizontal、area、pie、donut、nested_donut、scatter、waterfall、sankey、bullet、dual_axis、budget_comparison、combination、funnel、gantt、gauge、pareto、radar、slope、sunburst、treemap、wordcloud、boxplot、heatmap、matrix_heatmap、correlation_matrix、parallel。
- **物化模板约 37 个**（`smartbi/services/materialized_analytics/templates/`，Polars 跑在上传数据上，结果存 `smart_bi_pg_analysis_results`；路由 `/api/smartbi/analytics/*`、`/list-factory-templates`）：
  - 通用：top_n_by_dim、monthly_trend、category_distribution、anomaly_detection、pareto_analysis、defect_rate_top_n、purchase_inventory_inflow。
  - 其余多为餐饮专属。
- **按维度的分析函数**（`smartbi/services/analysis/`，约 35 个，`/api/analysis/sales|department|region|finance/*`）。
- **餐饮分析**（`restaurant_analyzer.py`、`services/restaurant/sections/` 约 40 段）：输出结构化 JSON，不是图表配置。餐饮线已废弃（Steve 2026-10-08），只取可复用的思路，不移植餐饮口径。
- **已有推荐逻辑**：
  - `chart_recommender.py`：LLM 走 `llm_router` 的 `SLOT.CHART`，按数据结构缓存，少于 50 行或无 key 退回规则；`/api/chart/smart-recommend`。
  - `/api/chart/recommend`：纯规则，带 ID 列和基数守卫（`api/chart_heuristics.py`）。
  - 其它：`scenario_detector.py`（LLM + 规则兜底）、`table_classifier.py`（规则版式类型）、物化模板各自的 `applies(schema)`。
- **前端**：
  - 通用渲染器 `web-admin/src/components/smartbi/DynamicChartRenderer.vue`。
  - `SmartBIAnalysis.vue` 调 `smartRecommendChart`；`FinanceAnalysis.vue`、`SalesAnalysis.vue` 调规则推荐。
  - 路由在 `web-admin/src/router/modules/smartbi.ts`。`ProductionAnalysis.vue` 等几个页面没有挂路由。

## 附录 D：工厂指标数据来源

- **生产与成本**：
  - `smartbi/gold/factory_production_etl.py` 把 `cretas_db.production_batches` / `production_reports` 抽到 `smartbi_db.fact_production_batch` → `agg_factory_batch_daily`（批次数、计划 / 实际 / 良品量、平均出成率、料 / 工 / 设备 / 其他 / 总成本）。
  - 接口 `smartbi/api/factory_production_gold.py`（`/api/smartbi/factory/cost-structure|yield-trend|product-cost-compare|process-cost|production-fused`），前端 `web-admin/src/api/smartbi/factoryGold.ts`。
- **销售**：`smartbi/api/factory_overview.py`（`/api/smartbi/factory-overview/sales`，读 `sales_orders` / `sales_order_items` / `customers`）。
- **生产 / 库存 / 采购分析**：`smartbi_compat/api/`（`/api/mobile/{factory_id}/smart-bi/analysis/*`）：完成率、质量率、OEE 趋势、库存账龄 / 临期 / 周转、采购。
  - web-admin 没有调用方。
  - 其中 quality 读的是餐饮表，工厂不能直接用。
- **上传表上的公式**：`metric_calculator.py`（约 35 个 MetricType，`/api/metrics/calculate`）。
- **行业参考常量**：`api/benchmark.py` 的 `FOOD_PROCESSING_BENCHMARKS`（毛利率、出成率、OEE、损耗率、库存周转天数等），静态值。
- ⚠️ 已知数据缺口：工厂谱系在 WIP 层断、部分执行数据稀疏（见 memory「食品 DAG 能力核查」）。F006 是 2026-10-24 重置的演示租户，业务数据量小，推荐和分析要能处理「没有数据」。

## 附录 E：「AI」在这里是什么

仓库里**没有本地部署的大模型**。文字生成全部走云端阿里百炼（qwen 系列，`common/llm_router.py` 多账号轮换）。本地跑的只有：

- embedding（Java `cretas-embedding.service`，gRPC `localhost:9090`，gte-base-zh 768 维；SmartBI 用于列名→标准字段、问题→模板 RAG）。
- 意图分类器。

所以「靠 AI 给建议」落地为：

- 规则筛候选。
- `llm_router` 排序和写理由；可选用 embedding 做指标 / 列名语义匹配。
- AI 只给候选、不授权，同 Steve 2026-10-02 的原则「置信度不作授权依据」。

## 附录 F：容易踩的坑

- `backend/python/smartbi/api/financial_dashboard.py` 是 **CRLF** 行尾，其它多数文件是 LF。改写脚本用文本模式 `newline=''` 并按文件原行尾替换；含转义的内容用编辑工具直接落盘，不经过 heredoc / sed（这次就被 sed 吃掉过一次 `\n`）。
- 跑 smartbi 单文件 `python -c "import smartbi.services..."` 会因为 `services.*` 绝对导入失败，用 pytest（conftest 设好了路径）。
- 前端的「源码断言」测试在 `web-admin/src/views/smart-bi/__tests__/financialDashboardState.spec.ts`，接线类改动可以照它的写法加一条。
- 金额显示按列名单位缩放（「万元」列显示带「万」，没写单位的不带），新结论文字用 `financial/base.py` 的 `format_amount`，不要自己格式化。
- 内置浏览器会话会过期登出，复验前先确认还登录着；导出验证可以在页面里拦截下载、读 xlsx 的 `xl/workbook.xml`，不必落盘。
