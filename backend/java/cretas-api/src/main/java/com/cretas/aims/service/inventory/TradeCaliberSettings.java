package com.cretas.aims.service.inventory;

import com.cretas.aims.repository.FactorySettingsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Trade policies share the versioned factory_settings.production_settings record. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeCaliberSettings {
    public static final String LEDGER_KEY = "counterpartyLedgerScopeMode";
    public static final String PURCHASE_PRICE_KEY = "purchasePriceSourceMode";
    public static final String CUSTOMER_AVERAGE_KEY = "customerTradedAverageMode";

    public enum LedgerMode { SCOPED, HIDDEN }
    public enum PurchasePriceMode { SERVER_DETERMINED, CLIENT_PRESERVED }
    public enum CustomerAverageMode { QUANTITY_WEIGHTED, SIMPLE }

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final FactorySettingsRepository repository;

    public LedgerMode ledgerMode(String factoryId) {
        return read(factoryId, LEDGER_KEY, LedgerMode.SCOPED);
    }

    public PurchasePriceMode purchasePriceMode(String factoryId) {
        return read(factoryId, PURCHASE_PRICE_KEY, PurchasePriceMode.SERVER_DETERMINED);
    }

    public CustomerAverageMode customerAverageMode(String factoryId) {
        return read(factoryId, CUSTOMER_AVERAGE_KEY, CustomerAverageMode.QUANTITY_WEIGHTED);
    }

    private <E extends Enum<E>> E read(String factoryId, String key, E defaultMode) {
        ObjectNode settings = object(repository.findProductionSettingsByFactoryId(factoryId));
        if (settings != null && settings.hasNonNull(key)) {
            String value = settings.get(key).textValue();
            for (E mode : defaultMode.getDeclaringClass().getEnumConstants()) {
                if (mode.name().equals(value)) return mode;
            }
            log.warn("Unknown trade policy in production_settings: factoryId={}, key={}", factoryId, key);
        } else if (settings == null) {
            log.warn("Malformed production_settings: factoryId={}, key={}", factoryId, key);
        }
        return defaultMode;
    }

    /** Older settings clients may update their own fields without clearing governed trade policies. */
    public static String preserveOnUpdate(String previous, String incoming) {
        ObjectNode prior = object(previous);
        ObjectNode updated = object(incoming);
        if (prior == null || updated == null) return incoming;
        boolean preserved = false;
        for (String key : new String[]{LEDGER_KEY, PURCHASE_PRICE_KEY, CUSTOMER_AVERAGE_KEY}) {
            if (prior.has(key)) {
                updated.set(key, prior.get(key));
                preserved = true;
            }
        }
        return preserved ? updated.toString() : incoming;
    }

    private static ObjectNode object(String value) {
        if (value == null || value.isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(value) instanceof ObjectNode node ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
