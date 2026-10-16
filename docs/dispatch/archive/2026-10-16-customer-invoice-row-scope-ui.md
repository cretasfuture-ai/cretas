# 客户开票快捷页行级权限 UI 验收

- Task `INVOICE-CUSTOMER-SCOPE-20261016`；Owner root；Base `459a5e13cc3c53b1e9299b52388a9aadf44e4e5d`。
- 实现、本地相关验证和真实 UI 完成；最终候选 CI、SHA、手动合并状态以本 PR 的 GitHub 回执为准。
- 仅本地隔离测试环境，复用上一轮五个账号、组织、客户、产品、库存和 helper。生产访问/部署/写入均为零。

## 实现

自动开票记录的 `requestedBy` 允许为空，行级归属继承同工厂来源销售订单的 `createdBy`。客户快捷查询在数据库关联来源订单，限制工厂、客户、来源订单创建人范围及软删除状态后，再分页和计数；没有有效来源订单的记录对受限范围不放行。ALL 保持原有查询。

发票详情及审核、驳回、开具入口先按来源订单范围校验。既有 `by-sales-order` 路由优先于宽匹配，保留其来源订单守卫；尚未接入范围筛选的发票主列表继续明确拒绝。收款快捷页不在本批开放。

两个已停用的手工申请入口在身份/租户/范围有效后仍到达原控制器，返回原有 410 指引，不创建记录。客户页开票日期改读真实 `issuedAt`；待审核、尚未开具时显示“—”，金额遮罩保持。

## 真实 UI 与数据库结果

既有 A–E 五个账号各通过销售页面，为同一客户建立一笔 1 个成品的订单。由测试工厂管理员通过现有订单确认和自动 OA 判定、全量发货单及仓库“确认并扣库存”流程完成五笔模拟发货。没有直接插入发票或改写订单状态。

自动待办最终五笔均 DONE，生成五条 REQUESTED 开票记录，申请人均为空。最初 UI 在自动处理完成前读到 3 条；数据库确认全部完成后刷新即为 5 条，未补写记录。每条不含税 54.87、税额 7.13、价税合计 62.00，页面与数据库一致。

| 角色页实际保存的范围 | A 在真实客户开票页可见的来源订单 | 数量 |
| --- | --- | --- |
| SELF | A | 1 |
| SELF_AND_BELOW | A、直属下属 B、孙级下属 C | 3 |
| DEPT_AND_BELOW | A、同部门 D | 2 |
| ALL | A、B、C、D、外部门 E | 5 |

每组核对真实响应 ID/总数、挂载表格编号、范围外编号不出现及金额，并保存截图。另一个已授权客户显示真实“暂无开票记录”空结果。

实际会话 API 补充控制（不冒充 UI 点击）：本人发票详情/来源订单发票入口 200；范围外两入口、跨工厂、未支持的发票主列表及收款快捷页 403；范围外审核/驳回/开具均 403。五条记录最终仍 REQUESTED、issuedAt 为空，没有产生越权审核或开具。HTTP 权限拒绝与行级守卫的独立证明分开：真实 PG 控制器/拦截器测试另外断言拒绝时 service/repository 零交互。

最终恢复 sales_manager ALL（version 12）。既有报价全行读回不变；全局功能权限指纹不变；对照业务工厂的 505 个表/工厂行指纹不变。仅测试新厂产生了明确记录的模拟订单、出库和待审核开票数据。

## 必要验证

- `CustomerInvoiceScopeRepositoryQueryValidationTest`：真实 JPA 启动/持久化，验证空申请人、申请人为本人但订单归他人、异工厂来源/发票、其他客户、软删除、无来源、空范围、分页顺序/计数和 ALL 兼容。补齐夹具 Customer 创建人后通过；没有放宽断言。
- `CustomerInvoiceDataScopeContractTest`：实际 Controller + DataScopeAspect，四种范围、typed actor、来源创建人范围及分页上限通过。
- `RoleDataScopePostgresContractTest` 的本批方法：真实 PG、实际 InvoiceController 映射、SELF 策略、详情/关联订单阳性与越权/异厂/删除/孤立记录阴性、三个写入口拒绝零业务调用，以及两个 tombstone 410，均通过。复用原生 PG helper 与 CI job，不另建测试设施。
- 相关既有自动开票 service/processor、手工入口 tombstone、报价范围及快捷查询拒绝对照目标测试通过；只复验夹具失败和后来修改的 PG 方法，未重复本地全量。
- 前端 `CustomerShortcutScope.spec.ts`、`InvoicesTabFields.spec.ts` 共 8 项通过，覆盖真实金额/开具日期字段、价格遮罩、403 与授权空结果。
- 窄权限终审发现 tombstone 返回值兼容问题，精确修复并补实际路由断言后 SCOPED PASS。`git -c core.whitespace=cr-at-eol diff --check` 通过。
- 新 Contract/JPA 类匹配 `java-gate-pr.yml` 实际选择器；PG 增量进入已有原生 PG 测试类。冻结后只做一次最终候选必需全量 CI。

私有证据：`C:/Users/Steve/Documents/assembly-integration-assessment-20261015` 下 `invoice-scope-target-tests.log`（保留最初夹具失败）、`invoice-scope-jpa-restored.log`、`invoice-retired-route-final.log`、`invoice-web-target-tests.log`，以及 `target-ui/invoice-scope-ui-receipt.json`（24 条验收记录）、`invoice-scope-db-final.json`、`invoice-scope-control-comparison.json`、`invoice-scope-*.png`。凭证、原始回执与截图不提交。

## 未覆盖

本批完成的是自动待审核开票记录在客户快捷页的行级权限闭环；未实际税务开具、上传 PDF 或 OCR，未开放受限账号发票主列表，未做收款快捷页、移动端或生产部署。不新增迁移，不变更正常业务工厂配置和数据。
