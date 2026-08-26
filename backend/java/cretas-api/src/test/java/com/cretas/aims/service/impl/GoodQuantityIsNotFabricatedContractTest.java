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
}
