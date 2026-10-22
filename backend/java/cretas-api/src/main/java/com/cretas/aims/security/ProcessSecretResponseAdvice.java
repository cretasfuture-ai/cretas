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

    public ProcessSecretResponseAdvice(ProcessSecretAccessService accessService,
                                       BomRecipeRepository recipeRepository,
                                       ObjectMapper objectMapper) {
        this.accessService = accessService;
        this.recipeRepository = recipeRepository;
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
        String scopeFactory = actor.isPlatformAdmin() ? pathFactoryId(http) : actor.factoryId();
        if (scopeFactory == null || accessService.protectedProductTypeIds(scopeFactory).isEmpty()) {
            return body;
        }
        Object payload = body instanceof ApiResponse<?> api ? api.getData() : body;
        if (payload == null) return body;

        JsonNode filtered = filter(payload, actor);
        if (filtered == null) return body;
        if (body instanceof ApiResponse<?> api) {
            ((ApiResponse<Object>) api).setData(filtered);
            return api;
        }
        return filtered;
    }

    /** @return 裁剪后的 JSON 树; 没有任何对象被裁剪时返回 null (调用方保留原响应)。 */
    JsonNode filter(Object payload, AuthenticatedActor actor) {
        Scope scope = new Scope(actor);
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
        final Map<String, Set<String>> protectedByFactory = new HashMap<>();
        final Map<ProductKey, ProcessSecretAccessService.Decision> decisions = new HashMap<>();
        final Map<String, Optional<String>> recipeProduct = new HashMap<>();
        boolean redacted;

        Scope(AuthenticatedActor actor) {
            this.actor = actor;
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
    private record Rule(BiFunction<Object, Scope, ProductKey> key, SummaryWriter summary) { }

    @FunctionalInterface
    private interface SummaryWriter {
        void write(Object value, JsonGenerator gen) throws IOException;
    }

    private static final Map<Class<?>, Rule> RULES = Map.of(
            BomRecipe.class, new Rule(
                    (v, s) -> new ProductKey(((BomRecipe) v).getFactoryId(), ((BomRecipe) v).getProductTypeId()),
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
                    }),
            BomRecipeItem.class, new Rule(
                    (v, s) -> new ProductKey(((BomRecipeItem) v).getFactoryId(),
                            s.productOfRecipe(((BomRecipeItem) v).getRecipeId())),
                    (v, g) -> {
                        BomRecipeItem i = (BomRecipeItem) v;
                        if (i.getId() != null) g.writeNumberField("id", i.getId());
                        g.writeStringField("recipeId", i.getRecipeId());
                    }),
            BomSeasoningItem.class, new Rule(
                    (v, s) -> new ProductKey(((BomSeasoningItem) v).getFactoryId(),
                            s.productOfRecipe(((BomSeasoningItem) v).getRecipeId())),
                    (v, g) -> {
                        BomSeasoningItem i = (BomSeasoningItem) v;
                        if (i.getId() != null) g.writeNumberField("id", i.getId());
                        g.writeStringField("recipeId", i.getRecipeId());
                    }),
            ProductProcessWorkflow.class, new Rule(
                    (v, s) -> new ProductKey(((ProductProcessWorkflow) v).getFactoryId(),
                            ((ProductProcessWorkflow) v).getProductTypeId()),
                    (v, g) -> {
                        ProductProcessWorkflow w = (ProductProcessWorkflow) v;
                        if (w.getId() != null) g.writeNumberField("id", w.getId());
                        g.writeStringField("factoryId", w.getFactoryId());
                        g.writeStringField("productTypeId", w.getProductTypeId());
                        g.writeStringField("status", w.getStatus() == null ? null : w.getStatus().name());
                    }),
            ProductProcessWorkflowRevision.class, new Rule(
                    (v, s) -> new ProductKey(((ProductProcessWorkflowRevision) v).getFactoryId(),
                            ((ProductProcessWorkflowRevision) v).getProductTypeId()),
                    (v, g) -> {
                        ProductProcessWorkflowRevision w = (ProductProcessWorkflowRevision) v;
                        if (w.getId() != null) g.writeNumberField("id", w.getId());
                        g.writeStringField("factoryId", w.getFactoryId());
                        g.writeStringField("productTypeId", w.getProductTypeId());
                    }),
            ProductProcessWorkflowDTO.class, new Rule(
                    (v, s) -> new ProductKey(((ProductProcessWorkflowDTO) v).getFactoryId(),
                            ((ProductProcessWorkflowDTO) v).getProductTypeId()),
                    (v, g) -> {
                        ProductProcessWorkflowDTO d = (ProductProcessWorkflowDTO) v;
                        if (d.getId() != null) g.writeNumberField("id", d.getId());
                        g.writeStringField("factoryId", d.getFactoryId());
                        g.writeStringField("productTypeId", d.getProductTypeId());
                        g.writeStringField("status", d.getStatus());
                        if (d.getVersion() != null) g.writeNumberField("version", d.getVersion());
                    }));

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
            ProductKey key = rule.key().apply(value, scope);
            ProcessSecretAccessService.Decision decision = scope.restricted(key.factoryId(), key.productTypeId());
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
