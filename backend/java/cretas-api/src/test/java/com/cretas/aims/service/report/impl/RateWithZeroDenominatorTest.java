package com.cretas.aims.service.report.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分母为 0 的比率必须是 <b>null</b>，⛔ 不是 100，也不是 0。
 *
 * <h3>实测（2026-08-29，线上 F006）</h3>
 *
 * <pre>
 *   GET /reports/on-time-delivery?startDate=2026-08-01&amp;endDate=2026-08-29
 *   → totalOrders = 0   而   onTimeRate = 100.0
 * </pre>
 *
 * 一张订单都没有的期间被报成「准时交付率 <b>100%</b>」。同一个方法里
 * {@code inFullRate} / {@code otifRate} 以及趋势图里**每一个没有发货的日子**
 * 都是同样的 100 —— 图表上是一排满分柱子。
 *
 * <p>同族的还有 {@code conversionRate = 100.0}（零订单 ⇒ 转化率 100%）。
 *
 * <h3>⚠️ 我的上一版扫描漏掉了它，而漏掉的方式值得单独记</h3>
 *
 * 那个脚本把 {@code '0' / '0.0' / '1' / '100' / '100.0'} 当噪音**过滤掉了**，
 * 而这次的缺陷值<b>正好是 100</b>。
 * ▎仪器把缺陷本身滤掉了 —— 「我看到的是全部吗」要连过滤条件一起问。
 *
 * <h3>为什么 0 也不行</h3>
 *
 * 0% 在比率语境里是**合法读数**（0% 准时 = 全部延误）。兜 0 会让下游
 * 分不清「全部延误」和「我不知道」—— 与本仓 {@code COALESCE(food_cost, 0)}
 * 把「没有成本卡」算成「零成本」是同一个形状。
 */
@DisplayName("比率：没有分母就说没有")
class RateWithZeroDenominatorTest {

    /**
     * 剥掉块注释和行注释。⚠️ 不处理字符串字面量里的 {@code //}，
     * 对这道闸够用（它扫的是代码形状，不是文案）。
     */
    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder out = new StringBuilder(noBlock.length());
        for (String line : noBlock.split("\n", -1)) {
            int i = line.indexOf("//");
            out.append(i >= 0 ? line.substring(0, i) : line).append('\n');
        }
        return out.toString();
    }

    private static BigDecimal rate(long numerator, long denominator) throws Exception {
        Method m = ProductionReportServiceImpl.class
                .getDeclaredMethod("rateOrNull", long.class, long.class);
        m.setAccessible(true);
        return (BigDecimal) m.invoke(null, numerator, denominator);
    }

    @Test
    @DisplayName("分母 0 ⇒ null（⛔ 不是 100，也不是 0）")
    void zeroDenominatorIsNull() throws Exception {
        assertThat(rate(0, 0)).isNull();
        assertThat(rate(5, 0)).isNull();
    }

    @Test
    @DisplayName("阴性对照：有分母时照常算（⛔ 防「一律返回 null」也能过）")
    void withDenominatorStillComputes() throws Exception {
        assertThat(rate(7, 10)).isEqualByComparingTo("70.00");
        assertThat(rate(1, 3)).isEqualByComparingTo("33.33");
    }

    @Test
    @DisplayName("阴性对照：真的全部准时 ⇒ 就是 100，不许因为「怕像编的」而改口")
    void allOnTimeIsStillHundred() throws Exception {
        assertThat(rate(10, 10)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("阴性对照：真的一个都没准时 ⇒ 就是 0，与「没有分母」必须分得开")
    void noneOnTimeIsZeroNotNull() throws Exception {
        assertThat(rate(0, 10)).isEqualByComparingTo("0.00");
    }

    /**
     * 源码闸：这一族的字面量不许回来。
     *
     * <p>⚠️ 它用的是文本判据，看不见 f-string / 拼接 —— 这一点写在这里，
     * ⛔ 不要把它当成「这一类已经不可能再出现」。它是兜底，不是免检。
     */
    @Test
    @DisplayName("源码闸：分母为 0 时兜 100 的老写法不许回来")
    void theHundredFallbackIsGone() throws IOException {
        Path f = Path.of("src", "main", "java", "com", "cretas", "aims", "service",
                "report", "impl", "ProductionReportServiceImpl.java");
        assertThat(Files.isRegularFile(f))
                .as("源码找不到 —— 仪器坏了，这条断言的读数作废")
                .isTrue();
        // 🔴 必须先剥注释再扫。第一版没剥，闸把**我自己注释里引用的旧写法**
        //    （`原来是 double conversionRate = 100.0;`）当成了被测对象，当场误报。
        //    本仓记过同形：源码闸一晚四次把自己写的注释当成被测对象。
        String src = stripComments(Files.readString(f, StandardCharsets.UTF_8));

        // 阳性对照：确认确实读到了那个文件（读到空串同样会让下面几条恒真）
        assertThat(src).contains("getOnTimeDeliveryReport");
        // 阳性对照之二：剥注释这一步没有把整份文件剥空
        assertThat(src).contains("rateOrNull");

        // ⚠️ 第一版写的是 `doesNotContain(": new BigDecimal(\"100\")")`，当场**误报**：
        //    同文件里 `lossOrNull` 的 `100 - rate` 是合法用法，被一起数了进去。
        //    ⇒ 收紧成「三元兜底」这个精确形状（以 `;` 或 `)` 收尾），
        //    ⛔ 而不是把 100 这个字面量整个禁掉。
        assertThat(src)
                .as("`x > 0 ? … : new BigDecimal(\"100\");` 这个兜底写法回来了")
                .doesNotContain(": new BigDecimal(\"100\");");
        assertThat(src)
                .as("`x > 0 ? … : new BigDecimal(\"100\"))` 这个兜底写法回来了")
                .doesNotContain(": new BigDecimal(\"100\"))");
        assertThat(src)
                .as("`double conversionRate = 100.0` 回来了")
                .doesNotContain("conversionRate = 100.0;");
    }
}
