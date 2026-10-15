package com.cretas.aims.blueprint.compilation;

import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.CapabilityArtifact;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.CompilationIssue;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.IssueSeverity;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.SourceFact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The read-only adapter for the {@code factory} root capability: it turns the confirmed facts about
 * the factory's own identity into a {@code FACTORY_SCOPE} artifact and nothing else.
 *
 * <p>The capability catalog registers {@code factory} as {@code READ_ONLY_IDENTITY} with exactly
 * two invariants -- <em>factoryId unchanged</em> and <em>tenant scope exact</em> -- and a recovery
 * strategy of <em>no mutation</em>. This class is that contract, and only that contract:</p>
 *
 * <ul>
 *   <li><b>tenant scope exact</b>: every fact must belong to the factory being compiled. The kernel
 *       already rejects cross-factory facts before any adapter runs; this adapter checks again so
 *       the invariant holds even when it is called directly, and reports
 *       {@code FACTORY_SCOPE_TENANT_MISMATCH}.</li>
 *   <li><b>factoryId unchanged</b>: a fact about the factory's identity must be <em>about this
 *       factory</em> ({@code subjectType=factory}, {@code subjectId=factoryId}), and a fact that
 *       carries the id itself must carry the same id. Anything else is
 *       {@code FACTORY_SCOPE_IDENTITY_DRIFT} -- a blueprint can describe a factory, it can never
 *       re-point it at another one.</li>
 *   <li><b>no mutation</b>: the artifact is a projection of confirmed facts with
 *       {@code businessWriteCount=0} and {@code publishState=NOT_PUBLISHED}. There is no
 *       materialize, no repository, no side effect -- the identity of a factory is not something a
 *       blueprint writes; the factory row already exists before any blueprint does.</li>
 * </ul>
 *
 * <p>The payload is deterministic (sorted field paths, sorted fact refs) so two compilations of the
 * same facts produce the same content digest, which is what lets a stale identity be detected as
 * drift by the downstream draft commands that pin this capability.</p>
 */
public final class FactoryScopeReader implements FactoryCapabilityAdapter {

    public static final String CAPABILITY_ID = "factory";
    public static final String ARTIFACT_TYPE = "FACTORY_SCOPE";
    public static final String SCHEMA_VERSION = "factory-scope/v1";
    public static final String ADAPTER_ID = "factory-scope-reader/v1";

    static final String SUBJECT_TYPE = "factory";
    /** Field paths whose value <em>is</em> the factory id; they may only ever equal the scope id. */
    static final Set<String> IDENTITY_FIELDS = Set.of("factoryId", "id");

    @Override
    public String adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public String capabilityId() {
        return CAPABILITY_ID;
    }

    @Override
    public String artifactType() {
        return ARTIFACT_TYPE;
    }

    @Override
    public String targetSchemaVersion() {
        return SCHEMA_VERSION;
    }

    @Override
    public boolean hasInput(CompilationContext context) {
        return !context.factsFor(CAPABILITY_ID).isEmpty();
    }

    @Override
    public AdapterOutput compile(CompilationContext context) {
        String factoryId = context.factoryId() == null ? "" : context.factoryId().trim();
        if (factoryId.isBlank()) {
            return new AdapterOutput(null, List.of(issue("FACTORY_SCOPE_FACTORY_ID_REQUIRED", "",
                    "工厂作用域编译缺少工厂 ID", "提供当前已授权工厂 ID 后重新编译")));
        }
        List<CompilationIssue> issues = new ArrayList<>();
        Map<String, String> identity = new TreeMap<>();
        for (SourceFact fact : context.factsFor(CAPABILITY_ID)) {
            if (!factoryId.equals(fact.factoryId())) {
                issues.add(issue("FACTORY_SCOPE_TENANT_MISMATCH", fact.factId(),
                        "事实属于另一个工厂，工厂作用域必须与当前租户完全一致",
                        "只使用当前工厂自己的权威事实"));
                continue;
            }
            if (!SUBJECT_TYPE.equals(fact.subjectType()) || !factoryId.equals(fact.subjectId())) {
                issues.add(issue("FACTORY_SCOPE_IDENTITY_DRIFT", fact.factId(),
                        "事实描述的不是当前工厂本身（subject 必须是 factory/" + factoryId + "）",
                        "工厂 ID 不可变更；修正事实的主体后重新编译"));
                continue;
            }
            if (IDENTITY_FIELDS.contains(fact.fieldPath()) && !factoryId.equals(fact.value())) {
                issues.add(issue("FACTORY_SCOPE_IDENTITY_DRIFT", fact.factId(),
                        "事实试图把工厂 ID 改成 " + fact.value(),
                        "工厂 ID 不可变更；蓝图只能描述工厂，不能把它指向另一家"));
                continue;
            }
            String previous = identity.putIfAbsent(fact.fieldPath(), fact.value());
            if (previous != null && !previous.equals(fact.value())) {
                issues.add(issue("FACTORY_SCOPE_FIELD_CONFLICT", fact.factId(),
                        "字段 " + fact.fieldPath() + " 有两个互相冲突的确认值",
                        "保留一个确认值后重新编译"));
            }
        }
        if (!issues.isEmpty()) {
            return new AdapterOutput(null, issues);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("factoryId", factoryId);
        payload.put("identity", Collections.unmodifiableMap(new TreeMap<>(identity)));
        payload.put("mutation", "NONE");
        payload.put("source", "CONFIRMED_FACTORY_FACTS");
        List<String> sourceRefs = context.factsFor(CAPABILITY_ID).stream()
                .map(SourceFact::factId).sorted().toList();
        CapabilityArtifact artifact = new CapabilityArtifact(
                CAPABILITY_ID, ARTIFACT_TYPE, SCHEMA_VERSION, payload, sourceRefs, "NOT_PUBLISHED", 0);
        return new AdapterOutput(artifact, List.of());
    }

    private CompilationIssue issue(String code, String factId, String message, String recovery) {
        return new CompilationIssue(code, IssueSeverity.BLOCKING, CAPABILITY_ID, factId, message, recovery);
    }
}
