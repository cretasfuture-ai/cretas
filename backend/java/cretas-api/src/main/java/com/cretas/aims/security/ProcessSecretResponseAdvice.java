package com.cretas.aims.security;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.ResolvableSerializer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 工艺保密的响应裁剪: 不管哪个接口、嵌套在哪一层 (分页 / 列表 / Map / DTO 字段), 只要响应里出现
 * 受保护产品的 BOM 配方、配方明细、调味明细、工艺 Workflow (实体或 DTO), 而调用者没有完整查看权,
 * 就把那个对象换成<b>摘要</b>, 并带 {@code processSecret.visibility = "RESTRICTED"}
 * —— 明确的「无权查看」, 不是 null, 前端不能把它当成「没填」或 0。
 *
 * <p>只在<b>本次 HTTP 响应</b>的序列化里生效 (专用 ObjectMapper + ThreadLocal 作用域),
 * 不影响业务代码里其它地方对这些对象的 JSON 序列化 (快照、缓存、Python 调用), 也不改实体本身。</p>
 *
 * <p>必须排在 {@link PriceFieldResponseAdvice} 之后: 价格先隐藏, 再转成 JSON 树。
 * 本厂没有任何保密策略时直接放行, 不做额外序列化。</p>
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProcessSecretResponseAdvice implements ResponseBodyAdvice<Object> {

    public static final String RESTRICTED_MESSAGE = "该产品工艺已设为保密，需要保密负责人授权才能查看完整内容";

    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

    private final ProcessSecretAccessService accessService;
    private final BomRecipeRepository recipeRepository;
    private final ObjectMapper filteringMapper;

    private final com.cretas.aims.repository.ProductionPlanRepository planRepository;

    public ProcessSecretResponseAdvice(ProcessSecretAccessService accessService,
                                       BomRecipeRepository recipeRepository,
                                       com.cretas.aims.repository.ProductionPlanRepository planRepository,
                                       ObjectMapper objectMapper) {
        this.accessService = accessService;
        this.recipeRepository = recipeRepository;
        this.planRepository = planRepository;
        ObjectMapper copy = objectMapper.copy();
        copy.setSerializerFactory(copy.getSerializerFactory().withSerializerModifier(new Modifier()));
        this.filteringMapper = copy;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || body instanceof String || body instanceof byte[] || body instanceof Resource
                || body instanceof JsonNode) {
            return body;
        }
        if (selectedContentType == null || !selectedContentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            return body;
        }
        if (!(request instanceof ServletServerHttpRequest servletRequest)) return body;
        HttpServletRequest http = servletRequest.getServletRequest();
        AuthenticatedActor actor = AuthenticatedActor.current(http).orElse(null);
        if (actor == null) return body;
        // 快路径要看「这次访问的是哪个工厂」: 路径上的 factoryId 优先, 身份里的工厂也算上
        // (审查 M2: factoryId=PLATFORM 一类跨厂令牌若只看身份会直接跳过裁剪)
        String pathFactory = pathFactoryId(http);
        boolean anyProtected = (pathFactory != null && !accessService.protectedProductTypeIds(pathFactory).isEmpty())
                || (actor.factoryId() != null && !accessService.protectedProductTypeIds(actor.factoryId()).isEmpty());
        if (!anyProtected) {
            return body;
        }
        Object payload = body instanceof ApiResponse<?> api ? api.getData() : body;
        if (payload == null) return body;

        JsonNode filtered = filter(payload, actor, pathFactory != null ? pathFactory : actor.factoryId());
        if (filtered == null) return body;
        if (body instanceof ApiResponse<?> api) {
            ((ApiResponse<Object>) api).setData(filtered);
            return api;
        }
        return filtered;
    }

    /** @return 裁剪后的 JSON 树; 没有任何对象被裁剪时返回 null (调用方保留原响应)。 */
    JsonNode filter(Object payload, AuthenticatedActor actor, String requestFactory) {
        Scope scope = new Scope(actor, requestFactory);
        SCOPE.set(scope);
        try {
            JsonNode tree = filteringMapper.valueToTree(payload);
            return scope.redacted ? tree : null;
        } finally {
            SCOPE.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private static String pathFactoryId(HttpServletRequest http) {
        Object raw = http.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return raw instanceof Map<?, ?> map ? ((Map<String, String>) map).get("factoryId") : null;
    }

    // ------------------------------------------------------------------ scope

    private record ProductKey(String factoryId, String productTypeId) { }

    private final class Scope {
        final AuthenticatedActor actor;
        /** 本次请求访问的工厂: 不带 factoryId 的 DTO (如 BomItemUsage) 按它判定。 */
        final String requestFactory;
        final Map<String, Set<String>> protectedByFactory = new HashMap<>();
        final Map<ProductKey, ProcessSecretAccessService.Decision> decisions = new HashMap<>();
        final Map<String, Optional<String>> recipeProduct = new HashMap<>();
        boolean redacted;

        Scope(AuthenticatedActor actor, String requestFactory) {
            this.actor = actor;
            this.requestFactory = requestFactory;
        }

        String factoryOfUsage() {
            return requestFactory;
        }

        boolean factoryHasProtection(String factoryId) {
            return factoryId != null && !protectedByFactory.computeIfAbsent(factoryId,
                    accessService::protectedProductTypeIds).isEmpty();
        }

        final Map<String, java.util.Set<String>> planProducts = new HashMap<>();

        java.util.Set<String> productsOfPlan(String factoryId, String planId) {
            if (factoryId == null || planId == null) return java.util.Set.of();
            return planProducts.computeIfAbsent(factoryId + "|" + planId, k -> planRepository
                    .findByIdAndFactoryId(planId, factoryId)
                    .map(plan -> com.cretas.aims.service.processsecret.ProcessSecretPlanProducts.of(plan,
                            id -> recipeRepository.findById(id).map(BomRecipe::getProductTypeId)))
                    .orElse(java.util.Set.of()));
        }

        /** @return 需要裁剪时返回判定 (含档位), 否则 null。 */
        ProcessSecretAccessService.Decision restricted(String factoryId, String productTypeId) {
            if (factoryId == null || productTypeId == null) return null;
            Set<String> protectedIds = protectedByFactory.computeIfAbsent(factoryId,
                    accessService::protectedProductTypeIds);
            if (!protectedIds.contains(productTypeId)) return null;
            ProcessSecretAccessService.Decision decision = decisions.computeIfAbsent(
                    new ProductKey(factoryId, productTypeId),
                    key -> accessService.decide(actor, key.factoryId(), key.productTypeId(),
                            ProcessSecretAction.VIEW_FULL));
            return decision.permitted() ? null : decision;
        }

        String productOfRecipe(String recipeId) {
            if (recipeId == null) return null;
            return recipeProduct.computeIfAbsent(recipeId,
                    id -> recipeRepository.findById(id).map(BomRecipe::getProductTypeId)).orElse(null);
        }
    }

    // ------------------------------------------------------------- serializers

    /** 每种受管类型: 怎么取 (工厂, 产品), 以及摘要里保留哪些字段。 */
    /**
     * {@code keys}: 这个对象牵涉的 (工厂, 产品), 任一受保护且无权即裁剪。
     * {@code failClosed}: 解析不出产品 (父记录已软删等) 且该厂有保密策略时按受限处理 (复审 M-3);
     * 对本来就可能与工艺无关的对象 (如无计划的领料单) 不开。
     */
    private record Rule(BiFunction<Object, Scope, java.util.List<ProductKey>> keys, boolean failClosed,
                        SummaryWriter summary) {
        Rule(BiFunction<Object, Scope, ProductKey> key, SummaryWriter summary) {
            this((v, s) -> java.util.Collections.singletonList(key.apply(v, s)), true, summary);
        }
    }

    @FunctionalInterface
    private interface SummaryWriter {
        void write(Object value, JsonGenerator gen) throws IOException;
    }

    private static final Map<Class<?>, Rule> RULES = Map.ofEntries(
            Map.entry(com.cretas.aims.entity.bom.BomVersion.class, new Rule(
                    (v, s) -> new ProductKey(((com.cretas.aims.entity.bom.BomVersion) v).getFactoryId(),
                            s.productOfRecipe(((com.cretas.aims.entity.bom.BomVersion) v).getBomRecipeId())),
                    (v, g) -> {
                        com.cretas.aims.entity.bom.BomVersion bv = (com.cretas.aims.entity.bom.BomVersion) v;
                        g.writeStringField("id", bv.getId());
                        g.writeStringField("bomRecipeId", bv.getBomRecipeId());
                        g.writeStringField("status", bv.getStatus() == null ? null : bv.getStatus().name());
                    })),
            Map.entry(com.cretas.aims.entity.factory.FactoryMaterialRequisition.class, new Rule(
                    (v, s) -> {
                        com.cretas.aims.entity.factory.FactoryMaterialRequisition r =
                                (com.cretas.aims.entity.factory.FactoryMaterialRequisition) v;
                        return s.productsOfPlan(r.getFactoryId(), r.getProductionPlanId()).stream()
                                .map(p -> new ProductKey(r.getFactoryId(), p)).toList();
                    }, false,
                    (v, g) -> {
                        com.cretas.aims.entity.factory.FactoryMaterialRequisition r =
                                (com.cretas.aims.entity.factory.FactoryMaterialRequisition) v;
                        g.writeStringField("id", r.getId());
                        g.writeStringField("productionPlanId", r.getProductionPlanId());
                        g.writeStringField("productionPlanNumber", r.getProductionPlanNumber());
                    })),
            Map.entry(com.cretas.aims.dto.workflow.ProductionWorkflowRuntimeDTO.class, new Rule(
                    (v, s) -> new ProductKey(((com.cretas.aims.dto.workflow.ProductionWorkflowRuntimeDTO) v).getFactoryId(),
                            ((com.cretas.aims.dto.workflow.ProductionWorkflowRuntimeDTO) v).getProductTypeId()),
                    (v, g) -> {
                        com.cretas.aims.dto.workflow.ProductionWorkflowRuntimeDTO d =
                                (com.cretas.aims.dto.workflow.ProductionWorkflowRuntimeDTO) v;
                        if (d.getProductionBatchId() != null) g.writeNumberField("productionBatchId", d.getProductionBatchId());
                        g.writeStringField("productTypeId", d.getProductTypeId());
                    })),
            Map.entry(com.cretas.aims.dto.bom.BomItemUsage.class, new Rule(
                    (v, s) -> {
                        com.cretas.aims.dto.bom.BomItemUsage u = (com.cretas.aims.dto.bom.BomItemUsage) v;
                        return new ProductKey(s.factoryOfUsage(), u.getProductTypeId());
                    },
                    (v, g) -> {
                        com.cretas.aims.dto.bom.BomItemUsage u = (com.cretas.aims.dto.bom.BomItemUsage) v;
                        g.writeStringField("recipeId", u.getRecipeId());
                        g.writeStringField("productTypeId", u.getProductTypeId());
                        g.writeStringField("productName", u.getProductName());
                    })),
            Map.entry(
            BomRecipe.class, new Rule(
                    (v, s) -> {
                        BomRecipe r = (BomRecipe) v;
                        // 记下「配方 → 产品」: 同一响应里嵌套的明细 / 调味 / 版本直接用, 不再逐个查库
                        if (r.getId() != null) s.recipeProduct.putIfAbsent(r.getId(), Optional.ofNullable(r.getProductTypeId()));
                        return new ProductKey(r.getFactoryId(), r.getProductTypeId());
                    },
                    (v, g) -> {
                        BomRecipe r = (BomRecipe) v;
                        g.writeStringField("id", r.getId());
                        g.writeStringField("factoryId", r.getFactoryId());
                        g.writeStringField("recipeCode", r.getRecipeCode());
                        g.writeStringField("productTypeId", r.getProductTypeId());
                        g.writeStringField("productName", r.getProductName());
                        if (r.getVersion() != null) g.writeNumberField("version", r.getVersion());
                        if (r.getIsCurrent() != null) g.writeBooleanField("isCurrent", r.getIsCurrent());
                        g.writeStringField("status", r.getStatus() == null ? null : r.getStatus().name());
                    })),
            Map.entry(BomRecipeItem.class, new Rule(
                    (v, s) -> new ProductKey(((BomRecipeItem) v).getFactoryId(),
                            s.productOfRecipe(((BomRecipeItem) v).getRecipeId())),
                    (v, g) -> {
                        BomRecipeItem i = (BomRecipeItem) v;
                        if (i.getId() != null) g.writeNumberField("id", i.getId());
                        g.writeStringField("recipeId", i.getRecipeId());
                    })),
            Map.entry(BomSeasoningItem.class, new Rule(
                    (v, s) -> new ProductKey(((BomSeasoningItem) v).getFactoryId(),
                            s.productOfRecipe(((BomSeasoningItem) v).getRecipeId())),
                    (v, g) -> {
                        BomSeasoningItem i = (BomSeasoningItem) v;
                        if (i.getId() != null) g.writeNumberField("id", i.getId());
                        g.writeStringField("recipeId", i.getRecipeId());
                    })),
            Map.entry(ProductProcessWorkflow.class, new Rule(
                    (v, s) -> new ProductKey(((ProductProcessWorkflow) v).getFactoryId(),
                            ((ProductProcessWorkflow) v).getProductTypeId()),
                    (v, g) -> {
                        ProductProcessWorkflow w = (ProductProcessWorkflow) v;
                        if (w.getId() != null) g.writeNumberField("id", w.getId());
                        g.writeStringField("factoryId", w.getFactoryId());
                        g.writeStringField("productTypeId", w.getProductTypeId());
                        g.writeStringField("status", w.getStatus() == null ? null : w.getStatus().name());
                    })),
            Map.entry(ProductProcessWorkflowRevision.class, new Rule(
                    (v, s) -> new ProductKey(((ProductProcessWorkflowRevision) v).getFactoryId(),
                            ((ProductProcessWorkflowRevision) v).getProductTypeId()),
                    (v, g) -> {
                        ProductProcessWorkflowRevision w = (ProductProcessWorkflowRevision) v;
                        if (w.getId() != null) g.writeNumberField("id", w.getId());
                        g.writeStringField("factoryId", w.getFactoryId());
                        g.writeStringField("productTypeId", w.getProductTypeId());
                    })),
            Map.entry(ProductProcessWorkflowDTO.class, new Rule(
                    (v, s) -> new ProductKey(((ProductProcessWorkflowDTO) v).getFactoryId(),
                            ((ProductProcessWorkflowDTO) v).getProductTypeId()),
                    (v, g) -> {
                        ProductProcessWorkflowDTO d = (ProductProcessWorkflowDTO) v;
                        if (d.getId() != null) g.writeNumberField("id", d.getId());
                        g.writeStringField("factoryId", d.getFactoryId());
                        g.writeStringField("productTypeId", d.getProductTypeId());
                        g.writeStringField("status", d.getStatus());
                        if (d.getVersion() != null) g.writeNumberField("version", d.getVersion());
                    })));

    static Rule ruleFor(Class<?> type) {
        for (Map.Entry<Class<?>, Rule> e : RULES.entrySet()) {
            if (e.getKey().isAssignableFrom(type)) return e.getValue();
        }
        return null;
    }

    private static final class Modifier extends BeanSerializerModifier {
        @Override
        @SuppressWarnings("unchecked")
        public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc,
                                                  JsonSerializer<?> serializer) {
            Rule rule = ruleFor(beanDesc.getBeanClass());
            return rule == null ? serializer : new GuardedSerializer((JsonSerializer<Object>) serializer, rule);
        }
    }

    private static final class GuardedSerializer extends JsonSerializer<Object>
            implements ResolvableSerializer, ContextualSerializer {

        private final JsonSerializer<Object> delegate;
        private final Rule rule;

        GuardedSerializer(JsonSerializer<Object> delegate, Rule rule) {
            this.delegate = delegate;
            this.rule = rule;
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (!writeSummaryIfRestricted(value, gen)) delegate.serialize(value, gen, provider);
        }

        @Override
        public void serializeWithType(Object value, JsonGenerator gen, SerializerProvider provider,
                                      TypeSerializer typeSer) throws IOException {
            if (!writeSummaryIfRestricted(value, gen)) delegate.serializeWithType(value, gen, provider, typeSer);
        }

        private boolean writeSummaryIfRestricted(Object value, JsonGenerator gen) throws IOException {
            Scope scope = SCOPE.get();
            if (scope == null || value == null) return false;
            ProcessSecretAccessService.Decision decision = null;
            boolean unresolved = false;
            String anyFactory = null;
            for (ProductKey key : rule.keys().apply(value, scope)) {
                if (key == null) continue;
                anyFactory = anyFactory != null ? anyFactory : key.factoryId();
                if (key.factoryId() == null || key.productTypeId() == null) {
                    unresolved = true;
                    continue;
                }
                ProcessSecretAccessService.Decision d = scope.restricted(key.factoryId(), key.productTypeId());
                if (d != null) {
                    decision = d;
                    break;
                }
            }
            if (decision == null && unresolved && rule.failClosed()
                    && scope.factoryHasProtection(anyFactory != null ? anyFactory : scope.factoryOfUsage())) {
                decision = new ProcessSecretAccessService.Decision(ProcessSecretAccessService.Outcome.DENY, null,
                        "无法确认所属产品");
            }
            if (decision == null) return false;
            scope.redacted = true;
            gen.writeStartObject();
            rule.summary().write(value, gen);
            gen.writeObjectFieldStart("processSecret");
            gen.writeStringField("visibility", "RESTRICTED");
            gen.writeStringField("storageMode",
                    decision.storageMode() == null ? null : decision.storageMode().name());
            gen.writeStringField("message", RESTRICTED_MESSAGE);
            gen.writeEndObject();
            gen.writeEndObject();
            return true;
        }

        @Override
        public void resolve(SerializerProvider provider) throws JsonMappingException {
            if (delegate instanceof ResolvableSerializer resolvable) resolvable.resolve(provider);
        }

        @Override
        @SuppressWarnings("unchecked")
        public JsonSerializer<?> createContextual(SerializerProvider provider, BeanProperty property)
                throws JsonMappingException {
            if (delegate instanceof ContextualSerializer contextual) {
                return new GuardedSerializer(
                        (JsonSerializer<Object>) contextual.createContextual(provider, property), rule);
            }
            return this;
        }

        @Override
        public boolean usesObjectId() {
            return delegate.usesObjectId();
        }
    }
}
