package com.cretas.aims.blueprint.bomassembly;

import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.Assembly;
import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.AssemblyBlocker;
import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.BomChoices;
import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.BomLineChoice;
import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.Facts;
import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.RawInputPort;
import com.cretas.aims.blueprint.workflowassembly.WorkflowCompileRequestAssembler;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.packagingdraft.PackagingDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.packagingdraft.PackagingDraftRepository;
import com.cretas.aims.service.bomdraft.BomRecipeDraftCommand;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.BomItem;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.DraftRequest;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.MaterialCategory;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.MaterialRef;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.MaterialRefType;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.PackagingDependencyType;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.PackagingPin;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.PortDirection;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.ProductRef;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.ProductRefType;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.WorkflowPin;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the {@code BomRecipeDraftCommand} request of a BOM DRAFT on the server, from the user's
 * {@link BomChoices}, the live product and the product's current Workflow DRAFT.
 *
 * <p>Sources, field by field — nothing a pin is made of comes from the request body:</p>
 * <ul>
 *   <li>product: the one active live finished good of this factory whose code is the route's product code,
 *       pinned with {@link BomRecipeDraftCommand#productDigest} (the value the preview recomputes);</li>
 *   <li>workflow: the product's current DRAFT and its current revision, pinned by id / hash / versions;
 *       it must be structurally complete;</li>
 *   <li>lines: each line names a raw-material INPUT port of that revision; the process node, material node,
 *       edge, unit and the live material (the node's {@code skuId}, re-read from the factory's raw-material
 *       table and pinned with {@link BomRecipeDraftCommand#materialDigest}) are read from the graph;</li>
 *   <li>packaging: {@code NONE}, unless a line is a packaging line — then the factory's current packaging
 *       DRAFT of the same blueprint lineage (and its digest).</li>
 * </ul>
 *
 * <p>The command stays the judge of the result: product / material / workflow drift, units, dimensions,
 * quantities, port duplication, packaging coverage. This class only turns choices into the request those
 * checks run on, and refuses (with a next step) when the live data cannot give it a pin.</p>
 *
 * <p>What a BOM can bind is whatever the Workflow DRAFT has as a raw-material input port. A raw material the
 * Workflow was not given (the entry process takes only the raw materials chosen when it was written) cannot
 * be a BOM line yet.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class BomCompileRequestAssembler {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ProductTypeRepository products;
    private final RawMaterialTypeRepository rawMaterials;
    private final ProductProcessWorkflowRepository workflows;
    private final ProductProcessWorkflowRevisionRepository revisions;
    private final PackagingDraftRepository packagingDrafts;
    private final BomRecipeDraftCommand command;
    private final UnitContractService units;
    private final ObjectMapper mapper;

    public BomCompileRequestAssembler(ProductTypeRepository products, RawMaterialTypeRepository rawMaterials,
                                      ProductProcessWorkflowRepository workflows,
                                      ProductProcessWorkflowRevisionRepository revisions,
                                      PackagingDraftRepository packagingDrafts, BomRecipeDraftCommand command,
                                      UnitContractService units, ObjectMapper mapper) {
        this.products = products;
        this.rawMaterials = rawMaterials;
        this.workflows = workflows;
        this.revisions = revisions;
        this.packagingDrafts = packagingDrafts;
        this.command = command;
        this.units = units;
        this.mapper = mapper;
    }

    /**
     * The platform unit catalog's canonical code of {@code unit} (「个」 → {@code pcs}). The BOM command saves units
     * as canonical codes only, while product / material rows and the Workflow's ports carry the catalog's storage
     * unit, so every unit the assembler hands to the command goes through here; an unknown unit is passed on as
     * it is and the command reports it.
     */
    public String canonicalUnit(String factoryId, String unit) {
        if (unit == null || unit.isBlank()) return unit == null ? "" : unit;
        return units.describe(factoryId, unit).map(CanonicalUnit::code).orElse(unit);
    }

    // ------------------------------------------------------------------ facts

    /** The live product and Workflow DRAFT of {@code productCode}, with every missing precondition as a blocker. */
    public Facts facts(String factoryId, String productCode) {
        List<AssemblyBlocker> blockers = new ArrayList<>();
        ProductType product = resolveProduct(factoryId, productCode, blockers);
        if (product == null) {
            return new Facts(productCode, null, null, null, List.of(), blockers);
        }
        ProductProcessWorkflow workflow = workflows
                .findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                        factoryId, product.getId(), ProductProcessWorkflow.Status.DRAFT).orElse(null);
        if (workflow == null) {
            blockers.add(new AssemblyBlocker("BOM_WORKFLOW_DRAFT_MISSING", "workflow",
                    "“" + product.getName() + "”还没有 Workflow 草稿，配方要钉在 Workflow 的某一版上",
                    "先在 Workflow 草稿里写出这个产品的 Workflow，再回来写配方"));
            return new Facts(productCode, product, null, null, List.of(), blockers);
        }
        ProductProcessWorkflowRevision revision = workflow.getCurrentRevisionId() == null ? null
                : revisions.findByIdAndFactoryId(workflow.getCurrentRevisionId(), factoryId).orElse(null);
        if (revision == null || !Objects.equals(revision.getRevisionHash(), workflow.getCurrentRevisionHash())
                || !Boolean.TRUE.equals(revision.getStructurallyComplete())) {
            blockers.add(new AssemblyBlocker("BOM_WORKFLOW_DRAFT_NOT_READY", "workflow",
                    "“" + product.getName() + "”当前的 Workflow 草稿还不是一份结构完整的版本",
                    "回到 Workflow 画布修好并重新保存，再回来写配方"));
            return new Facts(productCode, product, workflow, revision, List.of(), blockers);
        }
        List<RawInputPort> ports = rawInputPorts(factoryId, revision, blockers);
        if (ports.isEmpty() && blockers.isEmpty()) {
            blockers.add(new AssemblyBlocker("BOM_NO_RAW_INPUT_PORT", "workflow",
                    "“" + product.getName() + "”的 Workflow 草稿里没有任何投入原料的端口，配方没有可绑定的行",
                    "回到 Workflow 草稿，给入口工序选好投入的原料后重新写入"));
        }
        return new Facts(productCode, product, workflow, revision, ports, blockers);
    }

    private ProductType resolveProduct(String factoryId, String code, List<AssemblyBlocker> blockers) {
        List<ProductType> sameCode = products.findVisibleByFactoryIdAndIsActiveTrue(factoryId).stream()
                .filter(row -> code.equals(row.getCode())).toList();
        List<ProductType> finished = sameCode.stream().filter(row -> row.getProductCategory() != null
                && WorkflowCompileRequestAssembler.FINISHED_GOOD_CATEGORIES.contains(row.getProductCategory())).toList();
        if (finished.isEmpty()) {
            blockers.add(new AssemblyBlocker("BOM_PRODUCT_NOT_LIVE", "product",
                    "本厂正式产品表里没有编码为“" + code + "”的启用成品，配方只能挂在正式成品上",
                    "先在产品草稿里确认这个产品（类别选成品）并转正，再回来写配方"));
            return null;
        }
        if (finished.size() > 1) {
            blockers.add(new AssemblyBlocker("BOM_PRODUCT_AMBIGUOUS", "product",
                    "本厂正式产品表里编码“" + code + "”有 " + finished.size() + " 个启用的成品，无法确定配方挂在哪一个上",
                    "先在产品管理里停用或改码重复的成品"));
            return null;
        }
        return finished.getFirst();
    }

    private List<RawInputPort> rawInputPorts(String factoryId, ProductProcessWorkflowRevision revision,
                                             List<AssemblyBlocker> blockers) {
        List<ProductProcessWorkflowDTO.Node> nodes;
        List<ProductProcessWorkflowDTO.Edge> edges;
        try {
            nodes = mapper.readValue(revision.getNodesJson(), new TypeReference<>() { });
            edges = mapper.readValue(revision.getEdgesJson(), new TypeReference<>() { });
        } catch (Exception e) {
            blockers.add(new AssemblyBlocker("BOM_WORKFLOW_GRAPH_INVALID", "workflow",
                    "Workflow 草稿的图无法读取", "回到 Workflow 画布修复并重新保存"));
            return List.of();
        }
        Map<String, ProductProcessWorkflowDTO.Node> byId = new LinkedHashMap<>();
        nodes.forEach(node -> byId.put(node.getId(), node));
        List<RawInputPort> ports = new ArrayList<>();
        for (ProductProcessWorkflowDTO.Node process : nodes) {
            if (!"PROCESS".equals(process.getKind()) || !(process.getData() != null
                    && process.getData().get("ports") instanceof List<?> rawPorts)) {
                continue;
            }
            for (Object raw : rawPorts) {
                if (!(raw instanceof Map<?, ?> port) || !"INPUT".equals(text(port, "direction"))) continue;
                ProductProcessWorkflowDTO.Node materialNode = byId.get(text(port, "materialNodeId"));
                if (materialNode == null || !"RAW_MATERIAL".equals(materialNode.getKind())) continue;
                String portId = text(port, "id");
                String edgeId = edges.stream()
                        .filter(edge -> Objects.equals(edge.getSource(), materialNode.getId())
                                && Objects.equals(edge.getTarget(), process.getId())
                                && "output".equals(edge.getSourceHandle())
                                && Objects.equals(edge.getTargetHandle(), portId))
                        .map(ProductProcessWorkflowDTO.Edge::getId).findFirst().orElse(null);
                String skuId = text(materialNode.getData(), "skuId");
                if (edgeId == null || skuId.isBlank()) {
                    blockers.add(new AssemblyBlocker("BOM_WORKFLOW_GRAPH_INVALID", "workflow",
                            "Workflow 草稿里的端口 " + portId + " 没有对应的连线或物料",
                            "回到 Workflow 画布修复并重新保存"));
                    continue;
                }
                ports.add(new RawInputPort(process.getId(), text(process.getData(), "processName"), portId,
                        materialNode.getId(), skuId,
                        rawMaterials.findByIdAndFactoryId(skuId, factoryId).map(RawMaterialType::getName)
                                .orElse(text(materialNode.getData(), "name")),
                        text(port, "unit"), edgeId));
            }
        }
        return ports;
    }

    // ------------------------------------------------------------------ assembly

    /**
     * @return the command's request, or the blockers (the facts' own, or the choices that cannot be turned
     *         into a pinned line); never both
     * @throws BusinessException 400 when {@code choices} names no product
     */
    public Assembly assemble(String factoryId, String blueprintId, long blueprintVersion, String sourceDigest,
                             BomChoices choices, String confirmedPreviewDigest, String idempotencyKey, long operatorId) {
        if (choices.routeProductCode().isBlank()) {
            throw new BusinessException(400, "请指明配方属于蓝图里的哪个产品（routeProductCode）")
                    .withCode("BOM_ROUTE_PRODUCT_MISSING").withHintTarget("routeProductCode");
        }
        Facts facts = facts(factoryId, choices.routeProductCode());
        if (!facts.ready()) {
            return new Assembly(facts, null, facts.blockers());
        }
        List<AssemblyBlocker> blockers = new ArrayList<>();
        if (choices.changeReason().isBlank()) {
            blockers.add(new AssemblyBlocker("BOM_CHANGE_REASON_MISSING", "changeReason",
                    "变更原因没填，配方的每一行都要带变更原因", "写一句这份配方为什么这样定"));
        }
        if (choices.lines().isEmpty()) {
            blockers.add(new AssemblyBlocker("BOM_LINES_MISSING", "lines",
                    "配方至少要有一行", "为 Workflow 里每个投入原料的端口填一行"));
        }
        Map<String, RawInputPort> portsById = new LinkedHashMap<>();
        facts.rawInputPorts().forEach(port -> portsById.put(port.portId(), port));

        List<BomItem> items = new ArrayList<>();
        Set<String> usedPorts = new HashSet<>();
        boolean needsPackaging = false;
        for (int i = 0; i < choices.lines().size(); i++) {
            BomLineChoice line = choices.lines().get(i);
            String at = "lines[" + i + "]";
            if (line == null) {
                blockers.add(new AssemblyBlocker("BOM_LINE_EMPTY", at, "第 " + (i + 1) + " 行是空的", "删掉这一行或补全"));
                continue;
            }
            RawInputPort port = line.workflowPortId() == null ? null : portsById.get(line.workflowPortId());
            if (port == null) {
                blockers.add(new AssemblyBlocker("BOM_LINE_PORT_UNKNOWN", at + ".workflowPortId",
                        "第 " + (i + 1) + " 行选的端口不是这份 Workflow 草稿里投入原料的端口（Workflow 可能已被改过）",
                        "刷新预填后，从当前 Workflow 的端口里重新选"));
                continue;
            }
            if (!usedPorts.add(port.portId())) {
                blockers.add(new AssemblyBlocker("BOM_LINE_PORT_DUPLICATE", at + ".workflowPortId",
                        "“" + port.label() + "”这个端口被绑在了不止一行上", "合并重复行，或给这一行选另一个端口"));
                continue;
            }
            List<String> missing = new ArrayList<>();
            if (line.category() == null) missing.add("category（类别）");
            if (line.standardQuantity() == null) missing.add("standardQuantity（标准用量）");
            if (line.yieldPercent() == null) missing.add("yieldPercent（出成率）");
            if (line.optional() == null) missing.add("optional（是否可选）");
            if (!missing.isEmpty()) {
                blockers.add(new AssemblyBlocker("BOM_LINE_INCOMPLETE", at,
                        "第 " + (i + 1) + " 行（" + port.label() + "）还没填：" + String.join("、", missing),
                        "补齐这些项后再预览"));
                continue;
            }
            RawMaterialType material = rawMaterials.findByIdAndFactoryId(port.materialId(), factoryId)
                    .filter(row -> Boolean.TRUE.equals(row.getIsActive())).orElse(null);
            if (material == null) {
                blockers.add(new AssemblyBlocker("BOM_MATERIAL_NOT_LIVE", at,
                        "Workflow 里“" + port.materialName() + "”对应的正式原料已不存在或已停用",
                        "先恢复这个原料，或回到 Workflow 草稿换一种原料"));
                continue;
            }
            needsPackaging |= line.category() == MaterialCategory.PACKAGING;
            items.add(new BomItem(new MaterialRef(MaterialRefType.LIVE_MATERIAL, material.getId(),
                    command.materialDigest(material)), line.category(), line.standardQuantity(), canonicalUnit(factoryId, port.unit()),
                    line.yieldPercent(), "", null, line.optional(), port.materialNodeId(), port.processNodeId(),
                    port.portId(), port.edgeId(), PortDirection.INPUT, List.of(), choices.changeReason()));
        }

        PackagingPin packaging = new PackagingPin(PackagingDependencyType.NONE, "", "");
        if (needsPackaging) {
            Optional<PackagingDraft> draft = latestPackagingDraft(factoryId, blueprintId, blueprintVersion, sourceDigest);
            if (draft.isEmpty()) {
                blockers.add(new AssemblyBlocker("BOM_PACKAGING_DRAFT_MISSING", "packaging",
                        "配方里有包材行，需要同一份蓝图的包装草稿，本厂还没有",
                        "先写包装草稿（覆盖这些包材），再回来写配方"));
            } else {
                packaging = new PackagingPin(PackagingDependencyType.PACKAGING_DRAFT, draft.get().getId(),
                        draft.get().getDesiredDigest());
            }
        }
        if (!blockers.isEmpty()) {
            return new Assembly(facts, null, blockers);
        }

        ProductProcessWorkflow workflow = facts.workflow();
        ProductProcessWorkflowRevision revision = facts.revision();
        DraftRequest request = new DraftRequest(factoryId, blueprintId, blueprintVersion, sourceDigest,
                new ProductRef(ProductRefType.LIVE_PRODUCT, facts.product().getId(), command.productDigest(facts.product())),
                new WorkflowPin(workflow.getId(), revision.getId(), revision.getRevisionHash(),
                        revision.getDefinitionVersion(), revision.getSchemaVersion()),
                packaging, choices.outputQuantity(), canonicalUnit(factoryId, choices.outputUnit()), choices.netContentQuantity(),
                canonicalUnit(factoryId, choices.netContentUnit()), items, confirmedPreviewDigest, idempotencyKey, operatorId);
        return new Assembly(facts, request, List.of());
    }

    private Optional<PackagingDraft> latestPackagingDraft(String factoryId, String blueprintId, long blueprintVersion,
                                                          String sourceDigest) {
        PackagingDraft probe = new PackagingDraft();
        probe.setFactoryId(factoryId);
        probe.setBlueprintId(blueprintId);
        probe.setBlueprintVersion(blueprintVersion);
        probe.setSourceDigest(sourceDigest);
        probe.setStatus("DRAFT");
        return packagingDrafts.findAll(Example.of(probe), NEWEST_FIRST).stream()
                .filter(row -> row.getDesiredDigest() != null && !row.getDesiredDigest().isBlank()).findFirst();
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String string ? string : "";
    }
}
