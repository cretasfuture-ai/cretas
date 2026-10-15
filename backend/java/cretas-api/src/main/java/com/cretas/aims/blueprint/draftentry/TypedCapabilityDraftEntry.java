package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Converts the JSON body into the capability's own request record — always failing on unknown fields
 * and on an explicit null for a primitive field, whatever the injected mapper's global settings —
 * then hands the typed request to the subclass.
 */
public abstract class TypedCapabilityDraftEntry<Q> implements CapabilityDraftEntry {

    private final ObjectMapper mapper;
    private final Class<Q> requestType;

    protected TypedCapabilityDraftEntry(ObjectMapper mapper, Class<Q> requestType) {
        this.mapper = mapper;
        this.requestType = requestType;
    }

    @Override
    public final Object preview(JsonNode request) {
        return preview(read(unsignedForPreview(request)));
    }

    /**
     * {@code operatorId} is server-owned: materialize gets it re-signed by the controller, preview has
     * no operator yet. With FAIL_ON_NULL_FOR_PRIMITIVES an absent primitive {@code operatorId} would be
     * refused as an unfilled input, so preview fills in the commands' "not signed" value 0 — only
     * when absent; an explicit value is left for the command to judge.
     */
    private static JsonNode unsignedForPreview(JsonNode request) {
        if (request instanceof com.fasterxml.jackson.databind.node.ObjectNode object && !object.has("operatorId")) {
            com.fasterxml.jackson.databind.node.ObjectNode copy = object.deepCopy();
            copy.put("operatorId", 0L);
            return copy;
        }
        return request;
    }

    @Override
    public final Object materialize(JsonNode signedRequest, DraftOperator operator) {
        return materialize(read(signedRequest), operator);
    }

    protected abstract Object preview(Q request);

    protected abstract Object materialize(Q request, DraftOperator operator);

    private Q read(JsonNode body) {
        try {
            // 显式打开: Spring Boot 注入的 mapper 默认关掉 FAIL_ON_UNKNOWN_PROPERTIES, 请求类型上的
            // @JsonIgnoreProperties(ignoreUnknown = false) 等于默认值, 盖不过全局开关 —— 不打开的话
            // 生产上带 "publish":true 之类未知字段的 body 会被静默接受(测试用 new ObjectMapper() 测不出来)。
            //
            // FAIL_ON_NULL_FOR_PRIMITIVES 同理: 草稿提案把用户还没填的字段发成显式 null, 落到 boolean/int
            // 这类基本类型上默认会被静默转成 false/0 —— 「是否副产物」没填就成了「否」, 命令照样 READY。
            return mapper.readerFor(requestType)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .readValue(body);
        } catch (UnrecognizedPropertyException e) {
            // Before MismatchedInputException: it is a subclass.
            String field = path(e);
            if (e.getPropertyName() != null && !field.equals(e.getPropertyName()) && !field.endsWith("." + e.getPropertyName())) {
                field = field.isEmpty() ? e.getPropertyName() : field + "." + e.getPropertyName();
            }
            throw new BusinessException(400, "请求里有不认识的字段 " + field + "，请删掉它后重试")
                    .withCode("BLUEPRINT_DRAFT_UNKNOWN_FIELD");
        } catch (MismatchedInputException e) {
            String field = path(e);
            if (e.getTargetType() != null && e.getTargetType().isPrimitive() && String.valueOf(e.getOriginalMessage()).contains("null")) {
                throw new BusinessException(400, "还有必填项没填：" + field + "，请补齐后再预览")
                        .withCode("BLUEPRINT_DRAFT_REQUIRED_INPUT_MISSING");
            }
            if (!field.isEmpty()) {
                // An enum value the command does not know, a string where a number goes, a bad date …
                // Points at the field the user filled; the value itself is not echoed back.
                throw new BusinessException(400, "字段 " + field + " 的值不合法，请按提示重新填写")
                        .withCode("BLUEPRINT_DRAFT_INPUT_INVALID");
            }
            throw invalidBody();
        } catch (Exception e) {
            throw invalidBody();
        }
    }

    /** Nothing to point at: the body as a whole is not a request of this capability. */
    private static BusinessException invalidBody() {
        return new BusinessException(400, "建厂草稿请求的格式不对，无法识别为这类草稿的请求")
                .withCode("BLUEPRINT_DRAFT_BODY_INVALID");
    }

    /** The same path syntax as a proposal's {@code requiredInputs}, e.g. {@code positions[0].healthCertificateRequired}. */
    private static String path(MismatchedInputException e) {
        StringBuilder path = new StringBuilder();
        for (var reference : e.getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) path.append('.');
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
