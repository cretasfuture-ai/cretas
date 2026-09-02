package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomSeasoningWorkspaceResponse;
import com.cretas.aims.dto.bom.SeasoningBindingCreateRequest;
import com.cretas.aims.dto.bom.SeasoningBindingMutationResponse;
import com.cretas.aims.dto.bom.SeasoningBindingUpdateRequest;
import com.cretas.aims.dto.workflow.WorkflowRevisionCandidateDTO;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.material.MaterialMainCategory;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.bom.BomSeasoningWorkspaceService;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.WorkflowProcessPath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BomSeasoningWorkspaceServiceImpl implements BomSeasoningWorkspaceService {

    /**
     * 🔴 2026-08-28: 原来这里是一张<b>小类</b>字面值白名单
     * {@code {AUXILIARY, SEASONING, 辅料, 调料, 调味料}}, 「添加剂」不在里面 ⇒ 六膳门 9 个
     * 添加剂类物料 (保水剂T22 / 复合磷酸盐(闻达) / 小苏打 / 无磷保水剂 …) 全部绑不上,
     * 而保水剂和复合磷酸盐是肉制品核心辅料, 按 g/kg 投入计量, 与调料同一口径。
     *
     * <p>⛔ 修法<b>不是</b>往白名单里再加一个字符串 —— 那只是把下次要漏的那个换个名字
     * (生产上这一列有 12 个平级取值, 「角色」和「品类」混在同一列)。改成查<b>大类</b>:
     * 辅料下面无论加多少小类 (添加剂 / 调味料 / 未来的任何一个), 这里一个字都不用改。
     * 见 {@link MaterialMainCategory}。
     */
    private static final String AUXILIARY_MAIN_CATEGORY = MaterialMainCategory.AUXILIARY;

    private final BomRecipeRepository recipeRepository;
    private final BomSeasoningItemRepository seasoningItemRepository;
    private final ProductWorkProcessRepository productWorkProcessRepository;
    private final WorkProcessRepository workProcessRepository;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final ProductWorkflowResolutionService workflowResolutionService;
    private final BomWorkflowRevisionService bomWorkflowRevisionService;
    private final BomItemSubstituteService substituteService;
    private final ProductProcessWorkflowRevisionRepository workflowRevisionRepository;
    private final BomRecipeService bomRecipeService;

    @Override
    @Transactional(readOnly = true)
    public BomSeasoningWorkspaceResponse getWorkspace(String factoryId, String recipeId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        PinnedWorkflowGraph pinnedGraph = recipe.getWorkflowRevisionHash() == null
                ? null
                : bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe);
        List<ResolvedProcess> workflow = resolveProcesses(factoryId, recipe, pinnedGraph);
        List<BomRecipe> family = familyForStatus(recipe);
        String bindingRecipeId = recipe.getSharedRecipeId() == null
                ? recipeId : recipe.getSharedRecipeId();
        Map<String, BomRecipe> owners = family.stream().collect(Collectors.toMap(
                BomRecipe::getId, Function.identity()));
        List<BomSeasoningItem> bindings = family.stream()
                .flatMap(member -> seasoningItemRepository
                        .findByRecipeIdOrderBySeqAsc(member.getId()).stream())
                .filter(binding -> bindingAppliesTo(
                        binding,
                        owners.get(binding.getRecipeId()),
                        recipe,
                        family))
                .toList();
        Map<String, BomWorkflowRevisionService.CostScopeProfile> processCostProfiles =
                pinnedGraph == null
                ? Map.of()
                : bomWorkflowRevisionService.resolveProcessCostProfiles(factoryId, recipe);

        List<String> processIds = workflow.stream().map(ResolvedProcess::workProcessId).distinct().toList();
        Map<String, WorkProcess> workProcesses = workProcessRepository
                .findByFactoryIdAndIdIn(factoryId, processIds).stream()
                .collect(Collectors.toMap(WorkProcess::getId, Function.identity()));
        Map<String, List<BomSeasoningItem>> byNode = bindings.stream()
                .filter(b -> b.getWorkflowProcessNodeId() != null)
                .collect(Collectors.groupingBy(BomSeasoningItem::getWorkflowProcessNodeId));
        Map<String, Long> masterOccurrences = workflow.stream().collect(Collectors.groupingBy(
                ResolvedProcess::workProcessId, Collectors.counting()));
        Map<String, List<BomSeasoningItem>> legacyByProcess = bindings.stream()
                .filter(b -> b.getWorkflowProcessNodeId() == null && b.getWorkProcessId() != null)
                .collect(Collectors.groupingBy(BomSeasoningItem::getWorkProcessId));

        BomSeasoningWorkspaceResponse response = new BomSeasoningWorkspaceResponse();
        response.setRecipeId(recipe.getId());
        response.setProductTypeId(recipe.getProductTypeId());
        response.setProductName(recipe.getProductName());
        response.setStatus(recipe.getStatus());
        response.setEditable(recipe.getStatus() == BomRecipe.Status.DRAFT);
        response.setSeasoningRevision(recipe.getSeasoningRevision());
        populatePinnedRevisionSummary(response, factoryId, recipe, bindingRecipeId, pinnedGraph);

        for (ResolvedProcess configured : workflow) {
            WorkProcess master = workProcesses.get(configured.workProcessId());
            List<BomSeasoningItem> processBindings = new ArrayList<>(
                    byNode.getOrDefault(configured.processNodeId(), List.of()));
            if (masterOccurrences.getOrDefault(configured.workProcessId(), 0L) == 1L) {
                processBindings.addAll(legacyByProcess.getOrDefault(configured.workProcessId(), List.of()));
            }
            BomWorkflowRevisionService.CostScopeProfile costProfile =
                    processCostProfiles.get(configured.processNodeId());
            String costScope = costProfile == null ? "SHARED" : costProfile.costScope();
            response.getProcesses().add(new BomSeasoningWorkspaceResponse.ProcessView(
                    configured.processNodeId(),
                    configured.workProcessId(),
                    master != null ? master.getProcessName() : configured.workProcessId(),
                    master != null ? master.getProcessCategory() : null,
                    configured.processOrder(),
                    configured.standardBasisQuantity(),
                    configured.standardBasisUnit(),
                    configured.standardBasisMaterialKind(),
                    configured.standardUsageSupported(),
                    costScope,
                    costProfile == null ? List.of(recipe.getProductTypeId())
                            : costProfile.productTypeIds(),
                    recipe.getStatus() == BomRecipe.Status.DRAFT,
                    processBindings));
        }

        Map<String, RawMaterialType> materials = materialTypeRepository.findAllById(bindings.stream()
                        .map(BomSeasoningItem::getMaterialTypeId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(RawMaterialType::getId, Function.identity()));
        Map<String, String> processNames = response.getProcesses().stream().collect(Collectors.toMap(
                BomSeasoningWorkspaceResponse.ProcessView::getWorkflowProcessNodeId,
                BomSeasoningWorkspaceResponse.ProcessView::getProcessName));
        Set<String> validWorkProcessIds = workflow.stream().map(ResolvedProcess::workProcessId).collect(Collectors.toSet());

        LinkedHashMap<String, List<BomSeasoningItem>> byMaterial = new LinkedHashMap<>();
        for (BomSeasoningItem binding : bindings) {
            collectAnomalies(response, binding, processNames, validWorkProcessIds, masterOccurrences, materials);
            if (binding.getMaterialTypeId() != null) {
                byMaterial.computeIfAbsent(binding.getMaterialTypeId(), ignored -> new ArrayList<>()).add(binding);
            }
        }
        byMaterial.forEach((materialId, usages) -> {
            RawMaterialType material = materials.get(materialId);
            BomSeasoningItem first = usages.get(0);
            List<BomSeasoningWorkspaceResponse.ProcessUsage> processUsages = usages.stream()
                    .map(binding -> new BomSeasoningWorkspaceResponse.ProcessUsage(
                            binding.getWorkflowProcessNodeId(),
                            binding.getWorkProcessId(),
                            processNames.getOrDefault(binding.getWorkflowProcessNodeId(), binding.getWorkProcessId()),
                            binding.getDosagePerKgG(),
                            binding.getSubsequentPotRatio()))
                    .toList();
            response.getMaterialSummaries().add(new BomSeasoningWorkspaceResponse.MaterialSummary(
                    materialId,
                    material != null ? material.getCode() : null,
                    material != null ? material.getName() : first.getName(),
                    material != null ? material.getCategory() : null,
                    material != null ? material.getUnit() : null,
                    effectivePrice(first),
                    processUsages));
        });
        return response;
    }

    @Override
    @Transactional
    public SeasoningBindingMutationResponse createBinding(String factoryId, String recipeId,
                                                           String workProcessId,
                                                           SeasoningBindingCreateRequest request) {
        BomRecipe recipe = editableRecipe(factoryId, recipeId);
        ResolvedProcess process = validateWorkflow(recipe, factoryId, workProcessId,
                request.getWorkflowProcessNodeId());
        BindingTarget target = resolveBindingTarget(factoryId, recipe, process.processNodeId());
        RawMaterialType material = validateMaterial(factoryId, request.getMaterialTypeId());
        validateValues(request.getDosagePerKgG(), request.getSubsequentPotRatio());
        seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                target.recipe().getId(), process.processNodeId(), material.getId()).ifPresent(existing -> {
            throw duplicate(existing);
        });
        claimRevision(target.recipe(), factoryId, request.getExpectedRevision());

        BomSeasoningItem binding = new BomSeasoningItem();
        binding.setRecipeId(target.recipe().getId());
        binding.setFactoryId(factoryId);
        binding.setWorkProcessId(workProcessId);
        binding.setWorkflowProcessNodeId(process.processNodeId());
        binding.setCostScope(target.costScope());
        binding.setCostScopeKey(target.costScopeKey());
        binding.setMaterialTypeId(material.getId());
        binding.setSection("COOKING");
        binding.setSeq(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc(
                target.recipe().getId(), process.processNodeId()).size());
        apply(binding, material, request.getDosagePerKgG(), request.getSubsequentPotRatio(),
                request.getCountInSeasoning(), request.getRemark());
        BomSeasoningItem saved = seasoningItemRepository.save(binding);
        substituteService.replaceForSeasoningItem(
                factoryId, target.recipe().getId(), saved.getId(),
                request.getSubstitutes() == null ? List.of() : request.getSubstitutes());
        bomRecipeService.calculateCost(factoryId, target.recipe().getId());
        return new SeasoningBindingMutationResponse(request.getExpectedRevision() + 1, saved);
    }

    @Override
    @Transactional
    public SeasoningBindingMutationResponse updateBinding(String factoryId, String recipeId, Long bindingId,
                                                           SeasoningBindingUpdateRequest request) {
        BomRecipe recipe = editableRecipe(factoryId, recipeId);
        BomSeasoningItem binding = loadBindingForWorkspace(recipe, bindingId);
        ResolvedProcess process = validateWorkflow(
                recipe, factoryId, binding.getWorkProcessId(), binding.getWorkflowProcessNodeId());
        BindingTarget target = resolveBindingTarget(factoryId, recipe, process.processNodeId());
        assertBindingTarget(binding, target);
        RawMaterialType material = validateMaterial(factoryId, request.getMaterialTypeId());
        validateValues(request.getDosagePerKgG(), request.getSubsequentPotRatio());
        seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                        target.recipe().getId(), binding.getWorkflowProcessNodeId(), material.getId())
                .filter(existing -> !existing.getId().equals(bindingId))
                .ifPresent(existing -> { throw duplicate(existing); });
        claimRevision(target.recipe(), factoryId, request.getExpectedRevision());
        binding.setMaterialTypeId(material.getId());
        binding.setCostScope(target.costScope());
        binding.setCostScopeKey(target.costScopeKey());
        apply(binding, material, request.getDosagePerKgG(), request.getSubsequentPotRatio(),
                request.getCountInSeasoning(), request.getRemark());
        BomSeasoningItem saved = seasoningItemRepository.save(binding);
        if (request.getSubstitutes() != null) {
            substituteService.replaceForSeasoningItem(
                    factoryId, target.recipe().getId(), saved.getId(), request.getSubstitutes());
        }
        bomRecipeService.calculateCost(factoryId, target.recipe().getId());
        return new SeasoningBindingMutationResponse(request.getExpectedRevision() + 1, saved);
    }

    @Override
    @Transactional
    public SeasoningBindingMutationResponse deleteBinding(String factoryId, String recipeId, Long bindingId,
                                                           Long expectedRevision) {
        BomRecipe recipe = editableRecipe(factoryId, recipeId);
        BomSeasoningItem binding = loadBindingForWorkspace(recipe, bindingId);
        BindingTarget target = resolveBindingTarget(
                factoryId, recipe, binding.getWorkflowProcessNodeId());
        assertBindingTarget(binding, target);
        claimRevision(target.recipe(), factoryId, expectedRevision);
        substituteService.replaceForSeasoningItem(
                factoryId, target.recipe().getId(), bindingId, List.of());
        binding.softDelete();
        seasoningItemRepository.save(binding);
        bomRecipeService.calculateCost(factoryId, target.recipe().getId());
        return new SeasoningBindingMutationResponse(expectedRevision + 1, null);
    }

    private void apply(BomSeasoningItem binding, RawMaterialType material, BigDecimal dosage,
                       BigDecimal ratio, Boolean countInSeasoning, String remark) {
        PriceSnapshot price = resolvePrice(material);
        binding.setName(material.getName());
        binding.setDosagePerKgG(dosage);
        binding.setSubsequentPotRatio(ratio);
        binding.setPriceSource1(price.movingAveragePrice());
        binding.setPriceSource2(price.purchaseReferencePrice());
        binding.setCountInSeasoning(countInSeasoning != null ? countInSeasoning : Boolean.TRUE);
        binding.setRemark(remark);
    }

    private BigDecimal effectivePrice(BomSeasoningItem binding) {
        return positive(binding.getPriceSource1())
                ? binding.getPriceSource1()
                : positive(binding.getPriceSource2()) ? binding.getPriceSource2() : null;
    }

    private BomRecipe loadRecipe(String factoryId, String recipeId) {
        BomRecipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new EntityNotFoundException("BomRecipe 不存在: id=" + recipeId));
        if (!factoryId.equals(recipe.getFactoryId())) {
            throw new IllegalArgumentException("配方不属于该工厂");
        }
        return recipe;
    }

    private BomRecipe editableRecipe(String factoryId, String recipeId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw new BusinessException(409, "只有 DRAFT BOM 可修改调料")
                    .withCode("SEASONING_READ_ONLY")
                    .withHint("请先克隆为新的 DRAFT 版本");
        }
        if (recipe.getWorkflowRevisionHash() == null) {
            throw new BusinessException(409, "BOM 草稿尚未自动关联完整工艺")
                    .withCode("BOM_WORKFLOW_REVISION_REQUIRED")
                    .withHint("请先保存唯一且结构完整的 Workflow 草稿，再重新创建 BOM 草稿");
        }
        return recipe;
    }

    private BomSeasoningItem loadBinding(String recipeId, Long bindingId) {
        return seasoningItemRepository.findByIdAndRecipeId(bindingId, recipeId)
                .orElseThrow(() -> new EntityNotFoundException("调料绑定不存在: id=" + bindingId));
    }

    private BomSeasoningItem loadBindingForWorkspace(BomRecipe recipe, Long bindingId) {
        List<BomRecipe> family = familyForStatus(recipe);
        if (family.size() == 1) {
            return loadBinding(recipe.getId(), bindingId);
        }
        Map<String, BomRecipe> owners = family.stream().collect(Collectors.toMap(
                BomRecipe::getId, Function.identity()));
        return seasoningItemRepository.findById(bindingId)
                .filter(binding -> owners.containsKey(binding.getRecipeId()))
                .filter(binding -> bindingAppliesTo(
                        binding,
                        owners.get(binding.getRecipeId()),
                        recipe,
                        family))
                .orElseThrow(() ->
                        new EntityNotFoundException("调料绑定不属于当前 BOM 工作区: id=" + bindingId));
    }

    private BindingTarget resolveBindingTarget(
            String factoryId, BomRecipe recipe, String workflowProcessNodeId) {
        BomWorkflowRevisionService.CostScopeProfile profile =
                bomWorkflowRevisionService.resolveProcessCostProfiles(factoryId, recipe)
                .get(workflowProcessNodeId);
        if (profile == null) {
            throw new BusinessException(409, "工序不属于当前 BOM 固定的目标产出路径")
                    .withCode("SEASONING_WORKFLOW_NODE_OUTSIDE_TARGET");
        }
        List<BomRecipe> family = familyForStatus(recipe);
        List<BomRecipe> targetMembers = family.stream()
                .filter(member -> profile.productTypeIds().contains(member.getProductTypeId()))
                .sorted(Comparator.comparing(BomRecipe::getTargetTerminalNodeId))
                .toList();
        if (targetMembers.size() != profile.productTypeIds().size()) {
            throw new BusinessException(409, "工序成本目标与当前 BOM Family 不完整")
                    .withCode("SEASONING_COST_SCOPE_TARGET_INCOMPLETE")
                    .withHint("请刷新 BOM；如工艺已有新修订，请使用“升级到最新工艺”");
        }
        BomRecipe owner;
        if (!"SHARED".equals(profile.costScope())) {
            owner = targetMembers.get(0);
        } else if (family.size() == 1) {
            // Legacy single-output BOMs predate outputRole. Their only recipe is
            // deterministically the shared-cost owner and must remain editable.
            owner = family.get(0);
        } else {
            owner = family.stream()
                    .filter(member -> member.getOutputRole() == BomRecipe.OutputRole.MAIN)
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(409, "BOM Family 缺少主产出")
                            .withCode("BOM_FAMILY_MAIN_REQUIRED"));
        }
        return new BindingTarget(owner, profile.costScope(), profile.costScopeKey());
    }

    private void assertBindingTarget(BomSeasoningItem binding, BindingTarget target) {
        boolean legacySharedBinding = binding.getCostScope() == null
                && "SHARED".equals(target.costScope());
        boolean legacyScopeKey = binding.getCostScopeKey() == null
                && !"OUTPUT_GROUP".equals(target.costScope());
        if (!target.recipe().getId().equals(binding.getRecipeId())
                || (!legacySharedBinding && !target.costScope().equals(binding.getCostScope()))
                || (!legacyScopeKey && !Objects.equals(
                        target.costScopeKey(), binding.getCostScopeKey()))) {
            throw new BusinessException(409, "调料绑定与当前工序的共享范围不一致")
                    .withCode("SEASONING_COST_SCOPE_CONFLICT")
                    .withHint("请刷新 BOM 工作区后重试；历史绑定不会被静默移动");
        }
    }

    private List<BomRecipe> familyForStatus(BomRecipe reference) {
        if (reference.getBomFamilyId() == null) return List.of(reference);
        return recipeRepository
                .findByFactoryIdAndBomFamilyIdAndStatusOrderByProductTypeIdAsc(
                        reference.getFactoryId(),
                        reference.getBomFamilyId(),
                        reference.getStatus()).stream()
                .filter(member -> Objects.equals(
                        reference.getWorkflowRevisionId(), member.getWorkflowRevisionId()))
                .toList();
    }

    private boolean bindingAppliesTo(
            BomSeasoningItem binding,
            BomRecipe owner,
            BomRecipe target,
            List<BomRecipe> family) {
        if (owner == null) return false;
        if (binding.getCostScopeKey() != null && !binding.getCostScopeKey().isBlank()) {
            Set<String> terminalIds = java.util.Arrays.stream(
                            binding.getCostScopeKey().split(","))
                    .collect(Collectors.toSet());
            return terminalIds.contains(target.getTargetTerminalNodeId());
        }
        if ("OUTPUT_GROUP".equals(binding.getCostScope())) return false;
        if ("OUTPUT_EXCLUSIVE".equals(binding.getCostScope())) {
            return owner.getId().equals(target.getId());
        }
        return family.stream().anyMatch(member -> member.getId().equals(target.getId()));
    }

    private ResolvedProcess validateWorkflow(BomRecipe recipe, String factoryId, String workProcessId,
                                             String workflowProcessNodeId) {
        if (workflowProcessNodeId == null || workflowProcessNodeId.isBlank()) {
            throw new BusinessException(400, "新增工序辅料必须指定 Workflow 工序节点")
                    .withCode("SEASONING_WORKFLOW_NODE_REQUIRED");
        }
        ResolvedProcess matched = resolveProcesses(factoryId, recipe).stream()
                .filter(process -> workflowProcessNodeId.equals(process.processNodeId()))
                .filter(process -> workProcessId != null && workProcessId.equals(process.workProcessId()))
                .findFirst().orElse(null);
        if (matched == null) {
            throw new BusinessException(400, "所选工序不是该 SKU 的有效工序")
                    .withHint("请从当前 SKU 的 workflow 中选择工序");
        }
        if (!matched.standardUsageSupported()) {
            throw new BusinessException(400, "当前 Workflow 工序的产出单位缺失、冲突或不支持，无法保存工序辅料")
                    .withCode("SEASONING_STANDARD_BASIS_UNSUPPORTED")
                    .withHint("请回到 Workflow 检查该工序的产出端口及半成品单位，保存修订后再配置辅料");
        }
        return matched;
    }

    private List<ResolvedProcess> resolveProcesses(String factoryId, BomRecipe recipe) {
        PinnedWorkflowGraph pinnedGraph = recipe.getWorkflowRevisionHash() == null
                ? null
                : bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe);
        return resolveProcesses(factoryId, recipe, pinnedGraph);
    }

    private List<ResolvedProcess> resolveProcesses(
            String factoryId, BomRecipe recipe, PinnedWorkflowGraph pinnedGraph) {
        if (pinnedGraph != null || recipe.getStatus() == BomRecipe.Status.DRAFT) {
            PinnedWorkflowGraph graph = pinnedGraph != null
                    ? pinnedGraph
                    : bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe);
            return graph.processes().stream()
                    .map(step -> resolvedProcess(step, graph))
                    .toList();
        }
        Optional<WorkflowProcessPath> activePath = workflowResolutionService
                .resolveProcessPath(factoryId, recipe.getProductTypeId());
        if (activePath.isPresent()) {
            return activePath.get().processes().stream()
                    .map(step -> new ResolvedProcess(
                            step.processNodeId(), step.workProcessId(), step.order(),
                            null, null, null, false))
                    .toList();
        }
        return productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, recipe.getProductTypeId())
                .stream()
                .map(process -> new ResolvedProcess(
                        "legacy:" + process.getId(), process.getWorkProcessId(), process.getProcessOrder(),
                        null, null, null, false))
            .toList();
    }

    private ResolvedProcess resolvedProcess(
            PinnedWorkflowGraph.ProcessStep step, PinnedWorkflowGraph graph) {
        StandardBasis basis = standardBasis(graph, step.processNodeId());
        return new ResolvedProcess(
                step.processNodeId(), step.workProcessId(), step.order(),
                basis.quantity(), basis.unit(), basis.materialKind(), basis.supported());
    }

    private StandardBasis standardBasis(PinnedWorkflowGraph graph, String processNodeId) {
        ProductProcessWorkflowDTO.Node node = graph.nodes().stream()
                .filter(candidate -> Objects.equals(processNodeId, candidate.getId()))
                .findFirst().orElse(null);
        if (node == null || node.getData() == null) return StandardBasis.unsupported();

        Set<String> portUnits = new java.util.LinkedHashSet<>();
        Set<String> portKinds = new java.util.LinkedHashSet<>();
        Set<String> processUnits = new java.util.LinkedHashSet<>();
        Set<String> processKinds = new java.util.LinkedHashSet<>();
        Set<String> fallbackUnits = new java.util.LinkedHashSet<>();
        Set<String> fallbackKinds = new java.util.LinkedHashSet<>();
        Map<String, ProductProcessWorkflowDTO.Node> nodesById = graph.nodes().stream()
                .collect(Collectors.toMap(ProductProcessWorkflowDTO.Node::getId, Function.identity(),
                        (left, right) -> left));
        Object rawPorts = node.getData().get("ports");
        if (rawPorts instanceof List<?> ports) {
            for (Object rawPort : ports) {
                if (!(rawPort instanceof Map<?, ?> port)
                        || !"INPUT".equalsIgnoreCase(Objects.toString(port.get("direction"), ""))) {
                    continue;
                }
                addCanonicalUnit(portUnits, port.get("unit"));
                addCanonicalKind(portKinds, port.get("materialKind"));
                collectMaterialNodeContract(
                        nodesById.get(Objects.toString(port.get("materialNodeId"), null)),
                        fallbackUnits, fallbackKinds);
            }
        }
        addCanonicalUnit(processUnits, node.getData().get("inputUnit"));
        addCanonicalKind(processKinds, node.getData().get("inputMaterialKind"));
        for (ProductProcessWorkflowDTO.Edge edge : graph.edges()) {
            if (Objects.equals(processNodeId, edge.getTarget())) {
                collectMaterialNodeContract(nodesById.get(edge.getSource()), fallbackUnits, fallbackKinds);
            }
        }
        // dosagePerKgG 的运行公式是「本工序实际投入 kg × g/kg ÷ 1000」，与产出单位无关。
        // 保存门禁必须读取 INPUT 契约；拿 OUTPUT 的盒/箱/袋来放行或拦截会与正式报工
        // ProcessSheetServiceImpl#reportingMassToKg 的真实算式分裂。
        Set<String> inputUnits = !portUnits.isEmpty()
                ? portUnits : (!processUnits.isEmpty() ? processUnits : fallbackUnits);
        Set<String> inputKinds = !portKinds.isEmpty()
                ? portKinds : (!processKinds.isEmpty() ? processKinds : fallbackKinds);
        if (inputUnits.isEmpty() || inputUnits.stream().anyMatch(unit -> !isRuntimeMassUnit(unit))) {
            return StandardBasis.unsupported();
        }
        String materialKind = inputKinds.size() == 1 ? inputKinds.iterator().next() : null;
        return new StandardBasis(BigDecimal.ONE, "kg", materialKind, true);
    }

    private boolean isRuntimeMassUnit(String unit) {
        return "kg".equals(unit) || "g".equals(unit);
    }

    private void collectMaterialNodeContract(ProductProcessWorkflowDTO.Node materialNode,
                                             Set<String> outputUnits,
                                             Set<String> outputKinds) {
        if (materialNode == null || materialNode.getData() == null) return;
        addCanonicalUnit(outputUnits, materialNode.getData().get("baseUnit"));
        addCanonicalKind(outputKinds, materialNode.getData().get("materialKind"));
        addCanonicalKind(outputKinds, materialNode.getKind());
    }

    private void addCanonicalUnit(Set<String> units, Object rawUnit) {
        String unit = canonicalWorkflowUnit(Objects.toString(rawUnit, null));
        if (unit != null) units.add(unit);
    }

    private void addCanonicalKind(Set<String> kinds, Object rawKind) {
        String kind = canonicalMaterialKind(Objects.toString(rawKind, null));
        if (kind != null) kinds.add(kind);
    }

    private String canonicalMaterialKind(String rawKind) {
        if (rawKind == null || rawKind.isBlank()) return null;
        return switch (rawKind.trim().toUpperCase(Locale.ROOT)) {
            case "SEMI_FINISHED", "SEMI_FINISHED_PRODUCT" -> "SEMI_FINISHED";
            case "FINISHED", "FINISHED_GOOD", "FINISHED_PRODUCT", "PRODUCT" -> "FINISHED_GOOD";
            case "MATERIAL", "CELL", "RAW_MATERIAL", "PROCESS" -> null;
            default -> rawKind.trim().toUpperCase(Locale.ROOT);
        };
    }

    /**
     * 走系统权威别名表。原来是一张只有 7 组的私有 switch (无 袋/bag、无 件/个/只)，
     * 结果喂给 {@code addCanonicalUnit} 去重时，同一个单位的两种写法会被当成两种单位
     * —— 「这道工序有几种单位」就会数多。与 2026-07-31 报工/结单那两处同一个根因。
     */
    private String canonicalWorkflowUnit(String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank()) return null;
        return com.cretas.aims.service.unit.impl.UnitContractServiceImpl.crossLanguageCode(rawUnit);
    }

    private void populatePinnedRevisionSummary(
            BomSeasoningWorkspaceResponse response,
            String factoryId,
            BomRecipe recipe,
            String bindingRecipeId,
            PinnedWorkflowGraph graph) {
        if (graph == null) return;
        response.setWorkflowRevisionId(graph.workflowRevisionId());
        response.setWorkflowId(graph.workflowId());
        workflowRevisionRepository.findByIdAndFactoryId(graph.workflowRevisionId(), factoryId)
                .ifPresent(revision ->
                        response.setWorkflowOwnerProductTypeId(revision.getProductTypeId()));
        response.setWorkflowDefinitionVersion(graph.definitionVersion());
        response.setWorkflowRevisionHash(graph.revisionHash());
        response.setWorkflowRootCount(graph.rootMaterialTypeIds().size());
        response.setWorkflowProcessCount(graph.processes().size());
        response.setWorkflowTargetCount(
                bomWorkflowRevisionService.resolvePinnedTerminalOutputs(factoryId, recipe).size());
        response.setWorkflowTargetProductTypeId(graph.targetProductTypeId());
        response.setBomFamilyId(recipe.getBomFamilyId());
        response.setSharedRecipeId(bindingRecipeId);
        response.setSharedRulesOwner(bindingRecipeId.equals(recipe.getId()));
        response.setOutputRole(recipe.getOutputRole() == null ? null : recipe.getOutputRole().name());
        response.setCostAllocationRatio(recipe.getCostAllocationRatio());
        bomWorkflowRevisionService.findNewerCompatibleDraft(factoryId, recipe).ifPresent(revision -> {
            response.setWorkflowUpgradeAvailable(true);
            response.setWorkflowUpgradeRevisionId(revision.getId());
            response.setWorkflowUpgradeDefinitionVersion(revision.getDefinitionVersion());
        });

        bomWorkflowRevisionService.listCompatible(factoryId, recipe.getId()).stream()
                .filter(candidate -> Objects.equals(candidate.getRevisionId(), graph.workflowRevisionId())
                        || (Objects.equals(candidate.getWorkflowId(), graph.workflowId())
                        && Objects.equals(candidate.getRevisionHash(), graph.revisionHash())))
                .findFirst()
                .ifPresent(candidate -> {
                    response.setWorkflowRevisionStatus(displayRevisionStatus(candidate));
                    response.setWorkflowRevisionSavedAt(candidate.getSavedAt());
                });
    }

    private String displayRevisionStatus(WorkflowRevisionCandidateDTO candidate) {
        if (candidate.isEnabled()) return "ENABLED";
        return candidate.getStatus();
    }

    private record ResolvedProcess(
            String processNodeId,
            String workProcessId,
            Integer processOrder,
            BigDecimal standardBasisQuantity,
            String standardBasisUnit,
            String standardBasisMaterialKind,
            boolean standardUsageSupported) { }

    private record StandardBasis(BigDecimal quantity, String unit, String materialKind, boolean supported) {
        private static StandardBasis unsupported() {
            return new StandardBasis(null, null, null, false);
        }
    }

    private RawMaterialType validateMaterial(String factoryId, String materialTypeId) {
        RawMaterialType material = materialTypeRepository.findById(materialTypeId)
                .orElseThrow(() -> new BusinessException(400, "调料物料不存在: " + materialTypeId));
        if (!factoryId.equals(material.getFactoryId())) {
            throw new BusinessException(400, "调料物料不属于当前工厂");
        }
        if (!Boolean.TRUE.equals(material.getIsActive())) {
            throw new BusinessException(400, "调料物料已停用: " + material.getName());
        }
        // 判据是【大类】。存量行的 main_category 由 V20261101_04 从 category 回填 (NOT NULL + CHECK),
        // 新建/编辑时由 RawMaterialType#setCategory 推出 —— 两条路同一张映射表。
        // ⚠️ "003" 料号前缀那条旁路保留不动: 它是按料号 (而不是按分类) 认辅料的另一条既有判据,
        //    动它是另一件事, 这次不碰。
        String mainCategory = material.getMainCategory() == null ? "" : material.getMainCategory().trim();
        boolean auxiliary = AUXILIARY_MAIN_CATEGORY.equals(mainCategory)
                || "003".equals(material.getPrimaryCode());
        if (!auxiliary) {
            throw new BusinessException(400, "只能选择辅料物料: " + material.getName())
                    .withHint("物料大类需为「辅料」(调料 / 调味料 / 添加剂 等小类都归辅料)")
                    .withHintTarget("materialTypeId");
        }
        // 2026-09-02: 不再要求物料先有成本价才能绑定 (owner 底层缺口 2)。无价的绑定 priceSource 双 null,
        //   工作区以 MISSING_PRICE 异常显示, BOM 成本 CostValue.incomplete, 报工调料成本暂记 0 并显式 warning。
        return material;
    }

    /**
     * 物料当前的价格快照。两个价都没有 ⇒ 双 null (⛔ 不是 0, 也不再拒绝绑定):
     * 「不知道单价」和「单价为 0」对下游是两件事, 下游按 null 走 incomplete / warning。
     */
    private PriceSnapshot resolvePrice(RawMaterialType material) {
        if (positive(material.getMovingAvgPrice())) {
            return new PriceSnapshot(material.getMovingAvgPrice(), null);
        }
        if (positive(material.getUnitPrice())) {
            return new PriceSnapshot(null, material.getUnitPrice());
        }
        return new PriceSnapshot(null, null);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private record PriceSnapshot(BigDecimal movingAveragePrice, BigDecimal purchaseReferencePrice) { }

    private record BindingTarget(BomRecipe recipe, String costScope, String costScopeKey) { }

    private void validateValues(BigDecimal dosage, BigDecimal ratio) {
        if (dosage == null || dosage.signum() <= 0) {
            throw new BusinessException(400, "每 kg 调料用量必须大于 0");
        }
        if (ratio != null && (ratio.compareTo(BigDecimal.ZERO) < 0 || ratio.compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException(400, "后续锅比例必须在 0 到 1 之间");
        }
    }

    private void claimRevision(BomRecipe recipe, String factoryId, Long expectedRevision) {
        if (expectedRevision == null || recipeRepository.claimSeasoningRevision(
                recipe.getId(), factoryId, expectedRevision) != 1) {
            throw new BusinessException(409, "调料配置已被其他人修改")
                    .withCode("SEASONING_REVISION_CONFLICT")
                    .withHint("请重新加载最新配置后再保存");
        }
    }

    private BusinessException duplicate(BomSeasoningItem existing) {
        return new BusinessException(409, "该调料已在本工序配置，bindingId=" + existing.getId())
                .withCode("SEASONING_BINDING_DUPLICATE")
                .withHint("请定位并编辑现有调料行");
    }

    private void collectAnomalies(BomSeasoningWorkspaceResponse response, BomSeasoningItem binding,
                                  Map<String, String> processNames, Set<String> validWorkProcessIds,
                                  Map<String, Long> masterOccurrences,
                                  Map<String, RawMaterialType> materials) {
        if (binding.getWorkflowProcessNodeId() != null
                && !processNames.containsKey(binding.getWorkflowProcessNodeId())) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "INVALID_PROCESS", "调料尚未绑定当前 workflow 的有效工序", binding.getId()));
        } else if (binding.getWorkflowProcessNodeId() == null
                && (binding.getWorkProcessId() == null || !validWorkProcessIds.contains(binding.getWorkProcessId()))) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "INVALID_PROCESS", "历史调料绑定的工序不在当前 workflow 中", binding.getId()));
        } else if (binding.getWorkflowProcessNodeId() == null
                && masterOccurrences.getOrDefault(binding.getWorkProcessId(), 0L) > 1L) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "AMBIGUOUS_PROCESS_NODE", "历史调料仅保存工序主数据，无法区分重复工序节点", binding.getId()));
        }
        if (binding.getMaterialTypeId() == null) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "MISSING_MATERIAL_LINK", "历史调料需要重新选择物料", binding.getId()));
            return;
        }
        RawMaterialType material = materials.get(binding.getMaterialTypeId());
        if (material == null || !Boolean.TRUE.equals(material.getIsActive())) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "INVALID_MATERIAL", "调料物料不存在或已停用", binding.getId()));
        } else if (!positive(material.getMovingAvgPrice())
                && !positive(material.getUnitPrice())) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "MISSING_PRICE", "调料物料缺少有效移动平均库存成本或未税采购参考价", binding.getId()));
        }
    }
}
