package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
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
    public static final String TOTALS_ONLY_MESSAGE = "该产品工艺已设为保密：可以看物料和本单总量，配比、单位用量和标准出成率已隐藏";
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
            // 换料影响: 配方成本差额 ÷ 单价差 = 该料的单位用量
            "oldRecipeMaterialCost", "newRecipeMaterialCost", "costDelta");

    /**
     * 实测出成 / 损耗 / 超投 / 实际配比份额。跨批汇总会逼近标准出成率和配比, 单批又能由「本单投入 ÷ 产出」算出。
     * Steve 2026-10-26 第三问要求「分权限显示」, 具体分档待定 ⇒ 先 fail-closed: 与配比类同档隐藏, 只留投入产出总量。
     * 分档定了以后只改这一组 (以及 ProcessSecretDataLayerContractTest 的归类)。
     */
    public static final Set<String> ACTUAL_YIELD_FIELDS = Set.of(
            "yieldRate", "actualYieldRate", "stepYieldRate", "cumulativeYieldRate", "realYieldRate",
            "todayYieldRate", "weightedYieldRate", "asOfYieldRate", "netYieldRate", "goodOutputRate",
            "sourceConsumedRatio", "weightSharePct", "inputRatioPercent", "lossRate", "avgLossRate",
            "lossPercentage", "scrapRate", "scrapLossRate", "overFeedRate", "actualSharePercent");

    /** 名字含这些片段的字段一并按配比类处理 (统计里的平均转换率、损耗率等)。 */
    static final List<String> RATIO_SUFFIXES = List.of("conversionrate", "conversionratio", "wastagerate");

    /**
     * 名字本身有歧义的字段: 只有同一对象里出现了标志字段时才按配比类处理。
     * {@code rate} / {@code wastage} / {@code avgChange} 在转换率变更趋势里就是转换率和损耗率。
     */
    static final Map<String, Set<String>> CONTEXTUAL_RATIO_FIELDS = Map.of(
            "rate", Set.of("changeType"),
            "wastage", Set.of("changeType"),
            "avgChange", Set.of("currentRate"));

    /** 「本单总量」档保留、无权档 (平台 / 他厂) 去掉的总量字段。 */
    public static final Set<String> QUANTITY_FIELDS = Set.of(
            "requiredQuantity", "requiredQty", "plannedQty", "plannedQuantityKg", "rawInputKg", "quantity",
            "shortfallQuantity", "standardKg", "actualQuantity", "totalRequired");

    /** 对象归属的产品字段 (按顺序取第一个); 多产出计划的锚点 {@code __...} 不算归属。 */
    static final List<String> PRODUCT_KEYS = List.of("productTypeId", "sourceProductTypeId");
    static final List<String> RECIPE_KEYS = List.of("recipeId", "bomRecipeId", "sourceRecipeId");

    private final ProcessSecretAccessService accessService;
    private final BomRecipeRepository recipeRepository;
    private final ProductionWorkflowInstanceRepository instanceRepository;
    private final ProductionPlanRepository planRepository;
    private final FactoryMaterialRequisitionRepository requisitionRepository;
    private final BomRecipeDraftRepository draftRepository;

    public ProcessSecretPayloadScrubber(ProcessSecretAccessService accessService,
                                        BomRecipeRepository recipeRepository,
                                        ProductionWorkflowInstanceRepository instanceRepository,
                                        ProductionPlanRepository planRepository,
                                        FactoryMaterialRequisitionRepository requisitionRepository,
                                        BomRecipeDraftRepository draftRepository) {
        this.accessService = accessService;
        this.recipeRepository = recipeRepository;
        this.instanceRepository = instanceRepository;
        this.planRepository = planRepository;
        this.requisitionRepository = requisitionRepository;
        this.draftRepository = draftRepository;
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
     * @return true 表示有字段被去掉 (调用方要改用裁剪后的树)
     */
    public boolean scrub(JsonNode tree, AuthenticatedActor actor, String requestFactory,
                         Set<ProcessSecretReadTracker.Read> reads, String resourceRef) {
        if (tree == null || !(tree.isObject() || tree.isArray())) return false;
        Run run = new Run(actor, requestFactory, resourceRef);
        if (!run.anyProtection(reads)) return false;
        run.resolveReads(reads);
        // 第一遍: 输出里出现了哪些产品归属
        run.collectAttributions(tree, null);
        run.decideFallback();
        // 第二遍: 裁剪
        run.walk(tree, null);
        run.flushAudits();
        return run.changed;
    }

    // ------------------------------------------------------------------

    private record Key(String factoryId, String productTypeId) { }

    private final class Run {
        final AuthenticatedActor actor;
        final String requestFactory;
        final String resourceRef;
        final Map<String, Set<String>> protectedByFactory = new HashMap<>();
        final Map<Key, Level> levels = new HashMap<>();
        final Map<String, Optional<Key>> recipeKeys = new HashMap<>();
        /** 本次读过、且对当前调用者受限的产品 (含解析不出产品的读取, productTypeId 为 null)。 */
        final Set<Key> restrictedReads = new LinkedHashSet<>();
        final Set<Key> attributed = new LinkedHashSet<>();
        /** 归属不清时用的档位; null 表示归属不清的对象不用裁剪。 */
        Level fallback;
        /** 读过的受限产品没在输出里出现归属 → 整份按受限处理。 */
        boolean distrustAttribution;
        final Map<Key, Set<String>> hiddenByKey = new LinkedHashMap<>();
        final Set<Key> allowedWithContent = new LinkedHashSet<>();
        boolean changed;

        Run(AuthenticatedActor actor, String requestFactory, String resourceRef) {
            this.actor = actor;
            this.requestFactory = requestFactory;
            this.resourceRef = resourceRef;
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
                    case PRODUCTION_PLAN -> planKeys(read.factoryId(), read.id());
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
                for (Key key : keys) {
                    if (isProtected(key) && level(key) != Level.FULL) restrictedReads.add(key);
                }
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
            Set<String> products = planRepository.findByIdAndFactoryId(planId, factoryId)
                    .map(plan -> ProcessSecretPlanProducts.of(plan, recipeId -> recipeKey(factoryId, recipeId)
                            .map(Key::productTypeId)))
                    .orElse(Set.of());
            if (products.isEmpty()) return List.of(new Key(factoryId, null));
            return products.stream().map(p -> new Key(factoryId, p)).toList();
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

        /** 对象自身的归属 (没有则 null, 由上层继承)。 */
        Key ownKey(ObjectNode node, Key inherited) {
            String factory = text(node, "factoryId");
            if (factory == null) factory = inherited != null ? inherited.factoryId() : requestFactory;
            for (String field : PRODUCT_KEYS) {
                String product = text(node, field);
                if (product != null && !product.startsWith("__")) return new Key(factory, product);
            }
            for (String field : RECIPE_KEYS) {
                String recipeId = text(node, field);
                if (recipeId == null) continue;
                Optional<Key> viaRecipe = recipeKey(factory, recipeId);
                if (viaRecipe.isPresent()) return viaRecipe.get();
            }
            return null;
        }

        void collectAttributions(JsonNode node, Key inherited) {
            if (node instanceof ObjectNode object) {
                Key own = ownKey(object, inherited);
                Key effective = own != null ? own : inherited;
                if (own != null) attributed.add(own);
                object.fields().forEachRemaining(e -> collectAttributions(e.getValue(), effective));
            } else if (node instanceof ArrayNode array) {
                array.forEach(child -> collectAttributions(child, inherited));
            }
        }

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
            if (node instanceof ObjectNode object) {
                if (object.has(MARKER)) return;   // V1 已换成摘要
                Key own = ownKey(object, inherited);
                Key effective = own != null ? own : inherited;
                scrubObject(object, effective);
                List<JsonNode> children = new ArrayList<>();
                object.elements().forEachRemaining(children::add);
                for (JsonNode child : children) walk(child, effective);
            } else if (node instanceof ArrayNode array) {
                array.forEach(child -> walk(child, inherited));
            }
        }

        void scrubObject(ObjectNode object, Key key) {
            List<String> ratioFields = new ArrayList<>();
            List<String> quantityFields = new ArrayList<>();
            for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) {
                String name = it.next();
                if (object.get(name) == null || object.get(name).isNull()) continue;
                if (isRatioField(name) || isContextualRatioField(object, name)) ratioFields.add(name);
                else if (QUANTITY_FIELDS.contains(name)) quantityFields.add(name);
            }
            if (ratioFields.isEmpty() && quantityFields.isEmpty()) return;

            Level level;
            Key auditKey;
            if (key != null && !distrustAttribution) {
                if (!isProtected(key)) return;
                level = level(key);
                auditKey = key;
                if (level == Level.FULL) {
                    if (!ratioFields.isEmpty() && !allowedWithContent.contains(key)) {
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
                    if (level == Level.FULL) return;
                }
            } else {
                if (fallback == null) return;
                level = fallback;
                auditKey = null;
            }

            List<String> hidden = new ArrayList<>(ratioFields);
            if (level == Level.NONE) hidden.addAll(quantityFields);
            if (hidden.isEmpty()) return;
            for (String name : hidden) object.putNull(name);
            ObjectNode marker = object.putObject(MARKER);
            marker.put("visibility", level == Level.NONE ? "RESTRICTED" : "TOTALS_ONLY");
            marker.put("message", level == Level.NONE ? RESTRICTED_MESSAGE : TOTALS_ONLY_MESSAGE);
            ArrayNode names = marker.putArray("hiddenFields");
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
        if (RATIO_FIELDS.contains(name) || ACTUAL_YIELD_FIELDS.contains(name)) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : RATIO_SUFFIXES) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
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
