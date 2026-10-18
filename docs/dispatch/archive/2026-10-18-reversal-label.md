# P35 红字凭证中文类型显示

Base 22a2ddeaa15c3d26c1ccd404ea2789aba5feaa1a；Owner root；worktree
C:/Users/Steve/cretas-analysis-provenance-20261015；分支 codex/trace-analysis-vision-daily-review-20261015。
用户授权本地打开红字凭证、检查后继续并直接修复发现的问题；不push、合并、部署或操作生产。

现场读取发现：报废红字凭证沿用EXPENSE类型，页面通用映射显示“报销”，与真实报废冲销用途不符。
只复用已有VOUCHER_REVERSAL来源判断和中文来源标签，改正类型展示。
Scope仅VoucherDetail.vue、本归档及ACTIVE；无后端、API、模型、分录、权限或数据库变更。

验收：复用现有34项前端测试与构建；新厂正常账号真实原/红互跳，核对5.71元反向分录、
原凭证已冲销与红字已过账、原审批和有效4.71元修订保留，记录当前利润表与指标读回。
完整日志、截图和业务只读回执保留私有Documents/factory-data-p35，P34昂贵后端证据原样保留。

## 完成结果

- 产品代码只增加一条展示条件：现有isVoucherReversal判断为真时，复用sourceBusinessTypeLabel中文标签。
  后端VoucherType与分录完全不改；原始报废和有效修订仍显示“存货报废损失”。
- 5文件34项既有Web目标测试通过；最终Vite构建成功（90秒）。无新增测试脚手架。
  第一条npm转发构建参数被本机解析为root路径，失败日志保留；按已核实build脚本直接调用Vite成功。
- 正常新厂账号真实点击0296→0291→报废审批→0297→审批→0296；最终停在红字凭证。
  页面“类型：红字冲销凭证”，不再显示报销。无页面/HTTP异常、无被阻止的业务写请求。
- 0296已过账，借1405库存5.71、贷6711存货报废损失5.71；与已冲销的0291日期相同、双向ID一致，
  反向分录逐科目相抵。报废审批0.5盒/损失4.71元/原审批人和时间均保留。
- 继续读回：13条报废凭证历史中只有0297为有效损失凭证；利润表及锁账快照720收入、378.12成本、
  4.71费用、337.17净利润一致；完整生产成本指标561.32元。
- 与P34已接受快照对照，24张库存/审批/财务业务表精确不变；指标定义不变、旧指标历史不变。
  指标查询复用现有缓存与计算逻辑，缓存过期时刷新计算时间并新增同期间561.32元历史，
  已逐条核验来源。不能将指标GET的计算副作用误报为26表完全零变化。
  初次过严快照断言及详细差异保留；原P31源库26表仍完全不变。
- 纯UI显示修复，复用P34的351项后端证据；本轮不重跑未变的Java代码，不声称新增后端验收。

证据根目录 C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p35：
web-tests.log、web-build.log、web-build-argument-failure.log、ui-final.json、v0296-before/final.png、
readback-*.json、readback-snapshot-difference.json、最终scope哈希及本地提交回执。
验收仅覆盖当前新厂本地数据；无SQL直接业务写入、无业务mutation接口、无推送/合并/部署或生产操作。
P34完整归档仍在2026-10-18-disposal-lifecycle-guard.md；本批没有重做已有报废或财务功能。
