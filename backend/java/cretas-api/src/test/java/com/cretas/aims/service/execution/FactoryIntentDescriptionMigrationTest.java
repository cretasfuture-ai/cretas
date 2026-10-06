package com.cretas.aims.service.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261101_58: 意图描述 / 按钮标题去开发者字样。
 *
 * <p>迁移是纯数据改写, 单测碰不到真库, 所以这里守的是迁移文件本身的三个性质:
 * <ol>
 *   <li><b>新文本干净</b>: 每条新描述 / 新标题都过 {@link ClarificationButtonText#looksLikeDeveloperText}
 *       (Sprint / Loop / 灵魂 / R1-R4 / Tool / 驼峰字段名 / 枚举码都不许再出现) 且是中文;</li>
 *   <li><b>幂等 + 不踩手改</b>: 每条 UPDATE 都带「旧文本仍等于」的守卫和 {@code deleted_at IS NULL};</li>
 *   <li><b>仪器活着</b>: 守卫里的旧文本绝大多数<i>确实</i>会被同一个判据命中 —— 否则「新文本干净」
 *       只是因为判据什么都抓不到 (恒真式)。</li>
 * </ol>
 * 已在 prod 只读盘点 + pglast 真解析器 + 内存表模拟 (跑 2 遍 / 手改行对照) 验证过执行语义, 见提交说明。
 */
@DisplayName("V20261101_58 工厂意图描述去开发者字样")
class FactoryIntentDescriptionMigrationTest {

    private static final String FILE = "/db/flyway/V20261101_58__factory_intent_descriptions_without_developer_text.sql";

    private static final Pattern UPDATE = Pattern.compile(
            "UPDATE ai_intent_configs SET\\s+(description|intent_name) = \\$d\\$(.*?)\\$d\\$,"
                    + "(.*?)WHERE intent_code = '([A-Z0-9_]+)'(.*?)\\s+AND (description|intent_name) = \\$d\\$(.*?)\\$d\\$;",
            Pattern.DOTALL);

    private record Stmt(String column, String newText, String body, String code, String guards,
                        String guardColumn, String oldText) {
    }

    @Test
    @DisplayName("111 条 UPDATE (106 描述 + 5 标题), 每条新文本干净且是中文, 同一意图同一列只改一次")
    void everyNewTextIsCleanChinese() throws IOException {
        List<Stmt> stmts = parse();

        assertThat(stmts).hasSize(111);
        assertThat(stmts.stream().filter(s -> s.column().equals("description")).count()).isEqualTo(106);
        assertThat(stmts.stream().filter(s -> s.column().equals("intent_name")).count()).isEqualTo(5);
        Set<String> seen = new HashSet<>();
        for (Stmt s : stmts) {
            assertThat(seen.add(s.column() + ":" + s.code())).as("重复改写 " + s.code()).isTrue();
            assertThat(ClarificationButtonText.looksLikeDeveloperText(s.newText()))
                    .as(s.code() + " 新文本仍含开发者字样: " + s.newText()).isFalse();
            assertThat(s.newText()).as(s.code()).matches("(?s).*[\\u4e00-\\u9fff].*");
            assertThat(s.newText()).as(s.code() + " 新旧相同 = 空改").isNotEqualTo(s.oldText());
            assertThat(s.newText().length()).as(s.code() + " 新文本不能是空壳")
                    .isGreaterThanOrEqualTo(s.column().equals("description") ? 6 : 3);
        }
    }

    @Test
    @DisplayName("幂等守卫: 每条只在旧文本仍相等时生效, 且只动未删除的行, 并递增 config_version")
    void everyUpdateIsGuardedByTheOldValue() throws IOException {
        for (Stmt s : parse()) {
            assertThat(s.guardColumn()).as(s.code() + " 守卫列必须与改写列一致").isEqualTo(s.column());
            assertThat(s.guards()).as(s.code()).contains("deleted_at IS NULL");
            assertThat(s.body()).as(s.code()).contains("config_version = COALESCE(config_version, 0) + 1");
        }
    }

    @Test
    @DisplayName("对照 (判据活着): 旧文本里绝大多数确实带开发者字样, 判据不是什么都抓不到")
    void theDetectorFiresOnTheOldTexts() throws IOException {
        List<Stmt> descriptions = parse().stream().filter(s -> s.column().equals("description")).toList();
        long flagged = descriptions.stream()
                .filter(s -> ClarificationButtonText.looksLikeDeveloperText(s.oldText())).count();

        // 实测 (本文件生成时): 106 条旧描述里被判据命中的占大头; 没命中的是「版本快照测试」「mark」
        // 「download URL」这类判据看不见的写法 —— 它们靠盘点时的人工 / 更宽的检测改写 (见迁移头注释)。
        assertThat(flagged).as("命中的旧描述数").isGreaterThanOrEqualTo(95);
        // 且新文本命中数为 0 (与上一条合起来: 判据能抓到旧的, 抓不到新的)
        assertThat(descriptions.stream()
                .filter(s -> ClarificationButtonText.looksLikeDeveloperText(s.newText())).count()).isZero();
        // 用户在 prod 截到的原文必须在被改写之列
        List<String> codes = new ArrayList<>();
        descriptions.forEach(s -> codes.add(s.code()));
        assertThat(codes).contains("HACCP_STATUS_QUERY", "PRODUCTION_BATCH_CREATE", "SHIPMENT_CONFIRM_CREATE");
    }

    // ==================== helpers ====================

    private List<Stmt> parse() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream(FILE)) {
            assertThat(in).as("迁移文件在 classpath 上: " + FILE).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Stmt> out = new ArrayList<>();
        Matcher m = UPDATE.matcher(sql);
        while (m.find()) {
            out.add(new Stmt(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6), m.group(7)));
        }
        // 文件里 UPDATE 的总数 = 解析出的条数 (没有被正则漏掉的)
        assertThat(out).hasSize(countOccurrences(sql, "UPDATE ai_intent_configs SET"));
        return out;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
