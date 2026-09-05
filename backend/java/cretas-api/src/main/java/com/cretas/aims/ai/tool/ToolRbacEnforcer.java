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
     *       (owner 2026-09-06 裁定, 见下表 CRM 段那 7 条只读工具)</li>
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
            // ⚠️ 下面这 7 条是本表里**仅有的只读工具**。它们在这儿不是因为"写敏感",
            // 而是因为 owner 2026-09-06 裁定「客户名单只有 销售/财务/工厂管理员/运营 能看」——
            // 客户名单本身成了受控数据。CustomerListTool 直接调
            // CustomerService#getCustomerList, ⛔ 不经过 CustomerController,
            // 所以 controller 上那道 @RequirePermission 对 AI 路径**一点用都没有**:
            // 不在这里补一条, 生产经理在 HTTP 面被 403, 转头问一句 AI 就拿到全厂名单。
            // 权限码与 CustomerController#getCustomerList 逐字相同 (同一条裁定的两个执行点,
            // 由 CustomerListToolAiPathPermissionTest 钉住两边不许漂)。
            //
            // 🔴 2026-09-06 第二票 (SEC-CUSTOMER-ROSTER-REMAINING-PATHS): #615 只关了
            //    customer_list 一个, 而 CRM 下还有 6 个只读工具走**同样的 CustomerService
            //    方法**、同样不经过 controller。只关一个等于把绕行入口从 7 个减到 6 个。
            //    这 7 条的权限码由 CustomerListToolAiPathPermissionTest 逐条反射读
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
            Map.entry("pricing_strategy_toggle", Set.of("sales:read_write", "finance:read_write"))
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
