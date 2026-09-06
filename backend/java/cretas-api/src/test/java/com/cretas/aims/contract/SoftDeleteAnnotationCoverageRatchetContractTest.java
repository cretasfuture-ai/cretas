package com.cretas.aims.contract;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 棘轮: 「类级 {@code @Where(deleted_at IS NULL)} 但<b>没有</b>类级 {@code @SQLDelete}」的实体
 * 必须逐个登记在清单里, 清单只许变短。
 *
 * <h2>它守的是什么 (2026-09-06, ARCH-PRODUCTION-PLAN-HARD-DELETE-PATH)</h2>
 *
 * <p>{@code BaseEntity}(@MappedSuperclass) 上同时写着 {@code @SQLDelete} 和 {@code @Where},
 * <b>两个都被 Hibernate 静默忽略</b> —— BaseEntity 自己的 R68 注释 (2026-04-29) 记着这件事,
 * 并说明 {@code @Where} 是靠 R68-FIX-A <b>批量补进每一个子类</b>才生效的。
 * 🔴 <b>{@code @SQLDelete} 当年没有跟着批量补。</b>
 *
 * <p>于是全仓长成一个很危险的形状: 几百个实体<b>查询时</b>表现得像软删(@Where 过滤掉
 * deleted_at 非空的行), <b>删除时</b>却是物理 DELETE。两者叠加的结果是
 * ——「删掉的东西查不到」在两种实现下<b>读数完全一样</b>, 所以没有人发现过它。
 *
 * <p>它咬人的地方在<b>引用完整性</b>: {@code process_sheet_rows.plan_id} 没有 FK,
 * 计划行被物理删掉之后那些工单行就成了孤儿 (prod 上 56 行, 2026-08-29)。
 * 另一处是<b>发号器/幂等键</b>: 见 {@code SoftDeleteBlindSpotContractTest}
 * ——「这个键被占用了吗」在软删/硬删两种世界里答案不同。
 *
 * <h2>为什么是清单而不是一个数字</h2>
 *
 * <p>🔴 第一版把基线写成 {@code PINNED_OFFENDERS = 329} 这个<b>整数字面量</b>。
 * 那样两个并行 PR 各修一个实体时, <b>先合的那个把数字改成 328, 后合的那个就红了</b>
 * —— 而后者什么都没做错。本仓 Flyway 版本号撞号是同一形状(并行线各自数 max)。
 * 改成清单之后, 两个 PR 各删自己那一行, git 只在同一行冲突时才报 ——
 * 而那正是「两个人修了同一个实体」, 该报。
 *
 * <p>形态 E(宁可窄而可信, 不要宽而被关掉): 建闸当天清单里有 329 个实体, 一上来硬红
 * 就是一道当天被人加 {@code @Disabled} 的闸。所以闸只禁增长。
 *
 * <h2>三种红，措辞不同(⛔ 不是同一件事)</h2>
 *
 * <ol>
 *   <li><b>不在清单 + 缺 {@code @SQLDelete}</b> ⇒ 新引入了一个 offender。列全名。</li>
 *   <li><b>在清单 + 已有 {@code @SQLDelete}</b> ⇒ 已经修好了, 清单没跟着删行。
 *       ⛔ 这条不能放行 —— 放行等于允许清单只增不减, 棘轮就退化成摆设。</li>
 *   <li><b>清单里的类扫不到</b> ⇒ 实体被删掉 / 改名 / 不再是 {@code @Entity}。
 *       <b>那不是「修好了」</b>, 需要人明确判断之后手动删清单行(并说明去向)。</li>
 * </ol>
 *
 * <h2>⚠️ 本闸看不见什么 (代理判据边界)</h2>
 *
 * <ul>
 *   <li>它只看<b>注解</b>, 不看有没有人真的调 {@code delete()}。清单长不等于有 329 个活缺陷。</li>
 *   <li>它<b>不</b>判断某个实体<b>应该</b>软删还是硬删。有些实体(日志/闭包/血缘)本来就
 *       该禁止删除, 正确修法是 {@code @PreRemove} 抛异常, 不是补 {@code @SQLDelete} ——
 *       那种实体减不下清单, 也不该硬凑。</li>
 *   <li>它看不见 {@code @Modifying @Query("DELETE …")} 和 JdbcTemplate 原生删除 ——
 *       那两条路绕过 {@code @SQLDelete}, 与本注解无关。</li>
 * </ul>
 */
@DisplayName("软删注解覆盖率棘轮: @Where 有而 @SQLDelete 没有的实体必须逐个登记, 清单只许变短")
class SoftDeleteAnnotationCoverageRatchetContractTest {

    /** 只扫这个包 —— 与 {@code @EntityScan(basePackages = "com.cretas.aims")} 同一范围。 */
    private static final String BASE_PACKAGE = "com.cretas.aims";

    /** 与 {@code known-failing-tests.txt} 同形: 一行一个 FQCN, {@code #} 注释, 空行忽略。 */
    private static final Path MANIFEST =
            Path.of("src/test/resources/soft-delete-known-violations.txt");

    /** 仪器活着的下界: 扫不到这么多实体就说明 classpath 扫描本身出问题了。 */
    private static final int MIN_EXPECTED_ENTITIES = 300;

    private record EntityAnnotations(String className, String table, boolean where, boolean sqlDelete) { }

    // ── 主断言: 三态棘轮 ────────────────────────────────────────────────────

    @Test
    @DisplayName("清单必须与实测一一对上(新增 / 已修未删行 / 类不存在, 三种红分开报)")
    void manifestMustMatchReality() throws IOException {
        Map<String, EntityAnnotations> byName = scanEntities();

        // 硬约束 9: 验证「没有新增 offender」时, 同时要有一个「一定会发生」的读数 ——
        // 扫描如果一个实体都没找到, 新增数天然是 0, 长得和「全修好了」一模一样。
        assertTrue(byName.size() >= MIN_EXPECTED_ENTITIES,
                "classpath 扫描只找到 " + byName.size() + " 个 @Entity(期望 >= " + MIN_EXPECTED_ENTITIES
                        + ") —— 这是仪器坏了, 不是缺陷清零。本轮读数作废。");

        Set<String> manifest = readManifest();
        assertFalse(manifest.isEmpty(),
                "清单 " + MANIFEST + " 一行都没读到 —— 解析坏了或文件被清空。"
                        + "清单为空时下面三条检查全部恒真, 读数作废。");

        List<String> newlyIntroduced = new ArrayList<>();
        List<String> fixedButStillListed = new ArrayList<>();
        List<String> listedButMissing = new ArrayList<>();

        for (EntityAnnotations e : byName.values()) {
            if (e.where() && !e.sqlDelete() && !manifest.contains(e.className())) {
                newlyIntroduced.add(e.className() + "  (table=" + e.table() + ")");
            }
        }
        for (String listed : manifest) {
            EntityAnnotations e = byName.get(listed);
            if (e == null) {
                listedButMissing.add(listed + "  (" + describeMissing(listed) + ")");
            } else if (e.sqlDelete()) {
                fixedButStillListed.add(listed + "  (table=" + e.table() + ")");
            }
        }

        long offenders = byName.values().stream().filter(e -> e.where() && !e.sqlDelete()).count();
        System.out.println("SOFT_DELETE_RATCHET entities_scanned=" + byName.size()
                + " with_where=" + byName.values().stream().filter(EntityAnnotations::where).count()
                + " with_where_and_sqldelete="
                + byName.values().stream().filter(e -> e.where() && e.sqlDelete()).count()
                + " offenders=" + offenders
                + " manifest=" + manifest.size()
                + " newly_introduced=" + newlyIntroduced.size()
                + " fixed_but_still_listed=" + fixedButStillListed.size()
                + " listed_but_missing=" + listedButMissing.size());

        // ⛔ 逐条全打, 不截断 —— 截断会让人分不清「新增了哪一个」。
        StringBuilder msg = new StringBuilder();
        if (!newlyIntroduced.isEmpty()) {
            msg.append("\n🔴 新增 ").append(newlyIntroduced.size())
               .append(" 个 offender(带类级 @Where(deleted_at IS NULL) 却没有类级 @SQLDelete, 且不在清单里):\n  - ")
               .append(String.join("\n  - ", newlyIntroduced))
               .append("\n这类实体「查询时像软删, 删除时是物理 DELETE」—— 两种实现的 findById 读数一样,\n")
               .append("所以缺陷只会在引用完整性 / 发号器占用语义上显形(见 class javadoc)。\n")
               .append("修法二选一, 都要显式写在**子类**上(父类 @MappedSuperclass 上的那个不生效):\n")
               .append("  a) 该软删 -> @SQLDelete(sql = \"UPDATE <表名> SET deleted_at = NOW() WHERE id = ?\")\n")
               .append("  b) 压根不该删 -> @PreRemove 抛异常(见 BatchLineageEdge)\n")
               .append("⛔ 不要把它加进 ").append(MANIFEST).append(" 来让这道闸变绿。\n");
        }
        if (!fixedButStillListed.isEmpty()) {
            msg.append("\n🟢→📄 ").append(fixedButStillListed.size())
               .append(" 个实体已经有 @SQLDelete 了, 但清单里还留着它 —— 请从 ")
               .append(MANIFEST).append(" 删掉这些行(棘轮往前走一格):\n  - ")
               .append(String.join("\n  - ", fixedButStillListed)).append('\n');
        }
        if (!listedButMissing.isEmpty()) {
            msg.append("\n❓ ").append(listedButMissing.size())
               .append(" 个清单条目在实测里扫不到。**这不是「修好了」** —— 实体可能被删掉/改名/")
               .append("不再是 @Entity。请人工判断去向后手动删清单行, 并在 commit message 里说明:\n  - ")
               .append(String.join("\n  - ", listedButMissing)).append('\n');
        }

        assertTrue(msg.isEmpty(), "软删注解覆盖率棘轮不通过:" + msg);
    }

    // ── 阳性对照: 检测器两个方向都能开火 ────────────────────────────────────

    @Test
    @DisplayName("阳性对照: 检测器必须把已修好的三个实体判为「非 offender」且不在清单里")
    void detectorRecognisesFixedEntities() throws IOException {
        Map<String, EntityAnnotations> byName = scanEntities();
        Set<String> manifest = readManifest();

        for (String fixed : List.of(
                "com.cretas.aims.entity.ProductionPlan",
                "com.cretas.aims.entity.ProductionBatch",
                "com.cretas.aims.entity.processentry.ProcessSheetRow")) {
            EntityAnnotations e = byName.get(fixed);
            if (e == null) {
                throw new AssertionError("扫描里根本没有 " + fixed + " —— 仪器够不着被测对象, 本轮读数作废");
            }
            System.out.println("SOFT_DELETE_RATCHET fixed_entity " + fixed
                    + " where=" + e.where() + " sqlDelete=" + e.sqlDelete()
                    + " inManifest=" + manifest.contains(fixed));
            assertTrue(e.where(), fixed + " 应该带类级 @Where(deleted_at IS NULL)");
            assertTrue(e.sqlDelete(), fixed + " 应该带类级 @SQLDelete —— 本 PR 刚补上的那个");
            assertFalse(manifest.contains(fixed), fixed + " 已修好, 不该还留在清单里");
        }
    }

    @Test
    @DisplayName("阳性对照: 检测器必须**能**判出 offender —— offender 列表不许是空的")
    void detectorCanStillFireOnAKnownOffender() {
        Map<String, EntityAnnotations> byName = scanEntities();
        List<EntityAnnotations> offenders = byName.values().stream()
                .filter(e -> e.where() && !e.sqlDelete())
                .toList();

        // 没有这一条, manifestMustMatchReality 在「检测器永远返回空」时同样是绿的。
        assertFalse(offenders.isEmpty(),
                "检测器一个 offender 都没找到。要么全仓真的清零了(那就把清单清空并删掉本条对照), "
                        + "要么就是判定逻辑坏了 —— 后者远更可能。");
        System.out.println("SOFT_DELETE_RATCHET sample_offender=" + offenders.get(0).className());
    }

    // ── 清单 ────────────────────────────────────────────────────────────────

    private static Set<String> readManifest() throws IOException {
        if (!Files.isRegularFile(MANIFEST)) {
            throw new AssertionError("清单文件不在 " + MANIFEST.toAbsolutePath()
                    + " —— 闸测错了地方(或文件被删), 不是「没问题」");
        }
        Set<String> out = new LinkedHashSet<>();
        List<String> dupes = new ArrayList<>();
        for (String raw : Files.readAllLines(MANIFEST, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!out.add(line)) {
                dupes.add(line);
            }
        }
        assertTrue(dupes.isEmpty(), "清单里有重复行(会让计数骗人): " + dupes);
        return out;
    }

    /** 区分「类压根不存在」和「类还在但不再是 @Entity」—— 两种去向的处理不一样。 */
    private static String describeMissing(String fqcn) {
        try {
            Class.forName(fqcn);
            return "类还在, 但已经不是 @Entity 了(或没被 " + BASE_PACKAGE + " 扫到)";
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return "类不存在(被删或改名)";
        }
    }

    // ── 扫描 ────────────────────────────────────────────────────────────────

    /**
     * 用反射(结构)而不是文本 grep 来数 —— 形态 C⁸: 字符串计数量的是文本, 反射量的是结构。
     * 注释里提到 {@code @SQLDelete} 的 javadoc 不会被数进来。
     */
    private static Map<String, EntityAnnotations> scanEntities() {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        Map<String, EntityAnnotations> out = new LinkedHashMap<>();
        for (BeanDefinition bd : provider.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type;
            try {
                type = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new AssertionError("扫描到 " + bd.getBeanClassName() + " 却加载不了 —— 仪器坏了", e);
            }
            Table table = type.getDeclaredAnnotation(Table.class);
            // ⚠️ getDeclaredAnnotation 而不是 getAnnotation: 本闸问的正是「这个**子类自己**
            //    有没有写」。父类 @MappedSuperclass 上那两个注解 Hibernate 不认, 闸也不该认。
            Where where = type.getDeclaredAnnotation(Where.class);
            SQLDelete sqlDelete = type.getDeclaredAnnotation(SQLDelete.class);
            out.put(type.getName(), new EntityAnnotations(
                    type.getName(),
                    table != null && !table.name().isBlank() ? table.name() : "(默认表名)",
                    where != null && isSoftDeleteClause(where.clause()),
                    sqlDelete != null));
        }
        return out;
    }

    /** 只认软删过滤那一种 {@code @Where} —— 别的业务过滤条件与本闸无关。 */
    private static boolean isSoftDeleteClause(String clause) {
        if (clause == null) {
            return false;
        }
        String normalised = clause.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return normalised.contains("deleted_at is null");
    }
}
