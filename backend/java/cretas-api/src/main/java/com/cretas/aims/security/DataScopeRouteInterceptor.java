package com.cretas.aims.security;

import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.exception.BusinessException;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import java.util.*;

/** External alternate reads fail closed until their queries support the same row policy. */
@Component
@RequiredArgsConstructor
public class DataScopeRouteInterceptor implements HandlerInterceptor {
    private final DataScopeResolver resolver;
    private final JdbcTemplate jdbc;
    public static final String RESOURCE_ATTRIBUTE=DataScopeRouteInterceptor.class.getName()+".resource";
    private record Resource(String root,String table,List<String> keys) {}
    private static final List<Resource> RESOURCES=List.of(
        new Resource("quotes","operational_quotes",List.of("quoteId")),
        new Resource("sales/orders","sales_orders",List.of("orderId")),
        new Resource("sales/deliveries","sales_delivery_records",List.of("deliveryId","parentDeliveryId","orderId")),
        new Resource("warehouse/deliveries","sales_delivery_records",List.of("deliveryId")),
        new Resource("purchase/orders","purchase_orders",List.of("orderId","orderNumber")),
        new Resource("purchase/receives","purchase_receive_records",List.of("receiveId","orderId")),
        new Resource("warehouse/receiving/receipts","purchase_receive_records",List.of("receiptId","orderId")),
        new Resource("warehouse/receiving/tasks","purchase_orders",List.of("taskId")),
        new Resource("finance/invoices/by-sales-order","sales_orders",List.of("salesOrderId")),
        new Resource("finance/invoices","invoice_records",List.of("invoiceId")),
        new Resource("finance/payments/by-sales-order","sales_orders",List.of("salesOrderId")),
        new Resource("finance/payments","payment_records",List.of("paymentId")),
        new Resource("production-plans/by-sales-order","sales_orders",List.of("salesOrderId")),
        new Resource("reference-data/customers","customers",List.of("id")),
        new Resource("reference-data/quotes","operational_quotes",List.of("id")),
        new Resource("reference-data/sales-orders","sales_orders",List.of("id")),
        new Resource("reference-data/purchase-orders","purchase_orders",List.of("id")),
        new Resource("reference-data/batches","material_batches",List.of("id")),
        new Resource("markers/sales-order","sales_orders",List.of("id")),
        new Resource("markers/purchase-order","purchase_orders",List.of("id")),
        new Resource("customers","customers",List.of("customerId")),
        new Resource("material-batches","material_batches",List.of("batchId")),
        new Resource("return-orders/purchase-source","purchase_orders",List.of("purchaseOrderId")),
        new Resource("return-orders","return_orders",List.of("returnOrderId")),
        new Resource("quality-return-orders","quality_return_orders",List.of("id")));
    private static boolean rowScopeSupported(HandlerMethod method) {
        return org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(method.getMethod(),RowScopeSupported.class)
                || org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(method.getBeanType(),RowScopeSupported.class);
    }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        if (!(handler instanceof HandlerMethod)) return true;
        Object raw=request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(raw instanceof Map<?,?> vars) || !(vars.get("factoryId") instanceof String factory)) return true;
        String base="/api/mobile/{factoryId}/";
        Object matched=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String pattern=matched==null ? "" : matched.toString();
        if (!pattern.startsWith(base)) return true;
        String path=pattern.substring(base.length());
        Resource resource=RESOURCES.stream().filter(r->path.equals(r.root()) || path.startsWith(r.root()+"/")).findFirst().orElse(null);
        if (resource==null && vars.get("recordId") instanceof String && vars.get("moduleCode") instanceof String module) {
            String table=Map.of("sales_order","sales_orders","purchase_order","purchase_orders","customer","customers",
                    "material_batch","material_batches","return_order","return_orders","quality_return_order","quality_return_orders").get(module);
            if (table!=null) resource=new Resource("",table,List.of("recordId"));
        }
        // Purchase's unrelated-record shortcuts also cannot expose global order statistics.
        boolean aggregateShortcut=Set.of("quotes/by-customer", "finance/invoices/by-customer", "finance/payments/by-customer").contains(path)
            || path.equals("sales/statistics") || path.equals("inventory/by-warehouse") || path.startsWith("list-summary/") || path.startsWith("workflow-stats/")
            || path.equals("purchase/statistics") || path.equals("purchase/receiving-tasks")
            || (path.startsWith("warehouse/receiving/") && !path.equals("warehouse/receiving/default-warehouse") && resource==null);
        // Production and finance have additional endpoints whose queries are not yet bound to
        // DataScopeContext. Keep them inside the typed actor/scope checks instead of letting the
        // generic resource-null compatibility return expose a factory-wide result or write.
        String unsupportedScopeDomain = null;
        if (resource == null) {
            if (path.equals("production-plans") || path.startsWith("production-plans/")) {
                unsupportedScopeDomain = "生产计划";
            } else if (path.equals("finance") || path.startsWith("finance/")) {
                unsupportedScopeDomain = "财务";
            }
        }
        if (resource==null && !aggregateShortcut && unsupportedScopeDomain == null) return true;
        if (resource!=null) request.setAttribute(RESOURCE_ATTRIBUTE, switch(resource.table()) {
            case "sales_delivery_records" -> "sales_orders";
            case "purchase_receive_records" -> "purchase_orders";
            default -> resource.table();
        });
        AuthenticatedActor actor=AuthenticatedActor.current(request).orElseThrow(()->new BusinessException(401,"缺少已认证数据访问身份"));
        if (actor.hasFactoryBypass()) return true;
        if (actor.isPlatformAdmin() || !factory.equals(actor.factoryId())) throw new BusinessException(403,"无权访问其他工厂数据");
        // Only this summary branch binds the same explicit creator predicate as production lists.
        if (request.getMethod().equals("POST") && path.equals("list-summary/{entityType}")
                && "productionPlan".equals(vars.get("entityType"))
                && ((HandlerMethod) handler).getBeanType().equals(com.cretas.aims.controller.ListSummaryController.class)
                && BusinessRowScope.resolve(request,factory,resolver,jdbc) != null) return true;
        if (request.getMethod().equals("GET") && Set.of("workflow-stats/production","workflow-stats/finance").contains(path)
                && ((HandlerMethod) handler).getBeanType().equals(com.cretas.aims.controller.WorkflowStatsController.class)
                && rowScopeSupported((HandlerMethod) handler)
                && BusinessRowScope.resolve(request,factory,resolver,jdbc) != null) return true;
        if (unsupportedScopeDomain != null) {
            List<Long> creators = BusinessRowScope.resolve(request, factory, resolver, jdbc);
            if (creators == null) return true;
            HandlerMethod method = (HandlerMethod) handler;
            String controller = method.getBeanType().getName();
            if (Set.of("com.cretas.aims.controller.finance.AccountController",
                    "com.cretas.aims.controller.finance.VoucherSubjectMappingController",
                    "com.cretas.aims.controller.finance.VoucherExportConfigController",
                    "com.cretas.aims.controller.finance.AccountingPeriodController").contains(controller)) {
                if (request.getMethod().equals("GET")) return true;
                throw new BusinessException(403,"此操作修改全工厂财务配置或期间，需要全工厂数据范围");
            }
            if (controller.equals("com.cretas.aims.controller.finance.MonthCloseController"))
                throw new BusinessException(403,"月结属于全工厂操作，需要全工厂数据范围");
            if (!rowScopeSupported(method))
                throw new BusinessException(403,"当前数据范围尚不支持" + unsupportedScopeDomain + "入口，访问已停止");
            if (unsupportedScopeDomain.equals("生产计划") && vars.get("planId") instanceof String planId) {
                List<Long> owners=jdbc.query("SELECT created_by FROM production_plans WHERE factory_id=? AND id=? AND deleted_at IS NULL",
                        (rs,n)->rs.getObject(1,Long.class),factory,planId);
                if (owners.size()!=1 || owners.get(0)==null || !creators.contains(owners.get(0)))
                    throw new BusinessException(403,"该生产计划不在当前账号的数据范围内");
            }
            return true;
        }
        if (request.getMethod().equals("GET") && resource != null && path.equals(resource.root())
                && Set.of("finance/invoices","finance/payments").contains(path)
                && rowScopeSupported((HandlerMethod) handler)) {
            BusinessRowScope.resolve(request,factory,resolver,jdbc);
            return true;
        }
        DataScopeContext context=resolver.resolve(actor.id());
        if (context==null || !factory.equals(context.getFactoryId())) throw new BusinessException(403,"无有效工厂数据身份");
        if (context.getScope()==DataScope.ALL) return true;
        if (request.getMethod().equals("GET") && path.equals("warehouse/receiving/tasks")
                && ((HandlerMethod) handler).getBeanType().equals(com.cretas.aims.controller.warehouse.WarehouseReceivingController.class)
                && rowScopeSupported((HandlerMethod) handler)) return true;
        // These retired endpoints only return 410 with a migration hint; they cannot create records.
        if (request.getMethod().equals("POST") && Set.of("finance/invoices/request", "finance/invoices/request-from-order").contains(path)) return true;
        // The payment service checks the locked source order before its first write.
        if (request.getMethod().equals("POST") && path.equals("finance/payments/record")) return true;
        // These two paginated controller queries bind @DataScope and filter child creators in SQL.
        if (request.getMethod().equals("GET") && Set.of("sales/orders/by-customer", "return-orders/by-customer").contains(path)) return true;
        if (request.getMethod().equals("GET") && path.equals("quotes/by-customer")
                && ((HandlerMethod) handler).hasMethodAnnotation(com.cretas.aims.annotation.DataScope.class)) return true;
        if (request.getMethod().equals("GET") && path.equals("finance/invoices/by-customer")
                && ((HandlerMethod) handler).hasMethodAnnotation(com.cretas.aims.annotation.DataScope.class)) return true;
        if (request.getMethod().equals("GET") && path.equals("finance/payments/by-customer")
                && ((HandlerMethod) handler).hasMethodAnnotation(com.cretas.aims.annotation.DataScope.class)) return true;
        if (resource!=null) {
            if (resource.root().equals("warehouse/receiving/tasks")
                    && !path.equals("warehouse/receiving/tasks/{taskId}/close-short"))
                throw new BusinessException(403,"当前数据范围不支持此收货任务查询或客户来料操作，请使用主列表");
            String key=resource.keys().stream().filter(k->vars.get(k) instanceof String).findFirst().orElse(null);
            if (key!=null) {
                String id=(String)vars.get(key), sql;
                if (resource.table().equals("payment_records"))
                    sql="SELECT o.created_by FROM payment_records p JOIN sales_orders o ON o.id=p.sales_order_id AND o.factory_id=p.factory_id WHERE p.factory_id=? AND p.id::text=? AND p.deleted_at IS NULL AND o.deleted_at IS NULL";
                else if (resource.table().equals("invoice_records"))
                    sql="SELECT o.created_by FROM invoice_records i JOIN sales_orders o ON o.id=i.sales_order_id AND o.factory_id=i.factory_id WHERE i.factory_id=? AND i.id::text=? AND i.deleted_at IS NULL AND o.deleted_at IS NULL";
                else if (resource.table().equals("operational_quotes"))
                    sql="SELECT submitted_by_user_id FROM operational_quotes WHERE factory_id=? AND id::text=? AND deleted_at IS NULL";
                else if (resource.table().equals("sales_delivery_records") && key.equals("orderId"))
                    sql="SELECT created_by FROM sales_orders WHERE factory_id=? AND id::text=?";
                else if (resource.table().equals("sales_delivery_records"))
                    // Linked deliveries inherit order ownership; standalone samples inherit customer ownership.
                    sql="SELECT CASE WHEN NULLIF(d.sales_order_id,'') IS NULL THEN c.created_by ELSE o.created_by END FROM sales_delivery_records d LEFT JOIN sales_orders o ON o.id=d.sales_order_id AND o.factory_id=d.factory_id LEFT JOIN customers c ON c.id=d.customer_id AND c.factory_id=d.factory_id WHERE d.factory_id=? AND d.id::text=?";
                else if(resource.table().equals("purchase_receive_records") && (key.equals("receiveId") || key.equals("receiptId")))
                    sql="SELECT o.created_by FROM purchase_receive_records r JOIN purchase_orders o ON o.id=r.purchase_order_id AND o.factory_id=r.factory_id WHERE r.factory_id=? AND r.id::text=?";
                else {
                    String table=resource.table().equals("purchase_receive_records") ? "purchase_orders" : resource.table();
                    String column=key.equals("orderNumber") ? "order_number" : "id::text";
                    sql="SELECT created_by FROM "+table+" WHERE factory_id=? AND "+column+"=?";
                }
                List<Long> owners=jdbc.query(sql,(rs,n)->rs.getObject(1,Long.class),factory,id);
                if (owners.size()!=1) throw new BusinessException(403,"该记录不在当前数据范围或不存在");
                resolver.assertCanAccess(factory,owners.get(0)); return true;
            }
            if (path.equals(resource.root()) && request.getMethod().equals("GET")
                    && Set.of("sales/orders","purchase/orders","customers","material-batches","return-orders","quality-return-orders").contains(resource.root())) return true;
            // Child creation checks the locked parent in the service before its first write.
            if (path.equals(resource.root()) && request.getMethod().equals("POST")) return true;
        }
        throw new BusinessException(403,"当前数据范围不支持此快捷查询或批量操作，请使用主列表");
    }
}
