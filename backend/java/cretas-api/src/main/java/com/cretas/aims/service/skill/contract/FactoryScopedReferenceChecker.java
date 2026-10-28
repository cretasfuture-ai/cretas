package com.cretas.aims.service.skill.contract;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Whether a parameter value names a record of the executing factory. The record kinds and their
 * tables are fixed here in code (never taken from a contract or a request), so a contract can only
 * pick a kind, not a table or a column.
 */
@Component
@RequiredArgsConstructor
public class FactoryScopedReferenceChecker {

    /** kind → table; every table has an {@code id} and a {@code factory_id} column. */
    public static final Map<String, String> KINDS = Map.of(
            "MATERIAL_BATCH", "material_batches",
            "PRODUCTION_BATCH", "production_batches",
            "RAW_MATERIAL_TYPE", "raw_material_types",
            "PRODUCT_TYPE", "product_types",
            "SUPPLIER", "suppliers",
            "CUSTOMER", "customers");

    private final JdbcTemplate jdbcTemplate;

    /** @return true only when exactly one record of this kind with this id belongs to the factory */
    public boolean belongsToFactory(String kind, String factoryId, String id) {
        String table = KINDS.get(kind);
        if (table == null || factoryId == null || factoryId.isBlank() || id == null || id.isBlank()) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE CAST(id AS TEXT) = ? AND factory_id = ?",
                Integer.class, id, factoryId);
        return count != null && count == 1;
    }
}
