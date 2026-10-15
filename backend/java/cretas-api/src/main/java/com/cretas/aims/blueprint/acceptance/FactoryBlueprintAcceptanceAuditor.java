package com.cretas.aims.blueprint.acceptance;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts.Availability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * B27 fail-closed acceptance auditor. It can prove an acceptance package is incomplete or ready
 * for real users; it cannot sign on behalf of a factory user and performs no business write.
 */
public final class FactoryBlueprintAcceptanceAuditor {

    public static final String SCHEMA_VERSION = "factory-blueprint-acceptance-audit/v1";

    public enum EvidenceScope { CURRENT_CANDIDATE, HISTORICAL_REFERENCE }

    public enum SignoffActor { REAL_USER, CODEX, AUTOMATION }

    public enum SignoffDecision { SIGNED, REJECTED, PENDING }

    public enum AutomatedGateStatus { PASS, BLOCKED_PLATFORM_GAPS, BLOCKED_EVIDENCE_GAPS }

    public enum UserAcceptanceStatus { ACCEPTED, REJECTED, PENDING }

    public record CapabilityEvidence(
            String capabilityId,
            int confirmedFactCount,
            List<String> evidenceRefs,
            EvidenceScope evidenceScope,
            String candidateDigest,
            Availability adapterAvailability,
            String draftReceiptState,
            String simulationState) {
        public CapabilityEvidence {
            capabilityId = clean(capabilityId);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            evidenceScope = evidenceScope == null ? EvidenceScope.HISTORICAL_REFERENCE : evidenceScope;
            candidateDigest = clean(candidateDigest);
            adapterAvailability = adapterAvailability == null
                    ? Availability.BLOCKED_OWNER_COMMAND_MISSING : adapterAvailability;
            draftReceiptState = clean(draftReceiptState);
            simulationState = clean(simulationState);
        }
    }

    public record ScenarioEvidence(
            String scenarioId,
            String status,
            String candidateDigest,
            List<String> evidenceRefs,
            int productionBusinessWriteCount) {
        public ScenarioEvidence {
            scenarioId = clean(scenarioId);
            status = clean(status);
            candidateDigest = clean(candidateDigest);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public record ModuleReview(
            String role,
            String reviewerId,
            String status,
            String candidateDigest,
            List<String> capabilityIds,
            String evidenceRef) {
        public ModuleReview {
            role = clean(role);
            reviewerId = clean(reviewerId);
            status = clean(status);
            candidateDigest = clean(candidateDigest);
            capabilityIds = capabilityIds == null ? List.of() : List.copyOf(capabilityIds);
            evidenceRef = clean(evidenceRef);
        }
    }

    public record UserSignoff(
            String role,
            String userId,
            SignoffActor actor,
            SignoffDecision decision,
            String candidateDigest,
            String reason,
            Instant signedAt) {
        public UserSignoff {
            role = clean(role);
            userId = clean(userId);
            actor = actor == null ? SignoffActor.AUTOMATION : actor;
            decision = decision == null ? SignoffDecision.PENDING : decision;
            candidateDigest = clean(candidateDigest);
            reason = clean(reason);
        }
    }

    public record GoldenLedger(
            String factoryId,
            String factoryName,
            String blueprintId,
            int blueprintVersion,
            String candidateDigest,
            String environmentState,
            List<CapabilityEvidence> capabilities,
            List<ScenarioEvidence> scenarios,
            List<ModuleReview> moduleReviews,
            List<UserSignoff> userSignoffs,
            int productionBusinessWriteCount) {
        public GoldenLedger {
            factoryId = clean(factoryId);
            factoryName = clean(factoryName);
            blueprintId = clean(blueprintId);
            candidateDigest = clean(candidateDigest);
            environmentState = clean(environmentState);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
            moduleReviews = moduleReviews == null ? List.of() : List.copyOf(moduleReviews);
            userSignoffs = userSignoffs == null ? List.of() : List.copyOf(userSignoffs);
        }
    }

    public record AuditIssue(String code, String subject, String message, String recovery) {
        public AuditIssue {
            code = clean(code); subject = clean(subject); message = clean(message); recovery = clean(recovery);
        }
    }

    public record AcceptanceAudit(
            String schemaVersion,
            String factoryId,
            String factoryName,
            String blueprintId,
            int blueprintVersion,
            String candidateDigest,
            AutomatedGateStatus automatedGateStatus,
            UserAcceptanceStatus userAcceptanceStatus,
            String overallStatus,
            List<String> missingCapabilityCommands,
            List<String> missingUserRoles,
            List<String> passedScenarios,
            List<AuditIssue> issues,
            int productionBusinessWriteCount,
            String deploymentState,
            String acceptanceBoundary) {
        public AcceptanceAudit {
            schemaVersion = SCHEMA_VERSION;
            missingCapabilityCommands = List.copyOf(missingCapabilityCommands);
            missingUserRoles = List.copyOf(missingUserRoles);
            passedScenarios = List.copyOf(passedScenarios);
            issues = List.copyOf(issues);
            deploymentState = clean(deploymentState);
            acceptanceBoundary = "CODEX_AUDIT_IS_NOT_USER_ACCEPTANCE";
        }
    }

    private static final Set<String> REQUIRED_SCENARIOS = Set.of(
            "HAPPY_PATH", "PARTIAL_INPUT", "RULE_UNKNOWN", "BRANCH_JOIN", "REWORK_LIMIT",
            "VERSION_DRIFT", "IDEMPOTENT_RETRY", "MODULE_PARTIAL_FAILURE", "CANCEL_RECOVERY",
            "ZERO_PRODUCTION_WRITE");
    private static final Set<String> REQUIRED_USER_ROLES = Set.of(
            "FACTORY_OWNER", "PRODUCTION_OWNER", "QUALITY_OWNER", "WAREHOUSE_OWNER");

    private final Set<String> capabilityIds = FactoryCapabilityAdapterContracts.b25Defaults().contracts().stream()
            .map(FactoryCapabilityAdapterContracts.CapabilityContract::capabilityId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    public AcceptanceAudit audit(GoldenLedger ledger) {
        validateIdentity(ledger);
        List<AuditIssue> issues = new ArrayList<>();
        Map<String, CapabilityEvidence> capabilityMap = uniqueCapabilities(ledger, issues);
        List<String> missingCommands = new ArrayList<>();
        for (String capabilityId : capabilityIds.stream().sorted().toList()) {
            CapabilityEvidence evidence = capabilityMap.get(capabilityId);
            if (evidence == null) {
                issues.add(issue("CAPABILITY_EVIDENCE_MISSING", capabilityId,
                        "当前 candidate 缺少能力证据", "按 26 项目录补齐确认事实与模块证据"));
                continue;
            }
            if (evidence.confirmedFactCount() <= 0 || evidence.evidenceRefs().isEmpty()
                    || evidence.evidenceScope() != EvidenceScope.CURRENT_CANDIDATE
                    || !ledger.candidateDigest().equals(evidence.candidateDigest())) {
                issues.add(issue("CURRENT_CAPABILITY_EVIDENCE_INCOMPLETE", capabilityId,
                        "历史证据、空证据或 digest 不匹配不能证明当前 candidate",
                        "对当前 digest 重新取得用户确认与可定位 evidence refs"));
            }
            if (evidence.adapterAvailability() == Availability.BLOCKED_OWNER_COMMAND_MISSING) {
                missingCommands.add(capabilityId);
            } else if (!"PASS".equals(evidence.simulationState())) {
                issues.add(issue("CAPABILITY_SIMULATION_NOT_PASS", capabilityId,
                        "能力隔离模拟未通过", "修复并对同一 candidate digest 重跑"));
            }
        }

        List<String> passedScenarios = validateScenarios(ledger, issues);
        validateModuleReviews(ledger, issues);
        UserAcceptanceStatus userStatus = userAcceptance(ledger, issues);
        List<String> missingRoles = missingUserRoles(ledger);

        if (!"ISOLATED_NOT_DEPLOYED".equals(ledger.environmentState())
                || ledger.productionBusinessWriteCount() != 0) {
            issues.add(issue("PRODUCTION_BOUNDARY_VIOLATION", ledger.factoryId(),
                    "B27 只能在隔离、未部署且生产零写条件下运行",
                    "停止验收并回读生产写入/部署状态"));
        }

        AutomatedGateStatus automatedStatus;
        if (!missingCommands.isEmpty()) automatedStatus = AutomatedGateStatus.BLOCKED_PLATFORM_GAPS;
        else if (issues.stream().anyMatch(item -> !item.code().startsWith("USER_"))) {
            automatedStatus = AutomatedGateStatus.BLOCKED_EVIDENCE_GAPS;
        } else automatedStatus = AutomatedGateStatus.PASS;
        String overall = automatedStatus == AutomatedGateStatus.PASS
                && userStatus == UserAcceptanceStatus.ACCEPTED ? "ACCEPTED" : "BLOCKED";
        issues.sort(Comparator.comparing(AuditIssue::code).thenComparing(AuditIssue::subject));
        return new AcceptanceAudit(SCHEMA_VERSION, ledger.factoryId(), ledger.factoryName(),
                ledger.blueprintId(), ledger.blueprintVersion(), ledger.candidateDigest(),
                automatedStatus, userStatus, overall, missingCommands.stream().sorted().toList(),
                missingRoles, passedScenarios, issues, ledger.productionBusinessWriteCount(),
                ledger.environmentState(),
                "CODEX_AUDIT_IS_NOT_USER_ACCEPTANCE");
    }

    public void requireAccepted(AcceptanceAudit audit) {
        if (!"ACCEPTED".equals(audit.overallStatus())) {
            throw new IllegalStateException("Factory Blueprint user acceptance is not complete: "
                    + audit.automatedGateStatus() + " / " + audit.userAcceptanceStatus());
        }
    }

    private void validateIdentity(GoldenLedger ledger) {
        Objects.requireNonNull(ledger, "ledger");
        if (ledger.factoryId().isBlank() || ledger.factoryName().isBlank() || ledger.blueprintId().isBlank()
                || ledger.blueprintVersion() <= 0 || ledger.candidateDigest().isBlank()) {
            throw new IllegalArgumentException("Golden ledger identity is incomplete");
        }
    }

    private Map<String, CapabilityEvidence> uniqueCapabilities(GoldenLedger ledger, List<AuditIssue> issues) {
        Map<String, CapabilityEvidence> values = new LinkedHashMap<>();
        for (CapabilityEvidence evidence : ledger.capabilities()) {
            if (!capabilityIds.contains(evidence.capabilityId())) {
                issues.add(issue("UNKNOWN_CAPABILITY_EVIDENCE", evidence.capabilityId(),
                        "证据不属于 v2 能力目录", "删除或迁移到正确 capability"));
            } else if (values.putIfAbsent(evidence.capabilityId(), evidence) != null) {
                issues.add(issue("DUPLICATE_CAPABILITY_EVIDENCE", evidence.capabilityId(),
                        "同一能力出现重复 golden ledger 行", "合并为一条当前 candidate 权威证据"));
            }
        }
        return values;
    }

    private List<String> validateScenarios(GoldenLedger ledger, List<AuditIssue> issues) {
        Map<String, ScenarioEvidence> values = new LinkedHashMap<>();
        for (ScenarioEvidence scenario : ledger.scenarios()) {
            if (values.putIfAbsent(scenario.scenarioId(), scenario) != null) {
                issues.add(issue("DUPLICATE_SCENARIO", scenario.scenarioId(),
                        "隔离场景重复", "保留当前 candidate 的唯一回执"));
            }
        }
        List<String> passed = new ArrayList<>();
        for (String scenarioId : REQUIRED_SCENARIOS.stream().sorted().toList()) {
            ScenarioEvidence scenario = values.get(scenarioId);
            if (scenario == null || !"PASS".equals(scenario.status())
                    || !ledger.candidateDigest().equals(scenario.candidateDigest())
                    || scenario.evidenceRefs().isEmpty() || scenario.productionBusinessWriteCount() != 0) {
                issues.add(issue("SCENARIO_NOT_PROVEN", scenarioId,
                        "场景缺失、失败、证据/digest 不匹配或产生生产写",
                        "在隔离环境对当前 candidate 重跑并保存回执"));
            } else passed.add(scenarioId);
        }
        return List.copyOf(passed);
    }

    private void validateModuleReviews(GoldenLedger ledger, List<AuditIssue> issues) {
        Set<String> reviewed = new LinkedHashSet<>();
        for (ModuleReview review : ledger.moduleReviews()) {
            if (!"PASS".equals(review.status()) || review.reviewerId().isBlank()
                    || review.evidenceRef().isBlank() || !ledger.candidateDigest().equals(review.candidateDigest())) {
                issues.add(issue("MODULE_REVIEW_INVALID", review.role(),
                        "模块复核未绑定当前 candidate 或缺少 reviewer/evidence",
                        "由模块负责人对当前 digest 重新复核"));
                continue;
            }
            reviewed.addAll(review.capabilityIds());
        }
        List<String> missing = capabilityIds.stream().filter(id -> !reviewed.contains(id)).sorted().toList();
        if (!missing.isEmpty()) issues.add(issue("MODULE_REVIEW_COVERAGE_INCOMPLETE", "capabilities",
                "未被模块负责人复核: " + missing, "补齐按 owner 分组的当前 candidate 复核"));
    }

    private UserAcceptanceStatus userAcceptance(GoldenLedger ledger, List<AuditIssue> issues) {
        boolean rejected = ledger.userSignoffs().stream().anyMatch(item -> item.actor() == SignoffActor.REAL_USER
                && item.decision() == SignoffDecision.REJECTED
                && ledger.candidateDigest().equals(item.candidateDigest()));
        for (UserSignoff signoff : ledger.userSignoffs()) {
            if (signoff.decision() == SignoffDecision.SIGNED && signoff.actor() != SignoffActor.REAL_USER) {
                issues.add(issue("USER_SIGNOFF_ACTOR_INVALID", signoff.role(),
                        "Codex/自动化不能代替真实用户签署", "由指定真实工厂角色在当前 candidate 上签署"));
            }
        }
        if (rejected) return UserAcceptanceStatus.REJECTED;
        List<String> missing = missingUserRoles(ledger);
        if (!missing.isEmpty()) {
            issues.add(issue("USER_ACCEPTANCE_PENDING", "roles", "待真实用户签署: " + missing,
                    "在模块复核完成后由真实用户逐角色签署当前 digest"));
            return UserAcceptanceStatus.PENDING;
        }
        return UserAcceptanceStatus.ACCEPTED;
    }

    private List<String> missingUserRoles(GoldenLedger ledger) {
        Set<String> accepted = ledger.userSignoffs().stream()
                .filter(item -> item.actor() == SignoffActor.REAL_USER)
                .filter(item -> item.decision() == SignoffDecision.SIGNED)
                .filter(item -> item.signedAt() != null && !item.userId().isBlank() && item.reason().length() >= 8)
                .filter(item -> ledger.candidateDigest().equals(item.candidateDigest()))
                .map(UserSignoff::role)
                .collect(java.util.stream.Collectors.toSet());
        return REQUIRED_USER_ROLES.stream().filter(role -> !accepted.contains(role)).sorted().toList();
    }

    private AuditIssue issue(String code, String subject, String message, String recovery) {
        return new AuditIssue(code, subject, message, recovery);
    }

    private static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
