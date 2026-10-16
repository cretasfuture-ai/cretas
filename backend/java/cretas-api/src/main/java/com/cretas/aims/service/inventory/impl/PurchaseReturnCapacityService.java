package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.dto.inventory.PurchaseReturnAvailability;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitUsageScene;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Purchase-return source capacity and retry-idempotency boundary.
 * The source PO row is locked before any return-order write is attempted.
 */
@Service
public class PurchaseReturnCapacityService {
    private static final Set<ReturnOrderStatus> CAPACITY_STATUSES = EnumSet.of(
            ReturnOrderStatus.DRAFT, ReturnOrderStatus.SUBMITTED,
            ReturnOrderStatus.APPROVED, ReturnOrderStatus.FINANCE_APPROVED,
            ReturnOrderStatus.COMPLETED);
    private static final Set<PurchaseOrderStatus> NON_RETURNABLE = EnumSet.of(
            PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.SUBMITTED,
            PurchaseOrderStatus.FINANCE_REJECTED, PurchaseOrderStatus.CANCELLED);

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final ReturnOrderItemRepository returnOrderItemRepository;

    @Autowired(required = false)
    private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Autowired(required = false)
    private UnitContractService unitContractService;
    @Autowired(required = false)
    private DataScopeResolver dataScopeResolver;
    @Autowired(required = false)
    private EntityManager entityManager;

    public PurchaseReturnCapacityService(PurchaseOrderRepository purchaseOrderRepository,
                                         PurchaseOrderItemRepository purchaseOrderItemRepository,
                                         ReturnOrderRepository returnOrderRepository,
                                         ReturnOrderItemRepository returnOrderItemRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.returnOrderRepository = returnOrderRepository;
        this.returnOrderItemRepository = returnOrderItemRepository;
    }

    public record Preparation(String deterministicId, ReturnOrder existingOrder) {
        public boolean isReplay() { return existingOrder != null; }
    }

    @Transactional
    public Preparation prepare(String factoryId, CreateReturnOrderRequest request, Long actorId) {
        if (request == null || !ReturnType.PURCHASE_RETURN.name().equals(request.getReturnType())) {
            return new Preparation(null, null);
        }
        String actorNamespace = actorNamespace(actorId);
        if (request.getSourceOrderId() == null || request.getSourceOrderId().isBlank()) {
            throw error(422, "采购退货必须指定源采购单", "PURCHASE_RETURN_SOURCE_REQUIRED");
        }
        if (request.getCustomFields() != null && !request.getCustomFields().isEmpty()
                && hasText(request.getRequestId())) {
            throw error(422, "带请求幂等键的采购退货不支持自定义字段", "PURCHASE_RETURN_IDEMPOTENCY_CUSTOM_FIELDS_UNSUPPORTED");
        }

        // Serialize retries with the same factory/actor/key even when they name
        // different source POs. The lock order is idempotency key first, PO second.
        if (hasText(request.getRequestId()) && entityManager != null) {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .setParameter("key", factoryId + "\n" + actorNamespace + "\n" + request.getRequestId())
                    .getSingleResult();
        }

        PurchaseOrder source = purchaseOrderRepository
                .findByIdAndFactoryIdForUpdate(request.getSourceOrderId(), factoryId)
                .orElseThrow(() -> error(404, "源采购单不存在或不属于当前工厂", "PURCHASE_RETURN_SOURCE_NOT_FOUND"));
        if (entityManager != null) {
            entityManager.refresh(source);
        }
        assertSourceAccess(factoryId, source.getCreatedBy());
        if (source.getStatus() == null || NON_RETURNABLE.contains(source.getStatus())) {
            throw error(422, "源采购单当前状态不允许退货", "PURCHASE_RETURN_SOURCE_STATUS_INVALID");
        }
        if (!Objects.equals(source.getSupplierId(), request.getCounterpartyId())) {
            throw error(422, "退货供应商必须与源采购单一致", "PURCHASE_RETURN_SUPPLIER_MISMATCH");
        }

        String deterministicId = null;
        if (hasText(request.getRequestId())) {
            deterministicId = UUID.nameUUIDFromBytes((factoryId + "\n" + actorNamespace + "\n" + request.getRequestId())
                    .getBytes(StandardCharsets.UTF_8)).toString();
            Optional<ReturnOrder> existing = returnOrderRepository.findById(deterministicId);
            if (existing.isPresent()) {
                if (samePayload(existing.get(), request, source.getId(), factoryId, actorId)) {
                    return new Preparation(deterministicId, existing.get());
                }
                throw error(409, "相同请求幂等键对应的退货内容不一致", "PURCHASE_RETURN_IDEMPOTENCY_PAYLOAD_CONFLICT")
                        .withHint("请使用新的请求幂等键").withHintTarget("请求幂等键");
            }
        }

        List<PurchaseOrderItem> sourceItems = Optional.ofNullable(
                purchaseOrderItemRepository.findByPurchaseOrderId(source.getId())).orElse(List.of());
        Map<String, List<PurchaseOrderItem>> linesByMaterial = sourceItems.stream()
                .filter(i -> i.getMaterialTypeId() != null)
                .collect(Collectors.groupingBy(PurchaseOrderItem::getMaterialTypeId));
        Map<String, BigDecimal> incoming = new LinkedHashMap<>();
        for (CreateReturnOrderRequest.ReturnOrderItemDTO item : Optional.ofNullable(request.getItems()).orElse(List.of())) {
            if (item == null || item.getMaterialTypeId() == null || item.getMaterialTypeId().isBlank()
                    || item.getQuantity() == null || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw error(422, "采购退货物料和数量必须为正数", "PURCHASE_RETURN_QUANTITY_INVALID");
            }
            exactTwoDecimals(item.getQuantity(), "采购退货数量必须精确到两位小数");
            incoming.merge(item.getMaterialTypeId(), item.getQuantity(), BigDecimal::add);
        }
        if (incoming.isEmpty()) {
            throw error(422, "采购退货至少需要一条物料", "PURCHASE_RETURN_ITEMS_EMPTY");
        }

        Map<String, BigDecimal> returned = returnedByMaterial(factoryId, source.getId());
        boolean physicalReturn = request.getWithGoods() == null || Boolean.TRUE.equals(request.getWithGoods());
        for (Map.Entry<String, BigDecimal> entry : incoming.entrySet()) {
            List<PurchaseOrderItem> matches = linesByMaterial.getOrDefault(entry.getKey(), List.of());
            if (matches.size() != 1) {
                throw error(422, matches.isEmpty() ? "源采购单缺少对应物料行" : "源采购单对应物料行不唯一",
                        "PURCHASE_RETURN_SOURCE_MATERIAL_AMBIGUOUS");
            }
            PurchaseOrderItem line = matches.get(0);
            if (!physicalReturn) continue;
            BigDecimal receivedBase = receivedBaseQuantity(factoryId, line);
            if (receivedBase == null || receivedBase.compareTo(BigDecimal.ZERO) <= 0) {
                throw error(422, "源采购单物料尚未实际收货或缺少单位契约", "PURCHASE_RETURN_RECEIVED_UNAVAILABLE");
            }
            BigDecimal alreadyReturned = returned.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (alreadyReturned.add(entry.getValue()).compareTo(receivedBase) > 0) {
                throw error(409, "采购退货数量超过源单可退余额", "PURCHASE_RETURN_CAPACITY_EXCEEDED")
                        .withHint("请减少退货数量后重试").withHintTarget("可退余额");
            }
        }

        return new Preparation(deterministicId, null);
    }

    @Transactional(readOnly = true)
    public PurchaseReturnAvailability availability(String factoryId, String purchaseOrderId) {
        PurchaseOrder source = purchaseOrderRepository.findByIdAndFactoryId(purchaseOrderId, factoryId)
                .orElseThrow(() -> error(404, "源采购单不存在或不属于当前工厂", "PURCHASE_RETURN_SOURCE_NOT_FOUND"));
        assertSourceAccess(factoryId, source.getCreatedBy());
        if (source.getStatus() == null || NON_RETURNABLE.contains(source.getStatus())) {
            throw error(422, "源采购单当前状态不允许退货", "PURCHASE_RETURN_SOURCE_STATUS_INVALID");
        }
        List<PurchaseOrderItem> sourceItems = Optional.ofNullable(
                purchaseOrderItemRepository.findByPurchaseOrderId(source.getId())).orElse(List.of());
        Map<String, BigDecimal> returned = returnedByMaterial(factoryId, source.getId());
        Map<String, List<PurchaseOrderItem>> grouped = sourceItems.stream()
                .filter(line -> line.getMaterialTypeId() != null)
                .collect(Collectors.groupingBy(PurchaseOrderItem::getMaterialTypeId,
                        LinkedHashMap::new, Collectors.toList()));
        List<PurchaseReturnAvailability.Item> items = new ArrayList<>();
        for (Map.Entry<String, List<PurchaseOrderItem>> e : grouped.entrySet()) {
            String base = null;
            BigDecimal received = BigDecimal.ZERO;
            boolean valid = true;
            for (PurchaseOrderItem line : e.getValue()) {
                String lineBase = baseUnit(factoryId, line);
                BigDecimal lineReceived = receivedBaseQuantity(factoryId, line);
                if (lineReceived == null || lineBase == null
                        || (base != null && !sameUnit(factoryId, base, lineBase))) {
                    valid = false;
                    break;
                }
                base = lineBase;
                received = received.add(lineReceived);
            }
            if (!valid) continue;
            BigDecimal returnedQty = returned.getOrDefault(e.getKey(), BigDecimal.ZERO);
            items.add(new PurchaseReturnAvailability.Item(e.getKey(), base,
                    received, returnedQty, received.subtract(returnedQty).max(BigDecimal.ZERO)));
        }
        return new PurchaseReturnAvailability(source.getId(), items);
    }

    private Map<String, BigDecimal> returnedByMaterial(String factoryId, String sourceId) {
        List<ReturnOrder> orders = Optional.ofNullable(returnOrderRepository
                .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                        factoryId, ReturnType.PURCHASE_RETURN, sourceId)).orElse(List.of());
        Map<String, BigDecimal> result = new HashMap<>();
        for (ReturnOrder order : orders) {
            if (order == null || !CAPACITY_STATUSES.contains(order.getStatus())
                    || !Boolean.TRUE.equals(order.getWithGoods())) continue;
            List<ReturnOrderItem> items = Optional.ofNullable(returnOrderItemRepository
                    .findByReturnOrderId(order.getId())).orElse(List.of());
            for (ReturnOrderItem item : items) {
                if (item.getMaterialTypeId() != null && item.getQuantity() != null) {
                    result.merge(item.getMaterialTypeId(), item.getQuantity(), BigDecimal::add);
                }
            }
        }
        return result;
    }

    private BigDecimal receivedBaseQuantity(String factoryId, PurchaseOrderItem line) {
        BigDecimal received = line.getReceivedQuantity();
        if (received == null || received.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        String base = baseUnit(factoryId, line);
        String sourceUnit = firstText(line.getPurchasePackageUnitSnapshot(), line.getUnit());
        BigDecimal factor = positive(line.getPackageToBaseFactorSnapshot());
        if (factor != null && sameUnit(factoryId, sourceUnit, line.getInventoryBaseUnitSnapshot())
                && !sameUnit(factoryId, sourceUnit, base)) {
            return received.multiply(factor);
        }
        if (sameUnit(factoryId, sourceUnit, base)) return received;
        if (factor != null && sameUnit(factoryId, sourceUnit, firstText(line.getPurchasePackageUnitSnapshot(), sourceUnit))) {
            return received.multiply(factor);
        }
        return convert(factoryId, line.getMaterialTypeId(), received, sourceUnit, base);
    }

    private String baseUnit(String factoryId, PurchaseOrderItem line) {
        if (hasText(line.getInventoryBaseUnitSnapshot())) return line.getInventoryBaseUnitSnapshot().trim();
        if (rawMaterialTypeRepository == null || line.getMaterialTypeId() == null) return null;
        Optional<RawMaterialType> master = rawMaterialTypeRepository.findByIdAndFactoryId(line.getMaterialTypeId(), factoryId);
        return master.map(RawMaterialType::getUnit).filter(this::hasText).map(String::trim).orElse(null);
    }

    private BigDecimal convert(String factoryId, String materialId, BigDecimal quantity, String from, String to) {
        if (!hasText(from) || !hasText(to) || unitContractService == null) return null;
        try {
            UnitConversionResult result = unitContractService.convert(quantity, new UnitConversionContext(
                    factoryId, materialId, from, to, LocalDateTime.now(), UnitUsageScene.INVENTORY,
                    12, RoundingMode.HALF_UP));
            return result != null && result.succeeded() ? result.quantity() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean sameUnit(String factoryId, String left, String right) {
        if (!hasText(left) || !hasText(right)) return false;
        if (unitContractService != null) {
            try { return unitContractService.areEquivalent(factoryId, left.trim(), right.trim()); }
            catch (RuntimeException ignored) { }
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private boolean samePayload(ReturnOrder existing, CreateReturnOrderRequest request,
                                String sourceId, String factoryId, Long actorId) {
        if (!Objects.equals(existing.getFactoryId(), factoryId)
                || existing.getReturnType() != ReturnType.PURCHASE_RETURN
                || !Objects.equals(existing.getSourceOrderId(), sourceId)
                || !Objects.equals(existing.getCounterpartyId(), request.getCounterpartyId())
                || !Objects.equals(existing.getReturnDate(), request.getReturnDate())
                || !Objects.equals(existing.getReason(), request.getReason())
                || !Objects.equals(existing.getRemark(), request.getRemark())
                || !Objects.equals(existing.getCreatedBy(), actorId)
                || !Objects.equals(Boolean.TRUE.equals(existing.getWithGoods()),
                        request.getWithGoods() == null || Boolean.TRUE.equals(request.getWithGoods()))) return false;
        List<String> expected = Optional.ofNullable(request.getItems()).orElse(List.of()).stream()
                .map(this::requestKey).sorted().toList();
        List<String> actual = Optional.ofNullable(returnOrderItemRepository.findByReturnOrderId(existing.getId()))
                .orElse(List.of()).stream().map(this::entityKey).sorted().toList();
        if (!expected.equals(actual)) return false;
        BigDecimal expectedTotal = Optional.ofNullable(request.getItems()).orElse(List.of()).stream()
                .map(item -> item.getQuantity() != null && item.getUnitPrice() != null
                        ? item.getQuantity().multiply(item.getUnitPrice()).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return existing.getTotalAmount() != null && existing.getTotalAmount().compareTo(expectedTotal) == 0;
    }

    private String requestKey(CreateReturnOrderRequest.ReturnOrderItemDTO item) {
        return String.join("|", n(item.getMaterialTypeId()), n(item.getProductTypeId()), n(item.getItemName()),
                decimal(item.getQuantity()), decimal(item.getUnitPrice()), n(item.getBatchNumber()), n(item.getReason()));
    }

    private String entityKey(ReturnOrderItem item) {
        return String.join("|", n(item.getMaterialTypeId()), n(item.getProductTypeId()), n(item.getItemName()),
                decimal(item.getQuantity()), decimal(item.getUnitPrice()), n(item.getBatchNumber()), n(item.getReason()));
    }

    private String decimal(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }
    private String n(String value) { return value == null ? "" : value; }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String firstText(String first, String second) { return hasText(first) ? first : (hasText(second) ? second : null); }
    private BigDecimal positive(BigDecimal value) { return value != null && value.compareTo(BigDecimal.ZERO) > 0 ? value : null; }
    private void exactTwoDecimals(BigDecimal value, String message) {
        try { value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw error(422, message, "PURCHASE_RETURN_PRECISION_INVALID"); }
    }
    private BusinessException error(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code);
    }

    private String actorNamespace(Long actorId) {
        boolean http = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() != null;
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentExecution().orElse(null);
        if (http && actor == null) {
            throw error(401, "缺少已认证数据访问身份", "PURCHASE_RETURN_ACTOR_REQUIRED");
        }
        if (actor != null) {
            if (!Objects.equals(actor.id(), actorId)) {
                throw error(403, "请求用户身份与认证身份不一致", "PURCHASE_RETURN_ACTOR_MISMATCH");
            }
            return actor.type().name() + ":" + actor.id();
        }
        return "FACTORY_USER:" + actorId;
    }

    private void assertSourceAccess(String factoryId, Long createdBy) {
        boolean http = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() != null;
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentExecution().orElse(null);
        if (!http && actor == null) return;
        if (dataScopeResolver == null) {
            throw error(503, "数据范围组件未就绪，访问已停止", "PURCHASE_RETURN_DATASCOPE_UNAVAILABLE");
        }
        if (http) dataScopeResolver.assertCanAccess(factoryId, createdBy);
        else dataScopeResolver.assertActorCanAccess(factoryId, createdBy, actor);
    }
}
