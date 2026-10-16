package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.enums.ReceiveExceptionType;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseExceptionRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.inventory.PurchaseExceptionService;
import com.cretas.aims.service.inventory.ReturnOrderService;
import com.cretas.aims.service.notification.NotificationService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitUsageScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 采购异常单 Service 实现（SP6）
 *
 * <p>decideException(RETURN_OVER) 使用 REQUIRES_NEW 隔离退货单创建，
 * 防止 doomed-tx 传播回本事务（参见 feedback_failsoft_catch_cannot_save_doomed_tx）。
 *
 * <p>D-6 责任绑定：generateExceptionsForReceive 从调用方传入 ownerUserId/ownerName
 * （通常来自 PurchaseOrder.createdBy + User.username）。
 * decideException 检查调用方是否为本人或主管角色，他人收到 403 + 责任人信息。
 * 注意：鉴权用 callerRole 参数（来自 request attribute "role"，JwtAuthInterceptor 设置），
 * 不用 SecurityContextHolder（项目已知坑：SecurityContext 永空）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseExceptionServiceImpl implements PurchaseExceptionService {

    /** 主管角色：factory_super_admin 和 procurement_manager 可代为决策 */
    private static final Set<String> SUPERVISOR_ROLES = Set.of(
            "factory_super_admin", "procurement_manager"
    );

    private final PurchaseExceptionRepository exceptionRepository;

    /** D-BUG3: RETURN_OVER 决策时创建采购退货单 (PURCHASE_RETURN ReturnOrder)。 */
    private final ReturnOrderService returnOrderService;

    /** D-BUG3: 查源采购单行项目, 取退货单价 (PurchaseException 无 unitPrice 字段)。 */
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;

    /** 读时补齐可读单号与供应商名 —— 异常表里只有两个 UUID。 */
    private final PurchaseOrderRepository purchaseOrderRepository;

    /** Optional authority for legacy rows whose immutable packaging snapshot is absent. */
    @Autowired(required = false)
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    /** Optional unit authority; snapshots remain the source of truth for historical PO rows. */
    @Autowired(required = false)
    private UnitContractService unitContractService;

    /** 可选通知服务：未配置时静默跳过（不影响主流程） */
    @Autowired(required = false)
    private NotificationService notificationService;

    /**
     * Self-injection (proxy) 用于触发 RETURN_OVER 退货单创建的 {@code REQUIRES_NEW} 事务边界。
     *
     * <p>退货单创建 ({@link #createReturnOrderForException}) 必须跑独立事务: 创建失败抛
     * {@code BusinessException} 只回滚退货单自身, 不 doom 父事务 (异常单 RESOLVED 状态仍持久化)。
     * 参考 {@code ReverseTransferService} R6 self-proxy 范式 (本仓库多次复发的 doomed-tx 防御)。
     *
     * <p>单测兼容: 单测直接 new 实例 ({@code self} 为 null), 通过 {@link #proxy()} fallback 到 this。
     */
    @Autowired
    @Lazy
    private PurchaseExceptionServiceImpl self;

    /** Self-proxy fallback: Spring 注入则用代理 (触发 REQUIRES_NEW), 单测下 self==null 退回 this。 */
    private PurchaseExceptionServiceImpl proxy() {
        return self != null ? self : this;
    }

    // ─── generateExceptionsForReceive ─────────────────────────────────────

    @Override
    @Transactional
    public List<PurchaseException> generateExceptionsForReceive(
            String factoryId,
            String receiveRecordId,
            String purchaseOrderId,
            String supplierId,
            String materialTypeId,
            String materialName,
            BigDecimal poQuantity,
            BigDecimal receivedQuantity,
            String unit,
            Long createdBy,
            Long ownerUserId,
            String ownerName) {

        List<PurchaseException> result = new ArrayList<>();

        if (poQuantity == null || receivedQuantity == null) {
            return result;
        }

        int cmp = receivedQuantity.compareTo(poQuantity);
        if (cmp == 0) {
            // 无异常
            return result;
        }

        ReceiveExceptionType type = cmp > 0 ? ReceiveExceptionType.OVER_RECEIVE : ReceiveExceptionType.UNDER_RECEIVE;
        BigDecimal exceptionQty = receivedQuantity.subtract(poQuantity).abs();

        PurchaseException pe = new PurchaseException();
        pe.setFactoryId(factoryId);
        pe.setReceiveRecordId(receiveRecordId);
        pe.setPurchaseOrderId(purchaseOrderId);
        pe.setSupplierId(supplierId);
        pe.setMaterialTypeId(materialTypeId);
        pe.setMaterialName(materialName);
        pe.setExceptionType(type);
        pe.setPoQuantity(poQuantity);
        pe.setReceivedQuantity(receivedQuantity);
        pe.setExceptionQty(exceptionQty);
        pe.setUnit(unit);
        pe.setStatus("PENDING");
        pe.setCreatedBy(createdBy);
        pe.setExceptionNumber(generateExceptionNumber(factoryId));
        // D-6 责任绑定：责任人=源采购单的创建人（ownerUserId/ownerName 由调用方从 PO 查出传入）
        pe.setOwnerUserId(ownerUserId);
        pe.setOwnerName(ownerName);

        PurchaseException saved = exceptionRepository.save(pe);
        log.info("[SP6] 生成采购异常单 {} 类型={} 差异={} {} 责任人={}({})",
                pe.getExceptionNumber(), type, exceptionQty, unit, ownerName, ownerUserId);

        // D-6 通知：生成异常单时给责任人发站内通知
        if (notificationService != null && ownerUserId != null) {
            try {
                notificationService.notifyUser(
                        factoryId,
                        ownerUserId,
                        "采购入库异常待处理",
                        String.format("异常单 %s：物料「%s」%s（差异 %s %s），请前往「采购管理 → 入库异常」处理。",
                                pe.getExceptionNumber(),
                                materialName != null ? materialName : "未知物料",
                                type == ReceiveExceptionType.OVER_RECEIVE ? "超收" : "少收",
                                exceptionQty.stripTrailingZeros().toPlainString(),
                                unit != null ? unit : "")
                );
            } catch (Exception notifyEx) {
                // 通知失败不影响主流程
                log.warn("[SP6] 发送异常通知失败（不影响入库）: exceptionId={}, ownerUserId={}, error={}",
                        saved.getId(), ownerUserId, notifyEx.getMessage());
            }
        }

        result.add(saved);
        return result;
    }

    // ─── decideException ───────────────────────────────────────────────────

    /**
     * REQUIRES_NEW：decideException 如果触发 RETURN_OVER 分支，
     * 退货单创建失败不得 doom 本事务（异常单本身状态变更仍持久化）。
     *
     * <p>注意：@Transactional(propagation=REQUIRES_NEW) 仅在真正调用
     * 子 @Service（另一 Spring bean）时才能在 REQUIRES_NEW 下正确隔离。
     * 此处 decideException 本身就是该入口，外部调用时 Spring 会开新事务。
     *
     * <p>D-6 鉴权：callerRole 来自 request attribute "role"（JwtAuthInterceptor 设置），
     * 不用 SecurityContextHolder（项目已知坑：SecurityContext 永空）。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PurchaseException decideException(
            String exceptionId,
            String factoryId,
            ExceptionDecision decision,
            String notes,
            Long decisionBy,
            String callerRole) {

        PurchaseException pe = exceptionRepository.findById(exceptionId)
                .orElseThrow(() -> new BusinessException("采购异常单不存在: " + exceptionId));

        if (!factoryId.equals(pe.getFactoryId())) {
            throw new BusinessException(403, "无权操作此异常单");
        }
        if (!"PENDING".equals(pe.getStatus())) {
            throw new BusinessException("异常单已处理（status=" + pe.getStatus() + "），无法再次决策");
        }

        // D-6 责任绑定鉴权：本人或主管角色才可决策（Fool-proof Rule 2 — 指明责任人是谁）
        boolean isSupervisor = callerRole != null && SUPERVISOR_ROLES.contains(callerRole);
        boolean isOwner = pe.getOwnerUserId() != null && pe.getOwnerUserId().equals(decisionBy);
        if (!isOwner && !isSupervisor) {
            String ownerInfo = pe.getOwnerName() != null
                    ? pe.getOwnerName() + "（userId=" + pe.getOwnerUserId() + "）"
                    : (pe.getOwnerUserId() != null ? "userId=" + pe.getOwnerUserId() : "未知");
            throw new BusinessException(403,
                    "仅责任采购人 " + ownerInfo + " 或主管可处理此异常单，当前用户无权操作");
        }

        // 决策类型与异常类型不兼容校验
        if (pe.getExceptionType() == ReceiveExceptionType.OVER_RECEIVE &&
                (decision == ExceptionDecision.ACCEPT_SHORT || decision == ExceptionDecision.REQUEST_RESUPPLY)) {
            throw new BusinessException("超收异常不能使用少收决策").withCode("EXCEPTION_DECISION_MISMATCH");
        }
        if (pe.getExceptionType() == ReceiveExceptionType.UNDER_RECEIVE &&
                (decision == ExceptionDecision.ACCEPT_OVER || decision == ExceptionDecision.RETURN_OVER)) {
            throw new BusinessException("少收异常不能使用超收决策").withCode("EXCEPTION_DECISION_MISMATCH");
        }

        // BLOCKER 3: RETURN_OVER 必须能创建采购退货单 (ReturnOrder.counterpartyId = supplierId, NOT NULL)。
        // 若异常单无供应商信息 (supplierId 空), 退货单创建会在 REQUIRES_NEW 内抛 → fail-soft catch 吞掉
        // → 异常单变 RESOLVED 但无退货单 → 用户看到"处理成功" (降级假成功, 违反禁降级)。
        // 在【翻 RESOLVED 之前】前置校验, 让操作员看到明确错误, 决策不提交 (校验在 catch 外, fail-loud)。
        if (decision == ExceptionDecision.RETURN_OVER
                && (pe.getSupplierId() == null || pe.getSupplierId().isBlank())) {
            throw new BusinessException(422, "无供应商信息, 无法创建采购退货单")
                    .withCode("RETURN_OVER_NO_SUPPLIER")
                    .withHint("该入库异常未关联供应商, 无法退回供应商。"
                            + "请改用「接受超收」决策, 或联系采购补全供应商信息后重试。");
        }

        pe.setDecision(decision);
        pe.setDecisionBy(decisionBy);
        pe.setDecisionAt(LocalDateTime.now());
        pe.setDecisionNotes(notes);
        pe.setStatus("RESOLVED");

        PurchaseException saved = exceptionRepository.save(pe);
        log.info("[SP6] 异常单 {} 决策={} by userId={}", pe.getExceptionNumber(), decision, decisionBy);

        // D-BUG3: RETURN_OVER 决策 → 真正创建采购退货单 (PURCHASE_RETURN ReturnOrder)。
        // 客户红线 (catalog 行2399-2413): "退货跟钱有关, 必须先财务审批" — 退货单初始 DRAFT,
        // 走 submit → approve → finance-approve → complete 链, 不自动审批。
        //
        // 幂等: 仅在 returnOrderId 尚未设置时创建 (重复决策已被上面的 PENDING 守卫拦截,
        // 此处双保险防御历史脏数据 / 并发)。
        // 事务隔离: 退货单创建走 self-proxy REQUIRES_NEW, 失败只回滚退货单, 异常单 RESOLVED 仍持久化。
        if (decision == ExceptionDecision.RETURN_OVER && saved.getReturnOrderId() == null) {
            try {
                ReturnOrder returnOrder = proxy().createReturnOrderForException(saved, decisionBy, notes);
                // 退货单创建成功 (独立事务已 commit) → 回写关联 ID 到异常单。
                saved.setReturnOrderId(returnOrder.getId());
                saved = exceptionRepository.save(saved);
                log.info("[SP6] 异常单 {} RETURN_OVER 已生成采购退货单 {} (退货单号 {}), 待财务审批",
                        saved.getExceptionNumber(), returnOrder.getId(), returnOrder.getReturnNumber());
            } catch (BusinessException be) {
                // MEDIUM: 收窄 fail-soft — 退货单创建的【业务规则拒绝】(deliberate, 如缺必填/校验失败)
                // 必须 fail-loud, 不能吞成"异常单 RESOLVED 但无退货单"的降级假成功 (违反禁降级)。
                // re-throw 会回滚本事务 (RESOLVED flip), 操作员看到明确错误, 不留半截状态。
                // (BLOCKER 3 的 supplierId 空校验已在 catch 外前置, 此处兜结构性/下游业务拒绝。)
                log.warn("[SP6] 异常单 {} RETURN_OVER 退货单创建被业务规则拒绝 (回滚决策, fail-loud): {}",
                        saved.getExceptionNumber(), be.getMessage());
                throw be;
            } catch (Exception e) {
                // 仅【真·非预期/瞬时基础设施错误】才 best-effort 吞 (异常单仍 RESOLVED, 可手动补建退货单),
                // 避免 doomed-tx 拖垮异常单决策。结构性编程错误已由上面 BusinessException 分支 fail-loud。
                log.error("[SP6] 异常单 {} RETURN_OVER 退货单创建遇非预期错误 (异常单仍标 RESOLVED, 可手动补建): {}",
                        saved.getExceptionNumber(), e.getMessage(), e);
            }
        }

        return saved;
    }

    // ─── createReturnOrderForException (RETURN_OVER 退货单创建) ─────────────────

    /**
     * D-BUG3: 为 RETURN_OVER 异常单创建一张采购退货单 (PURCHASE_RETURN ReturnOrder)。
     *
     * <p>映射: 退货单 counterparty=供应商 (exception.supplierId), sourceOrder=采购单
     * (exception.purchaseOrderId), 退货物料=异常单物料, 退货数量=超收差值 (exception.exceptionQty)。
     * 退货单价取自源采购单对应行项目 (PurchaseOrderItem.unitPrice), 用于退货金额 → AR/AP 冲减。
     *
     * <p>状态: 初始 DRAFT (ReturnOrderService.createReturnOrder 强制), 不自动审批 —— 客户红线
     * "退货必须先财务审批", 退货单后续走 submit → approve → finance-approve → complete 链。
     *
     * <p>{@code REQUIRES_NEW}: 独立事务, 创建失败只回滚退货单自身, 不污染异常单 RESOLVED 状态。
     *
     * @param exception  已 RESOLVED 的 RETURN_OVER 异常单
     * @param decisionBy 决策人 userId (退货单 createdBy)
     * @param notes      决策备注 (并入退货原因)
     * @return 新建的退货单 (DRAFT)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReturnOrder createReturnOrderForException(PurchaseException exception, Long decisionBy, String notes) {
        CreateReturnOrderRequest request = new CreateReturnOrderRequest();
        request.setReturnType(ReturnType.PURCHASE_RETURN.name());
        request.setCounterpartyId(exception.getSupplierId());
        request.setSourceOrderId(exception.getPurchaseOrderId());
        request.setReturnDate(LocalDate.now());
        // 有货退回供应商 (默认), AR/AP 冲减在 completeReturnOrder 触发 (实物退回后)。
        request.setWithGoods(Boolean.TRUE);
        String reason = "采购超收退货 (异常单 " + exception.getExceptionNumber() + ")";
        if (notes != null && !notes.isBlank()) {
            reason = reason + " — " + notes;
        }
        request.setReason(reason);

        // 退货行: generic ReturnOrder 按原料库存基本单位扣减，因此这里必须先把异常单位
        // 换成同厂源 PO 唯一物料行的库存基本单位，并把 PO 未税包装价换成含税基本单位价。
        PurchaseReturnLine returnLine = resolveReturnLine(exception);
        CreateReturnOrderRequest.ReturnOrderItemDTO item = new CreateReturnOrderRequest.ReturnOrderItemDTO();
        item.setMaterialTypeId(exception.getMaterialTypeId());
        item.setItemName(exception.getMaterialName());
        item.setQuantity(returnLine.quantity());
        item.setUnitPrice(returnLine.unitPrice());
        item.setReason(reason);
        request.setItems(List.of(item));

        // ReturnOrderService 会写 ReturnOrder.factoryId = 传入 factoryId (= 异常单 factoryId, 跨租户安全)。
        return returnOrderService.createReturnOrder(exception.getFactoryId(), request, decisionBy);
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int RETURN_UNIT_PRICE_SCALE = 4;

    private record PurchaseReturnLine(BigDecimal quantity, BigDecimal unitPrice) { }

    /**
     * Resolve the DTO boundary used by generic purchase-return inventory deduction.
     * PO snapshots are immutable and take precedence over current material master data.
     */
    private PurchaseReturnLine resolveReturnLine(PurchaseException exception) {
        if (exception.getPurchaseOrderId() == null || exception.getMaterialTypeId() == null) {
            throw returnConversionError("异常单缺少源采购单或物料");
        }
        var sourceOrderOptional = purchaseOrderRepository
                .findByIdAndFactoryId(exception.getPurchaseOrderId(), exception.getFactoryId());
        if (sourceOrderOptional == null || sourceOrderOptional.isEmpty()) {
            throw returnConversionError("源采购单不存在或不属于当前工厂");
        }
        PurchaseOrder sourceOrder = sourceOrderOptional.get();

        List<PurchaseOrderItem> sourceItems = purchaseOrderItemRepository.findByPurchaseOrderId(sourceOrder.getId());
        if (sourceItems == null) sourceItems = List.of();
        List<PurchaseOrderItem> matches = sourceItems.stream()
                .filter(i -> exception.getMaterialTypeId().equals(i.getMaterialTypeId()))
                .toList();
        if (matches.size() != 1) {
            throw returnConversionError(matches.isEmpty()
                    ? "源采购单缺少对应物料行"
                    : "源采购单对应物料行不唯一");
        }
        PurchaseOrderItem item = matches.get(0);
        if (item.getUnitPrice() == null || item.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw returnConversionError("源采购单物料行缺少有效单价");
        }

        String purchaseUnit = firstText(item.getPurchasePackageUnitSnapshot(), item.getUnit());
        String baseUnit = firstText(item.getInventoryBaseUnitSnapshot(), legacyBaseUnit(exception, item));
        if (baseUnit == null) {
            throw returnConversionError("源采购单缺少库存基本单位");
        }
        BigDecimal packageToBase = positive(item.getPackageToBaseFactorSnapshot());
        if (packageToBase == null && sameUnit(purchaseUnit, baseUnit, exception.getFactoryId())) {
            packageToBase = BigDecimal.ONE;
        }
        if (packageToBase == null && purchaseUnit != null) {
            packageToBase = convertedQuantity(BigDecimal.ONE, purchaseUnit, baseUnit, exception);
        }

        BigDecimal returnQuantity = resolveReturnQuantity(exception, purchaseUnit, baseUnit, packageToBase);
        if (returnQuantity == null || returnQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw returnConversionError("异常数量无法换算为库存基本单位");
        }
        try {
            // ReturnOrderService deducts purchase returns at scale 2. Refuse a lossy
            // conversion (e.g. 1 g -> .001 kg) instead of silently deducting zero.
            returnQuantity = returnQuantity.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw returnConversionError("异常数量换算结果不能按库存精度精确表达（最多两位小数）");
        }

        String priceUnit = firstText(item.getPriceUnit(), purchaseUnit);
        BigDecimal priceFactor = positive(item.getQuantityToPriceFactor());
        if (priceFactor == null && sameUnit(priceUnit, purchaseUnit, exception.getFactoryId())) {
            priceFactor = BigDecimal.ONE;
        }
        if (priceFactor == null || packageToBase == null || packageToBase.compareTo(BigDecimal.ZERO) <= 0) {
            throw returnConversionError("源采购单缺少有效的计价/包装换算");
        }
        BigDecimal taxRate = item.getTaxRate() == null ? BigDecimal.ZERO : item.getTaxRate();
        if (taxRate.compareTo(BigDecimal.ZERO) < 0) {
            throw returnConversionError("源采购单税率无效");
        }
        BigDecimal taxMultiplier = BigDecimal.ONE.add(taxRate.divide(HUNDRED, 12, RoundingMode.HALF_UP));
        BigDecimal baseUnitPrice = item.getUnitPrice()
                .multiply(priceFactor)
                .divide(packageToBase, 12, RoundingMode.HALF_UP)
                .multiply(taxMultiplier)
                .setScale(RETURN_UNIT_PRICE_SCALE, RoundingMode.HALF_UP);
        return new PurchaseReturnLine(returnQuantity, baseUnitPrice);
    }

    private BigDecimal resolveReturnQuantity(PurchaseException exception, String purchaseUnit,
                                             String baseUnit, BigDecimal packageToBase) {
        String exceptionUnit = exception.getUnit();
        if (exceptionUnit == null || exception.getExceptionQty() == null) return null;
        if (sameUnit(exceptionUnit, baseUnit, exception.getFactoryId())) {
            return exception.getExceptionQty();
        }
        if (packageToBase != null && sameUnit(exceptionUnit, purchaseUnit, exception.getFactoryId())) {
            return exception.getExceptionQty().multiply(packageToBase);
        }
        return convertedQuantity(exception.getExceptionQty(), exceptionUnit, baseUnit, exception);
    }

    private BigDecimal convertedQuantity(BigDecimal quantity, String fromUnit, String toUnit,
                                        PurchaseException exception) {
        if (unitContractService == null || fromUnit == null || toUnit == null) return null;
        try {
            UnitConversionContext context = new UnitConversionContext(
                    exception.getFactoryId(), exception.getMaterialTypeId(), fromUnit, toUnit,
                    LocalDateTime.now(), UnitUsageScene.INVENTORY, 12, RoundingMode.HALF_UP);
            UnitConversionResult result = unitContractService.convert(quantity, context);
            return result != null && result.succeeded() ? result.quantity() : null;
        } catch (RuntimeException ex) {
            log.warn("[SP6] 采购退货单位换算失败 {} -> {}: {}", fromUnit, toUnit, ex.getMessage());
            return null;
        }
    }

    private String legacyBaseUnit(PurchaseException exception, PurchaseOrderItem item) {
        if (rawMaterialTypeRepository == null || item.getMaterialTypeId() == null) return null;
        try {
            var material = rawMaterialTypeRepository.findById(item.getMaterialTypeId());
            if (material == null) return null;
            return material
                    .filter(value -> exception.getFactoryId().equals(value.getFactoryId()))
                    .map(com.cretas.aims.entity.RawMaterialType::getUnit)
                    .orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean sameUnit(String left, String right, String factoryId) {
        if (left == null || right == null) return false;
        String l = canonicalUnit(factoryId, left);
        String r = canonicalUnit(factoryId, right);
        return l.equalsIgnoreCase(r);
    }

    private String canonicalUnit(String factoryId, String raw) {
        if (unitContractService != null) {
            try {
                var normalized = unitContractService.normalize(factoryId, raw);
                if (normalized != null && normalized.recognized() && normalized.code() != null) {
                    return normalized.code().trim();
                }
            } catch (RuntimeException ignored) {
                // Fall through to the immutable snapshot literal.
            }
        }
        return raw.trim();
    }

    private String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : (second != null && !second.isBlank() ? second : null);
    }

    private BigDecimal positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0 ? value : null;
    }

    private BusinessException returnConversionError(String message) {
        return new BusinessException(422, message)
                .withCode("PURCHASE_RETURN_UNIT_CONVERSION_UNAVAILABLE");
    }

    // ─── listExceptions ────────────────────────────────────────────────────

    @Override
    public List<PurchaseException> listExceptions(String factoryId, String status) {
        List<PurchaseException> rows = (status == null || status.isBlank())
                ? exceptionRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId)
                : exceptionRepository.findByFactoryIdAndStatus(factoryId, status);
        enrichForDisplay(rows);
        return rows;
    }

    /**
     * 把只有 UUID 的三项补成人能读的：采购单号、供应商名、物料名。
     *
     * <h3>为什么在读时补</h3>
     *
     * <p>2026-08-28 逐屏走查实测，这个接口对 F006 返回 31 条，每条都是：
     * {@code purchaseOrderNumber=null / materialName=null / supplierName=null}，
     * 只有 {@code purchaseOrderId} / {@code materialTypeId} / {@code supplierId} 三个 UUID。
     * 屏上于是显示「采购单: 39603589-739e-4094-ac50-ab745e977077」、物料名一列全是「—」、
     * 供应商从不显示。⛔ 这三样前端都造不出来。
     *
     * <p>{@code materialName} 是<b>快照列</b>，存量 31 行全是 null —— 只改写入侧不会修好它们，
     * 而补历史要写迁移。读时按源采购单查一次，<b>存量与新增一起修好</b>。
     *
     * <p>⚠️ 按 {@code purchaseOrderId} 批量取，⛔ 不在循环里逐条查（31 条会变成 31 次往返）。
     */
    private void enrichForDisplay(List<PurchaseException> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<String> poIds = rows.stream()
                .map(PurchaseException::getPurchaseOrderId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (poIds.isEmpty()) {
            return;
        }

        Map<String, PurchaseOrder> poById = purchaseOrderRepository.findAllById(poIds).stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, po -> po, (a, b) -> a));

        // (采购单, 物料类型) -> 行项目上的物料名快照
        Map<String, String> materialNameByKey = purchaseOrderItemRepository.findByPurchaseOrderIdIn(poIds).stream()
                .filter(it -> it.getMaterialTypeId() != null && it.getMaterialName() != null)
                .collect(Collectors.toMap(
                        it -> it.getPurchaseOrderId() + "|" + it.getMaterialTypeId(),
                        PurchaseOrderItem::getMaterialName,
                        (a, b) -> a));

        for (PurchaseException pe : rows) {
            PurchaseOrder po = poById.get(pe.getPurchaseOrderId());
            if (po != null) {
                pe.setPurchaseOrderNumber(po.getOrderNumber());
                pe.setSupplierName(po.getSupplierName());
            }
            if (pe.getMaterialName() == null && pe.getMaterialTypeId() != null) {
                pe.setMaterialName(
                        materialNameByKey.get(pe.getPurchaseOrderId() + "|" + pe.getMaterialTypeId()));
            }
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private String generateExceptionNumber(String factoryId) {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = exceptionRepository.countPendingByFactoryId(factoryId) + 1;
        return String.format("EX-%s-%s-%04d", factoryId, date, count);
    }
}
