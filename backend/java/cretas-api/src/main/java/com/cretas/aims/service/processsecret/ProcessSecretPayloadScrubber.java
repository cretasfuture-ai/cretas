package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工艺保密 V2 的统一出口裁剪: 对一份即将发出的 JSON (HTTP 响应体 / AI 工具结果) 按字段词表去掉
 * 配比、单位用量、转换率、标准出成率, 不管它来自哪个接口、哪个 DTO。
 *
 * <p>判定分三档 ({@link Level}): 保密负责人和有效授权 → 完整; 本厂其他账号 → 「物料 + 本单总量」
 * (只去配比类字段, 总量保留); 平台人员 / 他厂 / 无法识别 → 连总量一起去掉。</p>
 *
 * <p>对象归属: 对象自身或上层的 {@code productTypeId} (多产出计划的锚点 {@code __...} 不算),
 * 其次 {@code recipeId} / {@code bomRecipeId}。归属不清、而本次读过受限产品的工艺内容
 * ({@link ProcessSecretReadTracker}) 时按受限处理; 读过的受限产品在输出里一个归属都没有出现
 * (内容挂在别的产品或锚点下) 时, 整份输出都按受限处理 —— 宁可多遮, 不漏。</p>
 *
 * <p>每个被裁剪的产品每次输出写一条 DENY 审计; 受保护产品把配比类字段完整发给有权的人时写 ALLOW,
 * ALLOW 写不进去按受限处理 (与 {@link ProcessSecretAccessService#require} 一致)。</p>
 */
@Component
public class ProcessSecretPayloadScrubber {

    public static final String MARKER = "processSecret";
    public static final String TOTALS_ONLY_MESSAGE = "该产品工艺已设为保密：可以看物料和本单总量，配比、单位用量、标准出成率和成本合计已隐藏";
    public static final String RESTRICTED_MESSAGE = "该产品工艺已设为保密，需要保密负责人授权才能查看";

    public enum Level { FULL, TOTALS_ONLY, NONE }

    /**
     * 配比 / 单位用量 / 转换率 / 标准出成率: 受限 (含「本单总量」档) 一律去掉。
     * 新增此类字段要加在这里, ProcessSecretDataLayerContractTest 会扫描 DTO 字段名逼着归类。
     */
    public static final Set<String> RATIO_FIELDS = Set.of(
            "conversionRate", "conversionRatio", "oldConversionRate", "newConversionRate",
            "wastageRate", "oldWastageRate", "newWastageRate", "currentWastage",
            "standardUsage", "standardQuantity", "outputQuantityPerUnit", "perKgUsage",
            "inputShareRatio", "inputShareRatios", "dosagePerKgG", "injectionAmountKg",
            "overallYieldRate", "standardYieldRate", "standardYieldPercent", "standardYieldMax", "standardYieldMin",
            "desiredStandardYieldMin", "desiredStandardYieldMax",
            "currentYieldRate", "previousYieldRate", "suggestedYieldRate", "expectedCurrentYieldRate",
            "rawToFgYield", "rawToWipYield", "wipToFgYield",
            "inputSharePercent", "maxSharePercent", "subsequentPotRatio", "yieldPercent", "yieldMin", "yieldMax",
            "suggestedStandardQuantity", "currentRate", "suggestedRate", "conversionRateChange", "wastageRateChange",
            // 汇合配比校验 (缺口 06) 的落库快照: 配方份额, 以及「实际 − 配方」的偏差
            "recipeSharePercent", "deviationPp", "maxDeviationPp",
            // 每一行「是否超差」+ 实际份额 = 配方份额落在 实际 ± 容差 之内, 攒几次就逼近配方 (第二轮 #8)
            "overTolerance",
            // 报工行的回料配方比例 (工艺里配好的份额, 不是实测; 第二轮 #3)
            "inputRatioPercent",
            // 添加剂合规核对里的添加量 (g/kg) 就是配方用量; 超限倍数 = 添加量 ÷ 国标限量 (国标公开) 同样能还原
            "usageAmount", "exceedRatio",
            // 工艺图节点的投入 → 产出换算规则 (画布配置工具)
            "conversionRule",
            // 换料影响: 配方成本差额 ÷ 单价差 = 该料的单位用量
            "oldRecipeMaterialCost", "newRecipeMaterialCost", "costDelta");

    /*
     * 实测出成 / 损耗 / 超投 / 实际配比份额 (Steve 2026-10-26 第三问 = A「单批可看、汇总不给」):
     * 单批的数本来就能由「本单投入 ÷ 产出」算出, 所以本厂账号在单据视图 (报工、批次详情、本单成本) 里照常看;
     * 跨批汇总 (按产品平均 / 趋势、出成率分析页、工厂指标) 会逼近标准出成率和配比, 只给负责人和授权人。
     * 平台人员两者都看不到。
     */

    /** 单批 / 单单据的实测值: 单据视图里与「本单总量」同档, 汇总视图 ({@code aggregateView}) 里按配比类隐藏。 */
    public static final Set<String> SINGLE_RUN_ACTUAL_FIELDS = Set.of(
            "yieldRate", "actualYieldRate", "stepYieldRate", "cumulativeYieldRate", "realYieldRate",
            "asOfYieldRate", "sourceConsumedRatio", "weightSharePct", "lossRate",
            "overFeedRate", "actualSharePercent", "batchYield");

    /** 只出现在跨批汇总里的实测值 (日 / 加权 / 平均 / KPI / 按产品指标): 一律按配比类隐藏。 */
    public static final Set<String> AGGREGATE_ACTUAL_FIELDS = Set.of(
            "todayYieldRate", "weightedYieldRate", "netYieldRate", "goodOutputRate", "avgLossRate",
            "lossPercentage", "scrapRate", "scrapLossRate", "avgYieldRate", "averageYieldRate", "monthlyYieldRate",
            "yieldRateChange");

    /**
     * 汇总视图: {@code /api/mobile/{factoryId}/<模块>} 的模块段是这些之一 (出成率分析页、工厂指标、看板、报表),
     * 或路径里任一段是 {@link #AGGREGATE_ANY_SEGMENTS}。这些响应里单批实测值和跨批总量也算「汇总」。
     * AI 工具的回答一律按汇总视图处理。判定用 Spring 匹配到的路由模板, 不用原始 URI (矩阵参数 / 转义绕不过去)。
     */
    public static final Set<String> AGGREGATE_ROUTE_MODULES = Set.of(
            "production-analytics", "indicators", "canvas-indicators", "dashboard", "reports", "finance",
            "wastage-reports", "restaurant-dashboard");
    public static final Set<String> AGGREGATE_ANY_SEGMENTS = Set.of(
            "kpi", "statistics", "analytics", "trend", "daily-trend", "by-process", "by-product");

    /** 分页信息 (无权档也保留, 否则列表翻不了页)。 */
    static final Set<String> PAGING_FIELDS = Set.of(
            "totalElements", "totalPages", "page", "size", "pageSize", "pageNum", "pageNumber", "current", "pages",
            "numberOfElements");

    /** 名字含这些片段的字段一并按配比类处理 (统计里的平均转换率、损耗率等)。 */
    static final List<String> RATIO_SUFFIXES = List.of("conversionrate", "conversionratio", "wastagerate");

    /**
     * 名字本身有歧义的字段: 只有同一对象里出现了标志字段时才按配比类处理。
     * {@code rate} / {@code wastage} / {@code avgChange} 在转换率变更趋势里就是转换率和损耗率。
     */
    static final Map<String, Set<String>> CONTEXTUAL_RATIO_FIELDS = Map.of(
            // BOM 明细里的 yieldRate 是标准出成率; 和 goodOutputRate 同处的是按产品的汇总指标
            "yieldRate", Set.of("standardQuantity", "dosagePerKgG", "actualQuantity", "goodOutputRate"),
            // BOM 明细里的 actualQuantity 是出成调整后的单位用量, 不是本单总量 (独立审查 #9)
            "actualQuantity", Set.of("standardQuantity"),
            "rate", Set.of("changeType"),
            "wastage", Set.of("changeType"),
            "avgChange", Set.of("currentRate"));

    /** 「本单总量」档保留、无权档 (平台 / 他厂) 去掉的总量字段。 */
    public static final Set<String> QUANTITY_FIELDS = Set.of(
            "requiredQuantity", "requiredQty", "plannedQty", "plannedQuantityKg", "rawInputKg", "quantity",
            "shortfallQuantity", "standardKg", "actualQuantity", "totalRequired");

    /**
     * 对象归属的产品字段 (按顺序取第一个)。多产出计划的锚点 id 就是原料 id, 不受保护:
     * 它在受限计划的产品集合里, 由 planSiblings 按受限处理 (独立审查第三轮 A), 不靠 id 前缀认。
     */
    static final List<String> PRODUCT_KEYS = List.of("productTypeId", "sourceProductTypeId");
    static final List<String> RECIPE_KEYS = List.of("recipeId", "bomRecipeId", "sourceRecipeId");

    private final ProcessSecretAccessService accessService;
    private final BomRecipeRepository recipeRepository;
    private final ProductionWorkflowInstanceRepository instanceRepository;
    private final ProductionPlanRepository planRepository;
    private final FactoryMaterialRequisitionRepository requisitionRepository;
    private final BomRecipeDraftRepository draftRepository;
    private final ProductionBatchRepository batchRepository;

    public ProcessSecretPayloadScrubber(ProcessSecretAccessService accessService,
                                        BomRecipeRepository recipeRepository,
                                        ProductionWorkflowInstanceRepository instanceRepository,
                                        ProductionPlanRepository planRepository,
                                        FactoryMaterialRequisitionRepository requisitionRepository,
                                        BomRecipeDraftRepository draftRepository,
                                        ProductionBatchRepository batchRepository) {
        this.accessService = accessService;
        this.recipeRepository = recipeRepository;
        this.instanceRepository = instanceRepository;
        this.planRepository = planRepository;
        this.requisitionRepository = requisitionRepository;
        this.draftRepository = draftRepository;
        this.batchRepository = batchRepository;
    }

    /** 判定档位 (不写审计)。 */
    public Level levelOf(AuthenticatedActor actor, String factoryId, String productTypeId) {
        ProcessSecretAccessService.Decision decision = accessService.decide(actor, factoryId, productTypeId,
                ProcessSecretAction.VIEW_FULL);
        if (decision.permitted()) return Level.FULL;
        return degradedLevel(actor, factoryId);
    }

    /** 无完整查看权时的档位: 本厂工厂用户看「物料 + 本单总量」, 其余一律看不到。 */
    static Level degradedLevel(AuthenticatedActor actor, String factoryId) {
        boolean sameFactoryUser = actor != null && !actor.isPlatformAdmin() && factoryId != null
                && factoryId.equals(actor.factoryId());
        return sameFactoryUser ? Level.TOTALS_ONLY : Level.NONE;
    }

    /**
     * 原地裁剪。
     *
     * @param requestFactory 本次输出所属的工厂 (路径上的 factoryId, 或工具的工厂上下文)
     * @param reads          本次作用域里读过的工艺内容
     * @param aggregateView  汇总视图 (分析页 / 指标 / 报表 / AI 回答): 单批实测值也按配比类隐藏
     * @return true 表示有字段被去掉 (调用方要改用裁剪后的树)
     */
    public boolean scrub(JsonNode tree, AuthenticatedActor actor, String requestFactory,
                         Set<ProcessSecretReadTracker.Read> reads, String resourceRef, boolean aggregateView) {
        if (tree == null || !(tree.isObject() || tree.isArray())) return false;
        Run run = new Run(actor, requestFactory, resourceRef, aggregateView);
        if (!run.anyProtection(reads)) return false;
        run.resolveReads(reads);
        // 第一遍: 输出里出现了哪些产品归属
        run.restrictedInOutput = run.collectAttributions(tree, null);
        run.decideFallback();
        // 第二遍: 裁剪
        run.walk(tree, null);
        run.flushAudits();
        return run.changed;
    }

    /**
     * 路由模板 (如 {@code /api/mobile/{factoryId}/production-analytics/by-product}) 是否汇总视图。
     * 见 {@link #AGGREGATE_ROUTE_MODULES} / {@link #AGGREGATE_ANY_SEGMENTS}。
     */
    public static boolean isAggregateRoute(String routePattern) {
        if (routePattern == null) return false;
        String[] segments = routePattern.split("/");
        for (int i = 0; i < segments.length; i++) {
            String s = segments[i].toLowerCase(Locale.ROOT);
            if (AGGREGATE_ANY_SEGMENTS.contains(s) || s.endsWith("-trend") || s.endsWith("-statistics")
                    || s.endsWith("-stats")) {
                return true;
            }
            // 半成品全历史加权出成率; 半成品库存 / 盘点 (/{factoryId}/semi-finished...) 是单据, 不算 (第三轮 F)
            if ("semi-finished".equals(s) && i > 0 && "yield".equalsIgnoreCase(segments[i - 1])) {
                return true;
            }
            if (i > 0 && "{factoryid}".equals(segments[i - 1].toLowerCase(Locale.ROOT))
                    && AGGREGATE_ROUTE_MODULES.contains(s)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------

    private record Key(String factoryId, String productTypeId) { }

    private final class Run {
        final AuthenticatedActor actor;
        final String requestFactory;
        final String resourceRef;
        final boolean aggregateView;
        final Map<String, Set<String>> protectedByFactory = new HashMap<>();
        final Map<Key, Level> levels = new HashMap<>();
        final Map<String, Optional<Key>> recipeKeys = new HashMap<>();
        /** 本次读过、且对当前调用者受限的产品 (含解析不出产品的读取, productTypeId 为 null)。 */
        final Set<Key> restrictedReads = new LinkedHashSet<>();
        final Set<Key> attributed = new LinkedHashSet<>();
        /** 受限计划牵涉的全部产品 (含不受保护的兄弟产出): 归属到它们的对象同样按受限处理。 */
        final Set<Key> planSiblings = new LinkedHashSet<>();
        final Map<String, List<Key>> planCache = new HashMap<>();
        /** 归属不清时用的档位; null 表示归属不清的对象不用裁剪。 */
        Level fallback;
        /** 读过的受限产品没在输出里出现归属 → 整份按受限处理。 */
        boolean distrustAttribution;
        final Map<Key, Set<String>> hiddenByKey = new LinkedHashMap<>();
        final Set<Key> allowedWithContent = new LinkedHashSet<>();
        boolean changed;

        Run(AuthenticatedActor actor, String requestFactory, String resourceRef, boolean aggregateView) {
            this.actor = actor;
            this.requestFactory = requestFactory;
            this.resourceRef = resourceRef;
            this.aggregateView = aggregateView;
        }

        Set<String> protectedIn(String factoryId) {
            if (factoryId == null) return Set.of();
            return protectedByFactory.computeIfAbsent(factoryId, accessService::protectedProductTypeIds);
        }

        boolean anyProtection(Set<ProcessSecretReadTracker.Read> reads) {
            if (!protectedIn(requestFactory).isEmpty()) return true;
            if (actor != null && !protectedIn(actor.factoryId()).isEmpty()) return true;
            for (ProcessSecretReadTracker.Read read : reads) {
                if (!protectedIn(read.factoryId()).isEmpty()) return true;
            }
            return false;
        }

        Level level(Key key) {
            if (key.productTypeId() == null) return degradedLevel(actor, key.factoryId());
            if (!protectedIn(key.factoryId()).contains(key.productTypeId())) return Level.FULL;
            return levels.computeIfAbsent(key, k -> levelOf(actor, k.factoryId(), k.productTypeId()));
        }

        boolean isProtected(Key key) {
            return key.productTypeId() == null || protectedIn(key.factoryId()).contains(key.productTypeId());
        }

        void resolveReads(Set<ProcessSecretReadTracker.Read> reads) {
            for (ProcessSecretReadTracker.Read original : reads) {
                ProcessSecretReadTracker.Read read = withFactory(original);
                if (read == null || protectedIn(read.factoryId()).isEmpty()) continue;
                List<Key> keys = switch (read.kind()) {
                    case PRODUCT -> List.of(new Key(read.factoryId(), read.id()));
                    case RECIPE -> List.of(recipeKey(read.factoryId(), read.id())
                            .orElse(new Key(read.factoryId(), null)));
                    case WORKFLOW_INSTANCE -> List.of(instanceKey(read.factoryId(), read.id())
                            .orElse(new Key(read.factoryId(), null)));
                    case WORKFLOW_OUTPUT -> withMember(read, List.of(new Key(read.factoryId(), read.id())));
                    case PRODUCTION_PLAN -> withMember(read, planKeys(read.factoryId(), read.id()));
                    case PRODUCTION_BATCH -> withMember(read, batchKeys(read.factoryId(), read.id()));
                    case MATERIAL_REQUISITION -> requisitionRepository.findById(read.id())
                            .filter(r -> read.factoryId().equals(r.getFactoryId()))
                            .filter(r -> r.getProductionPlanId() != null && !r.getProductionPlanId().isBlank())
                            .map(r -> planKeys(read.factoryId(), r.getProductionPlanId()))
                            .orElse(List.of());
                    case BOM_DRAFT -> draftRepository.findById(read.id())
                            .filter(d -> read.factoryId().equals(d.getFactoryId()))
                            .filter(d -> "LIVE_PRODUCT".equals(d.getTargetProductRefType()))
                            .map(d -> List.of(new Key(read.factoryId(), d.getTargetProductRefId())))
                            .orElse(List.of());
                };
                boolean anyRestricted = false;
                for (Key key : keys) {
                    if (isProtected(key) && level(key) != Level.FULL) {
                        restrictedReads.add(key);
                        anyRestricted = true;
                    }
                }
                // 计划牵涉多个产品 (多产出 / 半成品 / 副产): 受限计划里的「不受保护的兄弟产品」也不能当归属,
                // 同一道工序的配比快照会挂在每一个产出行上 (独立审查第二轮 #2)
                if (anyRestricted && keys.size() > 1) planSiblings.addAll(keys);
            }
        }

        /** 领料明细在 PostLoad 时主单还是代理, 只知道主单 ID: 这里补上工厂 (查不到就丢弃, 不认他厂的单)。 */
        ProcessSecretReadTracker.Read withFactory(ProcessSecretReadTracker.Read read) {
            if (read.factoryId() != null) return read;
            if (read.kind() != ProcessSecretReadTracker.Kind.MATERIAL_REQUISITION) return null;
            return requisitionRepository.findById(read.id())
                    .map(r -> new ProcessSecretReadTracker.Read(read.kind(), r.getFactoryId(), read.id()))
                    .orElse(null);
        }

        /** 计划牵涉的全部产品; 计划查不到 (已删) 时当作「无法确认所属产品」。 */
        List<Key> planKeys(String factoryId, String planId) {
            // 同一次输出里多个批次 / 报工行指向同一计划时只查一次 (独立审查第二轮 #10)
            return planCache.computeIfAbsent(factoryId + "|" + planId, k -> {
                Set<String> products = planRepository.findByIdAndFactoryId(planId, factoryId)
                        .map(plan -> ProcessSecretPlanProducts.of(plan, recipeId -> recipeKey(factoryId, recipeId)
                                .map(Key::productTypeId)))
                        .orElse(Set.of());
                if (products.isEmpty()) return List.of(new Key(factoryId, null));
                return products.stream().map(p -> new Key(factoryId, p)).toList();
            });
        }

        Optional<Key> recipeKey(String factoryId, String recipeId) {
            if (factoryId == null || recipeId == null) return Optional.empty();
            return recipeKeys.computeIfAbsent(factoryId + "|" + recipeId, k -> recipeRepository.findById(recipeId)
                    .filter(r -> factoryId.equals(r.getFactoryId()))
                    .map(BomRecipe::getProductTypeId)
                    .map(p -> new Key(factoryId, p)));
        }

        Optional<Key> instanceKey(String factoryId, String instanceId) {
            try {
                return instanceRepository.findById(Long.valueOf(instanceId))
                        .filter(i -> factoryId.equals(i.getFactoryId()))
                        .map(ProductionWorkflowInstance::getProductTypeId)
                        .map(p -> new Key(factoryId, p));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        /** 读数带了「这一行自己的产品」(半成品 / 副产 / 锚点) 时, 它也算这张计划的成员 (独立审查第三轮 C)。 */
        List<Key> withMember(ProcessSecretReadTracker.Read read, List<Key> keys) {
            String member = read.member();
            if (member == null) return keys;
            // 空成员 = 无法确认 (例如工艺图解析不了): 当作未知产品, 按受保护处理
            Key key = new Key(read.factoryId(), member.isBlank() ? null : member);
            if (keys.contains(key)) return keys;
            List<Key> all = new ArrayList<>(keys);
            all.add(key);
            return all;
        }

        /** 报工行 / 消耗记录的批次: 批次的产品 + 它所属计划牵涉的全部产品; 批次查不到时当作未知产品。 */
        List<Key> batchKeys(String factoryId, String batchId) {
            final Long id;
            try {
                id = Long.valueOf(batchId);
            } catch (NumberFormatException e) {
                return List.of();
            }
            return batchRepository.findByIdAndFactoryId(id, factoryId).map(batch -> {
                Set<Key> keys = new LinkedHashSet<>();
                if (batch.getProductTypeId() != null && !batch.getProductTypeId().isBlank()) {
                    keys.add(new Key(factoryId, batch.getProductTypeId()));
                }
                if (batch.getProductionPlanId() != null && !batch.getProductionPlanId().isBlank()) {
                    keys.addAll(planKeys(factoryId, batch.getProductionPlanId()));
                }
                return List.copyOf(keys);
                // 查不到 (已软删 / 他厂) 的批次: 当作「无法确认所属产品」按受保护处理, 与计划查不到同一口径
                // (独立审查 V2.1 复核 M4: 只有批次的 BOM 自动扣料记录原来会静默放行)
            }).orElse(List.of(new Key(factoryId, null)));
        }

        /** 对象自身的归属 (没有则 null, 由上层继承)。 */
        Key ownKey(ObjectNode node, Key inherited) {
            Key declared = declaredKey(node, inherited);
            return declared != null ? declared : productIdKey(node, factoryOf(node, inherited));
        }

        String factoryOf(ObjectNode node, Key inherited) {
            String factory = text(node, "factoryId");
            if (factory == null) factory = inherited != null ? inherited.factoryId() : requestFactory;
            return factory;
        }

        /** productTypeId / 配方给出的归属。 */
        Key declaredKey(ObjectNode node, Key inherited) {
            String factory = factoryOf(node, inherited);
            for (String field : PRODUCT_KEYS) {
                String product = text(node, field);
                if (product != null) return new Key(factory, product);
            }
            for (String field : RECIPE_KEYS) {
                String recipeId = text(node, field);
                if (recipeId == null) continue;
                Optional<Key> viaRecipe = recipeKey(factory, recipeId);
                if (viaRecipe.isPresent()) return viaRecipe.get();
            }
            return null;
        }

        /**
         * 销售行 / 报表行用 productId 指产品: 它在别处也指商城商品等, 所以只在命中「对当前调用者受限」的
         * 本厂受保护产品时才认作归属, 且不算进 {@link #attributed} —— 只会让对象更严, 不会让原本按受限处理的
         * 对象因为归到一个有权的产品而放出, 也不会关掉「归属不可信」(独立审查 PR3 M2)。
         */
        Key productIdKey(ObjectNode node, String factory) {
            String productId = text(node, "productId");
            if (productId == null || !protectedIn(factory).contains(productId)) return null;
            Key key = new Key(factory, productId);
            return level(key) == Level.FULL ? null : key;
        }

        /** @return 这一层 (含下层) 里对当前调用者受限的受保护产品; 没有为 null */
        Key collectAttributions(JsonNode node, Key inherited) {
            if (node instanceof ObjectNode object) {
                Key own = ownKey(object, inherited);
                Key effective = own != null ? own : inherited;
                if (declaredKey(object, inherited) != null) attributed.add(own);
                Key below = own != null && own.productTypeId() != null && isProtected(own) && level(own) != Level.FULL
                        ? own : null;
                List<JsonNode> children = new ArrayList<>();
                object.elements().forEachRemaining(children::add);
                for (JsonNode child : children) {
                    Key found = collectAttributions(child, effective);
                    if (below == null) below = found;
                }
                if (below != null) restrictedBelow.put(object, below);
                return below;
            } else if (node instanceof ArrayNode array) {
                Key below = null;
                for (JsonNode child : array) {
                    Key found = collectAttributions(child, inherited);
                    if (below == null) below = found;
                }
                return below;
            }
            return null;
        }

        /**
         * 下层有受限的受保护产品行的对象 (订单下的销售行、报表里的产品行): 它的成本合计减去可见的其他行,
         * 就是保密产品的成本 (Steve 2026-10-28 选 A)。按身份记, 不改 JSON。
         */
        final Map<ObjectNode, Key> restrictedBelow = new java.util.IdentityHashMap<>();
        /** 输出里任意位置出现的受限保密产品 (同级的「合计」对象没有自己的归属时用它; 独立审查 PR3 M1)。 */
        Key restrictedInOutput;

        void decideFallback() {
            if (restrictedReads.isEmpty()) return;
            fallback = restrictedReads.stream().map(this::level)
                    .anyMatch(l -> l == Level.NONE) ? Level.NONE : Level.TOTALS_ONLY;
            for (Key read : restrictedReads) {
                if (read.productTypeId() == null || !attributed.contains(read)) {
                    distrustAttribution = true;
                    break;
                }
            }
        }

        void walk(JsonNode node, Key inherited) {
            walk(node, inherited, false);
        }

        /** {@code derived}: 上层是按配比自动算出的对象, 下层的数量 (例如分到哪些批次) 加起来就是它。 */
        void walk(JsonNode node, Key inherited, boolean derived) {
            if (node instanceof ObjectNode object) {
                // ⛔ 不因为对象里已有 processSecret 键就跳过: 自定义字段里也能出现同名键 (独立审查 #11);
                // V1 摘要本身不含配比字段, 走一遍也不会改它
                Key own = ownKey(object, inherited);
                Key effective = own != null ? own : inherited;
                scrubObject(object, effective, own != null, derived);
                boolean childrenDerived = derived || isRecipeDerived(object) || isReworkDeduction(object);
                List<JsonNode> children = new ArrayList<>();
                object.elements().forEachRemaining(children::add);
                for (JsonNode child : children) walk(child, effective, childrenDerived);
            } else if (node instanceof ArrayNode array) {
                boolean automaticSibling = false;
                for (JsonNode child : array) {
                    if (child instanceof ObjectNode o && (isRecipeDerived(o) || isReworkDeduction(o))) {
                        automaticSibling = true;
                        break;
                    }
                }
                for (JsonNode child : array) {
                    if (automaticSibling && child instanceof ObjectNode o) sharesWithAutomaticSiblings.add(o);
                    walk(child, inherited, derived);
                }
            }
        }

        /**
         * 同一列表里有按配比算出的行时, 其余行的占比 (人工行 q 与占比 s 可见 → 自动行 = q × (100 − s) ÷ s)
         * 同样是配比算出来的 (独立审查 V2.1 复核 H3)。按对象身份记, 不改 JSON。
         */
        final Set<ObjectNode> sharesWithAutomaticSiblings =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        static final Set<String> SHARE_FIELDS = Set.of("weightSharePct", "costSharePct", "weightShare", "costShare");

        /**
         * 按配比自动带出的行 (调料 / 包材自动分摊, automatic=true): 它的数量 = 配比 × 投入量,
         * 「本单总量」在这里就等于配比本身 (F006 验收 2026-10-27: 0.1725 kg ÷ 115 kg = 1.5 g/kg)。
         */
        boolean isRecipeDerived(ObjectNode object) {
            // 回料自动扣减 (automaticRework): 投料量 = 回料配比 × 主料投入, 落库的 row_payload 里只剩这个标志
            // (独立审查 V2.1 M4)。落库的消耗记录由出口按 source_type 标 automatic (ProcessSecretRecipeDerived)。
            return object.path("automatic").asBoolean(false) || object.path("automaticRework").asBoolean(false);
        }

        /**
         * 报工行操作记录的「字段: 旧→新」摘要是把快照直接转成文字, 自动带出行的数量也在里面
         * (形如 {@code quantity=0.1725, ..., automatic=true})。文字裁不掉单个数, 整段按配比类处理。
         */
        boolean isDerivedText(ObjectNode object, String name) {
            if (!"diffSummary".equals(name) || !object.path(name).isTextual()) return false;
            String text = object.path(name).asText();
            if (text.contains("automatic=true") || text.contains("automaticRework=true")) return true;
            for (String field : RATIO_FIELDS) {
                if (text.contains(field + "=") || text.contains(field + ":")) return true;
            }
            return false;
        }

        /**
         * 回料自动扣减: 目标量 = 回料配比 × 主料投入, 且 手工 + 自动 + 仍缺 = 目标 ——
         * 配比字段藏了, 这几个量一除照样得出配比 (独立审查 V2.1 H2)。主料投入与手工投入是实际值, 照给。
         */
        boolean isReworkDeduction(ObjectNode object) {
            return object.has("inputRatioPercent") && object.has("targetKg");
        }

        static final Set<String> REWORK_DERIVED_FIELDS = Set.of("targetKg", "drawnKg", "shortfallKg");

        /**
         * 按配比算出的行上同时带着的实际值 (估算辅料行的主料投入 kg、原料批次的初始 / 剩余库存): 照「本单总量」档给;
         * 配比藏了以后, 用量也藏了, 只剩它推不出配比。
         */
        static final Set<String> DERIVED_LINE_ACTUAL_FIELDS = Set.of("rawInputKg", "initialQuantity", "remainingQuantity");

        void scrubObject(ObjectNode object, Key key, boolean ownKeyed, boolean derived) {
            List<String> ratioFields = new ArrayList<>();
            List<String> quantityFields = new ArrayList<>();
            // 无权档 (平台 / 他厂) 连总量也不给: 词表列不全 (独立审查 #6), 所以除了标识类数字一律去掉
            List<String> otherNumbers = new ArrayList<>();
            List<String> costFields = new ArrayList<>();
            // 按配比算出来的对象 (及其下层): 非标识数字一律按配比处理; 人工录入的实际投料 (automatic=false) 照常给
            boolean derivedFromRecipe = derived || isRecipeDerived(object);
            boolean reworkDeduction = isReworkDeduction(object);
            for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) {
                String name = it.next();
                JsonNode value = object.get(name);
                if (value == null || value.isNull() || MARKER.equals(name)) continue;
                if (isRatioField(name) || isContextualRatioField(object, name) || isDerivedText(object, name)) {
                    ratioFields.add(name);
                }
                else if (derivedFromRecipe && value.isNumber() && !isIdentifierLike(name)
                        && !DERIVED_LINE_ACTUAL_FIELDS.contains(name)) ratioFields.add(name);
                else if (reworkDeduction && REWORK_DERIVED_FIELDS.contains(name)) ratioFields.add(name);
                else if (SHARE_FIELDS.contains(name) && sharesWithAutomaticSiblings.contains(object)) ratioFields.add(name);
                // 成本合计 (Steve 2026-10-28 选 A): 调料 / 包材 / 原料小计、总成本、单位成本里含按配比算出的金额,
                // 减去可见的人工行就是自动行金额, 再 ÷ 单价 ÷ 投入就是配比; 落库的批次 / 半成品 / 成品单位成本
                // 也是这样滚出来的。受保护产品的这类字段对无授权账号一律隐藏, 不看本次输出有没有带出自动行
                else if (value.isNumber() && (isCostTotalField(name) || isContextualCostField(object, name))) {
                    ratioFields.add(name);
                    costFields.add(name);
                }
                // 操作记录摘要是文字: 本单总量也写在里面, 无权档整段不给
                else if ("diffSummary".equals(name) && value.isTextual()) quantityFields.add(name);
                else if (SINGLE_RUN_ACTUAL_FIELDS.contains(name)) {
                    // 单批实测: 单据视图里与总量同档; 汇总视图里与配比同档
                    (aggregateView ? ratioFields : quantityFields).add(name);
                } else if (QUANTITY_FIELDS.contains(name)) quantityFields.add(name);
                else if (value.isNumber() && !isIdentifierLike(name)) otherNumbers.add(name);
            }
            if (ratioFields.isEmpty() && quantityFields.isEmpty() && otherNumbers.isEmpty()) return;

            Level level;
            Key auditKey;
            if (key != null && !distrustAttribution && planSiblings.contains(key)
                    && (!isProtected(key) || level(key) == Level.FULL)) {
                // 受限计划里的兄弟产出 (含调用者有权的主产品: 合并的计划成本里有它看不到的兄弟产出的配方金额;
                // 独立审查 PR3 复核 M-c): 按受限处理, 审计记到被读的受限产品上
                level = fallback;
                auditKey = null;
            } else if (key != null && !distrustAttribution) {
                if (!isProtected(key)) {
                    hideCostsAboveRestricted(object, ownKeyed, costFields, quantityFields, otherNumbers);
                    return;
                }
                level = level(key);
                auditKey = key;
                if (level == Level.FULL) {
                    // 只有成本合计时不写 ALLOW: 那不是工艺内容, 负责人每看一次成本就记一条会淹没审计 (PR3 L2)
                    if (ratioFields.size() > costFields.size() && !allowedWithContent.contains(key)) {
                        if (accessService.viewFullAllowedInRequest(key.factoryId(), key.productTypeId())
                                || accessService.audit(key.factoryId(), key.productTypeId(), actor,
                                ProcessSecretAction.VIEW_FULL, "ALLOW", "完整工艺随业务数据输出", resourceRef)) {
                            allowedWithContent.add(key);
                        } else {
                            // 审计写不进去: 不放出完整工艺
                            level = degradedLevel(actor, key.factoryId());
                            levels.put(key, level);
                        }
                    }
                    if (level == Level.FULL) {
                        hideCostsAboveRestricted(object, ownKeyed, costFields, quantityFields, otherNumbers);
                        return;
                    }
                }
            } else {
                if (fallback == null) {
                    hideCostsAboveRestricted(object, ownKeyed, costFields, quantityFields, otherNumbers);
                    return;
                }
                level = fallback;
                auditKey = null;
            }

            List<String> hidden = new ArrayList<>(ratioFields);
            // 汇总视图里跨批的投入 / 产出总量一除就是汇总出成率 (Steve 第三问 A: 汇总不给; 第二轮 #4)
            if (level == Level.NONE || aggregateView) {
                hidden.addAll(quantityFields);
                hidden.addAll(otherNumbers);
            }
            hide(object, hidden, level, auditKey);
        }

        /**
         * 自身不受限、但下层有受限保密产品行的对象 (或没有任何归属、而输出里别处有受限保密产品行的同级「合计」):
         * 隐藏它的成本合计, 审计记到那个产品上。那个产品对调用者是无权档 (平台 / 他厂) 或这是汇总视图时,
         * 上层的总量也一并隐藏 —— 上层总量减去可见的其他行就是被隐藏的那一行 (独立审查 PR3 M1 / M3)。
         */
        void hideCostsAboveRestricted(ObjectNode object, boolean ownKeyed, List<String> costFields,
                                      List<String> quantityFields, List<String> otherNumbers) {
            Key below = restrictedBelow.get(object);
            // 「自己没有产品」看对象本身, 不看从上层继承的: 挂在不受保护产品下面的合计对象照样是合计
            // (独立审查 PR3 复核 M-a)。用 productId 指了别的 (不受保护 / 有权) 产品的行不是「合计」, 不连带
            if (below == null && !ownKeyed && text(object, "productId") == null) below = restrictedInOutput;
            if (below == null) return;
            Level level = level(below);
            List<String> hidden = new ArrayList<>(costFields);
            if (level == Level.NONE || aggregateView) {
                hidden.addAll(quantityFields);
                hidden.addAll(otherNumbers);
            }
            hide(object, hidden, level, below);
        }

        void hide(ObjectNode object, List<String> hidden, Level level, Key auditKey) {
            if (hidden.isEmpty() || level == Level.FULL) return;
            for (String name : hidden) object.putNull(name);
            // 已有标记 (V1 摘要) 时并入, 不覆盖它的 RESTRICTED
            ObjectNode marker = object.get(MARKER) instanceof ObjectNode existing ? existing : object.putObject(MARKER);
            if (!"RESTRICTED".equals(marker.path("visibility").asText())) {
                marker.put("visibility", level == Level.NONE ? "RESTRICTED" : "TOTALS_ONLY");
                marker.put("message", level == Level.NONE ? RESTRICTED_MESSAGE : TOTALS_ONLY_MESSAGE);
            }
            ArrayNode names = marker.get("hiddenFields") instanceof ArrayNode existingNames
                    ? existingNames : marker.putArray("hiddenFields");
            hidden.forEach(names::add);
            changed = true;
            if (auditKey != null) {
                hiddenByKey.computeIfAbsent(auditKey, k -> new LinkedHashSet<>()).addAll(hidden);
            } else {
                for (Key read : restrictedReads) {
                    hiddenByKey.computeIfAbsent(read, k -> new LinkedHashSet<>()).addAll(hidden);
                }
            }
        }

        void flushAudits() {
            hiddenByKey.forEach((key, hidden) -> accessService.audit(key.factoryId(), key.productTypeId(), actor,
                    ProcessSecretAction.VIEW_FULL, "DENY",
                    (level(key) == Level.NONE ? "无权查看，已隐藏" : "降级为物料与本单总量，已隐藏")
                            + String.join(",", hidden),
                    resourceRef));
        }
    }

    static boolean isRatioField(String name) {
        if (RATIO_FIELDS.contains(name) || AGGREGATE_ACTUAL_FIELDS.contains(name)) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : RATIO_SUFFIXES) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }

    /** 标识 / 排序 / 计数类数字 (无权档也保留, 页面才能定位行)。 */
    static boolean isIdentifierLike(String name) {
        return PAGING_FIELDS.contains(name)
                || name.equals("id") || name.endsWith("Id") || name.endsWith("Ids") || name.equals("version")
                || name.endsWith("Version") || name.endsWith("Order") || name.equals("seq") || name.endsWith("Seq")
                || name.endsWith("Count") || name.endsWith("No") || name.endsWith("Number");
    }

    /**
     * 成本合计类字段: 名字含 cost / profit / margin / achievement / variance (总成本、单位成本、毛利、毛利率,
     * 以及「实际成本 ÷ 计划成本」的达成率和差异 —— 计划侧可见时它们一样能反推自动行金额)。
     * 销售额、单价不算 (与配比无关); 计数 / 标识类数字不算。
     */
    static final List<String> COST_TOTAL_PARTS = List.of("cost", "profit", "margin", "achievement", "variance",
            // BOM 成本明细的 subtotal = 用量 × 单价; 多段成本的 stageSubtotal / contributionPerBox (独立审查 PR3 H3)
            "subtotal", "contribution");

    /** 半成品 / 回料的单价就是滚上来的单位成本 (落库的 WIP unit_cost), 不是采购价 (独立审查 PR3 H2)。 */
    static final Set<String> WIP_COST_MARKERS = Set.of("rowTotalCost", "inheritedCost", "addedCost");
    static final Set<String> WIP_SOURCE_TYPES = Set.of("REWORK", "REWORK_AUTO");

    static boolean isContextualCostField(ObjectNode object, String name) {
        if (!"unitPrice".equals(name)) return false;
        return WIP_COST_MARKERS.stream().anyMatch(object::has)
                || WIP_SOURCE_TYPES.contains(object.path("sourceType").asText(""));
    }

    /** 名字不带上面片段的金额: 共锅调料成本分到本批的那一份 (元; 独立审查 PR3 复核 M-b)。 */
    static final Set<String> EXACT_COST_FIELDS = Set.of("batchShare");

    /**
     * 名字带 cost / variance 等片段、实际是数量或时长的字段: 成本分母产量、入库差异量、盘盈数量、换线分钟数。
     * 产量不算保密 (Steve 2026-10-28), 按「本单总量」档处理; 元/kg、元/件这类单位金额不在此列。
     */
    static final List<String> NON_MONEY_SUFFIXES = List.of("quantity", "qty", "minutes");

    public static boolean isCostTotalField(String name) {
        if (EXACT_COST_FIELDS.contains(name)) return true;
        if (isIdentifierLike(name)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        // 「每数量 / 每分钟多少钱」(costPerQuantity、unitCostQty) 仍是金额, 不豁免
        boolean moneyPerUnit = lower.contains("per") || lower.contains("price") || lower.contains("amount")
                || lower.contains("unit");
        if (!moneyPerUnit && NON_MONEY_SUFFIXES.stream().anyMatch(lower::endsWith)) return false;
        return COST_TOTAL_PARTS.stream().anyMatch(lower::contains);
    }

    static boolean isContextualRatioField(ObjectNode object, String name) {
        Set<String> markers = CONTEXTUAL_RATIO_FIELDS.get(name);
        return markers != null && markers.stream().anyMatch(object::has);
    }

    private static String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) return null;
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
