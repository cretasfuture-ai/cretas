package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.EdgeType;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteEdge;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteGraph;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteIssue;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteNode;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.TypeItem;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.productdraft.ProductTypeDraftCommand;
import com.cretas.aims.service.productdraft.ProductTypeDraftModels.DesiredProduct;
import com.cretas.aims.service.productdraft.ProductTypeDraftModels.DraftRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code products} proposal: one {@link ProductTypeDraftCommand} request per
 * {@code suggestedData.productTypes} entry, keyed by the product code, followed by one
 * semi-finished (WIP) request per non-terminal step of that product's blueprint route.
 *
 * <p>New vs update follows the command: it identifies a product by {@code (factoryId, code)} and
 * refuses a blank {@code targetProductTypeId} when that code already exists ("不能把更新伪装成新建").
 * So the proposal looks the code up with the same repository method and, when found, targets that
 * product. Code, name, unit and product category are copied only when the interview gave them; a
 * missing one is left empty and asked for. In particular a missing product category is <em>not</em>
 * defaulted to {@code FINISHED_PRODUCT} (the legacy apply's default) — the category decides which
 * downstream selectors see the SKU.</p>
 *
 * <p><b>Semi-finished SKUs.</b> The Workflow compiler binds every non-terminal route step to a
 * {@code SEMI_FINISHED} SKU (its {@code WIP_BINDING_REQUIRED}). For a product whose route
 * ({@code BlueprintResponse.routeGraphs}, matched by product code) is one valid chain of FORWARD
 * edges, every step except the last gets an item:</p>
 * <ul>
 *   <li>item key {@code WIP:{productCode}:{routeNodeId}} — stable when steps are renamed;</li>
 *   <li>category {@code SEMI_FINISHED}: derived from the step's position (it has a next step), not guessed;</li>
 *   <li>code {@code {productCode}-WIP{n}}, n = 1.. in route order — generated here, so the same
 *       blueprint always proposes the same codes. The code is the draft's natural key. When it cannot
 *       be used as-is (too long for the column, already a blueprint product's code, or the factory
 *       holds a non-semi-finished product with that code) it is asked instead. A factory
 *       semi-finished product with that code is targeted, exactly as for finished products;</li>
 *   <li>name and unit are not route facts and are always asked.</li>
 * </ul>
 * <p>A route that is invalid, has non-FORWARD edges, is not a single line, or is one of several
 * routes for the same product yields no semi-finished items; the finished item's evidence says why.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class ProductsDraftProposer implements CapabilityDraftProposer {

    /**
     * The command accepts any non-blank category except {@code RAW_MATERIAL}; these are the values the
     * product table actually uses ({@code ProductTypeServiceImpl} code prefixes, {@link ProductCategory}),
     * so the user picks one of them instead of typing a new category. {@code SEMI_FINISHED} is last on
     * purpose: semi-finished SKUs normally come from the route items below, a finished product should
     * not land on it by picking the first option.
     */
    static final List<Option> CATEGORY_OPTIONS = List.of(
            new Option(ProductCategory.FINISHED_PRODUCT, "成品"), new Option(ProductCategory.PACKAGING, "包材"),
            new Option(ProductCategory.SEASONING, "调料"), new Option(ProductCategory.CUSTOMER_MATERIAL, "客供料"),
            new Option(ProductCategory.SEMI_FINISHED, "半成品"));
    static final String CATEGORY_CONSTRAINT =
            "不能为空，也不能是 RAW_MATERIAL（原料走原料能力）；产品表现有取值：FINISHED_PRODUCT（成品）、"
                    + "PACKAGING（包材）、SEASONING（调料）、CUSTOMER_MATERIAL（客供料）、"
                    + "SEMI_FINISHED（半成品，工序之间的在制品；路线上的半成品会单独列出，成品不要选它）";

    /** Item keys of semi-finished items: {@code WIP:{productCode}:{routeNodeId}}. */
    static final String WIP_ITEM_KEY_PREFIX = "WIP:";
    /** Generated semi-finished code: {@code {productCode}-WIP{n}}. */
    static final String WIP_CODE_INFIX = "-WIP";
    /** {@code product_types.code} and {@code product_type_drafts.natural_key} are VARCHAR(50). */
    static final int MAX_CODE = 50;
    /** {@code product_type_drafts.desired_name} is VARCHAR(200). */
    static final int MAX_NAME = 200;
    /** {@code product_types.unit} and {@code product_type_drafts.desired_unit} are VARCHAR(20). */
    static final int MAX_UNIT = 20;

    private final ProductTypeRepository productTypeRepository;

    public ProductsDraftProposer(ProductTypeRepository productTypeRepository) {
        this.productTypeRepository = productTypeRepository;
    }

    @Override
    public String capabilityId() {
        return ProductTypeDraftCommand.CAPABILITY_ID;
    }

    @Override
    public Proposed propose(Source source) {
        var blueprint = source.blueprint();
        ApplyAIInitRequest payload = blueprint.getSourcePayload();
        List<TypeItem> products = payload.getSuggestedData() == null || payload.getSuggestedData().getProductTypes() == null
                ? List.of() : payload.getSuggestedData().getProductTypes();

        Set<String> seen = new HashSet<>();
        for (TypeItem product : products) {
            String code = text(product == null ? null : product.getCode());
            if (!code.isEmpty() && !seen.add(code)) {
                return Proposed.blocked("BLUEPRINT_DUPLICATE_PRODUCT_CODE",
                        "建厂蓝图里产品编号“" + code + "”出现了不止一次，无法确定每条草稿对应哪个产品",
                        "回到建厂访谈，修正重复的产品编号");
            }
        }

        List<RouteGraph> routes = blueprint.getRouteGraphs() == null ? List.of() : blueprint.getRouteGraphs();
        List<ProposedItem> items = new ArrayList<>();
        for (int i = 0; i < products.size(); i++) {
            TypeItem product = products.get(i);
            if (product == null) continue;
            String code = text(product.getCode());
            Chain chain = chain(routes, code);
            items.add(item(source, i, product, chain));
            for (int step = 0; step + 1 < chain.ordered().size(); step++) {
                items.add(semiFinishedItem(source, product, code, chain, step, seen));
            }
        }
        // No products in the blueprint => no items; the controller reports BLUEPRINT_PROPOSAL_EMPTY.
        return new Proposed(items);
    }

    private ProposedItem item(Source source, int index, TypeItem product, Chain chain) {
        var blueprint = source.blueprint();
        String from = "sourcePayload.suggestedData.productTypes[" + index + "].";
        String code = text(product.getCode());
        String name = text(product.getName());
        String unit = text(product.getUnit());
        String category = text(product.getProductCategory());
        String title = name.isEmpty() ? (code.isEmpty() ? "第 " + (index + 1) + " 个产品" : code) : name;
        List<RequiredInput> inputs = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();

        if (code.isEmpty()) {
            inputs.add(RequiredInput.text("desired.code", "“" + title + "”的产品编号",
                    "建厂访谈没有给这个产品编号", "不能为空；本厂已有的编号会被当作更新那个产品", null));
        } else {
            evidence.add(new Evidence(from + "code", code));
        }
        if (name.isEmpty()) {
            inputs.add(RequiredInput.text("desired.name", "“" + title + "”的产品名称",
                    "建厂访谈没有给这个产品名称", "不能为空，且不能与本厂其他产品同名", null));
        } else {
            evidence.add(new Evidence(from + "name", name));
        }
        if (unit.isEmpty()) {
            inputs.add(RequiredInput.text("desired.unit", "“" + title + "”用什么单位计量",
                    "建厂访谈没有给这个产品的单位", "必须是本厂单位目录能识别的单位（如 kg、斤、盒、箱）", null));
        } else {
            evidence.add(new Evidence(from + "unit", unit));
        }
        String canonicalCategory = category.toUpperCase(java.util.Locale.ROOT);
        if (category.isEmpty()) {
            inputs.add(RequiredInput.oneOf("desired.productCategory", "“" + title + "”属于哪类产品",
                    "建厂访谈没有给产品类别", CATEGORY_CONSTRAINT, CATEGORY_OPTIONS));
        } else if (!com.cretas.aims.service.productdraft.ProductTypeDraftCommand.FACTORY_PRODUCT_CATEGORIES
                .contains(canonicalCategory)) {
            // An interview category the product table does not know (e.g. 「熟食」) is asked, not copied:
            // copied, it would reach the live table and nothing downstream would recognise it.
            inputs.add(RequiredInput.oneOf("desired.productCategory", "“" + title + "”属于哪类产品",
                    "建厂访谈给的类别「" + category + "」不是系统的产品类别", CATEGORY_CONSTRAINT, CATEGORY_OPTIONS));
            evidence.add(new Evidence(from + "productCategory", "未带入：" + category));
            category = "";
        } else {
            category = canonicalCategory;
            evidence.add(new Evidence(from + "productCategory", category));
        }
        if (chain.rejection() != null) {
            evidence.add(new Evidence(chain.path(), "未提出半成品：" + chain.rejection()));
        }

        String targetId = null;
        if (!code.isEmpty()) {
            Optional<ProductType> existing = productTypeRepository.findByFactoryIdAndCode(source.factoryId(), code);
            if (existing.isPresent()) {
                targetId = existing.get().getId();
                evidence.add(new Evidence("factory.productTypes[code=" + code + "].id", targetId));
            }
        }

        DraftRequest request = new DraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), targetId,
                new DesiredProduct(nullIfEmpty(code), nullIfEmpty(name), nullIfEmpty(unit), nullIfEmpty(category)),
                null, null, 0L);
        String itemKey = code.isEmpty() ? "#" + (index + 1) : code;
        return new ProposedItem(itemKey, (targetId == null ? "新建产品：" : "更新产品：") + title, request, inputs, evidence);
    }

    /**
     * The semi-finished SKU produced by step {@code step} (0-based) of {@code chain}, which is not the
     * last step. {@code blueprintCodes} are the blueprint's own product codes.
     */
    private ProposedItem semiFinishedItem(Source source, TypeItem product, String productCode, Chain chain, int step,
                                          Set<String> blueprintCodes) {
        var blueprint = source.blueprint();
        RouteNode node = chain.ordered().get(step);
        int total = chain.ordered().size();
        int sequence = step + 1;
        String productTitle = text(product.getName()).isEmpty() ? productCode : text(product.getName());
        String title = productTitle + "·" + node.label() + "后";
        List<RequiredInput> inputs = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        String nodeAt = chain.path() + ".nodes[" + chain.positions().get(node.id()) + "]";
        evidence.add(new Evidence(chain.path() + ".productCode", productCode));
        evidence.add(new Evidence(nodeAt + ".id", node.id()));
        evidence.add(new Evidence(nodeAt + ".label", node.label()));
        evidence.add(new Evidence(chain.path() + ".order",
                "第 " + sequence + " 道 / 共 " + total + " 道，后面还有工序，所以这道工序的产出是半成品（SEMI_FINISHED）"));

        String code = productCode + WIP_CODE_INFIX + sequence;
        String codeProblem = null;
        String targetId = null;
        if (code.length() > MAX_CODE) {
            codeProblem = "按规则生成的编号“" + code + "”超过 " + MAX_CODE + " 字";
        } else if (blueprintCodes.contains(code)) {
            codeProblem = "按规则生成的编号“" + code + "”已是建厂蓝图里另一个产品的编号";
        } else {
            Optional<ProductType> existing = productTypeRepository.findByFactoryIdAndCode(source.factoryId(), code);
            if (existing.isPresent() && ProductCategory.SEMI_FINISHED.equals(text(existing.get().getProductCategory()))) {
                targetId = existing.get().getId();
                evidence.add(new Evidence("factory.productTypes[code=" + code + "].id", targetId));
            } else if (existing.isPresent()) {
                // The command would require targeting it (same code), but the live writer refuses to change
                // an existing SKU's category outside the migration flow (SKU_UNIT_MIGRATION_REQUIRED).
                String existingCategory = text(existing.get().getProductCategory());
                codeProblem = "本厂已有编号“" + code + "”的产品“" + text(existing.get().getName()) + "”，类别是"
                        + (existingCategory.isEmpty() ? "未分类" : existingCategory)
                        + "，不是半成品；普通编辑不能改它的类别";
            }
        }
        if (codeProblem == null) {
            evidence.add(new Evidence("generated.code", code));
        } else {
            inputs.add(RequiredInput.text("desired.code", "“" + title + "”的半成品编号", codeProblem,
                    "不能为空，不超过 " + MAX_CODE + " 字；用本厂和建厂蓝图里都没用过的编号（本厂已有的半成品编号会被当作更新那个半成品）",
                    MAX_CODE));
        }
        inputs.add(RequiredInput.text("desired.name", "“" + title + "”的半成品叫什么",
                "路线只说明“" + node.label() + "”之后有在制品，没有说它叫什么",
                "这道工序产出的在制品叫什么，如『解冻后牛腱』；不能为空，不超过 " + MAX_NAME + " 字，且不能与本厂其他产品同名",
                MAX_NAME));
        inputs.add(RequiredInput.text("desired.unit", "“" + title + "”的半成品用什么单位计量",
                "路线没有给在制品的计量单位",
                "必须是本厂单位目录能识别的单位（如 kg、斤、个），不超过 " + MAX_UNIT + " 字；半成品只定基本单位，不带规格和箱规",
                MAX_UNIT));

        DraftRequest request = new DraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), targetId,
                new DesiredProduct(codeProblem == null ? code : null, null, null, ProductCategory.SEMI_FINISHED),
                null, null, 0L);
        return new ProposedItem(WIP_ITEM_KEY_PREFIX + productCode + ":" + node.id(),
                (targetId == null ? "新建半成品：" : "更新半成品：") + title, request, inputs, evidence);
    }

    /**
     * The product's route as an ordered single line, or why it is not one. {@code ordered} is empty when
     * there is no route or it is rejected; {@code rejection} is null unless a route exists and is rejected.
     */
    record Chain(String path, List<RouteNode> ordered, Map<String, Integer> positions, String rejection) {
        static Chain none(String path) {
            return new Chain(path, List.of(), Map.of(), null);
        }

        static Chain rejected(String path, String why) {
            return new Chain(path, List.of(), Map.of(), why);
        }
    }

    static Chain chain(List<RouteGraph> routes, String productCode) {
        String path = "routeGraphs[productCode=" + productCode + "]";
        if (productCode.isEmpty()) return Chain.none(path);
        List<RouteGraph> mine = routes.stream()
                .filter(graph -> graph != null && productCode.equals(text(graph.productCode()))).toList();
        if (mine.isEmpty()) return Chain.none(path);
        if (mine.size() > 1) {
            return Chain.rejected(path, "建厂蓝图里这个产品有 " + mine.size() + " 条路线，无法确定按哪一条拆半成品");
        }
        RouteGraph graph = mine.get(0);
        if (graph.validation() == null || !graph.validation().valid()) {
            String issues = graph.validation() == null ? "没有校验结果"
                    : graph.validation().issues().stream().map(RouteIssue::code).collect(Collectors.joining("、"));
            return Chain.rejected(path, "路线没有通过校验（" + issues + "）");
        }
        Set<String> otherTypes = graph.edges().stream().map(RouteEdge::type).filter(type -> type != EdgeType.FORWARD)
                .map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!otherTypes.isEmpty()) {
            return Chain.rejected(path, "路线含 " + String.join("、", otherTypes)
                    + " 连线，不是纯顺序路线；分支、合流、返工处的半成品要按确认后的路线另建");
        }

        Map<String, RouteNode> byId = new HashMap<>();
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < graph.nodes().size(); i++) {
            byId.put(graph.nodes().get(i).id(), graph.nodes().get(i));
            positions.put(graph.nodes().get(i).id(), i);
        }
        Map<String, String> next = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        boolean singleLine = graph.entryNodeIds().size() == 1 && graph.terminalNodeIds().size() == 1
                && graph.edges().size() == graph.nodes().size() - 1;
        for (RouteEdge edge : graph.edges()) {
            if (next.put(edge.fromNodeId(), edge.toNodeId()) != null
                    || incoming.merge(edge.toNodeId(), 1, Integer::sum) > 1) {
                singleLine = false;
            }
        }
        List<RouteNode> ordered = new ArrayList<>();
        if (singleLine) {
            String cursor = graph.entryNodeIds().get(0);
            Set<String> visited = new HashSet<>();
            while (cursor != null && visited.add(cursor)) {
                ordered.add(byId.get(cursor));
                cursor = next.get(cursor);
            }
            singleLine = ordered.size() == graph.nodes().size();
        }
        if (!singleLine) {
            return Chain.rejected(path, "路线不是一条首尾相接的单线（有工序接了多道下游或汇入了多道上游）");
        }
        return new Chain(path, List.copyOf(ordered), Map.copyOf(positions), null);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nullIfEmpty(String value) {
        return value.isEmpty() ? null : value;
    }
}
