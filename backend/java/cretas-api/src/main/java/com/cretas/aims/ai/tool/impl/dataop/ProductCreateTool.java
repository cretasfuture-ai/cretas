package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.dto.producttype.ProductTypeSuggestionDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.ProductTypeService;
import com.cretas.aims.service.ProductCreateCommandReceiptService;
import com.cretas.aims.service.ProductWorkProcessService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 产品创建工具 (飞轮衔接) —— "说一句话建产品"。
 *
 * <p>不只建空壳: 通过数据飞轮 ({@code suggestDefaults} 智能填充 + 最相似产品) 给新产品配好<b>大框架</b>:
 * <ul>
 *   <li>属性默认 (类别/单位/克重) 取自飞轮最相似产品;</li>
 *   <li><b>自动继承</b>最相似产品的整条<b>工序链</b> (复用 {@code copyChain} 原子复制) —— 这是"大框架";</li>
 *   <li>把相似产品的 <b>BOM 原料规则 + 调料配方</b> 作为<b>建议</b>返回 (不自动套用, 由用户调用量/损耗细节)。</li>
 * </ul>
 * 用户随后只微调细节。preview 先展示整套计划 (防呆), 确认后才落库。
 */
@Slf4j
@Component
public class ProductCreateTool extends AbstractBusinessTool {

    /** Internal-only token parameter. Controller strips it from the public preview response. */
    public static final String SERVER_BINDING_KEY = "_serverProductCreateBinding";
    private static final ThreadLocal<PreviewBindingCapture> PREVIEW_BINDING_CAPTURE = new ThreadLocal<>();
    private static final ThreadLocal<ExecutionCapture> EXECUTION_CAPTURE = new ThreadLocal<>();
    private static final com.fasterxml.jackson.databind.ObjectMapper BINDING_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final List<String> SKU_CATEGORIES = List.of(
            "FINISHED_PRODUCT", "SEMI_FINISHED", "CUSTOMER_MATERIAL", "CONTRACT_MANUFACTURING");

    @Autowired
    private ProductTypeService productTypeService;
    @Autowired
    private ProductTypeRepository productTypeRepository;
    @Autowired
    private ProductWorkProcessService productWorkProcessService;
    @Autowired
    private BomRecipeService bomRecipeService;
    @Autowired
    private com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard processSecretGuard;
    @Autowired
    private ProductCreateCommandReceiptService commandReceiptService;

    /** A stale confirmed plan needs a new preview, before any product was committed. */
    @Override
    protected String buildErrorResult(String message, Integer errorCode) {
        String base = super.buildErrorResult(message, errorCode);
        if (!Integer.valueOf(409).equals(errorCode)
                || commandReceiptService == null || !ProductCreateCommandReceiptService.hasScope()) {
            return base;
        }
        try {
            ProductCreateCommandReceiptService.ReceiptSnapshot receipt =
                    commandReceiptService.readCurrent().orElse(null);
            if (receipt == null
                    || receipt.state() != com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord.State.IN_PROGRESS
                    || receipt.outcomeStatus() != null
                    || receipt.entityType() != null || receipt.entityId() != null || receipt.stage() != null) {
                return base;
            }
            com.fasterxml.jackson.databind.JsonNode parsed = objectMapper.readTree(base);
            if (!(parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode result)) {
                return base;
            }
            // Reuse the Gateway's definite pre-write information-required contract.
            // Copy/readback/commit uncertainty remains on the ordinary error path.
            result.put("success", false);
            result.put("status", "NEED_MORE_INFO");
            result.put("needMoreInfo", true);
            result.put("requiresNewPreview", true);
            result.put("recoveryAction", "REPREVIEW");
            result.put("message", result.path("error").asText("方案已变化，请重新预览后确认"));
            return objectMapper.writeValueAsString(result);
        } catch (Exception unavailableProof) {
            log.warn("[PRODUCT-CREATE] Pre-write rejection proof unavailable; outcome remains uncertain");
            return base;
        }
    }

    private boolean mayInheritFrom(String factoryId, String sourceProductId) {
        String ref = "AI tool product_create inherit " + sourceProductId;
        return processSecretGuard.permitsProduct(factoryId, sourceProductId,
                com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL, ref)
                && processSecretGuard.permitsProduct(factoryId, sourceProductId,
                com.cretas.aims.entity.processsecret.ProcessSecretAction.EXPORT, ref);
    }

    @Override
    public String getToolName() {
        return "product_create";
    }

    @Override
    public String getDescription() {
        return "新建产品(SKU)并从数据飞轮继承最相似产品的工序链作为大框架, 同时给出 BOM/调料配置建议供微调。" +
                "适用场景: 用户说'建一个XX产品'/'新增产品XX'。会自动找历史上最像的产品, 把它的工序链复制给新产品, " +
                "并把它的原料BOM/调料配方作为建议返回(不自动套用, 由用户调细节)。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("productName", Map.of("type", "string", "description", "产品名称(必需), 如 '叮咚红烧肉 200g'"));
        props.put("unit", Map.of("type", "string", "description", "计量单位, 如 盒/份/kg; 不填则按飞轮相似产品建议"));
        props.put("productCategory", Map.of(
                "type", "string",
                "enum", SKU_CATEGORIES,
                "description", "SKU 产品类别；半成品必须使用 SEMI_FINISHED"));
        props.put("specification", Map.of("type", "string", "description", "规格, 如 200g/盒"));
        props.put("customerName", Map.of("type", "string", "description", "关联客户名(可选)"));
        props.put("inheritFrom", Map.of("type", "string", "description", "显式指定继承哪个产品的工序链(可选); 不填则飞轮自动找最相似产品"));
        return Map.of("type", "object", "properties", props, "required", List.of("productName"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("productName");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.WRITE;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return false;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("production:read_write", "system:read_write");
    }

    @Override
    public String getVersion() {
        return "2.0.0";
    }

    /** Opens the only supported bridge for the HTTP controller to receive its server-bound plan. */
    public static PreviewBindingCapture openPreviewBindingCapture(String factoryId) {
        if (factoryId == null || factoryId.isBlank() || PREVIEW_BINDING_CAPTURE.get() != null) {
            throw new IllegalStateException("product preview binding capture scope is invalid");
        }
        PreviewBindingCapture capture = new PreviewBindingCapture(factoryId);
        PREVIEW_BINDING_CAPTURE.set(capture);
        return capture;
    }

    /** Opens the HTTP-only reconciliation bridge for a product created before a later failure. */
    public static ExecutionCapture openExecutionCapture(String factoryId) {
        if (factoryId == null || factoryId.isBlank() || EXECUTION_CAPTURE.get() != null) {
            throw new IllegalStateException("product execution capture scope is invalid");
        }
        ExecutionCapture capture = new ExecutionCapture(factoryId);
        EXECUTION_CAPTURE.set(capture);
        return capture;
    }

    public static void capturePreviewBindingJson(String factoryId, String bindingJson) {
        PreviewBindingCapture capture = PREVIEW_BINDING_CAPTURE.get();
        if (capture == null) {
            throw new IllegalStateException("product preview binding capture scope is missing");
        }
        capture.captureJson(factoryId, bindingJson);
    }

    /** Read-only classification helper for the HTTP reconciliation path. */
    public static boolean hasNonEmptyPreparedChain(String bindingJson) {
        if (bindingJson == null || bindingJson.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> binding = BINDING_MAPPER.readValue(
                    bindingJson,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
            Object rawChain = binding.get("chain");
            if (!(rawChain instanceof Map<?, ?> chain)) {
                return false;
            }
            Object processes = chain.get("processes");
            return processes instanceof List<?> list && !list.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static final class PreviewBindingCapture implements AutoCloseable {
        private final String factoryId;
        private String json;
        private boolean closed;

        private PreviewBindingCapture(String factoryId) {
            this.factoryId = factoryId;
        }

        private void capture(String factoryId, Map<String, Object> binding) {
            if (!this.factoryId.equals(factoryId) || json != null || closed) {
                throw new IllegalStateException("product preview binding capture is invalid");
            }
            try {
                json = BINDING_MAPPER.writeValueAsString(binding);
            } catch (Exception exception) {
                throw new IllegalStateException("product preview binding could not be serialized");
            }
        }

        public void captureJson(String factoryId, String bindingJson) {
            if (!this.factoryId.equals(factoryId) || bindingJson == null || bindingJson.isBlank()
                    || json != null || closed) {
                throw new IllegalStateException("product preview binding capture is invalid");
            }
            try {
                if (!BINDING_MAPPER.readTree(bindingJson).isObject()) {
                    throw new IllegalStateException("product preview binding capture is invalid");
                }
            } catch (Exception exception) {
                throw new IllegalStateException("product preview binding capture is invalid");
            }
            json = bindingJson;
        }

        public String requireJson() {
            if (json == null || json.isBlank()) {
                throw new com.cretas.aims.exception.BusinessException(502,
                        "AI 建产品预览缺少服务端绑定计划")
                        .withCode("PRODUCT_CREATE_BINDING_MISSING")
                        .withHint("请稍后重新发起预览");
            }
            return json;
        }

        @Override
        public void close() {
            closed = true;
            if (PREVIEW_BINDING_CAPTURE.get() == this) {
                PREVIEW_BINDING_CAPTURE.remove();
            }
        }
    }

    public static final class ExecutionCapture implements AutoCloseable {
        private final String factoryId;
        private String createdProductId;
        private String phase;
        private boolean closed;

        private ExecutionCapture(String factoryId) {
            this.factoryId = factoryId;
        }

        private void recordCreated(String factoryId, String productId) {
            if (!this.factoryId.equals(factoryId) || productId == null || productId.isBlank()
                    || closed || createdProductId != null) {
                throw new IllegalStateException("product execution capture is invalid");
            }
            createdProductId = productId;
            phase = "PRODUCT_CREATED";
        }

        private void recordPhase(String factoryId, String phase) {
            if (!this.factoryId.equals(factoryId) || phase == null || phase.isBlank() || closed
                    || createdProductId == null) {
                throw new IllegalStateException("product execution capture is invalid");
            }
            this.phase = phase;
        }

        public String createdProductId() {
            return createdProductId;
        }

        public String phase() {
            return phase;
        }

        @Override
        public void close() {
            closed = true;
            if (EXECUTION_CAPTURE.get() == this) {
                EXECUTION_CAPTURE.remove();
            }
        }
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("product");
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params, Map<String, Object> context) {
        Plan plan = resolvePlan(factoryId, params);
        String productCategory = validatedCategory(params);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "PREVIEW");
        r.put("productName", plan.name);
        r.put("productCategory", productCategory);
        r.put("defaults", plan.defaults);
        r.put("inheritFromProduct", plan.sourceName);
        r.put("suggestedProcessChain", plan.processNames);
        r.put("suggestedBom", plan.bomSuggestion);
        r.put("suggestedSeasoning", plan.seasoningSuggestion);
        capturePreviewBinding(factoryId, bindingFor(plan, productCategory));
        r.put("message", plan.source == null
                ? "将新建产品「" + plan.name + "」(飞轮未找到可继承工序链的相似产品, 工序需手动配)"
                : "将新建产品「" + plan.name + "」, 大框架沿用最相似产品「" + plan.sourceName + "」的 "
                        + plan.processNames.size() + " 道工序链; BOM/调料作为建议供你调细节");
        return r;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        ProductCreateCommandReceiptService.requireScope();
        Plan plan = resolveBoundPlan(factoryId, params);
        String productCategory = validatedCategory(params);
        if (!Objects.equals(productCategory, plan.productCategory)) {
            throw new com.cretas.aims.exception.BusinessException(409,
                    "确认参数与已预览的产品类别不一致，请重新预览")
                    .withHint("请重新发起产品创建预览");
        }
        if (plan.source != null && plan.preparedChain != null) {
            // This is deliberately before createProductType: source/master drift must not
            // leave a product shell behind. copyPreparedChain repeats the check after create
            // to close the race between this read and the actual copy transaction.
            productWorkProcessService.validatePreparedChain(
                    factoryId, plan.source.getId(), plan.preparedChain);
        }

        // 1. 建产品 (属性默认来自飞轮)
        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setName(plan.name);
        Object unitDefault = plan.defaults.get("unit");
        dto.setUnit(unitDefault != null ? String.valueOf(unitDefault) : "盒");
        dto.setProductCategory(productCategory);
        dto.setSpecification(getString(params, "specification"));
        Object gpu = plan.defaults.get("gramsPerUnit");
        if (gpu != null) {
            dto.setGramsPerUnit(decimal(gpu));
        }
        String customer = getString(params, "customerName");
        if (customer != null) {
            dto.setRelatedCustomer(customer);
        }
        // created_by 是 NOT NULL: 普通 controller 路径由其自身从 token 填 dto.createdBy;
        // 工具路径必须从 context.userId 显式设置, 否则 product_types.created_by 违反 NOT NULL 约束。
        Object uid = context == null ? null : context.get("userId");
        if (uid instanceof Number) {
            dto.setCreatedBy(((Number) uid).longValue());
        }
        ProductTypeDTO created = commandReceiptService.createProductAndAttach(factoryId, dto);
        captureCreatedProduct(factoryId, created.getId());

        // 2. 飞轮继承: 自动复制最相似产品的工序链 (大框架)
        int copiedProcesses = 0;
        if (plan.source != null && plan.preparedChain != null
                && !plan.preparedChain.processes().isEmpty()) {
            try {
                copiedProcesses = commandReceiptService.copyPreparedAndAdvance(
                        factoryId, plan.source.getId(), created.getId(), plan.preparedChain);
            } catch (Exception e) {
                // The wrapper is atomic with its CAS.  Read the durable stage before
                // deciding whether this is a known partial product, a committed copy
                // whose acknowledgement was lost, or an unknown outcome.
                ProductCreateCommandReceiptService.ReceiptSnapshot receipt;
                try {
                    receipt = commandReceiptService.readCurrent().orElse(null);
                } catch (Exception readFailure) {
                    receipt = null;
                }
                if (receipt != null && ProductCreateCommandReceiptService.COPY_COMMITTED
                        .equals(receipt.stage())) {
                    copiedProcesses = plan.preparedChain.processes().size();
                } else if (receipt != null && ProductCreateCommandReceiptService.PRODUCT_CREATED
                        .equals(receipt.stage())) {
                    log.warn("[PRODUCT-CREATE] 工序链复制失败，产品已创建，需人工配置: productId={}, sourceProductId={}",
                            created.getId(), plan.source.getId());
                    throw new com.cretas.aims.exception.BusinessException(502,
                            "产品「" + created.getName() + "」（ID: " + created.getId()
                                    + "）已创建，但相似产品「" + plan.sourceName
                                    + "」的工序链复制失败，未完成继承；请人工配置。")
                            .withCode("PRODUCT_CHAIN_COPY_FAILED")
                            .withHint("请检查源产品工序链后重新配置；系统不会自动重试");
                } else {
                    throw new com.cretas.aims.exception.BusinessException(502,
                            "产品「" + created.getName() + "」（ID: " + created.getId()
                                    + "）的创建结果无法核对，需人工检查；系统不会自动重试。")
                            .withCode("PRODUCT_CREATE_OUTCOME_UNKNOWN")
                            .withHint("请根据产品ID核对产品和工序链，不要重复提交创建请求");
                }
            }
            captureExecutionPhase(factoryId, "CHAIN_COPY_COMMITTED");
            try {
                commandReceiptService.verifyPreparedReadbackAndAdvance(
                        factoryId, created.getId(), plan.preparedChain);
            } catch (Exception e) {
                throw new com.cretas.aims.exception.BusinessException(502,
                        "产品「" + created.getName() + "」（ID: " + created.getId()
                                + "）的工序链复制结果无法核对，需人工检查；系统不会自动重试。")
                        .withCode("PRODUCT_CHAIN_READBACK_UNKNOWN")
                        .withHint("请根据产品ID核对工序链，不要重复提交创建请求");
            }
        } else {
            try {
                commandReceiptService.verifyEmptyReadbackAndAdvance(factoryId, created.getId());
            } catch (Exception e) {
                throw new com.cretas.aims.exception.BusinessException(502,
                        "产品「" + created.getName()
                                + "」（ID: " + created.getId() + "）的空工序链结果无法核对，需人工检查；系统不会自动重试。")
                        .withCode("PRODUCT_CHAIN_READBACK_UNKNOWN")
                        .withHint("请根据产品ID核对工序链，不要重复提交创建请求");
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("productId", created.getId());
        r.put("productName", created.getName());
        r.put("inheritedFromProduct", plan.sourceName);
        r.put("copiedProcessCount", copiedProcesses);
        r.put("suggestedBom", plan.bomSuggestion);
        r.put("suggestedSeasoning", plan.seasoningSuggestion);
        r.put("nextStep", "大框架(工序链)已配好; 请按建议调 BOM 用量/损耗 + 调料配方细节");
        r.put("message", copiedProcesses > 0
                ? "产品「" + created.getName() + "」已建, 已继承「" + plan.sourceName + "」的 " + copiedProcesses
                    + " 道工序链作为大框架; BOM/调料见建议, 请调细节"
                : plan.source == null
                    ? "产品「" + created.getName() + "」已建 (飞轮无相似工序链可继承, 工序请手动配)"
                    : "产品「" + created.getName() + "」已建 (相似产品「" + plan.sourceName
                        + "」没有可继承的工序链, 工序请手动配)");
        return r;
    }

    /** 飞轮解析: 属性默认 + 最相似源产品 + 它的工序链/BOM/调料 (供 preview 与 execute 共用)。 */
    private Plan resolvePlan(String factoryId, Map<String, Object> params) {
        Plan p = new Plan();
        p.name = getString(params, "productName");
        p.defaults = new LinkedHashMap<>();
        p.processNames = new ArrayList<>();
        p.bomSuggestion = new ArrayList<>();
        p.seasoningSuggestion = new ArrayList<>();

        String requestedCategory = validatedCategory(params);
        // 飞轮属性建议 (智能填充)
        try {
            ProductTypeSuggestionDTO sug = productTypeService.suggestDefaults(
                    factoryId, p.name, requestedCategory);
            if (sug != null) {
                if (sug.getUnit() != null) p.defaults.put("unit", sug.getUnit());
                if (sug.getGramsPerUnit() != null) p.defaults.put("gramsPerUnit", sug.getGramsPerUnit());
                if (sug.getProductCategory() != null) p.defaults.put("productCategory", sug.getProductCategory());
                p.matchedFrom = sug.getBaseProductName() != null ? sug.getBaseProductName() : sug.getMatchedFrom();
            }
        } catch (Exception e) {
            log.warn("[PRODUCT-CREATE] suggestDefaults 失败: {}", e.getMessage());
        }
        // 用户显式 unit 覆盖飞轮建议
        String explicitUnit = getString(params, "unit");
        if (explicitUnit != null) p.defaults.put("unit", explicitUnit);

        // 解析源产品 (inheritFrom 优先, 否则飞轮最相似)
        String explicitSource = getString(params, "inheritFrom");
        String sourceName = explicitSource != null ? explicitSource : p.matchedFrom;
        if (sourceName != null) {
            p.source = productTypeRepository.findByFactoryIdAndName(factoryId, sourceName).orElse(null);
            if (p.source != null && !requestedCategory.equals(p.source.getProductCategory())) {
                log.info("[PRODUCT-CREATE] 忽略跨类别继承: requested={}, source={}({})",
                        requestedCategory, p.source.getName(), p.source.getProductCategory());
                p.source = null;
            }
            // 工艺保密: 预览会带出源产品的 BOM 用量 / 调料配比, 执行会复制它的工序链。
            // 受保护的源要求完整查看 + 导出授权 (与复制配方同一口径); 点名的直接拒绝, 飞轮自动挑的换成不继承
            if (p.source != null && !mayInheritFrom(factoryId, p.source.getId())) {
                if (explicitSource != null) {
                    throw new com.cretas.aims.exception.BusinessException(403,
                            "「" + p.source.getName() + "」的工艺已设为保密，不能作为继承来源");
                }
                p.source = null;
            }
            if (p.source != null) p.sourceName = p.source.getName();
        }

        // 源的工序链 + BOM + 调料 (建议)
        if (p.source != null) {
            p.preparedChain = productWorkProcessService.prepareChain(factoryId, p.source.getId());
            p.processNames = new ArrayList<>(p.preparedChain.processNames());
            try {
                bomRecipeService.getCurrentRecipe(factoryId, p.source.getId())
                        .map(recipe -> bomRecipeService.getRecipe(factoryId, recipe.getId()))
                        .ifPresent(recipe -> {
                            for (BomRecipeItem b : recipe.getItems()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("materialName", b.getMaterialName());
                                row.put("standardQuantity", b.getStandardQuantity());
                                row.put("yieldRate", b.getYieldRate());
                                row.put("materialCategory", b.getMaterialCategory());
                                row.put("unit", b.getUnit());
                                p.bomSuggestion.add(row);
                            }
                        });
            } catch (Exception ignore) { /* BOM 可选 */ }
            try {
                bomRecipeService.getSeasoningByProduct(factoryId, p.source.getId()).ifPresent(sea -> {
                    if (sea.getSeasoningItems() != null) {
                        sea.getSeasoningItems().forEach(it -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("name", it.getName());
                            row.put("section", it.getSection());
                            row.put("dosagePerKgG", it.getDosagePerKgG());
                            p.seasoningSuggestion.add(row);
                        });
                    }
                });
            } catch (Exception ignore) { /* 调料可选 */ }
        }
        return p;
    }

    /** Reconstructs only from the server-issued token snapshot; no current source defaults are re-resolved. */
    private Plan resolveBoundPlan(String factoryId, Map<String, Object> params) {
        Object raw = params.get(SERVER_BINDING_KEY);
        if (!(raw instanceof String rawBinding) || rawBinding.isBlank()) {
            throw new com.cretas.aims.exception.BusinessException(409,
                    "产品创建确认凭证缺少服务端计划绑定，请重新预览")
                    .withCode("PRODUCT_CREATE_BINDING_REQUIRED")
                    .withHint("请重新发起产品创建预览");
        }
        Map<String, Object> binding;
        try {
            binding = objectMapper.reader()
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .forType(new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {})
                    .readValue(rawBinding);
        } catch (Exception invalidBinding) {
            throw bindingRejected();
        }
        if (!(binding.get("version") instanceof Number version) || version.intValue() != 1) {
            throw bindingRejected();
        }
        Plan plan = new Plan();
        plan.name = requiredBindingText(binding, "productName");
        plan.productCategory = requiredBindingText(binding, "productCategory");
        if (!Objects.equals(plan.name, getString(params, "productName"))) {
            throw bindingRejected();
        }
        plan.defaults = mapBinding(binding.get("defaults"));
        plan.processNames = stringList(binding.get("processNames"));
        plan.bomSuggestion = List.of();
        plan.seasoningSuggestion = List.of();
        plan.sourceName = nullableBindingText(binding.get("sourceName"));
        String sourceId = nullableBindingText(binding.get("sourceProductId"));
        Object rawChain = binding.get("chain");
        if (sourceId != null) {
            plan.source = productTypeRepository.findByIdAndFactoryId(sourceId, factoryId)
                    .orElseThrow(() -> bindingRejected());
            if (!Objects.equals(plan.sourceName, plan.source.getName())
                    || !Objects.equals(plan.productCategory, plan.source.getProductCategory())) {
                throw bindingRejected();
            }
            if (!mayInheritFrom(factoryId, sourceId)) {
                throw new com.cretas.aims.exception.BusinessException(403,
                        "继承来源当前不可导出，无法确认产品创建")
                        .withHint("请重新预览并确认当前可授权的来源");
            }
            plan.preparedChain = parsePreparedChain(sourceId, rawChain);
            plan.processNames = new ArrayList<>(plan.preparedChain.processNames());
        } else if (rawChain != null) {
            throw bindingRejected();
        }
        return plan;
    }

    private Map<String, Object> bindingFor(Plan plan, String productCategory) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("version", 1);
        binding.put("productName", plan.name);
        binding.put("productCategory", productCategory);
        binding.put("defaults", new LinkedHashMap<>(plan.defaults));
        binding.put("sourceName", plan.sourceName);
        binding.put("sourceProductId", plan.source == null ? null : plan.source.getId());
        binding.put("processNames", new ArrayList<>(plan.processNames));
        if (plan.preparedChain != null) {
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("sourceProductId", plan.preparedChain.sourceProductId());
            chain.put("digest", plan.preparedChain.digest());
            chain.put("processNames", new ArrayList<>(plan.preparedChain.processNames()));
            chain.put("processCount", plan.preparedChain.processes().size());
            chain.put("processes", plan.preparedChain.processes().stream()
                    .map(this::preparedProcessMap).toList());
            chain.put("masters", plan.preparedChain.masters().stream().map(master -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", master.id());
                row.put("lockVersion", master.lockVersion());
                row.put("selectable", master.selectable());
                return row;
            }).toList());
            binding.put("chain", chain);
        }
        return binding;
    }

    private Map<String, Object> preparedProcessMap(
            ProductWorkProcessService.PreparedProcess row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("workProcessId", row.workProcessId());
        values.put("processOrder", row.processOrder());
        values.put("unitOverride", row.unitOverride());
        values.put("estimatedMinutesOverride", row.estimatedMinutesOverride());
        values.put("reportingRequired", row.reportingRequired());
        values.put("allowSemiFinishedInjection", row.allowSemiFinishedInjection());
        values.put("allowMultipleUpstreamSources", row.allowMultipleUpstreamSources());
        values.put("allowFinishedGoodsSource", row.allowFinishedGoodsSource());
        values.put("defaultCostCategory", row.defaultCostCategory());
        values.put("packagingTemplate", row.packagingTemplate());
        values.put("auxAllocMethod", row.auxAllocMethod());
        values.put("standardYieldRate", row.standardYieldRate());
        values.put("auxUnitPrice", row.auxUnitPrice());
        values.put("auxBasis", row.auxBasis());
        return values;
    }

    private ProductWorkProcessService.PreparedChain parsePreparedChain(
            String sourceId, Object rawChain) {
        if (!(rawChain instanceof Map<?, ?> raw)) {
            throw bindingRejected();
        }
        Map<String, Object> chain = objectMapper.convertValue(raw,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        if (!Objects.equals(sourceId, nullableBindingText(chain.get("sourceProductId")))) {
            throw bindingRejected();
        }
        String digest = requiredBindingText(chain, "digest");
        List<ProductWorkProcessService.PreparedProcess> processes = new ArrayList<>();
        Object rawProcesses = chain.get("processes");
        if (!(rawProcesses instanceof List<?> processList)) {
            throw bindingRejected();
        }
        for (Object rawProcess : processList) {
            if (!(rawProcess instanceof Map<?, ?>)) {
                throw bindingRejected();
            }
            Map<String, Object> row = objectMapper.convertValue(rawProcess,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            processes.add(new ProductWorkProcessService.PreparedProcess(
                    requiredBindingText(row, "workProcessId"), integer(row.get("processOrder")),
                    nullableBindingText(row.get("unitOverride")), integer(row.get("estimatedMinutesOverride")),
                    bool(row.get("reportingRequired")), bool(row.get("allowSemiFinishedInjection")),
                    bool(row.get("allowMultipleUpstreamSources")), bool(row.get("allowFinishedGoodsSource")),
                    nullableBindingText(row.get("defaultCostCategory")),
                    listMap(row.get("packagingTemplate")), nullableBindingText(row.get("auxAllocMethod")),
                    decimal(row.get("standardYieldRate")), decimal(row.get("auxUnitPrice")),
                    nullableBindingText(row.get("auxBasis"))));
        }
        List<ProductWorkProcessService.PreparedMaster> masters = new ArrayList<>();
        Object rawMasters = chain.get("masters");
        if (!(rawMasters instanceof List<?> masterList)) {
            throw bindingRejected();
        }
        for (Object rawMaster : masterList) {
            if (!(rawMaster instanceof Map<?, ?>)) {
                throw bindingRejected();
            }
            Map<String, Object> row = objectMapper.convertValue(rawMaster,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            masters.add(new ProductWorkProcessService.PreparedMaster(
                    requiredBindingText(row, "id"), longValue(row.get("lockVersion")),
                    Boolean.TRUE.equals(row.get("selectable"))));
        }
        List<String> processNames = stringList(chain.get("processNames"));
        return new ProductWorkProcessService.PreparedChain(sourceId, processes, masters, digest, processNames);
    }

    private Map<String, Object> mapBinding(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?>)) throw bindingRejected();
        return objectMapper.convertValue(value,
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) throw bindingRejected();
        return list.stream().map(this::nullableBindingText).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listMap(Object value) {
        if (value == null) return null;
        if (!(value instanceof List<?> list)) throw bindingRejected();
        return list.stream().map(item -> {
            if (!(item instanceof Map<?, ?>)) throw bindingRejected();
            return objectMapper.convertValue(item,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        }).toList();
    }

    private Integer integer(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        try { return Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw bindingRejected(); }
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw bindingRejected(); }
    }

    private Boolean bool(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean bool) return bool;
        throw bindingRejected();
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw bindingRejected(); }
    }

    private static void capturePreviewBinding(String factoryId, Map<String, Object> binding) {
        PreviewBindingCapture capture = PREVIEW_BINDING_CAPTURE.get();
        if (capture != null) {
            capture.capture(factoryId, binding);
        }
    }

    private static void captureCreatedProduct(String factoryId, String productId) {
        ExecutionCapture capture = EXECUTION_CAPTURE.get();
        if (capture != null) {
            capture.recordCreated(factoryId, productId);
        }
    }

    private static void captureExecutionPhase(String factoryId, String phase) {
        ExecutionCapture capture = EXECUTION_CAPTURE.get();
        if (capture != null) {
            capture.recordPhase(factoryId, phase);
        }
    }

    private String requiredBindingText(Map<String, Object> binding, String key) {
        String value = nullableBindingText(binding.get(key));
        if (value == null) throw bindingRejected();
        return value;
    }

    private String nullableBindingText(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private com.cretas.aims.exception.BusinessException bindingRejected() {
        return new com.cretas.aims.exception.BusinessException(409,
                "产品创建确认计划已失效，请重新预览")
                .withCode("PRODUCT_CREATE_BINDING_INVALID")
                .withHint("请重新发起产品创建预览");
    }

    private String validatedCategory(Map<String, Object> params) {
        String category = getString(params, "productCategory", "FINISHED_PRODUCT").trim().toUpperCase();
        if (!SKU_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("不支持的 SKU 产品类别: " + category
                    + "；可选值为 " + String.join(", ", SKU_CATEGORIES));
        }
        return category;
    }

    /** 飞轮解析结果 (内部传递)。 */
    private static class Plan {
        String name;
        String matchedFrom;
        String sourceName;
        ProductType source;
        Map<String, Object> defaults;
        List<String> processNames;
        String productCategory;
        ProductWorkProcessService.PreparedChain preparedChain;
        List<Map<String, Object>> bomSuggestion;
        List<Map<String, Object>> seasoningSuggestion;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
