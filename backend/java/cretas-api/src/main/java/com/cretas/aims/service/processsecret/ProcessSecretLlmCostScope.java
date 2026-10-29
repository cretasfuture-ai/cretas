package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.security.AuthenticatedActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 发给大模型的成本数据按<b>当前调用者</b>裁剪 (Steve 10-29 修订: AI 对话可以带保密产品, 只要不泄露到别的工厂、
 * 不被 AI 学走再对外复述; 用提示词 / 系统约束限制, 不能一刀切)。
 *
 * <ul>
 *   <li>保密负责人 / 持有效授权的人 (对该产品「完整查看」放行): 提示词、上下文、工具结果照常带该产品的成本。</li>
 *   <li>其他人 (同厂无授权、平台、他厂、认不出身份 —— 含没有用户的定时任务): 该产品的成本金额不进提示词;
 *       合计只在他能看的行上逐行累加 (不做「总数 − 保密」), 并注明「不含 N 个保密产品」。</li>
 * </ul>
 *
 * <p>判定走 {@link ProcessSecretAccessService#decide} (与 HTTP 出口同一个判定点); 被隐藏范围连同牵涉这些产品的
 * 生产计划一起给出 ({@link ProcessSecretAggregateExclusions#forHidden}: 多产出计划的锚点是原料 id, 只按产品排除会漏)。
 * 取不到范围时直接抛出, 调用方据此不发 (fail-closed)。</p>
 *
 * <p>「不含 N 个保密产品」的 N 只数对该调用者隐藏的、真正受保护的产品; 计划锚点和同计划的普通产品不算。</p>
 */
@Component
public class ProcessSecretLlmCostScope {

    public static final String WITHHELD_REASON = "该产品工艺已设为保密，当前账号无权查看其成本，未提供给 AI 分析";

    /** 共享的 AI 分析 / 报告条目对当前读者不可见时的占位 (与其他页面「已保密」一致)。 */
    public static final String READ_WITHHELD = "已保密（该分析含工艺保密产品的数据，当前账号无权查看）";

    /**
     * 当前读者 / 调用者在本厂的保密视角 (一次判定, 多处复用)。
     * {@code tier}: 共享缓存档位; {@code stamp}: 给新生成的分析条目打的戳 (生成时的保密产品集合 + 生成者可完整查看的集合)。
     */
    public record ReaderView(boolean factoryHasProtected, String protectedHash, Set<String> fullView, boolean fullForAll) {
        public static final ReaderView NO_PROTECTED = new ReaderView(false, "", Set.of(), true);

        /** 没有保密产品 = 空串 (沿用原缓存); 否则 = 本厂保密产品集合 + 可完整查看集合 的稳定哈希。 */
        public String tier() {
            return factoryHasProtected ? "ps-" + sha256(protectedHash + "|" + String.join(",", fullView)).substring(0, 16) : "";
        }

        /** 审计里给人看的档位: 本厂没有保密产品 / 全部能看 / 部分能看 / 都不能看。 */
        public String auditLabel() {
            if (!factoryHasProtected) return "NO_PROTECTED";
            if (fullForAll) return "FULL";
            return fullView.isEmpty() ? "NONE" : "PARTIAL";
        }

        /** 新生成条目的戳; 没有保密产品时为 null (不需要)。 */
        public String stamp() {
            return factoryHasProtected ? protectedHash + ";" + String.join(",", fullView) : null;
        }

        /**
         * 这个读者能不能看一条按 {@link #stamp()} 生成的共享条目: 没有保密产品 / 读者能看全 → 能 (含没有戳的旧条目);
         * 否则只有戳在、保密产品集合没变、且生成者能看的保密产品读者都能看, 才能。
         */
        public boolean mayRead(String rowStamp) {
            if (!factoryHasProtected || fullForAll) return true;
            if (rowStamp == null || rowStamp.indexOf(';') < 0) return false;
            String hash = rowStamp.substring(0, rowStamp.indexOf(';'));
            String ids = rowStamp.substring(rowStamp.indexOf(';') + 1);
            if (!hash.equals(protectedHash)) return false;
            return ids.isEmpty() || fullView.containsAll(List.of(ids.split(",")));
        }
    }

    /**
     * 有保密产品的工厂, 所有带业务数据的 AI 对话 / 分析的系统约束 (唯一一份, 各提示词构造处引用它)。
     */
    public static final String HARNESS_INSTRUCTION =
            "【工艺保密约束】本工厂有工艺保密产品。对话里出现的保密产品配方、工艺与成本，只用于回答当前这位用户在本次对话中的问题；"
            + "不得写入任何可共享或可复用的内容（共享报告、模板、知识库、记忆、示例），不得建议把它们发给他人或其他工厂。";

    /**
     * 对当前调用者隐藏的范围。{@code productTypeIds} / {@code planIds} 是排除范围 (含计划锚点),
     * {@code protectedProductTypeIds} 只是被隐藏的、真正受保护的产品, {@code protectedProductsByPlan} 是每个被排除计划背后的这些产品。
     */
    public record Excluded(Set<String> productTypeIds, Set<String> planIds, Set<String> protectedProductTypeIds,
                           Map<String, Set<String>> protectedProductsByPlan, Set<String> planProductTypeIds) {
        public static final Excluded NONE = new Excluded(Set.of(), Set.of(), Set.of(), Map.of(), Set.of());

        /**
         * 按产品出数的地方 (经营报告毛利榜等) 要遮的产品: 隐藏的保密产品、计划锚点, 以及与它们同计划联产的普通产品
         * (联产品的成本与保密产品分摊, 单独给会泄露)。
         */
        public Set<String> withheldProducts() {
            Set<String> all = new java.util.TreeSet<>(productTypeIds);
            all.addAll(planProductTypeIds);
            return all;
        }

        public boolean isEmpty() {
            return productTypeIds.isEmpty() && planIds.isEmpty();
        }

        public boolean coversProduct(String productTypeId) {
            return productTypeId != null && productTypeIds.contains(productTypeId);
        }

        public boolean coversPlan(String planId) {
            return planId != null && planIds.contains(planId);
        }

        /** 批次: 产品或所属计划牵涉被隐藏的保密产品即排除; 有隐藏范围而这一行认不出产品时同样排除 (fail-closed)。 */
        public boolean coversBatch(String productTypeId, String planId) {
            if (isEmpty()) return false;
            if (productTypeId == null || productTypeId.isBlank()) return true;
            return coversProduct(productTypeId) || coversPlan(planId);
        }

        /**
         * 产品级成本 (由多个批次累加): 产品本身在范围内, 或任一来源批次的计划牵涉被隐藏的保密产品, 就整份不给。
         * 来源计划未知 ({@code null}) 而有隐藏范围时同样不给 (fail-closed)。
         */
        public boolean coversProductOrAnyPlan(String productTypeId, Collection<String> sourcePlanIds) {
            if (isEmpty()) return false;
            if (productTypeId == null || coversProduct(productTypeId) || sourcePlanIds == null) return true;
            return sourcePlanIds.stream().anyMatch(this::coversPlan);
        }

        /** 一行被排除时, 它背后是哪几个被隐藏的保密产品 (计数用; 锚点 / 普通产品不算)。 */
        public Set<String> protectedBehind(String productTypeId, Collection<String> planIds) {
            Set<String> behind = new LinkedHashSet<>();
            if (productTypeId != null && protectedProductTypeIds.contains(productTypeId)) behind.add(productTypeId);
            if (planIds != null) {
                for (String planId : planIds) {
                    if (planId != null) behind.addAll(protectedProductsByPlan.getOrDefault(planId, Set.of()));
                }
            }
            return behind;
        }
    }

    /** 去掉隐藏行之后剩下的行, 以及去掉了几行、涉及几个被隐藏的保密产品 (给「不含 N 个保密产品」用)。 */
    public record Rows(List<Map<String, Object>> allowed, int excludedRows, int excludedProducts) {
        public boolean anyExcluded() {
            return excludedRows > 0;
        }

        /** 没排除就是空串; 排除了就是一句带「不含 N 个保密产品」的说明。 */
        public String note() {
            return excludedRows == 0 ? "" : ProcessSecretLlmCostScope.note(excludedProducts);
        }
    }

    /** N = 被隐藏的真正受保护的产品个数; 认不出是哪个保密产品 (N=0 但确有行被排除) 时不报数字。 */
    public static String note(int excludedProducts) {
        if (excludedProducts <= 0) {
            return "说明：以上成本与合计不含工艺保密产品相关的数据（当前账号无权查看其成本）。";
        }
        return "说明：以上成本与合计不含 " + excludedProducts + " 个保密产品（当前账号无权查看其成本）。";
    }

    private final ProcessSecretAggregateExclusions exclusions;
    private final ProcessSecretAccessService accessService;
    private final boolean standalone;

    @Autowired
    public ProcessSecretLlmCostScope(ProcessSecretAggregateExclusions exclusions, ProcessSecretAccessService accessService) {
        this.exclusions = exclusions;
        this.accessService = accessService;
        this.standalone = false;
    }

    private ProcessSecretLlmCostScope() {
        this.exclusions = null;
        this.accessService = null;
        this.standalone = true;
    }

    /** 单测里裸 new 出来的服务用的默认实例 (与 LlmEgressGuard.standalone() 同款): 没有保密产品。生产一律由 Spring 注入。 */
    public static ProcessSecretLlmCostScope standalone() {
        return new ProcessSecretLlmCostScope();
    }

    private static AuthenticatedActor caller() {
        return AuthenticatedActor.fromCurrentExecution().orElse(null);
    }

    private Set<String> protectedIds(String factoryId) {
        if (standalone) return Set.of();
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalStateException("工厂未识别，无法判定工艺保密范围，成本数据不发给 AI");
        }
        return accessService.protectedProductTypeIds(factoryId);
    }

    /** 当前调用者对哪些保密产品有「完整查看」(负责人 / 有效授权); 认不出身份 = 一个都没有。 */
    private Set<String> fullView(String factoryId, Set<String> protectedIds) {
        AuthenticatedActor actor = caller();
        Set<String> full = new TreeSet<>();
        for (String productTypeId : protectedIds) {
            if (accessService.decide(actor, factoryId, productTypeId, ProcessSecretAction.VIEW_FULL).permitted()) {
                full.add(productTypeId);
            }
        }
        return full;
    }

    /** 对当前调用者隐藏的范围 (完整授权的调用者 = 空)。 */
    public Excluded forCurrentCaller(String factoryId) {
        Set<String> protectedIds = protectedIds(factoryId);
        if (protectedIds.isEmpty()) return Excluded.NONE;
        Set<String> hidden = new TreeSet<>(protectedIds);
        hidden.removeAll(fullView(factoryId, protectedIds));
        if (hidden.isEmpty()) return Excluded.NONE;
        ProcessSecretAggregateExclusions.ProtectedScope scope = exclusions.forHidden(factoryId, hidden);
        return new Excluded(Set.copyOf(scope.scope().productTypeIds()), Set.copyOf(scope.scope().planIds()),
                scope.protectedProductTypeIds(), scope.protectedProductsByPlan(), scope.planProductTypeIds());
    }

    /**
     * 与调用者无关: 本厂全部保密产品的范围 (含牵涉它们的计划)。只给 AI 访问审计判「这次请求的数据是否牵涉保密产品」用,
     * ⛔ 不用来裁剪 (裁剪按 {@link #forCurrentCaller})。
     */
    public Excluded allProtected(String factoryId) {
        Set<String> protectedIds = protectedIds(factoryId);
        if (protectedIds.isEmpty()) return Excluded.NONE;
        ProcessSecretAggregateExclusions.ProtectedScope scope = exclusions.forHidden(factoryId, new TreeSet<>(protectedIds));
        return new Excluded(Set.copyOf(scope.scope().productTypeIds()), Set.copyOf(scope.scope().planIds()),
                scope.protectedProductTypeIds(), scope.protectedProductsByPlan(), scope.planProductTypeIds());
    }

    /** 与调用者无关: 结构化数据里任何一层出现本厂保密产品 / 牵涉它们的计划 (审计用, 口径同 {@link #containsProtected})。 */
    public boolean dataInvolvesAnyProtected(String factoryId, Object data) {
        Excluded all = allProtected(factoryId);
        return !all.isEmpty() && scan(all, data, 0);
    }

    /** 结构化数据里任何一层出现 {@code scope} 覆盖的产品 / 计划 (已算好范围时用, 免得重复计算)。 */
    public static boolean mentions(Excluded scope, Object data) {
        return !scope.isEmpty() && scan(scope, data, 0);
    }

    /** 本厂有没有任何保密产品 (与调用者无关)。 */
    public boolean factoryHasProtected(String factoryId) {
        return !protectedIds(factoryId).isEmpty();
    }

    /** 当前读者 / 调用者的保密视角 (一次判定)。 */
    public ReaderView readerView(String factoryId) {
        Set<String> protectedIds = protectedIds(factoryId);
        if (protectedIds.isEmpty()) return ReaderView.NO_PROTECTED;
        Set<String> full = fullView(factoryId, protectedIds);
        String protectedHash = sha256(String.join(",", new TreeSet<>(protectedIds))).substring(0, 16);
        return new ReaderView(true, protectedHash, full, full.containsAll(protectedIds));
    }

    /**
     * 共享 AI 分析缓存的保密档位: 本厂没有保密产品 = 空串 (沿用原缓存); 有保密产品 = 本厂保密产品集合与当前调用者
     * 可完整查看集合的稳定哈希 —— 同档复用, 跨档不复用; 新设一个保密产品后旧档位一律失效; 缓存行按工厂存, 跨厂不复用。
     */
    public String accessTier(String factoryId) {
        return readerView(factoryId).tier();
    }

    /** 有保密产品的工厂: 带业务数据的提示词要带的系统约束; 判定失败时也带 (宁多勿少)。 */
    public String harnessFor(String factoryId) {
        try {
            return factoryHasProtected(factoryId) ? HARNESS_INSTRUCTION : "";
        } catch (RuntimeException e) {
            return HARNESS_INSTRUCTION;
        }
    }

    public boolean isProtectedBatch(String factoryId, String productTypeId, String planId) {
        return forCurrentCaller(factoryId).coversBatch(productTypeId, planId);
    }

    /**
     * 批次行 (每行带 {@code productTypeId} / {@code productionPlanId}) 去掉对当前调用者隐藏的整行。
     * 合计由调用方在 {@link Rows#allowed()} 上逐行累加。
     */
    public Rows withoutProtectedBatches(String factoryId, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return new Rows(List.of(), 0, 0);
        Excluded excluded = forCurrentCaller(factoryId);
        if (excluded.isEmpty()) return new Rows(new ArrayList<>(rows), 0, 0);
        List<Map<String, Object>> allowed = new ArrayList<>(rows.size());
        Set<String> products = new LinkedHashSet<>();
        int excludedRows = 0;
        for (Map<String, Object> row : rows) {
            String productTypeId = text(row.get("productTypeId"));
            String planId = text(row.get("productionPlanId"));
            if (excluded.coversBatch(productTypeId, planId)) {
                excludedRows++;
                products.addAll(excluded.protectedBehind(productTypeId, planId == null ? List.of() : List.of(planId)));
            } else {
                allowed.add(row);
            }
        }
        // AI 访问审计: 这次请求的数据里有保密行 (已按调用者排除) —— 标进当前请求的那条记录
        if (excludedRows > 0) ProcessSecretAiAccessAudit.markCurrentInvolvesProtected();
        return new Rows(allowed, excludedRows, products.size());
    }

    /**
     * 中央兜底: 发给模型的结构化数据里, 任何一层出现对当前调用者隐藏的 {@code productTypeId} /
     * {@code productionPlanId} 就返回 true —— 说明调用方漏了过滤, 整次不发。
     */
    public boolean containsProtected(String factoryId, Object data) {
        Excluded excluded = forCurrentCaller(factoryId);
        if (excluded.isEmpty()) return false;
        return scan(excluded, data, 0);
    }

    private static boolean scan(Excluded excluded, Object node, int depth) {
        if (node == null) return false;
        if (depth > 12) return true;   // 太深看不完: 有隐藏范围时按「含保密」处理 (fail-closed)
        if (node instanceof Map<?, ?> map) {
            if (excluded.coversProduct(text(map.get("productTypeId")))
                    || excluded.coversPlan(text(map.get("productionPlanId")))) {
                return true;
            }
            for (Object value : map.values()) {
                if (scan(excluded, value, depth + 1)) return true;
            }
            return false;
        }
        if (node instanceof Collection<?> items) {
            for (Object item : items) {
                if (scan(excluded, item, depth + 1)) return true;
            }
        }
        return false;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
