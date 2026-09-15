package com.cretas.aims.ai.tool.impl.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「四句真实问法各自会被哪个意图的关键词接住」的契约 —— 读的是<b>迁移 SQL 里那份关键词</b>,
 * 不在测试里再抄一份 (形态 D: 抄一份就会漂)。
 *
 * <h3>它量的是什么, 不量什么</h3>
 * 识别层短路径的打分是「问句 contains 关键词的个数」(IntentRecognitionPipelineServiceImpl 短问句分支),
 * 这里照这个口径比较 LINEAGE_QUERY 与 SOURCE_INDIVIDUAL_QUERY <b>两者之间</b>谁得分高。
 * ⚠️ 它<b>看不见</b>库里其它几十个意图 (它们的关键词散在几十条迁移里), 也看不见 LLM 兜底路由 ——
 * 所以「上 prod 后四句真机实测」这一步仍然不能省; 本闸只保证「我登记的关键词至少真的能命中我想命中的句子,
 * 而且不会把三类问题互相串到对方的工具上」。
 *
 * <p>自保: 先断言解析到的关键词数 &gt; 0 —— 正则解析失败最像「全都合规」。
 */
class LineageAndSourceIndividualIntentKeywordContractTest {

    private static final Path D4_REGISTRATION = Paths.get(
            "src/main/resources/db/flyway/V20260823_05__d4_intent_registration.sql");
    private static final Path THIS_MIGRATION = Paths.get(
            "src/main/resources/db/flyway/V20261101_44__ai_lineage_by_batch_number_and_source_individual_query.sql");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 任何 SQL 单引号串里的 JSON 数组字面量。 */
    private static final Pattern JSON_ARRAY_LITERAL = Pattern.compile("'(\\[\"[^']*\\])'");

    // ── 四句真机问法 (2026-09-15 19:0x 在 CATTLE_3701_001 上实测过的原句) ──
    private static final String Q_LINEAGE = "批次 SL-DEMO-BATCH-V1 来自哪几头牛";
    private static final String Q_OFFSPRING = "SL-BULL-001 这头公牛有哪些后代";
    private static final String Q_DNA = "哪一头牛的 DNA 比对不匹配";
    private static final String Q_WHERE = "这头牛去了哪";

    @Test
    @DisplayName("迁移里没有 Flyway 占位符写法, 且两个意图的关键词数组都解析得出来")
    void migrationParsesAndHasNoPlaceholder() throws IOException {
        String sql = read(THIS_MIGRATION);
        assertThat(sql).as("Flyway 把 $ 后面紧跟 { 当占位符, 注释里也算").doesNotContain("${");
        assertThat(lineageKeywords()).hasSizeGreaterThan(15);
        assertThat(sourceIndividualKeywords()).hasSizeGreaterThan(10);
    }

    @Test
    @DisplayName("「批次 … 来自哪几头牛」→ LINEAGE_QUERY 得分严格高于 SOURCE_INDIVIDUAL_QUERY")
    void lineageQuestionRoutesToLineage() throws IOException {
        assertWins(Q_LINEAGE, lineageKeywords(), "LINEAGE_QUERY", sourceIndividualKeywords(), "SOURCE_INDIVIDUAL_QUERY");
    }

    @Test
    @DisplayName("「这头公牛有哪些后代」/「DNA 比对不匹配」/「这头牛去了哪」→ SOURCE_INDIVIDUAL_QUERY 严格胜出")
    void individualQuestionsRouteToSourceIndividual() throws IOException {
        for (String q : List.of(Q_OFFSPRING, Q_DNA, Q_WHERE)) {
            assertWins(q, sourceIndividualKeywords(), "SOURCE_INDIVIDUAL_QUERY", lineageKeywords(), "LINEAGE_QUERY");
        }
    }

    @Test
    @DisplayName("阴性: 「今天屠宰了几头牛」不属于本任务, 两个意图都不该被它命中 (别把屠宰报工抢过来)")
    void slaughterCountIsNotClaimed() throws IOException {
        String q = "今天屠宰了几头牛, 产出多少公斤";
        assertThat(score(q, lineageKeywords())).as("LINEAGE_QUERY 命中: %s", hits(q, lineageKeywords())).isZero();
        assertThat(score(q, sourceIndividualKeywords())).as("SOURCE_INDIVIDUAL_QUERY 命中: %s", hits(q, sourceIndividualKeywords())).isZero();
    }

    // ── 打分 (与识别层短路径同口径: contains 计数) ───────────────────────

    private static void assertWins(String question, Set<String> winner, String winnerName,
                                   Set<String> loser, String loserName) {
        int w = score(question, winner);
        int l = score(question, loser);
        assertThat(w)
                .as("「%s」应至少命中 %s 的一个关键词; 命中: %s", question, winnerName, hits(question, winner))
                .isGreaterThan(0);
        assertThat(w)
                .as("「%s」%s=%d (%s) 必须严格高于 %s=%d (%s)", question, winnerName, w, hits(question, winner),
                        loserName, l, hits(question, loser))
                .isGreaterThan(l);
    }

    private static int score(String question, Set<String> keywords) {
        return hits(question, keywords).size();
    }

    private static List<String> hits(String question, Set<String> keywords) {
        String normalized = question.toLowerCase(Locale.ROOT).trim();
        List<String> out = new ArrayList<>();
        for (String kw : keywords) {
            if (kw != null && !kw.isEmpty() && normalized.contains(kw.toLowerCase(Locale.ROOT))) {
                out.add(kw);
            }
        }
        return out;
    }

    // ── 从迁移 SQL 取关键词 ─────────────────────────────────────────────────

    /**
     * LINEAGE_QUERY 在 prod 上 = D4 登记的原始数组 ∪ 本次迁移 upsert 的数组 (迁移 ON CONFLICT 时 jsonb 合并,
     * 这里同样取并集); 全新库上只有本次迁移那份 —— 它必须自带 D4 的 15 条, 下面顺手钉住这一点。
     */
    private static Set<String> lineageKeywords() throws IOException {
        List<String> d4 = arrayAfter(read(D4_REGISTRATION), "'LINEAGE_QUERY'");
        List<String> mine = arrayAfter(read(THIS_MIGRATION), "'LINEAGE_QUERY'");
        assertThat(mine)
                .as("V20261101_44 走 upsert: 全新库上它是 LINEAGE_QUERY 唯一的一份关键词, 必须包含 D4 的原始 15 条")
                .containsAll(d4);
        Set<String> out = new LinkedHashSet<>(d4);
        out.addAll(mine);
        return out;
    }

    private static Set<String> sourceIndividualKeywords() throws IOException {
        return new LinkedHashSet<>(arrayAfter(read(THIS_MIGRATION), "'SOURCE_INDIVIDUAL_QUERY'"));
    }

    /** 从 anchor (第一次出现处) 之后的第一段 JSON 数组字面量取关键词。 */
    private static List<String> arrayAfter(String sql, String anchor) throws IOException {
        int at = sql.indexOf(anchor);
        assertThat(at).as("迁移里应有 %s", anchor).isGreaterThanOrEqualTo(0);
        Matcher m = JSON_ARRAY_LITERAL.matcher(sql);
        String literal = null;
        while (m.find()) {
            if (m.start() > at) {
                literal = m.group(1);
                break;
            }
        }
        assertThat(literal).as("%s 之后应有一段 JSON 数组字面量", anchor).isNotNull();
        return JSON.readValue(literal, new TypeReference<List<String>>() { });
    }

    private static String read(Path p) throws IOException {
        assertThat(p).as("迁移文件必须存在, 否则这条闸在空集上恒绿").exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
