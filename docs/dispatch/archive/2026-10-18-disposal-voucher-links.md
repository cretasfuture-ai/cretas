# P32 报废成本修订凭证关联

Base 2c5129a834b3f5317df1be43b17b47129d562fbf；worktree C:/Users/Steve/cretas-analysis-provenance-20261015，branch codex/trace-analysis-vision-daily-review-20261015，Owner root。用户授权打开4.71元修订凭证并检查后继续接通；只本地、不push/合并/部署。

Scope：web-admin/src/api/voucher.ts、web-admin/src/views/finance/voucher/VoucherDetail.vue、其既有__tests__/VoucherDetail.integration.spec.ts；本归档和ACTIVE。复用GET vouchers/by-business及详情接口、现有红冲链接、报废审批详情；不改后端/会计记录/期间/数据库/其他页面。原4.21凭证与红字净0，新凭证4.71。

完成条件：新厂账号可从修订页直达同厂原凭证、其红字和报废审批；中文来源和报废类型；请求过期/切租户/失败不显示错误关联；目标组件测试、Web构建、真实本地UI点击与API/数据库只读验证。P31财务/JPA证据复用，不重复写业务或重跑无变化Java测试。

## 结果

本地完成，review待用户体验。正常新厂管理员会话读取并点击：V-2026-0189→原V-2026-0187→红V-2026-0188→原；修订页直达红字以及报废审批5，再返回修订页。修订凭证类型显示“存货报废损失”、来源显示“报废成本修订”，不再把此次报废显示为报销或英文来源。关联查询失败明确报错并提供重读，切换工厂/用户/路由/缓存失活后忽略旧响应；原/红归属、状态和双向ID均验证后才显示入口。不扫描整份凭证台账，不重建接口或财务模型。

16项VoucherDetail真实挂载测试通过（原11项+本批5项），覆盖三个入口、异厂响应、红字不匹配、切厂/切凭证陈旧响应、失败后重试。命令npm test -- src/views/finance/voucher/__tests__/VoucherDetail.integration.spec.ts，退出0。npm run build -- --outDir C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p32/dist通过，退出0；这是Vite完整生产构建，不声称全仓vue-tsc或全套测试通过。P31已通过294项Java/JPA和财务并发证据原样复用，本批没有Java变化。

真实HTTP和PostgreSQL只读回读：原凭证REVERSED、红字POSTED、新凭证POSTED，同厂且关联ID一致。原借6711/贷1405各4.21，红字借1405/贷6711各4.21，逐科目净0；新借6711/贷1405各4.71，三张合计最终费用4.71、库存贷方4.71。报废审批APPROVED、actualLoss4.71、approvedBy12；利润337.17未变，P30源库完整快照不变。不发生业务写入、真实付款、反结账、重算或部署。

私有证据C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p32：voucher-tests.log、web-build.log、accounting-readback.json、ui.json、五张ui截图及最终acceptance.json/源码哈希。第一次UI脚本只等标签文字导致过早读取金额，保存工具失败事实；改为等待当前详情标题和金额可见后，全部实际点击通过，无产品pageerror。浏览器为直接Playwright MCP，新厂测试页停在31768的修订凭证；不把它称为应用内原31764标签已跳转。

本批只接通当前修订→最初原凭证/对应红字/报废审批的查看入口，不声称新增完整多次修订历史浏览、修改凭证作废策略或完成生产验收。保持独立分支，无push/PR/合并/部署。
