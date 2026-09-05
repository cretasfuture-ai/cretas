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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 棘轮: 「类级 {@code @Where(deleted_at IS NULL)} 但<b>没有</b>类级 {@code @SQLDelete}」的实体数<b>只许减少</b>。
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
 * 另一处是<b>发号器</b>: 见 {@link com.cretas.aims.repository.SoftDeleteBlindSpotContractTest}
 * ——「这个号被占用了吗」在软删/硬删两种世界里答案不同。
 *
 * <h2>为什么是棘轮而不是硬门禁</h2>
 *
 * <p>形态 E: <b>宁可窄而可信, 不要宽而被关掉。</b> 建闸当天 offender 有
 * {@value #PINNED_OFFENDERS} 个 —— 一上来就硬红 = 一道当天被人加 {@code @Disabled} 的闸。
 * 所以只禁增长: 想新加一个带 {@code @Where(deleted_at IS NULL)} 的实体, 就得同时决定
 * 它的 {@code delete()} 是软是硬, 并把决定写进注解里(而不是继承一个不生效的父类注解)。
 *
 * <p>⛔ 修完一批就把 {@link #PINNED_OFFENDERS} 往下调, ⛔ 不许往上调。
 *
 * <h2>⚠️ 本闸看不见什么 (代理判据边界)</h2>
 *
 * <ul>
 *   <li>它只看<b>注解</b>, 不看有没有人真的调 {@code delete()}。offender 里绝大多数
 *       今天根本没有删除路径 —— 数字大不等于有 329 个活着的缺陷。</li>
 *   <li>它<b>不</b>判断某个实体<b>应该</b>软删还是硬删。有些实体(日志/闭包/血缘)本来就
 *       该禁止删除, 正确修法是 {@code @PreRemove} 抛异常, 不是补 {@code @SQLDelete} ——
 *       那种实体减不下这个数, 也不该硬凑。</li>
 *   <li>它看不见 {@code @Modifying @Query("DELETE …")} 和 JdbcTemplate 原生删除 ——
 *       那两条路绕过 {@code @SQLDelete}, 与本注解无关。</li>
 * </ul>
 */
@DisplayName("软删注解覆盖率棘轮: @Where 有而 @SQLDelete 没有的实体只许变少")
class SoftDeleteAnnotationCoverageRatchetContractTest {

    /** 只扫这个包 —— 与 {@code @EntityScan(basePackages = "com.cretas.aims")} 同一范围。 */
    private static final String BASE_PACKAGE = "com.cretas.aims";

    /**
     * 2026-09-06 建闸当天的实测基线。
     *
     * <p>建闸前: 352 个实体带类级 {@code @Where(deleted_at IS NULL)}, 其中只有 20 个
     * 同时带类级 {@code @SQLDelete} ⇒ 332 个 offender。
     * 同一个 PR 给 {@code ProductionPlan} / {@code ProductionBatch} /
     * {@code ProcessSheetRow} 补上 {@code @SQLDelete} ⇒ <b>329</b>。
     */
    private static final int PINNED_OFFENDERS = 329;

    /** 仪器活着的下界: 扫不到这么多实体就说明 classpath 扫描本身出问题了。 */
    private static final int MIN_EXPECTED_ENTITIES = 300;

    private record EntityAnnotations(String className, String table, boolean where, boolean sqlDelete) { }

    // ── 主断言: 棘轮 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("offender 数不得超过钉住的基线")
    void offenderCountMustNotGrow() {
        List<EntityAnnotations> all = scanEntities();

        // 硬约束 9: 验证「没有新增 offender」时, 同时要有一个「一定会发生」的读数 ——
        // 扫描本身如果一个实体都没找到, offender 数天然是 0, 长得和「全修好了」一模一样。
        assertTrue(all.size() >= MIN_EXPECTED_ENTITIES,
                "classpath 扫描只找到 " + all.size() + " 个 @Entity(期望 >= " + MIN_EXPECTED_ENTITIES
                        + ") —— 这是仪器坏了, 不是缺陷清零。offender 读数本轮作废。");

        List<String> offenders = all.stream()
                .filter(e -> e.where() && !e.sqlDelete())
                .map(e -> e.className() + "  (table=" + e.table() + ")")
                .sorted()
                .toList();

        System.out.println("SOFT_DELETE_RATCHET entities_scanned=" + all.size()
                + " with_where=" + all.stream().filter(EntityAnnotations::where).count()
                + " with_where_and_sqldelete="
                + all.stream().filter(e -> e.where() && e.sqlDelete()).count()
                + " offenders=" + offenders.size()
                + " pinned=" + PINNED_OFFENDERS);

        // ⛔ 失败信息里逐条全打, 不截断 —— 截断会让人分不清「新增了哪一个」。
        assertTrue(offenders.size() <= PINNED_OFFENDERS,
                "带类级 @Where(deleted_at IS NULL) 却没有类级 @SQLDelete 的实体从 "
                        + PINNED_OFFENDERS + " 涨到了 " + offenders.size() + "。\n"
                        + "这类实体「查询时像软删, 删除时是物理 DELETE」—— 两种实现的 findById 读数一样,\n"
                        + "所以缺陷只会在引用完整性 / 发号器占用语义上显形(见 class javadoc)。\n"
                        + "修法二选一, 都要显式写在**子类**上(父类 @MappedSuperclass 上的那个不生效):\n"
                        + "  a) 该软删 -> @SQLDelete(sql = \"UPDATE <表名> SET deleted_at = NOW() WHERE id = ?\")\n"
                        + "  b) 压根不该删 -> @PreRemove 抛异常(见 BatchLineageEdge)\n"
                        + "⛔ 不要为了让这道闸变绿而把 PINNED_OFFENDERS 调大。\n\n"
                        + "当前全部 offender (" + offenders.size() + " 个):\n  - "
                        + String.join("\n  - ", offenders));
    }

    // ── 阳性对照: 检测器两个方向都能开火 ────────────────────────────────────

    @Test
    @DisplayName("阳性对照: 检测器必须把已修好的三个实体判为「非 offender」")
    void detectorRecognisesFixedEntities() {
        List<EntityAnnotations> all = scanEntities();

        for (String fixed : List.of(
                "com.cretas.aims.entity.ProductionPlan",
                "com.cretas.aims.entity.ProductionBatch",
                "com.cretas.aims.entity.processentry.ProcessSheetRow")) {
            EntityAnnotations e = all.stream()
                    .filter(x -> x.className().equals(fixed))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "扫描里根本没有 " + fixed + " —— 仪器够不着被测对象, 本轮读数作废"));
            System.out.println("SOFT_DELETE_RATCHET fixed_entity " + fixed
                    + " where=" + e.where() + " sqlDelete=" + e.sqlDelete());
            assertTrue(e.where(), fixed + " 应该带类级 @Where(deleted_at IS NULL)");
            assertTrue(e.sqlDelete(), fixed + " 应该带类级 @SQLDelete —— 本 PR 刚补上的那个");
        }
    }

    @Test
    @DisplayName("阳性对照: 检测器必须**能**判出 offender —— offender 列表不许是空的")
    void detectorCanStillFireOnAKnownOffender() {
        List<EntityAnnotations> all = scanEntities();
        List<EntityAnnotations> offenders = all.stream()
                .filter(e -> e.where() && !e.sqlDelete())
                .toList();

        // 没有这一条, offenderCountMustNotGrow 的 `<=` 在「检测器永远返回空」时同样是绿的。
        assertFalse(offenders.isEmpty(),
                "检测器一个 offender 都没找到。要么全仓真的清零了(那就把 PINNED_OFFENDERS 调到 0 "
                        + "并删掉本条对照), 要么就是判定逻辑坏了 —— 后者远更可能。");
        System.out.println("SOFT_DELETE_RATCHET sample_offender=" + offenders.get(0).className());
    }

    // ── 扫描 ────────────────────────────────────────────────────────────────

    /**
     * 用反射(结构)而不是文本 grep 来数 —— 形态 C⁸: 字符串计数量的是文本, 反射量的是结构。
     * 注释里提到 {@code @SQLDelete} 的 javadoc 不会被数进来。
     */
    private static List<EntityAnnotations> scanEntities() {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<EntityAnnotations> out = new ArrayList<>();
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
            out.add(new EntityAnnotations(
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
