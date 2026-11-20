package com.cretas.aims.service.shortage;

import com.cretas.aims.domain.OrderUsageWhitelists;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.ShortageScheduleQueryRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.scheduling.daily.CapacityUnits;
import com.cretas.aims.service.scheduling.daily.ExperienceCapacity;
import com.cretas.aims.service.scheduling.daily.ExperienceCapacityService;
import com.cretas.aims.service.shortage.DeliveryRiskEvaluator.Facts;
import com.cretas.aims.service.shortage.DeliveryRiskEvaluator.Level;
import com.cretas.aims.service.shortage.DeliveryRiskEvaluator.MaterialGap;
import com.cretas.aims.service.shortage.DeliveryRiskEvaluator.MaterialStatus;
import com.cretas.aims.service.shortage.DeliveryRiskEvaluator.PlanFact;
import com.cretas.aims.service.shortage.DeliveryRiskEvaluator.Result;
import com.cretas.aims.service.shortage.dto.ShortageReport;
import com.cretas.aims.service.unit.UnitDisplayNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 交付风险: 读订单 / 计划 / 入库进度 / 工序 / 排程 / 物料, 交给 {@link DeliveryRiskEvaluator} 判定,
 * 有风险的订单落成处理实例 ({@link DeliveryRiskCaseStore})。只生成任务和记录结果, 不改排程、不下采购。
 *
 * <p>这里不开事务: 缺料分析在没有配方时抛业务异常, 算「物料未知」; 放在同一事务里会把整轮扫描标成回滚。
 * 已完成量用已入库量, 比报工滞后, 判定上偏保守。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryRiskService {

    static final int SCAN_LIMIT = 500;
    private static final Set<ProductionPlanStatus> COUNTED_PLANS = EnumSet.of(ProductionPlanStatus.PLANNED,
            ProductionPlanStatus.PENDING, ProductionPlanStatus.IN_PROGRESS, ProductionPlanStatus.PAUSED,
            ProductionPlanStatus.COMPLETED);

    private final SalesOrderRepository orderRepository;
    private final SalesOrderItemRepository itemRepository;
    private final ProductionPlanRepository planRepository;
    private final ProductTypeRepository productTypeRepository;
    private final FinishedGoodsBatchRepository finishedGoodsRepository;
    private final ProductionBatchRepository batchRepository;
    private final WorkProcessTaskRepository taskRepository;
    private final ShortageScheduleQueryRepository scheduleQuery;
    private final ShortageAnalysisService shortageAnalysis;
    private final ExperienceCapacityService experienceCapacity;
    private final DeliveryRiskCaseStore store;

    public record SignalView(String code, String label) {
    }

    /** 一张订单的评估; 存进实例详情, 也是扫描结果的一行。 */
    public record OrderAssessment(String salesOrderId, String orderNumber, String customerName, String orderStatusLabel,
                                  LocalDate dueDate, String level, List<SignalView> signals, List<String> evidence,
                                  List<String> unknowns, List<String> impact, List<String> suggestions,
                                  Integer estimatedDaysNeeded, Long daysLeft, int materialGapCount,
                                  LocalDateTime latestPlannedEnd, int lateSchedules, LocalDateTime assessedAt) {
    }

    public record ScanResult(int scanned, boolean truncated, int risk, int unknown, int ok, int created, int refreshed,
                             List<OrderAssessment> orders) {
    }

    public ScanResult scan(String factoryId, ActorRef actor) {
        List<SalesOrder> orders = orderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(factoryId,
                OrderUsageWhitelists.SO_PLANNABLE, PageRequest.of(0, SCAN_LIMIT));
        List<OrderAssessment> out = new ArrayList<>();
        int created = 0;
        int refreshed = 0;
        for (SalesOrder o : orders) {
            OrderAssessment a = assess(factoryId, o);
            out.add(a);
            switch (store.record(factoryId, a, fingerprint(a), actor)) {
                case CREATED -> created++;
                case REFRESHED -> refreshed++;
                case NONE -> { }
            }
        }
        return new ScanResult(orders.size(), orders.size() == SCAN_LIMIT, count(out, Level.RISK), count(out, Level.UNKNOWN),
                count(out, Level.OK), created, refreshed, out);
    }

    /**
     * 只评估指定订单并登记 (紧急插单把排程挤后之后用): 有风险开 / 刷新处理实例, 没风险不动。
     * 不开事务, 与 {@link #scan} 同理 (缺料分析可能抛业务异常)。
     *
     * @return 评估并写过结果的订单数 (本厂不存在的订单 id 跳过)
     */
    public int refreshOrders(String factoryId, Collection<String> orderIds, ActorRef actor) {
        int done = 0;
        for (String id : orderIds) {
            SalesOrder o = orderRepository.findByIdAndFactoryId(id, factoryId).orElse(null);
            if (o == null) {
                continue;
            }
            try {
                OrderAssessment a = assess(factoryId, o);
                store.record(factoryId, a, fingerprint(a), actor);
                done++;
            } catch (RuntimeException e) {
                // 一个订单评估失败不能让后面的订单都不登记
                log.error("交付风险: 订单 {} 登记失败: {}", id, e.getMessage(), e);
            }
        }
        return done;
    }

    /** 按当前数据复核一条实例, 结论写回。 */
    public OrderAssessment recheck(String factoryId, String caseId, ActorRef actor) {
        OrderAssessment a = assessCase(factoryId, caseId);
        store.recordCheck(factoryId, caseId, a, fingerprint(a), actor);
        return a;
    }

    /** 关闭 / 取消前先评估一次, 让处理结果旁边有当时的事实。 */
    public OrderAssessment assessCase(String factoryId, String caseId) {
        ShortageCase c = store.own(factoryId, caseId);
        SalesOrder o = orderRepository.findByIdAndFactoryId(c.getSourceId(), factoryId)
                .orElseThrow(() -> new BusinessException(404, "实例关联的销售订单不存在"));
        return assess(factoryId, o);
    }

    OrderAssessment assess(String factoryId, SalesOrder o) {
        List<SalesOrderItem> items = itemRepository.findBySalesOrderIdAndSalesOrderFactoryId(o.getId(), factoryId);
        boolean open = OrderUsageWhitelists.SO_PLANNABLE.contains(o.getStatus());
        boolean pending = open && (items.isEmpty() || items.stream().anyMatch(i -> i.getPendingQuantity().signum() > 0));

        List<ProductionPlan> plans = plans(factoryId, o.getId());
        LocalDate today = LocalDate.now();
        LocalDate due = o.getRequiredDeliveryDate();
        List<PlanFact> planFacts = plans.stream().map(p -> planFact(factoryId, p, today)).toList();

        List<LineSchedule> live = scheduleQuery.findLiveByPlanIds(factoryId, plans.stream()
                .filter(p -> p.getStatus() != ProductionPlanStatus.COMPLETED).map(ProductionPlan::getId).toList());
        LocalDateTime latestEnd = live.stream().map(LineSchedule::getPlannedEndTime).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        int undated = (int) live.stream().filter(ls -> ls.getPlannedEndTime() == null).count();
        int late = due == null ? 0 : (int) live.stream().filter(ls -> ls.getPlannedEndTime() != null
                && ls.getPlannedEndTime().toLocalDate().isAfter(due)).count();

        MaterialView m = pending ? material(factoryId, o.getId()) : new MaterialView(MaterialStatus.CLEAR, null, List.of());
        Result r = DeliveryRiskEvaluator.evaluate(new Facts(today, due, pending, planFacts, live.size(), undated, latestEnd,
                late, m.status(), m.note(), m.gaps()));

        List<String> impact = new ArrayList<>();
        if (o.getCustomerName() != null) {
            impact.add("客户: " + o.getCustomerName());
        }
        for (SalesOrderItem i : items) {
            if (i.getPendingQuantity().signum() > 0) {
                impact.add((i.getProductName() != null ? i.getProductName() : i.getProductTypeId()) + " 待交 "
                        + i.getPendingQuantity().stripTrailingZeros().toPlainString() + " "
                        + (i.getUnit() == null ? "(单位未知)" : UnitDisplayNames.display(i.getUnit())));
            }
        }
        return new OrderAssessment(o.getId(), o.getOrderNumber(), o.getCustomerName(),
                o.getStatus() == null ? null : o.getStatus().getDisplayName(), due, r.level().name(),
                r.signals().stream().map(s -> new SignalView(s.name(), s.label())).toList(), r.evidence(), r.unknowns(),
                impact, r.suggestions(), r.estimatedDaysNeeded(), r.daysLeft(), m.gaps().size(), latestEnd, late,
                LocalDateTime.now());
    }

    private List<ProductionPlan> plans(String factoryId, String orderId) {
        Map<String, ProductionPlan> byId = new LinkedHashMap<>();
        planRepository.findByFactoryIdAndSourceOrderIdExact(factoryId, orderId).forEach(p -> byId.put(p.getId(), p));
        planRepository.findByFactoryIdAndSourceOrderIdsContaining(factoryId, "[\"" + orderId + "\"]")
                .forEach(p -> byId.put(p.getId(), p));
        return byId.values().stream().filter(p -> COUNTED_PLANS.contains(p.getStatus())).toList();
    }

    private PlanFact planFact(String factoryId, ProductionPlan p, LocalDate today) {
        boolean joint = p.getTargetFinishedGoodIds() != null && !p.getTargetFinishedGoodIds().isEmpty();
        Set<String> targets = joint ? new HashSet<>(p.getTargetFinishedGoodIds()) : Set.of(String.valueOf(p.getProductTypeId()));
        String unit = p.getPlannedUnit();
        BigDecimal done = BigDecimal.ZERO;
        for (Object[] row : finishedGoodsRepository.sumProductionOutputByPlan(factoryId, p.getId())) {
            if (done == null || !targets.contains(String.valueOf(row[0]))) {
                continue;
            }
            Optional<BigDecimal> q = CapacityUnits.convert((BigDecimal) row[2], (String) row[1], unit);
            done = q.isPresent() ? done.add(q.get()) : null; // 单位换不了: 已完成量未知, 不当 0
        }
        BigDecimal remaining = null;
        if (p.getStatus() == ProductionPlanStatus.COMPLETED) {
            remaining = BigDecimal.ZERO;
        } else if (done != null && p.getPlannedQuantity() != null) {
            remaining = p.getPlannedQuantity().subtract(done).max(BigDecimal.ZERO);
        }
        BigDecimal perDay = null;
        String source = null;
        if (!joint && p.getProductTypeId() != null) { // 联产计划的产品是原料锚点, 它的日产能不能直接比
            var product = productTypeRepository.findByIdAndFactoryId(p.getProductTypeId(), factoryId).orElse(null);
            if (product != null && product.getDailyCapacity() != null && product.getDailyCapacity().signum() > 0) {
                perDay = CapacityUnits.convert(product.getDailyCapacity(), product.getDailyCapacityUnit(), unit)
                        .filter(v -> v.signum() > 0).orElse(null);
                source = perDay == null ? null : "人工填写";
            }
            if (perDay == null) {
                ExperienceCapacity e = experienceCapacity.experience(factoryId, p.getProductTypeId(), unit, today);
                if (e.available()) {
                    perDay = e.perDay();
                    source = "历史估算";
                }
            }
        }
        int total = 0;
        int left = 0;
        for (var b : batchRepository.findByFactoryIdAndProductionPlanId(factoryId, p.getId())) {
            for (WorkProcessTask t : taskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, b.getId())) {
                total++;
                if (t.getStatus() == WorkProcessTask.Status.PENDING || t.getStatus() == WorkProcessTask.Status.IN_PROGRESS) {
                    left++;
                }
            }
        }
        return new PlanFact(p.getId(), p.getPlanNumber(), unit == null ? null : UnitDisplayNames.display(unit),
                p.getPlannedQuantity(), done, remaining, perDay, source, total == 0 ? null : left, total == 0 ? null : total);
    }

    private record MaterialView(MaterialStatus status, String note, List<MaterialGap> gaps) {
    }

    private MaterialView material(String factoryId, String orderId) {
        ShortageReport report;
        try {
            report = shortageAnalysis.analyzeForSalesOrder(factoryId, orderId);
        } catch (RuntimeException e) {
            log.info("交付风险: 订单 {} 缺料分析不可用: {}", orderId, e.getMessage());
            return new MaterialView(MaterialStatus.UNKNOWN, "物料状态算不出: " + e.getMessage(), List.of());
        }
        if (report.isFullySatisfied()) {
            return new MaterialView(MaterialStatus.CLEAR, null, List.of());
        }
        if (report.getMaterialShortages() == null) {
            return new MaterialView(MaterialStatus.UNKNOWN, "缺料分析没有给出物料明细", List.of());
        }
        if (report.getMaterialShortages().isEmpty()) {
            return new MaterialView(MaterialStatus.CLEAR, null, List.of());
        }
        List<MaterialGap> gaps = new ArrayList<>();
        for (var s : report.getMaterialShortages()) {
            Object[] row = scheduleQuery.findLiveOrderArrival(factoryId, s.getMaterialTypeId()).stream().findFirst().orElse(null);
            long orders = row == null || row[2] == null ? 0 : ((Number) row[2]).longValue();
            long undated = row == null || row[1] == null ? 0 : ((Number) row[1]).longValue();
            LocalDate latest = row == null || undated > 0 ? null : (LocalDate) row[0];
            gaps.add(new MaterialGap(s.getMaterialTypeName() != null ? s.getMaterialTypeName() : s.getMaterialTypeId(),
                    orders > 0, latest));
        }
        return new MaterialView(MaterialStatus.GAP, null, gaps);
    }

    private static int count(List<OrderAssessment> rows, Level level) {
        return (int) rows.stream().filter(a -> level.name().equals(a.level())).count();
    }

    /** 交期 + 风险信号: 这两样没变, 已结束的实例就不因重新扫描再开一条。 */
    static String fingerprint(OrderAssessment a) {
        String raw = a.dueDate() + "|" + a.level() + "|" + a.signals().stream().map(SignalView::code).sorted().toList();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
