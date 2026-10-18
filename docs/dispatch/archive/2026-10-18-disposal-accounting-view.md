# P33 报废审批与凭证关联查看

Base 58ace9dd8307e0259ed32dcba957fe1ad9b27c06；独立worktree C:/Users/Steve/cretas-analysis-provenance-20261015，branch codex/trace-analysis-vision-daily-review-20261015，Owner root。本批用户要求查看报废审批0.5盒/4.71/审批人及修订凭证对应关系，并检查后继续；发现审批详情缺少反向查看凭证入口，补齐只读入口。P32 58ace9dd83已完成，证据原样保留，不表示合并。

Scope：DisposalController、DisposalRecordService、DisposalCostAccountingService及对应既有Controller/Service测试；DisposalRecordRepositoryQueryValidationTest增加真实EM查询验证；web-admin质量disposals/list.vue、既有list.integration.test.ts、复用api/voucher.ts类型；必要独立小组件及其集成测试；ACTIVE和本归档。无Entity/Repository/迁移、凭证写入、审批或成本口径变更。

复用现有按factory+原业务ID/修订前缀查询及真实原/红字关联，不扫全厂台账或猜最新修订号。新增只读接口同时要求质量模块准入和finance读权限，校验记录归属及每张红字关联；前端复用凭证详情，明确无凭证/错误/加载和重试，失活/换厂拒绝陈旧响应。

完成条件：相关Java测试与真实JPA查询通过、前端目标测试/构建、仅重载P31本地副本、不改业务数据；正常新厂UI打开审批并往返原/红/修订凭证，读回0.5盒/4.71/审批人12和利润337.17不变。不得push、合并、部署、写生产或真实付款。P31/P32已过无变化验证复用。

## 本地验收结果

2026-10-18完成，review待用户体验，仍为独立分支。复用P31数据库p31_finance_closeout_20261018和Web31768，只重载专用Java10068（新PID71600），原Java10064/PID46300、10066/PID50512及原体验Web31764不动。新增GET /api/mobile/{factoryId}/disposal-records/{id}/vouchers同时受quality_inspection模块和finance:read或read_write保护；先验证记录归属，再复用成本更正原有factory/记录精确ID/修订前缀查询，补真实红字关联。

修复终审发现：REVERSED原凭证缺少红字ID时409；存在红字ID则要求原状态REVERSED、红字POSTED，且检查工厂/未删除/来源类型/来源ID/原始ID。缺失链不能静默显示为已完整冲销。前端复用已有Voucher类型与凭证详情，按财务权限显示只读小组件，列出原始/红字/各次修订的凭证号、中文状态和金额；无记录与失败分别展示，提供重读，工厂/记录切换、卸载或缓存失活时拒绝旧响应。未新增会计写流程或调整历史金额。

### 通过证据

- Java首次29类299项，仅新增JPA测试的跨厂样本违反既有全局(sourceBusinessType,sourceBusinessId)唯一约束，298项通过，1项fixture错误。改为异厂5#r3作为同修订前缀反例，保留同厂5/5#r2阳性和50#r2阴性；不删约束或断言。另补缺失红字/未过账红字两个行为反例后，最终4相关类56项全绿，其中真实EM/JPA2项。其他25类245项直接复用，最新29份XML去重301项全绿；不是一次全量301。完整首次失败日志、XML和最终目标命令/日志/源码freeze均保存。
- 前端最终5文件32项全绿，含既有报废页面、余量/读回、凭证详情及新关联组件5项；覆盖三类凭证、异厂、错误红字、切厂旧响应、失败重试。最终npm run build -- --outDir专用私有dist成功；此前条件提示修正前的构建单独保留，不冒充最终构建。不声称全仓vue-tsc或全套测试已跑。
- 正常新厂管理员及独立财务账号12读取本厂审批5，返回原V-2026-0187(REVERSED,4.21)、红V-2026-0188(POSTED,4.21)、修订V-2026-0189(POSTED,4.71)。每张都同厂；原/红双向ID正确。借6711/贷1405三张合计费用4.71、库存贷4.71；审批数量0.50盒、actualLoss4.71、approvedBy12、APPROVED。
- 新厂管理员使用本厂路径读取真实异厂报废ID返回404；现有p16_qc_review（quality_manager，质量准入但无财务权限）读取本厂审批返回403且明确finance缺权。两项请求前后26表快照完全相同；没有临时放宽角色或新增账号。P30源库26表exact unchanged。
- 正常新厂UI从审批详情分别点击原、红、修订，再从修订页既有入口回到审批；均显示准确金额和关系，页面error=0，finance/disposal业务写请求=0。最终停在http://127.0.0.1:31768/quality/disposals?disposalId=5，弹窗同时显示0.5盒/4.71/审批人p30_factory_finance及三张凭证按钮。该浏览器为直接Playwright MCP，不声称应用内原31764标签已跳转。
- 最后利润表费用4.71/净利337.17，期间LOCKED、对账PASS，原财务数据未被本批修改。业务SQL DML=0；没有反结账、重算、审批、库存扣减、真实付款或生产写入。独立风险复核确认新增只读权限/租户边界无剩余P0/P1；最终UI由root实际点击取证。

私有证据C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p33：api.json、ui.json、四张ui截图、java-first-fixture-failure.log、first-run-reports、java-target-green.log、web-tests.log、web-build.log、product-final-freeze.json、format-only-delta.json、最终acceptance.json。P31原29份XML在p31-baseline-reports保留。新增行CRLF→LF格式修正有规范化等价证据；未将凭据写入tracked内容，diff-check/台账检查通过。

本地提交后不push、不开PR、不合并、不部署。多次修订历史按实际来源查询展示，不猜“最新”序号；本批不声称另行执行多轮成本更正或财务写入验收，P31相关业务证据直接复用。
