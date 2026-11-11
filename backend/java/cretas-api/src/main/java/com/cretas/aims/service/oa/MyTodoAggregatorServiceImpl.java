package com.cretas.aims.service.oa;

import com.cretas.aims.dto.oa.TodoItemDTO;
import com.cretas.aims.dto.oa.TodoItemDTO.TodoType;
import com.cretas.aims.dto.inventory.PaymentRequestApprovedDTO;
import com.cretas.aims.dto.inventory.WastageReportDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord;
import com.cretas.aims.entity.inventory.SupplierDeliveryNote;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.service.inventory.PaymentRequestService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.inventory.WastageReportService;
import com.cretas.aims.service.inventory.SupplierDeliveryNoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 我的待办聚合服务实现 — RN OA 待办中心后端。
 *
 * <p>按权限出数 (纪律排查 B13, Steve 2026-11-10; 不再按角色名写死「只有财务经理 / 出纳有待办」):
 * 每类待办对应一组权限 (任一即可, 见 {@link #TYPE_PERMISSIONS}), 调用方的岗位具备其中一项才出这一类。
 * 默认值下 finance_manager / cashier / 厂长拿到的就是原来的那几类; 其它岗位按自己的权限拿到相应的类,
 * 没有任何相关权限的岗位仍然是空集合。
 *
 * <p>fail-soft 原则: 每个域查询都包在独立 try/catch 中，单域失败 log warn + 该类型返空，
 * 不影响其他域正常返回，不拖垮整体请求（符合 .claude/rules 禁降级假数据规范）。
 *
 * <p>阈值: {@code cretas.oa.todo.detail-threshold}（默认 5000），
 * {@code needDetail = amount == null || amount.compareTo(threshold) > 0}。
 *
 * @since 2026-06-12 (OA 待办聚合 B2)
 */
@Slf4j
@Service
public class MyTodoAggregatorServiceImpl implements MyTodoAggregatorService {

    // ─── Dependencies ──────────────────────────────────────────────────────

    private final PurchaseService purchaseService;
    private final SalesService salesService;
    private final SupplierDeliveryNoteService supplierDeliveryNoteService;
    private final FactoryStocktakeRepository stocktakeRepository;
    private final FactoryWarehouseRepository warehouseRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final PaymentRequestService paymentRequestService;
    private final SalesPriceAdjustmentService salesPriceAdjustmentService;
    private final WastageReportService wastageReportService;

    /**
     * 采购财审改走 OA 实例后需要的两个依赖 —— **字段注入而不是构造器参数**。
     *
     * <p>本类有两个构造器，且类注释里记着 2026-06-12 的 prod 启动阻塞事故
     * （多构造器时 Spring 隐式注入失效）。往构造器加参数要同时改两个构造器和全部测试调用点，
     * 风险与收益不成比例；{@code required = false} 拿不到就按本类既有的 fail-soft 返回空列表。
     */
    @Autowired(required = false)
    private WorkflowEngineService workflowEngineService;

    @Autowired(required = false)
    private PurchaseOrderRepository purchaseOrderRepository;

    /** 按权限出数用; 同上是字段注入 (本类多构造器, 不动构造器参数)。缺失 = 所有人都没有待办, 不放开。 */
    @Autowired(required = false)
    private PermissionService permissionService;

    /**
     * 「能办哪些待办」判强制插单时要看本厂启用中的插单审批画布点没点名这个岗位 (B4)。
     * {@code @Lazy}: 审批画布服务的依赖链很长, 不让它和本类形成启动期环; 拿不到 = 只认当前确有待审实例。
     */
    @Lazy
    @Autowired(required = false)
    private ApprovalWorkflowService approvalWorkflowService;

    // ─── Config ────────────────────────────────────────────────────────────

    /** 待办金额阈值，超过则强制进详情页审批。默认 5000 元。 */
    @Value("${cretas.oa.todo.detail-threshold:5000}")
    private BigDecimal detailThreshold = new BigDecimal("5000");

    /**
     * Spring 注入构造器（必要依赖，{@code detailThreshold} 通过 {@code @Value} 注入）。
     * 测试中通过 {@link #MyTodoAggregatorServiceImpl(PurchaseService, SalesService,
     * SupplierDeliveryNoteService, FactoryStocktakeRepository, FactoryWarehouseRepository,
     * ReturnOrderRepository, CustomerRepository, SupplierRepository, PaymentRequestService,
     * SalesPriceAdjustmentService, BigDecimal)}
     * 直接传入阈值，无需 Spring 上下文。
     *
     * <p>⚠️ 本类有两个构造器，Spring 隐式构造器注入只在「恰好一个构造器」时生效；
     * 多构造器必须显式 {@code @Autowired} 标注主构造器，否则 Spring 回退找默认构造器 →
     * {@code NoSuchMethodException: <init>()} → 启动失败（2026-06-12 prod 部署阻塞事故根因，
     * 表象为"启动 hang"：context refresh 失败但已创建 bean 的非 daemon 线程把 JVM 钉死）。
     */
    @Autowired
    public MyTodoAggregatorServiceImpl(
            PurchaseService purchaseService,
            SalesService salesService,
            SupplierDeliveryNoteService supplierDeliveryNoteService,
            FactoryStocktakeRepository stocktakeRepository,
            FactoryWarehouseRepository warehouseRepository,
            ReturnOrderRepository returnOrderRepository,
            CustomerRepository customerRepository,
            SupplierRepository supplierRepository,
            PaymentRequestService paymentRequestService,
            SalesPriceAdjustmentService salesPriceAdjustmentService,
            WastageReportService wastageReportService) {
        this.purchaseService = purchaseService;
        this.salesService = salesService;
        this.supplierDeliveryNoteService = supplierDeliveryNoteService;
        this.stocktakeRepository = stocktakeRepository;
        this.warehouseRepository = warehouseRepository;
        this.returnOrderRepository = returnOrderRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
        this.paymentRequestService = paymentRequestService;
        this.salesPriceAdjustmentService = salesPriceAdjustmentService;
        this.wastageReportService = wastageReportService;
    }

    /**
     * 测试友好构造器 — 允许直接设置阈值，无需 Spring 容器。
     */
    MyTodoAggregatorServiceImpl(
            PurchaseService purchaseService,
            SalesService salesService,
            SupplierDeliveryNoteService supplierDeliveryNoteService,
            FactoryStocktakeRepository stocktakeRepository,
            FactoryWarehouseRepository warehouseRepository,
            ReturnOrderRepository returnOrderRepository,
            CustomerRepository customerRepository,
            SupplierRepository supplierRepository,
            PaymentRequestService paymentRequestService,
            SalesPriceAdjustmentService salesPriceAdjustmentService,
            WastageReportService wastageReportService,
            BigDecimal detailThreshold) {
        this(purchaseService, salesService, supplierDeliveryNoteService,
                stocktakeRepository, warehouseRepository, returnOrderRepository,
                customerRepository, supplierRepository, paymentRequestService,
                salesPriceAdjustmentService, wastageReportService);
        this.detailThreshold = detailThreshold;
    }

    // ─── TodoType → 所需权限 (任一即可) ─────────────────────────────────────

    /**
     * 每类待办要求调用方具备的权限 (任一)。门槛取各类审批 / 处理接口自己的门槛, 所以「列表里出现 = 点进去能办」:
     * <ul>
     *   <li>采购 / 销售财审、价格异常、退货财审: {@code finance:read_write} (这些接口的 @RequirePermission)</li>
     *   <li>盘点差异 / 改价: 各自的审批码 {@code approval_xxx:approve} (谁能批由权限配置决定)</li>
     *   <li>报损: {@code wastage:approve}; 付款: {@code payment:disburse} (默认出纳); 都是可配置岗位名单的权限码</li>
     *   <li>强制插单: 排程 / 生产读写 (插单审批接口的门槛), 再叠加 OA 当前节点的审批岗位</li>
     * </ul>
     */
    static final Map<TodoType, List<String>> TYPE_PERMISSIONS;

    static {
        Map<TodoType, List<String>> m = new java.util.EnumMap<>(TodoType.class);
        m.put(TodoType.PURCHASE_FINANCE_REVIEW, List.of("finance:read_write"));
        m.put(TodoType.SALES_FINANCE_REVIEW, List.of("finance:read_write"));
        m.put(TodoType.PRICE_ANOMALY, List.of("finance:read_write"));
        m.put(TodoType.STOCKTAKE_APPROVAL, List.of(
                com.cretas.aims.service.workflow.ApprovalAuthority.code(
                        com.cretas.aims.service.impl.PermissionServiceImpl.APPROVAL_STOCKTAKE)));
        m.put(TodoType.RETURN_FINANCE_REVIEW, List.of("finance:read_write"));
        m.put(TodoType.SALES_PRICE_ADJUSTMENT, List.of(
                com.cretas.aims.service.workflow.ApprovalAuthority.code(
                        com.cretas.aims.service.impl.PermissionServiceImpl.APPROVAL_PRICE_ADJUSTMENT)));
        m.put(TodoType.WASTAGE_APPROVAL, List.of(PermissionService.WASTAGE_APPROVE_PERMISSION));
        m.put(TodoType.PAYMENT_DISBURSE, List.of(PermissionService.PAYMENT_DISBURSE_PERMISSION));
        m.put(TodoType.FORCE_INSERT_APPROVAL, List.of("scheduling:read_write", "production:read_write"));
        TYPE_PERMISSIONS = Collections.unmodifiableMap(m);
    }

    /** 调用方岗位在本厂具备权限的待办类型 (保持枚举声明顺序)。 */
    private Set<TodoType> typesFor(String factoryId, String callerRole) {
        if (permissionService == null || callerRole == null || callerRole.isBlank()) {
            return Collections.emptySet();
        }
        return typesMatching(factoryId, code -> permissionService.roleHasPermission(factoryId, callerRole, code));
    }

    /** 具体用户在本厂具备权限的待办类型: 与岗位路径同一张 TYPE_PERMISSIONS, 但叠加个人权限覆盖。 */
    private Set<TodoType> typesForUser(String factoryId, com.cretas.aims.entity.User user) {
        if (permissionService == null || user == null || user.getRoleCode() == null || user.getRoleCode().isBlank()) {
            return Collections.emptySet();
        }
        return typesMatching(factoryId, code -> permissionService.hasPermission(user, code));
    }

    private Set<TodoType> typesMatching(String factoryId, java.util.function.Predicate<String> granted) {
        Set<TodoType> out = new LinkedHashSet<>();
        for (TodoType type : TodoType.values()) {
            List<String> codes = TYPE_PERMISSIONS.get(type);
            if (codes == null) continue;
            try {
                for (String code : codes) {
                    if (granted.test(code)) {
                        out.add(type);
                        break;
                    }
                }
            } catch (RuntimeException failure) {
                // fail-soft: 权限配置读不到时这一类待办不出 (宁可少给不多给), 不影响其它类
                log.warn("[MyTodoAggregator] 权限读取失败, 跳过 type={} factoryId={}: {}", type, factoryId, failure.getMessage());
            }
        }
        return out;
    }

    // ─── 能办的待办类型 (App 决定显不显示待办入口, B4) ───────────────────────

    @Override
    public Set<TodoType> availableTypes(String factoryId, String callerRole) {
        return narrowToNamedApprover(factoryId, callerRole, typesFor(factoryId, callerRole));
    }

    @Override
    public Set<TodoType> availableTypesForUser(String factoryId, com.cretas.aims.entity.User user) {
        if (user == null) {
            return Collections.emptySet();
        }
        return narrowToNamedApprover(factoryId, user.getRoleCode(), typesForUser(factoryId, user));
    }

    /**
     * 强制插单的门槛 {@code production:read_write} 默认给了操作员、车间主任、班组长等 9 个岗位, 但真正能批的是
     * 审批画布节点 {@code approverRoles} 点名的岗位 (列表取数 {@link #fetchForceInsertApproval} 就按这个过滤)。
     * 只按权限判, 操作员的 App 会多出一个永远空的待办入口。所以这一类再要求: 厂长 (审批引擎兜底同口径),
     * 或本厂启用中的插单审批画布里有审批节点点名了这个岗位, 或此刻确有一条在等这个岗位批的实例 (旧版本画布上的实例)。
     * 读不到 → 这一类不算 (宁可少给入口, 不给空入口)。
     */
    private Set<TodoType> narrowToNamedApprover(String factoryId, String role, Set<TodoType> types) {
        if (!types.contains(TodoType.FORCE_INSERT_APPROVAL) || isForceInsertApprover(factoryId, role)) {
            return types;
        }
        Set<TodoType> narrowed = new LinkedHashSet<>(types);
        narrowed.remove(TodoType.FORCE_INSERT_APPROVAL);
        return narrowed;
    }

    private boolean isForceInsertApprover(String factoryId, String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        if (com.cretas.aims.entity.enums.FactoryUserRole.factory_super_admin.name().equals(role)) {
            return true;
        }
        try {
            if (approvalWorkflowService != null) {
                ApprovalWorkflow active = approvalWorkflowService
                        .getActiveByDecisionType(factoryId, DecisionType.FORCE_INSERT).orElse(null);
                if (active != null && namesApproverRole(
                        approvalWorkflowService.deserializeNodes(active.getNodesJson()), role)) {
                    return true;
                }
            }
            return !fetchForceInsertApproval(factoryId, role).isEmpty();
        } catch (RuntimeException failure) {
            log.warn("[MyTodoAggregator] 插单审批画布读取失败, 不给插单待办入口 factoryId={} role={}: {}",
                    factoryId, role, failure.getMessage());
            return false;
        }
    }

    /** 与 {@code WorkflowEngineServiceImpl#findPendingForRole} 同一判据: approval 节点的 approverRoles 逐项 equals。 */
    private static boolean namesApproverRole(List<ApprovalWorkflowNode> nodes, String role) {
        if (nodes == null) {
            return false;
        }
        for (ApprovalWorkflowNode node : nodes) {
            if (node == null || !"approval".equals(node.getType()) || node.getConfig() == null) {
                continue;
            }
            Object roles = node.getConfig().get("approverRoles");
            if (roles instanceof List<?> roleList) {
                for (Object r : roleList) {
                    if (r != null && role.equals(String.valueOf(r))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ─── API ───────────────────────────────────────────────────────────────

    @Override
    public List<TodoItemDTO> listTodos(String factoryId, String callerRole) {
        return collect(factoryId, callerRole, typesFor(factoryId, callerRole));
    }

    @Override
    public List<TodoItemDTO> listTodosForUser(String factoryId, com.cretas.aims.entity.User user) {
        if (user == null) {
            return Collections.emptyList();
        }
        return collect(factoryId, user.getRoleCode(), typesForUser(factoryId, user));
    }

    @Override
    public int countTodosForUser(String factoryId, com.cretas.aims.entity.User user) {
        return listTodosForUser(factoryId, user).size();
    }

    private List<TodoItemDTO> collect(String factoryId, String callerRole, Set<TodoType> types) {
        if (types.isEmpty()) {
            return Collections.emptyList();
        }

        List<TodoItemDTO> all = new ArrayList<>();
        for (TodoType type : types) {
            try {
                List<TodoItemDTO> items = fetchByType(factoryId, callerRole, type);
                all.addAll(items);
            } catch (Exception e) {
                log.warn("[MyTodoAggregator] fan-out failed for type={} factoryId={}: {}",
                        type, factoryId, e.getMessage(), e);
                // fail-soft: skip this type, continue with others
            }
        }

        // Sort by submittedAt descending (null submittedAt goes last)
        all.sort(Comparator.comparing(TodoItemDTO::getSubmittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        return all;
    }

    @Override
    public int countTodos(String factoryId, String callerRole) {
        return listTodos(factoryId, callerRole).size();
    }

    // ─── Per-type fan-out ─────────────────────────────────────────────────

    private List<TodoItemDTO> fetchByType(String factoryId, String callerRole, TodoType type) {
        return switch (type) {
            case PURCHASE_FINANCE_REVIEW -> fetchPurchaseFinanceReview(factoryId, callerRole);
            case SALES_FINANCE_REVIEW    -> fetchSalesFinanceReview(factoryId);
            case PRICE_ANOMALY           -> fetchPriceAnomaly(factoryId);
            case STOCKTAKE_APPROVAL      -> fetchStocktakeApproval(factoryId);
            case RETURN_FINANCE_REVIEW   -> fetchReturnFinanceReview(factoryId);
            case SALES_PRICE_ADJUSTMENT  -> fetchSalesPriceAdjustment(factoryId);
            case WASTAGE_APPROVAL        -> fetchWastageApproval(factoryId, callerRole);
            case PAYMENT_DISBURSE        -> fetchPaymentDisburse(factoryId);
            case FORCE_INSERT_APPROVAL   -> fetchForceInsertApproval(factoryId, callerRole);
        };
    }

    // ── 强制插单审批 ─────────────────────────────────────────────────────

    /**
     * 强制插单待审批 —— 与采购财审同一口径: 问 OA, 取「当前活动节点的 approverRoles 含调用方岗位」的 RUNNING 实例。
     * 审批动作走通用的 OA 实例端点 (instanceId + expectedNodeId), 与盘点审批一样, App 不需要单独的审批接口。
     */
    private List<TodoItemDTO> fetchForceInsertApproval(String factoryId, String callerRole) {
        if (workflowEngineService == null || callerRole == null || callerRole.isBlank()) {
            return Collections.emptyList();
        }
        Page<ApprovalWorkflowInstance> page = workflowEngineService.findPendingForRole(
                factoryId, callerRole, "FORCE_INSERT", PageRequest.of(0, 200));
        if (page == null || page.getContent() == null || page.getContent().isEmpty()) {
            return Collections.emptyList();
        }
        List<TodoItemDTO> todos = new ArrayList<>();
        for (ApprovalWorkflowInstance inst : page.getContent()) {
            String planId = inst.getBusinessEntityId();
            if (planId == null || planId.isBlank()) continue;
            todos.add(TodoItemDTO.builder()
                    .type(TodoType.FORCE_INSERT_APPROVAL)
                    .refId(planId)
                    .refNumber(planId)
                    .title("强制插单审批 — " + planId)
                    .amount(null)
                    .counterparty("")
                    .submittedBy(inst.getInitiatedBy() != null ? String.valueOf(inst.getInitiatedBy()) : null)
                    .submittedAt(inst.getInitiatedAt())
                    .needDetail(false)
                    .detailPath(null)
                    .instanceId(inst.getId())
                    .expectedNodeId(inst.getCurrentNodeIds() == null || inst.getCurrentNodeIds().isEmpty()
                            ? null : inst.getCurrentNodeIds().get(0))
                    .build());
        }
        return todos;
    }

    // ── 采购财审 ─────────────────────────────────────────────────────────

    /**
     * 采购财审待办 —— 按 **OA 实例的当前节点** 取，不再按采购单状态取。
     *
     * <p>⚠️ 2026-08-15 修正（prod 实测发现）。原实现查
     * {@code PurchaseOrderStatus.PENDING_FINANCE_REVIEW}，而 **OA 投影根本不经过这个状态**：
     * {@code projectWorkflowState} 只有 {@code RUNNING→WORKFLOW_RUNNING} 和
     * {@code APPROVED→FINANCE_APPROVED}。唯一会置成 PENDING_FINANCE_REVIEW 的
     * {@code legacyApproveOrder} 只被一个 AI 工具调用，普通流程到不了。
     *
     * <p>实测：在 F006 提交一张 ¥40,000 的采购单后，实例停在 {@code approval_finance}
     * 节点等 finance_manager，而全库 {@code PENDING_FINANCE_REVIEW} 的采购单数 = **0**
     * —— 也就是说**财务经理的待办里永远看不到正在等他审批的那张单**。
     * 读数完全正常（列表成功返回、空列表），量的却不是该量的东西。
     *
     * <p>现在改成问 OA：{@code findPendingForRole} 返回「当前活动节点的 approverRoles
     * 含调用方角色」的 RUNNING 实例，这才是「等我审批」的权威定义。
     */
    private List<TodoItemDTO> fetchPurchaseFinanceReview(String factoryId, String callerRole) {
        if (workflowEngineService == null || purchaseOrderRepository == null
                || callerRole == null || callerRole.isBlank()) {
            return Collections.emptyList();
        }
        Page<ApprovalWorkflowInstance> page = workflowEngineService.findPendingForRole(
                factoryId, callerRole, "PURCHASE_ORDER", PageRequest.of(0, 200));
        if (page == null || page.getContent() == null || page.getContent().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> orderIds = page.getContent().stream()
                .map(ApprovalWorkflowInstance::getBusinessEntityId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.toList());
        Map<String, PurchaseOrder> byId = new HashMap<>();
        purchaseOrderRepository.findAllById(orderIds).forEach(po -> byId.put(po.getId(), po));

        List<TodoItemDTO> todos = new ArrayList<>();
        for (ApprovalWorkflowInstance inst : page.getContent()) {
            PurchaseOrder po = byId.get(inst.getBusinessEntityId());
            // 查不到业务单就跳过 —— 不伪造一张卡片让人点进去 404
            if (po == null) continue;
            TodoItemDTO dto = mapPurchaseOrder(po);
            dto.setInstanceId(inst.getId());
            dto.setExpectedNodeId(inst.getCurrentNodeIds() == null || inst.getCurrentNodeIds().isEmpty()
                    ? null : inst.getCurrentNodeIds().get(0));
            // 提交时间用实例的发起时间, 比采购单 createdAt 更接近「什么时候到我这儿的」
            if (inst.getInitiatedAt() != null) dto.setSubmittedAt(inst.getInitiatedAt());
            todos.add(dto);
        }
        return todos;
    }

    private TodoItemDTO mapPurchaseOrder(PurchaseOrder po) {
        BigDecimal amount = po.getTotalAmount();
        String supplierName = po.getSupplierName();
        String title = "采购财审 — " + (supplierName != null ? supplierName : po.getOrderNumber());

        // submittedAt: use createdAt as best proxy for when it entered PENDING_FINANCE_REVIEW
        // (PurchaseOrder doesn't track financeSubmittedAt separately)
        LocalDateTime submittedAt = po.getCreatedAt();

        return TodoItemDTO.builder()
                .type(TodoType.PURCHASE_FINANCE_REVIEW)
                .refId(po.getId())
                .refNumber(po.getOrderNumber())
                .title(title)
                .amount(amount)
                .counterparty(supplierName)
                .submittedAt(submittedAt)
                .needDetail(needsDetail(amount))
                .detailPath("TodoDetail/purchase/" + po.getId())
                .build();
    }

    // ── 销售财审 ─────────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchSalesFinanceReview(String factoryId) {
        var page = salesService.getSalesOrdersByStatus(
                factoryId, SalesOrderStatus.PENDING_FINANCE_REVIEW, 1, Integer.MAX_VALUE);
        if (page == null || page.getContent() == null) return Collections.emptyList();

        return page.getContent().stream()
                .map(so -> mapSalesOrder(so))
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapSalesOrder(SalesOrder so) {
        BigDecimal amount = so.getTotalAmount();
        String customerName = so.getCustomerName();
        String title = "销售财审 — " + (customerName != null ? customerName : so.getOrderNumber());

        LocalDateTime submittedAt = so.getCreatedAt();

        return TodoItemDTO.builder()
                .type(TodoType.SALES_FINANCE_REVIEW)
                .refId(so.getId())
                .refNumber(so.getOrderNumber())
                .title(title)
                .amount(amount)
                .counterparty(customerName)
                .submittedAt(submittedAt)
                .needDetail(needsDetail(amount))
                .detailPath("TodoDetail/sales/" + so.getId())
                .build();
    }

    // ── 价格异常 ─────────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchPriceAnomaly(String factoryId) {
        Page<SupplierDeliveryNote> page = supplierDeliveryNoteService
                .listPendingPriceAnomalyApprovals(factoryId, null,
                        PageRequest.of(0, Integer.MAX_VALUE / 2));
        if (page == null) return Collections.emptyList();

        return page.getContent().stream()
                .map(note -> mapPriceAnomaly(note))
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapPriceAnomaly(SupplierDeliveryNote note) {
        BigDecimal amount = note.getTotalAmount();
        String supplierName = note.getSupplierName();
        String title = "价格异常 — " + (supplierName != null ? supplierName : note.getNoteNumber());

        LocalDateTime submittedAt = note.getPriceAnomalySubmittedAt();
        if (submittedAt == null) {
            submittedAt = note.getCreatedAt();
        }

        return TodoItemDTO.builder()
                .type(TodoType.PRICE_ANOMALY)
                .refId(note.getId())
                .refNumber(note.getNoteNumber())
                .title(title)
                .amount(amount)
                .counterparty(supplierName)
                .submittedAt(submittedAt)
                .needDetail(needsDetail(amount))
                .detailPath("TodoDetail/priceAnomaly/" + note.getId())
                .build();
    }

    // ── 盘点审批 ─────────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchStocktakeApproval(String factoryId) {
        Page<FactoryStocktake> page = stocktakeRepository.findByFactoryIdAndOptionalStatus(
                factoryId, FactoryStocktake.Status.PENDING_APPROVAL,
                PageRequest.of(0, Integer.MAX_VALUE / 2));
        if (page == null) return Collections.emptyList();

        return page.getContent().stream()
                .map(s -> mapStocktake(s))
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapStocktake(FactoryStocktake stocktake) {
        // 盘点无金额字段 → needDetail=true（保守，强制看详情）
        String warehouseName = resolveWarehouseName(stocktake);
        String title = "盘点审批 — " + stocktake.getStocktakeNo()
                + "（" + stocktake.getPeriodMonth() + "）";

        LocalDateTime submittedAt = stocktake.getSubmittedAt();
        if (submittedAt == null) {
            submittedAt = stocktake.getCreatedAt();
        }

        TodoItemDTO dto = TodoItemDTO.builder()
                .type(TodoType.STOCKTAKE_APPROVAL)
                .refId(stocktake.getId())
                .refNumber(stocktake.getStocktakeNo())
                .title(title)
                .amount(null)  // 盘点任务无金额
                .counterparty(warehouseName)
                .submittedAt(submittedAt)
                .needDetail(true)  // 盘点无金额 → 保守 true
                .detailPath("TodoDetail/stocktake/" + stocktake.getId())
                .build();

        String instanceId = stocktake.getWorkflowInstanceId();
        dto.setInstanceId(instanceId);
        if (instanceId != null && workflowEngineService != null) {
            workflowEngineService.getInstance(stocktake.getFactoryId(), instanceId)
                    .map(ApprovalWorkflowInstance::getCurrentNodeIds)
                    .filter(nodes -> !nodes.isEmpty())
                    .map(nodes -> nodes.get(0))
                    .ifPresent(dto::setExpectedNodeId);
        }
        return dto;
    }

    // ── 付款（出纳） ──────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchReturnFinanceReview(String factoryId) {
        Page<ReturnOrder> page = returnOrderRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(
                factoryId, ReturnOrderStatus.APPROVED,
                PageRequest.of(0, Integer.MAX_VALUE / 2));
        if (page == null) return Collections.emptyList();

        return page.getContent().stream()
                .map(this::mapReturnOrder)
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapReturnOrder(ReturnOrder order) {
        BigDecimal amount = order.getTotalAmount();
        String counterparty = resolveReturnCounterparty(order);
        LocalDateTime submittedAt = order.getApprovedAt();
        if (submittedAt == null) {
            submittedAt = order.getUpdatedAt();
        }
        if (submittedAt == null) {
            submittedAt = order.getCreatedAt();
        }

        return TodoItemDTO.builder()
                .type(TodoType.RETURN_FINANCE_REVIEW)
                .refId(order.getId())
                .refNumber(order.getReturnNumber())
                .title("退货财审 — " + order.getReturnNumber())
                .amount(amount)
                .counterparty(counterparty)
                .submittedAt(submittedAt)
                .needDetail(needsDetail(amount))
                .detailPath("TodoDetail/return/" + order.getId())
                .build();
    }

    // ── 销售改价待审批 ────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchSalesPriceAdjustment(String factoryId) {
        List<SalesPriceAdjustmentRecord> records =
                salesPriceAdjustmentService.listPendingApprovals(factoryId);
        if (records == null) return Collections.emptyList();

        return records.stream()
                .map(this::mapSalesPriceAdjustment)
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapSalesPriceAdjustment(SalesPriceAdjustmentRecord r) {
        // 改价无单笔"金额"语义 → 用新单价做展示金额 (Rule 2 上下文); needDetail 保守 true,
        // 价格审批必须看改前/改后对比, 不允许一键通过.
        BigDecimal newPrice = r.getNewUnitPrice();
        String changeDir = (r.getOldUnitPrice() != null && r.getNewUnitPrice() != null
                && r.getNewUnitPrice().compareTo(r.getOldUnitPrice()) < 0) ? "降价" : "涨价";
        String title = "改价审批 — " + changeDir
                + " " + (r.getOldUnitPrice() != null ? r.getOldUnitPrice().stripTrailingZeros().toPlainString() : "?")
                + " → " + (newPrice != null ? newPrice.stripTrailingZeros().toPlainString() : "?");

        return TodoItemDTO.builder()
                .type(TodoType.SALES_PRICE_ADJUSTMENT)
                .refId(r.getId())
                .refNumber(r.getSalesOrderId())
                .title(title)
                .amount(newPrice)
                .counterparty(r.getAdjustedByName())
                .submittedBy(r.getAdjustedByName())
                .submittedAt(r.getCreatedAt())
                .needDetail(true)  // 改价审批必须看改前/改后对比 → 强制进详情
                .detailPath("TodoDetail/salesPriceAdjustment/" + r.getId())
                .build();
    }

    private List<TodoItemDTO> fetchWastageApproval(String factoryId, String callerRole) {
        Page<WastageReportDTO> page = wastageReportService.listPending(
                factoryId, callerRole, PageRequest.of(0, Integer.MAX_VALUE / 2));
        if (page == null || page.getContent() == null) return Collections.emptyList();

        return page.getContent().stream()
                .map(this::mapWastageReport)
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapWastageReport(WastageReportDTO report) {
        String counterparty = resolveWastageCounterparty(report);
        LocalDateTime submittedAt = report.getSubmittedAt();
        if (submittedAt == null) {
            submittedAt = report.getCreatedAt();
        }

        return TodoItemDTO.builder()
                .type(TodoType.WASTAGE_APPROVAL)
                .refId(report.getId())
                .refNumber(report.getReportNo())
                .title("报损审批 — " + report.getReportNo())
                .amount(null)
                .counterparty(counterparty)
                .submittedBy(report.getSubmittedBy() != null ? String.valueOf(report.getSubmittedBy()) : null)
                .submittedAt(submittedAt)
                .needDetail(true)
                .detailPath("TodoDetail/wastage/" + report.getId())
                .build();
    }

    // ── 付款（出纳） ──────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchPaymentDisburse(String factoryId) {
        List<PaymentRequestApprovedDTO> prs = paymentRequestService.listApprovedForPaymentWithDetails(factoryId);
        if (prs == null) return Collections.emptyList();

        return prs.stream()
                .map(pr -> mapPaymentRequest(pr))
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapPaymentRequest(PaymentRequestApprovedDTO pr) {
        BigDecimal amount = pr.getAmount();
        String counterparty = hasText(pr.getSupplierName()) ? pr.getSupplierName() : "付款对象需核对";
        String title = "付款 — " + pr.getRequestNumber();

        LocalDateTime submittedAt = pr.getCreatedAt();
        if (submittedAt == null) {
            submittedAt = pr.getApprovedAt();
        }

        return TodoItemDTO.builder()
                .type(TodoType.PAYMENT_DISBURSE)
                .refId(pr.getId())
                .refNumber(pr.getRequestNumber())
                .title(title)
                .amount(amount)
                .counterparty(counterparty)
                .submittedAt(submittedAt)
                .needDetail(needsDetail(amount))
                .detailPath("TodoDetail/payment/" + pr.getId())
                .build();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    /**
     * 判断是否需要进详情页审批。
     * 规则: amount == null（无金额信息保守处理）OR amount > threshold → true。
     * amount == threshold → false（等于阈值视为小额，允许一键审批）。
     */
    private boolean needsDetail(BigDecimal amount) {
        if (amount == null) return true;
        return amount.compareTo(detailThreshold) > 0;
    }

    private String resolveWarehouseName(FactoryStocktake stocktake) {
        if (stocktake == null || !hasText(stocktake.getWarehouseId())) {
            return "盘点仓库需核对";
        }
        return warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(
                        stocktake.getWarehouseId(), stocktake.getFactoryId())
                .map(FactoryWarehouse::getName)
                .filter(this::hasText)
                .orElse("盘点仓库需核对");
    }

    private String resolveWastageCounterparty(WastageReportDTO report) {
        if (report == null) {
            return "报损位置需核对";
        }
        if (hasText(report.getWarehouseId())) {
            return warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(
                            report.getWarehouseId(), report.getFactoryId())
                    .map(FactoryWarehouse::getName)
                    .filter(this::hasText)
                    .orElse("报损仓库需核对");
        }
        if (hasText(report.getTrackType())) {
            return report.getTrackType();
        }
        return "报损位置需核对";
    }

    private String resolveReturnCounterparty(ReturnOrder order) {
        if (order == null || !hasText(order.getCounterpartyId())) {
            return "退货对象需核对";
        }
        String counterpartyId = order.getCounterpartyId();
        String factoryId = order.getFactoryId();
        if (order.getReturnType() == ReturnType.SALES_RETURN) {
            return customerRepository.findByIdAndFactoryId(counterpartyId, factoryId)
                    .map(Customer::getName)
                    .filter(this::hasText)
                    .orElse("客户需核对");
        }
        if (order.getReturnType() == ReturnType.PURCHASE_RETURN) {
            return supplierRepository.findByIdAndFactoryId(counterpartyId, factoryId)
                    .map(Supplier::getName)
                    .filter(this::hasText)
                    .orElse("供应商需核对");
        }
        return "退货对象需核对";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
