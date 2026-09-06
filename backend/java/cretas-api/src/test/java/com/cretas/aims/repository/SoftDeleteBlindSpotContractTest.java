package com.cretas.aims.repository;

import com.cretas.aims.repository.smartbi.SmartBiAnalysisCacheRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「必须看得到软删行」的那几条查询是 {@code nativeQuery = true}。
 *
 * <h2>它守的是什么</h2>
 * 352 个实体带类级 {@code @Where(clause = "deleted_at IS NULL")}(2026-09-06 反射实测,
 * 全仓 443 个 {@code @Entity})。它让 Hibernate 把「deleted_at IS NULL」拼进每一条 HQL/JPQL。
 *
 * <p>🔴 <b>订正 (2026-09-06, ARCH-PRODUCTION-PLAN-HARD-DELETE-PATH)</b>: 本段原文写的是
 * 「{@code BaseEntity}(@MappedSuperclass) 上有 {@code @SQLDelete} ——
 * <b>全仓每一次 {@code repository.delete()} 都是软删</b>」。<b>那句话是错的, 而且是承重的。</b>
 *
 * <p>{@code BaseEntity} 上那句 {@code @SQLDelete} 与它旁边的 {@code @Where} 一样,
 * 挂在 {@code @MappedSuperclass} 上<b>被 Hibernate 静默忽略</b>(BaseEntity 自己的 R68 注释
 * 2026-04-29 就记着这件事)。R68-FIX-A 当年把 {@code @Where} <b>批量补进了每个子类</b>,
 * 而 {@code @SQLDelete} <b>没有跟着批量补</b> —— 2026-09-06 实测: 352 个带 {@code @Where}
 * 的实体里只有 20 个同时带类级 {@code @SQLDelete}。
 *
 * <p>⇒ 正确的说法是: <b>绝大多数实体的 {@code repository.delete()} 是物理 DELETE</b>。
 * prod 实证 —— {@code operation_logs} 里 3 条成功的 {@code PRODUCTION_PLAN/DELETE},
 * 对应 3 行全部 ROW_ABSENT; {@code process_sheet_rows} 因此留下 56 行孤儿。
 * {@code SalesServiceImpl.deleteDraft} 独立发现过同一件事, 并靠显式调 {@code softDelete()} 绕开。
 *
 * <p>⚠️ 这条订正<b>不削弱</b>本闸: 下面登记的三条查询守的是「占用语义」,
 * 而软删行确实存在(prod 上 {@code production_plans} 61 行里 8 行 deleted_at 非空,
 * 且 20 个实体本来就在软删) —— 只要有<b>一条</b>软删路径, 发号器问错问题就会永久错位。
 * 覆盖率那一面由 {@code SoftDeleteAnnotationCoverageRatchetContractTest} 棘轮守着。
 *
 * <p>Hibernate 把 {@code @Where} 拼进<b>每一条</b>作用于该实体的 HQL/JPQL(含 {@code @Query}
 * 写的 JPQL 和 Spring Data 派生查询)。<b>原生 SQL 不经过它。</b>
 *
 * <p>于是「号段生成」和「upsert 前的存在性查询」这两类,
 * 一旦写成 JPQL 就会问错问题:
 * <pre>
 *   我想知道: 这个键/号是不是已经被占用了 (软删的也占着, 因为唯一索引看得见它)
 *   我实际问的: 这个键/号有没有一条**活着**的行
 *   ⇒ 软删一行 → 永久错位 → 每次都算出同一个已被占用的值 → duplicate key, 而且是永久撞
 * </pre>
 *
 * <h2>为什么单测/编译都拦不住</h2>
 * 这是<b>形态 B(机制在, 没接上)</b>的一个变种: 代码看起来完全正确, 注释甚至明写
 * 「不过滤 deletedAt」—— 而过滤是 Hibernate 在生成 SQL 时<b>静默</b>加上去的。
 * 语法合法、编译通过、mock 掉 repository 的单测全绿。只有真库 + 真的存在软删行时才炸。
 *
 * <h2>prod 实证 (2026-08-19 报错普查)</h2>
 * <table>
 *   <tr><th>约束</th><th>全窗口</th><th>近 7 天</th><th>成因</th></tr>
 *   <tr><td>{@code uk_cache_key}</td><td>5625</td><td><b>2090</b></td>
 *       <td>缓存 5 行里 3 行软删, {@code (F006,dashboard:month)} 就在其中</td></tr>
 *   <tr><td>{@code uk_voucher_factory_number}</td><td>499</td><td>84</td>
 *       <td>{@code V-2026-0001..0013} 活着 / {@code 0014..0017} 软删 ⇒ 发 0014 ⇒ 必撞</td></tr>
 *   <tr><td>{@code factory_material_requisitions_requisition_no_key}</td><td>35</td><td>35</td>
 *       <td>当天 6 张里 1 张软删 ⇒ count=5 ⇒ 发 {@code MR20260818-0006} ⇒ 撞(已修)</td></tr>
 * </table>
 *
 * <p>⚠️ 本闸<b>只钉登记过的那几条</b>(形态 E: 宁可窄而可信, 不要宽而被关掉)。
 * 它<b>不</b>自动发现新的盲点 —— 发号器全量分类是另一件事。新增「必须跨软删」的查询时,
 * 把它加进 {@link #MUST_SEE_SOFT_DELETED}。
 */
class SoftDeleteBlindSpotContractTest {

    /** 一条必须跨软删可见的查询。 */
    private record CrossSoftDelete(Class<?> repo, String method, String why) { }

    /**
     * 登记表 —— 目前两类合法理由:
     * ① 「这个值是不是已经被占用过」(占用语义, 不是「有没有活着的行」);
     * ② 「这个 id 历史上曾经是谁」(审计/历史展示语义 —— 引用方早已落库,
     *   现在只是把 id 翻译成人看得懂的名字, 不是把软删对象重新端给用户)。
     * ⛔ 往里加之前先确认: 读路径通常<b>不</b>该进来(软删的东西不该端给用户) ——
     *   ①②是明确的例外, 不是默认可以加。
     */
    private static final List<CrossSoftDelete> MUST_SEE_SOFT_DELETED = List.of(
            new CrossSoftDelete(VoucherRepository.class,
                    "maxVoucherSequenceByFactoryIdAndYear",
                    "凭证号段: 号一旦发出就不该回收, 软删的凭证仍占着它的号(审计可追溯)"),
            new CrossSoftDelete(SmartBiAnalysisCacheRepository.class,
                    "findIncludingDeletedByFactoryIdAndCacheKey",
                    "缓存写路径: uk_cache_key 看得见软删行, 写路径看不见它就只能 INSERT 然后撞"),
            new CrossSoftDelete(com.cretas.aims.repository.UserRepository.class,
                    "findDisplayNameByIdIncludingDeleted",
                    "R-2①: 账号被软删(比如员工离职)后, 他历史上创建/申请/审批过的单据"
                            + "仍要能显示他的姓名 —— 不能因为账号软删就让「这张单是谁建的」变回空白")
    );

    @Test
    @DisplayName("登记的跨软删查询必须是 nativeQuery —— 改回 JPQL 会被 @Where 静默过滤")
    void registeredCrossSoftDeleteQueriesMustBeNative() {
        List<String> broken = new ArrayList<>();

        for (CrossSoftDelete entry : MUST_SEE_SOFT_DELETED) {
            Method m = findSingleMethod(entry.repo(), entry.method());
            Query q = m.getAnnotation(Query.class);

            if (q == null) {
                broken.add(entry.repo().getSimpleName() + "#" + entry.method()
                        + " —— 没有 @Query 注解(= Spring Data 派生查询, 会被 @Where 过滤)。理由: " + entry.why());
            } else if (!q.nativeQuery()) {
                broken.add(entry.repo().getSimpleName() + "#" + entry.method()
                        + " —— @Query 是 JPQL 而不是 nativeQuery=true, Hibernate 会把类级 "
                        + "@Where(deleted_at IS NULL) 拼进去。理由: " + entry.why());
            }
        }

        assertTrue(broken.isEmpty(),
                "以下查询语义上必须看得到软删行, 但它们会被 @Where 静默过滤:\n  - "
                        + String.join("\n  - ", broken)
                        + "\n\n修法: 改成 @Query(value = \"<原生 SQL>\", nativeQuery = true)。"
                        + "\n⛔ 不要试图用 JPQL 里加 OR v.deletedAt IS NOT NULL 绕开 —— @Where 是 AND 进去的。");
    }

    /**
     * 阳性对照 —— 证明这个检测器<b>能</b>返回 false。
     *
     * <p>没有这一条, 上面那条断言可能是恒真式(比如 {@code getAnnotation} 因为某种原因
     * 永远返回一个 {@code nativeQuery=true} 的东西), 而我们不会知道。
     * 这里挑的是同一个 repository 上<b>故意保持被过滤</b>的读路径方法。
     */
    @Test
    @DisplayName("阳性对照: 检测器对派生查询必须判为「非 native」")
    void detectorFlagsDerivedQueryAsNotNative() {
        Method readPath = findSingleMethod(SmartBiAnalysisCacheRepository.class,
                "findByFactoryIdAndCacheKey");
        Query q = readPath.getAnnotation(Query.class);

        assertEquals(null, q,
                "findByFactoryIdAndCacheKey 应该是 Spring Data 派生查询(无 @Query) —— "
                        + "它是**读**路径, 被 @Where 过滤是对的(软删的缓存不该端给用户)。"
                        + "如果它长出了 @Query, 这条阳性对照就失效了, 换一个派生查询方法。");
    }

    /**
     * 第二条阳性对照 —— 证明检测器也能识别「有 @Query 但是 JPQL」这一类。
     *
     * <p>上一条只覆盖了「压根没有 @Query」。这一类才是真正会咬人的形状:
     * 有 {@code @Query}、看起来很正规、而 {@code nativeQuery} 默认是 {@code false}。
     */
    @Test
    @DisplayName("阳性对照: 检测器对 JPQL @Query 必须判为「非 native」")
    void detectorFlagsJpqlQueryAsNotNative() {
        Method jpql = findSingleMethod(VoucherRepository.class, "findActiveClosingVouchers");
        Query q = jpql.getAnnotation(Query.class);

        assertTrue(q != null, "findActiveClosingVouchers 应该带 @Query");
        assertFalse(q.nativeQuery(),
                "findActiveClosingVouchers 是 JPQL(nativeQuery 默认 false) —— "
                        + "这条对照证明检测器分得清 native 和 JPQL。它本身**不该**改成 native: "
                        + "它查的是 active 凭证, 被 @Where 过滤正是它要的。");
    }

    private static Method findSingleMethod(Class<?> type, String name) {
        List<Method> hits = Arrays.stream(type.getMethods())
                .filter(m -> m.getName().equals(name))
                .toList();
        assertEquals(1, hits.size(),
                type.getSimpleName() + "#" + name + " 期望恰好 1 个同名方法, 实际 " + hits.size()
                        + " 个。重载会让这道闸指向错的那一个 —— 请改成按签名定位。");
        return hits.get(0);
    }
}
