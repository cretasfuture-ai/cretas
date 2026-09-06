package com.cretas.aims.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移级契约：软删实体依赖的那些<b>部分唯一索引</b>（{@code … WHERE deleted_at IS NULL}）必须一直躺在迁移里。
 *
 * <p><b>它补的是哪一块</b>（2026-09-07，P0 FIX-ENTITY-FULL-UNIQUE-VS-PROD-PARTIAL）：
 * 那次把 12 个软删实体上的 {@code @UniqueConstraint} 摘掉了——因为 JPA 表达不了部分唯一，
 * 实体一声明就在 H2 建出<b>全量</b>键，比 prod 严，「软删再建同键」在 prod 合法而在 H2 撞键。
 * 代价是<b>真实的</b>：H2 从此对这些表没有任何唯一性保护，
 * {@code LogisticsPlanGraphRepositoryTest.deliveryOrderCanOnlyBelongToOneActiveTrip}
 * 那条「第二个 stop 挂同一张订单必须被 DB 拒」的断言因此无法再成立，已删除并由本闸接管。
 *
 * <p>⚠️ <b>H2 补不回来</b>——不是没试：2026-09-07 在 H2 2.2.224 上实测
 * {@code CREATE UNIQUE INDEX ix ON t(a,b) WHERE deleted_at IS NULL} 直接
 * {@code JdbcSQLSyntaxErrorException 42000}，H2 不支持部分索引。
 * ⇒ 行为层面的证明只能在真 PostgreSQL 上做（本次在 docker 的 prod schema 副本上跑过四态：
 * 活行互斥 → 软删后同键可建 → 再建又被拒），CI 里能守住的是<b>机制还在不在</b>，也就是本闸。
 *
 * <p><b>守的是什么</b>：唯一性现在只由 prod 那个部分索引承担，实体侧不再有备份。
 * 谁要是在后续迁移里把某个索引删掉、或者重建成全量，本闸报红——⛔ 否则那会是一次
 * 「没有任何测试会红的」静默失守：H2 本来就不管，prod 上要等到真出重复数据才看得见。
 *
 * <p><b>阳性对照</b>：{@code deleted_at IS NULL} 这个片段本身必须在迁移全文里命中——
 * 读迁移那一步一旦写错（路径错、编码错），「每个索引都找不到」与「匹配方式坏了」完全同形。
 */
@DisplayName("软删实体依赖的部分唯一索引必须一直在迁移里")
class PartialUniqueIndexBackingSoftDeleteContractTest {

    private static final Path DB = Path.of("src/main/resources/db");

    /** 2026-09-07 摘掉实体声明的那 13 个键：索引名 → 它守着哪张表的什么。 */
    private static final Map<String, String> MUST_BE_PARTIAL_IN_MIGRATIONS = new LinkedHashMap<>();

    static {
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uk_production_plan_create_request", "production_plans 幂等键");
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uq_annual_tax_factory_user_year", "annual_tax_settlements 年度汇算唯一");
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uq_salary_factory_user_month", "salary_items 工资单月唯一");
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uk_poar_factory_name_active", "purchase_order_approval_rules 规则名唯一");
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uq_lda_resource_date", "logistics_daily_availability 资源日唯一");
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uq_ldo_batch_store", "logistics_delivery_orders 批次内门店唯一");
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uq_ls_trip_seq", "logistics_stops 车次内序号唯一");
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uq_ls_order_single_trip", "logistics_stops 一张订单只挂一个活停靠点");
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uq_lsm_factory_name", "logistics_store_master 门店名唯一");
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uq_lt_plan_tripno", "logistics_trips 计划内车次号唯一");
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uq_lvd_binding", "logistics_vehicle_drivers 车-司机绑定唯一");
    }

    /**
     * 🔴 这两个部分索引<b>只活在 prod 上，仓库迁移里没有</b>（2026-09-07 普查：prod 310 个非主键唯一索引、
     * 104 个部分索引，仓库里找不到名字的 74 个中<b>恰好这 2 个</b>是部分索引）。
     *
     * <p>后果是<b>从仓库迁移新建的库与 prod 不一致</b>：
     * {@code notify_templates} 的迁移建的是<b>全量</b> {@code CONSTRAINT uq_notify_templates_factory_code}
     * （prod 上那个已经不在了），{@code business_rules} 的迁移里<b>一个唯一约束都没有</b>。
     * ⇒ 新库上这两张表要么比 prod 严（软删再建同 code 会被拒），要么完全不设防。
     *
     * <p>⛔ 不在这道闸里顺手补迁移——补它要单独一版迁移 + 存量重复检查，与本次 P0 是两件事，已单独登记。
     * 补上之后请把这两条从这里搬到上面那张表里，本闸会因为「集合变了」报红提醒你搬。
     */
    private static final Set<String> PROD_ONLY_NOT_IN_MIGRATIONS = Set.of(
            "uq_notify_templates_factory_code_active",
            "uq_business_rules_factory_code_active");

    private static final Pattern CREATE_UNIQUE_INDEX =
            Pattern.compile("CREATE\\s+UNIQUE\\s+INDEX\\b[^;]*;", Pattern.CASE_INSENSITIVE);

    @Test
    void everyPartialUniqueIndexBackingASoftDeletedEntityIsStillDeclaredPartialInMigrations() throws IOException {
        String sql = readAllMigrations();

        assertThat(sql)
                .as("阳性对照：迁移全文里必须找得到 deleted_at IS NULL——找不到说明读迁移这一步本身就错了，"
                        + "那样「每个索引都缺失」是恒真的")
                .containsIgnoringCase("deleted_at IS NULL");

        List<String> partial = new ArrayList<>();
        Matcher m = CREATE_UNIQUE_INDEX.matcher(sql);
        while (m.find()) {
            String stmt = m.group();
            if (stmt.toLowerCase().contains("deleted_at is null")) partial.add(stmt);
        }
        System.out.println("PARTIAL_UNIQUE_INDEX_STATEMENTS_IN_MIGRATIONS=" + partial.size());

        List<String> missing = new ArrayList<>();
        List<String> notPartial = new ArrayList<>();
        for (Map.Entry<String, String> e : MUST_BE_PARTIAL_IN_MIGRATIONS.entrySet()) {
            String name = e.getKey();
            boolean declaredPartial = partial.stream().anyMatch(s -> containsWord(s, name));
            boolean declaredAtAll = CREATE_UNIQUE_INDEX.matcher(sql).results()
                    .anyMatch(r -> containsWord(r.group(), name));
            if (declaredPartial) continue;
            (declaredAtAll ? notPartial : missing).add(name + " (" + e.getValue() + ")");
        }

        assertThat(notPartial)
                .as("这些索引在迁移里还在，但已经不是 `WHERE deleted_at IS NULL` 的部分索引了。"
                        + "实体侧已经不声明唯一键了，改成全量等于让「软删再建同键」在生产上被拒。")
                .isEmpty();
        assertThat(missing)
                .as("这些索引在迁移里找不到了。唯一性现在只由它们承担，删掉它就是静默失守——"
                        + "H2 本来就不管，prod 上要等真出重复数据才看得见。")
                .isEmpty();

        // 那两个只活在 prod 上的：集合一变就报红, 提醒把补好迁移的那个搬到上面的表里。
        List<String> prodOnlyNowInMigrations = PROD_ONLY_NOT_IN_MIGRATIONS.stream()
                .filter(n -> partial.stream().anyMatch(s -> containsWord(s, n)))
                .sorted().toList();
        assertThat(prodOnlyNowInMigrations)
                .as("这些索引原本只活在 prod 上；现在迁移里有了 —— 把它从 PROD_ONLY_NOT_IN_MIGRATIONS "
                        + "搬到 MUST_BE_PARTIAL_IN_MIGRATIONS，让它从此受本闸保护")
                .isEmpty();
    }

    /**
     * 闭环：那个真 PostgreSQL 行为测试<b>必须存在，且必须被 CI 点名</b>。
     *
     * <p>🔴 这条回答的是一个具体的问题：<b>如果有人把 {@code SoftDeletePartialUniqueIndexPostgresContractTest}
     * 整个删掉，有没有任何东西会红？</b> 加这条之前答案是<b>没有</b> —— 它是 opt-in 的（没有
     * {@code CRETAS_WORKFLOW_PG_VERIFY} 就跳过），源码里也没有任何人引用它，
     * ⇒ 删掉它全量套件只会少一个 skipped，读数与「一切正常」完全重合。
     *
     * <p>⚠️ 光把它加进工作流的 {@code -Dtest=} 不够：Surefire 在「指定的测试里有一部分找不到」时不一定报错，
     * ⇒ 删文件仍可能悄悄通过。所以这里<b>两头都钉</b>：文件在不在（删文件 ⇒ 红）、工作流点没点它（从车道里摘掉 ⇒ 红）。
     *
     * <p>📌 顺带一个 2026-09-07 量到的读数：仓库里 <b>15 个</b> opt-in PG 测试，只有 <b>6 个</b>（含本次接入的这个）
     * 被 {@code java-gate-pr.yml} 点名，其余 <b>9 个在任何 CI 里都不跑</b>。
     * ⚠️ 障碍不是「自建 runner 没有 Docker」—— 那条车道 {@code runs-on: [self-hosted, cretas]}，
     * 用的是 runner 上的<b>原生 homebrew postgresql@17</b>，与 Docker 无关。⇒ 接进来只是往列表里加一个名字。
     */
    @Test
    void theRealPostgresBehaviourTestExistsAndIsNamedByCi() throws IOException {
        Path pgTest = Path.of("src/test/java/com/cretas/aims/integration",
                "SoftDeletePartialUniqueIndexPostgresContractTest.java");
        assertThat(Files.exists(pgTest))
                .as("行为层的证明只有真 PostgreSQL 跑得了（H2 建不出部分唯一索引）。这个文件没了，"
                        + "「软删后同键可复用 / 活行仍互斥」就再没有任何地方证过。")
                .isTrue();

        Path workflow = Path.of("../../../.github/workflows/java-gate-pr.yml");
        assertThat(Files.exists(workflow))
                .as("阳性对照：读不到工作流时，下面那条断言是恒假而不是恒真，但仍要点名说清楚是路径的问题")
                .isTrue();
        assertThat(Files.readString(workflow, StandardCharsets.UTF_8))
                .as("它必须留在 postgres-reversal-concurrency 那条车道的 -Dtest= 列表里——"
                        + "摘出去它就退回「opt-in 且没人跑」，与不存在读数相同")
                .contains("SoftDeletePartialUniqueIndexPostgresContractTest");
    }

    private static boolean containsWord(String haystack, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(haystack).find();
    }

    private static String readAllMigrations() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(DB)) {
            for (Path p : files.filter(f -> f.toString().toLowerCase().endsWith(".sql")).toList()) {
                sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sb.toString();
    }
}
