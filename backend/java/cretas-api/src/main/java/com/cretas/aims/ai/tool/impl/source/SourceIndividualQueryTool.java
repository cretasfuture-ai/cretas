package com.cretas.aims.ai.tool.impl.source;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.source.SourceIndividualDTO;
import com.cretas.aims.dto.source.SourceIndividualDetailDTO;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.enums.DnaVerificationStatus;
import com.cretas.aims.entity.source.SourceIndividual;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import com.cretas.aims.service.source.SourceIndividualDetailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 源头个体（这头牛）查询 Tool —— FEAT-AI-LINEAGE-INDIVIDUAL-TOOLS-20260915。
 *
 * <p>手机「AI 问答」问「SL-BULL-001 这头公牛有哪些后代」「哪一头牛的 DNA 比对不匹配」
 * 「这头牛去了哪」时, 意图 {@code SOURCE_INDIVIDUAL_QUERY} 路由到这里。
 *
 * <h3>三条入口, 一个都不给就追问</h3>
 * <ol>
 *   <li>{@code individual_code} → 档案编码精确匹配；</li>
 *   <li>{@code tag_number} → 耳标精确匹配。同一耳标可能有多份档案（换过耳标 / 重录）,
 *       取最新的一份并在摘要里<b>说出来</b>「同耳标有 N 份档案, 取最新」；</li>
 *   <li>只给 {@code dna_status} → 列该状态的个体（上限 {@value #LIST_LIMIT}, 多了说「还有 N 头」）。</li>
 * </ol>
 *
 * <h3>⛔ 找不到就说找不到</h3>
 * 编码 / 耳标查不到时明确回「没有编码为 X 的个体」, <b>不</b>模糊匹配成别的牛 ——
 * 一句「大概是这头」在溯源上就是假话。变异对照: 把找不到改成 searchByKeyword 兜底, 测试必须红。
 *
 * <h3>摘要里不放什么</h3>
 * 这是内部工具（AI 问答带 token）, 结构化段保留档案全字段；但人话摘要里<b>不出现</b>
 * {@code externalGeneBankId} / {@code dnaSampleCode} —— 那两样是给基因库对账用的, 念给人听没有意义,
 * 还会被当成「另一个编号」误报。
 *
 * @since 2026-09-15
 */
@Slf4j
@Component
public class SourceIndividualQueryTool extends AbstractBusinessTool {

    /** 只给 dna_status 时列表上限。 */
    static final int LIST_LIMIT = 50;

    static final Set<String> ALLOWED_ASKS = Set.of(
            "profile", "pedigree", "offspring", "siblings", "downstream", "list");

    @Autowired
    private SourceIndividualRepository sourceIndividualRepository;

    @Autowired
    private SourceIndividualDetailService sourceIndividualDetailService;

    @Override
    public String getToolName() {
        return "source_individual_query";
    }

    @Override
    public String getDescription() {
        return "查询源头个体（一头牛 / 一头猪）的档案、系谱、后代、同父兄弟、去向, 或按 DNA 比对状态列个体. "
                + "给档案编码 (如 SL-BULL-001) 或耳标号即可; 问「有哪些后代」用 ask=offspring, "
                + "「父本母本是谁」用 ask=pedigree, 「这头牛去了哪 / 做成了哪批货」用 ask=downstream, "
                + "「哪几头 DNA 不匹配 / 未采样」只给 dna_status. 适用: "
                + "\"SL-BULL-001 这头公牛有哪些后代\" / \"哪一头牛的 DNA 比对不匹配\" / \"这头牛去了哪\".";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> code = new HashMap<>();
        code.put("type", "string");
        code.put("description", "个体档案编码, 精确匹配 (例: SL-BULL-001). 与 tag_number 二选一");
        properties.put("individual_code", code);

        Map<String, Object> tag = new HashMap<>();
        tag.put("type", "string");
        tag.put("description", "耳标号, 精确匹配 (例: 110370281000001). 与 individual_code 二选一");
        properties.put("tag_number", tag);

        Map<String, Object> dna = new HashMap<>();
        dna.put("type", "string");
        dna.put("description", "DNA 比对状态: UNSAMPLED(未采样) / SAMPLED(已采样) / MATCHED(已比中) / "
                + "MISMATCH(不匹配). 也接受中文. 只给这个参数时列出该状态的全部个体");
        dna.put("enum", List.of("UNSAMPLED", "SAMPLED", "MATCHED", "MISMATCH"));
        properties.put("dna_status", dna);

        Map<String, Object> ask = new HashMap<>();
        ask.put("type", "string");
        ask.put("description", "要看哪一段: profile(档案, 默认) / pedigree(父本母本+后代+同父兄弟) / "
                + "offspring(后代) / siblings(同父兄弟) / downstream(去了哪: 原料批次→生产→成品→出货) / "
                + "list(按 dna_status 列个体)");
        ask.put("enum", List.copyOf(ALLOWED_ASKS));
        ask.put("default", "profile");
        properties.put("ask", ask);

        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    /** 没有单个必填项 —— 「三选一」由 {@link #validateRequiredParams} 表达。 */
    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    /**
     * 编码 / 耳标 / DNA 状态三样一个都没给 ⇒ 报 individual_code 缺失, 走基类标准的
     * NEED_MORE_INFO 出口（根级 needMoreInfo=true, 前端会当成追问而不是失败）。
     */
    @Override
    protected List<String> validateRequiredParams(Map<String, Object> params) {
        if (hasAnyLookupKey(params)) {
            return new ArrayList<>();
        }
        return List.of("individual_code");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("individual_code".equals(paramName)) {
            return "请问是哪头牛? 说档案编码 (如 SL-BULL-001) 或耳标号就行; "
                    + "要按 DNA 比对状态找的话, 说「不匹配 / 已比中 / 已采样 / 未采样」";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        if (!hasAnyLookupKey(params)) {
            // doExecute 被直接调用时的自保（正常入口在 validateRequiredParams 就拦下了）
            return buildSimpleResult(getParameterQuestion("individual_code"),
                    Map.of("status", "NEED_MORE_INFO"));
        }
        String code = blankToNull(getString(params, "individual_code"));
        String tag = blankToNull(getString(params, "tag_number"));
        String dnaRaw = blankToNull(getString(params, "dna_status"));
        String ask = normalizeAsk(getString(params, "ask"));

        if (ask != null && !ALLOWED_ASKS.contains(ask)) {
            return buildSimpleResult("ask 不合法 (允许: " + ALLOWED_ASKS + ")",
                    Map.of("status", "VALIDATION_ERROR", "field", "ask", "value", ask));
        }

        DnaVerificationStatus dnaStatus = null;
        if (dnaRaw != null) {
            dnaStatus = parseDnaStatus(dnaRaw);
            if (dnaStatus == null) {
                return buildSimpleResult(
                        "dna_status 不合法: " + dnaRaw + " (允许: UNSAMPLED/未采样, SAMPLED/已采样, "
                                + "MATCHED/已比中, MISMATCH/不匹配)",
                        Map.of("status", "VALIDATION_ERROR", "field", "dna_status", "value", dnaRaw));
            }
        }

        log.info("source_individual_query factoryId={} code={} tag={} dna={} ask={}",
                factoryId, code, tag, dnaStatus, ask);

        // ── 入口 3: 只给 DNA 状态 ⇒ 列表 ──────────────────────────────────
        if (code == null && tag == null) {
            return listByDnaStatus(factoryId, dnaStatus);
        }

        // ── 入口 1 / 2: 定位到一头 ─────────────────────────────────────────
        SourceIndividual individual;
        String lookupNote = null;
        if (code != null) {
            individual = sourceIndividualRepository
                    .findByFactoryIdAndIndividualCode(factoryId, code)
                    .orElse(null);
            if (individual == null) {
                return notFound("编码", code);
            }
        } else {
            List<SourceIndividual> byTag =
                    sourceIndividualRepository.findByFactoryIdAndTagNumberOrderByCreatedAtDesc(factoryId, tag);
            if (byTag == null || byTag.isEmpty()) {
                return notFound("耳标", tag);
            }
            individual = byTag.get(0);
            if (byTag.size() > 1) {
                lookupNote = "同耳标 " + tag + " 有 " + byTag.size() + " 份档案, 取最新的一份 ("
                        + individual.getIndividualCode() + ")";
            }
        }

        SourceIndividualDetailDTO detail =
                sourceIndividualDetailService.getDetail(factoryId, individual.getId());
        String effectiveAsk = ask == null || "list".equals(ask) ? "profile" : ask;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ask", effectiveAsk);
        data.put("individual", individualStruct(detail.getIndividual()));
        if (lookupNote != null) {
            data.put("lookupNote", lookupNote);
        }

        String body = switch (effectiveAsk) {
            case "offspring" -> {
                data.put("offspring", detail.getOffspring());
                yield offspringSummary(detail);
            }
            case "siblings" -> {
                data.put("siblingsBySire", detail.getSiblingsBySire());
                yield siblingsSummary(detail);
            }
            case "pedigree" -> {
                data.put("sire", detail.getSire());
                data.put("dam", detail.getDam());
                data.put("offspring", detail.getOffspring());
                data.put("siblingsBySire", detail.getSiblingsBySire());
                yield pedigreeSummary(detail);
            }
            case "downstream" -> {
                data.put("batches", detail.getBatches());
                data.put("downstream", detail.getDownstream());
                yield downstreamSummary(detail);
            }
            default -> {
                data.put("sire", detail.getSire());
                data.put("dam", detail.getDam());
                data.put("offspringCount", detail.getOffspring() == null ? 0 : detail.getOffspring().size());
                data.put("batchCount", detail.getBatches() == null ? 0 : detail.getBatches().size());
                yield profileSummary(detail);
            }
        };
        String summary = lookupNote == null ? body : lookupNote + "。" + body;
        data.put("summary", summary);
        return buildSimpleResult(summary, data);
    }

    // ──────────────────────────────────────────────────────────────────
    // 入口 3: 按 DNA 状态列表
    // ──────────────────────────────────────────────────────────────────

    private Map<String, Object> listByDnaStatus(String factoryId, DnaVerificationStatus status) {
        Page<SourceIndividual> page = sourceIndividualRepository
                .findByFactoryIdAndDnaVerificationStatusOrderByIndividualCodeAsc(
                        factoryId, status, PageRequest.of(0, LIST_LIMIT));
        List<SourceIndividual> rows = page == null ? List.of() : page.getContent();
        long total = page == null ? 0 : page.getTotalElements();

        List<Map<String, Object>> items = new ArrayList<>();
        for (SourceIndividual si : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", si.getId());
            m.put("individualCode", si.getIndividualCode());
            m.put("tagNumber", si.getTagNumber());
            m.put("species", si.getSpecies());
            m.put("breed", si.getBreed());
            m.put("sex", si.getSex());
            m.put("originFarm", si.getOriginFarm());
            m.put("dnaVerificationStatus", si.getDnaVerificationStatus() == null
                    ? null : si.getDnaVerificationStatus().name());
            items.add(m);
        }

        String label = dnaLabel(status);
        StringBuilder sb = new StringBuilder();
        if (total == 0) {
            sb.append("没有 ").append(label).append(" 的个体");
        } else {
            sb.append(label).append(" 的个体共 ").append(total).append(" 头");
            List<String> lines = new ArrayList<>();
            for (SourceIndividual si : rows) {
                String line = si.getIndividualCode();
                if (si.getTagNumber() != null) line += "(耳标 " + si.getTagNumber() + ")";
                lines.add(line);
            }
            com.cretas.aims.ai.tool.impl.lineage.LineageQueryTool.appendList(sb, lines);
            if (total > rows.size()) {
                sb.append("\n还有 ").append(total - rows.size()).append(" 头未列出");
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ask", "list");
        data.put("dnaStatus", status.name());
        data.put("dnaStatusLabel", label);
        data.put("items", items);
        data.put("listedCount", rows.size());
        data.put("totalCount", total);
        data.put("summary", sb.toString());
        return buildSimpleResult(sb.toString(), data);
    }

    // ──────────────────────────────────────────────────────────────────
    // 人话摘要 —— ⛔ 这里面不许出现 externalGeneBankId / dnaSampleCode
    // ──────────────────────────────────────────────────────────────────

    private static String profileSummary(SourceIndividualDetailDTO d) {
        SourceIndividualDTO i = d.getIndividual();
        StringBuilder sb = new StringBuilder();
        sb.append(i.getIndividualCode());
        appendPart(sb, "耳标 ", i.getTagNumber());
        appendPart(sb, "", i.getBreed());
        appendPart(sb, "", i.getSex());
        if (i.getBirthDate() != null) appendPart(sb, "出生 ", i.getBirthDate().toString());
        appendPart(sb, "来自 ", i.getOriginFarm());
        appendPart(sb, "", dnaLabel(i.getDnaVerificationStatus()));
        sb.append(" · 父本 ").append(parentWord(d.getSire(), i.getSireIndividualId()));
        sb.append(" / 母本 ").append(parentWord(d.getDam(), i.getDamIndividualId()));
        int offspring = d.getOffspring() == null ? 0 : d.getOffspring().size();
        int batches = d.getBatches() == null ? 0 : d.getBatches().size();
        sb.append(" · 后代 ").append(offspring).append(" 头");
        sb.append(" · 挂了 ").append(batches).append(" 个原料批次");
        return sb.toString();
    }

    private static String offspringSummary(SourceIndividualDetailDTO d) {
        return d.getIndividual().getIndividualCode() + " " + offspringClause(d);
    }

    /** 「有 N 头后代: …」/「没有登记后代」—— profile / pedigree / offspring 三处共用同一句。 */
    private static String offspringClause(SourceIndividualDetailDTO d) {
        List<SourceIndividualDetailDTO.Summary> offspring = d.getOffspring();
        if (offspring == null || offspring.isEmpty()) {
            return "没有登记后代";
        }
        return "有 " + offspring.size() + " 头后代" + joinSummaries(offspring);
    }

    private static String siblingsSummary(SourceIndividualDetailDTO d) {
        String code = d.getIndividual().getIndividualCode();
        if (d.getIndividual().getSireIndividualId() == null) {
            return code + " 未登记父本, 算不出同父兄弟";
        }
        List<SourceIndividualDetailDTO.Summary> sibs = d.getSiblingsBySire();
        if (sibs == null || sibs.isEmpty()) {
            return code + " 没有同父兄弟";
        }
        return code + " 有 " + sibs.size() + " 头同父兄弟" + joinSummaries(sibs);
    }

    private static String pedigreeSummary(SourceIndividualDetailDTO d) {
        SourceIndividualDTO i = d.getIndividual();
        StringBuilder sb = new StringBuilder(i.getIndividualCode());
        sb.append(": 父本 ").append(parentWord(d.getSire(), i.getSireIndividualId()));
        sb.append(", 母本 ").append(parentWord(d.getDam(), i.getDamIndividualId()));
        sb.append("; ").append(offspringClause(d));
        if (i.getSireIndividualId() != null) {
            List<SourceIndividualDetailDTO.Summary> sibs = d.getSiblingsBySire();
            sb.append("; 同父兄弟 ").append(sibs == null ? 0 : sibs.size()).append(" 头");
            if (sibs != null && !sibs.isEmpty()) {
                sb.append(joinSummaries(sibs));
            }
        }
        return sb.toString();
    }

    private static String downstreamSummary(SourceIndividualDetailDTO d) {
        String code = d.getIndividual().getIndividualCode();
        List<SourceIndividualDetailDTO.BatchSummary> batches = d.getBatches();
        if (batches == null || batches.isEmpty()) {
            return code + " 还没有入库成原料批次, 没有去向";
        }
        StringBuilder sb = new StringBuilder(code).append(" 的去向: 原料批次 ");
        for (int k = 0; k < batches.size(); k++) {
            if (k > 0) sb.append("、");
            SourceIndividualDetailDTO.BatchSummary b = batches.get(k);
            sb.append(b.getBatchNumber());
            if (b.getQuantity() != null) {
                sb.append("(").append(b.getQuantity().stripTrailingZeros().toPlainString());
                if (b.getUnit() != null) sb.append(b.getUnit());
                sb.append(")");
            }
        }
        SourceIndividualDetailDTO.Downstream ds = d.getDownstream();
        appendStage(sb, "生产批次", ds == null ? null : ds.getProductionBatches());
        appendStage(sb, "成品", ds == null ? null : ds.getFinishedBatches());
        appendStage(sb, "出货", ds == null ? null : ds.getShipments());
        boolean anyDownstream = ds != null && (
                nonEmpty(ds.getProductionBatches()) || nonEmpty(ds.getFinishedBatches()) || nonEmpty(ds.getShipments()));
        if (!anyDownstream) {
            sb.append(" —— 还没投产, 没有更下游的去向");
        }
        return sb.toString();
    }

    private static void appendStage(StringBuilder sb, String stage, List<TraceabilityDTO.LineageNode> nodes) {
        if (!nonEmpty(nodes)) return;
        sb.append(" → ").append(stage).append(" ");
        for (int k = 0; k < nodes.size(); k++) {
            if (k > 0) sb.append("、");
            TraceabilityDTO.LineageNode n = nodes.get(k);
            // 批次实体被删时 batchNumber 为 null —— 如实写「已不可查」, ⛔ 不编个号
            sb.append(n.getBatchNumber() != null ? n.getBatchNumber() : "(已不可查)");
            if (n.getName() != null) sb.append("(").append(n.getName()).append(")");
        }
    }

    private static boolean nonEmpty(List<?> l) {
        return l != null && !l.isEmpty();
    }

    /** 2026-09-16: 一头牛一行(≥2 头), 老板在手机气泡里能对着看; 1 头接在冒号后。返回值自带前导「: 」或「:」+ 每行「- 」。 */
    private static String joinSummaries(List<SourceIndividualDetailDTO.Summary> list) {
        List<String> lines = new ArrayList<>();
        for (SourceIndividualDetailDTO.Summary s : list) {
            lines.add(s.getIndividualCode() + "(" + dnaLabel(s.getDnaVerificationStatus()) + ")");
        }
        StringBuilder sb = new StringBuilder();
        com.cretas.aims.ai.tool.impl.lineage.LineageQueryTool.appendList(sb, lines);
        return sb.toString();
    }

    /** 父/母本一句话: 未登记 / 已登记但档案不可查 / 编码(耳标)。 */
    private static String parentWord(SourceIndividualDetailDTO.Summary p, String registeredId) {
        if (p == null) {
            return registeredId == null ? "未登记" : "已登记但档案不可查";
        }
        return p.getIndividualCode() + (p.getTagNumber() == null ? "" : "(耳标 " + p.getTagNumber() + ")");
    }

    private static void appendPart(StringBuilder sb, String prefix, String value) {
        if (value == null || value.isBlank()) return;
        sb.append(" · ").append(prefix).append(value);
    }

    /** 结构化段: 档案全字段（含基因库 ID / DNA 样本号 —— 只在这里, 不进摘要）。 */
    private static Map<String, Object> individualStruct(SourceIndividualDTO i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("individualCode", i.getIndividualCode());
        m.put("tagNumber", i.getTagNumber());
        m.put("species", i.getSpecies());
        m.put("breed", i.getBreed());
        m.put("sex", i.getSex());
        m.put("birthDate", i.getBirthDate());
        m.put("originFarm", i.getOriginFarm());
        m.put("externalGeneBankId", i.getExternalGeneBankId());
        m.put("dnaSampleCode", i.getDnaSampleCode());
        m.put("dnaVerificationStatus", i.getDnaVerificationStatus() == null
                ? null : i.getDnaVerificationStatus().name());
        m.put("dnaVerificationStatusLabel", dnaLabel(i.getDnaVerificationStatus()));
        m.put("sireIndividualId", i.getSireIndividualId());
        m.put("damIndividualId", i.getDamIndividualId());
        m.put("notes", i.getNotes());
        return m;
    }

    private Map<String, Object> notFound(String what, String value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "INDIVIDUAL_NOT_FOUND");
        data.put("lookupBy", what);
        data.put("value", value);
        return buildSimpleResult("没有" + what + "为 " + value + " 的个体", data);
    }

    // ──────────────────────────────────────────────────────────────────
    // DNA 状态措辞（LineageQueryTool 的摘要也用这两个, ⛔ 别再抄一份）
    // ──────────────────────────────────────────────────────────────────

    /** 摘要里的 DNA 状态措辞。{@code null} 状态 ⇒ 「DNA 状态未知」（如实, 不编成未采样）。 */
    public static String dnaLabel(DnaVerificationStatus status) {
        if (status == null) return "DNA 状态未知";
        return switch (status) {
            case UNSAMPLED -> "DNA 未采样";
            case SAMPLED -> "DNA 已采样";
            case MATCHED -> "DNA 已比中";
            case MISMATCH -> "DNA 不匹配";
        };
    }

    /** {@link #dnaLabel(DnaVerificationStatus)} 的字符串重载 —— 血缘节点上的状态是 {@code name()} 字符串。 */
    public static String dnaLabel(String statusName) {
        if (statusName == null || statusName.isBlank()) return dnaLabel((DnaVerificationStatus) null);
        try {
            return dnaLabel(DnaVerificationStatus.valueOf(statusName.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return "DNA 状态未知";
        }
    }

    /**
     * 枚举名或中文 → 枚举。⚠️ 用<b>整串</b>精确匹配, 不用 contains ——
     * 「不匹配」含「匹配」, contains 会把 MISMATCH 认成 MATCHED。认不出返回 {@code null}。
     */
    public static DnaVerificationStatus parseDnaStatus(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT)
                .replace("DNA", "").replace(" ", "").replace("　", "");
        return switch (s) {
            case "MISMATCH", "不匹配", "不符", "比对不匹配", "比对不一致", "不一致" -> DnaVerificationStatus.MISMATCH;
            case "MATCHED", "已比中", "比中", "匹配", "已匹配", "比对一致" -> DnaVerificationStatus.MATCHED;
            case "SAMPLED", "已采样", "采样" -> DnaVerificationStatus.SAMPLED;
            case "UNSAMPLED", "未采样", "没采样" -> DnaVerificationStatus.UNSAMPLED;
            default -> null;
        };
    }

    // ──────────────────────────────────────────────────────────────────
    // 小工具
    // ──────────────────────────────────────────────────────────────────

    private static boolean hasAnyLookupKey(Map<String, Object> params) {
        return params != null && (isPresent(params.get("individual_code"))
                || isPresent(params.get("tag_number"))
                || isPresent(params.get("dna_status")));
    }

    private static boolean isPresent(Object v) {
        return v != null && !v.toString().trim().isEmpty();
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * ask 归一化: 英文枚举原样小写; 槽位正则 / LLM 抽出来的中文说法映射到枚举
     * （「后代」→ offspring, 「去了哪」→ downstream ...）。映射不到的原样返回, 由调用方按
     * {@link #ALLOWED_ASKS} 报 VALIDATION_ERROR, ⛔ 不静默改成 profile。
     */
    public static String normalizeAsk(String s) {
        String t = blankToNull(s);
        if (t == null) return null;
        String lower = t.toLowerCase(Locale.ROOT);
        if (ALLOWED_ASKS.contains(lower)) return lower;
        if (lower.contains("后代") || lower.contains("子代") || lower.contains("子女") || lower.contains("儿女")) {
            return "offspring";
        }
        if (lower.contains("系谱") || lower.contains("父本") || lower.contains("母本") || lower.contains("父母")) {
            return "pedigree";
        }
        if (lower.contains("兄弟") || lower.contains("姐妹") || lower.contains("同父")) {
            return "siblings";
        }
        if (lower.contains("去了哪") || lower.contains("去向") || lower.contains("流向") || lower.contains("哪些批次")) {
            return "downstream";
        }
        if (lower.contains("档案")) {
            return "profile";
        }
        return lower;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
