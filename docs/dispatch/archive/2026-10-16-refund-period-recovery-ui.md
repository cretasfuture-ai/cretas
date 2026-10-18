# 锁账退款撤销页面恢复闭环

P12-REFUND-RECOVERY-20261016；Base 61a45c5f0c6442914bf686fde5669819de8e21aa；root协调及运行时/DB/浏览器/台账唯一Owner；复用原worktree与codex/trace-analysis-vision-daily-review-20261015。用户授权第三方检查并按优先级接通现有功能、daily_factory_a本地验收、实际问题直接修；禁止push/PR/merge/deploy及生产/真实转账。

已核对：P11业务HTTP/PG锁账及回滚正确。P0页面断点：退款撤销失败显示阻塞弹框/路径文字，没有直接处理/返回原退款操作；会计期间页加载/反结账缺身份与请求时序保护、缺重复提交保护，凭证数量请求参数错层/失败假0。优先复用现有期间页面和接口，保留原退款审批与锁账规则，不新增会计引擎。

root scope：本档案/ACTIVE、web-admin/src/utils/accountingPeriodRecovery.ts及测试、api/inventory.ts与api/__tests__/inventory.returnRefund.spec.ts、views/sales/returns/detail.vue与refundAndLineage.integration.spec.ts、components/layout/ReturnBanner.vue与其测试、menuConfig.ts及其测试。处理期间错误由退款页承接、严格限定本地期间路由及原单回跳，复用既有ReturnBanner；工厂/actor/原单变更清理恢复入口；不自动反结账或自动审批。

P12-PERIOD-UI；Owner reversal_ui；Base同上；scope仅web-admin/src/views/finance/accounting-period/index.vue与该目录新增/现有组件测试（不改monthCloseHelpers业务规则）。加请求/身份/激活保护、反结账重复提交保护、中文化、正确凭证参数与明确失败；复用root的安全回跳helper，成功后可返回原退款。不得改root文件、依赖、后端、运行时/DB/浏览器/Git/dispatch，不得启动Maven。目标验证npm test -- src/views/finance/accounting-period（root统一最终构建）。回传实际文件、命令/结果、风险及下一步；代码冻结后由root整链UI+HTTP/PG本地验收。

## 实际收口

- 退款页复用既有 API 的 `actionHint` 与会计期间路由，锁账失败保留中文说明和“处理锁账并返回本单”。不自动反结账、不自动审批，也不改全局错误处理。
- 回跳只接收内部精确退款详情，强制唯一 refundRequestId 与 ledgerId，并绑定 factory/actor。畸形或重复恢复标记拒绝，不能退化成普通深链。复用 ReturnBanner，其他既有通用返回行为保留。
- 复用期间页，补请求时序、工厂/账号/页面激活保护，身份变更关闭所有旧弹窗；审批与驳回确认互斥、防重复提交。期间读取失败明确显示错误，不保留旧数据。
- 后端凭证列表没有承诺按期间过滤，移除不可靠的“本月 N 笔凭证”数量与请求，改为提示核对已有会计凭证页面。会计期间入口同时挂载普通财务菜单和财务主管专用菜单。
- reversal_ui 最终冻结：2 文件、23 测试通过。reversal_risk 只读独立审查 SCOPED PASS，无剩余代码阻断。没有 backend/Entity/Repository/迁移/依赖修改，复用 P10/P11 真后端与既有目标测试证据，不重复 Maven。

## daily_factory_a 本地实操与只读核对

复用受控克隆 runtime（Java10062、Web31762、PG55447 / p10_refund_http_20261016），原环境及源库不变。使用既有测试时钟代理模拟 2026-10-31 退款、2026-11-01 撤销；主机时间与认证时钟不变。业务仅普通 HTTP / 页面操作，SQL 为只读验证，真实付款与生产写入均为 0。

本轮新退款 RF-20261031-8fe9a11c，金额15元；复用退货 RT-SAL-20261016-0013。daily_finance_a 发起撤销，daily_factory_a 在真实页面独立复核：

1. 原期间已结账且无调整窗口。页面确认撤销触发真实 HTTP400；请求仍待审批，原退款/原凭证状态不变，16 张业务表精确相等，余额540元、净资金口径0元不变。
2. 点击新增恢复按钮进入2026-10期间，填写明确本地原因并反结账；原申请与撤销流水 query 精确保留，返回后仍待审批，无自动资金动作。
3. 再次独立确认撤销：申请 CANCELLED，撤销流水 APPROVED；余额540→525元、净资金口径0→15元；原凭证 V-2026-0044 REVERSED，红字凭证 V-2026-0045 POSTED。
4. 红字借银行存款15元、贷应收账款15元，与原分录逐科目净额为0；实际页面双向打开原/红字凭证。凭证业务日期保留2026-10-31，反结账及撤销批准审计日期2026-11-01，未改写原付款与原批准审计。
5. 原始流水、分录和历史审批不可变；此前演练数据保留，仅本轮期间审计字段与客户ORM元信息按预期变化；新增1退款申请、2条应收流水、2凭证、4分录；源库16表精确不变。

私有证据：`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p10/recovery-ui/`，包含 baseline / pending / ui-locked-rollback / business-final JSON 与 browser 截图/可访问树。业务核对命令：私有 `p12_validate.py blocked`、`p12_validate.py final`，均退出0；凭据不进入 Git。

## 前端验证与边界

冻结目标7文件98测试通过：accountingPeriodRecovery、inventory.returnRefund、ReturnBanner、refundAndLineage、accounting-period、VoucherDetail；财务菜单目标6通过、64不在本轮选择器内。日志在私有 `factory-data-p12-accepted-target.log`、`factory-data-p12-finance-final.log`。

菜单全文件实际69通过、1失败：存量质量分组断言期待3组，Base和本次源码均为相同4组；两版质量对象文本 SHA256 `a79485f250d5b53054aafd09dde4752ffa02faae10fb8d09461b90eab5aa79d0`，本批未改质量分组、未放宽断言。一次 npm 参数转发未生效触发该全文件运行，直接 Vitest CLI 精确重跑财务6项通过。不能宣称全套测试全绿。

`npm run build:check`（vue-tsc -b && vite build）退出0，Vite2分15秒；菜单最后补项后另跑冻结源码 `node node_modules/vue-tsc/bin/vue-tsc.js -b` 退出0。最终产物菜单含两个会计期间入口；source-frozen.json 的全部前端SHA256回读一致。日志为私有 `factory-data-p12-accepted-build.log`、`factory-data-p12-frozen-typecheck.log`；Git diff --check 与 check_dispatch_active.py 通过。

仅本地提交本批14文件，保持独立分支与原本地运行时。浏览器以daily_factory_a停在本轮15元撤销申请及精确流水；用户体验未代替用户本人验收。未 push/PR/merge/deploy、无生产或真实付款。结构化回执与最终commit/干净状态回读保存在私有 recovery-ui/acceptance-receipt.json，不将凭据、原图或业务明细入Git。
