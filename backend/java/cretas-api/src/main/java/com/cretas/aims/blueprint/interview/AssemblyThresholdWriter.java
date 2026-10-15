package com.cretas.aims.blueprint.interview;

import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.entity.canvas.ThresholdValueType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.service.canvas.ThresholdKeyCatalog;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Assembly adapter over the existing threshold schema; never changes global defaults. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true")
public class AssemblyThresholdWriter {
    private final FactoryThresholdRepository rows;
    private final ThresholdResolverService resolver;

    public void validate(String factoryId, String key, String value) {
        if (factoryId == null || factoryId.isBlank() || "*".equals(factoryId)) {
            throw new BusinessException(403, "建厂阈值必须限定到目标工厂，不能修改全局标准");
        }
        ThresholdKeyCatalog.validateAndCategorize(key);
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new BusinessException(400, "阈值必须填写，且不能超过100字");
        }
        ThresholdValueType type = valueType(factoryId, key, value);
        try {
            switch (type) {
                case INTEGER -> Integer.parseInt(value);
                case DECIMAL, DOUBLE -> new BigDecimal(value);
                case STRING -> { }
            }
        } catch (NumberFormatException failure) {
            throw new BusinessException(400, "阈值“" + key + "”不符合已登记的数字类型");
        }
    }

    private ThresholdValueType valueType(String factoryId, String key, String value) {
        return rows.findByFactoryIdAndThresholdKey(factoryId, key)
                .or(() -> rows.findByFactoryIdAndThresholdKey("*", key))
                .map(FactoryThreshold::getValueType)
                .orElseThrow(() -> new BusinessException(409,
                        "标准“" + key + "”尚未登记值类型，不能通过建厂猜测类型；请先在正式阈值中心登记"));
    }

    @Transactional
    public void upsert(String factoryId, String key, String value) {
        validate(factoryId, key, value);
        FactoryThreshold row = rows.findByFactoryIdAndThresholdKey(factoryId, key).orElseGet(() -> {
            String initialDefault = rows.findByFactoryIdAndThresholdKey("*", key)
                    .map(FactoryThreshold::getDefaultValue).filter(v -> !v.isBlank()).orElse(value);
            return FactoryThreshold.builder().factoryId(factoryId).thresholdKey(key)
                    .category(ThresholdKeyCatalog.categoryOf(key)).valueType(valueType(factoryId, key, value))
                    .defaultValue(initialDefault).build();
        });
        row.setThresholdValue(value);
        rows.saveAndFlush(row);
        resolver.invalidate(factoryId, key);
    }
}
