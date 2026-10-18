package com.cretas.aims.service.indicator;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorComputation;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.indicator.IndicatorComputationRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategyRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tenant-local definitions only. Business values remain owned by the existing metric strategies. */
@Service
@RequiredArgsConstructor
public class FactoryIndicatorProvisioner {
    private final FactoryRepository factoryRepository;
    private final IndicatorRepository indicatorRepository;
    private final IndicatorComputationRepository computationRepository;
    private final IndicatorComputationStrategyRegistry strategyRegistry;

    private record Definition(String code, String name, String unit, String description) { }

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("FACTORY_LU_YIELD_RATE", "实际出成率", "%",
                    "合格成品净重÷实际首道投料净重；按已完工且已结单的生产单统计，包装净含量取生产计划固定快照。"),
            new Definition("FACTORY_GOOD_OUTPUT_RATE", "生产良品率（按净重）", "%",
                    "合格成品净重÷（合格净重＋明确清点的不良品净重）；未清点不按零不良计算。"),
            new Definition("FACTORY_LU_UNIT_COST", "完整单位成本（每公斤合格品）", "元/kg",
                    "完整实际归集成本÷合格成品净重；缺价、缺工时或费用未录时不发布完整成本。"),
            new Definition("FACTORY_ACTUAL_TOTAL_COST", "完整生产成本", "元",
                    "按完工期间累计完整实际归集成本；部分成本、参考成本不伪装为完整成本。"));

    public static List<String> defaultNames() {
        return DEFINITIONS.stream().map(Definition::name).toList();
    }

    /** Exact labels from the interview and existing tenant codes share the preview/apply contract. */
    public static String optOutCode(String value) {
        if (value == null || value.isBlank() || "都要看".equals(value.trim())) {
            return null;
        }
        String candidate = value.trim();
        return DEFINITIONS.stream().filter(definition -> definition.name().equals(candidate))
                .map(Definition::code).findFirst().orElse(candidate);
    }

    /**
     * Joins the formal creation/apply transaction. The existing factory lock serializes retries
     * across JVMs. Existing tenant definitions (including inactive ones) are never overwritten.
     * Any persistence/strategy failure rolls back the caller's complete operation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int provision(String factoryId) {
        Factory factory = factoryRepository.findByIdForUpdate(factoryId)
                .orElseThrow(() -> new IllegalStateException("工厂不存在，无法初始化经营指标"));
        if (factory.getType() != FactoryType.FACTORY) {
            return 0;
        }
        int created = 0;
        for (int order = 0; order < DEFINITIONS.size(); order++) {
            Definition definition = DEFINITIONS.get(order);
            if (indicatorRepository.existsIncludingDeleted(factoryId, definition.code())) {
                continue;
            }
            if (strategyRegistry.find(definition.code()).isEmpty()) {
                throw new IllegalStateException("经营指标计算能力未注册：" + definition.code());
            }
            Indicator indicator = new Indicator();
            indicator.setId(UUID.randomUUID().toString());
            indicator.setFactoryId(factoryId);
            indicator.setCode(definition.code());
            indicator.setName(definition.name());
            indicator.setDescription(definition.description());
            indicator.setCategory("FACTORY");
            indicator.setUnit(definition.unit());
            indicator.setComputeStrategy("CACHED");
            indicator.setCacheTtlSeconds(300);
            indicator.setDisplayOrder(order + 1);
            indicator.setConfig(Map.of("provisioningSource", "factory-actual-metrics-v1"));
            indicatorRepository.save(indicator);

            IndicatorComputation computation = new IndicatorComputation();
            computation.setIndicatorId(indicator.getId());
            computation.setComputeType("JPA_AGGREGATE");
            computation.setComputeSource(definition.code());
            computationRepository.save(computation);
            created++;
        }
        return created;
    }
}
