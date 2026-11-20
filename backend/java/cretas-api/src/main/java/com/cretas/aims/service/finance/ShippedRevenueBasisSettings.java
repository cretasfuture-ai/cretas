package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.report.ShippedRevenueBasis;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The shipped-profit policy shares the existing versioned factory settings row. */
public final class ShippedRevenueBasisSettings {
    public static final String KEY = "shippedRevenueBasis";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private ShippedRevenueBasisSettings() {}

    public static ShippedRevenueBasis read(String productionSettings) {
        JsonNode value = parse(productionSettings).get(KEY);
        return value == null || value.isNull() ? ShippedRevenueBasis.DEFAULT : validate(value.asText());
    }

    public static void write(FactorySettings settings, ShippedRevenueBasis basis) {
        settings.setProductionSettings(withBasis(settings.getProductionSettings(), basis));
    }

    /** An older settings client can replace its known fields without clearing this policy. */
    public static String preserveOnUpdate(String previous, String incoming) {
        JsonNode basis = parse(previous).get(KEY);
        return basis == null || basis.isNull() ? incoming : withBasis(incoming, validate(basis.asText()));
    }

    public static ShippedRevenueBasis validate(Object value) {
        if (value instanceof String name) {
            for (var basis : ShippedRevenueBasis.values()) {
                if (basis.name().equals(name)) return basis;
            }
        }
        throw new BusinessException(400, "发货收入口径必须选 GROSS（折扣前）或 NET（扣折扣与退货后）");
    }

    private static String withBasis(String settings, ShippedRevenueBasis basis) {
        return parse(settings).put(KEY, basis.name()).toString();
    }

    private static ObjectNode parse(String settings) {
        if (settings == null || settings.isBlank()) return JSON.createObjectNode();
        try {
            if (JSON.readTree(settings) instanceof ObjectNode object) return object;
        } catch (JsonProcessingException e) {
            throw invalidSettings();
        }
        throw invalidSettings();
    }

    private static BusinessException invalidSettings() {
        return new BusinessException(409, "工厂生产设置格式异常，请修复设置后重新查询发货收入口径");
    }
}
