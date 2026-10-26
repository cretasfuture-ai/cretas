package com.cretas.aims.service.impl;

import com.cretas.aims.service.production.DefectCountResolver;
import com.cretas.aims.entity.enums.DefectCountOutcome;
import com.cretas.aims.dto.common.ImportResult;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.DeliveryWarnDTO;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.production.UpdatePlanScheduleMetaRequest;
import com.cretas.aims.dto.production.ProductionPlanImportDTO;
import com.cretas.aims.enums.DeliveryWarnLevel;
import com.cretas.aims.dto.production.ProductionPlanMaterialAdvisoryDTO;
import com.cretas.aims.dto.production.ProductionSettlementBomEligibilityResponse;
import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.dto.production.ProductionSettlementResponse;
import com.cretas.aims.dto.production.ProductionTransitClearingRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptResponse;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.workflow.WorkflowPlanBindingDTO;
import com.cretas.aims.dto.workflow.WorkflowPlanRebindSummaryDTO;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.service.bom.PlanBomItems;
import com.cretas.aims.entity.bom.BomItemSubstitute;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.User;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomItemSubstituteRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.ProductionInventoryOwnershipGuard;
import com.cretas.aims.service.wip.WipLedgerEntries;
import com.cretas.aims.utils.ExcelUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 生产计划服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
public class ProductionPlanServiceImpl implements ProductionPlanService {
    private static final Logger log = LoggerFactory.getLogger(ProductionPlanServiceImpl.class);
    private static final ObjectMapper PROCESS_SHEET_ROW_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ProductionPlanRepository productionPlanRepository;

    /** SSOP 阻产台账（可选注入，见 recordSsopBlockingGateIfAny）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.foodsafety.SsopExecutionRecordRepository ssopExecutionRecordRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.foodsafety.SsopBlockingGateRepository ssopBlockingGateRepository;
    private final ProductionBatchRepository productionBatchRepository;

    /**
     * 已编译的运行时实例 —— 只用来判「这个计划还能不能换配方」。
     *
     * <p>实例是批次物化时按<b>批次自己那份</b>权威冻结的图(nodes_json), 之后不会跟着计划的
     * 指针走。所以计划一旦物化出实例, 光改计划指针是骗人的: 报工单读的还是旧图。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository
            productionWorkflowInstanceRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.workprocess.WorkProcessTaskRepository
            repinWorkProcessTaskRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.workflow.WorkflowTaskPortRepository
            repinWorkflowTaskPortRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final ProductionPlanBatchUsageRepository planBatchUsageRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ProductionPlanMapper productionPlanMapper;
    private final ConversionRepository conversionRepository;
    private final SchedulingService schedulingService;
    private final ProductionLineRepository productionLineRepository;
    private final UserRepository userRepository;
    private final ExcelUtil excelUtil;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CustomerRepository customerRepository;
    /** Sprint 3 Track-F: unified cross-business link service (double-write w/ sourceOrderId). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LinkArrayService linkArrayService;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.finance.ProductionCostConsistency productionCostConsistency;
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.finance.ReturnedCostSynchronizationService returnedCostSynchronizationService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.unit.UnitContractService unitContractService;

    // Manual constructor (Lombok @RequiredArgsConstructor not working)
    public ProductionPlanServiceImpl(
            ProductionPlanRepository productionPlanRepository,
            ProductionBatchRepository productionBatchRepository,
            MaterialBatchRepository materialBatchRepository,
            MaterialConsumptionRepository materialConsumptionRepository,
            ProductionPlanBatchUsageRepository planBatchUsageRepository,
            ProductTypeRepository productTypeRepository,
            ProductionPlanMapper productionPlanMapper,
            ConversionRepository conversionRepository,
            SchedulingService schedulingService,
            ProductionLineRepository productionLineRepository,
            UserRepository userRepository,
            ExcelUtil excelUtil,
            SalesOrderRepository salesOrderRepository,
            SalesOrderItemRepository salesOrderItemRepository) {
        this.productionPlanRepository = productionPlanRepository;
        this.productionBatchRepository = productionBatchRepository;
        this.materialBatchRepository = materialBatchRepository;
        this.materialConsumptionRepository = materialConsumptionRepository;
        this.planBatchUsageRepository = planBatchUsageRepository;
        this.productTypeRepository = productTypeRepository;
        this.productionPlanMapper = productionPlanMapper;
        this.conversionRepository = conversionRepository;
        this.schedulingService = schedulingService;
        this.productionLineRepository = productionLineRepository;
        this.userRepository = userRepository;
        this.excelUtil = excelUtil;
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
    }

    /** SP2 二次加工: WIP 半成品库存扣减. required=false 避免循环依赖风险. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.wip.WipInventoryService wipInventoryService;

    /** R2 (2026-07-04): 结单族 process-row 的 SFI/FG 投料严格扣减 (防 phantom). required=false 兼容单测. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.inventory.FinishedGoodsFeedService finishedGoodsFeedService;

    /** Settlement SFI consumption must have an immutable OUT ledger row. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SemiFinishedInventoryTransactionRepository semiFinishedInventoryTransactionRepository;

    /** 质检放行 (缺口 11/13): 成品入库时叠加「待检」。未注入 (单测) ⇒ 与以前一样 AVAILABLE。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.quality.release.QcInspectionPolicy qcInspectionPolicy;

    private String finishedGoodsOutputStatus(String factoryId, String productTypeId, String batchNumber,
                                             String planId, String baseStatus) {
        return qcInspectionPolicy == null ? baseStatus
                : qcInspectionPolicy.finishedGoodsStatusForOutput(
                        factoryId, productTypeId, planId, baseStatus, batchNumber);
    }

    /** SP12 T3: 生产撤回审批流引擎. required=false 兼容无 WorkflowEngine 环境 (e.g. 单测). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.workflow.WorkflowEngineService workflowEngine;

    /** 日常报工修正：待审批期间必须在任何结单库存动作之前 fail closed。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.ProductionReportCorrectionService productionReportCorrectionService;

    /** 客供 Workflow 结单只允许把订单要求中缺失的手工批次合并进服务端报工事实。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.production.CustomerSuppliedSettlementCoverageGuard
            customerSuppliedSettlementCoverageGuard;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /** Round 9 Fix (R8-α Gap #3): Canvas dynamic field persistence for production_plan. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    /**
     * T143: 物料单位换算器 (箱↔kg via MaterialPackagingHierarchy). required=false 兼容单测:
     * 未注入时 B3 库存校验退回旧的同单位比较 (历史 F001 RPF 路径单位一致, 不受影响).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.uom.MaterialUomConverter materialUomConverter;

    /** 逐道报工原料量按行单位记录，结单扣减前转换回批次库存单位。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.UnitConversionService unitConversionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.production.SalesOrderPlanQuantityNormalizer salesOrderPlanQuantityNormalizer;

    /**
     * T144: 物料库存单位改读 MaterialBatch.quantityUnit (称重批次单位, e.g. kg) 而非
     * RawMaterialType.unit (箱). 保留 rawMaterialTypeRepository 仅作 fallback (无可用批次时).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.RawMaterialTypeRepository rawMaterialTypeRepository;

    @Autowired
    private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    /**
     * PRODUCTION_TO_FINISHED 血缘边接线点。required=false 兼容单测（与本类其它可选依赖同款）。
     *
     * <p>⚠️ {@code finished_goods_batches} 没有 production_batch_id 列，只有 production_plan_id，
     * 所以这里走 {@code recordProductionToFinishedByPlan}，由
     * {@code FinishedBatchProductionAligner} 做「一计划多批次」的二次对齐；对不齐就不写边。
     */
    @Autowired(required = false)
    private com.cretas.aims.service.lineage.LineageEdgeRecorder lineageEdgeRecorder;

    /** 结单族 SFI 投料 (deductProcessSheetStockFeeds) 的 WIP_CONSUME 写点; required=false 同上。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.lineage.SemiFeedLineageRecorder semiFeedLineageRecorder;

    /** raw-centric 多成品: 建计划/转批次守卫 —— 断言 owner 启用 workflow 终端覆盖所选成品. required=false 兼容单测. */
    @Autowired(required = false)
    private com.cretas.aims.service.workflow.ProductWorkflowResolutionService workflowResolutionService;

    /** Workflow-governed products must never silently fall back to the legacy plan path. */
    @Autowired(required = false)
    private ProductProcessWorkflowRepository productProcessWorkflowRepository;

    /** 以销定产批量建计划: 取产品工序名拼 processName (镜像前端 loadBomProcesses). required=false 兼容单测. */
    @Autowired(required = false)
    private com.cretas.aims.service.ProductWorkProcessService productWorkProcessService;

    /**
     * 完工链 GAP 3/4 (F006 — 2026-06-02): 转批次时从 product_work_processes 模板 spawn 工序任务.
     * {@code WorkProcessTaskServiceImpl} 不注入 ProductionPlanService → 无循环依赖, 普通注入即可.
     * required=false 兼容单测 (反射注入 mock) 与无工序配置场景.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.workprocess.WorkProcessTaskService workProcessTaskService;

    /**
     * Fable 审计修复 (2026-06-11 — 多租户安全红线): 工厂级"免工序报工默认值".
     *
     * <p>createProductionPlan 对 skipProcessReporting=null 的新建计划解析为"该工厂的默认值"
     * (F006=true 两点, 其他工厂=false 逐道), 取代旧的"全系统 null→true"过度泛化。
     * required=false 兼容单测 (反射注入或不注入); 不注入 / 查不到 → 兜底 false (安全=逐道)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.FactorySettingsRepository factorySettingsRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionSettlementRepository productionSettlementRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionSettlementOutputLineRepository productionSettlementOutputLineRepository;

    /**
     * BY_STOCK 小结与仓库确认桥接。小结已经完成库存过账，本仓库只读取其会话摘要来补建
     * {@link ProductionSettlement} 元数据，绝不重放小结库存动作。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionInterimSettlementRepository productionInterimSettlementRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionSettlementConsumptionRepository productionSettlementConsumptionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionSettlementLaborRepository productionSettlementLaborRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SemiFinishedInventoryRepository semiFinishedInventoryRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionTransitLedgerRepository productionTransitLedgerRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BomRecipeRepository bomRecipeRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.bom.BomSeasoningItemRepository bomSeasoningItemRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BomRecipeItemRepository bomRecipeItemRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BomItemSubstituteRepository bomItemSubstituteRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.bom.BomWorkflowRevisionService bomWorkflowRevisionService;

    // 🔴 2026-09-06 移除 bomYieldSuggestionRepository —— 见 resolveEffectiveProductYieldRate 注释:
    //    #594 之后 bom_yield_suggestions 只产 PENDING(喂「标准出成率」页面给人看), 不再自动 apply。
    //    prod 上残留的 12 条 APPLIED 全是 applied_by=SYSTEM、最后 6 条都是 100.00 —— 那是被删掉的
    //    自动 apply 循环留下的, 让它回流进解析器等于把「自学习把自己学没了」那条路重新接上。
    //    ⚠️ Repository 上那个 findFirstBy...APPLIED 查询方法**保留**(供出成率页面/审计追溯), 只是
    //    生产计划这一侧不再读它 —— 全仓 grep 确认它在 src/main 里已零调用点。

    /**
     * 主原料份额 → 建计划时的投入量。与领料单共用同一份解析 (形态 D: 两份一定会漂)。
     * 见 {@link com.cretas.aims.service.workflow.PlanInputShareResolver}。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.workflow.PlanInputShareResolver planInputShareResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WarehouseResolver warehouseResolver;

    /**
     * 🔴 成本传导 (2026-07-04): 结单族 CUSTOMER_ORDER 成品入库单位成本基准.
     *
     * <p>结单族 {@code createFinishedGoodsFromReceipt} 历史只写 unitPrice(售价) 不写 unitCost(成本) →
     * 成品批次 unitCost=null → 毛利/COGS 对结单族全盲 (期末 COGS 结转 honest-null 排除, 结单族销售无成本)。
     * 复用 {@link OrderCostBreakdownService#computeByPlan} (单一权威成本, 与 SAFETY_STOCK 小结的成本传导
     * 同源 = 逐道 原料+人工+调料+包装, 但结单族生产批不设 ProductionBatch.totalCost, 故不能直接镜像
     * interim-settle 的 computeOutputUnitCost, 改走 MaterialConsumption 派生的权威成本, 与 出厂核算 字节一致)。
     * required=false 兼容单测 (反射注入 mock); 不注入 → unitCost 保持 null (诚实, 不伪造 ¥0)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.yield.OrderCostBreakdownService orderCostBreakdownService;

    /**
     * R1/R2 (2026-06-14): 取消计划按批次定向级联关闭 WorkProcessTask (新表), 取代旧的
     * "按产品类型全关 ProcessTask" (会误关同产品并行另一批次的活跃任务)。
     * required=false 兼容单测 (反射注入) 与无工序任务场景。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.workprocess.WorkProcessTaskRepository workProcessTaskRepository;

    /**
     * R2 (2026-06-14): 检测 IN_PROGRESS 计划是否已有 YIELD 报工 (有数据 → 禁止直接取消, 导向撤回审批)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionReportRepository productionReportRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProcessSheetRowRepository processSheetRowRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.production.ReportedOutputCostAllocationService reportedOutputCostAllocationService;

    /**
     * 生产仓占用的释放 (释放路径 3/3: <b>结单扣减</b>) + 「这批料被谁占着」的归属查询。
     *
     * <p>⚠️ 释放<b>只能</b>发生在结单三道闸之前, 也就是 {@link #settleProduction} 内部:
     * 三道闸 ({@code deriveRawConsumptions} 预填 / {@code resolveAvailableBefore} 校验 /
     * {@code postMaterialBatchConsumption} 过账) 读的都是
     * {@link MaterialBatch#getCurrentQuantity()} = 收货 − 已用 − 预留, 于是为报工 R 挂的预留
     * 会把 R 自己那一笔挡在门外; 而「先扣后放」会让 {@code used + reserved > receipt},
     * 被 {@code assertConsumptionInvariant} 当场炸掉。两头夹死。
     *
     * <p>required=false: 与本类其余可选依赖同一惯例, 精简测试上下文缺失时跳过。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.production.WorkshopReportReservationService workshopReservationService;

    /**
     * R2 (2026-06-14 v2): 报工整单撤回服务 (恢复 WIP/原料/FGB/工序任务 + 反冲成本)。
     *
     * <p><b>为何用它而非 executeCancelApproved</b>: PRODUCTION_REVERSAL 审批流的"审批通过回调"
     * ({@code executeCancelApproved}) 在主代码中<b>无任何调用方</b> —— 工作流引擎
     * ({@code WorkflowEngineServiceImpl.terminateAtEnd}) 是纯状态机, 到达 APPROVED 终态只设置
     * instance.status, <b>从不回调业务层</b> (无 moduleCode→handler 派发器)。即便 executeCancelApproved
     * 被触发, 它本身也只置 CANCELLED + 级联关任务, <b>不恢复任何库存</b>。
     *
     * <p>而 {@link ReportReversalService} 是<b>自包含且已验证可恢复库存</b>的撤回流:
     * {@code submitReversal} (G1/G2/G3 守卫 + 自身 PENDING 审批) → {@code approveReversal}
     * → {@code executeReversal} (软删报工 + 写 REVERSE SIT 行 + 回放移动均价 + 复位工序任务 +
     * 标 FGB REVERSED + 清回填 costUnitPrice)。
     *
     * <p>故 R2 把"有报工/WIP 的计划取消"导向报工撤回流 (文员先撤报工恢复库存, 报工软删后
     * {@link #hasProductionData} 返 false → 再直接取消空计划)。required=false 兼容单测。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.reversal.ReportReversalService reportReversalService;

    /** Optional so existing non-HTTP jobs and unit tests retain their original contract. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BusinessRowScope businessRowScope;

    private List<Long> visiblePlanCreators(String factoryId) {
        return BusinessRowScope.creators(businessRowScope, factoryId);
    }

    private void requireVisiblePlan(String factoryId, ProductionPlan plan) {
        if (!factoryId.equals(plan.getFactoryId())) {
            throw new BusinessException(403, "无权访问该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法访问");
        }
        BusinessRowScope.requireOwner(businessRowScope, factoryId, plan.getCreatedBy());
    }

    private Specification<ProductionPlan> planScope(String factoryId, List<Long> creatorIds) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("factoryId"), factoryId));
            if (creatorIds != null) {
                if (creatorIds.isEmpty()) return cb.disjunction();
                predicates.add(root.get("createdBy").in(creatorIds));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private Specification<ProductionPlan> planKeyword(String keyword) {
        return (root, query, cb) -> {
            if (keyword == null || keyword.isBlank()) return cb.conjunction();
            var productType = root.join("productType", jakarta.persistence.criteria.JoinType.LEFT);
            String pattern = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("planNumber")), pattern),
                    cb.like(cb.lower(productType.get("name")), pattern));
        };
    }

    private Specification<ProductionPlan> planStatus(ProductionPlanStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    private Specification<ProductionPlan> planStatusIn(Collection<ProductionPlanStatus> statuses) {
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    /**
     * 解析某工厂的"免工序报工默认值" (Fable 审计修复 — 多租户安全).
     *
     * <p>仅当 createProductionPlan 的 request.skipProcessReporting 为 null (调用方未显式指定,
     * 如 RN/AI 客户端) 时使用。显式 true/false 一律尊重调用方。
     *
     * <p>多租户安全: 查询带 factoryId, 一个工厂的配置不影响另一个;
     * 无 settings 行 / 列为 null → false (逐道, 安全默认, 保留溯源/成本/出成率/自学习/人效)。
     */
    private boolean resolveSkipProcessReportingDefault(String factoryId) {
        if (factorySettingsRepository == null) {
            return false; // 单测无注入 → 安全默认逐道
        }
        try {
            Boolean def = factorySettingsRepository.findSkipProcessReportingDefaultByFactoryId(factoryId);
            return Boolean.TRUE.equals(def);
        } catch (Exception e) {
            log.warn("解析工厂免工序报工默认值失败, 兜底逐道 (false): factoryId={}, err={}", factoryId, e.getMessage());
            return false;
        }
    }

    /**
     * {@code plannedQuantity} is the planned finished-product output, never the
     * first-process feed quantity.  Raw-material feed is only known after a
     * recipe/yield calculation or actual reporting, so it must not be guessed
     * from a sales-order line.
     */
    static String resolvePlannedOutputUnit(String productUnit) {
        if (productUnit == null || productUnit.isBlank()) {
            throw new BusinessException(422, "产品主单位未配置，无法创建生产计划")
                    .withCode("PRODUCTION_UNIT_NOT_CONFIGURED")
                    .withHint("请先在成品 SKU 中配置主单位，再创建或复制生产计划");
        }
        return productUnit.trim();
    }

    private String resolvePlannedOutputUnitForProduct(String factoryId, String productTypeId) {
        String productUnit = productTypeId == null ? null
                : productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                        .map(ProductType::getUnit)
                        .orElse(null);
        return resolvePlannedOutputUnit(productUnit);
    }

    private PlanUnitAuthority resolvePlanUnitAuthority(
            String factoryId, String productTypeId, List<String> targetFinishedGoodIds) {
        return resolvePlanUnitAuthority(
                factoryId, productTypeId, targetFinishedGoodIds, null, null);
    }

    private PlanUnitAuthority resolvePlanUnitAuthority(
            String factoryId, String productTypeId, List<String> targetFinishedGoodIds,
            Long selectedWorkflowId, Integer selectedWorkflowVersion) {
        return resolvePlanUnitAuthority(factoryId, productTypeId, targetFinishedGoodIds,
                selectedWorkflowId, selectedWorkflowVersion, true);
    }

    /**
     * @param failClosed true=没有可用画布工艺就当场抛; false=返回 null 交给调用方决定何时抛。
     *
     *     <p>🔴 建计划必须传 false: 工艺要求要排在**鉴权与归属校验之后**。否则跨工厂追加
     *     销售单本该 403, 却会先撞上「该产品尚未配置生产工艺」的 409 —— 越权请求收到一条
     *     误导性业务提示, 真正的拒绝理由被吞掉。
     */
    private PlanUnitAuthority resolvePlanUnitAuthority(
            String factoryId, String productTypeId, List<String> targetFinishedGoodIds,
            Long selectedWorkflowId, Integer selectedWorkflowVersion, boolean failClosed) {
        String productionBaseUnit = resolvePlannedOutputUnitForProduct(factoryId, productTypeId);
        boolean hasSelectedId = selectedWorkflowId != null;
        boolean hasSelectedVersion = selectedWorkflowVersion != null;
        if (hasSelectedId != hasSelectedVersion) {
            throw new BusinessException(400, "Workflow ID 与版本必须成对提交")
                    .withCode("WORKFLOW_SELECTION_INCOMPLETE")
                    .withHintTarget(hasSelectedId ? "selectedWorkflowVersion" : "selectedWorkflowId");
        }
        if (hasSelectedId && workflowResolutionService == null) {
            throw new BusinessException(500, "Workflow 解析服务未初始化，无法固定所选版本")
                    .withCode("WORKFLOW_RESOLUTION_UNAVAILABLE");
        }
        if (workflowResolutionService != null) {
            Optional<com.cretas.aims.service.workflow.WorkflowPlanOutputContract> contract;
            if (hasSelectedId) {
                contract = Optional.of(workflowResolutionService.resolvePinnedPlanOutputContract(
                        factoryId, productTypeId, selectedWorkflowId, selectedWorkflowVersion,
                        targetFinishedGoodIds));
            } else {
                contract = workflowResolutionService.resolveActivePlanOutputContract(
                        factoryId, productTypeId, targetFinishedGoodIds);
            }
            if (contract.isPresent()) {
                var value = contract.get();
                return new PlanUnitAuthority(productionBaseUnit, value.plannedUnit(),
                        resolveNetWeightGramsForProduct(factoryId, productTypeId),
                        ProductionBatch.WorkflowSelectionMode.WORKFLOW,
                        value.workflowId(), value.definitionVersion(),
                        value.revisionId(), value.revisionHash(), value.outputUnitBySku());
            }
        }
        if (productProcessWorkflowRepository != null) {
            boolean governed = productProcessWorkflowRepository
                    .findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc(factoryId).stream()
                    .anyMatch(row -> Objects.equals(productTypeId, row.getProductTypeId()));
            if (governed) {
                throw new BusinessException(409, "该产品已有 Workflow 配置，但尚无可用于生产的启用版本")
                        .withCode("WORKFLOW_ENABLED_REQUIRED")
                        .withHint("请先完成 BOM、发布并启用其固定的 Workflow 版本")
                        .withHintTarget("selectedWorkflowId");
            }
        }
        // 🔴 2026-08-09 (Steve 拍板): 老路(LEGACY)整条下架 —— 系统只认画布工艺。
        //
        // 这里原本回落成 LEGACY 权威(工艺/BOM 字段全 null, 报工走 product_work_processes 模板)。
        // 现在没有已发布并启用的画布工艺 = 不能生产, 当场 fail closed。
        // ⚠️ 这是**有意的**收紧: 拍板时已知 509 个产品里只有 8 个配了画布工艺,
        //    其余产品要先补工艺才能建计划。不要因为「挡住了很多产品」就把这里改回回落。
        if (!failClosed) {
            return null;
        }
        throw workflowRequired();
    }

    private BusinessException workflowRequired() {
        return new BusinessException(409, "该产品尚未配置生产工艺，不能生产")
                .withCode("WORKFLOW_REQUIRED")
                .withHint("请先在「产品工艺画布」为该产品建立工艺，发布并启用后再建计划")
                .withHintTarget("productTypeId")
                .withSeverity("BLOCKING");
    }

    private BigDecimal resolveNetWeightGramsForProduct(String factoryId, String productTypeId) {
        if (productTypeId == null) return null;
        return productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .map(ProductType::getGramsPerUnit)
                .orElse(null);
    }

    /**
     * 把**尚未开工**的计划重钉到当前生效的工艺修订 + BOM(Steve 2026-08-09 拍板)。
     *
     * <p>计划在创建那一刻就把工艺修订与 BOM 钉成快照, 之后改配方/发新版本都不影响它 ——
     * 这条对**已生产**的批次是刚性要求(食品溯源: 这批货当时按什么配方做的必须说得清)。
     * 但对「今天建了计划、明天改了配方、还一克料没投」的计划, 快照就成了枷锁: 用不上新配方,
     * 只能删了重建。本方法只解这一半。
     *
     * <p>🔴 「有没有开工」的判据不能看计划状态, 也不能看有没有批次 —— 真机样本
     * PLAN-1786184738975(状态 IN_PROGRESS + 已有 1 个生产批次, 却一克料都没扣)会被这两者
     * 双双误判成"已生产"而拒绝更新。正确判据是报工行的**三个信号全干净**:
     *   · rowStatus        != SUBMITTED   (未物化成批次)
     *   · submissionStatus != SUBMITTED   (未正式提交)
     *   · interimSettledAt == null        (未被生产小结扣过料)
     * 任一命中 ⇒ 这个计划已经动过真库存 ⇒ 一律不动。草稿行(SAVED/DRAFT)不算开工。
     *
     * <p>⛔ 判定在写入的**同一个事务**里做 —— 列清单到点确认之间计划可能刚好开工,
     * 只信列表那一刻的快照就会把已开工的计划改掉。
     */
    @Override
    @Transactional
    public ProductionPlan repinPlanToCurrentAuthority(String factoryId, String planId, Long operatorId) {
        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(planId)
                .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                .orElseThrow(() -> new BusinessException(404, "生产计划不存在")
                        .withCode("PRODUCTION_PLAN_NOT_FOUND"));
        requireVisiblePlan(factoryId, plan);
        requirePlanNotStarted(factoryId, planId, plan);

        return applyCurrentAuthorityToPlan(factoryId, plan);
    }

    @Override
    @Transactional
    public WorkflowPlanRebindSummaryDTO repinUnstartedPlansToCurrentAuthority(
            String factoryId,
            Collection<Long> previousWorkflowIds,
            Long operatorId) {
        Set<Long> workflowIds = previousWorkflowIds == null
                ? Set.of()
                : previousWorkflowIds.stream().filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (workflowIds.isEmpty()) {
            return WorkflowPlanRebindSummaryDTO.empty();
        }

        List<ProductionPlan> candidates = productionPlanRepository
                .findByFactoryIdAndSelectedWorkflowIdIn(factoryId, workflowIds).stream()
                .sorted(Comparator.comparing(ProductionPlan::getId))
                .toList();
        List<String> rebound = new ArrayList<>();
        List<String> preserved = new ArrayList<>();

        for (ProductionPlan candidate : candidates) {
            ProductionPlan plan = productionPlanRepository.findByIdForUpdate(candidate.getId())
                    .filter(row -> factoryId.equals(row.getFactoryId()))
                    .orElse(null);
            if (plan == null || !workflowIds.contains(plan.getSelectedWorkflowId())) {
                continue;
            }
            if (hasStartedProduction(factoryId, plan.getId())) {
                preserved.add(planReference(plan));
                continue;
            }
            // Workflow 更新是系统级权威迁移，不是人工修改计划。只要尚未开始报工，
            // 即使计划因排产确认而锁定，也必须跟随当前配置；锁只继续约束人工重钉入口。
            applyCurrentAuthorityToPlan(factoryId, plan);
            rebound.add(planReference(plan));
        }

        return WorkflowPlanRebindSummaryDTO.builder()
                .reboundPlanCount(rebound.size())
                .preservedStartedPlanCount(preserved.size())
                .reboundPlanNumbers(List.copyOf(rebound))
                .preservedPlanNumbers(List.copyOf(preserved))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, List<WorkflowPlanBindingDTO>> listWorkflowPlanBindings(
            String factoryId,
            Collection<Long> workflowIds) {
        Set<Long> ids = workflowIds == null
                ? Set.of()
                : workflowIds.stream().filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<WorkflowPlanBindingDTO>> bindings = new LinkedHashMap<>();
        productionPlanRepository.findByFactoryIdAndSelectedWorkflowIdIn(factoryId, ids).stream()
                .sorted(Comparator.comparing(this::planReference))
                .forEach(plan -> bindings.computeIfAbsent(plan.getSelectedWorkflowId(), ignored -> new ArrayList<>())
                        .add(WorkflowPlanBindingDTO.builder()
                                .planId(plan.getId())
                                .planNumber(plan.getPlanNumber())
                                .status(plan.getStatus() == null ? null : plan.getStatus().name())
                                .productionStarted(hasStartedProduction(factoryId, plan.getId()))
                                .build()));
        return bindings.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> List.copyOf(entry.getValue()),
                (left, right) -> left,
                LinkedHashMap::new));
    }

    private String planReference(ProductionPlan plan) {
        return plan.getPlanNumber() == null || plan.getPlanNumber().isBlank()
                ? plan.getId()
                : plan.getPlanNumber();
    }

    private ProductionPlan applyCurrentAuthorityToPlan(String factoryId, ProductionPlan plan) {

        PlanUnitAuthority authority = resolvePlanUnitAuthority(
                factoryId, plan.getProductTypeId(), plan.getTargetFinishedGoodIds());
        // 与新建计划走**同一个** applyPlanUnitAuthority —— 不另写一套赋值,
        // 否则两处口径迟早分叉(本仓最常见的缺陷形态)。
        applyPlanUnitAuthority(plan, authority);
        ProductionPlan saved = productionPlanRepository.save(plan);
        // 只改计划指针是骗人的: 批次自己存了一份权威(DB 触发器**只在 INSERT 时**从计划复制),
        //    报工读的运行时实例又是按批次那份编译并冻结的。三样一起搬, 否则界面说换了、
        //    操作工报的还是旧工艺(2026-08-09 真机实测 PLAN-1786184738975)。
        repinPlanBatches(factoryId, saved);
        return saved;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            readOnly = true)
    public boolean pinNewPlanToCurrentAuthorityIfAvailable(String factoryId, ProductionPlan plan) {
        if (plan == null || !Objects.equals(factoryId, plan.getFactoryId())) {
            throw new BusinessException(400, "新生产计划与当前工厂不一致")
                    .withCode("PRODUCTION_PLAN_FACTORY_MISMATCH");
        }
        PlanUnitAuthority authority;
        try {
            // The resolution service is transactional and deliberately translates a
            // missing active Workflow into a BusinessException.  Probe its DTO path first
            // so the expected "no Workflow" branch never throws through a transaction
            // interceptor (which would mark the caller's transaction rollback-only even
            // when this method catches the exception).
            if (workflowResolutionService != null) {
                List<String> targets = plan.getTargetFinishedGoodIds() == null
                        || plan.getTargetFinishedGoodIds().isEmpty()
                        ? List.of(plan.getProductTypeId()) : plan.getTargetFinishedGoodIds();
                var resolution = workflowResolutionService.resolveForOutputs(
                        factoryId, targets);
                if (resolution != null && resolution.getCandidates() != null
                        && resolution.getCandidates().isEmpty()) {
                    if (productProcessWorkflowRepository != null
                            && productProcessWorkflowRepository.existsByFactoryIdAndProductTypeId(
                                    factoryId, plan.getProductTypeId())) {
                        throw new BusinessException(409, "该产品已有 Workflow 配置，但尚无可用于生产的启用版本")
                                .withCode("WORKFLOW_ENABLED_REQUIRED")
                                .withHint("请先完成 BOM、发布并启用其固定的 Workflow 版本")
                                .withHintTarget("selectedWorkflowId");
                    }
                    return false;
                }
            }
            authority = resolvePlanUnitAuthority(
                    factoryId, plan.getProductTypeId(), plan.getTargetFinishedGoodIds(),
                    null, null, false);
        } catch (BusinessException ex) {
            // 自动计划还承担缺料/采购建议载体职责。完全没有覆盖该产品的 Workflow 时
            // 允许先保留 PENDING 计划；其余解析错误（版本不完整、跨租户、配置冲突）
            // 继续 fail closed，不能被这个兼容分支吞掉。
            if ("WORKFLOW_SINGLE_OUTPUT_NOT_FOUND".equals(ex.getErrorCode())
                    || "WORKFLOW_SHARED_OUTPUT_NOT_FOUND".equals(ex.getErrorCode())) {
                return false;
            }
            throw ex;
        }
        if (authority == null) {
            return false;
        }
        // 与手工建计划、更新到当前配方共用同一个赋值入口，避免只补 Workflow
        // 却漏掉 BOM family/recipe/version/outputUnits 形成新的半张快照。
        applyPlanUnitAuthority(plan, authority);
        return true;
    }

    /**
     * 把计划的新权威搬到它名下的批次, 并丢掉据旧权威编译的运行时产物让它重编译。
     *
     * <p>调用前必须已经过 {@link #requirePlanNotStarted} —— 这里删的是编译产物
     * (实例/任务/端口), 只有一克料没动过的计划才允许。
     */
    private void repinPlanBatches(String factoryId, ProductionPlan plan) {
        if (productionBatchRepository == null) {
            return;
        }
        List<ProductionBatch> batches =
                productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, plan.getId());
        if (batches == null || batches.isEmpty()) {
            return;
        }
        for (ProductionBatch batch : batches) {
            if (batch.getId() == null) {
                continue;
            }
            // ⛔ 原来就没有运行时的批次(e.g. 正式报工时生成的 CLK-B-* 记账批次)**不要**替它造一个 ——
            //    重钉的职责是「把旧的换成新的」, 不是「给没有的凭空补一个」。
            boolean hadRuntime = dropCompiledRuntime(factoryId, batch.getId());
            copyPlanAuthorityToBatch(factoryId, plan.getId(), batch.getId());
            if (hadRuntime) {
                rematerializeRuntime(factoryId, plan, batch);
            }
        }
    }

    /**
     * 按新权威当场重编译运行时 —— 复用<b>转批次时同一个</b> {@code spawnTasks}。
     *
     * <p>只删不建是不行的: 读路径不会自动物化, 真机实测报
     * {@code WORKFLOW_RUNTIME_NOT_MATERIALIZED}「运行时快照尚未生成」, 等于把批次弄坏,
     * 而界面已经说「已更新到当前生效配方」。
     *
     * <p>刷掉一级缓存再建: 上面刚原生 UPDATE 了 production_batches, 持久化上下文里那份
     * ProductionBatch 还是旧权威, 编译器读到旧值就白搬了。
     */
    private void rematerializeRuntime(String factoryId, ProductionPlan plan, ProductionBatch batch) {
        if (workProcessTaskService == null) {
            throw new BusinessException(500, "工序任务服务未初始化，配方更新已回滚")
                    .withCode("WORK_PROCESS_TASK_SERVICE_UNAVAILABLE");
        }
        entityManager.flush();
        entityManager.clear();
        Long responsibleId = plan.getAssignedSupervisorId();
        workProcessTaskService.spawnTasks(factoryId, batch.getId(), batch.getProductTypeId(),
                plan.getSkipProcessReporting(), responsibleId, responsibleId);
    }

    /**
     * 批次那几列在实体上是 {@code insertable=false, updatable=false}(归 DB 触发器所有),
     * 而触发器只挂 BEFORE INSERT —— 所以重钉只能走原生 UPDATE, 逐列照抄触发器 WORKFLOW 分支。
     */
    private void copyPlanAuthorityToBatch(String factoryId, String planId, Long batchId) {
        entityManager.createNativeQuery("""
                UPDATE production_batches b
                   SET workflow_selection_mode = p.workflow_selection_mode,
                       selected_workflow_id = p.selected_workflow_id,
                       selected_workflow_version = p.selected_workflow_version,
                       selected_workflow_revision_id = p.selected_workflow_revision_id,
                       selected_workflow_revision_hash = p.selected_workflow_revision_hash,
                       selected_bom_family_id = p.selected_bom_family_id,
                       selected_bom_recipe_ids_by_product =
                           COALESCE(p.selected_bom_recipe_ids_by_product, '{}'::jsonb),
                       selected_bom_versions_by_product =
                           COALESCE(p.selected_bom_versions_by_product, '{}'::jsonb),
                       workflow_output_units_by_product =
                           COALESCE(p.workflow_output_units_by_product, '{}'::jsonb),
                       target_finished_good_ids =
                           COALESCE(p.target_finished_good_ids, '[]'::jsonb)
                  FROM production_plans p
                 WHERE b.id = :batchId
                   AND b.factory_id = :factoryId
                   AND p.id = :planId
                   AND p.factory_id = :factoryId
                """)
                .setParameter("batchId", batchId)
                .setParameter("planId", planId)
                .setParameter("factoryId", factoryId)
                .executeUpdate();
    }

    /**
     * 丢掉按旧权威编译的运行时(端口 → 任务 → 实例), 让下次 materializeIfActive 重新编译。
     *
     * <p>外键只有三条且全在这个三元组内部(ports→tasks / ports→instance / tasks→instance),
     * 但另有几张表用<b>无外键</b>的列指向任务 —— 那种引用删的时候一声不吭, 留下孤儿。
     * 有任何一条就 fail closed 不删。
     *
     * <p>🔴 三个引用列<b>类型不一致</b>: production_reports / semi_finished_inventory 那两个是
     * bigint, 而 process_checkin_records.process_task_id 是 <b>varchar</b>(实测 prod 里它和
     * work_process_tasks.id 一条都对不上, 是另一个域的任务)。第一版没钉类型, PG 直接报
     * {@code operator does not exist: character varying = bigint} —— 整条语句 prepare 不了。
     * 所以每一处比较都显式钉类型, 不靠推断。
     */
    boolean dropCompiledRuntime(String factoryId, Long batchId) {   // 包内可见: 供闸测直接调
        if (productionWorkflowInstanceRepository == null) {
            return false;
        }
        var instance = productionWorkflowInstanceRepository
                .findByFactoryIdAndProductionBatchId(factoryId, batchId).orElse(null);
        if (instance == null) {
            return false;
        }
        requireNoSoftReferencesToTasks(instance.getId());
        if (repinWorkflowTaskPortRepository != null) {
            repinWorkflowTaskPortRepository.deleteAll(repinWorkflowTaskPortRepository
                    .findByFactoryIdAndWorkflowInstanceId(factoryId, instance.getId()));
        }
        if (repinWorkProcessTaskRepository != null) {
            repinWorkProcessTaskRepository.deleteAll(repinWorkProcessTaskRepository
                    .findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(
                            factoryId, instance.getId()));
        }
        productionWorkflowInstanceRepository.delete(instance);
        entityManager.flush();
        log.info("重钉配方: 已丢弃陈旧运行时实例 factoryId={}, batchId={}, instanceId={}",
                factoryId, batchId, instance.getId());
        return true;
    }

    private void requireNoSoftReferencesToTasks(Long instanceId) {
        Number referencing = (Number) entityManager.createNativeQuery("""
                SELECT (SELECT COUNT(*) FROM production_reports r
                         WHERE r.work_process_task_id IN (
                               SELECT t.id FROM work_process_tasks t
                                WHERE t.workflow_instance_id = :iid))
                     + (SELECT COUNT(*) FROM semi_finished_inventory s
                         WHERE s.source_work_process_task_id IN (
                               SELECT t.id FROM work_process_tasks t
                                WHERE t.workflow_instance_id = :iid))
                     + (SELECT COUNT(*) FROM process_checkin_records c
                         WHERE c.process_task_id IN (
                               SELECT t.id::text FROM work_process_tasks t
                                WHERE t.workflow_instance_id = :iid))
                """)
                .setParameter("iid", instanceId)
                .getSingleResult();
        if (referencing != null && referencing.longValue() > 0) {
            throw new BusinessException(409, "该计划的报工任务已被引用，不能更换配方版本")
                    .withCode("PRODUCTION_PLAN_TASKS_REFERENCED")
                    .withHint("已有报工/入库/打卡记录挂在这些任务上；如需按新配方生产请新建计划")
                    .withSeverity("warning");
        }
    }

    static final String REPIN_BLOCKED_STARTED =
            "已投料/已报工，保持原配方快照；如需按新配方生产请新建计划";
    static final String REPIN_BLOCKED_LOCKED = "计划已锁定";

    /**
     * 「能不能更新到当前配方」的<b>唯一</b>判据 —— 菜单灰显与端点拒绝都走这里。
     *
     * <p>⛔ 不要在别处再写一份。第一版就栽在这上面: 菜单用了
     * {@code hasRealProductionActivity}(它<b>不查</b> rowStatus), 端点用三信号,
     * 于是「只有 rowStatus=SUBMITTED」的计划菜单显示可点、点下去 409 —— 两处口径打架。
     *
     * @param hasActivity 惰性求值: 报工行三信号已命中时不必再查一次库
     * @return 不能重钉的人话原因; 可以重钉时返回 null
     */
    String repinBlockedReason(String factoryId, String planId, boolean locked,   // 包内可见: 供闸测直接调
                                      java.util.function.BooleanSupplier hasActivity) {
        if (startedByProcessSheetRows(factoryId, planId)) {
            return REPIN_BLOCKED_STARTED;
        }
        if (hasActivity != null && hasActivity.getAsBoolean()) {
            return REPIN_BLOCKED_STARTED;
        }
        return locked ? REPIN_BLOCKED_LOCKED : null;
    }

    /** 三信号判据: rowStatus / submissionStatus / interimSettledAt 任一命中即视为已开工。 */
    private boolean startedByProcessSheetRows(String factoryId, String planId) {
        if (processSheetRowRepository == null) {
            return false;
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId);
        return rows != null && rows.stream()
                .anyMatch(row -> ProcessSheetRow.SUBMISSION_SUBMITTED.equals(row.getSubmissionStatus())
                        || "SUBMITTED".equals(row.getRowStatus())
                        || row.getInterimSettledAt() != null);
    }

    private boolean hasStartedProduction(String factoryId, String planId) {
        return startedByProcessSheetRows(factoryId, planId)
                || hasRealProductionActivity(factoryId, planId);
    }

    private void requirePlanNotStarted(String factoryId, String planId, ProductionPlan plan) {
        String reason = repinBlockedReason(factoryId, planId,
                Boolean.TRUE.equals(plan.getIsLocked()),
                () -> hasRealProductionActivity(factoryId, planId));
        if (reason == null) {
            return;
        }
        String message;
        String code;
        if (REPIN_BLOCKED_LOCKED.equals(reason)) {
            message = "该计划已锁定，不能更换配方版本";
            code = "PRODUCTION_PLAN_LOCKED";
        } else {
            message = "该计划已经开工，不能更换配方版本";
            code = "PRODUCTION_PLAN_ALREADY_STARTED";
        }
        throw new BusinessException(409, message)
                .withCode(code)
                .withHint(reason)
                .withSeverity("warning");
    }

    private void applyPlanUnitAuthority(ProductionPlan plan, PlanUnitAuthority authority) {
        plan.setPlannedUnit(authority.unit());
        plan.setWorkflowOutputUnit(authority.workflowOutputUnit());
        plan.setPlannedNetWeightGrams(authority.netWeightGrams());
        plan.setWorkflowSelectionMode(authority.mode());
        plan.setSelectedWorkflowId(authority.workflowId());
        plan.setSelectedWorkflowVersion(authority.workflowVersion());
        plan.setSelectedWorkflowRevisionId(authority.workflowRevisionId());
        plan.setSelectedWorkflowRevisionHash(authority.workflowRevisionHash());
        plan.setWorkflowOutputUnitsByProduct(new LinkedHashMap<>(authority.outputUnitsByProduct()));
        if (authority.mode() == ProductionBatch.WorkflowSelectionMode.WORKFLOW) {
            plan.setTargetFinishedGoodIds(new ArrayList<>(authority.outputUnitsByProduct().keySet()));
            applyWorkflowBomAuthority(plan, authority);
            return;
        }
        plan.setSelectedBomFamilyId(null);
        plan.setSelectedBomRecipeIdsByProduct(new LinkedHashMap<>());
        plan.setSelectedBomVersionsByProduct(new LinkedHashMap<>());
        if (bomRecipeRepository == null) {
            plan.setSelectedBomRecipeId(null);
            plan.setSelectedBomVersion(null);
            return;
        }
        // ⛔ 这里【曾经】有一道 `mode == WORKFLOW && activeRecipe.isEmpty()` 的 409
        //    ("当前产品没有已激活的 BOM" / ACTIVE_BOM_REQUIRED)。它是【到不了的代码】——
        //    本方法在上面 `if (authority.mode() == WORKFLOW) { ...; return; }` 已经早退,
        //    走到这一行时 mode 必然【不是】 WORKFLOW, 那个条件恒为 false。
        //    也就是说: 那道闸从写下来的那天起就【一次都没有执行过】。本仓形态 B: 机制在、没接上。
        //
        //    ⚠️ 删掉不是放松准入 —— WORKFLOW 模式的 BOM 准入由 applyWorkflowBomAuthority
        //    → resolvePinnedBomAuthority 的 ACTIVE_BOM_FAMILY_REQUIRED 唯一负责, 那道是活的。
        //    留着一道永不执行的闸更糟: 它让人以为这里有保护, 从而跳过核实。
        Optional<BomRecipe> activeRecipe = bomRecipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                        plan.getFactoryId(), plan.getProductTypeId(), BomRecipe.Status.ACTIVE);
        activeRecipe.ifPresentOrElse(recipe -> {
            plan.setSelectedBomRecipeId(recipe.getId());
            plan.setSelectedBomVersion(recipe.getVersion());
        }, () -> {
            plan.setSelectedBomRecipeId(null);
            plan.setSelectedBomVersion(null);
        });
    }

    private void applyWorkflowBomAuthority(ProductionPlan plan, PlanUnitAuthority authority) {
        if (bomRecipeRepository == null) {
            throw new BusinessException(500, "BOM authority service is unavailable")
                    .withCode("BOM_AUTHORITY_UNAVAILABLE");
        }
        if (authority.workflowRevisionId() == null
                || isBlank(authority.workflowRevisionHash())
                || authority.outputUnitsByProduct().isEmpty()) {
            throw new BusinessException(409,
                    "The selected Workflow does not expose an immutable revision authority")
                    .withCode("WORKFLOW_REVISION_AUTHORITY_REQUIRED")
                    .withHint("Publish the exact Workflow revision and activate its BOM family before creating a plan");
        }
        PinnedBomAuthority pinnedBom = resolvePinnedBomAuthority(
                plan.getFactoryId(), authority, authority.outputUnitsByProduct().keySet());
        plan.setSelectedBomFamilyId(pinnedBom.familyId());
        plan.setSelectedBomRecipeIdsByProduct(new LinkedHashMap<>(pinnedBom.recipeIdsByProduct()));
        plan.setSelectedBomVersionsByProduct(new LinkedHashMap<>(pinnedBom.versionsByProduct()));
        plan.setSelectedBomRecipeId(pinnedBom.compatibilityAnchorRecipeId());
        plan.setSelectedBomVersion(pinnedBom.compatibilityAnchorVersion());
    }

    private PinnedBomAuthority resolvePinnedBomAuthority(
            String factoryId,
            PlanUnitAuthority authority,
            Collection<String> terminalProductTypeIds) {
        List<BomRecipe> candidates = bomRecipeRepository
                .findByFactoryIdAndWorkflowRevisionIdAndStatusOrderByProductTypeIdAsc(
                        factoryId, authority.workflowRevisionId(), BomRecipe.Status.ACTIVE)
                .stream()
                .filter(recipe -> Objects.equals(authority.workflowId(), recipe.getWorkflowId()))
                .filter(recipe -> Objects.equals(authority.workflowVersion(), recipe.getWorkflowDefinitionVersion()))
                .filter(recipe -> Objects.equals(authority.workflowRevisionHash(), recipe.getWorkflowRevisionHash()))
                .filter(recipe -> !isBlank(recipe.getBomFamilyId()))
                .toList();
        Map<String, List<BomRecipe>> byFamily = candidates.stream()
                .collect(Collectors.groupingBy(BomRecipe::getBomFamilyId,
                        LinkedHashMap::new, Collectors.toList()));
        Set<String> terminals = terminalProductTypeIds.stream()
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PinnedBomAuthority> matches = new ArrayList<>();
        for (Map.Entry<String, List<BomRecipe>> entry : byFamily.entrySet()) {
            Map<String, List<BomRecipe>> recipesByProduct = entry.getValue().stream()
                    .collect(Collectors.groupingBy(BomRecipe::getProductTypeId,
                            LinkedHashMap::new, Collectors.toList()));
            if (!recipesByProduct.keySet().equals(terminals)
                    || !terminals.stream()
                    .allMatch(sku -> recipesByProduct.getOrDefault(sku, List.of()).size() == 1)) {
                continue;
            }
            Map<String, String> recipeIds = new LinkedHashMap<>();
            Map<String, Integer> versions = new LinkedHashMap<>();
            boolean invalid = false;
            for (String sku : terminals) {
                BomRecipe recipe = recipesByProduct.get(sku).getFirst();
                if (isBlank(recipe.getTargetTerminalNodeId())
                        || recipe.getVersion() == null
                        || !Boolean.TRUE.equals(recipe.getIsCurrent())) {
                    invalid = true;
                    break;
                }
                recipeIds.put(sku, recipe.getId());
                versions.put(sku, recipe.getVersion());
            }
            if (invalid) {
                continue;
            }
            BomRecipe anchor = entry.getValue().stream()
                    .filter(recipe -> Objects.equals(recipe.getId(), recipe.getSharedRecipeId()))
                    .findFirst()
                    .orElseGet(() -> recipesByProduct.get(terminals.iterator().next()).getFirst());
            matches.add(new PinnedBomAuthority(
                    entry.getKey(), Map.copyOf(recipeIds), Map.copyOf(versions),
                    anchor.getId(), anchor.getVersion()));
        }
        if (matches.size() != 1) {
            // 用户看得见的文案一律中文 —— 这里原本整段英文, 中文界面上直接甩给工厂总监。
            // hint 也从「Activate exactly one…」改成能照着做的一句: 说清去哪、做什么。
            throw new BusinessException(409,
                    matches.isEmpty()
                            ? "该工艺版本还没有生效的 BOM 配方"
                            : "该工艺版本对应了多个生效的 BOM 配方, 无法确定用哪一个")
                    .withCode(matches.isEmpty()
                            ? "ACTIVE_BOM_FAMILY_REQUIRED"
                            : "ACTIVE_BOM_FAMILY_AMBIGUOUS")
                    .withHint(matches.isEmpty()
                            ? "请到「产品-工序配置」画布上为当前已发布的工艺版本建好 BOM 并启用, 再回来建计划"
                            : "请到「产品-工序配置」画布上停用多余的 BOM, 同一个工艺版本只保留一个生效配方");
        }
        return matches.getFirst();
    }

    private record PlanUnitAuthority(
            String unit,
            String workflowOutputUnit,
            BigDecimal netWeightGrams,
            ProductionBatch.WorkflowSelectionMode mode,
            Long workflowId,
            Integer workflowVersion,
            Long workflowRevisionId,
            String workflowRevisionHash,
            Map<String, String> outputUnitsByProduct) {
    }

    private record PinnedBomAuthority(
            String familyId,
            Map<String, String> recipeIdsByProduct,
            Map<String, Integer> versionsByProduct,
            String compatibilityAnchorRecipeId,
            Integer compatibilityAnchorVersion) {
    }

    private boolean sameTargetSelection(List<String> left, List<String> right) {
        return normalizedTargetSelection(left).equals(normalizedTargetSelection(right));
    }

    private Set<String> normalizedTargetSelection(List<String> values) {
        if (values == null) return Set.of();
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public boolean getSkipProcessReportingDefault(String factoryId) {
        return resolveSkipProcessReportingDefault(factoryId);
    }

    private void runConfiguredValidation(String factoryId, String operation, java.util.Map<String, Object> context) {
        if (validationRuleEvaluator == null) return;
        try {
            validationRuleEvaluator.validate(factoryId, "production_plan", operation, context);
        } catch (com.cretas.aims.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Canvas validation non-blocking error: {}", e.getMessage());
        }
    }

    /**
     * PR #289 §B3 (2026-05-10 客户对接): 开始生产前的原料库存校验.
     *
     * <p>客户原话 (line 35): "那这个开始的话点的时候会有一个判断吗就是我的库存够不够…加一个我觉得还是
     * 加一个不然万一是不就得合对一下是吧"
     *
     * <p>校验逻辑:
     * <ol>
     *   <li>拉取 BOM items (按 productTypeId)</li>
     *   <li>对每个 BOM item 计算需求量: bomItem.calculateActualQuantity() (已含出成率) × plan.plannedQuantity</li>
     *   <li>查询该 materialType 在该 factory 的可用库存合计 (AVAILABLE 状态, receipt - used - reserved)</li>
     *   <li>若任一原料 available &lt; required → 抛 BusinessException 列出所有缺口</li>
     * </ol>
     *
     * <p>边界:
     * <ul>
     *   <li>无 BOM 配置 (BOM 空 list) → skip 校验, 仍允许开始 (生产可能不需要原料, 或客户尚未配置)</li>
     *   <li>plan.plannedQuantity == null → skip (历史数据兼容)</li>
     *   <li>新版 BOM Repository 未注入 (隔离单测场景) → skip</li>
     *   <li>stock 恰好等于 required → 通过 (边界严格不阻断)</li>
     * </ul>
     */
    private void validateMaterialStockSufficient(String factoryId, ProductionPlan plan) {
        if (bomRecipeItemRepository == null) {
            log.debug("BomRecipeItemRepository 未注入, 跳过 B3 库存校验");
            return;
        }
        if (plan.getProductTypeId() == null || plan.getProductTypeId().isBlank()) {
            log.debug("生产计划无 productTypeId, 跳过 B3 库存校验: planId={}", plan.getId());
            return;
        }
        if (plan.getPlannedQuantity() == null
                || plan.getPlannedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("生产计划 plannedQuantity 为空或非正, 跳过 B3 库存校验: planId={}", plan.getId());
            return;
        }

        List<BomRecipeItem> bomItems;
        try {
            // 🔴 2026-08-18 判据五坎2: 多产出计划的 productTypeId 是入口原料的锚点, 产品 BOM 按构造恒空
            //    ⇒ 原来这里恒 return, 库存校验对这类计划**从来没生效过**(静默跳过, 不报错).
            //    PlanBomItems 先试产品 current BOM(行为不变), 空了再用计划钉住的配方.
            bomItems = PlanBomItems.resolve(factoryId, plan, bomRecipeItemRepository);
        } catch (Exception e) {
            log.warn("加载 BOM 失败, 跳过 B3 库存校验: planId={}, err={}", plan.getId(), e.getMessage());
            return;
        }
        if (bomItems == null || bomItems.isEmpty()) {
            log.debug("产品无 BOM 配置, 跳过 B3 库存校验: productTypeId={}", plan.getProductTypeId());
            return;
        }

        BigDecimal plannedQty = plan.getPlannedQuantity();
        List<String> shortages = new ArrayList<>();

        for (BomRecipeItem item : bomItems) {
            if (item.getMaterialTypeId() == null || item.getStandardQuantity() == null) {
                continue;
            }
            // T159-B Change4: prefer live RawMaterialType.name over BOM snapshot (snapshot may be stale).
            String materialName = resolveLiveMaterialName(item.getMaterialTypeId(), item.getMaterialName());

            // 单位需求 (已含出成率: standardQuantity / (yieldRate/100)), 单位 = BOM unit (e.g. g)
            BigDecimal perUnitRequired = item.calculateActualQuantity();
            BigDecimal totalRequiredBom = perUnitRequired.multiply(plannedQty);

            // 库存量 (称重批次单位, e.g. kg)
            BigDecimal available = resolveAvailableRawStock(factoryId, plan, item.getMaterialTypeId());
            if (available == null) {
                available = BigDecimal.ZERO;
            }

            // T144: 把 BOM 需求量 (g) 换算到称重批次单位 (kg) 再比较. g↔kg 走 converter → CONVERTED.
            String stockUnit = resolveMaterialStockUnit(factoryId, plan, item.getMaterialTypeId());
            String bomUnit = item.getUnit();
            BigDecimal totalRequired = totalRequiredBom;  // 默认: 单位一致或无换算器, 沿用原值

            if (materialUomConverter != null && bomUnit != null && stockUnit != null
                    && !bomUnit.trim().equalsIgnoreCase(stockUnit.trim())) {
                com.cretas.aims.service.uom.MaterialUomConverter.ConversionResult conv =
                        materialUomConverter.toComparableQuantity(
                                item.getMaterialTypeId(), totalRequiredBom, bomUnit, stockUnit);
                if (conv.isAbacaSkip()) {
                    // 抄码料: 每箱重量不一, 跳过按规格的库存校验 (不阻断), 仅记录.
                    log.info("B3 库存校验: 抄码料 {} 跳过库存校验 (planId={})", materialName, plan.getId());
                    continue;
                }
                if (conv.isUnconvertible()) {
                    log.warn("N1 开工无条件化: 原料单位无法换算, 仅记录预警不阻塞开工: planId={}, materialTypeId={}, materialName={}, bomUnit={}, stockUnit={}",
                            plan.getId(), item.getMaterialTypeId(), materialName, bomUnit, stockUnit);
                    continue;
                }
                totalRequired = conv.getQuantity();  // 已换算到称重批次单位 (kg)
            }

            if (available.compareTo(totalRequired) < 0) {
                BigDecimal shortageQty = totalRequired.subtract(available);
                String unit = stockUnit != null ? stockUnit : (bomUnit != null ? bomUnit : "");
                shortages.add(String.format(
                        "%s: 需要 %s%s, 可用 %s%s, 缺口 %s%s",
                        materialName,
                        totalRequired.stripTrailingZeros().toPlainString(), unit,
                        available.stripTrailingZeros().toPlainString(), unit,
                        shortageQty.stripTrailingZeros().toPlainString(), unit));
            }
        }

        if (!shortages.isEmpty()) {
            log.warn("N1 开工无条件化: 原料库存不足仅预警, 不阻塞开工: planId={}, shortages={}", plan.getId(), shortages);
            return;
        }

        log.debug("B3 库存校验通过: planId={}, productTypeId={}, plannedQuantity={}, bomItems={}",
                plan.getId(), plan.getProductTypeId(), plannedQty, bomItems.size());
    }

    /**
     * 缺料核算的结果 + <b>没算成的那部分</b> + <b>库存本身为 0 的那部分</b>。
     *
     * <p>⚠️ {@code uncomputable} 只进汇总语的<b>口径说明</b>, <b>不</b>做成预警项 ——
     * 实测全库 ACTIVE BOM 里 F006 有 69.2% 的行没有标准用量(7 个配方中 6 个受影响),
     * 做成预警就是天天误报, 而天天误报的提示最终会被人忽略掉(形态 E)。
     * 它要解决的是「一条预警都没有」同时表示「都够」和「一行都没算」这个歧义, 不是催人去配数据。
     *
     * <p>🔴 缺陷31 (owner 实测 2026-09-02): {@code zeroStock} 补的是另一个歧义 —— 上面那条
     * 「缺口」预警<b>只报算得出需求量的那些行</b>。AUXILIARY/PACKAGING 全厂库存为 0 时,
     * 如果算出的需求量恰好是 0 (辅料用量按 {@code dosagePerKgG} 现场折算, 计划期算不出总量)
     * 或者这一行连需求量都算不出来(跳进上面的 byReporting/uncomputable), 它就会<b>同时</b>
     * 不出现在缺口清单、也不出现在豁免清单里 —— 「库存为 0」这件事与需求量多少无关,
     * 计划期 100% 可判, 不该被「算不出需求量」这件事拖累。
     * ⛔ 豁免只对 RAW 成立(用量由报工当场决定); 非 RAW 物料库存为 0 时无论投料多少都会拦, 必须说。
     */
    private record AdvisoryScan(List<ProductionPlanMaterialAdvisoryDTO.Item> items,
                                List<String> byReporting,
                                List<String> uncomputable,
                                List<String> zeroStock) { }

    private List<ProductionPlanMaterialAdvisoryDTO.Item> buildMaterialAdvisoryItems(String factoryId, ProductionPlan plan) {
        return scanMaterialAdvisory(factoryId, plan).items();
    }

    private AdvisoryScan scanMaterialAdvisory(String factoryId, ProductionPlan plan) {
        if (bomRecipeItemRepository == null
                || plan.getProductTypeId() == null || plan.getProductTypeId().isBlank()
                || plan.getPlannedQuantity() == null
                || plan.getPlannedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return new AdvisoryScan(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        List<BomRecipeItem> bomItems;
        try {
            // 🔴 2026-08-18 判据五坎2: 同上 —— 多产出计划在这里恒空 ⇒ 界面印「暂无缺料预警」,
            //    而那正是 PR #2803 花力气消灭的那句话(它同时表示「都够」和「一行都没算」).
            bomItems = PlanBomItems.resolve(factoryId, plan, bomRecipeItemRepository);
        } catch (Exception e) {
            log.warn("Material advisory failed to load BOM: planId={}, err={}", plan.getId(), e.getMessage());
            return new AdvisoryScan(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        if (bomItems == null || bomItems.isEmpty()) {
            return new AdvisoryScan(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        BigDecimal plannedQty = plan.getPlannedQuantity();
        BigDecimal effectiveProductYieldRate = resolveEffectiveProductYieldRate(factoryId, plan.getProductTypeId());
        List<ProductionPlanMaterialAdvisoryDTO.Item> warnings = new ArrayList<>();
        // 🔴 2026-08-18: 没有标准用量的行**算不出需求量**, 只能跳过 —— 但跳过的条数必须留痕,
        //    否则「一条预警都没有」会同时表示「都够」和「一行都没算」(硬约束 4 的同一个坑)。
        //    实测 F006 黄油鸡 BOM 7 行里, 4 个原料的 standardQuantity 全是 null,
        //    于是缺料预警对这个计划的原料**从来没生效过**, 而界面说「暂无缺料预警」。
        List<String> byReporting = new ArrayList<>();
        List<String> uncomputable = new ArrayList<>();

        // 🔴 2026-09-05 第 2 步「建计划时按标准算料」: 绑了 workflow 投料端口的主料行
        //    没有 standardQuantity (那是设计, 配比在画布端口上), 原来一律跳过 ⇒ 六膳门
        //    25 条 ACTIVE BOM 明细里 16 条绑端口, 缺料预警对主料**从来没算过一次**。
        //
        //    现在: 有份额 + 有标准出成率 ⇒ 按 计划产出 ÷ 出成率 × 端口份额 摊出投入量。
        //    ⚠️ 两个前提缺任何一个都算不出, 那时仍旧走下面的 byReporting 留痕 ——
        //       ⛔ 不能拿 0 顶替: 「需要 0kg 主料」是个看起来很确定的假答案。
        //
        //    份额解析走 PlanInputShareResolver (与领料单同一份, 见该类注释)。
        //    建计划这一刻**必然**还没转批次, 所以走的是「读计划钉住的修订 nodesJson」那条。
        Map<String, BigDecimal> portRequirements = planInputShareResolver == null
                ? Map.of()
                : planInputShareResolver.spreadToPorts(
                        planInputShareResolver.resolve(factoryId, plan),
                        plannedQty,
                        effectiveProductYieldRate);

        for (BomRecipeItem item : bomItems) {
            String portId = item.getWorkflowInputPortId();
            boolean boundToPort = portId != null && !portId.isBlank();
            BigDecimal portRequired = boundToPort ? portRequirements.get(portId) : null;

            if (item.getMaterialTypeId() == null
                    || (item.getStandardQuantity() == null && portRequired == null)) {
                String skippedName = resolveLiveMaterialName(item.getMaterialTypeId(), item.getMaterialName());
                // ⚠️ 分两类, 措辞完全不同 —— 绑了 workflow 投料端口的行【本来就没有计划用量】,
                //    投多少由报工当场决定。把它说成「未配标准用量」等于诬告用户的数据坏了。
                //    实测: 全库 ACTIVE BOM 里 12 条空标准量的行 **无一例外** 都绑着端口
                //    (标准量为空 && 未绑端口 的组合是 0 条) ⇒ 这是设计, 不是缺失。
                if (boundToPort) {
                    byReporting.add(skippedName);
                } else {
                    uncomputable.add(skippedName);
                }
                continue;
            }
            String materialName = resolveLiveMaterialName(item.getMaterialTypeId(), item.getMaterialName());
            // 端口份额算出来的是**总投入量**(已含出成率折算), 与下面 BOM 标准量那条路算出来的
            // totalRequiredBom 同口径; 两者互斥, 端口优先 —— 绑了端口的行 standardQuantity 恒为 null。
            BigDecimal totalRequiredBom = portRequired != null
                    ? portRequired
                    : calculateRuntimeRequiredQuantity(item).multiply(plannedQty);

            BigDecimal available = resolveAvailableRawStock(factoryId, plan, item.getMaterialTypeId());
            if (available == null) {
                available = BigDecimal.ZERO;
            }

            String stockUnit = resolveMaterialStockUnit(factoryId, plan, item.getMaterialTypeId());
            String bomUnit = item.getUnit();
            String unit = stockUnit != null ? stockUnit : (bomUnit != null ? bomUnit : "");
            BigDecimal totalRequired = totalRequiredBom;
            if (materialUomConverter != null && bomUnit != null && stockUnit != null
                    && !bomUnit.trim().equalsIgnoreCase(stockUnit.trim())) {
                com.cretas.aims.service.uom.MaterialUomConverter.ConversionResult conv =
                        materialUomConverter.toComparableQuantity(
                                item.getMaterialTypeId(), totalRequiredBom, bomUnit, stockUnit);
                if (conv.isAbacaSkip()) {
                    continue;
                }
                if (conv.isUnconvertible()) {
                    warnings.add(ProductionPlanMaterialAdvisoryDTO.Item.builder()
                            .materialTypeId(item.getMaterialTypeId())
                            .materialName(materialName)
                            .requiredQuantity(totalRequiredBom)
                            .availableQuantity(available)
                            .shortageQuantity(null)
                            .unit(bomUnit)
                            .message(String.format("原料「%s」BOM单位(%s)与库存单位(%s)无法换算, 请核对单位配置",
                                    materialName, bomUnit, stockUnit))
                            .build());
                    continue;
                }
                totalRequired = conv.getQuantity();
                unit = stockUnit;
            }

            if (available.compareTo(totalRequired) < 0) {
                BigDecimal shortageQty = totalRequired.subtract(available);
                warnings.add(ProductionPlanMaterialAdvisoryDTO.Item.builder()
                        .materialTypeId(item.getMaterialTypeId())
                        .materialName(materialName)
                        .requiredQuantity(totalRequired)
                        .availableQuantity(available)
                        .shortageQuantity(shortageQty)
                        .unit(unit)
                        .kind(ProductionPlanMaterialAdvisoryDTO.Kind.FACTORY_SHORTAGE)
                        .message(String.format("%s: 需要 %s%s, 可用 %s%s, 缺口 %s%s",
                                materialName,
                                totalRequired.stripTrailingZeros().toPlainString(), unit,
                                available.stripTrailingZeros().toPlainString(), unit,
                                shortageQty.stripTrailingZeros().toPlainString(), unit))
                        .build());
                continue;
            }

            // 🔴 全厂够, 再看**生产仓**够不够 —— 「忘了调拨」是一种独立的处境, 下一步是领料而非采购。
            BigDecimal workshopAvailable = resolveWorkshopRawStock(factoryId, plan, item.getMaterialTypeId());
            if (workshopAvailable != null && workshopAvailable.compareTo(totalRequired) < 0) {
                warnings.add(ProductionPlanMaterialAdvisoryDTO.Item.builder()
                        .materialTypeId(item.getMaterialTypeId())
                        .materialName(materialName)
                        .requiredQuantity(totalRequired)
                        .availableQuantity(available)
                        .workshopAvailableQuantity(workshopAvailable)
                        .shortageQuantity(totalRequired.subtract(workshopAvailable))
                        .unit(unit)
                        .kind(ProductionPlanMaterialAdvisoryDTO.Kind.NOT_IN_WORKSHOP)
                        .message(String.format(
                                "%s: 全厂有 %s%s, 但生产仓只有 %s%s。逐道报工按生产仓扣料，请先领料或调拨 %s%s",
                                materialName,
                                available.stripTrailingZeros().toPlainString(), unit,
                                workshopAvailable.stripTrailingZeros().toPlainString(), unit,
                                totalRequired.subtract(workshopAvailable).stripTrailingZeros().toPlainString(), unit))
                        .build());
            }
        }

        List<String> zeroStock = scanZeroStockNonRawMaterials(factoryId, plan, bomItems, warnings);
        return new AdvisoryScan(warnings, byReporting, uncomputable, zeroStock);
    }

    /**
     * 缺陷31: 「库存为 0」与需求量多少无关, 计划期 100% 可判 —— 独立于上面那条只报
     * 「算得出需求量」的缺口扫描。两个来源都要扫:
     * <ol>
     *   <li>{@code bom_recipe_items} 里的非 RAW 行 (PACKAGING 等) —— 已经在上面的循环里算过
     *       需求量的, 已在 {@code warnings} 里, 这里跳过去重; 需求量算不出来 (byReporting/
     *       uncomputable) 或算出来恰好是 0 的, 从来没进过 warnings, 这里补上。</li>
     *   <li>{@code bom_seasoning_items} (调料/辅料, 按 {@code dosagePerKgG} 现场折算, 计划期
     *       从来算不出总量) —— 上面那个循环<b>根本不读这张表</b>, 与 {@code resolveEligibleBomMaterialTypeIds}
     *       2026-08-18 那次踩的坑 (调料在另一张表, 消费侧只读了 bom_recipe_items) 同一形状。
     *       实测: 熟肉饼 BOM 的 7 个辅料(洋葱泥/食用盐/白砂糖/复合磷酸盐/黑胡椒/小苏打/白洋葱粒)
     *       全部只存在于 bom_seasoning_items, {@code bom_recipe_items} 里一行都没有。</li>
     * </ol>
     *
     * <p>库存口径复用 {@link #resolveAvailableRawStock} —— 与上面缺口计算<b>同一个方法</b>
     * (同租户/同来料归属口径), ⛔ 不新开一条查询。
     */
    private List<String> scanZeroStockNonRawMaterials(String factoryId, ProductionPlan plan,
            List<BomRecipeItem> bomItems, List<ProductionPlanMaterialAdvisoryDTO.Item> warnings) {
        Set<String> seen = warnings.stream()
                .map(ProductionPlanMaterialAdvisoryDTO.Item::getMaterialTypeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> zeroStock = new ArrayList<>();

        for (BomRecipeItem item : bomItems) {
            String materialTypeId = item.getMaterialTypeId();
            if (materialTypeId == null || !seen.add(materialTypeId)) {
                continue;
            }
            if (!isNonRawStockCheckableCategory(item.getMaterialCategory())) {
                continue;
            }
            BigDecimal available = resolveAvailableRawStock(factoryId, plan, materialTypeId);
            if (available != null && available.compareTo(BigDecimal.ZERO) == 0) {
                zeroStock.add(resolveLiveMaterialName(materialTypeId, item.getMaterialName()));
            }
        }

        if (bomSeasoningItemRepository != null) {
            Set<String> recipeIds = bomItems.stream()
                    .map(BomRecipeItem::getRecipeId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (String recipeId : recipeIds) {
                List<com.cretas.aims.entity.bom.BomSeasoningItem> seasoningItems;
                try {
                    seasoningItems = bomSeasoningItemRepository.findByRecipeIdOrderBySeqAsc(recipeId);
                } catch (RuntimeException ex) {
                    // ⛔ 读不到调料不能让「原料参考」整个挂掉, 留痕给排查用 (同 6.08.18 那次修复的纪律)。
                    log.warn("[原料参考] 调料明细读不出来 recipe={}: {}", recipeId, ex.getMessage());
                    continue;
                }
                if (seasoningItems == null) {
                    continue;
                }
                for (com.cretas.aims.entity.bom.BomSeasoningItem seasoning : seasoningItems) {
                    String materialTypeId = seasoning.getMaterialTypeId();
                    if (materialTypeId == null || !seen.add(materialTypeId)) {
                        continue;
                    }
                    BigDecimal available = resolveAvailableRawStock(factoryId, plan, materialTypeId);
                    if (available != null && available.compareTo(BigDecimal.ZERO) == 0) {
                        zeroStock.add(resolveLiveMaterialName(materialTypeId, seasoning.getName()));
                    }
                }
            }
        }
        return zeroStock;
    }

    /**
     * RAW 以外、真正代表「投入」且能查库存的类别 —— PACKAGING / AUXILIARY 等。
     *
     * <p>⛔ RAW 排除: 它的豁免走「不参与事前核算」名单(投料量由报工当场决定), 不是这里。
     * ⛔ {@link BomRecipeItem#CATEGORY_BYPRODUCT} 排除: 那是这张配方的产出声明, 不是投入。
     * ⛔ {@link BomRecipeItem#CATEGORY_SEMI_FINISHED} 排除: 回料没有 materialTypeId, 本来就查不了原料库存。
     * ⛔ 空/未知类别排除: DB 侧默认值是 "RAW", 测试里 {@code new BomRecipeItem()} 没显式设置类别时
     *    也是 null —— 按 RAW 处理, 避免把不确定的东西误判成「非 RAW」。
     */
    private static boolean isNonRawStockCheckableCategory(String materialCategory) {
        if (materialCategory == null || materialCategory.isBlank()) {
            return false;
        }
        String upper = materialCategory.trim().toUpperCase(java.util.Locale.ROOT);
        if ("RAW".equals(upper)) {
            return false;
        }
        if (BomRecipeItem.CATEGORY_BYPRODUCT.equals(upper)) {
            return false;
        }
        if (BomRecipeItem.CATEGORY_SEMI_FINISHED.equals(upper)) {
            return false;
        }
        return true;
    }

    /**
     * 生产仓这一层<b>量得到吗</b> —— 与 {@link #resolveWorkshopRawStock} 的可判定条件同源。
     *
     * <p>存在的唯一理由是让「无预警」那句话分得清「生产仓没问题」和「没量生产仓」。
     *
     * <p>🔴 缺陷38 系统性排查 (2026-09-02) — <b>刻意不改</b>, 逐字保留 CUSTOMER_SUPPLIED
     * 恒 false: 这个方法只按<b>计划</b>粒度判定, 没有 materialTypeId, 推不出「这张计划的 BOM
     * 里有没有 PACKAGING/AUXILIARY 行」。{@link #resolveWorkshopRawStock} 已经按<b>物料</b>粒度
     * 修正(辅料/包材现在真的会核对生产仓), 逐行预警(NOT_IN_WORKSHOP)因此已经是对的; 本方法只影响
     * 「全部预警都为空时」那句<b>收尾文案</b>是否说「生产仓也已备料」—— 说得更保守(维持现状)只会让
     * CUSTOMER_SUPPLIED 计划在辅料/包材恰好也备齐生产仓时,收尾少说一句好话,不会漏报任何预警,
     * 不是数据缺陷。要治本必须把 BOM 物料大类集合传进来改造成按类别判定, 属于更大的重构,
     * 本次不做(硬约束 8: 显式登记「故意不改」并写明理由)。
     */
    private boolean workshopCaliberAvailable(String factoryId, ProductionPlan plan) {
        if (warehouseResolver == null) {
            return false;
        }
        if (plan != null && plan.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED) {
            return false;
        }
        try {
            String workshopId = warehouseResolver.resolveWorkshopId(factoryId);
            return workshopId != null && !workshopId.isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 生产仓可用量; 无法判定时返回 {@code null}（<b>不是 0</b>）。
     *
     * <p>⛔ 读不到就返回 null 让调用方跳过 —— 兜底成 0 会把「我不知道」翻译成「一粒都没有」,
     * 于是每个计划都长出一条假的「请先领料」。本仓形态 A¹⁰。
     *
     * <p>返回 null 的情形: 没配生产仓 / resolver 不可用 / 客户来料计划下的 <b>RAW</b> 物料
     * (那类走另一套归属校验, 仓库维度的结论不适用)。
     *
     * <p>🔴 缺陷38 系统性排查 (2026-09-02): 原来对 CUSTOMER_SUPPLIED 计划<b>无条件</b>返回 null,
     * 与 {@link #resolveAvailableRawStock}(缺陷49) 同一形状漏了物料大类判断 —— PACKAGING/AUXILIARY
     * 本就该走工厂自有库存(与「工厂备料生产」同一条件), 而
     * {@link com.cretas.aims.repository.MaterialBatchRepository#sumAvailableRawStockQuantityByMaterialTypeAndWarehouse}
     * 本来就只认 COMPANY_OWNED —— 于是「忘了调拨到生产仓」这条预警对 CUSTOMER_SUPPLIED 计划下的
     * 辅料/包材<b>从未生效过</b>, 同一物料换到普通计划下却会正常预警, 纯粹因为 materialSupplyMode
     * 不同。RAW 逐字不变: 仍然整体跳过(客供归属校验管它, 与仓库维度无关)。
     */
    private BigDecimal resolveWorkshopRawStock(String factoryId, ProductionPlan plan, String materialTypeId) {
        if (warehouseResolver == null || materialTypeId == null) {
            return null;
        }
        if (plan != null && plan.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED
                && !ProductionInventoryOwnershipGuard.isCompanyStockEligibleCategory(
                        resolveMaterialMainCategory(factoryId, materialTypeId))) {
            return null;
        }
        String workshopId;
        try {
            workshopId = warehouseResolver.resolveWorkshopId(factoryId);
        } catch (RuntimeException e) {
            log.debug("[advisory] 生产仓解析失败 factory={}: {}", factoryId, e.getMessage());
            return null;
        }
        if (workshopId == null || workshopId.isBlank()) {
            return null;
        }
        return materialBatchRepository.sumAvailableRawStockQuantityByMaterialTypeAndWarehouse(
                factoryId, materialTypeId, workshopId);
    }

    /**
     * T144: 读取物料的实际库存单位 = AVAILABLE 批次的 {@code MaterialBatch.quantityUnit}
     * (称重入库口径, e.g. "kg"), <b>不是</b> {@code RawMaterialType.unit} (箱, 仅采购/展示标签).
     *
     * <p>原料称重入库 — 权威库存量是 kg. BOM 克(g) 与 kg 走 converter 的 g↔kg 路径 → CONVERTED,
     * 不再误报"原料不足", 也不需要装箱规格/409 摩擦.
     *
     * <p>各批次单位混用时取最常见的并记 warning. 无可用批次时回退 RawMaterialType.unit
     * (无库存场景, 后续 available=0 仍会正确判短缺).
     */
    /**
     * T159-B Change4: 优先从 RawMaterialType 主数据读取原料真实名称, BOM 快照作 fallback.
     *
     * <p>BOM 快照 (item.getMaterialName()) 在重命名后可能陈旧 (例如"气调盒"变更为"吸塑盒").
     * 使用主数据真名可以让 UNCONVERTIBLE 错误消息向用户展示正确的原料名, 减少误导.
     *
     * @param materialTypeId 原料类型 ID
     * @param snapshotName   BOM 快照名称 (fallback)
     * @return 优先返回主数据真名; 快照名次选; 兜底 "原料 {id}"
     */
    /**
     * 成品的<b>标准出成率</b>(%) —— 唯一来源是 {@code bom_recipes.overall_yield_rate}(表头)。
     *
     * <h3>口径(2026-09-06 owner 裁定, RECON-LEGAL-VALUE-SENTINEL-20260906)</h3>
     * <ul>
     *   <li>{@code NULL} = <b>没配</b>。默认值早在 {@code V20261028_69} 就被摘掉了。</li>
     *   <li>🔴 {@code 100.00} 是<b>合法取值</b>(零损耗), <b>不再</b>当哨兵。</li>
     *   <li>&gt; 100 也合法(保水/腌制, 六膳门猪舌实测 105–126%), 上限见
     *       {@link com.cretas.aims.dto.bom.BomYieldApplyRequest#getYieldRate()}。</li>
     * </ul>
     *
     * <h3>🔴 为什么删掉了「恰好一个主料」那道闸</h3>
     *
     * <p>它数的是 {@code BomRecipeItem.yieldRate != 100.00}, 而<b>行级出成率已经废弃</b>:
     * 实体默认 100.00, {@code BomRecipeServiceImpl#applyDtoToItem}(add/update 共用) 与
     * {@code #createInputSkeleton} 都<b>硬写</b> 100.00, DTO 上也标着 deprecated/ignored。
     * 2026-09-06 直查 prod: {@code bom_recipe_items} <b>144/144 行</b>(两个租户, 含软删)
     * 全是 {@code 100.00} ⇒ 这个计数<b>恒为 0</b> ⇒ 恒不等于 1 ⇒ 这个方法在 prod 上
     * <b>永远在读表头之前就 return null</b>, 且 {@code log.warn} 只在 count &gt; 1 时开口,
     * 所以连一行日志都没有。级联: PR #599 的
     * {@code PlanInputShareResolver#spreadToPorts} 在 {@code yieldRatePercent == null}
     * 时返回 {@code Map.of()} ⇒ 那个功能<b>一次都没算过</b>。
     *
     * <p>主料身份的权威定义是 <b>workflow INPUT 端口份额</b>
     * ({@link com.cretas.aims.service.workflow.PlanInputShareResolver}), 不是行级出成率。
     *
     * <p>⚠️ 出成率折算<b>只在一处</b>发生 —— {@code spreadToPorts}。⛔ 不要在
     * {@link #calculateRuntimeRequiredQuantity} 里再折一次, 那会对绑端口的行折两遍。
     *
     * @return 配置了就返回它(含 100.00、含 &gt; 100); 没配返回 {@code null} —— ⛔ 不兜底成
     *         某个数字, 「需要 0kg 主料」是个看起来很确定的假答案(形态 A¹⁰)。
     */
    private BigDecimal resolveEffectiveProductYieldRate(String factoryId, String productTypeId) {
        if (productTypeId == null || bomRecipeRepository == null) {
            return null;
        }
        Optional<BomRecipe> recipe = bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                factoryId, productTypeId, BomRecipe.Status.ACTIVE);
        if (recipe.isPresent() && isPositive(recipe.get().getOverallYieldRate())) {
            return recipe.get().getOverallYieldRate();
        }
        // ⛔ 不许静默 return null —— 「没配出成率」和「配了但算不出」在下游长得一模一样,
        //    而这正是本次缺陷藏了这么久的原因。留一条可观测的痕, 带上是哪个配方。
        logMissingYieldStandardThrottled(factoryId, productTypeId,
                recipe.map(BomRecipe::getProductName).orElse(null));
        return null;
    }

    /** 「没配标准出成率」的节流日志键 → 上次打印时刻(ms)。缺料预警每开一次界面就调一遍, 不节流会刷屏。 */
    private static final Map<String, Long> MISSING_YIELD_LOG_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MISSING_YIELD_LOG_INTERVAL_MS = 10L * 60 * 1000;
    /** 键数量上限 —— 租户 × SKU 无界, 满了就整体清空重新计时, ⛔ 不让日志节流表变成内存泄漏。 */
    private static final int MISSING_YIELD_LOG_MAX_KEYS = 2000;

    private void logMissingYieldStandardThrottled(String factoryId, String productTypeId, String productName) {
        String key = factoryId + "|" + productTypeId;
        long now = System.currentTimeMillis();
        Long last = MISSING_YIELD_LOG_AT.get(key);
        if (last != null && now - last < MISSING_YIELD_LOG_INTERVAL_MS) {
            return;
        }
        if (MISSING_YIELD_LOG_AT.size() >= MISSING_YIELD_LOG_MAX_KEYS) {
            MISSING_YIELD_LOG_AT.clear();
        }
        MISSING_YIELD_LOG_AT.put(key, now);
        log.info("[MaterialAdvisory] 标准出成率未配置, 主料投入量按端口份额摊不出来: "
                        + "factoryId={}, productTypeId={}, productName={} —— 请在「标准出成率」页面填写",
                factoryId, productTypeId, productName);
    }

    /**
     * 单位计划产出所需的这一行原料量。
     *
     * <p>⚠️ 只走行级口径 {@link BomRecipeItem#calculateActualQuantity()}
     * ({@code standardQuantity / (yieldRate/100)}, 与成本侧 {@code BomServiceImpl} 同一份公式)。
     *
     * <p>🔴 这里<b>不</b>再按成品标准出成率折算 —— 那件事已经由
     * {@link com.cretas.aims.service.workflow.PlanInputShareResolver#spreadToPorts} 独占,
     * 绑端口的行走那一条(它们 {@code standardQuantity} 恒为 null, 根本到不了这个方法)。
     * 两处都折 = 同一个折算有两份(形态 D), 对绑端口的行会折两遍。
     */
    private BigDecimal calculateRuntimeRequiredQuantity(BomRecipeItem item) {
        if (item == null || item.getStandardQuantity() == null) {
            return BigDecimal.ZERO;
        }
        return item.calculateActualQuantity();
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 给用户看的成品名 —— ⛔ 不要把 productTypeId(UUID) 甩到界面上。
     *
     * <p>与 {@link #resolveLiveMaterialName} 同形: 先问主数据活值, 再退回 BOM 快照名。
     * 两者都拿不到时才退回 id, 并且明说那是「未命名成品」, 不假装那串 UUID 是个名字。</p>
     */
    private String resolveFinishedGoodLabel(String productTypeId, String snapshotName) {
        if (productTypeRepository != null && productTypeId != null) {
            try {
                var pt = productTypeRepository.findById(productTypeId).orElse(null);
                if (pt != null && pt.getName() != null && !pt.getName().isBlank()) {
                    return pt.getName();
                }
            } catch (RuntimeException error) {
                log.debug("读取成品名失败 {} ({})", productTypeId, error.getMessage());
            }
        }
        if (snapshotName != null && !snapshotName.isBlank()) {
            return snapshotName;
        }
        return "未命名成品";
    }

    private String resolveLiveMaterialName(String materialTypeId, String snapshotName) {
        if (rawMaterialTypeRepository != null && materialTypeId != null) {
            try {
                var mt = rawMaterialTypeRepository.findById(materialTypeId).orElse(null);
                if (mt != null && mt.getName() != null && !mt.getName().isBlank()) {
                    return mt.getName();
                }
            } catch (Exception e) {
                log.debug("[T159] 读取原料名失败 {} ({})", materialTypeId, e.getMessage());
            }
        }
        // Fallback to BOM snapshot
        if (snapshotName != null && !snapshotName.isBlank()) {
            return snapshotName;
        }
        return "原料 " + (materialTypeId != null ? materialTypeId : "?");
    }

    /**
     * 物料大类 (原料/辅料/包材) —— 权威来源 {@code RawMaterialType.mainCategory}，
     * 与 {@code FactoryMaterialRequisitionServiceImpl.resolveMaterialMainCategory} 同一个取数方式
     * (同文件那个方法的注释已明说: ⛔ 不查 BOM 行自带的 materialCategory 副本,
     * 那是编辑器可改的独立字段, 与本方法找批次时用的口径不同源会漂)。
     * 查不到一律返回 null, {@link ProductionInventoryOwnershipGuard#isCompanyStockEligibleCategory}
     * 对 null 按最严格口径处理, 不会误放宽。
     */
    private String resolveMaterialMainCategory(String factoryId, String materialTypeId) {
        if (rawMaterialTypeRepository == null || materialTypeId == null) {
            return null;
        }
        try {
            return rawMaterialTypeRepository.findByIdAndFactoryId(materialTypeId, factoryId)
                    .map(com.cretas.aims.entity.RawMaterialType::getMainCategory)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("读取物料大类失败: {} ({})", materialTypeId, e.getMessage());
            return null;
        }
    }

    private BigDecimal resolveAvailableRawStock(String factoryId,
                                                ProductionPlan plan,
                                                String materialTypeId) {
        if (plan != null && plan.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED) {
            requireCustomerSuppliedPlanLineage(plan, "原料库存");
            BigDecimal customerOwned = isBlank(plan.getSourceOrderId())
                    ? materialBatchRepository.sumAvailableUnassignedCustomerOwnedRawStock(
                            factoryId, materialTypeId, plan.getCustomerId())
                    : materialBatchRepository.sumAvailableCustomerSuppliedRawStock(
                            factoryId, materialTypeId, plan.getCustomerId(), plan.getSourceOrderId());
            // 缺陷 49 (2026-09-02 owner 现场实测, LIUSHANMEN 真实数据直查确认): 此时自己开的那个
            // CUSTOMER_SUPPLIED 计划, L1纸箱(PACKAGING)/冰水(AUXILIARY) 的实际库存全部是 COMPANY_OWNED,
            // 上面的客户所有查询恒为 0 —— 与结单写路径守卫
            // (ProductionInventoryOwnershipGuard.assertMaterialBatchAllowed, 缺陷 38) 同样的口径:
            // 辅料/包材(含调料, 经 MaterialMainCategory 归一) 本就该由工厂自有库存出, RAW 不受影响
            // (仍只能用客户来料, 逐字不变)。
            if (ProductionInventoryOwnershipGuard.isCompanyStockEligibleCategory(
                    resolveMaterialMainCategory(factoryId, materialTypeId))) {
                BigDecimal companyOwned =
                        materialBatchRepository.sumAvailableRawStockQuantityByMaterialType(factoryId, materialTypeId);
                return customerOwned.add(companyOwned);
            }
            return customerOwned;
        }
        // Repository contract excludes customer-owned inventory while retaining
        // null ownership only for read-compatible legacy company stock.
        // 缺陷 49: 上面这个方法已限定 RAW/LOGISTICS/WORKSHOP 三类仓
        // (与本类的 isSettlementEligibleWarehouse 同一个判定; 权威枚举集合见
        // FactoryWarehouse.SETTLEMENT_ELIGIBLE_TYPES, 这个 2 参数重载在 repository 里
        // 已经补上该集合再委托给真正执行 @Query 的 3 参数方法)。
        return materialBatchRepository.sumAvailableRawStockQuantityByMaterialType(factoryId, materialTypeId);
    }

    /**
     * 🔴 缺陷38 系统性排查 (2026-09-02): CUSTOMER_SUPPLIED 分支原来<b>只</b>查客供批次单位 ——
     * 与 {@link #resolveAvailableRawStock}(缺陷49) 修复前同一形状。PACKAGING/AUXILIARY 走公司
     * 自有库存(isCompanyStockEligibleCategory)时, 客供批次通常查不到任何行, {@code units} 恒空,
     * 于是每次都落到方法末尾「无可用批次」的兜底 —— 回退 {@code RawMaterialType.unit}(采购/展示
     * 标签, 如「箱」), 而不是公司批次真实的称重/计数单位(如「个」)。resolveAvailableRawStock 那边
     * 已经把公司库存数量算进「可用量」, 这里的单位却没跟上, 于是「需要 X箱, 可用 50箱」这类提示
     * 用错的单位比较, 与结单/领料侧的真实单位口径自相矛盾(与缺陷49同一形态 D)。RAW 逐字不变:
     * 仍然只认客供批次单位。
     */
    private String resolveMaterialStockUnit(String factoryId,
                                            ProductionPlan plan,
                                            String materialTypeId) {
        if (materialTypeId == null) {
            return null;
        }
        try {
            java.util.List<String> units;
            if (plan != null && plan.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED) {
                requireCustomerSuppliedPlanLineage(plan, "原料库存单位");
                java.util.List<String> customerUnits = isBlank(plan.getSourceOrderId())
                        ? materialBatchRepository.findUnassignedCustomerOwnedRawStockUnits(
                                factoryId, materialTypeId, plan.getCustomerId())
                        : materialBatchRepository.findCustomerSuppliedRawStockUnits(
                                factoryId, materialTypeId, plan.getCustomerId(), plan.getSourceOrderId());
                if (ProductionInventoryOwnershipGuard.isCompanyStockEligibleCategory(
                        resolveMaterialMainCategory(factoryId, materialTypeId))) {
                    java.util.List<String> companyUnits =
                            materialBatchRepository.findRawStockUnitsByMaterialType(factoryId, materialTypeId);
                    units = new java.util.ArrayList<>();
                    if (customerUnits != null) {
                        units.addAll(customerUnits);
                    }
                    if (companyUnits != null) {
                        units.addAll(companyUnits);
                    }
                } else {
                    units = customerUnits;
                }
            } else {
                units = materialBatchRepository.findRawStockUnitsByMaterialType(factoryId, materialTypeId);
            }
            if (units != null && !units.isEmpty()) {
                if (units.size() > 1) {
                    log.warn("物料 {} 可用批次单位混用 {}, 取最常见 {}",
                            materialTypeId, units, units.get(0));
                }
                return units.get(0);
            }
        } catch (Exception e) {
            log.debug("读取批次库存单位失败: {} ({})", materialTypeId, e.getMessage());
        }
        // 无可用批次: 回退 RawMaterialType.unit (后续 available=0 判短缺, 不影响正确性)
        if (rawMaterialTypeRepository != null) {
            try {
                return rawMaterialTypeRepository.findById(materialTypeId)
                        .map(RawMaterialType::getUnit)
                        .orElse(null);
            } catch (Exception e) {
                log.debug("读取物料库存单位失败: {} ({})", materialTypeId, e.getMessage());
            }
        }
        return null;
    }

    /**
     * P0-12: 校验销售订单行来源 (字段粒度修正),自动回填订单/客户/产品
     * 客户原话 4216s: "关联销售订单产品 / 客户名称应该自动带出来"
     */
    private void validateAndEnrichSalesOrderSource(String factoryId, CreateProductionPlanRequest request) {
        if (request.getSourceType() != PlanSourceType.CUSTOMER_ORDER) {
            boolean customerInventoryProduction = request.getSourceType() == PlanSourceType.SAFETY_STOCK
                    && (request.getOutputOwnership() == InventoryOwnership.CUSTOMER_OWNED
                        || request.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED
                        || !isBlank(request.getCustomerId()));
            if (customerInventoryProduction) {
                if (isBlank(request.getCustomerId())) {
                    throw new BusinessException(400, "客户专属库存生产必须选择归属客户")
                            .withCode("CUSTOMER_INVENTORY_PRODUCTION_CUSTOMER_REQUIRED")
                            .withHintTarget("customerId");
                }
                if (!isBlank(request.getSourceOrderId()) || !isBlank(request.getSourceOrderItemId())) {
                    throw new BusinessException(400, "客户专属库存生产不能同时关联销售订单")
                            .withCode("CUSTOMER_INVENTORY_PRODUCTION_ORDER_FORBIDDEN")
                            .withHint("如已有销售订单，请改用销售订单生产")
                            .withHintTarget("sourceOrderId");
                }
                if (customerRepository == null) {
                    throw new BusinessException(500, "客户资料校验服务不可用，不能创建客户专属库存生产")
                            .withCode("CUSTOMER_VALIDATION_UNAVAILABLE");
                }
                var customer = customerRepository.findByIdAndFactoryId(request.getCustomerId(), factoryId)
                        .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()))
                        .orElseThrow(() -> new BusinessException(400, "归属客户不存在、已停用或不属于当前工厂")
                                .withCode("CUSTOMER_INVENTORY_PRODUCTION_CUSTOMER_INVALID")
                                .withHintTarget("customerId"));
                // Selecting a customer does not select the input ownership. Preserve the
                // legacy meaning only when an older client explicitly selected customer output.
                boolean customerSupplied = request.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED
                        || (request.getMaterialSupplyMode() == null
                            && request.getOutputOwnership() == InventoryOwnership.CUSTOMER_OWNED);
                request.setProcessingMode(customerSupplied
                        ? SalesProcessingMode.TOLL_PROCESSING : SalesProcessingMode.STANDARD_SALE);
                request.setMaterialSupplyMode(customerSupplied
                        ? MaterialSupplyMode.CUSTOMER_SUPPLIED : MaterialSupplyMode.FACTORY_SUPPLIED);
                request.setOutputOwnership(customerSupplied
                        ? InventoryOwnership.CUSTOMER_OWNED : InventoryOwnership.COMPANY_OWNED);
                request.setSourceOrderId(null);
                request.setSourceOrderItemId(null);
                request.setSourceOrderIds(new java.util.ArrayList<>());
                request.setSourceCustomerName(customer.getName());
                return;
            }
            // Every other non-sales plan remains ordinary company-owned inventory.
            request.setCustomerId(null);
            request.setProcessingMode(null);
            request.setMaterialSupplyMode(null);
            request.setOutputOwnership(InventoryOwnership.COMPANY_OWNED);
            return;
        }
        // 优先使用 sourceOrderItemId (新粒度); 兼容老 sourceOrderId 调用方
        String itemIdStr = request.getSourceOrderItemId();
        if (itemIdStr == null || itemIdStr.isBlank()) {
            if (request.getSourceOrderId() != null && !request.getSourceOrderId().isBlank()) {
                // 向后兼容: 旧调用只传 sourceOrderId — 仅校验订单, 不回填行
                SalesOrder so = salesOrderRepository.findById(request.getSourceOrderId())
                        .orElseThrow(() -> new BusinessException(404, "关联的销售订单不存在: " + request.getSourceOrderId())
                                .withHint("请刷新销售订单列表后重新选择").withHintTarget("sourceOrderId"));
                if (!factoryId.equals(so.getFactoryId())) {
                    throw new BusinessException(403, "无权关联其他工厂的销售订单")
                            .withHint("销售订单不属于该工厂, 请选择本工厂的订单").withHintTarget("sourceOrderId");
                }
                BusinessRowScope.requireOwner(businessRowScope, factoryId, so.getCreatedBy());
                // 财审闸门: 客户订单必须先通过财务审核才能流转车间排产
                assertSalesOrderFinanceApproved(so, "sourceOrderId");
                snapshotSalesOwnershipContract(request, so,
                        so.getProcessingMode(), so.getMaterialSupplyMode(), "sourceOrderId");
                if ((request.getSourceCustomerName() == null || request.getSourceCustomerName().isBlank())
                        && so.getCustomerName() != null) {
                    request.setSourceCustomerName(so.getCustomerName());
                }
                return;
            }
            throw new BusinessException(400, "选择客户订单来源时,必须指定关联的销售订单产品行 (sourceOrderItemId)")
                    .withHint("请选择销售订单中具体的产品行").withHintTarget("sourceOrderItemId");
        }

        Long itemId;
        try {
            itemId = Long.parseLong(itemIdStr);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "销售订单行ID格式无效: " + itemIdStr)
                    .withHint("销售订单行 ID 应为数字, 请重新选择").withHintTarget("sourceOrderItemId");
        }
        SalesOrderItem item = salesOrderItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(404, "销售订单行不存在或不属于本工厂")
                        .withHint("请刷新销售订单列表后重新选择").withHintTarget("sourceOrderItemId"));
        SalesOrder so = salesOrderRepository.findById(item.getSalesOrderId())
                .orElseThrow(() -> new BusinessException(404, "销售订单行不存在或不属于本工厂")
                        .withHint("请刷新销售订单列表后重新选择").withHintTarget("sourceOrderItemId"));
        if (!factoryId.equals(so.getFactoryId())) {
            throw new BusinessException(404, "销售订单行不存在或不属于本工厂")
                    .withHint("请刷新销售订单列表后重新选择").withHintTarget("sourceOrderItemId");
        }
        BusinessRowScope.requireOwner(businessRowScope, factoryId, so.getCreatedBy());
        // 财审闸门: 客户订单必须先通过财务审核才能流转车间排产
        assertSalesOrderFinanceApproved(so, "sourceOrderItemId");

        // A line-level override is the finest-grained sales truth. Legacy lines
        // without a snapshot inherit the authoritative order-header contract.
        SalesProcessingMode processingMode = item.getProcessingMode() != null
                ? item.getProcessingMode() : so.getProcessingMode();
        MaterialSupplyMode materialSupplyMode = item.getMaterialSupplyMode() != null
                ? item.getMaterialSupplyMode() : so.getMaterialSupplyMode();
        snapshotSalesOwnershipContract(request, so, processingMode, materialSupplyMode,
                "sourceOrderItemId");

        // 自动回填: 订单ID/客户名/产品类型
        request.setSourceOrderId(so.getId());
        if (request.getSourceCustomerName() == null || request.getSourceCustomerName().isBlank()) {
            request.setSourceCustomerName(so.getCustomerName());
        }
        if ((request.getProductTypeId() == null || request.getProductTypeId().isBlank())
                && item.getProductTypeId() != null) {
            request.setProductTypeId(item.getProductTypeId());
        }
    }

    /**
     * Freeze the sales contract that determines legal ownership of future output.
     * Both modes null is supported only for a legacy sales order and remains null.
     */
    private void snapshotSalesOwnershipContract(CreateProductionPlanRequest request,
                                                SalesOrder salesOrder,
                                                SalesProcessingMode processingMode,
                                                MaterialSupplyMode materialSupplyMode,
                                                String hintTarget) {
        request.setCustomerId(salesOrder.getCustomerId());
        if (processingMode == null && materialSupplyMode == null) {
            request.setProcessingMode(null);
            request.setMaterialSupplyMode(null);
            request.setOutputOwnership(null);
            return;
        }
        if (processingMode == null || materialSupplyMode == null) {
            throw new BusinessException(409, "销售订单的加工/供料合同不完整，不能生成库存归属快照")
                    .withCode("SALES_SUPPLY_CONTRACT_INCOMPLETE")
                    .withHint("请先在销售订单补全加工模式和供料模式")
                    .withHintTarget(hintTarget);
        }
        if (processingMode == SalesProcessingMode.STANDARD_SALE
                && materialSupplyMode == MaterialSupplyMode.CUSTOMER_SUPPLIED) {
            throw new BusinessException(409, "普通销售不能使用客户供料，不能生成库存归属快照")
                    .withCode("SALES_SUPPLY_CONTRACT_INVALID")
                    .withHint("请修正销售订单的加工模式或供料模式")
                    .withHintTarget(hintTarget);
        }

        request.setProcessingMode(processingMode);
        request.setMaterialSupplyMode(materialSupplyMode);
        request.setOutputOwnership(processingMode == SalesProcessingMode.TOLL_PROCESSING
                ? InventoryOwnership.CUSTOMER_OWNED
                : InventoryOwnership.COMPANY_OWNED);
    }

    /**
     * 财审闸门 — 客户订单(CUSTOMER_ORDER 源)必须先通过财务审核, 才能流转车间排产建生产计划.
     *
     * <p>对应需求 (转录 C-1): "销售计划→下单→财务审批→流转车间→排产闭环, 财务审批完流到张权按订单排产".
     * 在此之前任何 production:read_write 角色可对未审订单排产, 绕过财务. 此校验堵住该缺口.
     *
     * <p>放行条件: SO 状态为 FINANCE_APPROVED 或其后续态 (PROCESSING / PARTIAL_DELIVERED / COMPLETED),
     * 即已通过财审且尚未取消的订单. 拒绝条件: 审批前态 (DRAFT / CONFIRMED / PENDING_FINANCE_REVIEW /
     * FINANCE_REJECTED) 或 CANCELLED. 仅对 CUSTOMER_ORDER 源生效; SAFETY_STOCK 等非客户订单源无 SO, 不经过此校验.
     *
     * <p>仅约束新建计划, 不回溯历史数据 (无 DB 约束).
     *
     * @param so         已校验属于本工厂的销售订单
     * @param hintTarget 防呆提示定位字段 (sourceOrderId 或 sourceOrderItemId)
     */
    private void assertSalesOrderFinanceApproved(SalesOrder so, String hintTarget) {
        SalesOrderStatus status = so.getStatus();
        boolean approved = status == SalesOrderStatus.FINANCE_APPROVED
                || status == SalesOrderStatus.PROCESSING
                || status == SalesOrderStatus.PARTIAL_DELIVERED
                || status == SalesOrderStatus.COMPLETED;
        if (!approved) {
            String statusLabel = status != null ? status.getDisplayName() : "未知";
            throw new BusinessException(409,
                    "该销售订单未通过财务审核 (当前状态: " + statusLabel + "), 无法排产")
                    .withHint("请先提交财审, 待财务审核通过后再创建生产计划")
                    .withHintTarget(hintTarget);
        }
    }

    /**
     * SP5 多 SO 合并工单: 规范化并校验 sourceOrderIds.
     *
     * <p>规则:
     * <ol>
     *   <li>若 request.sourceOrderIds 为 null/空 → 用 plan.sourceOrderId (单 SO 场景向后兼容) 填充。</li>
     *   <li>若 plan.sourceOrderId 不在列表中 → 自动追加 (保证列表包含主 SO)。</li>
     *   <li>每个追加的 SO (超出主 sourceOrderId 的) 校验: 属于本工厂 + 已财审。</li>
     *   <li>列表去重 (相同 ID 多次出现)。</li>
     * </ol>
     *
     * <p>只对 CUSTOMER_ORDER 场景执行额外 SO 校验; MANUAL/AI_FORECAST 等源不传 sourceOrderIds.
     * 此方法在 plan entity 已赋值但尚未 save 时调用 — 直接写 plan.sourceOrderIds。
     *
     * @param factoryId 工厂 ID (用于 SO 归属校验)
     * @param plan      已由 mapper 赋值的 entity (未 save)
     * @param request   原始请求 (用于读取 sourceOrderIds / sourceOrderId)
     */
    private void normalizeAndValidateSourceOrderIds(String factoryId, ProductionPlan plan,
            com.cretas.aims.dto.production.CreateProductionPlanRequest request) {
        java.util.List<String> ids = plan.getSourceOrderIds() != null
                ? new java.util.ArrayList<>(plan.getSourceOrderIds())
                : new java.util.ArrayList<>();

        // 单 SO 向后兼容: 若列表为空, 用 sourceOrderId 初始化
        String primarySoId = plan.getSourceOrderId();
        if (ids.isEmpty() && primarySoId != null && !primarySoId.isBlank()) {
            ids.add(primarySoId);
        }

        // 若 primarySoId 不在列表中, 补进去
        if (primarySoId != null && !primarySoId.isBlank() && !ids.contains(primarySoId)) {
            ids.add(0, primarySoId);  // 保持主 SO 在首位
        }

        // 去重保持顺序
        java.util.List<String> deduped = ids.stream()
                .distinct()
                .collect(java.util.stream.Collectors.toList());

        // 对超出主 SO 的额外 SO 做校验 (主 SO 在 validateAndEnrichSalesOrderSource 里已验过)
        if (deduped.size() > 1) {
            for (int i = 1; i < deduped.size(); i++) {
                String extraSoId = deduped.get(i);
                if (extraSoId == null || extraSoId.isBlank()) continue;
                SalesOrder so = salesOrderRepository.findById(extraSoId)
                        .orElseThrow(() -> new BusinessException(404,
                                "追加的销售订单不存在: " + extraSoId)
                                .withHint("请刷新销售订单列表后重新选择")
                                .withHintTarget("sourceOrderIds"));
                if (!factoryId.equals(so.getFactoryId())) {
                    throw new BusinessException(403,
                            "追加的销售订单不属于该工厂: " + extraSoId)
                            .withHint("只能合并本工厂的销售订单")
                            .withHintTarget("sourceOrderIds");
                }
                BusinessRowScope.requireOwner(businessRowScope, factoryId, so.getCreatedBy());
                assertSalesOrderFinanceApproved(so, "sourceOrderIds");
                assertCompatibleOwnershipContract(plan, so, extraSoId);
            }
        }

        plan.setSourceOrderIds(deduped);
        log.debug("SP5 normalizeSourceOrderIds: planId={}, sourceOrderIds={}", plan.getId(), deduped);
    }

    private void assertCompatibleOwnershipContract(ProductionPlan plan,
                                                   SalesOrder salesOrder,
                                                   String salesOrderId) {
        InventoryOwnership extraOutputOwnership = salesOrder.getProcessingMode() == null
                ? null
                : salesOrder.getProcessingMode() == SalesProcessingMode.TOLL_PROCESSING
                        ? InventoryOwnership.CUSTOMER_OWNED
                        : InventoryOwnership.COMPANY_OWNED;
        boolean compatible = Objects.equals(plan.getCustomerId(), salesOrder.getCustomerId())
                && plan.getProcessingMode() == salesOrder.getProcessingMode()
                && plan.getMaterialSupplyMode() == salesOrder.getMaterialSupplyMode()
                && plan.getOutputOwnership() == extraOutputOwnership;
        if (!compatible) {
            throw new BusinessException(409,
                    "合并生产计划中的销售订单客户或加工/供料合同不一致: " + salesOrderId)
                    .withCode("MERGED_SALES_OWNERSHIP_CONTRACT_MISMATCH")
                    .withHint("同一生产计划只能合并客户和库存归属合同完全一致的销售订单")
                    .withHintTarget("sourceOrderIds");
        }
    }

    @Override
    @Transactional
    public ProductionPlanDTO createProductionPlan(String factoryId, CreateProductionPlanRequest request, Long userId) {
        BusinessRowScope.requireOwner(businessRowScope, factoryId, userId);
        if (request.getSourceType() == null) {
            request.setSourceType(PlanSourceType.MANUAL);
        }
        String clientRequestId = trimToNull(request.getClientRequestId());
        request.setClientRequestId(clientRequestId);
        if (clientRequestId != null) {
            /*
             * 同一工厂内串行化“查幂等键 → 创建”。数据库唯一索引仍是最后防线；
             * 工厂行锁让并发双击不会把唯一冲突传播成一次 500。
             */
            if (entityManager != null) {
                entityManager.find(com.cretas.aims.entity.Factory.class, factoryId,
                        jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            }
            Optional<ProductionPlan> existing = productionPlanRepository
                    .findByFactoryIdAndCreatedByAndClientRequestId(factoryId, userId, clientRequestId);
            if (existing.isPresent()) {
                log.info("生产计划创建幂等命中: factoryId={}, userId={}, clientRequestId={}, planId={}",
                        factoryId, userId, clientRequestId, existing.get().getId());
                return toDTOWithConversionInfo(existing.get());
            }
        }

        // Build validation context including Canvas V3 custom fields (e.g. cf_tank_id)
        // so SpEL rules like '#cf_tank_id != null' can evaluate correctly.
        java.util.Map<String, Object> validationCtx = new java.util.HashMap<>();
        validationCtx.put("plannedQuantity", request.getPlannedQuantity() != null ? request.getPlannedQuantity() : java.math.BigDecimal.ZERO);
        validationCtx.put("productTypeId", request.getProductTypeId() != null ? request.getProductTypeId() : "");
        validationCtx.put("status", "DRAFT");
        // Merge custom fields into context so SpEL rules can reference cf_* variables
        if (request.getCustomFields() != null) {
            for (var entry : request.getCustomFields().entrySet()) {
                validationCtx.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
            }
        }
        runConfiguredValidation(factoryId, "CREATE", validationCtx);
        // 验证产品类型是否存在
        if (!productTypeRepository.existsByIdAndFactoryId(request.getProductTypeId(), factoryId)) {
            throw new ResourceNotFoundException("产品类型不存在");
        }

        // P0-12: 校验销售订单来源 + 回填客户名
        validateAndEnrichSalesOrderSource(factoryId, request);

        // P1-0: 存货生产(SAFETY_STOCK) 计划数量非必填; 其他来源类型必须大于 0
        if (request.getSourceType() != PlanSourceType.SAFETY_STOCK) {
            if (request.getPlannedQuantity() == null
                    || request.getPlannedQuantity().compareTo(new java.math.BigDecimal("0.01")) < 0) {
                throw new BusinessException(400, "计划数量不能为空")
                        .withHint("请输入计划数量").withHintTarget("plannedQuantity");
            }
        } else if (request.getPlannedQuantity() == null) {
            // 存货生产无计划数量要求; 但 production_plans.planned_quantity 列在 prod 是 NOT NULL,
            // 故存 0 (下游一律按 ≤0 视为"无计划": zeroIfNull / B3 库存校验跳过, 语义正确)。
            request.setPlannedQuantity(java.math.BigDecimal.ZERO);
        }

        if (!Boolean.TRUE.equals(request.getConfirmPotentialDuplicate())) {
            productionPlanRepository
                    .findTopByFactoryIdAndProductTypeIdAndSourceTypeAndPlannedDateAndPlannedQuantityAndCreatedAtAfterOrderByCreatedAtDesc(
                            factoryId,
                            request.getProductTypeId(),
                            request.getSourceType(),
                            request.getPlannedDate(),
                            request.getPlannedQuantity(),
                            LocalDateTime.now().minusMinutes(5))
                    .ifPresent(candidate -> {
                        throw new BusinessException(409, "检测到刚刚创建过内容相同的生产计划")
                                .withCode("POTENTIAL_DUPLICATE_PRODUCTION_PLAN")
                                .withHint("已有计划 " + candidate.getPlanNumber()
                                        + "。若确实需要另一张独立计划，请确认后重新提交")
                                .withHintTarget("confirmPotentialDuplicate");
                    });
        }

        // P1-4: 客户订单来源必须填写工序名称和批次日期
        if (request.getSourceType() == PlanSourceType.CUSTOMER_ORDER) {
            if (request.getProcessName() == null || request.getProcessName().isBlank()) {
                throw new BusinessException(400, "客户订单来源的生产计划必须填写工序名称")
                        .withHint("请填写工序名称").withHintTarget("processName");
            }
            if (request.getBatchDate() == null) {
                throw new BusinessException(400, "客户订单来源的生产计划必须填写批次日期")
                        .withHint("请选择批次日期").withHintTarget("batchDate");
            }
        }

        // Resolve by selected terminal outputs, not by the legacy product_type_id anchor.
        // The resolved exact workflow/version is persisted on the plan and later copied to its batch.
        // failClosed=false: 这里只解析, 不抛。工艺要求排在下面的 SO 归属/跨厂校验之后,
        // 否则越权请求会先收到「该产品尚未配置生产工艺」而不是 403。
        PlanUnitAuthority planUnitAuthority = resolvePlanUnitAuthority(
                factoryId, request.getProductTypeId(), request.getTargetFinishedGoodIds(),
                request.getSelectedWorkflowId(), request.getSelectedWorkflowVersion(), false);
        if (planUnitAuthority != null
                && planUnitAuthority.mode() == ProductionBatch.WorkflowSelectionMode.WORKFLOW) {
            if (Boolean.TRUE.equals(request.getSkipProcessReporting())) {
                throw new BusinessException(400, "Workflow 生产计划必须使用逐道报工")
                        .withCode("WORKFLOW_PLAN_REQUIRES_STEPWISE")
                        .withHint("请把报工模式切换为「逐道报工」后再创建")
                        .withHintTarget("skipProcessReporting");
            }
            // Workflow-backed plans always materialize stepwise tasks.
            request.setSkipProcessReporting(Boolean.FALSE);
        }

        // Fable 审计修复 (2026-06-11 — 多租户安全红线):
        //   免工序报工开关 skipProcessReporting=null (调用方未显式指定, 如 RN/AI 客户端)
        //   → 解析为"该工厂的默认值" (F006=true 两点, 其他工厂=false 逐道), 而非旧的"全系统 null→true"。
        //   显式 true/false 一律尊重调用方 (web 开关传显式值)。
        //   这样其他工厂 (逐道是其溯源/成本/出成率/自学习/人效价值) 不被静默改成两点。
        if (request.getSkipProcessReporting() == null) {
            request.setSkipProcessReporting(resolveSkipProcessReportingDefault(factoryId));
        }

        // plannedUnit 由下面的 applyPlanUnitAuthority 统一赋值(工艺闸通过之后)。

        // 创建生产计划
        ProductionPlan plan = productionPlanMapper.toEntity(request, factoryId, userId.longValue());

        // SP5 多 SO 合并: 规范化 sourceOrderIds — 确保 sourceOrderId 也在列表中,
        // 并校验追加的每个 SO 属于本工厂且已财审 (向后兼容: 单 SO 场景 sourceOrderIds 为空时自动补填)。
        normalizeAndValidateSourceOrderIds(factoryId, plan, request);

        // ⛔ 工艺闸放在这里 —— 鉴权(403)与归属契约校验跑完之后, 才轮到「有没有工艺」。
        if (planUnitAuthority == null) {
            throw workflowRequired();
        }
        applyPlanUnitAuthority(plan, planUnitAuthority);

        plan = productionPlanRepository.save(plan);

        // Sprint 3 Track-F (C-LINKARRAY-1): unified BusinessLink double-write.
        // ProductionPlan.sourceOrderId stays for backward compat; new code reads via
        // LinkArrayService.getOutboundLinks(PRODUCTION_PLAN, id).
        // SP5: 额外的 SO (合并场景) 也写入 LinkArray, 保证双向检索一致。
        if (linkArrayService != null) {
            final String planIdForLink = plan.getId();
            final String userIdStr = userId != null ? userId.toString() : null;
            java.util.List<String> allSourceOrderIds = plan.getSourceOrderIds() != null
                    ? plan.getSourceOrderIds() : java.util.Collections.emptyList();
            for (String soId : allSourceOrderIds) {
                try {
                    linkArrayService.link(factoryId,
                            "PRODUCTION_PLAN", planIdForLink,
                            "sale",
                            "SALES_ORDER", soId,
                            "生产源单", userIdStr);
                } catch (Exception e) {
                    log.warn("BusinessLink double-write failed for production plan {} → SO {}: {}",
                            planIdForLink, soId, e.getMessage());
                }
            }
        }

        // Round 9 Fix (R8-α Gap #3 per-module template): persist Canvas V3 dynamic fields.
        // Customer-configured fields like 客户订单号, QC 等级, 特殊工艺参数, 成品包装要求
        // now land in the cf_* columns of production_plans. Previously silently dropped.
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "production_plan", plan.getId(), request.getCustomFields());
            } catch (Exception e) {
                log.warn("Canvas dynamic fields save failed for production plan {}: {}", plan.getId(), e.getMessage());
            }
        }

        // 如果指定了原材料批次，创建关联
        if (request.getMaterialBatchIds() != null && request.getMaterialBatchIds().length > 0) {
            assignMaterialBatchesToPlan(plan, Arrays.asList(request.getMaterialBatchIds()));
        }

        log.info("创建生产计划成功: planNumber={}", plan.getPlanNumber());

        // 触发自动排产（在事务提交后异步执行，确保数据已持久化）
        final String finalPlanId = plan.getId();
        final String finalPlanNumber = plan.getPlanNumber();
        final String finalFactoryId = factoryId;
        final Long finalUserId = userId;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    schedulingService.onProductionPlanCreated(finalFactoryId, finalPlanId, finalPlanNumber, finalUserId);
                    log.debug("已触发自动排产（事务提交后）: planId={}", finalPlanId);
                } catch (Exception e) {
                    // 自动排产失败不影响计划创建
                    log.error("触发自动排产失败: planId={}, error={}", finalPlanId, e.getMessage());
                }
            }
        });

        return toDTOWithConversionInfo(plan);
    }

    /**
     * 以销定产批量建计划 (六扇门 2026-06-24): 选 SO 多个产品行, 各建一张计划。
     * 产品+数量后端权威解析自 SalesOrderItem (不信前端传值); @Transactional 原子。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public java.util.List<ProductionPlanDTO> createPlansFromSalesOrder(
            String factoryId, com.cretas.aims.dto.production.BatchPlanFromSalesOrderRequest req, Long userId) {
        // 跨租户校验: SO 须属当前工厂
        // Lock the SO row so two tabs cannot both observe "no plan" and create
        // duplicate effective plans for the same order line.
        SalesOrder so = salesOrderRepository.findByIdAndFactoryIdForUpdate(req.getSourceOrderId(), factoryId)
                .orElseThrow(() -> new com.cretas.aims.exception.EntityNotFoundException(
                        "销售订单不存在或不属于当前工厂: " + req.getSourceOrderId()));
        BusinessRowScope.requireOwner(businessRowScope, factoryId, so.getCreatedBy());

        java.util.List<ProductionPlan> relatedPlans = new java.util.ArrayList<>(
                productionPlanRepository.findByFactoryIdAndSourceOrderIdExact(factoryId, so.getId()));
        productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(
                        factoryId, "[\"" + so.getId().replace("\"", "\\\"") + "\"]")
                .stream().filter(plan -> relatedPlans.stream().noneMatch(existing -> existing.getId().equals(plan.getId())))
                .forEach(relatedPlans::add);
        java.util.Map<String, BigDecimal> effectivePlannedByItem = relatedPlans.stream()
                .filter(plan -> plan.getStatus() != ProductionPlanStatus.CANCELLED)
                .filter(plan -> plan.getSourceOrderItemId() != null && !plan.getSourceOrderItemId().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(
                        ProductionPlan::getSourceOrderItemId,
                        java.util.stream.Collectors.reducing(
                                BigDecimal.ZERO,
                                plan -> plan.getSourceDisplayQuantity() != null
                                        ? plan.getSourceDisplayQuantity()
                                        : (plan.getPlannedQuantity() != null ? plan.getPlannedQuantity() : BigDecimal.ZERO),
                                BigDecimal::add)));

        // SO 行 (权威来源): 按 id 索引, 只接受属于本 SO 的行
        java.util.Map<String, SalesOrderItem> itemById = new java.util.HashMap<>();
        for (SalesOrderItem it : salesOrderItemRepository.findBySalesOrderId(so.getId())) {
            itemById.put(String.valueOf(it.getId()), it);
        }

        // ── SP5 合并订单 (2026-08-19, 测试同学 8-03「合并订单无法合并数量」) ───────────────
        // 追加订单按【产品类型】与主订单行配对: 主 10 + 关联 5 → 一张计划 15。
        //
        // ⚠️ 硬约束 8: remaining = ordered - delivered - alreadyPlanned 跨 SO 之后
        //    **每一项口径都变了**, 逐项确认过 ——
        //    · ordered/delivered: 必须逐行取各自 SO 行的值, ⛔ 不能拿主行的乘个数;
        //      而且各行单位可能不同(箱/盒/kg), 所以**先各自归一到成品基本单位再相加**,
        //      ⛔ 不能把原始数量相加。
        //    · alreadyPlanned: 现有 map 以 sourceOrderItemId 为键, 而合并计划只存得下
        //      主行一个 itemId ⇒ 追加订单的行在这张 map 里查不到。补一条 SO 级判据:
        //      某个 SO 一旦被某张有效计划以【同产品类型】合并过, 它那一行就是**整行被吃掉**了
        //      (合并永远吃满剩余量), 再次合并要 fail-loud, 不能静默重复排产。
        java.util.List<String> extraOrderIds = req.getExtraSourceOrderIds() == null
                ? java.util.List.of()
                : req.getExtraSourceOrderIds().stream()
                        .filter(id -> id != null && !id.isBlank())
                        .filter(id -> !id.equals(so.getId()))   // 主订单不重复计
                        .distinct()
                        .collect(java.util.stream.Collectors.toList());

        // extraSoId -> (productTypeId -> 该 SO 下该产品的行)
        java.util.Map<String, java.util.List<SalesOrderItem>> extraItemsByOrder = new java.util.LinkedHashMap<>();
        java.util.Map<String, SalesOrder> extraOrderById = new java.util.LinkedHashMap<>();
        for (String extraId : extraOrderIds) {
            SalesOrder extraSo = salesOrderRepository.findByIdAndFactoryIdForUpdate(extraId, factoryId)
                    .orElseThrow(() -> new com.cretas.aims.exception.BusinessException(400,
                            "合并的销售订单不存在或不属于当前工厂: " + extraId)
                            .withHintTarget("extraSourceOrderIds"));
            BusinessRowScope.requireOwner(businessRowScope, factoryId, extraSo.getCreatedBy());
            assertSalesOrderFinanceApproved(extraSo, "extraSourceOrderIds");
            extraOrderById.put(extraId, extraSo);
            extraItemsByOrder.put(extraId,
                    new java.util.ArrayList<>(salesOrderItemRepository.findBySalesOrderId(extraId)));
        }

        java.util.List<ProductionPlanDTO> created = new java.util.ArrayList<>();
        for (String itemId : req.getItemIds()) {
            String itemClientRequestId = batchItemClientRequestId(req.getClientRequestId(), itemId);
            Optional<ProductionPlan> idempotentPlan = productionPlanRepository
                    .findByFactoryIdAndCreatedByAndClientRequestId(factoryId, userId, itemClientRequestId);
            if (idempotentPlan.isPresent()) {
                created.add(toDTOWithConversionInfo(idempotentPlan.get()));
                continue;
            }
            SalesOrderItem item = itemById.get(itemId);
            if (item == null) {
                throw new com.cretas.aims.exception.BusinessException(400,
                        "产品行不属于该销售订单: " + itemId)
                        .withHint("请刷新后重新选择产品行");
            }
            if (item.getProductTypeId() == null) {
                throw new com.cretas.aims.exception.BusinessException(400,
                        "产品行缺少产品类型, 无法生成计划: " + (item.getProductName() != null ? item.getProductName() : itemId));
            }
            // reviewer Issue4: 计划数量取"未交付剩余量"(订单量 - 已交付), 避免对已部分交付的 SO 过量生产。
            // 转录"据销售领用量算出需领量"。全部已交付 → 拒绝该行 (防呆: 提示取消)。
            BigDecimal ordered = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            BigDecimal delivered = item.getDeliveredQuantity() != null ? item.getDeliveredQuantity() : BigDecimal.ZERO;
            BigDecimal alreadyPlanned = effectivePlannedByItem.getOrDefault(itemId, BigDecimal.ZERO);
            BigDecimal remaining = ordered.subtract(delivered).subtract(alreadyPlanned);
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                throw new com.cretas.aims.exception.BusinessException(409,
                        "产品行已被有效生产计划完整覆盖: " + (item.getProductName() != null ? item.getProductName() : itemId))
                        .withCode("SALES_ORDER_ITEM_PLAN_ALREADY_EXISTS")
                        .withHint("请查看现有生产计划；已取消计划不占用生产数量")
                        .withHintTarget("sourceOrderItemId");
            }
            ProductType product = productTypeRepository
                    .findByIdAndFactoryId(item.getProductTypeId(), factoryId)
                    .orElseThrow(() -> new com.cretas.aims.exception.EntityNotFoundException(
                            "产品类型不存在或不属于当前工厂: " + item.getProductTypeId()));
            if (salesOrderPlanQuantityNormalizer == null) {
                throw new BusinessException(500, "销售订单单位换算服务未初始化")
                        .withCode("SALES_PLAN_NORMALIZER_UNAVAILABLE");
            }
            var normalizedPlanQuantity = salesOrderPlanQuantityNormalizer.normalize(remaining, item, product);

            // ── 合并追加订单里同产品类型的行 ──────────────────────────────────────────
            // 各行单位可能不同 ⇒ 逐行归一到成品基本单位后相加 (⛔ 不相加原始数量)。
            BigDecimal mergedBaseQuantity = normalizedPlanQuantity.quantity();
            java.util.List<String> contributingOrderIds = new java.util.ArrayList<>();
            contributingOrderIds.add(so.getId());
            for (java.util.Map.Entry<String, java.util.List<SalesOrderItem>> e : extraItemsByOrder.entrySet()) {
                String extraSoId = e.getKey();
                boolean matchedAnyLine = false;
                for (SalesOrderItem extraItem : e.getValue()) {
                    if (!item.getProductTypeId().equals(extraItem.getProductTypeId())) {
                        continue;
                    }
                    matchedAnyLine = true;
                    // 这一行是否已被别的计划吃掉? 两条判据都要过。
                    String extraItemId = String.valueOf(extraItem.getId());
                    BigDecimal extraOrdered = extraItem.getQuantity() != null
                            ? extraItem.getQuantity() : BigDecimal.ZERO;
                    BigDecimal extraDelivered = extraItem.getDeliveredQuantity() != null
                            ? extraItem.getDeliveredQuantity() : BigDecimal.ZERO;
                    BigDecimal extraPlanned = plannedQuantityForSalesOrderItem(
                            factoryId, extraSoId, extraItemId, item.getProductTypeId());
                    BigDecimal extraRemaining = extraOrdered.subtract(extraDelivered).subtract(extraPlanned);
                    if (extraRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new com.cretas.aims.exception.BusinessException(409,
                                "合并的销售订单产品行已被有效生产计划覆盖: "
                                        + (extraOrderById.get(extraSoId) != null
                                            ? extraOrderById.get(extraSoId).getOrderNumber() : extraSoId)
                                        + " - " + (extraItem.getProductName() != null
                                            ? extraItem.getProductName() : extraItemId))
                                .withCode("MERGED_SALES_ORDER_ITEM_PLAN_ALREADY_EXISTS")
                                .withHint("请从合并列表中移除该订单，或先取消覆盖它的生产计划")
                                .withHintTarget("extraSourceOrderIds");
                    }
                    var extraNormalized = salesOrderPlanQuantityNormalizer
                            .normalize(extraRemaining, extraItem, product);
                    mergedBaseQuantity = mergedBaseQuantity.add(extraNormalized.quantity());
                }
                if (!matchedAnyLine) {
                    // 防呆: 用户以为合并了, 其实那张订单里没有这个产品 —— 明说, 别静默忽略。
                    throw new com.cretas.aims.exception.BusinessException(400,
                            "合并的销售订单里没有该产品: "
                                    + (extraOrderById.get(extraSoId) != null
                                        ? extraOrderById.get(extraSoId).getOrderNumber() : extraSoId)
                                    + " - " + (item.getProductName() != null ? item.getProductName() : itemId))
                            .withCode("MERGED_SALES_ORDER_PRODUCT_NOT_FOUND")
                            .withHint("合并订单需与主订单包含相同的产品；请核对后重新选择")
                            .withHintTarget("extraSourceOrderIds");
                }
                contributingOrderIds.add(extraSoId);
            }
            // CUSTOMER_ORDER 来源在 createProductionPlan 强制要求 processName + batchDate。
            // 批量路径补齐 processName；batchDate 是实际转批次日，必须与计划生产日独立保存。
            String processName = "两点报工";
            if (productWorkProcessService != null) {
                try {
                    java.util.List<String> names = productWorkProcessService
                            .listByProduct(factoryId, item.getProductTypeId()).stream()
                            .map(com.cretas.aims.dto.ProductWorkProcessDTO::getProcessName)
                            .filter(n -> n != null && !n.isBlank())
                            .distinct().collect(java.util.stream.Collectors.toList());
                    if (!names.isEmpty()) processName = String.join("、", names);
                } catch (Exception e) {
                    log.warn("以销定产取工序名失败, 用默认两点报工: productTypeId={}, err={}",
                            item.getProductTypeId(), e.getMessage());
                }
            }
            CreateProductionPlanRequest one = new CreateProductionPlanRequest();
            // 每行各自的产品 + 数量 (权威自 SO 行)
            one.setProductTypeId(item.getProductTypeId());
            one.setPlannedQuantity(mergedBaseQuantity);
            one.setPlannedUnit(normalizedPlanQuantity.unit());
            // ⚠️ sourceDisplayQuantity 保持【主订单行】的显示量与显示单位不变 ——
            //    effectivePlannedByItem 拿它跟主 SO 行的下单量做同口径相减, 换成合并后的
            //    总量会让主行的「已排产」虚高 (硬约束 8: 只改求和会连累别的项)。
            //    追加订单那边的重复排产由上面 plannedQuantityForSalesOrderItem 那条判据挡。
            one.setSourceDisplayQuantity(normalizedPlanQuantity.displayQuantity());
            one.setSourceDisplayUnit(normalizedPlanQuantity.displayUnit());
            one.setSourceType(PlanSourceType.CUSTOMER_ORDER);
            one.setSourceOrderId(so.getId());
            one.setSourceOrderItemId(itemId);
            // 合并来源全部记下来 —— 计划详情的「合并订单」区、单据追溯、以及销售订单侧
            // 「生产中」那个**派生**标签都靠它 (⛔ 不给 SalesOrder 硬塞 PROCESSING 状态)。
            one.setSourceOrderIds(contributingOrderIds);
            one.setSourceCustomerName(so.getCustomerName());
            one.setCustomerOrderNumber(so.getOrderNumber());
            one.setProcessName(processName);
            one.setBatchDate(req.getBatchDate());
            // 计划级共享设置
            one.setPlannedDate(req.getPlannedDate());
            one.setExpectedCompletionDate(req.getExpectedCompletionDate());
            one.setEstimatedWorkers(req.getEstimatedWorkers());
            one.setAssignedSupervisorId(req.getAssignedSupervisorId());
            one.setNotes(req.getNotes());
            one.setSkipProcessReporting(req.getSkipProcessReporting());
            one.setClientRequestId(itemClientRequestId);
            one.setConfirmPotentialDuplicate(req.getConfirmPotentialDuplicate());
            // 注 (reviewer Issue2): createProductionPlan 走 REQUIRED 传播参与本外层事务, 其 afterCommit
            // 自动排程在外层唯一一次 commit 后逐 plan 触发 (回滚则全不触发) — 原子语义正确。
            created.add(createProductionPlan(factoryId, one, userId));
        }
        log.info("以销定产批量建计划: factoryId={}, soId={}, 产品行={}, 创建计划={}",
                factoryId, so.getId(), req.getItemIds().size(), created.size());
        return created;
    }

    /**
     * 追加(被合并)销售订单某一行的「已排产量」。
     *
     * <p>⚠️ 为什么不能直接复用 {@code effectivePlannedByItem}: 那张 map 以
     * {@code sourceOrderItemId} 为键, 而一张**合并计划只存得下主订单行的那一个 itemId** ——
     * 被合并进来的行在里面查不到, 于是会被读成「从没排过产」, 第二次合并就重复排产。
     *
     * <p>两条判据取较严的:
     * <ol>
     *   <li>精确命中 {@code sourceOrderItemId} —— 覆盖非合并计划(每行一张计划)。</li>
     *   <li>该 SO 出现在某张有效计划的 {@code sourceOrderIds} 里, 且那张计划就是这个产品 ——
     *       说明它已被合并吃过。合并**永远吃满剩余量**, 所以这一行按【整行已排满】算,
     *       即返回 ordered - delivered, 让上游的 remaining 归零并 fail-loud。</li>
     * </ol>
     *
     * @return 已排产量 (与该行下单量同口径, 即该 SO 行自己的显示单位)
     */
    private BigDecimal plannedQuantityForSalesOrderItem(
            String factoryId, String salesOrderId, String salesOrderItemId, String productTypeId) {
        java.util.List<ProductionPlan> plans = new java.util.ArrayList<>(
                productionPlanRepository.findByFactoryIdAndSourceOrderIdExact(factoryId, salesOrderId));
        productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(
                        factoryId, "[\"" + salesOrderId.replace("\"", "\\\"") + "\"]")
                .stream()
                .filter(plan -> plans.stream().noneMatch(existing -> existing.getId().equals(plan.getId())))
                .forEach(plans::add);

        BigDecimal exact = BigDecimal.ZERO;
        boolean consumedByMerge = false;
        for (ProductionPlan plan : plans) {
            if (plan.getStatus() == ProductionPlanStatus.CANCELLED) {
                continue;
            }
            if (salesOrderItemId.equals(plan.getSourceOrderItemId())) {
                BigDecimal q = plan.getSourceDisplayQuantity() != null
                        ? plan.getSourceDisplayQuantity()
                        : (plan.getPlannedQuantity() != null ? plan.getPlannedQuantity() : BigDecimal.ZERO);
                exact = exact.add(q);
                continue;
            }
            // 判据 2: 这张计划把本 SO 当作【被合并方】吃进去了 (它的主行属于别的 SO)。
            boolean mergedThisOrder = plan.getSourceOrderIds() != null
                    && plan.getSourceOrderIds().contains(salesOrderId)
                    && !salesOrderId.equals(plan.getSourceOrderId())
                    && productTypeId != null
                    && productTypeId.equals(plan.getProductTypeId());
            if (mergedThisOrder) {
                consumedByMerge = true;
            }
        }
        if (consumedByMerge) {
            // 整行已被合并吃满 —— 返回一个必然让 remaining <= 0 的值。
            return new BigDecimal("1E+18");
        }
        return exact;
    }

    private String batchItemClientRequestId(String batchClientRequestId, String salesOrderItemId) {
        String stableInput = batchClientRequestId + ":" + salesOrderItemId;
        return "so-item:" + UUID.nameUUIDFromBytes(
                stableInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public ProductionPlanDTO repairSalesPlanBatchDate(
            String factoryId, String planId, LocalDate expectedCurrentBatchDate, LocalDate targetBatchDate) {
        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(planId)
                .filter(p -> factoryId.equals(p.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        if (plan.getSourceType() != PlanSourceType.CUSTOMER_ORDER
                || plan.getStatus() != ProductionPlanStatus.PENDING) {
            throw new BusinessException(409, "仅允许修复尚未开工的销售来源计划")
                    .withCode("PLAN_BATCH_DATE_REPAIR_STATUS_INVALID")
                    .withHint("请刷新计划状态；已开工计划必须走正式业务更正流程");
        }
        if (!productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId).isEmpty()
                || (processSheetRowRepository != null
                    && !processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId).isEmpty())) {
            throw new BusinessException(409, "计划已有批次或报工，拒绝修改批次日期")
                    .withCode("PLAN_BATCH_DATE_REPAIR_ALREADY_STARTED")
                    .withHint("不得通过历史修复接口改写已开始生产的计划");
        }

        LocalDate current = plan.getBatchDate();
        if (targetBatchDate.equals(current)) {
            return productionPlanMapper.toDTO(plan);
        }
        // 该桥接只修复旧 batch-from-so 把 plannedDate 误写进 batchDate 的已知形态。
        // 一旦已校正为独立日期，除相同目标重放外，任何再次改写都必须走正式业务更正流程。
        if (!java.util.Objects.equals(current, plan.getPlannedDate())) {
            throw new BusinessException(409, "计划批次日期已完成历史校正，拒绝再次覆盖")
                    .withCode("PLAN_BATCH_DATE_REPAIR_ALREADY_CORRECTED")
                    .withHint("相同目标可安全重放；不同日期请走正式业务更正流程")
                    .withHintTarget("targetBatchDate");
        }
        if (!java.util.Objects.equals(expectedCurrentBatchDate, current)) {
            throw new BusinessException(409, "批次日期已变化，拒绝覆盖")
                    .withCode("PLAN_BATCH_DATE_REPAIR_CONFLICT")
                    .withHint("请重新读取计划后核对批次日期")
                    .withHintTarget("expectedCurrentBatchDate");
        }
        if (plan.getPlannedDate() == null || targetBatchDate.isAfter(plan.getPlannedDate())) {
            throw new BusinessException(400, "批次日期不能晚于计划生产日")
                    .withCode("PLAN_BATCH_DATE_INVALID")
                    .withHintTarget("targetBatchDate");
        }

        plan.setBatchDate(targetBatchDate);
        ProductionPlan saved = productionPlanRepository.saveAndFlush(plan);
        return productionPlanMapper.toDTO(saved);
    }

    /**
     * M-PREP-1 (Sprint 4 W2): 创建草稿态生产计划 — status=PREPARED.
     *
     * <p>调用 {@link #createProductionPlan} 走完正常创建流程后,
     * 把 status 从 PENDING 翻成 PREPARED 并保存。
     * 这样 validation / sales-order-source 校验等业务逻辑全部复用。</p>
     */
    @Override
    @Transactional
    public ProductionPlanDTO createDraftProductionPlan(String factoryId, CreateProductionPlanRequest request, Long userId) {
        ProductionPlanDTO created = createProductionPlan(factoryId, request, userId);
        ProductionPlan plan = productionPlanRepository.findById(created.getId())
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", created.getId()));
        plan.setStatus(ProductionPlanStatus.PREPARED);
        plan = productionPlanRepository.save(plan);
        log.info("[M-PREP-1] 创建草稿生产计划: planId={}, planNumber={}", plan.getId(), plan.getPlanNumber());
        return toDTOWithConversionInfo(plan);
    }

    /**
     * M-PREP-1 (Sprint 4 W2): 提交草稿态生产计划 — PREPARED → PENDING.
     */
    @Override
    @Transactional
    public ProductionPlanDTO commitDraftProductionPlan(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }
        if (plan.getStatus() != ProductionPlanStatus.PREPARED) {
            throw new BusinessException(409, "只能提交草稿态 (PREPARED) 的生产计划, 当前状态: " + plan.getStatus())
                    .withHint("请刷新生产计划列表查看最新状态");
        }
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan = productionPlanRepository.save(plan);
        log.info("[M-PREP-1] 提交草稿生产计划: planId={}, planNumber={}", plan.getId(), plan.getPlanNumber());
        return toDTOWithConversionInfo(plan);
    }

    /**
     * M-DELIVERY-WARN-1 (Sprint 4 W2): 获取交货预警列表.
     *
     * <p>查询 expectedCompletionDate &lt; today + windowDays 且状态非 COMPLETED/CANCELLED
     * 的生产计划, 然后按距交期天数分级 (OVERDUE / URGENT / WARN / NORMAL)。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<DeliveryWarnDTO> getDeliveryWarnings(String factoryId, int windowDays) {
        if (windowDays <= 0) windowDays = 7;  // 默认 7 天
        LocalDate today = LocalDate.now();
        LocalDate upperBound = today.plusDays(windowDays);
        Specification<ProductionPlan> warning = (root, query, cb) -> cb.and(
                cb.isNotNull(root.get("expectedCompletionDate")),
                cb.lessThan(root.get("expectedCompletionDate"), upperBound),
                cb.not(root.get("status").in(ProductionPlanStatus.COMPLETED, ProductionPlanStatus.CANCELLED)));
        List<ProductionPlan> plans = productionPlanRepository.findAll(
                planScope(factoryId, visiblePlanCreators(factoryId)).and(warning),
                Sort.by(Sort.Direction.ASC, "expectedCompletionDate"));
        return plans.stream()
                .map(p -> classifyDeliveryWarn(p, today))
                .collect(Collectors.toList());
    }

    /**
     * 计算单条生产计划的交货预警等级.
     * <ul>
     *   <li>OVERDUE: daysUntilDeadline &lt; 0 (已超期)</li>
     *   <li>URGENT:  0 &le; daysUntilDeadline &lt; 3</li>
     *   <li>WARN:    3 &le; daysUntilDeadline &lt; 7</li>
     *   <li>NORMAL:  daysUntilDeadline &ge; 7</li>
     * </ul>
     */
    private DeliveryWarnDTO classifyDeliveryWarn(ProductionPlan plan, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, plan.getExpectedCompletionDate());
        // 判据本体搬去 DeliveryWarnLevel —— 工序任务卡片的「超期」徽章读的是同一份。
        // ⛔ 不要在这里把四档再写一遍: 两份口径一定会漂, 而且漂了不报错。
        String level = DeliveryWarnLevel.ofDaysUntilDeadline(days).name();
        String productTypeName = null;
        try {
            if (plan.getProductType() != null) {
                productTypeName = plan.getProductType().getName();
            }
        } catch (Exception ignored) {
            // 容忍 lazy-load 失败 — productTypeName 为 null 由前端兜底
        }
        return DeliveryWarnDTO.builder()
                .planId(plan.getId())
                .planNumber(plan.getPlanNumber())
                .factoryId(plan.getFactoryId())
                .productTypeId(plan.getProductTypeId())
                .productTypeName(productTypeName)
                .plannedQuantity(plan.getPlannedQuantity())
                .actualQuantity(plan.getActualQuantity())
                .expectedCompletionDate(plan.getExpectedCompletionDate())
                .status(plan.getStatus() != null ? plan.getStatus().name() : null)
                .daysUntilDeadline(days)
                .warnLevel(level)
                .sourceCustomerName(plan.getSourceCustomerName())
                .build();
    }

    @Override
    @Transactional
    public ProductionPlanDTO updateProductionPlan(String factoryId, String planId, CreateProductionPlanRequest request) {
        // Build validation context including Canvas V3 custom fields for UPDATE too
        java.util.Map<String, Object> updateCtx = new java.util.HashMap<>();
        updateCtx.put("planId", planId);
        updateCtx.put("plannedQuantity", request.getPlannedQuantity() != null ? request.getPlannedQuantity() : java.math.BigDecimal.ZERO);
        updateCtx.put("productTypeId", request.getProductTypeId() != null ? request.getProductTypeId() : "");
        if (request.getCustomFields() != null) {
            for (var entry : request.getCustomFields().entrySet()) {
                updateCtx.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
            }
        }
        runConfiguredValidation(factoryId, "UPDATE", updateCtx);
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // M-PREP-1: 草稿态 (PREPARED) 也允许更新, 与 PENDING 相同
        if (plan.getStatus() != ProductionPlanStatus.PENDING
                && plan.getStatus() != ProductionPlanStatus.PREPARED) {
            throw new BusinessException(409, "只能修改待处理或草稿态的生产计划")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        // Issue #759: 锁定的计划不可编辑
        if (Boolean.TRUE.equals(plan.getIsLocked())) {
            throw new BusinessException(409, "生产计划已锁定, 不可编辑")
                    .withHint("先解锁该计划再尝试修改");
        }

        // P0-12: 校验销售订单来源 + 回填客户名
        validateAndEnrichSalesOrderSource(factoryId, request);

        boolean productChanged = request.getProductTypeId() != null
                && !request.getProductTypeId().equals(plan.getProductTypeId());
        boolean targetsChanged = request.getTargetFinishedGoodIds() != null
                && !sameTargetSelection(
                        request.getTargetFinishedGoodIds(), plan.getTargetFinishedGoodIds());
        PlanUnitAuthority updatedAuthority = null;
        if (productChanged || targetsChanged || plan.getPlannedUnit() == null
                || plan.getWorkflowSelectionMode() == null) {
            String effectiveProductId = request.getProductTypeId() != null
                    ? request.getProductTypeId() : plan.getProductTypeId();
            List<String> effectiveTargets = request.getTargetFinishedGoodIds() != null
                    ? request.getTargetFinishedGoodIds() : plan.getTargetFinishedGoodIds();
            updatedAuthority = resolvePlanUnitAuthority(factoryId, effectiveProductId, effectiveTargets);
            request.setPlannedUnit(updatedAuthority.unit());
        } else {
            request.setPlannedUnit(plan.getPlannedUnit());
        }

        // 更新计划信息
        productionPlanMapper.updateEntity(plan, request);
        if (updatedAuthority != null) applyPlanUnitAuthority(plan, updatedAuthority);
        plan = productionPlanRepository.save(plan);

        log.info("更新生产计划成功: planId={}", planId);
        return toDTOWithConversionInfo(plan);
    }

    /**
     * 已开工计划仍可改的排产元数据。见接口文档：这里的安全性来自**入参契约本身**
     * ({@link UpdatePlanScheduleMetaRequest} 只有四个软字段)，而不是靠这段代码克制。
     *
     * <p>允许集 = 非终态：PENDING / PREPARED / IN_PROGRESS / PAUSED。
     * COMPLETED / CANCELLED / PENDING_APPROVAL 一律 409 —— 已完工计划的排产元数据是
     * 人效与工时报表的历史事实，改它等于改账；审批中的计划正被工作流引用。
     */
    @Override
    @Transactional
    public ProductionPlanDTO updatePlanScheduleMeta(String factoryId, String planId,
            UpdatePlanScheduleMetaRequest request) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        ProductionPlanStatus status = plan.getStatus();
        if (status == ProductionPlanStatus.COMPLETED
                || status == ProductionPlanStatus.CANCELLED
                || status == ProductionPlanStatus.PENDING_APPROVAL) {
            throw new BusinessException(409, "已结束或审批中的生产计划不可修改排产信息")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        if (Boolean.TRUE.equals(plan.getIsLocked())) {
            throw new BusinessException(409, "生产计划已锁定, 不可编辑")
                    .withHint("先解锁该计划再尝试修改");
        }

        // 指派主管必须是同厂用户: 不校验的话任意 userId 都能写进来, 排产看板会显示别厂的人名
        // (enrichWithAssignmentNames 按 id 直接查 users 表, 不带 factory 过滤)。
        if (request.assignedSupervisorId() != null) {
            User supervisor = userRepository.findById(request.assignedSupervisorId())
                    .orElseThrow(() -> new BusinessException(400, "指派的主管不存在")
                            .withHint("请重新选择主管后再保存"));
            if (!factoryId.equals(supervisor.getFactoryId())) {
                throw new BusinessException(403, "指派的主管不属于当前工厂")
                        .withHint("只能指派本厂用户为主管");
            }
        }

        // PUT 语义: 四个字段全量覆盖, null = 清空 (见 DTO 文档)。
        plan.setExpectedCompletionDate(request.expectedCompletionDate());
        plan.setEstimatedWorkers(request.estimatedWorkers());
        plan.setAssignedSupervisorId(request.assignedSupervisorId());
        plan.setNotes(request.notes());
        plan = productionPlanRepository.save(plan);

        log.info("更新生产计划排产信息成功: planId={}, status={}", planId, status);
        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional
    public void deleteProductionPlan(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // M-PREP-1: 草稿态 (PREPARED) 也允许删除 (丢弃草稿)
        if (plan.getStatus() != ProductionPlanStatus.PENDING
                && plan.getStatus() != ProductionPlanStatus.PREPARED) {
            throw new BusinessException(409, "只能删除待处理或草稿态的生产计划")
                    .withHint("已开始或已完成的计划不可删除, 请取消代替");
        }

        productionPlanRepository.delete(plan);
        log.info("删除生产计划成功: planId={}", planId);
    }

    /**
     * 复制生产计划 — 见 {@link ProductionPlanService#copyProductionPlan} 文档.
     *
     * <p>实现说明:
     * <ul>
     *   <li>用 {@link ProductionPlanRepository#findById} + factory 隔离 (404 / 403).</li>
     *   <li>新计划 status = PENDING, createdBy = 当前用户, planNumber 重新生成.</li>
     *   <li>不走 {@link #createProductionPlan} — 跳过 SO source validation /
     *       Canvas validation / auto scheduling (复制场景假设源已通过).</li>
     *   <li>不复制 actualQuantity / actual*Cost / approval* / locked* / 状态字段.</li>
     * </ul>
     */
    @Override
    @Transactional
    @com.cretas.aims.annotation.Loggable(module = "PRODUCTION_PLAN", action = "COPY",
            entityType = "ProductionPlan", entityIdParam = "sourcePlanId")
    public ProductionPlanDTO copyProductionPlan(String factoryId, String sourcePlanId, Long userId) {
        ProductionPlan source = productionPlanRepository.findById(sourcePlanId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", sourcePlanId));

        if (!source.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法复制");
        }

        ProductionPlan newPlan = new ProductionPlan();
        newPlan.setId(UUID.randomUUID().toString());
        newPlan.setFactoryId(factoryId);
        // planNumber: 复用 ProductionPlanMapper 的 generatePlanNumber 模式 (PLAN-{ts}-{uuid8})
        newPlan.setPlanNumber("PLAN-" + System.currentTimeMillis()
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        // 复制业务字段
        newPlan.setProductTypeId(source.getProductTypeId());
        newPlan.setPlannedQuantity(source.getPlannedQuantity());
        newPlan.setTargetFinishedGoodIds(source.getTargetFinishedGoodIds() == null ? null
                : new ArrayList<>(source.getTargetFinishedGoodIds()));
        applyPlanUnitAuthority(newPlan, resolvePlanUnitAuthority(
                factoryId, source.getProductTypeId(), source.getTargetFinishedGoodIds()));
        newPlan.setPlannedDate(source.getPlannedDate());
        newPlan.setExpectedCompletionDate(source.getExpectedCompletionDate());
        newPlan.setPlanType(source.getPlanType());
        newPlan.setCustomerOrderNumber(source.getCustomerOrderNumber());
        newPlan.setPriority(source.getPriority());
        newPlan.setNotes(source.getNotes());
        newPlan.setEstimatedMaterialCost(source.getEstimatedMaterialCost());
        newPlan.setEstimatedLaborCost(source.getEstimatedLaborCost());
        newPlan.setEstimatedEquipmentCost(source.getEstimatedEquipmentCost());
        newPlan.setEstimatedOtherCost(source.getEstimatedOtherCost());
        newPlan.setSuggestedProductionLineId(source.getSuggestedProductionLineId());
        newPlan.setEstimatedWorkers(source.getEstimatedWorkers());
        newPlan.setAssignedSupervisorId(source.getAssignedSupervisorId());
        newPlan.setSourceType(source.getSourceType());
        newPlan.setSourceOrderId(source.getSourceOrderId());
        newPlan.setSourceOrderIds(source.getSourceOrderIds() == null ? null
                : new ArrayList<>(source.getSourceOrderIds()));
        newPlan.setSourceOrderItemId(source.getSourceOrderItemId());
        newPlan.setCustomerId(source.getCustomerId());
        newPlan.setProcessingMode(source.getProcessingMode());
        newPlan.setMaterialSupplyMode(source.getMaterialSupplyMode());
        newPlan.setOutputOwnership(source.getOutputOwnership());
        newPlan.setSourceCustomerName(source.getSourceCustomerName());
        newPlan.setProcessName(source.getProcessName());
        newPlan.setBatchDate(source.getBatchDate());
        newPlan.setForecastReason(source.getForecastReason());
        newPlan.setAiConfidence(source.getAiConfidence());
        newPlan.setIsMixedBatch(source.getIsMixedBatch() != null ? source.getIsMixedBatch() : false);
        newPlan.setMixedBatchType(source.getMixedBatchType());
        newPlan.setRelatedOrders(source.getRelatedOrders());
        newPlan.setCrValue(source.getCrValue());
        // 重置状态字段
        newPlan.setStatus(ProductionPlanStatus.PENDING);
        newPlan.setCreatedBy(userId);
        newPlan.setAllocatedQuantity(java.math.BigDecimal.ZERO);
        newPlan.setIsFullyMatched(false);
        newPlan.setIsLocked(false);
        // 不复制: actualQuantity / actual*Cost / approval* / locked*
        // / currentProbability / probabilityUpdatedAt / startTime / endTime
        // / isForceInserted / requiresApproval (默认值已 OK)

        newPlan = productionPlanRepository.save(newPlan);

        log.info("复制生产计划: source={}({}) → new={}({})",
                sourcePlanId, source.getPlanNumber(),
                newPlan.getId(), newPlan.getPlanNumber());
        return toDTOWithConversionInfo(newPlan);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionPlanDTO getProductionPlanById(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductionPlanDTO> getProductionPlanList(String factoryId, PageRequest pageRequest) {
        Sort sort = Sort.by(
                pageRequest.getSortDirection().equalsIgnoreCase("DESC") ?
                Sort.Direction.DESC : Sort.Direction.ASC,
                pageRequest.getSortBy()
        );

        org.springframework.data.domain.PageRequest pageable =
            org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                sort
            );

        Page<ProductionPlan> planPage;
        List<Long> creatorIds = visiblePlanCreators(factoryId);

        // headed-audit 修复 (2026-07-03): keyword 之前从未被应用到查询, 搜索框全无效。
        // 空白/空字符串一律当"未提供"处理 (null), 交给 repository 端的 CAST null-guard。
        String keyword = pageRequest.getKeyword() != null && !pageRequest.getKeyword().isBlank()
                ? pageRequest.getKeyword().trim()
                : null;

        // 如果指定了状态过滤
        if (pageRequest.getStatus() != null && !pageRequest.getStatus().isEmpty()) {
            String statusFilter = pageRequest.getStatus().toUpperCase();
            if ("UNFINISHED".equals(statusFilter) || "ACTIVE".equals(statusFilter)) {
                planPage = productionPlanRepository.findAll(
                        planScope(factoryId, creatorIds)
                                .and(planStatusIn(List.of(ProductionPlanStatus.PENDING, ProductionPlanStatus.IN_PROGRESS)))
                                .and(planKeyword(keyword)), pageable);
                log.info("按未完成状态过滤生产计划: factoryId={}, status={}, keyword={}", factoryId, statusFilter, keyword);
            } else {
                try {
                    ProductionPlanStatus status = ProductionPlanStatus.valueOf(statusFilter);
                    planPage = productionPlanRepository.findAll(
                            planScope(factoryId, creatorIds).and(planStatus(status)).and(planKeyword(keyword)), pageable);
                    log.info("按状态过滤生产计划: factoryId={}, status={}, keyword={}", factoryId, status, keyword);
                } catch (IllegalArgumentException e) {
                    log.warn("无效的状态值: {}, 返回全部数据 (keyword={})", pageRequest.getStatus(), keyword);
                    planPage = productionPlanRepository.findAll(
                            planScope(factoryId, creatorIds).and(planKeyword(keyword)), pageable);
                }
            }
        } else {
            planPage = productionPlanRepository.findAll(
                    planScope(factoryId, creatorIds).and(planKeyword(keyword)), pageable);
        }

        List<ProductionPlanDTO> planDTOs = planPage.getContent().stream()
                .map(this::toDTOWithConversionInfo)
                .collect(Collectors.toList());

        return PageResponse.of(
                planDTOs,
                pageRequest.getPage(),
                pageRequest.getSize(),
                planPage.getTotalElements()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionPlanDTO> getProductionPlansByStatus(String factoryId, ProductionPlanStatus status) {
        List<Long> creatorIds = visiblePlanCreators(factoryId);
        return productionPlanRepository.findAll(planScope(factoryId, creatorIds).and(planStatus(status)))
                .stream()
                .map(this::toDTOWithConversionInfo)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionPlanDTO> getProductionPlansByDateRange(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<Long> creatorIds = visiblePlanCreators(factoryId);
        Specification<ProductionPlan> date = (root, query, cb) ->
                cb.between(root.get("plannedDate"), startDate, endDate);
        return productionPlanRepository.findAll(planScope(factoryId, creatorIds).and(date)).stream()
                .map(this::toDTOWithConversionInfo)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionPlanDTO> getTodayProductionPlans(String factoryId) {
        List<Long> creatorIds = visiblePlanCreators(factoryId);
        Specification<ProductionPlan> today = (root, query, cb) ->
                cb.equal(root.get("plannedDate"), LocalDate.now());
        return productionPlanRepository.findAll(planScope(factoryId, creatorIds).and(today)).stream()
                .map(this::toDTOWithConversionInfo)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionPlanMaterialAdvisoryDTO getMaterialAdvisory(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权查看该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法查看");
        }

        AdvisoryScan scan = scanMaterialAdvisory(factoryId, plan);
        List<ProductionPlanMaterialAdvisoryDTO.Item> warnings = scan.items();
        // 🔴 没算成的那部分要说出来 —— 否则「一条预警都没有」同时表示「都够」和「一行都没算」。
        //    实测 F006 黄油鸡 BOM 7 行里 4 个原料没有标准用量, 缺料核算对它们从未生效。
        //    ⚠️ 只做口径说明, 不做告警(全库 69.2% 的 ACTIVE BOM 行都缺这个数, 告警会天天响)。
        // 🔴 缺陷31: 三组顺序固定 —— ①缺口(已在上面 warnings 里) ②库存为 0(不看需求量,
        //    非 RAW 专属) ③豁免名单(byReporting/uncomputable, RAW 专属)。⛔ 不要把 ②挪到 ③后面
        //    —— 「库存为 0」比「未配标准用量」更值得先看到, 它是拦生产的硬闸, 不是口径说明。
        StringBuilder note = new StringBuilder();
        if (!scan.zeroStock().isEmpty()) {
            note.append("（库存为 0（无论投料多少都会拦）：")
                    .append(String.join("、", scan.zeroStock())).append("）");
        }
        if (!scan.byReporting().isEmpty()) {
            note.append("（其中 ").append(scan.byReporting().size())
                    .append(" 项投料量由报工当场决定，不参与事前核算：")
                    .append(String.join("、", scan.byReporting())).append("）");
        }
        if (!scan.uncomputable().isEmpty()) {
            note.append("（其中 ").append(scan.uncomputable().size())
                    .append(" 项未配标准用量，未参与核算：")
                    .append(String.join("、", scan.uncomputable())).append("）");
        }
        String uncomputableNote = note.toString();
        // 🔴 2026-08-18: 原来这句只说「暂无缺料预警」, 不说自己量的是哪一层库存。
        //    实测 F006 黄油鸡计划 4 个原料各 200kg 全在原料仓、生产仓 0 —— 这句话说「不缺料」,
        //    点进逐道录入四行全是「0kg / 原料仓另有 200kg，待调拨入生产仓」。数没算错, 是没报口径。
        //    本仓判据「报一个数的时候, 把这个数是怎么来的一起报」。
        //
        // ⚠️ 三态, 不是两态 (硬约束 4): 「没量到生产仓」不能折叠进「生产仓没问题」——
        //    没配生产仓的工厂照样会走到这里, 那时宣称「生产仓也已备料」就是一句没量就下的结论。
        //
        // 🔴 缺陷31: zeroStock 非空时不许再说「库存足够」——那句话与下面的（库存为 0：...）
        //    note 直接矛盾。hasWarning 同理要把 zeroStock 算进去。
        boolean hasZeroStock = !scan.zeroStock().isEmpty();
        String message;
        if (!warnings.isEmpty()) {
            message = "原料库存参考: " + warnings.stream()
                    .map(ProductionPlanMaterialAdvisoryDTO.Item::getMessage)
                    .collect(Collectors.joining("; "));
        } else if (hasZeroStock) {
            message = "原料库存参考: 有物料库存为 0（见下）";
        } else if (workshopCaliberAvailable(factoryId, plan)) {
            message = "原料库存参考: 全厂库存足够, 生产仓也已备料";
        } else {
            message = "原料库存参考: 全厂库存足够（未核对生产仓 —— 本厂未配置生产仓）";
        }
        message = message + uncomputableNote;
        return ProductionPlanMaterialAdvisoryDTO.builder()
                .planId(plan.getId())
                .planNumber(plan.getPlanNumber())
                .hasWarning(!warnings.isEmpty() || hasZeroStock)
                .message(message)
                .warnings(warnings)
                .build();
    }

    @Override
    @Transactional
    public ProductionPlanDTO startProduction(String factoryId, String planId) {
        return startProduction(factoryId, planId, null);
    }

    @Override
    @Transactional
    public ProductionPlanDTO startProduction(String factoryId, String planId, Long operatorId) {
        // R6 (2026-06-14): 悲观写锁取计划, 并发双击/重试串行化。第二个请求拿锁后看到已 IN_PROGRESS → 409。
        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // 验证状态 (在悲观锁内 — 并发第二个请求在此被拦)
        if (plan.getStatus() != ProductionPlanStatus.PENDING) {
            throw new BusinessException(409, "只能开始待处理的生产计划")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        // 2026-08-02: SSOP 阻产台账 —— 只记录, 【不阻断】开工。
        //    本类多处写着 "N1 开工无条件化"(原料不足只预警不阻塞), 那是明确的既有决定;
        //    把 SSOP 未完成变成硬拦与它直接相反, 是需要单独拍板的另一件事。
        //    这里补的只是台账: 让月报的「月内阻产事件」有真实数据(此前那张表有读无写)。
        recordSsopBlockingGateIfAny(factoryId, planId);

        // SP2 二次加工: 开始生产时扣减 WIP 半成品库存
        // 注: 在事务内执行, 扣减失败直接抛出异常回滚整个 startProduction (fail-closed, 无 fail-soft)
        // ⛔ 刻意留在这里而不下沉到 createBatchFromPlan: 逐工序录入(ClerkProcessEntryServiceImpl)
        //    走的是「前端先调 /create-batch, 再由 materializeBatch 按 edges 精确扣半成品」这条路,
        //    把扣减挪进 createBatchFromPlan 会让同一 SECONDARY 计划被扣两次(幽灵超扣)。
        //    详见 ClerkProcessEntryServiceImpl 里 "不调用 startProduction" 那段注释。
        if ("SECONDARY".equals(plan.getPlanSourceType()) && plan.getSecondarySourceWipId() != null) {
            if (wipInventoryService == null) {
                throw new BusinessException(500, "二次加工服务未初始化, 无法扣减半成品库存");
            }
            wipInventoryService.deductForSecondaryPlan(
                    plan.getSecondarySourceWipId(),
                    plan.getPlannedQuantity() != null ? plan.getPlannedQuantity() : java.math.BigDecimal.ZERO,
                    factoryId,
                    operatorId);
            log.info("SP2 二次加工 WIP 扣减完成: planId={}, wipId={}", planId, plan.getSecondarySourceWipId());
        }

        // 🔴 2026-08-02: 「开工」必须同时把批次建出来。
        //
        // 此前 startProduction 只翻状态不建批次, 而报工与结单都需要批次 → 计划进入
        // 「已开工但没有生产批次」这个死状态, 三条路全走不通(2026-08-02 prod 真机逐条点过):
        //   - 「继续录入/逐道录入」: 当场弹「该计划已开工但没有生产批次」, 进不去;
        //   - 「核对结单」: 弹窗**能打开也能填**, 填完点提交才被
        //     ensureWorkflowSettlementUsesSubmittedReports 以 409 拒掉(workflow 计划必须
        //     先有已提交的逐道报工行) —— 让人白填一遍, 比直接挡住更费事;
        //   - 「补建批次」: createBatchFromPlan 也只放行 PENDING, 同样 409。
        // 用户只能作废重建。
        // 谁在调 /start (2026-08-02 更正 —— #2173 里我把触发端写错成了 PC):
        //   ✅ RN 手机端 ProductionPlanManagementScreen 的「开始生产」按钮
        //      (handleStartProduction → productionPlanApiClient.startProduction, 带二次确认弹窗);
        //   ✅ AI 工具 PlanUpdateTool (把状态改成 IN_PROGRESS 时);
        //   ❌ web-admin **不调** —— plans/list.vue 里的 handleStart 是死代码,
        //      定义了但模板从未引用 (2026-08-02 grep 实证: 全文件只出现 1 次, 即定义处)。
        //
        // 所以真实场景是**跨端**的, 比同端误操作更难察觉:
        // 车间主管在手机上点「开始生产」(完全正常的动作) → 计划 IN_PROGRESS 但没有批次
        // → 回到 PC, 文员发现这个计划**什么都做不了**。而 PC 前端
        // (plans/list.vue openProcessEntry) 只在 status===PENDING 时才自动补建批次 ——
        // 它把「状态已 IN_PROGRESS」当成了「已经有批次」的证据, 于是跳过补建。
        // prod 实测 7 个计划卡在这里(F001 6 个最早到 2026-03-12, 六膳门 1 个)。
        //
        // 修法是让两个「开工」入口收敛到同一实现(materializeBatchForPlan), 而不是在
        // createBatchFromPlan 那边放宽状态门 —— 后者会重新打开 R6 并发洞(第一笔事务未提交
        // 批次时, 第二笔看到「IN_PROGRESS 且无批次」会建出第二个批次)。
        // 并发安全由本方法上面那把悲观写锁 + PENDING 状态门保证: 第二个请求拿锁后看到
        // IN_PROGRESS 就 409, 走不到这里。
        //
        // ⚠️ 副作用(有意): 开工现在继承了转批次的前置校验(单位缺失 422 / 数量非法 422 /
        //    workflow pin 不覆盖产出 / 工序任务服务不可用 500)。这些计划以前「能开工」,
        //    但开完就是死的 —— 当场报错并给出可执行提示, 比事后卡死好。
        //    prod 实测 36 个 PENDING 计划全部有单位与数量, 现网无计划因此受阻。
        ProductionBatch createdBatch = materializeBatchForPlan(factoryId, plan);
        log.info("开工已建批次: planId={}, batchId={}, batchNumber={}",
                planId, createdBatch.getId(), createdBatch.getBatchNumber());

        if (applicationEventPublisher != null) {
            try {
                applicationEventPublisher.publishEvent(new com.cretas.aims.event.ProductionStartedEvent(
                        this, factoryId, plan.getId(), plan.getPlanNumber(), plan.getProductTypeId()));
            } catch (Exception e) { log.warn("Publish ProductionStartedEvent failed: {}", e.getMessage()); }
        }

        log.info("开始生产: planId={}", planId);
        return toDTOWithConversionInfo(plan);
    }


    /** SSOP 阻产状态集 —— 与 {@code SsopProductionGateCheckTool} 同一份口径。 */
    private static final java.util.List<String> SSOP_BLOCKING_STATUSES =
            java.util.List.of("SCHEDULED", "IN_PROGRESS", "FAILED");

    /**
     * 开工时若今日 SSOP 清洁未全部完成, 记一条阻产台账。
     *
     * <h2>这张表此前【有读无写】</h2>
     *
     * {@code SsopMonthlyAuditReportTool} 的「月内阻产事件」在读 {@code ssop_blocking_gates},
     * 但全仓<b>没有任何 writer</b>(表由 V20260821_37 建, 迁移里无种子) —— 那一栏永远是空列表,
     * 读起来像"本月零阻产", 实际是"没人记"。
     *
     * <h2>只记不拦</h2>
     *
     * 本类多处写着「N1 开工无条件化」(原料不足只记录预警, 不再阻塞开工)。
     * 本方法遵守它: <b>无论 SSOP 是否完成, 开工照常进行</b>。
     * 要不要变成硬拦是<b>单独的业务决定</b>, 与 N1 冲突, 别顺手改。
     *
     * <p>去重按<b>生产计划</b>({@code production_plan_id} 是 NOT NULL,
     * 仓储也已带 {@code existsByProductionPlanIdAndResolvedAtIsNull})。
     *
     * <p>任何异常只记 WARN 不外抛 —— 台账写失败不该把开工带崩。
     */
    private void recordSsopBlockingGateIfAny(String factoryId, String planId) {
        if (ssopExecutionRecordRepository == null || ssopBlockingGateRepository == null) {
            return;
        }
        try {
            java.time.LocalDate today = java.time.LocalDate.now();
            java.util.List<com.cretas.aims.entity.foodsafety.SsopExecutionRecord> blocking =
                    ssopExecutionRecordRepository.findByFactoryIdAndExecutionDateAndStatusIn(
                            factoryId, today, SSOP_BLOCKING_STATUSES);
            if (blocking.isEmpty()) {
                return;
            }
            if (ssopBlockingGateRepository.existsByProductionPlanIdAndResolvedAtIsNull(planId)) {
                return;
            }
            java.util.List<Long> procedureIds = blocking.stream()
                    .map(com.cretas.aims.entity.foodsafety.SsopExecutionRecord::getProcedureId)
                    .distinct()
                    .toList();
            String reason = String.format("开工时 %s 仍有 %d 项 SSOP 清洁未完成(状态在 %s 中)",
                    today, blocking.size(), SSOP_BLOCKING_STATUSES);
            ssopBlockingGateRepository.save(
                    com.cretas.aims.entity.foodsafety.SsopBlockingGate.builder()
                            .factoryId(factoryId)
                            .productionPlanId(planId)
                            .blockedAt(LocalDateTime.now())
                            .blockReason(reason)
                            .failedProcedureIds(procedureIds)
                            .build());
            log.warn("[SSOP阻产台账] factory={} planId={} 已记一条: {}", factoryId, planId, reason);
        } catch (Exception e) {
            log.warn("[SSOP阻产台账] 记录失败, 不影响开工: factory={} planId={} err={}",
                    factoryId, planId, e.getMessage());
        }
    }

    @Override
    @Transactional
    public ProductionPlanDTO completeProduction(String factoryId, String planId,
                                                BigDecimal actualQuantity, Long completedBy) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }
        if (requiresProductionSettlement(factoryId)) {
            throw new BusinessException(409, "六扇门生产完成必须先核对结单")
                    .withCode("PRODUCTION_SETTLEMENT_REQUIRED")
                    .withHint("请使用“核对结单”录入实际产量、实际领用明细和人效后再完成")
                    .withHintTarget("核对结单");
        }
        if (plan.getStatus() != ProductionPlanStatus.IN_PROGRESS
                && plan.getStatus() != ProductionPlanStatus.PENDING) {
            throw new BusinessException(409, "只能完成未完成的生产计划")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        runConfiguredValidation(factoryId, "COMPLETE", java.util.Map.of(
            "planId", planId,
            "actualQuantity", actualQuantity != null ? actualQuantity : java.math.BigDecimal.ZERO));

        plan.setStatus(ProductionPlanStatus.COMPLETED);
        if (plan.getStartTime() == null) {
            plan.setStartTime(LocalDateTime.now());
        }
        plan.setEndTime(LocalDateTime.now());
        plan.setActualQuantity(actualQuantity);
        plan = productionPlanRepository.save(plan);

        // GAP 6 (F006): 计划级完工应级联完成关联批次并发 BatchCompletedEvent,
        // 触发 SupplyChainOrchestrator.onBatchCompleted (扣料 REQUIRES_NEW + goodQuantity>0 时建成品).
        // ProductionCompletedEvent 无建成品监听器, 必须显式走批次完工链.
        List<ProductionBatch> linked = productionBatchRepository
                .findByFactoryIdAndProductionPlanId(factoryId, planId);
        for (ProductionBatch b : linked) {
            if (b.getStatus() == ProductionBatchStatus.IN_PROGRESS
                    || b.getStatus() == ProductionBatchStatus.PLANNED
                    || b.getStatus() == ProductionBatchStatus.PRODUCING) {
                b.setStatus(ProductionBatchStatus.COMPLETED);
                b.setEndTime(LocalDateTime.now());
                if (actualQuantity != null) {
                    b.setActualQuantity(actualQuantity);
                    // 计划级无良/次品拆分 → 全部计为良品 (成品创建需 goodQuantity>0).
                    b.setGoodQuantity(actualQuantity);
                }
                try { b.calculateMetrics(); } catch (Exception ignore) {}
                ProductionBatch cb = productionBatchRepository.save(b);
                if (applicationEventPublisher != null) {
                    try {
                        applicationEventPublisher.publishEvent(
                                new com.cretas.aims.event.BatchCompletedEvent(this, cb));
                        log.info("计划完成级联完成批次 + 发 BatchCompletedEvent(建成品): batchId={}", cb.getId());
                    } catch (Exception e) {
                        log.warn("BatchCompletedEvent 发布失败 (fail-soft): batchId={}, err={}", cb.getId(), e.getMessage());
                    }
                }
            }
        }

        if (applicationEventPublisher != null) {
            try {
                // 🔴 2026-08-19: 这个事件原来不带操作人 —— 于是它的两个监听器各自写死了
                //    SYSTEM_USER_ID = 0L, 而 prod 的 users 表里没有 id=0 (min(id)=1309)。
                //    ⚠️ 这个发布点当初逃过了排查, 因为它写的是**全限定名**
                //    (`new com.cretas.aims.event.ProductionCompletedEvent`) ——
                //    `grep "new ProductionCompletedEvent"` 一个字都看不见它, 是编译器抓出来的。
                //    ⇒ 形态 C⁸ 的又一例: 数结构要用 AST/编译器, 不要用字符串。
                applicationEventPublisher.publishEvent(new com.cretas.aims.event.ProductionCompletedEvent(
                        this, factoryId, plan.getId(), plan.getPlanNumber(),
                        plan.getProductTypeId(), actualQuantity, completedBy));
            } catch (Exception e) { log.warn("Publish ProductionCompletedEvent failed: {}", e.getMessage()); }
        }

        log.info("完成生产: planId={}, actualQuantity={}", planId, actualQuantity);
        return toDTOWithConversionInfo(plan);
    }

    /**
     * 把结单时的「这批有没有不良品」三态结论落到结算记录上, 并校验结论与数量自洽。
     *
     * <p>⛔ 核心约束: <b>「没回答」不许被翻译成「零不良」</b>。
     * 2026-08-26 实测生产 72 张报工单零填写, 而旧代码兜底填 0, 于是 8 个已完工批次
     * 全部声称 100% 良品 —— 其中 4 个的质检明确记了不合格。V20260826_05/06 已清掉那批假数,
     * 这里保证它不会从结单这条路再长回来。</p>
     */
    private void applyDefectCountOutcome(ProductionSettlement settlement,
                                         ProductionSettlementRequest request) {
        DefectCountResolver.Resolved resolved = DefectCountResolver.resolve(
                request.getDefectCountOutcome(), request.getDefectQuantity());
        settlement.setDefectCountOutcome(resolved.outcome());
        settlement.setDefectQuantity(resolved.quantity());
        backfillDefectCountToBatch(settlement.getFactoryId(),
                settlement.getProductionPlanId(), resolved);
    }

    /**
     * 把结单的不良品结论回填到批次上 —— <b>只在能唯一确定的时候</b>。
     *
     * <p>下游(良品率/损耗/人效/AI 产能工具)读的是 {@code production_batches.defect_quantity},
     * 所以结论只落在结算记录上还不够。但一个计划可能对多个正式批次,
     * 生产实测 87% 的已结单计划只有 1 个 —— 唯一时回填, 多于一个时<b>不猜</b>。</p>
     *
     * <p>⛔ 跳过时<b>记 WARN 并写明原因</b>, 不静默 —— 静默跳过会让「没回填」和
     * 「回填了但值是空」变得不可分, 那正是这条链路已经踩过两次的坑。</p>
     */
    private void backfillDefectCountToBatch(String factoryId, String planId,
                                            DefectCountResolver.Resolved resolved) {
        if (productionBatchRepository == null || factoryId == null || planId == null) {
            return;
        }
        List<ProductionBatch> batches =
                productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId);
        DefectCountResolver.BackfillDecision decision =
                DefectCountResolver.decideBatchBackfill(resolved, batches.size());
        if (!decision.write()) {
            if (decision.skipReason() != null && decision.skipReason().startsWith("AMBIGUOUS")) {
                log.warn("结单不良品数未回填批次: 计划 {} 有 {} 个正式批次, 无法确定该记到哪一个; "
                                + "结算记录上的结论仍然保留, 盘点时据此补录。reason={}",
                        planId, batches.size(), decision.skipReason());
            }
            return;
        }
        ProductionBatch batch = batches.get(0);
        batch.setDefectQuantity(decision.quantity());
        productionBatchRepository.save(batch);
        log.info("结单不良品数已回填批次: plan={} batch={} defectQuantity={}",
                planId, batch.getId(), decision.quantity());
    }

    @Override
    @Transactional
    public ProductionSettlementResponse settleProduction(String factoryId, String planId,
                                                         ProductionSettlementRequest request, Long settledBy) {
        if (request == null) {
            throw new BusinessException(400, "结单内容不能为空").withHintTarget("核对结单");
        }
        if (isBlank(request.getIdempotencyKey())) {
            throw new BusinessException(400, "缺少幂等键 idempotencyKey")
                    .withHint("请刷新页面后重试, 系统会自动生成结单提交键")
                    .withHintTarget("核对结单");
        }

        ProductionPlan plan = request.isConfirm()
                ? productionPlanRepository.findByIdForUpdate(planId)
                        .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                        .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId))
                : productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                        .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        Optional<ProductionSettlement> sameRequest = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                        factoryId, planId, request.getIdempotencyKey());
        if (sameRequest.isPresent()) {
            return toSettlementResponse(sameRequest.get(),
                    List.of("该结单请求已提交过, 已返回原结单结果"));
        }
        productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId)
                .ifPresent(existing -> {
                    throw new BusinessException(409, "该生产计划已结单, 不能重复完成")
                            .withCode("PRODUCTION_ALREADY_SETTLED")
                            .withHint("请刷新生产计划列表查看最新状态")
                            .withHintTarget("核对结单");
                });

        if (productionReportCorrectionService != null) {
            productionReportCorrectionService.assertNoPendingForPlan(factoryId, planId);
        }

        // 🔴🔒 生产仓预留释放 —— 必须在下面【任何一道】读 getCurrentQuantity() 的闸之前。
        //   报工提交时把 materialBatchRefs 的量记进 material_batches.reserved_quantity
        //   (WorkshopReportReservationService), 让「可用 = 在手 − 已提交报工引用量」成立。
        //   但结单三道闸读的是同一个 getCurrentQuantity() = 收货 − 已用 − 预留 ⇒
        //   为报工 R 挂的预留会把 R 自己那一笔挡在门外: prod 上领料按报工所需量整批建仓,
        //   引用量恰好等于收货量 ⇒ 20 − 0 − 20 = 0 ⇒ 每一单都结不掉, 不是边角料。
        //   ⛔ 不能改成「先扣后放」(例如挂在 ProductionSettledEvent 上): 那时 used 已经加上去了,
        //      used + reserved > receipt, assertConsumptionInvariant 当场 409 BATCH_OVER_CONSUMED。
        //   ⇒ 可行解只有这一个位置。整个方法是 @Transactional, 后面任何一步抛异常都会把释放一起回滚。
        if (workshopReservationService != null) {
            java.math.BigDecimal releasedReservation =
                    workshopReservationService.releaseForPlan(factoryId, planId);
            if (releasedReservation.signum() > 0) {
                log.info("结单前释放生产仓报工占用: factoryId={}, planId={}, 释放={}",
                        factoryId, planId, releasedReservation);
            }
        }

        ensureWorkflowSettlementUsesSubmittedReports(factoryId, plan);
        ProductionSettlementRequest effectiveRequest = isWorkflowPlan(plan) || request.isConfirm()
                ? deriveConfirmedSettlementRequest(factoryId, planId, request, isWorkflowPlan(plan))
                : request;
        if (isWorkflowPlan(plan)
                && plan.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED) {
            if (customerSuppliedSettlementCoverageGuard == null) {
                throw new BusinessException(500, "客供结单完整性校验服务未初始化，不能结单")
                        .withCode("CUSTOMER_SUPPLIED_SETTLEMENT_GUARD_UNAVAILABLE")
                        .withHintTarget("rawMaterialConsumptions");
            }
            customerSuppliedSettlementCoverageGuard.mergeMissingManualRawConsumptions(
                    factoryId, plan, effectiveRequest, request);
        }

        validateSettlementRequest(plan, effectiveRequest);

        // 缺陷 38 结单热修: 物料大类在这里一次性批量取全 (⛔ 不逐行查),
        // 然后传进下面两个循环调用链 (validation + posting), 避免 N+1。
        java.util.Map<String, String> settlementMaterialMainCategoryByType = batchResolveMaterialMainCategories(
                factoryId, settlementMaterialTypeIds(effectiveRequest));

        List<ProductionSettlementConsumption> consumptionLines = new ArrayList<>();
        appendConsumptionLines(factoryId, plan, "RAW_MATERIAL", effectiveRequest.getRawMaterialConsumptions(),
                consumptionLines, settlementMaterialMainCategoryByType);
        appendConsumptionLines(factoryId, plan, "SEMI_FINISHED", effectiveRequest.getSemiFinishedConsumptions(),
                consumptionLines, settlementMaterialMainCategoryByType);
        appendConsumptionLines(factoryId, plan, "AUXILIARY", effectiveRequest.getAuxiliaryConsumptions(),
                consumptionLines, settlementMaterialMainCategoryByType);

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(UUID.randomUUID().toString());
        settlement.setFactoryId(factoryId);
        settlement.setProductionPlanId(planId);
        settlement.setPlanNumber(plan.getPlanNumber());
        settlement.setIdempotencyKey(request.getIdempotencyKey());
        settlement.setPlannedQuantity(zeroIfNull(plan.getPlannedQuantity()));
        settlement.setActualFinishedQuantity(isWorkflowPlan(plan)
                ? effectiveRequest.getActualFinishedQuantity()
                : zeroIfNull(effectiveRequest.getActualFinishedQuantity()));
        settlement.setActualSemiFinishedQuantity(zeroIfNull(effectiveRequest.getActualSemiFinishedQuantity()));
        settlement.setQuantityUnit(resolveSettlementRequestUnit(effectiveRequest));
        settlement.setQuantityVarianceReason(trimToNull(effectiveRequest.getQuantityVarianceReason()));
        settlement.setQuantityVarianceNote(trimToNull(effectiveRequest.getQuantityVarianceNote()));
        settlement.setMaterialVarianceReason(trimToNull(effectiveRequest.getMaterialVarianceReason()));
        settlement.setMaterialVarianceNote(trimToNull(effectiveRequest.getMaterialVarianceNote()));
        settlement.setLaborDeferredReason(trimToNull(effectiveRequest.getLaborDeferredReason()));
        applyDefectCountOutcome(settlement, effectiveRequest);
        settlement.setPlanStatusAfter(ProductionPlanStatus.COMPLETED);
        settlement.setPostingStatus("PENDING_WAREHOUSE_RECEIPT");
        settlement.setPostingMessage("已完成结单和实际领用扣减; 等待仓库确认实收后才生成成品库存, 差异进入中转挂账");
        settlement.setSettledBy(settledBy);
        settlement.setSettledAt(LocalDateTime.now());
        settlement = productionSettlementRepository.save(settlement);
        persistWorkflowSettlementOutputs(factoryId, plan, settlement, effectiveRequest);

        for (ProductionSettlementConsumption line : consumptionLines) {
            line.setSettlementId(settlement.getId());
        }
        // Persist first so each immutable OUT ledger row can point to the exact consumption row.
        productionSettlementConsumptionRepository.saveAll(consumptionLines);
        postConsumptionToInventory(factoryId, plan, consumptionLines, settledBy);
        // 🔴🔒 R2 (2026-07-04): 结单族 process-row 的 SFI/FG 投料<b>严格扣减</b> (防 phantom 库存腐蚀)。
        //   逐道录入把常驻半成品(SFI)/成品(FG)作投料记在 process_sheet_rows.upstreamSources, 但结单预填
        //   把 semiFinishedConsumptions 留空 (让文员"手工再加"), 且 FG 投料结单请求无对应字段 → 这些投料
        //   此前<b>从不扣减</b> = SFI/FG 可用量不降 (幻库存: 消耗了却还在库, 可被重复领用/发货)。此处按 process-row
        //   直接严格扣减 (缺失/不足即抛, 整事务回滚, 禁止降级)。成本侧由 R4 computeByPlan (含 SFI/FG 投料桶)
        //   在仓库确认实收创建 FG 时补入, 此处仅补扣减。仅结单族 (SAFETY_STOCK 走小结扣减, 内部守卫)。
        deductProcessSheetStockFeeds(factoryId, plan, effectiveRequest);
        productionSettlementLaborRepository.saveAll(toLaborLines(factoryId, planId, settlement.getId(), effectiveRequest.getLaborSegments()));

        plan.setStatus(ProductionPlanStatus.COMPLETED);
        if (plan.getStartTime() == null) {
            plan.setStartTime(LocalDateTime.now());
        }
        plan.setEndTime(LocalDateTime.now());
        plan.setActualQuantity(settlement.getActualFinishedQuantity());
        productionPlanRepository.save(plan);

        // 🔴🔒🔒 结单族「扣减即打戳」根修 (bug fix 2026-07-04, #1216↔#1217 姊妹流根因闭合):
        //   延迟扣减设计下, 报工写的 MaterialConsumption 行恒 interimSettledAt IS NULL; SAFETY_STOCK 走
        //   「小结」逐笔扣 usedQuantity 时原子盖戳, 而结单族 (非 SAFETY_STOCK) 走本「结单」路径扣减 usedQuantity
        //   却从不盖戳 → 其报工消耗行永久停留 IS NULL, 使该谓词对结单族失去「待扣减」语义 (#1216 盘点 SUM /
        //   #1215 关单 count 守卫此前各自把结单族门控排除来绕开)。此处在结单时补盖戳, 令 interimSettledAt IS NULL
        //   对所有计划族统一 = 「尚未扣减」→ 两处门控退化为冗余而安全; 残留 #2 (跨计划投料把结单族未结行留在他单
        //   WKS 批次 → 关单守卫误 409 永久卡死) 随之闭合。⚠️ 仅结单族 (非 SAFETY_STOCK) 打戳: SAFETY_STOCK
        //   的打戳由小结原子完成, 提前打戳会让小结 §① 漏扣 → 幻库存 (故此处 sourceType 守卫不可去)。
        //   按 productionBatchId ∈ 本计划 process_sheet_rows.batch_id 定位 (非 mc.production_plan_id —— 非末道行
        //   plan_id 故意 null, 会漏), 与小结 §① / 撤销侧同 key。幂等 (已戳不再戳), factory-scoped。
        if (plan.getSourceType() != PlanSourceType.SAFETY_STOCK) {
            int stamped = materialConsumptionRepository
                    .stampInterimSettledForPlan(factoryId, planId, settlement.getSettledAt());
            if (stamped > 0) {
                log.info("结单族消耗打戳: factoryId={}, planId={}, sourceType={}, 打戳 {} 笔报工消耗 "
                                + "(interimSettledAt IS NULL 现统一=待扣减)",
                        factoryId, planId, plan.getSourceType(), stamped);
            }
            // 🔴🔒🔒 结单族 process_sheet_rows「结单即打戳」(2026-07-05, 镜像上方消耗打戳):
            //   SFI/FG 投料延迟扣减对结单族在上方 deductProcessSheetStockFeeds 已扣, 但 process_sheet_rows
            //   的 interim_settled_at 若不打戳会永久 IS NULL → 被 findUnsettledStockFeedRows (已去族门控) 永久
            //   计入 pending → 结单后 (SFI/FG 已扣) 仍被半成品盘点/发货/调拨减去 → 双减幻库存。此处打戳令
            //   IS NULL 对所有族统一 = 「待扣」。⚠️ 仅结单族 (sourceType 守卫内): SAFETY_STOCK 的 row 打戳由
            //   小结原子完成, 提前打戳会让小结漏处理产出行。processSheetRowRepository @Autowired(required=false),
            //   prod 恒存在; 缺失 (精简测试上下文) 则跳过 (与 deductProcessSheetStockFeeds null-guard 同策略)。
            if (processSheetRowRepository != null) {
                int rowsStamped = processSheetRowRepository
                        .stampInterimSettledForPlan(factoryId, planId, settlement.getSettledAt());
                if (rowsStamped > 0) {
                    log.info("结单族逐工序行打戳: factoryId={}, planId={}, 打戳 {} 行 "
                                    + "(process_sheet_rows.interim_settled_at, pending SFI/FG 投料现统一排除)",
                            factoryId, planId, rowsStamped);
                }
            }
        }

        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.ProductionSettledEvent(
                    this, factoryId, plan.getId(), plan.getPlanNumber(), plan.getProductTypeId(),
                    settlement.getId(), settlement.getActualFinishedQuantity()));
        }

        List<String> warnings = List.of("已按实际领用扣减原料/半成品库存; 成品需仓库确认实收后再入库");
        log.info("六扇门生产结单完成: factoryId={}, planId={}, settlementId={}, finished={}, semiFinished={}",
                factoryId, planId, settlement.getId(),
                settlement.getActualFinishedQuantity(), settlement.getActualSemiFinishedQuantity());
        return toSettlementResponse(settlement, warnings);
    }

    /**
     * Rebuild the normal close-plan request from current server facts. Only explicit
     * exception reasons are carried from the caller; quantities, batches and labor
     * lines are never trusted in confirmation-only mode.
     */
    private ProductionSettlementRequest deriveConfirmedSettlementRequest(
            String factoryId, String planId, ProductionSettlementRequest confirmation,
            boolean allowManualSemiFinishedConsumptions) {
        // Confirmation mode re-derives production facts, but the quality-count answer is
        // an explicit user conclusion that process-sheet rows do not contain. Validate it
        // before reading/mutating the derived request; null stays unknown (never zero).
        DefectCountResolver.Resolved confirmedDefectCount = DefectCountResolver.resolve(
                confirmation.getDefectCountOutcome(), confirmation.getDefectQuantity());
        ProductionSettlementPrefillResponse latest = getSettlementPrefill(factoryId, planId);
        // Run the same one-click guard inside the locked settlement transaction,
        // after its idempotent return. A retry must not audit already-consumed stock.
        if (confirmation.isConfirm()) {
            if (customerSuppliedSettlementCoverageGuard == null) {
                throw new BusinessException(500, "结单完整性校验服务未初始化，不能结单")
                        .withCode("CUSTOMER_SUPPLIED_SETTLEMENT_GUARD_UNAVAILABLE");
            }
            latest = customerSuppliedSettlementCoverageGuard.audit(factoryId, planId, latest);
            customerSuppliedSettlementCoverageGuard.requireCleanForOneClick(latest);
        }
        ProductionSettlementRequest derived = latest != null ? latest.getPrefill() : null;
        List<ProductionSettlementPrefillResponse.Issue> blockers = latest == null || latest.getAudit() == null
                ? List.of(issue("SETTLEMENT_FACTS_UNAVAILABLE", "无法读取当前报工事实，不能结单。", "processSheetRows"))
                : Optional.ofNullable(latest.getAudit().getIssues()).orElseGet(ArrayList::new).stream()
                        .filter(i -> i.getSeverity() == ProductionSettlementPrefillResponse.Severity.BLOCKER)
                        .filter(i -> !isResolvedSettlementOverride(i, confirmation))
                        .toList();
        if (derived == null || !blockers.isEmpty()) {
            String detail = blockers.stream()
                    .map(ProductionSettlementPrefillResponse.Issue::getMessage)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("；"));
            throw new BusinessException(409, "当前生产事实仍有阻塞项，不能结单")
                    .withCode("PRODUCTION_SETTLEMENT_BLOCKED")
                    .withHint(isBlank(detail) ? "请先完成并正式提交逐道报工" : detail)
                    .withHintTarget("核对结单");
        }
        derived.setIdempotencyKey(confirmation.getIdempotencyKey());
        derived.setConfirm(true);
        derived.setQuantityVarianceReason(firstNonBlank(
                confirmation.getQuantityVarianceReason(), derived.getQuantityVarianceReason()));
        derived.setQuantityVarianceNote(trimToNull(confirmation.getQuantityVarianceNote()));
        derived.setMaterialVarianceReason(trimToNull(confirmation.getMaterialVarianceReason()));
        derived.setMaterialVarianceNote(trimToNull(confirmation.getMaterialVarianceNote()));
        derived.setLaborDeferredReason(firstNonBlank(
                derived.getLaborDeferredReason(), confirmation.getLaborDeferredReason()));
        derived.setDefectCountOutcome(confirmedDefectCount.outcome());
        derived.setDefectQuantity(confirmedDefectCount.quantity());
        // Workflow facts remain server-authoritative, but external WIP consumption is
        // intentionally a caller-entered exception: it is not present in process
        // reports and must be carried into the derived request so the normal
        // SEMI_FINISHED validation, inventory deduction, and OUT ledger path runs.
        if (allowManualSemiFinishedConsumptions
                && !isEmpty(confirmation.getSemiFinishedConsumptions())) {
            derived.setSemiFinishedConsumptions(new ArrayList<>(confirmation.getSemiFinishedConsumptions()));
        }
        return derived;
    }

    private boolean isResolvedSettlementOverride(ProductionSettlementPrefillResponse.Issue issue,
                                                 ProductionSettlementRequest confirmation) {
        if (issue == null || issue.getCode() == null) {
            return false;
        }
        return switch (issue.getCode()) {
            case "QUANTITY_VARIANCE_OVER_PLAN" -> !isBlank(confirmation.getQuantityVarianceReason());
            case "MATERIAL_CONSUMPTION_EMPTY" -> !isBlank(confirmation.getMaterialVarianceReason());
            default -> false;
        };
    }

    private void ensureWorkflowSettlementUsesSubmittedReports(String factoryId, ProductionPlan plan) {
        boolean workflowPlan = isWorkflowPlan(plan);
        if (!workflowPlan || Boolean.TRUE.equals(plan.getSkipProcessReporting())) {
            return;
        }
        if (processSheetRowRepository == null) {
            throw new BusinessException(500, "逐道报工服务未初始化，workflow 计划不能结单")
                    .withCode("WORKFLOW_REPORTING_UNAVAILABLE")
                    .withHintTarget("核对结单");
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, plan.getId());
        boolean hasSubmitted = rows != null && rows.stream().anyMatch(this::isUsableProcessSheetRow);
        // 🔴 2026-08-18: 逐道报工有【两条腿】—— web 的逐道录入写 process_sheet_rows,
        //    App 的报工写 production_reports + work_process_tasks + 半成品台账。
        //    这道闸原来只看前者, 于是「工人在 App 把两道工都报完、任务 COMPLETED、审批也过了」
        //    的计划, 文员点「核对结单」照样被拒 409「必须先完成并正式提交逐道报工」——
        //    prod 实测 (PLAN-1786954657305, 报工 23832/23833 均 APPROVED)。
        //    ⇒ 纯用 App 报完的 workflow 计划**永远结不了单**, 这正是判据里那种「为什么点不动」。
        //
        // ⚠️ 闸的**意图**是「逐道报工做完了没有」, 不是「web 那张表有没有行」——
        //    量的对象错了(形态 A)。这里补上另一条腿, 意图不变、不放松:
        //    仍要求每一道工序任务都 COMPLETED, 只是承认 App 报工也算数。
        if (!hasSubmitted) {
            hasSubmitted = hasCompletedTaskLevelReporting(factoryId, plan);
        }
        if (!hasSubmitted) {
            throw new BusinessException(409, "workflow 计划必须先完成并正式提交逐道报工")
                    .withCode("WORKFLOW_REPORTING_REQUIRED")
                    .withHint("请在 web 逐道录入并提交，或让操作员在 App 上把每道工序都报完，再核对结单")
                    .withHintTarget("核对结单");
        }
    }

    /**
     * App 那条腿：本计划的批次下，工序任务是否<b>全部</b>报完（COMPLETED）。
     *
     * <p>⛔ 要求「全部」而不是「有一条」—— 与 web 那条腿等价严格。web 侧
     * {@code isUsableProcessSheetRow} 只要一行已提交就放行, 是因为那张表按行提交、
     * 行数不等于工序数; 而任务表天然一工序一行, 所以这边用「全部完成」才是同一个标准,
     * 放宽成 anyMatch 会让只报了第一道的计划也能结单。
     *
     * <p>⚠️ 没有任务 → 返回 false（不是 true）。「一条任务都没有」是<b>量不到</b>,
     * 不能当成「报完了」(硬约束 4: 别把没量到折叠进没问题)。
     */
    private boolean hasCompletedTaskLevelReporting(String factoryId, ProductionPlan plan) {
        if (workProcessTaskRepository == null || plan == null || plan.getId() == null) {
            return false;
        }
        List<ProductionBatch> batches =
                productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, plan.getId());
        if (batches == null || batches.isEmpty()) {
            return false;
        }
        boolean sawAnyTask = false;
        for (ProductionBatch b : batches) {
            List<com.cretas.aims.entity.workprocess.WorkProcessTask> tasks =
                    workProcessTaskRepository
                            .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, b.getId());
            if (tasks == null || tasks.isEmpty()) {
                continue;
            }
            sawAnyTask = true;
            for (com.cretas.aims.entity.workprocess.WorkProcessTask t : tasks) {
                if (t.getStatus() != com.cretas.aims.entity.workprocess.WorkProcessTask.Status.COMPLETED) {
                    return false;   // 还有工序没报完 → 不放行
                }
            }
        }
        return sawAnyTask;
    }

    private boolean isWorkflowPlan(ProductionPlan plan) {
        return plan != null
                && plan.getWorkflowSelectionMode() == ProductionBatch.WorkflowSelectionMode.WORKFLOW;
    }

    private void persistWorkflowSettlementOutputs(
            String factoryId,
            ProductionPlan plan,
            ProductionSettlement settlement,
            ProductionSettlementRequest request) {
        if (!isWorkflowPlan(plan)) {
            return;
        }
        if (productionSettlementOutputLineRepository == null || bomRecipeRepository == null
                || bomWorkflowRevisionService == null) {
            // ⛔ 读不到权威口径就炸, 不许退回一个「看起来能跑」的默认判据 ——
            //    那正是坎 4 的成因(两处各自判「是不是副产物」)。
            throw new BusinessException(500, "产出成本口径服务未装配, 不能结单")
                    .withCode("WORKFLOW_OUTPUT_SETTLEMENT_UNAVAILABLE")
                    .withHint("请联系管理员检查 production_settlement_output_lines 迁移与 BOM 工艺服务装配");
        }
        Map<String, String> recipeIds = Optional.ofNullable(plan.getSelectedBomRecipeIdsByProduct())
                .orElseGet(Map::of);
        Map<String, Integer> recipeVersions = Optional.ofNullable(plan.getSelectedBomVersionsByProduct())
                .orElseGet(Map::of);
        Map<String, String> outputUnits = Optional.ofNullable(plan.getWorkflowOutputUnitsByProduct())
                .orElseGet(Map::of);
        List<ProductionSettlementRequest.OutputLine> reportedOutputs =
                Optional.ofNullable(request.getTerminalOutputs()).orElseGet(ArrayList::new);
        Set<String> reportedSkus = reportedOutputs.stream()
                .map(ProductionSettlementRequest.OutputLine::getProductTypeId)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (reportedOutputs.isEmpty() || !reportedSkus.equals(recipeIds.keySet())
                || !recipeIds.keySet().equals(recipeVersions.keySet())
                || !recipeIds.keySet().equals(outputUnits.keySet())) {
            throw new BusinessException(409,
                    "Submitted terminal outputs do not match the plan's pinned Workflow terminal set")
                    .withCode("WORKFLOW_OUTPUT_SET_MISMATCH")
                    .withHint("Submit one or more terminal batches for every pinned output SKU and no other SKU");
        }

        Map<String, BigDecimal> reportedRatios = null;
        List<ProductionSettlementOutputLine> lines = new ArrayList<>();
        for (ProductionSettlementRequest.OutputLine output : reportedOutputs) {
            String productTypeId = trimToNull(output.getProductTypeId());
            String batchNumber = trimToNull(output.getBatchNumber());
            String unit = canonicalReceiptUnit(trimToNull(output.getUnit()));
            if (productTypeId == null || batchNumber == null || unit == null
                    || output.getQuantity() == null || output.getQuantity().signum() <= 0) {
                throw new BusinessException(409, "Workflow terminal output line is incomplete")
                        .withCode("WORKFLOW_OUTPUT_LINE_INVALID");
            }
            String recipeId = trimToNull(recipeIds.get(productTypeId));
            Integer recipeVersion = recipeVersions.get(productTypeId);
            if (recipeId == null || recipeVersion == null) {
                throw new BusinessException(409, "Pinned BOM output recipe authority is incomplete")
                        .withCode("PINNED_BOM_OUTPUT_RECIPE_INVALID");
            }
            BomRecipe recipe = bomRecipeRepository.findById(recipeId)
                    .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                    .filter(candidate -> productTypeId.equals(candidate.getProductTypeId()))
                    .filter(candidate -> Objects.equals(plan.getSelectedBomFamilyId(), candidate.getBomFamilyId()))
                    .filter(candidate -> Objects.equals(plan.getSelectedWorkflowRevisionId(),
                            candidate.getWorkflowRevisionId()))
                    .filter(candidate -> Objects.equals(plan.getSelectedWorkflowRevisionHash(),
                            candidate.getWorkflowRevisionHash()))
                    .filter(candidate -> Objects.equals(recipeVersion, candidate.getVersion()))
                    .orElseThrow(() -> new BusinessException(409,
                            "Pinned BOM output recipe no longer matches the plan authority")
                            .withCode("PINNED_BOM_OUTPUT_RECIPE_INVALID"));
            String expectedUnit = canonicalReceiptUnit(firstNonBlank(
                    outputUnits.get(productTypeId), recipe.getOutputUnit()));
            if (!unit.equals(expectedUnit)) {
                throw new BusinessException(409,
                        "成品「" + resolveFinishedGoodLabel(productTypeId, recipe.getProductName()) + "」的报产单位与工艺/BOM 约定的产出单位不一致")
                        .withCode("WORKFLOW_OUTPUT_UNIT_MISMATCH")
                        .withHint("工艺约定按「" + expectedUnit + "」报产, 本次提交的是「" + unit
                                + "」。请回到报工页把该产出的单位改成「" + expectedUnit + "」后重新结单")
                        .withHintTarget("terminalOutputs");
            }
            if (isBlank(recipe.getTargetTerminalNodeId()) || recipe.getOutputRole() == null) {
                throw new BusinessException(409,
                        "成品「" + resolveFinishedGoodLabel(productTypeId, recipe.getProductName()) + "」的 BOM 没有记录它在工艺图上的产出位置和角色, 不能结单")
                        .withCode("PINNED_BOM_OUTPUT_POLICY_INCOMPLETE")
                        .withHint("请前往 BOM成本管理 打开该成品的 BOM, 重新选择工艺修订并激活新版本后重试")
                        .withHintTarget("bom");
            }
            if (!Objects.equals(recipe.getTargetTerminalNodeId(), trimToNull(output.getMaterialNodeId()))) {
                throw new BusinessException(409,
                        "成品「" + resolveFinishedGoodLabel(productTypeId, recipe.getProductName()) + "」报的不是 BOM 钉住的那个工艺终端产出")
                        .withCode("WORKFLOW_TERMINAL_NODE_MISMATCH")
                        .withHint("请回到报工页重新选择该成品对应的终端产出后重新结单; "
                                + "若工艺图改过, 请先在 BOM成本管理 升级到最新工艺并激活")
                        .withHintTarget("terminalOutputs");
            }
            // 🔴 坎 4: 「这是不是副产物」只问一处 —— BomWorkflowRevisionService#resolveOutputCostPolicy。
            //    这里原来只判 outputRole == BY_PRODUCT, 而 BOM 侧那两处还会再问一句
            //    targetProducedUnderActualIoSemantics 把自动编号出来的占位角色豁免掉,
            //    净效果是 NRV 被这道闸要求、被那道闸禁止 → 多成品计划结不了单。
            // ⚠️ 三态缺一不可: 只豁免 NRV 那一支的话, 占位产出下一步就会撞上
            //    OUTPUT_COST_ALLOCATION_RATIO_REQUIRED(它的 ratio 是自动置 0 的) —— 拒答只是挪了一格。
            BigDecimal allocationRatio = recipe.getCostAllocationRatio();
            switch (bomWorkflowRevisionService.resolveOutputCostPolicy(factoryId, recipe)) {
                case BY_PRODUCT_NRV -> {
                    if (recipe.getByproductNrvUnitPrice() == null
                            || recipe.getByproductNrvUnitPrice().signum() <= 0) {
                        throw new BusinessException(409,
                                "副产品「" + resolveFinishedGoodLabel(productTypeId, recipe.getProductName()) + "」还没有单位可变现净值, 成本无法抵扣, 不能结单")
                                .withCode("BYPRODUCT_NRV_REQUIRED")
                                .withHint("请前往 BOM成本管理 打开该 BOM Family 的「产出成本」, "
                                        + "为这个副产品填写单位可变现净值(预计售价扣除后续加工与销售费用), 再重新结单")
                                .withHintTarget("byproductNrvUnitPrice");
                    }
                }
                case ALLOCATION_RATIO -> {
                    if (allocationRatio == null && reportedOutputCostAllocationService != null) {
                        if (reportedRatios == null) {
                            reportedRatios = reportedOutputCostAllocationService.resolve(factoryId, plan.getId(), reportedOutputs);
                        }
                        allocationRatio = reportedRatios.get(productTypeId);
                    }
                    if (allocationRatio == null || allocationRatio.signum() <= 0) {
                        throw new BusinessException(409,
                                "成品「" + resolveFinishedGoodLabel(productTypeId, recipe.getProductName()) + "」还没有成本分摊比例, 不能结单")
                                .withCode("OUTPUT_COST_ALLOCATION_RATIO_REQUIRED")
                                // ⛔ 这句原来写「请前往 Workflow 配置为每个产出设置角色与成本分摊比例」——
                                //    而画布上【没有】这个输入框(ProcessPort.outputRole 已弃用, 0 处 v-model)。
                                //    换成两个真实存在的入口: 画布的「副产 Cell」标记 / 报工页每行的比例输入。
                                .withHint("这条工艺有多个都不是副产的成品产出, BOM 阶段定不出各自占多少成本。"
                                        + "如果其中某个其实是副产, 请回到工艺画布把它改成「副产」Cell 后重新生成并激活 BOM; "
                                        + "如果它们确实都是正经成品, 请在报工页为每个产出填写成本分摊比例(合计 100%)后重新结单")
                                .withHintTarget("costAllocationRatio");
                    }
                }
                case ACTUAL_IO_PLACEHOLDER -> {
                    // 角色与比例是【早期版本】按节点 id 顺序自动编号出来的占位值, 用户从没被问过 ——
                    // 这里既不能要 NRV 也不能要 ratio > 0。成本口径由入库那步的分摊器统一裁定。
                    // ⚠️ 2026-08-18(坎 3) 之后不再产生这种占位值(没人声明时改成 CO_PRODUCT +
                    //    ratio=null), 但 prod 已经落库的那 15 行读出来还是老形状, 这一支必须留着。
                }
            }

            ProductionSettlementOutputLine line = ProductionSettlementOutputLine.create();
            line.setFactoryId(factoryId);
            line.setSettlementId(settlement.getId());
            line.setProductionPlanId(plan.getId());
            line.setProductTypeId(productTypeId);
            line.setReportedBatchNumber(batchNumber);
            line.setReportedQuantity(requireInventoryQuantityScale(
                    output.getQuantity(),
                    "WORKFLOW_OUTPUT_QUANTITY_SCALE_INVALID",
                    "Workflow 报产数量"));
            line.setQuantityUnit(unit);
            line.setBomFamilyId(plan.getSelectedBomFamilyId());
            line.setBomRecipeId(recipe.getId());
            line.setBomRecipeVersion(recipe.getVersion());
            line.setTargetTerminalNodeId(recipe.getTargetTerminalNodeId());
            line.setOutputRole(recipe.getOutputRole());
            line.setCostAllocationRatio(allocationRatio);
            line.setByproductNrvUnitPrice(recipe.getByproductNrvUnitPrice());
            lines.add(line);
        }
        productionSettlementOutputLineRepository.saveAll(lines);
    }

    // ==================== Phase 2A: 报工→核算自动化 (核对结单预填) ====================

    /** 数量差异预填阈值: |计划-实际|/计划 ≤ 5% 视为"无显著差异", 自动填原因; 超则留空让人选。 */
    private static final BigDecimal SETTLEMENT_VARIANCE_THRESHOLD = new BigDecimal("0.05");

    /**
     * A修: 判断计划是否跨单位 (末道产出单位 份/盒 ≠ 原计划单位 kg)。
     *
     * <p>completeProduction 把批次 unit 覆盖成产出单位时, 原计划单位写入 {@code plannedUnit}
     * (见 {@link ProductionBatch#getPlannedUnit()})。任一非取消批次 plannedUnit != unit 即跨单位 →
     * {@code actualFinished(份)} 与 {@code plan.plannedQuantity(kg)} 不可直接比较, 跳过超产判断,
     * 镜像 batch 层 efficiency=null 的诚实留空做法。</p>
     */
    private boolean isCrossUnitPlan(List<ProductionBatch> batches) {
        if (batches == null) {
            return false;
        }
        return batches.stream()
                .filter(b -> b != null && b.getStatus() != ProductionBatchStatus.CANCELLED)
                .anyMatch(b -> b.getPlannedUnit() != null && b.getUnit() != null
                        && !b.getPlannedUnit().equals(b.getUnit()));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionSettlementPrefillResponse getSettlementPrefill(String factoryId, String planId) {
        if (productionReportCorrectionService != null) {
            productionReportCorrectionService.assertNoPendingForPlan(factoryId, planId);
        }
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        List<ProductionSettlementPrefillResponse.Issue> issues = new ArrayList<>();

        // 1) 取本计划全部非取消批次的逐道 YIELD 报工 (按 processOrder, createdAt 已排序)
        List<ProductionBatch> batches = productionBatchRepository
                .findByFactoryIdAndProductionPlanId(factoryId, planId);

        // 逐道电子表格包含本计划实际使用的原料批次、内部 WIP 流转和工时，
        // 是核对结单的完整事实来源。旧 YIELD 报工只保留为历史计划的兼容回退，
        // 不能因为它存在就跳过更完整的逐道数据。
        ProductionSettlementPrefillResponse sheetPrefill =
                deriveSettlementPrefillFromProcessSheetRows(factoryId, plan, batches);
        if (sheetPrefill != null) {
            return sheetPrefill;
        }

        List<ProductionReport> allReports = new ArrayList<>();
        if (batches != null) {
            for (ProductionBatch b : batches) {
                if (b == null || b.getId() == null || b.getStatus() == ProductionBatchStatus.CANCELLED) {
                    continue;
                }
                List<ProductionReport> reports = productionReportRepository
                        .findYieldReportsByBatch(factoryId, b.getId());
                if (reports != null) {
                    allReports.addAll(reports);
                }
            }
        }

        if (allReports.isEmpty()) {
            // 无任何报工 → 不臆造, 返回空预填 + 明确 issue 让人手填 (诚实空态)
            issues.add(issue("NO_YIELD_REPORTS",
                    "该计划暂无逐道报工记录, 无法自动预填; 请手工录入实际产量、领用与人效后结单。",
                    "actualFinishedQuantity"));
            return buildPrefillResponse(emptyPrefill(plan), issues);
        }

        // 2) actualFinishedQuantity ← 末道 (最大 processOrder) 报工的产出量汇总
        BigDecimal actualFinished = deriveLastStepOutput(allReports);
        if (actualFinished == null || actualFinished.compareTo(BigDecimal.ZERO) <= 0) {
            issues.add(issue("FINISHED_OUTPUT_MISSING",
                    "末道工序产出量缺失或为 0, 无法自动带入实际成品产量; 请在产出核对处手工填写。",
                    "actualFinishedQuantity"));
            actualFinished = null;
        }

        // 3) rawMaterialConsumptions ← 各道 materialBatchRefs 聚合 (按 materialBatchId 求和)
        List<ProductionSettlementRequest.ConsumptionLine> rawLines =
                deriveRawConsumptions(factoryId, plan, allReports, issues);

        // 4) laborSegments ← 各道 laborSegments / 单一工时汇总
        List<ProductionSettlementRequest.LaborSegment> laborSegments = deriveLaborSegments(allReports);
        String laborDeferredReason = null;
        if (laborSegments.isEmpty()) {
            // derive 不出工时 → 不臆造分钟数, 留空 + 默认延期原因让人确认/补录 (满足 settle 校验)。
            // INFO 级: 已给合法延期原因, 不阻塞提交; 仅提醒可补录。
            laborDeferredReason = "工时稍后补录";
            issues.add(infoIssue("LABOR_MISSING",
                    "报工中未带可用工时段, 已默认置为“工时稍后补录”; 如需计入人效请手工补录工时/人数。",
                    "laborSegments"));
        }

        // 5) quantityVarianceReason ← 阈值内自动填, 超阈值留空 + issue
        BigDecimal planned = zeroIfNull(plan.getPlannedQuantity());
        // A修: 末道产出单位(份/盒) 可能 ≠ plan.plannedQuantity 单位(kg) → 跨单位裸比误报超产.
        boolean crossUnit = isCrossUnitPlan(batches);
        String varianceReason = null;
        if (crossUnit) {
            // 跨单位: 不比较产量差异, 不臆造; 留 INFO 让人核对实际产量 (非 BLOCKER, 不阻一键确认).
            issues.add(infoIssue("QUANTITY_UNIT_CROSS",
                    "末道产出单位与计划单位不同(如 份/盒 vs kg), 已跳过产量差异自动判断; 请人工核对实际产量。",
                    "quantityVarianceReason"));
        } else if (actualFinished != null && planned.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = actualFinished.subtract(planned).abs();
            BigDecimal ratio = diff.divide(planned, 6, RoundingMode.HALF_UP);
            if (ratio.compareTo(SETTLEMENT_VARIANCE_THRESHOLD) <= 0) {
                varianceReason = "无显著差异";
            } else if (actualFinished.compareTo(planned) > 0) {
                // settle 校验: 实际 > 计划 必须有超产原因; 留空让人选 (对齐前端差异原因下拉)
                issues.add(issue("QUANTITY_VARIANCE_OVER_PLAN",
                        "实际产量(" + stripTrailing(actualFinished) + ")超过计划("
                                + stripTrailing(planned) + ")超过 5%, 请选择超产原因后再确认。",
                        "quantityVarianceReason"));
            }
            // 实际 < 计划 且超阈值: settle 不强制原因, 不阻塞, 不标 issue (留空合法)
        }

        // 6) 材料领用为空 → 不能自动臆造原因, 标 issue (settle 要求至少一行或 materialVarianceReason)
        if (rawLines.isEmpty()) {
            issues.add(issue("MATERIAL_CONSUMPTION_EMPTY",
                    "报工中未找到可用原料批次领用记录, 无法自动带入实际领用; 请手工录入原料/半成品领用, 或选择物料差异原因。",
                    "rawMaterialConsumptions"));
        }

        // semiFinishedConsumptions: 默认留空。本计划 WIP 是计划内部自产自耗 (不进官方半成品领用扣减)。
        // 仅"跨计划领用外部既有半成品库存"才填 — 该信息不在 YIELD 报工里可靠 derive, 故留空 + INFO 提示让人确认。
        // INFO 级不阻塞一键确认 (绝大多数计划无跨计划半成品领用)。
        issues.add(infoIssue("SEMI_FINISHED_CONFIRM",
                "如本次生产领用了其他计划产出的外部半成品库存, 请在“半成品实际领用”处手工添加; 本计划内部自产自耗的中间品无需在此填写。",
                "semiFinishedConsumptions"));

        // 🔴 2026-08-18: 这条(App 报工)腿原来【不建 terminalOutputs】, 而 web 那条腿建。
        //    @Builder.Default 让它是空 list ⇒ persistWorkflowSettlementOutputs 看到
        //    reportedOutputs.isEmpty() 就抛 409 WORKFLOW_OUTPUT_SET_MISMATCH ⇒
        //    工人在 App 把每道工都报完、审批也过了, 文员点「核对结单」仍然结不了单。
        //    ⚠️ 那句报错听起来像用户传错了, 实际是服务端自己推导为空(见 deriveTerminalOutputsFromReports)。
        List<ProductionSettlementRequest.OutputLine> terminalOutputs =
                deriveTerminalOutputsFromReports(factoryId, plan, batches, allReports, issues);
        if (terminalOutputs.isEmpty()) {
            issues.add(issue("FINISHED_OUTPUT_MISSING",
                    "末道报工没有可用的产出(数量或产品缺失), 无法自动带入实际成品产量; 请在产出核对处手工填写。",
                    "terminalOutputs"));
        }

        ProductionSettlementRequest prefill = ProductionSettlementRequest.builder()
                .idempotencyKey(null) // 前端生成, 防呆 Rule 4 (打开 dialog 一次性生成)
                .actualFinishedQuantity(actualFinished)
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                // 单位从末道产出取, 与 web 那条腿同一个 helper —— ⛔ 不留 null 让前端猜
                .quantityUnit(resolveTerminalOutputUnit(terminalOutputs))
                .quantityVarianceReason(varianceReason)
                .laborDeferredReason(laborDeferredReason)
                .rawMaterialConsumptions(rawLines)
                .semiFinishedConsumptions(new ArrayList<>())
                .auxiliaryConsumptions(new ArrayList<>())
                .laborSegments(laborSegments)
                .terminalOutputs(terminalOutputs)
                .build();

        return buildPrefillResponse(prefill, issues);
    }

    private ProductionSettlementPrefillResponse deriveSettlementPrefillFromProcessSheetRows(
            String factoryId, ProductionPlan plan, List<ProductionBatch> batches) {
        if (processSheetRowRepository == null || plan == null || isBlank(plan.getId())) {
            return null;
        }

        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, plan.getId());
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        List<ProductionSettlementPrefillResponse.Issue> issues = new ArrayList<>();
        List<ParsedProcessSheetRow> parsedRows = new ArrayList<>();
        for (ProcessSheetRow row : rows) {
            if (!isUsableProcessSheetRow(row)) {
                continue;
            }
            ProcessSheetRowRequest request = parseProcessSheetRowPayload(row, issues);
            if (request != null) {
                parsedRows.add(new ParsedProcessSheetRow(row, request));
            }
        }
        if (parsedRows.isEmpty()) {
            return issues.isEmpty() ? null : buildPrefillResponse(emptyPrefill(plan), issues);
        }
        parsedRows.sort(Comparator
                .comparing((ParsedProcessSheetRow r) -> r.request().getProcessOrder(),
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(r -> r.row().getCreatedAt(), Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(r -> r.row().getId(), Comparator.nullsLast(Long::compareTo)));

        List<ProductionSettlementRequest.OutputLine> terminalOutputs =
                deriveTerminalProcessSheetOutputs(factoryId, plan, parsedRows);
        BigDecimal actualFinished = sumCompatibleTerminalOutputs(terminalOutputs);
        long terminalUnitCount = terminalOutputs.stream()
                .map(ProductionSettlementRequest.OutputLine::getUnit)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .map(ProductionPlanServiceImpl::canonicalReceiptUnit)
                .distinct()
                .count();
        if (terminalUnitCount > 1) {
            issues.add(infoIssue("FINISHED_OUTPUT_UNIT_MIXED",
                    "终端产出包含多个不能直接相加的单位，请按 SKU、批次和单位分别核对。",
                    "terminalOutputs"));
        }
        if (terminalOutputs.isEmpty()
                || (terminalUnitCount <= 1
                        && (actualFinished == null || actualFinished.compareTo(BigDecimal.ZERO) <= 0))) {
            issues.add(issue("FINISHED_OUTPUT_MISSING",
                    "逐道电子表格末道产出量缺失或为 0, 无法自动带入实际成品产量; 请在产出核对处手工填写。",
                    "actualFinishedQuantity"));
            actualFinished = null;
        }

        List<ProductionSettlementRequest.ConsumptionLine> rawLines =
                deriveRawConsumptionsFromProcessSheetRows(factoryId, parsedRows, issues);

        List<ProductionSettlementRequest.LaborSegment> laborSegments =
                deriveLaborSegmentsFromProcessSheetRows(parsedRows);
        String laborDeferredReason = null;
        if (laborSegments.isEmpty()) {
            laborDeferredReason = "工时稍后补录";
            issues.add(infoIssue("LABOR_MISSING",
                    "逐道电子表格中未带可用工时段, 已默认置为“工时稍后补录”; 如需计入人效请手工补录工时和人数。",
                    "laborSegments"));
        }

        BigDecimal planned = zeroIfNull(plan.getPlannedQuantity());
        boolean crossUnit = isCrossUnitPlan(batches)
                || terminalUnitCount > 1
                || terminalOutputs.stream()
                        .map(ProductionSettlementRequest.OutputLine::getProductTypeId)
                        .map(this::trimToNull)
                        .filter(Objects::nonNull)
                        .distinct()
                        .count() > 1;
        String varianceReason = null;
        if (crossUnit) {
            issues.add(infoIssue("QUANTITY_UNIT_CROSS",
                    "末道产出单位与计划单位不同, 已跳过产量差异自动判断; 请人工核对实际产量。",
                    "quantityVarianceReason"));
        } else if (actualFinished != null && planned.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = actualFinished.subtract(planned).abs();
            BigDecimal ratio = diff.divide(planned, 6, RoundingMode.HALF_UP);
            if (ratio.compareTo(SETTLEMENT_VARIANCE_THRESHOLD) <= 0) {
                varianceReason = "无显著差异";
            } else if (actualFinished.compareTo(planned) > 0) {
                issues.add(issue("QUANTITY_VARIANCE_OVER_PLAN",
                        "实际产量(" + stripTrailing(actualFinished) + ")超过计划("
                                + stripTrailing(planned) + ")超过 5%, 请选择超产原因后再确认。",
                        "quantityVarianceReason"));
            }
        }

        if (rawLines.isEmpty()) {
            issues.add(issue("MATERIAL_CONSUMPTION_EMPTY",
                    "逐道电子表格中未找到可用原料批次领用记录, 无法自动带入实际领用; 请手工录入原料/半成品领用或选择物料差异原因。",
                    "rawMaterialConsumptions"));
        }

        issues.add(infoIssue("SEMI_FINISHED_CONFIRM",
                "如本次生产领用了其他计划产出的外部半成品库存, 请在“半成品实际领用”处手工添加; 本计划内部自产自耗的中间品无需在此填写。",
                "semiFinishedConsumptions"));

        ProductionSettlementRequest prefill = ProductionSettlementRequest.builder()
                .idempotencyKey(null)
                .actualFinishedQuantity(actualFinished)
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .quantityUnit(resolveTerminalOutputUnit(terminalOutputs))
                .quantityVarianceReason(varianceReason)
                .laborDeferredReason(laborDeferredReason)
                .rawMaterialConsumptions(rawLines)
                .semiFinishedConsumptions(new ArrayList<>())
                .auxiliaryConsumptions(new ArrayList<>())
                .laborSegments(laborSegments)
                .terminalOutputs(terminalOutputs)
                .build();
        return buildPrefillResponse(prefill, issues);
    }

    private boolean isUsableProcessSheetRow(ProcessSheetRow row) {
        if (row == null || isBlank(row.getRowPayload())) {
            return false;
        }
        String status = trimToNull(row.getRowStatus());
        String submission = trimToNull(row.getSubmissionStatus());
        if (ProcessSheetRow.SUBMISSION_DRAFT.equals(submission)) {
            return false;
        }
        if (ProcessSheetRow.SUBMISSION_SUBMITTED.equals(submission)) {
            return true;
        }
        // Explicit legacy compatibility: rows written before submission_status existed
        // retain their historical rowStatus semantics, but new DRAFT rows never enter settlement.
        boolean legacy = submission == null || ProcessSheetRow.SUBMISSION_LEGACY.equals(submission);
        return legacy && (status == null || "SAVED".equals(status) || "SUBMITTED".equals(status));
    }

    private ProcessSheetRowRequest parseProcessSheetRowPayload(ProcessSheetRow row,
            List<ProductionSettlementPrefillResponse.Issue> issues) {
        try {
            return PROCESS_SHEET_ROW_MAPPER.readValue(row.getRowPayload(), ProcessSheetRowRequest.class);
        } catch (Exception ex) {
            issues.add(issue("PROCESS_SHEET_PAYLOAD_INVALID",
                    "逐道电子表格行 " + row.getClientRowId() + " 的报工内容无法解析, 请重新保存该行后再结单。",
                    "processSheetRows"));
            return null;
        }
    }

    private List<ProductionSettlementRequest.OutputLine> deriveTerminalProcessSheetOutputs(
            String factoryId,
            ProductionPlan plan,
            List<ParsedProcessSheetRow> rows) {
        Map<String, String> pinnedTerminalNodes = isWorkflowPlan(plan)
                ? resolvePinnedTerminalNodes(factoryId, plan)
                : Map.of();
        if (isWorkflowPlan(plan) && pinnedTerminalNodes.isEmpty()) {
            return List.of();
        }
        Set<String> consumedBatchNumbers = rows.stream()
                .flatMap(r -> Optional.ofNullable(r.request().getUpstreamSources()).orElseGet(ArrayList::new).stream())
                .map(ProcessSheetRowRequest.UpstreamRef::getSourceBatchNumber)
                .filter(s -> !isBlank(s))
                .collect(Collectors.toSet());

        Map<String, ProductionSettlementRequest.OutputLine> outputs = new LinkedHashMap<>();
        for (ParsedProcessSheetRow parsed : rows) {
            ProcessSheetRow row = parsed.row();
            ProcessSheetRowRequest req = parsed.request();
            BigDecimal output = req.getOutputQuantity();
            if (output == null || output.compareTo(BigDecimal.ZERO) <= 0 || isBlank(row.getBatchNumber())) {
                continue;
            }
            if (consumedBatchNumbers.contains(row.getBatchNumber())) {
                continue;
            }
            String productTypeId = trimToNull(req.getProductTypeId());
            if (isWorkflowPlan(plan)) {
                String expectedTerminalNodeId = pinnedTerminalNodes.get(productTypeId);
                if (expectedTerminalNodeId == null
                        || !expectedTerminalNodeId.equals(trimToNull(req.getMaterialNodeId()))) {
                    continue;
                }
            }
            String unit = firstNonBlank(trimToNull(req.getOutputUnit()), trimToNull(req.getUnit()));
            String key = firstNonBlank(productTypeId, "") + "\u0000" + row.getBatchNumber()
                    + "\u0000" + firstNonBlank(unit, "");
            ProductionSettlementRequest.OutputLine existing = outputs.get(key);
            if (existing == null) {
                outputs.put(key, ProductionSettlementRequest.OutputLine.builder()
                        .productTypeId(productTypeId)
                        .batchNumber(row.getBatchNumber())
                        .quantity(output)
                        .unit(unit)
                        .materialNodeId(trimToNull(req.getMaterialNodeId()))
                        .workflowPortId(trimToNull(req.getWorkflowPortId()))
                        .build());
            } else {
                existing.setQuantity(zeroIfNull(existing.getQuantity()).add(output));
            }
        }
        if (!outputs.isEmpty()) {
            return new ArrayList<>(outputs.values());
        }
        if (isWorkflowPlan(plan)) {
            return List.of();
        }

        Integer maxOrder = rows.stream()
                .map(r -> r.request().getProcessOrder())
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
        List<ProductionSettlementRequest.OutputLine> fallback = new ArrayList<>();
        for (ParsedProcessSheetRow parsed : rows) {
            ProcessSheetRowRequest req = parsed.request();
            boolean inLastStep = maxOrder == null || maxOrder.equals(req.getProcessOrder());
            BigDecimal output = req.getOutputQuantity();
            if (inLastStep && output != null && output.compareTo(BigDecimal.ZERO) > 0) {
                fallback.add(ProductionSettlementRequest.OutputLine.builder()
                        .productTypeId(trimToNull(req.getProductTypeId()))
                        .batchNumber(trimToNull(parsed.row().getBatchNumber()))
                        .quantity(output)
                        .unit(firstNonBlank(trimToNull(req.getOutputUnit()), trimToNull(req.getUnit())))
                        .materialNodeId(trimToNull(req.getMaterialNodeId()))
                        .workflowPortId(trimToNull(req.getWorkflowPortId()))
                        .build());
            }
        }
        return fallback;
    }

    private Map<String, String> resolvePinnedTerminalNodes(String factoryId, ProductionPlan plan) {
        if (!isWorkflowPlan(plan) || bomRecipeRepository == null
                || plan.getSelectedWorkflowRevisionId() == null
                || isBlank(plan.getSelectedWorkflowRevisionHash())
                || isBlank(plan.getSelectedBomFamilyId())) {
            return Map.of();
        }
        Map<String, String> recipeIds = Optional.ofNullable(plan.getSelectedBomRecipeIdsByProduct())
                .orElseGet(Map::of);
        Map<String, Integer> recipeVersions = Optional.ofNullable(plan.getSelectedBomVersionsByProduct())
                .orElseGet(Map::of);
        if (recipeIds.isEmpty() || !recipeIds.keySet().equals(recipeVersions.keySet())) {
            return Map.of();
        }
        Map<String, String> terminals = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : recipeIds.entrySet()) {
            String productTypeId = entry.getKey();
            Integer expectedVersion = recipeVersions.get(productTypeId);
            Optional<BomRecipe> recipe = bomRecipeRepository.findById(entry.getValue())
                    .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                    .filter(candidate -> productTypeId.equals(candidate.getProductTypeId()))
                    .filter(candidate -> Objects.equals(plan.getSelectedBomFamilyId(),
                            candidate.getBomFamilyId()))
                    .filter(candidate -> Objects.equals(plan.getSelectedWorkflowRevisionId(),
                            candidate.getWorkflowRevisionId()))
                    .filter(candidate -> Objects.equals(plan.getSelectedWorkflowRevisionHash(),
                            candidate.getWorkflowRevisionHash()))
                    .filter(candidate -> Objects.equals(expectedVersion, candidate.getVersion()))
                    .filter(candidate -> !isBlank(candidate.getTargetTerminalNodeId()));
            if (recipe.isEmpty()) {
                return Map.of();
            }
            terminals.put(productTypeId, recipe.get().getTargetTerminalNodeId());
        }
        return terminals;
    }

    private BigDecimal sumCompatibleTerminalOutputs(List<ProductionSettlementRequest.OutputLine> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return null;
        }
        Set<String> units = outputs.stream()
                .map(ProductionSettlementRequest.OutputLine::getUnit)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .map(ProductionPlanServiceImpl::canonicalReceiptUnit)
                .collect(Collectors.toSet());
        if (units.size() > 1) {
            return null;
        }
        return outputs.stream()
                .map(ProductionSettlementRequest.OutputLine::getQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean hasCompletePositiveTerminalOutputs(
            List<ProductionSettlementRequest.OutputLine> outputs) {
        return outputs != null
                && !outputs.isEmpty()
                && outputs.stream().allMatch(output ->
                        output != null
                                && !isBlank(output.getProductTypeId())
                                && !isBlank(output.getBatchNumber())
                                && !isBlank(output.getUnit())
                                && output.getQuantity() != null
                                && output.getQuantity().signum() > 0);
    }

    private String resolveTerminalOutputUnit(List<ProductionSettlementRequest.OutputLine> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return null;
        }
        Set<String> units = outputs.stream()
                .map(ProductionSettlementRequest.OutputLine::getUnit)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .map(ProductionPlanServiceImpl::canonicalReceiptUnit)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return units.size() == 1 ? units.iterator().next() : null;
    }

    private String resolveSettlementRequestUnit(ProductionSettlementRequest request) {
        String requestedUnit = canonicalReceiptUnit(trimToNull(request.getQuantityUnit()));
        String terminalUnit = resolveTerminalOutputUnit(request.getTerminalOutputs());
        if (requestedUnit != null && terminalUnit != null && !requestedUnit.equals(terminalUnit)) {
            throw new BusinessException(409, "结单单位与末道产出单位不一致")
                    .withCode("PRODUCTION_SETTLEMENT_OUTPUT_UNIT_MISMATCH")
                    .withHint("结单单位: " + com.cretas.aims.service.unit.UnitDisplayNames.display(requestedUnit)
                            + ", 末道产出单位: " + com.cretas.aims.service.unit.UnitDisplayNames.display(terminalUnit))
                    .withHintTarget("成品产出");
        }
        return firstNonBlank(terminalUnit, requestedUnit);
    }

    private List<ProductionSettlementRequest.ConsumptionLine> deriveRawConsumptionsFromProcessSheetRows(
            String factoryId, List<ParsedProcessSheetRow> rows,
            List<ProductionSettlementPrefillResponse.Issue> issues) {
        String currentPlanId = rows.stream()
                .map(r -> trimToNull(r.row().getPlanId()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        Set<Long> currentProcessBatchIds = rows.stream()
                .map(r -> r.row().getBatchId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, BigDecimal> qtyByBatchAndInputSlot = new LinkedHashMap<>();
        Map<String, String> batchIdByKey = new LinkedHashMap<>();
        Map<String, String> workflowMaterialNodeIdByKey = new LinkedHashMap<>();
        Map<String, String> workflowInputPortIdByKey = new LinkedHashMap<>();
        Map<String, String> reportedUnitByKey = new LinkedHashMap<>();
        for (ParsedProcessSheetRow parsed : rows) {
            List<ProcessSheetRowRequest.RawInput> rawInputs = parsed.request().getRawMaterialInputs();
            if (rawInputs == null) {
                continue;
            }
            for (ProcessSheetRowRequest.RawInput input : rawInputs) {
                if (input == null || isBlank(input.getMaterialBatchId())
                        || input.getQuantity() == null || input.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                String batchId = input.getMaterialBatchId();
                String materialNodeId = trimToNull(input.getMaterialNodeId());
                String inputPortId = trimToNull(input.getWorkflowPortId());
                String reportedUnit = firstNonBlank(
                        trimToNull(input.getUnit()),
                        firstNonBlank(trimToNull(parsed.request().getInputUnit()),
                                firstNonBlank(trimToNull(parsed.request().getUnit()), "kg")));
                String key = batchId + "\u0000" + firstNonBlank(materialNodeId, "")
                        + "\u0000" + firstNonBlank(inputPortId, "")
                        + "\u0000" + reportedUnit;
                batchIdByKey.putIfAbsent(key, batchId);
                workflowMaterialNodeIdByKey.putIfAbsent(key, materialNodeId);
                workflowInputPortIdByKey.putIfAbsent(key, inputPortId);
                reportedUnitByKey.putIfAbsent(key, reportedUnit);
                qtyByBatchAndInputSlot.merge(key, input.getQuantity(), BigDecimal::add);
            }
        }

        List<ProductionSettlementRequest.ConsumptionLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : qtyByBatchAndInputSlot.entrySet()) {
            String batchId = batchIdByKey.get(e.getKey());
            String workflowMaterialNodeId = workflowMaterialNodeIdByKey.get(e.getKey());
            String workflowInputPortId = workflowInputPortIdByKey.get(e.getKey());
            String reportedUnit = reportedUnitByKey.get(e.getKey());
            BigDecimal reportedQuantity = e.getValue();
            MaterialBatch batch = materialBatchRepository.findByIdAndFactoryId(batchId, factoryId).orElse(null);
            if (batch == null) {
                issues.add(issue("RAW_BATCH_NOT_FOUND",
                        "逐道电子表格引用的原料批次 " + batchId + " 已不存在, 未自动带入; 请手工核对该料领用。",
                        "rawMaterialConsumptions"));
                continue;
            }
            String quantityUnit = trimToNull(batch.getQuantityUnit());
            if (quantityUnit == null) {
                issues.add(issue("RAW_BATCH_UNIT_MISSING",
                        "原料批次 " + safeBatchRef(batch) + " 未配置计量单位, 未自动带入; 请先修正批次单位。",
                        "rawMaterialConsumptions"));
                continue;
            }
            BigDecimal qty = convertProcessInputToBatchUnit(reportedQuantity, reportedUnit, quantityUnit);
            if (qty == null) {
                issues.add(issue("RAW_BATCH_UNIT_INCOMPATIBLE",
                        "原料批次 " + safeBatchRef(batch) + " 的库存单位 " + quantityUnit
                                + " 无法与逐道报工单位 " + reportedUnit + " 换算, 未自动带入; 请先修正批次单位。",
                        "rawMaterialConsumptions"));
                continue;
            }
            BigDecimal available = settlementPrefillAvailable(factoryId, currentPlanId, batch);
            PendingConsumptionConflict conflict = resolveOtherPendingConsumption(
                    factoryId, currentPlanId, currentProcessBatchIds, batchId);
            if (conflict.quantity().add(qty).compareTo(available) > 0) {
                BatchHold hold = explainBatchHold(
                        factoryId, currentPlanId, batch, conflict, qty, available, quantityUnit);
                if (hold != null) {
                    issues.add(issue(hold.code(), hold.message() + hold.hint() + "。",
                            "rawMaterialConsumptions"));
                    continue;
                }
                // hold == null ⇒ 没有别的计划待结、也没有任何预留 ⇒ 纯粹是库存不够。
                // 交给下面那道「超过当前可用量」说实话, ⛔ 不要在这里编一个不存在的冲突计划。
            }
            if (available.compareTo(qty) < 0) {
                issues.add(issue("RAW_BATCH_INSUFFICIENT",
                        "原料批次 " + safeBatchRef(batch) + " 逐道领用 " + stripTrailing(reportedQuantity)
                                + reportedUnit + "（库存单位 " + stripTrailing(qty) + quantityUnit + "）超过当前可用量 "
                                + stripTrailing(available) + quantityUnit
                                + ", 未自动带入该行; 请人工核对实际领用批次和数量。",
                        "rawMaterialConsumptions"));
                continue;
            }
            lines.add(ProductionSettlementRequest.ConsumptionLine.builder()
                    .materialBatchId(batchId)
                    .workflowMaterialNodeId(workflowMaterialNodeId)
                    .workflowInputPortId(workflowInputPortId)
                    .materialTypeId(trimToNull(batch.getMaterialTypeId()))
                    .batchNumber(trimToNull(batch.getBatchNumber()))
                    .quantity(qty)
                    .unit(quantityUnit)
                    .warehouseId(trimToNull(batch.getWarehouseId()))
                    .note("自动带入自逐道电子表格")
                    .build());
        }
        return lines;
    }

    private PendingConsumptionConflict resolveOtherPendingConsumption(
            String factoryId, String currentPlanId, Set<Long> currentProcessBatchIds, String materialBatchId) {
        if (materialConsumptionRepository == null || isBlank(materialBatchId)) {
            return PendingConsumptionConflict.NONE;
        }
        BigDecimal quantity = BigDecimal.ZERO;
        Set<String> planRefs = new LinkedHashSet<>();
        List<MaterialConsumption> pending = materialConsumptionRepository
                .findByFactoryIdAndBatchId(factoryId, materialBatchId);
        if (pending == null) {
            return PendingConsumptionConflict.NONE;
        }
        for (MaterialConsumption consumption : pending) {
            if (consumption == null || consumption.getInterimSettledAt() != null
                    || zeroIfNull(consumption.getQuantity()).compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Long productionBatchId = consumption.getProductionBatchId();
            if (productionBatchId != null && currentProcessBatchIds.contains(productionBatchId)) {
                continue;
            }
            String ownerPlanId = trimToNull(consumption.getProductionPlanId());
            if (ownerPlanId == null && productionBatchId != null && processSheetRowRepository != null) {
                ownerPlanId = processSheetRowRepository.findByFactoryIdAndBatchId(factoryId, productionBatchId)
                        .stream()
                        .map(ProcessSheetRow::getPlanId)
                        .map(this::trimToNull)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
            }
            if (currentPlanId != null && currentPlanId.equals(ownerPlanId)) {
                continue;
            }
            quantity = quantity.add(consumption.getQuantity());
            if (ownerPlanId != null) {
                String resolvedOwnerPlanId = ownerPlanId;
                String ref = productionPlanRepository.findByIdAndFactoryId(ownerPlanId, factoryId)
                        .map(p -> firstNonBlank(p.getPlanNumber(), resolvedOwnerPlanId))
                        .orElse(ownerPlanId);
                planRefs.add(ref);
            }
        }
        return new PendingConsumptionConflict(quantity, List.copyOf(planRefs));
    }

    private record PendingConsumptionConflict(BigDecimal quantity, List<String> planRefs) {
        private static final PendingConsumptionConflict NONE =
                new PendingConsumptionConflict(BigDecimal.ZERO, List.of());
    }

    /** 「这批料被谁占着」的一句话裁定 (面向用户的中文文案 + 可执行的下一步)。 */
    private record BatchHold(String code, String message, String hint) {
    }

    /**
     * 🔑 <b>唯一裁定</b>: 这批料现在被谁占着 —— 结单预填和结单校验两处共用。
     *
     * <p><b>它修的是什么</b>: 原来两处各写一份, 而且都在
     * {@code conflict.planRefs().isEmpty()} 时一律说「已被<b>其他未结生产计划</b>待结占用」——
     * 可是走到那一支恰恰意味着 {@code conflict.quantity() == 0}, <b>一个别的计划都没有</b>。
     * 于是文案把人支去找一个不存在的计划, 而真正占着料的是<b>本计划自己已提交的报工</b>
     * (2026-08-18 prod 实测形态: 领料按报工所需量整批建仓, 引用量 == 收货量)。
     *
     * <p><b>三种占用方各说各的, 说不出来就不说</b>:
     * <ol>
     *   <li>{@code conflict.quantity() > 0} → 真的有别的计划待结占用。只有这一支才配说「其他计划」。</li>
     *   <li>本计划自己的在占报工 &gt; 0 → 说清是自己的报工占的, 并告诉用户结单会自动释放。</li>
     *   <li>批次上还有别的预留 → 只说「有预留占用」, ⛔ 不臆造是谁 (batch 上的 reserved 是聚合计数器,
     *       归属信息不在那儿)。</li>
     *   <li>都不是 → 返回 {@code null}, 交给「超过当前可用量」那道闸说实话。</li>
     * </ol>
     *
     * @return {@code null} 表示「没有任何占用方, 就是库存不够」——调用方必须放行到可用量那道闸
     */
    private BatchHold explainBatchHold(String factoryId, String planId, MaterialBatch batch,
                                       PendingConsumptionConflict conflict,
                                       BigDecimal requested, BigDecimal available, String unit) {
        String unitLabel = unit == null ? "" : unit;
        String batchRef = safeBatchRef(batch);
        BigDecimal pending = zeroIfNull(conflict.quantity());
        if (pending.compareTo(BigDecimal.ZERO) > 0) {
            String planRefs = conflict.planRefs().isEmpty()
                    ? "其他未结生产计划"
                    : String.join("、", conflict.planRefs());
            return new BatchHold("RAW_BATCH_CROSS_PLAN_CONFLICT",
                    "原料批次 " + batchRef + " 当前可用 " + stripTrailing(available) + unitLabel
                            + "，但 " + planRefs + " 已待结占用 " + stripTrailing(pending) + unitLabel
                            + "；本计划还需 " + stripTrailing(requested) + unitLabel
                            + "。系统不会让两个计划重复扣同一批原料。",
                    "请先结清 " + planRefs + "，或撤销该计划里报错的那条报工；也可以改用其他批次或补充库存");
        }
        BigDecimal ownHold = workshopReservationService == null || batch == null
                ? BigDecimal.ZERO
                : zeroIfNull(workshopReservationService
                        .reservedByPlanOnBatch(factoryId, planId, batch.getId()));
        if (ownHold.compareTo(BigDecimal.ZERO) > 0) {
            return new BatchHold("RAW_BATCH_HELD_BY_OWN_REPORT",
                    "原料批次 " + batchRef + " 当前可用 " + stripTrailing(available) + unitLabel
                            + "，是被本计划自己已提交的报工占着 " + stripTrailing(ownHold) + unitLabel
                            + "（料还在货架上，没有被别的计划领走）；本计划还需 "
                            + stripTrailing(requested) + unitLabel + "。",
                    "这笔占用会在核对结单时自动释放，直接结单即可；如果那条报工报错了，"
                            + "请先在报工审批里驳回它，或整单撤回后重报");
        }
        BigDecimal reserved = batch == null ? BigDecimal.ZERO : zeroIfNull(batch.getReservedQuantity());
        if (reserved.compareTo(BigDecimal.ZERO) > 0) {
            return new BatchHold("RAW_BATCH_RESERVED_ELSEWHERE",
                    "原料批次 " + batchRef + " 当前可用 " + stripTrailing(available) + unitLabel
                            + "，该批次另有 " + stripTrailing(reserved) + unitLabel
                            + " 处于预留占用（料在货架上未领走）；本计划还需 "
                            + stripTrailing(requested) + unitLabel + "。",
                    "请到原料批次页核对这笔预留并释放，或改用其他批次");
        }
        return null;
    }

    private BigDecimal convertProcessInputToBatchUnit(
            BigDecimal quantity, String reportedUnit, String batchUnit) {
        if (quantity == null || reportedUnit == null || batchUnit == null) {
            return null;
        }
        if (unitConversionService != null) {
            BigDecimal converted = unitConversionService.convert(quantity, reportedUnit, batchUnit);
            if (converted != null) {
                return converted.stripTrailingZeros();
            }
        }
        BigDecimal kilograms = toKilograms(quantity, reportedUnit);
        if (kilograms == null) {
            return null;
        }
        return fromKilograms(kilograms, batchUnit);
    }

    private BigDecimal toKilograms(BigDecimal quantity, String unit) {
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "kg", "kilogram", "公斤", "千克" -> quantity;
            case "g", "gram", "克" -> quantity.divide(new BigDecimal("1000"), 9, RoundingMode.HALF_UP);
            case "t", "ton", "吨" -> quantity.multiply(new BigDecimal("1000"));
            default -> null;
        };
    }

    private BigDecimal fromKilograms(BigDecimal kilograms, String unit) {
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "kg", "kilogram", "公斤", "千克" -> kilograms.stripTrailingZeros();
            case "g", "gram", "克" -> kilograms.multiply(new BigDecimal("1000")).stripTrailingZeros();
            case "t", "ton", "吨" -> kilograms.divide(new BigDecimal("1000"), 9, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            default -> null;
        };
    }

    private List<ProductionSettlementRequest.LaborSegment> deriveLaborSegmentsFromProcessSheetRows(
            List<ParsedProcessSheetRow> rows) {
        List<ProductionSettlementRequest.LaborSegment> segments = new ArrayList<>();
        for (ParsedProcessSheetRow parsed : rows) {
            List<com.cretas.aims.dto.processentry.LaborSegment> raw = parsed.request().getLaborSegments();
            if (raw == null) {
                continue;
            }
            for (com.cretas.aims.dto.processentry.LaborSegment seg : raw) {
                Integer minutes = deriveSegmentMinutes(seg);
                if (minutes == null || minutes <= 0) {
                    continue;
                }
                Integer headcount = seg.getWorkerCount();
                segments.add(ProductionSettlementRequest.LaborSegment.builder()
                        .workType(firstNonBlank(parsed.request().getProcessName(), parsed.request().getProcessCode(), "逐道电子表格"))
                        .minutes(minutes)
                        .headcount(headcount)
                        .build());
            }
        }
        return segments;
    }

    private Integer deriveSegmentMinutes(com.cretas.aims.dto.processentry.LaborSegment seg) {
        if (seg == null || isBlank(seg.getStartTime()) || isBlank(seg.getEndTime())) {
            return null;
        }
        try {
            java.time.LocalTime start = java.time.LocalTime.parse(seg.getStartTime());
            java.time.LocalTime end = java.time.LocalTime.parse(seg.getEndTime());
            long minutes = java.time.Duration.between(start, end).toMinutes();
            if (minutes < 0) {
                minutes += 24 * 60;
            }
            return minutes > 0 ? (int) minutes : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private record ParsedProcessSheetRow(ProcessSheetRow row, ProcessSheetRowRequest request) {
    }

    /** 末道工序产出量: 取最大 processOrder 的全部报工, 汇总其 outputQuantity。 */
    /**
     * 末道工序序号 —— {@link #deriveLastStepOutput} 与
     * {@link #deriveTerminalOutputsFromReports} 共用，⛔ 不要各写一份。
     *
     * <p>返回 null 表示报工上没有 processOrder（老数据），调用方按「全部都算末道」退化。
     */
    private static Integer lastStepOrder(List<ProductionReport> reports) {
        Integer maxOrder = null;
        for (ProductionReport r : reports) {
            Integer order = r.getProcessOrder();
            if (order != null && (maxOrder == null || order > maxOrder)) {
                maxOrder = order;
            }
        }
        return maxOrder;
    }

    /**
     * 末道产出 → {@code terminalOutputs}（App 报工那条路径）。
     *
     * <h3>🔴 为什么必须有它（2026-08-18 prod 实测）</h3>
     * {@code getSettlementPrefill} 有两条腿：web 逐道电子表格、App 逐道报工。
     * <b>web 那条建了 {@code terminalOutputs}，App 这条没建</b> ——
     * {@code @Builder.Default} 让它是空 list，于是 {@code persistWorkflowSettlementOutputs}
     * 看到 {@code reportedOutputs.isEmpty()} 就抛 {@code 409 WORKFLOW_OUTPUT_SET_MISMATCH}。
     *
     * <p>后果：工人在 App 把每道工都报完、任务 COMPLETED、审批也过了，
     * 文员点「核对结单」仍然结不了单 —— 这正是判据里那种「为什么点不动」。
     *
     * <p>⚠️ 那句报错还会误导人：它说「提交的末道产出与计划固定的 Workflow 末道集合不一致」，
     * 听起来像<b>用户传错了</b>；实际是<b>服务端自己推导为空</b>（workflow 计划的结单请求
     * 会被 {@code deriveConfirmedSettlementRequest} 整个替换掉，客户端传什么都不参与判定）。
     * 判别实验：不传 / 传对 / 传错 {@code terminalOutputs} 三次报错完全相同。
     *
     * <h3>口径</h3>
     * <ul>
     *   <li>只取<b>末道</b>工序的报工（与 {@link #deriveLastStepOutput} 同一个判定，共用 helper）</li>
     *   <li>按 <b>(SKU, 单位)</b> 分组求和 —— ⛔ 不跨单位相加（同一 SKU 出现 kg 和 盒 时
     *       各自成行，让上游的「多单位」提示照常出得来）</li>
     *   <li>报工没带 productTypeId 时回落计划的产品；<b>两者都没有就跳过</b>
     *       —— 造一条 SKU 为空的产出行会让下游的集合比对恒不相等</li>
     * </ul>
     */
    private List<ProductionSettlementRequest.OutputLine> deriveTerminalOutputsFromReports(
            String factoryId, ProductionPlan plan, List<ProductionBatch> batches,
            List<ProductionReport> reports, List<ProductionSettlementPrefillResponse.Issue> issues) {
        if (reports == null || reports.isEmpty()) {
            return new ArrayList<>();
        }
        Integer maxOrder = lastStepOrder(reports);
        String planProduct = plan == null ? null : trimToNull(plan.getProductTypeId());

        // 产出行的批次身份 = 该报工所属【生产批次】的批次号。
        // web 那条腿用的是 process_sheet_row.batch_number, 而那张表的 batch_number 正是同一个东西
        // (见 ProcessSheetRow 类注释: 行锚在 batch_id / batch_number 上) —— 两条腿口径一致。
        Map<String, String> batchNumbers = new LinkedHashMap<>();
        if (batches != null) {
            for (ProductionBatch b : batches) {
                if (b != null && b.getId() != null && !isBlank(b.getBatchNumber())) {
                    batchNumbers.put(String.valueOf(b.getId()), trimToNull(b.getBatchNumber()));
                }
            }
        }

        // 末道物料节点: 用既有权威 resolvePinnedTerminalNodes (web 那条腿拿它做过滤, 这里拿它填)。
        // ⚠️ 工序任务上的 workflowNodeId 是【工序节点】(process:…), 而配方钉的是【物料节点】
        //    (material:finished:…) —— 两者不是一个东西, 直接拿任务的去比会永远不等。实测:
        //    task.workflow_node_id = process:36be0c48-…:1786933141612
        //    recipe.target_terminal_node_id = material:finished:1786933141612
        Map<String, String> pinnedTerminals = resolvePinnedTerminalNodes(factoryId, plan);
        boolean pinned = isWorkflowPlan(plan) && !pinnedTerminals.isEmpty();

        // key = SKU + NUL + 批次号 + NUL + 单位 —— 与 web 那条腿【同一个键形状】(形态 D: 不各写一份)。
        // 分隔符用 NUL: 与本文件既有写法一致(见 workflowInputPortId 那处), 它不可能出现在业务串里,
        // 避免 "A"+"BC" 与 "AB"+"C" 撞成同一个 key。
        Map<String, ProductionSettlementRequest.OutputLine> byLine = new LinkedHashMap<>();
        for (ProductionReport r : reports) {
            if (r == null) {
                continue;
            }
            if (maxOrder != null && !maxOrder.equals(r.getProcessOrder())) {
                continue;
            }
            BigDecimal qty = r.getOutputQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String sku = firstNonBlank(trimToNull(r.getProductTypeId()), planProduct);
            if (isBlank(sku)) {
                continue;   // 空 SKU 的产出行会让下游集合比对恒不相等
            }
            // ⛔ 不在钉住集合里的 SKU 不许出线, 也不许静默丢掉 —— 明说是哪一个, 让人能去核对。
            //    (这一条替代了「materialNodeId 由用户传、服务端过滤」那道闸: 本腿没有用户输入,
            //     真正在守的是「只有计划钉住的末道 SKU 才会被算成成品产出」。)
            String terminalNodeId = pinnedTerminals.get(sku);
            if (pinned && terminalNodeId == null) {
                addIssueOnce(issues, "WORKFLOW_OUTPUT_SKU_NOT_PINNED",
                        "末道报工的产品 " + sku + " 不在本计划钉住的 Workflow 末道产出集合里, 未带入成品产出; "
                                + "请核对报工选的产品, 或确认该产出是否应计入本计划。",
                        "terminalOutputs");
                continue;
            }
            String batchNumber = r.getBatchId() == null
                    ? null : batchNumbers.get(String.valueOf(r.getBatchId()));
            if (isBlank(batchNumber)) {
                addIssueOnce(issues, "WORKFLOW_OUTPUT_BATCH_MISSING",
                        "末道报工找不到对应的生产批次号, 无法带入成品产出; 请在产出核对处手工填写批次号。",
                        "terminalOutputs");
                continue;
            }
            String unit = trimToNull(r.getOutputUnit());
            String key = sku + "\0" + batchNumber + "\0" + (unit == null ? "" : unit);
            ProductionSettlementRequest.OutputLine line = byLine.get(key);
            if (line == null) {
                byLine.put(key, ProductionSettlementRequest.OutputLine.builder()
                        .productTypeId(sku)
                        .batchNumber(batchNumber)
                        .quantity(qty)
                        .unit(unit)
                        .materialNodeId(terminalNodeId)
                        .build());
            } else {
                line.setQuantity(line.getQuantity().add(qty));
            }
        }
        return new ArrayList<>(byLine.values());
    }

    /** 同一个 code 只记一次 —— 同一批报工里多行触发同一个原因时, 不要刷屏。 */
    private void addIssueOnce(List<ProductionSettlementPrefillResponse.Issue> issues,
            String code, String message, String field) {
        if (issues == null) {
            return;
        }
        for (ProductionSettlementPrefillResponse.Issue existing : issues) {
            if (existing != null && code.equals(existing.getCode())) {
                return;
            }
        }
        issues.add(issue(code, message, field));
    }

    private BigDecimal deriveLastStepOutput(List<ProductionReport> reports) {
        Integer maxOrder = lastStepOrder(reports);
        BigDecimal sum = null;
        for (ProductionReport r : reports) {
            // maxOrder 为 null (报工无 processOrder) → 退化为汇总全部 outputQuantity
            boolean inLastStep = maxOrder == null
                    ? true
                    : maxOrder.equals(r.getProcessOrder());
            if (!inLastStep) {
                continue;
            }
            BigDecimal out = r.getOutputQuantity();
            if (out != null) {
                sum = sum == null ? out : sum.add(out);
            }
        }
        return sum;
    }

    /**
     * 从各道报工的 materialBatchRefs 聚合原料领用 (按 materialBatchId 求和)。
     * <p>materialBatchRefs JSON 元素: {"materialBatchId": Long, "quantity": Number, "unit": String|null}。
     * <p>每个 batch 必须仍存在 + 在原料仓 + 在当前 BOM + 可用量足够, 否则不臆造该行 (settle 会拒),
     * 而是<b>留空 + 标 issue</b> 让人核对补录 (宁可少填不可瞎填)。
     */
    private List<ProductionSettlementRequest.ConsumptionLine> deriveRawConsumptions(
            String factoryId, ProductionPlan plan, List<ProductionReport> reports,
            List<ProductionSettlementPrefillResponse.Issue> issues) {
        // 聚合: materialBatchId(String) → 累计数量
        Map<String, BigDecimal> qtyByBatch = new LinkedHashMap<>();
        Map<String, String> unitByBatch = new LinkedHashMap<>();
        for (ProductionReport r : reports) {
            List<Map<String, Object>> refs = r.getMaterialBatchRefs();
            if (refs == null) {
                continue;
            }
            for (Map<String, Object> ref : refs) {
                if (ref == null) {
                    continue;
                }
                String batchId = asString(ref.get("materialBatchId"));
                BigDecimal qty = asBigDecimal(ref.get("quantity"));
                if (isBlank(batchId) || qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                qtyByBatch.merge(batchId, qty, BigDecimal::add);
                String unit = asString(ref.get("unit"));
                if (unit != null && !unitByBatch.containsKey(batchId)) {
                    unitByBatch.put(batchId, unit);
                }
            }
        }

        List<ProductionSettlementRequest.ConsumptionLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : qtyByBatch.entrySet()) {
            String batchId = e.getKey();
            BigDecimal qty = e.getValue();
            MaterialBatch batch = materialBatchRepository.findByIdAndFactoryId(batchId, factoryId).orElse(null);
            if (batch == null) {
                issues.add(issue("RAW_BATCH_NOT_FOUND",
                        "报工记录引用的原料批次 " + batchId + " 已不存在, 未自动带入; 请手工核对该料领用。",
                        "rawMaterialConsumptions"));
                continue;
            }
            BigDecimal available = settlementPrefillAvailable(factoryId, plan.getId(), batch);
            if (available.compareTo(qty) < 0) {
                // 报工领用量 > 当前可用 (批次已被部分消耗/标 USED_UP): 不臆造, 让人核对
                issues.add(issue("RAW_BATCH_INSUFFICIENT",
                        "原料批次 " + safeBatchRef(batch) + " 报工领用 " + stripTrailing(qty)
                                + " 超过当前可用量 " + stripTrailing(available)
                                + ", 未自动带入该行; 请人工核对实际领用批次/数量。",
                        "rawMaterialConsumptions"));
                continue;
            }
            // 不在此预填阶段强校验 BOM/原料仓 (settle 时会校验); 但若已知不在原料仓, 提示让人换批次
            ProductionSettlementRequest.ConsumptionLine line = ProductionSettlementRequest.ConsumptionLine.builder()
                    .materialBatchId(batchId)
                    .materialTypeId(trimToNull(batch.getMaterialTypeId()))
                    .batchNumber(trimToNull(batch.getBatchNumber()))
                    .quantity(qty)
                    .unit(firstNonBlank(unitByBatch.get(batchId), batch.getQuantityUnit()))
                    .warehouseId(trimToNull(batch.getWarehouseId()))
                    .note("自动带入自逐道报工")
                    .build();
            lines.add(line);
        }
        return lines;
    }

    /**
     * 结单预填看到的可用量 = 当前可用 + 本计划自己真实挂在批次上的报工预留。
     *
     * <p>正式结单会在三道库存闸之前释放本计划预留；预填若仍只读
     * {@link MaterialBatch#getCurrentQuantity()}，整批预留时就会把自己的领用行过滤掉，
     * 让前端连提交都发不出来。只加回“报工可归属量”和“批次实有 reserved”两者较小值，
     * 因而不会认领其他计划/未知来源的预留，也不会仅凭报工 JSON 虚增可用量。</p>
     */
    private BigDecimal settlementPrefillAvailable(String factoryId, String planId, MaterialBatch batch) {
        BigDecimal available = batch == null
                ? BigDecimal.ZERO
                : zeroIfNull(batch.getCurrentQuantity());
        if (batch == null || isBlank(planId) || workshopReservationService == null) {
            return available;
        }
        BigDecimal ownHold = zeroIfNull(workshopReservationService
                .reservedByPlanOnBatch(factoryId, planId, batch.getId()));
        BigDecimal actualReserved = zeroIfNull(batch.getReservedQuantity());
        return available.add(ownHold.min(actualReserved));
    }

    /**
     * 从各道报工聚合工时段。优先用 report.laborSegments (多时段×人数);
     * 缺失时回退到单一 totalWorkMinutes + totalWorkers。derive 不出任何工时 → 返回空列表。
     */
    private List<ProductionSettlementRequest.LaborSegment> deriveLaborSegments(List<ProductionReport> reports) {
        List<ProductionSettlementRequest.LaborSegment> segments = new ArrayList<>();
        for (ProductionReport r : reports) {
            String workType = trimToNull(r.getProcessCategory());
            List<Map<String, Object>> raw = r.getLaborSegments();
            boolean addedFromRaw = false;
            if (raw != null) {
                for (Map<String, Object> seg : raw) {
                    if (seg == null) {
                        continue;
                    }
                    Integer minutes = deriveSegmentMinutes(seg);
                    if (minutes == null || minutes <= 0) {
                        continue;
                    }
                    Integer headcount = asInteger(seg.get("headcount"));
                    segments.add(ProductionSettlementRequest.LaborSegment.builder()
                            .workType(firstNonBlank(workType, "逐道报工"))
                            .minutes(minutes)
                            .headcount(headcount)
                            .note(asString(seg.get("note")))
                            .build());
                    addedFromRaw = true;
                }
            }
            if (!addedFromRaw) {
                Integer minutes = r.getTotalWorkMinutes();
                if (minutes != null && minutes > 0) {
                    Integer headcount = r.getTotalWorkers();
                    segments.add(ProductionSettlementRequest.LaborSegment.builder()
                            .workType(firstNonBlank(workType, "逐道报工"))
                            .minutes(minutes)
                            .headcount(headcount)
                            .build());
                }
            }
        }
        return segments;
    }

    /** 工时段分钟: 直接读 minutes, 否则按 startTime/endTime 差值 (HH:mm) 计算。无法算 → null。 */
    private Integer deriveSegmentMinutes(Map<String, Object> seg) {
        Integer minutes = asInteger(seg.get("minutes"));
        if (minutes != null) {
            return minutes;
        }
        String start = asString(seg.get("startTime"));
        String end = asString(seg.get("endTime"));
        if (start == null || end == null) {
            return null;
        }
        try {
            java.time.LocalTime s = java.time.LocalTime.parse(start);
            java.time.LocalTime e = java.time.LocalTime.parse(end);
            long mins = java.time.Duration.between(s, e).toMinutes();
            if (mins < 0) {
                mins += 24 * 60;
            }
            return mins > 0 ? (int) mins : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private ProductionSettlementRequest emptyPrefill(ProductionPlan plan) {
        return ProductionSettlementRequest.builder()
                .idempotencyKey(null)
                .actualFinishedQuantity(null)
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .quantityUnit(null) // ProductionPlan 无单位字段; 前端提交时从行数据(unit/quantityUnit)补
                .rawMaterialConsumptions(new ArrayList<>())
                .semiFinishedConsumptions(new ArrayList<>())
                .auxiliaryConsumptions(new ArrayList<>())
                .laborSegments(new ArrayList<>())
                .build();
    }

    private ProductionSettlementPrefillResponse buildPrefillResponse(
            ProductionSettlementRequest prefill,
            List<ProductionSettlementPrefillResponse.Issue> issues) {
        // clean 只看 BLOCKER 级 — INFO 级提示 (如跨计划半成品确认) 不阻塞一键确认
        boolean clean = issues.stream()
                .noneMatch(i -> i.getSeverity() == ProductionSettlementPrefillResponse.Severity.BLOCKER);
        return ProductionSettlementPrefillResponse.builder()
                .prefill(prefill)
                .audit(ProductionSettlementPrefillResponse.Audit.builder()
                        .clean(clean)
                        .issues(issues)
                        .build())
                .build();
    }

    /** BLOCKER 级 issue (令 clean=false, 阻塞一键确认)。 */
    private ProductionSettlementPrefillResponse.Issue issue(String code, String message, String field) {
        return ProductionSettlementPrefillResponse.Issue.builder()
                .code(code).message(message).field(field)
                .severity(ProductionSettlementPrefillResponse.Severity.BLOCKER).build();
    }

    /** INFO 级 issue (仅提示, 不阻塞一键确认)。 */
    private ProductionSettlementPrefillResponse.Issue infoIssue(String code, String message, String field) {
        return ProductionSettlementPrefillResponse.Issue.builder()
                .code(code).message(message).field(field)
                .severity(ProductionSettlementPrefillResponse.Severity.INFO).build();
    }

    private String safeBatchRef(MaterialBatch batch) {
        if (batch == null) {
            return "?";
        }
        return !isBlank(batch.getBatchNumber()) ? batch.getBatchNumber() : String.valueOf(batch.getId());
    }

    private String firstNonBlank(String a, String b) {
        if (!isBlank(a)) {
            return a.trim();
        }
        return trimToNull(b);
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String stripTrailing(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionSettlementResponse getProductionSettlement(String factoryId, String planId) {
        if (visiblePlanCreators(factoryId) != null) {
            ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
            requireVisiblePlan(factoryId, plan);
        }
        // 🔴 2026-08-19: 这里原来是 requireProductionSettlement(...) —— 没有结单行就抛 404。
        //    那是把**合法空态**当成了错误 (存货生产走小结, 这张表里本来就没有行),
        //    而列表批量拉状态时会把它弹成全局红条, 报的还是**别的计划**。
        //    ⇒ 读接口如实回答 settled=false。
        //    ⚠️ 写路径 (confirmWarehouseReceipt / bridgeByStockSettlement / stopProduction)
        //       仍然走 bridge→404, 一个字没改 —— 那里拦住是对的。
        if (productionSettlementRepository == null) {
            throw new BusinessException(500, "生产结单服务未初始化")
                    .withHint("请确认 production_settlements migration 已执行")
                    .withHintTarget("生产结单");
        }
        return productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId)
                .map(settlement -> toSettlementResponse(settlement, Collections.emptyList()))
                .orElseGet(() -> notSettledResponse(factoryId, planId));
    }

    @Override
    @Transactional
    public ProductionSettlementResponse bridgeByStockSettlement(String factoryId, String planId) {
        if (visiblePlanCreators(factoryId) != null) {
            ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
            requireVisiblePlan(factoryId, plan);
        }
        ProductionSettlement settlement = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId)
                .orElseGet(() -> bridgeProductionSettlementForWarehouse(factoryId, planId));
        return toSettlementResponse(settlement, Collections.emptyList());
    }

    @Override
    @Transactional
    public ProductionWarehouseReceiptResponse confirmWarehouseReceipt(String factoryId, String planId,
                                                                      ProductionWarehouseReceiptRequest request,
                                                                      Long receivedBy) {
        if (request == null) {
            throw new BusinessException(400, "仓库确认内容不能为空").withHintTarget("仓库实收");
        }
        if (isBlank(request.getIdempotencyKey())) {
            throw new BusinessException(400, "缺少幂等键 idempotencyKey")
                    .withHint("请刷新页面后重试, 系统会自动生成仓库确认提交键")
                    .withHintTarget("仓库实收");
        }
        ensurePostingDependencies();

        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);
        ProductionSettlement settlement = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdForUpdate(factoryId, planId)
                .orElseGet(() -> bridgeProductionSettlementForWarehouse(factoryId, planId));

        if (settlement.getWarehouseReceivedAt() != null) {
            if (plan.getSourceType() == PlanSourceType.SAFETY_STOCK) {
                return toWarehouseReceiptResponse(settlement,
                        "该存货生产计划已随小结完成入库，无需重复仓库确认",
                        Collections.emptyList());
            }
            if (request.getIdempotencyKey().equals(settlement.getWarehouseReceiptIdempotencyKey())) {
                return toWarehouseReceiptResponse(settlement, "该仓库确认请求已提交过, 已返回原确认结果",
                        Collections.emptyList());
            }
            throw new BusinessException(409, "该生产结单已由仓库确认, 不能重复入库")
                    .withCode("PRODUCTION_RECEIPT_ALREADY_CONFIRMED")
                    .withHint("请刷新生产计划列表查看最新入库状态")
                    .withHintTarget("仓库实收");
        }

        if (isWorkflowPlan(plan)) {
            ensureWorkflowReceiptAuthority(plan);
            if (productionSettlementOutputLineRepository == null) {
                throw new BusinessException(500, "Workflow output receipt service is unavailable")
                        .withCode("WORKFLOW_RECEIPT_SERVICE_UNAVAILABLE")
                        .withHint("Verify the production settlement output-line migration and service wiring");
            }
            List<ProductionSettlementOutputLine> workflowOutputLines =
                    productionSettlementOutputLineRepository
                            .lockByFactoryIdAndSettlementId(factoryId, settlement.getId());
            if (workflowOutputLines.isEmpty()) {
                throw new BusinessException(409,
                        "Workflow settlement has no authoritative terminal output lines")
                        .withCode("WORKFLOW_RECEIPT_OUTPUT_LINES_REQUIRED")
                        .withHint("Rebuild the settlement from submitted process reports; the raw/owner SKU will not be received");
            }
            validateWorkflowReceiptOutputAuthority(plan, workflowOutputLines);
            return confirmWorkflowOutputReceipt(
                    factoryId, plan, settlement, workflowOutputLines, request, receivedBy);
        }

        BigDecimal reported = zeroIfNull(settlement.getActualFinishedQuantity());
        BigDecimal received = zeroIfNull(request.getReceivedQuantity());
        if (received.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "仓库实收数量必须大于 0").withHintTarget("仓库实收");
        }
        if (reported.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(409, "该结单没有成品产量, 不能确认成品入库")
                    .withHint("请确认是否只产半成品, 或先修正生产结单")
                    .withHintTarget("仓库实收");
        }
        if (received.compareTo(reported) > 0) {
            throw new BusinessException(409, "仓库实收数量不能超过生产报产数量")
                    .withCode("RECEIPT_EXCEEDS_PRODUCTION_REPORTED")
                    .withHint("生产报产: " + reported + ", 仓库实收: " + received + "; 请先让生产修正结单或拆分补录")
                    .withHintTarget("仓库实收");
        }

        String authoritativeUnit = resolveSettlementQuantityUnit(settlement);
        String requestedUnit = canonicalReceiptUnit(trimToNull(request.getQuantityUnit()));
        if (authoritativeUnit != null && requestedUnit != null && !authoritativeUnit.equals(requestedUnit)) {
            throw new BusinessException(409, "仓库实收单位与生产报产单位不一致")
                    .withCode("PRODUCTION_RECEIPT_UNIT_MISMATCH")
                    .withHint("生产报产单位: " + authoritativeUnit + ", 仓库实收单位: " + requestedUnit)
                    .withHintTarget("仓库实收");
        }
        String unit = firstNonBlank(authoritativeUnit, requestedUnit);
        if (unit == null) {
            throw new BusinessException(409, "生产结单缺少可核验的成品计量单位，不能确认入库")
                    .withCode("PRODUCTION_RECEIPT_UNIT_REQUIRED")
                    .withHint("请核对末道正式报工的产出单位；系统未改动成品库存")
                    .withHintTarget("仓库实收");
        }
        BigDecimal variance = reported.subtract(received);
        BigDecimal tolerance = receiptTolerance(unit);
        boolean hasVariance = variance.compareTo(BigDecimal.ZERO) != 0;
        boolean withinTolerance = hasVariance && variance.abs().compareTo(tolerance) <= 0 && tolerance.compareTo(BigDecimal.ZERO) > 0;
        boolean needsTransitLedger = hasVariance && !withinTolerance;

        if (needsTransitLedger && isBlank(request.getVarianceReason())) {
            throw new BusinessException(409, "仓库实收与生产报产不一致, 必须选择差异原因")
                    .withCode("PRODUCTION_RECEIPT_VARIANCE_REASON_REQUIRED")
                    .withHint("生产报产: " + reported + unit + ", 仓库实收: " + received + unit + ", 差异: " + variance + unit)
                    .withHintTarget("差异原因");
        }
        if (needsTransitLedger && isPendingResponsibilitySide(request.getResponsibilitySide())) {
            throw new BusinessException(409, "仓库实收差异超出容差, 必须明确责任侧")
                    .withCode("PRODUCTION_RECEIPT_RESPONSIBILITY_REQUIRED")
                    .withHint("请选择生产侧处理、仓库侧处理或称重误差后再确认入库")
                    .withHintTarget("责任侧");
        }

        FinishedGoodsBatch fgBatch = plan.getSourceType() == PlanSourceType.SAFETY_STOCK
                ? requireInterimFinishedGoodsForReceipt(plan, settlement, reported, received, unit)
                : createFinishedGoodsFromReceipt(plan, settlement, received, unit, receivedBy);
        ProductionTransitLedger ledger = needsTransitLedger
                ? createTransitLedger(settlement, reported, received, variance, tolerance, unit, request, receivedBy)
                : null;

        settlement.setWarehouseReceiptIdempotencyKey(request.getIdempotencyKey());
        settlement.setWarehouseReceivedQuantity(received);
        settlement.setWarehouseVarianceQuantity(variance);
        settlement.setQuantityUnit(unit);
        settlement.setWarehouseVarianceReason(trimToNull(request.getVarianceReason()));
        settlement.setWarehouseResponsibilitySide(needsTransitLedger
                ? trimToNull(request.getResponsibilitySide())
                : (withinTolerance ? "WEIGHING_ERROR" : null));
        settlement.setWarehouseVarianceNote(trimToNull(request.getVarianceNote()));
        settlement.setFinishedGoodsBatchId(fgBatch.getId());
        settlement.setTransitLedgerId(ledger != null ? ledger.getId() : null);
        settlement.setWarehouseReceivedBy(receivedBy);
        settlement.setWarehouseReceivedAt(LocalDateTime.now());
        if (needsTransitLedger) {
            settlement.setPostingStatus("PENDING_CLEARING");
            settlement.setPostingMessage("仓库已确认实收并生成成品库存; 生产报产与仓库实收存在差异, 已进入中转挂账");
        } else if (withinTolerance) {
            settlement.setPostingStatus("POSTED_WITH_TOLERANCE");
            settlement.setPostingMessage("仓库已确认实收并生成成品库存; 差异在10kg称重容差内, 不生成中转挂账");
        } else {
            settlement.setPostingStatus("POSTED");
            settlement.setPostingMessage("仓库已确认实收并生成成品库存");
        }
        productionSettlementRepository.save(settlement);
        int completedBatchCount = markReceiptProductionBatchesCompleted(factoryId, planId, settlement);

        List<String> warnings = new ArrayList<>();
        if (needsTransitLedger) {
            warnings.add("差异已进入中转挂账, 需继续判断责任归属并清账");
        } else if (withinTolerance) {
            warnings.add("差异在称重容差内, 本次不生成中转挂账");
        }
        if (completedBatchCount > 0) {
            warnings.add("已同步 " + completedBatchCount + " 个逐道报工成品批次为已完工, 可进入成品出厂核算");
        }
        return toWarehouseReceiptResponse(settlement, settlement.getPostingMessage(), warnings);
    }

    private void ensureWorkflowReceiptAuthority(ProductionPlan plan) {
        Map<String, String> recipes = Optional.ofNullable(plan.getSelectedBomRecipeIdsByProduct())
                .orElseGet(Map::of);
        Map<String, Integer> versions = Optional.ofNullable(plan.getSelectedBomVersionsByProduct())
                .orElseGet(Map::of);
        Map<String, String> units = Optional.ofNullable(plan.getWorkflowOutputUnitsByProduct())
                .orElseGet(Map::of);
        Set<String> targets = Optional.ofNullable(plan.getTargetFinishedGoodIds())
                .orElseGet(ArrayList::new).stream()
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean incomplete = plan.getSelectedWorkflowRevisionId() == null
                || isBlank(plan.getSelectedWorkflowRevisionHash())
                || isBlank(plan.getSelectedBomFamilyId())
                || recipes.isEmpty()
                || recipes.values().stream().anyMatch(value -> isBlank(value))
                || !recipes.keySet().equals(versions.keySet())
                || versions.values().stream().anyMatch(Objects::isNull)
                || !recipes.keySet().equals(units.keySet())
                || units.values().stream().anyMatch(value -> isBlank(value))
                || !recipes.keySet().equals(targets);
        if (incomplete) {
            throw new BusinessException(409,
                    "Workflow plan does not contain complete immutable receipt authority")
                    .withCode("WORKFLOW_RECEIPT_AUTHORITY_INCOMPLETE")
                    .withHint("Create a new plan from a published Workflow revision and its active BOM family; the raw/owner SKU will not be received");
        }
    }

    private void validateWorkflowReceiptOutputAuthority(
            ProductionPlan plan, List<ProductionSettlementOutputLine> lines) {
        Map<String, String> recipes = plan.getSelectedBomRecipeIdsByProduct();
        Map<String, Integer> versions = plan.getSelectedBomVersionsByProduct();
        Map<String, String> units = plan.getWorkflowOutputUnitsByProduct();
        Set<String> reportedSkus = lines.stream()
                .map(ProductionSettlementOutputLine::getProductTypeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean mismatch = !reportedSkus.equals(recipes.keySet())
                || lines.stream().anyMatch(line ->
                        !Objects.equals(plan.getSelectedBomFamilyId(), line.getBomFamilyId())
                                || !Objects.equals(recipes.get(line.getProductTypeId()),
                                        line.getBomRecipeId())
                                || !Objects.equals(versions.get(line.getProductTypeId()),
                                        line.getBomRecipeVersion())
                                || !Objects.equals(
                                        canonicalReceiptUnit(units.get(line.getProductTypeId())),
                                        canonicalReceiptUnit(line.getQuantityUnit())));
        if (mismatch) {
            throw new BusinessException(409,
                    "Workflow settlement output lines do not match the plan's pinned authority")
                    .withCode("WORKFLOW_RECEIPT_OUTPUT_AUTHORITY_MISMATCH")
                    .withHint("Rebuild the settlement from the exact submitted reports and pinned BOM family");
        }
    }

    private ProductionWarehouseReceiptResponse confirmWorkflowOutputReceipt(
            String factoryId,
            ProductionPlan plan,
            ProductionSettlement settlement,
            List<ProductionSettlementOutputLine> outputLines,
            ProductionWarehouseReceiptRequest request,
            Long receivedBy) {
        if (outputLines.stream().anyMatch(line ->
                request.getIdempotencyKey().equals(line.getReceiptIdempotencyKey()))) {
            return toWarehouseReceiptResponse(settlement,
                    "该仓库分次入库请求已提交过, 已返回当前累计结果",
                    Collections.emptyList());
        }
        List<ProductionWarehouseReceiptRequest.ReceiptLine> receiptLines =
                Optional.ofNullable(request.getOutputLines()).orElseGet(ArrayList::new);
        if (receiptLines.size() != outputLines.size()) {
            throw new BusinessException(409,
                    "Warehouse receipt must contain exactly one line for every reported terminal output batch")
                    .withCode("WORKFLOW_RECEIPT_OUTPUT_SET_MISMATCH");
        }
        Map<String, ProductionWarehouseReceiptRequest.ReceiptLine> receiptsByKey = new LinkedHashMap<>();
        for (ProductionWarehouseReceiptRequest.ReceiptLine receipt : receiptLines) {
            String key = outputReceiptKey(receipt.getProductTypeId(), receipt.getBatchNumber(),
                    receipt.getQuantityUnit());
            if (receiptsByKey.putIfAbsent(key, receipt) != null) {
                throw new BusinessException(409, "Duplicate Workflow warehouse receipt output line")
                        .withCode("WORKFLOW_RECEIPT_OUTPUT_DUPLICATE");
            }
        }

        Map<String, BigDecimal> receivedByUnit = new LinkedHashMap<>();
        Map<String, BigDecimal> receiptDeltaByKey = new LinkedHashMap<>();
        for (ProductionSettlementOutputLine output : outputLines) {
            String key = outputReceiptKey(output.getProductTypeId(), output.getReportedBatchNumber(),
                    output.getQuantityUnit());
            ProductionWarehouseReceiptRequest.ReceiptLine receipt = receiptsByKey.remove(key);
            if (receipt == null) {
                throw new BusinessException(409, "Workflow warehouse receipt output line is missing")
                        .withCode("WORKFLOW_RECEIPT_OUTPUT_SET_MISMATCH")
                        .withHint("Missing SKU/batch: " + output.getProductTypeId() + "/"
                                + output.getReportedBatchNumber());
            }
            BigDecimal receiptDelta = requireInventoryQuantityScale(
                    receipt.getReceivedQuantity(),
                    "WORKFLOW_RECEIPT_QUANTITY_SCALE_INVALID",
                    "Workflow 实收数量");
            BigDecimal alreadyReceived = zeroIfNull(output.getReceivedQuantity());
            BigDecimal cumulativeReceived = receiptDelta == null
                    ? alreadyReceived : alreadyReceived.add(receiptDelta);
            if (receiptDelta == null || receiptDelta.signum() <= 0) {
                throw new BusinessException(409, "本次实收数量必须大于 0")
                        .withCode("WORKFLOW_RECEIPT_DELTA_REQUIRED");
            }
            if (cumulativeReceived.compareTo(output.getReportedQuantity()) > 0) {
                throw new BusinessException(409, "累计仓库实收不能超过生产报产")
                        .withCode("RECEIPT_EXCEEDS_PRODUCTION_REPORTED")
                        .withHint("生产报产 " + output.getReportedQuantity() + output.getQuantityUnit()
                                + ", 已入库 " + alreadyReceived + output.getQuantityUnit()
                                + ", 本次拟入库 " + receiptDelta + output.getQuantityUnit());
            }
            output.setReceivedQuantity(cumulativeReceived);
            receiptDeltaByKey.put(key, receiptDelta);
            String canonicalUnit = canonicalReceiptUnit(output.getQuantityUnit());
            receivedByUnit.merge(canonicalUnit, cumulativeReceived, BigDecimal::add);
        }
        if (!receiptsByKey.isEmpty()) {
            throw new BusinessException(409, "Workflow warehouse receipt contains unreported output lines")
                    .withCode("WORKFLOW_RECEIPT_OUTPUT_SET_MISMATCH");
        }
        BigDecimal aggregateReceived = receivedByUnit.size() == 1
                ? receivedByUnit.values().iterator().next()
                : null;
        if (aggregateReceived != null && request.getReceivedQuantity() != null
                && aggregateReceived.compareTo(request.getReceivedQuantity()) != 0) {
            throw new BusinessException(409,
                    "Aggregate warehouse receipt quantity does not equal the output-line total")
                    .withCode("WORKFLOW_RECEIPT_AGGREGATE_MISMATCH");
        }

        boolean allReceived = outputLines.stream().allMatch(output ->
                zeroIfNull(output.getReceivedQuantity()).compareTo(output.getReportedQuantity()) == 0);
        String costWarning = allocateWorkflowOutputCosts(factoryId, plan.getId(), outputLines);
        LocalDateTime receivedAt = LocalDateTime.now();
        for (ProductionSettlementOutputLine output : outputLines) {
            String key = outputReceiptKey(output.getProductTypeId(), output.getReportedBatchNumber(),
                    output.getQuantityUnit());
            FinishedGoodsBatch batch = createFinishedGoodsForOutputLine(
                    plan, settlement, output, receiptDeltaByKey.get(key), receivedBy);
            output.setFinishedGoodsBatchId(batch.getId());
            output.setReceiptIdempotencyKey(request.getIdempotencyKey());
            output.setReceivedBy(receivedBy);
            output.setReceivedAt(receivedAt);
            output.setStatus(allReceived ? "RECEIVED" : "PARTIALLY_RECEIVED");
        }
        productionSettlementOutputLineRepository.saveAll(outputLines);

        settlement.setWarehouseReceiptIdempotencyKey(request.getIdempotencyKey());
        settlement.setWarehouseReceivedQuantity(aggregateReceived);
        settlement.setWarehouseVarianceQuantity(aggregateReceived == null
                ? null : zeroIfNull(settlement.getActualFinishedQuantity()).subtract(aggregateReceived));
        settlement.setQuantityUnit(receivedByUnit.size() == 1
                ? receivedByUnit.keySet().iterator().next()
                : null);
        settlement.setWarehouseVarianceReason(null);
        settlement.setWarehouseResponsibilitySide(null);
        settlement.setWarehouseVarianceNote(trimToNull(request.getVarianceNote()));
        settlement.setFinishedGoodsBatchId(outputLines.size() == 1
                ? outputLines.getFirst().getFinishedGoodsBatchId() : null);
        settlement.setTransitLedgerId(null);
        settlement.setWarehouseReceivedBy(receivedBy);
        settlement.setWarehouseReceivedAt(allReceived ? receivedAt : null);
        settlement.setPostingStatus(allReceived ? "POSTED" : "PENDING_WAREHOUSE_RECEIPT");
        // ⛔ 这两句是【用户可见】的: 原来是英文("Warehouse confirmed every pinned Workflow
        //    terminal output line" / "Completed N production batch records"), 直接出现在
        //    App/web 的响应里。判据: 用户看到的必须是中文。
        settlement.setPostingMessage(allReceived
                ? "仓库已确认全部末道产出, 成品已全部入库"
                : "本次分次入库已过账, 剩余数量继续待仓库确认");
        productionSettlementRepository.save(settlement);
        int completedBatchCount = allReceived
                ? markReceiptProductionBatchesCompleted(factoryId, plan.getId(), settlement)
                : 0;
        return toWarehouseReceiptResponse(settlement, settlement.getPostingMessage(),
                buildReceiptWarnings(completedBatchCount, costWarning));
    }

    private String outputReceiptKey(String productTypeId, String batchNumber, String unit) {
        return firstNonBlank(trimToNull(productTypeId), "") + "\u0000"
                + firstNonBlank(trimToNull(batchNumber), "") + "\u0000"
                + firstNonBlank(canonicalReceiptUnit(trimToNull(unit)), "");
    }

    private BigDecimal requireInventoryQuantityScale(
            BigDecimal quantity, String errorCode, String label) {
        if (quantity == null) {
            return null;
        }
        try {
            return quantity.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new BusinessException(409, label + "最多支持 4 位小数")
                    .withCode(errorCode)
                    .withHint("请修正数量后重新提交；系统不会对报产或库存数量静默舍入");
        }
    }

    /**
     * 组装仓库确认入库要给用户看的提示。
     *
     * <p>抽成纯函数是为了让闸能断言<b>行为</b>而不是源码文本 —— 2026-08-18 实测:
     * 第一版闸写的是 {@code src.contains("warnings.add(costWarning)")}, 而变异
     * {@code if (false && costWarning != null)} 把行为改掉了、那行字符却still在,
     * 于是闸<b>纹丝不动</b>。本仓形态 C⁸: 闸要量结构/行为, 不要量文本。
     *
     * @param costWarning 成本算不出时的原因; {@code null} 表示成本正常算出来了
     */
    public static List<String> buildReceiptWarnings(int completedBatchCount, String costWarning) {
        List<String> warnings = new ArrayList<>();
        if (completedBatchCount > 0) {
            warnings.add("已同步 " + completedBatchCount + " 个生产批次为已完工");
        }
        // 🔴 成本算不出时必须说出来 —— 静默留空会让用户以为「成本还没算」或「成本就是 0」
        if (costWarning != null) {
            warnings.add(costWarning);
        }
        return warnings;
    }

    /**
     * 给末道产出行分摊成本。
     *
     * @return {@code null} = 无需多嘴; 非 null 是<b>给用户看的一句话</b> —— 要么是
     *         「为什么没有成本 + 下一步」, 要么是「成本算出来了但不含哪些未录的桶」。
     *
     * <h3>🔴 为什么要返回原因而不是静默 return (2026-08-18 prod 实测)</h3>
     *
     * 原来这里 {@code totalCost == null} 就直接 return, 于是 {@code allocated_cost} /
     * {@code unit_cost} 一路留空、成品批次的 {@code unit_cost} 也是空, <b>而界面上没有任何说明</b> ——
     * 用户只看到成本栏是空的, 不知道是「还没算」「算不出」还是「真的是 0」。
     *
     * <p>本仓原则: <b>禁止降级处理, 不返回假数据, 明确显示错误</b>; 判据: <b>凡是拦住人的地方都要告诉下一步</b>。
     * ⛔ 特别地: 不许把「算不出」写成 ¥0 —— 那会让后续 COGS 按零成本结转。
     *
     * <p>实测那次算不出的根因(<b>本方法不负责修</b>, 属成本口径, 见 PR 说明):
     * 结单把领用事实写进 {@code production_settlement_consumptions}(4 行, RAW_MATERIAL, 各 20kg),
     * 而 {@code OrderCostBreakdownService} 按 {@code material_consumptions.production_batch_id} 归集 ——
     * <b>两张表不相交</b> ⇒ totalRawInput=0 ⇒ totalCost=0 ⇒ 这里拿到 null。
     */
    private String allocateWorkflowOutputCosts(
            String factoryId, String planId, List<ProductionSettlementOutputLine> lines) {
        com.cretas.aims.dto.yield.OrderCostBreakdownDTO breakdown =
                resolvePlanCostBreakdown(factoryId, planId);
        BigDecimal totalCost = usableTotalCost(breakdown);
        if (totalCost == null) {
            String reason = describeCostUnavailableReason(breakdown);
            log.warn("[结单成本] 算不出本计划权威成本, 成品单位成本留空 factory={} plan={} reason={}",
                    factoryId, planId, reason);
            return reason;
        }
        allocateWorkflowOutputCostsFromTotal(totalCost, lines);
        // 成本算出来了, 但「不强制填写」的桶如果没录, 仍然要说一声 ——
        // 否则用户会以为这个单位成本已经含了设备/其他成本。
        // 🔴 辅料先估 (2026-09-06): 成本【算出来了】这条路上同样要说 ——
        //    describeCostUnavailableReason 只在算不出时才被调到, 光挂在那边等于
        //    「估算成功时反而不告诉人这是估的」(本仓形态 B: 机制在, 没接到成功那条路上)。
        return joinCostNotes(describeUncollectedOptionalCostNote(breakdown),
                describeEstimatedAuxNote(breakdown));
    }

    /**
     * 把几句「给用户看的口径说明」拼成一句。全为 {@code null} 时返回 {@code null}(无需多嘴)。
     *
     * <p>⛔ 不在 {@link #describeUncollectedOptionalCostNote} 内部塞第二个主题 —— 那个方法
     * 讲的是「未录的可选成本桶」, 把辅料估算也塞进去会让它的名字开始说谎(本仓形态 B⁵:
     * 描述过期比没有描述更糟)。组合在调用点做。
     */
    static String joinCostNotes(String... notes) {
        StringBuilder sb = new StringBuilder();
        for (String note : notes) {
            if (note != null && !note.isBlank()) {
                sb.append(note);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 成本<b>算出来了</b>, 但设备/其他成本这类「不强制填写」的桶没录 —— 给一句说明。
     *
     * <p>🔴 为什么算出来了还要喊: 2026-07-30 裁定这两项不强制填写、缺了按 0 计入,
     * 于是 {@code totalCost} 是「不含它们」的口径。<b>不说</b>用户就会把这个单位成本
     * 当成全成本用去定价/算毛利。⛔ 这不是警告, 是口径说明: 它不阻断入库。
     *
     * @return {@code null} = 没有未录的可选成本桶, 无需多嘴
     */
    public static String describeUncollectedOptionalCostNote(
            com.cretas.aims.dto.yield.OrderCostBreakdownDTO breakdown) {
        if (breakdown == null || breakdown.getMissingCostItems() == null) {
            return null;
        }
        List<String> optional = breakdown.getMissingCostItems().stream()
                .filter(item -> com.cretas.aims.service.yield.OrderCostBreakdownService
                        .isOptionalCostItem(item))
                .toList();
        if (optional.isEmpty()) {
            return null;
        }
        return "本次成品单位成本【不含】以下未录成本(按 0 计入): "
                + joinMissingCostLabels(optional)
                + "。如需含这部分的成本, 请补录后在成本核算处重新核算。";
    }

    /**
     * 拿本计划的权威成本分解。读不到就是 {@code null}。
     *
     * <p>⛔ 不在这里先折成一个 {@code BigDecimal} —— 「为什么算不出」写在 DTO 的
     * {@code missingCostItems} 里, 折掉就再也说不出真实原因了 (这正是原来那句假话的成因)。
     */
    private com.cretas.aims.dto.yield.OrderCostBreakdownDTO resolvePlanCostBreakdown(
            String factoryId, String planId) {
        if (orderCostBreakdownService == null) {
            return null;
        }
        try {
            return orderCostBreakdownService.computeByPlan(factoryId, planId, false);
        } catch (RuntimeException ex) {
            log.warn("Workflow output cost allocation unavailable for factory={}, plan={}: {}",
                    factoryId, planId, ex.getMessage());
            return null;
        }
    }

    /**
     * 只有【完整】的总成本才能当分摊基数。
     *
     * <p>{@code costComplete=false} 时 {@code OrderCostBreakdownService} 会把 {@code totalCost}
     * 置 null (而 {@code knownCostSubtotal} 保留) —— 那是 2026-07-30 定下的口径:
     * 不完整的数不得喂 COGS/毛利。⛔ 所以这里【不】拿 {@code knownCostSubtotal} 顶替,
     * 它只能出现在给人看的提示里。
     */
    static BigDecimal usableTotalCost(com.cretas.aims.dto.yield.OrderCostBreakdownDTO breakdown) {
        return breakdown != null && breakdown.isHasData()
                && breakdown.getTotalCost() != null && breakdown.getTotalCost().signum() > 0
                ? breakdown.getTotalCost() : null;
    }

    /**
     * 把【真实的】算不出原因说成一句中文。
     *
     * <h3>🔴 为什么不能继续用一句固定文案 (2026-08-25 prod 实测)</h3>
     *
     * 计划 {@code ef5392c7-a575-42a3-8691-ebca0d8306e1} 入库时报的是
     * 「没有归集到本计划的原料领用成本」—— 而同一时刻读真实接口
     * {@code GET /api/mobile/F006/production/batches/{no}/cost-breakdown} 拿到的是:
     * <pre>
     * rawMaterialCost   = 40.32     7 行原料全部 COLLECTED, 单价均非空
     * knownCostSubtotal = 93.12
     * totalCost         = null      ← 真正的原因在下面这一行
     * missingCostItems  = [LABOR_RATE_OR_TIME:process-2,
     *                      EQUIPMENT_COST:PB-..., OTHER_COST:PB-...]
     * </pre>
     * 即原料领用成本<b>已经归集到了</b>, 缺的是人工/设备/其他。那句固定文案把用户指向了一个
     * <b>不存在的问题</b>(去查归集配置), 而真正要做的是补录第 2 道工序的工时/人工成本。
     *
     * <p>本仓形态 A: <b>报一个数的时候, 把这个数是怎么来的一起报</b>。
     *
     * @param breakdown 成本分解; {@code null} / {@code hasData=false} = 真的一行都没归集到
     */
    public static String describeCostUnavailableReason(
            com.cretas.aims.dto.yield.OrderCostBreakdownDTO breakdown) {
        if (breakdown == null || !breakdown.isHasData()) {
            return COST_UNAVAILABLE_WARNING;
        }
        List<String> missing = breakdown.getMissingCostItems() == null
                ? List.of() : breakdown.getMissingCostItems();
        // ⛔ 只有【会阻断】的那些才是「为什么算不出」。设备/其他成本不强制填写(2026-07-30 裁定),
        //    它们没录也照样能算 —— 把它们混进原因里会让用户去补一个补了也没用的东西。
        List<String> blocking = missing.stream()
                .filter(item -> !com.cretas.aims.service.yield.OrderCostBreakdownService
                        .isOptionalCostItem(item))
                .toList();
        if (blocking.isEmpty()) {
            return COST_UNAVAILABLE_WARNING;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本次未能算出成品单位成本: 还差 ").append(blocking.size()).append(" 项没采集齐");
        BigDecimal known = breakdown.getKnownCostSubtotal();
        if (known != null) {
            // ⛔ 把【已经归集到的】也说出来: 否则用户会以为一行都没算到 —— 那正是原来那句假话。
            sb.append("(已归集 ¥").append(known.stripTrailingZeros().toPlainString()).append(")");
        }
        sb.append(": ").append(joinMissingCostLabels(blocking)).append("。");
        // 🔴 审计透明度补丁 (2026-09-04, PLAN-1788305552728-D94F7E22 复现): 「已归集 ¥X」只报了一个
        // 汇总数, 审阅者验证不了它是不是把同一个物料批次算了两遍 —— prod 实测冰水批次 4cce21c0
        // 就是这样藏进 ¥1.9538 里的(traceCost 算一次、结单并入又算一次)。把贡献最大的几条摆出来,
        // 同一个 batchName 出现两次(depth 还不一样)就是双计的信号。
        String contributions = joinCostContributionLabels(breakdown.getSources());
        if (!contributions.isEmpty()) {
            sb.append("已归集明细(按金额降序): ").append(contributions).append("。");
        }
        sb.append("成品已按实收数量入库, 单位成本留空(不填 ¥0, 避免后续按零成本结转)。");
        // 🔴 客供主料成本口径 (a)（2026-09-04）: 即便还差别的项拒绝, 也要让人看到客供料已经
        // 按口径排除了 —— 不然会有人以为"还差单价"包括那些客供料, 白跑一趟去给客供料补价。
        String customerSuppliedNote = describeCustomerSuppliedNote(breakdown);
        if (customerSuppliedNote != null) {
            sb.append(customerSuppliedNote);
        }
        // 🔴 辅料先估 (2026-09-06): 即便这次因为别的缺项算不出总成本, 已经估出来的那部分辅料
        //    也要说清楚 —— 否则用户补完缺项再看, 会以为那些辅料是实际领用记录来的。
        String estimatedAuxNote = describeEstimatedAuxNote(breakdown);
        if (estimatedAuxNote != null) {
            sb.append(estimatedAuxNote);
        }
        sb.append("下一步: 请先补录上述缺项, 再到成本核算处重新核对本计划成本。");
        return sb.toString();
    }

    /** 已归集明细最多列几项 —— 全部展开会把提示拉成几千字, 与 {@link #MAX_LISTED_MISSING_COST_ITEMS} 同一条纪律。 */
    private static final int MAX_LISTED_COST_CONTRIBUTIONS = 5;

    /**
     * 把 {@code sources}(原料成本逐条回溯明细)按金额降序列出前 N 项, 供审阅者核对
     * 「同一个批次是不是被算了两遍」——同一个 {@code batchName} 出现两次、{@code depth} 还不一样,
     * 就是双计的信号(见类上方 {@link #describeCostUnavailableReason} javadoc)。
     *
     * @return 为空(无 sources 或全部金额未知)时返回空串, 调用方按空串跳过整段
     */
    static String joinCostContributionLabels(
            List<com.cretas.aims.dto.yield.OrderCostBreakdownDTO.SourceCost> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        List<com.cretas.aims.dto.yield.OrderCostBreakdownDTO.SourceCost> sorted = sources.stream()
                .filter(s -> s != null && s.getCost() != null)
                .sorted(java.util.Comparator.comparing(
                        com.cretas.aims.dto.yield.OrderCostBreakdownDTO.SourceCost::getCost).reversed())
                .toList();
        if (sorted.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (com.cretas.aims.dto.yield.OrderCostBreakdownDTO.SourceCost s : sorted) {
            if (shown >= MAX_LISTED_COST_CONTRIBUTIONS) {
                sb.append("; 等共 ").append(sorted.size()).append(" 项");
                break;
            }
            if (shown > 0) {
                sb.append("; ");
            }
            String name = s.getBatchName() != null ? s.getBatchName()
                    : (s.getBatchId() != null ? s.getBatchId() : "?");
            sb.append(name).append(" ¥").append(s.getCost().stripTrailingZeros().toPlainString());
            if (s.getDepth() != null) {
                sb.append(" 深度").append(s.getDepth());
            }
            shown++;
        }
        return sb.toString();
    }

    /**
     * 🔴 客供主料成本口径 (a)（owner 裁定，2026-09-04）：来料加工下，客供主料成本显式记 0
     * （加工费口径），不是「缺价、算不出」——这句话把这件事说给人看，⛔ 不要让它安静地
     * 变成「怎么这批的原料成本比想的低」这种疑问。
     *
     * @return {@code null} = 本计划没有客供料按口径记 0 的情况, 无需多嘴
     */
    public static String describeCustomerSuppliedNote(
            com.cretas.aims.dto.yield.OrderCostBreakdownDTO breakdown) {
        if (breakdown == null || breakdown.getCustomerSuppliedItems() == null
                || breakdown.getCustomerSuppliedItems().isEmpty()) {
            return null;
        }
        List<String> items = breakdown.getCustomerSuppliedItems();
        String batchNames = items.stream()
                .map(item -> {
                    int sep = item.indexOf(':');
                    return sep < 0 ? item : item.substring(sep + 1);
                })
                .collect(java.util.stream.Collectors.joining("、"));
        return "含客供料 " + items.size() + " 项按加工口径记 ¥0(不计入我方原料成本, 客户自供不代购): "
                + batchNames + "。";
    }

    /**
     * 🔴 辅料先估（owner 裁定，2026-09-06）：这批的辅料/调料<b>没有实际耗用记录</b>，成本是按
     * 「BOM 配比 × 实际主料投入」估出来的 —— 这句话把它说给人看。
     *
     * <p>⛔ 不能让估算值静默混进 {@code totalCost} 再被拿去定价/算毛利：估的和实际领用的
     * 在同一个「调料成本」数字里长得一模一样，唯一的区别就是有没有人说出来
     * （本仓形态 A¹³：每条读数都要带来源标记）。
     *
     * <p>措辞里的「{@value com.cretas.aims.service.yield.OrderCostBreakdownService#ESTIMATED_AUX_LABEL}」
     * 取自服务侧的常量，⛔ 不在这里重新写一遍那四个字（本仓形态 D）。
     *
     * @return {@code null} = 本次没有按配比估算的辅料, 无需多嘴
     */
    public static String describeEstimatedAuxNote(
            com.cretas.aims.dto.yield.OrderCostBreakdownDTO breakdown) {
        if (breakdown == null) {
            return null;
        }
        List<com.cretas.aims.dto.yield.OrderCostBreakdownDTO.EstimatedAuxItem> items =
                breakdown.getEstimatedAuxItems() == null
                        ? List.of() : breakdown.getEstimatedAuxItems();
        List<String> partiallyRecorded = breakdown.getPartiallyRecordedAuxItems() == null
                ? List.of() : breakdown.getPartiallyRecordedAuxItems();
        if (items.isEmpty() && partiallyRecorded.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (items.isEmpty()) {
            // 只有「部分记录」没有估算行 —— 直接说那一段, 见本方法末尾。
            return describePartiallyRecordedAux(partiallyRecorded);
        }
        sb.append("含").append(com.cretas.aims.service.yield.OrderCostBreakdownService.ESTIMATED_AUX_LABEL)
                .append("的辅料 ").append(items.size()).append(" 项(无实际耗用记录, 按 BOM 配比 × 实际主料投入折算)");
        int shown = 0;
        for (com.cretas.aims.dto.yield.OrderCostBreakdownDTO.EstimatedAuxItem item : items) {
            if (shown >= MAX_LISTED_MISSING_COST_ITEMS) {
                sb.append("; 等共 ").append(items.size()).append(" 项");
                break;
            }
            sb.append(shown == 0 ? ": " : "; ");
            sb.append(item.getMaterialName() == null ? item.getMaterialCode() : item.getMaterialName());
            if (item.getQuantity() != null) {
                sb.append(" ").append(item.getQuantity().stripTrailingZeros().toPlainString())
                        .append(item.getUnit() == null ? "" : item.getUnit());
            }
            // ⛔ 金额可能因脱敏被抹成 null —— 那时只说量, 不编一个 ¥。
            if (item.getAmount() != null) {
                sb.append(" ¥").append(item.getAmount().stripTrailingZeros().toPlainString());
            }
            shown++;
        }
        sb.append("。这部分是估算值, 不是实际领用记录; 要拿到实际成本请补录辅料领用后重新核算。");
        String partial = describePartiallyRecordedAux(partiallyRecorded);
        if (partial != null) {
            sb.append(partial);
        }
        return sb.toString();
    }

    /**
     * 🔴 辅料<b>部分记录</b>的那一句（2026-09-06 终审补）。
     *
     * <p>本批<b>有</b>辅料实耗记录, 但同一张 BOM 里还有几种<b>一条记录都没有</b> ——
     * 按「信记录不补差」口径这几种<b>不估</b>, 于是本批调料成本只含已记录的那几种。
     * <b>不说出来就是静默少算</b>: 成本非 null、看起来完整, 实际短了这几种的钱。
     *
     * <p>⛔ 措辞里不要写成「缺项」或「算不出」—— 它<b>不阻断</b>成本, 说的是<b>口径</b>:
     * 这个数就是「按已记录的算出来的」, 用户要更全的数就去补录。
     *
     * @return {@code null} = 本次没有部分记录的辅料, 无需多嘴
     */
    static String describePartiallyRecordedAux(List<String> partiallyRecorded) {
        if (partiallyRecorded == null || partiallyRecorded.isEmpty()) {
            return null;
        }
        List<String> names = partiallyRecorded.stream()
                .map(item -> {
                    int sep = item.indexOf(':');
                    return sep < 0 ? item : item.substring(sep + 1);
                })
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("另有 ").append(names.size())
                .append(" 项辅料本批没有任何耗用记录, 按「以实际记录为准、不补差」口径未计入成本(所以这个成本是偏低的下限): ");
        int shown = 0;
        for (String name : names) {
            if (shown >= MAX_LISTED_MISSING_COST_ITEMS) {
                sb.append("; 等共 ").append(names.size()).append(" 项");
                break;
            }
            if (shown > 0) {
                sb.append("、");
            }
            sb.append(name);
            shown++;
        }
        sb.append("。要把它们算进来, 请补录这几项的辅料领用后重新核算。");
        return sb.toString();
    }

    /** 最多列几项 —— 全部展开会把提示拉成几千字。 */
    private static final int MAX_LISTED_MISSING_COST_ITEMS = 5;

    static String joinMissingCostLabels(List<String> missing) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String item : missing) {
            if (shown >= MAX_LISTED_MISSING_COST_ITEMS) {
                sb.append("; 等共 ").append(missing.size()).append(" 项");
                break;
            }
            if (shown > 0) {
                sb.append("; ");
            }
            sb.append(missingCostItemLabel(item));
            shown++;
        }
        return sb.toString();
    }

    /**
     * 把 {@code CODE:detail} 形态的缺项码翻成中文。
     *
     * <p>⛔ 认不出来的码<b>原样输出</b>, 不静默丢掉 —— 一个没翻译的码至少还能搜到,
     * 丢掉就变成「差 N 项但只列出 N-1 项」。
     */
    public static String missingCostItemLabel(String item) {
        if (item == null || item.isBlank()) {
            return "未知缺项";
        }
        int sep = item.indexOf(':');
        String code = sep < 0 ? item : item.substring(0, sep);
        String detail = sep < 0 ? "" : item.substring(sep + 1);
        switch (code) {
            case "RAW_MATERIAL_PRICE":
                return "原料批次 " + detail + " 缺单价";
            case "PACKAGING_PRICE":
                return "包材 " + detail + " 缺单价";
            case "SEASONING_PRICE":
                return "调料 " + detail + " 缺单价";
            case "EQUIPMENT_COST":
                return "生产批次 " + detail + " 未录设备成本";
            case "OTHER_COST":
                return "生产批次 " + detail + " 未录其他成本";
            case "LABOR_COST_UNKNOWN":
                return "工序 " + detail + " 的人工成本未知(缺费率或工时)";
            case "LABOR_TIME_DETAIL_MISSING":
                return "工序 " + detail + " 缺可验证工时段(人工金额已计入)";
            case "LABOR_RATE_OR_TIME":
                // 历史码: 2026-08-25 拆成上面两个后不再产出, 保留是为了读旧数据不丢项。
                return "工序 " + detail + " 缺工时段或人工成本";
            // 🔴 辅料先估 (2026-09-06) 的三个码 —— ⛔ 三条都不是「可选成本桶」,
            //    它们会阻断 totalCost: 缺配比/缺单价时按 0 计就是把辅料成本白送掉。
            case "ESTIMATED_AUX_RATIO_MISSING":
                return "辅料 " + detail + " 无实际耗用记录, BOM 里也没有配比(g/kg), 估不出成本";
            case "ESTIMATED_AUX_PRICE_MISSING":
                return "辅料 " + detail + " 无实际耗用记录, BOM 里没有可用单价, 估不出成本";
            case "ESTIMATED_AUX_RAW_BASIS_UNVERIFIABLE":
                return "生产批次 " + detail + " 的主料投入量折不成 kg(计数单位或批次不可解析), "
                        + "辅料无法按配比估算";
            case "NO_COST_COLLECTED":
                return "本计划还没有归集到任何成本(没有领用、报工或投料记录)";
            case "SEMI_FINISHED_OR_FINISHED_FEED_PRICE":
                return "半成品/成品投料缺单位成本";
            case "PINNED_BOM_SNAPSHOT_UNAVAILABLE":
                return "计划 " + detail + " 读不到锁定的 BOM 快照";
            case "PINNED_BOM_SNAPSHOT_MISSING":
                return "锁定的 BOM 快照不存在: " + detail;
            case "PINNED_BOM_OUTPUT_BASIS_UNVERIFIABLE":
                return "锁定 BOM 的产出基准核不出来: " + detail;
            case "PINNED_BOM_ITEMS_MISSING":
                return "锁定 BOM 缺物料行: " + detail;
            default:
                return item;
        }
    }

    /**
     * 成本算不出时给用户的那句话。⛔ 不要改成「成本为 0」之类的说法 ——
     * 「算不出」和「是 0」对下游(COGS 结转/毛利)是两件完全不同的事。
     */
    public static final String COST_UNAVAILABLE_WARNING =
            "本次未能算出成品单位成本: 没有归集到本计划的原料领用成本。"
            + "成品已按实收数量入库, 单位成本留空(不填 ¥0, 避免后续按零成本结转)。"
            + "下一步: 请在成本核算处人工核对本计划成本, 或联系管理员检查成本归集配置。";

    static void allocateWorkflowOutputCostsFromTotal(
            BigDecimal totalCost, List<ProductionSettlementOutputLine> lines) {
        BigDecimal authoritativeTotal = totalCost.setScale(6, RoundingMode.HALF_UP);
        Map<String, List<ProductionSettlementOutputLine>> byProduct = lines.stream()
                .collect(Collectors.groupingBy(
                        ProductionSettlementOutputLine::getProductTypeId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        for (Map.Entry<String, List<ProductionSettlementOutputLine>> entry : byProduct.entrySet()) {
            ProductionSettlementOutputLine authority = entry.getValue().getFirst();
            boolean policyMismatch = entry.getValue().stream().anyMatch(line ->
                    line.getOutputRole() != authority.getOutputRole()
                            || Objects.compare(line.getCostAllocationRatio(),
                                    authority.getCostAllocationRatio(),
                                    Comparator.nullsFirst(BigDecimal::compareTo)) != 0
                            || Objects.compare(line.getByproductNrvUnitPrice(),
                                    authority.getByproductNrvUnitPrice(),
                                    Comparator.nullsFirst(BigDecimal::compareTo)) != 0);
            if (policyMismatch) {
                throw new BusinessException(409, "Output batches for one SKU have different BOM cost policies")
                        .withCode("OUTPUT_COST_POLICY_MISMATCH");
            }
        }
        Map<String, BigDecimal> productCosts = new LinkedHashMap<>();
        BigDecimal byproductCost = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        for (Map.Entry<String, List<ProductionSettlementOutputLine>> entry : byProduct.entrySet()) {
            ProductionSettlementOutputLine authority = entry.getValue().getFirst();
            // 与结单闸同一条判据的行级形态 —— ⛔ 不要在这里重新发明一次「是不是副产物」。
            com.cretas.aims.service.bom.BomWorkflowRevisionService.OutputCostPolicy policy =
                    com.cretas.aims.service.bom.BomWorkflowRevisionService.settlementLineCostPolicy(
                            authority.getOutputRole(), authority.getByproductNrvUnitPrice());
            if (policy == com.cretas.aims.service.bom.BomWorkflowRevisionService
                    .OutputCostPolicy.ACTUAL_IO_PLACEHOLDER) {
                // 这一行的角色/比例是【早期版本】自动编号出来的占位值(MAIN 100% / BY_PRODUCT 0%),
                // 不是任何人授权的成本口径。⛔ 既不能按 NRV 抵扣(没有 NRV, 原来这里直接 NPE),
                // 也不能按 0% 分摊 —— 那等于把「算不出」写成 ¥0, 后续会按零成本结转 COGS。
                // ⚠️ 措辞已随坎 3 更新: 那条编号规则已删, 现在只有历史行会走到这里。
                //    ⛔ 不要把提示写成「系统正在这么编号」—— 那是一句过期的承诺。
                throw new BusinessException(409,
                        "这批产出的成本分摊口径还没有人定过, 不能自动分摊成本")
                        .withCode("OUTPUT_COST_ALLOCATION_POLICY_UNAUTHORED")
                        // ⛔ 这句原来让用户「前往 Workflow 为每个产出设置角色与成本分摊比例」——
                        //    画布上没有那个输入框。改成指向真实存在的两个入口。
                        .withHint("这批 BOM 的产出角色与分摊比例是早期版本自动填上的占位值"
                                + "(主产出 100%、其余 0%), 不是有人定过的成本口径。"
                                + "请回到工艺画布, 把其中的副产改成「副产」Cell、其余保持成品, "
                                + "保存并重新生成激活 BOM 后再入库; "
                                + "若各产出都是正经成品, 请在报工页为每个产出填写成本分摊比例(合计 100%)")
                        .withHintTarget("costAllocationRatio");
            }
            if (policy != com.cretas.aims.service.bom.BomWorkflowRevisionService
                    .OutputCostPolicy.BY_PRODUCT_NRV) {
                continue;
            }
            BigDecimal productQuantity = entry.getValue().stream()
                    .map(ProductionSettlementOutputLine::getReceivedQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal productCost = authority.getByproductNrvUnitPrice()
                    .multiply(productQuantity)
                    .setScale(6, RoundingMode.HALF_UP);
            productCosts.put(entry.getKey(), productCost);
            byproductCost = byproductCost.add(productCost);
        }
        if (byproductCost.compareTo(authoritativeTotal) > 0) {
            throw new BusinessException(409, "By-product NRV allocation exceeds actual plan cost")
                    .withCode("BYPRODUCT_NRV_EXCEEDS_PLAN_COST");
        }
        BigDecimal primaryRatioTotal = byProduct.values().stream()
                .map(List::getFirst)
                .filter(line -> line.getOutputRole() != BomRecipe.OutputRole.BY_PRODUCT)
                .map(ProductionSettlementOutputLine::getCostAllocationRatio)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (primaryRatioTotal.signum() <= 0) {
            throw new BusinessException(409, "Pinned Workflow output allocation ratios are invalid")
                    .withCode("OUTPUT_COST_ALLOCATION_RATIO_INVALID");
        }
        BigDecimal remaining = authoritativeTotal.subtract(byproductCost);
        List<Map.Entry<String, List<ProductionSettlementOutputLine>>> primaryProducts =
                byProduct.entrySet().stream()
                        .filter(entry -> entry.getValue().getFirst().getOutputRole()
                                != BomRecipe.OutputRole.BY_PRODUCT)
                        .toList();
        BigDecimal primaryAllocated = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        for (int i = 0; i < primaryProducts.size(); i++) {
            Map.Entry<String, List<ProductionSettlementOutputLine>> entry = primaryProducts.get(i);
            ProductionSettlementOutputLine authority = entry.getValue().getFirst();
            BigDecimal productCost = i == primaryProducts.size() - 1
                    ? remaining.subtract(primaryAllocated)
                    : remaining.multiply(authority.getCostAllocationRatio())
                            .divide(primaryRatioTotal, 6, RoundingMode.HALF_UP);
            productCosts.put(entry.getKey(), productCost);
            primaryAllocated = primaryAllocated.add(productCost);
        }

        BigDecimal allocatedTotal = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        for (Map.Entry<String, List<ProductionSettlementOutputLine>> entry : byProduct.entrySet()) {
            List<ProductionSettlementOutputLine> productLines = entry.getValue();
            ProductionSettlementOutputLine authority = productLines.getFirst();
            BigDecimal productQuantity = productLines.stream()
                    .map(ProductionSettlementOutputLine::getReceivedQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal productCost = productCosts.get(entry.getKey());
            BigDecimal productAllocated = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
            for (int i = 0; i < productLines.size(); i++) {
                ProductionSettlementOutputLine line = productLines.get(i);
                BigDecimal allocated = i == productLines.size() - 1
                        ? productCost.subtract(productAllocated)
                        : productCost.multiply(line.getReceivedQuantity())
                                .divide(productQuantity, 6, RoundingMode.HALF_UP);
                line.setAllocatedCost(allocated);
                line.setUnitCost(allocated.divide(line.getReceivedQuantity(), 6, RoundingMode.HALF_UP));
                productAllocated = productAllocated.add(allocated);
                allocatedTotal = allocatedTotal.add(allocated);
            }
        }
        if (allocatedTotal.compareTo(authoritativeTotal) != 0) {
            throw new IllegalStateException("Workflow output cost allocation does not conserve total cost");
        }
    }

    private int markReceiptProductionBatchesCompleted(String factoryId, String planId, ProductionSettlement settlement) {
        List<ProductionBatch> queriedBatches = productionBatchRepository
                .findByFactoryIdAndProductionPlanId(factoryId, planId);
        List<ProductionBatch> batches = queriedBatches == null ? List.of() : queriedBatches.stream()
                .filter(Objects::nonNull)
                .filter(batch -> Objects.equals(factoryId, batch.getFactoryId()))
                .filter(batch -> !"CLERK_WIP".equals(batch.getBatchType()))
                .toList();
        if (batches.isEmpty()) {
            return 0;
        }
        DefectCountResolver.Resolved resolvedDefect = DefectCountResolver.resolve(
                settlement.getDefectCountOutcome(), settlement.getDefectQuantity());
        boolean qualityCanBeAssigned = batches.size() == 1
                && resolvedDefect.outcome() != null
                && resolvedDefect.outcome() != DefectCountOutcome.NOT_COUNTED
                && hasUniqueReceiptTerminalOutput(factoryId, settlement, batches.getFirst());
        int changed = 0;
        LocalDateTime completedAt = LocalDateTime.now();
        for (ProductionBatch batch : batches) {
            if (batch == null || batch.getId() == null) {
                continue;
            }
            if (batch.getStatus() == ProductionBatchStatus.COMPLETED
                    || batch.getStatus() == ProductionBatchStatus.CANCELLED) {
                continue;
            }
            BigDecimal actualQuantity = zeroIfNull(batch.getQuantity());
            if (actualQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                actualQuantity = zeroIfNull(batch.getActualQuantity());
            }
            if (actualQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                actualQuantity = zeroIfNull(settlement.getActualFinishedQuantity());
            }
            batch.setStatus(ProductionBatchStatus.COMPLETED);
            if (batch.getEndTime() == null) {
                batch.setEndTime(completedAt);
            }
            batch.setActualQuantity(actualQuantity);
            if (qualityCanBeAssigned && hasMatchingQualityUnit(settlement, batch)) {
                BigDecimal confirmedGood = settlement.getWarehouseReceivedQuantity() != null
                        ? settlement.getWarehouseReceivedQuantity()
                        : (batch.getQuantity() != null ? batch.getQuantity() : actualQuantity);
                if (confirmedGood != null && confirmedGood.signum() >= 0) {
                    batch.setGoodQuantity(confirmedGood);
                    batch.setDefectQuantity(resolvedDefect.quantity());
                    batch.setActualQuantity(confirmedGood.add(resolvedDefect.quantity()));
                } else {
                    batch.setGoodQuantity(null);
                    batch.setDefectQuantity(null);
                }
            } else {
                // This receipt cannot prove a quality conclusion for this batch
                // (unknown count, ambiguous output ownership, or unit mismatch).
                // Clear stale defaults so an old good=quantity/defect=0 pair cannot
                // present an unconfirmed 100% yield as a new receipt result.
                batch.setGoodQuantity(null);
                batch.setDefectQuantity(null);
            }
            batch.calculateMetrics();
            productionBatchRepository.save(batch);
            changed++;
        }
        return changed;
    }

    /** Quality is attributable only when receipt output identity is singular and auditable. */
    private boolean hasUniqueReceiptTerminalOutput(String factoryId,
                                                    ProductionSettlement settlement,
                                                    ProductionBatch batch) {
        if (productionSettlementOutputLineRepository == null || isBlank(settlement.getId())) {
            return true; // normal scalar receipt path: the official batch is the terminal output.
        }
        List<ProductionSettlementOutputLine> lines =
                productionSettlementOutputLineRepository
                        .findByFactoryIdAndSettlementIdOrderByProductTypeIdAscReportedBatchNumberAsc(
                                factoryId, settlement.getId());
        if (lines == null || lines.isEmpty()) {
            return true;
        }
        if (lines.size() != 1 || lines.getFirst() == null) {
            return false;
        }
        ProductionSettlementOutputLine line = lines.getFirst();
        String outputProductTypeId = trimToNull(line.getProductTypeId());
        String batchProductTypeId = trimToNull(batch.getProductTypeId());
        return outputProductTypeId != null && batchProductTypeId != null
                && Objects.equals(outputProductTypeId, batchProductTypeId)
                && (isBlank(line.getReportedBatchNumber()) || isBlank(batch.getBatchNumber())
                || Objects.equals(line.getReportedBatchNumber(), batch.getBatchNumber()))
                && sameUnit(factoryId, line.getQuantityUnit(), batch.getUnit());
    }

    private boolean hasMatchingQualityUnit(ProductionSettlement settlement, ProductionBatch batch) {
        return sameUnit(settlement.getFactoryId(), settlement.getQuantityUnit(), batch.getUnit());
    }

    private boolean sameUnit(String factoryId, String left, String right) {
        String leftValue = trimToNull(left);
        String rightValue = trimToNull(right);
        if (leftValue == null || rightValue == null) return false;
        if (unitContractService == null) {
            throw new IllegalStateException("UnitContractService is required for unit identity checks");
        }
        String canonicalLeft = unitContractService.storageUnit(factoryId, leftValue);
        String canonicalRight = unitContractService.storageUnit(factoryId, rightValue);
        return canonicalLeft != null && !canonicalLeft.isBlank()
                && Objects.equals(canonicalLeft, canonicalRight);
    }

    @Override
    @Transactional
    public ProductionWarehouseReceiptResponse clearProductionTransitLedger(String factoryId, String planId,
                                                                          ProductionTransitClearingRequest request,
                                                                          Long clearedBy) {
        if (request == null || isBlank(request.getClearingReason())) {
            throw new BusinessException(400, "清账原因不能为空")
                    .withHintTarget("中转挂账清账");
        }
        ensurePostingDependencies();

        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);
        ProductionSettlement settlement = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdForUpdate(factoryId, planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产结单", "productionPlanId", planId));

        if (!"PENDING_CLEARING".equals(settlement.getPostingStatus())) {
            throw new BusinessException(409, "该生产结单没有待清账的中转挂账")
                    .withCode("PRODUCTION_TRANSIT_NOT_PENDING")
                    .withHint("当前状态: " + settlement.getPostingStatus())
                    .withHintTarget("中转挂账清账");
        }

        ProductionTransitLedger ledger = productionTransitLedgerRepository
                .findOpenByFactoryIdAndSettlementIdForUpdate(factoryId, settlement.getId(), "OPEN")
                .orElseThrow(() -> new BusinessException(409, "未找到待清账的生产中转挂账")
                        .withCode("PRODUCTION_TRANSIT_LEDGER_NOT_FOUND")
                        .withHintTarget("中转挂账清账"));

        String clearingNote = firstNonBlank(request.getClearingNote(), "");
        String note = firstNonBlank(ledger.getNote(), "");
        String appended = "清账原因: " + request.getClearingReason()
                + (clearingNote.isBlank() ? "" : "; 清账说明: " + clearingNote)
                + "; clearedBy=" + (clearedBy != null ? clearedBy : 0L);
        ledger.setNote(note.isBlank() ? appended : note + "\n" + appended);
        ledger.setStatus("RESOLVED");
        productionTransitLedgerRepository.save(ledger);

        settlement.setPostingStatus("POSTED");
        settlement.setPostingMessage("中转挂账已清账，成品库存入库闭环完成");
        productionSettlementRepository.save(settlement);

        return toWarehouseReceiptResponse(settlement,
                "中转挂账已清账: " + plan.getPlanNumber(),
                List.of("责任侧已处理差异并完成清账"));
    }

    private void validateSettlementRequest(ProductionPlan plan, ProductionSettlementRequest request) {
        // 🔒🔒 (2026-07-04) sourceType 守卫: 存货生产(SAFETY_STOCK) 走「小结」逐批扣减入库, 严禁「结单」。
        //   结单的 postConsumptionToInventory 会再次扣减原料; 而 SAFETY_STOCK 的 interimSettle 早已逐笔
        //   扣过 usedQuantity → 若放行结单 = 原料双重扣减 + 财务口径腐蚀 (幻库存)。UI 隐藏了结单按钮,
        //   但 POST /production-plans/{id}/settle 是开放的 API, 必须在服务层锁死。与 interimSettle 反向对称
        //   (interimSettle 拒绝非 SAFETY_STOCK), 使每条结算路径按计划族锁死。
        if (plan.getSourceType() == PlanSourceType.SAFETY_STOCK) {
            throw new BusinessException(400, "存货生产计划不能结单, 请走「小结」逐批结算")
                    .withCode("SAFETY_STOCK_MUST_INTERIM_SETTLE")
                    .withHint("存货生产(SAFETY_STOCK)按小结逐批扣减入库; 结单会造成原料重复扣减")
                    .withHintTarget("小结");
        }
        if (plan.getStatus() != ProductionPlanStatus.IN_PROGRESS
                && plan.getStatus() != ProductionPlanStatus.PENDING) {
            throw new BusinessException(409, "只能结单未完成的生产计划")
                    .withHint("请刷新生产计划列表查看最新状态")
                    .withHintTarget("核对结单");
        }

        BigDecimal finished = zeroIfNull(request.getActualFinishedQuantity());
        BigDecimal semiFinished = zeroIfNull(request.getActualSemiFinishedQuantity());
        boolean hasWorkflowTerminalOutputs = isWorkflowPlan(plan)
                && hasCompletePositiveTerminalOutputs(request.getTerminalOutputs());
        if (!hasWorkflowTerminalOutputs
                && finished.add(semiFinished).compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "实际产量必须大于 0")
                    .withHint("请录入实际成品产量或半成品产量")
                    .withHintTarget("实际产量");
        }

        BigDecimal planned = zeroIfNull(plan.getPlannedQuantity());
        // A修: 跨单位(份/盒 vs kg)时产量裸比无意义, 跳过超产校验 (prefill 已留 INFO 让人核对).
        boolean crossUnit = isCrossUnitPlan(
                productionBatchRepository.findByFactoryIdAndProductionPlanId(plan.getFactoryId(), plan.getId()))
                || (isWorkflowPlan(plan)
                        && Optional.ofNullable(request.getTerminalOutputs()).orElseGet(ArrayList::new).stream()
                                .map(ProductionSettlementRequest.OutputLine::getProductTypeId)
                                .map(this::trimToNull)
                                .filter(Objects::nonNull)
                                .distinct()
                                .count() > 1);
        if (!crossUnit
                && planned.compareTo(BigDecimal.ZERO) > 0
                && finished.compareTo(planned) > 0
                && isBlank(request.getQuantityVarianceReason())) {
            throw new BusinessException(409, "实际产量超过计划产量, 必须选择超产原因")
                    .withCode("PRODUCTION_OVER_PLAN_REASON_REQUIRED")
                    .withHint("请选择超产原因后再提交; 计划产量: " + planned + ", 实际产量: " + finished)
                    .withHintTarget("超产原因");
        }

        if (isEmpty(request.getRawMaterialConsumptions())
                && isEmpty(request.getSemiFinishedConsumptions())
                && isEmpty(request.getAuxiliaryConsumptions())
                && isBlank(request.getMaterialVarianceReason())) {
            throw new BusinessException(400, "必须录入实际领用明细")
                    .withHint("请至少录入原料、半成品或辅料领用; 确无领用时请选择物料差异原因")
                    .withHintTarget("实际领用");
        }

        if (isEmpty(request.getLaborSegments()) && isBlank(request.getLaborDeferredReason())) {
            throw new BusinessException(400, "必须录入人效或选择工时延期原因")
                    .withHint("请录入工时/人数, 或选择“工时稍后补录”等延期原因")
                    .withHintTarget("人效");
        }
    }

    private void appendConsumptionLines(String factoryId, ProductionPlan plan, String sourceType,
                                        List<ProductionSettlementRequest.ConsumptionLine> requestLines,
                                        List<ProductionSettlementConsumption> target,
                                        java.util.Map<String, String> materialMainCategoryByType) {
        if (isEmpty(requestLines)) {
            return;
        }
        for (ProductionSettlementRequest.ConsumptionLine requestLine : requestLines) {
            if (requestLine == null) {
                throw new BusinessException(400, "领用明细不能为 null")
                        .withHintTarget("实际领用");
            }
            SemiFinishedInventory lockedSemiFinished = null;
            if ("SEMI_FINISHED".equals(sourceType)) {
                if (requestLine.getSemiFinishedInventoryId() == null) {
                    throw new BusinessException(400, "半成品领用必须选择半成品库存")
                            .withHintTarget("半成品领用");
                }
                lockedSemiFinished = semiFinishedInventoryRepository
                        .findByIdForUpdate(requestLine.getSemiFinishedInventoryId())
                        .orElseThrow(() -> new BusinessException(404,
                                "半成品库存不存在: " + requestLine.getSemiFinishedInventoryId())
                                .withHintTarget("半成品领用"));
                if (!factoryId.equals(lockedSemiFinished.getFactoryId())) {
                    throw new BusinessException(403, "无权领用其他工厂的半成品库存")
                            .withHintTarget("半成品领用");
                }
            }
            ProductionSettlementConsumption line = new ProductionSettlementConsumption();
            line.setFactoryId(factoryId);
            line.setProductionPlanId(plan.getId());
            line.setSourceType(sourceType);
            line.setMaterialBatchId(trimToNull(requestLine.getMaterialBatchId()));
            line.setSemiFinishedInventoryId(requestLine.getSemiFinishedInventoryId());
            line.setProductTypeId(trimToNull(requestLine.getProductTypeId()));
            line.setMaterialTypeId(trimToNull(requestLine.getMaterialTypeId()));
            line.setBatchNumber(trimToNull(requestLine.getBatchNumber()));
            line.setWorkflowMaterialNodeId(trimToNull(requestLine.getWorkflowMaterialNodeId()));
            line.setWorkflowInputPortId(trimToNull(requestLine.getWorkflowInputPortId()));
            line.setQuantity(requestLine.getQuantity());
            line.setUnit(trimToNull(requestLine.getUnit()));
            line.setWarehouseId(trimToNull(requestLine.getWarehouseId()));
            line.setNote(trimToNull(requestLine.getNote()));
            line.setAvailableBefore(resolveAvailableBefore(factoryId, plan, sourceType, requestLine,
                    lockedSemiFinished, materialMainCategoryByType));
            if (lockedSemiFinished != null) {
                // The request may carry a friendly/display batch or unit, but the
                // immutable settlement ledger must identify the locked inventory row.
                line.setBatchNumber(trimToNull(lockedSemiFinished.getIntermediateBatchNo()));
                line.setUnit(trimToNull(lockedSemiFinished.getUnit()));
            }
            if (isWorkflowPlan(plan)) {
                line.setProductTypeId(null);
            }
            target.add(line);
        }
    }

    private BigDecimal resolveAvailableBefore(String factoryId, ProductionPlan plan,
                                              String sourceType,
                                              ProductionSettlementRequest.ConsumptionLine line,
                                              SemiFinishedInventory lockedSemiFinished,
                                              java.util.Map<String, String> materialMainCategoryByType) {
        if ("SEMI_FINISHED".equals(sourceType)) {
            SemiFinishedInventory inventory = Objects.requireNonNull(lockedSemiFinished,
                    "locked semi-finished inventory is required");
            BigDecimal available = zeroIfNull(inventory.getAvailableQuantity());
            ensureQuantityWithinAvailable("半成品库存 " + inventory.getIntermediateBatchNo(), line.getQuantity(), available, "半成品领用");
            return available;
        }

        if (isBlank(line.getMaterialBatchId())) {
            throw new BusinessException(400, "原料/辅料领用必须选择批次")
                    .withHintTarget("实际领用");
        }
        MaterialBatch batch = materialBatchRepository.findByIdAndFactoryId(line.getMaterialBatchId(), factoryId)
                .orElseThrow(() -> new BusinessException(404, "原料批次不存在: " + line.getMaterialBatchId())
                        .withHintTarget("实际领用"));
        // 缺陷 38 结单热修: 大类从批次的 materialTypeId 查预取好的 map, ⛔ 不用请求行自带的
        // materialTypeId (用户可能没填/填错) —— 批次是权威, map 里没有就是 null, 按最严格口径。
        ensureMaterialBatchAllowedForSettlement(
                factoryId, plan, line, batch, "实际领用",
                materialMainCategoryByType.get(batch.getMaterialTypeId()));
        BigDecimal available = zeroIfNull(batch.getCurrentQuantity());
        Set<Long> currentProcessBatchIds = processSheetRowRepository == null
                ? Set.of()
                : processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, plan.getId()).stream()
                        .map(ProcessSheetRow::getBatchId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        PendingConsumptionConflict conflict = resolveOtherPendingConsumption(
                factoryId, plan.getId(), currentProcessBatchIds, line.getMaterialBatchId());
        if (conflict.quantity().add(zeroIfNull(line.getQuantity())).compareTo(available) > 0) {
            BatchHold hold = explainBatchHold(factoryId, plan == null ? null : plan.getId(), batch,
                    conflict, zeroIfNull(line.getQuantity()), available, batch.getQuantityUnit());
            if (hold != null) {
                throw new BusinessException(409, hold.message())
                        .withCode(hold.code())
                        .withHint(hold.hint())
                        .withHintTarget("实际领用");
            }
            // hold == null ⇒ 没有别的计划待结、也没有任何预留 ⇒ 纯粹是库存不够。
            // 交给下面 ensureQuantityWithinAvailable 说实话 (可用/本次领用两个数都摆出来)。
        }
        ensureQuantityWithinAvailable("原料批次 " + batch.getBatchNumber(), line.getQuantity(), available, "实际领用");
        if (line.getBatchNumber() == null) {
            line.setBatchNumber(batch.getBatchNumber());
        }
        if (line.getMaterialTypeId() == null) {
            line.setMaterialTypeId(batch.getMaterialTypeId());
        }
        if (line.getUnit() == null) {
            line.setUnit(batch.getQuantityUnit());
        }
        if (line.getWarehouseId() == null) {
            line.setWarehouseId(batch.getWarehouseId());
        }
        return available;
    }

    /**
     * 结单合法仓 = RAW/LOGISTICS 类型 ∪ WORKSHOP 类型，与本方法下面
     * {@link #ensureMaterialBatchAllowedForSettlement} 原来的内联判定字字相同，抽成共享方法后
     * {@link #resolveAvailableRawStock} 新增的可用量查询 (SQL 层 JPQL 子查询, 无法直接调
     * Java 方法) 也教得使用同一个定义 —— 看该方法上方的注释。
     *
     * <p>⛔ 抽在 {@code WarehouseResolver} 上会断现有测试: 那个字段在多个现有测试里是直接
     * {@code mock(WarehouseResolver.class)} 并只针对 isRawOrLogisticsWarehouse/isWorkshopWarehouse 打框，
     * 包装方法若放到那个 mock 上不会执行真实体而恒返 false (2026-09-02 实测踩过)。
     */
    private boolean isSettlementEligibleWarehouse(String factoryId, String warehouseId) {
        return warehouseResolver.isRawOrLogisticsWarehouse(factoryId, warehouseId)
                || warehouseResolver.isWorkshopWarehouse(factoryId, warehouseId);
    }

    private void ensureMaterialBatchAllowedForSettlement(String factoryId,
                                                         ProductionPlan plan,
                                                         ProductionSettlementRequest.ConsumptionLine line,
                                                         MaterialBatch batch,
                                                         String hintTarget,
                                                         String materialMainCategory) {
        if (plan != null) {
            assertMaterialBatchOwnershipMatchesPlan(plan, batch, materialMainCategory, hintTarget);
        }
        if (warehouseResolver == null) {
            throw new BusinessException(500, "仓库解析服务未初始化，不能核对生产领料")
                    .withHintTarget(hintTarget);
        }
        String rawWarehouseId = warehouseResolver.resolveLogisticsId(factoryId);
        if (isBlank(rawWarehouseId)) {
            throw new BusinessException(500, "未配置原料仓/物流仓，不能核对生产领料")
                    .withCode("PRODUCTION_RAW_WAREHOUSE_NOT_CONFIGURED")
                    .withHint("请先维护工厂仓库配置")
                    .withHintTarget(hintTarget);
        }
        // 2026-07-03 料流对齐修复: 生产领料 (transferToFactory) 把原料从原料仓 (WH-LOG) 物理迁到
        //   生产仓 (WORKSHOP/WH-WKS) 后, 逐道报工的 ensureRawMaterialWarehouse 已放行 WORKSHOP 批次
        //   (ProcessSheetServiceImpl 2026-07-03), 但本结单闸 (2026-06-13, 早于领料→生产仓模型) 仍只认
        //   原料仓/物流仓单一仓 → 结单预填自 WKS 批次带入的行被自己的结单闸 409 拒收, 阻断
        //   领料→调拨→生产仓→报工→结单 标准料流。现与报工闸对齐: 除配置原料仓外, 也放行本工厂
        //   RAW/LOGISTICS 类型仓 及 WORKSHOP 类型仓 (料合法落点) 的批次。
        //   诚实-null: batch 无仓 / 仓非 RAW/LOGISTICS/WORKSHOP (如 成品仓 FINISHED / 研发库 RD) → loud-fail 409。
        String batchWarehouseId = batch.getWarehouseId();
        // 缺陷 49 (2026-09-02): 三类仓判定抽成本类的 isSettlementEligibleWarehouse
        // (与 ProductionPlanServiceImpl.resolveAvailableRawStock 共用), ⛔ 不在第二处再写一次这三个枚举值。
        // 抽在本类而不是 WarehouseResolver 上: warehouseResolver 在已有测试里被
        // 直接 mock(WarehouseResolver.class) 且只根据 isRawOrLogisticsWarehouse/isWorkshopWarehouse 打框,
        // 若把共享方法放到它上面, mock 不会执行真实方法体而恒返 false。
        boolean warehouseAllowed = !isBlank(batchWarehouseId)
                && (rawWarehouseId.equals(batchWarehouseId)
                    || isSettlementEligibleWarehouse(factoryId, batchWarehouseId));
        if (!warehouseAllowed) {
            throw new BusinessException(409, "生产结单原料只能从原料仓/物流仓/生产仓领用，不能从其他仓库扣减")
                    .withCode("PRODUCTION_RAW_WAREHOUSE_REQUIRED")
                    .withHint("请重新选择原料仓/物流仓/生产仓批次后再提交")
                    .withHintTarget(hintTarget);
        }

        boolean workflowPlan = isWorkflowPlan(plan);
        BomSettlementEligibility eligibility = resolveBomEligibilityForSettlement(
                factoryId, plan, workflowPlan ? null : line.getProductTypeId());
        if (!eligibility.restricted()) {
            return;
        }
        if (!eligibility.bomFound()) {
            throw new BusinessException(409, "该生产计划没有可用 BOM，不能直接核对原料领用")
                    .withCode("PRODUCTION_BOM_REQUIRED")
                    .withHint("请核对计划固化的 BOM，或由主管确认物料差异后再结单")
                    .withHintTarget(hintTarget);
        }
        if (eligibility.materialTypeIds().isEmpty()) {
            throw new BusinessException(409, "该生产计划 BOM 没有原料明细，不能直接核对原料领用")
                    .withCode("PRODUCTION_BOM_ITEMS_REQUIRED")
                    .withHint("请先维护 BOM 原料明细")
                    .withHintTarget(hintTarget);
        }
        if (!eligibility.materialTypeIds().contains(batch.getMaterialTypeId())) {
            throw new BusinessException(409, "所选原料批次不属于该生产计划 BOM")
                    .withCode("PRODUCTION_CONSUMPTION_NOT_IN_BOM")
                    .withHint("请按产品 BOM 选择原料批次，避免结单扣错料")
                    .withHintTarget(hintTarget);
        }
        if (workflowPlan) {
            validateWorkflowConsumptionInputIdentity(
                    factoryId, plan, line, batch.getMaterialTypeId(), hintTarget);
        }
    }

    private void validateWorkflowConsumptionInputIdentity(String factoryId,
                                                          ProductionPlan plan,
                                                          ProductionSettlementRequest.ConsumptionLine line,
                                                          String materialTypeId,
                                                          String hintTarget) {
        String materialNodeId = trimToNull(line.getWorkflowMaterialNodeId());
        String inputPortId = trimToNull(line.getWorkflowInputPortId());
        if (materialNodeId == null && inputPortId == null) {
            // Manual historical compatibility: the family-wide material set is
            // still enforced above, but no false terminal ownership is invented.
            return;
        }
        if (materialNodeId == null || inputPortId == null) {
            throw new BusinessException(409, "Workflow 原料领用的物料节点和输入端口必须同时完整")
                    .withCode("WORKFLOW_INPUT_IDENTITY_INCOMPLETE")
                    .withHint("请重新提交对应工序报工后再结单")
                    .withHintTarget(hintTarget);
        }
        Map<String, String> pinnedRecipes = Optional.ofNullable(plan.getSelectedBomRecipeIdsByProduct())
                .orElseGet(Map::of);
        boolean matched = pinnedRecipes.keySet().stream().anyMatch(productTypeId ->
                workflowRecipeAllowsInput(factoryId, plan, productTypeId, materialTypeId,
                        materialNodeId, inputPortId));
        if (!matched) {
            throw new BusinessException(409, "原料领用端口不属于计划固定的 Workflow/BOM")
                    .withCode("WORKFLOW_INPUT_SLOT_NOT_IN_PINNED_BOM")
                    .withHint("请按当前计划的工艺端口重新报工，不能用其他版本或其他节点的投料")
                    .withHintTarget(hintTarget);
        }
    }

    private boolean workflowRecipeAllowsInput(String factoryId,
                                              ProductionPlan plan,
                                              String productTypeId,
                                              String materialTypeId,
                                              String materialNodeId,
                                              String inputPortId) {
        String recipeId = Optional.ofNullable(plan.getSelectedBomRecipeIdsByProduct())
                .orElseGet(Map::of).get(productTypeId);
        Integer version = Optional.ofNullable(plan.getSelectedBomVersionsByProduct())
                .orElseGet(Map::of).get(productTypeId);
        if (isBlank(recipeId) || version == null) {
            return false;
        }
        Optional<BomRecipe> recipe = bomRecipeRepository.findById(recipeId)
                .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                .filter(candidate -> productTypeId.equals(candidate.getProductTypeId()))
                .filter(candidate -> Objects.equals(plan.getSelectedBomFamilyId(),
                        candidate.getBomFamilyId()))
                .filter(candidate -> Objects.equals(plan.getSelectedWorkflowRevisionId(),
                        candidate.getWorkflowRevisionId()))
                .filter(candidate -> Objects.equals(plan.getSelectedWorkflowRevisionHash(),
                        candidate.getWorkflowRevisionHash()))
                .filter(candidate -> Objects.equals(version, candidate.getVersion()));
        if (recipe.isEmpty()) {
            return false;
        }
        if (bomWorkflowRevisionService != null) {
            boolean graphContainsInput = com.cretas.aims.service.bom.BomWorkflowRevisionService
                    .resolveInputSlots(bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe.get()))
                    .stream()
                    .anyMatch(slot -> materialNodeId.equals(slot.materialNodeId())
                            && inputPortId.equals(slot.inputPortId()));
            if (!graphContainsInput) {
                return false;
            }
        }
        BomRecipe rulesRecipe = recipe.get().getSharedRecipeId() == null
                || recipe.get().getSharedRecipeId().equals(recipe.get().getId())
                ? recipe.get()
                : bomRecipeRepository.findById(recipe.get().getSharedRecipeId())
                        .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                        .orElse(recipe.get());
        List<BomRecipeItem> eligibleItems = new ArrayList<>(
                bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(rulesRecipe.getId()).stream()
                        .filter(item -> !"OUTPUT_EXCLUSIVE".equals(item.getCostScope()))
                        .toList());
        if (rulesRecipe.getId().equals(recipe.get().getId())) {
            eligibleItems.addAll(bomRecipeItemRepository
                    .findByRecipeIdOrderBySortOrderAsc(recipe.get().getId()).stream()
                    .filter(item -> "OUTPUT_EXCLUSIVE".equals(item.getCostScope()))
                    .toList());
        } else {
            eligibleItems.addAll(bomRecipeItemRepository
                    .findByRecipeIdOrderBySortOrderAsc(recipe.get().getId()).stream()
                    .filter(item -> "OUTPUT_EXCLUSIVE".equals(item.getCostScope()))
                    .toList());
        }
        return eligibleItems.stream().anyMatch(item ->
                Objects.equals(materialTypeId, item.getMaterialTypeId())
                        && materialNodeId.equals(item.getWorkflowMaterialNodeId())
                        && inputPortId.equals(item.getWorkflowInputPortId()));
    }

    /**
     * @param materialMainCategory 物料大类(原料/辅料/包材)，
     *        权威来源 {@code RawMaterialType.mainCategory}。拿不到就显式传 null
     *        (按最严格口径处理)，⛔ 不要猜「辅料」。
     *        ⛔ 无 3-arg 重载 —— 2026-09-02 对抗终审裁定:
     *        3-arg 兼容重载是三次漏改的载体，已删除。
     */
    static void assertMaterialBatchOwnershipMatchesPlan(ProductionPlan plan,
                                                        MaterialBatch batch,
                                                        String materialMainCategory,
                                                        String hintTarget) {
        ProductionInventoryOwnershipGuard.assertMaterialBatchAllowed(
                plan, batch, materialMainCategory, hintTarget);
    }

    private static void requireCustomerSuppliedPlanLineage(ProductionPlan plan, String hintTarget) {
        ProductionInventoryOwnershipGuard.requireCustomerSuppliedPlanLineage(plan, hintTarget);
    }

    // 🔀 单行查询版 resolveMaterialMainCategory(factoryId, materialTypeId) 与并行落地的
    // 缺陷 49 修复重名重复, rebase 后编译器报 "重复的方法" —— 已删除这份, 统一用上面
    // (第 1641 行附近) 那份逐字同源的实现, 不留两份会漂的口径 (形态 D)。

    /**
     * 物料大类批量预取 (循环调用点用, 如结单逐行校验/过账) ——
     * 一次 {@code findByIdIn} 批量取全, ⛔ 不逐行 N+1。这是对抗终审
     * 明确指名的硬约束：结单行有 materialTypeId，必须批量取一次。
     *
     * @return materialTypeId -> mainCategory；查不到/跨厂的不入表 (map.get 恢复因 null,
     *         守卫同样按最严格口径处理)。
     */
    private java.util.Map<String, String> batchResolveMaterialMainCategories(
            String factoryId, java.util.Collection<String> materialTypeIds) {
        if (rawMaterialTypeRepository == null || materialTypeIds == null || materialTypeIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Set<String> ids = materialTypeIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        return rawMaterialTypeRepository.findByIdIn(ids).stream()
                .filter(rmt -> factoryId.equals(rmt.getFactoryId()))
                .collect(java.util.stream.Collectors.toMap(
                        com.cretas.aims.entity.RawMaterialType::getId,
                        com.cretas.aims.entity.RawMaterialType::getMainCategory,
                        (a, b) -> a));
    }

    /**
     * 结单请求里会走物料批次(非半成品)路径的全部 materialTypeId ——
     * 原料 + 辅料两个桶。半成品不走 MaterialBatch/RawMaterialType, 不列入。
     */
    private java.util.Set<String> settlementMaterialTypeIds(ProductionSettlementRequest request) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (java.util.List<ProductionSettlementRequest.ConsumptionLine> lines
                : java.util.List.of(request.getRawMaterialConsumptions(), request.getAuxiliaryConsumptions())) {
            if (lines == null) {
                continue;
            }
            for (ProductionSettlementRequest.ConsumptionLine line : lines) {
                if (line != null && line.getMaterialTypeId() != null) {
                    ids.add(line.getMaterialTypeId());
                }
            }
        }
        return ids;
    }

    /**
     * BOM 结单校验结果 (单一 source of truth, 被写路径守卫 {@link #ensureMaterialBatchAllowedForSettlement}
     * 和只读预过滤端点 {@link #getSettlementBomEligibility} 共用 —— 防止两处判定逻辑各写一份后来漂移不一致)。
     *
     * @param restricted      产品是否有 BOM 限制 (false = productTypeId 为空或 BOM 模块未启用, 不受限)
     * @param bomFound        restricted=true 时, 是否找到该产品当前生效 BOM
     * @param materialTypeIds BOM 允许的原料 materialTypeId 集合 (restricted=false 时无意义)
     */
    private record BomSettlementEligibility(boolean restricted, boolean bomFound, Set<String> materialTypeIds) {
        private static final BomSettlementEligibility UNRESTRICTED =
                new BomSettlementEligibility(false, false, Set.of());
    }

    /**
     * 解析生产计划 BOM 对结单原料领用的限制 (与写路径守卫判定逻辑 1:1 一致, 详见类上方
     * {@link #ensureMaterialBatchAllowedForSettlement} 调用处)。
     *
     * <p>计划创建时已经固化 {@code selectedBomRecipeId/selectedBomVersion}，结单必须优先按该
     * pinned recipe 校验，不能被逐道报工消费行里的 product identity 覆盖，也不能因产品后来激活了
     * 新版本而改写历史计划的校验口径。仅旧计划没有 pin 时，才保留行级 productTypeId 对混 SKU
     * 计划的兼容，并最终回退计划成品 productTypeId。
     */
    private BomSettlementEligibility resolveBomEligibilityForSettlement(String factoryId,
                                                                        ProductionPlan plan,
                                                                        String productTypeIdOverride) {
        if (bomRecipeRepository == null || bomRecipeItemRepository == null) {
            return BomSettlementEligibility.UNRESTRICTED;
        }

        boolean workflowPlan = plan != null && plan.getWorkflowSelectionMode()
                == ProductionBatch.WorkflowSelectionMode.WORKFLOW;
        Map<String, String> pinnedRecipes = plan == null
                || plan.getSelectedBomRecipeIdsByProduct() == null
                ? Map.of() : plan.getSelectedBomRecipeIdsByProduct();
        if (workflowPlan && (plan.getSelectedWorkflowRevisionId() == null
                || isBlank(plan.getSelectedBomFamilyId())
                || pinnedRecipes.isEmpty())) {
            return new BomSettlementEligibility(true, false, Set.of());
        }
        if (workflowPlan && isBlank(productTypeIdOverride) && pinnedRecipes.size() > 1) {
            Set<String> familyMaterialTypeIds = new LinkedHashSet<>();
            for (String outputProductTypeId : pinnedRecipes.keySet()) {
                BomSettlementEligibility outputEligibility = resolveBomEligibilityForSettlement(
                        factoryId, plan, outputProductTypeId);
                if (!outputEligibility.bomFound()) {
                    return new BomSettlementEligibility(true, false, Set.of());
                }
                familyMaterialTypeIds.addAll(outputEligibility.materialTypeIds());
            }
            return new BomSettlementEligibility(true, true, familyMaterialTypeIds);
        }
        String pinnedRecipeId = workflowPlan && !isBlank(productTypeIdOverride)
                ? trimToNull(pinnedRecipes.get(productTypeIdOverride))
                : plan == null ? null : trimToNull(plan.getSelectedBomRecipeId());
        if (workflowPlan && pinnedRecipeId == null) {
            return new BomSettlementEligibility(true, false, Set.of());
        }
        Optional<BomRecipe> recipe;
        if (pinnedRecipeId != null) {
            Integer pinnedVersion = workflowPlan && !isBlank(productTypeIdOverride)
                    ? Optional.ofNullable(plan.getSelectedBomVersionsByProduct())
                            .orElseGet(Map::of).get(productTypeIdOverride)
                    : plan.getSelectedBomVersion();
            recipe = bomRecipeRepository.findById(pinnedRecipeId)
                    .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                    .filter(candidate -> !workflowPlan
                            || Objects.equals(plan.getSelectedBomFamilyId(), candidate.getBomFamilyId()))
                    .filter(candidate -> !workflowPlan
                            || Objects.equals(plan.getSelectedWorkflowRevisionId(), candidate.getWorkflowRevisionId()))
                    .filter(candidate -> pinnedVersion == null
                            || Objects.equals(pinnedVersion, candidate.getVersion()));
        } else {
            String productTypeId = firstNonBlank(
                    productTypeIdOverride,
                    plan != null ? plan.getProductTypeId() : null);
            if (isBlank(productTypeId)) {
                return BomSettlementEligibility.UNRESTRICTED;
            }
            recipe = bomRecipeRepository
                    .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(factoryId, productTypeId);
        }
        if (recipe.isEmpty()) {
            return new BomSettlementEligibility(true, false, Set.of());
        }
        BomRecipe rulesRecipe = recipe.get().getSharedRecipeId() == null
                || recipe.get().getSharedRecipeId().equals(recipe.get().getId())
                ? recipe.get()
                : bomRecipeRepository.findById(recipe.get().getSharedRecipeId())
                        .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                        .orElse(recipe.get());
        List<BomRecipeItem> sharedRuleItems =
                bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(rulesRecipe.getId());
        List<BomRecipeItem> eligibleItems = new ArrayList<>(sharedRuleItems.stream()
                .filter(item -> !"OUTPUT_EXCLUSIVE".equals(item.getCostScope())).toList());
        if (!rulesRecipe.getId().equals(recipe.get().getId())) {
            eligibleItems.addAll(bomRecipeItemRepository
                    .findByRecipeIdOrderBySortOrderAsc(recipe.get().getId()).stream()
                    .filter(item -> "OUTPUT_EXCLUSIVE".equals(item.getCostScope())).toList());
        } else {
            eligibleItems.addAll(sharedRuleItems.stream()
                    .filter(item -> "OUTPUT_EXCLUSIVE".equals(item.getCostScope())).toList());
        }
        if (recipe.get().getWorkflowRevisionId() != null
                && bomWorkflowRevisionService != null
                && eligibleItems.stream().anyMatch(item -> item.getWorkflowMaterialNodeId() != null)) {
            Set<String> exactInputSlots = com.cretas.aims.service.bom.BomWorkflowRevisionService
                    .resolveInputSlots(bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe.get()))
                    .stream()
                    .map(slot -> slot.materialNodeId() + "\u0000"
                            + slot.inputPortId() + "\u0000" + slot.edgeId())
                    .collect(Collectors.toSet());
            // 🔴 2026-08-03 修复: 这道过滤原来会把<b>不挂工艺投入槽</b>的成本行(包材/辅料)一并消灭。
            //
            // 闸门条件是 anyMatch(node != null) —— 正常 workflow BOM 的原料必然带槽, 所以过滤对
            // <b>全部</b>行生效; 而过滤第一个条件就是"三个槽字段都非空", 于是槽本就为 NULL 的
            // 包材/辅料行被直接丢出 eligibleItems → materialTypeIds 少了它们 → 结单时
            // contains() 判定"所选原料批次不属于该生产计划 BOM" → 409。
            //
            // "包材不挂工艺槽"正是系统别处的既有模型: BomRecipeServiceImpl#reconcileUpgradedInputSkeletons
            // 显式把 PACKAGING 排除在槽认领之外, settlement-prefill 也对包材吐 node=null。两处口径打架。
            // prod 实证: F006 结单 eligibility 只认 1 条(冻猪蹄), 而 BOM 有 3 条(冻猪蹄+成品盒+封膜);
            // LIUSHANMEN recipe bb7cd9f2 的 3 条 AUXILIARY 槽全为 NULL, 一旦下计划结单会同样被拒(潜伏)。
            //
            // 修法: 过滤只<b>收窄带槽行</b>, 不<b>消灭无槽行</b>。原料侧"必须落在钉住工艺的精确投入槽上"
            // 这条约束一个字没放松(带槽行仍走原判据), validateWorkflowConsumptionInputIdentity 二道闸不动。
            // 本方法只产出白名单集合, 不参与任何扣减与成本计算。
            eligibleItems = eligibleItems.stream()
                    .filter(item -> {
                        boolean slotBound = item.getWorkflowMaterialNodeId() != null
                                || item.getWorkflowInputPortId() != null
                                || item.getWorkflowEdgeId() != null;
                        if (!slotBound) {
                            // 包材/辅料等不挂工艺投入槽的成本行 —— 照常计入允许集。
                            return true;
                        }
                        return item.getWorkflowMaterialNodeId() != null
                                && item.getWorkflowInputPortId() != null
                                && item.getWorkflowEdgeId() != null
                                && exactInputSlots.contains(item.getWorkflowMaterialNodeId()
                                + "\0" + item.getWorkflowInputPortId()
                                + "\0" + item.getWorkflowEdgeId());
                    })
                    .toList();
        }
        Set<String> bomMaterialTypeIds = eligibleItems.stream()
                .map(BomRecipeItem::getMaterialTypeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // 🔴 2026-08-18 修复: 调料存在【另一张表】bom_seasoning_items, 从来进不了这个白名单。
        //
        // prod 实测(F006 PLAN-1786954657305): 系统自己生成的领料单 9 行, 白名单只认 7 行 ——
        // 差的两行正是 AUXILIARY 的「香辛料」「黄油调味料」。工人按单领了、投了, 结单时被告知
        // 「所选原料批次不属于该生产计划 BOM」。同一个「什么算 BOM 内物料」有两个定义(形态 D):
        //   产出侧 ProcessSheetServiceImpl.buildAutomaticBomRequirements 读 bom_seasoning_items 合成投料行
        //   消费侧 这里 只读 bom_recipe_items ⇒ 自己发的料自己不认
        //
        // ⚠️ 这半个洞是「领料单补上调料」那个修复(#2803)造出来的: 在那之前领料单里没有调料,
        //    没人领得到, 也就撞不到这道闸。修一处会把拒答挪到下一处 —— 本仓硬约束 8 的原形。
        //
        // ⚠️ 与上面那段 2026-08-03 的注释同族: 都是「本来就不挂工艺投入槽的成本行被白名单漏掉」。
        //    调料同样不挂槽, 所以这里直接并入, 不参与槽判定。
        //    本方法只产出白名单集合, 不参与任何扣减与成本计算; 原料侧「必须落在钉住工艺的精确
        //    投入槽上」那条约束一个字没放松(带槽行仍走上面的判据)。
        if (bomSeasoningItemRepository != null) {
            Set<String> recipeIdsForSeasoning = new LinkedHashSet<>();
            recipeIdsForSeasoning.add(rulesRecipe.getId());
            recipeIdsForSeasoning.add(recipe.get().getId());
            for (String seasoningRecipeId : recipeIdsForSeasoning) {
                try {
                    bomSeasoningItemRepository.findByRecipeIdOrderBySeqAsc(seasoningRecipeId).stream()
                            .filter(Objects::nonNull)
                            .map(com.cretas.aims.entity.bom.BomSeasoningItem::getMaterialTypeId)
                            .map(this::trimToNull)
                            .filter(Objects::nonNull)
                            .forEach(bomMaterialTypeIds::add);
                } catch (RuntimeException ex) {
                    // ⛔ 读不到调料不能让结单整个挂掉, 但也不能装作没这回事 —— 留痕给排查用。
                    log.warn("[结单BOM白名单] 调料明细读不出来 recipe={}: {}",
                            seasoningRecipeId, ex.getMessage());
                }
            }
        }
        if (bomItemSubstituteRepository != null && !eligibleItems.isEmpty()) {
            Set<Long> eligibleParentIds = eligibleItems.stream()
                    .map(BomRecipeItem::getId).filter(Objects::nonNull).collect(Collectors.toSet());
            Set<String> eligibleRecipeIds = eligibleItems.stream()
                    .map(BomRecipeItem::getRecipeId).collect(Collectors.toSet());
            eligibleRecipeIds.stream().flatMap(eligibleRecipeId ->
                    bomItemSubstituteRepository.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(
                            factoryId, eligibleRecipeId).stream())
                    .filter(rule -> rule.getParentKind() == BomItemSubstitute.ParentKind.RECIPE_ITEM)
                    .filter(rule -> eligibleParentIds.contains(rule.getParentRecipeItemId()))
                    .map(BomItemSubstitute::getSubstituteMaterialTypeId)
                    .filter(Objects::nonNull)
                    .forEach(bomMaterialTypeIds::add);
        }
        return new BomSettlementEligibility(true, true, bomMaterialTypeIds);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionSettlementBomEligibilityResponse getSettlementBomEligibility(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);
        BomSettlementEligibility eligibility =
                resolveBomEligibilityForSettlement(factoryId, plan, null);
        return ProductionSettlementBomEligibilityResponse.builder()
                .restricted(eligibility.restricted())
                .bomFound(eligibility.bomFound())
                .materialTypeIds(new ArrayList<>(eligibility.materialTypeIds()))
                .build();
    }

    private void ensureQuantityWithinAvailable(String label, BigDecimal requested, BigDecimal available, String hintTarget) {
        BigDecimal qty = zeroIfNull(requested);
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, label + " 领用数量必须大于 0")
                    .withHintTarget(hintTarget);
        }
        if (qty.compareTo(available) > 0) {
            throw new BusinessException(409, label + " 领用超出可用量; 可用: " + available + ", 本次领用: " + qty)
                    .withCode("PRODUCTION_CONSUMPTION_EXCEEDS_AVAILABLE")
                    .withHint("请将领用数量调整到不超过 " + available)
                    .withHintTarget(hintTarget);
        }
    }

    private void postConsumptionToInventory(String factoryId,
                                            ProductionPlan plan,
                                            List<ProductionSettlementConsumption> lines,
                                            Long operatorId) {
        if (isEmpty(lines)) {
            return;
        }
        // 缺陷 38 结单热修: 过账环节的第二个循环, 同样需要大类 —— 一次性批量预取, 不逐行查。
        java.util.Map<String, String> materialMainCategoryByType = batchResolveMaterialMainCategories(
                factoryId, lines.stream()
                        .map(ProductionSettlementConsumption::getMaterialTypeId)
                        .collect(java.util.stream.Collectors.toSet()));
        for (ProductionSettlementConsumption line : lines) {
            if ("SEMI_FINISHED".equals(line.getSourceType())) {
                postSemiFinishedConsumption(factoryId, line, operatorId);
            } else {
                postMaterialBatchConsumption(factoryId, plan, line, materialMainCategoryByType);
            }
        }
    }

    private void postMaterialBatchConsumption(String factoryId,
                                               ProductionPlan plan,
                                               ProductionSettlementConsumption line,
                                               java.util.Map<String, String> materialMainCategoryByType) {
        MaterialBatch batch = materialBatchRepository
                .findByIdAndFactoryIdForUpdate(line.getMaterialBatchId(), factoryId)
                .orElseThrow(() -> new BusinessException(404, "原料批次不存在: " + line.getMaterialBatchId())
                        .withHintTarget("实际领用"));
        // BOM identity was already validated while the request line still carried
        // its mixed-SKU productTypeId. Re-running BOM resolution here would lose
        // that line-level identity and could incorrectly fall back to the plan's
        // primary product. The ownership guard remains mandatory after the lock.
        // 大类同上: 从批次的 materialTypeId 查预取好的 map, 不用行自带的字面值。
        assertMaterialBatchOwnershipMatchesPlan(
                plan, batch, materialMainCategoryByType.get(batch.getMaterialTypeId()), "实际领用");
        // 质检放行 (缺口 11): 结单领用在制半成品批次 —— 待检 / 不合格不能领 (只作用于 PRODUCTION_BATCH 来源)。
        com.cretas.aims.service.quality.release.QcInspectionGuard.assertWipBatchDrawable(
                batch, batch.getBatchNumber(), "实际领用");
        BigDecimal available = zeroIfNull(batch.getCurrentQuantity());
        ensureQuantityWithinAvailable("原料批次 " + batch.getBatchNumber(), line.getQuantity(), available, "实际领用");

        BigDecimal qty = zeroIfNull(line.getQuantity());
        batch.setUsedQuantity(zeroIfNull(batch.getUsedQuantity()).add(qty));
        batch.setLastUsedAt(LocalDateTime.now());
        if (batch.getCurrentQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            batch.setStatus(MaterialBatchStatus.USED_UP);
        }
        materialBatchRepository.save(batch);
        inventoryLowStockEventPublisher.publishIfLowStock(factoryId, batch, "OUT");
    }

    private void postSemiFinishedConsumption(String factoryId, ProductionSettlementConsumption line,
                                              Long operatorId) {
        if (semiFinishedInventoryTransactionRepository == null
                || line.getId() == null || line.getSettlementId() == null) {
            throw new BusinessException(409, "半成品结单消耗缺少可追溯 OUT 流水凭据")
                    .withCode("WIP_LEDGER_UNAVAILABLE")
                    .withHintTarget("半成品领用");
        }
        SemiFinishedInventory inventory = semiFinishedInventoryRepository.findByIdForUpdate(line.getSemiFinishedInventoryId())
                .orElseThrow(() -> new BusinessException(404, "半成品库存不存在: " + line.getSemiFinishedInventoryId())
                        .withHintTarget("半成品领用"));
        if (!factoryId.equals(inventory.getFactoryId())) {
            throw new BusinessException(403, "无权领用其他工厂的半成品库存")
                    .withHintTarget("半成品领用");
        }
        // 质检放行 (缺口 11): 结单领用常驻半成品 —— 待检 / 不合格不能领。
        com.cretas.aims.service.quality.release.QcInspectionGuard.assertSemiDrawable(
                inventory, zeroIfNull(line.getQuantity()), "半成品领用");
        BigDecimal available = zeroIfNull(inventory.getAvailableQuantity());
        ensureQuantityWithinAvailable("半成品库存 " + inventory.getIntermediateBatchNo(), line.getQuantity(), available, "半成品领用");

        BigDecimal qty = zeroIfNull(line.getQuantity());
        BigDecimal remaining = available.subtract(qty);
        SemiFinishedInventoryTransaction out = WipLedgerEntries.settlementConsumptionRow(
                factoryId, inventory, qty, remaining, line.getSettlementId(), line.getId(), operatorId);
        if (out == null) {
            throw new BusinessException(409, "半成品结单消耗无法生成 OUT 流水")
                    .withCode("WIP_LEDGER_UNAVAILABLE")
                    .withHintTarget("半成品领用");
        }
        inventory.setConsumedQuantity(zeroIfNull(inventory.getConsumedQuantity()).add(qty));
        inventory.setAvailableQuantity(remaining);
        inventory.setStatus(remaining.compareTo(BigDecimal.ZERO) <= 0
                ? SemiFinishedInventory.Status.DEPLETED
                : SemiFinishedInventory.Status.AVAILABLE);
        semiFinishedInventoryRepository.save(inventory);
        semiFinishedInventoryTransactionRepository.save(out);
    }

    /**
     * 🔴🔒 R2 (2026-07-04): 结单族 —— 按 process-row 严格扣减常驻 SFI/FG 投料 (防 phantom 库存腐蚀)。
     *
     * <p>逐道录入把常驻半成品(SFI, {@code semiFinished=true})/成品(FG, {@code finishedGoods=true})作投料记在
     * {@code process_sheet_rows.upstreamSources}。这些投料边<b>不写</b> MaterialConsumption (投的是外部常驻库存,
     * 非本计划在制 WIP), 且结单预填把 {@code semiFinishedConsumptions} 留空 + FG 投料结单请求无字段 → 此前
     * 永不扣减 = 幻库存 (消耗了却不减可用量)。此处严格扣减 (镜像小结 §②/(C) 的 consumeClerkSemiStrict /
     * consumeForFeedStrict, 缺失/不足即抛 → 整 {@code settleProduction @Transactional} 回滚, 禁止降级)。
     *
     * <ul>
     *   <li><b>仅结单族</b>: SAFETY_STOCK 走小结 (interimSettle) 扣减, 此处守卫跳过避免双扣。</li>
     *   <li><b>防双扣</b>: 若结单请求带手工 {@code semiFinishedConsumptions} (文员手工管理 SFI 领用) → 尊重手工,
     *       跳过 SFI 自动扣; FG 投料无手工字段, 照常自动扣。</li>
     *   <li><b>幂等</b>: {@code settleProduction} 已阻断重复结单 (PRODUCTION_ALREADY_SETTLED + idempotencyKey),
     *       故本扣减每个计划恰执行一次。</li>
     * </ul>
     */
    private void deductProcessSheetStockFeeds(String factoryId, ProductionPlan plan,
                                              ProductionSettlementRequest request) {
        if (plan == null || plan.getSourceType() == PlanSourceType.SAFETY_STOCK) {
            return;   // SAFETY_STOCK 由小结扣减
        }
        if (processSheetRowRepository == null || isBlank(plan.getId())) {
            return;
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, plan.getId());
        if (isEmpty(rows)) {
            return;
        }
        Set<String> manuallyPostedSemiBatchNumbers = resolveManualSemiBatchNumbers(factoryId, request);
        BigDecimal sfiOut = BigDecimal.ZERO;
        BigDecimal fgOut = BigDecimal.ZERO;
        int sfiCount = 0;
        int fgCount = 0;
        List<ProductionSettlementPrefillResponse.Issue> ignore = new ArrayList<>();
        for (ProcessSheetRow row : rows) {
            if (!isUsableProcessSheetRow(row)) {
                continue;
            }
            ProcessSheetRowRequest req = parseProcessSheetRowPayload(row, ignore);
            if (req == null || isEmpty(req.getUpstreamSources())) {
                continue;
            }
            for (ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
                BigDecimal feed = zeroIfNull(ref.getFeedQuantityKg());
                if (feed.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                String srcBatchNo = ref.getSourceBatchNumber();
                if (ref.isFinishedGoods()) {
                    if (finishedGoodsFeedService == null) {
                        // 禁止降级: 有 FG 投料却无扣减服务 → loud-fail (绝不静默留 phantom)
                        throw new BusinessException(500, "成品投料扣减服务未就绪, 无法完成结单 (投料来源: "
                                + srcBatchNo + ")").withHintTarget("核对结单");
                    }
                    BigDecimal drawn = finishedGoodsFeedService.consumeForFeedStrict(factoryId, srcBatchNo, feed, "kg");
                    fgOut = fgOut.add(zeroIfNull(drawn));
                    fgCount++;
                } else if (ref.isSemiFinished()) {
                    if (ref.isStockConsumedAtSubmit()) {
                        continue;   // 回料已在正式报工事务内严格扣减；结单只做一次消费
                    }
                    if (manuallyPostedSemiBatchNumbers.contains(srcBatchNo)) {
                        continue;   // 仅该同一 SFI 已走手工领用时跳过，不能全局跳过其他真实投料
                    }
                    if (wipInventoryService == null) {
                        throw new BusinessException(500, "半成品投料扣减服务未就绪, 无法完成结单 (投料来源: "
                                + srcBatchNo + ")").withHintTarget("核对结单");
                    }
                    BigDecimal drawn = wipInventoryService.consumeClerkSemiStrict(factoryId, srcBatchNo, feed);
                    sfiOut = sfiOut.add(zeroIfNull(drawn));
                    sfiCount++;
                    // 血缘: 产出它的生产批次 → 本行生产批次 (结单只扣一次, 见上方幂等说明)。
                    if (semiFeedLineageRecorder != null) {
                        semiFeedLineageRecorder.recordSemiFeed(factoryId, srcBatchNo, row.getBatchId(), drawn,
                                null, com.cretas.aims.utils.SecurityUtils.currentUserId(),
                                "settleSfiFeed:" + plan.getId() + ":row" + row.getId() + ":" + srcBatchNo,
                                "SETTLEMENT_SFI_FEED");
                    }
                }
            }
        }
        if (sfiCount > 0 || fgCount > 0) {
            log.info("结单族 SFI/FG 投料扣减 (R2): factoryId={}, planId={}, SFI {} 笔 ({}), FG {} 笔 ({}){}",
                    factoryId, plan.getId(), sfiCount, sfiOut, fgCount, fgOut,
                    manuallyPostedSemiBatchNumbers.isEmpty()
                            ? ""
                            : " [同批次 SFI 手工补录已定点替换]");
        }
    }

    private Set<String> resolveManualSemiBatchNumbers(String factoryId,
                                                       ProductionSettlementRequest request) {
        if (request == null || isEmpty(request.getSemiFinishedConsumptions())
                || semiFinishedInventoryRepository == null) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (ProductionSettlementRequest.ConsumptionLine line : request.getSemiFinishedConsumptions()) {
            if (line == null || line.getSemiFinishedInventoryId() == null) {
                continue;
            }
            semiFinishedInventoryRepository.findById(line.getSemiFinishedInventoryId())
                    .filter(inventory -> factoryId.equals(inventory.getFactoryId()))
                    .map(SemiFinishedInventory::getIntermediateBatchNo)
                    .map(this::trimToNull)
                    .ifPresent(result::add);
        }
        return result;
    }

    private List<ProductionSettlementLabor> toLaborLines(String factoryId, String planId, String settlementId,
                                                         List<ProductionSettlementRequest.LaborSegment> requestLines) {
        if (isEmpty(requestLines)) {
            return Collections.emptyList();
        }
        List<ProductionSettlementLabor> result = new ArrayList<>();
        for (ProductionSettlementRequest.LaborSegment requestLine : requestLines) {
            if (requestLine == null || requestLine.getHeadcount() == null) {
                // The process-sheet fact did not include a worker count. Do not turn
                // that absence into one worker; the source row remains the audit fact.
                continue;
            }
            ProductionSettlementLabor line = new ProductionSettlementLabor();
            line.setSettlementId(settlementId);
            line.setFactoryId(factoryId);
            line.setProductionPlanId(planId);
            line.setWorkerId(requestLine.getWorkerId());
            line.setWorkerName(trimToNull(requestLine.getWorkerName()));
            line.setWorkType(trimToNull(requestLine.getWorkType()));
            line.setMinutes(requestLine.getMinutes());
            line.setHeadcount(requestLine.getHeadcount());
            line.setHourlyRate(requestLine.getHourlyRate());
            line.setLaborCost(requestLine.getLaborCost());
            line.setNote(trimToNull(requestLine.getNote()));
            result.add(line);
        }
        return result;
    }

    private ProductionSettlementResponse toSettlementResponse(ProductionSettlement settlement, List<String> warnings) {
        return ProductionSettlementResponse.builder()
                .settlementId(settlement.getId())
                .productionPlanId(settlement.getProductionPlanId())
                .planNumber(settlement.getPlanNumber())
                .status(settlement.getPlanStatusAfter() != null ? settlement.getPlanStatusAfter().name() : null)
                .plannedQuantity(settlement.getPlannedQuantity())
                .actualFinishedQuantity(settlement.getActualFinishedQuantity())
                .actualSemiFinishedQuantity(settlement.getActualSemiFinishedQuantity())
                .quantityUnit(resolveSettlementQuantityUnit(settlement))
                .postingStatus(settlement.getPostingStatus())
                .postingMessage(settlement.getPostingMessage())
                .warehouseReceivedQuantity(settlement.getWarehouseReceivedQuantity())
                .warehouseVarianceQuantity(settlement.getWarehouseVarianceQuantity())
                .finishedGoodsBatchId(settlement.getFinishedGoodsBatchId())
                .transitLedgerId(settlement.getTransitLedgerId())
                .outputLines(loadOutputLineDtos(settlement))
                .warnings(warnings != null ? warnings : Collections.emptyList())
                .createdClearingLedgerIds(settlement.getTransitLedgerId() != null
                        ? List.of(settlement.getTransitLedgerId())
                        : Collections.emptyList())
                .createdInventoryTxnIds(Collections.emptyList())
                .settled(true)
                .build();
    }

    /**
     * 「这张计划没有仓库确认单」的<b>如实回答</b> —— ⛔ 不是异常。
     *
     * <p>存货生产(SAFETY_STOCK)走小结, 成品在小结时就入库了, {@code production_settlements}
     * 里本来就没有行。读接口把它抛成 404, 前端就会弹一个红条说「尚未结单」,
     * 而用户看到的是**别的计划**的报错 (2026-08-19 客户实报)。
     */
    private ProductionSettlementResponse notSettledResponse(String factoryId, String planId) {
        String planNumber = null;
        String planStatus = null;
        if (productionPlanRepository != null) {
            ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId).orElse(null);
            if (plan != null) {
                planNumber = plan.getPlanNumber();
                planStatus = plan.getStatus() == null ? null : plan.getStatus().name();
            }
        }
        return ProductionSettlementResponse.builder()
                .settled(false)
                .productionPlanId(planId)
                .planNumber(planNumber)
                .status(planStatus)
                .warnings(Collections.emptyList())
                .createdClearingLedgerIds(Collections.emptyList())
                .createdInventoryTxnIds(Collections.emptyList())
                .outputLines(Collections.emptyList())
                .build();
    }

    /**
     * Historical settlements may predate quantity-unit persistence. Recover the
     * canonical unit from the same submitted terminal process rows that produced
     * the settlement quantity. This method is intentionally read-only: warehouse
     * confirmation persists the resolved unit in the existing settlement atomically.
     */
    private String resolveSettlementQuantityUnit(ProductionSettlement settlement) {
        String persisted = canonicalReceiptUnit(trimToNull(settlement.getQuantityUnit()));
        if (persisted != null || processSheetRowRepository == null
                || isBlank(settlement.getFactoryId()) || isBlank(settlement.getProductionPlanId())) {
            return persisted;
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(
                settlement.getFactoryId(), settlement.getProductionPlanId());
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<ProductionSettlementPrefillResponse.Issue> ignoredIssues = new ArrayList<>();
        List<ParsedProcessSheetRow> parsedRows = rows.stream()
                .filter(this::isUsableProcessSheetRow)
                .map(row -> new ParsedProcessSheetRow(row, parseProcessSheetRowPayload(row, ignoredIssues)))
                .filter(parsed -> parsed.request() != null)
                .toList();
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(
                settlement.getProductionPlanId(), settlement.getFactoryId()).orElse(null);
        String recovered = resolveTerminalOutputUnit(deriveTerminalProcessSheetOutputs(
                settlement.getFactoryId(), plan, parsedRows));
        if (recovered != null) {
            log.info("只读恢复历史结单成品单位: factoryId={}, planId={}, settlementId={}, quantityUnit={}",
                    settlement.getFactoryId(), settlement.getProductionPlanId(), settlement.getId(), recovered);
        }
        return recovered;
    }

    /**
     * 把 BY_STOCK 小结的库存真值桥接为仓库确认所需的结单元数据。
     *
     * <p>历史上 {@code interim-settle} 与普通 {@code settle} 各写一张互不相通的表：前者已经扣料并
     * 创建可用 FG，后者才是仓库列表 GET/确认入口读取的表。这里在计划行悲观锁内做一次严格派生，
     * 只插入缺失的 {@link ProductionSettlement}；不会调用小结、扣料、保存报工行或创建 FG。
     */
    private ProductionSettlement bridgeProductionSettlementForWarehouse(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(planId)
                .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        // 所有补建入口都锁同一 plan；拿锁后必须重读，保证重复 GET/刷新只创建一行。
        Optional<ProductionSettlement> raced = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId);
        if (raced.isPresent() && plan.getSourceType() != PlanSourceType.SAFETY_STOCK) {
            return raced.get();
        }
        if (plan.getSourceType() != PlanSourceType.SAFETY_STOCK) {
            throw missingProductionSettlement();
        }
        if (productionInterimSettlementRepository == null
                || finishedGoodsBatchRepository == null || processSheetRowRepository == null) {
            throw new BusinessException(500, "存货生产仓库确认桥接服务未初始化")
                    .withCode("BY_STOCK_SETTLEMENT_BRIDGE_UNAVAILABLE")
                    .withHint("请联系管理员检查小结、报工和成品库存组件")
                    .withHintTarget("仓库实收");
        }
        if (plan.getStatus() != ProductionPlanStatus.COMPLETED) {
            throw new BusinessException(409, "存货生产计划尚未停产, 不能由仓库确认入库")
                    .withCode("BY_STOCK_RECEIPT_REQUIRES_STOPPED_PLAN")
                    .withHint("请先完成本次小结并停产")
                    .withHintTarget("仓库实收");
        }

        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId);
        if (isEmpty(rows) || rows.stream().anyMatch(row -> row == null
                || row.getInterimSettledAt() == null
                || (!ProcessSheetRow.SUBMISSION_SUBMITTED.equals(row.getSubmissionStatus())
                        && !ProcessSheetRow.SUBMISSION_DRAFT.equals(row.getSubmissionStatus())))) {
            throw incompleteByStockSettlementRows();
        }

        List<ProductionInterimSettlement> sessions = productionInterimSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqAsc(factoryId, planId);
        if (isEmpty(sessions)) {
            if (rows.stream().anyMatch(row ->
                    ProcessSheetRow.SUBMISSION_DRAFT.equals(row.getSubmissionStatus()))) {
                throw incompleteByStockSettlementRows();
            }
            throw new BusinessException(409, "该存货生产计划没有可核验的小结会话")
                    .withCode("BY_STOCK_INTERIM_SETTLEMENT_REQUIRED")
                    .withHint("系统不会用报工行猜测库存结果；请先完成小结")
                    .withHintTarget("仓库实收");
        }
        for (int i = 0; i < sessions.size(); i++) {
            Integer sessionSeq = sessions.get(i).getSessionSeq();
            if (sessionSeq == null || sessionSeq != i + 1) {
                throw new BusinessException(409, "存货生产小结会话序号不连续, 不能安全补建仓库确认")
                        .withCode("BY_STOCK_INTERIM_SETTLEMENT_SEQUENCE_INVALID")
                        .withHint("请联系管理员核对小结审计记录；系统未改动任何库存")
                        .withHintTarget("仓库实收");
            }
        }
        // 审批撤销会把原正式报工退回 DRAFT；合法重新小结会重新过账并打戳，
        // 但历史实现不会恢复 submissionStatus。只认与本计划当前有效小结会话
        // 精确相同的 postedAt，避免把普通草稿、旧撤销会话或伪造时间戳当成已结算。
        if (rows.stream().anyMatch(row ->
                ProcessSheetRow.SUBMISSION_DRAFT.equals(row.getSubmissionStatus())
                        && sessions.stream().noneMatch(session ->
                                Objects.equals(session.getPostedAt(), row.getInterimSettledAt())))) {
            throw incompleteByStockSettlementRows();
        }

        LinkedHashSet<String> batchNumbers = new LinkedHashSet<>();
        BigDecimal summarizedFinishedQuantity = BigDecimal.ZERO;
        for (ProductionInterimSettlement session : sessions) {
            Map<String, Object> summary = session.getSummary();
            if (summary == null) {
                throw invalidInterimFinishedGoods("小结摘要缺失");
            }
            Object numbers = summary.get("finishedGoodsBatchNumbers");
            if (numbers instanceof Collection<?> collection) {
                collection.stream()
                        .map(value -> value != null ? String.valueOf(value) : null)
                        .map(this::trimToNull)
                        .filter(Objects::nonNull)
                        .forEach(batchNumbers::add);
            }
            BigDecimal sessionFinished = asBigDecimal(summary.get("finishedQuantity"));
            if (sessionFinished == null || sessionFinished.signum() < 0) {
                throw invalidInterimFinishedGoods("小结成品数量无效");
            }
            summarizedFinishedQuantity = summarizedFinishedQuantity.add(sessionFinished);
        }
        if (batchNumbers.isEmpty() || summarizedFinishedQuantity.signum() <= 0) {
            throw invalidInterimFinishedGoods("小结没有可核验的成品批次或成品数量");
        }

        List<FinishedGoodsBatch> finishedGoodsBatches = new ArrayList<>();
        BigDecimal producedQuantity = BigDecimal.ZERO;
        String quantityUnit = null;
        String canonicalQuantityUnit = null;
        boolean multiTarget = byStockTargetIds(plan).size() > 1;
        boolean mixedUnits = false;
        for (String batchNumber : batchNumbers) {
            FinishedGoodsBatch finishedGoods = finishedGoodsBatchRepository
                    .findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                    .orElseThrow(() -> invalidInterimFinishedGoods("小结记录的成品批次不存在: " + batchNumber));
            validateInterimFinishedGoodsIdentity(plan, finishedGoods);

            String batchUnit = trimToNull(finishedGoods.getUnit());
            String canonicalBatchUnit = canonicalReceiptUnit(batchUnit);
            if (canonicalQuantityUnit == null) {
                quantityUnit = batchUnit;
                canonicalQuantityUnit = canonicalBatchUnit;
            } else if (!Objects.equals(canonicalQuantityUnit, canonicalBatchUnit)) {
                // 多成品计划各 SKU 单位本来就可以不同(袋/盒) —— 真值在逐 SKU 产出行上, 抬头不加总。
                // 单成品计划出现两种单位仍是数据错误。
                if (!multiTarget) {
                    throw invalidInterimFinishedGoods("多次小结的成品批次计量单位不一致: "
                            + quantityUnit + " / " + batchUnit);
                }
                mixedUnits = true;
            }

            BigDecimal batchProduced = zeroIfNull(finishedGoods.getProducedQuantity());
            if (batchProduced.signum() <= 0) {
                throw invalidInterimFinishedGoods("小结成品批次数量无效: " + batchNumber);
            }
            producedQuantity = producedQuantity.add(batchProduced);
            finishedGoodsBatches.add(finishedGoods);
        }
        if (producedQuantity.compareTo(summarizedFinishedQuantity) != 0) {
            throw invalidInterimFinishedGoods("小结摘要数量与成品批次合计不一致: 摘要="
                    + summarizedFinishedQuantity + ", 批次合计=" + producedQuantity);
        }

        ProductionInterimSettlement latestSession = sessions.get(sessions.size() - 1);
        ProductionSettlement settlement = raced.orElseGet(ProductionSettlement::new);
        if (settlement.getId() == null) {
            settlement.setId(UUID.randomUUID().toString());
        }
        settlement.setFactoryId(factoryId);
        settlement.setProductionPlanId(planId);
        settlement.setPlanNumber(plan.getPlanNumber());
        settlement.setIdempotencyKey("by-stock-interim:" + latestSession.getId());
        settlement.setPlannedQuantity(zeroIfNull(plan.getPlannedQuantity()));
        // ⛔ 不同单位的数量不能相加: 混合单位时抬头留空, 不编一个「310 袋 + 238 盒 = 548」出来。
        settlement.setActualFinishedQuantity(mixedUnits ? null : producedQuantity);
        settlement.setActualSemiFinishedQuantity(BigDecimal.ZERO);
        settlement.setQuantityUnit(mixedUnits ? null : quantityUnit);
        settlement.setPlanStatusAfter(ProductionPlanStatus.COMPLETED);
        /*
         * SAFETY_STOCK 采用“生产小结即入库”的单一真值：InterimSettle 已经完成扣料并创建
         * 成品批次，桥接表只镜像同一过账事实，绝不再要求仓库二次确认。
         */
        settlement.setPostingStatus("POSTED");
        settlement.setPostingMessage("生产小结已完成扣料并入库；共 " + finishedGoodsBatches.size()
                + " 个成品批次，无需重复仓库确认");
        // 兼容旧的单批次标量；多批次不能伪造某一批次作为整张结单的代表。
        settlement.setFinishedGoodsBatchId(finishedGoodsBatches.size() == 1
                ? finishedGoodsBatches.get(0).getId() : null);
        settlement.setSettledBy(latestSession.getPostedBy());
        settlement.setSettledAt(latestSession.getPostedAt());
        settlement.setWarehouseReceiptIdempotencyKey("by-stock-interim:" + latestSession.getId());
        settlement.setWarehouseReceivedQuantity(mixedUnits ? null : producedQuantity);
        settlement.setWarehouseVarianceQuantity(BigDecimal.ZERO);
        settlement.setWarehouseVarianceReason(null);
        settlement.setWarehouseResponsibilitySide(null);
        settlement.setWarehouseVarianceNote(null);
        settlement.setTransitLedgerId(null);
        settlement.setWarehouseReceivedBy(latestSession.getPostedBy());
        settlement.setWarehouseReceivedAt(latestSession.getPostedAt());
        ProductionSettlement saved = productionSettlementRepository.save(settlement);
        persistByStockSettlementOutputs(factoryId, plan, saved, finishedGoodsBatches);
        log.info("BY_STOCK 结单桥接已对齐小结入库真值: factoryId={}, planId={}, settlementId={}, sessions={}, fgBatches={}, quantity={} {}",
                factoryId, planId, saved.getId(), sessions.size(), batchNumbers,
                mixedUnits ? "(混合单位, 见产出行)" : producedQuantity, mixedUnits ? "" : quantityUnit);
        return saved;
    }

    /**
     * 存货生产(小结即入库)结单的逐成品产出行 —— 与普通结单 {@link #persistWorkflowSettlementOutputs} 同一张表、
     * 同一套 BOM 钉选, 只是数量与成品批次取自小结已建好的 FG(小结即实收, 不再要仓库二次确认)。
     *
     * <p>为什么必须写: 结单成本重算 {@code recalculateSettledPlanCosts} 按产出行分摊总成本并回写 FG 单位成本,
     * 桥接从前只写抬头 ⇒ 产出行为空 ⇒ 恒 409「结单产出尚未全部入库」, 设备费/其他费用永远录不进去,
     * 存货生产计划的成本永远停在「部分」(F006【演示L1】计划 836010b8, 2026-10-24)。
     *
     * <p>只对 Workflow 计划写(钉了 BOM family 才有产出行口径); 已有产出行时不重复写(桥接可能被调用多次)。
     * 某个成品找不到钉住的配方时不写任何行 —— 让重算如实 409, ⛔ 不拿一份没钉住的配方凑数。
     */
    private void persistByStockSettlementOutputs(String factoryId, ProductionPlan plan,
                                                 ProductionSettlement settlement,
                                                 List<FinishedGoodsBatch> finishedGoodsBatches) {
        if (!isWorkflowPlan(plan) || productionSettlementOutputLineRepository == null || bomRecipeRepository == null
                || isBlank(plan.getSelectedBomFamilyId())) {
            return;
        }
        if (!productionSettlementOutputLineRepository
                .lockByFactoryIdAndSettlementId(factoryId, settlement.getId()).isEmpty()) {
            return;
        }
        Map<String, String> recipeIds = Optional.ofNullable(plan.getSelectedBomRecipeIdsByProduct()).orElseGet(Map::of);
        Map<String, Integer> recipeVersions = Optional.ofNullable(plan.getSelectedBomVersionsByProduct()).orElseGet(Map::of);
        Map<String, BigDecimal> tracedRatios = byStockBranchCostRatios(factoryId, plan.getId(), finishedGoodsBatches);
        List<ProductionSettlementOutputLine> lines = new ArrayList<>();
        for (FinishedGoodsBatch fg : finishedGoodsBatches) {
            String productTypeId = trimToNull(fg.getProductTypeId());
            String recipeId = productTypeId == null ? null : trimToNull(recipeIds.get(productTypeId));
            Integer recipeVersion = productTypeId == null ? null : recipeVersions.get(productTypeId);
            BomRecipe recipe = recipeId == null || recipeVersion == null ? null : bomRecipeRepository.findById(recipeId)
                    .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                    .filter(candidate -> productTypeId.equals(candidate.getProductTypeId()))
                    .filter(candidate -> Objects.equals(plan.getSelectedBomFamilyId(), candidate.getBomFamilyId()))
                    .filter(candidate -> Objects.equals(recipeVersion, candidate.getVersion()))
                    .orElse(null);
            if (recipe == null || isBlank(recipe.getTargetTerminalNodeId()) || recipe.getOutputRole() == null) {
                log.warn("BY_STOCK 结单产出行未写: 成品 {} 没有可核验的钉选配方 (planId={}, recipeId={}, version={})",
                        productTypeId, plan.getId(), recipeId, recipeVersion);
                return;
            }
            BigDecimal produced = zeroIfNull(fg.getProducedQuantity());
            ProductionSettlementOutputLine line = ProductionSettlementOutputLine.create();
            line.setFactoryId(factoryId);
            line.setSettlementId(settlement.getId());
            line.setProductionPlanId(plan.getId());
            line.setProductTypeId(productTypeId);
            line.setReportedBatchNumber(fg.getBatchNumber());
            line.setReportedQuantity(produced);
            line.setQuantityUnit(canonicalReceiptUnit(trimToNull(fg.getUnit())));
            line.setBomFamilyId(plan.getSelectedBomFamilyId());
            line.setBomRecipeId(recipe.getId());
            line.setBomRecipeVersion(recipe.getVersion());
            line.setTargetTerminalNodeId(recipe.getTargetTerminalNodeId());
            line.setOutputRole(recipe.getOutputRole());
            line.setCostAllocationRatio(recipe.getCostAllocationRatio() != null
                    || recipe.getOutputRole() == BomRecipe.OutputRole.BY_PRODUCT || tracedRatios == null
                    ? recipe.getCostAllocationRatio() : tracedRatios.get(fg.getId()));
            line.setByproductNrvUnitPrice(recipe.getByproductNrvUnitPrice());
            line.setReceivedQuantity(produced);
            line.setFinishedGoodsBatchId(fg.getId());
            line.setReceiptIdempotencyKey(settlement.getWarehouseReceiptIdempotencyKey());
            lines.add(line);
        }
        productionSettlementOutputLineRepository.saveAll(lines);
    }

    /**
     * 存货生产多成品的分摊比例(百分比, 按 FG 批次 id) = 各成品那条分支已追溯成本的占比
     * (分叉点按重量 + 副产 NRV 先扣, 之后各走各的工序)。
     * 普通结单靠报工页的多产出行凭证 ({@code ReportedOutputCostAllocationService}), 而存货计划的分叉发生在
     * 上游半成品, 成品行不是多产出行 —— 那条路在这里永远拿不到凭证(F006【演示L4】8d77ec57, 2026-10-25)。
     *
     * <p>分支成本优先取成品那道生产批的成本分解 ({@code OrderCostBreakdownService#computeByBatch}, 含上游
     * 半成品投料); 只有拿不全时才退回 FG 小结单位成本。⚠️ 小结单位成本不含半成品投料
     * (白切鸡胸小结 ¥692.46, 分支实为 ¥1835.30), 拿它当比例会把成本从鸡胸挪给鸡腿 —— 所以它只是兜底,
     * 且两种来源不混用。
     *
     * <p>任何一个成品两种来源都拿不到成本就返回 null —— 让重算如实 409, ⛔ 不把缺成本的那个当 0 分走。
     */
    private Map<String, BigDecimal> byStockBranchCostRatios(String factoryId, String planId,
                                                            List<FinishedGoodsBatch> finishedGoodsBatches) {
        if (finishedGoodsBatches == null || finishedGoodsBatches.size() < 2) {
            return null;
        }
        // 同一 SKU 小结过多次 (多个 FG 批) 时分支成本是这几批合起来的, 按批取会各拿一整份 —— 走兜底。
        boolean oneFgPerSku = finishedGoodsBatches.stream().map(fg -> trimToNull(fg.getProductTypeId()))
                .distinct().count() == finishedGoodsBatches.size();
        if (oneFgPerSku && orderCostBreakdownService != null && productionBatchRepository != null) {
            List<ProductionBatch> batches = Optional.ofNullable(
                    productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId)).orElseGet(List::of);
            Map<String, BigDecimal> costs = new LinkedHashMap<>();
            List<com.cretas.aims.entity.processentry.ProcessSheetRow> planRows = processSheetRowRepository == null
                    ? List.of() : Optional.ofNullable(processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId))
                    .orElseGet(List::of);
            Map<String, BigDecimal> jointCostByGroup = new HashMap<>();
            for (FinishedGoodsBatch fg : finishedGoodsBatches) {
                List<ProductionBatch> branch = batches.stream()
                        .filter(b -> b.getStatus() != ProductionBatchStatus.CANCELLED)
                        .filter(b -> Objects.equals(trimToNull(b.getProductTypeId()), trimToNull(fg.getProductTypeId())))
                        .toList();
                if (branch.size() != 1 || isBlank(branch.get(0).getBatchNumber())) {
                    costs = null;
                    break;
                }
                BigDecimal branchCost;
                try {
                    branchCost = byStockBranchCost(factoryId, planId, branch.get(0), planRows, jointCostByGroup);
                } catch (RuntimeException e) {
                    log.warn("BY_STOCK 分支成本读不出, 退回小结单位成本 (planId={}, batch={}): {}",
                            planId, branch.get(0).getBatchNumber(), e.getMessage());
                    costs = null;
                    break;
                }
                costs.put(fg.getId(), branchCost);
            }
            Map<String, BigDecimal> ratios = costs == null ? null : ratiosFromCosts(costs);
            if (ratios != null) {
                return ratios;
            }
        }
        return byStockTracedCostRatios(finishedGoodsBatches);
    }

    /**
     * 一个成品的分支成本。成品批若是某道「多产出且有 ≥2 个正式产出批」工序的成员 (L5 组装 4→2:
     * 单层/双层两个成品批), 投入只记在第一个成员批上 —— 按批单算会让一个吃掉全部、另一个算不出。
     * 这时 = 整组合起来的联合成本 × 本成员报工落库的 {@code costAllocationRatio} (与报工页那次分摊同一组比例)。
     * 其余成品照旧取自己那道批的成本分解。
     *
     * @return 成本未知 → null (由调用方整体退回兜底口径)
     */
    private BigDecimal byStockBranchCost(String factoryId, String planId, ProductionBatch branchBatch,
                                         List<com.cretas.aims.entity.processentry.ProcessSheetRow> planRows,
                                         Map<String, BigDecimal> jointCostByGroup) {
        MultiOutputMembership membership = null;
        for (com.cretas.aims.entity.processentry.ProcessSheetRow row : planRows) {
            if (Objects.equals(branchBatch.getId(), row.getBatchId())) {
                membership = multiOutputMembership(row);
                if (membership != null) {
                    break;
                }
            }
        }
        if (membership != null) {
            String groupKey = membership.groupKey();
            List<Long> memberBatchIds = planRows.stream()
                    .filter(row -> row.getBatchId() != null)
                    .filter(row -> {
                        MultiOutputMembership other = multiOutputMembership(row);
                        return other != null && other.groupKey().equals(groupKey);
                    })
                    .map(com.cretas.aims.entity.processentry.ProcessSheetRow::getBatchId)
                    .distinct().toList();
            if (memberBatchIds.size() >= 2) {
                BigDecimal joint = jointCostByGroup.computeIfAbsent(groupKey, key -> {
                    com.cretas.aims.dto.yield.OrderCostBreakdownDTO group =
                            orderCostBreakdownService.computeByBatchIds(factoryId, planId + "|" + key, memberBatchIds, false);
                    return group == null || !group.isCostComplete() || group.getTotalCost() == null
                            ? BigDecimal.ZERO : group.getTotalCost();
                });
                return joint.signum() <= 0 ? null
                        : joint.multiply(membership.ratioPercent()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            }
        }
        com.cretas.aims.dto.yield.OrderCostBreakdownDTO breakdown =
                orderCostBreakdownService.computeByBatch(factoryId, branchBatch.getBatchNumber(), false);
        return breakdown == null || !breakdown.isCostComplete() ? null : breakdown.getTotalCost();
    }

    private record MultiOutputMembership(String groupKey, BigDecimal ratioPercent) {}

    /** 已提交的多产出成员行 → (组键, 分摊比例%); 不是 / 比例不在 (0,100] → null。 */
    static MultiOutputMembership multiOutputMembership(com.cretas.aims.entity.processentry.ProcessSheetRow row) {
        if (row == null || row.getRowPayload() == null
                || !com.cretas.aims.entity.processentry.ProcessSheetRow.SUBMISSION_SUBMITTED.equals(row.getSubmissionStatus())) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode p = PROCESS_SHEET_ROW_MAPPER.readTree(row.getRowPayload());
            if (p == null || !p.path("multiOutputMember").asBoolean(false)) {
                return null;
            }
            String base = trimStatic(p.path("multiOutputBaseRowId").asText(null));
            com.fasterxml.jackson.databind.JsonNode ratio = p.get("costAllocationRatio");
            if (base == null || ratio == null || ratio.isNull()) {
                return null;
            }
            BigDecimal percent = new BigDecimal(ratio.asText());
            if (percent.signum() <= 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
                return null;
            }
            return new MultiOutputMembership(row.getProcessCode() + "|" + row.getProcessOrder() + "|" + base, percent);
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimStatic(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 兜底口径: FG 小结单位成本 × 产量 的占比 (见 {@link #byStockBranchCostRatios} 为什么只是兜底)。 */
    static Map<String, BigDecimal> byStockTracedCostRatios(List<FinishedGoodsBatch> finishedGoodsBatches) {
        if (finishedGoodsBatches == null || finishedGoodsBatches.size() < 2) {
            return null;
        }
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        for (FinishedGoodsBatch fg : finishedGoodsBatches) {
            if (fg.getId() == null || fg.getUnitCost() == null || fg.getProducedQuantity() == null) {
                return null;
            }
            costs.put(fg.getId(), fg.getUnitCost().multiply(fg.getProducedQuantity()));
        }
        return ratiosFromCosts(costs);
    }

    /** 成本占比(百分比, 6 位); 少于 2 个 / 任一缺失或 ≤0 → null。 */
    static Map<String, BigDecimal> ratiosFromCosts(Map<String, BigDecimal> costs) {
        if (costs == null || costs.size() < 2 || costs.containsKey(null)
                || costs.values().stream().anyMatch(cost -> cost == null || cost.signum() <= 0)) {
            return null;
        }
        BigDecimal total = costs.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> ratios = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : costs.entrySet()) {
            ratios.put(entry.getKey(), entry.getValue().multiply(BigDecimal.valueOf(100))
                    .divide(total, 6, RoundingMode.HALF_UP));
        }
        return ratios;
    }

    /**
     * 存货多成品的产出行比例是派生值 (不是谁填的口径), 每次重算按当前分支成本重取并覆盖:
     * 上游成本被修正 (补录 / #1153 回溯口径) 之后, 冻结在旧值上的比例会把成本分错。
     * 取不出就原样留着 (空比例照旧如实 409)。
     */
    private List<ProductionSettlementOutputLine> refreshByStockLineRatios(
            String factoryId, String planId, List<ProductionSettlementOutputLine> lines) {
        List<ProductionSettlementOutputLine> primary = lines.stream()
                .filter(line -> line.getOutputRole() != BomRecipe.OutputRole.BY_PRODUCT).toList();
        if (lines.size() < 2 || primary.size() < 2) {
            return lines;
        }
        Map<String, BigDecimal> ratios = byStockBranchCostRatios(factoryId, planId,
                finishedGoodsBatchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId));
        if (ratios == null || primary.stream().anyMatch(line -> ratios.get(line.getFinishedGoodsBatchId()) == null)) {
            return lines;
        }
        primary.forEach(line -> line.setCostAllocationRatio(ratios.get(line.getFinishedGoodsBatchId())));
        productionSettlementOutputLineRepository.saveAll(lines);
        return lines;
    }

    private BusinessException incompleteByStockSettlementRows() {
        return new BusinessException(409, "存货生产报工尚未全部正式提交并小结, 不能补建仓库确认")
                .withCode("BY_STOCK_SETTLEMENT_ROWS_INCOMPLETE")
                .withHint("请核对逐道报工和小结状态；系统不会重放库存动作")
                .withHintTarget("仓库实收");
    }

    private void validateInterimFinishedGoods(ProductionPlan plan, FinishedGoodsBatch finishedGoods,
                                              BigDecimal summarizedFinishedQuantity) {
        validateInterimFinishedGoodsIdentity(plan, finishedGoods);
        BigDecimal produced = zeroIfNull(finishedGoods.getProducedQuantity());
        if (produced.signum() <= 0 || produced.compareTo(summarizedFinishedQuantity) != 0) {
            throw invalidInterimFinishedGoods("小结摘要数量与成品批次数量不一致");
        }
    }

    private List<String> byStockTargetIds(ProductionPlan plan) {
        return Optional.ofNullable(plan.getTargetFinishedGoodIds()).orElseGet(ArrayList::new)
                .stream().map(this::trimToNull).filter(Objects::nonNull).distinct().toList();
    }

    private void validateInterimFinishedGoodsIdentity(ProductionPlan plan, FinishedGoodsBatch finishedGoods) {
        if (!plan.getId().equals(finishedGoods.getProductionPlanId())) {
            throw invalidInterimFinishedGoods("小结成品批次不属于当前生产计划");
        }
        List<String> targetIds = byStockTargetIds(plan);
        // 多成品(联产/原料分流)计划: 每个小结成品批次必须属于计划钉住的终端集合之一。
        // 从前一律拒绝「多成品计划不能合并为一条仓库实收」⇒ 多成品存货计划永远结不了单
        // (F006【演示L4】8d77ec57 / 【演示L5】f9ba5b6f, 2026-10-24)。逐 SKU 的真值写进结单产出行。
        String actualProductTypeId = trimToNull(finishedGoods.getProductTypeId());
        boolean skuMatches = targetIds.size() > 1
                ? targetIds.contains(actualProductTypeId)
                : Objects.equals(targetIds.isEmpty() ? trimToNull(plan.getProductTypeId()) : targetIds.get(0),
                        actualProductTypeId);
        if (!skuMatches) {
            throw invalidInterimFinishedGoods("小结成品 SKU 与计划终端 SKU 不一致");
        }
        if (isBlank(finishedGoods.getUnit())) {
            throw invalidInterimFinishedGoods("小结成品批次缺少计量单位");
        }
        String status = trimToNull(finishedGoods.getStatus());
        if (status == null || !Set.of(
                FinishedGoodsBatch.Status.AVAILABLE,
                FinishedGoodsBatch.Status.DEPLETED,
                FinishedGoodsBatch.Status.EXPIRED,
                FinishedGoodsBatch.Status.FROZEN,
                FinishedGoodsBatch.Status.DEFECTIVE,
                // 缺口 11/13: 待检只管出货, 不管结单记账 —— 它是真实入库的成品, 只是还没放行。
                FinishedGoodsBatch.Status.PENDING_INSPECTION).contains(status)) {
            throw invalidInterimFinishedGoods("小结成品批次不是有效的已入库真值: " + status);
        }
        InventoryOwnership expectedOwnership = Optional.ofNullable(plan.getOutputOwnership())
                .orElse(InventoryOwnership.COMPANY_OWNED);
        InventoryOwnership batchOwnership = Optional.ofNullable(finishedGoods.getOwnership())
                .orElse(InventoryOwnership.COMPANY_OWNED);
        if (!Objects.equals(expectedOwnership, batchOwnership)) {
            throw invalidInterimFinishedGoods("小结成品批次库存归属与生产计划不一致");
        }
        if (expectedOwnership == InventoryOwnership.CUSTOMER_OWNED
                && !Objects.equals(trimToNull(plan.getCustomerId()), trimToNull(finishedGoods.getOwnerCustomerId()))) {
            throw invalidInterimFinishedGoods("小结成品批次归属客户与生产计划不一致");
        }
    }

    /** 兼容历史未完成桥接：单批次 BY_STOCK 仓库确认只复用小结已创建的 FG，禁止再建第二批。 */
    private FinishedGoodsBatch requireInterimFinishedGoodsForReceipt(ProductionPlan plan,
                                                                     ProductionSettlement settlement,
                                                                     BigDecimal reported,
                                                                     BigDecimal received,
                                                                     String unit) {
        if (received.compareTo(reported) != 0) {
            throw new BusinessException(409, "存货生产小结已按报产数量生成成品库存, 仓库实收差异不能直接覆盖")
                    .withCode("BY_STOCK_RECEIPT_VARIANCE_REQUIRES_RECONCILIATION")
                    .withHint("请先由生产侧撤销/修正小结后再确认；系统未改动现有成品库存")
                    .withHintTarget("仓库实收");
        }
        if (isBlank(settlement.getFinishedGoodsBatchId())) {
            throw invalidInterimFinishedGoods("桥接结单没有关联小结成品批次");
        }
        FinishedGoodsBatch batch = finishedGoodsBatchRepository.findById(settlement.getFinishedGoodsBatchId())
                .filter(candidate -> settlement.getFactoryId().equals(candidate.getFactoryId()))
                .orElseThrow(() -> invalidInterimFinishedGoods("桥接关联的成品批次不存在"));
        validateInterimFinishedGoods(plan, batch, reported);
        if (!Objects.equals(canonicalReceiptUnit(unit), canonicalReceiptUnit(batch.getUnit()))) {
            throw new BusinessException(409, "仓库实收单位与小结成品批次单位不一致")
                    .withCode("BY_STOCK_RECEIPT_UNIT_MISMATCH")
                    .withHint("小结批次单位: " + batch.getUnit() + ", 本次实收单位: " + unit)
                    .withHintTarget("仓库实收");
        }
        return batch;
    }

    /**
     * 结单/实收侧的单位归一 —— <b>走系统权威别名表</b>，不再自己维护一份。
     *
     * <p>原来是一张只有 5 组 (盒/箱/片/公斤/克) 的私有 switch，两个 blocking 门
     * ({@code WORKFLOW_OUTPUT_UNIT_MISMATCH} / {@code PRODUCTION_SETTLEMENT_OUTPUT_UNIT_MISMATCH})
     * 直接拿它的结果做 equals。后果和 2026-07-31 客户在报工那侧撞到的是同一个:</p>
     * <ul>
     *   <li><b>误拦</b> —— 表里没有的单位原样返回，于是「袋」≠「bag」，而权威表里
     *       {@code alias("bag","bag","袋")} 本就是同一个单位。报工那道 (#2077) 修好后，
     *       客户下一步结单会撞上这一道。</li>
     *   <li><b>还漏了同义中文</b> —— 权威表里 件/个/只 都是 {@code pcs}，这里三个各自原样返回，
     *       一个端口写「个」另一个写「件」同样会被判成不一致。</li>
     * </ul>
     *
     * <p>⚠️ 已知边界: 这里用的是 <b>built-in</b> 表 (24 组系统单位)，不含<b>租户自定义别名</b>
     * —— 与改动前一致，不是新增的缺口。要覆盖租户别名得把 factoryId 串进这 13 个调用点，
     * 那是另一件事；真要做请一并参考 {@code ProcessSheetServiceImpl#configuredUnitsEquivalent}
     * 的两段式写法 (built-in 优先，再落 {@code UnitContractService#areEquivalent})。</p>
     */
    private static String canonicalReceiptUnit(String unit) {
        return com.cretas.aims.service.unit.impl.UnitContractServiceImpl.crossLanguageCode(unit);
    }

    private BusinessException invalidInterimFinishedGoods(String detail) {
        return new BusinessException(409, "存货生产小结成品真值无法唯一核验: " + detail)
                .withCode("BY_STOCK_INTERIM_FINISHED_GOODS_INVALID")
                .withHint("请联系管理员核对小结会话与成品批次；系统未改动任何库存")
                .withHintTarget("仓库实收");
    }

    /**
     * ⚠️ 「没有 production_settlements 行」<b>不总是错误</b>: 存货生产(SAFETY_STOCK)走「小结」,
     * 结单真值在 {@code production_interim_settlements}, 这张表里本来就没有行。
     *
     * <p>🔴 2026-08-19 客户实际反馈: 生产计划列表给每个 COMPLETED 计划批量拉结单状态
     * ({@code GET /{planId}/settlement}), 列表里一条<b>过往</b>的存货生产计划命中这里 → 404
     * → 前端拦截器弹全局红条。用户以为是自己刚做的操作失败了, 换页面回来还弹,
     * 而筛「已结单」就不弹 —— 因为报的是<b>另一条</b>计划。
     *
     * <p>本次只给它加 errorCode(便于日志/前端识别), <b>不改状态码与行为</b> ——
     * 对「确认入库」那条<b>写</b>路径而言, 拦住是对的。
     * ⏭️ 更深的一层(查状态的<b>读</b>接口不该把合法空态抛成 404)另案, 见交接件。
     */
    private BusinessException missingProductionSettlement() {
        return new BusinessException(404, "该生产计划尚未结单, 不能确认入库")
                .withCode("PRODUCTION_SETTLEMENT_NOT_FOUND")
                .withHint("请先由生产文员录入实际产量、实际领用和人效并提交结单")
                .withHintTarget("仓库实收");
    }

    private void ensurePostingDependencies() {
        if (finishedGoodsBatchRepository == null || productionTransitLedgerRepository == null || warehouseResolver == null) {
            throw new BusinessException(500, "生产入库过账服务未初始化")
                    .withHint("请联系管理员检查成品库存、仓库解析和中转挂账组件")
                    .withHintTarget("仓库实收");
        }
    }

    private BigDecimal receiptTolerance(String unit) {
        String normalized = unit != null ? unit.trim().toLowerCase(Locale.ROOT) : "";
        if ("kg".equals(normalized) || "公斤".equals(normalized) || "千克".equals(normalized)) {
            return new BigDecimal("10.00");
        }
        if ("g".equals(normalized) || "克".equals(normalized)) {
            return new BigDecimal("10000.00");
        }
        return BigDecimal.ZERO;
    }

    private FinishedGoodsBatch createFinishedGoodsForOutputLine(
            ProductionPlan plan,
            ProductionSettlement settlement,
            ProductionSettlementOutputLine output,
            BigDecimal receiptDelta,
            Long receivedBy) {
        Optional<FinishedGoodsBatch> existing = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumber(settlement.getFactoryId(),
                        output.getReportedBatchNumber());
        if (existing.isPresent()) {
            FinishedGoodsBatch batch = existing.get();
            if (!Objects.equals(plan.getId(), batch.getProductionPlanId())
                    || !Objects.equals(output.getProductTypeId(), batch.getProductTypeId())
                    || !Objects.equals(canonicalReceiptUnit(batch.getUnit()),
                            canonicalReceiptUnit(output.getQuantityUnit()))) {
                throw new BusinessException(409,
                        "Reported output batch number already belongs to another production output")
                        .withCode("FINISHED_GOODS_BATCH_CONFLICT");
            }
            batch.setProducedQuantity(zeroIfNull(batch.getProducedQuantity()).add(receiptDelta));
            // 质检放行: 同批号追加入库 = 一笔新的产出, 需检产品重新待检 (只动 AVAILABLE)。
            batch.setStatus(finishedGoodsOutputStatus(settlement.getFactoryId(), batch.getProductTypeId(),
                    batch.getBatchNumber(), plan.getId(), batch.getStatus()));
            return finishedGoodsBatchRepository.save(batch);
        }

        ProductType productType = productTypeRepository
                .findByIdAndFactoryId(output.getProductTypeId(), settlement.getFactoryId())
                .orElseThrow(() -> new BusinessException(409,
                        "Workflow output SKU no longer exists: " + output.getProductTypeId())
                        .withCode("WORKFLOW_OUTPUT_SKU_NOT_FOUND"));
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setFactoryId(settlement.getFactoryId());
        batch.setBatchNumber(output.getReportedBatchNumber());
        batch.setProductTypeId(output.getProductTypeId());
        batch.setProductName(productType.getName());
        batch.setProducedQuantity(receiptDelta);
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setUnit(output.getQuantityUnit());
        batch.setUnitPrice(productType.getUnitPrice());
        batch.setUnitCost(output.getUnitCost());
        batch.setProductionDate(LocalDate.now());
        int shelfLifeDays = productType.getShelfLifeDays() != null
                ? productType.getShelfLifeDays() : 180;
        batch.setExpireDate(LocalDate.now().plusDays(shelfLifeDays));
        // 🔴 2026-08-25 prod 实测: 这里原来把一句**来源描述**写进了 storage_location。
        //    F006 的 18 条成品批次里 16 条因此躺着 "Workflow terminal output warehouse receipt" 这类文字, 与本厂
        //    WAREHOUSE_LAYOUT 里配置的位置编码匹配数为 0 ⇒ 仓储空间的位置筛选、
        //    位置卡片「N 批」、countAt() 在生产数据上恒为 0 —— 核心功能被这一行掏空。
        //    ⛔ 来源本来就记在 productionPlanId 等字段上, 不需要借位置字段再存一份(形态 D)。
        //    ⇒ 留 null: 「未分配位置」是**真的**, 而一个假位置会让下游全部读错。
        //       仓管在「仓储空间 → 现场盘点 → 调整位置」里填真实位置, 后端已校验它必须
        //       存在于本厂配置(见 WarehouseLayoutController#moveFinishedGoods)。
        // (不再写入 storage_location)
        batch.setProductionPlanId(plan.getId());
        batch.setOwnership(plan.getOutputOwnership());
        batch.setOwnerCustomerId(plan.getOutputOwnership() == InventoryOwnership.CUSTOMER_OWNED
                ? plan.getCustomerId() : null);
        batch.setSourceSalesOrderId(plan.getSourceOrderId());
        batch.setSourceSalesOrderItemId(plan.getSourceOrderItemId());
        batch.setWarehouseId(warehouseResolver.resolveFinishedGoodsId(settlement.getFactoryId()));
        // 质检放行 (缺口 11/13): 需检产品 / 有未处理 CCP 偏离 ⇒ 待检, 其余照旧 AVAILABLE。
        batch.setStatus(finishedGoodsOutputStatus(settlement.getFactoryId(), output.getProductTypeId(),
                output.getReportedBatchNumber(), plan.getId(), FinishedGoodsBatch.Status.AVAILABLE));
        batch.setCreatedBy(receivedBy != null ? receivedBy : 0L);
        batch.setRemark("Workflow output receipt: " + settlement.getPlanNumber());
        FinishedGoodsBatch saved = finishedGoodsBatchRepository.save(batch);
        recordProductionToFinishedLineage(saved, receiptDelta, receivedBy, "WORKFLOW_OUTPUT_RECEIPT",
                output.getReportedBatchNumber());
        return saved;
    }

    /**
     * 结单/实收两条成品入库路径共用的 PRODUCTION_TO_FINISHED 写边点（本类唯一一处）。
     *
     * <p>⛔ 不要在两个 {@code createFinishedGoods*} 方法里各写一份 —— 那正是形态 D
     * 「同一件事两份实现」，其中一份会先漂。
     */
    private void recordProductionToFinishedLineage(FinishedGoodsBatch saved, BigDecimal quantity,
                                                   Long operatorId, String origin,
                                                   String producingBatchNumberHint) {
        if (lineageEdgeRecorder == null || saved == null) {
            return;
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("origin", origin);
        meta.put("finishedBatchNumber", saved.getBatchNumber());
        // 🔴 2026-10-24 prod 实测: 逐行入库的成品批号 = 报工生产批号 (CLK-B-… / PB-PLAN-…), 而同计划有
        //    3 个同产品正式批次 ⇒ 只按计划对齐恒 AMBIGUOUS ⇒ 3 个成品批没有 PRODUCTION_TO_FINISHED 边。
        //    报工批号是精确键 (同厂同计划才采纳, 见 FinishedBatchProductionAligner)。
        lineageEdgeRecorder.recordProductionToFinishedByPlan(
                saved.getFactoryId(), saved.getProductionPlanId(), saved.getProductTypeId(),
                producingBatchNumberHint, saved.getId(), quantity, saved.getUnit(), operatorId,
                "finishedBatch:" + saved.getId(), meta);
    }

    private FinishedGoodsBatch createFinishedGoodsFromReceipt(ProductionPlan plan,
                                                              ProductionSettlement settlement,
                                                              BigDecimal received,
                                                              String unit,
                                                              Long receivedBy) {
        List<String> targetFinishedGoodIds = Optional.ofNullable(plan.getTargetFinishedGoodIds())
                .orElseGet(ArrayList::new).stream()
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (targetFinishedGoodIds.size() > 1) {
            throw new BusinessException(409, "多成品计划必须按终端 SKU 逐行确认入库，不能把原料 owner 当成品入库")
                    .withCode("MULTI_OUTPUT_RECEIPT_REQUIRES_LINES")
                    .withHint("请按逐道报工的终端 SKU、批次和单位分别确认实收")
                    .withHintTarget("仓库实收");
        }
        String finishedProductTypeId = targetFinishedGoodIds.isEmpty()
                ? trimToNull(plan.getProductTypeId())
                : targetFinishedGoodIds.get(0);
        if (isBlank(finishedProductTypeId)) {
            throw new BusinessException(409, "生产计划缺少产品类型, 不能生成成品库存")
                    .withHint("请先修正生产计划产品类型")
                    .withHintTarget("仓库实收");
        }
        String batchNumber = finishedGoodsBatchNumber(settlement);
        Optional<FinishedGoodsBatch> existing =
                finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(settlement.getFactoryId(), batchNumber);
        if (existing.isPresent()) {
            return existing.get();
        }

        ProductType productType = finishedProductTypeId != null
                ? productTypeRepository.findByIdAndFactoryId(
                        finishedProductTypeId, settlement.getFactoryId()).orElse(null)
                : null;

        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setFactoryId(settlement.getFactoryId());
        batch.setBatchNumber(batchNumber);
        batch.setProductTypeId(finishedProductTypeId);
        batch.setProductName(productType != null ? productType.getName() : null);
        batch.setProducedQuantity(received);
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setUnit(unit);
        batch.setUnitPrice(productType != null ? productType.getUnitPrice() : null);
        // 🔴 成本传导 (2026-07-04): 结单族成品单位成本 = 该计划权威生产成本 / 入库量 (诚实 null, 不伪造 ¥0)。
        //   与 SAFETY_STOCK 小结成本同基准 (原料+人工+调料+包装), 让期末 COGS 结转能纳入结单族销售。
        batch.setUnitCost(resolveReceiptUnitCost(settlement.getFactoryId(), plan.getId(), received));
        batch.setProductionDate(LocalDate.now());
        int shelfLifeDays = productType != null && productType.getShelfLifeDays() != null
                ? productType.getShelfLifeDays()
                : 180;
        batch.setExpireDate(LocalDate.now().plusDays(shelfLifeDays));
        // 🔴 2026-08-25: 同上 —— 「仓库确认入库」是**一句来源描述**, 不是储存位置。
        //    把它写进 storage_location 会让仓储空间的位置筛选与「某位置有几批」全部读错。
        //    ⇒ 留 null(未分配), 由仓管在「调整位置」里填真实位置(后端已校验其存在性)。
        // (不再写入 storage_location)
        batch.setProductionPlanId(plan.getId());
        batch.setOwnership(plan.getOutputOwnership());
        batch.setOwnerCustomerId(plan.getOutputOwnership() == InventoryOwnership.CUSTOMER_OWNED
                ? plan.getCustomerId() : null);
        batch.setSourceSalesOrderId(plan.getSourceOrderId());
        batch.setSourceSalesOrderItemId(plan.getSourceOrderItemId());
        batch.setWarehouseId(warehouseResolver.resolveFinishedGoodsId(settlement.getFactoryId()));
        // 质检放行 (缺口 11/13): 需检产品 / 有未处理 CCP 偏离 ⇒ 待检, 其余照旧 AVAILABLE。
        batch.setStatus(finishedGoodsOutputStatus(settlement.getFactoryId(), finishedProductTypeId,
                batchNumber, plan.getId(), FinishedGoodsBatch.Status.AVAILABLE));
        batch.setCreatedBy(receivedBy != null ? receivedBy : 0L);
        batch.setRemark("生产结单仓库确认入库: " + settlement.getPlanNumber());
        FinishedGoodsBatch saved = finishedGoodsBatchRepository.save(batch);
        recordProductionToFinishedLineage(saved, received, receivedBy, "SETTLEMENT_WAREHOUSE_RECEIPT", null);
        return saved;
    }

    /**
     * 🔴 成本传导 — 结单族成品入库单位成本 (诚实 null, 禁止降级).
     *
     * <p>unitCost = 该生产计划的权威生产成本 (原料+人工+调料+包装, 由 {@link OrderCostBreakdownService}
     * 沿 MaterialConsumption 边回溯归集) / 入库量 (scale-4, HALF_UP)。与 SAFETY_STOCK 小结的成本传导
     * 同一基准, 也与 出厂核算/成本汇总页字节一致。
     *
     * <p><b>诚实 null (禁止伪造 ¥0)</b>: 以下任一 → 返 null (成品成本未知, 不当 0 摊入 COGS):
     * <ul>
     *   <li>{@code orderCostBreakdownService} 未注入 (单测反射场景);</li>
     *   <li>该计划无生产批次 (hasData=false) — 无成本可归集;</li>
     *   <li>归集总成本 null 或 ≤0 — 全部投入物料/人工均未定价 (upstream priceless);</li>
     *   <li>入库量 ≤0 — 无分母。</li>
     * </ul>
     * 常规场景 (物料已定价) → 返回真实传导成本。null 由 期末 COGS 结转 honest-null 排除 + 记 WARN 暴露缺口,
     * 不静默造 0。
     */
    private BigDecimal resolveReceiptUnitCost(String factoryId, String planId, BigDecimal received) {
        if (orderCostBreakdownService == null || planId == null
                || received == null || received.signum() <= 0) {
            return null;
        }
        try {
            com.cretas.aims.dto.yield.OrderCostBreakdownDTO cb =
                    orderCostBreakdownService.computeByPlan(factoryId, planId, false);
            if (cb == null || !cb.isHasData()
                    || cb.getTotalCost() == null || cb.getTotalCost().signum() <= 0) {
                return null;   // 诚实: 无成本基准 (无批次 / 全未定价) → 未知, 不伪造 ¥0
            }
            return cb.getTotalCost().divide(received, 4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("结单族成品成本传导失败 (factory={}, plan={}): {} — unitCost 置 null (诚实, 不伪造)",
                    factoryId, planId, e.getMessage());
            return null;
        }
    }

    @Override
    @Transactional
    public com.cretas.aims.dto.production.ProductionCostRecalculationResponse recalculateSettledPlanCosts(
            String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);
        if (productionCostConsistency != null) productionCostConsistency.assertChangeAllowed(factoryId, planId);
        if (productionSettlementRepository == null || productionSettlementOutputLineRepository == null
                || orderCostBreakdownService == null) {
            throw new BusinessException(500, "结单成本重算依赖不可用, 本次未执行");
        }
        ProductionSettlement settlement = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdForUpdate(factoryId, planId)
                .orElseThrow(() -> new BusinessException(409, "该计划尚未结单, 不能重算结单成本"));
        if (!"POSTED".equals(settlement.getPostingStatus())) {
            throw new BusinessException(409, "结单尚未完成仓库入库, 不能重算产出成本")
                    .withCode("SETTLEMENT_NOT_POSTED");
        }
        List<ProductionSettlementOutputLine> lines = productionSettlementOutputLineRepository
                .lockByFactoryIdAndSettlementId(factoryId, settlement.getId());
        if (lines.isEmpty() && plan.getSourceType() == PlanSourceType.SAFETY_STOCK) {
            // 本修复之前桥接的存货结单只有抬头没有产出行 —— 用小结已建好的 FG 补齐一次(与新桥接同一口径),
            // 否则这些历史计划永远录不进设备/其他费用。补不出来(无钉选配方)就照旧如实 409。
            persistByStockSettlementOutputs(factoryId, plan, settlement,
                    finishedGoodsBatchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId));
            lines = productionSettlementOutputLineRepository.lockByFactoryIdAndSettlementId(factoryId, settlement.getId());
        }
        if (plan.getSourceType() == PlanSourceType.SAFETY_STOCK && finishedGoodsBatchRepository != null) {
            lines = refreshByStockLineRatios(factoryId, planId, lines);
        }
        if (lines.isEmpty() || lines.stream().anyMatch(line -> line.getReceivedQuantity() == null
                || line.getReceivedQuantity().signum() <= 0 || line.getFinishedGoodsBatchId() == null)) {
            throw new BusinessException(409, "结单产出尚未全部入库, 不能重算产出成本")
                    .withCode("SETTLEMENT_OUTPUT_NOT_RECEIVED");
        }

        com.cretas.aims.dto.yield.OrderCostBreakdownDTO breakdown =
                orderCostBreakdownService.computeByPlan(factoryId, planId, false);
        BigDecimal totalCost = usableTotalCost(breakdown);
        if (totalCost == null) {
            throw new BusinessException(409, describeCostUnavailableReason(breakdown))
                    .withCode("PLAN_COST_INCOMPLETE");
        }

        List<ProductionBatch> productionBatches = productionBatchRepository
                .findByFactoryIdAndProductionPlanId(factoryId, planId);
        List<SemiFinishedInventory> reworkRows = new ArrayList<>();
        if (semiFinishedInventoryRepository != null) {
            for (ProductionBatch batch : productionBatches) {
                if (batch.getId() == null) continue;
                semiFinishedInventoryRepository.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, batch.getId())
                        .stream().filter(w -> Boolean.TRUE.equals(w.getIsRework()))
                        .forEach(reworkRows::add);
            }
        }

        BigDecimal sharedUnitCost;
        if (!reworkRows.isEmpty()) {
            long primaryProducts = lines.stream()
                    .filter(line -> line.getOutputRole() != BomRecipe.OutputRole.BY_PRODUCT)
                    .map(ProductionSettlementOutputLine::getProductTypeId).distinct().count();
            boolean hasByproduct = lines.stream()
                    .anyMatch(line -> line.getOutputRole() == BomRecipe.OutputRole.BY_PRODUCT);
            if (primaryProducts != 1 || hasByproduct) {
                throw new BusinessException(409, "多主产/副产计划含回料时需先配置独立成本分摊, 本次未改任何成本")
                        .withCode("REWORK_MULTI_OUTPUT_COST_POLICY_REQUIRED");
            }
            BigDecimal totalProduced = lines.stream().map(ProductionSettlementOutputLine::getReceivedQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .add(reworkRows.stream().map(SemiFinishedInventory::getProducedQuantity)
                            .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
            if (totalProduced.signum() <= 0) {
                throw new BusinessException(409, "良品与回料总产出为 0, 无法计算单位成本");
            }
            // 良品与回料对外都按 4 位单位成本；先统一量化再同时写，避免一边 6 位、一边 4 位
            // 导致“来源相同但逐格比较不相等”。
            sharedUnitCost = totalCost.divide(totalProduced, 4, RoundingMode.HALF_UP);
            for (ProductionSettlementOutputLine line : lines) {
                line.setUnitCost(sharedUnitCost);
                line.setAllocatedCost(line.getReceivedQuantity().multiply(sharedUnitCost)
                        .setScale(6, RoundingMode.HALF_UP));
            }
            for (SemiFinishedInventory wip : reworkRows) {
                wip.setUnitCost(sharedUnitCost);
                wip.setAccumulatedCost(wip.getProducedQuantity().multiply(sharedUnitCost)
                        .setScale(2, RoundingMode.HALF_UP));
            }
            semiFinishedInventoryRepository.saveAll(reworkRows);
        } else {
            allocateWorkflowOutputCostsFromTotal(totalCost, lines);
            sharedUnitCost = lines.size() == 1 ? lines.getFirst().getUnitCost() : null;
        }
        productionSettlementOutputLineRepository.saveAll(lines);

        int finishedGoodsCount = 0;
        for (ProductionSettlementOutputLine line : lines) {
            com.cretas.aims.entity.inventory.FinishedGoodsBatch fg = finishedGoodsBatchRepository
                    .findByIdAndFactoryIdForUpdate(line.getFinishedGoodsBatchId(), factoryId)
                    .orElseThrow(() -> new BusinessException(409, "结单产出对应成品批次不存在: "
                            + line.getFinishedGoodsBatchId()));
            if (!planId.equals(fg.getProductionPlanId())) {
                throw new BusinessException(409, "成品批次不属于当前计划, 本次成本重算已回滚");
            }
            BigDecimal previousUnitCost = fg.getUnitCost();
            fg.setUnitCost(line.getUnitCost());
            finishedGoodsBatchRepository.save(fg);
            if (returnedCostSynchronizationService != null) returnedCostSynchronizationService.synchronize(
                    factoryId, fg, previousUnitCost, com.cretas.aims.utils.SecurityUtils.currentUserId());
            finishedGoodsCount++;
        }

        // Multi-output report batches retain their independently allocated source
        // costs. The plan pool above updates settlement/FG values; copying the
        // entire pool into every report batch would multiply production cost.
        boolean multiOutputPool = lines.stream().map(ProductionSettlementOutputLine::getProductTypeId)
                .distinct().limit(2).count() > 1;
        for (ProductionBatch batch : productionBatches) {
            if (multiOutputPool) continue;
            batch.setMaterialCost(zeroIfNull(breakdown.getRawMaterialCost())
                    .add(breakdown.getSeasoningCost() == null ? BigDecimal.ZERO : breakdown.getSeasoningCost())
                    .add(breakdown.getPackagingCost() == null ? BigDecimal.ZERO : breakdown.getPackagingCost()));
            batch.setLaborCost(breakdown.getLaborCost());
            batch.setEquipmentCost(breakdown.getEquipmentCost());
            batch.setOtherCost(breakdown.getOtherCost());
            batch.calculateMetrics();
        }
        if (!multiOutputPool) productionBatchRepository.saveAll(productionBatches);

        if (productionCostConsistency != null) productionCostConsistency.invalidateCostIndicators(factoryId);

        // 🔴 客供主料成本口径 (a)（2026-09-04）: 显式说出「这次重算含客供料 N 项按加工口径记 0」——
        // 见类上方 describeCustomerSuppliedNote javadoc, ⛔ 不能只报一个总成本让人猜为什么比预期低。
        String customerSuppliedNote = describeCustomerSuppliedNote(breakdown);
        // 🔴 辅料先估 (2026-09-06): 同理 —— 这个 totalCost 里含估算值, ⛔ 不说就等于没说。
        String estimatedAuxNote = describeEstimatedAuxNote(breakdown);
        log.info("结单成本重算完成 factory={} plan={} settlement={} total={} unit={} outputs={} fg={} rework={}"
                        + " customerSuppliedItems={} estimatedAuxItems={}",
                factoryId, planId, settlement.getId(), totalCost, sharedUnitCost,
                lines.size(), finishedGoodsCount, reworkRows.size(),
                breakdown.getCustomerSuppliedItemCount(), breakdown.getEstimatedAuxItemCount());
        return com.cretas.aims.dto.production.ProductionCostRecalculationResponse.builder()
                .productionPlanId(planId)
                .settlementId(settlement.getId())
                .authoritativeTotalCost(totalCost)
                .sharedUnitCost(sharedUnitCost)
                .outputLineCount(lines.size())
                .finishedGoodsBatchCount(finishedGoodsCount)
                .reworkWipCount(reworkRows.size())
                .productionBatchCount(productionBatches.size())
                // 🔴 审计透明度补丁 (2026-09-04): 让审阅者能看到逐项明细, 而不是只有一个总数。
                .costContributions(breakdown.getSources())
                .customerSuppliedItems(breakdown.getCustomerSuppliedItems())
                .customerSuppliedNote(customerSuppliedNote)
                .estimatedAuxItems(breakdown.getEstimatedAuxItems())
                .estimatedAuxNote(estimatedAuxNote)
                .build();
    }

    private String finishedGoodsBatchNumber(ProductionSettlement settlement) {
        String planNumber = settlement.getPlanNumber() != null ? settlement.getPlanNumber() : settlement.getProductionPlanId();
        String raw = "FG-" + planNumber;
        if (raw.length() <= 64) {
            return raw;
        }
        String suffix = settlement.getId() != null && settlement.getId().length() >= 8
                ? settlement.getId().substring(0, 8)
                : UUID.randomUUID().toString().substring(0, 8);
        return raw.substring(0, 55) + "-" + suffix;
    }

    private ProductionTransitLedger createTransitLedger(ProductionSettlement settlement,
                                                        BigDecimal reported,
                                                        BigDecimal received,
                                                        BigDecimal variance,
                                                        BigDecimal tolerance,
                                                        String unit,
                                                        ProductionWarehouseReceiptRequest request,
                                                        Long receivedBy) {
        String responsibility = trimToNull(request.getResponsibilitySide());
        if (responsibility == null || !Set.of("PRODUCTION", "WAREHOUSE", "WEIGHING_ERROR").contains(responsibility)) {
            throw new BusinessException(400, "责任归属无效: " + responsibility)
                    .withHint("请选择 PRODUCTION、WAREHOUSE 或 WEIGHING_ERROR")
                    .withHintTarget("责任归属");
        }

        ProductionTransitLedger ledger = new ProductionTransitLedger();
        ledger.setId(UUID.randomUUID().toString());
        ledger.setFactoryId(settlement.getFactoryId());
        ledger.setSettlementId(settlement.getId());
        ledger.setProductionPlanId(settlement.getProductionPlanId());
        ledger.setPlanNumber(firstNonBlank(settlement.getPlanNumber(), settlement.getProductionPlanId()));
        ledger.setLedgerType("FINISHED_GOODS_RECEIPT");
        ledger.setReportedQuantity(reported);
        ledger.setConfirmedQuantity(received);
        ledger.setVarianceQuantity(variance);
        ledger.setToleranceQuantity(tolerance);
        ledger.setQuantityUnit(unit);
        ledger.setVarianceReason(trimToNull(request.getVarianceReason()));
        ledger.setResponsibilitySide(responsibility);
        ledger.setStatus("OPEN");
        ledger.setNote(trimToNull(request.getVarianceNote()));
        ledger.setCreatedBy(receivedBy);
        ledger.setCreatedAt(LocalDateTime.now());
        return productionTransitLedgerRepository.save(ledger);
    }

    private ProductionWarehouseReceiptResponse toWarehouseReceiptResponse(ProductionSettlement settlement,
                                                                         String message,
                                                                         List<String> warnings) {
        return ProductionWarehouseReceiptResponse.builder()
                .settlementId(settlement.getId())
                .productionPlanId(settlement.getProductionPlanId())
                .planNumber(settlement.getPlanNumber())
                .productionReportedQuantity(settlement.getActualFinishedQuantity())
                .warehouseReceivedQuantity(settlement.getWarehouseReceivedQuantity())
                .varianceQuantity(settlement.getWarehouseVarianceQuantity())
                .toleranceQuantity(settlement.getQuantityUnit() == null
                        ? null
                        : receiptTolerance(settlement.getQuantityUnit()))
                .quantityUnit(settlement.getQuantityUnit())
                .postingStatus(settlement.getPostingStatus())
                .finishedGoodsBatchId(settlement.getFinishedGoodsBatchId())
                .transitLedgerId(settlement.getTransitLedgerId())
                .outputLines(loadOutputLineDtos(settlement))
                .message(message)
                .warnings(warnings != null ? warnings : Collections.emptyList())
                .build();
    }

    private List<com.cretas.aims.dto.production.ProductionOutputLineDTO> loadOutputLineDtos(
            ProductionSettlement settlement) {
        if (productionSettlementOutputLineRepository == null || settlement == null
                || isBlank(settlement.getId()) || isBlank(settlement.getFactoryId())) {
            return Collections.emptyList();
        }
        return productionSettlementOutputLineRepository
                .findByFactoryIdAndSettlementIdOrderByProductTypeIdAscReportedBatchNumberAsc(
                        settlement.getFactoryId(), settlement.getId())
                .stream()
                .map(line -> com.cretas.aims.dto.production.ProductionOutputLineDTO.builder()
                        .productTypeId(line.getProductTypeId())
                        .reportedBatchNumber(line.getReportedBatchNumber())
                        .reportedQuantity(line.getReportedQuantity())
                        .quantityUnit(line.getQuantityUnit())
                        .bomFamilyId(line.getBomFamilyId())
                        .bomRecipeId(line.getBomRecipeId())
                        .bomRecipeVersion(line.getBomRecipeVersion())
                        .outputRole(line.getOutputRole() != null ? line.getOutputRole().name() : null)
                        .costAllocationRatio(line.getCostAllocationRatio())
                        .allocatedCost(line.getAllocatedCost())
                        .unitCost(line.getUnitCost())
                        .receivedQuantity(line.getReceivedQuantity())
                        .finishedGoodsBatchId(line.getFinishedGoodsBatchId())
                        .status(line.getStatus())
                        .build())
                .toList();
    }

    private boolean requiresProductionSettlement(String factoryId) {
        if (factorySettingsRepository != null) {
            Boolean skipProcessReportingDefault =
                    factorySettingsRepository.findSkipProcessReportingDefaultByFactoryId(factoryId);
            if (skipProcessReportingDefault != null) {
                return Boolean.TRUE.equals(skipProcessReportingDefault);
            }
        }
        return isLiushanmenFactory(factoryId);
    }

    private boolean isLiushanmenFactory(String factoryId) {
        return "F006".equalsIgnoreCase(factoryId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isPendingResponsibilitySide(String value) {
        String normalized = trimToNull(value);
        return normalized == null || "PENDING".equals(normalized);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isEmpty(Collection<?> values) {
        return values == null || values.isEmpty();
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    @Override
    @Transactional
    public void cancelProductionPlan(String factoryId, String planId, String reason) {
        cancelProductionPlan(factoryId, planId, reason, null);
    }

    @Override
    @Transactional
    public void cancelProductionPlan(String factoryId, String planId, String reason, Long operatorId) {
        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(planId)
            .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // 取消接口是命令幂等的：重复点击不追加备注，也不再次触碰关联批次/任务。
        if (plan.getStatus() == ProductionPlanStatus.CANCELLED) {
            log.info("取消生产计划幂等命中: factoryId={}, planId={}", factoryId, planId);
            return;
        }

        // 已完成的计划不能直接取消 — 引导走批次级「整单撤回」(canonical 撤回, 会恢复库存 + 走审批).
        // 计划级 PRODUCTION_REVERSAL 已废弃 (死路, 不恢复库存); 客户需求的整单撤回是工单/批次级.
        if (plan.getStatus() == ProductionPlanStatus.COMPLETED) {
            throw new BusinessException(409, "已完成的生产计划不能直接取消")
                    .withHint("请在「生产批次详情」用「整单撤回」逐批次撤回 — 会自动恢复原料/半成品库存并走审批");
        }

        // 六扇门红线 (审计 Tier0 #01): 待审批 (PENDING_APPROVAL) 的计划已进入
        // PRODUCTION_REVERSAL 审批流, 不允许通过本接口直接取消绕过主管审批.
        // 防止: COMPLETED → request-cancel (PENDING_APPROVAL) → 旧 cancel 直接 CANCELLED 绕审批 + 留下悬挂工作流实例.
        if (plan.getStatus() == ProductionPlanStatus.PENDING_APPROVAL) {
            throw new BusinessException(409, "该计划正在撤回审批流程中, 不能直接取消")
                    .withHint("请在「撤回审批」列表中处理, 或等待主管审批");
        }

        // Issue #759: 锁定的计划不可取消
        if (Boolean.TRUE.equals(plan.getIsLocked())) {
            throw new BusinessException(409, "生产计划已锁定, 不可取消")
                    .withHint("先解锁该计划再尝试取消");
        }
        String cancellationReason = trimToNull(reason);
        if (cancellationReason == null) {
            throw new BusinessException(400, "取消原因不能为空")
                    .withHint("请填写本次取消的业务原因");
        }

        // 找到本计划关联的活跃批次 (R1: 仅本计划的批次, 不波及同产品其它计划)。
        List<ProductionBatch> planBatches = productionBatchRepository
                .findByFactoryIdAndProductionPlanId(factoryId, planId);

        // R2 (2026-06-14 v2): IN_PROGRESS 且已有报工 / 已消耗 WIP 的计划禁止直接取消。
        // 直接置 CANCELLED 会留下孤儿批次 + 未冲销的 WIP 消耗 (后续同产品领料被 G7 防呆拦)。
        //
        // ⚠️ 不再导向 PRODUCTION_REVERSAL 审批流: 该流的"审批通过回调" executeCancelApproved 在主代码
        //    中无调用方 (工作流引擎是纯状态机, 不回调业务层), 且即便触发也不恢复库存 —— 是死路。
        //    改导向「报工撤回」流 (ReportReversalService): 它自带审批 + 真正恢复 WIP/原料/FGB/任务。
        //    两步: ① 对每个有报工的批次提交报工撤回 (有数据 → 主管审批 → 自动 executeReversal 恢复库存);
        //          ② 报工软删后本计划 hasProductionData 返 false → 再调本接口直接取消空计划。
        // 防呆 4 位一体: message 具体 (说明已开工有报工 + 列出批次号) + 含 next action (报工撤回, 指明路径)
        //    + sticky (前端按 4xx 处理) + hintTarget (报工撤回 button)。
        // 修1 (🔴): 区分 SECONDARY 二次加工"只开工扣了 WIP 无报工" vs "已有报工"。
        //   - 只开工扣 WIP (无 YIELD 报工): 报工撤回流是 no-op (无报工可冲), 旧逻辑让用户去报工撤回 = 死路
        //     (撤回不还 startProduction 扣的那笔 WIP → 计划永卡 IN_PROGRESS, WIP 孤儿)。
        //     → 此处在同一事务内先反冲还回开工扣的 WIP, 再直接取消, 不导向报工撤回。
        //   - 已有报工: 仍走报工撤回流 (ReportReversalService 冲报工产生的 WIP)。
        boolean hasYields = hasYieldReports(factoryId, plan);
        boolean isSecondaryInProgress = plan.getStatus() == ProductionPlanStatus.IN_PROGRESS
                && "SECONDARY".equals(plan.getPlanSourceType())
                && plan.getSecondarySourceWipId() != null;
        boolean hasProductionActivity = hasRealProductionActivity(factoryId, planId)
                || hasYields
                || isSecondaryInProgress;

        if (isSecondaryInProgress && !hasYields) {
            // 反冲开工扣的半成品 WIP (还回 plannedQuantity), 然后直接取消 (不卡 IN_PROGRESS, 不导向报工撤回)。
            if (wipInventoryService == null) {
                throw new BusinessException(500, "二次加工服务未初始化, 无法反冲半成品库存");
            }
            BigDecimal reverseQty = plan.getPlannedQuantity() != null
                    ? plan.getPlannedQuantity() : java.math.BigDecimal.ZERO;
            if (reverseQty.compareTo(java.math.BigDecimal.ZERO) > 0) {
                wipInventoryService.reverseSecondaryDeduct(
                        plan.getSecondarySourceWipId(), reverseQty, factoryId,
                        operatorId);
                log.info("修1 SECONDARY 取消反冲 WIP: planId={}, wipId={}, qty={}",
                        planId, plan.getSecondarySourceWipId(), reverseQty);
            }
            // 落入下方直接取消逻辑 (置 CANCELLED + 级联空批次/任务)。
        } else if (hasProductionActivity) {
            // 无论计划当前处于 PENDING / IN_PROGRESS / PAUSED 等何种非终态，
            // 一旦存在正式报工、消耗、产出或小结，都必须走报工撤回，不能靠状态值绕过。
            String batchHint = buildReversalBatchHint(planBatches);
            throw new BusinessException(409, "该计划已有正式报工、消耗、产出或小结, 不能直接取消"
                    + (batchHint.isEmpty() ? "" : " (涉及批次: " + batchHint + ")"))
                    .withCode("PLAN_HAS_PRODUCTION_DATA")
                    .withHint("请先在「报工撤回」中对相关批次提交整单撤回 (经主管审批后自动冲销报工与 WIP 库存恢复原料), "
                            + "撤回完成后即可取消此空计划")
                    .withHintTarget("报工撤回");
        }

        // R2: IN_PROGRESS 但无报工无 WIP (空批次) — 安全直接取消, 顺手把空批次置 CANCELLED。
        // PENDING — 通常无批次, 即便有 (异常态) 也按 R1 仅级联本批次任务。

        // 更新计划状态
        plan.setStatus(ProductionPlanStatus.CANCELLED);
        plan.setNotes(plan.getNotes() != null ?
            plan.getNotes() + "\n取消原因：" + cancellationReason :
            "取消原因：" + cancellationReason);
        plan.setCancelReason(cancellationReason);
        plan.setCancelledBy(operatorId);
        plan.setCancelledAt(LocalDateTime.now());
        productionPlanRepository.save(plan);

        // R1 (2026-06-14): 按批次定向级联关闭 WorkProcessTask (新表), 取代旧的"按产品类型全关 ProcessTask"。
        // 旧逻辑 findByFactoryIdAndProductTypeId 会把同产品另一并行活跃批次的任务一并 CLOSED (六扇门 6.1+6.2 场景误伤)。
        cancelPlanBatchesAndTasks(factoryId, planId, planBatches);

        log.info("取消生产计划: planId={}, operatorId={}, reason={}, batches={}",
                planId, operatorId, cancellationReason, planBatches != null ? planBatches.size() : 0);
    }

    /**
     * R2 (2026-06-14): 判断一个计划是否已产生生产数据 (报工 / 已消耗 WIP), 用于禁止 IN_PROGRESS 直接取消。
     *
     * <p>判定信号 (任一为真即"有数据"):
     * <ul>
     *   <li>本计划任一非取消批次已有 YIELD 报工 (production_reports.report_type=YIELD)</li>
     *   <li>SECONDARY 二次加工计划已 IN_PROGRESS (开工时已扣减 secondarySourceWipId 的 WIP 半成品)</li>
     * </ul>
     */
    private boolean hasProductionData(String factoryId, ProductionPlan plan) {
        // SECONDARY 计划开工即扣 WIP — 已 IN_PROGRESS 视为有数据 (供 requestCancelWithApproval 判定撤回资格)。
        // 注: cancelProductionPlan 不再用本方法做"导向报工撤回 vs 反冲"的二分, 改用 hasYieldReports 细分 (修1)。
        if ("SECONDARY".equals(plan.getPlanSourceType())
                && plan.getSecondarySourceWipId() != null
                && plan.getStatus() == ProductionPlanStatus.IN_PROGRESS) {
            return true;
        }
        return hasYieldReports(factoryId, plan);
    }

    /**
     * 修1 (🔴): 纯报工检测 — 本计划任一非取消批次是否已有 YIELD 报工。
     *
     * <p>不含 {@link #hasProductionData} 的 "SECONDARY 开工即扣 WIP 视为有数据" 短路, 用于 cancel 区分
     * "SECONDARY 只开工扣 WIP 无报工" (→ 反冲 WIP 直接取消) vs "已有报工" (→ 导向报工撤回流)。
     */
    private boolean hasYieldReports(String factoryId, ProductionPlan plan) {
        if (productionReportRepository != null) {
            List<ProductionBatch> batches = productionBatchRepository
                    .findByFactoryIdAndProductionPlanId(factoryId, plan.getId());
            if (batches != null) {
                for (ProductionBatch b : batches) {
                    if (b.getId() == null || b.getStatus() == ProductionBatchStatus.CANCELLED) {
                        continue;
                    }
                    List<ProductionReport> yields = productionReportRepository
                            .findYieldReportsByBatch(factoryId, b.getId());
                    if (yields != null && !yields.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return hasMaterializedProcessSheetRows(factoryId, plan.getId());
    }

    private boolean hasMaterializedProcessSheetRows(String factoryId, String planId) {
        if (processSheetRowRepository == null || isBlank(planId)) {
            return false;
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId);
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        return rows.stream().anyMatch(row -> isUsableProcessSheetRow(row)
                && (row.getBatchId() != null || !isBlank(row.getBatchNumber())));
    }

    /**
     * R2 (2026-06-14 v2): 拼出本计划"需先撤报工"的批次号提示 (防呆 Rule 2 — context 必带身份信息)。
     * 仅列出非取消批次的 batchNumber (无则退回 id), 用于 cancel 拒绝消息引导用户去报工撤回。
     */
    private String buildReversalBatchHint(List<ProductionBatch> planBatches) {
        if (planBatches == null || planBatches.isEmpty()) {
            return "";
        }
        java.util.List<String> refs = new java.util.ArrayList<>();
        for (ProductionBatch b : planBatches) {
            if (b == null || b.getId() == null || b.getStatus() == ProductionBatchStatus.CANCELLED) {
                continue;
            }
            String ref = b.getBatchNumber() != null && !b.getBatchNumber().isBlank()
                    ? b.getBatchNumber()
                    : String.valueOf(b.getId());
            refs.add(ref);
        }
        return String.join(", ", refs);
    }

    /**
     * R1 (2026-06-14): 按批次定向级联取消 — 把本计划每个未取消批次置 CANCELLED, 并 CANCELLED 其活跃 WorkProcessTask。
     *
     * <p>严格按 batchId 查任务 ({@code findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc}),
     * 不碰其它批次的任务 (即便同一 productTypeId)。终态任务 (COMPLETED/SKIPPED/CANCELLED) 不变。
     */
    private void cancelPlanBatchesAndTasks(String factoryId, String planId, List<ProductionBatch> planBatches) {
        if (planBatches == null || planBatches.isEmpty()) {
            return;
        }
        for (ProductionBatch batch : planBatches) {
            if (batch.getId() == null) {
                continue;
            }
            // 空批次 / 活跃批次 → CANCELLED (已 CANCELLED/COMPLETED 不动)
            if (batch.getStatus() != ProductionBatchStatus.CANCELLED
                    && batch.getStatus() != ProductionBatchStatus.COMPLETED) {
                batch.setStatus(ProductionBatchStatus.CANCELLED);
                productionBatchRepository.save(batch);
            }
            if (workProcessTaskRepository == null) {
                continue; // 单测无注入 → 跳过 (有注入的测试覆盖级联)
            }
            List<com.cretas.aims.entity.workprocess.WorkProcessTask> tasks = workProcessTaskRepository
                    .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, batch.getId());
            int closedCount = 0;
            for (com.cretas.aims.entity.workprocess.WorkProcessTask task : tasks) {
                if (!task.getStatus().isTerminal()) {
                    task.setStatus(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.CANCELLED);
                    closedCount++;
                }
            }
            if (closedCount > 0) {
                workProcessTaskRepository.saveAll(tasks);
                log.info("R1 级联取消 {} 个工序任务: planId={}, batchId={}", closedCount, planId, batch.getId());
            }
        }
    }

    /**
     * @deprecated 计划级 PRODUCTION_REVERSAL 审批流已废弃。结构性死路: workflow 引擎是纯状态机, 从不
     * 回调 {@link #executeCancelApproved}/{@code executeReversal} (二者在主代码零调用方), 即便回调也只
     * 改状态、不恢复库存。且无前端调用方 (web-admin 批次详情用的是批次级「整单撤回」)。
     * <p>canonical 撤回 = 批次级整单撤回 {@link com.cretas.aims.service.reversal.ReportReversalService}
     * (符合客户 6.09 需求「工单整单撤回 + 审批 + 无数据直撤 + 角色」, 含 G1 下游/G2 出货/G3 幂等守卫 +
     * 真正恢复原料/WIP/FGB)。IN_PROGRESS+数据的计划取消已由 {@link #cancelProductionPlan} 导向报工撤回流。
     * <p>本方法保留签名但现抛 409 引导改走批次整单撤回 (防呆 Rule 5: dead-end → 导航)。
     */
    @Override
    @Deprecated
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public String requestCancelWithApproval(String factoryId, String planId, String reason, Long userId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂");
        }
        throw new BusinessException(409, "计划级撤回审批流已停用")
                .withCode("USE_BATCH_REVERSAL")
                .withHint("请在「生产批次详情」用「整单撤回」逐批次撤回 — 会经主管审批并自动恢复原料/半成品库存 (无报工数据则直接撤回)")
                .withHintTarget("报工撤回");
    }

    /**
     * SP12 T3 / R2 (2026-06-14 v2): 审批通过后执行撤回（仅供 PRODUCTION_REVERSAL workflow 回调调用）.
     * PENDING_APPROVAL → CANCELLED，先冲销报工 + 恢复 WIP/原料/FGB 库存，再级联关闭关联工序任务。
     *
     * <p><b>⚠️ 接通现状 (R2 v2 查清)</b>: 本方法在主代码中<b>无任何调用方</b> —— 工作流引擎
     * ({@code WorkflowEngineServiceImpl.terminateAtEnd}) 是纯状态机, PRODUCTION_REVERSAL 审批到达
     * APPROVED 终态后只设置 instance.status, <b>从不回调业务层</b> (无 moduleCode→handler 派发器)。
     * 因此<b>用户面的"有报工计划取消"已改为导向报工撤回流</b> ({@link ReportReversalService},
     * 见 {@link #cancelProductionPlan} R2)。
     *
     * <p>本方法保留并<b>加固</b>为: 若未来有人把 PRODUCTION_REVERSAL 审批回调接到此方法,
     * 它会<b>真正恢复库存</b> (调 {@code reportReversalService.submitReversal} 对每个有报工的批次执行
     * 整单撤回, 自包含 G1/G2/G3 守卫 + 软删报工 + 写 REVERSE SIT + 回放均价 + 复位任务 + 标 FGB REVERSED),
     * 而非像旧版只置 CANCELLED 留下未冲销 WIP。
     */
    @Override
    @Deprecated
    @org.springframework.transaction.annotation.Transactional
    public void executeCancelApproved(String planId) {
        // @Deprecated: 计划级 PRODUCTION_REVERSAL 已废弃 (见 requestCancelWithApproval)。本回调零调用方。
        // body 保留 #834 加固版 (调 ReportReversalService 真恢复库存) 作为安全网 —— 万一有遗留
        // PENDING_APPROVAL 计划被接上, 它会正确冲销而非旧版只置 CANCELLED。新撤回一律走批次级整单撤回。
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(plan.getFactoryId(), plan);

        if (plan.getStatus() != ProductionPlanStatus.PENDING_APPROVAL) {
            throw new BusinessException(409, "生产计划不处于待审批状态，无法执行撤回")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        List<ProductionBatch> planBatches = productionBatchRepository
                .findByFactoryIdAndProductionPlanId(plan.getFactoryId(), planId);

        // R2 (2026-06-14 v2): 审批已通过 → 对每个有报工的批次真正冲销库存 (恢复 WIP/原料/FGB)。
        // 用报工撤回服务 (自包含恢复逻辑), 审批已在 PRODUCTION_REVERSAL 流完成, 故直接 submit→execute。
        // reportReversalService 未注入 (单测/旧 context) → 跳过, 仅做状态收尾 (旧行为, 由有注入路径覆盖)。
        reverseBatchReportsForApprovedCancel(plan.getFactoryId(), planId, planBatches);

        plan.setStatus(ProductionPlanStatus.CANCELLED);
        productionPlanRepository.save(plan);

        // R1 (2026-06-14): 审批通过后也按批次定向级联 (取代旧的"按产品类型全关 ProcessTask")。
        // 与 cancelProductionPlan 一致, 仅关本计划批次的活跃 WorkProcessTask, 不波及同产品其它计划。
        cancelPlanBatchesAndTasks(plan.getFactoryId(), planId, planBatches);

        log.info("SP12 T3 生产撤回审批通过，计划已取消: planId={}, batches={}", planId,
                planBatches != null ? planBatches.size() : 0);
    }

    /**
     * R2 (2026-06-14 v2): PRODUCTION_REVERSAL 审批通过后, 对本计划每个有报工的批次执行整单撤回,
     * 恢复 WIP/原料/FGB 库存 + 复位工序任务 + 反冲成本。复用 {@link ReportReversalService} 自包含逻辑。
     *
     * <p>审批已在 PRODUCTION_REVERSAL 流完成 → 直接 submit (有报工时通常返 PENDING) 再显式
     * executeReversal 落地恢复。守卫 (下游消费 G1 / 成品出货 G2) 失败会抛出 → 整体事务回滚,
     * 计划不会被错误置 CANCELLED (诚实失败, 禁止静默)。
     *
     * <p>reportReversalService / productionReportRepository 未注入时跳过 (单测/旧 context)。
     */
    private void reverseBatchReportsForApprovedCancel(String factoryId, String planId,
            List<ProductionBatch> planBatches) {
        if (reportReversalService == null || planBatches == null || planBatches.isEmpty()) {
            return;
        }
        for (ProductionBatch batch : planBatches) {
            if (batch == null || batch.getId() == null
                    || batch.getStatus() == ProductionBatchStatus.CANCELLED) {
                continue;
            }
            // 仅对有 YIELD 报工的批次撤回 (无报工批次 submitReversal 是 no-op DONE, 跳过省调用)。
            if (productionReportRepository != null) {
                List<ProductionReport> yields = productionReportRepository
                        .findYieldReportsByBatch(factoryId, batch.getId());
                if (yields == null || yields.isEmpty()) {
                    continue;
                }
            }
            // submitReversal: 有报工 → 创建 PENDING (或快速撤回 APPROVED 自动 execute); 再显式 execute 兜底恢复。
            var reversalLog = reportReversalService.submitReversal(
                    factoryId, batch.getId(), null,
                    "PRODUCTION_REVERSAL 审批通过自动撤回 (planId=" + planId + ")");
            if (reversalLog != null && reversalLog.getId() != null
                    && reversalLog.getStatus() != com.cretas.aims.entity.ReportReversalLog.ReversalStatus.DONE) {
                reportReversalService.executeReversal(reversalLog.getId(), factoryId);
            }
            log.info("R2 审批撤回: planId={}, batchId={}, reversalLogId={}, status={}", planId,
                    batch.getId(), reversalLog != null ? reversalLog.getId() : null,
                    reversalLog != null ? reversalLog.getStatus() : null);
        }
    }

    /**
     * 锁定生产计划 (Issue #759, 2026-05-17).
     */
    @Override
    @Transactional
    public ProductionPlanDTO lockProductionPlan(String factoryId, String planId, String reason, Long userId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // 已取消/已完成的计划不需要锁定
        if (plan.getStatus() == ProductionPlanStatus.CANCELLED
                || plan.getStatus() == ProductionPlanStatus.COMPLETED) {
            throw new BusinessException(409, "已取消或已完成的生产计划无需锁定");
        }

        if (Boolean.TRUE.equals(plan.getIsLocked())) {
            log.info("生产计划已处于锁定状态, 重复 lock 调用: planId={}", planId);
            return toDTOWithConversionInfo(plan);
        }

        plan.setIsLocked(true);
        plan.setLockReason(reason);
        plan.setLockedAt(java.time.LocalDateTime.now());
        plan.setLockedBy(userId);
        productionPlanRepository.save(plan);

        log.info("锁定生产计划: planId={}, userId={}, reason={}", planId, userId, reason);
        return toDTOWithConversionInfo(plan);
    }

    /**
     * 解锁生产计划 (Issue #759, 2026-05-17).
     */
    @Override
    @Transactional
    public ProductionPlanDTO unlockProductionPlan(String factoryId, String planId, Long userId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        if (!Boolean.TRUE.equals(plan.getIsLocked())) {
            log.info("生产计划未锁定, 重复 unlock 调用: planId={}", planId);
            return toDTOWithConversionInfo(plan);
        }

        plan.setIsLocked(false);
        // 保留 lock_reason / locked_at / locked_by 作为最后一次记录
        productionPlanRepository.save(plan);

        log.info("解锁生产计划: planId={}, userId={}", planId, userId);
        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional
    public ProductionPlanDTO pauseProduction(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // 只能暂停进行中的计划
        if (plan.getStatus() != ProductionPlanStatus.IN_PROGRESS) {
            throw new BusinessException(409, "只能暂停进行中的生产计划")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        plan.setStatus(ProductionPlanStatus.PAUSED);
        plan = productionPlanRepository.save(plan);

        log.info("暂停生产: planId={}", planId);
        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional
    public ProductionPlanDTO resumeProduction(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // 只能恢复暂停的计划
        if (plan.getStatus() != ProductionPlanStatus.PAUSED) {
            throw new BusinessException(409, "只能恢复暂停的生产计划")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan = productionPlanRepository.save(plan);

        log.info("恢复生产: planId={}", planId);
        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional
    public ProductionPlanDTO updateActualCosts(String factoryId, String planId,
                                               BigDecimal materialCost,
                                               BigDecimal laborCost,
                                               BigDecimal equipmentCost,
                                               BigDecimal otherCost) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);
        if (productionCostConsistency != null) productionCostConsistency.assertChangeAllowed(factoryId, planId);

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        validateNonNegativeCost("materialCost", materialCost);
        validateNonNegativeCost("laborCost", laborCost);
        validateNonNegativeCost("equipmentCost", equipmentCost);
        validateNonNegativeCost("otherCost", otherCost);

        // 设备/其他成本的核算读取批次级字段。只有唯一正式批次时才有明确归属，
        // 多批次禁止猜测分摊；空批次继续保留原有计划级更新行为。
        ProductionBatch officialBatch = null;
        List<ProductionBatch> supplementalCostBatches = new ArrayList<>();
        if (equipmentCost != null || otherCost != null) {
            List<ProductionBatch> officialBatches = productionBatchRepository
                    .findByFactoryIdAndProductionPlanId(factoryId, planId);
            officialBatch = officialBatches == null ? null : officialBatches.stream()
                    .filter(Objects::nonNull)
                    .filter(batch -> Objects.equals(factoryId, batch.getFactoryId()))
                    .filter(batch -> !"CLERK_WIP".equals(batch.getBatchType()))
                    .findFirst()
                    .orElse(null);
            long officialBatchCount = officialBatches == null ? 0 : officialBatches.stream()
                    .filter(Objects::nonNull)
                    .filter(batch -> Objects.equals(factoryId, batch.getFactoryId()))
                    .filter(batch -> !"CLERK_WIP".equals(batch.getBatchType()))
                    .count();
            if (officialBatchCount > 1) {
                // Nonnegative costs summing to an explicitly confirmed zero
                // have one allocation only: every batch is zero. Never use
                // this confirmation to erase a previously recorded expense.
                boolean onlyZero = (equipmentCost == null || equipmentCost.signum() == 0)
                        && (otherCost == null || otherCost.signum() == 0);
                supplementalCostBatches = officialBatches.stream().filter(Objects::nonNull)
                        .filter(batch -> Objects.equals(factoryId, batch.getFactoryId()))
                        .filter(batch -> !"CLERK_WIP".equals(batch.getBatchType())).toList();
                boolean hasRecordedExpense = (equipmentCost != null && plan.getActualEquipmentCost() != null
                        && plan.getActualEquipmentCost().signum() > 0)
                        || (otherCost != null && plan.getActualOtherCost() != null && plan.getActualOtherCost().signum() > 0)
                        || supplementalCostBatches.stream().anyMatch(batch ->
                        (equipmentCost != null && batch.getEquipmentCost() != null && batch.getEquipmentCost().signum() > 0)
                                || (otherCost != null && batch.getOtherCost() != null && batch.getOtherCost().signum() > 0));
                if (!onlyZero || hasRecordedExpense) {
                    throw new BusinessException(409, "该生产计划关联多个正式批次，无法自动分摊或覆盖设备/其他成本")
                            .withHint("多批次仅支持统一确认无费用；有实际费用时需明确各批次分摊")
                            .withHintTarget("equipmentCost");
                }
                officialBatch = null;
            }
        }

        // 更新实际成本
        if (materialCost != null) {
            plan.setActualMaterialCost(materialCost);
        }
        if (laborCost != null) {
            plan.setActualLaborCost(laborCost);
        }
        if (equipmentCost != null) {
            plan.setActualEquipmentCost(equipmentCost);
        }
        if (otherCost != null) {
            plan.setActualOtherCost(otherCost);
        }

        if (officialBatch != null) supplementalCostBatches.add(officialBatch);
        for (ProductionBatch costBatch : supplementalCostBatches) {
            if (equipmentCost != null) {
                costBatch.setEquipmentCost(equipmentCost);
            }
            if (otherCost != null) {
                costBatch.setOtherCost(otherCost);
            }
            productionBatchRepository.save(costBatch);
        }

        plan = productionPlanRepository.save(plan);

        log.info("更新实际成本: planId={}", planId);
        boolean recalculated = productionSettlementRepository != null && productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdForUpdate(factoryId, planId)
                .filter(settlement -> "POSTED".equals(settlement.getPostingStatus())).isPresent();
        if (recalculated) {
            // Self invocation remains inside updateActualCosts' transaction: any failure rolls back
            // the plan, batch, settlement allocation and finished-goods cost together.
            recalculateSettledPlanCosts(factoryId, planId);
        }
        ProductionPlanDTO result = toDTOWithConversionInfo(plan);
        result.setSettledCostsRecalculated(recalculated);
        return result;
    }

    private void validateNonNegativeCost(String fieldName, BigDecimal value) {
        if (value != null && value.signum() < 0) {
            throw new BusinessException(400, fieldName + " 不能为负数")
                    .withHint("请传入大于或等于 0 的成本；省略参数表示不修改");
        }
    }

    @Override
    @Transactional
    public void assignMaterialBatches(String factoryId, String planId, List<String> batchIds) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        assignMaterialBatchesToPlan(plan, batchIds);
        log.info("分配原材料批次: planId={}, batchCount={}", planId, batchIds.size());
    }

    @Override
    @Transactional
    public void recordMaterialConsumption(String factoryId, String planId, String batchId, BigDecimal quantity, Long operatorId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        MaterialBatch batch = materialBatchRepository.findByIdAndFactoryIdForUpdate(batchId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 单发调用点(每次一个 batchId), 单行查询即可, 不需要批量。
        assertMaterialBatchOwnershipMatchesPlan(
                plan, batch, resolveMaterialMainCategory(factoryId, batch.getMaterialTypeId()), "原料领用");

        // 检查库存是否足够
        if (batch.getCurrentQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(409, "批次库存不足")
                    .withHint("请减少计划数量, 或先入库补货");
        }

        // 创建消耗记录
        // C-B1 同族 fix: MaterialConsumption.unitPrice/totalCost/recordedBy 均 NOT NULL。
        //   - 批次单价可能为 null (未录价批次) → 兜底 ZERO, 否则 setUnitPrice(null)/multiply(null) → 500
        //   - recordedBy 走 FK→users: 优先当前操作人 (operatorId), 兜底计划创建人, 均无则不可记录
        BigDecimal unitPrice = batch.getUnitPrice() != null ? batch.getUnitPrice() : BigDecimal.ZERO;
        Long recordedBy = operatorId != null ? operatorId : plan.getCreatedBy();
        if (recordedBy == null) {
            throw new BusinessException(401, "无法识别操作人，无法记录消耗")
                    .withHint("请重新登录后重试");
        }
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setFactoryId(factoryId);
        consumption.setProductionPlanId(planId);
        consumption.setBatchId(batchId);
        consumption.setQuantity(quantity);
        consumption.setUnitPrice(unitPrice);
        consumption.setTotalCost(quantity.multiply(unitPrice));
        consumption.setConsumptionTime(LocalDateTime.now());
        consumption.setRecordedBy(recordedBy);
        // 🔴 2026-08-27: 此前**从不设 sourceType** ⇒ VoucherConsumptionSourcePolicy 对 null 返回 false
        //    ⇒ 这条领用不进财务凭证, 成本悄悄少算(静默, 无信号)。
        //
        // 这里恒为 PRODUCTION_ISSUE 而不是 :1665 那种三元判据: 本方法一进来就按 planId 取了
        // plan 并校验工厂归属, 上面 setProductionPlanId(planId) 用的就是它 —— **不存在无计划的分支**。
        consumption.setSourceType("PRODUCTION_ISSUE");
        materialConsumptionRepository.save(consumption);

        // 更新批次库存
        // 注意: currentQuantity 是计算属性，通过增加 usedQuantity 来减少 currentQuantity
        batch.setUsedQuantity(batch.getUsedQuantity().add(quantity));
        batch.setLastUsedAt(LocalDateTime.now());
        // 🔒 全局兜底: 增加 usedQuantity 后断言 used+reserved≤receipt, 防超扣 (误伤-proof 后置校验)
        batch.assertConsumptionInvariant();
        if (batch.getCurrentQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            batch.setStatus(MaterialBatchStatus.USED_UP);
        }
        materialBatchRepository.save(batch);
        inventoryLowStockEventPublisher.publishIfLowStock(factoryId, batch, "OUT");

        log.info("记录材料消耗: planId={}, batchId={}, quantity={}", planId, batchId, quantity);
    }

    @Override
    public Map<String, Object> getProductionStatistics(String factoryId, LocalDate startDate, LocalDate endDate) {
        Specification<ProductionPlan> date = (root, query, cb) -> {
            if (startDate == null || endDate == null) return cb.conjunction();
            return cb.between(root.get("plannedDate"), startDate, endDate);
        };
        List<ProductionPlan> plans = productionPlanRepository.findAll(
                planScope(factoryId, visiblePlanCreators(factoryId)).and(date));

        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalPlans", plans.size());
        statistics.put("completedPlans", plans.stream().filter(p -> p.getStatus() == ProductionPlanStatus.COMPLETED).count());
        statistics.put("inProgressPlans", plans.stream().filter(p -> p.getStatus() == ProductionPlanStatus.IN_PROGRESS).count());
        statistics.put("pendingPlans", plans.stream().filter(p -> p.getStatus() == ProductionPlanStatus.PENDING).count());

        // 计算总成本
        BigDecimal totalCost = plans.stream()
                .filter(p -> p.getStatus() == ProductionPlanStatus.COMPLETED)
                .map(p -> {
                    BigDecimal cost = BigDecimal.ZERO;
                    if (p.getActualMaterialCost() != null) cost = cost.add(p.getActualMaterialCost());
                    if (p.getActualLaborCost() != null) cost = cost.add(p.getActualLaborCost());
                    if (p.getActualEquipmentCost() != null) cost = cost.add(p.getActualEquipmentCost());
                    if (p.getActualOtherCost() != null) cost = cost.add(p.getActualOtherCost());
                    return cost;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        statistics.put("totalCost", totalCost);

        return statistics;
    }

    @Override
    public List<ProductionPlanDTO> getPendingPlansToExecute(String factoryId) {
        // N1b: 待执行列表即车间未完成列表, 包含已下达未开工(PENDING)和进行中(IN_PROGRESS)。
        List<ProductionPlan> plans = productionPlanRepository.findAll(
                planScope(factoryId, visiblePlanCreators(factoryId))
                        .and(planStatusIn(List.of(ProductionPlanStatus.PENDING, ProductionPlanStatus.IN_PROGRESS))));
        return plans.stream()
            .filter(plan -> plan.getDeletedAt() == null)
            .map(productionPlanMapper::toDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public List<ProductionPlanDTO> batchCreateProductionPlans(String factoryId,
                                                              List<CreateProductionPlanRequest> requests,
                                                              Long userId) {
        return requests.stream()
                .map(request -> createProductionPlan(factoryId, request, userId))
                .collect(Collectors.toList());
    }

    @Override
    public byte[] exportProductionPlans(String factoryId, LocalDate startDate, LocalDate endDate,
                                        boolean maskPrice) {
        // RBAC defense-in-depth (P0-C sweep, 2026-05-12): maskPrice parameter wired
        // through but currently no-op — ProductionPlanImportDTO has no cost/price
        // columns. When export is extended to include actualMaterialCost / laborCost /
        // etc., honor maskPrice by swapping to a masked DTO. PR #450 sweep matrix row 9.
        Specification<ProductionPlan> date = (root, query, cb) -> {
            if (startDate == null || endDate == null) return cb.conjunction();
            return cb.between(root.get("expectedCompletionDate"), startDate, endDate);
        };
        List<ProductionPlanImportDTO> exportData = productionPlanRepository
                .findAll(planScope(factoryId, visiblePlanCreators(factoryId)).and(date)).stream()
                .map(this::toImportDTO)
                .collect(Collectors.toList());
        return excelUtil.exportToExcel(exportData, ProductionPlanImportDTO.class, "生产计划");
    }

    @Override
    public byte[] generateImportTemplate() {
        return excelUtil.generateTemplate(ProductionPlanImportDTO.class, "生产计划导入模板");
    }

    @Override
    @Transactional
    public ImportResult<ProductionPlanDTO> importProductionPlansFromExcel(String factoryId, InputStream inputStream, Long userId) {
        List<ProductionPlanImportDTO> excelData;
        try {
            excelData = excelUtil.importFromExcel(inputStream, ProductionPlanImportDTO.class);
        } catch (Exception e) {
            throw new BusinessException(400, "Excel文件解析失败: " + e.getMessage())
                    .withHint("请检查 Excel 文件格式是否正确, 列是否匹配模板").withHintTarget("file");
        }

        ImportResult<ProductionPlanDTO> result = ImportResult.create(excelData.size());

        for (int i = 0; i < excelData.size(); i++) {
            int rowNumber = i + 2; // Excel row (header is row 1)
            ProductionPlanImportDTO row = excelData.get(i);
            try {
                // Validate required fields
                if (row.getProductName() == null || row.getProductName().trim().isEmpty()) {
                    result.addFailure(rowNumber, "产品名称不能为空", toJson(row));
                    continue;
                }
                if (row.getPlannedQuantity() == null) {
                    result.addFailure(rowNumber, "计划数量不能为空", toJson(row));
                    continue;
                }
                if (row.getExpectedCompletionDate() == null) {
                    result.addFailure(rowNumber, "预计完成日期不能为空", toJson(row));
                    continue;
                }

                // Resolve product name to ID
                Optional<ProductType> productType = productTypeRepository
                        .findByFactoryIdAndName(factoryId, row.getProductName().trim());
                if (!productType.isPresent()) {
                    result.addFailure(rowNumber, "产品 \"" + row.getProductName() + "\" 不存在", toJson(row));
                    continue;
                }

                // Build CreateProductionPlanRequest
                CreateProductionPlanRequest request = new CreateProductionPlanRequest();
                request.setProductTypeId(productType.get().getId());
                request.setPlannedQuantity(row.getPlannedQuantity());
                request.setPlannedDate(row.getExpectedCompletionDate());
                request.setExpectedCompletionDate(row.getExpectedCompletionDate());
                request.setPriority(row.getPriority() != null ? row.getPriority() : 5);
                request.setCustomerOrderNumber(row.getCustomerOrderNumber());
                request.setNotes(row.getNotes());
                request.setEstimatedWorkers(row.getEstimatedWorkers());
                request.setSourceType(PlanSourceType.EXCEL_IMPORT);

                // Resolve optional supervisor username
                if (row.getSupervisorUsername() != null && !row.getSupervisorUsername().trim().isEmpty()) {
                    Optional<User> supervisor = userRepository.findByUsername(row.getSupervisorUsername().trim());
                    if (supervisor.isPresent()) {
                        request.setAssignedSupervisorId(supervisor.get().getId());
                    } else {
                        log.warn("第{}行: 主管用户名 \"{}\" 未找到，已忽略", rowNumber, row.getSupervisorUsername());
                    }
                }

                ProductionPlanDTO created = createProductionPlan(factoryId, request, userId);
                result.addSuccess(created);
            } catch (Exception e) {
                result.addFailure(rowNumber, "保存失败: " + e.getMessage(), toJson(row));
            }
        }

        log.info("Excel导入完成: 总计={}, 成功={}, 失败={}", result.getTotalCount(), result.getSuccessCount(), result.getFailureCount());
        return result;
    }

    /**
     * 分配原材料批次到生产计划
     */
    private void assignMaterialBatchesToPlan(ProductionPlan plan, List<String> batchIds) {
        // 先取完全部批次(materialTypeId 要等批次加载后才知道), 再一次性批量预取大类,
        // ⛔ 不逐个 batchId 查 RawMaterialType —— 这是缺陷 38 结单热修同一条纪律。
        java.util.Map<String, MaterialBatch> batchesById = new java.util.LinkedHashMap<>();
        for (String batchId : batchIds) {
            MaterialBatch batch = materialBatchRepository.findByIdAndFactoryId(batchId, plan.getFactoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));
            batchesById.put(batchId, batch);
        }
        java.util.Map<String, String> materialMainCategoryByType = batchResolveMaterialMainCategories(
                plan.getFactoryId(), batchesById.values().stream()
                        .map(MaterialBatch::getMaterialTypeId)
                        .collect(java.util.stream.Collectors.toSet()));

        for (String batchId : batchIds) {
            MaterialBatch batch = batchesById.get(batchId);

            assertMaterialBatchOwnershipMatchesPlan(
                    plan, batch, materialMainCategoryByType.get(batch.getMaterialTypeId()), "原料批次");

            // 检查批次状态
            if (batch.getStatus() != MaterialBatchStatus.AVAILABLE) {
                throw new BusinessException(409, "批次 " + batch.getBatchNumber() + " 不可用")
                        .withHint("请刷新批次列表后重新选择, 或选择其他可用批次");
            }

            // 创建关联
            ProductionPlanBatchUsage usage = new ProductionPlanBatchUsage();
            usage.setProductionPlanId(plan.getId());
            usage.setMaterialBatchId(batchId);
            usage.setPlannedQuantity(BigDecimal.ZERO); // 需要根据实际需求设置
            planBatchUsageRepository.save(usage);
        }
    }

    /**
     * 将计划实体转换为DTO并填充转换率信息
     */
    private ProductionPlanDTO toDTOWithConversionInfo(ProductionPlan plan) {
        ProductionPlanDTO dto = productionPlanMapper.toDTO(plan);
        enrichWithConversionRateInfo(dto, plan.getFactoryId(), plan.getProductTypeId());
        enrichWithAssignmentNames(dto, plan);
        enrichWithTerminalActionCapabilities(dto, plan);
        return dto;
    }

    /**
     * 把状态机约束前置给 UI，避免用户点下“停产”以后才知道空计划只能取消，
     * 或者已经报工但尚未小结。后端命令守卫仍是最终权威。
     */
    private void enrichWithTerminalActionCapabilities(ProductionPlanDTO dto, ProductionPlan plan) {
        ProductionPlanStatus status = plan.getStatus();
        boolean terminal = status == null
                || status == ProductionPlanStatus.COMPLETED
                || status == ProductionPlanStatus.CANCELLED
                || status == ProductionPlanStatus.PENDING_APPROVAL;
        if (terminal) {
            dto.setHasProductionActivity(status == ProductionPlanStatus.COMPLETED);
            dto.setHasUnsettledProduction(false);
            dto.setCanCancel(false);
            dto.setCanStop(false);
            dto.setCanRepinAuthority(false);
            dto.setRepinBlockedReason("计划已结束");
            dto.setStopBlockedReason("计划已结束");
            dto.setNextAction(status == ProductionPlanStatus.COMPLETED ? "查看生产档案" : "无需操作");
            return;
        }

        boolean hasActivity = hasRealProductionActivity(plan.getFactoryId(), plan.getId());
        boolean hasUnsettled = hasActivity && (
                !findUnsettledPlanConsumptions(plan.getFactoryId(), plan.getId()).isEmpty()
                        || hasUnsettledStockFeedOrOutputRows(plan.getFactoryId(), plan.getId()));
        boolean safetyStock = plan.getSourceType() == PlanSourceType.SAFETY_STOCK;
        boolean stoppableStatus =
                status == ProductionPlanStatus.PENDING || status == ProductionPlanStatus.IN_PROGRESS;
        boolean locked = Boolean.TRUE.equals(plan.getIsLocked());

        dto.setHasProductionActivity(hasActivity);
        dto.setHasUnsettledProduction(hasUnsettled);
        dto.setCanCancel(!hasActivity && !locked);
        dto.setCanStop(stoppableStatus && safetyStock && hasActivity && !hasUnsettled);

        // 未开工才允许「更新到当前配方」。判据与 repinPlanToCurrentAuthority 里的三信号同源:
        // hasRealProductionActivity 已经在判「有没有真的动过生产」, 这里复用它而不是另写一套,
        // 免得菜单说可以点、后端又 409(两处口径打架是本仓最常见的缺陷形态)。
        // ⛔ 与端点走同一个 repinBlockedReason —— 前端只消费下发结果, 不自己推断。
        String repinBlocked = repinBlockedReason(
                plan.getFactoryId(), plan.getId(), locked, () -> hasActivity);
        dto.setCanRepinAuthority(repinBlocked == null);
        dto.setRepinBlockedReason(repinBlocked);

        if (!stoppableStatus) {
            dto.setStopBlockedReason("仅待执行或进行中的计划支持停产");
        } else if (!safetyStock) {
            dto.setStopBlockedReason("仅存货生产计划支持停产");
        } else if (!hasActivity) {
            dto.setStopBlockedReason("尚无正式报工或小结，空计划请取消");
        } else if (hasUnsettled) {
            dto.setStopBlockedReason("仍有未小结的投料、消耗或产出");
        } else {
            dto.setStopBlockedReason(null);
        }

        if (locked && !hasActivity) {
            dto.setNextAction("先解锁，再取消空计划");
        } else if (!hasActivity && (status == ProductionPlanStatus.PREPARED
                || status == ProductionPlanStatus.PLANNED)) {
            dto.setNextAction("确认后进入未完成，或取消空计划");
        } else if (!hasActivity) {
            dto.setNextAction("逐道录入，或取消空计划");
        } else if (hasUnsettled) {
            dto.setNextAction("先完成生产小结");
        } else if (safetyStock) {
            dto.setNextAction("可停产并关闭计划");
        } else {
            dto.setNextAction("核对结单");
        }
    }

    private void enrichWithAssignmentNames(ProductionPlanDTO dto, ProductionPlan plan) {
        if (plan.getSuggestedProductionLineId() != null) {
            productionLineRepository.findById(plan.getSuggestedProductionLineId())
                .ifPresent(line -> dto.setSuggestedProductionLineName(line.getName()));
        }
        if (plan.getAssignedSupervisorId() != null) {
            userRepository.findById(plan.getAssignedSupervisorId())
                .ifPresent(user -> dto.setAssignedSupervisorName(user.getFullName()));
        }
        if (plan.getCancelledBy() != null) {
            userRepository.findById(plan.getCancelledBy())
                    .ifPresent(user -> dto.setCancelledByName(
                            !isBlank(user.getFullName()) ? user.getFullName() : user.getUsername()));
        }
    }

    /**
     * 填充转换率配置状态到DTO
     * 检查该产品类型是否有配置转换率
     */
    private void enrichWithConversionRateInfo(ProductionPlanDTO dto, String factoryId, String productTypeId) {
        if (factoryId == null || productTypeId == null) {
            dto.setConversionRateConfigured(false);
            return;
        }

        // 查询该产品类型的所有转换率配置
        List<MaterialProductConversion> conversions =
            conversionRepository.findByFactoryIdAndProductTypeId(factoryId, productTypeId);

        if (conversions != null && !conversions.isEmpty()) {
            // 有转换率配置
            dto.setConversionRateConfigured(true);

            // 如果只有一个配置，直接返回该转换率和损耗率
            // 如果有多个配置（多种原材料），返回第一个作为示例（前端可以点击查看详情）
            MaterialProductConversion firstConversion = conversions.get(0);
            dto.setConversionRate(firstConversion.getConversionRate());
            dto.setWastageRate(firstConversion.getWastageRate());

            log.debug("产品类型 {} 已配置转换率: {} 个配置", productTypeId, conversions.size());
        } else {
            // 没有转换率配置
            dto.setConversionRateConfigured(false);
            dto.setConversionRate(null);
            dto.setWastageRate(null);

            log.debug("产品类型 {} 未配置转换率", productTypeId);
        }
    }

    /**
     * 将对象转为JSON字符串（用于导入失败记录）
     */
    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /**
     * 将生产计划实体转为导入/导出DTO
     */
    @Override
    @Transactional
    public ProductionBatch createBatchFromPlan(String factoryId, String planId) {
        // R6 (2026-06-14): 悲观写锁取计划, 防 RN 双击/重试并发双建批次。
        // 第二个请求拿锁后看到计划已 IN_PROGRESS → 状态校验抛 409, 幂等拒绝。
        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);

        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }
        // 🔴 原来只允许 PENDING, 于是「开始生产」之后的计划**永远补不了批次** ——
        // 而 workflow 模式的计划没有批次就报不了工, 也结不了单, PC 端彻底死锁:
        //   1. 点「开始生产」→ status=IN_PROGRESS, 但**不建批次**
        //   2. 点「逐道录入」→ 前端只在 PENDING 时自动建批次, 这里被跳过 → 抽屉空白
        //   3. 点「核对结单」→ 409「workflow 计划必须先完成并正式提交逐道报工」
        // 三条路全走不通, 且全是正常 UI 操作。2026-08-01 六膳门 prod 实测撞到。
        //
        // 放开 IN_PROGRESS **但只在还没有批次时** —— 已有批次仍然拦住, 防重复转批次。
        if (plan.getStatus() != ProductionPlanStatus.PENDING) {
            List<ProductionBatch> existingBatches =
                    productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId);
            if (!existingBatches.isEmpty()) {
                // 幂等: 已经转过批次就把既有批次还回去, 不报错 —— 调用方(逐道录入抽屉)
                // 每次打开都会调一次, 报 409 只会变成一个每次都弹的无用提示。
                log.info("计划 {} 已有 {} 个批次, 直接复用 (幂等)", planId, existingBatches.size());
                return existingBatches.get(0);
            }
            // ⛔ 刻意**不**放行「IN_PROGRESS 且没有批次」去补建批次:
            //   R6 并发守卫(ProductionPlanCancelConcurrencyTest)要求「并发第二个 create-batch
            //   看到 IN_PROGRESS 就必须 409, 不许建出第二个批次」。放行会在第一笔事务尚未提交
            //   批次时重新打开这个洞 —— 拿 UX 死锁换并发重复批次是亏的。
            //
            //   根因已在 2026-08-02 收敛: startProduction 现在持锁调用 materializeBatchForPlan,
            //   新请求不会再生成「IN_PROGRESS 且无批次」。这里仍保留 fail-closed 分支, 只接住
            //   V20261029_46 迁移前的存量或库外漂移; 不能自动补建, 否则会重开 R6 双批次并发洞。
            //   删除条件: V20261029_46 已覆盖全部环境, 且连续一个发布周期的审计/遥测确认
            //   IN_PROGRESS+无批次为 0 后, 可删专用提示并折叠为普通状态冲突。
            if (plan.getStatus() == ProductionPlanStatus.IN_PROGRESS) {
                // 「开工了但没有批次」—— 报工与结单都需要批次, 这个计划在 PC 端走不下去了。
                // 说清楚处境比丢一句「只有待处理的计划可以转为批次」有用得多。
                throw new BusinessException(409, "该计划已开工但没有生产批次，无法报工或结单")
                        .withHint("请用 APP 对该计划逐道报工（会自动建批次）；"
                                + "若现场不用 APP，请取消该计划后重新创建并直接点「逐道录入」")
                        .withHintTarget("productionPlan");
            }
            throw new BusinessException(409, "只有待处理的计划可以转为批次")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        return materializeBatchForPlan(factoryId, plan);
    }

    /**
     * 把一个<b>已经取到悲观写锁、已确认可开工</b>的计划物化成生产批次。
     *
     * <p>「开工」在系统里有两个入口 —— {@link #startProduction} 与 {@link #createBatchFromPlan} ——
     * 它们各自做过一半的事: 前者只翻状态不建批次, 后者建批次但不扣 SECONDARY 的 WIP。
     * 于是先点「开工」的计划会卡进「已开工但没有生产批次」, 报工/结单/补建三条路全堵死
     * (prod 实测 7 个)。这个方法是两者收敛后的<b>唯一</b>批次物化实现。
     *
     * <p>⛔ 刻意接收<b>已锁定的 plan 实体</b>而不是 planId: 若让 startProduction 去调
     * {@link #createBatchFromPlan}, 就会在同一事务里第二次 {@code findByIdForUpdate} ——
     * 对同事务是重入(无害), 但多一次 SELECT ... FOR UPDATE 往返, 且会破坏 R6 并发用例里
     * 「取锁至多一次」的断言。锁与状态门留在各自入口, 这里只管物化。
     *
     * @param plan 调用方必须已经完成: 悲观写锁 + 工厂归属校验 + 状态门(PENDING)
     */
    private ProductionBatch materializeBatchForPlan(String factoryId, ProductionPlan plan) {
        String planId = plan.getId();

        BigDecimal batchTargetQuantity = plan.getPlannedQuantity() == null
                ? BigDecimal.ZERO
                : plan.getPlannedQuantity();
        if (batchTargetQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(422, "生产计划数量不能为负数")
                    .withCode("INVALID_PLANNED_QUANTITY")
                    .withHint("请修正生产计划数量后重试");
        }
        if (plan.getSourceType() != PlanSourceType.SAFETY_STOCK
                && batchTargetQuantity.compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException(422, "非库存生产计划必须填写大于 0 的计划数量")
                    .withCode("PLANNED_QUANTITY_REQUIRED")
                    .withHint("库存生产可按实际报工；其他来源请先补充计划数量");
        }

        // Plans pin an exact workflow/version. Activation may move after plan creation;
        // conversion validates the pin itself and never silently resolves a newer graph.
        if (workflowResolutionService != null
                && plan.getWorkflowSelectionMode() == ProductionBatch.WorkflowSelectionMode.WORKFLOW) {
            List<String> selectedOutputs = plan.getTargetFinishedGoodIds() == null
                    || plan.getTargetFinishedGoodIds().isEmpty()
                    ? List.of(plan.getProductTypeId())
                    : plan.getTargetFinishedGoodIds();
            workflowResolutionService.assertPinnedWorkflowCoversOutputs(
                    factoryId, plan.getSelectedWorkflowId(), plan.getSelectedWorkflowVersion(), selectedOutputs);
        }

        // N1 (2026-06-12): 转批次=开工不再受原料库存预检阻断; 缺料只记录预警。
        runConfiguredValidation(factoryId, "START", java.util.Map.of("planId", planId));
        validateMaterialStockSufficient(factoryId, plan);

        // 查产品名称
        String productName = "未知产品";
        Optional<ProductType> ptOpt = productTypeRepository.findByIdAndFactoryId(
                plan.getProductTypeId(), factoryId);
        if (ptOpt.isPresent()) {
            productName = ptOpt.get().getName();
        }

        // 生成批次号
        String batchNumber = "PB-" + plan.getPlanNumber() + "-" + System.currentTimeMillis() % 100000;
        if (productionBatchRepository.existsByFactoryIdAndBatchNumber(factoryId, batchNumber)) {
            batchNumber = batchNumber + "-" + (int)(Math.random() * 1000);
        }

        ProductionBatch batch = ProductionBatch.builder()
                .factoryId(factoryId)
                .batchNumber(batchNumber)
                .productionPlanId(plan.getId())
                .productTypeId(plan.getProductTypeId())
                .productName(productName)
                .plannedQuantity(batchTargetQuantity)
                .quantity(batchTargetQuantity)
                .unit(requireProductionUnit(plan.getPlannedUnit(), "生产计划 " + plan.getPlanNumber()))
                .workflowSelectionMode(plan.getWorkflowSelectionMode())
                .selectedWorkflowId(plan.getSelectedWorkflowId())
                .selectedWorkflowVersion(plan.getSelectedWorkflowVersion())
                // GAP 3/4 (F006): 转批次=开始生产, 批次直接 IN_PROGRESS + 设 startTime,
                // 使逐道报工 YieldBatchSelect (筛 status=IN_PROGRESS) 立刻可见.
                .status(ProductionBatchStatus.IN_PROGRESS)
                .startTime(LocalDateTime.now())
                .workerCount(plan.getEstimatedWorkers())
                .notes("从计划 " + plan.getPlanNumber() + " 创建")
                .createdBy(plan.getCreatedBy())
                .build();

        // 映射产线建议
        if (plan.getSuggestedProductionLineId() != null) {
            productionLineRepository.findById(plan.getSuggestedProductionLineId()).ifPresent(line -> {
                batch.setEquipmentName(line.getName());
            });
        }

        // 映射指派主管
        if (plan.getAssignedSupervisorId() != null) {
            batch.setSupervisorId(plan.getAssignedSupervisorId());
            userRepository.findById(plan.getAssignedSupervisorId()).ifPresent(user -> {
                batch.setSupervisorName(user.getFullName());
            });
        }

        ProductionBatch saved = productionBatchRepository.save(batch);

        // GAP 3/4 (F006): 转批次时 spawn 报工任务 (报工需要任务实例).
        //   计划级免工序报工 (六扇门 Wave2, V20261017_01): plan.skipProcessReporting=true 或 产品 0 工序
        //   → spawn 2 个批次级哨兵任务 (领料+产出); 否则逐道从 product_work_processes 模板 spawn。
        //   头尾责任人 = 计划的 assignedSupervisorId, 头尾同一人 (一人兼)。
        //   Fable 审计修复 (问题3 — 撤回 claim): 计划当前无独立的 materialResponsibleId / outputResponsibleId
        //   字段, 也无 spawn 后分设头尾责任人的代码。"web/RN 可 updatePlan 分设头尾责任人"不成立 (已删除该 claim)。
        //   头尾分设是后续 (P2): 需 plan 加两字段 + web 头尾责任人选择 UI; 一人兼对六扇门首期够用。
        //   任务级粒度: 操作员可在 WorkProcessTask.start 时自分配 / 主管经 WorkProcessTask.updatePlan 改单个任务的 assignedTo。
        // Workflow selection is pinned at batch insert. Spawn must remain in this transaction and
        // fail closed so a partially created batch can never retry under a newer activation.
        if (workProcessTaskService == null) {
            throw new BusinessException(500, "工序任务服务未初始化，批次创建已回滚")
                    .withCode("WORK_PROCESS_TASK_SERVICE_UNAVAILABLE")
                    .withHint("请检查后端服务配置后重试创建批次");
        }
        Long responsibleId = plan.getAssignedSupervisorId();
        List<WorkProcessTaskDTO> spawnedTasks =
                workProcessTaskService.spawnTasks(factoryId, saved.getId(), saved.getProductTypeId(),
                        plan.getSkipProcessReporting(), responsibleId, responsibleId);
        log.info("转批次已 spawn 报工任务: batchId={}, productTypeId={}, skipProcessReporting={}, taskCount={}",
                saved.getId(), saved.getProductTypeId(), plan.getSkipProcessReporting(), spawnedTasks.size());

        // 更新计划状态为 IN_PROGRESS
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setStartTime(LocalDateTime.now());
        productionPlanRepository.save(plan);

        log.info("从计划创建批次: planId={}, batchId={}, batchNumber={}", planId, saved.getId(), saved.getBatchNumber());
        return saved;
    }

    private static String requireProductionUnit(String unit, String context) {
        if (unit == null || unit.isBlank()) {
            throw new BusinessException(422, context + " 缺少单位，不能生成生产任务")
                    .withCode("PRODUCTION_UNIT_NOT_CONFIGURED")
                    .withHint("请修复产品、Workflow 或工序的单位配置后重试");
        }
        return unit.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private ProductionPlanImportDTO toImportDTO(ProductionPlan plan) {
        ProductionPlanImportDTO dto = new ProductionPlanImportDTO();
        // Resolve product name
        if (plan.getProductType() != null) {
            dto.setProductName(plan.getProductType().getName());
        } else {
            productTypeRepository.findByIdAndFactoryId(
                            plan.getProductTypeId(), plan.getFactoryId())
                    .ifPresent(pt -> dto.setProductName(pt.getName()));
        }
        dto.setPlannedQuantity(plan.getPlannedQuantity());
        dto.setExpectedCompletionDate(plan.getExpectedCompletionDate());
        dto.setPriority(plan.getPriority());
        dto.setEstimatedWorkers(plan.getEstimatedWorkers());
        dto.setCustomerOrderNumber(plan.getCustomerOrderNumber());
        dto.setNotes(plan.getNotes());
        // Resolve production line code
        if (plan.getSuggestedProductionLineId() != null) {
            productionLineRepository.findById(plan.getSuggestedProductionLineId())
                    .ifPresent(line -> dto.setProductionLineCode(line.getLineCode()));
        }
        // Resolve supervisor username
        if (plan.getAssignedSupervisorId() != null) {
            userRepository.findById(plan.getAssignedSupervisorId())
                    .ifPresent(user -> dto.setSupervisorUsername(user.getUsername()));
        }
        return dto;
    }

    // -------------------------------------------------------------------------
    // SP2 二次加工
    // -------------------------------------------------------------------------

    /**
     * SP2 二次加工: 基于 WIP 半成品创建二次加工计划。
     *
     * <p>此方法只创建计划 (PENDING), 不扣减 WIP 库存。
     * WIP 扣减在 {@link #startProduction} 时执行 (fail-closed 事务内)。
     *
     * @since SP2 (2026-06-10, feat/liushanmen-sp2-reversal)
     */
    @Override
    @Transactional
    public ProductionPlanDTO createSecondaryPlan(
            String factoryId, Long wipId, java.math.BigDecimal quantity,
            String productTypeId, java.time.LocalDate plannedDate, Long submittedBy) {

        if (wipInventoryService == null) {
            throw new BusinessException(500, "二次加工服务未初始化");
        }

        // 1. 校验 WIP 存在且余量充足 (查询不扣减)
        var availableList = wipInventoryService.listAvailableWip(factoryId);
        com.cretas.aims.entity.SemiFinishedInventory wip = availableList.stream()
                .filter(w -> w.getId().equals(wipId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("半成品库存", "id", wipId));

        if (quantity == null || quantity.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "计划加工数量必须大于 0");
        }
        if (wip.getAvailableQuantity() == null ||
                quantity.compareTo(wip.getAvailableQuantity()) > 0) {
            throw new BusinessException(409, String.format(
                    "半成品可用量不足: 需要 %s, 可用 %s",
                    quantity.stripTrailingZeros().toPlainString(),
                    wip.getAvailableQuantity() != null
                            ? wip.getAvailableQuantity().stripTrailingZeros().toPlainString()
                            : "0"));
        }

        // 2. 校验目标产品类型存在
        com.cretas.aims.entity.ProductType productType =
                productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("产品类型", "id", productTypeId));

        // 3. 生成计划编号
        String planNumber = "SEC-" + factoryId + "-"
                + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").format(
                        plannedDate != null ? plannedDate : java.time.LocalDate.now())
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        // 4. 构建并保存计划
        ProductionPlan plan = new ProductionPlan();
        // BUG-GOLD-RERUN-SECONDARY-PLAN-500: ProductionPlan @Id 是手动赋值 String, 漏 setId →
        //   persist 抛 IdentifierGenerationException → 500。与常规 createPlan(line 878) 同模式补 UUID。
        plan.setId(java.util.UUID.randomUUID().toString());
        plan.setFactoryId(factoryId);
        plan.setPlanNumber(planNumber);
        plan.setProductTypeId(productTypeId);
        plan.setPlannedQuantity(quantity);
        applyPlanUnitAuthority(plan, resolvePlanUnitAuthority(factoryId, productTypeId, null));
        plan.setPlannedDate(plannedDate != null ? plannedDate : java.time.LocalDate.now());
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setCreatedBy(submittedBy);
        // SP2 特有字段
        plan.setPlanSourceType("SECONDARY");
        plan.setSecondarySourceWipId(wipId);

        // Gap A (2026-06-12 多段链): 回填源订单 — secondary plan 从 WIP 派生, 追溯 wip→batch→origin plan→sourceOrder。
        //   否则 /multi-stage-cost 按 source_order_id 查计划时查不到 semi B 段 (stageCount 只见首段 normal plan)。
        //   fail-soft: 追溯失败不阻塞建计划 (二次加工本身可独立于订单)。
        try {
            if (wip.getBatchId() != null) {
                com.cretas.aims.entity.ProductionBatch srcBatch =
                        productionBatchRepository.findById(wip.getBatchId()).orElse(null);
                if (srcBatch != null && srcBatch.getProductionPlanId() != null) {
                    ProductionPlan originPlan =
                            productionPlanRepository.findById(srcBatch.getProductionPlanId()).orElse(null);
                    if (originPlan != null) {
                        plan.setSourceOrderId(originPlan.getSourceOrderId());
                        if (originPlan.getSourceOrderIds() != null && !originPlan.getSourceOrderIds().isEmpty()) {
                            plan.setSourceOrderIds(new java.util.ArrayList<>(originPlan.getSourceOrderIds()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SP2 createSecondaryPlan 源订单回填失败 (fail-soft, 不阻塞建计划): wipId={} err={}",
                    wipId, e.getMessage());
        }

        plan = productionPlanRepository.save(plan);

        log.info("SP2 创建二次加工计划: planId={}, wipId={}, quantity={}, factoryId={}",
                plan.getId(), wipId, quantity, factoryId);

        return toDTOWithConversionInfo(plan);
    }

    /**
     * SP5 双向检索: 供 controller 将已加载 entity 转为 DTO，避免 controller 直接调用 private helper.
     */
    @Override
    public ProductionPlanDTO toPlanDTO(com.cretas.aims.entity.ProductionPlan plan) {
        return toDTOWithConversionInfo(plan);
    }

    /**
     * G3b 停产 — BY_STOCK 库存永续计划纯状态关闭。
     *
     * <p><b>⚠️ 关键约束</b>: 此方法仅翻转状态 (→ COMPLETED) 并保存, 绝不:
     * <ul>
     *   <li>调用 completeProduction 或 settleProduction</li>
     *   <li>发布 BatchCompletedEvent 或 ProductionCompletedEvent</li>
     *   <li>触发任何物料扣减或成品创建</li>
     * </ul>
     * 小结 (interimSettle) 已逐批扣减原料 + 分批入库; 停产若再触发扣减会造成双重扣减。
     */
    @Override
    @Transactional
    public void stopProduction(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(planId)
                .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        requireVisiblePlan(factoryId, plan);
        if (plan.getSourceType() != PlanSourceType.SAFETY_STOCK) {
            throw new BusinessException(400, "仅存货生产计划可停产");
        }
        // 🔴 (2026-07-06) plan-status 守卫: 缺此守卫时对已 COMPLETED/CANCELLED 等终态计划重复调用本接口,
        //   状态置 COMPLETED 是 no-op, 但下方无条件 plan.setEndTime(now()) 仍会执行 → 覆盖原完工时间戳
        //   (审计腐蚀, 喂生产时长/人效报表); 双击或 API replay 即可复现。允许集 PENDING/IN_PROGRESS 镜像
        //   前端 web-admin/src/views/production/plans/list.vue isUnfinishedStatus() 白名单 (停产按钮
        //   仅在这两个状态下渲染) —— PENDING 允许是因为 interimSettle 从不要求 IN_PROGRESS, 存货生产计划
        //   可能全程停留 PENDING (从未 startProduction) 就直接小结+停产关闭。
        if (plan.getStatus() == ProductionPlanStatus.COMPLETED) {
            log.info("停产幂等命中: factoryId={}, planId={}, endTime={}", factoryId, planId, plan.getEndTime());
            return;
        }
        if (plan.getStatus() != ProductionPlanStatus.PENDING
                && plan.getStatus() != ProductionPlanStatus.IN_PROGRESS) {
            throw new BusinessException(409, "只能停产待处理或进行中的生产计划")
                    .withHint("当前状态: " + plan.getStatus().getDisplayName() + ", 请刷新生产计划列表查看最新状态");
        }
        if (!hasRealProductionActivity(factoryId, planId)) {
            throw new BusinessException(409, "该计划尚无正式报工或小结，不能停产")
                    .withCode("PLAN_HAS_NO_PRODUCTION_ACTIVITY")
                    .withHint("空计划请使用「取消计划」；产生正式报工并完成小结后才能停产")
                    .withSeverity("BLOCKING")
                    .withHintTarget("取消计划");
        }
        // 🔒🔒 (2026-07-04) 幻库存守卫: 停产是纯状态翻转 (→ COMPLETED, 零扣减)。延迟扣减设计下, 报工写的
        //   MaterialConsumption 行恒 interimSettledAt IS NULL, 仅在「小结」时才逐笔扣减 usedQuantity。若计划
        //   尚有未结报工消耗却直接停产 → 状态变 COMPLETED → 小结守卫 (interimSettle 拒绝终态) 从此拦截 →
        //   这些原料永远不被扣减 = 幻库存 (物料实际消耗但库存从不减)。故停产前必须拦截: 有未结报工消耗时
        //   loud-block, 导向"先小结再停产"。检测口径与 interimSettle 完全一致 (按 process_sheet_rows.batch_id
        //   ∈ 本计划各道 → material_consumptions.production_batch_id, interim_settled_at IS NULL), 因逐工序
        //   在制道消耗 production_plan_id 故意为 null, 只能靠 batchId 定位。
        List<MaterialConsumption> unsettled = findUnsettledPlanConsumptions(factoryId, planId);
        // 🔴🔒🔒 (2026-07-05) 第二检测面 — SFI/FG 中段起步料流盲区: 上方消耗检测只覆盖写了
        //   MaterialConsumption 且 process_sheet_rows.batch_id 非空的行。但「中段起步」计划纯由外部
        //   常驻半成品(SFI)/成品(FG)投料喂 (row.upstreamSources semiFinished/finishedGoods), 这些投料边
        //   <b>不写</b> MaterialConsumption; 且纯 SFI 中间道 (SAVED_SFI) batchId 为 null 被上方 filter 排除 →
        //   全程零消耗 → 消耗守卫放行 → 停产 COMPLETED → 小结被终态守卫拦死 → 已投 SFI/FG 永不扣减 = 幻库存
        //   (可重复发货/领用)。故并联检测「本计划有未结产出行 或 未结 SFI/FG 投料行」(见 helper), 任一命中即拦。
        boolean unsettledStockFeed = hasUnsettledStockFeedOrOutputRows(factoryId, planId);
        if (!unsettled.isEmpty() || unsettledStockFeed) {
            String detail = !unsettled.isEmpty()
                    ? ("该计划仍有 " + unsettled.size() + " 笔未结报工消耗")
                    : "该计划仍有未小结的半成品/成品投料或产出";
            throw new BusinessException(409, detail + ", 请先小结再停产")
                    .withCode("STOP_BLOCKED_UNSETTLED_CONSUMPTION")
                    .withHint("停产不扣料; 若此时停产, 已报工消耗的原料 / 已投入的半成品(SFI)/成品(FG) 将永不扣减 "
                            + "(幻库存)。请先「小结」结算这些消耗与投料")
                    .withSeverity("BLOCKING")
                    .withHintTarget("小结");
        }
        plan.setStatus(ProductionPlanStatus.COMPLETED);
        plan.setEndTime(LocalDateTime.now());
        productionPlanRepository.save(plan);
        if (productionInterimSettlementRepository == null) {
            throw new BusinessException(500, "存货生产小结服务未初始化, 无法建立仓库确认桥接")
                    .withCode("BY_STOCK_SETTLEMENT_BRIDGE_UNAVAILABLE")
                    .withHintTarget("停产");
        }
        List<ProductionInterimSettlement> completedSessions = productionInterimSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqAsc(factoryId, planId);
        if (!isEmpty(completedSessions)) {
            // 小结已经完成全部库存动作；停产只额外创建仓库确认所需的元数据，绝不重放小结。
            bridgeProductionSettlementForWarehouse(factoryId, planId);
        }
        log.info("停产 (存货生产纯状态关闭, 无扣料无事件): factoryId={}, planId={}", factoryId, planId);
        // NO completeProduction, NO settleProduction, NO BatchCompletedEvent, NO consumption posting.
    }

    /**
     * 自动排产产生的空批次不算生产活动。只有正式报工行、报工产出或已经过账的小结会话
     * 才能支撑“停产→完成”，避免空计划被伪造成已生产。
     */
    private boolean hasRealProductionActivity(String factoryId, String planId) {
        if (materialConsumptionRepository != null) {
            List<MaterialConsumption> directConsumptions =
                    materialConsumptionRepository.findByProductionPlanId(planId);
            if (directConsumptions != null && directConsumptions.stream()
                    .anyMatch(consumption -> Objects.equals(factoryId, consumption.getFactoryId()))) {
                return true;
            }
        }
        if (processSheetRowRepository != null) {
            List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId);
            if (rows != null && rows.stream().anyMatch(row ->
                    ProcessSheetRow.SUBMISSION_SUBMITTED.equals(row.getSubmissionStatus()))) {
                return true;
            }
        }
        if (productionInterimSettlementRepository != null) {
            List<ProductionInterimSettlement> sessions = productionInterimSettlementRepository
                    .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqAsc(factoryId, planId);
            if (sessions != null && !sessions.isEmpty()) {
                return true;
            }
        }
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId).orElse(null);
        return plan != null && hasYieldReports(factoryId, plan);
    }

    /**
     * 🔒🔒 (2026-07-04) 查本计划待结算 (interimSettledAt IS NULL) 的报工消耗。
     *
     * <p>检测口径与 {@code InterimSettleServiceImpl.interimSettle} 的扣减侧完全一致: 按
     * {@code process_sheet_rows.batch_id} (物化后的 per-道 ProductionBatch.id) ∈ 本计划各道, 反查
     * {@code material_consumptions.production_batch_id + interim_settled_at IS NULL}。不能按
     * production_plan_id 查 —— 逐工序首/中间道写的 raw 消耗其 production_plan_id 故意为 null (防成本双计),
     * 只有 production_batch_id 恒有值。
     *
     * <p>{@code processSheetRowRepository} 为 {@code @Autowired(required=false)}, 生产环境恒存在;
     * 若缺失 (某些精简测试上下文) 则返回空集 (与既有 helper line ~1785/~3184 的 null-guard 同策略)。
     */
    private List<MaterialConsumption> findUnsettledPlanConsumptions(String factoryId, String planId) {
        if (processSheetRowRepository == null || isBlank(planId)) {
            return List.of();
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId);
        List<Long> planBatchIds = rows.stream()
                .map(ProcessSheetRow::getBatchId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (planBatchIds.isEmpty()) {
            return List.of();
        }
        return materialConsumptionRepository
                .findByFactoryIdAndProductionBatchIdInAndInterimSettledAtIsNull(factoryId, planBatchIds);
    }

    /**
     * 🔴🔒🔒 (2026-07-05) 停产守卫第二检测面 —— 本计划是否存在<b>未结</b> (interim_settled_at IS NULL) 且
     * 「有产出锚 或 含 SFI/FG 投料」的逐工序行。
     *
     * <p>补 {@link #findUnsettledPlanConsumptions} 的两处盲区: (a) 纯 SFI 中间道 (SAVED_SFI) {@code batchId}
     * 为 null 被消耗守卫的 {@code batchId != null} filter 排除; (b) SFI/FG 投料边根本不写 MaterialConsumption。
     * 「中段起步」计划全程零消耗但有真实待扣 SFI/FG 投料, 只有靠 process_sheet_rows 才检测得到。
     *
     * <p>判据: 行 {@code interim_settled_at IS NULL} 且 (i) {@code batchNumber != null} (有产出/SFI 锚 —— 含
     * SAVED_SFI, 其 batchId null 但 batchNumber 非空) 或 (ii) upstreamSources 含 {@code semiFinished/finishedGoods}
     * 且 feed>0 (边缘: 无产出锚但确有外部投料)。命中即代表小结会处理该行 (扣 SFI/FG / 入库产出), 停产会把它
     * 遗留成幻库存。processSheetRowRepository @Autowired(required=false), 缺失则返回 false (与既有 null-guard 同策略)。
     */
    private boolean hasUnsettledStockFeedOrOutputRows(String factoryId, String planId) {
        if (processSheetRowRepository == null || isBlank(planId)) {
            return false;
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId);
        List<ProductionSettlementPrefillResponse.Issue> ignore = new ArrayList<>();
        for (ProcessSheetRow row : rows) {
            if (row == null || row.getInterimSettledAt() != null) {
                continue;   // 已结: 小结/结单已处理该行, 停产不会遗留
            }
            // (i) 有产出/SFI 锚 (含 SAVED_SFI: batchId null 但 batchNumber 非空 → 消耗守卫盲区)
            if (trimToNull(row.getBatchNumber()) != null) {
                return true;
            }
            // (ii) 无产出锚但含外部 SFI/FG 投料 (边缘)
            ProcessSheetRowRequest req = parseProcessSheetRowPayload(row, ignore);
            if (req == null || isEmpty(req.getUpstreamSources())) {
                continue;
            }
            for (ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
                if ((ref.isSemiFinished() || ref.isFinishedGoods())
                        && zeroIfNull(ref.getFeedQuantityKg()).compareTo(BigDecimal.ZERO) > 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
