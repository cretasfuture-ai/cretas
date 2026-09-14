package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.approval.ExecutionContext;
import com.cretas.aims.dto.inventory.CreateDeliveryRequest;
import com.cretas.aims.dto.inventory.CreateDeliveryShipmentRequest;
import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.inventory.SalesOrderReservationDTO;
import com.cretas.aims.dto.inventory.UpdateSalesOrderRequest;
import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.enums.CustomerSource;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.CustomerStockFulfillmentMode;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.*;
import com.cretas.aims.event.SalesOrderConfirmedEvent;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.annotation.DataScope;
import com.cretas.aims.annotation.Loggable;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.inventory.SalesOrderSuppliedMaterialRequirementService;
import com.cretas.aims.service.inventory.FgQuantityUnitConverter;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.sales.SalesFinishedGoodsOwnershipGuard;
import com.cretas.aims.service.sales.FinishedGoodsBatchSaleabilityGuard;
import com.cretas.aims.service.rules.annotation.RuleEvaluate;
import com.cretas.aims.service.inventory.OverReceiveLimit;
import com.cretas.aims.service.unit.UnitDisplayNames;
import com.cretas.aims.service.workflow.ApprovalWorkflowExecutor;
import com.cretas.aims.service.workflow.SelfApprovalPolicy;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SalesServiceImpl implements SalesService {

    private static final Logger log = LoggerFactory.getLogger(SalesServiceImpl.class);

    private final SalesOrderRepository salesOrderRepository;

    /** 储存位置是受控词表 —— 判据只有一份, 见 WarehouseLocationCatalog。
     *  ⚠️ 用字段注入而不是构造器注入: 本类有两个显式构造器, 且测试里被直接 new 30 处,
     *  改签名会一次打断全部, 与本次改动无关。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.warehouse.WarehouseLocationCatalog locationCatalog;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final SalesDeliveryRecordRepository deliveryRecordRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final CustomerRepository customerRepository;
    private final ProductTypeRepository productTypeRepository;
    private final com.cretas.aims.service.finance.ArApService arApService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ProductPackagingSpecService productPackagingSpecService;

    /** D1 双仓流转 (2026-05-10 spec, PR #309 A1=A) — sales 只从 WH-LOG 出. */
    @org.springframework.beans.factory.annotation.Autowired
    private WarehouseResolver warehouseResolver;

    /** Validates user-selected source warehouse codes against the current factory. */
    @org.springframework.beans.factory.annotation.Autowired
    private FactoryWarehouseRepository factoryWarehouseRepository;

    /** Required in the Spring runtime; field injection preserves historical unit-test constructors. */
    @org.springframework.beans.factory.annotation.Autowired
    private SalesOrderSuppliedMaterialRequirementService suppliedMaterialRequirementService;

    /** P0-13 批次分配校验（可选注入，避免破坏现有构造器）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService batchAllocationService;

    /**
     * FINISHED_TO_SHIPMENT 血缘边接线点（可选注入，理由同上）。
     *
     * <p>🔴 出货边的载体<b>不是</b> {@code shipment_records} —— 那张表在生产库全库 0 行，
     * 写入端点已冻结成 410 GONE（{@code LegacyShipmentWriteFreezeControllerTest}）。
     * 真正承载出货的是 {@code sales_delivery_records}，所以边写在这里（真正扣成品库存的那一步），
     * 节点 id 用 {@code SalesDeliveryItem.deliveryRecordId}，载体在 edge.meta.shipmentCarrier 里标明。
     *
     * <p>⚠️ ⛔ 不要把边写在 {@code SalesDeliveryBatchAllocationServiceImpl} 的分配落库处：
     * 那张表是「先删后写」的可变草稿（重新分配会整行删掉重来），而血缘边 append-only 删不掉 ——
     * 草稿改一次就留下一条永久的假边。发货扣减这一步才是不可逆的事实。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.lineage.LineageEdgeRecorder lineageEdgeRecorder;

    // ==================== 超发上限 (2026-08-28) ====================

    /**
     * 全局超发上限（ops 兜底，全工厂生效）。<b>留空 = 本层不表态</b>，交给阈值中心。
     *
     * <p>⚠️ 默认必须是 {@code null} 而不是 {@code 0.00}：采购侧用 {@code :0.00} 当默认值时，
     * 「没人配过」和「限制 0%」不可区分，生产上按 0% 把货堵在了门口。
     * 见 {@link OverReceiveLimit} 的类注释。
     */
    @org.springframework.beans.factory.annotation.Value("${cretas.sales.over-ship-rate:#{null}}")
    private BigDecimal salesOverShipRateProperty;

    /**
     * 阈值中心读取器（可选注入）。
     *
     * <p>{@code null}（单元测试直接 {@code new SalesServiceImpl(...)}，无 Spring 上下文）时
     * 行为等价于「阈值中心里没有这一行」，<b>不是</b>「限制 0%」。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.canvas.ThresholdResolverService thresholdResolverService;

    /**
     * 解析本次发货实际生效的超发上限。<b>销售链唯一的取值入口。</b>
     *
     * <p>梯子：阈值中心 {@code sales.delivery.over_ship_rate} &gt; 全局属性 &gt; 不限制。
     * 阈值中心自己内部还有一层 per-factory → 全局 {@code '*'} 的解析，本方法不重复实现它。
     *
     * <p>⚠️ 每次确认只调一次（在逐行循环<b>外</b>），避免逐行打 DB / 打缓存。
     *
     * <p>⛔ 不要在别处再写一遍这个梯子 —— 同一个梯子有两份，它一定会漂。
     */
    private OverReceiveLimit resolveOverShipLimit(String factoryId) {
        if (thresholdResolverService != null) {
            // 传 null 当 fallback: 读不到就是读不到, ⛔ 不要兜底成 0
            //（那会把「没配置」翻译成「一件都不许超发」，正是采购侧踩过的那个坑）
            BigDecimal configured = thresholdResolverService.getBigDecimal(
                    factoryId, com.cretas.aims.service.canvas.ThresholdKeys.SALES_DELIVERY_OVER_SHIP_RATE, null);
            if (configured != null) {
                if (configured.signum() < 0) {
                    // 负数是坏配置，不是「不限制」。放行而不是抛，避免一条坏配置把发货全堵死；
                    // 但必须留痕，否则没人知道这条配置是废的。
                    log.warn("SALES_OVER_SHIP_RATE_INVALID: factoryId={}, 配置值为负 ({}), 本次按【不限制】处理",
                            factoryId, configured.toPlainString());
                } else {
                    return OverReceiveLimit.of(configured, OverReceiveLimit.SOURCE_SALES_THRESHOLD_HUB);
                }
            }
        }
        if (salesOverShipRateProperty != null && salesOverShipRateProperty.signum() >= 0) {
            return OverReceiveLimit.of(salesOverShipRateProperty, OverReceiveLimit.SOURCE_SALES_PROPERTY);
        }
        return OverReceiveLimit.unlimited(OverReceiveLimit.SOURCE_SALES_UNCONFIGURED);
    }

    /**
     * 超发被拦时的留痕（2026-08-29）。
     *
     * <p>可选注入：本类有大量 legacy 单测走 4/8 参数构造器，那些上下文没有这个 bean。
     * null 时不留痕，⛔ 但 409 照抛 —— 闸与留痕是两件事，缺一个不该带垮另一个。
     *
     * <p>🔴 它必须是<b>独立的 bean</b>：留痕要跑 {@code REQUIRES_NEW} 才能在
     * {@code warehouseConfirmDelivery} 回滚之后活下来，而 Spring 的事务是代理生效的
     * —— 写成本类的私有方法，propagation 一个字都不起作用且不会报错。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.inventory.SalesExceptionService salesExceptionService;

    /** HACCP 放行闸（可选注入，见 assertHaccpReleaseAllowed）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository haccpMonitoringRecordRepository;

    /** Delivery-line lock access; field injection keeps historical test constructors stable. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SalesDeliveryItemRepository deliveryItemRepository;

    /** 成品预留台账（2026-07-06；可选注入，legacy 单测无此 bean 时降级为不写台账）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.inventory.FgReservationLedgerService reservationLedgerService;

    /**
     * 自动应收挂账的存量切换缝检测。Optional for legacy unit tests。
     *
     * <p>2026-08-28 起挂账幂等由 {@code ArApService#recordReceivableIfAbsent} 按
     * (SALES_DELIVERY, deliveryId) 负责; 这里只用来认出「升级前挂过整单应收」的在飞订单,
     * 对它们保持旧行为以免双重计费。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ArApTransactionRepository arApTransactionRepository;

    /**
     * 自动应收挂账的幂等来源类型 —— 与 DB 唯一键 {@code uk_aat_ar_invoice_per_shipment}
     * (V20261031_06) 的 {@code source_type} 取值一致。对应采购侧的 {@code "PURCHASE_RECEIVE"}。
     */
    private static final String SOURCE_DOC_TYPE_SALES_DELIVERY = "SALES_DELIVERY";

    /** Canvas Config — 可选注入，模块未部署时不影响现有逻辑 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FactoryConfigService factoryConfigService;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /** Canvas V2: DB-driven default values */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DefaultValueResolver defaultValueResolver;

    /** Canvas V2: DB-driven formula engine */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.FormulaEngine formulaEngine;

    /** Canvas V3: Dynamic field persistence (cf_xxx columns) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    /** T3: 业务员双字段过渡 — 用于 resolveSalespersonField */
    @org.springframework.beans.factory.annotation.Autowired
    private UserRepository userRepository;

    /** Sprint4-H F-AR-1: BOM 标准成本查询. Optional — 模块未部署时 cost breakdown 仍可工作 (返 null). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.bom.BomRecipeService bomRecipeService;

    /** SP3: 成本超支阈值解析. Optional — 未注册时超支字段返 null. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.bom.CostVarianceService costVarianceService;

    /**
     * 六扇门 D1: 同口径标准成本解析 (料 + 研发预估标准人工). Optional.
     *
     * <p>修复假阳性超支报警: BOM 标准 (纯料) vs 实际 (含人工) 口径不一致 → 系统性虚高方差。
     * 此服务返回含人工的标准成本, 与含人工的实际成本同口径可比。未注册时回退到纯料 BOM 标准
     * (保留旧行为, 但下方逻辑会优先用 StandardCostService 的同口径值)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.bom.StandardCostService standardCostService;

    /** SP12 实际成本拆分: 订单 → 生产计划. Optional — 未注册时 actualCostSplit 返 null. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.ProductionPlanRepository productionPlanRepository;

    /** SP12 实际成本拆分: 计划 → 生产批次 (取 laborCost/equipmentCost/otherCost). Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.ProductionBatchRepository productionBatchRepository;

    /** SP12 实际成本拆分: 批次 → 领料消耗记录 (逐料 quantity/unitPrice/totalCost). Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.MaterialConsumptionRepository materialConsumptionRepository;

    /** SP12 实际成本拆分: 物料名称/分类/单位查询. Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.RawMaterialTypeRepository rawMaterialTypeRepository;

    /** 六扇门多段成本: 批次 → 半成品段 (SemiFinishedInventory 行, 每行=一次两点报工转化). Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.SemiFinishedInventoryRepository semiFinishedInventoryRepository;

    /** 六扇门多段成本: 半成品段 → IN 流水 (取 reportId 反查 laborCost/materialCost 拆段成本). Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository semiFinishedInventoryTransactionRepository;

    /** 六扇门多段成本: 反查报工 laborCost/materialCost (段内料/人工拆分). Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.ProductionReportRepository productionReportRepository;
    // 注: 段名解析复用已有 final 字段 productTypeRepository (构造注入, line 56)。

    /**
     * Issue #793: 客户协议价 lookup — auto-applies customer-specific or global selling price
     * at SO creation when caller does not explicitly set {@code unitPrice}.
     * Optional injection so unit tests with mocked deps can run without booting price-list module.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.inventory.PriceListService priceListService;

    /**
     * 单位落库归一 —— 「不许英文单位, 用户看到必须是中文」(Steve 2026-08-18).
     *
     * <h3>🔴 为什么销售侧非接不可 (prod 实测 2026-08-18)</h3>
     * {@code sales_order_items.unit} 里躺着 {@code box}×2 / {@code case}×1,
     * {@code sales_delivery_items.unit} 里还有 {@code box}×2。而对应商品
     * 「SOP-20260817-01-黄油鸡-成品800g」的<b>档案单位本来就是中文「盒」</b>,
     * 包装规格也是「箱/盒」—— 英文<b>不是从档案带出来的</b>, 是 web-admin
     * {@code canonicalSalesOrderItemPayload} 在构造请求体时把「盒」正规化成了 {@code box}
     * (前端 {@code UNIT_ALIASES} 的规范码方向与后端<b>相反</b>: 前端 盒→box, 后端 box→盒)。
     *
     * <p>{@link UnitContractService#storageUnit} 的 javadoc 写着「全系统唯一口径,
     * <b>任何写入路径都必须走这里</b>」, 而全仓 5 个调用点里<b>没有一个在销售侧</b> ——
     * 典型的本仓形态 B「机制在、只是没接上」。这里把它接上。
     *
     * <p>⚠️ 只治<b>写入源头</b>。读侧出口翻译已由 PR #2837 上线, ⛔ 不在这里重复翻一遍。
     *
     * <p>Optional 注入: 与本类其它 optional 依赖同 idiom, 未注册时退回原样写入
     * (= 今天的行为, 不会更糟)。真实 Spring 上下文里 {@code UnitContractServiceImpl}
     * 是 {@code @Service}, 由 {@code SalesOrderUnitStorageContractTest} 钉住它确实被接上。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.unit.UnitContractService unitContractService;

    /**
     * 销售下单「发得出货吗」的三档明示 (非阻断). Optional —— 未注册时不出提示。
     * 拦截开关默认关闭, 见 {@code SalesOrderStockAvailabilityService#ENFORCE_GATE_PROPERTY}。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.sales.SalesOrderStockAvailabilityService stockAvailabilityService;

    /**
     * P1 #23 S-CREDIT-1: 客户信用预检 (2026-05-17).
     * SUSPENDED → 硬阻塞; WARNING (exceeds) → 仅 log (soft warn, FE 通过 GET credit-status 预呈现).
     * Optional — 未注册时跳过检查 (不影响现有逻辑).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.crm.CustomerCreditCheckService customerCreditCheckService;

    /**
     * Canvas-Pricing Phase 4b (2026-05-18): 价格策略引擎注入.
     *
     * <p>Optional injection — Phase 4b 模块未部署时 (老 jar) 不影响 SO 创建.
     * 注入存在时, createSalesOrder 在 resolve unitPrice 之后调用 pricingEngine.calculate()
     * 应用 TIERED / PROMOTION / MEMBER / BUNDLE 策略 (CYCLE 是月底批处理不在此路径).
     *
     * <p>Legacy fallback: 注入为 null 时, 走原 priceListService → itemDTO.getUnitPrice() 路径,
     * 完全向前兼容.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.pricing.PricingEngine pricingEngine;

    /**
     * E-5 毛利红线守卫 (W1 六扇门追溯矩阵 E-5, 2026-06-10).
     *
     * <p>Optional injection — 未部署时跳过毛利红线检查 (不影响现有逻辑).
     * 注入存在时, createSalesOrder 在 resolve unitPrice 后对每行逐一检查,
     * belowRedline=true → 返回 200 + marginWarnings 列表 (warn 非阻断, per PR #693 / DECISIONS-5).
     * belowRedline=null (成本未配置) → WARN log + 放行 (不假装拦截, 不静默放行).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.pricing.GrossMarginRedlineService grossMarginRedlineService;

    /**
     * P1 #33 销售价 ≥ 研发预估价 跨流校验守卫 (G流 SP10 → E流销售订单, 2026-06-11).
     *
     * <p>Optional injection — 未部署时跳过估价校验 (不影响现有逻辑).
     * 注入存在时, createSalesOrder 在 resolve unitPrice 后对每行逐一检查,
     * belowEstimate=true → 收集预警到 priceWarnings (非阻断, 200 + 警告, 决策对齐 #693 防呆).
     * belowEstimate=null (研发未报价) → 诚实跳过 (不警告, 不伪造).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.pricing.EstimatePriceCheckService estimatePriceCheckService;

    /** Sprint 6 W2-B: 数据权限解析器 (optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataScopeResolver dataScopeResolver;

    /** T126 Phase 1: 成品库存调整日志 Repository (optional for test ctor). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;

    /**
     * 2026-08-12: 卖原料/辅料/包材时从【物料批次】扣, 不是成品批次 (optional for test ctor)。
     *
     * <p>Steve/六膳门张权:「有啥不能卖的 给钱 我都能卖」。销售订单可以选到物料字典里的条目,
     * 而那些货在 material_batches 里 —— LIUSHANMEN 实测成品批次 12 条 / 物料批次 92 条。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.RawMaterialTypeRepository salesRawMaterialTypeRepository;

    /** 同上: 物料 FIFO 与扣减原语 (getFIFOBatches / useBatchQuantity)。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.MaterialBatchService salesMaterialBatchService;

    /**
     * 「这一行卖的是成品还是物料」的<b>唯一</b>判定处 (2026-08-19)。
     *
     * <p>财审联动那条路 ({@code SupplyChainOrchestrator}) 调的是同一个 bean ——
     * 它原来没有这个判据, 于是物料行去建生产计划撞了外键。⛔ 不要在本类里再抄一份。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.sales.SellableLineDomainResolver sellableLineDomainResolver;

    /** N9: configurable sales approval threshold via legacy approval chain + graph workflow. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ApprovalChainService approvalChainService;

    /** N9: optional graph lookup; absence preserves legacy sales flow in tests/partial deployments. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ApprovalWorkflowService approvalWorkflowService;

    /** N9: persisted workflow engine for factories that published SALES_ORDER_APPROVAL workflows. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    /**
     * 「发起人能否审批自己的单」的唯一判定处 —— 与采购单、调拨单共用同一份语义。
     * 此前本处无条件禁止自审, 而采购单有私有例外, 同一条规则两种行为。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SelfApprovalPolicy selfApprovalPolicy;

    /** Legacy in-memory executor retained for compatibility with older partial deployments. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ApprovalWorkflowExecutor approvalWorkflowExecutor;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    public SalesServiceImpl(SalesOrderRepository salesOrderRepository,
                            SalesOrderItemRepository salesOrderItemRepository,
                            SalesDeliveryRecordRepository deliveryRecordRepository,
                            FinishedGoodsBatchRepository finishedGoodsBatchRepository,
                            CustomerRepository customerRepository,
                            ProductTypeRepository productTypeRepository,
                            com.cretas.aims.service.finance.ArApService arApService,
                            ApplicationEventPublisher applicationEventPublisher,
                            ProductPackagingSpecService productPackagingSpecService) {
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.deliveryRecordRepository = deliveryRecordRepository;
        this.finishedGoodsBatchRepository = finishedGoodsBatchRepository;
        this.customerRepository = customerRepository;
        this.productTypeRepository = productTypeRepository;
        this.arApService = arApService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.productPackagingSpecService = productPackagingSpecService;
    }

    /** Legacy unit-test constructor. Spring production wiring uses the required 9-argument constructor. */
    public SalesServiceImpl(SalesOrderRepository salesOrderRepository,
                            SalesOrderItemRepository salesOrderItemRepository,
                            SalesDeliveryRecordRepository deliveryRecordRepository,
                            FinishedGoodsBatchRepository finishedGoodsBatchRepository,
                            CustomerRepository customerRepository,
                            ProductTypeRepository productTypeRepository,
                            com.cretas.aims.service.finance.ArApService arApService,
                            ApplicationEventPublisher applicationEventPublisher) {
        this(salesOrderRepository, salesOrderItemRepository, deliveryRecordRepository,
                finishedGoodsBatchRepository, customerRepository, productTypeRepository,
                arApService, applicationEventPublisher, null);
    }

    // ==================== 销售订单 ====================

    /**
     * Phase 4a follow-up (issue #38) — @RuleEvaluate attached to fire ORDER scope rules.
     *
     * <p>Method signature has POJO arg {@code request} (CreateSalesOrderRequest), so direct
     * annotation works (matches MaterialBatchServiceImpl.createMaterialBatch pattern, no
     * wrap helper needed). Aspect's {@code target="request"} binding resolves to the DTO.
     *
     * <p>If REJECT result: {@link com.cretas.aims.service.rules.RuleViolationException}
     * propagates → caught by GlobalExceptionHandler → HTTP 400 with actionHint + severity.
     */
    @Override
    @RuleEvaluate(value = "ORDER", target = "request")
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "CREATE", entityType = "SalesOrder",
              summary = "'创建销售单 客户=' + #request.customerId")
    public SalesOrder createSalesOrder(String factoryId, CreateSalesOrderRequest request, Long userId) {
        validateSupplyContract(
                request.getProcessingMode(), request.getMaterialSupplyMode(), request.getItems());
        assertItemsAreSellable(factoryId, request.getItems());
        validateSuppliedMaterialPayload(
                request.getProcessingMode(), request.getMaterialSupplyMode(),
                normalizeCustomerStockFulfillmentMode(request.getCustomerStockFulfillmentMode()),
                request.getSuppliedMaterials(), false);
        // Canvas V2: DB-driven validation — pre-compute totalAmount so rules can reference it
        BigDecimal preComputedTotal = BigDecimal.ZERO;
        Set<String> seenProductIdsForCheck = new HashSet<>();
        boolean hasDuplicate = false;
        if (request.getItems() != null) {
            for (CreateSalesOrderRequest.SalesOrderItemDTO itemDTO : request.getItems()) {
                if (itemDTO.getProductTypeId() != null && !seenProductIdsForCheck.add(itemDTO.getProductTypeId())) {
                    hasDuplicate = true;
                }
                if (itemDTO.getQuantity() != null && itemDTO.getUnitPrice() != null) {
                    BigDecimal discountRate = itemDTO.getDiscountRate() != null ? itemDTO.getDiscountRate() : BigDecimal.ZERO;
                    BigDecimal line = itemDTO.getQuantity().multiply(itemDTO.getUnitPrice())
                            .multiply(BigDecimal.ONE.subtract(discountRate));
                    preComputedTotal = preComputedTotal.add(line);
                }
            }
        }
        runConfiguredValidation(factoryId, "CREATE", Map.of(
                "itemCount", request.getItems() != null ? request.getItems().size() : 0,
                "totalAmount", preComputedTotal,
                "hasDuplicateProduct", hasDuplicate));

        // 验证客户 (Sprint 4 W2 S-INVOICE-CLIENT-1: 捕获 customer 用于 default prefill)
        com.cretas.aims.entity.Customer customer = customerRepository
                .findByIdAndFactoryId(request.getCustomerId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("客户不存在或不属于当前组织"));

        // P1 #23 S-CREDIT-1: 创建前信用预检 (防呆 R1 + R2 + R5)
        // SUSPENDED → 硬阻塞 409; WARNING (exceeds) → 仅 log soft warn (后端不阻塞, 前端通过 GET credit-status 预呈现).
        if (customerCreditCheckService != null) {
            try {
                com.cretas.aims.dto.customer.CreditStatusDTO creditStatus =
                        customerCreditCheckService.checkCreditAvailable(
                                factoryId, request.getCustomerId(), preComputedTotal);
                if (creditStatus.getCreditStatus() == com.cretas.aims.entity.enums.CreditStatus.SUSPENDED) {
                    throw new BusinessException(409,
                            "客户 " + customer.getName() + " 信用已被冻结, 无法创建新销售单")
                            .withHint(creditStatus.getSuggestedAction())
                            .withHintTarget("customerId")
                            .withSeverity("ERROR");
                }
                if (creditStatus.isExceeds()) {
                    // soft warn — 仅 log, 不阻塞 (按 backlog "超期警告" 语义, 销售有权强制下单)
                    log.warn("P1-23 信用预警: factoryId={}, customer={}, requested={}, available={}, action={}",
                            factoryId, customer.getName(), preComputedTotal,
                            creditStatus.getAvailable(), creditStatus.getSuggestedAction());
                }
            } catch (BusinessException be) {
                // 信用阻塞 — 重新抛出
                throw be;
            } catch (Exception e) {
                // 检查本身失败 (e.g. customer 不存在 — 但上面已 fetch) — 仅 log, 不阻塞 SO 创建
                log.warn("P1-23 信用检查失败 (跳过, 不阻塞 SO): factoryId={}, customerId={}, error={}",
                        factoryId, request.getCustomerId(), e.getMessage());
            }
        }

        String orderNumber = generateSalesOrderNumber(factoryId);

        SalesOrder order = new SalesOrder();
        order.setFactoryId(factoryId);
        order.setOrderNumber(orderNumber);
        order.setCustomerId(request.getCustomerId());
        order.setOrderDate(request.getOrderDate() != null ? request.getOrderDate() : java.time.LocalDate.now());
        order.setRequiredDeliveryDate(request.getRequiredDeliveryDate());
        order.setDeliveryAddress(request.getDeliveryAddress());
        order.setDiscountAmount(request.getDiscountAmount() != null ? request.getDiscountAmount() : BigDecimal.ZERO);
        order.setRemark(request.getRemark());
        resolveSalespersonField(order, request.getSalesperson(), factoryId);
        order.setShippingIncluded(request.getShippingIncluded());
        order.setShippingFee(request.getShippingFee());
        order.setExtraFees(request.getExtraFees());
        order.setQuoteId(request.getQuoteId()); // 报价→订单联动 (5016s 客户流程文档)
        order.setStatus(SalesOrderStatus.DRAFT);
        // P3 多仓: 外部渠道采购单标题 (如 "0601-熟食T+2"). Nullable.
        order.setExternalOrderTitle(request.getExternalOrderTitle());
        order.setProcessingMode(request.getProcessingMode());
        order.setMaterialSupplyMode(request.getMaterialSupplyMode());
        order.setCustomerStockFulfillmentMode(
                normalizeCustomerStockFulfillmentMode(request.getCustomerStockFulfillmentMode()));
        order.setCreatedBy(userId);

        // Sprint 4 W2 S-INVOICE-CLIENT-1 Option 3 三层 default 链 — 第 1→2 层 prefill:
        // 请求显式 > 客户级 default. SO 上的值会在 Item 创建时继续下放 (第 2→3 层).
        order.setDefaultTaxRate(request.getDefaultTaxRate() != null
                ? request.getDefaultTaxRate()
                : customer.getDefaultTaxRate());
        order.setDefaultInvoiceType(request.getDefaultInvoiceType() != null
                ? request.getDefaultInvoiceType()
                : customer.getDefaultInvoiceType());

        order = salesOrderRepository.save(order);

        // SKU 重复校验
        Set<String> seenProductIds = new HashSet<>();
        for (CreateSalesOrderRequest.SalesOrderItemDTO itemDTO : request.getItems()) {
            if (!seenProductIds.add(itemDTO.getProductTypeId())) {
                throw new BusinessException(409, "同一订单不能添加重复的产品: " + (itemDTO.getProductName() != null ? itemDTO.getProductName() : itemDTO.getProductTypeId()))
                        .withHint("请合并相同产品行, 或选择其他产品").withHintTarget("productTypeId");
            }
        }

        // 创建行项目
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<SalesOrderItem> items = new ArrayList<>();

        // E-5 毛利红线: 收集低于红线的预警文案 (非阻断 — 决策修正 2026-06-10).
        // 仅含"低于毛利红线"语义文案 (GrossMarginCheckResult 保证不含成本数值), 随订单返回前端展示.
        List<String> marginWarnings = new ArrayList<>();

        // P1 #33 销售价 ≥ 研发预估价: 收集低于研发预估价的预警文案 (非阻断 — 决策对齐 #693).
        // 仅含"低于研发预估价"语义文案 (EstimatePriceCheckResult 保证不含预估价数值), 随订单返回.
        List<String> priceWarnings = new ArrayList<>();

        for (CreateSalesOrderRequest.SalesOrderItemDTO itemDTO : request.getItems()) {
            SalesOrderItem item = new SalesOrderItem();
            item.setSalesOrderId(order.getId());
            item.setProductTypeId(itemDTO.getProductTypeId());
            // 自动填充产品名称
            String productName = itemDTO.getProductName();
            if (productName == null || productName.isBlank()) {
                productName = resolveSellableName(factoryId, itemDTO.getProductTypeId());
            }
            item.setProductName(productName);
            item.setQuantity(itemDTO.getQuantity());
            // 写入点 1/5 (createSalesOrder, 客户端入参) — 见 storageUnit() javadoc
            item.setUnit(storageUnit(factoryId, itemDTO.getUnit()));
            applyPackagingSelection(factoryId, itemDTO, item);
            // Issue #793: auto-apply 客户协议价 when caller did not provide explicit unitPrice.
            // Resolution: customer-specific selling price → global selling price → caller's value.
            // User-provided price always wins (per May 7 part2 L284-303 — manual override allowed).
            BigDecimal resolvedUnitPrice = itemDTO.getUnitPrice();
            if (priceListService != null
                    && (resolvedUnitPrice == null || resolvedUnitPrice.signum() == 0)) {
                BigDecimal contractPrice = priceListService.findActivePriceForOrder(
                        factoryId,
                        request.getCustomerId(),
                        itemDTO.getProductTypeId(),
                        order.getOrderDate()).orElse(null);
                if (contractPrice != null) {
                    resolvedUnitPrice = contractPrice;
                    log.info("Issue #793: auto-applied 协议价 — SO={}, customerId={}, productId={}, "
                            + "price={}", order.getOrderNumber(), request.getCustomerId(),
                            itemDTO.getProductTypeId(), contractPrice);
                }
            }
            // Canvas-Pricing Phase 4b (2026-05-18): apply 价格策略 (TIERED / PROMOTION / MEMBER / BUNDLE).
            // 此处用 calculate() 写 PricingApplicationLog (审计 audit), CYCLE 策略由月底批处理路径处理.
            // pricingEngine 为 null 时 (老 jar 部署), 完全跳过 — 走原 resolvedUnitPrice (legacy fallback).
            if (pricingEngine != null
                    && resolvedUnitPrice != null
                    && resolvedUnitPrice.signum() > 0
                    && itemDTO.getQuantity() != null
                    && itemDTO.getQuantity().signum() > 0) {
                try {
                    // 查 productCategory (scope_filter 用)
                    String productCategory = productTypeRepository.findById(itemDTO.getProductTypeId())
                            .map(ProductType::getCategory).orElse(null);
                    // 客户分组 — Customer.importance enum (per Sprint 4 W2 spec)
                    String customerGroup = customer.getImportance() != null
                            ? customer.getImportance().name() : null;
                    // customerId: PricingRequest 期望 Long, Customer.id 是 String (length=191).
                    // 仅在 ID 为纯数字时 parse, 否则传 null (MEMBER 策略仍可走 customerGroup 路径).
                    Long customerIdLong = null;
                    if (request.getCustomerId() != null) {
                        try {
                            customerIdLong = Long.parseLong(request.getCustomerId());
                        } catch (NumberFormatException ignored) {
                            // Customer.id 是 String hash/UUID — pricingEngine 仅用作 audit log 引用,
                            // null 不阻断 strategy 应用 (per PricingRequest customerId 字段 javadoc).
                        }
                    }
                    // SP5 F3: ProductType.standardCost is now available (V20260910_43 migration).
                    // Pass it as costEstimate so PricingEngineImpl.checkWarnings() can surface
                    // below-cost warnings. Null guard: if standardCost not configured, pass null
                    // and warning is silently disabled (same as pre-SP5 behaviour).
                    java.math.BigDecimal costEstimateForLine = null;
                    try {
                        costEstimateForLine = productTypeRepository.findById(itemDTO.getProductTypeId())
                                .map(com.cretas.aims.entity.ProductType::getStandardCost)
                                .orElse(null);
                    } catch (Exception e) {
                        log.debug("SP5 F3: failed to resolve standardCost for productType {} — "
                                + "below-cost check disabled for this line: {}",
                                itemDTO.getProductTypeId(), e.getMessage());
                    }
                    com.cretas.aims.service.pricing.PricingRequest pricingReq =
                            com.cretas.aims.service.pricing.PricingRequest.builder()
                                    .factoryId(factoryId)
                                    .productId(itemDTO.getProductTypeId())
                                    // Pass full BigDecimal qty (no intValue truncation) so fractional
                                    // /kg quantities price correctly — engine now takes BigDecimal.
                                    .quantity(itemDTO.getQuantity())
                                    .unitPriceList(resolvedUnitPrice)
                                    .customerId(customerIdLong)
                                    .customerGroup(customerGroup)
                                    .productCategory(productCategory)
                                    .costEstimate(costEstimateForLine)  // SP5 F3: from ProductType.standardCost
                                    .businessEntityType("SO_LINE")
                                    .businessEntityId(order.getOrderNumber()
                                            + "/" + itemDTO.getProductTypeId())
                                    .build();
                    com.cretas.aims.service.pricing.PricingResult pricingResult =
                            pricingEngine.calculate(pricingReq);
                    if (pricingResult != null && pricingResult.getFinalPrice() != null
                            && pricingResult.getFinalPrice().signum() >= 0) {
                        // BUGFIX (2026-05-30, surfaced by F006 UI E2E): PricingResult.finalPrice
                        // is a LINE TOTAL — PricingEngineImpl computes
                        //   originalPrice = unitPriceList × quantity   (line total before strategies)
                        //   finalPrice    = originalPrice − Σ discounts (line total after strategies)
                        // but a SO item stores a PER-UNIT price. Assigning the line total directly to
                        // resolvedUnitPrice made item.unitPrice = qty × price and
                        // order.totalAmount = qty × (qty × price) = qty² × price
                        // (e.g. 276 × ¥50 was stored as unitPrice ¥13,800, total ¥3,808,800).
                        // Convert back to per-unit: perUnit = finalPrice / quantity (HALF_UP, scale 4 —
                        // matches numeric(15,4); scale 2 silently rewrote the posted price).
                        BigDecimal finalPerUnit = lineTotalToUnitPrice(
                                pricingResult.getFinalPrice(), itemDTO.getQuantity(), resolvedUnitPrice);
                        log.info("Canvas-Pricing Phase 4b: 应用策略 — SO={}, productId={}, "
                                + "origUnitPrice={}, lineTotal(orig={}, final={}), finalUnitPrice={}, "
                                + "appliedStrategies={}, warnings={}",
                                order.getOrderNumber(), itemDTO.getProductTypeId(),
                                resolvedUnitPrice, pricingResult.getOriginalPrice(),
                                pricingResult.getFinalPrice(), finalPerUnit,
                                pricingResult.getAppliedStrategies() != null
                                        ? pricingResult.getAppliedStrategies().size() : 0,
                                pricingResult.getWarnings() != null
                                        ? pricingResult.getWarnings().size() : 0);
                        resolvedUnitPrice = finalPerUnit;
                    }
                } catch (UnsupportedOperationException e) {
                    // Phase 4b skeleton — PricingEngineImpl 抛 UnsupportedOp, 跳过应用走 legacy fallback.
                    log.debug("Phase 4b skeleton: pricingEngine.calculate 抛 UnsupportedOp, "
                            + "走 legacy unitPrice — SO={}, error={}", order.getOrderNumber(), e.getMessage());
                } catch (Exception e) {
                    // 异常不阻塞 SO 创建 — log warn 走 legacy fallback.
                    log.warn("Canvas-Pricing Phase 4b: pricingEngine.calculate 失败, "
                            + "走 legacy unitPrice — SO={}, productId={}, error={}",
                            order.getOrderNumber(), itemDTO.getProductTypeId(), e.getMessage());
                }
            }
            item.setUnitPrice(resolvedUnitPrice);

            // ── E-5 毛利红线预警 (W1 追溯矩阵 E-5; 非阻断决策修正 2026-06-10) ──────────────
            // 决策 (SP3 spec §6.3 / SP5 spec §8): 低于红线只"预警"不"卡死"
            //   客户原话: "低于底线提示红色, 不是不允许" → HTTP 200 + 警告字段, 不返回 4xx.
            // 在 unitPrice 最终确定后 (含策略引擎折扣) 才做红线检查, 保证检查的是真实报价.
            // resolvedUnitPrice=null 表示未填价格 → 跳过 (BOM/价格缺失不是红线语义).
            if (grossMarginRedlineService != null && resolvedUnitPrice != null
                    && resolvedUnitPrice.signum() > 0
                    && itemDTO.getProductTypeId() != null) {
                com.cretas.aims.dto.pricing.GrossMarginCheckResult marginResult =
                        grossMarginRedlineService.checkMargin(
                                factoryId, itemDTO.getProductTypeId(), resolvedUnitPrice);
                if (Boolean.TRUE.equals(marginResult.getBelowRedline())) {
                    // 低于毛利红线 → 收集预警 (不阻断). 文案不含成本数值 (GrossMarginCheckResult 保证).
                    String displayName = item.getProductName() != null
                            ? item.getProductName() : itemDTO.getProductTypeId();
                    String warn = "行项目「" + displayName + "」" + marginResult.getWarningMessage()
                            + " — 如需继续, 请向销售经理确认";
                    marginWarnings.add(warn);
                    log.info("E-5 毛利红线预警 (非阻断): SO 行 productTypeId={} 低于红线 — {} (订单仍创建)",
                            itemDTO.getProductTypeId(), marginResult.getWarningMessage());
                } else if (marginResult.getBelowRedline() == null) {
                    // 成本数据缺失 / 未配置毛利红线 → WARN log + 放行
                    // 按"禁止降级"原则: 不静默放行也不假装拦截; 明确 WARN 让运营可见配置缺口
                    log.warn("E-5 毛利红线: 无法核算 productTypeId={} 报价 ¥{} — {} (SO 仍创建, 请补配置标准成本/毛利率)",
                            itemDTO.getProductTypeId(), resolvedUnitPrice.toPlainString(),
                            marginResult.getWarningMessage());
                }
                // belowRedline=false → 报价合规, 静默
            }
            // ── end E-5 毛利红线预警 ────────────────────────────────────────────────────

            // ── P1 #33 销售价 ≥ 研发预估价 跨流校验 (G流 SP10 → E流; 非阻断 2026-06-11) ──────
            // 转录需求: 销售订单价不应低于研发预估价 (SP10 QuotationTask 建议售价).
            // 决策对齐 #693: 低于预估价 = 警告放行 (200 + priceWarnings), 不 409 硬拦
            //   (防呆 Rule 1: 提交前看到边界, 不提交后被拒).
            // 在 unitPrice 最终确定后 (含策略引擎折扣) 才校验, 保证比较真实报价.
            // resolvedUnitPrice=null/0 → 跳过 (未填价不是"低于预估价"语义, 与 E-5 守卫一致).
            if (estimatePriceCheckService != null && resolvedUnitPrice != null
                    && resolvedUnitPrice.signum() > 0
                    && itemDTO.getProductTypeId() != null) {
                com.cretas.aims.dto.pricing.EstimatePriceCheckResult estimateResult =
                        estimatePriceCheckService.checkAgainstEstimate(
                                factoryId, itemDTO.getProductTypeId(), resolvedUnitPrice);
                if (Boolean.TRUE.equals(estimateResult.getBelowEstimate())) {
                    // 低于研发预估价 → 收集预警 (不阻断). 文案不含预估价数值 (EstimatePriceCheckResult 保证).
                    String displayName = item.getProductName() != null
                            ? item.getProductName() : itemDTO.getProductTypeId();
                    String warn = "行项目「" + displayName + "」" + estimateResult.getWarningMessage()
                            + " — 如需继续, 请向销售经理确认";
                    priceWarnings.add(warn);
                    log.info("#33 估价预警 (非阻断): SO 行 productTypeId={} 低于研发预估价 — {} (订单仍创建)",
                            itemDTO.getProductTypeId(), estimateResult.getWarningMessage());
                }
                // belowEstimate=null (研发未报价) → 诚实跳过, 不警告不伪造 (无 WARN log: 大量产品本就无 R&D 报价)
                // belowEstimate=false → 报价合规, 静默
            }
            // ── end P1 #33 估价校验 ─────────────────────────────────────────────────────

            // Sprint 4 W2 S-INVOICE-CLIENT-1 Option 3 三层 default 链 — 第 2→3 层 prefill:
            // Item 显式 > SO defaultTaxRate (已 prefill 自客户) > 0. 用户改 SO 级 default 触发批量重算
            // 由前端 watch 处理, 后端只兜底防 NPE.
            BigDecimal effectiveTaxRate = itemDTO.getTaxRate();
            if (effectiveTaxRate == null) {
                effectiveTaxRate = order.getDefaultTaxRate() != null ? order.getDefaultTaxRate() : BigDecimal.ZERO;
            }
            item.setTaxRate(effectiveTaxRate);
            item.setDiscountRate(itemDTO.getDiscountRate() != null ? itemDTO.getDiscountRate() : BigDecimal.ZERO);
            item.setRemark(itemDTO.getRemark());
            applyLineDisplaySnapshots(itemDTO, item);
            item.setSourceWarehouseCode(normalizeSourceWarehouseCode(
                    factoryId, itemDTO.getSourceWarehouseCode()));
            item.setProcessingMode(request.getProcessingMode());
            item.setMaterialSupplyMode(request.getMaterialSupplyMode());
            // P3 多仓字段 (V20260915_01) — 全 nullable, 普通订单不传不影响现有逻辑
            item.setDestWarehouseName(itemDTO.getDestWarehouseName());
            item.setDestWarehouseCode(itemDTO.getDestWarehouseCode());
            item.setExternalPoId(itemDTO.getExternalPoId());
            item.setBarcode(itemDTO.getBarcode());
            item.setAppointmentTime(itemDTO.getAppointmentTime());
            item.setRequiredArrivalDate(itemDTO.getRequiredArrivalDate());
            items.add(item);

            // E-2 空价草稿: unitPrice 为 null 时 calculateLineAmount 返 null,
            // 该行对 totalAmount 贡献 0 (FIX-E2 已允许 totalAmount=0 草稿; 提审时强制有价).
            // 不 null-coalesce 会在此 NPE (BigDecimal.add(null)).
            BigDecimal lineAmount = calculateLineAmount(factoryId, item);
            totalAmount = totalAmount.add(lineAmount != null ? lineAmount : BigDecimal.ZERO);
        }

        salesOrderItemRepository.saveAll(items);

        List<SalesOrderSuppliedMaterialRequirement> suppliedMaterials = List.of();
        if (request.getSuppliedMaterials() != null && !request.getSuppliedMaterials().isEmpty()) {
            suppliedMaterials = requireSuppliedMaterialRequirementService()
                    .createForOrder(order, request.getSuppliedMaterials());
        }
        order.setSuppliedMaterials(new ArrayList<>(suppliedMaterials));

        order.setTotalAmount(totalAmount);
        // SP4: tax_amount = Σ (lineAmountWithTax − lineAmount) across items.
        // 使用减法兜底防止 ×(1+rate) 舍入裂缝 (spec §2.4 "税额减法兜底").
        // 向后兼容: item.taxRate=null/0 时 lineAmountWithTax==lineAmount, 贡献 0.
        BigDecimal taxAmountSum = items.stream()
                .map(it -> {
                    BigDecimal la = it.getLineAmount();
                    BigDecimal lawt = it.getLineAmountWithTax();
                    if (la == null || lawt == null) return BigDecimal.ZERO;
                    return lawt.subtract(la);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        order.setTaxAmount(taxAmountSum);
        order = salesOrderRepository.save(order);

        // Canvas V3: Persist dynamic custom fields via DynamicFieldService
        // (dual-track: JPA columns above + dynamic cf_xxx columns here)
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "sales_order", order.getId(), request.getCustomFields());
                log.info("Persisted {} custom fields for sales order {}",
                        request.getCustomFields().size(), order.getId());
            } catch (Exception e) {
                // Non-blocking: log but don't rollback the order
                log.warn("Failed to persist custom fields for sales order {}: {}", order.getId(), e.getMessage());
            }
        }

        // E-5: 把收集到的毛利红线预警附到返回订单上 (非阻断, 随 HTTP 200 返回, 前端展示 sticky warning).
        order.setMarginWarnings(marginWarnings);
        if (!marginWarnings.isEmpty()) {
            log.info("创建销售订单: factoryId={}, orderNumber={}, 含 {} 条毛利红线预警 (非阻断)",
                    factoryId, orderNumber, marginWarnings.size());
        }

        // P1 #33: 把收集到的研发预估价预警附到返回订单上 (非阻断, 随 HTTP 200 返回, 前端 sticky warning).
        order.setPriceWarnings(priceWarnings);
        if (!priceWarnings.isEmpty()) {
            log.info("创建销售订单: factoryId={}, orderNumber={}, 含 {} 条研发预估价预警 (非阻断)",
                    factoryId, orderNumber, priceWarnings.size());
        }

        // 「发得出货吗」三档明示 (非阻断, 第一步只提示不拦截) — 同 priceWarnings 通道。
        List<String> stockAdvisories = collectStockAdvisories(factoryId, request.getItems());
        order.setStockAdvisories(stockAdvisories);
        if (!stockAdvisories.isEmpty()) {
            log.info("创建销售订单: factoryId={}, orderNumber={}, 含 {} 条库存可发性提示 (非阻断)",
                    factoryId, orderNumber, stockAdvisories.size());
        }

        log.info("创建销售订单: factoryId={}, orderNumber={}, items={}", factoryId, orderNumber, items.size());

        // Round 4 Fix P1-14: publish SalesOrderCreatedEvent so trigger chains can react
        // (e.g. snapshot market price, auto-schedule production, notify sales manager).
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.SalesOrderCreatedEvent(
                this, factoryId, order.getId(),
                order.getCustomerId(), order.getTotalAmount()));
            log.info("已发布 SalesOrderCreatedEvent: SO={}", order.getId());
        } catch (Exception e) {
            log.error("发布 SalesOrderCreatedEvent 失败: {}", e.getMessage(), e);
        }

        return order;
    }

    @Override
    @Transactional(readOnly = true)
    public SalesOrder getSalesOrderById(String factoryId, String orderId) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在"));
        if (!order.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权访问该销售订单")
                    .withHint("当前销售订单不属于该工厂, 无法访问");
        }
        org.hibernate.Hibernate.initialize(order.getSuppliedMaterials());
        return order;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesOrderReservationDTO> getActiveReservations(String factoryId, String orderId) {
        getSalesOrderById(factoryId, orderId);
        if (reservationLedgerService == null) {
            throw new BusinessException(503, "成品预留服务暂不可用")
                    .withCode("FG_RESERVATION_SERVICE_UNAVAILABLE")
                    .withHint("请稍后刷新订单详情；不要改用普通库存代替客户预留库存");
        }
        return reservationLedgerService.listActiveReservations(factoryId, orderId);
    }

    @Override
    public PageResponse<SalesOrder> getSalesOrders(String factoryId, int page, int size) {
        return getSalesOrders(factoryId, null, page, size);
    }

    @Override
    @DataScope("created_by")  // Sprint 5 Track G POC + Sprint 6 W2-B sweep (SELF+keyword DB-side + chain)
    public PageResponse<SalesOrder> getSalesOrders(String factoryId, String keyword, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<SalesOrder> result;

        // Sprint 5 POC + Sprint 6 W2-B sweep:
        // - SELF        → findByCreatedBy / searchByKeywordAndCreatedBy (DB-side, no in-memory)
        // - SELF_AND_BELOW / DEPT_AND_BELOW → resolveCreatedByChain + IN-list query
        // - ALL / CUSTOM / no-ctx → 原 path
        DataScopeContext dsCtx = DataScopeContext.current();
        com.cretas.aims.entity.enums.DataScope scope = (dsCtx != null && dsCtx.getUserId() != null)
                ? dsCtx.getScope() : null;
        boolean selfScope = scope == com.cretas.aims.entity.enums.DataScope.SELF;
        boolean chainScope = scope == com.cretas.aims.entity.enums.DataScope.SELF_AND_BELOW
                || scope == com.cretas.aims.entity.enums.DataScope.DEPT_AND_BELOW;
        List<Long> chain = null;
        if (chainScope && dsCtx != null) {
            chain = dataScopeResolver != null
                    ? dataScopeResolver.resolveCreatedByChain(dsCtx)
                    : List.of(dsCtx.getUserId());
            if (chain == null || chain.isEmpty()) chain = List.of(dsCtx.getUserId());
        }

        if (keyword != null && !keyword.isBlank()) {
            // Bug G + audit H1: escape SQL LIKE wildcards (% _ \) so user-typed wildcards
            // become literal characters. Repository uses ESCAPE '\' clause.
            String escaped = keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
            if (selfScope) {
                log.debug("DataScope SELF + keyword (DB-side): created_by={}", dsCtx.getUserId());
                result = salesOrderRepository.searchByFactoryAndKeywordAndCreatedBy(
                        factoryId, escaped, dsCtx.getUserId(), pageRequest);
            } else if (chainScope) {
                log.debug("DataScope {} + keyword: chain size={}", scope, chain.size());
                result = salesOrderRepository.searchByFactoryAndKeywordAndCreatedByIn(
                        factoryId, escaped, chain, pageRequest);
            } else {
                result = salesOrderRepository.searchByFactoryAndKeyword(factoryId, escaped, pageRequest);
            }
        } else if (selfScope) {
            log.debug("DataScope SELF: filter sales orders by created_by={}", dsCtx.getUserId());
            result = salesOrderRepository.findByFactoryIdAndCreatedByOrderByCreatedAtDesc(
                    factoryId, dsCtx.getUserId(), pageRequest);
        } else if (chainScope) {
            log.debug("DataScope {} for sales orders: chain size={}", scope, chain.size());
            result = salesOrderRepository.findByFactoryIdAndCreatedByInOrderByCreatedAtDesc(
                    factoryId, chain, pageRequest);
        } else {
            result = salesOrderRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
        }
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    public PageResponse<SalesOrder> getSalesOrdersByStatus(String factoryId, SalesOrderStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size);
        Page<SalesOrder> result = salesOrderRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(factoryId, status, pageRequest);
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "CONFIRM", entityType = "SalesOrder",
              entityIdParam = "orderId")
    public SalesOrder confirmOrder(String factoryId, String orderId, Long initiatorUserId) {
        SalesOrder order = salesOrderRepository.findByIdAndFactoryIdForUpdate(orderId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在: " + orderId));
        runConfiguredValidation(factoryId, "STATUS_CHANGE", Map.of(
                "status", order.getStatus().name(), "targetStatus", "CONFIRMED"));
        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw new BusinessException(409, "只有草稿状态的订单可以确认")
                    .withHint("请刷新订单列表查看最新状态");
        }
        checkTransitionAllowed(factoryId, order.getStatus().name(), "CONFIRMED");
        order.setStatus(SalesOrderStatus.CONFIRMED);
        order.setConfirmedAt(LocalDateTime.now());
        SalesOrder saved = salesOrderRepository.save(order);
        log.info("确认销售订单: orderId={}, orderNumber={}", orderId, saved.getOrderNumber());

        SalesOrder routed = routeSalesOrderByApprovalPolicy(
                factoryId, saved, "订单确认后进入统一 OA 审批", initiatorUserId);

        // Only notify after the persisted OA route has been established. A missing or
        // invalid graph must roll the transaction back without dispatching downstream work.
        try {
            applicationEventPublisher.publishEvent(new SalesOrderConfirmedEvent(this, factoryId, routed.getId()));
            log.info("已发布SalesOrderConfirmedEvent(仅通知): SO={}", routed.getId());
        } catch (Exception e) {
            log.error("发布SalesOrderConfirmedEvent失败: SO={}", routed.getId(), e);
        }
        return routed;
    }

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "SUBMIT_FINANCE", entityType = "SalesOrder",
              entityIdParam = "orderId")
    public SalesOrder submitForFinanceReview(String factoryId, String orderId, Long initiatorUserId) {
        SalesOrder order = salesOrderRepository.findByIdAndFactoryIdForUpdate(orderId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在: " + orderId));
        runConfiguredValidation(factoryId, "STATUS_CHANGE", Map.of(
                "status", order.getStatus().name(), "targetStatus", "PENDING_FINANCE_REVIEW"));
        if (order.getStatus() != SalesOrderStatus.CONFIRMED
                && order.getStatus() != SalesOrderStatus.FINANCE_REJECTED) {
            throw new BusinessException(409, "只有已确认或财务驳回状态的订单可以提交财务审核")
                    .withHint("请刷新订单列表查看最新状态");
        }

        return routeSalesOrderByApprovalPolicy(
                factoryId, order, "销售订单重新进入统一 OA 审批", initiatorUserId);
    }

    /**
     * E-2 提审价格守卫 (六扇门追溯矩阵 E-2, 2026-06-10).
     *
     * <p>草稿/确认阶段允许行项目单价为空（下单时可空）。
     * 提交财务审核时，所有行必须有正数单价且订单总额 > 0。
     *
     * <p>fool-proof Rule 1+4位一体: 报错信息精确到行（哪几行缺价）+ actionHint 指导下一步操作。
     */
    private void validatePriceBeforeFinanceReview(String factoryId, String orderId, SalesOrder order) {
        List<SalesOrderItem> items = salesOrderItemRepository.findBySalesOrderId(orderId);
        if (items == null || items.isEmpty()) {
            throw new BusinessException(409,
                    "订单「" + order.getOrderNumber() + "」没有行项目，无法提交财务审核")
                    .withHint("请先添加行项目再提审");
        }

        List<String> missingPriceLines = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            SalesOrderItem item = items.get(i);
            BigDecimal price = item.getUnitPrice();
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                String lineLabel = (item.getProductName() != null && !item.getProductName().isBlank())
                        ? "第" + (i + 1) + "行「" + item.getProductName() + "」"
                        : "第" + (i + 1) + "行（产品ID: " + item.getProductTypeId() + "）";
                missingPriceLines.add(lineLabel);
            }
        }

        if (!missingPriceLines.isEmpty()) {
            String lineDetail = String.join("、", missingPriceLines);
            throw new BusinessException(409,
                    "订单「" + order.getOrderNumber() + "」提审失败：" + lineDetail + " 单价为空——"
                    + "审核时单价不能为空（需求行717）")
                    .withHint("请先补全以上行项目的单价，再提交财务审核");
        }

        // 双重保险: 若所有行都有正价但总额仍为 0（极端边界），也拒绝
        if (order.getTotalAmount() == null || order.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(409,
                    "订单「" + order.getOrderNumber() + "」总金额为 0，无法提交财务审核")
                    .withHint("请检查行项目单价和数量是否正确填写");
        }
    }

    private SalesOrder routeSalesOrderByApprovalPolicy(
            String factoryId, SalesOrder order, String notes, Long initiatorUserId) {
        validatePriceBeforeFinanceReview(factoryId, order.getId(), order);

        Map<String, Object> context = buildSalesApprovalContext(order);
        return routeSalesOrderByWorkflow(factoryId, order, context, notes, initiatorUserId);
    }

    private String buildSalesApprovalDecisionSnapshot(
            String factoryId, SalesOrder order, Map<String, Object> context, boolean requiresApproval) {
        BigDecimal amount = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        if (approvalChainService == null) {
            return "判定方式：未配置销售审批规则；本单未税金额：¥" + amount.toPlainString()
                    + "；判定结果：无需审批";
        }
        Optional<ApprovalChainConfig> matched = approvalChainService.findMatchingConfig(
                factoryId, DecisionType.SALES_ORDER_APPROVAL, context);
        List<ApprovalChainConfig> configured = approvalChainService.getConfigsByDecisionType(
                factoryId, DecisionType.SALES_ORDER_APPROVAL);
        if (matched.isPresent()) {
            ApprovalChainConfig config = matched.get();
            boolean autoApproved = approvalChainService.canAutoApprove(config, context);
            return "判定方式：" + (autoApproved ? "命中免审配置自动通过" : "命中阈值审批")
                    + "；本单未税金额：¥" + amount.toPlainString()
                    + "；配置名称：" + config.getName()
                    + "；配置版本：v" + config.getVersion()
                    + "；审批触发条件：" + readableRule(config.getTriggerCondition())
                    + "；免审配置：" + readableRule(config.getAutoApproveCondition())
                    + "；判定结果：" + (requiresApproval ? "需要财务审批" : "自动通过");
        }
        String configuredRules = configured.isEmpty() ? "无"
                : configured.stream().map(config -> config.getName() + " v" + config.getVersion()
                        + " [触发=" + readableRule(config.getTriggerCondition())
                        + ", 免审=" + readableRule(config.getAutoApproveCondition()) + "]")
                .collect(Collectors.joining("、"));
        return "判定方式：未命中需要审批的阈值规则"
                + "；本单未税金额：¥" + amount.toPlainString()
                + "；现行审批/免审配置：" + configuredRules
                + "；判定结果：自动通过";
    }

    private String readableRule(String rule) {
        return rule == null || rule.isBlank() ? "未配置" : rule;
    }

    private boolean hasActiveSalesWorkflow(String factoryId) {
        return approvalWorkflowService != null
                && approvalWorkflowService.getActiveByDecisionType(
                        factoryId, DecisionType.SALES_ORDER_APPROVAL).isPresent();
    }

    private ApprovalWorkflow requireActiveSalesWorkflow(String factoryId) {
        if (approvalWorkflowService == null || workflowEngine == null) {
            throw new BusinessException(422, "当前工厂未配置已启用的销售订单 OA 审批流程")
                    .withCode("SALES_APPROVAL_WORKFLOW_REQUIRED")
                    .withHint("请先在统一 OA 中发布并启用 SALES_ORDER 审批流程")
                    .withHintTarget("approvalWorkflow");
        }
        return approvalWorkflowService.getActiveByDecisionType(
                        factoryId, DecisionType.SALES_ORDER_APPROVAL)
                .orElseThrow(() -> new BusinessException(422,
                        "当前工厂未配置已启用的销售订单 OA 审批流程")
                        .withCode("SALES_APPROVAL_WORKFLOW_REQUIRED")
                        .withHint("请先在统一 OA 中发布并启用销售订单审批流程")
                        .withHintTarget("approvalWorkflow"));
    }

    private Map<String, Object> buildSalesApprovalContext(SalesOrder order) {
        BigDecimal amount = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        Map<String, Object> context = new HashMap<>();
        context.put("amount", amount);
        context.put("totalAmount", amount);
        context.put("orderId", order.getId());
        context.put("orderNumber", order.getOrderNumber() != null ? order.getOrderNumber() : "");
        context.put("customerId", order.getCustomerId() != null ? order.getCustomerId() : "");
        context.put("customerName", order.getCustomerName() != null ? order.getCustomerName() : "");
        context.put("externalOrderTitle", order.getExternalOrderTitle() != null ? order.getExternalOrderTitle() : "");
        context.put("externalOrder", isExternalChannelOrder(order));
        return context;
    }

    private boolean isExternalChannelOrder(SalesOrder order) {
        if (order == null) {
            return false;
        }
        if (hasText(order.getExternalOrderTitle())) {
            return true;
        }
        Customer customer = null;
        if (customerRepository != null && hasText(order.getCustomerId())) {
            try {
                Optional<Customer> found = customerRepository.findByIdAndFactoryId(order.getCustomerId(), order.getFactoryId());
                if (found != null && found.isPresent()) {
                    customer = found.get();
                }
            } catch (Exception e) {
                log.warn("加载客户渠道信息失败，按普通销售订单处理: orderId={}, customerId={}, error={}",
                        order.getId(), order.getCustomerId(), e.getMessage());
            }
        }
        String customerName = customer != null ? customer.getName() : order.getCustomerName();
        String businessType = customer != null ? customer.getBusinessType() : null;
        String customerType = customer != null ? customer.getCustomerType() : null;
        String type = customer != null ? customer.getType() : null;
        CustomerSource source = customer != null ? customer.getSource() : null;
        return source == CustomerSource.PLATFORM
                || hasExternalChannelToken(customerName)
                || hasExternalChannelToken(businessType)
                || hasExternalChannelToken(customerType)
                || hasExternalChannelToken(type);
    }

    private boolean hasExternalChannelToken(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("external")
                || normalized.contains("channel")
                || normalized.contains("platform")
                || normalized.contains("marketplace")
                || normalized.contains("third_party")
                || normalized.contains("外部")
                || normalized.contains("渠道")
                || normalized.contains("平台");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private SalesOrder routeSalesOrderByWorkflow(String factoryId, SalesOrder order, Map<String, Object> context,
                                                 String notes, Long initiatorUserId) {
        if (workflowEngine == null) {
            throw new BusinessException(503, "审批运行时暂不可用")
                    .withCode("OA_RUNTIME_UNAVAILABLE");
        }
        if (initiatorUserId == null) {
            throw new BusinessException(401, "无法识别销售订单审批发起人")
                    .withCode("SALES_APPROVAL_INITIATOR_REQUIRED");
        }

        Optional<ApprovalWorkflowInstance> instanceLookup = workflowEngine
                .getCurrentInstance(factoryId, "SALES_ORDER", order.getId())
                .filter(existing -> existing.getStatus() == InstanceStatus.RUNNING);
        if (instanceLookup.isEmpty()) {
            instanceLookup = workflowEngine.startWorkflowIfConfigured(
                    factoryId, "SALES_ORDER", order.getId(), context, initiatorUserId);
        }
        if (instanceLookup.isEmpty()) {
            return approveFinanceForOrder(
                    factoryId, order, "当前业务无需审批，系统直接通过", null, initiatorUserId);
        }
        ApprovalWorkflowInstance instance = instanceLookup.get();

        if (instance.getStatus() == InstanceStatus.RUNNING) {
            checkTransitionAllowed(factoryId, order.getStatus().name(), "PENDING_FINANCE_REVIEW");
            order.setStatus(SalesOrderStatus.PENDING_FINANCE_REVIEW);
            order.setFinanceReviewedBy(null);
            order.setFinanceReviewedAt(null);
            order.setFinanceReviewNotes(notes);
            SalesOrder saved = salesOrderRepository.save(order);
            log.info("销售订单进入审批工作流: orderId={}, orderNumber={}, instanceId={}, amount={}",
                    order.getId(), saved.getOrderNumber(), instance.getId(), saved.getTotalAmount());
            return saved;
        }

        if (instance.getStatus() == InstanceStatus.APPROVED) {
            return approveFinanceForOrder(factoryId, order, "销售审批工作流自动通过", null, null);
        }

        if (instance.getStatus() == InstanceStatus.REJECTED) {
            return rejectFinanceForOrder(factoryId, order, "销售审批工作流自动拒绝", null);
        }

        throw new BusinessException(409,
                "销售订单审批工作流未得到有效审批结果: " + instance.getStatus())
                .withHint("请检查销售订单审批工作流配置后重试")
                .withHintTarget("approvalWorkflow");
    }

    private void startSalesApprovalWorkflowIfConfigured(
            String factoryId, SalesOrder order, Map<String, Object> context) {
        if (approvalWorkflowService == null || approvalWorkflowExecutor == null) {
            return;
        }
        Optional<ApprovalWorkflow> workflow = approvalWorkflowService
                .getActiveByDecisionType(factoryId, DecisionType.SALES_ORDER_APPROVAL);
        if (workflow.isEmpty()) {
            return;
        }
        try {
            ExecutionContext execution = approvalWorkflowExecutor.start(
                    workflow.get(), order.getId(), context, order.getCreatedBy());
            log.info("已启动销售订单审批工作流: orderId={}, executionId={}, status={}",
                    order.getId(), execution.getExecutionId(), execution.getStatus());
        } catch (Exception e) {
            throw new BusinessException(409,
                    "销售订单 " + order.getOrderNumber() + " 启动审批流程失败：" + e.getMessage())
                    .withHint("请检查销售订单审批工作流配置后重试")
                    .withHintTarget("approvalWorkflow");
        }
    }

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "OA_ACTION", entityType = "SalesOrder",
              entityIdParam = "orderId")
    public SalesOrder applyWorkflowAction(String factoryId,
                                          String orderId,
                                          String instanceId,
                                          Long actorId,
                                          String actorRole,
                                          HistoryAction action,
                                          String notes) {
        SalesOrder order = salesOrderRepository.findByIdAndFactoryIdForUpdate(orderId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在: " + orderId));
        if (workflowEngine == null) {
            throw new BusinessException(503, "OA 审批服务不可用")
                    .withCode("SALES_APPROVAL_ENGINE_UNAVAILABLE");
        }
        ApprovalWorkflowInstance instance = workflowEngine.getInstance(factoryId, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("OA 审批实例不存在: " + instanceId));
        if (!"SALES_ORDER".equals(instance.getModuleCode())
                || !orderId.equals(instance.getBusinessEntityId())) {
            throw new BusinessException(400, "审批实例与销售订单不匹配")
                    .withCode("SALES_APPROVAL_IDENTITY_MISMATCH");
        }
        if (instance.getStatus() != InstanceStatus.RUNNING) {
            return order;
        }
        if (actorId != null
                && actorId.equals(instance.getInitiatedBy())
                && (selfApprovalPolicy == null
                    || !selfApprovalPolicy.allowsSelfApproval(factoryId, instance, actorId, actorRole))) {
            throw new BusinessException(403, "发起人不能审批自己的销售订单")
                    .withCode("SALES_SELF_APPROVAL_FORBIDDEN")
                    .withHint("请由当前 OA 节点授权的其他审批人处理，或在 Canvas 中明确将发起人配置为该节点审批人");
        }
        if (action == HistoryAction.REJECT && (notes == null || notes.isBlank())) {
            throw new BusinessException(422, "驳回销售订单必须填写原因")
                    .withHintTarget("notes");
        }

        ApprovalWorkflowInstance updated = workflowEngine.transitionNode(
                instanceId, actorId, actorRole, action, notes);
        if (updated.getStatus() == InstanceStatus.RUNNING) {
            if (order.getStatus() != SalesOrderStatus.PENDING_FINANCE_REVIEW) {
                checkTransitionAllowed(factoryId, order.getStatus().name(), "PENDING_FINANCE_REVIEW");
                order.setStatus(SalesOrderStatus.PENDING_FINANCE_REVIEW);
            }
            return salesOrderRepository.save(order);
        }
        if (updated.getStatus() == InstanceStatus.APPROVED) {
            return approveFinanceForOrder(factoryId, order, notes, null, actorId);
        }
        if (updated.getStatus() == InstanceStatus.REJECTED) {
            return rejectFinanceForOrder(factoryId, order, notes, actorId);
        }
        throw new BusinessException(409, "销售订单 OA 审批实例状态无法回写: " + updated.getStatus())
                .withCode("SALES_APPROVAL_STATE_UNSUPPORTED")
                .withHint("请刷新销售订单状态后重试；如持续出现，请在原审批发起处重新提交审批");
    }

    @Override
    @Transactional
    public SalesOrder financeApproveOrder(String factoryId, String orderId, String notes, Long reviewerId) {
        return financeApproveOrder(factoryId, orderId, notes, null, reviewerId);
    }

    /**
     * 六扇门 V1 §2.2 audit fix #6: accept estimatedCost from finance reviewer dialog.
     * Pre-fix: dialog only had notes prompt — finance had no place to record cost.
     * Now: dialog shows totalAmount + cost input + auto-computed profit.
     */
    @Override
    @Transactional
    public SalesOrder financeApproveOrder(String factoryId, String orderId, String notes,
                                           java.math.BigDecimal estimatedCost, Long reviewerId) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        assertNoOaApprovalBypass(factoryId, orderId);
        // 强制加载 items，防止事件处理器中 LazyInitializationException
        org.hibernate.Hibernate.initialize(order.getItems());
        if (order.getStatus() != SalesOrderStatus.PENDING_FINANCE_REVIEW) {
            throw new BusinessException(409, "只有待财务审核状态的订单可以审批")
                    .withHint("请刷新订单列表查看最新状态");
        }
        return approveFinanceForOrder(factoryId, order, notes, estimatedCost, reviewerId);
    }

    private SalesOrder approveFinanceForOrder(String factoryId, SalesOrder order, String notes,
                                              java.math.BigDecimal estimatedCost, Long reviewerId) {
        org.hibernate.Hibernate.initialize(order.getItems());
        // N9 fix (六扇门财审阈值): 免审/阈值未触发的自动通过路径在 CONFIRMED 状态直接调用本方法,
        // 但 sales_order 状态机只有合法边 CONFIRMED → PENDING_FINANCE_REVIEW → FINANCE_APPROVED,
        // 缺少 CONFIRMED → FINANCE_APPROVED 直通边 → checkTransitionAllowed 会 409, 低额订单全卡死 DRAFT.
        // 修法: 自动通过时先走合法中间态 (复用既有边), 不新增状态机 schema 边.
        if (order.getStatus() == SalesOrderStatus.CONFIRMED) {
            checkTransitionAllowed(factoryId, order.getStatus().name(), "PENDING_FINANCE_REVIEW");
            order.setStatus(SalesOrderStatus.PENDING_FINANCE_REVIEW);
        }
        checkTransitionAllowed(factoryId, order.getStatus().name(), "FINANCE_APPROVED");
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        order.setFinanceReviewedBy(reviewerId);
        order.setFinanceReviewedAt(LocalDateTime.now());
        order.setFinanceReviewNotes(notes);

        // 六扇门 V1 §2.2 audit fix #6: persist finance-reviewer-input estimatedCost
        if (estimatedCost != null) {
            order.setEstimatedCost(estimatedCost);
        }

        // 计算预估利润（如果有 estimatedCost — 当前调用或历史值）
        if (order.getEstimatedCost() != null && order.getTotalAmount() != null) {
            order.setEstimatedProfit(order.getTotalAmount().subtract(order.getEstimatedCost()));
        }

        SalesOrder saved = salesOrderRepository.save(order);
        log.info("销售订单财务审核通过: orderId={}, orderNumber={}, reviewerId={}",
                saved.getId(), saved.getOrderNumber(), reviewerId);

        // 发布财务审核通过事件 → 触发供应链联动（库存检查+生产计划+采购建议）
        try {
            applicationEventPublisher.publishEvent(
                    new SalesOrderFinanceApprovedEvent(this, factoryId, saved.getId(), reviewerId));
            log.info("已发布SalesOrderFinanceApprovedEvent: SO={}", saved.getId());
        } catch (Exception e) {
            log.error("发布SalesOrderFinanceApprovedEvent失败(不影响审批): SO={}", saved.getId(), e);
        }

        return saved;
    }

    @Override
    @Transactional
    public SalesOrder financeRejectOrder(String factoryId, String orderId, String reason, Long reviewerId) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        assertNoOaApprovalBypass(factoryId, orderId);
        if (order.getStatus() != SalesOrderStatus.PENDING_FINANCE_REVIEW) {
            throw new BusinessException(409, "只有待财务审核状态的订单可以驳回")
                    .withHint("请刷新订单列表查看最新状态");
        }
        return rejectFinanceForOrder(factoryId, order, reason, reviewerId);
    }

    private SalesOrder rejectFinanceForOrder(
            String factoryId, SalesOrder order, String reason, Long reviewerId) {
        if (order.getStatus() == SalesOrderStatus.CONFIRMED) {
            checkTransitionAllowed(factoryId, order.getStatus().name(), "PENDING_FINANCE_REVIEW");
            order.setStatus(SalesOrderStatus.PENDING_FINANCE_REVIEW);
        }
        checkTransitionAllowed(factoryId, order.getStatus().name(), "FINANCE_REJECTED");
        order.setStatus(SalesOrderStatus.FINANCE_REJECTED);
        order.setFinanceReviewedBy(reviewerId);
        order.setFinanceReviewedAt(LocalDateTime.now());
        order.setFinanceReviewNotes(reason);
        SalesOrder saved = salesOrderRepository.save(order);
        log.info("销售订单财务审核驳回: orderId={}, orderNumber={}, reviewerId={}, reason={}",
                order.getId(), saved.getOrderNumber(), reviewerId, reason);
        return saved;
    }

    private void assertNoOaApprovalBypass(String factoryId, String orderId) {
        boolean activeWorkflowExists = hasActiveSalesWorkflow(factoryId);
        boolean instanceExists = workflowEngine != null
                && workflowEngine.getLatestInstance(factoryId, "SALES_ORDER", orderId).isPresent();
        if (activeWorkflowExists || instanceExists) {
            throw new BusinessException(409, "该销售订单已进入统一 OA，不能从业务页面直接审批")
                    .withCode("SALES_APPROVAL_OA_ONLY")
                    .withHint("请前往 OA 审批中心处理当前待办")
                    .withHintTarget("approvalCenter");
        }
    }

    /**
     * Sprint4-H F-AR-1: 财务成本核算 — 拉 BOM 标准成本 + 当前预估成本 +
     * (订单完成后) 实际生产成本, 自动计算预估利润 vs 实际利润对比.
     */
    @Override
    @Transactional(readOnly = true)
    public com.cretas.aims.dto.inventory.FinanceCostBreakdown getOrderCostBreakdown(
            String factoryId, String orderId) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        // 强制初始化 items (lazy)
        order.getItems().size();

        BigDecimal totalAmount = order.getTotalAmount() != null
                ? order.getTotalAmount()
                : BigDecimal.ZERO;
        BigDecimal estimatedCost = order.getEstimatedCost();
        BigDecimal estimatedProfit = order.getEstimatedProfit();

        BigDecimal bomStandardCostSum = BigDecimal.ZERO;
        BigDecimal actualCostSum = BigDecimal.ZERO;
        boolean anyBomAvailable = false;
        boolean anyActualMissing = false;
        // D1: 订单级标准成本是否全口径含人工 — 任一行标准缺人工 → 不做订单级超支报警 (避免假阳性).
        boolean allStdLaborIncluded = true;

        java.util.List<com.cretas.aims.dto.inventory.FinanceCostBreakdown.LineCostBreakdown> lines =
                new java.util.ArrayList<>();

        for (SalesOrderItem item : order.getItems()) {
            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            BigDecimal sellUnit = item.getUnitPrice();
            BigDecimal costUnit = item.getCostUnitPrice();
            BigDecimal lineAmount = (sellUnit != null)
                    ? qty.multiply(sellUnit).setScale(2, java.math.RoundingMode.HALF_UP)
                    : null;

            // D1: 同口径标准单位成本 — 含研发预估标准人工, 与含人工的实际成本可比.
            //   StandardCostService 返回 totalUnitCost = 料 + 标准人工 (口径不全时诚实 null).
            //   未注册 standardCostService 时回退到纯料 BOM 标准 (旧行为, 但口径偏低 → 下方报警会被
            //   laborIncluded 守卫拦住, 见订单级 + 行级判定)。
            BigDecimal bomUnit = null;
            BigDecimal bomLine = null;
            boolean lineStdLaborIncluded = false;
            if (item.getProductTypeId() != null) {
                try {
                    if (standardCostService != null) {
                        com.cretas.aims.service.bom.StandardCostService.StandardUnitCost std =
                                standardCostService.resolveStandardUnitCost(factoryId, item.getProductTypeId());
                        // 同口径标准 (含人工) 优先; null → 口径不全 (无 BOM/料未定价/缺研发人工) → 诚实留空
                        if (std.getTotalUnitCost() != null
                                && std.getTotalUnitCost().compareTo(BigDecimal.ZERO) > 0) {
                            bomUnit = std.getTotalUnitCost();
                            lineStdLaborIncluded = std.isLaborIncluded();
                        }
                    } else if (bomRecipeService != null) {
                        // 回退: 纯料 BOM 标准 (口径偏低, 不含人工). laborIncluded 留 false → 不误报。
                        java.util.Optional<com.cretas.aims.entity.bom.BomRecipe> recipeOpt =
                                bomRecipeService.getCurrentRecipe(factoryId, item.getProductTypeId());
                        if (recipeOpt.isPresent()) {
                            BigDecimal tc = recipeOpt.get().getTotalCost();
                            // B2 fix: null/<=0 totalCost 表示 BOM 料未定价, 不当 0 (避免误导财务).
                            if (tc != null && tc.compareTo(BigDecimal.ZERO) > 0) {
                                bomUnit = tc;
                            }
                        }
                    }
                    if (bomUnit != null) {
                        bomLine = qty.multiply(bomUnit).setScale(2, java.math.RoundingMode.HALF_UP);
                        bomStandardCostSum = bomStandardCostSum.add(bomLine);
                        anyBomAvailable = true;
                    }
                } catch (Exception e) {
                    log.warn("标准成本查询失败 (productTypeId={}): {}", item.getProductTypeId(), e.getMessage());
                }
            }

            BigDecimal actualLine = null;
            // B2 fix: costUnitPrice=0 means cost was never recorded (default or erroneous data),
            // not that cost is truly zero. Treat 0 same as null → missing.
            if (costUnit != null && costUnit.compareTo(BigDecimal.ZERO) > 0) {
                actualLine = qty.multiply(costUnit).setScale(2, java.math.RoundingMode.HALF_UP);
                actualCostSum = actualCostSum.add(actualLine);
            } else {
                anyActualMissing = true;
            }

            // SP3: 行级超支百分比.
            // D1 守卫: 仅当标准侧含人工 (lineStdLaborIncluded) 才算行级超支 — 否则标准 (纯料) 比
            //   实际 (含人工) 必然虚高超支 = 假阳性, 跳过不报 (variancePct 留 null, 财务看 caliberHint)。
            BigDecimal lineVariancePct = null;
            Boolean lineBelowThreshold = null;
            BigDecimal actualCostPerUnit = (costUnit != null && costUnit.compareTo(BigDecimal.ZERO) > 0) ? costUnit : null;
            // 实际成本存在但标准侧缺人工口径 → 标记订单级口径不全 (用于拦订单级报警)
            if (actualCostPerUnit != null && !lineStdLaborIncluded) {
                allStdLaborIncluded = false;
            }
            if (costVarianceService != null && actualCostPerUnit != null && bomUnit != null
                    && lineStdLaborIncluded) {
                lineVariancePct = costVarianceService.computeVariancePct(actualCostPerUnit, bomUnit);
                if (lineVariancePct != null) {
                    BigDecimal thresh = costVarianceService.resolveThreshold(factoryId, item.getProductTypeId());
                    lineBelowThreshold = lineVariancePct.compareTo(thresh) <= 0;
                }
            }

            lines.add(com.cretas.aims.dto.inventory.FinanceCostBreakdown.LineCostBreakdown.builder()
                    .productId(item.getProductTypeId())
                    .productName(item.getProductName())
                    .quantity(qty)
                    .unitPrice(sellUnit)
                    .lineAmount(lineAmount)
                    .bomStandardUnitCost(bomUnit)
                    .bomStandardLineCost(bomLine)
                    .actualLineCost(actualLine)
                    .standardCostPerUnit(bomUnit)
                    .actualCostPerUnit(actualCostPerUnit)
                    .variancePct(lineVariancePct)
                    .belowThreshold(lineBelowThreshold)
                    .build());
        }

        BigDecimal bomStandardCost = anyBomAvailable ? bomStandardCostSum : null;
        // actualCost: 任一行没有 costUnitPrice 即视为不完整 (订单未完成生产), 返 null
        BigDecimal actualCost = (anyActualMissing || order.getItems().isEmpty())
                ? null
                : actualCostSum;
        BigDecimal actualProfit = (actualCost != null)
                ? totalAmount.subtract(actualCost)
                : null;

        BigDecimal marginEst = null;
        BigDecimal marginAct = null;
        if (totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            if (estimatedProfit != null) {
                marginEst = estimatedProfit
                        .multiply(BigDecimal.valueOf(100))
                        .divide(totalAmount, 2, java.math.RoundingMode.HALF_UP);
            }
            if (actualProfit != null) {
                marginAct = actualProfit
                        .multiply(BigDecimal.valueOf(100))
                        .divide(totalAmount, 2, java.math.RoundingMode.HALF_UP);
            }
        }

        // B2 fix: produce specific actionable hints for each missing-cost scenario.
        StringBuilder hint = new StringBuilder();
        if (bomStandardCost == null) {
            // B2 fix: differentiate between "no BOM" and "BOM items missing unit price"
            hint.append("部分产品的 BOM 标准成本不可用 (无 ACTIVE BOM 配方, 或 BOM 配方中原料单价未配置). ");
            hint.append("请到「配方管理」为 BOM 原料填写单价后重新查看. ");
        }
        if (actualCost == null) {
            hint.append("实际成本暂不可用: 订单行成本单价未录入 (生产批次完工后系统自动回填). ");
        }
        // D1: 标准成本口径不全 (缺研发预估人工) → 提示财务标准成本未含人工, 超支对比已跳过.
        if (bomStandardCost != null && actualCost != null && !allStdLaborIncluded) {
            hint.append("标准成本未含研发预估人工 (部分产品缺 人工成本 元/kg), 已跳过超支对比以避免误报. " +
                    "请在「研发报价」补填人工成本后重新查看同口径超支. ");
        }
        String hintStr = hint.length() > 0 ? hint.toString().trim() : null;

        // SP3: 订单级超支计算.
        // D1 守卫: 仅当全部行标准侧含人工 (allStdLaborIncluded) 才做订单级超支报警 — 否则纯料标准
        //   vs 含人工实际 = 系统性虚高 = 假阳性, 跳过 (variancePct/alarmMessage 留 null)。
        java.math.BigDecimal orderVariancePct = null;
        java.math.BigDecimal orderVarianceAbsolute = null;
        Boolean orderBelowThreshold = null;
        String orderAlarmMessage = null;
        if (costVarianceService != null && actualCost != null && bomStandardCost != null
                && allStdLaborIncluded) {
            orderVariancePct = costVarianceService.computeVariancePct(actualCost, bomStandardCost);
            if (orderVariancePct != null) {
                orderVarianceAbsolute = actualCost.subtract(bomStandardCost);
                // 订单级阈值用第一行产品类型作为 hint (或 null → 工厂全局)
                String firstProductTypeId = order.getItems().isEmpty() ? null
                        : order.getItems().get(0).getProductTypeId();
                java.math.BigDecimal thresh = costVarianceService.resolveThreshold(factoryId, firstProductTypeId);
                orderBelowThreshold = orderVariancePct.compareTo(thresh) <= 0;
                if (Boolean.FALSE.equals(orderBelowThreshold)) {
                    // 通用预警文案 — 不内嵌绝对成本金额或具体百分比，
                    // 避免向无 finance:read 权限的销售角色泄露敏感数值。
                    // variancePct / varianceAbsolute 已在各自 @PriceSensitive 字段独立脱敏。
                    orderAlarmMessage = "成本超出标准阈值，请关注";
                }
            }
        }

        // SP4: 从订单头读 taxAmount (createSalesOrder/updateSalesOrder 已汇总写入)
        // 向后兼容: 旧订单 taxAmount=null → 视为 0 (未配置税率 = 免税)
        BigDecimal soTaxAmount = order.getTaxAmount() != null ? order.getTaxAmount() : BigDecimal.ZERO;
        // SP4: totalAmountWithTax = 未税净额 + 税额 (不含运费 shippingFee / extraFees; 纯价税对)
        BigDecimal totalAmountWithTax = totalAmount.add(soTaxAmount);

        return com.cretas.aims.dto.inventory.FinanceCostBreakdown.builder()
                .totalAmount(totalAmount)
                // SP4: 暴露税额 + 含税双值
                .taxAmount(soTaxAmount)
                .totalAmountWithTax(totalAmountWithTax)
                .bomStandardCost(bomStandardCost)
                .currentEstimatedCost(estimatedCost)
                .currentEstimatedProfit(estimatedProfit)
                .actualCost(actualCost)
                .actualProfit(actualProfit)
                .profitMarginEstimated(marginEst)
                .profitMarginActual(marginAct)
                .dataSourceHint(hintStr)
                .varianceAbsolute(orderVarianceAbsolute)
                .variancePct(orderVariancePct)
                .belowThreshold(orderBelowThreshold)
                .alarmMessage(orderAlarmMessage)
                // P1 #32: 委外加工费独立科目 — 诚实 null.
                // WorkProcess/WorkProcessTask 当前无 isOutsourced + outsourcedFee 列,
                // 委外加工费无生产记录来源 → 不伪造数字, 返 null.
                // 待 WorkProcess 加 is_outsourced 列后接入真实数据.
                .processingFee(null)
                // SP12: 实际成本拆分 (材料逐料 + 人工 + 制费), 来自生产真实领料/报工.
                // 诚实 null: 订单未投产 / 未领料 / 未报工时返 null. 不破坏上面聚合字段.
                .actualCostSplit(buildActualCostSplit(factoryId, orderId))
                .lines(lines)
                .build();
    }

    /**
     * SP12: 构建实际成本三分拆分 (材料逐料 / 人工 / 制费).
     *
     * <p>客户原话 (六扇门 [12:36]): "财务根据前面领料的批次去核算最终的实际成本"。
     * 把单一 actualCost 总额拆解成可核算的组分。
     *
     * <p>数据链: 销售订单 → ProductionPlan(sourceOrderId) → ProductionBatch(planId)
     * → MaterialConsumption(productionBatchId, 逐料) + 批次 laborCost / equipmentCost / otherCost。
     *
     * <p>诚实 null 策略:
     * <ul>
     *   <li>repos 未注册 (单测/模块未部署) → 整个 split 为 null;
     *   <li>订单无关联生产计划 → split 非 null 但各组分 null, dataSourceHint 说明"未投产";
     *   <li>批次无领料 → materialCost null + materials 空;
     *   <li>批次无报工 → laborCost null。
     * </ul>
     * 任何组分缺数据都不伪造数字。
     *
     * @return ActualCostSplit, 或 null (repos 不可用)
     */
    private com.cretas.aims.dto.inventory.FinanceCostBreakdown.ActualCostSplit
            buildActualCostSplit(String factoryId, String orderId) {
        // repos 未注册 → 诚实 null (不伪造)
        if (productionPlanRepository == null || productionBatchRepository == null
                || materialConsumptionRepository == null) {
            return null;
        }

        java.util.List<com.cretas.aims.entity.ProductionPlan> plans;
        try {
            plans = productionPlanRepository.findByFactoryIdAndSourceOrderId(factoryId, orderId);
        } catch (Exception e) {
            log.warn("SP12 实际成本拆分: 查询关联生产计划失败 (orderId={}): {}", orderId, e.getMessage());
            return null;
        }

        if (plans == null || plans.isEmpty()) {
            // 订单未关联生产计划 → 尚未投产, 无真实成本可拆
            return com.cretas.aims.dto.inventory.FinanceCostBreakdown.ActualCostSplit.builder()
                    .batchCount(0)
                    .materials(java.util.Collections.emptyList())
                    .dataSourceHint("订单尚未关联生产计划 (未投产), 暂无实际领料/报工成本可拆分.")
                    .build();
        }

        // 取所有关联计划下的批次
        java.util.Set<String> planIds = new java.util.HashSet<>();
        for (com.cretas.aims.entity.ProductionPlan p : plans) {
            if (p.getId() != null) {
                planIds.add(p.getId());
            }
        }

        java.util.List<com.cretas.aims.entity.ProductionBatch> batches =
                planIds.isEmpty()
                        ? java.util.Collections.emptyList()
                        : productionBatchRepository.findByFactoryIdAndProductionPlanIdIn(factoryId, planIds);

        int batchCount = batches.size();

        // ---- 人工 + 制费: 批次级 laborCost / equipmentCost + otherCost rollup ----
        BigDecimal laborSum = BigDecimal.ZERO;
        boolean anyLabor = false;
        BigDecimal overheadSum = BigDecimal.ZERO;
        boolean anyOverhead = false;
        java.util.List<Long> batchPkIds = new java.util.ArrayList<>();
        for (com.cretas.aims.entity.ProductionBatch b : batches) {
            if (b.getId() != null) {
                batchPkIds.add(b.getId());
            }
            if (b.getLaborCost() != null) {
                laborSum = laborSum.add(b.getLaborCost());
                anyLabor = true;
            }
            // 制费 = 设备成本 + 其他成本
            if (b.getEquipmentCost() != null) {
                overheadSum = overheadSum.add(b.getEquipmentCost());
                anyOverhead = true;
            }
            if (b.getOtherCost() != null) {
                overheadSum = overheadSum.add(b.getOtherCost());
                anyOverhead = true;
            }
        }
        BigDecimal laborCost = anyLabor ? laborSum.setScale(2, java.math.RoundingMode.HALF_UP) : null;
        BigDecimal overheadCost = anyOverhead ? overheadSum.setScale(2, java.math.RoundingMode.HALF_UP) : null;

        // ---- 材料: 逐料聚合 MaterialConsumption (领料消耗记录) ----
        // 按 materialTypeId 聚合: 实际用量 Σquantity, 金额 ΣtotalCost, 移动均价 = 金额/用量
        java.util.Map<String, BigDecimal> qtyByType = new java.util.LinkedHashMap<>();
        java.util.Map<String, BigDecimal> amtByType = new java.util.LinkedHashMap<>();
        // 兜底单价 (用量为 0 时用首个 unitPrice)
        java.util.Map<String, BigDecimal> fallbackUnitPrice = new java.util.HashMap<>();

        for (Long pkId : batchPkIds) {
            java.util.List<com.cretas.aims.entity.MaterialConsumption> cons;
            try {
                cons = materialConsumptionRepository.findByProductionBatchIdAndFactoryId(pkId, factoryId);
            } catch (Exception e) {
                log.warn("SP12 实际成本拆分: 查询批次 {} 领料失败: {}", pkId, e.getMessage());
                continue;
            }
            for (com.cretas.aims.entity.MaterialConsumption c : cons) {
                String typeId = c.getMaterialTypeId();
                if (typeId == null) {
                    // 无物料类型的消耗记录无法逐料归类, 跳过逐料 (但金额仍记到 "未分类")
                    typeId = "__UNCLASSIFIED__";
                }
                BigDecimal q = c.getQuantity() != null ? c.getQuantity() : BigDecimal.ZERO;
                BigDecimal amt = c.getTotalCost() != null ? c.getTotalCost() : BigDecimal.ZERO;
                qtyByType.merge(typeId, q, BigDecimal::add);
                amtByType.merge(typeId, amt, BigDecimal::add);
                if (c.getUnitPrice() != null) {
                    fallbackUnitPrice.putIfAbsent(typeId, c.getUnitPrice());
                }
            }
        }

        java.util.List<com.cretas.aims.dto.inventory.FinanceCostBreakdown.MaterialCostLine> materials =
                new java.util.ArrayList<>();
        BigDecimal materialSum = BigDecimal.ZERO;
        boolean anyMaterial = !amtByType.isEmpty();

        for (java.util.Map.Entry<String, BigDecimal> entry : amtByType.entrySet()) {
            String typeId = entry.getKey();
            BigDecimal amt = entry.getValue().setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal qty = qtyByType.getOrDefault(typeId, BigDecimal.ZERO);
            materialSum = materialSum.add(amt);

            // 移动均价 = 金额 / 用量 (用量 > 0); 否则用首个 unitPrice 兜底
            BigDecimal unitPrice = qty.compareTo(BigDecimal.ZERO) > 0
                    ? amt.divide(qty, 4, java.math.RoundingMode.HALF_UP)
                    : fallbackUnitPrice.get(typeId);

            String name = typeId;
            String category = null;
            String unit = null;
            if (!"__UNCLASSIFIED__".equals(typeId) && rawMaterialTypeRepository != null) {
                try {
                    java.util.Optional<com.cretas.aims.entity.RawMaterialType> rt =
                            rawMaterialTypeRepository.findById(typeId);
                    if (rt.isPresent()) {
                        name = rt.get().getName() != null ? rt.get().getName() : typeId;
                        category = rt.get().getCategory();
                        unit = rt.get().getUnit();
                    }
                } catch (Exception e) {
                    log.warn("SP12 实际成本拆分: 查询物料 {} 失败: {}", typeId, e.getMessage());
                }
            } else if ("__UNCLASSIFIED__".equals(typeId)) {
                name = "未分类领料";
            }

            materials.add(com.cretas.aims.dto.inventory.FinanceCostBreakdown.MaterialCostLine.builder()
                    .materialTypeId("__UNCLASSIFIED__".equals(typeId) ? null : typeId)
                    .materialName(name)
                    .category(category)
                    .actualQuantity(qty)
                    .unit(unit)
                    .unitPrice(unitPrice)
                    .amount(amt)
                    .build());
        }

        BigDecimal materialCost = anyMaterial ? materialSum.setScale(2, java.math.RoundingMode.HALF_UP) : null;

        // ---- 合计 = 非 null 组分之和 ----
        BigDecimal total = null;
        if (materialCost != null || laborCost != null || overheadCost != null) {
            total = BigDecimal.ZERO;
            if (materialCost != null) total = total.add(materialCost);
            if (laborCost != null) total = total.add(laborCost);
            if (overheadCost != null) total = total.add(overheadCost);
        }

        // ---- 数据缺失提示 ----
        StringBuilder hint = new StringBuilder();
        if (batchCount == 0) {
            hint.append("订单已关联生产计划但尚无生产批次 (未排批). ");
        }
        if (materialCost == null && batchCount > 0) {
            hint.append("批次暂无领料消耗记录 (未领料或自动扣料未执行). ");
        }
        if (laborCost == null && batchCount > 0) {
            hint.append("批次暂无人工成本 (未报工或工时×时薪未配置). ");
        }
        String hintStr = hint.length() > 0 ? hint.toString().trim() : null;

        return com.cretas.aims.dto.inventory.FinanceCostBreakdown.ActualCostSplit.builder()
                .materialCost(materialCost)
                .laborCost(laborCost)
                .overheadCost(overheadCost)
                .totalActualCost(total)
                .batchCount(batchCount)
                .materials(materials)
                .dataSourceHint(hintStr)
                .build();
    }

    // ==================== 六扇门多段成本逐段分配 (两点报工成本拆分核心) ====================

    /**
     * 六扇门多段生产成本逐段分配。
     *
     * <p>回溯链: 销售订单 → ProductionPlan(sourceOrderId) → ProductionBatch(planId)
     * → SemiFinishedInventory(batchId, 每行=一段=一次两点报工转化)
     * → SemiFinishedInventoryTransaction(IN, reportId) → ProductionReport(laborCost/materialCost)。
     *
     * <p><b>每段算法</b>:
     * <ul>
     *   <li>段排序: 按 SemiFinishedInventory.processOrder 升序 (第一段=最上游原料转化, 末段=成品);</li>
     *   <li>段材料/人工: 取该段全部 IN 流水的 reportId, 反查 ProductionReport, Σ materialCost / Σ laborCost
     *       (诚实 null: 全部 report 该字段为 null → 段该组分 null, 不伪造 0);</li>
     *   <li>段制费: report 不分制费, 段级无来源 → 诚实 null (制费在批次级, 不强行摊到段);</li>
     *   <li>段小计 = materialCost + laborCost + overheadCost (非 null 之和, 仅本段新增, 不含上游累积);</li>
     *   <li>产出半成品 unitCost = SemiFinishedInventory.unitCost (#713 已逐段滚动累积的移动均价);</li>
     *   <li>累积成本 = SemiFinishedInventory.accumulatedCost (上游 + 本段全部投入);</li>
     *   <li>每盒贡献 = 段小计 / 最终成品盒数 (末段 producedQuantity, 未知则 null)。</li>
     * </ul>
     *
     * <p><b>两点报工人工诚实 null</b>: 两点报工 (领料入 + 产出出) 时人工常"登下一期"按期间分摊,
     * 报工当下 laborCost=null → 该段 laborCost 诚实 null + laborHint。绝不伪造。
     */
    @Override
    @Transactional(readOnly = true)
    public com.cretas.aims.dto.inventory.MultiStageCostBreakdown getOrderMultiStageCost(
            String factoryId, String orderId) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);

        String productName = null;
        BigDecimal orderQty = null;
        try {
            order.getItems().size();  // 强制初始化 lazy
            if (order.getItems() != null && !order.getItems().isEmpty()) {
                SalesOrderItem first = order.getItems().get(0);
                productName = first.getProductName();
                orderQty = first.getQuantity();
            }
        } catch (Exception e) {
            log.warn("多段成本: 读取订单行失败 (orderId={}): {}", orderId, e.getMessage());
        }

        // repos 未注册 → 诚实 hint (不伪造)
        if (productionPlanRepository == null || productionBatchRepository == null
                || semiFinishedInventoryRepository == null) {
            return com.cretas.aims.dto.inventory.MultiStageCostBreakdown.builder()
                    .orderId(orderId)
                    .productName(productName)
                    .stages(java.util.Collections.emptyList())
                    .stageCount(0)
                    .dataSourceHint("多段成本模块未部署。")
                    .build();
        }

        java.util.List<com.cretas.aims.entity.ProductionPlan> plans;
        try {
            plans = productionPlanRepository.findByFactoryIdAndSourceOrderId(factoryId, orderId);
        } catch (Exception e) {
            log.warn("多段成本: 查询关联生产计划失败 (orderId={}): {}", orderId, e.getMessage());
            plans = java.util.Collections.emptyList();
        }

        if (plans == null || plans.isEmpty()) {
            return com.cretas.aims.dto.inventory.MultiStageCostBreakdown.builder()
                    .orderId(orderId)
                    .productName(productName)
                    .stages(java.util.Collections.emptyList())
                    .stageCount(0)
                    .dataSourceHint("订单尚未关联生产计划 (未投产), 暂无多段成本可拆分。")
                    .build();
        }

        java.util.Set<String> planIds = new java.util.HashSet<>();
        for (com.cretas.aims.entity.ProductionPlan p : plans) {
            if (p.getId() != null) {
                planIds.add(p.getId());
            }
        }

        java.util.List<com.cretas.aims.entity.ProductionBatch> batches =
                planIds.isEmpty()
                        ? java.util.Collections.emptyList()
                        : productionBatchRepository.findByFactoryIdAndProductionPlanIdIn(factoryId, planIds);

        // 收集所有批次下的半成品段 (每行=一段)
        java.util.List<com.cretas.aims.entity.SemiFinishedInventory> allStages = new java.util.ArrayList<>();
        for (com.cretas.aims.entity.ProductionBatch b : batches) {
            if (b.getId() == null) {
                continue;
            }
            try {
                allStages.addAll(
                        semiFinishedInventoryRepository.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, b.getId()));
            } catch (Exception e) {
                log.warn("多段成本: 查询批次 {} 半成品段失败: {}", b.getId(), e.getMessage());
            }
        }

        if (allStages.isEmpty()) {
            return com.cretas.aims.dto.inventory.MultiStageCostBreakdown.builder()
                    .orderId(orderId)
                    .productName(productName)
                    .finalOutputBoxes(orderQty)
                    .stages(java.util.Collections.emptyList())
                    .stageCount(0)
                    .dataSourceHint("订单已投产但无半成品 WIP 段记录 (纯成品直产 / 未做两点报工)。"
                            + "可在订单财审实际成本拆分查看总额。")
                    .build();
        }

        // 段排序: processOrder 升序 (null 排末尾), 同序按 id 稳定
        allStages.sort(java.util.Comparator
                .comparing((com.cretas.aims.entity.SemiFinishedInventory s) ->
                        s.getProcessOrder() == null ? Integer.MAX_VALUE : s.getProcessOrder())
                .thenComparing(s -> s.getId() == null ? Long.MAX_VALUE : s.getId()));

        // 最终成品盒数 = 末段 producedQuantity (回退订单行数量)
        com.cretas.aims.entity.SemiFinishedInventory lastStage = allStages.get(allStages.size() - 1);
        BigDecimal finalBoxes = lastStage.getProducedQuantity();
        if (finalBoxes == null || finalBoxes.signum() <= 0) {
            finalBoxes = orderQty;
        }
        String finalUnit = lastStage.getUnit();

        // 产品名映射 (批量, 避免 N+1)
        java.util.Map<String, String> ptNameMap = new java.util.HashMap<>();
        if (productTypeRepository != null) {
            java.util.Set<String> ptIds = allStages.stream()
                    .map(com.cretas.aims.entity.SemiFinishedInventory::getProductTypeId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            if (!ptIds.isEmpty()) {
                try {
                    productTypeRepository.findByIdIn(ptIds)
                            .forEach(pt -> ptNameMap.put(pt.getId(), pt.getName()));
                } catch (Exception e) {
                    log.warn("多段成本: 查询产品名失败: {}", e.getMessage());
                }
            }
        }

        boolean anyLaborNull = false;
        BigDecimal chainSum = null;
        BigDecimal chainPerBox = null;
        java.util.List<com.cretas.aims.dto.inventory.MultiStageCostBreakdown.StageCost> stageCosts =
                new java.util.ArrayList<>();

        for (com.cretas.aims.entity.SemiFinishedInventory stage : allStages) {
            // 段内料/人工: 反查该段 IN 流水的 reportId → ProductionReport.materialCost/laborCost
            BigDecimal stageMaterial = null;
            BigDecimal stageLabor = null;
            if (semiFinishedInventoryTransactionRepository != null
                    && productionReportRepository != null && stage.getId() != null) {
                java.util.List<com.cretas.aims.entity.SemiFinishedInventoryTransaction> txns;
                try {
                    txns = semiFinishedInventoryTransactionRepository
                            .findBySemiFinishedIdOrderByCreatedAtAsc(stage.getId());
                } catch (Exception e) {
                    log.warn("多段成本: 查询段 {} 流水失败: {}", stage.getId(), e.getMessage());
                    txns = java.util.Collections.emptyList();
                }
                java.util.Set<Long> reportIds = new java.util.LinkedHashSet<>();
                for (com.cretas.aims.entity.SemiFinishedInventoryTransaction t : txns) {
                    if (com.cretas.aims.entity.SemiFinishedInventoryTransaction.TxnType.IN.equals(t.getTxnType())
                            && t.getReportId() != null) {
                        reportIds.add(t.getReportId());
                    }
                }
                if (!reportIds.isEmpty()) {
                    java.util.List<com.cretas.aims.entity.ProductionReport> reports;
                    try {
                        reports = productionReportRepository.findAllById(reportIds);
                    } catch (Exception e) {
                        log.warn("多段成本: 反查段 {} 报工失败: {}", stage.getId(), e.getMessage());
                        reports = java.util.Collections.emptyList();
                    }
                    BigDecimal matSum = BigDecimal.ZERO;
                    boolean anyMat = false;
                    BigDecimal labSum = BigDecimal.ZERO;
                    boolean anyLab = false;
                    for (com.cretas.aims.entity.ProductionReport r : reports) {
                        if (r.getMaterialCost() != null) {
                            matSum = matSum.add(r.getMaterialCost());
                            anyMat = true;
                        }
                        if (r.getLaborCost() != null) {
                            labSum = labSum.add(r.getLaborCost());
                            anyLab = true;
                        }
                    }
                    stageMaterial = anyMat ? matSum.setScale(2, java.math.RoundingMode.HALF_UP) : null;
                    stageLabor = anyLab ? labSum.setScale(2, java.math.RoundingMode.HALF_UP) : null;
                }
            }

            // 段制费: 报工不含制费, 段级无来源 → 诚实 null (不强行摊批次级 equipmentCost 到段)
            BigDecimal stageOverhead = null;

            // 段小计 = 本段新增 (料 + 人工 + 制费), 非 null 之和; 不含上游累积 (避免重复计数)
            BigDecimal subtotal = null;
            if (stageMaterial != null || stageLabor != null || stageOverhead != null) {
                subtotal = BigDecimal.ZERO;
                if (stageMaterial != null) subtotal = subtotal.add(stageMaterial);
                if (stageLabor != null) subtotal = subtotal.add(stageLabor);
                if (stageOverhead != null) subtotal = subtotal.add(stageOverhead);
            }

            // 上游领用累积: 累积成本 − 本段新增小计 (诚实, 仅当两者都有)
            BigDecimal upstreamCarried = null;
            if (stage.getAccumulatedCost() != null && subtotal != null) {
                BigDecimal diff = stage.getAccumulatedCost().subtract(subtotal);
                if (diff.signum() > 0) {
                    upstreamCarried = diff.setScale(2, java.math.RoundingMode.HALF_UP);
                }
            }

            // 每盒贡献 = 段小计 / 最终成品盒数
            BigDecimal perBox = null;
            if (subtotal != null && finalBoxes != null && finalBoxes.signum() > 0) {
                perBox = subtotal.divide(finalBoxes, 2, java.math.RoundingMode.HALF_UP);
            }

            // 人工诚实 null → hint
            String laborHint = null;
            String stageHint = null;
            if (stageLabor == null) {
                anyLaborNull = true;
                laborHint = "本段人工登下一期 (两点报工人工按期间统一分摊, 报工当下未结)。";
                stageHint = "人工未结 (登下一期)。";
            }
            if (stageMaterial == null && stageHint == null) {
                stageHint = "本段无领料消耗记录。";
            }

            // 链合计累加 (非 null 段)
            if (subtotal != null) {
                chainSum = (chainSum == null ? BigDecimal.ZERO : chainSum).add(subtotal);
            }
            if (perBox != null) {
                chainPerBox = (chainPerBox == null ? BigDecimal.ZERO : chainPerBox).add(perBox);
            }

            String ptName = ptNameMap.get(stage.getProductTypeId());
            stageCosts.add(com.cretas.aims.dto.inventory.MultiStageCostBreakdown.StageCost.builder()
                    .stageOrder(stage.getProcessOrder())
                    .semiCode(stage.getIntermediateBatchNo())
                    .stageName(ptName != null ? ptName : stage.getIntermediateBatchNo())
                    .productName(ptName)
                    .producedQuantity(stage.getProducedQuantity())
                    .producedUnit(stage.getUnit())
                    .upstreamConsumedQuantity(null)  // 上游领用量需 lineage 边精确归因, 当前以累积成本表达
                    .upstreamCarriedCost(upstreamCarried)
                    .materialCost(stageMaterial)
                    .laborCost(stageLabor)
                    .laborHint(laborHint)
                    .overheadCost(stageOverhead)
                    .stageSubtotal(subtotal)
                    .outputUnitCost(stage.getUnitCost())
                    .accumulatedCost(stage.getAccumulatedCost())
                    .contributionPerBox(perBox)
                    .materials(java.util.Collections.emptyList())  // 逐料归因到段需 report.materialBatchRefs, 后续增强
                    .stageHint(stageHint)
                    .build());
        }

        StringBuilder hint = new StringBuilder();
        if (anyLaborNull) {
            hint.append("部分段人工登下一期 (两点报工按期间统一分摊), 段成本暂仅含料/制费, 人工待结后回填。 ");
        }
        if (finalBoxes == null) {
            hint.append("末段未产出成品, 每盒口径暂不可算。 ");
        }
        String hintStr = hint.length() > 0 ? hint.toString().trim() : null;

        return com.cretas.aims.dto.inventory.MultiStageCostBreakdown.builder()
                .orderId(orderId)
                .productName(productName)
                .finalOutputBoxes(finalBoxes)
                .finalOutputUnit(finalUnit)
                .stages(stageCosts)
                .totalChainCost(chainSum)
                .totalCostPerBox(chainPerBox)
                .stageCount(stageCosts.size())
                .dataSourceHint(hintStr)
                .build();
    }

    /**
     * B3 售价趋势 — 财审辅助决策.
     *
     * <p>返回该产品最近 {@code limit} 笔成交记录的行级单价，
     * 供财务审核人员判断本单价格是否偏离历史水平。
     * 排除 DRAFT / CANCELLED 状态的订单。
     */
    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.cretas.aims.dto.inventory.SalesPriceTrendDTO> getProductPriceTrend(
            String factoryId, String productTypeId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        java.util.Set<SalesOrderStatus> excluded = java.util.Set.of(
                SalesOrderStatus.DRAFT, SalesOrderStatus.CANCELLED);
        var rows = salesOrderItemRepository.findRecentPriceByProduct(
                factoryId, productTypeId, excluded,
                org.springframework.data.domain.PageRequest.of(0, safeLimit));
        return rows.stream()
                .map(r -> com.cretas.aims.dto.inventory.SalesPriceTrendDTO.builder()
                        .orderNumber(r.getOrderNumber())
                        .orderDate(r.getOrderDate())
                        .unitPrice(r.getUnitPrice())
                        .quantity(r.getQuantity())
                        .unit(r.getUnit())
                        .build())
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "UPDATE", entityType = "SalesOrder",
              entityIdParam = "orderId")
    public SalesOrder updateSalesOrder(String factoryId, String orderId, UpdateSalesOrderRequest request) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw new BusinessException(409, "只有草稿状态的订单可以编辑")
                    .withHint("请刷新订单列表查看最新状态");
        }
        // Optimistic lock: explicit compare (see CustomerServiceImpl for rationale)
        if (request.getVersion() != null && !request.getVersion().equals(order.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                SalesOrder.class, orderId);
        }

        validateSupplyContract(
                request.getProcessingMode(), request.getMaterialSupplyMode(), request.getItems());
        CustomerStockFulfillmentMode requestedFulfillmentMode =
                request.getCustomerStockFulfillmentMode() != null
                        ? request.getCustomerStockFulfillmentMode()
                        : normalizeCustomerStockFulfillmentMode(
                                order.getCustomerStockFulfillmentMode());
        validateSuppliedMaterialPayload(
                request.getProcessingMode(), request.getMaterialSupplyMode(),
                requestedFulfillmentMode,
                request.getSuppliedMaterials(), true);
        order.setProcessingMode(request.getProcessingMode());
        order.setMaterialSupplyMode(request.getMaterialSupplyMode());
        order.setCustomerStockFulfillmentMode(requestedFulfillmentMode);

        // Canvas V2: DB-driven validation for UPDATE — pre-compute totalAmount if items changed
        BigDecimal updateTotal = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        boolean hasDupOnUpdate = false;
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            Set<String> seenIds = new HashSet<>();
            updateTotal = BigDecimal.ZERO;
            for (CreateSalesOrderRequest.SalesOrderItemDTO itemDTO : request.getItems()) {
                if (itemDTO.getProductTypeId() != null && !seenIds.add(itemDTO.getProductTypeId())) {
                    hasDupOnUpdate = true;
                }
                if (itemDTO.getQuantity() != null && itemDTO.getUnitPrice() != null) {
                    BigDecimal discountRate = itemDTO.getDiscountRate() != null ? itemDTO.getDiscountRate() : BigDecimal.ZERO;
                    BigDecimal line = itemDTO.getQuantity().multiply(itemDTO.getUnitPrice())
                            .multiply(BigDecimal.ONE.subtract(discountRate));
                    updateTotal = updateTotal.add(line);
                }
            }
        }
        runConfiguredValidation(factoryId, "UPDATE", Map.of(
                "itemCount", request.getItems() != null ? request.getItems().size() : 0,
                "totalAmount", updateTotal,
                "hasDuplicateProduct", hasDupOnUpdate,
                "currentStatus", order.getStatus().name(),
                // Bug fix 2026-04-24: 全局规则 ID=1 (factory_validation_rules) 用 #status,
                // 历史代码只传 currentStatus → #status null → null!='DRAFT' = true →
                // 任何 SO PUT 即使 status=DRAFT 也错误抛 "只有草稿状态". 双键兼容.
                "status", order.getStatus().name()));

        if (request.getSalesperson() != null) {
            resolveSalespersonField(order, request.getSalesperson(), factoryId);
        }
        if (request.getDeliveryAddress() != null) {
            order.setDeliveryAddress(request.getDeliveryAddress());
        }
        if (request.getRemark() != null) {
            order.setRemark(request.getRemark());
        }
        if (request.getShippingIncluded() != null) {
            order.setShippingIncluded(request.getShippingIncluded());
        }
        if (request.getShippingFee() != null) {
            order.setShippingFee(request.getShippingFee());
        }
        if (request.getExtraFees() != null) {
            order.setExtraFees(request.getExtraFees());
        }
        if (request.getRequiredDeliveryDate() != null) {
            order.setRequiredDeliveryDate(request.getRequiredDeliveryDate());
        }

        // 行项目更新 + 总金额重算
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            // 删除旧行项
            List<SalesOrderItem> oldItems = salesOrderItemRepository.findBySalesOrderId(order.getId());
            Map<String, SalesOrderItem> oldItemsByProduct = oldItems.stream()
                    .collect(Collectors.toMap(
                            SalesOrderItem::getProductTypeId,
                            item -> item,
                            (first, ignored) -> first));
            salesOrderItemRepository.deleteAll(oldItems);

            // 创建新行项 + 重算总金额
            BigDecimal totalAmount = BigDecimal.ZERO;
            List<SalesOrderItem> newItems = new ArrayList<>();
            for (CreateSalesOrderRequest.SalesOrderItemDTO itemDTO : request.getItems()) {
                SalesOrderItem item = new SalesOrderItem();
                item.setSalesOrderId(order.getId());
                item.setProductTypeId(itemDTO.getProductTypeId());
                String productName = itemDTO.getProductName();
                if (productName == null || productName.isBlank()) {
                    productName = resolveSellableName(factoryId, itemDTO.getProductTypeId());
                }
                item.setProductName(productName);
                item.setQuantity(itemDTO.getQuantity());
                // 写入点 2/5 (updateSalesOrder, 客户端入参)
                item.setUnit(storageUnit(factoryId, itemDTO.getUnit()));
                SalesOrderItem previous = oldItemsByProduct.get(itemDTO.getProductTypeId());
                if (previous != null
                        && previous.getPackagingSpecId() != null
                        && Objects.equals(previous.getPackagingSpecId(), itemDTO.getPackagingSpecId())) {
                    copyPackagingSnapshot(previous, item);
                    if (itemDTO.getQuantity() != null && previous.getPackagingSpecId() != null) {
                        item.setBoxQuantity(itemDTO.getQuantity());
                    }
                } else {
                    applyPackagingSelection(factoryId, itemDTO, item);
                }
                item.setUnitPrice(itemDTO.getUnitPrice());
                item.setDiscountRate(itemDTO.getDiscountRate() != null ? itemDTO.getDiscountRate() : BigDecimal.ZERO);
                // SP4 updateSalesOrder: 同 createSalesOrder 三层 default 链 — item 显式 > SO.defaultTaxRate > 0
                BigDecimal effectiveTaxRateUpdate = itemDTO.getTaxRate();
                if (effectiveTaxRateUpdate == null) {
                    effectiveTaxRateUpdate = order.getDefaultTaxRate() != null ? order.getDefaultTaxRate() : BigDecimal.ZERO;
                }
                item.setTaxRate(effectiveTaxRateUpdate);
                item.setRemark(itemDTO.getRemark());
                item.setSpecification(itemDTO.getSpecification());
                item.setBoxQuantity(itemDTO.getBoxQuantity());
                if (itemDTO.getSourceWarehouseCode() == null && previous != null) {
                    // Legacy clients may omit this field during a DRAFT update. Preserve the
                    // persisted shipping source instead of silently clearing it.
                    item.setSourceWarehouseCode(previous.getSourceWarehouseCode());
                } else {
                    item.setSourceWarehouseCode(normalizeSourceWarehouseCode(
                            factoryId, itemDTO.getSourceWarehouseCode()));
                }
                item.setDestWarehouseName(itemDTO.getDestWarehouseName());
                item.setDestWarehouseCode(itemDTO.getDestWarehouseCode());
                item.setExternalPoId(itemDTO.getExternalPoId());
                item.setBarcode(itemDTO.getBarcode());
                item.setAppointmentTime(itemDTO.getAppointmentTime());
                item.setRequiredArrivalDate(itemDTO.getRequiredArrivalDate());
                item.setProcessingMode(request.getProcessingMode());
                item.setMaterialSupplyMode(request.getMaterialSupplyMode());
                newItems.add(item);
                // E-2 空价草稿: unitPrice null → getLineAmount() null, 该行贡献 0 (同 createSalesOrder 防 NPE)
                BigDecimal lineAmount = item.getLineAmount();
                totalAmount = totalAmount.add(lineAmount != null ? lineAmount : BigDecimal.ZERO);
            }
            salesOrderItemRepository.saveAll(newItems);
            order.setTotalAmount(totalAmount);
            // SP4: 重算 tax_amount (与 createSalesOrder 逻辑对称, 减法兜底)
            BigDecimal taxAmountSumUpdate = newItems.stream()
                    .map(it -> {
                        BigDecimal la = it.getLineAmount();
                        BigDecimal lawt = it.getLineAmountWithTax();
                        if (la == null || lawt == null) return BigDecimal.ZERO;
                        return lawt.subtract(la);
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            order.setTaxAmount(taxAmountSumUpdate);
        } else {
            // Header modes are the MVP single truth. Keep draft-line snapshots aligned
            // even when this edit does not replace order items.
            List<SalesOrderItem> existingItems = salesOrderItemRepository.findBySalesOrderId(order.getId());
            existingItems.forEach(item -> {
                item.setProcessingMode(request.getProcessingMode());
                item.setMaterialSupplyMode(request.getMaterialSupplyMode());
            });
            if (!existingItems.isEmpty()) {
                salesOrderItemRepository.saveAll(existingItems);
            }
        }

        if (suppliedMaterialRequirementService != null) {
            List<SalesOrderSuppliedMaterialRequirement> suppliedMaterials =
                    suppliedMaterialRequirementService.updateForOrder(
                            order,
                            request.getSuppliedMaterials(),
                            request.getItems() != null && !request.getItems().isEmpty());
            order.setSuppliedMaterials(new ArrayList<>(suppliedMaterials));
        } else if (request.getSuppliedMaterials() != null
                && !request.getSuppliedMaterials().isEmpty()) {
            throw new IllegalStateException(
                    "SalesOrderSuppliedMaterialRequirementService is required for suppliedMaterials");
        }

        // Canvas V3: Update dynamic custom fields via DynamicFieldService
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "sales_order", order.getId(), request.getCustomFields());
                log.info("Updated {} custom fields for sales order {}",
                        request.getCustomFields().size(), order.getId());
            } catch (Exception e) {
                log.warn("Failed to update custom fields for sales order {}: {}", order.getId(), e.getMessage());
            }
        }

        log.info("更新销售订单: orderId={}, orderNumber={}", orderId, order.getOrderNumber());
        return salesOrderRepository.save(order);
    }

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "REPAIR_SOURCE_WAREHOUSE",
              entityType = "SalesOrderItem", entityIdParam = "itemId")
    public SalesOrderItem repairMissingSourceWarehouse(
            String factoryId, String orderId, Long itemId, String sourceWarehouseCode) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        if (order.getStatus() == SalesOrderStatus.DRAFT
                || order.getStatus() == SalesOrderStatus.CANCELLED) {
            throw new BusinessException(409, "当前订单状态不允许历史来源仓修复")
                    .withCode("SOURCE_WAREHOUSE_REPAIR_STATUS_INVALID")
                    .withHint("草稿请通过正常编辑保存；已取消订单不能修复来源仓");
        }

        SalesOrderItem item = salesOrderItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单行不存在"));
        if (!orderId.equals(item.getSalesOrderId())) {
            throw new BusinessException(409, "销售订单行与订单不匹配")
                    .withCode("SOURCE_WAREHOUSE_REPAIR_ITEM_MISMATCH")
                    .withHint("请刷新页面后重新选择要修复来源仓的订单行")
                    .withHintTarget("itemId");
        }

        String normalized = normalizeSourceWarehouseCode(factoryId, sourceWarehouseCode);
        if (normalized == null) {
            throw new BusinessException(400, "来源仓库编码不能为空")
                    .withCode("SOURCE_WAREHOUSE_INVALID")
                    .withHintTarget("sourceWarehouseCode");
        }
        String current = item.getSourceWarehouseCode();
        if (current != null && !current.isBlank()) {
            if (current.equals(normalized)) {
                return item;
            }
            throw new BusinessException(409, "订单行已有不同的来源仓库，拒绝覆盖")
                    .withCode("SOURCE_WAREHOUSE_REPAIR_CONFLICT")
                    .withHint("请核对原订单和发货记录，不要用历史修复接口改写既有仓库")
                    .withHintTarget("sourceWarehouseCode");
        }

        item.setSourceWarehouseCode(normalized);
        return salesOrderItemRepository.saveAndFlush(item);
    }

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "CANCEL", entityType = "SalesOrder",
              entityIdParam = "orderId")
    public SalesOrder cancelOrder(String factoryId, String orderId, String reason) {
        // getSalesOrderById 内部做 factoryId 租户隔离校验 (不属于该工厂 → 403).
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        // R39 BUG-8 fix: was only blocking COMPLETED → FINANCE_APPROVED+/PROCESSING/SHIPPED/CANCELLED
        // could be cancelled, breaking AR + production_plan invariants. Use whitelist.
        if (!com.cretas.aims.domain.OrderUsageWhitelists.SO_CANCELLABLE.contains(order.getStatus())) {
            throw new BusinessException(409,
                    "当前订单状态(" + order.getStatus().getDisplayName() + ")不允许取消。"
                  + "财务批准后请通过驳回/退款流程处理")
                    .withHint("请刷新订单列表查看最新状态");
        }
        order.setStatus(SalesOrderStatus.CANCELLED);
        // E-FP-2 (fool-proof Rule 3): 取消原因追加到 remark, 无专用字段故复用 remark 追溯.
        if (reason != null && !reason.isBlank()) {
            String existing = order.getRemark();
            String tag = "[取消原因: " + reason.trim() + "]";
            order.setRemark(existing == null || existing.isBlank() ? tag : existing + " " + tag);
        }
        log.info("取消销售订单: orderId={}, orderNumber={}, reason={}",
                orderId, order.getOrderNumber(), reason);
        SalesOrder saved = salesOrderRepository.save(order);

        // Bug 3 修复 (2026-07-04): 取消订单 → 作废已生成的 SALES_RECEIPT 凭证 (幽灵营收/应收根治)。
        // AFTER_COMMIT 异步 listener 处理 (SalesOrderVoucherListener.onSalesOrderCancelled), 只在
        // 取消事务真正提交后才作废凭证; 避免 voidVoucher 内层事务污染取消主事务 (doomed-tx)。
        try {
            applicationEventPublisher.publishEvent(
                    new com.cretas.aims.event.SalesOrderCancelledEvent(this, factoryId, saved.getId(), reason));
        } catch (Exception e) {
            log.error("发布SalesOrderCancelledEvent失败(不影响取消): SO={}", saved.getId(), e);
        }
        return saved;
    }

    /**
     * 短交结案的子发运单终结判定 —— 只有到达这些状态才不会再产生新的实发数量。
     * SHIPPED/DELIVERED 已经计入 {@code SalesOrderItem#deliveredQuantity}
     * (见 {@link #shipDelivery}); CANCELLED/RETURNED 不会再发货。其余状态
     * (DRAFT/PENDING_WAREHOUSE_CONFIRM/PICKED) 仍可能被继续确认发货, 必须拦。
     */
    private static final java.util.Set<SalesDeliveryStatus> SHORT_CLOSE_TERMINAL_SHIPMENT_STATUSES =
            java.util.EnumSet.of(
                    SalesDeliveryStatus.SHIPPED,
                    SalesDeliveryStatus.DELIVERED,
                    SalesDeliveryStatus.CANCELLED,
                    SalesDeliveryStatus.RETURNED);

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "CLOSE_SHORT_SHIPMENT", entityType = "SalesOrder",
              entityIdParam = "orderId")
    public SalesOrder closeShortShipment(
            String factoryId, String orderId,
            com.cretas.aims.dto.inventory.CloseShortShipmentRequest request, Long userId) {
        SalesOrder order = salesOrderRepository.findByIdAndFactoryIdForUpdate(orderId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在或不属于当前组织"));

        // 双击/网页重放幂等: 已经短交结案的订单直接原样返回, 不重复审计、不二次校验。
        if (order.getStatus() == SalesOrderStatus.SHORT_CLOSED) {
            return order;
        }
        if (order.getStatus() != SalesOrderStatus.PARTIAL_DELIVERED) {
            throw new BusinessException(409, "当前订单状态(" + order.getStatus().getDisplayName() + ")不允许短交结案")
                    .withCode("SALES_SHORT_CLOSE_STATUS_INVALID")
                    .withHint("只有部分发货状态可以短交结案，请刷新订单查看最新状态");
        }

        // 硬约束 8 (measurement-and-wiring): 订单挂着的每一张子发运单 (role != MASTER) 都要
        // 先确认已经到达终态, 否则它随时可能被继续确认发货, 结案锁定的缺口量就会失真。
        List<SalesDeliveryRecord> deliveries = deliveryRecordRepository.findBySalesOrderId(orderId);
        SalesDeliveryRecord pending = deliveries.stream()
                .filter(d -> factoryId.equals(d.getFactoryId()))
                .filter(d -> !"MASTER".equals(d.getRecordRole()))
                .filter(d -> !SHORT_CLOSE_TERMINAL_SHIPMENT_STATUSES.contains(d.getStatus()))
                .findFirst()
                .orElse(null);
        if (pending != null) {
            throw new BusinessException(409,
                    "订单仍有未完成的发运单 " + pending.getDeliveryNumber() + "，不能短交结案")
                    .withCode("SALES_SHORT_CLOSE_ACTIVE_DELIVERY")
                    .withHint("请先到「销售 → 发货记录」把发运单 " + pending.getDeliveryNumber()
                            + " 确认发货或取消，再回来短交结案");
        }

        List<SalesOrderItem> items = salesOrderItemRepository.findBySalesOrderId(orderId);
        if (items.isEmpty()) {
            throw new BusinessException(409, "订单没有可核对的商品行，不能短交结案")
                    .withCode("SALES_SHORT_CLOSE_LINES_REQUIRED")
                    .withHint("请联系销售经理核对订单明细");
        }
        BigDecimal shortfall = BigDecimal.ZERO;
        for (SalesOrderItem item : items) {
            BigDecimal ordered = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            BigDecimal delivered = item.getDeliveredQuantity() != null
                    ? item.getDeliveredQuantity() : BigDecimal.ZERO;
            shortfall = shortfall.add(ordered.subtract(delivered).max(BigDecimal.ZERO));
        }
        if (shortfall.compareTo(BigDecimal.ZERO) <= 0) {
            // 全部实发的订单会在 updateOrderDeliveryStatus 里自动流转 COMPLETED —— 这里没有
            // 缺口可关, 走到这个分支说明前端读到的是过期状态。
            throw new BusinessException(409, "订单已全部发货，无需短交结案")
                    .withCode("SALES_SHORT_CLOSE_NO_SHORTFALL")
                    .withHint("请刷新订单查看最新状态；全部发货完成会自动流转为已完成");
        }

        if (request == null || request.getReasonCode() == null) {
            throw new BusinessException(400, "请选择短交结案原因")
                    .withCode("SALES_SHORT_CLOSE_REASON_REQUIRED");
        }
        String notes = request.getNotes() == null ? null : request.getNotes().trim();
        if (request.getReasonCode() == com.cretas.aims.dto.inventory.CloseShortShipmentRequest.ReasonCode.OTHER
                && (notes == null || notes.isBlank())) {
            throw new BusinessException(400, "选择“其他原因”时必须填写补充说明")
                    .withCode("SALES_SHORT_CLOSE_NOTES_REQUIRED")
                    .withHintTarget("短交结案补充说明");
        }
        if (notes != null && notes.length() > 500) {
            throw new BusinessException(400, "补充说明不能超过500字")
                    .withCode("SALES_SHORT_CLOSE_NOTES_TOO_LONG");
        }

        order.setStatus(SalesOrderStatus.SHORT_CLOSED);
        order.setShortCloseReasonCode(request.getReasonCode().name());
        order.setShortCloseNotes(notes == null || notes.isBlank() ? null : notes);
        order.setShortClosedBy(userId);
        order.setShortClosedAt(LocalDateTime.now());
        order.setShortClosedQuantity(shortfall);
        SalesOrder saved = salesOrderRepository.save(order);
        log.info("销售订单短交结案: factoryId={}, orderId={}, orderNumber={}, shortfall={}, reason={}, userId={}",
                factoryId, orderId, order.getOrderNumber(), shortfall, request.getReasonCode(), userId);
        return saved;
    }

    /**
     * 复制销售订单 — 见 {@link SalesService#copySalesOrder} 文档.
     *
     * <p>实现说明:
     * <ul>
     *   <li>用 {@link #getSalesOrderById} 校验源订单 + factory 隔离 (404 / 403).</li>
     *   <li>新订单 status = DRAFT, createdBy = 当前用户, orderDate = 今天.</li>
     *   <li>items 用 fresh SalesOrderItem 实例 (Hibernate 不复用 id),
     *       deliveredQuantity/lockedQty/reservedQty 重置为 0.</li>
     *   <li>orderNumber 复用 {@link #generateSalesOrderNumber} (含 advisory lock 防 race).</li>
     *   <li>extraFees 是 JSON List — 浅 copy ArrayList 即可 (item 不可变).</li>
     * </ul>
     */
    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "COPY", entityType = "SalesOrder",
              entityIdParam = "sourceOrderId")
    public SalesOrder copySalesOrder(String factoryId, String sourceOrderId, Long userId) {
        SalesOrder source = getSalesOrderById(factoryId, sourceOrderId);
        validatePersistedSupplyContract(source);
        // 强制初始化 items (lazy collection)
        org.hibernate.Hibernate.initialize(source.getItems());

        String newOrderNumber = generateSalesOrderNumber(factoryId);

        SalesOrder newOrder = new SalesOrder();
        newOrder.setFactoryId(factoryId);
        newOrder.setOrderNumber(newOrderNumber);
        // 复制业务字段
        newOrder.setCustomerId(source.getCustomerId());
        newOrder.setOrderDate(LocalDate.now());
        newOrder.setRequiredDeliveryDate(source.getRequiredDeliveryDate());
        newOrder.setDeliveryAddress(source.getDeliveryAddress());
        newOrder.setDiscountAmount(source.getDiscountAmount() != null
                ? source.getDiscountAmount() : BigDecimal.ZERO);
        newOrder.setRemark(source.getRemark());
        newOrder.setSalesperson(source.getSalesperson());
        newOrder.setSalespersonId(source.getSalespersonId());
        newOrder.setShippingIncluded(source.getShippingIncluded());
        newOrder.setShippingFee(source.getShippingFee());
        // extraFees 是 List<ExtraFeeItem>; 浅 copy (item 不变, 由 Hibernate JSON 再序列化)
        if (source.getExtraFees() != null) {
            newOrder.setExtraFees(new ArrayList<>(source.getExtraFees()));
        }
        newOrder.setQuoteId(source.getQuoteId());
        newOrder.setDefaultTaxRate(source.getDefaultTaxRate());
        newOrder.setDefaultInvoiceType(source.getDefaultInvoiceType());
        newOrder.setBoxQuantity(source.getBoxQuantity());
        newOrder.setProcessingMode(source.getProcessingMode());
        newOrder.setMaterialSupplyMode(source.getMaterialSupplyMode());
        newOrder.setCustomerStockFulfillmentMode(
                normalizeCustomerStockFulfillmentMode(source.getCustomerStockFulfillmentMode()));
        // 重置状态字段
        newOrder.setStatus(SalesOrderStatus.DRAFT);
        newOrder.setCreatedBy(userId);
        // 不复制 confirmedAt/finance*/estimatedCost/estimatedProfit/actualShippedAmount
        // /invoiceStatus/invoicedAmount/paidAmount/settlementFlag/transportPlanStatus
        // /deliveryReminderDate/contractFile*/vflag/markerColor (默认值已 OK)

        newOrder = salesOrderRepository.save(newOrder);

        // 复制行项目 (fresh instances, 重置 delivered/locked/reserved)
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<SalesOrderItem> newItems = new ArrayList<>();
        for (SalesOrderItem srcItem : source.getItems()) {
            SalesOrderItem newItem = new SalesOrderItem();
            newItem.setSalesOrderId(newOrder.getId());
            newItem.setProductTypeId(srcItem.getProductTypeId());
            newItem.setProductName(srcItem.getProductName());
            newItem.setQuantity(srcItem.getQuantity());
            // 写入点 3/5 (copySalesOrder) — 源行可能是存量英文码, 复制出来的是【新行】,
            // 新行不该再带英文。storageUnit 幂等, 源行已规范时这里是恒等。
            newItem.setUnit(storageUnit(factoryId, srcItem.getUnit()));
            newItem.setUnitPrice(srcItem.getUnitPrice());
            newItem.setDiscountRate(srcItem.getDiscountRate() != null
                    ? srcItem.getDiscountRate() : BigDecimal.ZERO);
            newItem.setCostUnitPrice(srcItem.getCostUnitPrice());
            newItem.setTaxRate(srcItem.getTaxRate());
            newItem.setRemark(srcItem.getRemark());
            newItem.setSpecification(srcItem.getSpecification());
            newItem.setBoxQuantity(srcItem.getBoxQuantity());
            newItem.setPackagingSpecId(srcItem.getPackagingSpecId());
            newItem.setPackagingSpecName(srcItem.getPackagingSpecName());
            newItem.setPackagingUnit(srcItem.getPackagingUnit());
            newItem.setPackagingBaseUnit(srcItem.getPackagingBaseUnit());
            newItem.setPackagingFactor(srcItem.getPackagingFactor());
            newItem.setSourceWarehouseCode(srcItem.getSourceWarehouseCode());
            newItem.setProcessingMode(srcItem.getProcessingMode());
            newItem.setMaterialSupplyMode(srcItem.getMaterialSupplyMode());
            // deliveredQuantity / lockedQty / reservedQty 默认 ZERO — 不复制状态量
            newItems.add(newItem);

            BigDecimal lineAmount = newItem.getLineAmount();
            if (lineAmount != null) {
                totalAmount = totalAmount.add(lineAmount);
            }
        }
        salesOrderItemRepository.saveAll(newItems);

        if (suppliedMaterialRequirementService != null) {
            Map<Long, Long> copiedItemIds = new HashMap<>();
            for (int index = 0; index < source.getItems().size() && index < newItems.size(); index++) {
                Long sourceItemId = source.getItems().get(index).getId();
                Long copiedItemId = newItems.get(index).getId();
                if (sourceItemId != null && copiedItemId != null) {
                    copiedItemIds.put(sourceItemId, copiedItemId);
                }
            }
            List<SalesOrderSuppliedMaterialRequirement> copiedRequirements =
                    suppliedMaterialRequirementService.copyForOrder(
                            source, newOrder, copiedItemIds);
            newOrder.setSuppliedMaterials(new ArrayList<>(copiedRequirements));
        }
        newOrder.setTotalAmount(totalAmount);
        newOrder = salesOrderRepository.save(newOrder);

        log.info("复制销售订单: source={}({}) → new={}({}), items={}",
                sourceOrderId, source.getOrderNumber(),
                newOrder.getId(), newOrderNumber, newItems.size());
        return newOrder;
    }

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "DELETE", entityType = "SalesOrder",
              entityIdParam = "orderId")
    public void deleteDraft(String factoryId, String orderId, Long userId) {
        // getSalesOrderById 已含 404 + 403 跨工厂隔离
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        // 状态机约束: 仅 DRAFT 可删
        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw new BusinessException(409,
                    "仅 DRAFT 草稿状态可删除 (当前状态: " + order.getStatus().getDisplayName() + ")")
                    .withHint("如需取消已确认的订单, 请使用取消操作");
        }
        // 有 sales:read_write 权限的工厂内管理员均可删除草稿 (无 creator-only 限制)。
        // BaseEntity 上的 @SQLDelete 不会可靠地传播到实体；repository.delete(order)
        // 会物理删除父行，并被客供需求 FK 正确拦截。显式软删父子行，保留追溯。
        requireSuppliedMaterialRequirementService().softDeleteForDraftOrder(order);
        if (order.getItems() != null && !order.getItems().isEmpty()) {
            order.getItems().forEach(SalesOrderItem::softDelete);
            salesOrderItemRepository.saveAll(order.getItems());
        }
        order.softDelete();
        salesOrderRepository.save(order);
        log.info("删除销售订单草稿: orderId={}, orderNumber={}, operatorId={}",
                orderId, order.getOrderNumber(), userId);
    }

    /**
     * Round 14: Compute all aggregate formulas configured for a sales order.
     * Returns formula results keyed by formula_code (e.g., "tax_group_sum").
     * The "杀手锏 G1" killer feature — 39 factories have tax_group_sum configured,
     * but it was never evaluated at runtime until now.
     */
    public Map<String, Object> computeOrderFormulas(String factoryId, String orderId) {
        Map<String, Object> results = new java.util.LinkedHashMap<>();
        if (formulaEngine == null) return results;

        // Query all factory_formulas for this factory + sales_order module
        var formulas = formulaEngine.listFormulas(factoryId, "sales_order");
        if (formulas == null || formulas.isEmpty()) return results;

        Map<String, Object> context = Map.of(
                "factoryId", factoryId,
                "parentId", orderId);

        for (var formula : formulas) {
            try {
                Object result = formulaEngine.evaluateAny(factoryId, "sales_order", formula.getFormulaCode(), context);
                if (result != null) {
                    results.put(formula.getFormulaCode(), result);
                }
            } catch (Exception e) {
                log.warn("Formula {} evaluation failed for order {}: {}", formula.getFormulaCode(), orderId, e.getMessage());
            }
        }
        return results;
    }

    // ==================== 发货/出库 ====================

    @Override
    @Transactional
    public SalesDeliveryRecord createDeliveryRecord(String factoryId, CreateDeliveryRequest request, Long userId) {
        Map<Long, SalesOrderItem> sourceOrderItemsById = new HashMap<>();
        Map<String, List<SalesOrderItem>> sourceOrderItemsByProduct = new HashMap<>();
        // Round 9 Fix (R8-α Gap #1 template): Canvas validation rules now fire for
        // delivery creation. Customer-configured rules like "冷链商品必填运输温度" /
        // "发货金额 > 5万自动预警" / "物流公司必填" take effect here. Context
        // exposes {customerId, salesOrderId, itemCount, totalAmount, logisticsCompany,
        // deliveryAddress} so SpEL expressions can reason about the delivery.
        java.math.BigDecimal prelimTotal = request.getItems() == null ? java.math.BigDecimal.ZERO :
                request.getItems().stream()
                        .filter(i -> i.getUnitPrice() != null && i.getDeliveredQuantity() != null)
                        .map(i -> i.getDeliveredQuantity().multiply(i.getUnitPrice()))
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.util.Map<String, Object> validationCtx = new java.util.HashMap<>();
        validationCtx.put("customerId", request.getCustomerId());
        validationCtx.put("salesOrderId", request.getSalesOrderId());
        validationCtx.put("itemCount", request.getItems() == null ? 0 : request.getItems().size());
        validationCtx.put("totalAmount", prelimTotal);
        validationCtx.put("logisticsCompany", request.getLogisticsCompany());
        validationCtx.put("deliveryAddress", request.getDeliveryAddress());
        if (request.getCustomFields() != null) {
            // Merge customFields into validation context so rules can reference them
            // via #cf_<fieldCode> (matching the SalesOrder/MaterialBatch pattern).
            request.getCustomFields().forEach((k, v) -> validationCtx.put("cf_" + k, v));
        }
        runConfiguredValidation(factoryId, "delivery", "CREATE", validationCtx);

        // 验证客户
        Customer deliveryCustomer = customerRepository.findByIdAndFactoryId(request.getCustomerId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("客户不存在或不属于当前组织"));

        // R23 audit C3: was inline {FINANCE_APPROVED, CONFIRMED, PROCESSING, PARTIAL_DELIVERED}
        // — distinct from SO_SHIPPABLE because delivery here allows CONFIRMED (e.g.,
        // consignment / advance shipment). Centralized as SO_DELIVERABLE.
        if (request.getSalesOrderId() != null && !request.getSalesOrderId().isEmpty()) {
            // Pessimistic order lock serializes capacity checks across rapid double-clicks
            // and concurrent sessions. No two transactions can both consume the same
            // remaining order-line quantity.
            SalesOrder order = salesOrderRepository.findByIdAndFactoryIdForUpdate(
                            request.getSalesOrderId(), factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在或不属于当前组织"));
            if (order.getStatus() == null
                    || !com.cretas.aims.domain.OrderUsageWhitelists.SO_DELIVERABLE.contains(order.getStatus())) {
                throw new BusinessException(409, "只有财务已批准/已确认/处理中/部分发货状态的订单可以创建发货单")
                        .withHint("请刷新订单列表查看最新状态");
            }
            salesOrderItemRepository.findBySalesOrderId(request.getSalesOrderId()).forEach(item -> {
                sourceOrderItemsById.put(item.getId(), item);
                sourceOrderItemsByProduct
                        .computeIfAbsent(item.getProductTypeId(), ignored -> new ArrayList<>())
                        .add(item);
            });

            List<SalesDeliveryRecord> existing = deliveryRecordRepository.findBySalesOrderId(request.getSalesOrderId())
                    .stream().filter(d -> factoryId.equals(d.getFactoryId())).toList();
            if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
                SalesDeliveryRecord replay = existing.stream()
                        .filter(d -> Objects.equals(request.getIdempotencyKey(), d.getIdempotencyKey()))
                        .findFirst().orElse(null);
                if (replay != null) {
                    log.info("发货单幂等重放: factoryId={}, salesOrderId={}, deliveryNumber={}",
                            factoryId, request.getSalesOrderId(), replay.getDeliveryNumber());
                    return replay;
                }
            }

            Map<Long, BigDecimal> arrangedByOrderItem = effectiveMasterDeliveries(existing).stream()
                    .flatMap(delivery -> delivery.getItems().stream())
                    .filter(item -> item.getSalesOrderItemId() != null)
                    .collect(Collectors.groupingBy(
                            SalesDeliveryItem::getSalesOrderItemId,
                            Collectors.reducing(BigDecimal.ZERO,
                                    item -> item.getDeliveredQuantity() != null ? item.getDeliveredQuantity() : BigDecimal.ZERO,
                                    BigDecimal::add)));
            Map<Long, BigDecimal> requestedByOrderItem = new HashMap<>();
            for (CreateDeliveryRequest.DeliveryItemDTO requested : request.getItems()) {
                SalesOrderItem source = resolveDeliverySourceOrderItem(
                        requested, sourceOrderItemsById, sourceOrderItemsByProduct, true);
                BigDecimal requestedQuantity = requested.getDeliveredQuantity();
                if (requestedQuantity == null || requestedQuantity.signum() <= 0) {
                    throw new BusinessException(400, "发货数量必须大于0")
                            .withCode("DELIVERY_QUANTITY_INVALID")
                            .withHintTarget("deliveredQuantity");
                }
                requestedByOrderItem.merge(source.getId(), requestedQuantity, BigDecimal::add);
            }
            for (Map.Entry<Long, BigDecimal> requestedLine : requestedByOrderItem.entrySet()) {
                SalesOrderItem source = sourceOrderItemsById.get(requestedLine.getKey());
                BigDecimal ordered = source.getQuantity() != null ? source.getQuantity() : BigDecimal.ZERO;
                BigDecimal arranged = arrangedByOrderItem.getOrDefault(source.getId(), BigDecimal.ZERO);
                BigDecimal remaining = ordered.subtract(arranged).max(BigDecimal.ZERO);
                if (requestedLine.getValue().compareTo(remaining) > 0) {
                    throw new BusinessException(409,
                            "发货数量超过订单行剩余可安排数量（剩余 " + remaining
                                    + UnitDisplayNames.display(source.getUnit()) + "）")
                            .withCode("DELIVERY_QUANTITY_EXCEEDS_REMAINING")
                            .withHint("请刷新订单后按剩余可安排数量创建发货单")
                            .withHintTarget("deliveredQuantity");
                }
            }

            if (request.getDeliveryAddress() == null || request.getDeliveryAddress().isBlank()) {
                String fallbackAddress = order.getDeliveryAddress();
                if (fallbackAddress == null || fallbackAddress.isBlank()) {
                    fallbackAddress = deliveryCustomer.getShippingAddress();
                }
                request.setDeliveryAddress(fallbackAddress);
            }
        }

        if (request.getDeliveryAddress() == null || request.getDeliveryAddress().isBlank()) {
            throw new BusinessException(400, "客户/订单未维护收货地址，不能创建无地址发货单")
                    .withCode("DELIVERY_ADDRESS_REQUIRED")
                    .withHint("请在本次发货单补充地址，或维护订单/客户主档收货地址")
                    .withHintTarget("deliveryAddress");
        }

        String deliveryNumber = generateDeliveryNumber(factoryId);

        SalesDeliveryRecord record = new SalesDeliveryRecord();
        record.setFactoryId(factoryId);
        record.setDeliveryNumber(deliveryNumber);
        record.setSalesOrderId(request.getSalesOrderId());
        record.setRecordRole(request.getSalesOrderId() != null && !request.getSalesOrderId().isBlank() ? "MASTER" : "LEGACY");
        record.setIdempotencyKey(request.getIdempotencyKey());
        record.setCustomerId(request.getCustomerId());
        record.setDeliveryDate(request.getDeliveryDate());
        record.setDeliveryAddress(request.getDeliveryAddress());
        record.setLogisticsCompany(request.getLogisticsCompany());
        record.setTrackingNumber(request.getTrackingNumber());
        // Issue #740: 销售创建后直接进入 PENDING_WAREHOUSE_CONFIRM, 等仓库确认
        // (DRAFT 留给无 salesOrderId 的临时草稿, e.g. 内部样品 / 营销赠品).
        record.setStatus(request.getSalesOrderId() != null && !request.getSalesOrderId().isEmpty()
                ? SalesDeliveryStatus.PENDING_SPLIT
                : SalesDeliveryStatus.DRAFT);
        record.setShippedBy(userId);
        record.setRemark(request.getRemark());

        record = deliveryRecordRepository.save(record);

        // 创建发货行项目
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CreateDeliveryRequest.DeliveryItemDTO itemDTO : request.getItems()) {
            SalesDeliveryItem item = new SalesDeliveryItem();
            item.setDeliveryRecordId(record.getId());
            item.setProductTypeId(itemDTO.getProductTypeId());
            item.setProductName(itemDTO.getProductName());
            item.setDeliveredQuantity(itemDTO.getDeliveredQuantity());
            // 写入点 4/5 (createDeliveryRecord, 客户端入参) — prod 实测
            // sales_delivery_items.unit 也有 box×2, 与销售行同源
            item.setUnit(storageUnit(factoryId, itemDTO.getUnit()));
            SalesOrderItem sourceOrderItem = resolveDeliverySourceOrderItem(
                    itemDTO, sourceOrderItemsById, sourceOrderItemsByProduct,
                    request.getSalesOrderId() != null && !request.getSalesOrderId().isBlank());
            item.setSalesOrderItemId(sourceOrderItem != null ? sourceOrderItem.getId() : itemDTO.getSalesOrderItemId());
            if (sourceOrderItem != null && !Objects.equals(sourceOrderItem.getUnit(), itemDTO.getUnit())) {
                throw new BusinessException(400, "发货单位与销售订单行不一致")
                        .withCode("SALES_ORDER_ITEM_UNIT_MISMATCH")
                        .withHintTarget("unit");
            }
            if (sourceOrderItem != null && sourceOrderItem.getPackagingSpecId() != null) {
                if (itemDTO.getPackagingSpecId() != null
                        && !itemDTO.getPackagingSpecId().isBlank()
                        && !Objects.equals(itemDTO.getPackagingSpecId(), sourceOrderItem.getPackagingSpecId())) {
                    throw new BusinessException(400, "发货箱规与销售订单行不一致")
                            .withCode("PACKAGING_SPEC_ORDER_LINE_MISMATCH")
                            .withHintTarget("packagingSpecId");
                }
                copyPackagingSnapshot(sourceOrderItem, item);
            } else {
                applyPackagingSelection(
                        factoryId, itemDTO.getProductTypeId(), itemDTO.getUnit(),
                        itemDTO.getPackagingSpecId(), item);
            }
            item.setUnitPrice(itemDTO.getUnitPrice());
            // T4-D5 (issue #553): propagate source warehouse code from the request DTO.
            // Inventory allocation logic does NOT yet filter by this column — that's
            // deferred follow-up. This line is the data-contract propagation only.
            item.setSourceWarehouseCode(itemDTO.getSourceWarehouseCode());
            item.setRemark(itemDTO.getRemark());
            record.getItems().add(item);

            if (itemDTO.getUnitPrice() != null) {
                totalAmount = totalAmount.add(itemDTO.getDeliveredQuantity().multiply(itemDTO.getUnitPrice()));
            }
        }

        record.setTotalAmount(totalAmount);
        record = deliveryRecordRepository.save(record);

        // Round 9 Fix (R8-α Gap #1 template): persist Canvas V3 dynamic field values
        // via DynamicFieldService. Customer-configured fields (运输温度, 司机姓名,
        // 车牌号, 温度监控仪器型号 etc.) now land in the cf_* columns of
        // sales_delivery_records. Previously the DTO had no customFields slot so
        // frontend Canvas form submissions silently dropped the values.
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "delivery", record.getId(), request.getCustomFields());
            } catch (Exception e) {
                log.warn("Canvas dynamic fields save failed for delivery {}: {}", record.getId(), e.getMessage());
            }
        }

        // Round 9 Fix (R8-α Gap #1 template): publish event so Canvas-configured
        // trigger chains on the `delivery` module can fire (e.g. 物流推送 / 冷链启动 /
        // WMS 同步). TriggerChainExecutor.HANDLED_EVENTS has been extended to recognize
        // SalesDeliveryCreatedEvent.
        if (applicationEventPublisher != null) {
            try {
                applicationEventPublisher.publishEvent(new com.cretas.aims.event.SalesDeliveryCreatedEvent(
                        this, factoryId, record.getId(), record.getDeliveryNumber(),
                        record.getCustomerId(), record.getSalesOrderId(),
                        record.getDeliveryDate(), record.getTotalAmount()));
            } catch (Exception e) {
                log.warn("Publish SalesDeliveryCreatedEvent failed: {}", e.getMessage());
            }
        }

        log.info("创建发货单: factoryId={}, deliveryNumber={}, items={}, customFields={}",
                factoryId, deliveryNumber, request.getItems().size(),
                request.getCustomFields() == null ? 0 : request.getCustomFields().size());
        return record;
    }

    @Override
    @Transactional
    public SalesDeliveryRecord createDeliveryShipment(
            String factoryId, String parentDeliveryId, CreateDeliveryShipmentRequest request, Long userId) {
        SalesDeliveryRecord parentSnapshot = getDeliveryRecordById(factoryId, parentDeliveryId);
        if (parentSnapshot.getSalesOrderId() == null) {
            throw new BusinessException(409, "母发货单缺少销售订单关联")
                    .withCode("DELIVERY_SALES_ORDER_REQUIRED_FOR_SHIPMENT")
                    .withHint("该母发货单未关联销售订单，无法创建分批发运；"
                            + "请先在发货单中关联对应的销售订单，或改用整单发货流程");
        }
        salesOrderRepository.findByIdAndFactoryIdForUpdate(parentSnapshot.getSalesOrderId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在或不属于当前组织"));
        SalesDeliveryRecord parent = lockDeliveryForMutation(factoryId, parentDeliveryId);
        if (!"MASTER".equals(parent.getRecordRole()) || parent.getParentDeliveryId() != null) {
            throw new BusinessException(409, "只能在母发货单下创建分批发运")
                    .withCode("DELIVERY_MASTER_REQUIRED")
                    .withHint("请选择一个母发货单（而非分批发运）来创建子发运单");
        }
        List<SalesDeliveryRecord> children = getDeliveryShipments(factoryId, parentDeliveryId);
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            SalesDeliveryRecord replay = children.stream()
                    .filter(child -> Objects.equals(request.getIdempotencyKey(), child.getIdempotencyKey()))
                    .findFirst().orElse(null);
            if (replay != null) return replay;
        }

        Map<Long, SalesDeliveryItem> parentItems = parent.getItems().stream()
                .collect(Collectors.toMap(SalesDeliveryItem::getId, item -> item));
        Set<Long> requestedParentItemIds = new HashSet<>();
        for (CreateDeliveryShipmentRequest.Item requested : request.getItems()) {
            if (requested.getParentDeliveryItemId() == null
                    || !requestedParentItemIds.add(requested.getParentDeliveryItemId())) {
                throw new BusinessException(400, "同一母发货明细在一次子发运中只能出现一次")
                        .withCode("DUPLICATE_PARENT_DELIVERY_ITEM")
                        .withHintTarget("parentDeliveryItemId");
            }
        }
        Map<Long, BigDecimal> arrangedByOrderItem = children.stream()
                .filter(child -> child.getStatus() != SalesDeliveryStatus.CANCELLED
                        && child.getStatus() != SalesDeliveryStatus.RETURNED)
                .flatMap(child -> child.getItems().stream())
                .filter(item -> item.getSalesOrderItemId() != null)
                .collect(Collectors.groupingBy(
                        SalesDeliveryItem::getSalesOrderItemId,
                        Collectors.reducing(BigDecimal.ZERO,
                                item -> item.getDeliveredQuantity() != null ? item.getDeliveredQuantity() : BigDecimal.ZERO,
                                BigDecimal::add)));

        Map<Long, BigDecimal> requestedByOrderItem = new HashMap<>();
        for (CreateDeliveryShipmentRequest.Item requested : request.getItems()) {
            SalesDeliveryItem source = parentItems.get(requested.getParentDeliveryItemId());
            if (source == null) {
                throw new BusinessException(400, "子发运明细不属于当前母发货单")
                        .withCode("PARENT_DELIVERY_ITEM_INVALID");
            }
            requestedByOrderItem.merge(source.getSalesOrderItemId(), requested.getQuantity(), BigDecimal::add);
        }
        if ("FULL".equalsIgnoreCase(request.getDeliveryMode())) {
            for (SalesDeliveryItem source : parentItems.values()) {
                BigDecimal parentQty = source.getDeliveredQuantity() != null ? source.getDeliveredQuantity() : BigDecimal.ZERO;
                BigDecimal remaining = parentQty.subtract(
                        arrangedByOrderItem.getOrDefault(source.getSalesOrderItemId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
                BigDecimal requested = requestedByOrderItem.get(source.getSalesOrderItemId());
                if (requested == null || requested.compareTo(remaining) != 0) {
                    throw new BusinessException(409, "一次性全量发运必须覆盖母单每一行的全部剩余数量")
                            .withCode("DELIVERY_FULL_QUANTITY_REQUIRED")
                            .withHint("请为每一行填写等于剩余数量的分批发运数量，或改用「部分」发运模式")
                            .withHintTarget("items");
                }
            }
        }

        int sequence = children.stream().map(SalesDeliveryRecord::getShipmentSequence)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
        SalesDeliveryRecord child = new SalesDeliveryRecord();
        child.setFactoryId(factoryId);
        child.setDeliveryNumber(parent.getDeliveryNumber() + "-" + String.format("%02d", sequence));
        child.setSalesOrderId(parent.getSalesOrderId());
        child.setParentDeliveryId(parent.getId());
        child.setRecordRole("SHIPMENT");
        child.setShipmentSequence(sequence);
        child.setIdempotencyKey(request.getIdempotencyKey());
        child.setCustomerId(parent.getCustomerId());
        child.setDeliveryDate(request.getActualShipmentDate() != null
                ? request.getActualShipmentDate() : request.getPlannedShipmentDate());
        child.setDeliveryAddress(hasText(request.getDeliveryAddress())
                ? request.getDeliveryAddress().trim() : parent.getDeliveryAddress());
        child.setLogisticsCompany(request.getLogisticsCompany());
        child.setTrackingNumber(request.getTrackingNumber());
        child.setDeliveryMethod(hasText(request.getDeliveryMethod())
                ? request.getDeliveryMethod().trim().toUpperCase() : "LOGISTICS");
        if ("LOGISTICS".equals(child.getDeliveryMethod())) {
            if (!hasText(child.getLogisticsCompany())) {
                throw new BusinessException(400, "物流配送必须填写物流公司")
                        .withCode("SHIPMENT_LOGISTICS_COMPANY_REQUIRED");
            }
            if (!hasText(child.getTrackingNumber())) {
                throw new BusinessException(400, "物流配送必须填写运单号")
                        .withCode("SHIPMENT_TRACKING_REQUIRED");
            }
        }
        child.setRemark(request.getRemark());
        child.setStatus(SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        child.setShippedBy(userId);
        if (!hasText(child.getDeliveryAddress())) {
            throw new BusinessException(400, "子发运单必须包含发货地址快照")
                    .withCode("DELIVERY_ADDRESS_REQUIRED");
        }

        child.setTotalAmount(BigDecimal.ZERO);
        SalesDeliveryRecord saved = deliveryRecordRepository.save(child);

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CreateDeliveryShipmentRequest.Item requested : request.getItems()) {
            SalesDeliveryItem source = parentItems.get(requested.getParentDeliveryItemId());
            if (source == null) {
                throw new BusinessException(400, "子发运明细不属于当前母发货单")
                        .withCode("PARENT_DELIVERY_ITEM_INVALID");
            }
            Long lineId = source.getSalesOrderItemId();
            BigDecimal parentQty = source.getDeliveredQuantity() != null ? source.getDeliveredQuantity() : BigDecimal.ZERO;
            BigDecimal arranged = arrangedByOrderItem.getOrDefault(lineId, BigDecimal.ZERO);
            BigDecimal remaining = parentQty.subtract(arranged).max(BigDecimal.ZERO);
            if (requested.getQuantity() == null || requested.getQuantity().compareTo(BigDecimal.ZERO) <= 0
                    || requested.getQuantity().compareTo(remaining) > 0) {
                throw new BusinessException(409, "子发运数量超过母单剩余可安排数量（剩余 " + remaining
                        + UnitDisplayNames.display(source.getUnit()) + "）")
                        .withCode("SHIPMENT_QUANTITY_EXCEEDS_PARENT_REMAINING")
                        .withHint("请将本行分批发运数量调整为不超过剩余可安排数量")
                        .withHintTarget("quantity");
            }
            SalesDeliveryItem item = new SalesDeliveryItem();
            item.setSalesOrderItemId(source.getSalesOrderItemId());
            item.setProductTypeId(source.getProductTypeId());
            item.setProductName(source.getProductName());
            item.setDeliveredQuantity(requested.getQuantity());
            // 写入点 5/5 (createDeliveryShipment, 从母发运行复制) — 同 3/5, 新行不带英文
            item.setUnit(storageUnit(factoryId, source.getUnit()));
            item.setUnitPrice(source.getUnitPrice());
            item.setSourceWarehouseCode(source.getSourceWarehouseCode());
            item.setPackagingSpecId(source.getPackagingSpecId());
            item.setPackagingSpecName(source.getPackagingSpecName());
            item.setPackagingUnit(source.getPackagingUnit());
            item.setPackagingBaseUnit(source.getPackagingBaseUnit());
            item.setPackagingFactor(source.getPackagingFactor());
            item.setDeliveryRecordId(saved.getId());
            saved.getItems().add(item);
            if (item.getUnitPrice() != null) {
                totalAmount = totalAmount.add(item.getUnitPrice().multiply(item.getDeliveredQuantity()));
            }
        }
        saved.setTotalAmount(totalAmount);
        saved = deliveryRecordRepository.save(saved);
        updateMasterDeliveryStatus(parent, children, saved);
        // 缺陚42 (owner 拍板 「出库照抄入库」): 子发运单创建后自动按 FIFO 预填成品批次分配，
        // 不等用户另外点「分配批次」才看到推荐 —— 镜像入库侧 PendingPurchaseReceivingPanel.vue
        // openReceive() 的原则: 一开对话框就把每行默认填成 remainingReceivableQuantity。
        autoPrefillFifoAllocations(factoryId, saved);
        return saved;
    }

    /**
     * 缺陚42: 子发运单创建后结果默认没有任何批次分配, 仅在用户手动点「分配批次」时才按 FIFO 预填推荐
     * ({@code openBatchAllocDialog} @ web-admin detail.vue). 与入库侧「一开对话框就默认满额」的原则不一致 ——
     * 现在创建子发运单时就直接调用与 {@link #shipDelivery} P0-13 同一个 {@code autoAllocateFifo},
     * 把默认推荐提前到创建那一刻, 用户在「分配批次/发货记录」看到的就是已经预填好的结果, 可直接豆改。
     *
     * <p>—— 不会堆叠两次分配: {@code allocateBatches} 内部是先删后写 (见 {@code allocateBatches}
     * 方法中的 {@code deleteByFactoryIdAndDeliveryItemId}), 而本方法只在刚创建的新子发运单上调用一次, 那时每行肯定还没有
     * 任何分配记录 —— 不存在“重复预填覆盖用户手动编辑”的风险。
     *
     * <p>物料行没有成品批次可分配 ({@code isMaterialLine} 判定, 与 shipDelivery P0-13 同一处豁免) —— 跳过。
     * 库存不够时 {@code autoAllocateFifo} 返回 false 且不写任何记录 (“不做部分分配”, 见
     * {@code SalesDeliveryBatchAllocationServiceImpl.autoAllocateFifo} 头部注释) —— 那一行保持未分配、诚实显示,
     * 不报错也不让整张子发运单创建失败 —— 缺口留到「分配批次」手工处理或发货时 shipDelivery 的 P0-13 兵底重试。
     * 分配总量恰守 #484 定的 ≤ 发货行数量天花板 —— {@code autoAllocateFifo} 本身按 requiredQty 做上限, 不会超发。
     */
    private void autoPrefillFifoAllocations(String factoryId, SalesDeliveryRecord shipment) {
        if (batchAllocationService == null || shipment == null || shipment.getItems() == null) {
            return;
        }
        for (SalesDeliveryItem item : shipment.getItems()) {
            if (item.getDeliveredQuantity() == null || item.getDeliveredQuantity().signum() <= 0) {
                continue;
            }
            if (item.getProductTypeId() == null || item.getProductTypeId().isBlank()) {
                continue;
            }
            if (isMaterialLine(factoryId, item.getProductTypeId())) {
                continue;
            }
            try {
                batchAllocationService.autoAllocateFifo(
                        factoryId, String.valueOf(item.getId()), item.getProductTypeId(),
                        item.getDeliveredQuantity(), item.getUnit(), item.getSourceWarehouseCode());
            } catch (BusinessException e) {
                // 诚实降级: 预填是便利功能, 不是硬闸 —— 预填失败 (比如单位换算冲突) 不该拖垒
                // 子发运单创建这个更重要的动作。用户仍能去「分配批次」手工处理。
                log.warn("[缺陚42] 子发运创建后自动FIFO预填失败, 转手工分配: factoryId={}, itemId={}, reason={}",
                        factoryId, item.getId(), e.getMessage());
            }
        }
    }

    @Override
    public List<SalesDeliveryRecord> getDeliveryShipments(String factoryId, String parentDeliveryId) {
        SalesDeliveryRecord parent = getDeliveryRecordById(factoryId, parentDeliveryId);
        if (parent.getSalesOrderId() == null) return List.of();
        return deliveryRecordRepository.findBySalesOrderId(parent.getSalesOrderId()).stream()
                .filter(child -> factoryId.equals(child.getFactoryId()))
                .filter(child -> parentDeliveryId.equals(child.getParentDeliveryId()))
                .sorted(Comparator.comparing(child -> child.getShipmentSequence() == null ? Integer.MAX_VALUE : child.getShipmentSequence()))
                .toList();
    }

    @Override
    @Transactional
    public SalesDeliveryRecord cancelDeliveryShipment(
            String factoryId, String parentDeliveryId, String shipmentId, Long userId) {
        SalesDeliveryRecord childSnapshot = getDeliveryRecordById(factoryId, shipmentId);
        lockSalesOrderForDelivery(factoryId, childSnapshot);
        SalesDeliveryRecord parent = lockDeliveryForMutation(factoryId, parentDeliveryId);
        SalesDeliveryRecord child = lockDeliveryForMutation(factoryId, shipmentId);
        if (!Objects.equals(parentDeliveryId, child.getParentDeliveryId()) || !"SHIPMENT".equals(child.getRecordRole())) {
            throw new BusinessException(404, "子发运单不存在或不属于该母发货单");
        }
        if (child.getStatus() == SalesDeliveryStatus.CANCELLED) return child;
        if (child.getStatus() == SalesDeliveryStatus.SHIPPED || child.getStatus() == SalesDeliveryStatus.DELIVERED) {
            throw new BusinessException(409, "已确认发货的子发运单不能直接取消")
                    .withCode("DELIVERY_SHIPMENT_ALREADY_SHIPPED")
                    .withHint("该子发运单已确认发货，无法直接取消；如需撤销请走销售退货流程，对已发货数量做退货处理");
        }
        if (batchAllocationService != null) {
            child.getItems().forEach(item -> batchAllocationService.clearAllocations(factoryId, String.valueOf(item.getId())));
        }
        child.setStatus(SalesDeliveryStatus.CANCELLED);
        SalesDeliveryRecord saved = deliveryRecordRepository.save(child);
        updateMasterDeliveryStatus(parent, getDeliveryShipments(factoryId, parentDeliveryId), null);
        return saved;
    }

    private void updateMasterDeliveryStatus(
            SalesDeliveryRecord parent, List<SalesDeliveryRecord> existingChildren, SalesDeliveryRecord additionalChild) {
        List<SalesDeliveryRecord> children = new ArrayList<>(existingChildren == null ? List.of() : existingChildren);
        if (additionalChild != null && children.stream().noneMatch(child -> child.getId().equals(additionalChild.getId()))) {
            children.add(additionalChild);
        }
        List<SalesDeliveryRecord> effective = children.stream()
                .filter(child -> child.getStatus() != SalesDeliveryStatus.CANCELLED
                        && child.getStatus() != SalesDeliveryStatus.RETURNED).toList();
        BigDecimal planned = parent.getItems().stream()
                .map(SalesDeliveryItem::getDeliveredQuantity).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal arranged = effective.stream().flatMap(child -> child.getItems().stream())
                .map(SalesDeliveryItem::getDeliveredQuantity).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shipped = effective.stream()
                .filter(child -> child.getStatus() == SalesDeliveryStatus.SHIPPED
                        || child.getStatus() == SalesDeliveryStatus.DELIVERED)
                .flatMap(child -> child.getItems().stream())
                .map(SalesDeliveryItem::getDeliveredQuantity).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal delivered = effective.stream()
                .filter(child -> child.getStatus() == SalesDeliveryStatus.DELIVERED)
                .flatMap(child -> child.getItems().stream())
                .map(SalesDeliveryItem::getDeliveredQuantity).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        SalesDeliveryStatus state;
        if (planned.signum() > 0 && delivered.compareTo(planned) >= 0) state = SalesDeliveryStatus.DELIVERED;
        else if (planned.signum() > 0 && shipped.compareTo(planned) >= 0) state = SalesDeliveryStatus.SHIPPED;
        else if (shipped.signum() > 0) state = SalesDeliveryStatus.PARTIALLY_SHIPPED;
        else if (planned.signum() > 0 && arranged.compareTo(planned) >= 0) state = SalesDeliveryStatus.FULLY_SCHEDULED;
        else if (arranged.signum() > 0) state = SalesDeliveryStatus.PARTIALLY_SCHEDULED;
        else state = SalesDeliveryStatus.PENDING_SPLIT;
        parent.setStatus(state);
        deliveryRecordRepository.save(parent);
    }

    @Override
    @Transactional
    public SalesDeliveryRecord shipDelivery(String factoryId, String deliveryId, Long userId) {
        SalesDeliveryRecord record = lockDeliveryHierarchyForMutation(factoryId, deliveryId);
        if ("MASTER".equals(record.getRecordRole())) {
            throw new BusinessException(409, "母发货单不能直接确认发货，请先创建子发运单")
                    .withCode("DELIVERY_SHIPMENT_REQUIRED")
                    .withHint("完整发货也应创建一张全量子发运单，再分配批次并确认发货");
        }
        if (record.getStatus() != SalesDeliveryStatus.DRAFT
                && record.getStatus() != SalesDeliveryStatus.PICKED
                && record.getStatus() != SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM) {
            throw new BusinessException(409, "只有草稿/待仓库确认/已拣货状态的发货单可以发货")
                    .withHint("请刷新发货单列表查看最新状态");
        }

        boolean legacyMethodUnknown = !hasText(record.getDeliveryMethod())
                && (record.getRecordRole() == null || "LEGACY".equals(record.getRecordRole()));
        String deliveryMethod = hasText(record.getDeliveryMethod())
                ? record.getDeliveryMethod().trim().toUpperCase()
                : (legacyMethodUnknown ? "LEGACY" : "LOGISTICS");
        if (record.getDeliveryDate() == null) {
            throw new BusinessException(400, "确认发货前必须填写实际发运日期")
                    .withCode("SHIPMENT_DATE_REQUIRED");
        }
        if ("LOGISTICS".equals(deliveryMethod)
                && (!hasText(record.getLogisticsCompany()) || !hasText(record.getTrackingNumber()))) {
            throw new BusinessException(400, "物流发运必须填写物流公司和运单号")
                    .withCode("SHIPMENT_TRACKING_REQUIRED")
                    .withHint("自提或自送请明确选择对应配送方式，不能用空运单号代替");
        }
        if (!java.util.Set.of("LOGISTICS", "SELF_PICKUP", "SELF_DELIVERY", "LEGACY").contains(deliveryMethod)) {
            throw new BusinessException(400, "不支持的配送方式: " + deliveryMethod)
                    .withCode("DELIVERY_METHOD_INVALID");
        }

        // P0-13 强制批次分配校验：发货行必须已完成批次分配才能发货
        if (batchAllocationService != null) {
            for (SalesDeliveryItem item : record.getItems()) {
                String itemIdStr = String.valueOf(item.getId());
                // 物料行没有【成品】批次可分配 —— 它走 material_batches 的 FIFO,
                // 在 deductMaterialInventory 里扣。不豁免的话这道闸会把物料发货全部拦死。
                if (isMaterialLine(factoryId, item.getProductTypeId())) {
                    continue;
                }
                if (!batchAllocationService.isFullyAllocated(factoryId, itemIdStr)) {
                    String productLabel = item.getProductName() != null
                            ? item.getProductName()
                            : (item.getProductTypeId() != null ? item.getProductTypeId() : "未知产品");

                    /*
                     * 🔴 2026-08-20 owner 拍板: 从「强制人工分配」改为「默认自动按先进先出」。
                     *
                     * 原来这里直接抛 409, 提示「请在发货记录 Tab 点分配批次」——
                     * 而仓管在手机上做发货确认, 那句话对他是条死路(他不在电脑前)。
                     * 更根本的问题是**现场用不起来**: owner 原话「批次他们自己都管不过来,
                     * 我还得帮他录入」; 追溯改为按订单号/销售单做, 不依赖批次粒度。
                     *
                     * ⚠️ 自动分配**不丢数据**: HACCP 放行闸(下面 assertHaccpReleaseAllowed)
                     * 与成本核算读的都是「已分配的批次」, 不关心是人点的还是系统算的。
                     * 放弃的只是「实际搬走的那箱 ≠ FIFO 推荐时能人工纠正」这一项。
                     *
                     * ⚠️ 原始出处是 P0-13「昆山六扇门客户需求: 强制批次追溯」——
                     * 批次记录仍然完整, 变的是不再要求人工确认。
                     *
                     * ⛔ 库存真不够时仍然拦, 但话要说清「还差多少、去哪补」,
                     *    ⛔ 不能再让他去点一个手机上没有的按钮。
                     */
                    boolean allocated = batchAllocationService.autoAllocateFifo(
                            factoryId, itemIdStr, item.getProductTypeId(),
                            item.getDeliveredQuantity(), item.getUnit(), item.getSourceWarehouseCode());

                    if (!allocated) {
                        // 🔴 2026-09-02 六扇门实拍走查抓到: 原来这里把内部行 ID (itemIdStr, 数据库
                        // 自增主键) 拼进了报错正文 ——「发货行 255（产品：熟肉饼）可用库存不足」。
                        // 255 对仓管没有任何可操作性; 产品名才是他能核对的东西, 去掉数字, 其余
                        // (为什么 + 两条出路) 原样保留。
                        throw new BusinessException(409, "产品「" + productLabel + "」可用库存不足，无法发货")
                                .withHint("仓库里这个产品的可用批次不够本次发货量。"
                                        + "请先入库补货，或从其它库区调拨后再确认发货。")
                                .withHintTarget("库存");
                    }
                }
            }
        }

        // 2026-08-02 owner 拍板: HACCP 放行闸 —— 在批次分配【之后】跑, 因为它要按已分配批次判定
        assertHaccpReleaseAllowed(factoryId, record);


        // FIFO 扣减成品库存
        for (SalesDeliveryItem item : record.getItems()) {
            deductFinishedGoodsInventory(factoryId, record.getSalesOrderId(), item);
        }

        record.setStatus(SalesDeliveryStatus.SHIPPED);
        // shipped_by is the durable warehouse-confirm actor after shipment. Before
        // confirmation it identifies the sales creator for legacy compatibility.
        record.setShippedBy(userId);

        // 更新销售订单状态
        if (record.getSalesOrderId() != null) {
            updateOrderDeliveryStatus(record);
        }

        record = deliveryRecordRepository.save(record);
        if (record.getParentDeliveryId() != null) {
            SalesDeliveryRecord parent = getDeliveryRecordById(factoryId, record.getParentDeliveryId());
            updateMasterDeliveryStatus(parent, getDeliveryShipments(factoryId, parent.getId()), null);
        }
        log.info("发货确认: deliveryId={}, deliveryNumber={}", deliveryId, record.getDeliveryNumber());

        // 自动创建应收账款（销售发货 → AR_INVOICE）
        if (record.getSalesOrderId() != null) {
            try {
                SalesOrder order = salesOrderRepository.findById(record.getSalesOrderId()).orElse(null);
                if (order != null && order.getCustomerId() != null) {
                    // 🔴🔒 2026-08-28 会计正确性修复: 应收金额必须反映【实发】, 不是【订单】。
                    //
                    // 仓管在「确认发货」时可以覆盖实际发货数量 (warehouseConfirmDelivery,
                    // 客户原话「车装不下 / 现场少装」)。覆盖后重算的是**发货单** totalAmount,
                    // 而这里旧实现取的是**订单** totalAmount —— 两个数从此分家。prod 实测:
                    //   SO-20260828-0012 订 2 盒 → 仓管填 5 → 库存真扣 5, 发运单 110.62,
                    //                    应收只挂 44.25, 订单 COMPLETED ⇒ 3 盒没人收钱 (无条件错)
                    //   SO-20260828-0013 订 4 盒 → 仓管填 2 → 发运单 44.25,
                    //                    应收挂 88.50 ⇒ 客户被多收
                    //
                    // ⛔ 本修复只管会计正确性, **不表态**「超发允不允许、允许多少」——
                    //   采购侧有 overReceiveRate + purchase_exceptions 的先例, 销售侧两样都没有,
                    //   要不要照抄是产品决定, 不是这里能定的。无论策略怎么定,「应收 = 实发」都成立。
                    //
                    // 幂等键从「每 SO」改成「每子发运单」(SALES_DELIVERY, deliveryId):
                    //   · 同一发运单重复确认 → 幂等命中, 不重复挂账 (原防双击语义保持)
                    //   · 分批发货的不同发运单 → 各挂各的实发额, 累计 = 实发总额
                    //     (旧实现在这里 `arExists` 直接 skip, 第二批永远不挂账 → 少收)
                    // DB 侧唯一键同步换成 uk_aat_ar_invoice_per_shipment (V20261031_06) ——
                    // ⚠️ 采购侧 2026-07-02 只改代码没改约束, 那次修复从未生效, 直到 8/03 分批
                    //   到货第二批入不了库才被发现。这里代码与约束在同一个 PR 里改。
                    //
                    // ⚠️ 只有子发运单会走到这: shipDelivery 对 record_role='MASTER' 直接抛 409,
                    //   所以母单不会重复挂一笔。
                    //
                    // ⛔ 金额缺失时 recordReceivableIfAbsent 诚实跳过(返回 null), 不回落到
                    //   order.getTotalAmount() —— 那个回落正是本次要修的缺陷。
                    //
                    // 【切换缝】存量「整单挂账」的在飞订单保持旧行为, 否则客户被双重计费:
                    //   改动前一张 SO 只挂一笔全额 AR_INVOICE (source_id 为 NULL)。若这样一张
                    //   还有未发量的订单在升级后再发一批, 新逻辑会在整单应收之上**再挂一笔**。
                    //   prod 实测 SO-20260828-0013 正是此形: PARTIAL_DELIVERED, 已挂 88.50 整单额,
                    //   还剩 2 盒 —— 不设这道缝, 那 2 盒发货会再挂 44.25, 合计 132.75。
                    // ⇒ 存量订单的账不追溯改写(它们的错账另行人工调整), 新订单走正确口径。
                    boolean legacyWholeOrderAr = arApTransactionRepository != null
                            && arApTransactionRepository.existsLegacyArInvoiceWithoutSource(factoryId, order.getId());
                    if (legacyWholeOrderAr) {
                        log.info("应收自动挂账跳过(存量整单挂账, 保持旧行为避免双重计费): salesOrderId={}, deliveryId={}",
                                order.getId(), record.getId());
                    } else {
                        // 🔴 2026-08-28 合并口径 (#292 × #302): 两个改动**都要**, 且不能二选一。
                        //
                        //   #292 修的是【口径】: 应收要含税 —— 旧实现传 order.getTotalAmount()
                        //        (未税), 而同一次发货生成的销项发票是含税的 ⇒ 系统自己跟自己矛盾
                        //        (发票说客户欠 250.00 / 应收台账说欠 221.24)。生产 7/7 全中。
                        //   #302 修的是【数量基准】: 应收要按【实发】而不是订单 —— 仓管把实发数量
                        //        从 2 改成 5, 库存真扣 5 而应收只记 2 的钱 (生产实测 SO-20260828-0012)。
                        //
                        // ⛔ 陷阱: 直接取 #302 的 record.getTotalAmount() 会**静默退回未税** ——
                        //    sales_delivery_records.total_amount 是 Σ 实发量 × 单价, 而那个单价是
                        //    **未税**的 (实测 DLV-20260828-9106-01 发货单额 221.24 == 订单未税额)。
                        //    而且发货单上**没有任何税字段** (records 只有 total_amount,
                        //    items 只有 unit_price) ⇒ 税率必须回订单行借。
                        //    ⚠️ 那样合并**不会有任何测试变红**: #292 的用例钉订单口径、#302 的用例
                        //      钉实发口径, 各自都还绿 —— 这一格是两个 PR 的共同盲区。
                        //      由 shipArMergedCaliber_* 用例钉住。
                        //
                        // ⇒ 正确形态 = Σ(实发量 × 单价 × (1 + 税率/100)), 税率从
                        //   sales_delivery_items.salesOrderItemId → sales_order_items.taxRate 借。
                        //   与采购侧 PurchaseServiceImpl#taxInclusiveReceivedValue 同一范式与同一取整。
                        BigDecimal shippedValue = taxInclusiveShippedValue(order, record);
                        ArApTransaction ar = arApService.recordReceivableIfAbsent(
                                factoryId, order.getCustomerId(), order.getId(),
                                SOURCE_DOC_TYPE_SALES_DELIVERY, record.getId(),
                                shippedValue, LocalDate.now().plusDays(30), userId,
                                "销售发货自动挂账-" + record.getDeliveryNumber());
                        if (ar != null) {
                            log.info("自动创建应收(实发价税合计): orderId={}, deliveryId={}, 实发含税={}, 发货单未税={}, 订单含税={}, transactionId={}",
                                    order.getId(), record.getId(), shippedValue,
                                    record.getTotalAmount(), order.getPayableAmount(), ar.getId());
                        }
                    }
                }
            } catch (BusinessException e) {
                log.warn("应收自动挂账跳过(可能已存在): {}", e.getMessage());
            } catch (Exception e) {
                log.error("应收自动挂账失败: deliveryId={}", deliveryId, e);
            }
        }

        return record;
    }


    /**
     * HACCP 放行闸 —— 出货确认时按<b>已分配批次</b>校验关键控制点监控。
     *
     * <h2>这条判定此前【只存在于 AI 工具里】</h2>
     *
     * 2026-08-02 查证: "零偏离且有记录才算通过、无记录不可放行"只写在
     * {@code HaccpCheckpointReviewTool} / {@code HaccpStatusQueryTool},
     * <b>没有任何出货路径在用</b> —— 只有当有人用 AI 问的时候才存在。
     *
     * <h2>两档而不是一个开关(owner 明确要求"别把正常批次拦死")</h2>
     *
     * <ul>
     *   <li><b>有偏离 → 硬拦</b>。偏离是实打实的食品安全事件。</li>
     *   <li><b>无监控记录 → 只告警, 放行</b>。HACCP 监控在现场若录得不全,
     *       把"没录"当成"不合格"会把大量正常批次拦死 —— 那是把数据缺口变成停产。
     *       等覆盖率上来再考虑收紧, 是<b>另一个决定</b>。</li>
     * </ul>
     *
     * <p>任一依赖未注入时整闸跳过, 与 P0-13 批次分配校验同样的可选注入风格。
     */
    private void assertHaccpReleaseAllowed(String factoryId, SalesDeliveryRecord record) {
        if (haccpMonitoringRecordRepository == null || batchAllocationService == null) {
            return;
        }
        java.util.List<String> deviating = new java.util.ArrayList<>();
        java.util.List<String> unmonitored = new java.util.ArrayList<>();

        for (SalesDeliveryItem item : record.getItems()) {
            for (com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation alloc
                    : batchAllocationService.listByDeliveryItem(factoryId, String.valueOf(item.getId()))) {
                String batchNumber = alloc.getBatchNumber();
                if (batchNumber == null || batchNumber.isBlank()) {
                    continue;
                }
                java.util.List<com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord> records =
                        haccpMonitoringRecordRepository
                                .findByFactoryIdAndBatchNumberOrderByMonitoringTimeDesc(factoryId, batchNumber);
                if (records.isEmpty()) {
                    unmonitored.add(batchNumber);
                } else if (records.stream().anyMatch(
                        com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord::isDeviation)) {
                    deviating.add(batchNumber);
                }
            }
        }

        if (!unmonitored.isEmpty()) {
            log.warn("[HACCP放行闸] factory={} delivery={} 以下批次无 HACCP 监控记录, 已放行: {}",
                    factoryId, record.getId(), unmonitored);
        }
        if (!deviating.isEmpty()) {
            throw new BusinessException(409,
                    "以下批次的 HACCP 关键控制点存在偏离，不可发货：" + String.join("、", deviating))
                    .withCode("HACCP_DEVIATION_BLOCKS_RELEASE")
                    .withHint("请先在「质量管理 → HACCP 监控」中处理这些批次的偏离记录，"
                            + "或改分配其他批次后重新确认发货");
        }
    }

    @Override
    @Transactional
    public SalesDeliveryRecord confirmDelivered(String factoryId, String deliveryId) {
        SalesDeliveryRecord record = lockDeliveryHierarchyForMutation(factoryId, deliveryId);
        if (record.getStatus() != SalesDeliveryStatus.SHIPPED) {
            throw new BusinessException(409, "只有已发货状态的发货单可以确认签收")
                    .withHint("请刷新发货单列表查看最新状态");
        }
        if (record.getSignedByName() == null || record.getSignedByName().isBlank()
                || record.getSignaturePhotoUrls() == null
                || record.getSignaturePhotoUrls().isBlank()
                || "[]".equals(record.getSignaturePhotoUrls().trim())) {
            throw new BusinessException(409, "确认签收前必须填写签收人并上传至少一张客户签收凭证")
                    .withCode("SALES_DELIVERY_SIGNATURE_REQUIRED")
                    .withHint("请在销售订单的发货记录中点击“签收”，填写签收人、上传凭证照片后再确认")
                    .withHintTarget("销售管理 → 销售订单 → 发货记录");
        }
        record.setStatus(SalesDeliveryStatus.DELIVERED);
        if (record.getSignedAt() == null) record.setSignedAt(LocalDateTime.now());
        SalesDeliveryRecord saved = deliveryRecordRepository.save(record);
        if (record.getParentDeliveryId() != null) {
            SalesDeliveryRecord parent = getDeliveryRecordById(factoryId, record.getParentDeliveryId());
            updateMasterDeliveryStatus(parent, getDeliveryShipments(factoryId, parent.getId()), null);
        }
        updateOrderTransportReceiptStatus(factoryId, record.getSalesOrderId());
        log.info("签收确认: deliveryId={}, deliveryNumber={}", deliveryId, record.getDeliveryNumber());
        return saved;
    }

    private void updateOrderTransportReceiptStatus(String factoryId, String salesOrderId) {
        if (salesOrderId == null) return;
        SalesOrder order = salesOrderRepository.findByIdAndFactoryIdForUpdate(salesOrderId, factoryId).orElse(null);
        if (order == null) return;
        List<SalesDeliveryRecord> effective = deliveryRecordRepository.findBySalesOrderId(salesOrderId).stream()
                .filter(delivery -> factoryId.equals(delivery.getFactoryId()))
                .filter(delivery -> !"MASTER".equals(delivery.getRecordRole()))
                .filter(delivery -> delivery.getStatus() != SalesDeliveryStatus.CANCELLED
                        && delivery.getStatus() != SalesDeliveryStatus.RETURNED)
                .toList();
        if (effective.isEmpty()) return;
        long delivered = effective.stream().filter(delivery -> delivery.getStatus() == SalesDeliveryStatus.DELIVERED).count();
        if (delivered == effective.size()) {
            order.setTransportPlanStatus("RECEIVED");
        } else if (delivered > 0) {
            order.setTransportPlanStatus("PARTIALLY_RECEIVED");
        } else if (effective.stream().anyMatch(delivery -> delivery.getStatus() == SalesDeliveryStatus.SHIPPED)) {
            order.setTransportPlanStatus("IN_TRANSIT");
        }
        salesOrderRepository.save(order);
    }

    @Override
    public SalesDeliveryRecord getDeliveryRecordById(String factoryId, String deliveryId) {
        SalesDeliveryRecord record = deliveryRecordRepository.findById(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("发货单不存在"));
        if (!record.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权访问该发货单")
                    .withHint("当前发货单不属于该工厂, 无法访问");
        }
        return record;
    }

    /**
     * Locks a delivery aggregate before a state transition. Order locks are acquired first by
     * {@link #lockDeliveryHierarchyForMutation(String, String)}; within the delivery aggregate the
     * record is locked before its lines. This order is shared by ship, cancel and sign-off paths.
     */
    private SalesDeliveryRecord lockDeliveryForMutation(String factoryId, String deliveryId) {
        Optional<SalesDeliveryRecord> locked = deliveryRecordRepository
                .findByIdAndFactoryIdForUpdate(deliveryId, factoryId);
        // Mockito returns null for an unstubbed Optional method in historical constructor tests.
        // A Spring Data repository never does, so production always takes the locked query above.
        if (locked == null) {
            locked = deliveryRecordRepository.findById(deliveryId)
                    .filter(record -> factoryId.equals(record.getFactoryId()));
        }
        SalesDeliveryRecord record = locked
                .orElseThrow(() -> new ResourceNotFoundException("发货单不存在或不属于当前组织"));
        if (deliveryItemRepository != null) {
            deliveryItemRepository.findByDeliveryRecordIdForUpdate(deliveryId);
        }
        // Initialize the managed collection after the line locks; never replace an orphan-removal
        // collection with the query result.
        record.getItems().size();
        return record;
    }

    private SalesOrder lockSalesOrderForDelivery(String factoryId, SalesDeliveryRecord delivery) {
        if (delivery == null || delivery.getSalesOrderId() == null) return null;
        return salesOrderRepository.findByIdAndFactoryIdForUpdate(delivery.getSalesOrderId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在或不属于当前组织"));
    }

    private SalesDeliveryRecord lockDeliveryHierarchyForMutation(String factoryId, String deliveryId) {
        SalesDeliveryRecord snapshot = getDeliveryRecordById(factoryId, deliveryId);
        lockSalesOrderForDelivery(factoryId, snapshot);
        if (snapshot.getParentDeliveryId() != null) {
            lockDeliveryForMutation(factoryId, snapshot.getParentDeliveryId());
        }
        return lockDeliveryForMutation(factoryId, deliveryId);
    }

    @Override
    public PageResponse<SalesDeliveryRecord> getDeliveryRecords(String factoryId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<SalesDeliveryRecord> result = deliveryRecordRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    public List<SalesDeliveryRecord> getDeliveryRecordsByOrder(String factoryId, String salesOrderId) {
        // 跨租户校验: 仅返回属于当前工厂的发货记录 (防止越权读取别厂订单的发货明细)。
        // 参考 getDeliveryRecordById 的 factoryId 归属校验写法。
        return deliveryRecordRepository.findBySalesOrderId(salesOrderId).stream()
                .filter(r -> factoryId.equals(r.getFactoryId()))
                .collect(java.util.stream.Collectors.toList());
    }

    // ==================== Issue #740 仓库侧 confirm 流程 ====================

    @Override
    public PageResponse<SalesDeliveryRecord> getPendingWarehouseDeliveries(String factoryId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<SalesDeliveryRecord> result = deliveryRecordRepository.findByFactoryIdAndStatusIn(
                factoryId,
                List.of(SalesDeliveryStatus.DRAFT,
                        SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM,
                        SalesDeliveryStatus.PICKED),
                pageRequest);
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SalesDeliveryRecord> getWarehouseDeliveryHistory(String factoryId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<SalesDeliveryRecord> result = deliveryRecordRepository.findByFactoryIdAndStatusIn(
                factoryId,
                List.of(SalesDeliveryStatus.PARTIALLY_SHIPPED,
                        SalesDeliveryStatus.SHIPPED,
                        SalesDeliveryStatus.DELIVERED,
                        SalesDeliveryStatus.CANCELLED,
                        SalesDeliveryStatus.RETURNED),
                pageRequest);
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    /**
     * Issue #740: warehouse-side confirm. Allows override of 实际发货数量 per-line
     * (车装不下 / 现场少装 等场景), then 扣库存 + 转 SHIPPED + auto AR.
     *
     * <p>actualQuantities map: key = deliveryItemId (String), value = actual qty.
     * Items not in map keep original deliveredQuantity (sales-planned).
     *
     * <p>客户原话 (六扇门 May10 L825-830):
     *   "确认发货, 他肯定去看一下库存, 实际发了多少, 他肯定手动要填一下.
     *    比如说我可能计划发发一版, 那但是可能车或者是没装下什么的, 各种各样的因素,
     *    然后他可能就发了八十"
     */
    @Override
    @Transactional
    public SalesDeliveryRecord warehouseConfirmDelivery(String factoryId, String deliveryId,
                                                        java.util.Map<String, java.math.BigDecimal> actualQuantities,
                                                        Long userId) {
        SalesDeliveryRecord record = lockDeliveryHierarchyForMutation(factoryId, deliveryId);

        if (record.getStatus() != SalesDeliveryStatus.DRAFT
                && record.getStatus() != SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM
                && record.getStatus() != SalesDeliveryStatus.PICKED) {
            throw new BusinessException(409, "只有 草稿/待仓库确认/已拣货 状态的发货单可以确认")
                    .withHint("请刷新发货单列表查看最新状态");
        }

        // 应用 actualQuantities 覆盖 — 仅覆盖, 不删行
        // NOTE: 若 actual < planned, 之前的批次分配 (P0-13 enforce 总量==deliveredQuantity) 会
        // 不再匹配 → shipDelivery 会 reject. 仓库需先到 "发货记录 → 分配批次" 重新分配再 confirm.
        // 此 spec 妥协: warehouse confirm 是 happy-path (车装下了就发完); actual<planned 需要
        // 走"重新分配批次"的 explicit flow. 后续 follow-up: confirm UI 同时支持调整分配.
        boolean qtyOverridden = false;
        if (actualQuantities != null && !actualQuantities.isEmpty()) {
            // ⚠️ 循环外解析一次 —— 逐行解析会逐行打 DB/缓存, 且同一次确认里上限可能读出两个值
            final OverReceiveLimit overShipLimit = resolveOverShipLimit(factoryId);
            for (SalesDeliveryItem item : record.getItems()) {
                String itemIdKey = item.getId() == null ? null : String.valueOf(item.getId());
                if (itemIdKey == null) continue;
                java.math.BigDecimal actual = actualQuantities.get(itemIdKey);
                if (actual != null) {
                    if (actual.signum() < 0) {
                        throw new BusinessException(400, "发货行 " + itemIdKey + " 的实际发货数量不能为负")
                                .withHint("请检查输入");
                    }
                    // ⚠️ 校验在【写入之前】—— 失败要 fail-fast, 不要先改内存再抛,
                    //    那会留下一个已经被改脏的实体等着事务回滚(doomed-tx)。
                    //
                    // 🔴 留痕在【抛出之前】: 这道闸原本只拦不留痕, 超限抛 409 之后没有
                    //    任何记录说明「谁想超发多少、被哪条上限拦下」。recordBlockedOverShip
                    //    走独立事务(REQUIRES_NEW)提交, 所以外层这次回滚带不走它。
                    //    ⛔ 它永不抛异常 —— 留痕失败不许把 409 变成 500。
                    try {
                        assertWithinOverShipCap(item, itemIdKey, actual, overShipLimit);
                    } catch (BusinessException capViolation) {
                        recordBlockedOverShipAttempt(factoryId, record, item, actual,
                                overShipLimit, capViolation, userId);
                        throw capViolation;
                    }
                    if (item.getDeliveredQuantity() != null
                            && actual.compareTo(item.getDeliveredQuantity()) != 0) {
                        log.info("仓库 confirm 调整数量: deliveryId={}, itemId={}, plannedQty={} → actualQty={}",
                                deliveryId, itemIdKey, item.getDeliveredQuantity(), actual);
                        item.setDeliveredQuantity(actual);
                        qtyOverridden = true;
                    }
                }
            }
            if (qtyOverridden) {
                // 重新计算 totalAmount
                BigDecimal total = BigDecimal.ZERO;
                for (SalesDeliveryItem item : record.getItems()) {
                    if (item.getUnitPrice() != null && item.getDeliveredQuantity() != null) {
                        total = total.add(item.getDeliveredQuantity().multiply(item.getUnitPrice()));
                    }
                }
                record.setTotalAmount(total);
                record = deliveryRecordRepository.save(record);
            }
        }

        autoAllocatePrestockedReservations(factoryId, record);

        // 复用 shipDelivery 逻辑 — 含批次分配校验 + 扣库存 + 自动 AR
        return shipDelivery(factoryId, deliveryId, userId);
    }

    /**
     * 【超发上限】校验 —— 仓管把实发数量改大时，不许无上限地多发。
     *
     * <h2>这道闸在补哪个洞</h2>
     * 2026-08-28 生产实测：创建发货单时有一道 0% 硬闸
     * （{@code 发货数量超过订单行剩余可安排数量} → 409），但仓管在本方法所在的
     * {@code warehouseConfirmDelivery} 里覆盖实发数量时<b>只挡负数、没有任何上限</b>。
     * 于是「订 2 发 5」可以走完：<b>库存真扣 5 件，货真的出门了</b>。
     * 采购侧早有 {@code overReceiveRate} + 异常单两样，销售侧两样都没有。
     *
     * <h2>判据为什么是「实发 ≤ 计划 × (1+上限)」而不是「累计实发 ≤ 订单量 × (1+上限)」</h2>
     * 后者要重新推一遍跨发货单的累计账（主单/子单、已取消/已退回、拆单后谁算谁），
     * 而那本账<b>创建期已经算过了</b>：创建闸保证 {@code Σ计划 ≤ 订单量}。
     * 两道一叠即得 {@code Σ实发 ≤ Σ计划 × (1+上限) ≤ 订单量 × (1+上限)} —— 有界，且可证明。
     *
     * <p>⇒ 本闸<b>刻意窄</b>：只守「仓管这一步能把数量放大多少」。
     * ⛔ 它<b>不</b>守下面这些，别把它读成守了：
     * <ul>
     *   <li>创建期绕过（那道闸另在 {@code createDelivery}，是 0%）</li>
     *   <li>直接改库的越权写入</li>
     *   <li>{@code planned == null} 的行 —— 没有计划量就没有基准，本闸跳过；
     *       但那种行原有逻辑<b>本来也不会应用覆盖值</b>，所以不构成新的口子</li>
     * </ul>
     *
     * @param limit 本次确认生效的上限（{@link #resolveOverShipLimit} 解析，非 null；
     *              {@code isUnlimited()} 时直接放行）
     */
    private void assertWithinOverShipCap(SalesDeliveryItem item, String itemIdKey,
            BigDecimal actual, OverReceiveLimit limit) {
        if (limit == null || limit.isUnlimited()) {
            return;
        }
        BigDecimal planned = item.getDeliveredQuantity();
        if (planned == null) {
            // 没有计划量 = 没有基准。⛔ 不要兜底成 0 —— 那会把「不知道」判成「一件都不许发」。
            return;
        }
        BigDecimal maxAllowed = limit.maxAllowed(planned);
        if (maxAllowed == null || actual.compareTo(maxAllowed) <= 0) {
            return;
        }
        String unit = UnitDisplayNames.display(item.getUnit());
        throw new BusinessException(409, String.format(
                "实发数量超出超发上限: 发货行 %s 计划 %s%s, 本次实发 %s%s, 最大可发 %s%s (含 %s 超发)",
                itemIdKey,
                planned.stripTrailingZeros().toPlainString(), unit,
                actual.stripTrailingZeros().toPlainString(), unit,
                maxAllowed.stripTrailingZeros().toPlainString(), unit,
                limit.percentLabel()))
                .withCode("DELIVERY_QUANTITY_EXCEEDS_OVER_SHIP_CAP")
                .withHint("多出的部分本系统不会替你发出去 —— 请按实际装车量填写,"
                        + " 或联系业务员追加订单量后再发。"
                        + " 若本厂应当允许发更多, 在「系统设置 → 业务阈值配置 → 销售」调高发货超发上限。")
                .withHintTarget("actualQuantities");
    }

    /**
     * 给一次<b>被拦下的</b>超发尝试留痕 —— 这道闸此前只拦不留痕。
     *
     * <h2>⛔ 本方法永不抛异常</h2>
     * 调用点在 {@code catch (BusinessException)} 里、紧接着就要把那个 409 重新抛出去。
     * 如果留痕失败能抛出来，仓管看到的就从
     * 「实发数量超出超发上限，最大可发 2.2 盒」（可执行）
     * 变成「服务器内部错误」（不可执行）——<b>而发货本来就该被拒绝，结果没变，
     * 变的只是用户还能不能知道为什么。</b>
     * ⇒ 失败在 {@code SalesExceptionServiceImpl} 内部被吞掉并打 ERROR，
     * 这里再兜一层，防止依赖注入 / 代理层自己出问题。
     *
     * <h2>事务边界</h2>
     * {@code SalesExceptionService} 是<b>另一个 Spring bean</b>，
     * 其 {@code persistAttempt} 标了 {@code REQUIRES_NEW}。
     * ⛔ 不能写成本类的私有方法直接 save —— 本方法所在的事务下一行就抛 409 注定回滚，
     * 同事务写入<b>什么都留不下来</b>，而且不会有任何报错提示你它没留下。
     *
     * <p>{@code salesExceptionService} 是<b>可选注入</b>：本类有大量 legacy 单测用
     * 4/8 参数构造器直接 new，那些上下文里没有这个 bean。null 时静默跳过留痕，
     * ⛔ 但绝不跳过 409（闸本身与留痕是两件事）。
     */
    private void recordBlockedOverShipAttempt(String factoryId, SalesDeliveryRecord record,
            SalesDeliveryItem item, BigDecimal actual, OverReceiveLimit limit,
            BusinessException capViolation, Long userId) {
        if (salesExceptionService == null) {
            return;
        }
        // 只给【超发上限】这一条留痕。⚠️ 上面那个 catch 抓的是 BusinessException,
        // 日后 assertWithinOverShipCap 若长出第二种拒绝理由, 不该被记成超发。
        if (!"DELIVERY_QUANTITY_EXCEEDS_OVER_SHIP_CAP".equals(capViolation.getErrorCode())) {
            return;
        }
        try {
            BigDecimal rate = (limit == null || limit.isUnlimited()) ? null : limit.rate();
            String source = limit == null ? "limit-missing" : limit.source();
            BigDecimal maxAllowed = (limit == null || item == null)
                    ? null : limit.maxAllowed(item.getDeliveredQuantity());
            salesExceptionService.recordBlockedOverShip(
                    factoryId, record, item, actual, rate, source, maxAllowed, userId);
        } catch (Exception e) {
            log.error("SALES_EXCEPTION_RECORD_FAILED(caller): factoryId={}, deliveryId={}, itemId={}, "
                            + "本次超发尝试【没有留下记录】—— 发货仍按 409 拒绝, 结果不受影响",
                    factoryId,
                    record == null ? null : record.getId(),
                    item == null ? null : item.getId(), e);
        }
    }

    /**
     * 客户已有成品库存已经在财审时精确预留；仓管只核对实发数量，不应返回销售模块再选批次。
     * 推荐必须完整覆盖每一行后才写分配，任何不足都在扣库存前失败并由外层事务整体回滚。
     */
    private void autoAllocatePrestockedReservations(String factoryId, SalesDeliveryRecord record) {
        if (record == null || record.getSalesOrderId() == null) {
            return;
        }
        SalesOrder salesOrder = salesOrderRepository.findById(record.getSalesOrderId())
                .orElseThrow(() -> new BusinessException(409, "发货单关联的销售订单不存在")
                        .withCode("DELIVERY_SALES_ORDER_NOT_FOUND")
                        .withHint("该发货单关联的销售订单可能已被作废或删除，请核对订单状态；"
                                + "如订单确已作废，请改为公司库存发货或重新关联有效订单"));
        if (!factoryId.equals(salesOrder.getFactoryId())) {
            throw new BusinessException(403, "发货单关联的销售订单不属于当前工厂")
                    .withCode("DELIVERY_SALES_ORDER_CROSS_FACTORY");
        }
        if (salesOrder.getCustomerStockFulfillmentMode()
                != CustomerStockFulfillmentMode.PRESTOCKED) {
            return;
        }
        if (batchAllocationService == null) {
            throw new BusinessException(503, "客户预留批次分配服务暂不可用")
                    .withCode("PRESTOCKED_BATCH_ALLOCATION_UNAVAILABLE")
                    .withHint("请稍后重试；系统未扣减任何成品库存");
        }

        for (SalesDeliveryItem item : record.getItems()) {
            String deliveryItemId = String.valueOf(item.getId());
            if (batchAllocationService.isFullyAllocated(factoryId, deliveryItemId)) {
                continue;
            }
            BigDecimal required = item.getDeliveredQuantity();
            if (required == null || required.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(400, "产品 " + item.getProductName() + " 的实发数量必须大于 0")
                        .withCode("DELIVERY_ACTUAL_QUANTITY_INVALID")
                        .withHint("请填写本次实际装车数量；系统未扣减任何成品库存");
            }

            List<Map<String, Object>> recommendations = batchAllocationService.recommendFifo(
                    factoryId,
                    deliveryItemId,
                    item.getProductTypeId(),
                    required,
                    item.getUnit(),
                    item.getSourceWarehouseCode());
            BigDecimal recommendedTotal = recommendations.stream()
                    .map(row -> row.get("recommendedQuantity"))
                    .filter(Objects::nonNull)
                    .map(value -> value instanceof BigDecimal decimal
                            ? decimal : new BigDecimal(String.valueOf(value)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (recommendedTotal.compareTo(required) != 0) {
                throw new BusinessException(409, "产品 " + item.getProductName()
                        + " 的客户预留库存不足：需 " + required + UnitDisplayNames.display(item.getUnit())
                        + "，可匹配 " + recommendedTotal + UnitDisplayNames.display(item.getUnit()))
                        .withCode("PRESTOCKED_RESERVATION_INSUFFICIENT")
                        .withHint("请核对销售订单预留数量，或把实发数量改为本次实际可发数量；系统未扣减任何库存")
                        .withHintTarget("仓储管理 → 出货管理");
            }

            List<BatchAllocationDTO> allocations = recommendations.stream()
                    .map(row -> {
                        BatchAllocationDTO allocation = new BatchAllocationDTO();
                        allocation.setFinishedGoodsBatchId(String.valueOf(row.get("batchId")));
                        Object quantity = row.get("recommendedQuantity");
                        allocation.setAllocatedQty(quantity instanceof BigDecimal decimal
                                ? decimal : new BigDecimal(String.valueOf(quantity)));
                        return allocation;
                    })
                    .toList();
            batchAllocationService.allocateBatches(factoryId, deliveryItemId, allocations);
        }
    }

    @Override
    @Transactional
    public SalesDeliveryRecord uploadDeliverySignature(String factoryId, String deliveryId,
                                                        List<String> photoUrls, String signedByName, String remark) {
        SalesDeliveryRecord record = getDeliveryRecordById(factoryId, deliveryId);
        // 将 photoUrls 存为简单 JSON 数组字符串 (避免注入 ObjectMapper)
        String json = "[]";
        if (photoUrls != null && !photoUrls.isEmpty()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < photoUrls.size(); i++) {
                if (i > 0) sb.append(",");
                String url = photoUrls.get(i) == null ? "" : photoUrls.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append("\"").append(url).append("\"");
            }
            sb.append("]");
            json = sb.toString();
        }
        record.setSignaturePhotoUrls(json);
        record.setSignedByName(signedByName);
        record.setSignedAt(LocalDateTime.now());
        record.setSignatureRemark(remark);
        log.info("上传签收凭证: deliveryId={}, 照片数={}, 签收人={}",
                deliveryId, photoUrls == null ? 0 : photoUrls.size(), signedByName);
        return deliveryRecordRepository.save(record);
    }

    // ==================== 成品库存 ====================

    @Override
    public PageResponse<FinishedGoodsBatch> getFinishedGoodsBatches(String factoryId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FinishedGoodsBatch> result = finishedGoodsBatchRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    public FinishedGoodsBatch getFinishedGoodsBatchById(String factoryId, String batchId) {
        // Issue #786 follow-up to #761: single-item lookup (replaces FE list-filter fallback).
        // Mirror getSalesOrderById factory-isolation pattern (line 234-242).
        FinishedGoodsBatch batch = finishedGoodsBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("成品批次不存在"));
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权访问该成品批次")
                    .withHint("当前成品批次不属于该工厂, 无法访问");
        }
        return batch;
    }

    @Override
    public List<FinishedGoodsBatch> getAvailableBatches(String factoryId, String productTypeId) {
        // 老路径 — 维持 D5 WH-LOG 默认。委托给三参版本传 null sourceWarehouseCode 走 fallback.
        return getAvailableBatches(factoryId, productTypeId, null);
    }

    @Override
    public List<FinishedGoodsBatch> getAvailableBatches(String factoryId, String productTypeId,
                                                        String sourceWarehouseCode) {
        // T4-D5 #572 Phase B-1: honor caller-supplied sourceWarehouseCode (PR #547/#564 chain).
        //   - EXPLICIT sourceWarehouseCode → 仅该仓 (尊重显式来源选择).
        //   - BLANK (最常见) → 🔴 (2026-07-04) 跨全部可售仓 (FEFO, 排除 WH-RD), 与 recommend-fifo (G1)
        //     同口径。旧逻辑 blank 硬默认单一 WH-LOG/SALES_OUTBOUND_DEFAULT → 成品落生产仓 WH-WKS 时
        //     "库存查询" 假报无货 (推荐/发货能找到, 但可用性查询查不到 → 用户困惑). 可用性语义不变
        //     (仍减 reserved), 仅放宽仓库范围。
        if (sourceWarehouseCode != null && !sourceWarehouseCode.isBlank()) {
            String warehouseId = warehouseResolver.resolveId(factoryId, sourceWarehouseCode);
            return finishedGoodsBatchRepository
                    .findAvailableBatchesByWarehouse(factoryId, productTypeId, warehouseId);
        }
        return finishedGoodsBatchRepository
                .findAvailableBatchesFefoAllWarehousesExcluding(factoryId, productTypeId, WarehouseCodes.WH_RD);
    }

    @Override
    @Transactional
    public FinishedGoodsBatch createFinishedGoodsBatch(String factoryId, FinishedGoodsBatch batch, Long userId) {
        return doCreateFinishedGoodsBatch(factoryId, batch, userId, null);
    }

    // ==================== T126 Phase 1: 成品库存 Web 闭环 ====================

    /**
     * T126 Phase 1 (F8) — 期初入库路径。委托共享 worker, sourceType="OPENING"。
     * 不再 delegate-then-republish (旧实现会触发 trigger chain 两次)。
     */
    @Override
    @Transactional
    public FinishedGoodsBatch createOpeningFinishedGoodsBatch(String factoryId, FinishedGoodsBatch batch, Long userId) {
        return doCreateFinishedGoodsBatch(factoryId, batch, userId, "OPENING");
    }

    /**
     * 共享创建 worker。发布 <b>单个</b> FinishedGoodsCreatedEvent 携带 sourceType
     * (null=普通直接创建, "OPENING"=期初入库), 使 trigger chain 恰好触发一次且可过滤期初条目 (T126 F8)。
     * 运行在 caller 的 @Transactional 内 (private 自调用不开新事务)。
     */
    private FinishedGoodsBatch doCreateFinishedGoodsBatch(String factoryId, FinishedGoodsBatch batch, Long userId, String sourceType) {
        // Round 11 T3: Canvas Integration Template hook 1 — DB-driven validation
        if (validationRuleEvaluator != null) {
            try {
                validationRuleEvaluator.validate(factoryId, "finished_goods", "CREATE",
                        java.util.Map.of(
                            "productTypeId", batch.getProductTypeId() != null ? batch.getProductTypeId() : "",
                            "producedQuantity", batch.getProducedQuantity() != null ? batch.getProducedQuantity() : java.math.BigDecimal.ZERO));
            } catch (com.cretas.aims.exception.BusinessException e) { throw e; }
            catch (Exception e) { log.warn("Canvas validation non-blocking: {}", e.getMessage()); }
        }

        batch.setFactoryId(factoryId);
        batch.setCreatedBy(userId);
        if (batch.getBatchNumber() == null) {
            batch.setBatchNumber(generateFinishedGoodsBatchNumber(factoryId));
        }
        // Finished-goods creation defaults to WH-FG.
        // 若 caller 显式设置 warehouseId, 不覆盖.
        if (batch.getWarehouseId() == null) {
            batch.setWarehouseId(warehouseResolver.resolveFinishedGoodsId(factoryId));
        }
        batch = finishedGoodsBatchRepository.save(batch);

        // Round 11 T3 — close the FinishedGoodsCreatedEvent gap. Every finished-goods creation
        // path (manual / rework / 期初) is observable to trigger chains. T126 F8: single event
        // carrying sourceType so chains fire once and can filter opening entries.
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.FinishedGoodsCreatedEvent(
                    this,
                    batch.getFactoryId(),
                    null,  // sourceOrderId: null for direct creation (no production plan link)
                    batch.getProductTypeId(),
                    batch.getProducedQuantity(),
                    batch.getId(),
                    sourceType));
        } catch (Exception e) {
            log.warn("Publish FinishedGoodsCreatedEvent failed for {}: {}", batch.getId(), e.getMessage());
        }

        log.info("创建成品批次: factoryId={}, batchNumber={}, productTypeId={}, sourceType={}",
                factoryId, batch.getBatchNumber(), batch.getProductTypeId(), sourceType);
        return batch;
    }

    @Override
    @Transactional
    public com.cretas.aims.entity.inventory.FinishedGoodsBatch editFinishedGoodsBatch(
            String factoryId, String batchId,
            com.cretas.aims.dto.inventory.EditFinishedGoodsRequest request,
            Long operatorId) {
        // F12: isolation check first — getFinishedGoodsBatchById does the 403 guard
        FinishedGoodsBatch batch = getFinishedGoodsBatchById(factoryId, batchId);

        // ⛔ producedQuantity and unit are intentionally NOT touched here
        if (request.remark() != null) {
            batch.setRemark(request.remark());
        }
        if (request.storageLocation() != null) {
            // 受控词表 —— 判据见 WarehouseLocationCatalog。
            // ⚠️ 这是成品的**第二条**写路: /warehouse/layout/finished-goods/ 那条早就 fail closed,
            //    而这条(销售侧编辑批次)一直没校验 ⇒ 同一个字段两套规则。
            batch.setStorageLocation(
                    locationCatalog.requireConfiguredLocation(factoryId, request.storageLocation()));
        }
        if (request.expireDate() != null) {
            batch.setExpireDate(request.expireDate());
        }
        if (request.unitPrice() != null) {
            batch.setUnitPrice(request.unitPrice());
        }

        batch = finishedGoodsBatchRepository.save(batch);
        log.info("编辑成品批次: factoryId={}, batchId={}, operatorId={}", factoryId, batchId, operatorId);
        return batch;
    }

    @Override
    @Transactional
    public com.cretas.aims.entity.inventory.FinishedGoodsBatch adjustFinishedGoodsQuantity(
            String factoryId, String batchId,
            com.cretas.aims.dto.inventory.AdjustFinishedGoodsRequest request,
            Long operatorId) {
        // F12: isolation check first
        FinishedGoodsBatch batch = getFinishedGoodsBatchById(factoryId, batchId);

        BigDecimal beforeProduced = batch.getProducedQuantity();
        BigDecimal newProduced = beforeProduced.add(request.adjustmentQuantity());

        // F2: check new available quantity (newProduced - shipped - reserved >= 0)
        BigDecimal shipped  = batch.getShippedQuantity()  != null ? batch.getShippedQuantity()  : BigDecimal.ZERO;
        BigDecimal reserved = batch.getReservedQuantity() != null ? batch.getReservedQuantity() : BigDecimal.ZERO;
        BigDecimal newAvailable = newProduced.subtract(shipped).subtract(reserved);
        if (newAvailable.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(422, "调整后可用量为负数")
                    .withHint(String.format(
                            "当前已出库 %s 件, 已占用 %s 件, 调整量不得低于 %s",
                            shipped.toPlainString(),
                            reserved.toPlainString(),
                            shipped.add(reserved).subtract(beforeProduced).toPlainString()));
        }

        batch.setProducedQuantity(newProduced);
        batch = finishedGoodsBatchRepository.save(batch);

        // Write adjustment log (F1: operatorId from request attr, not SecurityContextHolder)
        if (finishedGoodsAdjustmentLogRepository != null) {
            com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog logEntry =
                    com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog.builder()
                            .factoryId(factoryId)
                            .batchId(batchId)
                            .adjustmentQuantity(request.adjustmentQuantity())
                            .beforeProduced(beforeProduced)
                            .afterProduced(newProduced)
                            .reason(request.reason())
                            .referenceType(request.referenceType() != null ? request.referenceType().name() : null)
                            .operatorId(operatorId)
                            .build();
            finishedGoodsAdjustmentLogRepository.save(logEntry);
        }

        log.info("调整成品批次数量: factoryId={}, batchId={}, delta={}, newProduced={}, operatorId={}",
                factoryId, batchId, request.adjustmentQuantity(), newProduced, operatorId);
        return batch;
    }

    @Override
    @Transactional
    public void voidFinishedGoodsBatch(String factoryId, String batchId, Long operatorId) {
        // F12: isolation check first
        FinishedGoodsBatch batch = getFinishedGoodsBatchById(factoryId, batchId);

        // F5: precondition — no shipped or reserved records
        BigDecimal shipped  = batch.getShippedQuantity()  != null ? batch.getShippedQuantity()  : BigDecimal.ZERO;
        BigDecimal reserved = batch.getReservedQuantity() != null ? batch.getReservedQuantity() : BigDecimal.ZERO;
        if (shipped.compareTo(BigDecimal.ZERO) > 0 || reserved.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(409, "该批次已有出库/预留记录, 无法作废")
                    .withHint(String.format(
                            "已出库 %s 件, 已预留 %s 件, 请先撤销相关出库/预留后再作废",
                            shipped.toPlainString(), reserved.toPlainString()));
        }

        // Soft-delete via BaseEntity.softDelete() (sets deleted_at, @Where filters it out)
        batch.softDelete();
        finishedGoodsBatchRepository.save(batch);
        log.info("作废成品批次: factoryId={}, batchId={}, operatorId={}", factoryId, batchId, operatorId);
    }

    // ==================== 统计 ====================

    @Override
    public Map<String, Object> getSalesStatistics(String factoryId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);
        List<SalesOrder> monthlyOrders = salesOrderRepository.findByFactoryIdAndDateRange(factoryId, monthStart, now);

        long totalOrders = monthlyOrders.size();
        // R23 audit C3: was inline {CONFIRMED, PENDING_FINANCE_REVIEW, FINANCE_APPROVED, PROCESSING}
        // — semantically "still in flight, not yet delivered/cancelled". Centralized as SO_IN_FLIGHT.
        long pendingOrders = monthlyOrders.stream()
                .filter(o -> o.getStatus() != null
                        && com.cretas.aims.domain.OrderUsageWhitelists.SO_IN_FLIGHT.contains(o.getStatus()))
                .count();
        BigDecimal monthlyRevenue = monthlyOrders.stream()
                .filter(o -> o.getStatus() != SalesOrderStatus.CANCELLED)
                .map(SalesOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        stats.put("monthlySalesOrderCount", totalOrders);
        stats.put("pendingDeliveryCount", pendingOrders);
        stats.put("monthlySalesAmount", monthlyRevenue);

        return stats;
    }

    // ==================== 内部方法 ====================

    private String generateSalesOrderNumber(String factoryId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "SO-" + dateStr + "-";
        // Serialize concurrent number generation on (factory, date) via a transaction-scoped
        // advisory lock. Released automatically at transaction end. Prevents the MAX+1 race
        // against uk_so_factory_order when two POSTs land in the same millisecond.
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(CAST(:fkey AS int), CAST(:dkey AS int))")
                .setParameter("fkey", factoryId.hashCode())
                .setParameter("dkey", dateStr.hashCode())
                .getSingleResult();
        String maxNumber = salesOrderRepository.findMaxOrderNumberByPrefix(factoryId, prefix + "%");
        int seq = 1;
        if (maxNumber != null && maxNumber.startsWith(prefix)) {
            try {
                seq = Integer.parseInt(maxNumber.substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {}
        }
        return String.format("SO-%s-%04d", dateStr, seq);
    }

    private String generateDeliveryNumber(String factoryId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long ts = System.currentTimeMillis() % 10000;
        return String.format("DLV-%s-%04d", dateStr, ts);
    }

    private String generateFinishedGoodsBatchNumber(String factoryId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long ts = System.currentTimeMillis() % 10000;
        return String.format("FG-%s-%04d", dateStr, ts);
    }

    /**
     * Canvas Config: 检查工作流状态转换是否被配置允许。
     * 如果 factoryConfigService 未注入（模块未部署），默认允许所有转换（向后兼容）。
     */
    /**
     * Canvas V2: Run DB-driven validation rules before hardcoded checks.
     * If no DB rules exist, this is a no-op — existing hardcoded validation still runs.
     */
    /**
     * Canvas V2: Calculate line amount using FormulaEngine, fall back to entity method.
     */
    private BigDecimal calculateLineAmount(String factoryId, SalesOrderItem item) {
        if (formulaEngine != null && factoryId != null && item.getQuantity() != null && item.getUnitPrice() != null) {
            try {
                Map<String, Object> vars = new HashMap<>();
                vars.put("quantity", item.getQuantity());
                vars.put("unitPrice", item.getUnitPrice());
                vars.put("discountRate", item.getDiscountRate() != null ? item.getDiscountRate() : BigDecimal.ZERO);
                BigDecimal result = formulaEngine.evaluate(factoryId, "sales_order", "LINE_AMOUNT", vars);
                if (result != null) return result;
            } catch (Exception e) {
                log.debug("FormulaEngine LINE_AMOUNT failed, using entity method: {}", e.getMessage());
            }
        }
        return item.getLineAmount();
    }

    /**
     * Convert a pricing-engine <em>line total</em> back to a <em>per-unit</em> price.
     *
     * <p>{@link com.cretas.aims.service.pricing.PricingResult#getFinalPrice()} is a line total
     * ({@code unitPriceList × quantity − Σ discounts}, see {@code PricingEngineImpl}). A
     * {@link SalesOrderItem} stores a per-unit price, so the engine result must be divided by the
     * quantity before {@code setUnitPrice}. Skipping this (the 2026-05-30 bug, surfaced by the F006
     * UI E2E) stored {@code unitPrice = qty × price} and {@code totalAmount = qty² × price}.
     *
     * <p><b>Scale is 4, not 2 (2026-08-28 fix).</b> {@code sales_order_items.unit_price} is
     * {@code numeric(15,4)} and the web client posts a 4-decimal ex-tax price derived from the
     * tax-inclusive price the user typed. Rounding to 2 here silently rewrote that price even when
     * the engine applied <em>no</em> strategy at all, and the entity then re-derived the line
     * amount from the truncated price — discarding the engine's own (correct) line total.
     *
     * <p>Production repro (SO-20260828-0001, F006): user entered 含税单价 ¥25.00 × 10 盒 @13%.
     * <pre>
     *   engine log: origUnitPrice=22.1239  lineTotal(orig=221.2390, final=221.2390)
     *               finalUnitPrice=22.12   appliedStrategies=0     ← nothing was applied
     *   persisted : unit_price 22.1200 → lineAmount 221.20 → lineAmountWithTax 249.96
     *   billed    : ¥249.96 instead of the ¥250.00 the user entered
     * </pre>
     * The error is per-unit and scales with quantity. 采购侧没有这个问题 —— 它以「含税金额 =
     * 单价 × 数量」为准，未税额由总额换算；销售侧反过来以取整后的未税单价为准。
     *
     * @return {@code lineTotal / quantity} (HALF_UP, scale 4 — matching the DB column), or
     *         {@code fallbackUnitPrice} when {@code lineTotal} is null / negative or
     *         {@code quantity} is null / non-positive.
     */
    public static BigDecimal lineTotalToUnitPrice(BigDecimal lineTotal, BigDecimal quantity,
                                                  BigDecimal fallbackUnitPrice) {
        // A negative line total must never become a negative unit price. PricingEngineImpl currently
        // clamps finalPrice to ≥ 0, but the engine contract is not enforced at compile time, so guard
        // here defensively rather than trust the upstream clamp (preflight-audit MEDIUM finding).
        if (lineTotal == null || lineTotal.signum() < 0 || quantity == null || quantity.signum() <= 0) {
            return fallbackUnitPrice;
        }
        return lineTotal.divide(quantity, 4, java.math.RoundingMode.HALF_UP);
    }

    private void runConfiguredValidation(String factoryId, String operation, Map<String, Object> context) {
        runConfiguredValidation(factoryId, "sales_order", operation, context);
    }

    // Round 9 Fix: overload with explicit moduleCode so delivery/shipment paths can
    // use their own module-scoped validation rules instead of reusing sales_order's.
    private void runConfiguredValidation(String factoryId, String moduleCode, String operation, Map<String, Object> context) {
        if (validationRuleEvaluator == null) return;
        try {
            validationRuleEvaluator.validate(factoryId, moduleCode, operation, context);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Canvas validation non-blocking error for {}.{}: {}",
                    moduleCode, operation, e.getMessage());
        }
    }

    private void checkTransitionAllowed(String factoryId, String fromStatus, String toStatus) {
        if (factoryConfigService != null) {
            try {
                if (!factoryConfigService.isTransitionAllowed(factoryId, "sales_order", fromStatus, toStatus)) {
                    throw new BusinessException(409, "当前配置不允许从 " + fromStatus + " 转换到 " + toStatus)
                            .withHint("请刷新订单列表查看最新状态");
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                // No workflow config or config parse error → default to ALLOW
                log.warn("Workflow transition check failed (defaulting to ALLOW): {} → {}, error: {}", fromStatus, toStatus, e.getMessage());
            }
        }
    }

    /**
     * FIFO 扣减成品库存
     * 按生产日期从早到晚，依次扣减可用库存
     *
     * <p><b>R6 #6 幻影预留释放 (2026-06-22)</b>: SO 经财务审核通过后由
     * {@code InventoryMatchingService.reserveStock} 按 FEFO 在成品批次上设 {@code reservedQuantity}
     * (软占位, 防其他订单抢同一库存)。发货时本方法把 reserved <b>转为</b> shipped —— 同一批物理库存
     * 不能既"已发"又"被预留"。
     * 旧逻辑只 {@code shipped += deduct} 不释放 reserved → {@code available = produced - shipped - reserved}
     * 双扣低估 (一批 produced=237.5 预留 50 发货 50 后 shipped=50 + reserved=50 → avail=137.5, 少 50),
     * 且若一批被预留耗尽 ({@code available=0}) 会误报"成品库存不足"无法发货。
     *
     * <p>修复: 扣减容量改为<b>物理未发量</b> {@code produced - shipped} (含被自己预留的部分),
     * 每批发货 {@code deduct} 后释放该批 reserved {@code min(deduct, reserved)} —— 永不为负、
     * 永不超发物理库存。
     *
     * <p>⚠️ <b>已知局限 (需终审知悉)</b>: reservedQuantity 是 productType 级 FEFO <b>聚合</b>预留,
     * 不绑定具体 SO。本修复优先扣<b>未预留</b>可用量 (available)、再扣本批被预留部分 —— 单 SO 场景
     * (预留它的 SO = 发货的 SO) 完全正确; 极端并发场景 (同批被多 SO 预留, 某 SO 超发) 理论上可能
     * 释放到他单预留, 这是聚合预留模型的既有局限, 非本修复引入。彻底解需 per-SO 预留台账 (另立项)。
     */
    /**
     * 订单行的品名 —— 成品查 product_types, 物料查 raw_material_types。
     *
     * <h2>🔴 2026-08-12 真机 E2E 抓到</h2>
     * 销售订单可以卖物料之后, 物料行落库时 {@code product_name} 是 <b>NULL</b> ——
     * 原来的兜底只查成品表, 物料查不到就 {@code orElse(null)}。
     * 实测 {@code RMT_1782891387679}(WL026 / 1.75L李锦记薄盐生抽)在 product_types 里 0 行。
     *
     * <p>后果比看上去重: {@code product_name} 是订单行上<b>唯一</b>保存的名字,
     * 列表 / 发货单 / 送货单 / 发票全靠它。一旦存成 null, <b>事后无法从行本身还原</b>。
     *
     * <p>这是「物料行落在成品的从属数据上」的第三个实例(前两个: 分配批次的空态、箱规 404)。
     * 所以这次不再逐点补, 收敛成这一个解析器 —— 谁要行的名字都走它。
     */
    private String resolveSellableName(String factoryId, String productTypeId) {
        if (productTypeId == null || productTypeId.isBlank()) return null;
        String fromProduct = productTypeRepository == null ? null
                : productTypeRepository.findById(productTypeId).map(ProductType::getName).orElse(null);
        if (fromProduct != null && !fromProduct.isBlank()) return fromProduct;
        if (salesRawMaterialTypeRepository == null) return null;
        return salesRawMaterialTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .map(com.cretas.aims.entity.RawMaterialType::getName)
                .orElse(null);
    }

    /**
     * 这一行卖的是物料(原料/辅料/包材)还是成品?
     *
     * <h2>判据: 启用商品优先, 否则物料</h2>
     * {@code product_type_id} 列没有外键, 两张表的 id 空间理论上可以重叠。全库实测:
     * 重叠 3 个, <b>全部是画布生成的占位</b>({@code __RAW_WORKFLOW_OWNER__*})<b>且全部停用</b>;
     * 「既是启用商品又是物料」的数量是 <b>0</b>。所以这条判据当前无歧义。
     *
     * <p>⚠️ 它依赖「没人会去启用那些画布占位」这个前提。前提一旦破裂, 后果是<b>扣错库存</b> ——
     * 所以这里不写注释了事, 而是撞上就 ERROR 日志 + 按成品处理(保持既有行为, 不静默改道)。
     */
    private boolean isMaterialLine(String factoryId, String productTypeId) {
        // 🔴 2026-08-19 形态 D: 这个判据原来是本类的私有实现, 而**财审联动那条路没有调它** ——
        //    于是物料行的 RMT_* id 被原样透传去建生产计划, 撞
        //    production_plans.product_type_id 的外键 (prod: 2026-08-13 一天 40 次)。
        //    修法不是在联动侧再抄一份(两份一定会漂), 而是把判定收敛到
        //    SellableLineDomainResolver, 两条路都调它。本方法保留为薄壳:
        //    调用点(发货扣库存)不变, 语义逐字等价 —— 由
        //    MaterialLineNotAProductionPlanContractTest 钉住等价性。
        //    ⚠️ resolver 为 null 只可能出现在没装配它的 legacy 单测里, 退回 false
        //    (= 按成品处理), 与本方法原来在 repository 为 null 时的行为一致。
        if (sellableLineDomainResolver == null) {
            return false;
        }
        return sellableLineDomainResolver.isMaterialLine(factoryId, productTypeId);
    }

    /**
     * 物料行的 FIFO 扣减 —— 与成品发货同一个位置、同一个语义, 只是库存源换成 material_batches。
     *
     * <p>用的是物料侧现成的原语: {@code getFIFOBatches} 挑批次, {@code useBatchQuantity} 扣数量
     * (它自带工厂隔离、超扣断言, 并会发库存变更事件触发低库存报警)。
     */
    private void deductMaterialInventory(String factoryId, SalesDeliveryItem item) {
        java.math.BigDecimal needed = item.getDeliveredQuantity();
        if (needed == null || needed.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return;
        }
        // ⛔ 依赖没接上时必须炸, 不能静默跳过 —— 那等于「发了货但没扣库存」, 比发不出货糟得多。
        if (salesMaterialBatchService == null) {
            throw new IllegalStateException(
                    "MaterialBatchService is required to ship a material line: " + item.getProductTypeId());
        }

        java.util.List<com.cretas.aims.dto.material.MaterialBatchDTO> batches =
                salesMaterialBatchService.getFIFOBatches(factoryId, item.getProductTypeId(), needed);
        java.math.BigDecimal remaining = needed;
        for (com.cretas.aims.dto.material.MaterialBatchDTO batch : batches) {
            if (remaining.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                break;
            }
            java.math.BigDecimal available = batch.getCurrentQuantity();
            if (available == null || available.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                continue;
            }
            java.math.BigDecimal take = available.min(remaining);
            salesMaterialBatchService.useBatchQuantity(factoryId, batch.getId(), take);
            remaining = remaining.subtract(take);
        }
        if (remaining.compareTo(java.math.BigDecimal.ZERO) > 0) {
            throw new BusinessException(409, "物料库存不足, 无法发货: " + item.getProductName()
                    + " 还差 " + remaining.toPlainString() + " " + UnitDisplayNames.display(item.getUnit()))
                    .withCode("MATERIAL_STOCK_INSUFFICIENT")
                    .withHint("请先入库或调整发货数量");
        }
    }

    private void deductFinishedGoodsInventory(String factoryId, String salesOrderId, SalesDeliveryItem item) {
        // 🔴 2026-08-12: 物料行(原料/辅料/包材)的货在 material_batches, 不在成品批次里。
        // 在这里分叉后 return, 下面整段成品逻辑(批次分配/FEFO/客户自有库存)一个字不动。
        if (isMaterialLine(factoryId, item.getProductTypeId())) {
            deductMaterialInventory(factoryId, item);
            return;
        }
        SalesOrder salesOrder = salesOrderId == null || salesOrderId.isBlank()
                ? null
                : salesOrderRepository.findById(salesOrderId)
                        .orElseThrow(() -> new BusinessException(409, "发货单关联的销售订单不存在")
                                .withCode("DELIVERY_SALES_ORDER_NOT_FOUND")
                                .withHint("该发货单关联的销售订单可能已被作废或删除，请核对订单状态；"
                                        + "如订单确已作废，请改为公司库存发货或重新关联有效订单"));
        if (salesOrder != null && !factoryId.equals(salesOrder.getFactoryId())) {
            throw new BusinessException(403, "发货单关联的销售订单不属于当前工厂")
                    .withCode("DELIVERY_SALES_ORDER_CROSS_FACTORY");
        }
        boolean prestockedCustomerStock = salesOrder != null
                && salesOrder.getCustomerStockFulfillmentMode()
                == CustomerStockFulfillmentMode.PRESTOCKED;
        if (prestockedCustomerStock && reservationLedgerService == null) {
            throw new IllegalStateException(
                    "FgReservationLedgerService is required for PRESTOCKED customer shipment");
        }
        // 🔴 (2026-07-06): HONOR the operator's batch allocation. The P0-13 allocation dialog lets
        // the warehouse pick EXACTLY which FG batches to ship (e.g. 近效期清库存 / 指定批次追溯), and
        // shipDelivery even HARD-GATES on 「批次分配完成」(isFullyAllocated). Historically the deduction
        // below re-ran its OWN FIFO/FEFO discovery and IGNORED the allocation table — the operator's
        // chosen batches were silently overridden, allocation records diverged from the physically
        // shipped batches (食品溯源失真), and the gate enforced something the deduction never respected.
        // If this line has allocation records, deduct EXACTLY those batches. No records
        // (batchAllocationService not wired in legacy unit tests / a line that never went through the
        // allocation UI) → fall back to the original FIFO/FEFO discovery below (unchanged).
        if (batchAllocationService != null) {
            List<com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation> allocations =
                    batchAllocationService.listByDeliveryItem(factoryId, String.valueOf(item.getId()));
            if (allocations != null && !allocations.isEmpty()) {
                deductByAllocations(factoryId, salesOrder, item, allocations);
                return;
            }
        }

        // ---- FALLBACK: original FIFO/FEFO discovery (no allocation records for this line) ----
        // T4-D5 #572 + 🔴 G1 (2026-07-03): per-row sourceWarehouseCode drives the candidate set.
        //   - EXPLICIT source → deduct only from that warehouse (respect explicit choice).
        //   - BLANK (common case) → deduct FEFO across ALL shippable (non-RD) warehouses. Mirrors
        //     recommendFifo / allocateBatches discovery so 「推荐能选 → 分配能过 → 发货能扣」三段一致.
        //     Fixes Steve #1 bug: FG produced into WH-WKS was invisible under the old WH-LOG-only default.
        // R6 #6: findShippable* 过滤 (produced-shipped>0, 不减 reserved), 让被本 SO 预留耗尽
        // (available=0) 的批次也能进入发货候选 —— Pass 2 动用其预留物理库存。
        String sourceCode = item.getSourceWarehouseCode();
        boolean hasExplicitSource = sourceCode != null && !sourceCode.isBlank();
        List<FinishedGoodsBatch> batches;
        if (hasExplicitSource) {
            String warehouseId = warehouseResolver.resolveId(factoryId, sourceCode);
            batches = prestockedCustomerStock
                    ? finishedGoodsBatchRepository.findShippablePrestockedBatchesByWarehouse(
                            factoryId, item.getProductTypeId(), warehouseId,
                            salesOrder.getCustomerId(), salesOrder.getId())
                    : SalesFinishedGoodsOwnershipGuard.requiresCustomerOwnedStock(salesOrder)
                    ? finishedGoodsBatchRepository.findShippableCustomerOwnedBatchesByWarehouse(
                            factoryId, item.getProductTypeId(), warehouseId,
                            salesOrder.getCustomerId(), salesOrder.getId())
                    : finishedGoodsBatchRepository.findShippableBatchesByWarehouse(
                            factoryId, item.getProductTypeId(), warehouseId);
        } else {
            batches = prestockedCustomerStock
                    ? finishedGoodsBatchRepository.findShippablePrestockedBatchesAllWarehousesExcluding(
                            factoryId, item.getProductTypeId(), WarehouseCodes.WH_RD,
                            salesOrder.getCustomerId(), salesOrder.getId())
                    : SalesFinishedGoodsOwnershipGuard.requiresCustomerOwnedStock(salesOrder)
                    ? finishedGoodsBatchRepository.findShippableCustomerOwnedBatchesAllWarehousesExcluding(
                            factoryId, item.getProductTypeId(), WarehouseCodes.WH_RD,
                            salesOrder.getCustomerId(), salesOrder.getId())
                    : finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(
                            factoryId, item.getProductTypeId(), WarehouseCodes.WH_RD);
        }

        // 🔴 C1 (2026-07-05): FG batches for one product may be recorded in DIFFERENT native units
        // (one 小结'd with productWeight → kg, another without → 盒/件, F006 confirmed live). All
        // arithmetic below runs in the delivery line's unit (targetUnit); each batch's native
        // available/reserved is converted INTO targetUnit for comparison, and the resulting
        // deduct amount is converted BACK to that batch's native unit before mutating it (batch
        // fields are persisted in native unit — see applyShipment). A batch whose native unit can't
        // be converted (缺 gramsPerUnit) is skipped — honest-null, never silently mixed in.
        String targetUnit = item.getUnit();
        // Defensive null-check: some legacy unit tests construct SalesServiceImpl with a null
        // productTypeRepository (see SalesOrderFulfillmentWarehouseTest) — mirrors the existing
        // Autowired(required=false) null-guard pattern used elsewhere in this class.
        BigDecimal gramsPerUnit = productTypeRepository != null
                ? productTypeRepository.findById(item.getProductTypeId()).map(ProductType::getGramsPerUnit).orElse(null)
                : null;

        BigDecimal remaining = item.getDeliveredQuantity();

        // Pass 1: 先扣未被预留的可用量 (available = produced - shipped - reserved) —— 不动任何预留。
        for (FinishedGoodsBatch batch : prestockedCustomerStock ? List.<FinishedGoodsBatch>of() : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            SalesFinishedGoodsOwnershipGuard.assertBatchAllowed(
                    salesOrder, batch, "finishedGoodsBatchId");
            BigDecimal availableNative = batch.getAvailableQuantity();
            if (availableNative.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal availableInTargetUnit = convertBatchToDeliveryUnit(
                    availableNative, batch, item, gramsPerUnit);
            if (availableInTargetUnit == null) {
                log.warn("发货扣减跳过批次(单位不可换算): factoryId={}, batchId={}, batchUnit={}, targetUnit={}, gramsPerUnit={}",
                        factoryId, batch.getId(), batch.getUnit(), targetUnit, gramsPerUnit);
                continue;
            }
            if (availableInTargetUnit.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal deductInTargetUnit = remaining.min(availableInTargetUnit);
            BigDecimal deductNative = toNativeDeductAmount(
                    deductInTargetUnit, item, batch, gramsPerUnit, availableNative);
            if (deductNative == null || deductNative.compareTo(BigDecimal.ZERO) <= 0) continue;
            applyShipment(item, batch, deductNative, BigDecimal.ZERO);  // 不释放预留 (扣的是未预留部分)
            recordDeductedBatch(item, batch);
            remaining = remaining.subtract(deductInTargetUnit);
        }

        // Pass 2: 仍不足 → 动用<b>本批被预留</b>的物理库存 (即本 SO 自己的预留), 发货同时释放对应 reserved。
        // 容量 = 物理未发量 - available = 被预留且未发的部分 = reserved (受限于 produced-shipped)。
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            for (FinishedGoodsBatch batch : batches) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal orderReserved = prestockedCustomerStock
                        ? reservationLedgerService.getActiveReservedForOrderAndBatch(
                                salesOrderId, batch.getId())
                        : (batch.getReservedQuantity() != null
                                ? batch.getReservedQuantity() : BigDecimal.ZERO);
                SalesFinishedGoodsOwnershipGuard.assertBatchAllowed(
                        salesOrder, batch,
                        !prestockedCustomerStock || orderReserved.compareTo(BigDecimal.ZERO) > 0,
                        "finishedGoodsBatchId");
                BigDecimal reserved = orderReserved;
                BigDecimal physicalUnshipped = batch.getProducedQuantity()
                        .subtract(batch.getShippedQuantity() != null ? batch.getShippedQuantity() : BigDecimal.ZERO);
                // 本批可从预留中发出的物理量 = min(reserved, 物理未发量)
                BigDecimal fromReservedNative = reserved.min(physicalUnshipped);
                if (fromReservedNative.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal fromReservedInTargetUnit = convertBatchToDeliveryUnit(
                        fromReservedNative, batch, item, gramsPerUnit);
                if (fromReservedInTargetUnit == null) {
                    log.warn("发货扣减(预留)跳过批次(单位不可换算): factoryId={}, batchId={}, batchUnit={}, targetUnit={}, gramsPerUnit={}",
                            factoryId, batch.getId(), batch.getUnit(), targetUnit, gramsPerUnit);
                    continue;
                }
                if (fromReservedInTargetUnit.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal deductInTargetUnit = remaining.min(fromReservedInTargetUnit);
                BigDecimal deductNative = toNativeDeductAmount(
                        deductInTargetUnit, item, batch, gramsPerUnit, fromReservedNative);
                if (deductNative == null || deductNative.compareTo(BigDecimal.ZERO) <= 0) continue;
                applyShipment(item, batch, deductNative, deductNative);  // 发 deduct 同时释放 deduct 预留 (预留转已发)
                // 台账镜像: applyShipment 已把 batch.reserved 减了 deductNative, 这里同步削掉本 SO
                // 在该批的 ACTIVE 台账行 (只削台账, 不再动 reserved), 维持 reserved == Σ台账 不变式。
                if (reservationLedgerService != null && salesOrderId != null) {
                    reservationLedgerService.syncReleaseOnShipment(salesOrderId, batch.getId(), deductNative);
                }
                recordDeductedBatch(item, batch);
                remaining = remaining.subtract(deductInTargetUnit);
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            String warehouseDisplay = hasExplicitSource ? sourceCode : "全部可售仓库(除研发库)";
            throw new BusinessException(String.format("成品库存不足: 产品=%s, 仓库=%s, 缺少数量=%s",
                item.getProductTypeId(), warehouseDisplay, remaining.toPlainString()))
                .withHint(prestockedCustomerStock
                        ? "请先完成该订单的客户已有库存预留，再按预留数量发货"
                        : "请检查 " + warehouseDisplay + " 的成品库存, 或先完成生产入库后再发货")
                .withHintTarget("生产计划");
        }
    }

    /**
     * 🔴 (2026-07-06): 按发货行的<b>批次分配记录</b>精确扣减成品库存 —— 兑现操作员在分配对话框里
     * 选定的批次身份 (近效期清库存 / 指定批次追溯)。与旧 FIFO 独立重扫的本质区别: 扣的<b>就是</b>
     * 用户选的批次, 不是 FIFO 另选的一批, 分配记录与实际扣减的成品批次因此一致 (追溯闭环)。
     *
     * <p><b>单位</b>: {@code alloc.getAllocatedQty()} 以发货行单位 (item.getUnit()) 记录
     * (见 {@code allocateBatches} 里 {@code alloc.setUnit(item.getUnit())}); 批次原生单位可能不同
     * (一批小结填了 productWeight → kg, 另一批未填 → 盒/件, C1)。故换算到发货单位记账, 扣减时再换算
     * 回批次原生单位写库 —— 与 FIFO 路径同一 {@link FgQuantityUnitConverter} /
     * {@link #toNativeDeductAmount} 口径, 不裸相减。换算失败 (缺 gramsPerUnit / 不兼容单位) → 诚实
     * loud-fail, 绝不裸误扣。
     *
     * <p><b>容量</b>: 只扣该批<b>可用量</b> (available = produced − shipped − reserved, 与 FIFO Pass1
     * 同口径, releaseReserved=0, 守 available≥0 不变式)。分配时 (allocateBatches) 已校验 可用≥分配,
     * 但发货时可能因并发/其它单发货导致可用下降 → 若某批可用已不足以覆盖其分配量 (超出亚单位换算漂移),
     * loud-fail 409 提示重新分配, <b>绝不静默少发或超发</b>。
     */
    private void deductByAllocations(String factoryId, SalesOrder salesOrder, SalesDeliveryItem item,
            List<com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation> allocations) {
        boolean prestockedCustomerStock = salesOrder != null
                && salesOrder.getCustomerStockFulfillmentMode()
                == CustomerStockFulfillmentMode.PRESTOCKED;
        if (prestockedCustomerStock && reservationLedgerService == null) {
            throw new IllegalStateException(
                    "FgReservationLedgerService is required for PRESTOCKED customer shipment");
        }
        String targetUnit = item.getUnit();
        // Defensive null-check: legacy unit tests may construct with null productTypeRepository.
        BigDecimal gramsPerUnit = productTypeRepository != null
                ? productTypeRepository.findById(item.getProductTypeId()).map(ProductType::getGramsPerUnit).orElse(null)
                : null;
        // 亚单位换算漂移容差: kg(scale4) / 盒(scale2) 双向 HALF_UP 每端 ~0.005 量级 → 0.01 兜底。
        final BigDecimal driftEps = new BigDecimal("0.01");

        BigDecimal remaining = item.getDeliveredQuantity();
        for (com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation alloc : allocations) {
            FinishedGoodsBatch batch = finishedGoodsBatchRepository
                    .findByIdAndFactoryIdForUpdate(alloc.getFinishedGoodsBatchId(), factoryId)
                    .orElseThrow(() -> new BusinessException(409, "分配的成品批次不存在或已删除: "
                            + alloc.getBatchNumber())
                            .withHint("请到「发货记录 → 分配批次」重新分配后再确认发货")
                            .withHintTarget("发货记录 Tab"));
            if (!factoryId.equals(batch.getFactoryId())) {
                throw new BusinessException(403, "分配的成品批次不属于当前工厂: " + alloc.getBatchNumber())
                        .withHint("跨工厂数据被拒绝, 请重新分配批次").withHintTarget("finishedGoodsBatchId");
            }
            BigDecimal orderReservedNative = prestockedCustomerStock
                    ? reservationLedgerService.getActiveReservedForOrderAndBatch(
                            salesOrder.getId(), batch.getId())
                    : BigDecimal.ZERO;
            SalesFinishedGoodsOwnershipGuard.assertBatchAllowed(
                    salesOrder,
                    batch,
                    !prestockedCustomerStock
                            || orderReservedNative.compareTo(BigDecimal.ZERO) > 0,
                    "finishedGoodsBatchId");
            FinishedGoodsBatchSaleabilityGuard.assertSaleable(batch, "finishedGoodsBatchId");
            String allocUnit = alloc.getUnit() != null ? alloc.getUnit() : targetUnit;
            // 分配量换算到 targetUnit (通常 allocUnit==targetUnit → 恒等), 统一在发货单位记账。
            BigDecimal allocQtyInTarget = FgQuantityUnitConverter
                    .convert(alloc.getAllocatedQty(), allocUnit, targetUnit, gramsPerUnit);
            if (allocQtyInTarget == null) {
                throw new BusinessException(409, "分配记录单位(" + allocUnit + ")与发货单位(" + targetUnit
                        + ")不一致且无法换算 (批次 " + alloc.getBatchNumber() + ")")
                        .withHint("请在产品资料补「每盒/份标准克重」或改用一致单位后重新分配")
                        .withHintTarget("finishedGoodsBatchId");
            }
            if (allocQtyInTarget.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            // 批次可用量 (与 FIFO Pass1 同口径: produced − shipped − reserved)。
            BigDecimal physicalUnshipped = batch.getProducedQuantity().subtract(
                    batch.getShippedQuantity() != null
                            ? batch.getShippedQuantity() : BigDecimal.ZERO);
            BigDecimal availableNative = prestockedCustomerStock
                    ? orderReservedNative.min(physicalUnshipped)
                    : batch.getAvailableQuantity();
            BigDecimal availableInTarget = convertBatchToDeliveryUnit(
                    availableNative, batch, item, gramsPerUnit);
            if (availableInTarget == null) {
                throw new BusinessException(409, "成品批次 " + batch.getBatchNumber()
                        + " 单位(" + batch.getUnit() + ")与发货单位(" + targetUnit + ")不可换算")
                        .withHint("请在产品资料补「每盒/份标准克重」或改用一致单位后重新分配")
                        .withHintTarget("finishedGoodsBatchId");
            }
            // 并发/其它单发货导致可用下降到不足覆盖其分配量 → loud-fail (超出亚单位漂移才算真不足)。
            if (allocQtyInTarget.subtract(availableInTarget).compareTo(driftEps) > 0) {
                throw new BusinessException(409, "成品批次 " + batch.getBatchNumber()
                        + " 可用库存不足 (可用=" + availableInTarget + targetUnit
                        + ", 已分配=" + allocQtyInTarget + targetUnit + ")")
                        .withHint("该批次库存在分配后发生变化 (可能被其它发货单占用), 请到「发货记录 → 分配批次」重新分配")
                        .withHintTarget("finishedGoodsBatchId");
            }
            // 实发 = min(分配量, 可用量): 漂移在容差内时 clamp 到可用, 不超发。
            BigDecimal shipInTarget = allocQtyInTarget.min(availableInTarget);
            BigDecimal shipNative = toNativeDeductAmount(
                    shipInTarget, item, batch, gramsPerUnit, availableNative);
            if (shipNative == null || shipNative.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            // PRESTOCKED 发的是本订单台账预留；普通订单仍扣未预留可用量。
            applyShipment(item, batch, shipNative,
                    prestockedCustomerStock ? shipNative : BigDecimal.ZERO);
            if (prestockedCustomerStock) {
                reservationLedgerService.syncReleaseOnShipment(
                        salesOrder.getId(), batch.getId(), shipNative);
            }
            recordDeductedBatch(item, batch);
            remaining = remaining.subtract(shipInTarget);
        }

        // 🔴 owner 2026-09-02 拍板: allocateBatches 现在只强校验 分配总量 <= deliveredQuantity
        // (放行短发, 见 SalesDeliveryBatchAllocationServiceImpl), 不再是 ==。所以这里的
        // remaining 在「有意短发但没跟着调低 deliveredQuantity」时会 > 0, 这是【预期】会发生的,
        // 不是异常状态。发货这一步仍然要求「实际扣的批次量 == deliveredQuantity」这个不变式
        // (否则应收账款会按 deliveredQuantity 算多——见后面自动创建 AR_INVOICE) ——
        // 短发的正确姿势是先经「仓库确认」把 deliveredQuantity 调到与实际一致 (actualQuantities),
        // 而不是留一份对不上号的分配记录。remaining > 漂移容差时仍然 loud-fail, 而不是静默少发。
        if (remaining.compareTo(driftEps) > 0) {
            throw new BusinessException(409, "按已分配批次发货后仍缺 " + remaining
                    + (targetUnit != null ? targetUnit : "") + " — 分配记录与发货数量不一致")
                    .withHint("如为短发, 请先到「仓储管理 → 出货管理」仓库确认里把实发数量改小；"
                            + "如需补齐分配, 请到「发货记录 → 分配批次」重新分配")
                    .withHintTarget("发货记录 Tab");
        }
    }

    /**
     * 🔴 C1 (2026-07-05): 把「目标单位(targetUnit)算出的扣减量」换算回该批次的<b>原生单位</b>
     * (batch.getShippedQuantity()/reservedQuantity 是按原生单位持久化的, 不能直接写入目标单位的量)。
     *
     * <p>换算是双向的 (targetUnit → nativeUnit 与 nativeUnit → targetUnit 互为逆运算), HALF_UP
     * 舍入两端各一次可能引入亚分/亚克的漂移 —— 用 {@code nativeCap}(该批在原生单位下的可扣上限,
     * 即调用方已经算好的 availableNative/fromReservedNative) 兜底 {@code min}, 保证换算漂移<b>绝不</b>
     * 让本次扣减超过该批次原生单位下真实剩余的物理量。
     *
     * @return 原生单位下的扣减量; 换算失败 (缺 gramsPerUnit / 单位不兼容) 返回 {@code null}
     */
    private BigDecimal toNativeDeductAmount(
            BigDecimal deductInTargetUnit,
            SalesDeliveryItem item,
            FinishedGoodsBatch batch,
            BigDecimal gramsPerUnit,
            BigDecimal nativeCap) {
        BigDecimal deductNative = FgQuantityUnitConverter.convertWithPackaging(
                deductInTargetUnit,
                item.getUnit(),
                batch.getUnit(),
                gramsPerUnit,
                item.getPackagingUnit(),
                item.getPackagingBaseUnit(),
                item.getPackagingFactor(),
                batch.getPackagingUnit(),
                batch.getPackagingBaseUnit(),
                batch.getPackagingFactor());
        if (deductNative == null) {
            return null;
        }
        return deductNative.min(nativeCap);
    }

    private BigDecimal convertBatchToDeliveryUnit(
            BigDecimal quantity,
            FinishedGoodsBatch batch,
            SalesDeliveryItem item,
            BigDecimal gramsPerUnit) {
        return FgQuantityUnitConverter.convertWithPackaging(
                quantity,
                batch.getUnit(),
                item.getUnit(),
                gramsPerUnit,
                batch.getPackagingUnit(),
                batch.getPackagingBaseUnit(),
                batch.getPackagingFactor(),
                item.getPackagingUnit(),
                item.getPackagingBaseUnit(),
                item.getPackagingFactor());
    }

    /**
     * 对成品批次执行发货: shipped += shipQty, reserved -= releaseReserved (不为负), 更新 DEPLETED 状态并保存。
     * R6 #6: releaseReserved 让发货释放对应预留, 避免 available 双扣低估。
     */
    private void applyShipment(SalesDeliveryItem item, FinishedGoodsBatch batch,
                               BigDecimal shipQty, BigDecimal releaseReserved) {
        recordShipmentLineage(item, batch, shipQty);
        batch.setShippedQuantity(
                (batch.getShippedQuantity() != null ? batch.getShippedQuantity() : BigDecimal.ZERO).add(shipQty));
        if (releaseReserved.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal reserved = batch.getReservedQuantity() != null
                    ? batch.getReservedQuantity() : BigDecimal.ZERO;
            // 防御性 max(0): 释放量已被 min(reserved, ...) 约束, 不应为负, 但守住不变式 reserved >= 0。
            batch.setReservedQuantity(reserved.subtract(releaseReserved).max(BigDecimal.ZERO));
        }
        if (batch.isDepleted()) {
            batch.setStatus("DEPLETED");
        }
        finishedGoodsBatchRepository.save(batch);
    }

    /**
     * 成品批次 → 出货单的血缘边写点（本类唯一一处，三条扣减路径共用）。
     *
     * <p>幂等键是 {@code delivery:<deliveryItemId>:<batchId>}：同一张发货行对同一个成品批次
     * 只留一条边；不同发货行发同一批次是两次真实出货，各自成边。
     */
    private void recordShipmentLineage(SalesDeliveryItem item, FinishedGoodsBatch batch,
                                       BigDecimal shipQty) {
        if (lineageEdgeRecorder == null || item == null || batch == null) {
            return;
        }
        String deliveryRecordId = item.getDeliveryRecordId();
        Map<String, Object> meta = new HashMap<>();
        meta.put("origin", "SALES_DELIVERY_SHIPMENT");
        meta.put("deliveryItemId", item.getId());
        meta.put("finishedBatchNumber", batch.getBatchNumber());
        lineageEdgeRecorder.recordFinishedToShipment(
                batch.getFactoryId(), batch.getId(), deliveryRecordId,
                shipQty, batch.getUnit(), null,
                "delivery:" + deliveryRecordId + ":" + batch.getId(), meta);
    }

    /** 记录发货行首个扣减的成品批次 (用于溯源)。 */
    private void recordDeductedBatch(SalesDeliveryItem item, FinishedGoodsBatch batch) {
        if (item.getFinishedGoodsBatchId() == null) {
            item.setFinishedGoodsBatchId(batch.getId());
        }
    }

    private void updateOrderDeliveryStatus(SalesDeliveryRecord record) {
        SalesOrder order = salesOrderRepository
                .findByIdAndFactoryIdForUpdate(record.getSalesOrderId(), record.getFactoryId())
                .orElse(null);
        if (order == null) return;
        boolean wasCompleted = order.getStatus() == SalesOrderStatus.COMPLETED;

        List<SalesOrderItem> orderItems = salesOrderItemRepository.findBySalesOrderId(order.getId());

        // 累加本次发货数量
        for (SalesDeliveryItem deliveryItem : record.getItems()) {
            SalesOrderItem orderItem = resolvePostedSalesOrderItem(deliveryItem, orderItems);
            BigDecimal previousDelivered = orderItem.getDeliveredQuantity() != null
                    ? orderItem.getDeliveredQuantity() : BigDecimal.ZERO;
            BigDecimal deliveryCostUnitPrice = computeDeliveryCostUnitPrice(record.getFactoryId(), deliveryItem);
            if (deliveryCostUnitPrice != null && deliveryItem.getDeliveredQuantity() != null
                    && deliveryItem.getDeliveredQuantity().signum() > 0) {
                orderItem.setCostUnitPrice(blendCostUnitPrice(
                        orderItem.getCostUnitPrice(),
                        previousDelivered,
                        deliveryCostUnitPrice,
                        deliveryItem.getDeliveredQuantity()));
            }
            BigDecimal shippedQuantity = deliveryItem.getDeliveredQuantity() != null
                    ? deliveryItem.getDeliveredQuantity() : BigDecimal.ZERO;
            orderItem.setDeliveredQuantity(previousDelivered.add(shippedQuantity));
        }
        salesOrderItemRepository.saveAll(orderItems);

        boolean allDelivered = orderItems.stream().allMatch(item ->
                item.getDeliveredQuantity().compareTo(item.getQuantity()) >= 0);
        boolean anyDelivered = orderItems.stream().anyMatch(item ->
                item.getDeliveredQuantity().compareTo(BigDecimal.ZERO) > 0);

        // Bug fix (2026-07): 订单头部「已发货金额」汇总回写 —— 之前 actualShippedAmount
        // 字段从未被写入(定义了但无 writer), 前端永远显示 ¥0.00。逐行 getShippedAmount()
        // (已发货数量 × 折后单价) 求和; unitPrice 为 null (空价行/@PriceSensitive 脱敏) 时该行
        // 贡献 0 —— 与 createSalesOrder/updateSalesOrder 对 totalAmount 的既有 null-safe 累加
        // 惯例保持一致 (line ~1945: "unitPrice null → getLineAmount() null, 该行贡献 0"), 不额外
        // 发明"整单隐藏"新语义。
        BigDecimal totalShipped = BigDecimal.ZERO;
        for (SalesOrderItem item : orderItems) {
            BigDecimal lineShipped = item.getShippedAmount();
            totalShipped = totalShipped.add(lineShipped != null ? lineShipped : BigDecimal.ZERO);
        }
        order.setActualShippedAmount(totalShipped);

        if (allDelivered) {
            order.setStatus(SalesOrderStatus.COMPLETED);
            // P0-9: 全部发货完成 → transportPlanStatus = DELIVERED
            order.setTransportPlanStatus("DELIVERED");
            // 预留台账清扫 (2026-07-06): 整单发完 → 释放该 SO 剩余 ACTIVE 台账行 + 相应削 batch.reserved。
            // 修"Pass1 从别批发货导致预留批永不释放"的孤儿 —— Pass2 镜像只削已从预留发出的部分,
            // 若发货走的是别批的 available (Pass1), 本 SO 的预留行会残留, 这里在完成时统一释放。
            if (reservationLedgerService != null && order.getId() != null) {
                try {
                    reservationLedgerService.releaseAllForOrder(order.getId());
                } catch (Exception e) {
                    log.error("整单完成预留清扫失败(不影响发货): SO={}", order.getId(), e);
                }
            }
        } else if (anyDelivered) {
            order.setStatus(SalesOrderStatus.PARTIAL_DELIVERED);
            // P0-9: 部分发货 → transportPlanStatus = IN_TRANSIT
            order.setTransportPlanStatus("IN_TRANSIT");
        } else {
            order.setStatus(SalesOrderStatus.PARTIAL_DELIVERED);
            order.setTransportPlanStatus("IN_TRANSIT");
        }
        SalesOrder savedOrder = salesOrderRepository.save(order);
        if (allDelivered && !wasCompleted && applicationEventPublisher != null) {
            try {
                applicationEventPublisher.publishEvent(new com.cretas.aims.event.SalesOrderCompletedEvent(
                        this, record.getFactoryId(), savedOrder.getId()));
            } catch (Exception e) {
                log.error("发布 SalesOrderCompletedEvent 失败，不影响发货: SO={}", savedOrder.getId(), e);
            }
        }
    }

    /**
     * Posts shipment quantity to the exact persisted sales-order line. Old delivery rows that
     * predate sales_order_item_id may fall back by product only when that identity is unique;
     * ambiguous legacy data fails closed instead of incrementing every same-SKU line.
     */
    private SalesOrderItem resolvePostedSalesOrderItem(
            SalesDeliveryItem deliveryItem, List<SalesOrderItem> orderItems) {
        if (deliveryItem.getSalesOrderItemId() != null) {
            return orderItems.stream()
                    .filter(item -> Objects.equals(item.getId(), deliveryItem.getSalesOrderItemId()))
                    .filter(item -> Objects.equals(item.getProductTypeId(), deliveryItem.getProductTypeId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(409,
                            "发货明细关联的销售订单行不存在或产品身份不一致")
                            .withCode("DELIVERY_ORDER_ITEM_IDENTITY_MISMATCH")
                            .withHint("为避免记错账，操作已阻止；请核对该发货明细与销售订单对应的产品行，"
                                    + "重新关联订单行后重试")
                            .withHintTarget("salesOrderItemId"));
        }
        List<SalesOrderItem> candidates = orderItems.stream()
                .filter(item -> Objects.equals(item.getProductTypeId(), deliveryItem.getProductTypeId()))
                .toList();
        if (candidates.size() != 1) {
            throw new BusinessException(409, candidates.isEmpty()
                    ? "历史发货明细在销售订单中找不到对应产品行"
                    : "历史发货明细缺少订单行身份，且同一产品存在多行，不能安全回写")
                    .withCode(candidates.isEmpty()
                            ? "DELIVERY_ORDER_ITEM_NOT_FOUND"
                            : "LEGACY_DELIVERY_ORDER_ITEM_AMBIGUOUS")
                    .withHint(candidates.isEmpty()
                            ? "请核对该产品是否仍在此销售订单中，或为该发货明细补充正确的订单行关联后重试"
                            : "该发货明细缺少明确的订单行归属，且同一产品在订单中有多行，"
                                    + "无法安全判断记到哪一行；请为该发货明细补充明确的订单行关联后重试")
                    .withHintTarget("salesOrderItemId");
        }
        return candidates.get(0);
    }

    /**
     * Cost of one delivery-line unit, derived from the actual finished-goods batches shipped.
     *
     * <p>Finished-goods batches may be stored in kg while the sales line is in boxes/pcs. Batch
     * unitCost is in the batch native unit, so one delivery unit is converted into the native unit
     * before multiplying. Allocation rows are in the delivery item unit and are used as weights for
     * multi-batch shipments.
     */
    private BigDecimal computeDeliveryCostUnitPrice(String factoryId, SalesDeliveryItem item) {
        if (item == null || item.getDeliveredQuantity() == null || item.getDeliveredQuantity().signum() <= 0) {
            return null;
        }
        BigDecimal gramsPerUnit = productTypeRepository != null
                ? productTypeRepository.findById(item.getProductTypeId()).map(ProductType::getGramsPerUnit).orElse(null)
                : null;

        if (batchAllocationService != null && item.getId() != null) {
            List<com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation> allocations =
                    batchAllocationService.listByDeliveryItem(factoryId, String.valueOf(item.getId()));
            if (allocations != null && !allocations.isEmpty()) {
                BigDecimal totalQty = BigDecimal.ZERO;
                BigDecimal totalCost = BigDecimal.ZERO;
                for (com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation alloc : allocations) {
                    FinishedGoodsBatch batch = finishedGoodsBatchRepository.findById(alloc.getFinishedGoodsBatchId())
                            .orElse(null);
                    if (batch == null || batch.getUnitCost() == null || alloc.getAllocatedQty() == null) {
                        continue;
                    }
                    String allocUnit = alloc.getUnit() != null ? alloc.getUnit() : item.getUnit();
                    BigDecimal allocQtyInItemUnit = FgQuantityUnitConverter
                            .convert(alloc.getAllocatedQty(), allocUnit, item.getUnit(), gramsPerUnit);
                    BigDecimal costPerItemUnit = convertBatchUnitCost(batch, item, gramsPerUnit);
                    if (allocQtyInItemUnit == null || costPerItemUnit == null
                            || allocQtyInItemUnit.signum() <= 0) {
                        continue;
                    }
                    totalQty = totalQty.add(allocQtyInItemUnit);
                    totalCost = totalCost.add(allocQtyInItemUnit.multiply(costPerItemUnit));
                }
                if (totalQty.signum() > 0) {
                    return totalCost.divide(totalQty, 4, java.math.RoundingMode.HALF_UP);
                }
            }
        }

        if (item.getFinishedGoodsBatchId() == null) {
            return null;
        }
        return finishedGoodsBatchRepository.findById(item.getFinishedGoodsBatchId())
                .map(batch -> convertBatchUnitCost(batch, item, gramsPerUnit))
                .orElse(null);
    }

    private BigDecimal convertBatchUnitCost(
            FinishedGoodsBatch batch, SalesDeliveryItem item, BigDecimal gramsPerUnit) {
        if (batch == null || batch.getUnitCost() == null || item == null || item.getUnit() == null) {
            return null;
        }
        BigDecimal nativeQtyPerTargetUnit = FgQuantityUnitConverter.convertWithPackaging(
                BigDecimal.ONE,
                item.getUnit(),
                batch.getUnit(),
                gramsPerUnit,
                item.getPackagingUnit(),
                item.getPackagingBaseUnit(),
                item.getPackagingFactor(),
                batch.getPackagingUnit(),
                batch.getPackagingBaseUnit(),
                batch.getPackagingFactor());
        if (nativeQtyPerTargetUnit == null || nativeQtyPerTargetUnit.signum() <= 0) {
            return null;
        }
        return batch.getUnitCost().multiply(nativeQtyPerTargetUnit)
                .setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal blendCostUnitPrice(BigDecimal existingCostUnitPrice, BigDecimal previousDelivered,
                                          BigDecimal deliveryCostUnitPrice, BigDecimal deliveryQuantity) {
        BigDecimal prevQty = previousDelivered != null ? previousDelivered : BigDecimal.ZERO;
        BigDecimal newQty = deliveryQuantity != null ? deliveryQuantity : BigDecimal.ZERO;
        if (prevQty.signum() <= 0 || existingCostUnitPrice == null) {
            return deliveryCostUnitPrice;
        }
        BigDecimal totalQty = prevQty.add(newQty);
        if (totalQty.signum() <= 0) {
            return deliveryCostUnitPrice;
        }
        return existingCostUnitPrice.multiply(prevQty)
                .add(deliveryCostUnitPrice.multiply(newQty))
                .divide(totalQty, 4, java.math.RoundingMode.HALF_UP);
    }

    private void applyPackagingSelection(
            String factoryId,
            CreateSalesOrderRequest.SalesOrderItemDTO request,
            SalesOrderItem item) {
        // The eight-argument constructor is retained solely for older isolated unit tests.
        // Spring production wiring always injects this required service.
        if (productPackagingSpecService == null) return;
        ProductPackagingSpecService.PackagingSelection selection =
                productPackagingSpecService.resolveSelection(
                        factoryId, request.getProductTypeId(), request.getUnit(), request.getPackagingSpecId());
        if (selection.spec() == null) return;
        item.setPackagingSpecId(selection.spec().getId());
        item.setPackagingSpecName(selection.spec().getName());
        item.setPackagingUnit(selection.spec().getPackageUnit());
        item.setPackagingBaseUnit(selection.spec().getBaseUnit());
        item.setPackagingFactor(selection.spec().getConversionFactor());
        if (request.getQuantity() != null) item.setBoxQuantity(request.getQuantity());
    }

    /**
     * 显式行字段优先；RN 只提交结构化包装规格时，保留上一步解析出的规格名和箱数。
     * 旧实现无条件写入 null，导致 Web 端看不到规格/箱数快照。
     */
    static void applyLineDisplaySnapshots(
            CreateSalesOrderRequest.SalesOrderItemDTO request,
            SalesOrderItem item) {
        if (request.getSpecification() != null && !request.getSpecification().isBlank()) {
            item.setSpecification(request.getSpecification());
        } else if (item.getSpecification() == null && item.getPackagingSpecName() != null) {
            item.setSpecification(item.getPackagingSpecName());
        }
        if (request.getBoxQuantity() != null) {
            item.setBoxQuantity(request.getBoxQuantity());
        }
    }

    private void applyPackagingSelection(
            String factoryId,
            String productTypeId,
            String unit,
            String packagingSpecId,
            SalesDeliveryItem item) {
        if (productPackagingSpecService == null) return;
        ProductPackagingSpecService.PackagingSelection selection =
                productPackagingSpecService.resolveSelection(factoryId, productTypeId, unit, packagingSpecId);
        if (selection.spec() == null) return;
        item.setPackagingSpecId(selection.spec().getId());
        item.setPackagingSpecName(selection.spec().getName());
        item.setPackagingUnit(selection.spec().getPackageUnit());
        item.setPackagingBaseUnit(selection.spec().getBaseUnit());
        item.setPackagingFactor(selection.spec().getConversionFactor());
    }

    private void copyPackagingSnapshot(SalesOrderItem source, SalesDeliveryItem target) {
        target.setPackagingSpecId(source.getPackagingSpecId());
        target.setPackagingSpecName(source.getPackagingSpecName());
        target.setPackagingUnit(source.getPackagingUnit());
        target.setPackagingBaseUnit(source.getPackagingBaseUnit());
        target.setPackagingFactor(source.getPackagingFactor());
    }

    private void copyPackagingSnapshot(SalesOrderItem source, SalesOrderItem target) {
        target.setPackagingSpecId(source.getPackagingSpecId());
        target.setPackagingSpecName(source.getPackagingSpecName());
        target.setPackagingUnit(source.getPackagingUnit());
        target.setPackagingBaseUnit(source.getPackagingBaseUnit());
        target.setPackagingFactor(source.getPackagingFactor());
    }

    /** 下单行必须是明确的成品或半成品；原料/包材只能进入 suppliedMaterials。 */
    /**
     * 单位落库前归一成权威码 —— 销售侧<b>所有</b>写入点都走这一个函数。
     *
     * <p>权威表在 {@code UnitContractServiceImpl.systemAliases()}: 计数/包装类的<b>规范码就是中文字</b>
     * ({@code alias("盒","盒","box")} —— 码是「盒」, {@code box} 是别名), 质量/体积/长度类保留
     * 国际符号 ({@code kg}/{@code g}/{@code t}/{@code jin}/{@code ml}…)。所以本函数
     * 「box→盒」而「kg→kg」, ⛔ 不是无差别中文化。
     *
     * <p>⛔ <b>没有新建第三张单位表</b> —— 本仓已经有三张在漂(形态 D), 这里只调既有权威。
     *
     * <p>{@code storageUnit} 对已经规范的值是<b>幂等</b>的(盒→盒, kg→kg, 只→只),
     * 对权威表认不出的自由文本原样返回, 所以接在写入点上不会改写用户自己填的东西。
     */
    private String storageUnit(String factoryId, String rawUnit) {
        if (unitContractService == null || rawUnit == null || rawUnit.isBlank()) {
            return rawUnit;
        }
        String canonical = unitContractService.storageUnit(factoryId, rawUnit);
        if (canonical == null || canonical.isBlank()) {
            return rawUnit;
        }
        if (!canonical.equals(rawUnit)) {
            log.info("单位落库归一: factoryId={}, raw={}, stored={}", factoryId, rawUnit, canonical);
        }
        return canonical;
    }

    /**
     * 下单时明示「这东西发得出去吗」—— <b>只提示, 不拦截</b>(第一步)。
     *
     * <p>三档与实测覆盖率见 {@code SalesOrderStockAvailabilityService} 的类 javadoc。
     * 拦截分支由 {@code isEnforcing()} 控制, 默认关闭; 打开的前提写在
     * {@code ENFORCE_GATE_PROPERTY} 上。
     */
    private List<String> collectStockAdvisories(
            String factoryId, List<CreateSalesOrderRequest.SalesOrderItemDTO> items) {
        List<String> advisories = new ArrayList<>();
        if (stockAvailabilityService == null || items == null || items.isEmpty()) {
            return advisories;
        }
        List<com.cretas.aims.service.sales.SalesOrderStockAvailabilityService.Line> lines =
                new ArrayList<>();
        for (CreateSalesOrderRequest.SalesOrderItemDTO item : items) {
            if (item == null || item.getProductTypeId() == null || item.getProductTypeId().isBlank()) {
                continue;
            }
            lines.add(new com.cretas.aims.service.sales.SalesOrderStockAvailabilityService.Line(
                    item.getProductTypeId(), item.getProductName(), item.getUnit()));
        }
        List<com.cretas.aims.service.sales.SalesOrderStockAvailabilityService.Availability> results =
                stockAvailabilityService.assess(factoryId, lines);
        for (var r : results) {
            if (r.message() != null && !r.message().isBlank()) {
                advisories.add(r.message());
            }
        }
        if (stockAvailabilityService.isEnforcing()) {
            var blocked = results.stream()
                    .filter(r -> r.tier()
                            == com.cretas.aims.service.sales.SalesOrderStockAvailabilityService.Tier.NONE)
                    .findFirst().orElse(null);
            if (blocked != null) {
                throw new BusinessException(400, blocked.message())
                        .withCode("SALES_ORDER_ITEM_NO_STOCK")
                        .withHint("去采购下单或建生产计划; 若确实有货, 请先补做入库")
                        .withHintTarget("productTypeId")
                        .withSeverity("BLOCKING");
            }
        }
        return advisories;
    }

    private void assertItemsAreSellable(
            String factoryId, List<CreateSalesOrderRequest.SalesOrderItemDTO> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (CreateSalesOrderRequest.SalesOrderItemDTO item : items) {
            String pid = item == null ? null : item.getProductTypeId();
            if (pid == null || pid.isBlank()) {
                continue;
            }
            ProductType product = productTypeRepository.findByIdAndFactoryId(pid, factoryId)
                    .orElseThrow(() -> new BusinessException(400, "销售订单商品不存在或不属于当前工厂")
                            .withCode("SALES_ORDER_ITEM_NOT_SELLABLE")
                            .withHintTarget("productTypeId")
                            .withSeverity("BLOCKING"));
            if (!ProductCategory.isSalesOrderSellable(product.getProductCategory())) {
                throw new BusinessException(400,
                        "销售订单只能选择成品或半成品: " + product.getName())
                        .withCode("SALES_ORDER_ITEM_NOT_SELLABLE")
                        .withHint("原料、包材和未知分类请移至客户供料 suppliedMaterials")
                        .withHintTarget("productTypeId")
                        .withSeverity("BLOCKING");
            }
        }
    }

    private void validateSupplyContract(
            SalesProcessingMode processingMode,
            MaterialSupplyMode materialSupplyMode,
            List<CreateSalesOrderRequest.SalesOrderItemDTO> items) {
        if (processingMode == null || materialSupplyMode == null) {
            throw new BusinessException(400, "加工方式和物料供应方式必须明确选择")
                    .withCode("SALES_ORDER_SUPPLY_CONTRACT_REQUIRED")
                    .withHintTarget(processingMode == null ? "processingMode" : "materialSupplyMode");
        }
        if (processingMode == SalesProcessingMode.STANDARD_SALE
                && materialSupplyMode == MaterialSupplyMode.CUSTOMER_SUPPLIED) {
            throw new BusinessException(400, "普通销售不能选择客户自带原料")
                    .withCode("SALES_ORDER_SUPPLY_CONTRACT_INVALID")
                    .withHint("如客户提供原料，请将加工方式改为代加工")
                    .withHintTarget("materialSupplyMode");
        }
        if (items == null) {
            return;
        }
        for (CreateSalesOrderRequest.SalesOrderItemDTO item : items) {
            if ((item.getProcessingMode() != null && item.getProcessingMode() != processingMode)
                    || (item.getMaterialSupplyMode() != null
                    && item.getMaterialSupplyMode() != materialSupplyMode)) {
                throw new BusinessException(400, "当前版本暂不支持同一销售订单内混合加工或供料方式")
                        .withCode("SALES_ORDER_MIXED_SUPPLY_MODE_UNSUPPORTED")
                        .withHint("请拆分为供料方式一致的销售订单")
                        .withHintTarget("items");
            }
        }
    }

    private void validateSuppliedMaterialPayload(
            SalesProcessingMode processingMode,
            MaterialSupplyMode materialSupplyMode,
            CustomerStockFulfillmentMode fulfillmentMode,
            List<CreateSalesOrderRequest.SuppliedMaterialRequirementDTO> suppliedMaterials,
            boolean nullMeansPreserve) {
        boolean customerSuppliedToll = processingMode == SalesProcessingMode.TOLL_PROCESSING
                && materialSupplyMode == MaterialSupplyMode.CUSTOMER_SUPPLIED;
        CustomerStockFulfillmentMode normalizedMode =
                normalizeCustomerStockFulfillmentMode(fulfillmentMode);
        if (!customerSuppliedToll && normalizedMode == CustomerStockFulfillmentMode.PRESTOCKED) {
            throw new BusinessException(400,
                    "只有代加工且客户自带原料的订单可以使用客户已有库存")
                    .withCode("CUSTOMER_STOCK_FULFILLMENT_MODE_INVALID")
                    .withHintTarget("customerStockFulfillmentMode");
        }
        boolean hasPayload = suppliedMaterials != null && !suppliedMaterials.isEmpty();
        if (customerSuppliedToll
                && normalizedMode == CustomerStockFulfillmentMode.ORDER_DRIVEN
                && (!nullMeansPreserve || suppliedMaterials != null)
                && !hasPayload) {
            throw new BusinessException(400, "代加工且客户自带原料时必须填写客供物料需求")
                    .withCode("CUSTOMER_SUPPLIED_REQUIREMENTS_REQUIRED")
                    .withHintTarget("suppliedMaterials");
        }
        if ((!customerSuppliedToll
                || normalizedMode == CustomerStockFulfillmentMode.PRESTOCKED) && hasPayload) {
            throw new BusinessException(400,
                    "使用客户已有库存时不能再创建本订单客供物料需求")
                    .withCode("CUSTOMER_SUPPLIED_REQUIREMENTS_MODE_INVALID")
                    .withHintTarget("suppliedMaterials");
        }
    }

    private CustomerStockFulfillmentMode normalizeCustomerStockFulfillmentMode(
            CustomerStockFulfillmentMode mode) {
        return mode != null ? mode : CustomerStockFulfillmentMode.ORDER_DRIVEN;
    }

    private SalesOrderSuppliedMaterialRequirementService requireSuppliedMaterialRequirementService() {
        if (suppliedMaterialRequirementService == null) {
            throw new IllegalStateException(
                    "SalesOrderSuppliedMaterialRequirementService is required for suppliedMaterials");
        }
        return suppliedMaterialRequirementService;
    }

    private void validatePersistedSupplyContract(SalesOrder source) {
        validateSupplyContract(source.getProcessingMode(), source.getMaterialSupplyMode(), null);
        validateSuppliedMaterialPayload(
                source.getProcessingMode(),
                source.getMaterialSupplyMode(),
                normalizeCustomerStockFulfillmentMode(source.getCustomerStockFulfillmentMode()),
                null,
                true);
        if (source.getItems() == null) {
            return;
        }
        for (SalesOrderItem item : source.getItems()) {
            if (item.getProcessingMode() != source.getProcessingMode()
                    || item.getMaterialSupplyMode() != source.getMaterialSupplyMode()) {
                throw new BusinessException(409, "源销售订单的供料契约不完整，不能复制")
                        .withCode("SALES_ORDER_SUPPLY_SNAPSHOT_INCONSISTENT")
                        .withHint("请先在草稿编辑中明确并保存加工方式与物料供应方式");
            }
        }
    }

    private String normalizeSourceWarehouseCode(String factoryId, String sourceWarehouseCode) {
        if (sourceWarehouseCode == null || sourceWarehouseCode.isBlank()) {
            return null;
        }
        String normalized = sourceWarehouseCode.trim();
        if (factoryWarehouseRepository == null) {
            throw new IllegalStateException("FactoryWarehouseRepository is required for source warehouse validation");
        }
        return factoryWarehouseRepository
                .findByFactoryIdAndCodeAndDeletedAtIsNull(factoryId, normalized)
                .filter(warehouse -> Boolean.TRUE.equals(warehouse.getIsActive()))
                // Sales source warehouses are deliberately limited to real stock
                // stages and the logistics/finished-goods stores.  UI filtering is
                // only a convenience; this server-side gate protects API callers,
                // old clients, and cross-role warehouse edits alike.
                .filter(warehouse -> warehouse.getType() != null && java.util.Set.of(
                        com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType.RAW,
                        com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType.WORKSHOP,
                        com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType.WIP,
                        com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType.LINESIDE,
                        com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType.LOGISTICS,
                        com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType.FINISHED
                ).contains(warehouse.getType()))
                .map(warehouse -> warehouse.getCode())
                .orElseThrow(() -> new BusinessException(400, "来源仓库不存在、已停用或不属于当前工厂")
                        .withCode("SOURCE_WAREHOUSE_INVALID")
                        .withHint("请重新选择当前工厂的有效来源仓库")
                        .withHintTarget("sourceWarehouseCode"));
    }

    private SalesOrderItem resolveDeliverySourceOrderItem(
            CreateDeliveryRequest.DeliveryItemDTO request,
            Map<Long, SalesOrderItem> sourceById,
            Map<String, List<SalesOrderItem>> sourceByProduct,
            boolean linkedOrder) {
        if (!linkedOrder) return null;
        if (request.getSalesOrderItemId() != null) {
            SalesOrderItem exact = sourceById.get(request.getSalesOrderItemId());
            if (exact == null || !Objects.equals(exact.getProductTypeId(), request.getProductTypeId())) {
                throw new BusinessException(400, "发货明细未关联到当前销售订单行")
                        .withCode("SALES_ORDER_ITEM_INVALID")
                        .withHintTarget("salesOrderItemId");
            }
            return exact;
        }

        List<SalesOrderItem> candidates = sourceByProduct
                .getOrDefault(request.getProductTypeId(), List.of());
        if (request.getPackagingSpecId() != null && !request.getPackagingSpecId().isBlank()) {
            return candidates.stream()
                    .filter(item -> Objects.equals(item.getPackagingSpecId(), request.getPackagingSpecId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(400, "发货箱规不属于当前销售订单明细")
                            .withCode("PACKAGING_SPEC_ORDER_LINE_MISMATCH")
                            .withHintTarget("packagingSpecId"));
        }
        if (candidates.isEmpty()) {
            throw new BusinessException(400, "发货产品不属于当前销售订单")
                    .withCode("SALES_ORDER_ITEM_INVALID")
                    .withHintTarget("productTypeId");
        }
        Set<String> packagingIds = candidates.stream()
                .map(item -> item.getPackagingSpecId() == null ? "" : item.getPackagingSpecId())
                .collect(Collectors.toSet());
        if (packagingIds.size() > 1) {
            throw new BusinessException(400, "该 SKU 在销售订单中使用了多个箱规，请选择具体订单行")
                    .withCode("PACKAGING_SPEC_REQUIRED")
                    .withHintTarget("salesOrderItemId");
        }
        return candidates.get(0);
    }

    private List<SalesDeliveryRecord> effectiveMasterDeliveries(List<SalesDeliveryRecord> deliveries) {
        if (deliveries == null) return List.of();
        return deliveries.stream()
                .filter(delivery -> delivery.getParentDeliveryId() == null)
                .filter(delivery -> !"SHIPMENT".equals(delivery.getRecordRole()))
                .filter(delivery -> delivery.getStatus() != SalesDeliveryStatus.CANCELLED
                        && delivery.getStatus() != SalesDeliveryStatus.RETURNED)
                .toList();
    }

    // ==================== T3: 业务员双字段过渡 ====================

    /** Numeric user-id string pattern (User.id is Long, frontend sends as decimal string). */
    private static final java.util.regex.Pattern USER_ID_PATTERN =
        java.util.regex.Pattern.compile("^\\d{1,19}$");

    /**
     * 解析业务员字段 — 双字段过渡 (option 3 per spec §4.A.4).
     * 数字字符串 (User.id) → lookup user → 写入 salesperson_id + salesperson(name 快照)
     * 含非数字字符的字符串 → 老路径，只写 salesperson
     * null/空 → 不动
     */
    public void resolveSalespersonField(SalesOrder order, String input, String factoryId) {
        if (input == null || input.isBlank()) return;
        if (USER_ID_PATTERN.matcher(input).matches()) {
            Long userId = Long.parseLong(input);
            // R4 audit S2: if numeric input matches existing FK, no-op (don't re-lookup)
            // — covers the "user clicks save without touching salesperson, HR deleted user
            // mid-edit" case where re-lookup would throw 404.
            Long currentFkId = order.getSalespersonId();
            if (currentFkId != null && currentFkId.equals(userId)) {
                return;
            }
            User user = userRepository.findById(userId)
                .filter(u -> factoryId.equals(u.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("业务员不存在或不属于本工厂: " + input));
            order.setSalespersonId(userId);
            order.setSalesperson(salespersonSnapshotOf(user));  // M1: snapshot name at save time
        } else {
            // R4 audit C2: defensive — if input string equals existing snapshot AND existing
            // FK is non-null, this is the legacy edit form re-PUTting the snapshot string
            // unchanged (LEGACY-mode list.vue dialog only sends `salesperson` not the FK).
            // Preserve the FK rather than wipe it, otherwise commission attribution silently
            // breaks for ANY legacy edit of a DYNAMIC-created SO.
            if (input.equals(order.getSalesperson()) && order.getSalespersonId() != null) {
                return;  // no-op, snapshot unchanged + FK already linked
            }
            order.setSalesperson(input);
            order.setSalespersonId(null);
        }
    }

    /**
     * 业务员姓名快照 —— <b>FK 指着谁，这里就必须有名字</b>。
     *
     * <h3>修的是什么</h3>
     * <p>原来这里直接写 {@code user.getFullName()}。{@code users.full_name} 是
     * <b>nullable</b> 的（{@code User.java:60} 没有 {@code nullable = false}），
     * 于是没填全名的账号建单时 {@code salesperson_id} 被写上、而 {@code salesperson}
     * 被写成 <b>null</b> —— 单据上「业务员」一栏空着，可 FK 明明指着一个真实的人。
     * 两个字段自相矛盾，而且没有任何报错。
     *
     * <h3>为什么退到 username 而不是抛异常</h3>
     * <p>这不是「兜底把不知道翻译成一个值」（形态 A¹⁰）—— 那条规则针对的是<b>凭空造数</b>。
     * 这里人是<b>已知</b>的：FK 有效、账号存在、{@code username} 非空
     * （{@code users.username} 声明为 {@code nullable = false}）。
     * 真正在撒谎的是 {@code null}：它说「这单没有业务员」，而 FK 说有。
     * ⇒ 退到一个<b>更弱但为真</b>的标识，比写一个为假的空值好；
     * 也比为了一个能正常下单的账号直接 400 好。
     *
     * <p>两者都拿不到才抛 —— 那时才真的是「不知道」，不许静默落库。
     *
     * <h3>实测口径（2026-08-19，cretas_prod_db / public schema，只读）</h3>
     * <ul>
     *   <li>{@code users} 79 行，{@code full_name} 非空 78 行 ⇒ <b>1 个账号</b>属于这一类；</li>
     *   <li>{@code sales_orders} 38 行，{@code salesperson_id} 非空 4 行，
     *       其中 {@code salesperson} 为 null 的 <b>0</b> 行 ——
     *       ⇒ 这条到今天为止<b>还没在生产上发生过</b>，是潜在缺陷不是已发生事故。</li>
     * </ul>
     * <p>App 端走的正是数字分支：RN {@code SalesOrderCreateScreen} 发
     * {@code String(user.id)}，而 {@code BaseUser.fullName} 在 RN 的类型里就是可选的。
     */
    static String salespersonSnapshotOf(User user) {
        String fullName = user.getFullName();
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }
        String username = user.getUsername();
        if (username != null && !username.isBlank()) {
            log.warn("业务员快照回退到 username: userId={} 没有 full_name —— "
                    + "单据会显示登录名, 请到用户资料补全姓名", user.getId());
            return username;
        }
        // 两个标识都没有 = 真的不知道。⛔ 不写 null 落库 —— 那会让 salesperson_id
        // 指着一个人而 salesperson 说没有人。
        throw new BusinessException(422,
                "业务员账号 " + user.getId() + " 既没有姓名也没有登录名, 无法记录业务员")
                .withHint("请先在用户管理里补全该账号的姓名")
                .withHintTarget("salesperson");
    }

    /**
     * 本次发货的【价税合计】实发金额 —— 应收挂账口径。
     *
     * <h3>它把 #292 与 #302 合成一个数</h3>
     * <ul>
     *   <li>#302【数量基准】: 按<b>实发</b>量算, 不按订单量
     *       (仓管把实发从 2 改成 5, 库存真扣 5 —— 生产实测 SO-20260828-0012)</li>
     *   <li>#292【口径】: 结果必须<b>含税</b>, 与同一次发货生成的销项发票一致
     *       (发票 250.00 / 旧应收 221.24 —— 生产 7/7 全中)</li>
     * </ul>
     *
     * <h3>⛔ 为什么不能直接用 {@code record.getTotalAmount()}</h3>
     * 它是 Σ 实发量 × 单价, 而 {@code SalesDeliveryItem.unitPrice} 是<b>未税</b>价
     * (实测 {@code DLV-20260828-9106-01} 发货单额 221.24 == 订单未税额 221.24)。
     * 而且发货单上<b>没有任何税字段</b>: {@code sales_delivery_records} 只有
     * {@code total_amount}, {@code sales_delivery_items} 只有 {@code unit_price}
     * ⇒ 税率只能回订单行借 ({@code salesOrderItemId → SalesOrderItem.taxRate})。
     *
     * <h3>诚实的降级 (与采购侧 taxInclusiveReceivedValue 同一纪律)</h3>
     * <ul>
     *   <li>行无单价/无数量 → 该行贡献 0 (与旧 totalAmount 同口径, 不伪造金额)</li>
     *   <li>挂不上订单行 / 税率为空 → <b>按 0 税率</b>只算未税额。
     *       ⛔ 不猜税率 —— 宁可少算, 也不凭空造一笔税。</li>
     * </ul>
     * 取整与 {@link com.cretas.aims.entity.inventory.PurchaseOrderItem#getLineAmountWithTax()}
     * 及采购侧助手一致: 行级 {@code setScale(2, HALF_UP)}, 税率除法 6 位 HALF_UP。
     *
     * <p>折扣: 订单级 {@code discountAmount} 按本次发货的<b>未税金额占订单未税总额的比例</b>
     * 摊到本次挂账上，口径与取整见 {@link #orderDiscountShareForShipment}。
     * 2026-08-29 之前它<b>完全不参与</b>本式 —— 有折扣的订单会把应收多记整整一个折扣额。
     */
    private BigDecimal taxInclusiveShippedValue(SalesOrder order, SalesDeliveryRecord record) {
        BigDecimal grossOfDiscount = taxInclusiveShippedValueBeforeDiscount(order, record);
        BigDecimal discountShare = orderDiscountShareForShipment(order, record);
        if (discountShare.signum() == 0) {
            return grossOfDiscount;
        }
        return grossOfDiscount.subtract(discountShare);
    }

    /** 折扣<b>之前</b>的本次发货价税合计 —— 原 {@code taxInclusiveShippedValue} 的算式，逐字未改。 */
    private BigDecimal taxInclusiveShippedValueBeforeDiscount(SalesOrder order, SalesDeliveryRecord record) {
        if (record == null || record.getItems() == null || record.getItems().isEmpty()) {
            return record != null && record.getTotalAmount() != null
                    ? record.getTotalAmount() : BigDecimal.ZERO;
        }
        java.util.Map<Long, BigDecimal> taxRateByOrderItemId = new java.util.HashMap<>();
        if (order != null && order.getItems() != null) {
            for (SalesOrderItem oi : order.getItems()) {
                if (oi.getId() != null) {
                    taxRateByOrderItemId.put(oi.getId(), oi.getTaxRate());
                }
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        for (SalesDeliveryItem item : record.getItems()) {
            if (item.getUnitPrice() == null || item.getDeliveredQuantity() == null) {
                continue;
            }
            BigDecimal net = item.getDeliveredQuantity().multiply(item.getUnitPrice())
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal rate = item.getSalesOrderItemId() != null
                    ? taxRateByOrderItemId.get(item.getSalesOrderItemId()) : null;
            if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) {
                total = total.add(net);
                continue;
            }
            BigDecimal multiplier = BigDecimal.ONE.add(
                    rate.divide(new BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP));
            total = total.add(net.multiply(multiplier).setScale(2, java.math.RoundingMode.HALF_UP));
        }
        return total;
    }

    /** 本次发货的<b>未税</b>金额 —— 折扣摊派的分子。空行时退回发货单头额（与含税式同一降级）。 */
    private BigDecimal netShippedValue(SalesDeliveryRecord record) {
        if (record == null) {
            return BigDecimal.ZERO;
        }
        if (record.getItems() == null || record.getItems().isEmpty()) {
            return record.getTotalAmount() != null ? record.getTotalAmount() : BigDecimal.ZERO;
        }
        BigDecimal net = BigDecimal.ZERO;
        for (SalesDeliveryItem item : record.getItems()) {
            if (item.getUnitPrice() == null || item.getDeliveredQuantity() == null) {
                continue;
            }
            net = net.add(item.getDeliveredQuantity().multiply(item.getUnitPrice())
                    .setScale(2, java.math.RoundingMode.HALF_UP));
        }
        return net;
    }

    /**
     * 订单级折扣摊到<b>本次发货</b>的份额。
     *
     * <h2>选了哪种口径，为什么</h2>
     * <b>按发货的未税金额占订单未税总额的比例摊</b>。
     * 依据不是「最常见」，而是本实体自己已有的权威口径:
     * {@code SalesOrder.getPayableAmount() = totalAmount(未税) − discountAmount + taxAmount}
     * —— 折扣是<b>扣在未税额上</b>的一笔平摊减项，且<b>不影响税额</b>。
     * ⇒ 分母只能是未税总额；用含税额当分母会让整单发完时摊出来的总和 ≠ discountAmount。
     *
     * <p>自洽判据（也是本方法唯一值得记住的一条）:
     * <b>整单一次发完时，挂账金额必须恰好等于 {@code order.getPayableAmount()}。</b>
     * 2026-08-29 之前不成立 —— 折扣完全不参与，应收整整多记一个折扣额。
     *
     * <h2>🔴 取整误差: 选了【累计摊派】，⛔ 不是「最后一批兜底」也不是「逐批 HALF_UP」</h2>
     * <pre>
     *   已摊(含本次) = round(discount × min(累计发货未税 / 订单未税, 1), 2)
     *   已摊(本次前) = round(discount × min(先前发货未税 / 订单未税, 1), 2)
     *   本次份额     = 已摊(含本次) − 已摊(本次前)
     * </pre>
     * 三种做法的差别，必须表态:
     * <ul>
     *   <li><b>逐批 HALF_UP</b>（各批独立四舍五入）: Σ 各批份额 <b>不等于</b> discountAmount，
     *       差几分。整单发完后应收合计 ≠ 应付额，财务对账每次都要解释那几分。⛔ 不选。</li>
     *   <li><b>最后一批兜底</b>（末批吸收全部残差）: 口径本身没问题，
     *       但它<b>要求在挂账那一刻知道「这是不是最后一批」</b>，而那是<b>未来的信息</b> ——
     *       订单可以长期只发一半、可以中途取消、可以追加。
     *       猜错的代价是把残差记到一个并非末批的批次上，之后再也无法更正。
     *       <b>一条需要预知未来才能执行的规则，不是可实现的规则。</b>⛔ 不选。</li>
     *   <li><b>累计摊派</b>（本实现）: 只用已经发生的事实。整单发完时
     *       累计发货未税 == 订单未税 ⇒ 累计已摊 == round(discount, 2) == discountAmount，
     *       <b>残差自动归零，不需要知道哪一批是最后一批</b>；
     *       单批与其等比份额的偏差 ≤ 0.01。✅ 选它。</li>
     * </ul>
     *
     * <h2>⛔ 本方法诚实的降级（一律返回 0，不猜）</h2>
     * <ul>
     *   <li>无折扣 / 折扣 ≤ 0 → 0。<b>这一格保证了本次改动对生产存量是零影响</b>:
     *       实测 63 张订单 0 张用订单级折扣，全部走这一分支，
     *       连 {@code findBySalesOrderId} 那次查询都不会发生。</li>
     *   <li>订单未税总额为 null 或 ≤ 0 → 0 + WARN。没有分母就没有比例，
     *       ⛔ 不兜底成「全摊到本次」——那会把「不知道」变成一个很有说服力的数。</li>
     * </ul>
     *
     * <h2>⚠️ 本方法<b>不</b>处理的（显式登记，不是漏了）</h2>
     * <ul>
     *   <li><b>退货冲回</b>: 已发货后退回时，先前那一批的折扣份额不会被本方法退回来。
     *       退货的应收冲销是另一条路径，本方法只管「发出去那一刻挂多少」。</li>
     *   <li><b>超发</b>: {@code min(比例, 1)} 把折扣封顶在 discountAmount，
     *       超发的部分<b>不再享受折扣</b>。这是有意的 —— 折扣是整单谈定的一笔钱，
     *       多发的货没有理由分走更多。</li>
     *   <li><b>折扣改了之后</b>: 改 {@code discountAmount} 不会重算已挂的账。</li>
     * </ul>
     *
     * @return 本次应从挂账金额中扣掉的折扣（非负；无法摊派时为 {@code ZERO}）
     */
    private BigDecimal orderDiscountShareForShipment(SalesOrder order, SalesDeliveryRecord record) {
        if (order == null || record == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount = order.getDiscountAmount();
        if (discount == null || discount.signum() <= 0) {
            return BigDecimal.ZERO;   // 生产存量 63/63 走这一支 —— 零行为变化
        }
        BigDecimal orderNet = order.getTotalAmount();
        if (orderNet == null || orderNet.signum() <= 0) {
            log.warn("订单级折扣无法摊派: orderId={} 折扣={} 但订单未税总额为 {} —— "
                            + "本次挂账【不扣折扣】(⛔ 不猜一个分母)",
                    order.getId(), discount.toPlainString(), orderNet);
            return BigDecimal.ZERO;
        }

        BigDecimal thisNet = netShippedValue(record);
        BigDecimal priorNet = priorShippedNetForOrder(order.getId(), record);

        BigDecimal allocatedIncludingThis = allocateByRatio(discount, priorNet.add(thisNet), orderNet);
        BigDecimal allocatedBefore = allocateByRatio(discount, priorNet, orderNet);
        BigDecimal share = allocatedIncludingThis.subtract(allocatedBefore);
        // 份额理论上非负 (累计只增不减); 真出现负数说明先前发货的口径与本次不一致 ——
        // ⛔ 不要把负折扣加到应收上(那会把应收调高), 记 WARN 后按 0 处理。
        if (share.signum() < 0) {
            log.warn("订单级折扣摊派算出负份额: orderId={}, deliveryId={}, 先前未税={}, 本次未税={} —— 按 0 处理",
                    order.getId(), record.getId(), priorNet, thisNet);
            return BigDecimal.ZERO;
        }
        return share;
    }

    /** {@code round(discount × min(numerator / orderNet, 1), 2)}，HALF_UP。 */
    private BigDecimal allocateByRatio(BigDecimal discount, BigDecimal numerator, BigDecimal orderNet) {
        if (numerator == null || numerator.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal ratio = numerator.divide(orderNet, 10, java.math.RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            ratio = BigDecimal.ONE;   // 超发不多分折扣
        }
        return discount.multiply(ratio).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 同一订单下、<b>本次之外</b>已经发出去的未税金额合计。
     *
     * <p>只数<b>货真的出门了</b>的那些状态 —— 草稿/待确认还没挂账，
     * 已取消/已退回的那部分不该继续占着折扣额度。
     *
     * <p>⚠️ 取的是 {@code record.getTotalAmount()}（Σ 实发量 × <b>未税</b>单价，
     * 见本类 {@code taxInclusiveShippedValue} 的实测注释），
     * 所以不需要把兄弟发货单的行明细都拉起来（避免 N+1 + 懒加载）。
     */
    private BigDecimal priorShippedNetForOrder(String salesOrderId, SalesDeliveryRecord current) {
        if (salesOrderId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (SalesDeliveryRecord sibling : deliveryRecordRepository.findBySalesOrderId(salesOrderId)) {
            if (sibling == null || sibling.getId() == null
                    || sibling.getId().equals(current.getId())) {
                continue;
            }
            SalesDeliveryStatus st = sibling.getStatus();
            if (st != SalesDeliveryStatus.PARTIALLY_SHIPPED
                    && st != SalesDeliveryStatus.SHIPPED
                    && st != SalesDeliveryStatus.DELIVERED) {
                continue;
            }
            if (sibling.getTotalAmount() != null) {
                sum = sum.add(sibling.getTotalAmount());
            }
        }
        return sum;
    }
}
