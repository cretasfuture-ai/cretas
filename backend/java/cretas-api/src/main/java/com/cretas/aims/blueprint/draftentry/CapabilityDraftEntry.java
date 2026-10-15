package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * One capability's production entry into its own module DRAFT command.
 *
 * <p>Design §2.2: every module materializes through its own command — there is no generic JSON
 * writer. An entry is therefore a thin, explicit adapter per capability: it converts the body to
 * that command's own request type and calls that command. What is common to all capabilities
 * (URL scope, server-built operator, operatorId re-signing) lives once in
 * {@link BlueprintDraftController}, not here.</p>
 *
 * <p>Registering an entry only opens DRAFT writes. Publication and activation stay closed
 * ({@code FactoryActivationSurfaceMustStayClosedTest}).</p>
 */
public interface CapabilityDraftEntry {

    /** Must be a MODULE_DRAFT capability of {@code FactoryCapabilityAdapterContracts}. */
    String capabilityId();

    /** Must equal the command's {@code REQUIRED_PERMISSION} and the adapter contract's permission. */
    String requiredPermission();

    /** Where this capability's body names its factory. Every open entry's request carries it top-level. */
    default String declaredFactoryId(JsonNode body) {
        return body.path("factoryId").asText(null);
    }

    /**
     * The blueprint the body binds itself to, or {@code null} when the command's request has no
     * blueprint lineage at all. A {@code null} here makes {@link BlueprintDraftController} call
     * {@link #requireBlueprintContext} instead of comparing ids.
     *
     * <p>⚠️ workflow: its command carries a compiled route graph and resolution catalog, which must be
     * assembled server-side from the blueprint, never taken from the request body
     * ({@code FactoryWorkflowDraftMaterializationService} javadoc). An entry that took them from the
     * body was opened in #136 and withdrawn. {@link WorkflowDraftEntry} takes only the user's choices
     * ({@link WorkflowDraftRequest}) and has {@code WorkflowCompileRequestAssembler} build the rest;
     * {@code DraftEntryRequestsNeverCarryAClientGraphTest} keeps any graph out of every entry's request.</p>
     */
    default String declaredBlueprintId(JsonNode body) {
        return body.path("blueprintId").asText(null);
    }

    /**
     * For a command whose request carries no blueprint: verify the URL's blueprint is this factory's
     * current DRAFT/VALIDATED blueprint, as the other commands do internally. The default refuses, so
     * an entry that forgets to implement it fails closed instead of skipping the check.
     */
    default void requireBlueprintContext(String factoryId, String blueprintId) {
        throw new BusinessException(409, "这类建厂草稿没有绑定蓝图，入口未实现蓝图核对，暂不能写入")
                .withCode("BLUEPRINT_DRAFT_BLUEPRINT_CONTEXT_UNVERIFIED");
    }

    Object preview(JsonNode request);

    /** {@code signedRequest.operatorId} has already been set to the server-resolved operator. */
    Object materialize(JsonNode signedRequest, DraftOperator operator);

    Object readBack(String factoryId, String draftId);

    /** The blueprint lineage a written-draft listing is scoped to. */
    record WrittenScope(String factoryId, String blueprintId, long blueprintVersion, String sourceDigest) {
    }

    /**
     * One DRAFT row this capability has written in the scope's lineage.
     *
     * @param naturalKey what the row is about in business terms (product code, warehouse code …);
     *                   null for a capability with one DRAFT per blueprint
     */
    record WrittenDraft(String draftId, String naturalKey, String label, String status, String desiredDigest,
                        LocalDateTime createdAt) {
    }

    /**
     * DRAFT rows this capability has written for the scope (same factory, blueprint, version and
     * source digest), newest first. {@link Optional#empty()} means the capability cannot list them
     * yet — distinct from an empty list ("listed, none written").
     */
    default Optional<List<WrittenDraft>> written(WrittenScope scope) {
        return Optional.empty();
    }

    default Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        throw new BusinessException(404, "这类建厂草稿暂不提供恢复方案")
                .withCode("BLUEPRINT_DRAFT_RECOVERY_UNSUPPORTED");
    }
}
