package com.cretas.aims.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * W9 红线 (AI-RBAC 系统性收口): AI Tool 执行链的 <b>中央 RBAC 强制点</b>。
 *
 * <p><b>问题</b> (Fable Wave5/6/7 系统性审计): #704 只给 4 个 tool 加了 AI 路径鉴权, 但整类敏感写工具
 * 仍无鉴权 —— 118 个写工具 (customer_delete / order_delete / finance_invoice_approve /
 * transfer_approve / user_create / password_reset / period_confirm_close 等) 默认
 * {@code requiresPermission()=false} + {@code hasPermission()=true}, AI 对话路径上低权角色即可执行。
 * W0 {@link WriteGuardService} 只是"确认门"(用户点确认即放行), 不做角色鉴权。
 *
 * <p><b>为什么需要中央点而不是逐 tool</b>: 共 6 条执行路径都直接 {@code tool.execute()}
 * (ToolDispatchService SITE B / DynamicToolSelectionService SITE C / SkillExecutorImpl SITE D /
 * ToolRouterServiceImpl SITE E / LlmIntentFallbackClientImpl SITE F / IntentExecutionOrchestrator
 * SITE A), 没有单一 Java choke point。逐 tool 加 {@code ToolRbacGuard} (像 #704) 易漏新 tool 且
 * 只在声明的路径生效。本 enforcer 与 W0 WriteGuard <b>在同样 6 个 SITE 紧邻调用</b>, 一处声明、
 * 全路径覆盖。
 *
 * <p><b>鉴权口径</b>: 复用 {@link ToolRbacGuard#hasAnyPermission} → 同 controller 的
 * {@link com.cretas.aims.service.PermissionService} 矩阵 (按 userId 加载 User + 工厂级权限),
 * 与 HTTP 面 {@code @RequirePermission} 完全一致。<b>fail-closed</b>: 解析失败/缺 userId → 拒绝。
 *
 * <p><b>权限来源 (两层)</b>:
 * <ol>
 *   <li>工具自身 {@link ToolExecutor#getRequiredPermissions()} override (最高优先, 显式声明);</li>
 *   <li>本类内置 <b>名称→权限映射</b> {@link #TOOL_PERMISSION_MAP} (覆盖 118 个写工具中的敏感子集,
 *       无需逐文件编辑)。</li>
 * </ol>
 *
 * <p><b>为什么不是 default-deny 所有写工具</b>: 118 个名称含写动词的工具里混有良性的图表/报表/标签
 * <i>生成</i>类 (financial_chart_generate / home_layout_generate / nutrition_label_generate /
 * trace_generate / regulatory_report_generate / revenue_report_generate / scale_config_generate),
 * 它们 by-suffix 命中 {@code _generate} 但实际是只读派生。对未映射写工具 default-deny 会误伤这些合法
 * 流程。因此 enforcer 对 <b>映射命中的敏感工具</b> 强制鉴权 (拒绝越权), 对 <b>未映射写工具</b> 放行
 * (保持原 W0 写确认行为)。安全增量是单调的: 之前所有写工具都无鉴权, 现在敏感子集有了; 无回归。
 */
@Slf4j
@Component
public class ToolRbacEnforcer {

    @Autowired
    private ToolRbacGuard rbacGuard;

    /**
     * 内置 工具名→所需权限 映射 (任一权限即可)。权限码与 controller {@code @RequirePermission} 同源。
     *
     * <p>覆盖原则: 凡是 <b>删除/审批/资金/用户与权限/库存调整/账期/转账</b> 等真敏感写操作必映射;
     * 良性 *_generate (图表/报表/标签) 故意不映射 (避免误伤只读派生)。
     *
     * <p>口径对齐 (已核 controller @RequirePermission):
     * <ul>
     *   <li>CustomerController <b>写</b> → finance/sales:read_write;
     *       <b>读(客户名单)</b> → sales:read_write|sales:read|finance:read_write|finance:read
     *       (owner 2026-09-06 裁定, 见下表 CRM 段那 8 条只读工具)</li>
     *   <li>InvoiceController → finance/sales:read_write</li>
     *   <li>TransferController → inventory:write</li>
     *   <li>UserController → hr:read_write</li>
     *   <li>SupplierController → finance/procurement:read_write</li>
     *   <li>AccountingPeriod/Voucher → finance:read_write</li>
     *   <li>PurchaseController → finance/procurement:read_write</li>
     * </ul>
     */
    static final Map<String, Set<String>> TOOL_PERMISSION_MAP = Map.ofEntries(
            // ===== CRM: 客户/订单/供应商 (删改建均敏感) =====
            // ⚠️ 下面这 8 条是本表里**仅有的只读工具**。它们在这儿不是因为"写敏感",
            // 而是因为 owner 2026-09-06 裁定「客户名单只有 销售/财务/工厂管理员/运营 能看」——
            // 客户名单本身成了受控数据。CustomerListTool 直接调
            // CustomerService#getCustomerList, ⛔ 不经过 CustomerController,
            // 所以 controller 上那道 @RequirePermission 对 AI 路径**一点用都没有**:
            // 不在这里补一条, 生产经理在 HTTP 面被 403, 转头问一句 AI 就拿到全厂名单。
            // 权限码与 CustomerController#getCustomerList 逐字相同 (同一条裁定的两个执行点,
            // 由 CustomerListToolAiPathPermissionTest 钉住两边不许漂)。
            //
            // 🔴 2026-09-06 第二票 (SEC-CUSTOMER-ROSTER-REMAINING-PATHS): #615 只关了
            //    customer_list 一个, 而 CRM/workdesk 下还有 7 个只读工具走**同样的 CustomerService
            //    方法**、同样不经过 controller。只关一个等于把绕行入口从 8 个减到 7 个。
            //    这 8 条的权限码由 CustomerListToolAiPathPermissionTest 逐条反射读
            //    CustomerController 上对应方法的 @RequirePermission 钉住 —— ⛔ 两边不许漂,
            //    也不许在这里手抄第二份口径 (形态 D)。
            //
            //    | tool                         | 它调的 service 方法            | HTTP 对位             |
            //    |------------------------------|-------------------------------|-----------------------|
            //    | customer_list                | getCustomerList               | GET /customers        |
            //    | customer_active              | getActiveCustomers            | /active               |
            //    | customer_search              | searchCustomersByName         | /search               |
            //    | customer_by_type             | getCustomersByType            | /by-type              |
            //    | customer_purchase_history    | getCustomerPurchaseHistory    | /{id}/purchase-history|
            //    | customer_price_memory_query  | CustomerPriceMemoryService    | /{id}/price-memory    |
            //    | customer_crm_status_query    | searchCustomersByName         | /{id} + /search       |
            //
            // 🔴 最后那条不在派活的清单里 —— 是按硬约束 8 grep「谁调 CustomerService 的名单方法」
            //    才捞出来的 (⛔ 数调用点, 不是数别人给的工具名)。它看着像「查单个客户的 CRM 状态」,
            //    但传 customerName 走的是 searchCustomersByName, 且**多条命中时把整份
            //    candidates(完整 CustomerDTO 列表) 原样返回** ⇒ customerName="a" 就是一次名单导出。
            //
            // ⛔ 故意**没有**映射 customer_stats: 它的默认分支 (不传 customerId) 走的是
            //    getOverallCustomerStatistics + 两张分布表 —— 那三个 HTTP 端点按档 C
            //    「一个客户身份都不吐」显式留开。给它挂四个码会让 AI 面比 HTTP 面更严,
            //    正好破坏本组测试要守的那条「两面逐字相同」不变式。
            //    ⚠️ 残余风险登记在案: customer_stats(customerId=X) 与已关的
            //    /{id}/statistics 同源。它需要调用方**先拿到一个 customerId**, 而产出
            //    customerId 的那 6 条路现在都关了。
            //
            // ⛔ 同样**故意没有**映射 customer_tracking_recent_query: 它走 CustomerRepository
            //    而不是 CustomerService 的名单方法, 且必须**先有 customerId** 才能反查出一个名字
            //    (⛔ 不能枚举)。它的主载荷是跟进记录, 属另一个域, HTTP 侧也不在本票范围。登记而非豁免。
            //
            // ⛔ customer_quality_standard —— 登记不挂门。必须先有 customerId (⛔ 不能枚举),
            //    只回那一个客户的 name/type/industry + 质检标准。它的使用者是 quality_inspector
            //    (L1 sales='-' finance='-'), 挂四码会**打断质检取标准这条业务链**, 而没有任何裁定
            //    支持那个收紧。⚠️ 与已关的 HTTP /{id} 的差别: 那条回整份 CustomerDTO
            //    (电话/税号/信用额度/余额), 这条只回名字 + 行业 + 质检标准。
            //
            // ⛔ sku_assemble —— 登记不挂门, 但**泄漏本身已在工具里改掉**:
            //    它原来在「按名字没匹配上」的分支里回 availableCustomers = 全部活跃客户名字,
            //    等于一份名录。给整个工具挂销售/财务码会改变「谁能建 SKU」(裁定不管这件事),
            //    所以改成只回 activeCustomerCount + 指向已带门的 customer_list。
            //
            // ⚠️ 上面这 4 条「登记不挂门」由 CustomerListToolAiPathPermissionTest 的
            //    REGISTERED_NOT_GATED 表钉住 —— 那张表和本注释是同一件事的两处表述, 但
            //    测试那份是**可执行**的: 新增一个碰客户数据的工具而不登记, 它当场红。
            Map.entry("customer_list",
                    Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read")),
            Map.entry("customer_active",
                    Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read")),
            Map.entry("customer_search",
                    Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read")),
            Map.entry("customer_by_type",
                    Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read")),
            Map.entry("customer_purchase_history",
                    Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read")),
            Map.entry("customer_price_memory_query",
                    Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read")),
            Map.entry("customer_crm_status_query",
                    Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read")),
            // 🔴🔴 第二把尺子 (「声明了 CustomerService / CustomerRepository 字段」) 才量出来的:
            //    上面 7 条是按「谁调 CustomerService 的名单方法」枚举的, 而 workdesk 下的
            //    customer_priority_query **直接走 CustomerRepository**, 那把尺子看不见它。
            //    它分页拉活跃客户 (limit 最大 200) 并逐条回 customerId + customerName +
            //    customerCode + contactPerson + **contactPhone** ⇒ 比 /active 还多两列联系方式。
            //    ⛔ 它没有 HTTP 对位方法 (workdesk 专用工具), 所以钉一致性那条断言按
            //    「等于裁定四码」直接断言, 而不是反射某个 controller 方法。
            Map.entry("customer_priority_query",
                    Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read")),
            //
            // 🔴🔴 第二把尺子 (「声明了 CustomerService / CustomerRepository 字段」) 补量出来的 CRM 工具。
            //    第一把尺子只看「调 CustomerService 名单方法」, 下面这几个走各自的
            //    CRM 记录仓库 (通话/商机/微信) 再**逐行反查客户名**, 那把尺子完全看不见它们,
            //    而它们都是**全厂枚举**: findByFactoryId...(factoryId) 取全部记录, 每行回 customerName
            //    ⇒ 有多少个有 CRM 活动的客户就吐多少个名字。
            Map.entry("call_record_followup_pending", Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read")),
            Map.entry("opportunity_stage_alert", Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read")),
            Map.entry("wechat_record_recent_query", Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read")),
            // 同域的两个**写**工具: 按本表既有的 customer_create 口径挂 sales:read_write
            // (⛔ 不挂只读码 —— 只读码会让 sales:read 的角色写 CRM 记录)。
            // 它们的码是裁定四码的子集, 所以完备性断言认它们「已挂门」。
            Map.entry("opportunity_transition_stage", Set.of("sales:read_write")),
            Map.entry("wechat_record_create", Set.of("sales:read_write")),
            Map.entry("customer_create", Set.of("sales:read_write", "finance:read_write")),
            Map.entry("customer_update", Set.of("sales:read_write", "finance:read_write")),
            Map.entry("customer_delete", Set.of("sales:read_write", "finance:read_write")),
            Map.entry("order_create", Set.of("sales:read_write")),
            Map.entry("order_update", Set.of("sales:read_write")),
            Map.entry("order_delete", Set.of("sales:read_write")),
            Map.entry("supplier_create", Set.of("procurement:read_write", "finance:read_write")),
            Map.entry("supplier_delete", Set.of("procurement:read_write", "finance:read_write")),
            Map.entry("sales_need_create", Set.of("sales:read_write")),
            Map.entry("return_order_create", Set.of("sales:read_write", "procurement:read_write")),

            // ===== 财务: 发票/账期/凭证/采购财审 (资金路径, 最高敏感) =====
            Map.entry("finance_invoice_approve", Set.of("finance:read_write")),
            Map.entry("purchase_finance_approve", Set.of("finance:read_write")),
            Map.entry("period_request_close", Set.of("finance:read_write")),
            Map.entry("period_confirm_close", Set.of("finance:read_write")),
            Map.entry("period_reopen", Set.of("finance:read_write")),
            Map.entry("voucher_generate", Set.of("finance:read_write")),
            Map.entry("voucher_batch_generate", Set.of("finance:read_write")),

            // ===== 采购 =====
            Map.entry("purchase_order_create", Set.of("procurement:read_write")),
            Map.entry("purchase_order_approve", Set.of("procurement:read_write")),
            Map.entry("procurement_order_create", Set.of("procurement:read_write")),
            Map.entry("requisition_create", Set.of("procurement:read_write")),

            // ===== 库存/调拨/批次 (越权动库存) =====
            Map.entry("transfer_create", Set.of("inventory:write", "warehouse:read_write")),
            Map.entry("transfer_approve", Set.of("inventory:write", "warehouse:read_write")),
            Map.entry("inventory_freeze", Set.of("inventory:write", "warehouse:read_write")),
            Map.entry("material_batch_create", Set.of("warehouse:read_write", "inventory:write")),
            Map.entry("material_batch_reserve", Set.of("warehouse:read_write", "inventory:write")),
            Map.entry("material_batch_release", Set.of("warehouse:read_write", "inventory:write")),
            Map.entry("batch_consumption_adjust", Set.of("warehouse:read_write", "inventory:write")),
            Map.entry("conversion_rate_update", Set.of("warehouse:read_write", "production:read_write")),

            // ===== HR / 用户与权限 (越权改人/批假/分配角色, 最高敏感) =====
            Map.entry("user_create", Set.of("hr:read_write", "system:read_write")),
            Map.entry("user_disable", Set.of("hr:read_write", "system:read_write")),
            Map.entry("user_role_assign", Set.of("system:read_write")),
            Map.entry("password_reset", Set.of("system:read_write", "hr:read_write")),
            Map.entry("hr_leave_approve", Set.of("hr:read_write")),

            // ===== 生产/计划/工序 (写库存/排程) =====
            Map.entry("production_plan_create", Set.of("production:read_write")),
            Map.entry("production_batch_create", Set.of("production:read_write")),
            Map.entry("plan_update", Set.of("production:read_write", "scheduling:read_write")),
            Map.entry("work_process_config_update", Set.of("production:read_write")),
            Map.entry("work_process_task_assign", Set.of("production:read_write")),
            Map.entry("ecn_approve", Set.of("production:read_write")),

            // ===== 出货 (发货是资金/库存出口) =====

            // ===== 系统/配置/告警规则/定时任务 (改全局行为) =====
            Map.entry("factory_feature_toggle", Set.of("system:read_write")),
            Map.entry("config_reset", Set.of("system:read_write")),
            Map.entry("alert_rule_create", Set.of("system:read_write")),
            Map.entry("alert_rule_update", Set.of("system:read_write")),
            Map.entry("alert_rule_delete", Set.of("system:read_write")),
            Map.entry("alert_rule_toggle", Set.of("system:read_write")),
            Map.entry("rule_create", Set.of("system:read_write")),
            Map.entry("rule_update", Set.of("system:read_write")),
            Map.entry("rule_delete", Set.of("system:read_write")),
            Map.entry("rule_toggle", Set.of("system:read_write")),
            Map.entry("scheduled_task_create", Set.of("system:read_write")),
            Map.entry("scheduled_task_update", Set.of("system:read_write")),
            Map.entry("scheduled_task_delete", Set.of("system:read_write")),
            Map.entry("scheduled_task_toggle", Set.of("system:read_write")),
            Map.entry("approval_action_execute", Set.of("system:read_write")),

            // ===== 餐饮 (业态写) =====
            Map.entry("restaurant_sales_plan_create", Set.of("restaurant:read_write")),
            Map.entry("restaurant_shift_create", Set.of("restaurant:read_write")),

            // ===== 定价策略 (改售价直接影响营收) =====
            Map.entry("pricing_strategy_create", Set.of("sales:read_write", "finance:read_write")),
            Map.entry("pricing_strategy_update", Set.of("sales:read_write", "finance:read_write")),
            Map.entry("pricing_strategy_toggle", Set.of("sales:read_write", "finance:read_write")),

            // ===== S6 验收 (2026-10-29, ERP-S6-02-SKILL-ROLE-BYPASS-001): 读受控数据的工具 =====
            // 生产经理 / 仓库经理经 monthly-financial-close Skill (以及「查看本月资产负债表」意图) 拿到了
            // 资产负债表, 而同一个人直接 GET finance/report/balance-sheet 是 403。原因: 这些工具直接调
            // service, 不经过 controller, controller 上那道 @RequirePermission 对 AI / Skill 路径不存在。
            // 规则: AI / Skill 返回的东西不能多于这个人直接调接口能拿到的 ⇒ 每个工具挂上「读同一份数据的
            // HTTP 方法」的权限码 (方法级优先, 否则类级), 逐字相同。权限码的唯一来源在 controller 一侧:
            // GatedDataToolAiPathPermissionTest 逐条反射读对位方法的注解, 两边漂了当场红; 同一个测试还扫
            // 全部工具, 凡注入了「只被带门 controller 使用的 service/repository」而没挂码的, 要么挂码要么登记理由。
            // ⚠️ 读数口径 (运营报表): report_oee / report_efficiency / report_inventory / report_kpi 等的 HTTP 对位
            //    方法只放 finance / procurement:price:view, 所以 AI 面随之收紧到同样的人。若业务上应当让生产 /
            //    仓库角色看这些运营报表, 该改的是 ReportController 上的注解 (两面一起放宽), 不是在这里单独放宽 AI 面。
            Map.entry("balance_sheet_query", Set.of("finance:read", "finance:read_write")),  // FinanceReportController#balanceSheet
            Map.entry("income_statement_query", Set.of("finance:read", "finance:read_write")),  // FinanceReportController#incomeStatement
            Map.entry("cashflow_statement_query", Set.of("finance:read", "finance:read_write")),  // FinanceReportController#cashFlow
            Map.entry("period_status_query", Set.of("finance:read", "finance:read_write")),  // AccountingPeriodController#status
            Map.entry("account_query", Set.of("finance:read", "finance:read_write")),  // AccountController#list
            Map.entry("account_tree_lookup", Set.of("finance:read", "finance:read_write")),  // AccountController#tree
            Map.entry("accounts_receivable_aging", Set.of("finance:read_write", "finance:read")),  // ArApController#getAgingAnalysis
            Map.entry("sales_record_payment", Set.of("finance:read_write")),  // ArApController#recordArPayment
            Map.entry("finance_dupont", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getFinanceReport
            Map.entry("finance_liquidity", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getFinanceReport
            Map.entry("finance_roa", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getFinanceReport
            Map.entry("finance_roe", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getFinanceReport
            Map.entry("finance_solvency", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getFinanceReport
            Map.entry("finance_stats", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getFinanceReport
            Map.entry("report_finance", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getFinanceReport
            Map.entry("report_finance_ratio", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getFinanceReport
            Map.entry("report_payment_status", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getFinanceReport
            Map.entry("payment_status", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getFinanceReport
            Map.entry("finance_invoice_status", Set.of("finance:read_write", "finance:read", "sales:read_write", "sales:read")),  // InvoiceController#detail
            Map.entry("finance_payment_record", Set.of("finance:read_write", "sales:read_write")),  // PaymentRecordController#recordPayment
            Map.entry("wage_cost_summary", Set.of("hr:read_write", "hr:read")),  // WagePolicyController#listCalculations
            Map.entry("opportunity_funnel_stats", Set.of("sales:view")),  // SalesOpportunityController#funnelStats
            Map.entry("commission_pending_total", Set.of("sales:view")),  // CommissionController#list
            Map.entry("report_dashboard_overview", Set.of("dashboard:read_write", "dashboard:read", "warehouse:read_write", "warehouse:read", "production:read_write", "production:read", "inventory:read_write", "inventory:read", "quality:read_write", "quality:read", "finance:read_write", "finance:read", "procurement:price:view")),  // ReportController#getDashboardOverview
            Map.entry("report_sales", Set.of("dashboard:read_write", "dashboard:read", "warehouse:read_write", "warehouse:read", "production:read_write", "production:read", "inventory:read_write", "inventory:read", "quality:read_write", "quality:read", "finance:read_write", "finance:read", "procurement:price:view")),  // ReportController#getDashboardOverview
            Map.entry("sales_report", Set.of("dashboard:read_write", "dashboard:read", "warehouse:read_write", "warehouse:read", "production:read_write", "production:read", "inventory:read_write", "inventory:read", "quality:read_write", "quality:read", "finance:read_write", "finance:read", "procurement:price:view")),  // ReportController#getDashboardOverview
            Map.entry("report_workshop_daily", Set.of("dashboard:read_write", "dashboard:read", "warehouse:read_write", "warehouse:read", "production:read_write", "production:read", "inventory:read_write", "inventory:read", "quality:read_write", "quality:read", "finance:read_write", "finance:read", "procurement:price:view")),  // ReportController#getProductionDashboard
            Map.entry("report_trends", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getPeriodComparisonReport
            Map.entry("report_oee", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getOeeReport
            Map.entry("report_efficiency", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getEfficiencyAnalysisReport
            Map.entry("report_benefit_overview", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getEfficiencyAnalysisReport
            Map.entry("report_kpi", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getKPIMetrics
            Map.entry("report_anomaly", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getAnomalyReport
            Map.entry("report_cost_variance", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getCostVarianceReport
            Map.entry("report_inventory", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getInventoryReport
            Map.entry("report_mrp_calculation", Set.of("finance:read", "finance:read_write", "procurement:price:view")),  // ReportController#getInventoryReport
            Map.entry("ecn_create", Set.of("production:read_write", "rd:read_write", "finance:read_write")),  // EcnController#create
            Map.entry("return_order_list", Set.of("sales:read_write", "sales:read", "procurement:read_write", "procurement:read")),  // ReturnOrderController#listReturnOrders
            Map.entry("return_order_detail", Set.of("sales:read_write", "sales:read", "procurement:read_write", "procurement:read", "finance:read_write", "finance:read")),  // ReturnOrderController#getReturnOrder
            Map.entry("return_order_statistics", Set.of("sales:read_write", "sales:read", "procurement:read_write", "procurement:read", "report:read")),  // ReturnOrderController#getStatistics
            Map.entry("return_order_approve", Set.of("sales:read_write", "procurement:read_write")),  // ReturnOrderController#approveReturnOrder
            Map.entry("batch_consumption_query", Set.of("procurement:price:view", "production:read", "production:read_write"))  // MaterialConsumptionController#getBatchConsumptionSummary
    );

    /**
     * 中央鉴权判定。在 <b>每个 tool 执行 SITE</b> 紧邻 W0 WriteGuard 调用。
     *
     * @param tool    待执行工具
     * @param context tool 执行上下文 (须含 userId 才能鉴权; 见 {@link ToolRbacGuard})
     * @return 判定结果 (allowed=true 放行; allowed=false 含拒绝文案)
     */
    public Decision check(ToolExecutor tool, Map<String, Object> context) {
        if (tool == null) {
            return Decision.allow();
        }
        Set<String> required = resolveRequiredPermissions(tool);
        if (required.isEmpty()) {
            // 未声明也未映射 → 放行 (保持原 W0 写确认行为, 不引入新拒绝)。
            return Decision.allow();
        }
        String[] codes = required.toArray(new String[0]);
        boolean ok = rbacGuard.hasAnyPermission(context, codes);
        if (ok) {
            return Decision.allow();
        }
        String action = tool.getToolName();
        String msg = rbacGuard.denyMessage(context, action, codes);
        log.warn("W9 AI-RBAC 拒绝: tool={}, requiredAny={}, userId={}",
                action, required, context != null ? context.get("userId") : null);
        return Decision.deny(msg, required);
    }

    /**
     * 便捷判定: 仅需 boolean (用于异常型 SITE — ToolRouter/Skill/AutoPlan)。
     */
    public boolean isAllowed(ToolExecutor tool, Map<String, Object> context) {
        return check(tool, context).isAllowed();
    }

    /**
     * 解析工具所需权限: 先看 tool 自身 override, 再回退内置映射。
     */
    public Set<String> resolveRequiredPermissions(ToolExecutor tool) {
        if (tool == null) {
            return Set.of();
        }
        Set<String> declared = tool.getRequiredPermissions();
        if (declared != null && !declared.isEmpty()) {
            return declared;
        }
        Set<String> mapped = TOOL_PERMISSION_MAP.get(tool.getToolName());
        return mapped != null ? mapped : Set.of();
    }

    /** 拒绝文案 (供 SITE 直接复用, 与 ToolRbacGuard.denyMessage 一致)。 */
    public String denyMessage(ToolExecutor tool, Map<String, Object> context) {
        if (tool == null) {
            return "无效的操作。";
        }
        Set<String> required = resolveRequiredPermissions(tool);
        return rbacGuard.denyMessage(context, tool.getToolName(),
                required.toArray(new String[0]));
    }

    /**
     * 鉴权判定结果。
     */
    public static final class Decision {
        private final boolean allowed;
        private final String message;
        private final Set<String> requiredPermissions;

        private Decision(boolean allowed, String message, Set<String> requiredPermissions) {
            this.allowed = allowed;
            this.message = message;
            this.requiredPermissions = requiredPermissions;
        }

        public static Decision allow() {
            return new Decision(true, null, Set.of());
        }

        public static Decision deny(String message, Set<String> requiredPermissions) {
            return new Decision(false, message, requiredPermissions);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getMessage() {
            return message;
        }

        public Set<String> getRequiredPermissions() {
            return requiredPermissions;
        }
    }
}
