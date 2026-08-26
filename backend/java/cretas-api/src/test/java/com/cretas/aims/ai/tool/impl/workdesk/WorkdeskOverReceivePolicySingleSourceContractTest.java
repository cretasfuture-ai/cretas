package com.cretas.aims.ai.tool.impl.workdesk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— 仓管员 Workdesk 的采购超收比例只许在 {@link WorkdeskOverReceivePolicy} 定义一次。
 *
 * <h2>为什么有这道闸 (2026-08-25 收敛前的实测长相)</h2>
 *
 * <p>同一个 {@code new BigDecimal("0.30")} 在三个 Tool 里各写了一遍
 * ({@code MaterialTodayReceivingQueryTool} / {@code ReceiveConfirmCreateTool} /
 * {@code ReceiveWithLimitTool})，其中一处的注释还写着
 * "mirror ReceiveWithLimitTool.OVER_RECEIVE_PCT" —— 也就是说写的人当时就知道这是副本，
 * 但语言层面没有任何东西阻止它们各走各的。除此之外，三个类的 javadoc、tool description、
 * 参数说明、异常文案里还各自复述了一遍字面的 "30%"，前端 Vue 又复述三遍。
 *
 * <p>这种漂移的症状不是报错，是<b>跑的是旧的那份</b>：改一处，剩下的照旧对仓管员说旧数。
 *
 * <h2>这道闸守什么</h2>
 *
 * <ol>
 *   <li>三个 Tool 的源码里不再有自己的 {@code OVER_RECEIVE_PCT} 声明，也不再有
 *       字面的百分比（剥注释后扫 —— ⛔ 不剥的话会命中解释"为什么不许写"的注释本身）；</li>
 *   <li>{@link WorkdeskOverReceivePolicy#pctLabel()} 的输出确实由常量算出来，
 *       不是另一个写死的字符串。</li>
 * </ol>
 *
 * <h2>⚠️ 这道闸【不】守什么</h2>
 *
 * <p>它不判断 0.30 这个值对不对。实际执行入库的
 * {@code PurchaseServiceImpl.overReceiveRate} 是
 * {@code @Value("${cretas.purchase.over-receive-rate:0.00}")}，而该属性在
 * {@code src/main/resources} 下从未设置过 —— 生产上真正允许的超收是 <b>0%</b>，
 * 与本 policy 展示的 30% 不一致。对齐是业务裁定，不在本闸范围内。
 */
class WorkdeskOverReceivePolicySingleSourceContractTest {

    private static final Path TOOL_DIR =
            Path.of("src/main/java/com/cretas/aims/ai/tool/impl/workdesk");

    private static final List<String> TOOLS = List.of(
            "MaterialTodayReceivingQueryTool.java",
            "ReceiveConfirmCreateTool.java",
            "ReceiveWithLimitTool.java");

    /** 剥块注释与行注释 —— 注释里提到某个数字不等于它会被算进去或发给用户。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "")
                  .replaceAll("(?m)(^|[^:])//.*$", "$1");
    }

    @Test
    @DisplayName("阳性对照: 剥注释确实在剥, 且确实留下了非注释内容")
    void stripCommentsActuallyStrips() {
        // ⛔ 没有这条, 后面"扫不到字面量"可能只是因为剥注释把整个文件吃光了 ——
        //    那样断言恒真, 闸就成了摆设。
        String sample = String.join("\n",
                "int keep = 1;",
                "// 行注释里有 30% 和 0.30",
                "/* 块注释里有 new BigDecimal(\"0.30\") */",
                "int alsoKeep = 2;");
        String stripped = stripComments(sample);
        assertTrue(stripped.contains("int keep = 1;"), "剥过头了: 非注释内容被删");
        assertTrue(stripped.contains("int alsoKeep = 2;"), "剥过头了: 非注释内容被删");
        assertTrue(!stripped.contains("30%"), "没剥掉行注释");
        assertTrue(!stripped.contains("BigDecimal"), "没剥掉块注释");
    }

    @Test
    @DisplayName("三个 Tool 都不再自己声明超收比例, 也不再写死百分比")
    void toolsCarryNoOwnCopyOfThePercentage() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String tool : TOOLS) {
            Path file = TOOL_DIR.resolve(tool);
            assertTrue(Files.exists(file), "扫描目标不存在, 读数无意义: " + file.toAbsolutePath());
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            // 阳性对照: 文件真的读到了 (不是空串让下面的检查恒真)
            assertTrue(code.length() > 500, "文件内容异常短, 读数无意义: " + tool);

            for (String line : code.split("\n")) {
                if (line.contains("OVER_RECEIVE_PCT")
                        && line.contains("=")
                        && line.contains("BigDecimal")
                        && !line.contains("WorkdeskOverReceivePolicy")) {
                    offenders.add(tool + " 自己声明了超收比例: " + line.trim());
                }
                if (line.matches(".*(\\b0\\.30\\b|30\\s*%%?|\\b1\\.3\\b).*")) {
                    offenders.add(tool + " 写死了百分比: " + line.trim());
                }
            }
        }
        assertNull(offenders.isEmpty() ? null : String.join("\n  ", offenders),
                "超收比例又长出副本了");
    }

    @Test
    @DisplayName("pctLabel() 由常量算出来, 不是另一个写死的字符串")
    void pctLabelIsDerivedFromTheConstant() {
        // 守的是"派生"这个性质: 标签必须等于 常量×100 的呈现, 而不是碰巧长得像。
        String expected = WorkdeskOverReceivePolicy.OVER_RECEIVE_PCT
                .multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros()
                .toPlainString() + "%";
        assertEquals(expected, WorkdeskOverReceivePolicy.pctLabel());
        // 且它确实带百分号 —— 拼进文案时不能是个裸数字
        assertTrue(WorkdeskOverReceivePolicy.pctLabel().endsWith("%"));
    }
}
