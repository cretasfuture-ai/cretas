package com.cretas.aims.service.intent;

import com.cretas.aims.ai.tool.gateway.*;
import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.ai.AiPurchaseTaskGrant;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.ai.AiPurchaseTaskGrantRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** The canonical AI purchase lane. No request map can establish confirmation or identity. */
@Service
@RequiredArgsConstructor
public class ControlledPurchaseTaskExecutionService {
    public static final String VERSION = "2.0.0";
    private final PurchaseTaskGrantService grants;
    private final AiPurchaseTaskGrantRepository grantRepository;
    private final IntentPreviewTokenRepository tokenRepository;
    private final ToolPrincipalPolicy principalPolicy;
    private final AuthenticatedToolPrincipalFactory principalFactory;
    private final RawMaterialTypeRepository materials;
    private final SupplierRepository suppliers;
    private final PreviewTokenService previewTokens;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;
    private final org.springframework.beans.factory.ObjectProvider<com.cretas.aims.ai.tool.ToolRegistry> tools;
    private final com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository frontlineGrants;

    public static String grantId(Long previewId) {
        if (previewId == null || previewId <= 0) throw denied("预览标识无效");
        return "preview-" + previewId;
    }

    /** Called before hashing/issuing the preview token; freezes date, DTO, units and money. */
    @Transactional(readOnly = true)
    public Map<String, Object> previewParameters(String factoryId, Map<String, Object> input) {
        authorize(factoryId);
        String supplier = text(input, "supplierId");
        String material = text(input, "materialTypeId");
        if (!"CNY".equals(text(input, "currency")) || !"kg".equals(text(input, "unit"))) {
            throw denied("受控采购仅支持明确的CNY/kg");
        }
        requireReferences(factoryId, supplier, material);
        BigDecimal quantity = decimal(input, "quantity");
        BigDecimal price = decimal(input, "unitPrice");
        BigDecimal rate = decimal(input, "taxRate");
        PurchaseTaskMoneyContract.Amounts amounts = PurchaseTaskMoneyContract.calculate(quantity, price, rate);
        BigDecimal budget = decimal(input, "amountCap").setScale(2, java.math.RoundingMode.UNNECESSARY);
        if (budget.compareTo(amounts.gross()) < 0 || budget.precision() - budget.scale() > 16) {
            throw denied("含税预算不足或超出金额精度");
        }
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO row = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        row.setMaterialTypeId(material); row.setQuantity(quantity); row.setUnitPrice(price);
        row.setTaxRate(rate); row.setUnit("kg"); row.setQuantityUnit("kg"); row.setPriceUnit("kg");
        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setSupplierId(supplier); request.setPurchaseType("DIRECT"); request.setOrderDate(LocalDate.now());
        request.setItems(List.of(row));
        if (input.get("expectedDeliveryDate") != null) request.setExpectedDeliveryDate(LocalDate.parse(text(input, "expectedDeliveryDate")));
        if (input.get("remark") != null) request.setRemark(text(input, "remark"));
        // JSON number node types/scales can change after persistence. Bind stable text values,
        // never relax the shared preview service's exact command digest validation.
        com.fasterxml.jackson.databind.node.ObjectNode dto = mapper.valueToTree(request);
        dto.put("orderDate", request.getOrderDate().toString());
        if (request.getExpectedDeliveryDate() != null) dto.put("expectedDeliveryDate", request.getExpectedDeliveryDate().toString());
        var itemNode = (com.fasterxml.jackson.databind.node.ObjectNode) dto.withArray("items").get(0);
        itemNode.put("quantity", quantity.toPlainString()); itemNode.put("unitPrice", price.toPlainString()); itemNode.put("taxRate", rate.toPlainString());
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("purchaseOrderRequest", mapper.convertValue(dto, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        canonical.put("currency", "CNY"); canonical.put("amountCap", budget.toPlainString());
        canonical.put("net", amounts.net().toPlainString()); canonical.put("tax", amounts.tax().toPlainString()); canonical.put("gross", amounts.gross().toPlainString());
        return canonical;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(String factoryId, Map<String, Object> parameters) {
        authorize(factoryId);
        CreatePurchaseOrderRequest request = purchase(parameters);
        requireReferences(factoryId, request.getSupplierId(), request.getItems().get(0).getMaterialTypeId());
        return Map.of("status", "PREVIEW", "newValues", parameters,
                "message", "仅创建采购草稿；含税预算受限，后续仍需独立采购及财务OA审批");
    }

    /** Reload the claimed persisted proof under lock. Caller-supplied claim/token objects are never authority. */
    @Transactional
    public Outcome executeConfirmed(String factoryId, Long previewId, String claimId) {
        entityManager.flush();
        entityManager.clear();
        AuthenticatedActor actor = authorize(factoryId);
        IntentPreviewToken token = entityManager.find(IntentPreviewToken.class, previewId, LockModeType.PESSIMISTIC_WRITE);
        requireToken(token, factoryId, actor.id());
        if (token.getStatus() != IntentPreviewToken.TokenStatus.EXECUTING || claimId == null || !claimId.equals(token.getClaimId())) {
            throw denied("缺少已验证的服务端确认认领");
        }
        if (!LocalDateTime.now().isBefore(token.getExpiresAt())) throw denied("确认已过期");
        Map<String, Object> parameters = parameters(token);
        CreatePurchaseOrderRequest request = purchase(parameters);
        var row = request.getItems().get(0);
        requireReferences(factoryId, request.getSupplierId(), row.getMaterialTypeId());
        String id = grantId(previewId);
        AiPurchaseTaskGrant grant = grantRepository.findByIdAndFactoryIdAndUserId(id, factoryId, actor.id()).orElse(null);
        if (grant == null) {
            grant = grants.issueConfirmedGrant(new PurchaseTaskGrantService.GrantSpec(factoryId, actor.id(), VERSION,
                    request.getSupplierId(), row.getMaterialTypeId(), row.getQuantity(), "kg", row.getUnitPrice(),
                    decimal(parameters, "amountCap"), text(parameters, "currency"), row.getTaxRate(), token.getExpiresAt(), token.getId(), request), id);
        }
        requireGrantBinding(grant, token.getId(), factoryId, actor.id());
        var result = grants.executeGrant(new PurchaseTaskGrantService.ExecutionRequest(grant.getId(),
                grant.isConsumed() ? grant.getVersion() - 1 : grant.getVersion(), factoryId, actor.id(), VERSION,
                id, request));
        // PurchaseService, grant consumption and these validations all share the same transaction.
        entityManager.flush();
        entityManager.clear();
        authorize(factoryId);
        requireReferences(factoryId, request.getSupplierId(), row.getMaterialTypeId());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                entityManager.flush();
                entityManager.clear();
                authorize(factoryId);
                IntentPreviewToken current = entityManager.find(IntentPreviewToken.class, previewId);
                entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
                requireToken(current, factoryId, actor.id());
                if (!claimId.equals(current.getClaimId()) || current.getStatus() != IntentPreviewToken.TokenStatus.EXECUTING
                        || !LocalDateTime.now().isBefore(current.getExpiresAt())) throw denied("确认在提交前失效");
                requireReferences(factoryId, request.getSupplierId(), row.getMaterialTypeId());
            }
        });
        return new Outcome(id, "DRAFT", result.purchaseOrderId(), result.amount(), result.quantity(), result.grantVersion(), result.replay());
    }

    /**
     * Confirm an immutable preview for later execution.  This path deliberately does
     * not call PurchaseService; it only creates the durable grant after the persisted
     * opaque proof has been claimed and the current actor/references are rechecked.
     */
    @Transactional
    public Outcome authorizeForLater(String factoryId, ConfirmationProof proof) {
        AuthenticatedActor actor = authorize(factoryId);
        if (proof == null || proof.proofToken() == null || proof.proofToken().isBlank()
                || proof.commandDigest() == null || proof.commandDigest().isBlank()) {
            throw denied("缺少服务端确认凭据");
        }
        var claim = previewTokens.claimToken(proof.proofToken(), factoryId, actor.id(), proof.commandDigest());
        if (claim == null || !claim.isSuccess() || claim.getToken() == null || claim.getClaimId() == null) {
            throw denied(claim == null ? "确认凭据不可用" : claim.getMessage());
        }
        IntentPreviewToken token = claim.getToken();
        requireToken(token, factoryId, actor.id());
        if (!Objects.equals(proof.commandDigest(), token.getCommandDigest())) throw denied("确认摘要不匹配");
        if (!LocalDateTime.now().isBefore(token.getExpiresAt())) throw denied("确认授权已过期");
        Map<String, Object> parameters = parameters(token);
        CreatePurchaseOrderRequest request = purchase(parameters);
        var row = request.getItems().get(0);
        requireReferences(factoryId, request.getSupplierId(), row.getMaterialTypeId());
        String id = grantId(token.getId());
        AiPurchaseTaskGrant grant = grants.issueConfirmedGrant(new PurchaseTaskGrantService.GrantSpec(
                factoryId, actor.id(), VERSION, request.getSupplierId(), row.getMaterialTypeId(), row.getQuantity(),
                "kg", row.getUnitPrice(), decimal(parameters, "amountCap"), text(parameters, "currency"),
                row.getTaxRate(), token.getExpiresAt(), token.getId(), request), id);
        requireGrantBinding(grant, token.getId(), factoryId, actor.id());
        if (!previewTokens.resolveClaim(proof.proofToken(), claim.getClaimId(), true, "采购授权已确认，等待稍后执行")) {
            throw denied("确认授权状态写入失败");
        }
        entityManager.flush();
        registerDeferredGrantAdmission(factoryId, actor.id(), token.getId(), id, request, false);
        return outcome(grant);
    }

    /** Execute a previously confirmed deferred grant. The request body carries only CAS state. */
    @Transactional
    public Outcome executeLater(String factoryId, String id, Long expectedVersion) {
        return executeDeferred(factoryId, id, expectedVersion, null);
    }

    /** Timer-only seam: identity is a persisted delegation, never request attributes or ThreadLocal. */
    @Transactional
    AiPurchaseTaskGrant admitTimerBinding(String factoryId, String id, Long expectedVersion) {
        AuthenticatedActor actor = authorize(factoryId);
        IntentPreviewToken token = entityManager.find(IntentPreviewToken.class, previewId(id), LockModeType.PESSIMISTIC_WRITE);
        requireToken(token, factoryId, actor.id());
        AiPurchaseTaskGrant grant = grantRepository.findForUpdate(id, factoryId, actor.id())
                .orElseThrow(() -> denied("未找到当前用户的采购授权"));
        requireGrantBinding(grant, token.getId(), factoryId, actor.id());
        if (token.getStatus()!=IntentPreviewToken.TokenStatus.CONFIRMED || grant.isRevoked() || grant.isConsumed()
                || !Objects.equals(grant.getVersion(),expectedVersion) || expectedVersion==null
                || !LocalDateTime.now().isBefore(token.getExpiresAt())) throw denied("定时采购需要当前已确认、未消费且未过期的授权");
        CreatePurchaseOrderRequest request=purchase(parameters(token));
        requireReferences(factoryId,request.getSupplierId(),request.getItems().get(0).getMaterialTypeId());
        registerDeferredGrantAdmission(factoryId,actor.id(),token.getId(),id,request,false);
        return grant;
    }

    boolean canViewTimerSnapshot(String factoryId) {
        var current=identity(factoryId);
        return current.principal()!=null && Boolean.TRUE.equals(current.principal().executionContext().get("canViewPurchasePrices"));
    }

    @Transactional
    Outcome executeScheduled(PurchaseGrantTimerActor timer) {
        var b = timer.binding();
        return executeDeferred(b.getFactoryId(), b.getGrantId(), b.getExpectedGrantVersion(), timer);
    }

    private Outcome executeDeferred(String factoryId, String id, Long expectedVersion, PurchaseGrantTimerActor timer) {
        AuthenticatedActor actor = timer == null ? identity(factoryId).actor() : identity(factoryId, timer.current(entityManager)).actor();
        if (expectedVersion == null || expectedVersion <= 0) throw denied("缺少授权版本");
        long previewId = previewId(id);
        IntentPreviewToken token = entityManager.find(IntentPreviewToken.class, previewId, LockModeType.PESSIMISTIC_WRITE);
        requireToken(token, factoryId, actor.id());
        AiPurchaseTaskGrant grant = grantRepository.findForUpdate(id, factoryId, actor.id())
                .orElseThrow(() -> denied("未找到当前用户的采购授权"));
        requireGrantBinding(grant, token.getId(), factoryId, actor.id());
        if (timer == null && entityManager.find(com.cretas.aims.entity.ai.PurchaseGrantTimerBinding.class,
                PurchaseGrantTimerActor.bindingId(id)) != null) throw denied("此授权已绑定一次定时执行，不能由手动HTTP/Tool/Skill消费");
        if (timer != null) {
            var b = timer.binding();
            if (!Objects.equals(b.getScopeDigest(), PurchaseGrantTimerActor.scopeDigest(grant))
                    || !Objects.equals(b.getInputDigest(), grant.getInputDigest())
                    || !Objects.equals(b.getInputSnapshot(), grant.getApprovedInputSnapshot())
                    || !Objects.equals(b.getCommandDigest(), token.getCommandDigest())
                    || !Objects.equals(b.getExpiresAt(), grant.getExpiresAt())) throw denied("定时绑定业务快照已变化");
        }
        if (grant.isConsumed()) {
            if (grant.getVersion() == null || !Objects.equals(grant.getVersion(), expectedVersion + 1L)) {
                throw denied("授权版本冲突");
            }
            if (timer != null) throw denied("已消费授权仅允许持久结果核对，不再执行");
            return replay(factoryId, new ConfirmationProof(token.getToken(), token.getCommandDigest(),
                    Instant.now())).orElseThrow(() -> denied("已消费授权结果不可读"));
        }
        authorize(factoryId, timer);
        if (token.getStatus() != IntentPreviewToken.TokenStatus.CONFIRMED) throw denied("授权尚未确认或结果未知");
        if (!LocalDateTime.now().isBefore(token.getExpiresAt())) throw denied("确认授权已过期");
        Map<String, Object> parameters = parameters(token);
        CreatePurchaseOrderRequest request = purchase(parameters);
        var row = request.getItems().get(0);
        requireReferences(factoryId, request.getSupplierId(), row.getMaterialTypeId());
        if (!Objects.equals(grant.getVersion(), expectedVersion)) {
            throw denied("授权版本冲突");
        }
        var result = grants.executeGrant(new PurchaseTaskGrantService.ExecutionRequest(
                id, expectedVersion, factoryId, actor.id(), VERSION,
                timer == null ? "deferred:" + id : "timer:" + timer.binding().getId(), request));
        entityManager.flush();
        registerDeferredGrantAdmission(factoryId, actor.id(), token.getId(), id, request, true, timer);
        return new Outcome(id, "DRAFT", result.purchaseOrderId(), result.amount(), result.quantity(), result.grantVersion(), result.replay());
    }

    private void registerDeferredGrantAdmission(String factoryId, Long userId, Long previewId,
                                                String grantId, CreatePurchaseOrderRequest request,
                                                boolean consumedRequired) {
        registerDeferredGrantAdmission(factoryId, userId, previewId, grantId, request, consumedRequired, null);
    }
    private void registerDeferredGrantAdmission(String factoryId, Long userId, Long previewId,
                                                String grantId, CreatePurchaseOrderRequest request,
                                                boolean consumedRequired, PurchaseGrantTimerActor timer) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                entityManager.flush();
                entityManager.clear();
                AuthenticatedActor actor = authorize(factoryId, timer);
                if (!Objects.equals(actor.id(), userId)) throw denied("授权主体在提交前发生变化");
                IntentPreviewToken current = entityManager.find(IntentPreviewToken.class, previewId,
                        LockModeType.PESSIMISTIC_WRITE);
                requireToken(current, factoryId, userId);
                if (current.getStatus() != IntentPreviewToken.TokenStatus.CONFIRMED) {
                    throw denied("授权状态在提交前发生变化");
                }
                if (!LocalDateTime.now().isBefore(current.getExpiresAt())) throw denied("确认授权已过期");
                AiPurchaseTaskGrant currentGrant = grantRepository.findForUpdate(grantId, factoryId, userId)
                        .orElseThrow(() -> denied("授权在提交前消失"));
                requireGrantBinding(currentGrant, previewId, factoryId, userId);
                if (consumedRequired != currentGrant.isConsumed()) throw denied("授权消费状态在提交前发生变化");
                requireReferences(factoryId, request.getSupplierId(), request.getItems().get(0).getMaterialTypeId());
            }
        });
    }

    @Transactional(readOnly = true)
    public Optional<Outcome> replay(String factoryId, ConfirmationProof proof) {
        return tokenRepository.findByToken(proof.proofToken())
                .filter(token -> PurchaseTaskGrantService.CANONICAL_TOOL.equals(token.getToolName())).flatMap(token -> {
            Authorized current = identity(factoryId);
            AuthenticatedActor actor = current.actor();
            requireToken(token, factoryId, actor.id());
            if (!Objects.equals(proof.commandDigest(), token.getCommandDigest())) throw denied("确认摘要不匹配");
            return grantRepository.findByIdAndFactoryIdAndUserId(grantId(token.getId()), factoryId, actor.id())
                    .filter(AiPurchaseTaskGrant::isConsumed).map(g -> {
                        requireGrantBinding(g, token.getId(), factoryId, actor.id());
                        return visible(outcome(g), current);
                    });
        });
    }

    @Transactional(readOnly = true)
    public Outcome status(String factoryId, String id) {
        Authorized current = identity(factoryId);
        AuthenticatedActor actor = current.actor();
        long preview = previewId(id);
        var grant = grantRepository.findByIdAndFactoryIdAndUserId(id, factoryId, actor.id());
        if (grant.isPresent()) {
            requireGrantBinding(grant.get(), preview, factoryId, actor.id());
            return visible(outcome(grant.get()), current);
        }
        IntentPreviewToken token = tokenRepository.findById(preview).orElseThrow(() -> denied("未找到当前用户的预览"));
        requireToken(token, factoryId, actor.id());
        String state = token.getStatus() == IntentPreviewToken.TokenStatus.EXECUTING ? "UNKNOWN" : token.getStatus().name();
        if (token.getStatus() == IntentPreviewToken.TokenStatus.CANCELLED) state = "REVOKED";
        if (token.getStatus() == IntentPreviewToken.TokenStatus.PENDING && !LocalDateTime.now().isBefore(token.getExpiresAt())) state = "EXPIRED";
        return new Outcome(id, state, null, null, null, null, false);
    }

    @Transactional
    public Outcome revoke(String factoryId, String id, Long expectedVersion, String reason) {
        AuthenticatedActor actor = identity(factoryId).actor();
        IntentPreviewToken token = entityManager.find(IntentPreviewToken.class, previewId(id), LockModeType.PESSIMISTIC_WRITE);
        requireToken(token, factoryId, actor.id());
        Optional<AiPurchaseTaskGrant> existing = grantRepository.findByIdAndFactoryIdAndUserId(id, factoryId, actor.id());
        if (existing.isPresent()) {
            requireGrantBinding(existing.get(), token.getId(), factoryId, actor.id());
            return outcome(grants.revokeGrant(id, factoryId, actor.id(), expectedVersion, actor.id(), reason));
        }
        if (token.getStatus() != IntentPreviewToken.TokenStatus.PENDING) throw denied("确认已处理或结果未知，禁止撤销或重试写入");
        if (!previewTokens.cancelToken(token.getToken(), factoryId, actor.id(), reason)) throw denied("撤销与确认冲突");
        return new Outcome(id, "REVOKED", null, null, null, null, false);
    }

    private AuthenticatedActor authorize(String factoryId) {
        return authorize(factoryId, null);
    }
    private AuthenticatedActor authorize(String factoryId, PurchaseGrantTimerActor timer) {
        Authorized current = timer == null ? identity(factoryId) : identity(factoryId, timer.current(entityManager));
        var principal = current.principal();
        if (principal == null) throw denied("当前授权不可用");
        if (!principal.principal().permissions().contains("procurement:read_write")
                || !Boolean.TRUE.equals(principal.executionContext().get("canViewPurchasePrices"))) throw denied("缺少当前采购写入或价格权限");
        if (!tools.getObject().isToolEnabledForFactory(factoryId, PurchaseTaskGrantService.CANONICAL_TOOL)) throw denied("采购工具已停用");
        String role = principal.principal().roles().iterator().next();
        if (FrontlineAiWriteGrantService.isFrontline(role)
                && !frontlineGrants.findByFactoryIdAndRevokedAtIsNull(factoryId).stream()
                .anyMatch(g -> role.equals(g.getRoleCode()) && "PURCHASE_ORDER_CREATE".equals(g.getIntentCode()))) {
            throw denied("当前一线角色未获采购AI办理授权");
        }
        return current.actor();
    }

    private Authorized identity(String factoryId) {
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentRequest()
                .filter(a -> a.type() == AuthenticatedActorType.FACTORY_USER && Objects.equals(factoryId, a.factoryId()))
                .orElseThrow(() -> denied("缺少当前租户的可信登录主体"));
        return identity(factoryId, actor);
    }

    private Authorized identity(String factoryId, AuthenticatedActor actor) {
        if (actor.type() != AuthenticatedActorType.FACTORY_USER || !factoryId.equals(actor.factoryId()))
            throw denied("缺少当前租户的可信登录主体");
        // Refresh managed authorization rows; the policy must not reuse a stale first-level snapshot.
        var user = entityManager.find(com.cretas.aims.entity.User.class, actor.id());
        var factory = entityManager.find(com.cretas.aims.entity.Factory.class, factoryId);
        if (user == null || factory == null) throw denied("登录主体或工厂已失效");
        entityManager.refresh(user); entityManager.refresh(factory);
        if (!Boolean.TRUE.equals(user.getIsActive()) || !Boolean.TRUE.equals(factory.getIsActive())
                || !factoryId.equals(user.getFactoryId())) throw denied("登录主体或工厂已失效");
        try {
            return new Authorized(actor, principalPolicy.rehydrate(principalFactory.create(factoryId, actor.id(), actor.role())).orElse(null));
        } catch (RuntimeException unavailablePermissionConfiguration) {
            // Identity is independently verified. Recovery may return ids/state with money redacted;
            // write admission above still fails closed when current authorization is unavailable.
            return new Authorized(actor, null);
        }
    }

    private void requireReferences(String factory, String supplier, String material) {
        var supplierRow = suppliers.findByIdAndFactoryId(supplier, factory).orElseThrow(() -> denied("采购供应商不属于当前工厂"));
        var materialRow = materials.findByIdAndFactoryId(material, factory).orElseThrow(() -> denied("采购物料不属于当前工厂"));
        if (entityManager.contains(supplierRow)) entityManager.refresh(supplierRow);
        if (entityManager.contains(materialRow)) entityManager.refresh(materialRow);
        if (!factory.equals(supplierRow.getFactoryId()) || !factory.equals(materialRow.getFactoryId())) throw denied("采购主数据租户在执行期间发生变化");
        if (!Boolean.TRUE.equals(supplierRow.getIsActive()) || !Boolean.TRUE.equals(materialRow.getIsActive())) throw denied("采购供应商或物料已停用");
    }

    private void requireToken(IntentPreviewToken token, String factory, Long user) {
        if (token == null || !Objects.equals(factory, token.getFactoryId()) || !Objects.equals(factory, token.getTenantId())
                || !Objects.equals(user, token.getUserId()) || !PurchaseTaskGrantService.CANONICAL_TOOL.equals(token.getToolName())
                || !"PURCHASE_ORDER_CREATE".equals(token.getIntentCode())
                || !VERSION.equals(token.getDescriptorVersion()) || token.getExecutionMode() != ToolExecutionMode.EXECUTE
                || !token.isBoundForExecution()) throw denied("确认凭据不属于当前采购范围");
        parameters(token);
    }

    private Map<String, Object> parameters(IntentPreviewToken token) {
        try {
            var node = mapper.readTree(token.getPreviewData());
            if (!ToolCommandDigest.parametersHash(node).equals(token.getParametersHash())
                    || !ToolCommandDigest.commandDigest(token.getFactoryId(), token.getUserId(), token.getToolName(),
                    token.getDescriptorVersion(), token.getExecutionMode(), node).equals(token.getCommandDigest())) throw denied("预览内容摘要发生变化");
            return mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (java.io.IOException invalid) { throw denied("预览内容不可读"); }
    }

    private CreatePurchaseOrderRequest purchase(Map<String, Object> parameters) {
        if (!"CNY".equals(text(parameters, "currency"))) throw denied("仅支持CNY");
        CreatePurchaseOrderRequest request = mapper.convertValue(parameters.get("purchaseOrderRequest"), CreatePurchaseOrderRequest.class);
        if (request == null || request.getItems() == null || request.getItems().size() != 1) throw denied("仅支持单行采购草稿");
        var row = request.getItems().get(0);
        if (!"kg".equals(row.getUnit()) || !"kg".equals(row.getQuantityUnit()) || !"kg".equals(row.getPriceUnit())) throw denied("仅支持kg数量及价格单位");
        var amount = PurchaseTaskMoneyContract.calculate(row.getQuantity(), row.getUnitPrice(), row.getTaxRate());
        if (amount.net().compareTo(decimal(parameters, "net")) != 0 || amount.tax().compareTo(decimal(parameters, "tax")) != 0
                || amount.gross().compareTo(decimal(parameters, "gross")) != 0 || amount.gross().compareTo(decimal(parameters, "amountCap")) > 0) throw denied("含税预算或金额摘要不一致");
        return request;
    }

    private Outcome outcome(AiPurchaseTaskGrant g) {
        String state = g.isConsumed() ? "DRAFT" : g.isRevoked() ? "REVOKED" : LocalDateTime.now().isBefore(g.getExpiresAt()) ? "PENDING" : "EXPIRED";
        return new Outcome(g.getId(), state, g.getResultPurchaseOrderId(), g.getResultAmount(), g.getResultQuantity(), g.getVersion(), g.isConsumed());
    }
    private static Outcome visible(Outcome result, Authorized current) {
        if (current.principal() != null && Boolean.TRUE.equals(current.principal().executionContext().get("canViewPurchasePrices"))) return result;
        return new Outcome(result.grantId(), result.state(), result.purchaseOrderId(), null, result.quantity(), result.version(), result.replay());
    }
    private record Authorized(AuthenticatedActor actor, ToolPrincipalPolicy.RehydratedPrincipal principal) { }
    private static void requireGrantBinding(AiPurchaseTaskGrant g, Long preview, String factory, Long user) {
        if (!Objects.equals(preview, g.getPreviewTokenId()) || !Objects.equals(factory,g.getFactoryId())
                || !Objects.equals(user,g.getUserId()) || !PurchaseTaskGrantService.CANONICAL_TOOL.equals(g.getToolName())
                || !VERSION.equals(g.getDescriptorVersion())) throw denied("持久采购授权与预览绑定不一致");
    }
    private static long previewId(String id) {
        try {
            if (id == null || !id.startsWith("preview-")) throw new NumberFormatException();
            long parsed = Long.parseLong(id.substring(8));
            if (parsed <= 0 || !id.equals(grantId(parsed))) throw new NumberFormatException();
            return parsed;
        }
        catch (NumberFormatException invalid) { throw denied("采购授权标识无效"); }
    }
    private static BigDecimal decimal(Map<String, Object> values, String name) {
        try { return new BigDecimal(text(values, name)); }
        catch (NumberFormatException invalid) { throw denied(name + "必须为明确数值"); }
    }
    private static String text(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value == null || value.toString().isBlank()) throw denied("缺少" + name);
        return value.toString();
    }
    private static BusinessException denied(String message) { return new BusinessException(403, message); }
    public record Outcome(String grantId, String state, String purchaseOrderId, BigDecimal grossAmount, BigDecimal quantity, Long version, boolean replay) { }
}
