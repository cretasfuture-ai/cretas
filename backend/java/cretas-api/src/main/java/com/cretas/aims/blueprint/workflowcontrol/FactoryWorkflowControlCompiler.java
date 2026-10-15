package com.cretas.aims.blueprint.workflowcontrol;

import com.cretas.aims.blueprint.rule.FactoryRuleCompiler;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.RuleDefinition;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.RuleKind;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.ValueType;
import com.cretas.aims.blueprint.rule.FactoryRuleUnitResolver;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.BranchPath;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.ControlBundle;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.ControlIssue;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.ControlPreview;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.IssueSeverity;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Static B23 compiler. It validates controlled overlays and never binds them to production runtime. */
public final class FactoryWorkflowControlCompiler {

    private final FactoryRuleCompiler rules;
    private final ObjectMapper canonicalMapper;

    public FactoryWorkflowControlCompiler(FactoryRuleUnitResolver units) {
        this.rules = new FactoryRuleCompiler(units);
        this.canonicalMapper = new ObjectMapper().findAndRegisterModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public ControlPreview compile(ControlBundle bundle) {
        List<ControlIssue> issues = new ArrayList<>();
        validateEnvelope(bundle, issues);
        if (bundle != null && bundle.variableCatalog() != null) {
            validatePolicies(bundle, issues);
        }
        issues.sort(java.util.Comparator.comparing(ControlIssue::path).thenComparing(ControlIssue::code));
        boolean blocked = issues.stream().anyMatch(item -> item.severity() == IssueSeverity.BLOCKING);
        return new ControlPreview(FactoryWorkflowControlModels.SCHEMA_VERSION,
                FactoryWorkflowControlModels.COMPILER_VERSION,
                blocked ? "BLOCKED" : "PREVIEW_READY", contentHash(bundle), bundle, issues,
                "NOT_PUBLISHED", "NOT_ACTIVATED", "NOT_BOUND_TO_RUNTIME", 0);
    }

    private void validateEnvelope(ControlBundle bundle, List<ControlIssue> issues) {
        if (bundle == null) {
            issues.add(block("CONTROL_BUNDLE_REQUIRED", "$", "控制语义不能为空", "重新编译已确认复杂路线"));
            return;
        }
        required(bundle.schemaVersion(), "SCHEMA_VERSION_REQUIRED", "$.schemaVersion", issues);
        if (!FactoryWorkflowControlModels.SCHEMA_VERSION.equals(bundle.schemaVersion())) {
            issues.add(block("SCHEMA_VERSION_UNSUPPORTED", "$.schemaVersion", "仅支持 factory-workflow-control/v1", "升级或重新生成控制语义"));
        }
        required(bundle.factoryId(), "FACTORY_REQUIRED", "$.factoryId", issues);
        required(bundle.sourceRouteDigest(), "SOURCE_ROUTE_DIGEST_REQUIRED", "$.sourceRouteDigest", issues);
        required(bundle.sourceWorkflowPreviewDigest(), "WORKFLOW_PREVIEW_DIGEST_REQUIRED", "$.sourceWorkflowPreviewDigest", issues);
        if (bundle.variableCatalog() == null) {
            issues.add(block("VARIABLE_CATALOG_REQUIRED", "$.variableCatalog", "必须锁定规则变量目录", "重新读取同工厂变量目录"));
        } else if (!bundle.factoryId().equals(bundle.variableCatalog().factoryId())) {
            issues.add(block("VARIABLE_CATALOG_FACTORY_MISMATCH", "$.variableCatalog.factoryId", "变量目录不属于目标工厂", "重新读取同工厂目录"));
        }
        if (bundle.branches().isEmpty() && bundle.joins().isEmpty() && bundle.reworkPolicies().isEmpty()) {
            issues.add(block("CONTROL_POLICY_REQUIRED", "$", "复杂路线至少需要一个 branch、join 或 rework policy", "补齐 B19 已确认控制语义"));
        }
    }

    private void validatePolicies(ControlBundle bundle, List<ControlIssue> issues) {
        Set<String> policyIds = new HashSet<>();
        Set<String> branchIds = new LinkedHashSet<>();
        for (int index = 0; index < bundle.branches().size(); index++) {
            var policy = bundle.branches().get(index);
            String path = "$.branches[" + index + "]";
            uniqueRequired(policy.policyId(), policyIds, "POLICY_ID", path + ".policyId", issues);
            required(policy.sourceProcessNodeId(), "SOURCE_PROCESS_REQUIRED", path + ".sourceProcessNodeId", issues);
            if (policy.selectionMode() == null) issues.add(block("SELECTION_MODE_REQUIRED", path + ".selectionMode", "必须明确分支选择模式", "选择 EXACTLY_ONE、AT_LEAST_ONE 或 ALL_MATCHING"));
            if (policy.unknownDisposition() == null) issues.add(block("UNKNOWN_DISPOSITION_REQUIRED", path + ".unknownDisposition", "必须明确 UNKNOWN 处置", "选择人工复核或阻断"));
            if (policy.paths().size() < 2) issues.add(block("BRANCH_PATHS_REQUIRED", path + ".paths", "条件分支至少需要两条路径", "补齐条件路径和默认路径"));
            if (policy.evidenceRefs().isEmpty()) issues.add(block("POLICY_EVIDENCE_REQUIRED", path + ".evidenceRefs", "分支策略缺少确认依据", "绑定访谈或文件证据"));
            long defaults = policy.paths().stream().filter(BranchPath::defaultPath).count();
            if (defaults != 1) issues.add(block("EXACTLY_ONE_DEFAULT_PATH_REQUIRED", path + ".paths", "每个分支必须且只能有一条显式 default/else", "确认无条件命中时的默认路径"));
            Set<String> targets = new HashSet<>();
            for (int pathIndex = 0; pathIndex < policy.paths().size(); pathIndex++) {
                BranchPath branch = policy.paths().get(pathIndex);
                String branchPath = path + ".paths[" + pathIndex + "]";
                uniqueRequired(branch.branchId(), branchIds, "BRANCH_ID", branchPath + ".branchId", issues);
                required(branch.targetProcessNodeId(), "TARGET_PROCESS_REQUIRED", branchPath + ".targetProcessNodeId", issues);
                if (!branch.targetProcessNodeId().isBlank() && !targets.add(branch.targetProcessNodeId())) {
                    issues.add(block("DUPLICATE_BRANCH_TARGET", branchPath + ".targetProcessNodeId", "同一分支策略不能重复指向相同目标", "合并重复路径"));
                }
                if (branch.evidenceRefs().isEmpty()) issues.add(block("BRANCH_EVIDENCE_REQUIRED", branchPath + ".evidenceRefs", "分支路径缺少确认依据", "绑定条件或 default 确认依据"));
                if (branch.defaultPath() && branch.guardRule() != null) {
                    issues.add(block("DEFAULT_PATH_GUARD_FORBIDDEN", branchPath + ".guardRule", "default/else 路径不能再带 guard", "移除 guard 或取消 default 标记"));
                } else if (!branch.defaultPath()) {
                    validateRule(bundle, branch.guardRule(), Set.of(RuleKind.ROUTE_GUARD), branchPath + ".guardRule", issues);
                }
            }
        }
        for (int index = 0; index < bundle.joins().size(); index++) {
            var join = bundle.joins().get(index);
            String path = "$.joins[" + index + "]";
            uniqueRequired(join.policyId(), policyIds, "POLICY_ID", path + ".policyId", issues);
            required(join.joinProcessNodeId(), "JOIN_PROCESS_REQUIRED", path + ".joinProcessNodeId", issues);
            if (join.mode() == null) issues.add(block("JOIN_MODE_REQUIRED", path + ".mode", "必须明确合流释放模式", "选择 ALL_SELECTED_COMPLETED、ANY_SELECTED_COMPLETED 或 MANUAL_RELEASE"));
            if (join.evidenceRefs().isEmpty()) issues.add(block("POLICY_EVIDENCE_REQUIRED", path + ".evidenceRefs", "合流策略缺少确认依据", "绑定负责人确认依据"));
            Set<String> upstream = new LinkedHashSet<>(join.upstreamBranchIds());
            if (upstream.size() < 2 || upstream.size() != join.upstreamBranchIds().size()) {
                issues.add(block("JOIN_UPSTREAMS_INVALID", path + ".upstreamBranchIds", "合流至少需要两个不重复的上游 branch IDs", "修正合流来源"));
            }
            for (String branchId : upstream) if (!branchIds.contains(branchId)) {
                issues.add(block("JOIN_UNKNOWN_BRANCH", path + ".upstreamBranchIds", "合流引用未知 branch: " + branchId, "引用同一 bundle 的 branch ID"));
            }
        }
        for (int index = 0; index < bundle.reworkPolicies().size(); index++) {
            var rework = bundle.reworkPolicies().get(index);
            String path = "$.reworkPolicies[" + index + "]";
            uniqueRequired(rework.policyVersionId(), policyIds, "POLICY_ID", path + ".policyVersionId", issues);
            required(rework.sourceProcessNodeId(), "SOURCE_PROCESS_REQUIRED", path + ".sourceProcessNodeId", issues);
            required(rework.returnProcessNodeId(), "RETURN_PROCESS_REQUIRED", path + ".returnProcessNodeId", issues);
            if (!rework.sourceProcessNodeId().isBlank() && rework.sourceProcessNodeId().equals(rework.returnProcessNodeId())) {
                issues.add(block("REWORK_SELF_RETURN_FORBIDDEN", path + ".returnProcessNodeId", "返工返回点不能等于触发点", "选择真实返工工序"));
            }
            validateRule(bundle, rework.triggerRule(), Set.of(RuleKind.ROUTE_GUARD, RuleKind.CRITICAL_LIMIT), path + ".triggerRule", issues);
            validateRule(bundle, rework.releaseRule(), Set.of(RuleKind.ROUTE_GUARD, RuleKind.CRITICAL_LIMIT), path + ".releaseRule", issues);
            if (rework.maxAttempts() < 1 || rework.maxAttempts() > 100) issues.add(block("REWORK_MAX_ATTEMPTS_INVALID", path + ".maxAttempts", "返工次数必须在 1..100", "确认可审计的有限次数"));
            if (rework.quantityPolicy() == null) issues.add(block("REWORK_QUANTITY_POLICY_REQUIRED", path + ".quantityPolicy", "必须明确返工数量策略", "选择整批、失败数量或人工拆分"));
            if (rework.genealogyPolicy() == null) issues.add(block("REWORK_GENEALOGY_REQUIRED", path + ".genealogyPolicy", "必须明确返工谱系策略", "选择同批 attempt 或子批次"));
            if (rework.dispositionOnLimit() == null) issues.add(block("REWORK_LIMIT_DISPOSITION_REQUIRED", path + ".dispositionOnLimit", "必须明确超限处置", "选择 HOLD、SCRAP 或 MANUAL_REVIEW"));
            if (rework.evidenceRefs().isEmpty()) issues.add(block("POLICY_EVIDENCE_REQUIRED", path + ".evidenceRefs", "返工策略缺少确认依据", "绑定质量/工艺负责人确认依据"));
        }
    }

    private void validateRule(
            ControlBundle bundle,
            RuleDefinition rule,
            Set<RuleKind> allowedKinds,
            String path,
            List<ControlIssue> issues) {
        if (rule == null) {
            issues.add(block("CONTROL_RULE_REQUIRED", path, "控制路径必须引用类型化规则", "创建并验证 factory-rule/v1"));
            return;
        }
        if (!bundle.factoryId().equals(rule.factoryId())) issues.add(block("RULE_FACTORY_MISMATCH", path + ".factoryId", "控制规则不属于目标工厂", "重新解析同工厂规则"));
        if (!allowedKinds.contains(rule.kind())) issues.add(block("RULE_KIND_NOT_ALLOWED", path + ".kind", "规则类型不适用于此控制点", "改用允许的 BOOLEAN 控制规则"));
        if (rule.resultType() != ValueType.BOOLEAN) issues.add(block("BOOLEAN_RULE_REQUIRED", path + ".resultType", "控制规则必须返回 BOOLEAN 三值结果", "改为 BOOLEAN rule"));
        var preview = rules.compile(rule, bundle.variableCatalog());
        if (!"PREVIEW_READY".equals(preview.status())) {
            List<String> codes = preview.validation().issues().stream().map(item -> item.code()).distinct().sorted().toList();
            issues.add(block("RULE_PREVIEW_BLOCKED", path, "规则静态验证或 test vectors 未通过: " + String.join(",", codes), "先修复 B21 规则预览"));
        }
    }

    private void required(String value, String code, String path, List<ControlIssue> issues) {
        if (value == null || value.isBlank()) issues.add(block(code, path, "必填标识或摘要缺失", "重新读取并锁定权威值"));
    }

    private void uniqueRequired(String value, Set<String> seen, String prefix, String path, List<ControlIssue> issues) {
        required(value, prefix + "_REQUIRED", path, issues);
        if (value != null && !value.isBlank() && !seen.add(value)) issues.add(block(prefix + "_DUPLICATE", path, "标识重复: " + value, "使用稳定且唯一的标识"));
    }

    private ControlIssue block(String code, String path, String message, String recovery) {
        return new ControlIssue(code, IssueSeverity.BLOCKING, path, message, recovery);
    }

    private String contentHash(ControlBundle bundle) {
        if (bundle == null) return "";
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            Object canonicalBundle = canonicalize(canonicalMapper.convertValue(bundle, Object.class));
            return HexFormat.of().formatHex(sha256.digest(canonicalMapper.writeValueAsBytes(java.util.Map.of(
                    "compilerVersion", FactoryWorkflowControlModels.COMPILER_VERSION,
                    "schemaVersion", FactoryWorkflowControlModels.SCHEMA_VERSION,
                    "bundle", canonicalBundle))));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } catch (Exception invalidContract) {
            throw new IllegalStateException("Control contract cannot be serialized", invalidContract);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalize).sorted(java.util.Comparator.comparing(this::sortKey)).toList();
        }
        return value;
    }

    private String sortKey(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("Canonical control value cannot be serialized", invalid);
        }
    }
}
