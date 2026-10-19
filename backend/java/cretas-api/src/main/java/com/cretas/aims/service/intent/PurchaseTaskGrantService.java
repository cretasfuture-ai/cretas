package com.cretas.aims.service.intent;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.ai.AiPurchaseTaskGrant;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.ai.AiPurchaseTaskGrantRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.PurchaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Server-side application boundary for a one-shot purchase draft grant. */
@Service
public class PurchaseTaskGrantService {

    public static final String CANONICAL_TOOL = "purchase_order_create";
    private static final String CNY = "CNY";
    private static final String KG = "kg";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    private final AiPurchaseTaskGrantRepository grantRepository;
    private final PurchaseService purchaseService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    public PurchaseTaskGrantService(AiPurchaseTaskGrantRepository grantRepository,
                                    PurchaseService purchaseService,
                                    PurchaseOrderRepository purchaseOrderRepository,
                                    PurchaseOrderItemRepository purchaseOrderItemRepository) {
        this.grantRepository = grantRepository;
        this.purchaseService = purchaseService;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
    }

    @Transactional
    public AiPurchaseTaskGrant issueGrant(GrantSpec spec) {
        Objects.requireNonNull(spec, "grant spec");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = spec.expiresAt() == null ? now.plus(DEFAULT_TTL) : spec.expiresAt();
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(DEFAULT_TTL))) throw reject("grant expiry must be within 15 minutes");
        requireText(spec.factoryId(), "factoryId");
        if (spec.userId() == null || spec.userId() <= 0) throw reject("userId is required");
        requireText(spec.descriptorVersion(), "descriptorVersion");
        requireText(spec.supplierId(), "supplierId");
        requireText(spec.materialTypeId(), "materialTypeId");
        validateMoneyScope(spec.quantityCap(), spec.quantityUnit(), spec.unitPrice(), spec.amountCap(), spec.currency(), spec.taxRate());

        CreatePurchaseOrderRequest approved = defensiveCopy(spec.approvedPurchaseOrderRequest());
        ValidatedInput input = validateAndCanonicalize(spec.supplierId(), spec.materialTypeId(), spec.quantityCap(), spec.unitPrice(), spec.taxRate(), approved);
        String snapshot = canonicalSnapshot(approved);
        AiPurchaseTaskGrant grant = AiPurchaseTaskGrant.builder()
                .factoryId(spec.factoryId()).userId(spec.userId()).toolName(CANONICAL_TOOL)
                .descriptorVersion(spec.descriptorVersion()).supplierId(spec.supplierId()).materialTypeId(spec.materialTypeId())
                .quantityCap(normalize(spec.quantityCap(), 15, 4, "quantityCap")).quantityUnit(KG)
                .unitPrice(normalize(spec.unitPrice(), 15, 4, "unitPrice")).amountCap(normalize(spec.amountCap(), 18, 2, "amountCap"))
                .currency(CNY).taxRate(BigDecimal.ZERO.setScale(2)).inputDigest(sha256(snapshot))
                .approvedInputSnapshot(snapshot).previewTokenId(spec.previewTokenId()).expiresAt(expiresAt).version(1L).build();
        if (input.amount().compareTo(grant.getAmountCap()) > 0) throw reject("approved amount exceeds grant cap");
        return grantRepository.saveAndFlush(grant);
    }

    @Transactional(readOnly = true)
    public Optional<AiPurchaseTaskGrant> inspectGrant(String grantId, String factoryId, Long userId) {
        return grantRepository.findByIdAndFactoryIdAndUserId(grantId, factoryId, userId);
    }

    @Transactional
    public AiPurchaseTaskGrant revokeGrant(String grantId, String factoryId, Long userId,
                                           Long expectedVersion, Long revokedBy, String reason) {
        AiPurchaseTaskGrant grant = lockedGrant(grantId, factoryId, userId);
        if (!Objects.equals(grant.getVersion(), expectedVersion)) throw reject("grant version mismatch");
        if (grant.isConsumed()) throw reject("consumed grant cannot be revoked");
        if (grant.isRevoked()) return grant;
        if (grantRepository.revokeIfCurrent(grantId, factoryId, userId, expectedVersion, LocalDateTime.now(), revokedBy, reason) != 1) throw reject("grant revoke lost the execution race");
        return grantRepository.findById(grantId).orElseThrow(() -> reject("grant disappeared"));
    }

    @Transactional
    public ExecutionResult executeGrant(ExecutionRequest request) {
        Objects.requireNonNull(request, "execution request");
        AiPurchaseTaskGrant grant = lockedGrant(request.grantId(), request.factoryId(), request.userId());
        if (!Objects.equals(grant.getToolName(), CANONICAL_TOOL) || !Objects.equals(grant.getDescriptorVersion(), request.descriptorVersion())) throw reject("grant tool or descriptor version mismatch");
        CreatePurchaseOrderRequest actual = defensiveCopy(request.purchaseOrderRequest());
        ValidatedInput input = validateAndCanonicalize(grant.getSupplierId(), grant.getMaterialTypeId(), grant.getQuantityCap(), grant.getUnitPrice(), grant.getTaxRate(), actual);
        String snapshot = canonicalSnapshot(actual);
        String digest = sha256(snapshot);
        if (!Objects.equals(grant.getInputDigest(), digest) || !Objects.equals(grant.getApprovedInputSnapshot(), snapshot)) throw reject("approved purchase input digest/snapshot mismatch");
        requireText(request.requestIdempotencyKey(), "requestIdempotencyKey");
        if (grant.isConsumed()) {
            if (request.expectedVersion() == null || request.expectedVersion() == Long.MAX_VALUE || grant.getVersion() == null
                    || grant.getVersion() != request.expectedVersion() + 1L) {
                throw reject("consumed grant version mismatch");
            }
            if (Objects.equals(grant.getConsumedRequestKey(), request.requestIdempotencyKey()) && Objects.equals(grant.getConsumedInputDigest(), digest) && grant.getResultPurchaseOrderId() != null) return new ExecutionResult(grant.getResultPurchaseOrderId(), grant.getResultAmount(), grant.getResultQuantity(), grant.getVersion(), true);
            throw reject("grant has already been consumed");
        }
        if (grant.isRevoked()) throw reject("grant is revoked");
        if (!Objects.equals(grant.getVersion(), request.expectedVersion())) throw reject("grant version mismatch");
        LocalDateTime now = LocalDateTime.now();
        if (!grant.getExpiresAt().isAfter(now)) throw reject("grant is expired");

        PurchaseOrder returned = purchaseService.createPurchaseOrder(request.factoryId(), actual, request.userId());
        PersistedDraft persisted = readBackDraft(request.factoryId(), returned);
        verifyPersistedDraft(grant, request, input, persisted.order(), persisted.items());
        if (grantRepository.consumeIfCurrent(grant.getId(), request.factoryId(), request.userId(), grant.getVersion(), now, now, request.requestIdempotencyKey(), digest, persisted.order().getId(), input.quantity(), input.amount()) != 1) throw reject("grant consume lost the execution race");
        return new ExecutionResult(persisted.order().getId(), input.amount(), input.quantity(), grant.getVersion() + 1, false);
    }

    private PersistedDraft readBackDraft(String factoryId, PurchaseOrder returned) {
        if (returned == null || returned.getId() == null) throw reject("purchase service returned no order id");
        purchaseOrderRepository.flush();
        PurchaseOrder persisted = purchaseOrderRepository.findByIdAndFactoryId(returned.getId(), factoryId).orElseThrow(() -> reject("purchase draft was not persisted in the tenant"));
        return new PersistedDraft(persisted, purchaseOrderItemRepository.findByPurchaseOrderId(persisted.getId()));
    }

    private AiPurchaseTaskGrant lockedGrant(String grantId, String factoryId, Long userId) {
        requireText(grantId, "grantId"); requireText(factoryId, "factoryId");
        if (userId == null || userId <= 0) throw reject("userId is required");
        return grantRepository.findForUpdate(grantId, factoryId, userId).orElseThrow(() -> reject("grant not found for caller scope"));
    }

    private static void verifyPersistedDraft(AiPurchaseTaskGrant grant, ExecutionRequest request, ValidatedInput input, PurchaseOrder order, List<PurchaseOrderItem> items) {
        if (!Objects.equals(order.getFactoryId(), request.factoryId()) || !Objects.equals(order.getCreatedBy(), request.userId()) || !Objects.equals(order.getSupplierId(), grant.getSupplierId()) || order.getStatus() != PurchaseOrderStatus.DRAFT || order.getTotalAmount() == null || order.getTotalAmount().compareTo(input.amount()) != 0 || order.getTaxAmount() == null || order.getTaxAmount().signum() != 0) throw reject("persisted purchase draft is outside grant scope");
        if (items == null || items.size() != 1) throw reject("persisted purchase row shape mismatch");
        PurchaseOrderItem item = items.get(0);
        if (!Objects.equals(item.getMaterialTypeId(), grant.getMaterialTypeId()) || item.getQuantity() == null || item.getQuantity().compareTo(input.quantity()) != 0 || !KG.equals(item.getUnit()) || !KG.equals(item.getPriceUnit()) || item.getQuantityToPriceFactor() == null || item.getQuantityToPriceFactor().compareTo(BigDecimal.ONE) != 0 || item.getUnitPrice() == null || item.getUnitPrice().compareTo(input.unitPrice()) != 0 || item.getTaxRate() == null || item.getTaxRate().signum() != 0) throw reject("persisted purchase row does not match approved input");
    }

    private static ValidatedInput validateAndCanonicalize(String supplierId, String materialTypeId, BigDecimal quantityCap, BigDecimal unitPriceCap, BigDecimal taxRate, CreatePurchaseOrderRequest request) {
        if (request == null || !Objects.equals(request.getSupplierId(), supplierId) || request.getItems() == null || request.getItems().size() != 1) throw reject("grant permits exactly one supplier and one material row");
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item = request.getItems().get(0);
        if (!Objects.equals(item.getMaterialTypeId(), materialTypeId) || !KG.equals(item.getUnit()) || !KG.equals(item.getQuantityUnit()) || !KG.equals(item.getPriceUnit())) throw reject("only exact kg quantity and price units are supported");
        BigDecimal quantity = normalize(item.getQuantity(), 15, 4, "quantity");
        BigDecimal price = normalize(item.getUnitPrice(), 15, 4, "unitPrice");
        BigDecimal cap = normalize(quantityCap, 15, 4, "quantityCap");
        BigDecimal grantedPrice = normalize(unitPriceCap, 15, 4, "unitPriceCap");
        BigDecimal actualTax = normalize(item.getTaxRate(), 5, 2, "taxRate");
        BigDecimal grantedTax = normalize(taxRate, 5, 2, "grantTaxRate");
        if (quantity.signum() <= 0 || quantity.compareTo(cap) > 0 || price.signum() <= 0 || price.compareTo(grantedPrice) != 0 || actualTax.signum() != 0 || grantedTax.signum() != 0) throw reject("quantity, positive price, cap and explicit zero tax mismatch");
        BigDecimal amount;
        try {
            amount = quantity.multiply(price).setScale(2, RoundingMode.HALF_UP);
            if (amount.precision() - amount.scale() > 18 - 2) throw reject("amount exceeds database precision");
        } catch (ArithmeticException ex) { throw reject("amount cannot be represented at database scale 2"); }
        item.setQuantity(quantity); item.setUnitPrice(price); item.setTaxRate(BigDecimal.ZERO.setScale(2));
        return new ValidatedInput(quantity, price, amount);
    }

    private static CreatePurchaseOrderRequest defensiveCopy(CreatePurchaseOrderRequest source) {
        if (source == null) throw reject("approved purchase DTO is required");
        CreatePurchaseOrderRequest copy = new CreatePurchaseOrderRequest();
        copy.setSupplierId(source.getSupplierId()); copy.setPurchaseType(source.getPurchaseType()); copy.setOrderDate(source.getOrderDate()); copy.setExpectedDeliveryDate(source.getExpectedDeliveryDate()); copy.setRemark(source.getRemark()); copy.setSalesOrderId(source.getSalesOrderId()); copy.setContractNumber(source.getContractNumber()); copy.setSettlementType(source.getSettlementType()); copy.setInvoiceReminderDays(source.getInvoiceReminderDays()); copy.setVersion(source.getVersion());
        copy.setItems(source.getItems() == null ? null : source.getItems().stream().map(PurchaseTaskGrantService::copyItem).toList());
        return copy;
    }

    private static CreatePurchaseOrderRequest.PurchaseOrderItemDTO copyItem(CreatePurchaseOrderRequest.PurchaseOrderItemDTO source) {
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO copy = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        copy.setMaterialTypeId(source.getMaterialTypeId()); copy.setSupplierMaterialId(source.getSupplierMaterialId()); copy.setPurchasePackagingSpecId(source.getPurchasePackagingSpecId()); copy.setMaterialPackagingSpecId(source.getMaterialPackagingSpecId()); copy.setMaterialName(source.getMaterialName()); copy.setQuantity(source.getQuantity()); copy.setUnit(source.getUnit()); copy.setQuantityUnit(source.getQuantityUnit()); copy.setUnitPrice(source.getUnitPrice()); copy.setPriceUnit(source.getPriceUnit()); copy.setTaxRate(source.getTaxRate()); copy.setRemark(source.getRemark()); copy.setSpecification(source.getSpecification()); copy.setContractNumber(source.getContractNumber()); copy.setBoxQuantity(source.getBoxQuantity());
        return copy;
    }

    private static String canonicalSnapshot(CreatePurchaseOrderRequest request) {
        StringBuilder out = new StringBuilder();
        append(out, request.getSupplierId()); append(out, request.getPurchaseType()); append(out, request.getOrderDate()); append(out, request.getExpectedDeliveryDate()); append(out, request.getRemark()); append(out, request.getSalesOrderId()); append(out, request.getContractNumber()); append(out, request.getSettlementType()); append(out, request.getInvoiceReminderDays()); append(out, request.getVersion());
        List<CreatePurchaseOrderRequest.PurchaseOrderItemDTO> items = request.getItems(); append(out, items == null ? null : items.size());
        if (items != null) for (CreatePurchaseOrderRequest.PurchaseOrderItemDTO item : items) { append(out, item.getMaterialTypeId()); append(out, item.getSupplierMaterialId()); append(out, item.getPurchasePackagingSpecId()); append(out, item.getMaterialPackagingSpecId()); append(out, item.getMaterialName()); append(out, item.getQuantity()); append(out, item.getUnit()); append(out, item.getQuantityUnit()); append(out, item.getUnitPrice()); append(out, item.getPriceUnit()); append(out, item.getTaxRate()); append(out, item.getRemark()); append(out, item.getSpecification()); append(out, item.getContractNumber()); append(out, item.getBoxQuantity()); }
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) { String text = value == null ? "<NULL>" : value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString() : String.valueOf(value); out.append(text.length()).append(':').append(text).append('|'); }

    private static BigDecimal normalize(BigDecimal value, int precision, int scale, String name) {
        if (value == null) throw reject(name + " is required");
        try { BigDecimal normalized = value.setScale(scale, RoundingMode.UNNECESSARY); if (normalized.precision() - normalized.scale() > precision - scale) throw reject(name + " exceeds database precision"); return normalized; }
        catch (ArithmeticException ex) { throw reject(name + " cannot be represented at database scale " + scale); }
    }

    private static void validateMoneyScope(BigDecimal quantityCap, String quantityUnit, BigDecimal unitPrice, BigDecimal amountCap, String currency, BigDecimal taxRate) {
        BigDecimal quantity = normalize(quantityCap, 15, 4, "quantityCap"); BigDecimal price = normalize(unitPrice, 15, 4, "unitPrice"); BigDecimal amount = normalize(amountCap, 18, 2, "amountCap"); BigDecimal tax = normalize(taxRate, 5, 2, "taxRate");
        if (!KG.equals(quantityUnit) || quantity.signum() <= 0 || price.signum() <= 0 || amount.signum() <= 0 || !CNY.equals(currency) || tax.signum() != 0) throw reject("grant requires positive kg quantity/price, explicit CNY and zero tax");
    }

    private static String sha256(String value) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(64); for (byte item : digest) result.append(String.format("%02x", item)); return result.toString(); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    private static void requireText(String value, String name) { if (value == null || value.isBlank()) throw reject(name + " is required"); }
    private static GrantRejectedException reject(String message) { return new GrantRejectedException(message); }
    private record ValidatedInput(BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) { }
    private record PersistedDraft(PurchaseOrder order, List<PurchaseOrderItem> items) { }

    public record GrantSpec(String factoryId, Long userId, String descriptorVersion, String supplierId, String materialTypeId, BigDecimal quantityCap, String quantityUnit, BigDecimal unitPrice, BigDecimal amountCap, String currency, BigDecimal taxRate, LocalDateTime expiresAt, Long previewTokenId, CreatePurchaseOrderRequest approvedPurchaseOrderRequest) { }
    public record ExecutionRequest(String grantId, Long expectedVersion, String factoryId, Long userId, String descriptorVersion, String requestIdempotencyKey, CreatePurchaseOrderRequest purchaseOrderRequest) { }
    public record ExecutionResult(String purchaseOrderId, BigDecimal amount, BigDecimal quantity, Long grantVersion, boolean replay) { }
    public static final class GrantRejectedException extends RuntimeException { public GrantRejectedException(String message) { super(message); } }
}
