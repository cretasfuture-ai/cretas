package com.cretas.aims.service.indicator;

import com.cretas.aims.controller.dto.indicator.IndicatorApplicabilityRequest.ProductSpec;
import com.cretas.aims.controller.dto.indicator.IndicatorApplicabilityResponse;
import com.cretas.aims.controller.dto.indicator.IndicatorApplicabilityResponse.MetricApplicability;
import com.cretas.aims.controller.dto.indicator.IndicatorApplicabilityResponse.ProductApplicability;
import com.cretas.aims.controller.dto.indicator.IndicatorApplicabilityResponse.ProductProfile;
import com.cretas.aims.controller.dto.indicator.IndicatorApplicabilityResponse.Status;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.WorkflowProcessPath;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Objects;

/**
 * Explains configuration applicability only. It deliberately does not query or invent runtime facts.
 */
@Service
@RequiredArgsConstructor
public class FactoryIndicatorApplicabilityService {
    public static final List<String> METRIC_CODES = List.of(
            "FACTORY_LU_YIELD_RATE", "FACTORY_GOOD_OUTPUT_RATE",
            "FACTORY_LU_UNIT_COST", "FACTORY_ACTUAL_TOTAL_COST");

    private final ProductTypeRepository productTypeRepository;
    private final UnitContractService units;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final ProductProcessWorkflowActivationRepository activationRepository;
    private final ObjectMapper objectMapper;
    private final ProductWorkflowResolutionService workflowResolutionService;

    @Transactional(readOnly = true)
    public IndicatorApplicabilityResponse assess(String factoryId) {
        List<ProductType> products = productTypeRepository.findVisibleByFactoryId(factoryId).stream()
                .filter(FactoryIndicatorApplicabilityService::eligibleProduct)
                .toList();
        List<ProductSpec> specs = products.stream().map(p -> currentSpec(factoryId, p)).toList();
        return assessSpecs(factoryId, specs, IndicatorApplicabilityResponse.Basis.CONFIGURATION_ONLY);
    }

    public static boolean eligibleProduct(ProductType product) {
        return Boolean.TRUE.equals(product.getIsActive()) && (product.getProductCategory() == null
                || "FINISHED_PRODUCT".equals(product.getProductCategory())
                || "CONTRACT_MANUFACTURING".equals(product.getProductCategory()));
    }

    private ProductSpec currentSpec(String factoryId, ProductType product) {
        String inputUnit = null;
        Boolean multiOutput = null;
        WorkflowProcessPath path = workflowResolutionService.resolveProcessPath(factoryId, product.getId()).orElse(null);
        if (path != null && product.getId().equals(path.terminalProductTypeId())) {
            var activation = activationRepository.findByFactoryIdAndProductTypeId(factoryId, path.ownerId()).orElse(null);
            var workflow = activation == null || !Boolean.TRUE.equals(activation.getEnabled())
                    || activation.getActiveWorkflowId() == null ? null
                    : workflowRepository.findByIdAndFactoryId(path.workflowId(), factoryId).orElse(null);
            if (workflow != null && workflow.getStatus() == ProductProcessWorkflow.Status.PUBLISHED
                    && path.ownerId().equals(workflow.getProductTypeId())
                    && activation.getActiveWorkflowId().equals(path.workflowId())
                    && activation.getActiveDefinitionVersion() != null
                    && activation.getActiveDefinitionVersion().equals(path.definitionVersion())
                    && activation.getActiveDefinitionVersion().equals(workflow.getDefinitionVersion())) {
                try {
                    if (workflow.getNodesJson() == null) {
                        throw new com.cretas.aims.exception.BusinessException(409,
                                "产品已启用的生产流程节点结构无效，请修复流程后重试")
                                .withCode("INDICATOR_APPLICABILITY_CONFIG_MALFORMED");
                    }
                    JsonNode nodes = objectMapper.readTree(workflow.getNodesJson());
                    if (nodes == null || !nodes.isArray()) {
                        throw new com.cretas.aims.exception.BusinessException(409,
                                "产品已启用的生产流程节点结构无效，请修复流程后重试")
                                .withCode("INDICATOR_APPLICABILITY_CONFIG_MALFORMED");
                    }
                    int terminalOutputs = 0; boolean hasByproduct = false;
                    Set<String> rawUnits = new LinkedHashSet<>();
                    boolean rawUnitMissing = false;
                    for (JsonNode node : nodes) {
                        JsonNode data = node.path("data");
                        if ("RAW_MATERIAL".equals(node.path("kind").asText())) {
                            if (!data.hasNonNull("baseUnit")) rawUnitMissing = true;
                            else {
                                String rawUnit = trim(data.path("baseUnit").asText());
                                if (rawUnit == null) rawUnitMissing = true;
                                else rawUnits.add(rawUnit);
                            }
                        }
                        if ("FINISHED_GOOD".equals(node.path("kind").asText())) {
                            terminalOutputs++;
                            hasByproduct |= data.path("isByproduct").asBoolean(false);
                        }
                    }
                    if (!rawUnits.isEmpty() && !rawUnitMissing) {
                        String candidate = rawUnits.iterator().next();
                        var candidateUnit = units.describe(factoryId, candidate).orElse(null);
                        boolean sameMassFamily = candidateUnit != null && candidateUnit.dimension() == com.cretas.aims.service.unit.UnitDimension.MASS;
                        for (String rawUnit : rawUnits) {
                            var described = units.describe(factoryId, rawUnit).orElse(null);
                            sameMassFamily = sameMassFamily && described != null
                                    && described.dimension() == com.cretas.aims.service.unit.UnitDimension.MASS
                                    && Objects.equals(candidateUnit.baseCode(), described.baseCode());
                        }
                        if (sameMassFamily) inputUnit = candidate;
                    }
                    if (terminalOutputs > 0) multiOutput = terminalOutputs > 1 || hasByproduct;
                } catch (JsonProcessingException e) {
                    throw new com.cretas.aims.exception.BusinessException(409,
                            "产品已启用的生产流程配置无法解析，请修复流程后重试")
                            .withCode("INDICATOR_APPLICABILITY_CONFIG_MALFORMED");
                }
            }
        }
        return new ProductSpec(product.getId(), product.getName(), product.getUnit(), inputUnit,
                product.getGramsPerUnit(), multiOutput);
    }

    public IndicatorApplicabilityResponse assessPlanned(String factoryId, List<ProductSpec> specs) {
        return assessSpecs(factoryId, specs == null ? List.of() : specs,
                IndicatorApplicabilityResponse.Basis.PROPOSED_CONFIGURATION);
    }

    private IndicatorApplicabilityResponse assessSpecs(String factoryId, List<ProductSpec> specs,
                                                       IndicatorApplicabilityResponse.Basis basis) {
        List<ProductApplicability> products = specs.stream().map(spec -> product(factoryId, spec)).toList();
        List<MetricApplicability> aggregate = aggregate(products);
        boolean anyBlocked = aggregate.stream().anyMatch(m -> m.status() == Status.BLOCKED);
        boolean anyUnresolved = aggregate.stream().anyMatch(m -> m.status() == Status.NEEDS_CONFIRMATION);
        IndicatorApplicabilityResponse.OverallStatus overall = anyBlocked
                ? IndicatorApplicabilityResponse.OverallStatus.BLOCKED
                : (anyUnresolved || aggregate.stream().anyMatch(m -> m.status() != Status.APPLICABLE)
                    ? IndicatorApplicabilityResponse.OverallStatus.PARTIAL
                    : IndicatorApplicabilityResponse.OverallStatus.COMPLETE);
        return new IndicatorApplicabilityResponse(factoryId, basis, overall, products, aggregate);
    }

    private ProductApplicability product(String factoryId, ProductSpec spec) {
        ProductProfile profile = new ProductProfile(trim(spec.unit()), trim(spec.inputUnit()),
                spec.gramsPerUnit(), spec.multiOutput());
        List<MetricApplicability> metrics = METRIC_CODES.stream()
                .map(code -> assessMetric(factoryId, profile, code)).toList();
        return new ProductApplicability(spec.productId(), spec.productName(), profile, metrics);
    }

    private MetricApplicability assessMetric(String factoryId, ProductProfile profile, String code) {
        List<String> facts = new ArrayList<>();
        // 联产已接入正式结单输出行与共享计划投入池。配置层只判断口径是否可
        // 换算；结算分摊、逐产出质量事实和成本完整性仍由运行时 fail-closed。
        if (Boolean.TRUE.equals(profile.multiOutput())) {
            facts.add("运行时需存在正式结单输出行、共享计划投入及逐产出质量事实");
        }
        if (profile.multiOutput() == null) {
            facts.add("确认是否存在多产物或副产品");
        }
        CanonicalUnit unit = profile.unit() == null ? null
                : units.describe(factoryId, profile.unit()).orElse(null);
        if (unit == null) {
            return metric(code, Status.NEEDS_CONFIRMATION, "产出单位未被单位契约识别", append(facts, "确认产出单位"));
        }
        if (unit.dimension() != UnitDimension.MASS && unit.dimension() != UnitDimension.COUNT
                && unit.dimension() != UnitDimension.PACKAGE) {
            return metric(code, Status.NOT_APPLICABLE, "当前四项实际流指标仅支持质量或可明确换算的计数/包装口径", facts);
        }
        CanonicalUnit input = profile.inputUnit() == null ? null
                : units.describe(factoryId, profile.inputUnit()).orElse(null);
        if (input == null) {
            return metric(code, Status.NEEDS_CONFIRMATION, "投入单位未被单位契约识别，无法证明质量口径", append(facts, "确认投入单位"));
        }
        if (input.dimension() != UnitDimension.MASS) {
            return metric(code, Status.NOT_APPLICABLE, "投入单位不是质量单位，不能安全计算质量指标", facts);
        }
        if ("箱".equals(unit.code())) {
            return metric(code, Status.NEEDS_CONFIRMATION, "箱装口径需要明确每箱基单位和箱规，不能直接套用每盒净重",
                    append(facts, "包装基单位及每箱数量"));
        }
        if (unit.dimension() != UnitDimension.MASS && profile.gramsPerUnit() == null) {
            return metric(code, Status.NEEDS_CONFIRMATION, "计数/包装产出缺少正数标准净重，无法换算质量", append(facts, "每单位净重(克)"));
        }
        if (profile.gramsPerUnit() != null && profile.gramsPerUnit().signum() <= 0) {
            return metric(code, Status.NEEDS_CONFIRMATION, "标准净重必须为正数", append(facts, "正数标准净重(克)"));
        }
        if (profile.multiOutput() == null) {
            return metric(code, Status.NEEDS_CONFIRMATION, "单位口径可用，但尚未确认是否存在多产物", facts);
        }
        return metric(code, Status.APPLICABLE,
                Boolean.TRUE.equals(profile.multiOutput())
                        ? "联产质量口径可换算；运行时需通过正式结单分摊与逐产出质量校验"
                        : "配置口径满足质量指标换算条件；运行数据仍需实际报工完成",
                append(facts, "已完成批次与结算数据"));
    }

    private static MetricApplicability metric(String code, Status status, String reason, List<String> facts) {
        return new MetricApplicability(code, metricName(code), status, reason, facts);
    }

    private static String metricName(String code) {
        return switch (code) {
            case "FACTORY_LU_YIELD_RATE" -> "实际出成率";
            case "FACTORY_GOOD_OUTPUT_RATE" -> "生产良品率";
            case "FACTORY_LU_UNIT_COST" -> "单位成本";
            case "FACTORY_ACTUAL_TOTAL_COST" -> "实际总成本";
            default -> code;
        };
    }

    private static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values); result.add(value); return result;
    }

    private static List<MetricApplicability> aggregate(List<ProductApplicability> products) {
        return METRIC_CODES.stream().map(code -> {
            List<MetricApplicability> rows = products.stream().flatMap(p -> p.metrics().stream())
                    .filter(m -> m.code().equals(code)).toList();
            if (rows.isEmpty()) return metric(code, Status.NEEDS_CONFIRMATION, "未提供产品配置", List.of("产品配置"));
            if (rows.stream().anyMatch(m -> m.status() == Status.BLOCKED)) return metric(code, Status.BLOCKED, "至少一个产品的指标配置明确不支持", List.of("产品指标配置"));
            if (rows.stream().anyMatch(m -> m.status() == Status.NEEDS_CONFIRMATION)) return metric(code, Status.NEEDS_CONFIRMATION, "部分产品缺少必要配置或确认", List.of("逐产品配置完整性"));
            if (rows.stream().allMatch(m -> m.status() == Status.NOT_APPLICABLE)) return metric(code, Status.NOT_APPLICABLE, "所有产品均不满足当前质量口径", List.of("质量单位配置"));
            if (rows.stream().anyMatch(m -> m.status() == Status.NOT_APPLICABLE)) return metric(code, Status.NEEDS_CONFIRMATION, "部分产品不适用，不能宣称全工厂指标可用", List.of("逐产品质量口径"));
            return metric(code, Status.APPLICABLE, "全部产品配置满足质量口径；运行数据仍需实际报工完成", List.of("已完成批次与结算数据"));
        }).toList();
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
