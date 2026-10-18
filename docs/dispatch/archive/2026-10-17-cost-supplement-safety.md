# P21 既有成本补录的上下文与失败重试

任务P21-COST-SUPPLEMENT-SAFETY-20261017，Owner root，Base 154b67eb4b6f480d5c70c9221a3547c2affd9cef。继续原独立分支，仅本地，不push/PR/合并/部署。P20验收和冻结数据沿用。

检查发现既有SupplementalCostsDialog的保存和重算分两次请求：保存返回后重算读取实时props，可能切换批次后重算另一计划；没有提交中的单次执行保护；已保存但重算失败后仍可编辑金额，重试只重算，编辑不会保存。复用既有PUT costs与recalculate-settled-costs，不新增另一套计算。

scope仅既有弹窗、批次详情挂载、目标Web测试和本档案/ACTIVE。完成条件：上下文明确，保存期间防重复；工厂/计划/用户或权限变化不延续旧请求、不产生错误成功提示；重算失败清楚说明已保存金额并安全重试；真实本地页面可打开/取消且不改原业务基线；目标Web测试与构建通过。

本轮不修改Java、数据库或指标公式，不以旧总成本填空；已出货样本14-17不通过写入人为制造重算失败。接口分步事务与已出货成本重估的会计影响不由界面防护替代，后续需要单独审查后再开展真实金额变更。

实现完成：弹窗显示当前批次；请求固定工厂/计划及用户上下文，单次提交保护，保存期间禁止输入和关闭。切换工厂、批次、身份、权限或卸载后，旧响应不继续发起另一计划重算，不发布成功提示。已经发出的保存请求仍可能在原计划完成，这不是服务端取消或事务回滚。

重算失败保留“费用已保存、核算尚未完成”提示，原金额重试不重复PUT；修改金额后按钮恢复“保存并重新核算”，新金额确实重新保存。稍后核算退出时通知原详情刷新，避免下次用旧默认值覆盖已保存费用；仅重算成功才发saved。父页面挂载沿用现有组件，绑定批次/身份，并在写权限或身份变化时关闭；权限撤销后恢复不会自动重新打开旧弹窗。

先红后绿：原实现3项失败分别复现重复PUT、保存后重算到另一工厂计划、重算失败后修改金额被丢弃。修复后增加旧用户/卸载/关闭、重算期间切换身份、失败后稍后退出刷新与真实Element表单输入禁用检查；实际挂载批次详情验证上下文传递、权限撤销和恢复。最终17文件152项目标测试通过，Vite构建通过，退出码0；源码与测试4文件SHA256冻结。本轮无Java/Entity/Repository改动，不重跑已通过P19 Java门禁；未声称全仓类型检查或CI已通过。

真实本地浏览器用新厂user2打开17批现有补录窗口，当前批次和12元设备/3元其他费用正确、中文提示可见，取消返回原成本卡128.33元、6.42元/盒。真实服务器未故障注入或提交费用，失败、并发和身份切换控制由实际组件挂载测试的模拟响应验证。原cost-breakdown HTTP逐字段与P19一致；PG回读生产、库存版本、出货、应收、质检逐值不变，旧指标快照保留。浏览器错误0。最终停在17批成本明细，另一平台建厂标签未动。

证据私有目录：`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p21/`，含web-red.log、web-target.log、web-final.log、web-build.log、source-freeze.json、api-cost-sources.json、after-ui.json、api-pg.log、supplement-dialog与final-page的txt/png、browser-errors.json、acceptance.json。目标测试命令沿用P20：`npm test -- src/views/production/batches/__tests__ src/views/indicator-center/__tests__ src/api/__tests__/productProductionMetrics.spec.ts src/api/__tests__/indicatorHistory.spec.ts`；构建`npm run build`。dispatch-active和diff检查通过。

后续优先事项：核对既有已结单成本更正对已出货成本、财务期间和经营指标快照的处理。现有recalculateSettledPlanCosts会更新成品批次单位成本，当前窄检查尚不能证明历史销售成本/凭证与该变化同步；保存和重算的分步失败也仍是后端一致性边界。不得把本轮界面防护或只读回读称为该业务金额变更链已验收。继续复用成本归集、结单重算与已有会计能力，不重复建成本模块。

交付为本地独立分支提交，无push、PR、合并、部署、迁移、生产访问或原业务金额写入。
