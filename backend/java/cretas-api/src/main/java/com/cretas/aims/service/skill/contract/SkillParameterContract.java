package com.cretas.aims.service.skill.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The parameters a reusable Skill accepts: names, types, bounds, required/optional and, for a
 * parameter that names a business record, which factory-scoped record kind it must be.
 *
 * <p>The contract is data the caller cannot widen at run time: every parameter not declared here is
 * refused, and identity keys (factory, user, role, permissions) can never be declared, because the
 * executing identity comes from the authenticated principal only. Validation happens on the server
 * before any tool runs ({@link SkillParameterContractValidator}).</p>
 */
public record SkillParameterContract(List<ParamSpec> params) {

    public static final String CONFIG_KEY = "parameterContract";
    static final int MAX_PARAMS = 30;
    static final int DEFAULT_MAX_TEXT_LENGTH = 200;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    public enum ParamType { STRING, INTEGER, NUMBER, BOOLEAN, DATE, ENUM }

    /**
     * @param factoryScopedRef when set, the value must be the id of a record of this kind in the
     *                         executing factory ({@link FactoryScopedReferenceChecker#KINDS})
     */
    public record ParamSpec(String name, ParamType type, boolean required, Double min, Double max,
            Integer maxLength, List<String> allowedValues, String factoryScopedRef) {
    }

    public SkillParameterContract {
        params = params == null ? List.of() : List.copyOf(params);
    }

    public Optional<ParamSpec> find(String name) {
        return params.stream().filter(spec -> spec.name().equals(name)).findFirst();
    }

    public Set<String> names() {
        Set<String> names = new HashSet<>();
        params.forEach(spec -> names.add(spec.name()));
        return names;
    }

    /**
     * Parses and checks the contract itself. A malformed contract is refused, never repaired:
     * unknown type, duplicate or identity name, min above max, an enum without values, an unknown
     * record kind.
     */
    public static SkillParameterContract parse(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("参数契约不能为空");
        }
        JsonNode list = node.isArray() ? node : node.get("params");
        if (list == null || !list.isArray()) {
            throw new IllegalArgumentException("参数契约必须是参数列表");
        }
        if (list.size() > MAX_PARAMS) {
            throw new IllegalArgumentException("参数契约最多 " + MAX_PARAMS + " 个参数");
        }
        List<ParamSpec> specs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : list) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("参数契约的每一项必须是对象");
            }
            String name = item.path("name").asText("");
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("参数名不合法：" + name);
            }
            if (SkillParameterContractValidator.isReservedKey(name)) {
                throw new IllegalArgumentException("参数契约不能声明身份参数：" + name);
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("参数重复：" + name);
            }
            ParamType type;
            try {
                type = ParamType.valueOf(item.path("type").asText("").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("参数「" + name + "」的类型不认识：" + item.path("type").asText(""));
            }
            Double min = number(item, "min", name);
            Double max = number(item, "max", name);
            if (min != null && max != null && min > max) {
                throw new IllegalArgumentException("参数「" + name + "」的下限大于上限");
            }
            if ((min != null || max != null) && type != ParamType.INTEGER && type != ParamType.NUMBER) {
                throw new IllegalArgumentException("参数「" + name + "」不是数字，不能设上下限");
            }
            Integer maxLength = null;
            if (item.hasNonNull("maxLength")) {
                if (!item.get("maxLength").canConvertToInt() || item.get("maxLength").asInt() <= 0) {
                    throw new IllegalArgumentException("参数「" + name + "」的 maxLength 必须是正整数");
                }
                maxLength = item.get("maxLength").asInt();
            }
            List<String> allowed = new ArrayList<>();
            if (item.has("allowedValues")) {
                if (!item.get("allowedValues").isArray()) {
                    throw new IllegalArgumentException("参数「" + name + "」的 allowedValues 必须是列表");
                }
                item.get("allowedValues").forEach(value -> allowed.add(value.asText()));
            }
            if (type == ParamType.ENUM && allowed.isEmpty()) {
                throw new IllegalArgumentException("枚举参数「" + name + "」必须列出可选值");
            }
            String ref = item.hasNonNull("factoryScopedRef") ? item.get("factoryScopedRef").asText() : null;
            if (ref != null && !FactoryScopedReferenceChecker.KINDS.containsKey(ref)) {
                throw new IllegalArgumentException("参数「" + name + "」引用的业务记录类型不支持：" + ref);
            }
            if (ref != null && type != ParamType.STRING && type != ParamType.INTEGER) {
                throw new IllegalArgumentException("参数「" + name + "」引用业务记录时类型只能是 STRING 或 INTEGER");
            }
            specs.add(new ParamSpec(name, type, item.path("required").asBoolean(false), min, max, maxLength,
                    List.copyOf(allowed), ref));
        }
        return new SkillParameterContract(specs);
    }

    /** Reads the contract a skill definition carries in its config, if it has one. */
    public static Optional<SkillParameterContract> fromConfig(Map<String, Object> config, ObjectMapper mapper) {
        if (config == null || !config.containsKey(CONFIG_KEY)) {
            return Optional.empty();
        }
        return Optional.of(parse(mapper.valueToTree(config.get(CONFIG_KEY))));
    }

    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode list = root.putArray("params");
        for (ParamSpec spec : params) {
            ObjectNode item = list.addObject();
            item.put("name", spec.name());
            item.put("type", spec.type().name());
            item.put("required", spec.required());
            if (spec.min() != null) item.put("min", spec.min());
            if (spec.max() != null) item.put("max", spec.max());
            if (spec.maxLength() != null) item.put("maxLength", spec.maxLength());
            if (!spec.allowedValues().isEmpty()) {
                ArrayNode values = item.putArray("allowedValues");
                spec.allowedValues().forEach(values::add);
            }
            if (spec.factoryScopedRef() != null) item.put("factoryScopedRef", spec.factoryScopedRef());
        }
        return root;
    }

    private static Double number(JsonNode item, String field, String name) {
        if (!item.hasNonNull(field)) {
            return null;
        }
        if (!item.get(field).isNumber()) {
            throw new IllegalArgumentException("参数「" + name + "」的 " + field + " 必须是数字");
        }
        return item.get(field).asDouble();
    }
}
