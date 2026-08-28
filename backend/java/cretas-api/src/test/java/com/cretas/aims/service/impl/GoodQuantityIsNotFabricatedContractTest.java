package com.cretas.aims.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「没人填不良品」⛔ 不许被写成「零不良」。
 *
 * <h2>它守什么</h2>
 *
 * <p>批次完工时，如果没人录过不良品，{@code goodQuantity} 就该保持 {@code null}
 * ——「不知道」。此前有三处代码把它<b>编</b>成了一个数：</p>
 *
 * <ol>
 *   <li>{@code WorkReportingServiceImpl} 自动完成分支：{@code good = actual}</li>
 *   <li>{@code WorkReportingServiceImpl.manualCompleteBatch}：{@code good = actual}，
 *       连 actual 都没有时兜底成 {@code ZERO}（等于断言「一件良品都没有」）</li>
 *   <li>{@code LaborEfficiencyServiceImpl} <b>读</b>侧：{@code good != null ? good : actual}
 *       —— 这是同一个谎的<b>第二个家</b>，只修写侧等于换个地方继续编</li>
 * </ol>
 *
 * <h2>🔴 生产实测（2026-08-26，{@code cretas_prod_db}，只读）</h2>
 *
 * <pre>
 *   61 个批次 / 1 个工厂
 *     53 个 IN_PROGRESS → good_quantity 全 NULL
 *      8 个 COMPLETED   → good_quantity 全 = actual_quantity   (100%)
 *   真正记录过不良品(good &lt; actual)的：**0 条**
 *   时间窗 2026-08-17 → 2026-08-25，仍在产生
 * </pre>
 *
 * <p>不是「良品率恰好 100%」，是那几行代码替所有人填了 100%。而一旦落库，
 * {@code NULL} 与「真的零不良」就<b>再也分不开</b>了 —— 这也是车间大屏刻意不放
 * 不良品率的原因：那个数没有意义。</p>
 *
 * <h2>判据的代理性（必须写明）</h2>
 *
 * <p>真正想守的是「运行时不会把 actual 当成 good」。静态扫描判不出运行时行为，
 * 所以用<b>源码级代理判据</b>：这两个文件里不许再出现
 * {@code setGoodQuantity(...getActualQuantity...)} 这种形状，
 * 以及读侧的 {@code getGoodQuantity() != null ? ... : ...getActualQuantity()}。</p>
 *
 * <p>⚠️ 已知盲区：换个写法（先赋给局部变量再 set）本闸看不出来。不打算靠加启发式
 * 去逼近 —— 它的作用是<b>让「把兜底加回来」这个动作必须显式地绕过一道写着理由的闸</b>，
 * 而不是物理上不可能。</p>
 */
@DisplayName("不良品数不许被编造（good ≠ actual 兜底）")
class GoodQuantityIsNotFabricatedContractTest {

    private static final Path MAIN = Paths.get("src/main/java/com/cretas/aims");

    private static String read(String rel) throws IOException {
        return Files.readString(MAIN.resolve(rel), StandardCharsets.UTF_8);
    }

    /** 去掉行注释与块注释，免得闸把自己的说明文字当成被测对象。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "")
                  .replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("阳性对照：这两个文件读得到且确实提到 goodQuantity（仪器活着）")
    void scannerActuallyReadsTheFiles() throws IOException {
        String wr = read("service/impl/WorkReportingServiceImpl.java");
        String le = read("service/impl/LaborEfficiencyServiceImpl.java");

        // 若哪天文件被重命名/拆分, 下面两条断言会红, 提醒去改本闸 ——
        // 否则 stripComments 后扫到空字符串, 本闸会静默恒绿。
        assertThat(wr).as("WorkReportingServiceImpl 读不到或已改名").contains("goodQuantity");
        assertThat(le).as("LaborEfficiencyServiceImpl 读不到或已改名").contains("GoodQuantity");
    }

    @Test
    @DisplayName("写侧：完工时不许把 actualQuantity 塞进 goodQuantity")
    void completionMustNotFabricateGoodQuantity() throws IOException {
        String src = stripComments(read("service/impl/WorkReportingServiceImpl.java"));

        assertThat(src)
                .as("完工分支又把 good 填成 actual 了 —— 那是把「没人填不良品」写成「零不良」，"
                        + "落库后与「真的零不良」再也分不开。不知道就留 null。")
                .doesNotContain("setGoodQuantity(batch.getActualQuantity())");

        // manualCompleteBatch 那处更糟: 连 actual 都没有时兜底成 ZERO
        assertThat(src)
                .as("手动完成又兜底成 ZERO 了 —— 那等于断言「一件良品都没有」")
                .doesNotContain("setGoodQuantity(batch.getActualQuantity() != null");
    }

    /**
     * 读侧。⚠️ 这条单独存在的理由：写侧修好之后，如果读侧还顶替，
     * 那个谎只是<b>换了个地方</b>继续编，而且更难发现（数据库里是 null，
     * 报表上却有数）。
     */
    @Test
    @DisplayName("读侧：人效统计不许用 actualQuantity 顶替缺失的 goodQuantity")
    void laborEfficiencyMustNotSubstituteActualForGood() throws IOException {
        String src = stripComments(read("service/impl/LaborEfficiencyServiceImpl.java"));

        assertThat(src)
                .as("读侧又拿 actual 顶替 good 了 —— 写侧不编了, 这里编, 等于换个地方继续编")
                .doesNotContain("getGoodQuantity() != null ? b.getGoodQuantity() : b.getActualQuantity()");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 扫描面扩到 ProductionReport (2026-08-28 整分支终审 I-1)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * 🔴 <b>这道闸原来只看 ProductionBatch。</b>
     *
     * <p>2026-08-28「不良品→回料链路」分支在<b>另一个实体</b>
     * ({@code ProductionReport}) 上把同一个动作又做了一遍 ——
     * {@code YieldReportServiceImpl} 里无条件 {@code .goodQuantity(effOutput)}。
     * 本闸<b>一声没吭</b>, 因为它的扫描面只有 {@code WorkReportingServiceImpl} +
     * {@code LaborEfficiencyServiceImpl} 两个文件、只针对 {@code ProductionBatch}。
     *
     * <p>F006 现在 43 张报工单一张都没填过不良品 ⇒ 那一行会让每张 OUTPUT 报工都带
     * {@code good_quantity = output_quantity}, 汇进人效导出的「合格数量」列后,
     * 每个工人每天「合格数量 = 产出数量、不良率 0.00%」, 而没有任何一个人清点过。
     *
     * <h2>判据 (仍是代理判据, 但比原来那两条窄且更难绕过)</h2>
     *
     * <p>{@code .goodQuantity(} 的每一处, <b>同一行里</b>必须出现
     * {@code getDefectQuantity} —— 也就是「良品数由『有没有人清点过不良品』把关」。
     * ⛔ 已知盲区: 把三元表达式拆成多行、或先算进局部变量再传, 本闸看不出来。
     * 不打算靠加启发式去逼近 (宁可窄而可信) —— 它的作用是让「把兜底加回来」这个动作
     * 必须显式地绕过一道写着理由的闸。
     */
    @Test
    @DisplayName("报工侧: goodQuantity 每一处都必须由 defectQuantity 把关 (没清点就是 null)")
    void yieldReportMustNotFabricateGoodQuantity() throws IOException {
        String src = stripComments(read("service/yield/impl/YieldReportServiceImpl.java"));

        java.util.List<String> unguarded = new java.util.ArrayList<>();
        for (String line : src.split("\\r?\\n")) {
            if (line.contains(".goodQuantity(") && !line.contains("getDefectQuantity")) {
                unguarded.add(line.trim());
            }
        }

        assertThat(unguarded)
                .as("报工的良品数又被无条件填成产出量了 —— 那是把「没人清点不良品」写成「这批全是良品」。"
                        + "没清点就留 null。(2026-08-28 终审 I-1: 这道闸此前只看 ProductionBatch, 漏了 ProductionReport)")
                .isEmpty();
    }

    /**
     * 阳性对照: 报工侧那个文件读得到、且确实<b>有</b> goodQuantity 赋值点。
     *
     * <p>没有这条, 文件被改名/字段被删之后 {@link #yieldReportMustNotFabricateGoodQuantity()}
     * 会因为「一处都没扫到」而静默恒绿 —— 那正是本仓反复踩过的「闸还在跑, 但它什么都没量」。
     */
    @Test
    @DisplayName("阳性对照: 报工侧确实有 goodQuantity 赋值点 (仪器活着)")
    void yieldReportScannerActuallyFindsGoodQuantityAssignments() throws IOException {
        String src = stripComments(read("service/yield/impl/YieldReportServiceImpl.java"));
        int occurrences = countOccurrences(src, ".goodQuantity(");
        assertThat(occurrences)
                .as("YieldReportServiceImpl 里一处 .goodQuantity( 都扫不到 —— 文件改名/字段搬家了? "
                        + "去改本闸, 否则它会静默恒绿")
                .isGreaterThan(0);
    }

    /** 数一个字面子串出现几次。⛔ 不用 split(regex) —— 那要在 Java 字符串里再转义一层, 容易被吃掉。 */
    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
