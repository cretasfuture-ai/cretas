package com.cretas.aims.service.intent;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.purchase.PurchaseGrantExecuteTool;
import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.execution.ToolPermissionChain;
import com.cretas.aims.service.execution.TrustedExecutionContext;
import com.cretas.aims.service.skill.SkillExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

/** Fixed USER/task/purpose adapters. No ambient transaction: refusal audit follows rollback. */
@Service
@RequiredArgsConstructor
public class PurchaseGrantCallerService {
    public enum Channel { TOOL, SKILL }
    private final ControlledPurchaseTaskExecutionService controlled;
    private final PurchaseGrantRefusalAuditService refusalAudit;
    private final ObjectProvider<ToolRegistry> tools;
    private final ObjectProvider<SkillExecutor> skills;
    private final ToolPermissionChain permissions;
    private final WriteGuardService writeGuard;
    private final ObjectMapper mapper;

    public ControlledPurchaseTaskExecutionService.Outcome execute(String factory, String grant, Long version, Channel channel) {
        AuthenticatedActor actor;
        var registry = tools.getObject();
        com.cretas.aims.ai.tool.ToolExecutor tool;
        Map<String, Object> params = new HashMap<>();
        try {
            actor = actor(factory);
            requireReference(grant, version);
            if (channel == null) throw new BusinessException(403, "采购授权调用来源无效");
            // Real persisted owner/task read BEFORE minting any process-local W0 authority.
            var owned = controlled.status(factory, grant);
            if (!grant.equals(owned.grantId())) throw new BusinessException(403, "采购授权归属不匹配");
            tool = registry.getExecutor(PurchaseGrantExecuteTool.NAME)
                    .filter(com.cretas.aims.ai.tool.ToolExecutor::isEnabled)
                    .filter(t -> PurchaseGrantExecuteTool.NAME.equals(t.getToolName()))
                    .filter(t -> t instanceof PurchaseGrantExecuteTool)
                    .orElseThrow(() -> new BusinessException(403, "采购授权工具未挂载或已停用"));
            if (!registry.isToolEnabledForFactory(factory, PurchaseGrantExecuteTool.NAME)) {
                throw new BusinessException(403, "采购授权工具已停用");
            }
            TrustedExecutionContext.enforcePrincipal(params, factory, actor.id(), actor.role());
            permissions.denyReason(tool, factory, actor.role(), PurchaseGrantExecuteTool.canonicalIntent(), params)
                    .ifPresent(reason -> { throw new BusinessException(403, reason); });
        } catch (BusinessException refused) {
            throw audited(factory, grant, refused);
        }
        params.put("grantId", grant);
        params.put("expectedVersion", version);
        // Parameters carry only task selection/CAS. Marker stays in context, never ToolCall JSON.
        Map<String, Object> trusted = writeGuard.withServerConfirmation(params);
        JsonNode result;
        try {
            // Reuse the existing guarded deterministic executor even for the fixed Tool adapter.
            // Do not add a direct ToolExecutor call, a generic dispatch cache or a retry lane.
            if (!writeGuard.isConfirmed(trusted)) throw new IllegalStateException("Missing server grant admission");
                var definition = SkillDefinition.builder().name("controlled-purchase-grant-execute")
                        .tools(List.of(PurchaseGrantExecuteTool.NAME)).accessMode("WRITE").enabled(true).build();
                if (channel == Channel.TOOL) definition.setName("controlled-purchase-grant-tool-bridge");
                var context = SkillContext.builder().factoryId(factory).userId(actor.id().toString())
                        .userRole(actor.role()).userQuery("执行本人已确认采购授权")
                        .extractedParams(trusted).build();
                var skill = skills.getObject().execute(definition, context);
                if (!skill.isSuccess()) throw new BusinessException(503, "采购执行结果未知，请查询原授权状态，勿重新执行")
                        .withCode("PURCHASE_GRANT_OUTCOME_UNKNOWN");
                // Skill wrapper success alone does not imply its inner Tool succeeded.
                result = mapper.valueToTree(skill.getData()).path(PurchaseGrantExecuteTool.NAME);
        } catch (BusinessException refused) {
            throw refused;
        } catch (Exception unknown) {
            throw new BusinessException(503, "采购执行结果未知，请查询原授权状态，勿重新执行", unknown)
                    .withCode("PURCHASE_GRANT_OUTCOME_UNKNOWN");
        }
        if (!result.path("success").isBoolean() || !result.path("success").booleanValue()) {
            boolean refused = "PURCHASE_GRANT_REJECTED".equals(result.path("code").asText());
            throw new BusinessException(refused ? 403 : 503,
                    result.path("error").asText("采购执行结果未知，请查询原授权状态，勿重新执行"))
                    .withCode(refused ? "PURCHASE_GRANT_REJECTED" : "PURCHASE_GRANT_OUTCOME_UNKNOWN");
        }
        try {
            var outcome = mapper.treeToValue(result.path("data"), ControlledPurchaseTaskExecutionService.Outcome.class);
            if (!grant.equals(outcome.grantId()) || !"DRAFT".equals(outcome.state()) || outcome.purchaseOrderId() == null) {
                throw new IllegalStateException("Tool result not bound to selected task");
            }
            return outcome;
        } catch (Exception unknown) {
            throw new BusinessException(503, "采购结果凭据不匹配，请查询原授权状态，勿重新执行", unknown)
                    .withCode("PURCHASE_GRANT_OUTCOME_UNKNOWN");
        }
    }

    /** Invoked by the actual Tool, including Skill's deterministic entry. Never trusts context identity. */
    public ControlledPurchaseTaskExecutionService.Outcome executeAuthorized(String factory, String grant, Long version) {
        try {
            actor(factory);
            requireReference(grant, version);
            if (!tools.getObject().isToolEnabledForFactory(factory, PurchaseGrantExecuteTool.NAME)) {
                throw new BusinessException(403, "采购授权工具已停用");
            }
            return controlled.executeLater(factory, grant, version);
        } catch (BusinessException | PurchaseTaskGrantService.GrantRejectedException refused) {
            throw audited(factory, grant, refused);
        }
    }

    private static AuthenticatedActor actor(String factory) {
        return AuthenticatedActor.fromCurrentRequest()
                .filter(a -> factory != null && !factory.isBlank() && a.type() == AuthenticatedActorType.FACTORY_USER
                        && Objects.equals(factory, a.factoryId()))
                .orElseThrow(() -> new BusinessException(403, "缺少当前租户的可信采购登录主体"));
    }
    private static void requireReference(String grant, Long version) {
        if (grant == null || !grant.matches("preview-[1-9][0-9]{0,18}") || version == null || version <= 0) {
            throw new BusinessException(403, "采购授权参考或版本无效");
        }
    }
    private BusinessException audited(String factory, String grant, RuntimeException refused) {
        var response = new BusinessException(403, refused.getMessage(), refused).withCode("PURCHASE_GRANT_REJECTED");
        try {
            refusalAudit.record(factory, grant, refused).ifPresent(id -> response.withHint("拒绝记录: " + id));
        } catch (RuntimeException unavailable) {
            var error = new BusinessException(503, "执行已拒绝，但拒绝审计暂不可写；请核对记录，勿重新执行", unavailable)
                    .withCode("GRANT_REFUSAL_AUDIT_UNAVAILABLE");
            error.addSuppressed(refused);
            throw error;
        }
        // Tool serialization also retains the correlation, without exposing command/proof.
        if (response.getActionHint() != null) return new BusinessException(403, response.getMessage() + "；" + response.getActionHint(), refused)
                .withCode("PURCHASE_GRANT_REJECTED");
        return response;
    }
}
