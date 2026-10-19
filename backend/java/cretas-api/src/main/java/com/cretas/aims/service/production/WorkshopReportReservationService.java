package com.cretas.aims.service.production;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 生产仓「报工占用 → 释放」的<b>唯一口径</b>。
 *
 * <h3>它解决什么</h3>
 *
 * <p>App 报工提交后, 那批料在生产仓里既没被扣掉也没被标记占用 ——
 * {@code material_batches.reserved_quantity} 上<b>没有任何代码在写</b>
 * (2026-08-18 prod 实测: F006 全部 65 个在用批次该列都是 0, 同表 receipt&gt;0 的 65 行 /
 * used&gt;0 的 50 行作阳性对照, 证明读得到真值)。于是同一批料可以被另一个计划重复领用/结算。
 *
 * <h3>⛔ 为什么占用和释放必须是同一个类</h3>
 *
 * <p><b>只做占用不做释放, 比不做更糟</b> —— 那是把库存永久占死。而释放有三条路
 * (驳回 / 冲销 / 结单扣减), 三条各写各的就必然漂 (同一个东西有两份, 它一定会漂)。
 * 所以「一份报工在各原料批次上占多少」这件事只有一个出处:
 * {@link #reservationOf(ProductionReport)}, 占用和三条释放路径全部从它取数。
 *
 * <h3>释放为什么必须发生在结单三道闸【之前】</h3>
 *
 * <p>{@link MaterialBatch#getCurrentQuantity()} = 收货 − 已用 − <b>预留</b>, 而结单路径
 * 三处闸读的都是它 ({@code deriveRawConsumptions} 预填 / {@code resolveAvailableBefore} 校验 /
 * {@code postMaterialBatchConsumption} 过账)。为报工 R 挂的预留会<b>把 R 自己那一笔挡在门外</b>:
 * prod 上领料按报工所需量整批建仓, 引用量恰好等于收货量 ⇒ {@code 20 − 0 − 20 = 0} ⇒
 * <b>每一单</b>都结不掉。所以结单侧的释放点在 {@code settleProduction} 内部、三道闸之前。
 *
 * <p>反过来「先扣后放」也不行: {@code used += qty} 之后 reserved 还挂着 ⇒
 * {@code used + reserved > receipt} ⇒ {@link MaterialBatch#assertConsumptionInvariant()} 当场炸。
 * 两头夹死, 可行解只有一个。
 *
 * <h3>谁还在占 —— 三个出口, 三个钩子</h3>
 *
 * <p>「这份报工还占着料吗」= {@link #holdsReservation(ProductionReport)}
 * (没被软删 且 没被驳回)。全仓能让报工离开这个集合的地方<b>恰好两处</b>
 * (2026-08-18 逐处核对: {@code ReportReversalServiceImpl:281} 软删、
 * {@code ProcessWorkReportingServiceImpl:86} 置 REJECTED), 加上结单一处, 一共三个出口 ——
 * 三处都调本类释放, 集合才不会漏掉占用不放的报工。
 */
@Slf4j
@Service
public class WorkshopReportReservationService {

    private final MaterialBatchRepository materialBatchRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final ProductionReportRepository productionReportRepository;
    private final WarehouseResolver warehouseResolver;
    private final EntityManager entityManager;

    @Autowired
    public WorkshopReportReservationService(MaterialBatchRepository materialBatchRepository,
                                            ProductionBatchRepository productionBatchRepository,
                                            ProductionReportRepository productionReportRepository,
                                            WarehouseResolver warehouseResolver,
                                            @Qualifier("primaryEntityManagerFactory")
                                            EntityManager entityManager) {
        this.materialBatchRepository = materialBatchRepository;
        this.productionBatchRepository = productionBatchRepository;
        this.productionReportRepository = productionReportRepository;
        this.warehouseResolver = Objects.requireNonNull(warehouseResolver, "warehouseResolver");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    /**
     * 🔑 <b>唯一口径</b>: 一份报工在各原料批次上占用多少。
     *
     * <p>数据源是 {@code production_reports.material_batch_refs} (jsonb)。2026-08-18 prod 实测
     * 元素恰好三个键: {@code materialBatchId}(String UUID) / {@code quantity}(数字) /
     * {@code unit}(String), 16/16 条如此 —— 不是构造出来的形状。
     *
     * <p>同一份报工引用同一批次多次要<b>求和</b>(prod 上每份报工引用 4 个不同批次各 20kg,
     * 但求和是数据允许的形状, 不能假设它不发生)。
     *
     * <p>⚠️ 这是个纯函数, 故意做成 static: 占用侧和三条释放侧都调它, 谁都改不动别人那一份。
     */
    public static Map<String, BigDecimal> reservationOf(ProductionReport report) {
        Map<String, BigDecimal> byBatch = new LinkedHashMap<>();
        if (report == null || report.getMaterialBatchRefs() == null) {
            return byBatch;
        }
        for (Map<String, Object> ref : report.getMaterialBatchRefs()) {
            if (ref == null) {
                continue;
            }
            Object rawId = ref.get("materialBatchId");
            String batchId = rawId == null ? null : rawId.toString().trim();
            if (batchId == null || batchId.isEmpty()) {
                continue;
            }
            BigDecimal qty = toQuantity(ref.get("quantity"));
            // ⛔ 0 / 负数不占用: 负数会让「释放」变成「占用」(reserved -= 负数), 与
            //    MaterialBatchServiceImpl 那两处同款守卫对称。
            if (qty == null || qty.signum() <= 0) {
                continue;
            }
            byBatch.merge(batchId, qty, BigDecimal::add);
        }
        return byBatch;
    }

    /**
     * 这份报工现在还占着料吗。
     *
     * <p>⚠️ 「占着」= 已提交 且 没被软删 且 没被驳回。⛔ 不看 {@code APPROVED} ——
     * 库存在<b>提交</b>那一刻就该被占上 (与 {@code YieldReportServiceImpl} 提交即过账同一时机),
     * 等审批才占等于审批前那段时间别的计划照样能把料领走。
     */
    public static boolean holdsReservation(ProductionReport report) {
        return report != null
                && report.getDeletedAt() == null
                && !"REJECTED".equals(report.getApprovalStatus());
    }

    private static BigDecimal toQuantity(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(raw.toString().trim());
        } catch (NumberFormatException e) {
            // 数量不是数字 = 读不出来, ⛔ 不兜底成 0 往下传 (那会被当成「这条不占料」的真实读数)。
            log.warn("报工领料引用的数量不是数字, 无法计入生产仓占用: raw={}", raw);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 占用
    // ─────────────────────────────────────────────────────────────

    /**
     * 报工提交时占用它引用的每个原料批次。返回实际占上的总量 (用于调用方留痕)。
     *
     * <p>超占直接炸 ({@link MaterialBatch#assertConsumptionInvariant()} → 409
     * {@code BATCH_OVER_CONSUMED}): 报工引用量超过批次收货量是真错误, 必须让人看见。
     */
    @Transactional
    public BigDecimal reserveForReport(String factoryId, ProductionReport report) {
        Map<String, BigDecimal> requested = strictReservationOf(report);
        if (requested.isEmpty()) {
            return BigDecimal.ZERO;
        }

        String workshopWarehouseId = warehouseResolver.resolveWorkshopId(factoryId);
        Map<String, MaterialBatch> locked = new TreeMap<>();
        // Lock and validate every requested row before mutating or saving any row.
        for (Map.Entry<String, BigDecimal> entry : new TreeMap<>(requested).entrySet()) {
            Optional<MaterialBatch> found =
                    materialBatchRepository.findByIdAndFactoryId(entry.getKey(), factoryId);
            if (found.isEmpty()) {
                throw reservationError(409,
                        "报工引用的原料批次不存在或不属于当前工厂: " + entry.getKey(),
                        "WORKSHOP_RESERVATION_BATCH_NOT_FOUND_OR_FOREIGN");
            }
            MaterialBatch batch = found.get();
            entityManager.refresh(batch, LockModeType.PESSIMISTIC_WRITE);
            validateLockedBatch(factoryId, workshopWarehouseId, entry.getKey(), entry.getValue(), batch);
            locked.put(entry.getKey(), batch);
        }

        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : new TreeMap<>(requested).entrySet()) {
            MaterialBatch batch = locked.get(entry.getKey());
            BigDecimal before = nz(batch.getReservedQuantity());
            batch.setReservedQuantity(before.add(entry.getValue()));
            // 🔒 增加 reserved 之后立刻断言 used+reserved≤receipt (与既有预留写入点同一守卫)。
            batch.assertConsumptionInvariant();
            materialBatchRepository.save(batch);
            total = total.add(entry.getValue());
            log.info("生产仓报工占用: factoryId={}, reportId={}, batchId={}, +{} ⇒ reserved={}, 可用={}",
                    factoryId, report == null ? null : report.getId(), entry.getKey(),
                    entry.getValue(), batch.getReservedQuantity(), batch.getCurrentQuantity());
        }
        return total;
    }

    /** Strict input shape for the write path; release paths intentionally keep reservationOf semantics. */
    private static Map<String, BigDecimal> strictReservationOf(ProductionReport report) {
        Map<String, BigDecimal> byBatch = new TreeMap<>();
        if (report == null || report.getMaterialBatchRefs() == null) {
            return byBatch;
        }
        for (Map<String, Object> ref : report.getMaterialBatchRefs()) {
            if (ref == null) {
                throw reservationError(400, "报工领料引用不能为空", "WORKSHOP_RESERVATION_REFERENCE_INVALID");
            }
            Object rawId = ref.get("materialBatchId");
            String batchId = rawId == null ? null : rawId.toString().trim();
            BigDecimal quantity = toQuantity(ref.get("quantity"));
            if (batchId == null || batchId.isEmpty() || quantity == null || quantity.signum() <= 0) {
                throw reservationError(400,
                        "报工领料引用必须包含有效批次和大于 0 的数量",
                        "WORKSHOP_RESERVATION_REFERENCE_INVALID");
            }
            byBatch.merge(batchId, quantity, BigDecimal::add);
        }
        return byBatch;
    }

    private void validateLockedBatch(String factoryId, String workshopWarehouseId,
                                     String requestedBatchId, BigDecimal quantity,
                                     MaterialBatch batch) {
        if (!Objects.equals(requestedBatchId, batch.getId())
                || !Objects.equals(factoryId, batch.getFactoryId())) {
            throw reservationError(409,
                    "锁定的原料批次身份或工厂不一致: " + requestedBatchId,
                    "WORKSHOP_RESERVATION_BATCH_IDENTITY_MISMATCH");
        }
        if (batch.getStatus() != MaterialBatchStatus.AVAILABLE) {
            throw reservationError(409,
                    "原料批次当前状态不可用于报工占用: " + requestedBatchId,
                    "WORKSHOP_RESERVATION_BATCH_NOT_AVAILABLE");
        }
        if (batch.getOwnership() != null && batch.getOwnership() != InventoryOwnership.COMPANY_OWNED) {
            throw reservationError(409,
                    "客供原料批次不能用于报工占用: " + requestedBatchId,
                    "WORKSHOP_RESERVATION_CUSTOMER_OWNED_FORBIDDEN");
        }
        if ("PRODUCTION_BATCH".equalsIgnoreCase(batch.getSourceDocType())) {
            throw reservationError(409,
                    "在制半成品批次不能作为报工原料占用: " + requestedBatchId,
                    "WORKSHOP_RESERVATION_WIP_FORBIDDEN");
        }
        if (!Objects.equals(workshopWarehouseId, batch.getWarehouseId())) {
            throw reservationError(409,
                    "报工原料批次不在当前工厂生产仓: " + requestedBatchId,
                    "WORKSHOP_RESERVATION_WAREHOUSE_MISMATCH");
        }
        BigDecimal available = nz(batch.getCurrentQuantity());
        if (available.compareTo(quantity) < 0) {
            throw reservationError(409,
                    String.format("原料批次可用量不足: %s, 可用 %s, 请求 %s",
                            requestedBatchId, available, quantity),
                    "BATCH_OVER_CONSUMED");
        }
    }

    private static BusinessException reservationError(int code, String message, String errorCode) {
        return new BusinessException(code, message)
                .withCode(errorCode)
                .withHint(reservationHint(errorCode))
                .withSeverity("BLOCKING");
    }

    private static String reservationHint(String errorCode) {
        return switch (errorCode) {
            case "WORKSHOP_RESERVATION_BATCH_NOT_FOUND_OR_FOREIGN",
                 "WORKSHOP_RESERVATION_BATCH_IDENTITY_MISMATCH",
                 "WORKSHOP_RESERVATION_BATCH_NOT_AVAILABLE" ->
                    "请重新选择当前工厂允许的可用生产仓原料批次";
            case "WORKSHOP_RESERVATION_WAREHOUSE_MISMATCH" ->
                    "请先正规领料入生产仓后再提交报工";
            case "WORKSHOP_RESERVATION_CUSTOMER_OWNED_FORBIDDEN" ->
                    "客供原料请走客供领料业务";
            case "WORKSHOP_RESERVATION_WIP_FORBIDDEN" ->
                    "在制半成品请走半成品流转业务";
            case "BATCH_OVER_CONSUMED" ->
                    "库存不足，请减少领料数量或更换批次";
            case "WORKSHOP_RESERVATION_REFERENCE_INVALID" ->
                    "请核对当前任务的批次引用、正数量和单位";
            default -> "请核对当前工厂的生产仓领料信息";
        };
    }

    // ─────────────────────────────────────────────────────────────
    // 释放 —— 驳回 / 冲销 / 结单扣减 三条路共用这一个出口
    // ─────────────────────────────────────────────────────────────

    /**
     * 释放这一份报工占的料。返回<b>实际</b>释放的总量。
     *
     * <p>⚠️ 释放量取 {@code min(本报工占的, 批次上还剩的预留)} —— 批次上的 reserved 是所有占用方的
     * <b>聚合</b>计数器, 减到负数会让 {@code getCurrentQuantity()} 大于实物量
     * (那比不释放更糟: 凭空多出可用量)。夹到 0 时<b>打日志</b>, 因为那意味着占用与释放已经对不上,
     * 是要查的信号, 不是可以咽下去的正常情况。
     */
    @Transactional
    public BigDecimal releaseForReport(String factoryId, ProductionReport report) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : reservationOf(report).entrySet()) {
            Optional<MaterialBatch> found =
                    materialBatchRepository.findByIdAndFactoryIdForUpdate(entry.getKey(), factoryId);
            if (found.isEmpty()) {
                log.warn("释放生产仓占用时找不到原料批次: factoryId={}, reportId={}, batchId={}",
                        factoryId, report == null ? null : report.getId(), entry.getKey());
                continue;
            }
            MaterialBatch batch = found.get();
            BigDecimal held = nz(batch.getReservedQuantity());
            BigDecimal wanted = entry.getValue();
            BigDecimal released = wanted.min(held);
            if (released.signum() <= 0) {
                continue;
            }
            if (released.compareTo(wanted) < 0) {
                log.warn("释放生产仓占用被夹到批次实有预留: factoryId={}, reportId={}, batchId={}, "
                                + "本次应释放={}, 批次上只有={} —— 占用与释放已对不上, 请查",
                        factoryId, report == null ? null : report.getId(), entry.getKey(), wanted, held);
            }
            batch.setReservedQuantity(held.subtract(released));
            materialBatchRepository.save(batch);
            total = total.add(released);
            log.info("生产仓报工释放: factoryId={}, reportId={}, batchId={}, -{} ⇒ reserved={}, 可用={}",
                    factoryId, report == null ? null : report.getId(), entry.getKey(),
                    released, batch.getReservedQuantity(), batch.getCurrentQuantity());
        }
        return total;
    }

    /**
     * 结单前释放<b>本计划</b>全部在占报工的占用。返回实际释放的总量。
     *
     * <p>释放的是本计划占的<b>全部</b>, 不是本次结单要扣的那几行 —— 计划走到结单就不再挂占了,
     * 多出来的部分本来就该还给别人。
     */
    @Transactional
    public BigDecimal releaseForPlan(String factoryId, String planId) {
        BigDecimal total = BigDecimal.ZERO;
        for (ProductionReport report : holdingReportsOfPlan(factoryId, planId)) {
            total = total.add(releaseForReport(factoryId, report));
        }
        return total;
    }

    /**
     * 本计划在某个原料批次上占了多少 —— 只读, 给「这批料被谁占着」的文案用。
     *
     * <p>⚠️ 与 {@link #releaseForPlan} 走<b>同一个</b>枚举 ({@link #holdingReportsOfPlan}) 和
     * 同一个口径 ({@link #reservationOf}), 所以「文案说占了多少」和「结单释放多少」不可能漂。
     */
    @Transactional(readOnly = true)
    public BigDecimal reservedByPlanOnBatch(String factoryId, String planId, String materialBatchId) {
        BigDecimal total = BigDecimal.ZERO;
        if (materialBatchId == null || materialBatchId.isBlank()) {
            return total;
        }
        for (ProductionReport report : holdingReportsOfPlan(factoryId, planId)) {
            BigDecimal held = reservationOf(report).get(materialBatchId);
            if (held != null) {
                total = total.add(held);
            }
        }
        return total;
    }

    /** 本计划下所有「还占着料」的 YIELD 报工 (按批次去重)。 */
    private List<ProductionReport> holdingReportsOfPlan(String factoryId, String planId) {
        List<ProductionReport> holding = new java.util.ArrayList<>();
        if (planId == null || planId.isBlank()) {
            return holding;
        }
        List<ProductionBatch> batches =
                productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId);
        if (batches == null) {
            return holding;
        }
        Set<Long> seenReports = new LinkedHashSet<>();
        for (ProductionBatch batch : batches) {
            if (batch == null || batch.getId() == null) {
                continue;
            }
            List<ProductionReport> reports =
                    productionReportRepository.findYieldReportsByBatch(factoryId, batch.getId());
            if (reports == null) {
                continue;
            }
            for (ProductionReport report : reports) {
                if (!holdsReservation(report) || !seenReports.add(report.getId())) {
                    continue;
                }
                holding.add(report);
            }
        }
        return holding;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
