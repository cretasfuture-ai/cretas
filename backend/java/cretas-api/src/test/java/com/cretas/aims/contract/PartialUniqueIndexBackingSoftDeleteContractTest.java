package com.cretas.aims.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * 🔴 <b>{@code db/baseline/} 必须排除在扫描面之外 —— 它是 prod schema 的 pg_dump，不是迁移。</b>
     *
     * <p>2026-09-07 第一份真基线落盘（票 {@code OPS-FRESH-DB-BASELINE-BOOTSTRAP-20260907}）时实测到的
     * 两个后果，<b>对照组是同一棵树、只把 {@code db/baseline/} 挪走再跑一次</b>：
     * <br>· 读数被污染：{@code PARTIAL_UNIQUE_INDEX_STATEMENTS_IN_MIGRATIONS} <b>113 → 217</b>；
     * <br>· 本闸直接<b>变红</b>：{@code uk_encoding_rules}（登记在 {@link #PROD_ONLY_BENIGN}，
     *   意思是「只活在 prod 上」）在 dump 里当然有 ⇒ 闸判定它「进迁移了，快搬家」。
     *
     * <p>⚠️ <b>而更坏的是它没红的那一半</b>：这份 dump 里<b>那 13 个受守护的索引名一个不少</b>
     * （实测各 2–4 次命中）⇒ 有人删掉一条真迁移，本闸<b>照样绿</b>，因为 dump 顶上了。
     * ▎<b>一份 prod 的快照，会让「迁移里有没有」这个问题变得不可证伪</b>——
     * 快照里什么都有，那正是它的用途。
     *
     * <p>⛔ 所以这不是「顺手把红消掉」，是这道闸的问题定义本身要求排除它：
     * 本闸问的是<b>「从迁移新建的库</b>会不会重现 prod 已修的 bug」，而基线<b>按定义</b>不参与那条路径
     * （新库 = 灌基线 → {@code flyway baseline} → 只跑基线<b>之上</b>的迁移）。
     */
    private static final Path BASELINE_DIR = DB.resolve("baseline");

    /**
     * 扫描面：{@code db/} 下的 {@code .sql}，⛔ 但不含 {@link #BASELINE_DIR}。
     * ⚠️ 两处 {@code Files.walk(DB)} 都必须走这一个判据，⛔ 不许一处改一处漏——
     * 那会让「统计用的分母」与「断言用的语料」是两棵不同的树（形态 D）。
     */
    private static boolean isMigrationSql(Path f) {
        return f.toString().toLowerCase().endsWith(".sql") && !f.startsWith(BASELINE_DIR);
    }

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
        // 2026-09-07 从 PROD_ONLY_NOT_IN_MIGRATIONS 搬上来的两条（票 OPS-PROD-ONLY-PARTIAL-INDEXES，
        // 迁移 V20261101_39）。搬家本身是被下面那条断言逼出来的：迁移一落地，
        // 「原本只活在 prod 上、现在迁移里有了」就会报红提醒搬 —— 这次它确实红了。
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uq_business_rules_factory_code_active", "business_rules 规则 code 唯一");
        MUST_BE_PARTIAL_IN_MIGRATIONS.put("uq_notify_templates_factory_code_active", "notify_templates 模板 code 唯一");
    }

    /**
     * 🔴 这两个部分索引<b>只活在 prod 上，仓库迁移里没有</b>（2026-09-07 普查：prod 310 个非主键唯一索引、
     * 104 个部分索引，仓库里找不到名字的 74 个中<b>恰好这 2 个</b>是部分索引）。
     *
     * <p>🔴 <b>后果比「不一致」更具体：从迁移新建的任何 PostgreSQL 库上，本 PR 刚修的这个 bug 会原样重现</b>，
     * 只是载体从 H2 换成 PG。两张表的迁移建的都是<b>全量</b>唯一约束——
     * {@code notify_templates} 是具名的 {@code CONSTRAINT uq_notify_templates_factory_code}
     * （{@code V20260621_01:16}），{@code business_rules} 是<b>内联无名</b>的
     * {@code UNIQUE(factory_id, rule_code)}（{@code V20260622_01:19}）——而 prod 上这两个全量键都<b>已经不在了</b>，
     * 只剩 {@code …_active} 的部分索引。⇒ 新库比 prod 严 ⇒ 这两张软删表「软删后重建同 code」会撞约束。
     *
     * <p>⚠️ <b>这段话我一开始写错了</b>：原文说「{@code business_rules} 的迁移里一个唯一约束都没有」，
     * 由此把它归成「完全不设防」——方向正好相反。成因是普查器<b>只认 {@code CREATE UNIQUE INDEX}</b>，
     * 对 {@code CREATE TABLE} 里的内联 {@code UNIQUE(...)} 是<b>全盲</b>的（全仓 123 处）。
     * ⇒ 一个看不见某种写法的仪器，不会报「我不确定」，它报的是<b>「没有」</b>。
     * 而那句「没有」正是当时决定延期的依据 ⇒ 依据错了，延期结论要重新过一遍（见票）。
     *
     * <p>⛔ 不在这道闸里顺手补迁移——补它要单独一版迁移 + 存量重复检查，与本次 P0 是两件事，已单独登记。
     * 补上之后请把这两条从这里搬到上面那张表里，本闸会因为「集合变了」报红提醒你搬。
     *
     * <p>✅ <b>2026-09-07 已补</b>（票 {@code OPS-PROD-ONLY-PARTIAL-INDEXES}，迁移
     * {@code V20261101_39}）：两条都搬进上面那张表了，本集合现在<b>空着</b>。
     * ⛔ 空集合<b>不要删</b>——它是这条流程的入口：下次再发现「只活在 prod 上」的<b>真缺口</b>，
     * 先登记到这里，本闸就会在补上迁移的那一刻报红提醒搬家。这次它确实是这么工作的。
     *
     * <p>⚠️ <b>空 ≠ 这一类不存在。</b>「已判为良性」的那些在
     * {@link #PROD_ONLY_BENIGN} 里 —— 两者刻意分开，⛔ 不折叠成一个空集合：
     * 折叠之后「不存在」与「存在但判过是良性」读数相同，下一个人不知道曾经有过判定。
     *
     * <p>📌 补的时候实测到的两件事，写在这里免得下次重新查：
     * <br>① prod 上这两张表的<b>全量</b>唯一键早就没了（约束只剩主键），⇒ 迁移必须允许「已经是
     * prod 那个形态」而不是硬删；但 ⛔ 不允许「两者都没有」——那是完全不设防。
     * <br>② {@code business_rules} 那个全量键是 {@code CREATE TABLE} 里的<b>内联无名</b>
     * {@code UNIQUE(...)}，名字由 PG 生成 ⇒ 迁移里按<b>约束定义</b>找它，⛔ 不能写死名字。
     * <b>名字不是身份</b>——这条与下面 {@code uq_bpic_recipe_wp} 那次误判同源。
     */
    private static final Set<String> PROD_ONLY_NOT_IN_MIGRATIONS = Set.of();

    /**
     * <b>确实只活在 prod 上、但已判定为良性</b>的部分唯一索引。⛔ 不要并进
     * {@link #PROD_ONLY_NOT_IN_MIGRATIONS}：那一格是「待补的真缺口」。
     *
     * <p>{@code uk_encoding_rules}（2026-09-07 实测）：
     * <br>· 迁移里它<b>只出现在一条 {@code DROP CONSTRAINT}</b>（{@code V20260824_05:31}）——
     *   ⚠️ <b>名字出现 ≠ 索引被创建</b>，这正是普查判据栽过的地方。
     * <br>· 同一支迁移紧接着建了 {@code idx_encoding_rules_unique}
     *   （{@code V20260824_05:34}）：{@code ON encoding_rules (factory_id, entity_type) WHERE deleted_at IS NULL}。
     * <br>· prod 上<b>两个都在，且 {@code indexdef} 的表/列/谓词完全一致</b>——
     *   即 prod 背着一对<b>冗余重复</b>的唯一索引，而新库只拿到后者。
     * <br>⇒ 唯一性在新库上<b>由等价索引承担</b>，不存在「新库比 prod 松」的窗口 ⇒ 良性。
     * ⚠️ 良性的依据是<b>那两个定义相等</b>，⛔ 不是「名字看起来像一对」。哪天
     * {@code idx_encoding_rules_unique} 被改窄或删掉，这条判定就作废。
     *
     * <p>🔴 <b>{@code uq_bpic_recipe_wp} 曾被误判为 prod-only，记在这里免得下次再判错一遍</b>：
     * 它在 {@code V20261028_60:24} 以旧名 {@code uq_bps_recipe_wp} 被 <b>CREATE</b>，
     * 又在 {@code V20261028_82:70} 被 {@code ALTER INDEX … RENAME TO} 改成现名 ——
     * <b>新库拿得到它，它不是缺口</b>。
     * ⚠️ 误判的成因是判据连着两次「少一个动词」：v1「名字出现过就算数」漏掉 {@code DROP}，
     * v2「区分 CREATE 与 DROP」漏掉 {@code RENAME TO}。
     * ⇒ ⛔ 不要再补第三个动词。正确判据是<b>按迁移顺序求最终状态</b>
     * （{@link #resolveFinalIndexState()} 已经是这么做的）——CREATE / DROP / RENAME 都只是状态转移。
     */
    private static final Set<String> PROD_ONLY_BENIGN = Set.of("uk_encoding_rules");

    private static final Pattern CREATE_UNIQUE_INDEX =
            Pattern.compile("CREATE\\s+UNIQUE\\s+INDEX\\b[^;]*;", Pattern.CASE_INSENSITIVE);

    @Test
    void everyPartialUniqueIndexBackingASoftDeletedEntityIsStillDeclaredPartialInMigrations() throws IOException {
        // ── 扫描面自检 ────────────────────────────────────────────────────────────
        // ⚠️ 排除 db/baseline/ 有两种失效方向, 两个都要判, ⛔ 不能只判一个:
        //    ① 排太宽 ⇒ 语料被打空 ⇒ 「每个索引都缺失」与「没在读」同形 ⇒ 下面①管;
        //    ② 没排掉 ⇒ dump 顶替迁移 ⇒ 闸对「删掉一条真迁移」变瞎 ⇒ 下面②管。
        int scanned;
        int excluded;
        try (Stream<Path> f1 = Files.walk(DB); Stream<Path> f2 = Files.walk(DB)) {
            scanned = (int) f1.filter(PartialUniqueIndexBackingSoftDeleteContractTest::isMigrationSql).count();
            excluded = (int) f2.filter(p -> p.toString().toLowerCase().endsWith(".sql"))
                    .filter(p -> p.startsWith(BASELINE_DIR)).count();
        }
        boolean baselinePresent = Files.isDirectory(BASELINE_DIR);
        System.out.println("READING: MIGRATION_SCAN_SCOPE scanned_sql=" + scanned
                + " excluded_baseline_sql=" + excluded
                + " baseline_dir=" + (baselinePresent ? "present" : "absent")
                + (baselinePresent ? "" : " ⇒ **本趟没有基线可排** —— excluded=0 ⛔ 不代表排除生效"));
        // ① 阳性对照
        assertThat(scanned)
                .as("阳性对照：排除 db/baseline/ 之后仍必须扫到成百上千个迁移 .sql；"
                        + "打空了的话下面每一条断言都变成「没在读」而不是「没找到」")
                .isGreaterThan(600);
        // ② 基线在场时必须真的一条都没进来
        if (baselinePresent) {
            assertThat(excluded)
                    .as("db/baseline/ 在场却一条 .sql 都没被排除 —— 说明排除判据没生效。"
                            + "后果不是读数难看：dump 里那 13 个受守护的索引名一个不少，"
                            + "⇒ 删掉一条真迁移本闸照样绿")
                    .isGreaterThan(0);
            try (Stream<Path> f3 = Files.walk(DB)) {
                assertThat(f3.filter(PartialUniqueIndexBackingSoftDeleteContractTest::isMigrationSql)
                        .noneMatch(p -> p.startsWith(BASELINE_DIR)))
                        .as("扫描面里混进了 db/baseline/ 下的文件")
                        .isTrue();
            }
        }

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
        // ⚠️ 这个数**同时**依赖两件事，报它的时候两件都要说清楚：
        //
        // ① 语句边界怎么切：到下一个 `;` 是 109/131，到下一条语句关键字是 110/132，
        //    裸 `CREATE UNIQUE INDEX` 出现次数是 133（差额在 DO $$ … $$ 块里，那里的 `;` 不是语句结尾）。
        //
        // ② 🔴 **在哪棵树上数的** —— 这一条才是 2026-09-07 那次 109 vs 111 分歧的真因。
        //    终审重数得到 111/133，我在自己分支上是 109/131，我当时**用①把它解释掉了**（口径差）。
        //    错的。把 #712 合进来再数：**恰好 111/133** —— 它的 V20261101_38 加了正好 2 条带
        //    `deleted_at` 谓词的 CREATE UNIQUE INDEX。⇒ 109+2=111、131+2=133，逐位吻合。
        //    ⚠️ 我拿一个**自己量过、听起来也成立**的机制，解释掉了一个由**输入不同**造成的差异。
        //    一个能解释症状的机制，不等于就是这次的成因——差两个数的时候先问「我们数的是不是同一棵树」。
        //
        // ⇒ 打印时把口径一起打出来；比对读数时先对齐 base。
        //   ⛔ 一个不说口径、不说树的数字，和不写它一样没用。
        System.out.println("PARTIAL_UNIQUE_INDEX_STATEMENTS_IN_MIGRATIONS=" + partial.size()
                + " (口径: CREATE UNIQUE INDEX 到下一个 ';'; 同口径下总语句数="
                + CREATE_UNIQUE_INDEX.matcher(sql).results().count() + ")");

        // 🔴 按**迁移顺序**求最终状态，⛔ 不是「文本里出现过一条部分索引语句就算数」。
        // 2026-09-07 终审指出：短路写法只守得住「原语句被编辑」，一条**后来的** `DROP INDEX uq_lt_plan_tripno;`
        // 会让原 CREATE 文本原封不动留着、闸保持绿 —— 而本类文档承诺的是「删掉就报红」。
        // ⇒ 文档承诺了实现做不到的事，且是往「更让人放心」的方向，比闸弱本身更危险 ⇒ 改实现。
        Map<String, String> finalState = resolveFinalIndexState();

        List<String> missing = new ArrayList<>();
        List<String> notPartial = new ArrayList<>();
        for (Map.Entry<String, String> e : MUST_BE_PARTIAL_IN_MIGRATIONS.entrySet()) {
            String state = finalState.getOrDefault(e.getKey(), "absent");
            if ("partial".equals(state)) continue;
            (state.equals("full") ? notPartial : missing).add(e.getKey() + " [" + state + "] (" + e.getValue() + ")");
        }

        assertThat(notPartial)
                .as("这些索引在迁移里还在，但按迁移顺序算出来的最终状态已经不是 `WHERE deleted_at IS NULL` 的部分索引了。"
                        + "实体侧已经不声明唯一键了，改成全量等于让「软删再建同键」在生产上被拒。")
                .isEmpty();
        assertThat(missing)
                .as("这些索引按迁移顺序算下来最终不存在了（被删、被改名，或后面有一条 DROP INDEX）。"
                        + "唯一性现在只由它们承担，删掉它就是静默失守——H2 本来就不管，prod 上要等真出重复数据才看得见。")
                .isEmpty();

        // 只活在 prod 上的那些：集合一变就报红, 提醒把补好迁移的那个搬到上面的表里。
        // ⚠️ 两个集合**一起**判 —— 「良性」也是一种判定, 它一旦进了迁移同样要搬家,
        //    否则 PROD_ONLY_BENIGN 会变成一个没有任何断言看着它的注释(那就是白写)。
        //    ⛔ 不要只判 PROD_ONLY_NOT_IN_MIGRATIONS: 它现在是空的, 只判它等于恒真。
        System.out.println("READING: PROD_ONLY_GAPS=" + PROD_ONLY_NOT_IN_MIGRATIONS.size()
                + " PROD_ONLY_BENIGN=" + PROD_ONLY_BENIGN.size()
                + " (空的 GAPS ≠ 这一类不存在 —— 已判为良性的在 BENIGN 里)");
        List<String> prodOnlyNowInMigrations = Stream.concat(
                        PROD_ONLY_NOT_IN_MIGRATIONS.stream(), PROD_ONLY_BENIGN.stream())
                .filter(n -> "partial".equals(finalState.get(n)))
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

        // 🔴 ⛔ 不能对整份 yml 做 substring —— 那样钉的性质是「这个字符串出现在这个文件的某处」：
        //    ① 一条提到该类名的**注释**就能喂饱它；
        //    ② 把名字挪进 final-full-suite 的列表（那条 job 带 `java-full-ready` 标签才跑）同样保持绿，
        //       **而这个测试实际停止运行**。
        //    ⇒ 这正是本 PR 要解决的那件事的元层面：我加这条闭环，是因为「加进 -Dtest= 不够，
        //      删文件仍可能悄悄通过」；而闭环自己有同一个形状的洞。⇒ 锚到**块 + 键 + 同一行**。
        String block = jobBlock(Files.readString(workflow, StandardCharsets.UTF_8),
                "postgres-reversal-concurrency");
        assertThat(block)
                .as("阳性对照：切不出 postgres-reversal-concurrency 这个 job（改名/删掉/缩进变了），"
                        + "下面那条就是恒假——先点名说清楚是切块这一步的问题")
                .isNotEmpty();

        List<String> dTestLines = block.lines().filter(l -> l.contains("-Dtest=")).toList();
        assertThat(dTestLines)
                .as("postgres-reversal-concurrency 里必须有 -Dtest= 那一行；没有它这道车道就不再点名任何测试")
                .isNotEmpty();
        assertThat(dTestLines)
                .as("它必须出现在 postgres-reversal-concurrency 的 **-Dtest= 那一行上**——"
                        + "只要求「出现在文件某处」是不够的：注释能喂饱它，挪进 final-full-suite（要标签才跑）也能，"
                        + "而两种情况下这个测试都停止运行")
                .anyMatch(l -> l.contains("SoftDeletePartialUniqueIndexPostgresContractTest"));
    }

    /**
     * 从 workflow 全文里切出某个 job 的正文：从 {@code ^\s*<name>:} 那一行起，到下一个**同缩进**的
     * {@code key:} 行为止。⛔ 不上 YAML 解析器（那是另一个量级），但足以把「文件某处」收紧成「这个块里」。
     */
    private static String jobBlock(String yaml, String jobName) {
        String[] lines = yaml.split("\n", -1);
        int start = -1, indent = -1;
        Pattern head = Pattern.compile("^(\\s*)" + Pattern.quote(jobName) + ":\\s*$");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = head.matcher(lines[i]);
            if (m.find()) {
                start = i;
                indent = m.group(1).length();
                break;
            }
        }
        if (start < 0) return "";
        StringBuilder sb = new StringBuilder();
        Pattern sibling = Pattern.compile("^\\s{" + indent + "}[A-Za-z_][\\w-]*:");
        for (int i = start + 1; i < lines.length; i++) {
            if (sibling.matcher(lines[i]).find()) break;
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /**
     * 按迁移的执行顺序把每个唯一索引名收敛到<b>最终状态</b>：{@code partial} / {@code full} / {@code dropped}
     * （没出现过的名字不在返回值里 ⇒ 调用方读作 {@code absent}）。
     *
     * <p>为什么必须按顺序：{@code CREATE …; DROP …;} 与 {@code DROP …; CREATE …;} 在「文本里出现过」这个
     * 口径下完全一样，而最终状态相反。⇒ 顺序敏感是这道闸能兑现文档承诺的前提。
     *
     * <p>⚠️ 已知边界（写出来，⛔ 不假装没有）：
     * <ul>
     *   <li>🔴 <b>文件顺序按裸文件名排，而「时间线」其实不是一条线</b>：{@code db/} 下有 5 个目录
     *       （{@code flyway/} 695、{@code migration/} 256、{@code migration-pg-converted/} 159、
     *       {@code manual-rollback/} 20、{@code migration-pg/} 7），合起来后有 <b>156 个文件名跨目录重复</b>
     *       （涉及 316 个文件）⇒ 它们之间的相对顺序取决于遍历顺序，而不是任何真实的执行顺序；
     *       {@code manual-rollback/} 里的 DROP 还会被当成正向时间线上的事件。
     *       ⇒ 「Flyway 版本号定宽 ⇒ 字典序即版本序」这句话<b>只在 {@code flyway/} 目录内部成立</b>。
     *       本闸跟踪的 11 个索引都只在 {@code flyway/} 里出现（已核），所以今天的读数是对的；
     *       但这不是设计上的保证。</li>
     *   <li>语句边界取到下一个 {@code ;}，所以 {@code DO $$ … $$} 块<b>里面</b>的 CREATE 认不全
     *       （全仓 224 个 DO 块里有 6 个含 CREATE UNIQUE INDEX，本闸跟踪的 11 个都不在其中——已核）；</li>
     *   <li>🔴 <b>内联 {@code UNIQUE(...)} / {@code CONSTRAINT … UNIQUE (...)} 完全看不见</b>。
     *       口径：<b>行首</b>（可带 {@code CONSTRAINT <名>} 前缀）的 {@code UNIQUE (} 有 <b>123</b> 处；
     *       若改数任意位置的 {@code \bUNIQUE\s*\(} 则是 <b>153</b> 处（多出来的是
     *       {@code ALTER TABLE … ADD CONSTRAINT … UNIQUE (…)} 那一类）。
     *       ⚠️ 两个数都对，问的不是同一件事 ⇒ 报它必须带口径，同本方法上面第 (c) 项。
     *       这个盲区已经造成过一次假读数，见 {@link #PROD_ONLY_NOT_IN_MIGRATIONS} 上的说明。</li>
     * </ul>
     */
    private static Map<String, String> resolveFinalIndexState() throws IOException {
        Pattern create = Pattern.compile(
                "CREATE\\s+UNIQUE\\s+INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z_]\\w*)[^;]*;",
                Pattern.CASE_INSENSITIVE);
        Pattern drop = Pattern.compile(
                "DROP\\s+INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+EXISTS\\s+)?([A-Za-z_]\\w*)",
                Pattern.CASE_INSENSITIVE);
        Pattern dropConstraint = Pattern.compile(
                "DROP\\s+CONSTRAINT\\s+(?:IF\\s+EXISTS\\s+)?([A-Za-z_]\\w*)", Pattern.CASE_INSENSITIVE);

        List<Path> ordered;
        try (Stream<Path> files = Files.walk(DB)) {
            ordered = files.filter(PartialUniqueIndexBackingSoftDeleteContractTest::isMigrationSql)
                    .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                    .toList();
        }
        Map<String, String> state = new LinkedHashMap<>();
        for (Path p : ordered) {
            // 🔴 必须先剥 SQL 注释, 而且**方向不安全**: 一条被注释掉的 partial CREATE 会把状态设成
            //    `partial`, 从而**掩盖掉一次真实的 DROP` —— 也就是说漏剥注释会让闸偏向「一切正常」。
            //    实测语料里被注释掉的: CREATE UNIQUE INDEX 5 条 / DROP INDEX 8 条 / DROP CONSTRAINT 5 条
            //    (今天没有一条命中被跟踪的 11 个名字, 所以剥与不剥的最终状态相同——已核)。
            // ⚠️ 必须**逐文件**剥: 第一版把全部 .sql 拼成一个字符串再剥, `/*…*/` 配 DOTALL 之下
            //    一处不成对的 `/*` 跨文件吞掉一大段, 读数变成「133 条 CREATE 里 126 条是注释」——
            //    荒谬, 但它是个看起来很确定的数。是阳性对照(剥完不该腰斩)把它捞出来的。
            String body = stripSqlComments(Files.readString(p, StandardCharsets.UTF_8));
            // 同一个文件里也要按**出现位置**排，否则文件内的 CREATE/DROP 先后同样会算反。
            record Event(int at, String name, String to) {}
            List<Event> events = new ArrayList<>();
            for (Matcher c = create.matcher(body); c.find(); ) {
                events.add(new Event(c.start(), c.group(1).toLowerCase(),
                        c.group().toLowerCase().contains("deleted_at is null") ? "partial" : "full"));
            }
            for (Matcher d = drop.matcher(body); d.find(); ) {
                events.add(new Event(d.start(), d.group(1).toLowerCase(), "dropped"));
            }
            for (Matcher d = dropConstraint.matcher(body); d.find(); ) {
                events.add(new Event(d.start(), d.group(1).toLowerCase(), "dropped"));
            }
            events.stream().sorted(Comparator.comparingInt(Event::at))
                    .forEach(ev -> state.put(ev.name(), ev.to()));
        }
        return state;
    }

    /** 剥掉 SQL 注释（{@code -- 行注释} 与 {@code /*…*}{@code /} 块注释）。⚠️ 只能<b>逐文件</b>调用，理由见调用处。 */
    private static String stripSqlComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)--.*$", "");
    }

    private static boolean containsWord(String haystack, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(haystack).find();
    }

    private static String readAllMigrations() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(DB)) {
            for (Path p : files.filter(PartialUniqueIndexBackingSoftDeleteContractTest::isMigrationSql).toList()) {
                sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sb.toString();
    }
}
