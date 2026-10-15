package com.cretas.aims.blueprint.rule;

import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitUsageScene;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Production-catalog read adapter. It does not write units or product conversions. */
public final class UnitContractRuleUnitResolver implements FactoryRuleUnitResolver {

    private final UnitContractService unitContractService;

    public UnitContractRuleUnitResolver(UnitContractService unitContractService) {
        this.unitContractService = unitContractService;
    }

    @Override
    public String catalogDigest(String factoryId) {
        String canonical = unitContractService.catalog(factoryId).stream()
                .sorted(Comparator.comparing(item -> Objects.toString(item.code(), "")))
                .map(item -> String.join("|",
                        Objects.toString(item.code(), ""),
                        Objects.toString(item.dimension(), "UNKNOWN"),
                        Objects.toString(item.baseCode(), ""),
                        item.factorToBase() == null ? "" : item.factorToBase().stripTrailingZeros().toPlainString(),
                        Boolean.toString(item.active())))
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @Override
    public Optional<UnitDescriptor> describe(String factoryId, String unit) {
        return unitContractService.describe(factoryId, unit)
                .filter(item -> item.active())
                .map(item -> new UnitDescriptor(item.code(), item.dimension()));
    }

    @Override
    public Conversion convert(
            String factoryId, BigDecimal value, String fromUnit, String toUnit, Instant at) {
        var result = unitContractService.convert(value, new UnitConversionContext(
                factoryId, null, fromUnit, toUnit, LocalDateTime.ofInstant(at, ZoneOffset.UTC), UnitUsageScene.QUALITY,
                12, RoundingMode.HALF_UP));
        if (!result.succeeded()) {
            return Conversion.failure(result.status().name(), result.message());
        }
        return Conversion.success(result.quantity(), result.toUnit());
    }
}
