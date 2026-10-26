# 工厂图表目录（Steve 已确认范围）

2026-10-25。Steve 已确认：“需要”的进入首批，“可选”的满足条件再推荐，餐饮专属排除。目标用户：工厂负责人、财务、生产、质量、采购和仓库负责人。本文确定候选范围，具体实现、测试和线上验收证据见本批交付回执；不把目录中的数据要求视为已满足。

当前代码证据以 `a8dde4421135b9a3acf34361debedb4e3b342cbe` 及本次导出、加权毛利率与界面收尾切片为范围；合并、部署、验收另见交付回执。F006 的真实数据量小：不能因为候选存在就画空图，更不能用演示数补齐。

## 选择建议

第一批优先：预算达成、同比环比、损益、毛利率、费用、产品/渠道/业务单元、成本结构、同产品单位成本、产量趋势、合格/不良数量及比率、库存临期/账龄、采购入库。各厂只出现满足数据要求的项目。

第二批可选：流程成本、库存周转、现金/应收、帕累托、热力图、批次分布、异常观察。统计样本、分母、单位或来源不满足时，仅显示缺口。

客户需求编号：①预算完成，②同期/环比，③产品结构，④成本结构，⑤渠道，⑥重点产品，⑦业务单元，⑧结论，⑨建议，⑩参考看板设计，⑪自选期间。⑧⑨⑪适用于每一张被选中的图；⑩属于界面设计参考，不产生一套计算口径。

“需要”表示适合进入工厂候选库；“可选”表示有明确使用场景时再推荐；“不需要”表示不移植。所有行的数据要求是**业务准入条件**，通常比现有 builder 的 `required_columns` 更严格。

## 一、财务候选：19 张

路径缩写 F = `backend/python/smartbi/services/financial/`。现有财务入口为 `/api/smartbi/financial-dashboard/batch`，界面为 `web-admin/src/views/smart-bi/FinancialDashboardPBI.vue`。注册成功不等于有足够数据；页面导航也没有列出所有 19 张。

| 工厂指标/需求 | 候选图 | 数据要求；缺什么就提示什么 | 是否需要及理由 | 已有实现与缺口 |
|---|---|---|---|---|
| 收入/毛利/费用/净利预算，① | budget_achievement | 同口径期间、预算、实际；明确科目和金额单位 | 需要：先看目标缺口 | F/budget_achievement.py；收入路径已有，其他科目不能冒用收入达成措辞 |
| 实际与上年/上月，② | yoy_mom_comparison | 当前与可比期间金额、月份；缺上年不能报同比，缺上月不能报环比 | 需要：区分规模与变化 | F/yoy_mom_comparison.py；需按指标验证完整期间和缺期说明 |
| 损益及利润形成，④ | pnl_waterfall | 收入、营业成本、费用、税和其他收支科目；合计与明细分层 | 需要：解释净利从哪里来 | F/pnl_waterfall.py；已有，缺科目应明确提示，不能把净利当经营现金 |
| 费用同比/预算，①②④ | expense_yoy_budget | 费用科目/费用表、实际、预算、上年和月份 | 需要：暴露超支 | F/expense_yoy_budget.py；已有；费用增加不按收入增长的好消息着色 |
| 品类收入/毛利同比，②③ | category_yoy_comparison | 品类、期间、两期金额；毛利必须有对应成本/毛利金额 | 需要：看结构变化 | F/category_yoy.py；已有金额图，指标切换/毛利口径需逐项接入 |
| 毛利率，②④ | gross_margin_trend | 收入+成本或收入+毛利金额优先；仅百分比时明确简单平均；可比期间 | 需要：避免平均比率掩盖收入权重 | F/gross_margin_trend.py + metrics.py；本次切片统一页首/规则/KPI，完整性和单位均须验收 |
| 品类收入结构，③ | category_structure_donut | 品类和两期金额；同一期间/金额单位 | 需要：看集中度和占比迁移 | F/category_structure.py；已有；层数/标签多时给明细，不塞满双环 |
| 收入到成本/费用/利润，④ | cost_flow_sankey | 可对账的损益科目与带符号金额 | 可选：老板需要追踪分解时有用 | F/cost_flow_sankey.py；已有；这是财务流向，不能当生产物料流/批次谱系 |
| 预算差额，① | variance_analysis | 同期预算和实际，明确差异方向 | 需要：按差额安排复盘顺序 | F/variance_analysis.py；已有；与预算达成图择主次，避免重复推荐 |
| 现金净流入分解，现金 | cash_flow_waterfall | 经营/投资/筹资现金流入流出，实际收付日期 | 需要：有现金流表才推荐 | F/cash_flow_waterfall.py；已有；损益表/销售订单不能替代收付记录 |
| 渠道收入/毛利，⑤ | channel_analysis | 真实渠道字段与金额；毛利另需成本 | 需要：比较渠道贡献 | F/channel_analysis.py；已有；无渠道不得按客户名猜渠道或造渠道 |
| 重点 SKU 收入/毛利排名，⑥ | product_ranking | 产品/SKU 标识、金额、单位；毛利另需成本 | 需要：找到重点产品 | F/product_ranking.py；已实现但不在当前财务导航，需接入口及真实 SKU 选择 |
| 应收账龄，销售/现金 | ar_aging | 客户、未核销应收、业务日期/到期日或明确账龄桶、截至日 | 需要：判断回款风险 | F/ar_aging.py；已有；必须和应收余额对账，订单总额不等于未收金额 |
| 现金收支趋势，现金 | cashflow_trend | 月份与三类现金收付；完整比较期 | 需要：观察资金缺口 | F/cashflow_trend.py；已有，需真实现金来源和零/缺失区别 |
| 工资/社保/福利等人工成本，④ | hr_cost_analysis | 人工成本项目、月份、金额；单位成本还需同口径产量 | 可选：财务有细项时看 | F/hr_cost_analysis.py；已有；考勤时长不能直接冒充已归集人工成本 |
| 单个指标目标达成，① | bullet_chart | 实际、目标、同单位与同期间 | 可选：适合紧凑摘要 | F/bullet_chart.py；已有；与预算主图重复时降序 |
| 产品/部门小图比较，③⑦ | small_multiples | 同一指标、类别、时间；每组有足够观察点 | 可选：多 SKU 厂按同轴比较 | F/small_multiples.py；已有；需限制数量、分页和统一坐标 |
| 业务单元贡献，⑦ | business_unit_analysis | 真实部门/事业部/分公司字段和金额；目标/上年可选 | 需要：区分责任单元 | F/business_unit_analysis.py；已挂财务导航；缺部门不把品类改名顶替 |
| 关键指标摘要，①②④ | kpi_scorecard | 每个指标所需分子/分母及单位；缺失单项不补 0 | 需要：作为摘要而非多一张重复图 | F/kpi_scorecard.py；当前不在财务图切换条，需和经营概览职责对齐 |

## 二、通用渲染库：28 种

路径缩写 C = `backend/python/smartbi/services/charts/`；注册表 `registry.py`，渲染器 `web-admin/src/components/smartbi/DynamicChartRenderer.vue`。这些是画图能力，**尚不是已经定义指标、拿到工厂数据的业务图**。下一阶段需把下表的数据条件编码成候选资格，不能把 28 个类型全部交给 AI 自由选。

| 工厂指标/需求 | 候选类型 | 数据要求/缺口 | 是否需要及理由 | 已有实现与还缺什么 |
|---|---|---|---|---|
| 产量/成本/库存/销售趋势，② | line | 时间+一个同单位指标；缺期留空 | 需要：趋势默认首选 | C/basic/line.py；缺工厂指标绑定和规则结论 |
| SKU/供应商/部门比较，③⑥⑦ | bar | 类别+同口径数值 | 需要：清楚比较 | C/basic/bar.py；缺真实维度绑定及数量限制 |
| 长名称产品/原料排名，⑥ | bar_horizontal | 类别+数值，按规则排序 | 需要：比竖柱更适合长名称 | C/basic/bar.py；缺 Top N/其余合并规则 |
| 库存量/产量构成趋势 | area | 时间+可累加指标；统一单位 | 可选：适合量，不堆叠比率 | C/basic/area.py；缺指标允许累加标记 |
| 成本/库存金额结构，③④ | pie | 类别+非负可加数；总额有意义 | 可选：类别很少时用 | C/basic/pie.py；类别多改条形，不画负值占比 |
| 成本/品类结构，③④ | donut | 同 pie；清楚显示分母/总额 | 需要：结构默认候选 | C/basic/pie.py；缺单期结构的工厂数据接线 |
| 两期结构，②③ | nested_donut | 两期同一类别集合、金额 | 可选：仅小分类数 | C/basic/pie.py；重复财务双环时只推荐一个 |
| 批次产量与单位成本关系，④ | scatter | 同一批次两数值、标识、单位、有效样本 | 可选：探索关联，不能称因果 | C/basic/scatter.py；缺样本/单位检查及“仅关联”措辞 |
| 成本差异/利润/库存收发变化，④ | waterfall | 可对账起点、带符号变动、终点；缺期初/期末不画库存瀑布 | 需要：解释差额 | C/financial/waterfall.py；业务含义需分别注册候选 |
| 已记录工序物料/成本流，④ | sankey | 真实边、方向、流量/金额、守恒/差异说明 | 可选：谱系完整时才准入 | C/financial/sankey.py；工厂 WIP 关系不完整时只能报缺口 |
| 产量/费用目标达成，① | bullet | 实际+明确目标+同单位期间 | 可选：用作摘要 | C/financial/bullet.py；缺目标出处，行业参考不是目标 |
| 产量与同口径单位成本，④ | dual_axis | 共用时间轴、两个明确单位 | 可选：确有不同量纲时用 | C/financial/dual_axis.py；需清楚标左右轴，不能共轴误导 |
| 预算/实际，① | budget_comparison | 科目/期间、预算、实际 | 需要：非财务指标的预算比较 | C/financial/waterfall.py；与财务同类图去重 |
| 产量+计划/完成率，①② | combination | 共用时间轴；比率分母非零且完整 | 可选：读两套指标有业务价值时 | C/specialized/combination.py；缺指标组合白名单 |
| 采购/销售单状态流转 | funnel | 同一对象队列、连续阶段、去重计数与时间窗口 | 可选：不能拿各状态独立库存冒充转化漏斗 | C/specialized/funnel.py；缺真实队列/阶段资格 |
| 排产/批次计划与实际 | gantt | 批次/任务、开始/结束、状态、产线 | 可选：以实际排程数据为前提 | C/specialized/gantt.py；缺业务接线及同名排程页面分工 |
| 单项完成率/设备状态 | gauge | 实际值、合法上下界；目标有出处 | 可选：信息密度低，不作为默认 | C/specialized/gauge.py；缺目标语义和不可计算态 |
| 不良原因/成本大项/采购集中度，④ | pareto | 类别+非负可加数、清楚排序与累计分母 | 需要：找前几大贡献项 | C/specialized/pareto.py；缺工厂维度/异常值校验 |
| 多项运营指标比较，⑦ | radar | 多指标可解释的归一化范围和方向；所有维度有数据 | 可选：不凑综合评分 | C/specialized/radar.py；缺标准化规则，默认降序 |
| 两期产品/部门变化，②⑦ | slope | 两个可比期间、同一对象、同单位 | 可选：对象少时清楚 | C/specialized/slope.py；缺工厂候选语义绑定 |
| 产品层级/成本层级，③④ | sunburst | 真实无环层级、非负可加数 | 可选：层级有业务意义时 | C/specialized/sunburst.py；缺层级来源和父子对账 |
| 多 SKU 金额结构，③⑥ | treemap | 层级或类别+非负金额；同一期间 | 可选：大类较多时替代饼图 | C/specialized/treemap.py；缺小项合并/可访问明细 |
| 质检/停机文本频次，质量 | wordcloud | 有来源的脱敏文本、词频；无文本不生成 | 不需要：不能替代缺陷数量和责任记录 | C/specialized/wordcloud.py；保留库能力，不进入首批工厂推荐 |
| 批次单位成本/出成率分布，④ | boxplot | 同口径逐批样本、单位、足够样本数；不得输入汇总均值假作分布 | 可选：看离散程度 | C/statistical/boxplot.py；缺样本量及异常点可追溯规则 |
| 日期×工序/产线产量或缺陷 | heatmap | 两维+同口径数值，缺失与零可区分 | 需要：定位时段/工序集中 | C/statistical/heatmap.py；缺工厂数据绑定和缺失样式 |
| 产品×月份成本/产量 | matrix_heatmap | 明确行列键与数值；缺失保留空 | 可选：密集比较 | C/statistical/heatmap.py；与 heatmap 业务重合时去重 |
| 工艺参数与质量的相关性 | correlation_matrix | 同批对齐多个数值列、足够样本、非恒定列 | 可选：探索而非原因诊断 | C/statistical/heatmap.py；缺样本守卫、泄漏/混杂说明 |
| 多工艺参数批次比较 | parallel | 批次标识、多数值参数、单位/规范化、样本上限 | 可选：专业用户场景 | C/statistical/parallel.py；默认不给普通老板页推荐 |

## 三、物化分析可复用项

路径缩写 T = `backend/python/smartbi/services/materialized_analytics/templates/`。现成 `applies(schema)` 只是起点；若它要求弱于下表，不得据此宣称数据够用。模板里部分 helper 来自餐饮 namespace，重用前要验证其科目、单位和筛选语义，不能仅改标题。

| 工厂指标/需求 | 候选模板/图 | 数据要求/缺口 | 是否需要及理由 | 已有实现与还缺什么 |
|---|---|---|---|---|
| 产品/供应商/原料排名，⑤⑥ | top_n_by_dim / 横条图 | 工厂维度+可汇总数值 | 需要：高复用 | T/top_n_by_dim.py；缺工厂字段映射、指标口径及逐图结论 |
| 产量/销售/成本趋势，② | monthly_trend / 折线 | 日期+指标，先确定日/周/月粒度 | 需要：基础趋势 | T/monthly_trend.py；当前语义有营收倾向，需工厂单位和期间约束 |
| 产品/成本结构，③④ | category_distribution / 环或条形 | 类别+非负可加数 | 需要：通用结构 | T/category_distribution.py；与财务结构图去重 |
| 同产品批次异常观察 | anomaly_detection / 趋势异常点 | 有序数值样本、足够历史、同产品单位 | 可选：只说偏离，不能推测原因 | T/anomaly_detection.py；需定义工厂样本资格及规则说明 |
| 缺陷/成本主要贡献，④ | pareto_analysis / 帕累托 | 类别+可加正值及累计分母 | 需要：排复盘顺序 | T/pareto_analysis.py；避免与图表库重复算一份 |
| 不良率/合格率，质量 | defect_rate_top_n / 条形 | 不良数+总数，或合格数+总数；真实产品/批次/工序维度 | 需要：把质量问题排出顺序 | T/defect_rate_top_n.py；现有 applies 允许仅缺陷数，应只报数量，不能编造不良率分母 |
| 采购入库金额/数量，采购库存 | purchase_inventory_inflow / 供应商排名+趋势 | 供应商/仓库、原料、金额；数量/单位、日期按子图另判 | 需要：采购侧有价值 | T/purchase_inventory_inflow.py；只是采购入库，不能声称已闭合“期初+收-发=期末” |

其余模板逐项排除/保留界限如下；“不需要”均表示不按餐饮口径移植：

| 模板 | 工厂是否需要 | 原因/复用边界 |
|---|---|---|
| dish_sales_top_n、dish_slow_movers、dish_category_breakdown、dish_store_drill | 不需要 | 菜品和门店语义；工厂 SKU 排名使用上面的通用模板 |
| dish_time_slot_matrix、dish_by_table_type、time_slot_revenue、table_type_comparison | 不需要 | 餐段/桌型不对应工厂工序或班次，不能更名套用 |
| member_consumption、member_deep_analytics、stored_value_card_consumption | 不需要 | 会员/储值卡不是工厂客户应收与预收 |
| groupon_channel_breakdown、combo_usage_rate、promotion_impact | 不需要 | 团购/套餐/促销语义不纳入工厂首批 |
| store_customer_stratification、store_performance、staff_performance | 不需要 | 餐饮门店/员工营业额口径；工厂部门/工时需要真实新指标定义 |
| payment_method_mix、reverse_checkout_stats、refund_analysis | 不需要 | POS 收款/反结账不是采购付款/销售退货，不能移植现成分母 |
| kitchen_dispatch_heatmap、reviews_sentiment_summary | 不需要 | 厨房调度/平台评价不是生产排程或质检缺陷数据 |
| revenue_management_report、profit_loss_statement、business_overview_summary、channel_analysis | 不需要 | 当前为餐饮汇总；工厂使用 F 财务科目或工厂销售接口 |
| weekday_weekend_pattern、period_comparison_trend、monthly_anomaly | 可选，暂不准入 | 日期比较思路可复用，但先用通用时间/异常模板，审清餐饮收入过滤再决定 |

## 四、工厂业务库候选

路径 G = `backend/python/smartbi/api/factory_production_gold.py`，E = `smartbi/gold/factory_production_etl.py`，前端客户端 `web-admin/src/api/smartbi/factoryGold.ts`。Gold 返回数据，不自动意味着已提供 ECharts option。`SmartBIAnalysis.vue` 已调用 production-fused；其余调用与完整业务图需逐项接线。

| 工厂指标/需求 | 候选接口/图 | 数据要求；缺少时说明 | 是否需要及理由 | 已有实现与还缺什么 |
|---|---|---|---|---|
| 料/工/设备/其他成本，④ | cost-structure → 环+条形 | 本厂批次成本分项、总成本、日期；价格权限 | 需要：直接回答成本结构 | G/cost-structure；需成本归集完整性、差额和同期间对账 |
| 良品率与生产出成率 | yield-trend → 折线 | 良品/实际量与投入/产出须分别具备；同单位同批次 | 需要，但先澄清口径 | G/yield-trend 注释为 good/actual；E 的报工 yield 又为 output/input，聚合用 AVG。不能将平均 yield 一律称“出成率”；推荐前必须分名、按分子分母聚合 |
| 同产品单位成本，④⑥ | product-cost-compare → 横条 | 产品ID、归集总成本、对应产量和SKU单位；单位转换有依据 | 需要：比较同类产品成本 | G/product-cost-compare；跨盒/袋/kg不能直接排名，缺成本不得标0元 |
| 工序成本/损耗，④ | process-cost → 堆叠条形 | 本厂工序报工、料工机成本、投入/产出、日期 | 需要：追到具体工序 | G/process-cost；需核对工序数据覆盖和WIP关联，不能拼凑未记录流向 |
| 生产/质量/成本联合趋势 | production-fused → 折线/组合 | 本厂受控 domain、同批次日期、有效指标和租户证明 | 需要：复用现成取数链 | G/production-fused + factoryGold.ts + SmartBIAnalysis.vue；需逐域验证可用指标和规则分析 |
| 销售金额/客户/SKU，⑤⑥ | factory-overview/sales → 趋势/排名 | sales_orders/items/customers；排除取消/草稿/财务驳回及软删 | 需要：直接用工厂订单 | smartbi/api/factory_overview.py；已有顶部销售卡，完整期间筛选、渠道/毛利仍需额外字段 |
| 完成率/OEE，生产 | smartbi_compat 生产分析 → 趋势/目标图 | 计划/实际、开停机/运行时长、节拍与质量分母；同单位 | 可选：完整数据后才推荐 | backend/python/smartbi_compat/api/；Web无完整调用证据，OEE不能只由产量估计 |
| 库存账龄/临期，库存 | compat 库存分析 → 桶图/横条 | 本厂批次、可用余额、入库/生产/效期日期、截至日 | 需要：指导处理积压临期 | 同上；需Web入口、冻结/占用/负库存口径和截止日回读 |
| 库存周转，库存 | compat 库存分析 → 趋势 | 同期领用/销售成本、期初期末或平均库存，统一金额/数量口径 | 可选：缺平均库存不报“周转天数” | 同上；需可对账分母，单张采购表不足 |
| 采购价格/供应商集中度，采购 | compat采购分析/通用条形 | 采购/到货明细、供应商、原料、金额/数量/单位与日期 | 需要：真实采购决策 | 同上；需订单与实收分开、价税与单位一致、Web调用 |
| 工厂质检合格率，质量 | compat quality | 本厂质检记录、检验数、合格/不良数、批次 | 需要该指标，但现接口不准入 | 当前 quality 读取餐饮来源；不可把它当工厂质检，先接真实工厂质检数据 |

## 五、推荐与分析的交付约束（目录确认后执行）

1. **先筛资格**：按本厂、日期、实际字段/表、单位、权限、完整分母、最少样本检查；不合格的图进入“还缺数据”，明确列名/表名及用途，不向 AI 发送不合格候选。
2. **只排合格候选**：调用现有 `common.llm_router.call_chain` / `SLOT.CHART`；输入候选 ID 与可核对的指标描述，输出 ID 排序和理由。丢弃或拒绝额外 ID、重复 ID、数字断言。AI 不计算指标、不增加候选、不授权。
3. **AI 不可用**：按规则优先级显示相同合格候选，明确“未经 AI 排序”；系统错误与真实无数据分开。用户改选按工厂保存，不跨租户读偏好。
4. **结论先规则后 AI**：共用指标库生成“做得好的/需要关注/建议”，带实际数字/期间/单位；只有变化事实，不自动推测价格、管理或人员原因。行业常量只能叫“行业参考值”，不能称本厂目标。
5. **可复核验收**：不等额权重、缺列/缺期、零分母、负数、单位、同义汇总、跨年、空厂、价格权限、AI越界及不可用各有阴阳性对照；结论/KPI/明细/导出同源。真实UI选图、改期间、改选持久化及SQL/API回读分别留证。

## 已确认的范围

Steve 于 2026-10-25 确认：**按本文“需要”建立候选库，“可选”仅在满足额外条件时推荐，“不需要”排除**。范围包含财务、成本/产量、库存、采购和真实质检；没有数据的指标继续列出缺口。

## 2026-10-25 后续补齐状态

本目录前面的“缺”是初始盘点，不表示以下能力仍未开发。本批补齐现金流三活动瀑布/趋势、截至日应收账龄、毛利/净利/费用预算、品类/SKU/渠道加权毛利、客户销售及逐单对账SKU金额、采购加权价格、完整工序成本/在制品核对损耗/联合趋势、质量时间趋势、已关账库存快照、条件式OEE/周转/偏离观察，以及选图服务端保存和逐图三格式导出。实现、验证、部署、真实数据验收分别记在 `../dispatch/archive/2026-10-25-smartbi-catalog-closure.md` 和本机交付回执。

仍取决于来源的项目：核心订单暂无可对账的SKU折后行小计及可信渠道，成本来源未完整归集设备/其他成本与WIP流向，设备开停机/理想节拍尚无自动业务来源，部门收入成本缺可靠归属；符合字段的上传表可推荐，不猜分摊。F006当前销售、确认收货、真实质检、现金凭证、应收与关账库存来源为空，保留缺口，不以样表替代真实业务验收。大规模数据继续执行显式上限并提示缩小范围，不静默截断，也未实现自动下钻或无限分页。
