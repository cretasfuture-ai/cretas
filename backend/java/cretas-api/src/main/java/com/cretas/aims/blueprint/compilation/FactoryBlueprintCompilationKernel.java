package com.cretas.aims.blueprint.compilation;

import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.AdapterReadiness;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.AdapterStatus;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.CapabilityArtifact;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.CapabilityChange;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.CapabilityChangeSet;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.ChangeStatus;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.CompilationArtifact;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.CompilationIssue;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.CompilationRequest;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.IssueSeverity;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.SourceFact;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic, side-effect-free compiler from confirmed facts to reviewable artifacts. */
public final class FactoryBlueprintCompilationKernel {

    private final Map<String, FactoryCapabilityAdapter> adapters;

    public FactoryBlueprintCompilationKernel(List<FactoryCapabilityAdapter> adapters) {
        Map<String, FactoryCapabilityAdapter> indexed = new LinkedHashMap<>();
        for (FactoryCapabilityAdapter adapter : adapters == null ? List.<FactoryCapabilityAdapter>of() : adapters) {
            FactoryCapabilityAdapter duplicate = indexed.putIfAbsent(adapter.capabilityId(), adapter);
            if (duplicate != null) {
                throw new IllegalArgumentException("Duplicate capability adapter: " + adapter.capabilityId());
            }
        }
        this.adapters = Map.copyOf(indexed);
    }

    public static FactoryBlueprintCompilationKernel b20Defaults() {
        return new FactoryBlueprintCompilationKernel(List.of(
                new FactoryRouteArtifactAdapter(),
                new FactoryScopeReader()));
    }

    public CompilationArtifact compile(CompilationRequest request) {
        Objects.requireNonNull(request, "request");
        List<CompilationIssue> issues = new ArrayList<>();
        validateRequest(request, issues);

        List<SourceFact> confirmedFacts = authoritativeFacts(request, issues);
        Map<String, List<SourceFact>> factsByCapability = groupByCapability(confirmedFacts);
        Set<String> requestedCapabilities = new LinkedHashSet<>(request.capabilityIds());
        List<AdapterReadiness> readiness = readiness(requestedCapabilities);
        List<CapabilityArtifact> artifacts = new ArrayList<>();
        List<CapabilityChange> changes = new ArrayList<>();

        FactoryCapabilityAdapter.CompilationContext context = new FactoryCapabilityAdapter.CompilationContext(
                request.factoryId(), request.blueprintId(), request.blueprintVersion(),
                confirmedFacts, request.routeCompilations());
        for (String capabilityId : requestedCapabilities.stream().sorted().toList()) {
            FactoryCapabilityAdapter adapter = adapters.get(capabilityId);
            List<SourceFact> capabilityFacts = factsByCapability.getOrDefault(capabilityId, List.of());
            boolean hasInput = !capabilityFacts.isEmpty() || (adapter != null && adapter.hasInput(context));
            if (!hasInput) {
                changes.add(change(capabilityId, ChangeStatus.NOT_REQUESTED, capabilityFacts, ""));
                continue;
            }
            if (adapter == null) {
                String reason = "该能力尚无只读编译适配器";
                issues.add(issue("CAPABILITY_ADAPTER_MISSING", IssueSeverity.BLOCKING, capabilityId, "", reason,
                        "实现并验证对应 FactoryCapabilityAdapter 后再生成预览"));
                changes.add(change(capabilityId, ChangeStatus.BLOCKED, capabilityFacts, reason));
                continue;
            }
            FactoryCapabilityAdapter.AdapterOutput output = adapter.compile(context);
            issues.addAll(output.issues());
            if (output.artifact() != null && (!capabilityId.equals(output.artifact().capabilityId())
                    || !adapter.artifactType().equals(output.artifact().artifactType())
                    || !adapter.targetSchemaVersion().equals(output.artifact().targetSchemaVersion()))) {
                issues.add(issue("ADAPTER_CONTRACT_VIOLATION", IssueSeverity.BLOCKING,
                        capabilityId, "", "适配器返回了不属于其声明能力或 schema 的产物",
                        "修复适配器 identity/type/schema 声明，禁止继续使用该产物"));
                changes.add(change(capabilityId, ChangeStatus.BLOCKED, capabilityFacts,
                        "适配器产物与声明契约不一致"));
                continue;
            }
            if (output.artifact() == null || output.issues().stream().anyMatch(this::blocking)) {
                changes.add(change(capabilityId, ChangeStatus.BLOCKED, capabilityFacts,
                        "适配器未生成可审阅产物"));
            } else {
                artifacts.add(output.artifact());
                changes.add(change(capabilityId, ChangeStatus.PREVIEW_READY, capabilityFacts, ""));
            }
        }

        boolean blocked = issues.stream().anyMatch(this::blocking);
        String status = blocked ? "BLOCKED" : artifacts.isEmpty() ? "EMPTY" : "PREVIEW_READY";
        artifacts.sort(Comparator.comparing(CapabilityArtifact::capabilityId));
        changes.sort(Comparator.comparing(CapabilityChange::capabilityId));
        issues.sort(Comparator.comparing(CompilationIssue::severity)
                .thenComparing(CompilationIssue::code)
                .thenComparing(CompilationIssue::capabilityId)
                .thenComparing(CompilationIssue::factId));
        CapabilityChangeSet changeSet = new CapabilityChangeSet(
                FactoryBlueprintCompilationModels.CHANGE_SET_SCHEMA_VERSION, changes, 0);
        String digest = digest(request, confirmedFacts, artifacts, changeSet, readiness, issues, status);
        return new CompilationArtifact(
                FactoryBlueprintCompilationModels.SCHEMA_VERSION,
                request.factoryId(), request.blueprintId(), request.blueprintVersion(),
                request.catalogVersion(), request.catalogDigest(), digest, status,
                "PREVIEW_ONLY", "NOT_PUBLISHED", "NOT_ACTIVATED",
                confirmedFacts, artifacts, changeSet, readiness, issues, 0);
    }

    private void validateRequest(CompilationRequest request, List<CompilationIssue> issues) {
        if (request.factoryId().isBlank()) issues.add(issue("FACTORY_ID_REQUIRED", IssueSeverity.BLOCKING,
                "", "", "编译请求缺少工厂范围", "提供当前已授权工厂 ID"));
        if (request.blueprintId().isBlank() || request.blueprintVersion() <= 0) issues.add(issue(
                "BLUEPRINT_IDENTITY_REQUIRED", IssueSeverity.BLOCKING, "", "", "编译请求缺少蓝图 ID 或版本",
                "先保存并回读权威蓝图草稿"));
        if (request.catalogVersion().isBlank() || request.catalogDigest().isBlank()) issues.add(issue(
                "CATALOG_LOCK_REQUIRED", IssueSeverity.BLOCKING, "", "", "编译请求未锁定能力目录版本和摘要",
                "从权威蓝图读取 catalogVersion 与 catalogDigest"));
        Set<String> unique = new LinkedHashSet<>(request.capabilityIds());
        if (unique.size() != request.capabilityIds().size() || unique.stream().anyMatch(String::isBlank)) issues.add(issue(
                "INVALID_CAPABILITY_CATALOG", IssueSeverity.BLOCKING, "", "", "能力目录包含空值或重复 ID",
                "重新加载服务端版本化能力目录"));
        if ("factory-capability-catalog/v2".equals(request.catalogVersion()) && unique.size() != 26) issues.add(issue(
                "CAPABILITY_CATALOG_INCOMPLETE", IssueSeverity.BLOCKING, "", "", "v2 能力目录必须完整包含 26 项能力",
                "重新加载服务端 v2 能力目录，禁止用局部清单编译"));
    }

    private List<SourceFact> authoritativeFacts(CompilationRequest request, List<CompilationIssue> issues) {
        Map<String, SourceFact> byIdentity = new TreeMap<>();
        for (SourceFact fact : request.facts()) {
            if (!request.factoryId().equals(fact.factoryId())) {
                issues.add(issue("CROSS_FACTORY_FACT", IssueSeverity.BLOCKING, fact.capabilityId(), fact.factId(),
                        "事实不属于当前工厂，已拒绝进入编译产物", "重新从当前工厂的权威蓝图读取事实"));
                continue;
            }
            if (!request.capabilityIds().contains(fact.capabilityId())) {
                issues.add(issue("UNKNOWN_FACT_CAPABILITY", IssueSeverity.BLOCKING, fact.capabilityId(), fact.factId(),
                        "事实引用了能力目录之外的 capabilityId", "修正事实映射或升级能力目录"));
                continue;
            }
            if (!fact.authoritative()) {
                issues.add(issue("NON_AUTHORITATIVE_FACT_IGNORED", IssueSeverity.WARNING,
                        fact.capabilityId(), fact.factId(), "AI 建议、推断或未确认事实未进入权威产物",
                        "由用户确认，或从既有配置权威回读后再编译"));
                continue;
            }
            if (fact.factId().isBlank() || fact.subjectType().isBlank() || fact.subjectId().isBlank()
                    || fact.fieldPath().isBlank() || fact.valueType().isBlank()) {
                issues.add(issue("INVALID_CONFIRMED_FACT", IssueSeverity.BLOCKING,
                        fact.capabilityId(), fact.factId(), "确认事实缺少稳定身份、字段路径或类型",
                        "补齐结构化事实字段后重新确认"));
                continue;
            }
            SourceFact existing = byIdentity.get(fact.identityKey());
            if (existing != null && (!existing.valueType().equals(fact.valueType())
                    || !existing.value().equals(fact.value()))) {
                issues.add(issue("CONFIRMED_FACT_CONFLICT", IssueSeverity.BLOCKING,
                        fact.capabilityId(), fact.factId(), "同一主体字段存在两个不同的确认值",
                        "请用户裁决并只保留一个确认值"));
                continue;
            }
            if (existing == null || fact.factId().compareTo(existing.factId()) < 0) {
                byIdentity.put(fact.identityKey(), fact);
            }
        }
        return byIdentity.values().stream()
                .sorted(Comparator.comparing(SourceFact::capabilityId)
                        .thenComparing(SourceFact::identityKey)
                        .thenComparing(SourceFact::factId))
                .toList();
    }

    private Map<String, List<SourceFact>> groupByCapability(List<SourceFact> facts) {
        Map<String, List<SourceFact>> grouped = new LinkedHashMap<>();
        for (SourceFact fact : facts) grouped.computeIfAbsent(fact.capabilityId(), ignored -> new ArrayList<>()).add(fact);
        return grouped;
    }

    private List<AdapterReadiness> readiness(Set<String> capabilityIds) {
        return capabilityIds.stream().sorted().map(capabilityId -> {
            FactoryCapabilityAdapter adapter = adapters.get(capabilityId);
            if (adapter == null) return new AdapterReadiness(capabilityId, AdapterStatus.MISSING,
                    "", "", "", "实现只读适配器；B20 不允许假定为已支持");
            return new AdapterReadiness(capabilityId, AdapterStatus.AVAILABLE,
                    adapter.adapterId(), adapter.artifactType(), adapter.targetSchemaVersion(),
                    "可生成只读预览；物化、发布与激活仍未开放");
        }).toList();
    }

    private CapabilityChange change(
            String capabilityId, ChangeStatus status, List<SourceFact> facts, String blockingReason) {
        return new CapabilityChange(capabilityId, status,
                facts.stream().map(SourceFact::fieldPath).distinct().sorted().toList(),
                facts.stream().map(SourceFact::factId).distinct().sorted().toList(), blockingReason);
    }

    private boolean blocking(CompilationIssue issue) {
        return issue.severity() == IssueSeverity.BLOCKING;
    }

    private CompilationIssue issue(String code, IssueSeverity severity, String capabilityId,
                                   String factId, String message, String recovery) {
        return new CompilationIssue(code, severity, capabilityId, factId, message, recovery);
    }

    private String digest(
            CompilationRequest request,
            List<SourceFact> facts,
            List<CapabilityArtifact> artifacts,
            CapabilityChangeSet changeSet,
            List<AdapterReadiness> readiness,
            List<CompilationIssue> issues,
            String status) {
        Map<String, Object> content = new TreeMap<>();
        content.put("schemaVersion", FactoryBlueprintCompilationModels.SCHEMA_VERSION);
        content.put("factoryId", request.factoryId());
        content.put("blueprintId", request.blueprintId());
        content.put("blueprintVersion", request.blueprintVersion());
        content.put("catalogVersion", request.catalogVersion());
        content.put("catalogDigest", request.catalogDigest());
        content.put("status", status);
        content.put("confirmedFacts", facts);
        content.put("capabilityArtifacts", artifacts);
        content.put("changeSet", changeSet);
        content.put("adapterReadiness", readiness);
        content.put("issues", issues);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical(content).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof CharSequence || value instanceof Enum<?>) return quote(value.toString());
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof SourceFact fact) return canonical(Map.ofEntries(
                Map.entry("factId", fact.factId()), Map.entry("factoryId", fact.factoryId()),
                Map.entry("capabilityId", fact.capabilityId()), Map.entry("subjectType", fact.subjectType()),
                Map.entry("subjectId", fact.subjectId()), Map.entry("fieldPath", fact.fieldPath()),
                Map.entry("valueType", fact.valueType()), Map.entry("value", fact.value()),
                Map.entry("source", fact.source().name()),
                Map.entry("confirmationState", fact.confirmationState().name()),
                Map.entry("evidenceRefs", fact.evidenceRefs())));
        if (value instanceof CapabilityArtifact artifact) return canonical(Map.of(
                "capabilityId", artifact.capabilityId(), "artifactType", artifact.artifactType(),
                "targetSchemaVersion", artifact.targetSchemaVersion(), "payload", artifact.payload(),
                "sourceFactRefs", artifact.sourceFactRefs(), "publishState", artifact.publishState(),
                "businessWriteCount", artifact.businessWriteCount()));
        if (value instanceof CapabilityChangeSet set) return canonical(Map.of(
                "schemaVersion", set.schemaVersion(), "changes", set.changes(),
                "businessWriteCount", set.businessWriteCount()));
        if (value instanceof CapabilityChange change) return canonical(Map.of(
                "capabilityId", change.capabilityId(), "status", change.status().name(),
                "fieldPaths", change.fieldPaths(), "sourceFactRefs", change.sourceFactRefs(),
                "blockingReason", change.blockingReason()));
        if (value instanceof AdapterReadiness item) return canonical(Map.of(
                "capabilityId", item.capabilityId(), "status", item.status().name(),
                "adapterId", item.adapterId(), "artifactType", item.artifactType(),
                "targetSchemaVersion", item.targetSchemaVersion(), "nextStep", item.nextStep()));
        if (value instanceof CompilationIssue issue) return canonical(Map.of(
                "code", issue.code(), "severity", issue.severity().name(),
                "capabilityId", issue.capabilityId(), "factId", issue.factId(),
                "message", issue.message(), "recovery", issue.recovery()));
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
            return sorted.entrySet().stream()
                    .map(entry -> quote(entry.getKey()) + ":" + canonical(entry.getValue()))
                    .reduce("{", (left, item) -> left.equals("{") ? left + item : left + "," + item) + "}";
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::canonical)
                    .reduce("[", (left, item) -> left.equals("[") ? left + item : left + "," + item) + "]";
        }
        if (value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) items.add(Array.get(value, index));
            return canonical(items);
        }
        return quote(String.valueOf(value));
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
