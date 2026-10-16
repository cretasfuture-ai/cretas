# 客户收款快捷页行级权限 UI 验收

- Task `PAYMENT-CUSTOMER-SCOPE-20261016`、测试子项 `PAYMENT-SCOPE-PG-20261016`；Owner root；Base `d126ba38ed1816f55d59b2e3fdb87dcba432e7ad`。
- 实现、本地目标验证和真实 UI 完成；最终候选 SHA、CI 与手动合并状态以本 PR 的 GitHub 回执为准。
- 仅复用本地隔离测试环境，生产访问、部署、写入均为零。没有新增迁移，没有重新验收已通过的报价和开票全流程。

## 实现

收款行级归属继承同工厂来源销售订单的创建人，不能使用登记收款的 `recordedBy` 判定归属。客户快捷查询关联来源订单，在 SQL 中限制工厂、客户、来源订单创建人范围、收款及来源订单软删除后，再分页和计数。受限范围看不到失效或缺少来源订单的收款；ALL 保持既有查询。

详情、关联订单入口和确认/驳回守卫使用相同来源归属；尚未接入范围查询的收款主列表继续拒绝。既有 finance/sales 功能权限和模块限制保留，没有给销售角色新增财务权限。客户页现有金额、日期、方式字段已经正确，不修改前端业务组件。

登记入口在锁定同工厂销售订单后、任何去重或写入前检查来源归属，覆盖 REST 与 AI 工具。复核发现 SSE 线程只有数值用户 ID，故在提交异步任务前捕获已认证 typed actor，工作线程使用普通 ThreadLocal 作用域绑定并在异常/正常退出时恢复；不继承到子线程。工厂用户才解析工厂范围，平台账号仅显式 bypass 角色放行，其余拒绝，防止同 ID 平台/工厂账号混用。只有数值身份而缺 typed actor 时拒绝；真正无请求、无用户执行身份的内部作业保留既有行为。

## 真实 UI 与持久化

复用上一切片 A–E 五个账号的已完成销售订单，没有重建订单或重复发货。管理员通过现有收款 UI 为每个来源订单登记一笔 12.00 模拟收款，五笔均为 PENDING、登记人同一账号，来源订单创建人各不相同。

| 角色页实际保存范围 | A 的客户收款页可见来源 | 数量 |
| --- | --- | --- |
| SELF | A | 1 |
| SELF_AND_BELOW | A、直属下属 B、孙级下属 C | 3 |
| DEPT_AND_BELOW | A、同部门 D | 2 |
| ALL | A、B、C、D、外部门 E | 5 |

每组核对真实响应 ID/总数、实际挂载表格的编号、金额、日期及范围外编号不出现，并保存截图。另一授权客户 200、总数 0，页面显示“该客户暂无收款记录”。UI 登录在一次本地服务重启期间失败，服务就绪后仅继续未完成场景。

实际会话 API 补充控制（不冒充 UI 点击）：本人收款详情/按订单查询 200；范围外详情/按订单查询、跨工厂、未支持的主列表、范围外登记/确认/驳回均 403。功能权限拒绝与行级守卫独立证明分开：真实 PG 控制器测试另断言越权时 service/repository 零交互；服务测试断言写前拒绝零副作用。

最终五笔仍 PENDING、未确认，来源订单、库存、发票、报价全行读回与上一切片一致。恢复 sales_manager ALL（version 16）。准备夹具时临时赋予测试管理员本地财务权限，已通过 Canvas UI 清除覆盖，逻辑配置恢复原值；全局权限指纹未变。既有对照工厂 505 个表/工厂行指纹未变。

## 必要验证

- `CustomerPaymentScopeRepositoryQueryValidationTest`：真实 JPA 启动和持久化，验证登记人不同于来源创建人、空登记人、异厂来源/收款、其他客户、软删除、无来源、空范围、分页顺序/计数与 ALL 兼容，通过。
- `CustomerPaymentDataScopeContractTest`：真实控制器和 DataScopeAspect、typed actor、四种范围及分页上限，4 项通过。
- `RoleDataScopePostgresContractTest.paymentRoutesInheritSourceOrderOwnerAndRejectBeforeBusinessCalls`：真实原生 PG、实际路由/控制器/AOP，授权访问与拒绝零业务调用，通过。最初夹具缺 legacy userId attribute 已补齐，未降低断言。
- `PaymentRecordWriteScopeContractTest`：有/无 REST 标记的隐藏来源拒绝、授权 PENDING 登记、无 Servlet 的 typed 用户异步正反对照，共 5 项通过。
- `PaymentTrustedUserDataScopeContractTest`：真实 resolver 的 SELF/ALL、空归属、未知用户、异厂、同 ID 平台账号拒绝/显式 bypass、作用域恢复和子线程隔离，3 项通过。
- `PaymentSseActorPropagationContractTest`：实际 executeStream 工作线程传递工厂、平台 auditor、平台 super_admin 三种 typed 身份，并证明线程池任务结束清理，3 项通过。
- 既有 JWT typed actor、可信用户作用域及收款控制器/跨租户目标测试通过。源码修改后的目标测试分次收取，未重复本地全量。
- 前端 `CustomerShortcutScope.spec.ts`、`PaymentsTabFields.spec.ts` 共 8 项通过，覆盖真实字段、金额遮罩、403 和授权空结果。
- 新 Contract/JPA 类匹配 `java-gate-pr.yml` 实际选择器；PG 增量进入已有原生 PG 测试类。源码/测试冻结后只执行一次最终候选必要全量 CI。
- 权限终审针对已发现的异步入口和 typed namespace 问题修复后返回 SCOPED PASS，无该范围剩余 blocker；差异检查通过。

私有证据根目录 `C:/Users/Steve/Documents/assembly-integration-assessment-20261015`：`payment-scope-target-tests.log` 保留初始夹具失败；修复后 `payment-scope-write-route-final.log`、`payment-typed-async-final.log`、`payment-sse-typed-transport.log` 和 `payment-web-target-tests.log`；`target-ui/payment-scope-ui-receipt.json`、`payment-scope-db-final.json`、`payment-scope-control-comparison.json`、`payment-scope-*.png`。凭证、原始回执和截图不提交。

## 边界

本批验收客户收款快捷页行级权限。没有实际资金核销、确认收款、银行接入、附件上传、移动端或生产部署；未开放受限账号收款主列表。AI SSE 身份传递和写前拒绝由真实方法/工作线程测试证明，未把它表述为一次真实 LLM 对话验收。
