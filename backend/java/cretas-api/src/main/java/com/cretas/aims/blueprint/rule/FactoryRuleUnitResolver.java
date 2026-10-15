package com.cretas.aims.blueprint.rule;

import com.cretas.aims.service.unit.UnitDimension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** Read-only unit boundary used by rule validation and isolated simulation. */
public interface FactoryRuleUnitResolver {

    String catalogDigest(String factoryId);

    Optional<UnitDescriptor> describe(String factoryId, String unit);

    default boolean sameQuantityUnit(String factoryId, String left, String right) {
        var a = describe(factoryId, left);
        var b = describe(factoryId, right);
        return a.isPresent() && b.isPresent() && a.get().code().equals(b.get().code());
    }

    Conversion convert(String factoryId, BigDecimal value, String fromUnit, String toUnit, Instant at);

    record UnitDescriptor(String code, UnitDimension dimension) {
    }

    record Conversion(boolean succeeded, BigDecimal value, String unit, String errorCode, String message) {
        public static Conversion success(BigDecimal value, String unit) {
            return new Conversion(true, value, unit, "", "");
        }

        public static Conversion failure(String code, String message) {
            return new Conversion(false, null, "", code, message);
        }
    }
}
