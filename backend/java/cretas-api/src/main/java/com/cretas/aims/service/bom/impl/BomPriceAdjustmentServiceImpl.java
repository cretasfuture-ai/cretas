package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.bom.BomPriceAdjustmentAudit;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal.SourceType;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal.Status;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.bom.BomPriceAdjustmentAuditRepository;
import com.cretas.aims.repository.bom.BomPriceAdjustmentProposalRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.bom.BomPriceAdjustmentService;
import com.cretas.aims.service.bom.BomRecipeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * BOM 调价建议 (B9 BOM 价随采购浮动)。
 *
 * <p>两条口径 (2026-11-10, D-S5-10 / D-S5-11):</p>
 * <ol>
 *   <li><b>历史版本的成本不回写。</b>只为可改的配方版本 (DRAFT / ACTIVE) 生成建议; 批准时再按
 *       当时 (加锁读) 的版本状态校验一次 —— 旧建议可能是在版本归档之前生成的。</li>
 *   <li><b>批准的就是审批人看到的那个数。</b>BOM 行价格只继承物料档案价 (移动均价优先, 否则档案单价;
 *       与 {@code BomRecipeServiceImpl#applyMaterialMasterPricing} 同一口径, 不形成第二套价格真值),
 *       所以建议价 = 生成时的档案价; 批准时写入的就是建议行上的 {@code proposedUnitPrice},
 *       建议行不再改写。若档案价在此期间变了, 拒绝 (409 STALE) 让人重新生成, ⛔ 不静默换成新价。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BomPriceAdjustmentServiceImpl implements BomPriceAdjustmentService {

    /** 批准时目标配方版本不可改 (已归档 / 已删除)。 */
    public static final String ERROR_RECIPE_NOT_EDITABLE = "BOM_PRICE_PROPOSAL_RECIPE_NOT_EDITABLE";
    /** 建议价与物料档案当前价不一致: 建议已过期, 须重新生成。 */
    public static final String ERROR_STALE = "BOM_PRICE_PROPOSAL_STALE";

    /** 价格可被调价建议改写的配方版本状态; ARCHIVED (历史版本) 不在其中。 */
    private static final Set<BomRecipe.Status> PRICE_WRITABLE_RECIPE_STATUSES =
            EnumSet.of(BomRecipe.Status.DRAFT, BomRecipe.Status.ACTIVE);

    private final BomRecipeItemRepository itemRepository;
    private final BomRecipeRepository recipeRepository;
    private final BomPriceAdjustmentProposalRepository proposalRepository;
    private final BomPriceAdjustmentAuditRepository auditRepository;
    private final RawMaterialTypeRepository materialRepository;
    /** 行价改了之后重算配方头成本 —— 与编辑 / 成本更正同一套实现 (审查 D)。 */
    private final BomRecipeService bomRecipeService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard processSecretGuard;

    @Override
    @Transactional
    public List<BomPriceAdjustmentProposal> generateFromReceive(String factoryId, PurchaseReceiveRecord receiveRecord) {
        if (receiveRecord == null || receiveRecord.getItems() == null) {
            return List.of();
        }
        List<BomPriceAdjustmentProposal> proposals = new ArrayList<>();
        for (PurchaseReceiveItem item : receiveRecord.getItems()) {
            proposals.addAll(generateForMaterial(
                    factoryId,
                    item.getMaterialTypeId(),
                    item.getMaterialName(),
                    item.getUnitPrice(),
                    SourceType.PURCHASE_RECEIVE,
                    receiveRecord.getId(),
                    item.getId()));
        }
        return proposals;
    }

    @Override
    @Transactional
    public List<BomPriceAdjustmentProposal> generateForMaterial(
            String factoryId,
            String materialTypeId,
            String materialName,
            BigDecimal latestPreTaxPrice,
            SourceType sourceType,
            String sourceReceiveRecordId,
            Long sourceReceiveItemId) {
        // latestPreTaxPrice 是触发信号 (这次收货/核价带了价), 不是建议价: 收货价 0.8 进库后档案移动均价是
        // 0.7501, BOM 行只能继承后者 —— 建议显示 0.8 而批准写 0.7501 就是 D-S5-11。没有价的收货照旧不出建议。
        if (materialTypeId == null || materialTypeId.isBlank() || latestPreTaxPrice == null
                || latestPreTaxPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }

        // 与批准同一锁序: 先锁物料, 再读/改 PENDING 建议 (确认入库路径里物料已被同一事务锁住, 这里是重入)
        Optional<RawMaterialType> material = lockLiveMaterial(factoryId, materialTypeId);
        BigDecimal proposedPrice = material.map(this::masterPrice).map(this::scaleMoney).orElse(null);
        if (proposedPrice == null || proposedPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Skip BOM price adjustment proposals: material master has no valid price, factoryId={}, materialTypeId={}",
                    factoryId, materialTypeId);
            return List.of();
        }

        List<BomRecipeItem> items = itemRepository.findByFactoryIdAndMaterialTypeId(factoryId, materialTypeId);
        if (items.isEmpty()) {
            return List.of();
        }

        List<String> recipeIds = items.stream()
                .map(BomRecipeItem::getRecipeId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        // 只有可改的版本 (DRAFT / ACTIVE) 才出建议: 归档版本是历史成本, 不得回写 (D-S5-10)。
        // 查不到的配方 (已删除) 同样不出建议。
        Map<String, BomRecipe> recipesById = recipeRepository.findAllById(recipeIds).stream()
                .filter(recipe -> factoryId.equals(recipe.getFactoryId()))
                .filter(recipe -> PRICE_WRITABLE_RECIPE_STATUSES.contains(recipe.getStatus()))
                .collect(Collectors.toMap(BomRecipe::getId, recipe -> recipe, (a, b) -> a, LinkedHashMap::new));
        int affectedProductCount = (int) recipesById.values().stream()
                .map(BomRecipe::getProductTypeId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        List<BomPriceAdjustmentProposal> proposals = new ArrayList<>();
        for (BomRecipeItem item : items) {
            if (item.getUnitPrice() == null || item.getRecipeId() == null) {
                continue;
            }
            BomRecipe recipe = recipesById.get(item.getRecipeId());
            if (recipe == null) {
                continue;
            }
            BigDecimal currentPrice = scaleMoney(item.getUnitPrice());
            if (currentPrice.compareTo(proposedPrice) == 0) {
                continue;
            }
            BigDecimal deltaAmount = proposedPrice.subtract(currentPrice).setScale(4, RoundingMode.HALF_UP);
            BigDecimal deltaPercent = calculateDeltaPercent(currentPrice, proposedPrice);
            BomPriceAdjustmentProposal pending = proposalRepository
                    .findByFactoryIdAndRecipeItemIdAndStatusAndDeletedAtIsNull(
                            factoryId, item.getId(), Status.PENDING)
                    .orElse(null);
            if (pending != null) {
                if (scaleMoney(pending.getProposedUnitPrice()).compareTo(proposedPrice) == 0) {
                    continue;
                }
                log.info("Updating existing pending BOM price adjustment proposal: proposalId={}, factoryId={}, recipeItemId={}",
                        pending.getId(), factoryId, item.getId());
                pending.setCurrentUnitPrice(currentPrice);
                pending.setProposedUnitPrice(proposedPrice);
                pending.setDeltaAmount(deltaAmount);
                pending.setDeltaPercent(deltaPercent);
                pending.setAffectedProductCount(affectedProductCount);
                pending.setSourceType(sourceType != null ? sourceType : SourceType.MANUAL_CHECK);
                pending.setSourceReceiveRecordId(sourceReceiveRecordId);
                pending.setSourceReceiveItemId(sourceReceiveItemId);
                if (materialName != null) {
                    pending.setMaterialName(materialName);
                }
                proposals.add(proposalRepository.save(pending));
                continue;
            }
            BomPriceAdjustmentProposal proposal = new BomPriceAdjustmentProposal();
            proposal.setFactoryId(factoryId);
            proposal.setRecipeId(item.getRecipeId());
            proposal.setRecipeItemId(item.getId());
            proposal.setRecipeCode(recipe != null ? recipe.getRecipeCode() : null);
            proposal.setProductTypeId(recipe != null ? recipe.getProductTypeId() : null);
            proposal.setProductName(recipe != null ? recipe.getProductName() : null);
            proposal.setMaterialTypeId(materialTypeId);
            proposal.setMaterialName(materialName != null ? materialName : item.getMaterialName());
            proposal.setCurrentUnitPrice(currentPrice);
            proposal.setProposedUnitPrice(proposedPrice);
            proposal.setDeltaAmount(deltaAmount);
            proposal.setDeltaPercent(deltaPercent);
            proposal.setAffectedProductCount(affectedProductCount);
            proposal.setStatus(Status.PENDING);
            proposal.setSourceType(sourceType != null ? sourceType : SourceType.MANUAL_CHECK);
            proposal.setSourceReceiveRecordId(sourceReceiveRecordId);
            proposal.setSourceReceiveItemId(sourceReceiveItemId);
            proposals.add(proposalRepository.save(proposal));
        }
        return proposals;
    }

    @Override
    @Transactional
    public BomPriceAdjustmentProposal approve(String factoryId, Long proposalId, Long approverId, String comment) {
        return approve(factoryId, proposalId, approverId, comment, null);
    }

    @Override
    @Transactional
    public BomPriceAdjustmentProposal approve(String factoryId, Long proposalId, Long approverId, String comment,
                                              BigDecimal expectedUnitPrice) {
        if (approverId == null) {
            throw new BusinessException(400, "审批人不能为空");
        }
        // 🔴 锁序 = 物料 -> 建议 -> 整族配方头 -> 配方行 (红线 3 统一, 见 BomRecipeHeadLocks), 与确认入库 (先锁物料、重算均价、再原地改写 PENDING 建议)
        // 同一方向, 不互等。每一行都在【加锁读】时第一次进本事务的持久化上下文: 先无锁读进来的托管对象,
        // 之后的 FOR UPDATE 不会刷新它的字段, 读到的就是旧值 (审查 A/B/C)。所以这里先只取物料 id 这一个标量。
        String materialTypeId = proposalRepository.findMaterialTypeIdByIdAndFactoryId(proposalId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("BOM调价建议不存在: " + proposalId));
        RawMaterialType material = lockLiveMaterial(factoryId, materialTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("物料档案不存在: " + materialTypeId));
        BomPriceAdjustmentProposal proposal = proposalRepository.lockByIdAndFactoryId(proposalId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("BOM调价建议不存在: " + proposalId));
        if (proposal.getStatus() != Status.PENDING) {
            throw new BusinessException(409, "只有待审批的BOM调价建议可以批准");
        }
        // 审批人看到的那个数: 传了就必须还是它 (建议可能在他看完之后被新的收货更新过)。不传不比, 向后兼容。
        if (expectedUnitPrice != null && (proposal.getProposedUnitPrice() == null
                || scaleMoney(expectedUnitPrice).compareTo(scaleMoney(proposal.getProposedUnitPrice())) != 0)) {
            throw stale();
        }

        // D-S5-10: 历史 (归档) 版本的成本不回写。配方头必须在工艺保密检查【之前】加锁读 ——
        // 保密检查按配方定位产品, 会无锁 findById 把配方读进上下文, 之后再 FOR UPDATE 拿到的是那份旧对象,
        // 并发激活新版本把它归档时这里仍会读成 ACTIVE (审查 A)。
        // N1: 锁的是整族配方头 (按 id 升序), 不只是这一个 —— 重算会改写同族所有成员的头。
        // 红线 3: 先头后行 (与激活 / 整表更新 / 草稿明细编辑同一方向): 先只取行所属配方 id (标量)。
        java.util.Optional<String> lineRecipeId = itemRepository.findRecipeIdById(proposal.getRecipeItemId());
        BomRecipe recipe = lineRecipeId
                .flatMap(id -> com.cretas.aims.service.bom.BomRecipeHeadLocks
                        .lockWithFamily(recipeRepository, factoryId, id))
                .orElse(null);
        BomRecipeItem item = itemRepository.findByIdForUpdate(proposal.getRecipeItemId())
                .orElseThrow(() -> new ResourceNotFoundException("BOM行不存在: " + proposal.getRecipeItemId()));
        if (!factoryId.equals(item.getFactoryId())) {
            throw new BusinessException(403, "无权审批该BOM调价建议");
        }
        if (!materialTypeId.equals(item.getMaterialTypeId())) {
            throw stale();   // 配方行已换成别的物料: 这条建议说的不再是这一行
        }
        if (lineRecipeId.isPresent() && !lineRecipeId.get().equals(item.getRecipeId())) {
            throw stale();   // 两步之间行被挪到别的产出: 锁住的不是它现在所在的配方
        }
        // 工艺保密 (写入侧): 批准会改写配方行的单价与行成本 (行成本 = 用量 × 单价), 属于改配方, 要编辑权
        if (processSecretGuard != null) {
            processSecretGuard.requireRecipe(factoryId, item.getRecipeId(),
                    com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT,
                    "bom price-adjustment approve " + proposalId);
        }
        if (recipe == null || !PRICE_WRITABLE_RECIPE_STATUSES.contains(recipe.getStatus())) {
            String version = recipe == null || recipe.getVersion() == null ? "" : " v" + recipe.getVersion();
            String state = recipe == null ? "已删除" : recipe.getStatus() == BomRecipe.Status.ARCHIVED
                    ? "已归档" : "不可修改 (" + recipe.getStatus() + ")";
            throw new BusinessException(409, "该调价建议对应的配方版本" + version + state
                    + "，历史版本的成本不能回写，本次未做任何修改")
                    .withCode(ERROR_RECIPE_NOT_EDITABLE)
                    .withHint("只有草稿或当前生效的配方版本可以调价；如需调整，请在当前生效版本上重新生成调价建议");
        }

        BigDecimal beforePrice = scaleMoney(item.getUnitPrice());
        BigDecimal masterPrice = masterPrice(material);
        if (masterPrice == null || masterPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "物料档案未配置有效价格: " + materialTypeId);
        }
        // D-S5-11: 写入的就是建议行上审批人看到的那个数。BOM 行只能继承档案价, 所以档案价若已不是这个数,
        // 建议就过期了 —— 拒绝并要求重新生成, ⛔ 不静默换成新价, 也不改写建议行。
        BigDecimal afterPrice = scaleMoney(proposal.getProposedUnitPrice());
        if (afterPrice == null || afterPrice.compareTo(scaleMoney(masterPrice)) != 0) {
            throw stale();
        }
        item.setUnitPrice(afterPrice);
        item.setTaxRate(material.getTaxRate() == null ? null
                : material.getTaxRate().getRate().multiply(BigDecimal.valueOf(100)));
        item.setItemCost(item.computeItemCost());
        itemRepository.save(item);
        // 审查 D: 行价变了, 配方头 total_material_cost / total_cost (标准成本、嵌套 BOM 读的是头) 必须同一事务重算,
        // 用编辑 / 成本更正那一套 (含同族分摊), ⛔ 不在这里另写一份。
        bomRecipeService.recomputeCostsAfterItemPriceChange(recipe);

        LocalDateTime now = LocalDateTime.now();
        proposal.setStatus(Status.APPROVED);
        proposal.setApprovedBy(approverId);
        proposal.setApprovedAt(now);
        proposal.setApprovalComment(comment);
        proposal.setAppliedAt(now);

        BomPriceAdjustmentAudit audit = new BomPriceAdjustmentAudit();
        audit.setProposalId(proposal.getId());
        audit.setFactoryId(factoryId);
        audit.setRecipeId(item.getRecipeId());
        audit.setRecipeItemId(item.getId());
        audit.setMaterialTypeId(item.getMaterialTypeId());
        audit.setBeforeUnitPrice(beforePrice);
        audit.setAfterUnitPrice(afterPrice);
        audit.setApprovedBy(approverId);
        audit.setApprovedAt(now);
        audit.setApprovalComment(comment);
        auditRepository.save(audit);

        return proposalRepository.save(proposal);
    }

    /** 驳回原因上限 (approval_comment 是 TEXT, 这里只防误贴整段日志)。 */
    static final int REJECT_REASON_MAX_LENGTH = 500;
    public static final String ERROR_REJECT_REASON_REQUIRED = "BOM_PRICE_PROPOSAL_REJECT_REASON_REQUIRED";

    @Override
    @Transactional
    public BomPriceAdjustmentProposal reject(String factoryId, Long proposalId, Long rejectorId, String reason) {
        if (rejectorId == null) {
            throw new BusinessException(401, "无法识别驳回人，请重新登录");
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(400, "驳回调价建议必须填写原因")
                    .withCode(ERROR_REJECT_REASON_REQUIRED)
                    .withHintTarget("reason");
        }
        if (trimmed.length() > REJECT_REASON_MAX_LENGTH) {
            throw new BusinessException(400, "驳回原因不能超过 " + REJECT_REASON_MAX_LENGTH + " 字")
                    .withHintTarget("reason");
        }
        // 与批准、确认入库同一锁序: 先锁物料, 再锁建议。只锁建议的话, 收货路径 (已持物料锁) 原地改写这条
        // PENDING 建议时会撞上乐观锁, 让一次正常的确认入库失败。
        String materialTypeId = proposalRepository.findMaterialTypeIdByIdAndFactoryId(proposalId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("BOM调价建议不存在: " + proposalId));
        lockLiveMaterial(factoryId, materialTypeId);
        BomPriceAdjustmentProposal proposal = proposalRepository.lockByIdAndFactoryId(proposalId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("BOM调价建议不存在: " + proposalId));
        if (proposal.getStatus() != Status.PENDING) {
            throw new BusinessException(409, "只有待审批的BOM调价建议可以驳回");
        }
        // 驳回不改配方行、不写调价审计 (审计记的是行价的前后值); 结论留在建议行上
        proposal.setStatus(Status.REJECTED);
        proposal.setApprovedBy(rejectorId);
        proposal.setApprovedAt(LocalDateTime.now());
        proposal.setApprovalComment(trimmed);
        log.info("BOM price adjustment proposal rejected: proposalId={}, factoryId={}, rejectorId={}",
                proposalId, factoryId, rejectorId);
        return proposalRepository.save(proposal);
    }

    @Override
    public BomPriceAdjustmentProposal get(String factoryId, Long proposalId) {
        return proposalRepository.findByIdAndFactoryId(proposalId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("BOM调价建议不存在: " + proposalId));
    }

    private BusinessException stale() {
        // 消息里不带价: 建议价 / 档案价都是 @PriceSensitive 字段, 错误消息绕过序列化脱敏
        return new BusinessException(409, "调价建议已过期：建议价已变化或与物料档案当前价不一致，本次未做任何修改")
                .withCode(ERROR_STALE)
                .withHint("请刷新调价建议，按最新的建议价重新审批；仍不一致时对该物料重新执行调价检查");
    }

    @Override
    public Page<BomPriceAdjustmentProposal> list(String factoryId, Status status, Pageable pageable) {
        if (status != null) {
            return proposalRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(factoryId, status, pageable);
        }
        return proposalRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable);
    }

    private Optional<RawMaterialType> lockLiveMaterial(String factoryId, String materialTypeId) {
        return materialRepository.lockByFactoryIdAndIdIn(factoryId, List.of(materialTypeId)).stream()
                .filter(found -> factoryId.equals(found.getFactoryId()) && found.getDeletedAt() == null)
                .findFirst();
    }

    /** BOM 行价格的唯一来源: 物料档案移动均价, 没有则档案单价。生成与批准共用这一处。 */
    private BigDecimal masterPrice(RawMaterialType material) {
        return material.getMovingAvgPrice() != null ? material.getMovingAvgPrice() : material.getUnitPrice();
    }

    private BigDecimal scaleMoney(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateDeltaPercent(BigDecimal currentPrice, BigDecimal proposedPrice) {
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return proposedPrice.subtract(currentPrice)
                .divide(currentPrice, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
