package com.cretas.aims.service.material;

import com.cretas.aims.dto.material.MaterialMergeDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.material.RawMaterialTypeAlias;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.RawMaterialTypeAliasRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.utils.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 物料别名 + 重复物料合并 (新工厂复盘: 「配方名称和系统名称对不上」「同类品牌多, 要整合」)。
 *
 * <p><b>合并只做三件事</b>: 被合并物料停用并记下去向、它的名字(和原有别名)挂到主物料上、记合并人和原因。
 * 历史批次 / 单据 / 成本一行不动。Owner 2026-11-03 裁定: 还有库存, 或还在当前配方、已发布工艺图里用着,
 * 拦住并列出位置 (配方与工艺图之间有同步关系, 自动改风险大)。
 * Owner 2026-11-04 放开一部分: 单位等价时, 草稿工艺图、供应商供货、一点没收货的采购单行
 * 由 {@link MaterialMergeReferenceReplacer} 自动换到主物料; 单位不同或已开始收货的仍然拦住。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialAliasMergeService {

    static final int MAX_LISTED_ITEMS = 10;
    private static final int MAX_MERGE_CHAIN = 10;

    private final RawMaterialTypeRepository materialTypeRepository;
    private final MaterialMergeReferenceReplacer referenceReplacer;
    private final RawMaterialTypeAliasRepository aliasRepository;
    private final UnitContractService unitContractService;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // ========== 别名 ==========

    public List<MaterialMergeDTO.AliasItem> listAliases(String factoryId, String materialTypeId) {
        requireMaterial(factoryId, materialTypeId);
        return aliasRepository.findByFactoryIdAndMaterialTypeIdOrderByCreatedAtAsc(factoryId, materialTypeId)
                .stream()
                .map(MaterialAliasMergeService::toItem)
                .toList();
    }

    /** 每个物料 id → 它的别名 (按添加先后)。列表页一次取一页, 不逐行查。 */
    public Map<String, List<String>> aliasesByMaterial(String factoryId, Collection<String> materialTypeIds) {
        if (materialTypeIds == null || materialTypeIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        aliasRepository.findByFactoryIdAndMaterialTypeIdIn(factoryId, materialTypeIds).stream()
                .sorted((a, b) -> Objects.compare(a.getCreatedAt(), b.getCreatedAt(),
                        (x, y) -> x == null || y == null ? 0 : x.compareTo(y)))
                .forEach(a -> result.computeIfAbsent(a.getMaterialTypeId(), k -> new ArrayList<>()).add(a.getAlias()));
        return result;
    }

    @Transactional
    @CacheEvict(value = "materialTypes", key = "#factoryId")
    public MaterialMergeDTO.AliasItem addAlias(String factoryId, String materialTypeId, String rawAlias) {
        RawMaterialType material = requireMaterial(factoryId, materialTypeId);
        if (material.getMergedIntoId() != null) {
            throw new BusinessException(409, "「" + material.getName() + "」已合并到别的物料，不能再加别名")
                    .withHint("请把别名加到合并后的主物料上")
                    .withHintTarget("alias");
        }
        String alias = cleanAlias(rawAlias);
        String normalized = RawMaterialTypeAlias.normalize(alias);
        ensureAliasFree(factoryId, normalized, alias);
        RawMaterialTypeAlias saved = aliasRepository.save(newAlias(
                factoryId, materialTypeId, alias, normalized, RawMaterialTypeAlias.SOURCE_MANUAL));
        log.info("物料别名已添加: factoryId={}, material={}, alias={}", factoryId, materialTypeId, alias);
        return toItem(saved);
    }

    @Transactional
    @CacheEvict(value = "materialTypes", key = "#factoryId")
    public void removeAlias(String factoryId, String materialTypeId, String aliasId) {
        RawMaterialTypeAlias alias = aliasRepository.findByIdAndFactoryId(aliasId, factoryId)
                .filter(a -> a.getMaterialTypeId().equals(materialTypeId))
                .orElseThrow(() -> new ResourceNotFoundException("别名不存在: " + aliasId));
        aliasRepository.delete(alias);
    }

    /** 删除物料时一起删掉它的别名 (调用方已在事务里)。 */
    public void removeAllAliases(String factoryId, String materialTypeId) {
        aliasRepository.deleteAll(
                aliasRepository.findByFactoryIdAndMaterialTypeIdOrderByCreatedAtAsc(factoryId, materialTypeId));
    }

    /**
     * 新建 / 改名物料时调用: 名字已经是别的物料的别名就拒绝 —— 否则又造出一个重复物料。
     * excludeMaterialId 是改名场景里的物料本身 (它自己的别名不算冲突, 但仍然撞名, 见下)。
     */
    public void ensureNameNotAnAlias(String factoryId, String name, String excludeMaterialId) {
        String normalized = RawMaterialTypeAlias.normalize(name);
        if (normalized.isEmpty()) {
            return;
        }
        Optional<RawMaterialTypeAlias> hit = aliasRepository.findByFactoryIdAndNormalizedAlias(factoryId, normalized);
        if (hit.isEmpty()) {
            return;
        }
        RawMaterialTypeAlias alias = hit.get();
        String ownerName = materialTypeRepository.findByIdAndFactoryId(alias.getMaterialTypeId(), factoryId)
                .map(RawMaterialType::getName).orElse(alias.getMaterialTypeId());
        if (alias.getMaterialTypeId().equals(excludeMaterialId)) {
            throw new BusinessException(409, "「" + name.trim() + "」已经是这个物料的别名")
                    .withHint("要用它当正式名称，请先删掉这个别名")
                    .withHintTarget("name");
        }
        throw new BusinessException(409, "「" + name.trim() + "」已是物料「" + ownerName + "」的别名")
                .withHint("这是同一个物料，请直接用「" + ownerName + "」，不要重复建档")
                .withHintTarget("name");
    }

    /**
     * 按名字找物料: 先比物料名, 再比别名; 落到已合并的物料时顺着合并去向找到主物料。
     * 名字对应不到唯一一个物料时返回空 (不猜)。
     */
    public Optional<RawMaterialType> resolveByName(String factoryId, String name) {
        String normalized = RawMaterialTypeAlias.normalize(name);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        List<RawMaterialType> byName = materialTypeRepository.findByFactoryId(factoryId).stream()
                .filter(m -> RawMaterialTypeAlias.normalize(m.getName()).equals(normalized))
                .toList();
        if (byName.size() > 1) {
            return Optional.empty();
        }
        if (byName.size() == 1) {
            return followMerge(factoryId, byName.get(0));
        }
        return aliasRepository.findByFactoryIdAndNormalizedAlias(factoryId, normalized)
                .flatMap(a -> materialTypeRepository.findByIdAndFactoryId(a.getMaterialTypeId(), factoryId))
                .flatMap(m -> followMerge(factoryId, m));
    }

    // ========== 合并 ==========

    public MaterialMergeDTO.MergeCheck checkMerge(String factoryId, String duplicateId, String masterId) {
        RawMaterialType duplicate = requireMaterial(factoryId, duplicateId);
        RawMaterialType master = requireMaterial(factoryId, masterId);
        return buildCheck(factoryId, duplicate, master);
    }

    @Transactional
    @CacheEvict(value = "materialTypes", key = "#factoryId")
    public MaterialMergeDTO.MergeResult merge(String factoryId, String duplicateId, MaterialMergeDTO.MergeRequest request) {
        String masterId = request == null || request.getMasterId() == null ? "" : request.getMasterId().trim();
        if (masterId.isEmpty()) {
            throw new BusinessException(400, "请选择要保留的主物料").withHintTarget("masterId");
        }
        MaterialMergeReferenceReplacer.Plan initialPlan = referenceReplacer.plan(
                factoryId, duplicateId, masterId);
        // Receiving confirms lock the PO header before recalculating the material. Keep the
        // merge order identical (workflow/PO headers, then materials) to avoid a PO↔material
        // deadlock between the two transactions.
        lockReferences(factoryId, initialPlan);

        List<String> lockIds = List.of(duplicateId, masterId).stream().distinct().sorted().toList();
        List<RawMaterialType> locked = materialTypeRepository.lockByFactoryIdAndIdIn(factoryId, lockIds);
        if (locked.size() != lockIds.size()) {
            throw new ResourceNotFoundException("物料不存在或不属于当前工厂");
        }
        Map<String, RawMaterialType> byId = locked.stream()
                .collect(Collectors.toMap(RawMaterialType::getId, m -> m));
        RawMaterialType duplicate = byId.get(duplicateId);
        RawMaterialType master = byId.get(masterId);

        // The candidate rows are now serialized against publish and receiving. Rebuild every
        // blocker after the locks; the pre-lock check would be a TOCTOU window.
        MaterialMergeDTO.MergeCheck check = buildCheck(factoryId, duplicate, master);
        if (!check.isCanMerge()) {
            String reasons = check.getBlockers().stream()
                    .map(MaterialMergeDTO.Blocker::getMessage)
                    .collect(Collectors.joining("；"));
            throw new BusinessException(409, "「" + duplicate.getName() + "」现在还不能合并：" + reasons)
                    .withHint(check.getBlockers().get(0).getHint())
                    .withCode("MATERIAL_MERGE_BLOCKED")
                    .withHintTarget("masterId");
        }

        MaterialMergeReferenceReplacer.Plan finalPlan = referenceReplacer.plan(
                factoryId, duplicateId, masterId);
        ensureReferencesWereLocked(initialPlan, finalPlan);
        MaterialMergeDTO.ReplacementResult replaced = referenceReplacer.apply(
                factoryId, duplicate, master, finalPlan);

        String operator = currentOperator();
        List<String> added = new ArrayList<>();
        // 被合并物料原有的别名整体挪到主物料上 (同一张表里改归属, 唯一约束不受影响)
        for (RawMaterialTypeAlias existing
                : aliasRepository.findByFactoryIdAndMaterialTypeIdOrderByCreatedAtAsc(factoryId, duplicateId)) {
            existing.setMaterialTypeId(masterId);
            aliasRepository.save(existing);
            added.add(existing.getAlias());
        }
        // 被合并物料的名字成为主物料的别名; 与主物料同名 (只是大小写 / 全半角不同) 时不需要
        String duplicateName = truncate(duplicate.getName().trim());
        String normalizedName = RawMaterialTypeAlias.normalize(duplicateName);
        if (!normalizedName.equals(RawMaterialTypeAlias.normalize(master.getName()))
                && aliasRepository.findByFactoryIdAndNormalizedAlias(factoryId, normalizedName).isEmpty()) {
            aliasRepository.save(newAlias(factoryId, masterId, duplicateName,
                    normalizedName, RawMaterialTypeAlias.SOURCE_MERGE));
            added.add(duplicateName);
        }

        duplicate.setIsActive(false);
        duplicate.setMergedIntoId(masterId);
        duplicate.setMergedAt(LocalDateTime.now());
        duplicate.setMergedBy(operator);
        duplicate.setMergeReason(blankToNull(request.getReason()));
        materialTypeRepository.save(duplicate);
        log.info("重复物料已合并: factoryId={}, duplicate={}({}), master={}({}), operator={}",
                factoryId, duplicateId, duplicate.getName(), masterId, master.getName(), operator);
        return MaterialMergeDTO.MergeResult.builder()
                .duplicateId(duplicateId)
                .masterId(masterId)
                .aliasesAdded(added)
                .replaced(replaced)
                .build();
    }

    private void lockReferences(String factoryId, MaterialMergeReferenceReplacer.Plan plan) {
        plan.draftWorkflows().keySet().stream().sorted()
                .forEach(id -> workflowRepository.lockByIdAndFactoryId(id, factoryId)
                        .orElseThrow(() -> new BusinessException(409, "工艺图状态已变化，请刷新后重试")
                                .withHint("请刷新受影响的工艺图，再重新检查合并条件")
                                .withCode("MATERIAL_MERGE_CONFLICT")));
        plan.purchaseOrderIds().stream().sorted()
                .forEach(id -> purchaseOrderRepository.findByIdAndFactoryIdForUpdate(id, factoryId)
                        .orElseThrow(() -> new BusinessException(409, "采购单状态已变化，请刷新后重试")
                                .withHint("请刷新受影响的采购单，再重新检查合并条件")
                                .withCode("MATERIAL_MERGE_CONFLICT")));
    }

    private void ensureReferencesWereLocked(MaterialMergeReferenceReplacer.Plan initiallyLocked,
                                            MaterialMergeReferenceReplacer.Plan finalPlan) {
        if (!initiallyLocked.draftWorkflows().keySet().containsAll(finalPlan.draftWorkflows().keySet())
                || !initiallyLocked.suppliers().keySet().containsAll(finalPlan.suppliers().keySet())
                || !initiallyLocked.purchaseOrderIds().containsAll(finalPlan.purchaseOrderIds())) {
            throw new BusinessException(409, "合并引用在锁定后发生变化，请刷新后重试")
                    .withHint("请重新预检合并，确认最新受影响单据后再提交")
                    .withCode("MATERIAL_MERGE_CONFLICT");
        }
    }

    private MaterialMergeDTO.MergeCheck buildCheck(String factoryId, RawMaterialType duplicate, RawMaterialType master) {
        if (duplicate.getId().equals(master.getId())) {
            throw new BusinessException(400, "主物料不能是它自己").withHintTarget("masterId");
        }
        if (duplicate.getMergedIntoId() != null) {
            throw new BusinessException(409, "「" + duplicate.getName() + "」已经合并过了")
                    .withHint("它已停用，请直接使用它合并到的主物料");
        }
        if (master.getMergedIntoId() != null) {
            throw new BusinessException(409, "「" + master.getName() + "」本身已合并到别的物料，不能当主物料")
                    .withHint("请改选它合并到的那个主物料")
                    .withHintTarget("masterId");
        }
        if (!Boolean.TRUE.equals(master.getIsActive())) {
            throw new BusinessException(409, "主物料「" + master.getName() + "」已停用")
                    .withHint("请先启用它，或选一个在用的物料当主物料")
                    .withHintTarget("masterId");
        }

        List<MaterialMergeDTO.Blocker> blockers = new ArrayList<>();
        if (!Objects.equals(duplicate.getMainCategory(), master.getMainCategory())) {
            blockers.add(MaterialMergeDTO.Blocker.builder()
                    .code("CATEGORY")
                    .message("大类不同（" + duplicate.getMainCategory() + " / " + master.getMainCategory() + "），不是同一种物料")
                    .items(List.of())
                    .hint("原料、辅料、包材之间不能合并")
                    .build());
        }
        stockBlocker(factoryId, duplicate.getId()).ifPresent(blockers::add);
        listBlocker("BOM", "还在 %d 张配方里用着", "请先到「配方管理」把这些配方里的该物料换成主物料",
                bomProducts(factoryId, duplicate.getId())).ifPresent(blockers::add);

        boolean unitsMatch = sameUnit(factoryId, duplicate.getUnit(), master.getUnit());
        MaterialMergeReferenceReplacer.Plan plan = referenceReplacer.plan(factoryId, duplicate.getId(), master.getId());
        List<MaterialMergeDTO.Replacement> replacements = new ArrayList<>();
        listBlocker("WORKFLOW", "还在 %d 张已发布工艺图里用着", "请先到「工艺流程」把这些工艺图里的投料换成主物料并发布新版本",
                publishedWorkflowProducts(factoryId, duplicate.getId())).ifPresent(blockers::add);
        listBlocker("PURCHASE_ORDER", "还有 %d 张采购单已开始收货", "已收的货记在旧物料上，请等这些采购单收货完成、关闭或取消后再合并",
                plan.stuckPurchaseOrders()).ifPresent(blockers::add);
        listBlocker("SUPPLIER_TOMBSTONE", "有 %d 家供应商的主物料供货记录已删除",
                "不能自动恢复已删除供货记录的价格或版本，请先清理这条历史记录后再合并",
                plan.supplierTombstones()).ifPresent(blockers::add);
        listBlocker("OPEN_RECEIPT", "还有 %d 张无采购单的收货单未完成",
                "已有收货草稿或待质检记录引用旧物料，请先完成、退回或作废后再合并",
                plan.openStandaloneReceipts()).ifPresent(blockers::add);
        if (unitsMatch) {
            replacement("WORKFLOW", "%d 张草稿工艺图", List.copyOf(new LinkedHashSet<>(plan.draftWorkflows().values())))
                    .ifPresent(replacements::add);
            replacement("SUPPLIER", "%d 家供应商的供货关系", List.copyOf(plan.suppliers().values()))
                    .ifPresent(replacements::add);
            replacement("PURCHASE_ORDER", "%d 张没收货的采购单", plan.purchaseOrderNumbers())
                    .ifPresent(replacements::add);
        } else {
            String why = "两个物料单位不同，不能自动替换。";
            listBlocker("DRAFT_WORKFLOW", "还在 %d 张草稿工艺图里用着", why + "请到「工艺流程」手工换成主物料",
                    List.copyOf(new LinkedHashSet<>(plan.draftWorkflows().values()))).ifPresent(blockers::add);
            listBlocker("SUPPLIER", "还有 %d 家供应商登记在供这个物料", why + "请到「供应商」把供货关系改到主物料上，或停用这些供货关系",
                    List.copyOf(plan.suppliers().values())).ifPresent(blockers::add);
            listBlocker("OPEN_PURCHASE_ORDER", "还有 %d 张采购单没完成", why + "请等这些采购单关闭或取消后再合并",
                    plan.purchaseOrderNumbers()).ifPresent(blockers::add);
        }

        List<String> warnings = new ArrayList<>();
        if (!unitsMatch) {
            warnings.add("单位不同：「" + duplicate.getName() + "」按 " + duplicate.getUnit()
                    + "，「" + master.getName() + "」按 " + master.getUnit() + "。合并后按主物料的单位记。");
        }

        Set<String> aliasesToAdd = new LinkedHashSet<>();
        if (!RawMaterialTypeAlias.normalize(duplicate.getName()).equals(RawMaterialTypeAlias.normalize(master.getName()))) {
            aliasesToAdd.add(duplicate.getName().trim());
        }
        aliasRepository.findByFactoryIdAndMaterialTypeIdOrderByCreatedAtAsc(factoryId, duplicate.getId())
                .forEach(a -> aliasesToAdd.add(a.getAlias()));

        return MaterialMergeDTO.MergeCheck.builder()
                .canMerge(blockers.isEmpty())
                .duplicate(brief(duplicate))
                .master(brief(master))
                .blockers(blockers)
                .replacements(replacements)
                .warnings(warnings)
                .aliasesToAdd(new ArrayList<>(aliasesToAdd))
                .build();
    }

    // ---------- 引用查询 (全部按 factory_id 限定) ----------

    private Optional<MaterialMergeDTO.Blocker> stockBlocker(String factoryId, String materialTypeId) {
        // 货架实物 = 收货 − 已用 (预留的货还在货架上, 也算); 已报废 / 已用完的批次不算
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT quantity_unit, COUNT(*), SUM(receipt_quantity - used_quantity) FROM material_batches "
                                + "WHERE factory_id = ?1 AND material_type_id = ?2 AND deleted_at IS NULL "
                                + "AND status NOT IN ('SCRAPPED', 'USED_UP') AND receipt_quantity - used_quantity > 0 "
                                + "GROUP BY quantity_unit ORDER BY quantity_unit")
                .setParameter(1, factoryId)
                .setParameter(2, materialTypeId)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        long batches = 0;
        List<String> parts = new ArrayList<>();
        for (Object[] row : rows) {
            batches += ((Number) row[1]).longValue();
            parts.add(plain(row[2]) + " " + row[0]);
        }
        return Optional.of(MaterialMergeDTO.Blocker.builder()
                .code("STOCK")
                .message("还有库存 " + String.join(" + ", parts) + "（" + batches + " 个批次）")
                .items(List.of())
                .hint("请先用完，或到「库存盘点」调整为 0 后再合并")
                .build());
    }

    private List<String> bomProducts(String factoryId, String materialTypeId) {
        return strings(entityManager.createNativeQuery(
                        "SELECT DISTINCT r.product_name FROM bom_recipes r WHERE r.factory_id = ?1 "
                                + "AND r.deleted_at IS NULL AND r.is_current = TRUE AND (r.id IN ("
                                + "  SELECT i.recipe_id FROM bom_recipe_items i WHERE i.factory_id = ?1 "
                                + "  AND i.material_type_id = ?2 AND i.deleted_at IS NULL) "
                                + "OR r.id IN ("
                                + "  SELECT s.recipe_id FROM bom_seasoning_items s WHERE s.material_type_id = ?2 "
                                + "  AND s.deleted_at IS NULL)) "
                                + "ORDER BY r.product_name")
                .setParameter(1, factoryId)
                .setParameter(2, materialTypeId)
                .getResultList());
    }

    private List<String> publishedWorkflowProducts(String factoryId, String materialTypeId) {
        // 工艺图节点以 JSON 存物料 id; id 是 UUID 级别的长串, 按带引号的整串匹配不会误命中
        return strings(entityManager.createNativeQuery(
                        "SELECT DISTINCT COALESCE(p.name, w.product_type_id) FROM product_process_workflows w "
                                + "LEFT JOIN product_types p ON p.id = w.product_type_id "
                                + "WHERE w.factory_id = ?1 AND w.deleted_at IS NULL "
                                + "AND w.status = 'PUBLISHED' "
                                + "AND CAST(w.nodes_json AS VARCHAR) LIKE ?2 "
                                + "ORDER BY 1")
                .setParameter(1, factoryId)
                .setParameter(2, "%\"" + materialTypeId + "\"%")
                .getResultList());
    }

    private static Optional<MaterialMergeDTO.Replacement> replacement(
            String code, String messageFormat, List<String> items) {
        if (items.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(MaterialMergeDTO.Replacement.builder()
                .code(code)
                .message(String.format(messageFormat, items.size()))
                .items(items.size() > MAX_LISTED_ITEMS ? items.subList(0, MAX_LISTED_ITEMS) : items)
                .build());
    }

    private static Optional<MaterialMergeDTO.Blocker> listBlocker(
            String code, String messageFormat, String hint, List<String> items) {
        if (items.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(MaterialMergeDTO.Blocker.builder()
                .code(code)
                .message(String.format(messageFormat, items.size()))
                .items(items.size() > MAX_LISTED_ITEMS ? items.subList(0, MAX_LISTED_ITEMS) : items)
                .hint(hint)
                .build());
    }

    // ---------- helpers ----------

    private RawMaterialType requireMaterial(String factoryId, String id) {
        return materialTypeRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("物料不存在或不属于当前工厂: " + id));
    }

    private Optional<RawMaterialType> followMerge(String factoryId, RawMaterialType start) {
        RawMaterialType current = start;
        for (int i = 0; i < MAX_MERGE_CHAIN && current.getMergedIntoId() != null; i++) {
            Optional<RawMaterialType> next = materialTypeRepository.findByIdAndFactoryId(current.getMergedIntoId(), factoryId);
            if (next.isEmpty()) {
                return Optional.empty();
            }
            current = next.get();
        }
        return current.getMergedIntoId() == null ? Optional.of(current) : Optional.empty();
    }

    private void ensureAliasFree(String factoryId, String normalized, String alias) {
        Optional<RawMaterialType> sameName = materialTypeRepository.findByFactoryId(factoryId).stream()
                .filter(m -> RawMaterialTypeAlias.normalize(m.getName()).equals(normalized))
                .findFirst();
        if (sameName.isPresent()) {
            throw new BusinessException(409, "「" + alias + "」已经是物料「" + sameName.get().getName() + "」的名称")
                    .withHint("如果它们是同一个物料，请用「合并重复物料」")
                    .withHintTarget("alias");
        }
        Optional<RawMaterialTypeAlias> taken = aliasRepository.findByFactoryIdAndNormalizedAlias(factoryId, normalized);
        if (taken.isPresent()) {
            String owner = materialTypeRepository.findByIdAndFactoryId(taken.get().getMaterialTypeId(), factoryId)
                    .map(RawMaterialType::getName).orElse(taken.get().getMaterialTypeId());
            throw new BusinessException(409, "「" + alias + "」已是物料「" + owner + "」的别名")
                    .withHint("同一个别名只能对应一个物料")
                    .withHintTarget("alias");
        }
    }

    private static String cleanAlias(String raw) {
        String alias = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (alias.isEmpty()) {
            throw new BusinessException(400, "请填写别名").withHintTarget("alias");
        }
        if (alias.length() > RawMaterialTypeAlias.MAX_LENGTH) {
            throw new BusinessException(400, "别名不能超过 " + RawMaterialTypeAlias.MAX_LENGTH + " 个字")
                    .withHintTarget("alias");
        }
        return alias;
    }

    private static RawMaterialTypeAlias newAlias(String factoryId, String materialTypeId, String alias,
                                                 String normalized, String source) {
        RawMaterialTypeAlias entity = new RawMaterialTypeAlias();
        entity.setId(UUID.randomUUID().toString());
        entity.setFactoryId(factoryId);
        entity.setMaterialTypeId(materialTypeId);
        entity.setAlias(alias);
        entity.setNormalizedAlias(normalized);
        entity.setSource(source);
        entity.setCreatedBy(currentOperator());
        return entity;
    }

    private static MaterialMergeDTO.AliasItem toItem(RawMaterialTypeAlias alias) {
        return MaterialMergeDTO.AliasItem.builder()
                .id(alias.getId())
                .alias(alias.getAlias())
                .source(alias.getSource())
                .build();
    }

    private static MaterialMergeDTO.MaterialBrief brief(RawMaterialType m) {
        return MaterialMergeDTO.MaterialBrief.builder()
                .id(m.getId())
                .code(m.getCode())
                .name(m.getName())
                .unit(m.getUnit())
                .mainCategory(m.getMainCategory())
                .build();
    }

    @SuppressWarnings("rawtypes")
    private static List<String> strings(List rows) {
        List<String> out = new ArrayList<>();
        for (Object row : rows) {
            if (row != null) {
                out.add(row.toString());
            }
        }
        return out;
    }

    private static String plain(Object number) {
        BigDecimal value = number instanceof BigDecimal bd ? bd : new BigDecimal(number.toString());
        return value.stripTrailingZeros().toPlainString();
    }

    /** 单位同一性问单位契约; 认不出的单位不当作相同 —— 它只决定要不要多给一句提醒。 */
    private boolean sameUnit(String factoryId, String left, String right) {
        try {
            return unitContractService.areEquivalent(factoryId, left, right);
        } catch (RuntimeException unknownUnit) {
            return false;
        }
    }

    private static String truncate(String value) {
        return value.length() > RawMaterialTypeAlias.MAX_LENGTH
                ? value.substring(0, RawMaterialTypeAlias.MAX_LENGTH) : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String currentOperator() {
        String username = SecurityUtils.currentUsername();
        return username == null || username.isBlank() ? "system" : username;
    }
}
