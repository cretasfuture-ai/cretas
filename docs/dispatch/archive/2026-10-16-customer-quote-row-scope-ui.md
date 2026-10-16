# 客户报价快捷页行级权限 UI 验收

- Task：`QUOTE-CUSTOMER-SCOPE-20261016`；Owner root；Base `b33f3ee324975ad6adb141b55bb3a3c76ee4e199`。
- 仅本地隔离测试环境；沿用上一轮账号、组织、客户和产品。生产未访问、未部署、未写入。
- 实现及本地验收完成。最终 CI、候选 SHA 和手动合并状态以本 PR 的 GitHub 回执为准；不使用 auto merge。

## 行为与边界

报价的创建人取 `submitted_by_user_id`，不是可指派的 `quoted_by_user_id`。客户报价查询先按工厂、客户、提交人范围和未删除条件过滤，再分页及计数。ALL 保持原查询，包括历史提交人为空的记录；受限范围不包含提交人为空的记录。

实际报价详情和引用报价详情按同一提交人范围校验，拒绝发生在业务查询/写入之前。尚未完成范围筛选的报价主列表、有效报价及引用列表对受限账号明确拒绝；开票、收款快捷页保留原有拒绝行为，未在本批开放。

客户报价页改读实际 DTO 字段 `quoteNo`、`productTypeId`、`unitPrice` 和 `unit`，保留价格遮罩；删除后端无对应字段的总额列。真实录价时发现空有效期发送空字符串被拒，页面改发 `null`，空有效期提交及读回通过。

## 真实 UI 结果

五个既有账号 A–E 分别通过报价页面，为同一客户各创建一条报价。A 的录价人指定 E，其他四条的录价人指定 A，证明提交人和录价人没有混用。A 的报价通过页面录入 62/个、空有效期，数据库读回一致。

| 通过角色页保存的范围 | A 在真实客户报价页可见 | 数量 |
| --- | --- | --- |
| SELF | A | 1 |
| SELF_AND_BELOW | A、直属下属 B、孙级下属 C | 3 |
| DEPT_AND_BELOW | A、同部门 D | 2 |
| ALL | A、B、C、D、外部门 E | 5 |

每组同时断言实际响应 ID/计数、真实挂载表格单号、隐藏记录不出现、62/个显示，并留截图。另一个已授权客户的真实空结果显示“该客户暂无报价单”，区别于拒绝。

补充真实会话 API 控制（不冒充 UI 操作）：本人报价/引用详情 200；范围外两种详情、跨工厂快捷查询、未支持范围的主列表/有效报价、开票/收款快捷查询以及范围外改价均 403。数据库确认仅 A 的报价有预期价格，其余四条仍待录价、价格为空，拒绝请求未改变记录。

最终恢复 ALL（version 8）。对照既有工厂的 505 个表/工厂行指纹完全不变，全局功能权限指纹不变。截图、原始回执和完整行读回留在私有本机证据目录，仓库不包含登录信息、令牌或凭证。

## 必要验证与证据复用

- `CustomerQuoteScopeRepositoryQueryValidationTest`：真实 Hibernate/JPA 启动及持久化查询通过；覆盖同客户不同提交人、指派给本人但非本人创建、其他工厂/客户、软删除、未知提交人、空范围、分页顺序/总数、ALL 兼容。
- `CustomerQuoteDataScopeContractTest`：实际 Controller + DataScopeAspect 的四种范围、typed actor、提交人列与分页上限通过。
- `RoleDataScopePostgresContractTest`：复用原生 PostgreSQL helper；新报价路由和既有拒绝/ALL 对照通过。新增引用详情矩阵只定向复验该方法：OWN 200，HIDDEN/FOREIGN/DELETED/UNKNOWN 403，拒绝时业务 service/repository 零调用。
- 相关既有 Controller、DataScopeAspect、客户子单据、记录权限及范围覆盖测试的通过证据直接复用。开发期间两项测试夹具问题（审计时间覆盖排序键、泛型 proxy 被推导成 varargs 数组）已修复，未删除行为断言。
- 前端 `CustomerShortcutScope.spec.ts` 与 `QuotesTabFields.spec.ts` 合计 8 项通过，含真实字段、价格遮罩、授权空结果与 403 区分。空有效期修复另以真实页面提交及数据库读回验证。
- 窄权限风险复核 SCOPED PASS。保留文件原有 CRLF；`git -c core.whitespace=cr-at-eol diff --check` 通过。
- 新 JPA/Contract 类均命中 `java-gate-pr.yml` 的实际选择器；PG 增量加入已有真实 PG 类，沿用同一原生 PG CI job。最终源码/测试冻结后，只运行一次最终候选必要全量 CI，不重复本地全量。

私有证据根：`C:/Users/Steve/Documents/assembly-integration-assessment-20261015`；重点为 `quote-scope-target-tests.log`（保留初次夹具失败）、`quote-scope-focused-retest.log`、`quote-reference-detail-final.log`、`quote-web-target-tests.log`、`target-ui/quote-scope-ui-receipt.json`、`target-ui/quote-scope-db-final.json`、`target-ui/quote-scope-control-comparison.json` 和 `target-ui/quote-scope-*.png`。

## 未覆盖

本批不验开票/收款快捷页开放、报价审批到销售下单、受限账号主列表/有效报价查询开放、移动端或生产部署；不把这些能力算作完成。未新增迁移、未改已有工厂配置和业务数据。
