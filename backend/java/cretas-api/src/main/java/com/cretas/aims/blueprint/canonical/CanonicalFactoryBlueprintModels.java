package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.PlannedChange;
import com.cretas.aims.security.ActorRef;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** API models for the factory-owned, versioned onboarding blueprint. */
public final class CanonicalFactoryBlueprintModels {

    public static final String SCHEMA_VERSION = "factory-blueprint/v1";
    public static final String SOURCE = "ONBOARDING_QUESTIONNAIRE_AGENT_SERVER_PLAN";

    private CanonicalFactoryBlueprintModels() {
    }

    @Data
    public static class SaveDraftRequest {
        private String factoryName;
        private String sessionId;
        /** 访谈源事实，不属于可 Apply 的工厂配置载荷。 */
        private String customerSupplied;
        /** 转换率的确认来源，不属于可 Apply 的转换配置字段。 */
        private List<ConversionEvidence> conversionEvidence;
        private String idempotencyKey;
        private Long expectedRowVersion;
        private ApplyAIInitRequest payload;
    }

    @Data
    public static class OperationRequest {
        private String idempotencyKey;
        private Long expectedRowVersion;
    }

    @Data
    public static class ConversionEvidence {
        private String fromCode;
        private String toCode;
        private String provenance;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class CapabilityAcceptanceRequest extends OperationRequest {
        private List<CapabilityAcceptanceDecision> decisions;
    }

    @Data
    public static class CapabilityAcceptanceDecision {
        private String capabilityId;
        private String decision;
        private String note;
        private List<String> evidenceRefs;
    }

    @Data
    @Builder
    public static class CapabilityDefinitionView {
        private String id;
        private String label;
        private String description;
        private int displayOrder;
        private List<String> dependsOn;
        private List<String> configurationTypes;
        private List<String> moduleSchemaCodes;
        private List<String> sectorProfiles;
        private List<String> authorityRefs;
        private List<String> requirementCodes;
        private boolean draftIntentEligible;
    }

    @Data
    @Builder
    public static class CapabilityCatalogView {
        private String catalogVersion;
        private String digest;
        private String source;
        private int capabilityCount;
        private List<CapabilityDefinitionView> definitions;
        private LocalDateTime loadedAt;
    }

    @Data
    @Builder
    public static class CapabilityView {
        private String id;
        private String label;
        private String description;
        private int displayOrder;
        private List<String> dependsOn;
        private List<String> configurationTypes;
        private List<String> moduleSchemaCodes;
        private List<String> sectorProfiles;
        private List<String> authorityRefs;
        private List<String> requirementCodes;
        private boolean draftIntentEligible;
        private List<String> configurationRefs;
        private int plannedCount;
        private boolean included;
        private String status;
        private List<String> missingDependencies;
        private String source;
        private LocalDateTime sourceUpdatedAt;
        private String confirmationState;
        private String blueprintPublishState;
        private String configurationApplyState;
        private String workflowPublishState;
        private boolean acceptanceEligible;
        private String acceptanceState;
        private List<ValidationIssue> validationIssues;
        private List<Map<String, Object>> configurationReceipts;
        private List<Map<String, Object>> acceptanceReceipts;
        private String nextStep;
    }

    @Data
    @Builder
    public static class ValidationReport {
        private boolean valid;
        private List<ValidationIssue> blockingIssues;
        private List<ValidationIssue> warnings;
        private int businessWriteCount;
        private LocalDateTime calculatedAt;
    }

    @Data
    @Builder
    public static class ValidationIssue {
        private String code;
        private String capabilityId;
        private String message;
        private String recovery;
    }

    @Data
    @Builder
    public static class SimulationReport {
        private boolean safe;
        private String safetyScope;
        private Map<String, Boolean> checks;
        private List<ValidationIssue> blockingIssues;
        private List<String> limitations;
        private List<String> affectedModules;
        private List<PlannedChange> changes;
        private List<DiffSummary> diff;
        private Map<String, Object> workflow;
        private int businessWriteCount;
        private LocalDateTime calculatedAt;
    }

    @Data
    @Builder
    public static class DiffSummary {
        private String group;
        private long createCount;
        private long overwriteCount;
        private long skipCount;
    }

    @Data
    @Builder
    public static class ReceiptView {
        private String id;
        private String operationType;
        private String status;
        private String idempotencyKey;
        private Integer blueprintVersion;
        private Long operatedBy;
        /** Typed audit identity; legacy operatedBy remains for factory-user compatibility. */
        private ActorRef operatedActor;
        private int businessWriteCount;
        private Map<String, Object> result;
        private List<Map<String, Object>> moduleResults;
        private Map<String, Object> recoveryPlan;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    public static class BlueprintResponse {
        private String id;
        private String factoryId;
        private String factoryName;
        private String schemaVersion;
        private String capabilityCatalogVersion;
        private String capabilityCatalogDigest;
        private Integer version;
        private Long rowVersion;
        private String lifecycleState;
        private String scopeType;
        private Long createdBy;
        private Long updatedBy;
        /** Typed audit identities; legacy numeric fields remain for compatibility. */
        private ActorRef createdActor;
        private ActorRef updatedActor;
        private ActorRef publishedActor;
        private String source;
        private String sessionId;
        private String customerSupplied;
        private List<ConversionEvidence> conversionEvidence;
        /** 用户已经复核并保存的精确配置载荷，用于刷新后恢复专业编辑器。 */
        private ApplyAIInitRequest sourcePayload;
        private List<CapabilityView> capabilities;
        private List<PlannedChange> plannedChanges;
        private List<Map<String, Object>> workflowLanes;
        /** Read-only structured route capability; no Workflow persistence is performed here. */
        private List<FactoryRouteGraphPrototype.RouteGraph> routeGraphs;
        private String configurationState;
        private String acceptanceState;
        private String workflowState;
        private ValidationReport validation;
        private SimulationReport simulation;
        private ReceiptView latestReceipt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime publishedAt;
    }

    @Data
    @Builder
    public static class VersionView {
        private String blueprintId;
        private String factoryId;
        private Integer version;
        private String schemaVersion;
        private String lifecycleState;
        private boolean published;
        private LocalDateTime publishedAt;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    public static class VersionDetailView {
        private String blueprintId;
        private String factoryId;
        private Integer version;
        private String schemaVersion;
        private String lifecycleState;
        private boolean published;
        private LocalDateTime publishedAt;
        private Long publishedBy;
        /** Typed history identities; legacy publishedBy remains for compatibility. */
        private ActorRef createdActor;
        private ActorRef publishedActor;
        private LocalDateTime createdAt;
        private Map<String, Object> snapshot;
        private Object changeSummary;
    }
}
