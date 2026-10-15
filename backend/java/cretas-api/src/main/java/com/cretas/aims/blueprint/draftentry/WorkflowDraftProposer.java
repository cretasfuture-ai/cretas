package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteGraph;
import com.cretas.aims.blueprint.workflowassembly.WorkflowAssemblyModels.AssemblyBlocker;
import com.cretas.aims.blueprint.workflowassembly.WorkflowAssemblyModels.EntryInputChoice;
import com.cretas.aims.blueprint.workflowassembly.WorkflowAssemblyModels.RouteFacts;
import com.cretas.aims.blueprint.workflowassembly.WorkflowCompileRequestAssembler;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.DraftMode;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.ConversionItem;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code workflow} proposal: one {@link WorkflowDraftRequest} per blueprint product route, keyed by the
 * route's product code.
 *
 * <p>Preconditions come from {@link WorkflowCompileRequestAssembler#resolve} and are passed on verbatim
 * as blockers (product / processes not promoted to the live tables, route not a pure chain). The
 * framework's {@code Proposed} holds items <em>or</em> blockers, so one blocked route blocks the whole
 * proposal — the blockers name the product, nothing is dropped silently.</p>
 *
 * <p>Filled by the server: lineage, route product code, and — read, not guessed — the mode and the
 * existing DRAFT's lock version / revision hash. Asked: the entry raw materials and one semi-finished
 * product per non-terminal process; the blueprint has no fact that binds a live row to a process.
 * {@code conversionRates} rows whose product is this route and whose raw material code is a live raw
 * material are pre-filled as entry rows the user still confirms (the list stays a required input).</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class WorkflowDraftProposer implements CapabilityDraftProposer {

    private final WorkflowCompileRequestAssembler assembler;

    public WorkflowDraftProposer(WorkflowCompileRequestAssembler assembler) {
        this.assembler = assembler;
    }

    @Override
    public String capabilityId() {
        return WorkflowDraftEntry.CAPABILITY_ID;
    }

    @Override
    public Proposed propose(Source source) {
        BlueprintResponse blueprint = source.blueprint();
        List<RouteGraph> routes = blueprint.getRouteGraphs() == null ? List.of() : blueprint.getRouteGraphs();
        if (routes.stream().allMatch(route -> route == null)) {
            return new Proposed(List.of());
        }
        List<Blocker> blockers = new ArrayList<>();
        List<ProposedItem> items = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            RouteGraph route = routes.get(i);
            if (route == null) continue;
            if (route.productCode() == null || route.productCode().isBlank()) {
                blockers.add(new Blocker("WORKFLOW_ROUTE_PRODUCT_CODE_MISSING",
                        "蓝图第 " + (i + 1) + " 条工艺路线（" + route.productName() + "）没有产品编码，无法对应正式产品",
                        "回到建厂访谈补上这条路线的产品编码"));
                continue;
            }
            RouteFacts facts = assembler.resolve(source.factoryId(), blueprint, route.productCode());
            if (!facts.ready()) {
                facts.blockers().forEach(b -> blockers.add(new Blocker(b.code(), b.message(), b.nextStep())));
                continue;
            }
            item(source, facts, blockers).ifPresent(items::add);
        }
        if (!blockers.isEmpty()) {
            return new Proposed(List.of(), blockers);
        }
        return new Proposed(items);
    }

    private Optional<ProposedItem> item(Source source, RouteFacts facts, List<Blocker> blockers) {
        BlueprintResponse blueprint = source.blueprint();
        String code = facts.productCode();
        String productName = facts.product().getName();
        List<RawMaterialType> raws = assembler.liveRawMaterials(source.factoryId());
        List<ProductType> semis = assembler.liveSemiFinished(source.factoryId());
        List<String> producers = facts.nonTerminalNodeIds();
        boolean blocked = false;
        if (raws.isEmpty()) {
            blockers.add(new Blocker("WORKFLOW_RAW_MATERIAL_MISSING",
                    "本厂还没有已启用的正式原料，“" + productName + "”的第一道工序没有原料可投",
                    "先在原料草稿里确认原料并转正，再回来写 Workflow 草稿"));
            blocked = true;
        }
        if (semis.size() < producers.size()) {
            blockers.add(new Blocker("WORKFLOW_SEMI_FINISHED_MISSING",
                    "“" + productName + "”的路线有 " + producers.size() + " 道非末道工序，每道要产出一种不同的半成品；"
                            + "本厂已启用的正式半成品只有 " + semis.size() + " 种",
                    "在产品草稿里补齐半成品（类别选半成品）并转正，再回来写 Workflow 草稿"));
            blocked = true;
        }
        if (blocked) return Optional.empty();

        String routePath = "routeGraphs[" + facts.routeIndex() + "]";
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence(routePath + ".productCode", code));
        evidence.add(new Evidence(routePath + ".nodes", facts.chain().stream()
                .map(id -> id + "=" + facts.processName(id)).collect(Collectors.joining(" → "))));
        evidence.add(new Evidence("factory.productTypes[code=" + code + "].id", facts.product().getId()));
        facts.chain().forEach(id -> evidence.add(new Evidence("factory.workProcesses[name=" + facts.processName(id) + "].id",
                facts.processByNode().get(id).getId())));

        // ---- entry rows the blueprint's conversion rates point at (still to be confirmed by the user)
        String entryNode = facts.entryNodeId();
        List<EntryInputChoice> prefilled = new ArrayList<>();
        Set<String> prefilledIds = new LinkedHashSet<>();
        List<ConversionItem> conversions = blueprint.getSourcePayload().getSuggestedData() == null
                || blueprint.getSourcePayload().getSuggestedData().getConversionRates() == null
                ? List.of() : blueprint.getSourcePayload().getSuggestedData().getConversionRates();
        for (int j = 0; j < conversions.size(); j++) {
            ConversionItem conversion = conversions.get(j);
            if (conversion == null || !code.equals(conversion.getToCode()) || conversion.getFromCode() == null) continue;
            List<RawMaterialType> byCode = raws.stream().filter(raw -> conversion.getFromCode().equals(raw.getCode())).toList();
            String from = "sourcePayload.suggestedData.conversionRates[" + j + "]";
            if (byCode.size() == 1 && prefilledIds.add(byCode.getFirst().getId())) {
                prefilled.add(new EntryInputChoice(entryNode, byCode.getFirst().getId()));
                evidence.add(new Evidence(from + ".fromCode", conversion.getFromCode() + " → 正式原料 " + byCode.getFirst().getId()));
            } else if (byCode.isEmpty()) {
                evidence.add(new Evidence(from + ".fromCode", conversion.getFromCode() + "（本厂正式原料里没有这个编码，未预填）"));
            }
        }

        List<RequiredInput> inputs = new ArrayList<>();
        String entryLabel = facts.processName(entryNode);
        List<Option> rawOptions = raws.stream()
                .map(raw -> new Option(raw.getId(), raw.getName() + "（" + raw.getUnit() + "）")).toList();
        inputs.add(RequiredInput.list("entryInputs", "“" + productName + "”的第一道工序“" + entryLabel + "”投入哪些原料",
                prefilled.isEmpty() ? "蓝图没有把正式原料绑定到这道工序的事实"
                        : "已按蓝图出成率里的原料预填，仍需确认（蓝图只说原料产出这个产品，没说投在哪道工序）",
                "至少 1 行；每行一种本厂已启用的正式原料，同一原料只选一次", "入口原料", 1, null, List.of(
                        RequiredInput.oneOf("routeNodeId", "投入到哪道工序", "首期只支持纯顺序路线，原料只投入第一道工序",
                                "只能是第一道工序：" + entryLabel, List.of(new Option(entryNode, entryLabel))),
                        RequiredInput.oneOf("rawMaterialId", "投入哪种原料", "要用正式原料表里的行，不能按名称猜",
                                "本厂已启用的正式原料之一：" + labels(rawOptions), rawOptions))));

        if (!producers.isEmpty()) {
            List<Option> producerOptions = producers.stream().map(id -> new Option(id, facts.processName(id))).toList();
            List<Option> semiOptions = semis.stream()
                    .map(semi -> new Option(semi.getId(), semi.getName() + "（" + semi.getUnit() + "）")).toList();
            inputs.add(RequiredInput.list("wipOutputs", "“" + productName + "”每道非末道工序产出什么半成品",
                    "蓝图没有把正式半成品绑定到工序的事实",
                    "恰好 " + producers.size() + " 行，每道非末道工序一行；各道工序的半成品互不相同",
                    "工序半成品", producers.size(), producers.size(), List.of(
                            RequiredInput.oneOf("routeNodeId", "哪道工序", "末道工序产出成品，不需要选",
                                    "非末道工序之一：" + labels(producerOptions), producerOptions),
                            RequiredInput.oneOf("semiFinishedProductId", "产出哪种半成品",
                                    "要用正式产品表里类别为半成品的行，不能按名称猜",
                                    "本厂已启用的正式半成品之一：" + labels(semiOptions), semiOptions))));
        }

        List<String> laterNodes = facts.chain().subList(1, facts.chain().size());
        if (!laterNodes.isEmpty()) {
            List<Option> laterOptions = laterNodes.stream().map(id -> new Option(id, facts.processName(id))).toList();
            inputs.add(RequiredInput.list("extraInputs", "“" + productName + "”后面的工序还要投入哪些原料（辅料、包材等）",
                    "蓝图没有把辅料、包材绑定到具体工序的事实；不填就只有入口原料，配方里也就只有这些原料",
                    "可留空（留空就只有入口原料）；每行一道入口以外的工序加一种本厂已启用的正式原料，同一道工序同一种原料只选一次", "追加原料", 0, null, List.of(
                            RequiredInput.oneOf("routeNodeId", "投入到哪道工序", "入口工序的原料在上面的入口原料里选",
                                    "入口以外的工序之一：" + labels(laterOptions), laterOptions),
                            RequiredInput.oneOf("rawMaterialId", "投入哪种原料", "要用正式原料表里的行，不能按名称猜",
                                    "本厂已启用的正式原料之一：" + labels(rawOptions), rawOptions))).allowNull());
        }

        DraftMode mode = DraftMode.CREATE_NEW_ONLY;
        Long expectedLockVersion = null;
        String expectedRevisionHash = null;
        Optional<ProductProcessWorkflowDTO> existing = assembler.currentDraft(source.factoryId(), facts.product().getId());
        if (existing.isPresent()) {
            mode = null;
            expectedLockVersion = existing.get().getLockVersion();
            expectedRevisionHash = existing.get().getRevisionHash();
            String at = "factory.productProcessWorkflows[productTypeId=" + facts.product().getId() + ",status=DRAFT]";
            evidence.add(new Evidence(at + ".lockVersion", String.valueOf(expectedLockVersion)));
            evidence.add(new Evidence(at + ".revisionHash", expectedRevisionHash));
            inputs.add(RequiredInput.oneOf("mode", "“" + productName + "”已经有 Workflow 草稿，怎么处理",
                    "已有草稿时默认的“只新建”会被拒绝，必须明确选一种处理方式",
                    "MERGE_EXISTING（合并进现有草稿）或 SNAPSHOT_AND_REPLACE（旧草稿另存快照后替换）",
                    List.of(new Option(DraftMode.MERGE_EXISTING.name(), "合并进现有草稿"),
                            new Option(DraftMode.SNAPSHOT_AND_REPLACE.name(), "旧草稿另存快照后替换"))));
        }

        WorkflowDraftRequest request = new WorkflowDraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), code, prefilled, List.of(), List.of(), mode, expectedLockVersion, expectedRevisionHash,
                null, null, null, 0L);
        return Optional.of(new ProposedItem(code, "Workflow 草稿：" + productName, request, inputs, evidence));
    }

    private static String labels(List<Option> options) {
        return options.stream().map(Option::label).collect(Collectors.joining("、"));
    }
}
