package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.report.ShippedRevenueBasis;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/** The shipped-profit policy shares the existing versioned factory settings row. */
@Slf4j
public final class ShippedRevenueBasisSettings {
    public static final String KEY = "shippedRevenueBasis";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ShippedRevenueBasisSettings() {}

    public static ShippedRevenueBasis read(String factoryId, String productionSettings) {
        ShippedRevenueBasis basis = storedBasis(factoryId, productionSettings);
        return basis == null ? ShippedRevenueBasis.DEFAULT : basis;
    }

    public static void write(FactorySettings settings, ShippedRevenueBasis basis) {
        settings.setProductionSettings(withBasis(settings.getProductionSettings(), basis));
    }

    /** An older settings client can replace its known fields without clearing this policy. */
    public static String preserveOnUpdate(String factoryId, String previous, String incoming) {
        ShippedRevenueBasis basis = storedBasis(factoryId, previous);
        return basis == null ? incoming : withBasis(incoming, basis);
    }

    public static ShippedRevenueBasis validate(Object value) {
        ShippedRevenueBasis basis = knownBasis(value);
        if (basis != null) return basis;
        throw new BusinessException(400, "发货收入口径必须选 GROSS（折扣前）或 NET（扣折扣与退货后）");
    }

    private static ShippedRevenueBasis knownBasis(Object value) {
        if (value instanceof String name) {
            for (var basis : ShippedRevenueBasis.values()) {
                if (basis.name().equals(name)) return basis;
            }
        }
        return null;
    }

    private static ShippedRevenueBasis storedBasis(String factoryId, String settings) {
        ObjectNode object = parse(settings);
        if (object == null) {
            log.warn("Ignoring malformed production_settings for shipped revenue basis: factoryId={}", factoryId);
            return null;
        }
        JsonNode value = object.get(KEY);
        if (value == null || value.isNull()) return null;
        ShippedRevenueBasis basis = knownBasis(value.textValue());
        if (basis == null) {
            log.warn("Ignoring unknown shippedRevenueBasis in production_settings: factoryId={}", factoryId);
        }
        return basis;
    }

    private static String withBasis(String settings, ShippedRevenueBasis basis) {
        ObjectNode object = parse(settings);
        if (object == null) {
            throw new BusinessException(409, "工厂生产设置格式异常")
                    .withHint("请在生产设置中重新保存后再修改发货收入口径");
        }
        return object.put(KEY, basis.name()).toString();
    }

    private static ObjectNode parse(String settings) {
        if (settings == null || settings.isBlank()) return JSON.createObjectNode();
        try {
            if (JSON.readTree(settings) instanceof ObjectNode object) return object;
        } catch (JsonProcessingException e) {
            return null;
        }
        return null;
    }
}
