package com.cretas.aims.service.production;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个「开工」入口必须产出同一种状态 —— 否则计划会卡进「开工了但没有批次」。
 *
 * <h2>2026-08-01 prod 实测的死锁</h2>
 *
 * <p>系统里有两个都叫「开工」的入口，做的事却不一样：
 *
 * <table>
 *   <tr><th></th><th>建批次</th><th>spawn 报工任务</th><th>置 IN_PROGRESS</th></tr>
 *   <tr><td>{@code createBatchFromPlan}（转批次 / 逐道录入前置）</td><td>✅</td><td>✅</td><td>✅</td></tr>
 *   <tr><td>{@code startProduction}（「开工」按钮）</td><td>❌</td><td>❌</td><td>✅</td></tr>
 * </table>
 *
 * <p>两个都只放行 {@code PENDING}，所以先点「开工」之后 {@code createBatchFromPlan}
 * <b>永远进不去</b>。而报工和结单都需要批次 → 计划只能作废重建。
 *
 * <p>触发它的是<b>最自然的点击顺序</b>：先「开工」再「逐道录入」。前端
 * {@code views/production/plans/list.vue} 的 {@code openProcessEntry} 只在
 * {@code status === 'PENDING'} 时才自动补建批次 —— 它把「状态是 IN_PROGRESS」
 * 当成了「已经有批次」的证据，而 {@code startProduction} 恰恰打破了这个假设。
 *
 * <p>prod 实测 7 个计划卡在这个状态（F001 6 个，最早 2026-03-12；六膳门 1 个）。
 *
 * <h2>为什么钉在源码上而不是跑一遍</h2>
 *
 * <p>{@code ProductionPlanServiceImpl} 的构造函数有几十个依赖，跑通一次真实
 * {@code startProduction} 需要把 workflow 解析、任务 spawn、库存校验全部搭起来 ——
 * 而那样搭出来的用例，一旦有人把 {@code createBatchFromPlan} 调用删掉，
 * mock 会安静地返回 null，用例照样绿。
 *
 * <p>这里要守的性质是「<b>任何一个</b> {@code startProduction} 入口都必须走到建批次那条路」，
 * 它是<b>结构性</b>的，所以直接对源码断言。同类做法见
 * {@code BlockingErrorsCarryActionHintContractTest}。
 *
 * <h2>🔴 2026-08-25：这道闸曾经钉错了范围（本次修的就是它）</h2>
 *
 * <p>第一版的取体逻辑是「{@code indexOf("public ProductionPlanDTO startProduction(")}
 * 之后到<b>下一个</b> {@code @Override} 为止」。后来 {@code startProduction} 长出了
 * 带 {@code operatorId} 的三参重载，两参那个变成一行委派：
 *
 * <pre>public ProductionPlanDTO startProduction(String factoryId, String planId) {
 *     return startProduction(factoryId, planId, null);
 * }</pre>
 *
 * <p>{@code indexOf} 取到的是<b>第一个</b>匹配（委派那个），到下一个 {@code @Override}
 * 恰好在三参重载之前收尾 —— 于是闸读到的方法体里当然没有 {@code materializeBatchForPlan}，
 * 从 2026-08-24 起在 main 上天天红。而<b>产品是对的</b>：真正干活的三参重载
 * ({@code ProductionPlanServiceImpl} 里 {@code ProductionBatch createdBatch =
 * materializeBatchForPlan(factoryId, plan);}) 一直在建批次。
 *
 * <p>所以这是「判据错了」不是「产品坏了」。修法不是放宽断言，是把守的东西从
 * <b>字面</b>（「第一个同名方法的方法体里有那串字」）抬到<b>性质</b>：
 *
 * <blockquote><b>每一个 {@code startProduction} 重载，要么自己调
 * {@code materializeBatchForPlan}，要么委派给另一个 {@code startProduction} 重载；
 * 且至少有一个是真的建批次的。</b></blockquote>
 *
 * <p>后半句不能省：只有前半句时，「把所有重载都写成互相委派」能让闸恒绿。
 * 三条阴性对照({@link #gateGoesRedWhenAnEntryPointSkipsMaterialization()} /
 * {@link #gateGoesRedWhenEveryOverloadOnlyDelegates()} /
 * {@link #extractionSeesEveryOverloadNotJustTheFirst()})把这两半都钉住了。
 *
 * <p>⚠️ 本文件本身可被验伪：把三参 {@code startProduction} 里那行
 * {@code materializeBatchForPlan(...)} 删掉，{@link #startProductionMustCreateBatch()} 立刻变红。
 */
@DisplayName("两个「开工」入口必须收敛 —— 开工不建批次 = 计划当场死锁")
class ProductionStartEntryPointsConvergeContractTest {

    private static final Path SERVICE = Paths.get(
            "src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java");
    private static final Path PLAN_LIST_VUE = Paths.get(
            "../../../web-admin/src/views/production/plans/list.vue");

    private static final String START_SIGNATURE = "public ProductionPlanDTO startProduction(";
    private static final String CREATE_BATCH_SIGNATURE = "public ProductionBatch createBatchFromPlan(";
    private static final String MATERIALIZE_SIGNATURE = "private ProductionBatch materializeBatchForPlan(";
    private static final String MATERIALIZE_CALL = "materializeBatchForPlan(factoryId, plan)";

    private String source() throws IOException {
        return Files.readString(SERVICE, StandardCharsets.UTF_8);
    }

    // ──────────────────────────── 取体: 花括号配平, 不靠「下一个 @Override」 ────────────────────────────

    /**
     * 取出 {@code src} 里<b>每一个</b>以 {@code signature} 开头的方法体（含签名行）。
     *
     * <p>用花括号计数配平, 而不是「到下一个 {@code @Override} 为止」—— 后者在方法后面跟的是
     * 普通 javadoc（而不是另一个 {@code @Override}）时会一路吃到文件尾, 在<b>存在重载</b>时
     * 又会把第一个重载的体截在下一个重载之前。本文件红了一整天就是后一种。
     *
     * <p>⚠️ 计数不解析字符串/注释。本类里的日志占位符 {@code "{}"} 是<b>成对</b>的, 所以配得平；
     * {@link #extractionIsNotSilentlyMisaligned()} 用锚点把「取到的确实是那段」钉住 ——
     * 一旦有人写进一个落单的花括号字面量, 那条阳性对照会先红。
     */
    private static List<String> methodBodies(String src, String signature) {
        List<String> bodies = new ArrayList<>();
        int from = 0;
        while (true) {
            int begin = src.indexOf(signature, from);
            if (begin < 0) {
                return bodies;
            }
            int open = src.indexOf('{', begin);
            if (open < 0) {
                return bodies;
            }
            int depth = 0;
            int end = -1;
            for (int i = open; i < src.length(); i++) {
                char c = src.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            if (end < 0) {
                bodies.add(src.substring(begin));
                return bodies;
            }
            bodies.add(src.substring(begin, end + 1));
            from = end + 1;
        }
    }

    private static String signatureLineOf(String body) {
        int brace = body.indexOf('{');
        return (brace < 0 ? body : body.substring(0, brace)).trim();
    }

    /**
     * 委派给另一个 {@code startProduction} 重载 —— 建批次的责任转移给了被调的那个。
     *
     * <p>⚠️ <b>这是代理判据, 标在这里</b>: 静态扫描判不出「这条 return 是不是唯一出口」。
     * 一个既有 {@code return startProduction(...)} 早退分支、主干又不建批次的重载会被本闸放过。
     * ⛔ 不要靠加启发式去逼近 —— 真要判准只有一条路: 让委派壳只有一行。
     * 兜底的是 {@link #entryPointsThatMaterialize}: 至少要有一个重载真的建批次,
     * 「全员互相委派」那种假收敛跑不掉。
     */
    private static boolean delegatesToAnotherOverload(String body) {
        return body.contains("return startProduction(");
    }

    /**
     * 返回「既不自己建批次、又不委派给别的重载」的入口签名。空 = 全部收敛。
     *
     * <p>刻意接 {@code String src} 而不是直接读文件, 这样阴性对照能拿合成源码喂它,
     * 证明这道闸<b>真的红得起来</b>。
     */
    private static List<String> entryPointsNotConverging(String src) {
        List<String> offenders = new ArrayList<>();
        for (String body : methodBodies(src, START_SIGNATURE)) {
            if (body.contains(MATERIALIZE_CALL) || delegatesToAnotherOverload(body)) {
                continue;
            }
            offenders.add(signatureLineOf(body));
        }
        return offenders;
    }

    /** 真的自己调了物化实现的入口数 —— 全部互相委派时它是 0, 那种「收敛」是假的。 */
    private static long entryPointsThatMaterialize(String src) {
        return methodBodies(src, START_SIGNATURE).stream()
                .filter(body -> body.contains(MATERIALIZE_CALL))
                .count();
    }

    // ──────────────────────────── 阳性对照 ────────────────────────────

    @Test
    @DisplayName("阳性对照: 源码读得到且两个方法都在")
    void positiveControl() throws IOException {
        String src = source();
        assertThat(src).contains(START_SIGNATURE);
        assertThat(src).contains(CREATE_BATCH_SIGNATURE);
    }

    @Test
    @DisplayName("阳性对照: 取体没有错位 —— 否则下面所有断言量的都不是那段代码")
    void extractionIsNotSilentlyMisaligned() throws IOException {
        List<String> bodies = methodBodies(source(), START_SIGNATURE);
        assertThat(bodies).as("一个 startProduction 都没取到, 本文件的断言全部无效").isNotEmpty();
        for (String body : bodies) {
            assertThat(body.trim())
                    .as("方法体没有以 } 收尾 —— 花括号配平被字符串里的落单花括号带偏了: "
                            + signatureLineOf(body))
                    .endsWith("}");
            assertThat(body)
                    .as("取体越界, 吃进了后面那个方法的 javadoc: " + signatureLineOf(body))
                    .doesNotContain("SSOP 阻产状态集");
        }
        assertThat(bodies.stream().filter(b -> b.contains("findByIdForUpdate")).count())
                .as("没有任何一个 startProduction 体里含 findByIdForUpdate —— "
                        + "干活的那个重载没被取到, 说明取体又只看见了委派壳")
                .isGreaterThanOrEqualTo(1);
    }

    // ──────────────────────────── 阴性对照: 证明这道闸红得起来 ────────────────────────────

    private static final String SYNTHETIC_HEAD =
            "class X {\n"
                    + "    @Override\n"
                    + "    public ProductionPlanDTO startProduction(String factoryId, String planId) {\n"
                    + "        return startProduction(factoryId, planId, null);\n"
                    + "    }\n"
                    + "    @Override\n"
                    + "    public ProductionPlanDTO startProduction("
                    + "String factoryId, String planId, Long operatorId) {\n";

    private static final String SYNTHETIC_TAIL = "        return toDTO(plan);\n    }\n}\n";

    @Test
    @DisplayName("阴性对照: 干活那个重载不建批次时, 闸必须报出它 (这正是 2026-08-01 的 bug)")
    void gateGoesRedWhenAnEntryPointSkipsMaterialization() {
        String broken = SYNTHETIC_HEAD
                + "        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);\n"
                + SYNTHETIC_TAIL;
        assertThat(entryPointsNotConverging(broken))
                .as("只翻状态不建批次的重载没被报出来 —— 这道闸在守空气")
                .hasSize(1);
        assertThat(entryPointsNotConverging(broken).get(0)).contains("Long operatorId");

        String fixed = SYNTHETIC_HEAD
                + "        ProductionBatch b = materializeBatchForPlan(factoryId, plan);\n"
                + SYNTHETIC_TAIL;
        assertThat(entryPointsNotConverging(fixed))
                .as("修好的形状被误报了 —— 闸太紧会被人关掉")
                .isEmpty();
    }

    @Test
    @DisplayName("阴性对照: 全部重载互相委派 = 谁都没建批次, 不许算收敛")
    void gateGoesRedWhenEveryOverloadOnlyDelegates() {
        String allDelegates =
                "class X {\n"
                        + "    public ProductionPlanDTO startProduction(String factoryId, String planId) {\n"
                        + "        return startProduction(factoryId, planId, null);\n"
                        + "    }\n"
                        + "    public ProductionPlanDTO startProduction("
                        + "String factoryId, String planId, Long operatorId) {\n"
                        + "        return startProduction(factoryId, planId);\n"
                        + "    }\n"
                        + "}\n";
        assertThat(entryPointsNotConverging(allDelegates))
                .as("逐个看每个重载都「委派了」, 所以前半句判据满足")
                .isEmpty();
        assertThat(entryPointsThatMaterialize(allDelegates))
                .as("而一个都没真建批次 —— 后半句判据必须把它拦下")
                .isZero();
    }

    @Test
    @DisplayName("阴性对照: 取体必须看见每一个重载, 不是只看见第一个 (旧版就栽在这)")
    void extractionSeesEveryOverloadNotJustTheFirst() {
        String twoOverloads = SYNTHETIC_HEAD
                + "        ProductionBatch b = materializeBatchForPlan(factoryId, plan);\n"
                + SYNTHETIC_TAIL;
        List<String> bodies = methodBodies(twoOverloads, START_SIGNATURE);
        assertThat(bodies).as("两个重载只取到一个 —— 旧版 indexOf + 下一个 @Override 的原病").hasSize(2);
        assertThat(bodies.get(0)).contains("return startProduction(factoryId, planId, null)");
        assertThat(bodies.get(1)).contains(MATERIALIZE_CALL);
    }

    // ──────────────────────────── 闸本体 ────────────────────────────

    @Test
    @DisplayName("🔴 每个 startProduction 入口都必须建批次 —— 只翻状态就是把计划做死")
    void startProductionMustCreateBatch() throws IOException {
        String src = source();

        assertThat(entryPointsNotConverging(src))
                .as("开工只置 IN_PROGRESS 不建批次 → 报工/结单/补建三条路全堵死, "
                        + "而 createBatchFromPlan 也只放行 PENDING, 用户只能作废重建")
                .isEmpty();

        assertThat(entryPointsThatMaterialize(src))
                .as("没有任何一个重载真的调 " + MATERIALIZE_CALL + " —— "
                        + "「每个重载都委派出去」也能让上一条恒绿, 这一条把那个空档堵死")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("🔴 两个入口必须调同一个物化实现 —— 各写一半正是本 bug 的成因")
    void bothEntryPointsShareOneImplementation() throws IOException {
        String src = source();
        int declarations = src.split("private ProductionBatch materializeBatchForPlan\\(", -1).length - 1;
        assertThat(declarations).as("物化实现必须唯一").isEqualTo(1);

        int callSites = src.split("materializeBatchForPlan\\(factoryId, plan\\)", -1).length - 1;
        assertThat(callSites)
                .as("两个「开工」入口(startProduction / createBatchFromPlan)各调一次")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("⛔ 不许经 createBatchFromPlan 绕一圈 —— 那会在同事务里二次取悲观锁")
    void startProductionMustNotReenterCreateBatchFromPlan() throws IOException {
        for (String body : methodBodies(source(), START_SIGNATURE)) {
            assertThat(body)
                    .as("调 createBatchFromPlan 会第二次 findByIdForUpdate(同事务重入, 无害但多一次"
                            + "SELECT FOR UPDATE 往返), 且破坏 R6 用例「取锁至多一次」的断言。命中: "
                            + signatureLineOf(body))
                    .doesNotContain("createBatchFromPlan(");
        }
    }

    @Test
    @DisplayName("⛔ 不许绕过 createBatchFromPlan 自己 new 一个批次 —— 那会漏掉 spawn 报工任务")
    void startProductionMustNotHandRollItsOwnBatch() throws IOException {
        for (String body : methodBodies(source(), START_SIGNATURE)) {
            assertThat(body)
                    .as("自己拼批次会漏掉 spawnTasks / workflow pin 校验 / 产线与主管映射, "
                            + "又变成第二套实现 —— 这正是本 bug 的成因。命中: " + signatureLineOf(body))
                    .doesNotContain("ProductionBatch.builder()");
        }
    }

    @Test
    @DisplayName("WIP 扣减刻意留在 startProduction —— 下沉会让逐工序录入二次扣减")
    void wipDeductionStaysOutOfCreateBatch() throws IOException {
        String src = source();
        String deduct = "deductForSecondaryPlan(";

        assertThat(methodBodies(src, START_SIGNATURE).stream().filter(b -> b.contains(deduct)).count())
                .as("找不到 WIP 扣减调用 —— 它必须在某个 startProduction 重载里")
                .isGreaterThanOrEqualTo(1);

        for (String signature : List.of(CREATE_BATCH_SIGNATURE, MATERIALIZE_SIGNATURE)) {
            for (String body : methodBodies(src, signature)) {
                assertThat(body)
                        .as("扣减不能挪进 " + signature + " —— 逐工序录入(ClerkProcessEntryServiceImpl)"
                                + "已按 edges 精确扣过一次, 两处都扣就是幽灵超扣")
                        .doesNotContain(deduct);
            }
        }
    }

    @Test
    @DisplayName("R6 并发守卫仍在: createBatchFromPlan 不许放行「IN_PROGRESS 且无批次」去补建")
    void createBatchStillRefusesStartedPlanWithoutBatch() throws IOException {
        assertThat(source())
                .as("放宽这道门会让并发第二个请求在第一笔事务提交前建出第二个批次(R6)。"
                        + "本轮是从 startProduction 那头收敛, 不是把这道门拆掉")
                .contains("该计划已开工但没有生产批次");
    }

    /**
     * 前端那半 —— 它是这个 bug 的另一个承载点。
     *
     * <p>{@code openProcessEntry} 用 {@code status === 'PENDING'} 当作「还没有批次」的判据。
     * 后端收敛之后这个假设重新成立（开工必然带批次），但断言把它钉住：
     * 谁要是再让某条路径产生「IN_PROGRESS 且无批次」，这条注释就是现场说明。
     */
    @Test
    @DisplayName("前端那半: 逐道录入的自动补建仍以 PENDING 为判据 —— 后端必须保证它成立")
    void frontendStillAssumesPendingMeansNoBatch() throws IOException {
        if (!Files.exists(PLAN_LIST_VUE)) {
            return; // 只跑后端模块时（无 web-admin 工作副本）跳过, 不制造假红
        }
        String vue = Files.readString(PLAN_LIST_VUE, StandardCharsets.UTF_8);
        assertThat(vue).as("阳性对照: 读到的是计划列表页").contains("openProcessEntry");
        assertThat(vue)
                .as("前端仍靠 PENDING 判断要不要补建批次 —— 这就是后端必须保证"
                        + "「非 PENDING ⇒ 一定有批次」的原因")
                .contains("=== 'PENDING'");
    }
}
