package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production entry into module DRAFT commands, one {@link CapabilityDraftEntry} per opened
 * capability. Everything every capability needs is done here, once:
 * <ul>
 *   <li>the body's factory and blueprint must match the URL;</li>
 *   <li>the operator is built on the server by {@link BlueprintDraftOperatorResolver} (design §17.37),
 *       and only the factory side may confirm (§9.2);</li>
 *   <li>the body's {@code operatorId} may only be empty or the signed-in user, and is then
 *       overwritten with the signed-in user before the command sees it;</li>
 *   <li>readback and recovery require the same operator check as materialize.</li>
 * </ul>
 * The URL stays under {@code /ai-initialize}, so {@code JwtAuthInterceptor} already limits a
 * {@code factory_super_admin} to its own factory; the resolver compares the factory again.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@RestController
@RequestMapping("/api/platform/factories/{factoryId}/ai-initialize/blueprints/{blueprintId}/drafts/{capabilityId}")
public class BlueprintDraftController {

    private final Map<String, CapabilityDraftEntry> entries;
    private final BlueprintDraftOperatorResolver operators;

    public BlueprintDraftController(List<CapabilityDraftEntry> entries, BlueprintDraftOperatorResolver operators) {
        Map<String, CapabilityDraftEntry> indexed = new LinkedHashMap<>();
        for (CapabilityDraftEntry entry : entries) {
            if (indexed.putIfAbsent(entry.capabilityId(), entry) != null) {
                throw new IllegalStateException("Duplicate capability draft entry: " + entry.capabilityId());
            }
        }
        this.entries = Map.copyOf(indexed);
        this.operators = operators;
    }

    /** The capabilities whose DRAFT entry is open (for contract tests and diagnostics). */
    public java.util.Set<String> openCapabilities() {
        return entries.keySet();
    }

    @PostMapping("/preview")
    @Operation(summary = "预览某项能力的建厂草稿(只读)")
    public ApiResponse<Object> preview(@PathVariable String factoryId, @PathVariable String blueprintId,
                                       @PathVariable String capabilityId, @RequestBody JsonNode body) {
        CapabilityDraftEntry entry = require(capabilityId);
        requireSameScope(entry, factoryId, blueprintId, body);
        return ApiResponse.success(entry.preview(body));
    }

    @PostMapping("/materialize")
    @Operation(summary = "确认写入某项能力的建厂草稿(只写 DRAFT, 不发布不激活)")
    public ApiResponse<Object> materialize(@PathVariable String factoryId, @PathVariable String blueprintId,
                                           @PathVariable String capabilityId, @RequestBody JsonNode body,
                                           HttpServletRequest httpRequest) {
        CapabilityDraftEntry entry = require(capabilityId);
        requireSameScope(entry, factoryId, blueprintId, body);
        DraftOperator operator = operators.resolveDraft(httpRequest, factoryId, entry.requiredPermission());
        long declared = body.path("operatorId").asLong(0);
        if (declared != 0 && declared != operator.userId()) {
            throw fail(400, "operatorId 只能是当前登录人(可留空, 由服务端填入)", "BLUEPRINT_DRAFT_OPERATOR_SPOOFED");
        }
        ObjectNode signed = ((ObjectNode) body).deepCopy();
        signed.put("operatorId", operator.userId());
        return ApiResponse.success(entry.materialize(signed, operator));
    }

    @GetMapping("/{draftId}")
    @Operation(summary = "回读某项能力的建厂草稿")
    public ApiResponse<Object> readBack(@PathVariable String factoryId, @PathVariable String blueprintId,
                                        @PathVariable String capabilityId, @PathVariable String draftId,
                                        HttpServletRequest httpRequest) {
        CapabilityDraftEntry entry = require(capabilityId);
        operators.resolveDraft(httpRequest, factoryId, entry.requiredPermission());
        return ApiResponse.success(entry.readBack(factoryId, draftId));
    }

    @GetMapping("/{draftId}/recovery-plan")
    @Operation(summary = "某项能力建厂草稿的恢复方案(只读)")
    public ApiResponse<Object> recoveryPlan(@PathVariable String factoryId, @PathVariable String blueprintId,
                                            @PathVariable String capabilityId, @PathVariable String draftId,
                                            @RequestParam String expectedDesiredDigest,
                                            HttpServletRequest httpRequest) {
        CapabilityDraftEntry entry = require(capabilityId);
        operators.resolveDraft(httpRequest, factoryId, entry.requiredPermission());
        return ApiResponse.success(entry.recoveryPlan(factoryId, draftId, expectedDesiredDigest));
    }

    private CapabilityDraftEntry require(String capabilityId) {
        CapabilityDraftEntry entry = entries.get(capabilityId);
        if (entry == null) {
            throw fail(404, "这项能力的建厂草稿入口还没有开放", "BLUEPRINT_DRAFT_CAPABILITY_NOT_OPEN");
        }
        return entry;
    }

    private static void requireSameScope(CapabilityDraftEntry entry, String factoryId, String blueprintId, JsonNode body) {
        if (!(body instanceof ObjectNode) || !factoryId.equals(entry.declaredFactoryId(body))) {
            throw fail(400, "请求里的工厂或蓝图与地址不一致", "BLUEPRINT_DRAFT_SCOPE_MISMATCH");
        }
        String declaredBlueprint = entry.declaredBlueprintId(body);
        if (declaredBlueprint == null) {
            entry.requireBlueprintContext(factoryId, blueprintId);
        } else if (!blueprintId.equals(declaredBlueprint)) {
            throw fail(400, "请求里的工厂或蓝图与地址不一致", "BLUEPRINT_DRAFT_SCOPE_MISMATCH");
        }
    }

    private static BusinessException fail(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code);
    }
}
