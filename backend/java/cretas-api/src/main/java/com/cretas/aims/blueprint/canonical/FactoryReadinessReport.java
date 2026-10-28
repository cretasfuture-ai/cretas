package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.controller.dto.indicator.IndicatorApplicabilityResponse;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A read-only delivery view. Configuration acceptance never implies business sign-off.
 *
 * <p>{@code stages} and {@code businessReadiness} were added without bumping
 * {@code schemaVersion}: older clients ignore them, and every state there is read from its own
 * persisted fact. A fact that could not be read is UNKNOWN, never READY/DONE.</p>
 */
public record FactoryReadinessReport(
        String schemaVersion, String factoryId, String blueprintId, Integer blueprintVersion,
        Long rowVersion, String lifecycleState, List<Capability> capabilities,
        List<ProductRuntime> currentRuntime, AppliedVersion appliedVersion,
        IndicatorApplicabilityResponse plannedIndicators,
        IndicatorApplicabilityResponse currentIndicators,
        String businessAcceptanceState, int businessWriteCount, LocalDateTime readAt,
        List<Stage> stages, List<BusinessDomain> businessReadiness) {
    public record Capability(String id, String label, String configurationState,
            String acceptanceState, String stage, String nextStep, int receiptCount) { }
    public record ProductRuntime(String productId, String productName, String state,
            Long workflowId, Integer workflowVersion, String bomId, Integer bomVersion,
            String reason) { }
    public record AppliedVersion(String blueprintId, Integer version, boolean candidateDiffers,
            String boundary) { }

    /** key: CONFIRMATION … FIRST_BUSINESS; state: DONE / PENDING / UNKNOWN / NOT_APPLICABLE. */
    public record Stage(String key, String label, String state, String evidence) { }

    /**
     * domain: PURCHASE / PRODUCTION / SHIPPING. applicability: APPLICABLE / NOT_APPLICABLE / UNKNOWN.
     * state: READY / MISSING / UNKNOWN / NOT_APPLICABLE. {@code notChecked} names what this view
     * deliberately does not require, so an absent check is never read as a pass.
     */
    public record BusinessDomain(String domain, String label, String applicability,
            String applicabilityBasis, String state, List<Check> checks, List<String> notChecked,
            FirstRecord firstRecord, FirstAction firstAction) {
        public BusinessDomain(String domain, String label, String applicability, String applicabilityBasis,
                String state, List<Check> checks, List<String> notChecked, FirstRecord firstRecord) {
            this(domain, label, applicability, applicabilityBasis, state, checks, notChecked, firstRecord, null);
        }

        public BusinessDomain withFirstAction(FirstAction action) {
            return new BusinessDomain(domain, label, applicability, applicabilityBasis, state, checks,
                    notChecked, firstRecord, action);
        }
    }

    /**
     * The first business action this domain can start now, only present when every check is MET.
     * {@code path} is an existing business page; {@code query} carries ids that really exist in this
     * factory (read from its own master data, never invented) so the page can open pre-filled.
     */
    public record FirstAction(String label, String path, java.util.Map<String, String> query, String detail) { }

    /** state: MET / MISSING / UNKNOWN. remedyKey names the existing page where the gap is filled. */
    public record Check(String code, String label, String state, String detail,
            String remedyKey, String remedyLabel) { }

    /**
     * state: COMPLETED (at least one document reached the domain's terminal status) / RECORDED
     * (documents exist, drafts included, none completed) / NONE / UNKNOWN (unreadable). count is
     * all documents, completedCount the terminal ones; both null when UNKNOWN.
     */
    public record FirstRecord(String state, Long count, Long completedCount, String detail) { }
}
