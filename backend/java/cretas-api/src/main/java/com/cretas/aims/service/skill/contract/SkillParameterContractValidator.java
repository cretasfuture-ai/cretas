package com.cretas.aims.service.skill.contract;

import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.service.skill.contract.SkillParameterContract.ParamSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side check of caller parameters against a Skill's contract, before any tool runs.
 * Refuses unknown and identity keys, missing required values, wrong types, out-of-bounds values
 * and record ids that are not the executing factory's. Never coerces a refused value into an
 * accepted one; numeric strings are accepted only when they parse exactly.
 */
@Component
@RequiredArgsConstructor
public class SkillParameterContractValidator {

    /** Keys a contract can never declare and a caller can never pass: identity, confirmation, session. */
    static final Set<String> RESERVED_KEYS;

    static {
        java.util.HashSet<String> keys = new java.util.HashSet<>(AutonomousToolPolicy.IDENTITY_KEYS);
        keys.addAll(Set.of("sessionId", "session_id", "userQuery", "confirmed", "confirmationToken",
                "previewToken", "idempotencyKey"));
        RESERVED_KEYS = Set.copyOf(keys);
    }

    private final FactoryScopedReferenceChecker references;

    public record Validation(Map<String, Object> accepted, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public static boolean isReservedKey(String key) {
        return RESERVED_KEYS.contains(key);
    }

    public Validation validate(SkillParameterContract contract, String factoryId, Map<String, Object> params) {
        Map<String, Object> input = params == null ? Map.of() : params;
        List<String> errors = new ArrayList<>();
        Map<String, Object> accepted = new LinkedHashMap<>();
        for (String key : input.keySet()) {
            if (isReservedKey(key)) {
                errors.add("不接受身份参数：" + key);
            } else if (contract.find(key).isEmpty()) {
                errors.add("参数不在契约内：" + key);
            }
        }
        for (ParamSpec spec : contract.params()) {
            Object value = input.get(spec.name());
            if (value == null) {
                if (spec.required()) {
                    errors.add("缺少必填参数：" + spec.name());
                }
                continue;
            }
            String problem = check(spec, value, factoryId);
            if (problem != null) {
                errors.add(problem);
            } else {
                accepted.put(spec.name(), normalize(spec, value));
            }
        }
        return new Validation(Map.copyOf(accepted), List.copyOf(errors));
    }

    private String check(ParamSpec spec, Object value, String factoryId) {
        String name = spec.name();
        switch (spec.type()) {
            case STRING, DATE, ENUM -> {
                if (!(value instanceof String text)) {
                    return "参数「" + name + "」必须是文字";
                }
                int limit = spec.maxLength() != null ? spec.maxLength() : SkillParameterContract.DEFAULT_MAX_TEXT_LENGTH;
                if (text.length() > limit) {
                    return "参数「" + name + "」超过 " + limit + " 个字符";
                }
                if (spec.type() == SkillParameterContract.ParamType.DATE) {
                    try {
                        LocalDate.parse(text);
                    } catch (DateTimeParseException invalid) {
                        return "参数「" + name + "」必须是日期（如 2026-10-28）";
                    }
                }
                if (spec.type() == SkillParameterContract.ParamType.ENUM && !spec.allowedValues().contains(text)) {
                    return "参数「" + name + "」只能是：" + String.join("、", spec.allowedValues());
                }
                if (!spec.allowedValues().isEmpty() && !spec.allowedValues().contains(text)) {
                    return "参数「" + name + "」只能是：" + String.join("、", spec.allowedValues());
                }
            }
            case INTEGER, NUMBER -> {
                BigDecimal number = asNumber(value);
                if (number == null) {
                    return "参数「" + name + "」必须是数字";
                }
                if (spec.type() == SkillParameterContract.ParamType.INTEGER && number.stripTrailingZeros().scale() > 0) {
                    return "参数「" + name + "」必须是整数";
                }
                if (spec.min() != null && number.compareTo(BigDecimal.valueOf(spec.min())) < 0) {
                    return "参数「" + name + "」不能小于 " + plain(spec.min());
                }
                if (spec.max() != null && number.compareTo(BigDecimal.valueOf(spec.max())) > 0) {
                    return "参数「" + name + "」不能大于 " + plain(spec.max());
                }
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    return "参数「" + name + "」必须是 true 或 false";
                }
            }
            default -> {
                return "参数「" + name + "」类型不支持";
            }
        }
        if (spec.factoryScopedRef() != null
                && !references.belongsToFactory(spec.factoryScopedRef(), factoryId, String.valueOf(normalize(spec, value)))) {
            return "参数「" + name + "」指向的记录不属于本工厂或不存在";
        }
        return null;
    }

    private static Object normalize(ParamSpec spec, Object value) {
        if (spec.type() == SkillParameterContract.ParamType.INTEGER) {
            return asNumber(value).longValueExact();
        }
        if (spec.type() == SkillParameterContract.ParamType.NUMBER) {
            return asNumber(value);
        }
        return value;
    }

    private static BigDecimal asNumber(Object value) {
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException notFinite) {
                return null;
            }
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException notNumber) {
                return null;
            }
        }
        return null;
    }

    private static String plain(Double bound) {
        return BigDecimal.valueOf(bound).stripTrailingZeros().toPlainString();
    }
}
