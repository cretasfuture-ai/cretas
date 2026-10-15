package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.BomLineChoice;
import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.Facts;
import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.RawInputPort;
import com.cretas.aims.blueprint.bomassembly.BomCompileRequestAssembler;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteGraph;
import com.cretas.aims.service.bomdraft.BomRecipeDraftCommand;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.MaterialCategory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code bom} proposal: one {@link BomDraftRequest} per blueprint product route, keyed by the route's
 * product code.
 *
 * <p>The BOM pins the product's current Workflow DRAFT, so the proposal needs the product promoted to the
 * live table and its Workflow DRAFT written. When either is missing the whole proposal is blocked with the
 * assembler's wording (which product, what to do first) — nothing is dropped silently and no pin is
 * invented. An empty blueprint (no routes) proposes nothing.</p>
 *
 * <p>Filled by the server: the lineage, the product code, the product's own unit as the output unit (read
 * from the live row), and one row per raw-material input port of the Workflow DRAFT with only the port set.
 * Asked: how much the recipe makes, why it is this way, and for every row its category, standard quantity,
 * yield and whether it is optional — the blueprint has no fact for any of them.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class BomDraftProposer implements CapabilityDraftProposer {

    private static final BigDecimal TINY = new BigDecimal("0.00000001");
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000000000");
    /** What the formal recipe writer keeps for every line, whatever is sent (BomRecipeItemDTO.yieldRate is ignored). */
    private static final BigDecimal LIVE_LINE_YIELD = new BigDecimal("100");
    /** The formal recipe is per ONE unit of the product (CreateBomRecipeRequest.outputQuantityPerUnit is fixed to 1). */
    private static final BigDecimal LIVE_OUTPUT_QUANTITY = BigDecimal.ONE;

    private static final List<Option> CATEGORIES = List.of(
            new Option(MaterialCategory.RAW.name(), "原料"),
            new Option(MaterialCategory.AUXILIARY.name(), "辅料"),
            new Option(MaterialCategory.PACKAGING.name(), "包材（需要先有包装草稿）"));

    private final BomCompileRequestAssembler assembler;

    public BomDraftProposer(BomCompileRequestAssembler assembler) {
        this.assembler = assembler;
    }

    @Override
    public String capabilityId() {
        return BomRecipeDraftCommand.CAPABILITY_ID;
    }

    @Override
    public Proposed propose(Source source) {
        BlueprintResponse blueprint = source.blueprint();
        List<RouteGraph> routes = blueprint.getRouteGraphs() == null ? List.of() : blueprint.getRouteGraphs();
        List<Blocker> blockers = new ArrayList<>();
        List<ProposedItem> items = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < routes.size(); i++) {
            RouteGraph route = routes.get(i);
            if (route == null) continue;
            if (route.productCode() == null || route.productCode().isBlank()) {
                blockers.add(new Blocker("BOM_ROUTE_PRODUCT_CODE_MISSING",
                        "蓝图第 " + (i + 1) + " 条工艺路线（" + route.productName() + "）没有产品编码，无法对应正式产品",
                        "回到建厂访谈补上这条路线的产品编码"));
                continue;
            }
            if (!seen.add(route.productCode())) continue;
            Facts facts = assembler.facts(source.factoryId(), route.productCode());
            if (!facts.ready()) {
                facts.blockers().forEach(b -> blockers.add(new Blocker(b.code(), b.message(), b.nextStep())));
                continue;
            }
            items.add(item(source, blueprint, route, facts));
        }
        return blockers.isEmpty() ? new Proposed(items) : new Proposed(List.of(), blockers);
    }

    private ProposedItem item(Source source, BlueprintResponse blueprint, RouteGraph route, Facts facts) {
        String code = route.productCode();
        String productName = facts.product().getName();
        String productUnit = facts.product().getUnit() == null ? "" : facts.product().getUnit();
        // the command saves the output unit as the catalog's canonical code (「个」 → pcs); the product row has the storage unit
        String outputUnit = assembler.canonicalUnit(source.factoryId(), productUnit);
        List<RawInputPort> ports = facts.rawInputPorts();

        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("routeGraphs[productCode=" + code + "].productCode", code));
        evidence.add(new Evidence("factory.productTypes[code=" + code + "].id", facts.product().getId()));
        evidence.add(new Evidence("factory.productTypes[code=" + code + "].unit", productUnit));
        evidence.add(new Evidence("factory.productProcessWorkflows[productTypeId=" + facts.product().getId() + ",status=DRAFT]"
                + ".currentRevisionHash", facts.workflow().getCurrentRevisionHash()));
        ports.forEach(port -> evidence.add(new Evidence("workflow.ports[" + port.portId() + "]", port.label())));

        List<Option> portOptions = ports.stream().map(port -> new Option(port.portId(), port.label())).toList();
        // 100: the formal recipe takes no per-line yield (it is computed from batch history), so a draft carrying
        // anything else could never be promoted; not a question for the user.
        List<BomLineChoice> rows = ports.stream()
                .map(port -> new BomLineChoice(port.portId(), null, null, LIVE_LINE_YIELD, null)).toList();

        List<RequiredInput> inputs = new ArrayList<>();
        evidence.add(new Evidence("rule.formalRecipe.outputQuantity",
                "正式配方按每 1 " + outputUnit + " 产出计量，下面的用量请按“做 1 " + outputUnit + "“" + productName + "”需要多少”填写"));
        evidence.add(new Evidence("rule.formalRecipe.lineYield", "正式配方不接收单行出成率（由正式批次历史自动计算），本草稿统一记 100"));
        inputs.add(RequiredInput.text("changeReason", "这份配方为什么这样定",
                "每一行配方都要带变更原因，蓝图没有这条事实", "不超过 500 字", 500));
        inputs.add(RequiredInput.list("lines", "“" + productName + "”的配方：Workflow 里每个投入原料的端口用多少",
                "蓝图没有给每种原料的用量；端口来自 " + productName + " 当前的 Workflow 草稿",
                "至少 1 行、最多 " + ports.size() + " 行；每个端口只能用一次", "配方行", 1, ports.size(), List.of(
                        RequiredInput.oneOf("workflowPortId", "哪个投入端口", "要选 Workflow 草稿里真实存在的投入端口",
                                "当前 Workflow 的投入端口之一：" + labels(portOptions), portOptions),
                        RequiredInput.oneOf("category", "这一行是什么类别", "蓝图没有说这种物料在配方里算原料、辅料还是包材",
                                "原料、辅料、包材之一", CATEGORIES),
                        RequiredInput.decimal("standardQuantity", "标准用量（按端口单位）", "蓝图没有给用量",
                                "大于 0，最多 8 位小数", TINY, MAX_QUANTITY),
                        RequiredInput.bool("optional", "这一行是否可选", "蓝图没有说这种物料是不是可以不放",
                                "是或否"))));

        BomDraftRequest request = new BomDraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), code, LIVE_OUTPUT_QUANTITY, outputUnit, null, "", null, rows, null, null, 0L);
        return new ProposedItem(code, "配方草稿：" + productName, request, inputs, evidence);
    }

    private static String labels(List<Option> options) {
        return options.stream().map(Option::label).collect(Collectors.joining("、"));
    }
}
